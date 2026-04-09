package it.belloworld.mercurygram.push;

import android.os.SystemClock;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.PushListenerController;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UnifiedPushReceiver;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.unifiedpush.android.connector.UnifiedPush;

import java.util.List;

/**
 * UnifiedPush-backed push provider plus the Simple Push (token_type=4)
 * registration helpers. Selected by ApplicationLoaderImpl.onCreatePushProvider,
 * replacing the no-op Google provider of the FOSS build.
 */
public final class UnifiedPushListenerServiceProvider implements PushListenerController.IPushListenerServiceProvider {
    public static final UnifiedPushListenerServiceProvider INSTANCE = new UnifiedPushListenerServiceProvider();

    // Registration retries back off instead of running at a fixed pace: a distributor that
    // refuses (no network, its own server down, a logged-out account) usually keeps refusing for
    // a while, and every attempt wakes it up. 10 s doubled up to 15 minutes, reset as soon as an
    // endpoint arrives.
    private static final long RETRY_MIN_MS = 10_000L;
    private static final long RETRY_MAX_MS = 15 * 60_000L;
    // How long a distributor gets to answer a register() before it counts as silent. Without
    // this a distributor that never answers leaves the settings screen on "(waiting for
    // endpoint)" forever and writes nothing to the event log, so a bug report about it carries
    // no trace at all.
    private static final long ACK_TIMEOUT_MS = 30_000L;
    private static long retryDelayMs = RETRY_MIN_MS;
    private static long lastEnsureMs = -RETRY_MIN_MS;

    private static Runnable stateListener;

    private UnifiedPushListenerServiceProvider() {}

    /**
     * Follows the registration state while the UnifiedPush settings screen is open, which is
     * what turns "(waiting for endpoint)" into the distributor name once it answers. Only that
     * screen ever listens, so a single slot is enough; it is cleared when the screen goes away.
     */
    public static void setStateListener(Runnable listener) {
        stateListener = listener;
    }

    /** Called from every path that changes what the settings screen shows. */
    public static void notifyStateChanged() {
        Runnable listener = stateListener;
        if (listener != null) {
            AndroidUtilities.runOnUIThread(listener);
        }
    }

