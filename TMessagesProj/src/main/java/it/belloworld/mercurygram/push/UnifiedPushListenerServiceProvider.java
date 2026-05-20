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
        return !UnifiedPush.getDistributors(ApplicationLoader.applicationContext).isEmpty();
    }

    @Override
    public String getLogTitle() {
        return "UnifiedPush";
    }

    @Override
    public void onRequestPushToken() {
        if (SharedConfig.disableUnifiedPush) {
            UnifiedPush.unregister(ApplicationLoader.applicationContext, "default");
        } else {
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
                        List<String> distributors = UnifiedPush.getDistributors(ApplicationLoader.applicationContext);
                        if (!distributors.isEmpty()) {
                            UnifiedPush.saveDistributor(ApplicationLoader.applicationContext, distributors.get(0));
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
                            MgSimplePush.unregister(currentAccount, token));
                }
            }
        });
    }
}
