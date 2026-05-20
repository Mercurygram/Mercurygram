package org.telegram.messenger;

import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextUtils;

import org.telegram.tgnet.ConnectionsManager;
import org.unifiedpush.android.connector.FailedReason;
import org.unifiedpush.android.connector.PushService;
import org.unifiedpush.android.connector.data.PushEndpoint;
import org.unifiedpush.android.connector.data.PushMessage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

import it.belloworld.mercurygram.WebPushDecryptor;

public class UnifiedPushReceiver extends PushService {

    private static long lastReceivedNotification = 0;
    private static long numOfReceivedNotifications = 0;
    private static long numDecryptSuccess = 0;
    private static long numDecryptFailed = 0;
    // Last reason a distributor refused to register us, for the settings diagnostic: without it
    // a failed registration is indistinguishable from one that never got an answer.
    private static String lastRegistrationFailure = null;
    // Short timeline of registration events for the settings diagnostic. A single "last
    // failure" string cannot tell whether an endpoint arrived before or after the failure,
    // which is exactly what a bug report about a missing endpoint needs.
    private static final int EVENT_LOG_SIZE = 20;
    private static final ArrayDeque<String> eventLog = new ArrayDeque<>(EVENT_LOG_SIZE);

    // Static WakeLock — prevents GC from finalizing/releasing it while async work is in progress.
    // Reference-counted: each onMessage() acquire increments, each completion release decrements.
    // Hard timeout (30s per-acquire) as safety net.
    private static PowerManager.WakeLock sWakeLock;

    private static synchronized void acquireWakeLock(PowerManager pm) {
        if (sWakeLock == null) {
            sWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mercurygram:wp");
            sWakeLock.setReferenceCounted(true);
        }
        sWakeLock.acquire(30_000);
    }

    private static synchronized void releaseWakeLock() {
        if (sWakeLock != null && sWakeLock.isHeld()) {
            try {
                sWakeLock.release();
            } catch (RuntimeException ignored) {
                // Already released by timeout
            }
        }
    }

    public static long getLastReceivedNotification() {
        return lastReceivedNotification;
    }

    public static long getNumOfReceivedNotifications() {
        return numOfReceivedNotifications;
    }

    public static long getNumDecryptSuccess() {
        return numDecryptSuccess;
    }

    public static long getNumDecryptFailed() {
        return numDecryptFailed;
    }

    public static String getLastRegistrationFailure() {
        return lastRegistrationFailure;
    }

    public static synchronized void log(String event) {
        if (eventLog.size() == EVENT_LOG_SIZE) {
            eventLog.removeFirst();
        }
        eventLog.addLast(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + event);
    }

    public static synchronized String getEventLog() {
        return TextUtils.join("\n", eventLog);
    }