    @Override
    public boolean hasServices() {
        if (SharedConfig.disableUnifiedPush) {
            // Upstream's initPushServices() then takes its no-push branch, which is what tells
            // the server there is no token for this device.
            return false;
        }
        // The embedded FCM distributor is our own package and is never auto-selected, so it only
        // counts once the user picked it explicitly. Counting it unconditionally would make a
        // device with no distributor app installed report push support: onRequestPushToken()
        // would save nothing, UnifiedPush.register() would return immediately, and
        // ApplicationLoader would skip the no-push path that tells the server there is no token.
        String ownPackage = ApplicationLoader.applicationContext.getPackageName();
        if (ownPackage.equals(UnifiedPush.getSavedDistributor(ApplicationLoader.applicationContext))) {
            return true;
        }
        // The connector drops its saved distributor on UNREGISTERED and on REGISTRATION_FAILED.
        // With no third-party distributor installed the loop below then finds nothing, this
        // returns false, and ensureRegistered() stops trying: push stays dead until the user
        // picks the built-in distributor again by hand. The remembered choice is what lets the
        // registration come back on its own.
        if (SharedConfig.mgEmbeddedFcmChosen
                && MgEmbeddedFcmDistributor.isAvailable(ApplicationLoader.applicationContext)) {
            return true;
        }
        for (String distributor : UnifiedPush.getDistributors(ApplicationLoader.applicationContext)) {
            if (!ownPackage.equals(distributor)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String getLogTitle() {
        return "UnifiedPush";
    }

    @Override
    public void onRequestPushToken() {
        String currentPushString = SharedConfig.pushString;
        if (!TextUtils.isEmpty(currentPushString)) {
            if (BuildVars.DEBUG_PRIVATE_VERSION && BuildVars.LOGS_ENABLED) {
                FileLog.d("UnifiedPush endpoint = " + currentPushString);
            }
        } else {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("No UnifiedPush string found");
            }
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                SharedConfig.pushStringGetTimeStart = SystemClock.elapsedRealtime();
                SharedConfig.saveConfig();
                if (UnifiedPush.getAckDistributor(ApplicationLoader.applicationContext) == null) {
                    // The embedded FCM distributor is our own package, so it is always in
                    // this list. Picking it silently would route push metadata through
                    // Google without the user ever asking, so it is only ever selected
                    // explicitly in the settings. Once it is selected, leave it alone: its
                    // acknowledgement needs a Play Services round trip, and falling back to
                    // another distributor meanwhile would undo the user's explicit choice.
                    String ownPackage = ApplicationLoader.applicationContext.getPackageName();
                    if (SharedConfig.mgEmbeddedFcmChosen
                            && MgEmbeddedFcmDistributor.isAvailable(ApplicationLoader.applicationContext)) {
                        // Writing back a choice the user already made through the warning dialog,
                        // not an auto-pick: the connector clears the saved distributor whenever it
                        // drops the registration, and nothing else puts it back.
                        UnifiedPush.saveDistributor(ApplicationLoader.applicationContext, ownPackage);
                    } else if (!ownPackage.equals(UnifiedPush.getSavedDistributor(ApplicationLoader.applicationContext))) {
                        List<String> distributors = UnifiedPush.getDistributors(ApplicationLoader.applicationContext);
                        for (String distributor : distributors) {
                            if (!ownPackage.equals(distributor)) {
                                UnifiedPush.saveDistributor(ApplicationLoader.applicationContext, distributor);
                                break;
                            }
                        }
                    }
                }
                UnifiedPushReceiver.log("register -> " + UnifiedPush.getSavedDistributor(ApplicationLoader.applicationContext));
                UnifiedPush.register(
                        ApplicationLoader.applicationContext,
                        "default",
                        "Mercurygram WebPush",
                        null
                );
                Utilities.globalQueue.postRunnable(
                        UnifiedPushListenerServiceProvider::checkAckTimeout, ACK_TIMEOUT_MS);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /**
     * A distributor answers register() with either an endpoint or a failure, and both paths log
     * and reschedule. Silence is the third outcome and the only one nothing used to notice.
     */
    private static void checkAckTimeout() {
        if (SharedConfig.disableUnifiedPush
                || UnifiedPush.getAckDistributor(ApplicationLoader.applicationContext) != null) {
            return;
        }
        UnifiedPushReceiver.log("no answer from "
                + UnifiedPush.getSavedDistributor(ApplicationLoader.applicationContext)
                + " after " + ACK_TIMEOUT_MS / 1000 + "s");
        notifyStateChanged();
        // Throttled like every other retry, so this cannot turn into a register() every 30 s:
        // the backoff passes the first time or two and then holds the attempts off.
        ensureRegistered();
    }

    /**
     * An endpoint arrived, so the next failure starts counting from the short delay again
     * instead of inheriting whatever the previous outage grew the backoff to.
     */
    public static void resetRegistrationBackoff() {
        retryDelayMs = RETRY_MIN_MS;
        lastEnsureMs = -RETRY_MIN_MS;
    }

    /**
     * Re-asserts the registration whenever it is not in a healthy state. The connector drops
     * the saved distributor and the token on every UNREGISTERED (the user deleting the app
     * inside the distributor, a distributor logout, or the connector itself when the saved
     * distributor is momentarily not resolvable) and on every REGISTRATION_FAILED, and nothing
     * else registers again before the next cold start: every NEW_ENDPOINT the distributor
     * sends afterwards carries a token the connector no longer knows and is dropped. Called
     * from MessagesController.getDifference(), the same spot upstream uses to re-assert the
     * push token on reconnect and foreground, so a failed registration is retried as the
     * specification asks (directly after INTERNAL_ERROR, once the network is back after
     * NETWORK) without a listener of its own. Also called from the push watchdog alarm, which
     * is what retries while the app is never opened. Throttled with a growing delay because
     * getDifference() runs per account and on every reconnect.
     */
    public static void ensureRegistered() {
        if (SharedConfig.disableUnifiedPush) {
            return;
        }
        if (UnifiedPush.getAckDistributor(ApplicationLoader.applicationContext) != null
                && !TextUtils.isEmpty(SharedConfig.pushString)) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastEnsureMs < retryDelayMs) {
            return;
        }
        lastEnsureMs = now;
        retryDelayMs = Math.min(retryDelayMs * 2, RETRY_MAX_MS);
        if (INSTANCE.hasServices()) {
            UnifiedPushReceiver.log("retry registration (next in " + retryDelayMs / 1000 + "s)");
            INSTANCE.onRequestPushToken();
        }
    }

    /**
     * Tears down everything the "Disable UnifiedPush" toggle owns: the distributor
     * subscription and both server-side token registrations. Idempotent, so the settings
     * toggle, the ntfy fallback dialog and a repeated call all end in the same state.
     */
    public static void applyDisabled() {
        // Same sentinel ApplicationLoader.initPushServices() writes on its no-push branch, so
        // a live apply and the next cold start agree on the status string.
        SharedConfig.pushStringStatus = "__NO_GOOGLE_PLAY_SERVICES__";
        UnifiedPushReceiver.log("disabled by the user");
        SharedConfig.setMgEmbeddedFcmChosen(false);
        dropCurrentRegistration();
        // unregister() leaves the saved distributor in place, so without this the toggle keeps
        // the app tied to it and the next endpoint announcement finds a distributor it was
        // supposed to have left.
        UnifiedPush.forceRemoveDistributor(ApplicationLoader.applicationContext);
        PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_WEB, null);
        notifyStateChanged();
    }

    /**
     * Moves the subscription to another distributor. The connector's saveDistributor() only
     * stores the new package name, so without the teardown below the old distributor keeps the
     * subscription forever (the app stays listed in it) and Telegram keeps pushing to an
     * endpoint nobody listens to any more.
     */
    public static void switchDistributor(String distributor) {
        // Only a distributor that already answered with an endpoint is left alone: the startup
        // auto-pick saves one without waiting for the answer, so comparing against the saved
        // package alone would turn re-picking a silently dead distributor into a no-op, with no
        // way to retry a failed registration from the settings.
        if (distributor.equals(UnifiedPush.getAckDistributor(ApplicationLoader.applicationContext))) {
            return;
        }
        UnifiedPushReceiver.log("switch -> " + distributor);
        subscribe(distributor);
    }

    /**
     * Asks the distributor in use for a fresh endpoint after something the endpoint is built
     * from changed (the gateway URL, the built-in distributor's VAPID key). The old endpoint
     * has to go first: Telegram would otherwise keep pushing to it, and the FCM endpoint check
     * no longer recognises it once the gateway moved.
     */
    public static void reregisterCurrent() {
        String distributor = UnifiedPush.getSavedDistributor(ApplicationLoader.applicationContext);
        if (distributor == null) {
            return;
        }
        UnifiedPushReceiver.log("re-register -> " + distributor);
        subscribe(distributor);
    }

    /**
     * Drops whatever subscription is live and takes a fresh one from the given distributor.
     * unregister() clears the saved distributor once its last instance goes, so the package has
     * to be written back before registering - otherwise register() has nobody to ask and the
     * app looks like it lost its distributor.
     */
    private static void subscribe(String distributor) {
        dropCurrentRegistration();
        UnifiedPush.saveDistributor(ApplicationLoader.applicationContext, distributor);
        SharedConfig.setMgEmbeddedFcmChosen(
                MgEmbeddedFcmDistributor.isSelf(ApplicationLoader.applicationContext, distributor));
        SharedConfig.setUnifiedPushEndpointUrl("");
        UnifiedPush.register(
                ApplicationLoader.applicationContext,
                "default",
                "Mercurygram WebPush",
                null
        );
        notifyStateChanged();
    }

    /** Unsubscribes from the current distributor and revokes both server-side tokens. */
    private static void dropCurrentRegistration() {
        UnifiedPush.unregister(ApplicationLoader.applicationContext, "default");
        // Before anything else clears SharedConfig.pushString.
        revokeServerTokens();
    }

    /**
     * Revokes both server-side tokens and clears the local copies. Also what the receiver
     * runs when the distributor drops us: PushListenerController.sendRegistrationToServer(type,
     * null) only nulls the native regId, so without this Telegram would keep pushing to the
     * dead endpoint and ensureRegistered() would keep seeing a token that no longer works.
     */
    public static void revokeServerTokens() {
        unregisterWebPush();
        unregisterSimplePush();
    }

    @Override
    public int getPushType() {
        return PushListenerController.PUSH_TYPE_WEB;
    }

    /**
     * Registers a Simple Push (token_type=4) endpoint URL with Telegram for all active accounts.
     * Simple Push is a plain PUT wake-up with no encrypted payload, used by Telegram to notify
     * about events where no content can be included (e.g., encrypted chats).
     *
     * Unlike sendRegistrationToServer(), this does NOT overwrite SharedConfig.pushString/pushType
     * (which remain set to the primary Web Push type=10 registration).
     */
    public static void sendSimplePushRegistration(String token) {
        if (TextUtils.isEmpty(token)) {
            return;
        }
        SharedConfig.pushStringSimple = token;
        SharedConfig.saveConfig();
        Utilities.stageQueue.postRunnable(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                UserConfig userConfig = UserConfig.getInstance(a);
                if (userConfig.getClientUserId() != 0) {
                    final int currentAccount = a;
                    AndroidUtilities.runOnUIThread(() ->
                            MgSimplePush.register(currentAccount, token));
                }
            }
        });
    }

