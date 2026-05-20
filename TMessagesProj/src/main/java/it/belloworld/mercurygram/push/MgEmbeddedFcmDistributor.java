package it.belloworld.mercurygram.push;

import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.SharedConfig;
import org.unifiedpush.android.embedded_fcm_distributor.EmbeddedDistributorReceiver;
import org.unifiedpush.android.embedded_fcm_distributor.Gateway;

/**
 * UnifiedPush distributor that delivers through Firebase Cloud Messaging without any
 * Google library: the upstream receiver only talks to Play Services over IPC, and Play
 * Services answers with a plain WebPush endpoint.
 *
 * FCM accepts pushes to that endpoint only with a VAPID authorization, which Telegram
 * does not send, so the endpoint handed to Telegram is the aesgcm-proxy gateway's /fcm
 * route: it folds the aesgcm headers into the body, signs with the private half of the
 * configured VAPID key and forwards to FCM. From
 * {@link org.telegram.messenger.UnifiedPushReceiver} on, the payload is handled exactly
 * like any other distributor's.
 *
 * The route and the VAPID public key are one gateway identity and only work as a pair:
 * a host that does not hold the matching private half signs with a key FCM does not
 * know, and every push is rejected. So both follow the same UnifiedPush gateway setting
 * - the route is derived from it, and the key sits next to it in the settings - instead
 * of being pinned to the Mercurygram gateway, which used to lock self-hosters out.
 */
public class MgEmbeddedFcmDistributor extends EmbeddedDistributorReceiver {

    public static final String DEFAULT_VAPID_PUBLIC_KEY =
            "BOocuINYMsroo0cng_bA3B1AhDGnfxkGuYE_J_gH5G3w_Ek1t_kAOXA8CZS1WtenzRFaGMwnTKGQ7Hp4h3Dmw1g";

    private static final Gateway GATEWAY = new Gateway() {
        @NonNull
        @Override
        public String getVapid() {
            return SharedConfig.mgFcmVapidKey;
        }

        @NonNull
        @Override
        public String getEndpoint(@NonNull String token) {
            return endpointPrefix() + token;
        }
    };

    /** The UnifiedPush gateway with the trailing slash the URL building below assumes. */
    public static String gatewayBase() {
        String gateway = SharedConfig.unifiedPushGateway;
        return gateway.endsWith("/") ? gateway : gateway + "/";
    }

    /** The gateway route FCM endpoints are handed to Telegram under. */
    public static String endpointPrefix() {
        return gatewayBase() + "fcm/";
    }

    @Nullable
    @Override
    public Gateway getGateway() {
        return GATEWAY;
    }

    /** True when the endpoint came from this distributor, which needs no extra wrapping. */
    public static boolean isFcmEndpoint(String endpoint) {
        return endpoint != null && endpoint.startsWith(endpointPrefix());
    }

    /**
     * True when this distributor can actually register. Without Play Services the receiver can
     * only answer REGISTRATION_FAILED, and the connector does not filter it out for us: its own
     * "own package needs Play Services" check matches the class names of the legacy 2.x embedded
     * distributor, which this artifact no longer ships, so our package is always listed.
     */
    public static boolean isAvailable(Context context) {
        try {
            context.getPackageManager().getPackageInfo("com.google.android.gms", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** True when the given distributor package name is this built-in distributor. */
    public static boolean isSelf(Context context, String distributor) {
        return context.getPackageName().equals(distributor);
    }
}