    @Override
    public void onNewEndpoint(PushEndpoint endpoint, String instance) {
        if (SharedConfig.disableUnifiedPush) {
            // A distributor re-announcing its endpoint (reboot, distributor update, ntfy
            // re-subscribe) would otherwise re-register both token types behind the toggle.
            // Tell it to stop rather than merely ignoring it.
            org.unifiedpush.android.connector.UnifiedPush.unregister(this, instance);
            return;
        }
        log("endpoint: " + android.net.Uri.parse(endpoint.getUrl()).getHost());
        lastRegistrationFailure = null;
        Utilities.globalQueue.postRunnable(() -> {
            SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime();

            // Persist the raw endpoint URL so we can detect ntfy.sh usage
            SharedConfig.setUnifiedPushEndpointUrl(endpoint.getUrl());

            // Ensure WebPush ECDH keys exist before registering
            SharedConfig.ensureWebPushKeys();

            // All distributors route through the /aesgcm gateway which serializes
            // WebPush headers into the body (common-proxies compatible format)
            String gateway = it.belloworld.mercurygram.push.MgEmbeddedFcmDistributor.gatewayBase();

            try {
                // The embedded FCM distributor already points at the gateway's /fcm route,
                // which folds the headers itself and signs the push for FCM. Wrapping it in
                // /aesgcm would fold twice and strip the VAPID signing.
                boolean fcm = it.belloworld.mercurygram.push.MgEmbeddedFcmDistributor.isFcmEndpoint(endpoint.getUrl());
                String gatewayUrl = fcm
                        ? endpoint.getUrl()
                        : gateway + "aesgcm?e=" + URLEncoder.encode(endpoint.getUrl(), StandardCharsets.UTF_8.name());

                // WebPush JSON token: endpoint + client keys for Telegram to encrypt payloads
                String p256dh = android.util.Base64.encodeToString(SharedConfig.webPushPublicKey,
                        android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
                String auth = android.util.Base64.encodeToString(SharedConfig.webPushAuthSecret,
                        android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);

                org.json.JSONObject keys = new org.json.JSONObject();
                keys.put("p256dh", p256dh);
                keys.put("auth", auth);
                org.json.JSONObject tokenObj = new org.json.JSONObject();
                tokenObj.put("endpoint", gatewayUrl);
                tokenObj.put("keys", keys);
                PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_WEB, tokenObj.toString());

                // Also register Simple Push (token_type=4) for encrypted chat wake-ups.
                // Telegram sends a PUT to this URL for events where no content can be included
                // (e.g. secret chats). The gateway correlates it with the Web Push POST and
                // triggers a synthetic wake-up if no encrypted payload arrives.
                String simplePushUrl = fcm
                        ? endpoint.getUrl()
                        : gateway + URLEncoder.encode(endpoint.getUrl(), StandardCharsets.UTF_8.name());
                it.belloworld.mercurygram.push.UnifiedPushListenerServiceProvider.sendSimplePushRegistration(simplePushUrl);
            } catch (Exception e) {
                FileLog.e(e);
            }

            // Notify NotificationsSettingsActivity to rebuild its rows (shows/hides ntfy.sh warning)
            AndroidUtilities.runOnUIThread(() -> {
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    if (UserConfig.getInstance(a).isClientActivated()) {
                        NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.notificationsSettingsUpdated);
                    }
                }
            });
        });
    }

    @Override
    public void onMessage(PushMessage message, String instance) {
        final long receiveTime = SystemClock.elapsedRealtime();

        lastReceivedNotification = receiveTime;
        numOfReceivedNotifications++;

        // Completion-based WakeLock: released when async work finishes,
        // hard 30s timeout as safety net. Reference-counted so concurrent
        // pushes don't release each other's lock.
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        acquireWakeLock(pm);

        // Try WebPush decryption first
        if (SharedConfig.webPushPrivateKey != null && SharedConfig.webPushPublicKey != null && SharedConfig.webPushAuthSecret != null) {
            try {
                byte[] plaintext = WebPushDecryptor.decrypt(
                        message.getContent(),
                        SharedConfig.webPushPrivateKey,
                        SharedConfig.webPushPublicKey,
                        SharedConfig.webPushAuthSecret
                );
                // Decrypted payload is JSON {"p":"<base64url-mtproto>"}, same as FCM
                org.json.JSONObject payloadJson = new org.json.JSONObject(new String(plaintext, StandardCharsets.UTF_8));
                String encoded = payloadJson.getString("p");
                numDecryptSuccess++;
                log("push");
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("WP START PROCESSING (decrypted)");
                }
                // Background thread: processRemoteMessage() blocks via static
                // countDownLatch.await() — calling from main thread deadlocks.
                // Pass System.currentTimeMillis() (not elapsedRealtime) because
                // processRemoteMessage() uses it as messageOwner.date (Unix epoch).
                Utilities.globalQueue.postRunnable(() -> {
                    try {
                        PushListenerController.processRemoteMessage(
                                PushListenerController.PUSH_TYPE_WEB, encoded, System.currentTimeMillis());
                    } finally {
                        releaseWakeLock();
                    }
                });
                return;
            } catch (Exception e) {
                numDecryptFailed++;
                log("push (decrypt failed)");
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("WP DECRYPT ERROR, falling back to wake-up: " + e.getMessage());
                }
                // Fall through to wake-up behavior
            }
        }

        // Fallback: wake up the app to fetch updates via MTProto
        AndroidUtilities.runOnUIThread(() -> {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("UP PRE INIT APP");
            }
            ApplicationLoader.postInitApplication();
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("UP POST INIT APP");
            }
            Utilities.stageQueue.postRunnable(() -> {
                try {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("UP START PROCESSING (wake-up fallback)");
                    }
                    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                        if (UserConfig.getInstance(a).isClientActivated()) {
                            ConnectionsManager.onInternalPushReceived(a);
                            ConnectionsManager.getInstance(a).resumeNetworkMaybe();
                        }
                    }
                } finally {
                    releaseWakeLock();
                }
            });
        });
    }

    @Override
    public void onRegistrationFailed(FailedReason reason, String instance) {
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("Failed to get endpoint: " + reason);
        }
        onRegistrationLost(String.valueOf(reason));
    }

    @Override
    public void onUnregistered(String instance) {
        onRegistrationLost("unregistered by the distributor");
    }

    /**
     * The connector has already dropped the token and the saved distributor by the time this
     * runs. Revoking both tokens at Telegram leaves SharedConfig.pushString empty, which is
     * what lets UnifiedPushListenerServiceProvider.ensureRegistered() register again on the
     * next reconnect instead of the next cold start.
     */
    private static void onRegistrationLost(String reason) {
        log(reason);
        SharedConfig.pushStringStatus = "__UNIFIEDPUSH_FAILED__";
        lastRegistrationFailure = reason;
        Utilities.globalQueue.postRunnable(() -> {
            SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime();
            PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_WEB, null);
            it.belloworld.mercurygram.push.UnifiedPushListenerServiceProvider.revokeServerTokens();
        });
    }
}
