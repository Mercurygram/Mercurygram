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

    private UnifiedPushListenerServiceProvider() {}

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
                    if (!ownPackage.equals(UnifiedPush.getSavedDistributor(ApplicationLoader.applicationContext))) {
                        List<String> distributors = UnifiedPush.getDistributors(ApplicationLoader.applicationContext);
                        for (String distributor : distributors) {
                            if (!ownPackage.equals(distributor)) {
                                UnifiedPush.saveDistributor(ApplicationLoader.applicationContext, distributor);
                                break;
                            }
                        }
                    }
                }
                UnifiedPush.register(
                        ApplicationLoader.applicationContext,
                        "default",
                        "Mercurygram WebPush",
                        null
                );
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
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
        dropCurrentRegistration();
        PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_WEB, null);
    }

    /**
     * Moves the subscription to another distributor. The connector's saveDistributor() only
     * stores the new package name, so without the teardown below the old distributor keeps the
     * subscription forever (the app stays listed in it) and Telegram keeps pushing to an
     * endpoint nobody listens to any more.
     */
    public static void switchDistributor(String distributor) {
        if (distributor.equals(UnifiedPush.getSavedDistributor(ApplicationLoader.applicationContext))) {
            return;
        }
        // unregister() targets the currently saved distributor and clears the pref itself, so it
        // has to run before the new one is saved.
        dropCurrentRegistration();
        SharedConfig.setUnifiedPushEndpointUrl("");
        UnifiedPush.saveDistributor(ApplicationLoader.applicationContext, distributor);
        UnifiedPush.register(
                ApplicationLoader.applicationContext,
                "default",
                "Mercurygram WebPush",
                null
        );
    }

    /** Unsubscribes from the current distributor and revokes both server-side tokens. */
    private static void dropCurrentRegistration() {
        UnifiedPush.unregister(ApplicationLoader.applicationContext, "default");
        // Before anything else clears SharedConfig.pushString.
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
