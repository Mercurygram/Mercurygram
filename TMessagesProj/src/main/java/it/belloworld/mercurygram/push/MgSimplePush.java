package it.belloworld.mercurygram.push;

import android.text.TextUtils;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.PushListenerController;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;

/**
 * Simple Push (token_type=4) registration: the plain PUT wake-up channel used
 * for secret-chat notifications. Extracted from MessagesController so the MG
 * footprint there is a single sync hook in registerForPush().
 */
public final class MgSimplePush {

    private MgSimplePush() {}

    // Per-account SharedPreferences key holding the last type-4 token the
    // server accepted. Lives in the userconfig file so logout (clearConfig)
    // wipes it with the rest of the account.
    private static final String PREF_REGISTERED_TOKEN = "mgSimplePushToken";

    /**
     * Migration + sync hook called from every registerForPush(): reconstructs
     * the type-4 token for installs predating Simple Push support and registers
     * it only when it differs from the one the server last accepted, so a
     * getDifference() does not cost an extra account.registerDevice on the
     * update stream.
     */
    public static void syncOnRegisterForPush(int account) {
            if (SharedConfig.disableUnifiedPush) {
                // Otherwise the block below re-registers type 4 on every getDifference(),
                // silently undoing the toggle.
                return;
            }
            // One-time migration for users updating from a version without Simple Push support:
            // pushStringSimple will be empty on first run after update, but unifiedPushEndpointUrl
            // was already persisted by the old version's onNewEndpoint(). Reconstruct the token
            // so type-4 gets registered without requiring the user to re-select their distributor.
            if (TextUtils.isEmpty(SharedConfig.pushStringSimple)
                    && !TextUtils.isEmpty(SharedConfig.unifiedPushEndpointUrl)) {
                String gateway = SharedConfig.unifiedPushGateway;
                if (!gateway.endsWith("/")) gateway += "/";
                try {
                    SharedConfig.pushStringSimple = gateway
                            + java.net.URLEncoder.encode(SharedConfig.unifiedPushEndpointUrl, "UTF-8");
                    SharedConfig.saveConfig();
                } catch (java.io.UnsupportedEncodingException ignored) {}
            }
            if (!TextUtils.isEmpty(SharedConfig.pushStringSimple)
                    && !SharedConfig.pushStringSimple.equals(registeredToken(account))) {
                register(account, SharedConfig.pushStringSimple);
            }
    }

    /**
     * Registers a Simple Push (token_type=4) URL with Telegram for this account.
     * Called after the primary Web Push (type=10) registration via sendSimplePushRegistration().
     * Uses the same pushAuthKey as the primary registration.
     * Does not set registeredForPush — that flag tracks the primary type=10 registration.
     */
    public static void register(int account, String token) {
        if (TextUtils.isEmpty(token) || UserConfig.getInstance(account).getClientUserId() == 0) {
            return;
        }
        if (SharedConfig.pushAuthKey == null) {
            SharedConfig.pushAuthKey = new byte[256];
            Utilities.random.nextBytes(SharedConfig.pushAuthKey);
            SharedConfig.saveConfig();
        }
        TL_account.registerDevice req = new TL_account.registerDevice();
        req.token_type = PushListenerController.PUSH_TYPE_SIMPLE;
        req.token = token;
        req.no_muted = false;
        req.secret = SharedConfig.pushAuthKey;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig userConfig = UserConfig.getInstance(a);
            if (a != account && userConfig.isClientActivated()) {
                req.other_uids.add(userConfig.getClientUserId());
            }
        }
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (response instanceof TLRPC.TL_boolTrue) {
                setRegisteredToken(account, token);
            }
            if (BuildVars.LOGS_ENABLED) {
                if (response instanceof TLRPC.TL_boolTrue) {
                    FileLog.d("account " + account + " registered simple push");
                } else {
                    FileLog.d("account " + account + " simple push registration failed: " + error);
                }
            }
        });
    }

    private static String registeredToken(int account) {
        return UserConfig.getInstance(account).getPreferences().getString(PREF_REGISTERED_TOKEN, "");
    }

    private static void setRegisteredToken(int account, String token) {
        UserConfig.getInstance(account).getPreferences().edit().putString(PREF_REGISTERED_TOKEN, token).apply();
    }

    /**
     * Revokes a device token of the given type (PushListenerController.PUSH_TYPE_SIMPLE for
     * the type-4 Simple Push URL, PUSH_TYPE_WEB for the type-10 Web Push JSON) for this account.
     */
    public static void unregister(int account, String token, int tokenType) {
        if (TextUtils.isEmpty(token) || UserConfig.getInstance(account).getClientUserId() == 0) {
            return;
        }
        if (tokenType == PushListenerController.PUSH_TYPE_SIMPLE) {
            setRegisteredToken(account, "");
        }
        TL_account.unregisterDevice req = new TL_account.unregisterDevice();
        req.token_type = tokenType;
        req.token = token;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig userConfig = UserConfig.getInstance(a);
            if (a != account && userConfig.isClientActivated()) {
                req.other_uids.add(userConfig.getClientUserId());
            }
        }
        ConnectionsManager.getInstance(account).sendRequest(req, null);
    }
}