    /**
     * Revokes the Web Push (token_type=10) registration. PushListenerController
     * .sendRegistrationToServer(type, null) only clears the local token, it never sends an
     * unregisterDevice, so without this Telegram keeps pushing to a dead endpoint.
     *
     * UserConfig.registeredForPush is deliberately left alone: pushString is now empty, so
     * registerForPush()'s regid.equals(SharedConfig.pushString) guard no longer short-circuits,
     * and the re-enable path resets the flag itself.
     */
    private static void unregisterWebPush() {
        // Capture the token BEFORE clearing: the runnable is async on stageQueue, so reading
        // SharedConfig.pushString there would see the already-cleared empty value and
        // the unregisterDevice request would never be sent.
        String token = SharedConfig.pushString;
        SharedConfig.pushString = "";
        SharedConfig.saveConfig();
        if (TextUtils.isEmpty(token)) {
            return;
        }
        Utilities.stageQueue.postRunnable(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                UserConfig userConfig = UserConfig.getInstance(a);
                if (userConfig.getClientUserId() != 0) {
                    final int currentAccount = a;
                    AndroidUtilities.runOnUIThread(() ->
                            MgSimplePush.unregister(currentAccount, token, PushListenerController.PUSH_TYPE_WEB));
                }
            }
        });
    }

    public static void unregisterSimplePush() {
        // Capture the token BEFORE clearing: the runnable is async on stageQueue, so reading
        // SharedConfig.pushStringSimple there would see the already-cleared empty value and
        // the unregisterDevice request would never be sent.
        String token = SharedConfig.pushStringSimple;
        SharedConfig.pushStringSimple = "";
        SharedConfig.saveConfig();
        if (TextUtils.isEmpty(token)) {
            return;
        }
        Utilities.stageQueue.postRunnable(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                UserConfig userConfig = UserConfig.getInstance(a);
                if (userConfig.getClientUserId() != 0) {
                    final int currentAccount = a;
                    AndroidUtilities.runOnUIThread(() ->
                            MgSimplePush.unregister(currentAccount, token, PushListenerController.PUSH_TYPE_SIMPLE));
                }
            }
        });
    }
}
