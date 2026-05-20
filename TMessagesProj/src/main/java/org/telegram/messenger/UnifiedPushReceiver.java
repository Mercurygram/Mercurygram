package org.telegram.messenger;

import android.os.PowerManager;
import android.os.SystemClock;

import org.telegram.tgnet.ConnectionsManager;
import org.unifiedpush.android.connector.FailedReason;
import org.unifiedpush.android.connector.PushService;
import org.unifiedpush.android.connector.data.PushEndpoint;
import org.unifiedpush.android.connector.data.PushMessage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import it.belloworld.mercurygram.WebPushDecryptor;

public class UnifiedPushReceiver extends PushService {

    private static long lastReceivedNotification = 0;
    private static long numOfReceivedNotifications = 0;
    private static long numDecryptSuccess = 0;
    private static long numDecryptFailed = 0;

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

    @Override
    public void onNewEndpoint(PushEndpoint endpoint, String instance) {
        Utilities.globalQueue.postRunnable(() -> {
            SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime();

            // Persist the raw endpoint URL so we can detect ntfy.sh usage
            SharedConfig.setUnifiedPushEndpointUrl(endpoint.getUrl());

            // Ensure WebPush ECDH keys exist before registering
            SharedConfig.ensureWebPushKeys();

            // All distributors route through the /aesgcm gateway which serializes
            // WebPush headers into the body (common-proxies compatible format)
            String gateway = SharedConfig.unifiedPushGateway;
            if (!gateway.endsWith("/")) gateway += "/";

            try {
                String gatewayUrl = gateway + "aesgcm?e=" + URLEncoder.encode(endpoint.getUrl(), StandardCharsets.UTF_8.name());

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
                String simplePushUrl = gateway + URLEncoder.encode(endpoint.getUrl(), StandardCharsets.UTF_8.name());
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
        SharedConfig.pushStringStatus = "__UNIFIEDPUSH_FAILED__";
        Utilities.globalQueue.postRunnable(() -> {
            SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime();
            PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_WEB, null);
            it.belloworld.mercurygram.push.UnifiedPushListenerServiceProvider.unregisterSimplePush();
        });
    }

    @Override
    public void onUnregistered(String instance) {
        SharedConfig.pushStringStatus = "__UNIFIEDPUSH_FAILED__";
        Utilities.globalQueue.postRunnable(() -> {
            SharedConfig.pushStringGetTimeEnd = SystemClock.elapsedRealtime();
            PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_WEB, null);
            it.belloworld.mercurygram.push.UnifiedPushListenerServiceProvider.unregisterSimplePush();
        });
    }
}
