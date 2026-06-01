/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;
import android.webkit.WebView;

import androidx.annotation.IntDef;
import androidx.annotation.RequiresApi;
import androidx.core.content.pm.ShortcutManagerCompat;

import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.SwipeGestureSettingsView;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

public class SharedConfig {
    /**
     * V2: Ping and check time serialized
     */
    private final static int PROXY_SCHEMA_V2 = 2;
    private final static int PROXY_CURRENT_SCHEMA_VERSION = PROXY_SCHEMA_V2;

    public final static int PASSCODE_TYPE_PIN = 0,
            PASSCODE_TYPE_PASSWORD = 1;
    private static int legacyDevicePerformanceClass = -1;

    public static boolean loopStickers() {
        return LiteMode.isEnabled(LiteMode.FLAG_ANIMATED_STICKERS_CHAT);
    }

    public static boolean readOnlyStorageDirAlertShowed;

    public static void checkSdCard(File file) {
        if (file == null || SharedConfig.storageCacheDir == null || readOnlyStorageDirAlertShowed) {
            return;
        }
        if (file.getPath().startsWith(SharedConfig.storageCacheDir)) {
            AndroidUtilities.runOnUIThread(() -> {
                if (readOnlyStorageDirAlertShowed) {
                    return;
                }
                BaseFragment fragment = LaunchActivity.getLastFragment();
                if (fragment != null && fragment.getParentActivity() != null) {
                    SharedConfig.storageCacheDir = null;
                    SharedConfig.saveConfig();
                    ImageLoader.getInstance().checkMediaPaths(() -> {

                    });

                    readOnlyStorageDirAlertShowed = true;
                    AlertDialog.Builder dialog = new AlertDialog.Builder(fragment.getParentActivity());
                    dialog.setTitle(LocaleController.getString(R.string.SdCardError));
                    dialog.setSubtitle(LocaleController.getString(R.string.SdCardErrorDescription));
                    dialog.setPositiveButton(LocaleController.getString(R.string.DoNotUseSDCard), (dialog1, which) -> {

                    });
                    Dialog dialogFinal = dialog.create();
                    dialogFinal.setCanceledOnTouchOutside(false);
                    dialogFinal.show();
                }
            });
        }
    }

    static Boolean allowPreparingHevcPlayers;

    public static boolean allowPreparingHevcPlayers() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
            return false;
        }
        if (allowPreparingHevcPlayers == null) {
            int codecCount = MediaCodecList.getCodecCount();
            int maxInstances = 0;
            int capabilities = 0;

            for (int i = 0; i < codecCount; i++) {
                MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
                if (codecInfo.isEncoder()) {
                    continue;
                }

                boolean found = false;
                for (int k = 0; k < codecInfo.getSupportedTypes().length; k++) {
                    if (codecInfo.getSupportedTypes()[k].contains("video/hevc")) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    continue;
                }
                capabilities = codecInfo.getCapabilitiesForType("video/hevc").getMaxSupportedInstances();
                if (capabilities > maxInstances) {
                    maxInstances = capabilities;
                }
            }
            allowPreparingHevcPlayers = maxInstances >= 8;
        }
        return allowPreparingHevcPlayers;
    }

    public static void togglePaymentByInvoice() {
        payByInvoice = !payByInvoice;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putBoolean("payByInvoice", payByInvoice)
                .apply();
    }

    public static void toggleDisableUnifiedPush() {
        disableUnifiedPush = !disableUnifiedPush;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putBoolean("mg_disableUnifiedPush", disableUnifiedPush)
                .commit();
    }

    public static void toggleDisableSecureFlags() {
        disableSecureFlags = !disableSecureFlags;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putBoolean("mg_disableSecureFlags", disableSecureFlags)
                .commit();
    }

    public static void toggleRemoveAdsAndProxySponsor() {
        removeAdsAndProxySponsor = !removeAdsAndProxySponsor;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putBoolean("mg_removeAdsAndProxySponsor", removeAdsAndProxySponsor)
                .commit();
    }

    public static void toggleDisableAutoUpdate() {
        disableAutoUpdate = !disableAutoUpdate;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putBoolean("mg_disableAutoUpdate", disableAutoUpdate)
                .apply();
    }

    public static void toggleAcceptPreReleaseUpdates() {
        acceptPreReleaseUpdates = !acceptPreReleaseUpdates;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putBoolean("mg_acceptPreReleaseUpdates", acceptPreReleaseUpdates)
                .apply();
    }

    public static void toggleUseSystemFont() {
        useSystemFont = !useSystemFont;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putBoolean("mg_useSystemFont", useSystemFont)
                .apply();
        AndroidUtilities.clearTypefaceCache();
    }

    // Privacy-critical pref: commit() (synchronous). A crash between flip and
    // disk write would silently revert the toggle and let MTProto fall back to
    // the upstream 24h TTL + CDN-allowed path on next launch, defeating the
    // mitigation the user just enabled.
    public static void toggleReduceTrackingFingerprint() {
        reduceTrackingFingerprint = !reduceTrackingFingerprint;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putBoolean("mg_reduceTrackingFingerprint", reduceTrackingFingerprint)
                .commit();
        applyReduceTrackingFingerprintToNative();
    }

    // Privacy-critical pref: commit() (synchronous). A crash between flip and
    // disk write would leave mg_useTor=false on next launch, defeating the
    // toggle and letting MTProto connect direct.
    public static void toggleMgUseTor() {
        mg_useTor = !mg_useTor;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putBoolean("mg_useTor", mg_useTor)
                .commit();
    }

    public static void toggleMgHideStories() {
        mg_hideStories = !mg_hideStories;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putBoolean("mg_hideStories", mg_hideStories)
                .apply();
    }

    public static void setMgTranslateMode(String mode) {
        mode = sanitizeMgTranslateMode(mode);
        mg_translateMode = mode;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putString("mg_translateMode", mode)
                .apply();
    }

    private static String sanitizeMgTranslateMode(String mode) {
        if (MG_TRANSLATE_MODE_DEFAULT.equals(mode)
                || MG_TRANSLATE_MODE_CLOUD.equals(mode)
                || MG_TRANSLATE_MODE_ALTERNATIVE.equals(mode)
                || MG_TRANSLATE_MODE_OFFLINE.equals(mode)) {
            return mode;
        }
        return MG_TRANSLATE_MODE_DEFAULT;
    }

    public static void toggleMgTranslateAutoFallback() {
        mg_translateAutoFallback = !mg_translateAutoFallback;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putBoolean("mg_translateAutoFallback", mg_translateAutoFallback)
                .apply();
    }

    public static void setMgTranslateOfflineFormatToastShown() {
        mg_translateOfflineFormatToastShown = true;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putBoolean("mg_translateOfflineFormatToastShown", true)
                .apply();
    }

    public static void toggleMgTranscribeOffline() {
        mg_transcribeOffline = !mg_transcribeOffline;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putBoolean("mg_transcribeOffline", mg_transcribeOffline)
                .apply();
    }

    public static void setMgTranscribeModel(String model) {
        mg_transcribeModel = model;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putString("mg_transcribeModel", model)
                .apply();
    }

    public static void setMgTranscribeLang(String lang) {
        mg_transcribeLang = lang;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putString("mg_transcribeLang", lang)
                .apply();
    }

    public static void toggleMgTranscribeVad() {
        mg_transcribeVad = !mg_transcribeVad;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putBoolean("mg_transcribeVad", mg_transcribeVad)
                .apply();
    }

    public static void setMgTranslateAltEngine(String engine) {
        engine = sanitizeMgTranslateAltEngine(engine);
        mg_translateAltEngine = engine;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putString("mg_translateAltEngine", engine)
                .apply();
        org.telegram.ui.Components.TranslateAlert2.clearMgAltInstanceBans();
    }

    public static void setMgTranslateAltInstanceMode(String mode) {
        mode = sanitizeMgTranslateAltInstanceMode(mode);
        mg_translateAltInstanceMode = mode;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putString("mg_translateAltInstanceMode", mode)
                .apply();
        org.telegram.ui.Components.TranslateAlert2.clearMgAltInstanceBans();
    }

    public static void setMgTranslateAltPinnedInstance(String url) {
        mg_translateAltPinnedInstance = url == null ? "" : url;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putString("mg_translateAltPinnedInstance", mg_translateAltPinnedInstance)
                .apply();
        org.telegram.ui.Components.TranslateAlert2.clearMgAltInstanceBans();
    }

    public static void setMgTranslateAltCustomInstance(String url) {
        mg_translateAltCustomInstance = url == null ? "" : url;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putString("mg_translateAltCustomInstance", mg_translateAltCustomInstance)
                .apply();
        org.telegram.ui.Components.TranslateAlert2.clearMgAltInstanceBans();
    }

    private static String sanitizeMgTranslateAltEngine(String engine) {
        if (MG_TRANSLATE_ALT_ENGINE_DUCKDUCKGO.equals(engine)
                || MG_TRANSLATE_ALT_ENGINE_LIBRE.equals(engine)
                || MG_TRANSLATE_ALT_ENGINE_GOOGLE.equals(engine)
                || MG_TRANSLATE_ALT_ENGINE_MYMEMORY.equals(engine)
                || MG_TRANSLATE_ALT_ENGINE_REVERSO.equals(engine)) {
            return engine;
        }
        return MG_TRANSLATE_ALT_ENGINE_DUCKDUCKGO;
    }

    private static String sanitizeMgTranslateAltInstanceMode(String mode) {
        if (MG_TRANSLATE_ALT_INSTANCE_MODE_AUTO.equals(mode)
                || MG_TRANSLATE_ALT_INSTANCE_MODE_PINNED.equals(mode)
                || MG_TRANSLATE_ALT_INSTANCE_MODE_CUSTOM.equals(mode)) {
            return mode;
        }
        return MG_TRANSLATE_ALT_INSTANCE_MODE_AUTO;
    }

    /**
     * Returns the ordered list of Mozhi instances to attempt for the current
     * {@link #mg_translateAltInstanceMode}: the full default pool for "auto"
     * (rotation), a single configured URL for "pinned" / "custom". Falls back
     * to the default pool when a pinned/custom mode is selected but no URL is
     * configured.
     */
    public static java.util.List<String> getMgTranslateAltActiveInstances() {
        if (MG_TRANSLATE_ALT_INSTANCE_MODE_PINNED.equals(mg_translateAltInstanceMode)
                && mg_translateAltPinnedInstance != null
                && !mg_translateAltPinnedInstance.isEmpty()) {
            return java.util.Collections.singletonList(mg_translateAltPinnedInstance);
        }
        if (MG_TRANSLATE_ALT_INSTANCE_MODE_CUSTOM.equals(mg_translateAltInstanceMode)
                && mg_translateAltCustomInstance != null
                && !mg_translateAltCustomInstance.isEmpty()) {
            return java.util.Collections.singletonList(mg_translateAltCustomInstance);
        }
        return MG_TRANSLATE_ALT_DEFAULT_INSTANCES;
    }

    public static void setMgTorIdleStopMinutes(int minutes) {
        if (minutes < 0) minutes = 0;
        int previous = mg_torIdleStopMinutes;
        mg_torIdleStopMinutes = minutes;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putInt("mg_torIdleStopMinutes", minutes)
                .apply();
        if (previous == minutes) return;
        try {
            // The plugin caches idleStopMinutes from the bind-time push;
            // without this AIDL re-push the user's new threshold doesn't
            // reach the controller until the next bind cycle (e.g. plugin
            // crash + recover), so 5→60 keeps killing tor at 5 minutes
            // and 60→5 leaves it running for the longer window.
            it.belloworld.mercurygram.tor.MgTorClient.getInstance().pushIdleStopMinutesIfBound();
            // 0 → non-zero: idleCheck previously cancelled the ticker, so
            // the new finite idle never fires without an explicit re-arm.
            // pushAggregatedClientPaused has the side-effect of arming
            // the ticker if any client is paused; no-ops when tor isn't
            // running.
            if (previous <= 0 && minutes > 0) {
                it.belloworld.mercurygram.tor.MgTorClient.getInstance().resumeIdleTickerIfNeeded();
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    // Propagate the reduce-tracking flag to the native MTProto layer for every
    // account: changes TEMP_AUTH_KEY_EXPIRE_TIME between the upstream 24h
    // default and the reduced 1h (with a ladder fallback inside native), and
    // rotates current temp keys immediately so the change takes effect on
    // both enable AND disable. On enable, rotation swaps in 1h-TTL keys
    // straight away; on disable, rotation discards the still-live short-TTL
    // keys so the user sees the upstream behavior immediately rather than
    // up to 1h later when the existing key would naturally expire.
    public static void applyReduceTrackingFingerprintToNative() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            // Per-iteration guard so a failure on account N (e.g. native
            // init not yet complete, ConnectionsManager singleton race)
            // doesn't abort the loop and leave accounts N+1..MAX with
            // stale native state while the SharedConfig flag shows enabled.
            try {
                org.telegram.tgnet.ConnectionsManager cm = org.telegram.tgnet.ConnectionsManager.getInstance(a);
                cm.setReducedTempKeyMode(reduceTrackingFingerprint);
                if (UserConfig.getInstance(a).isClientActivated()) {
                    cm.rotateTempAuthKeys();
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
    }

    // One-shot per BuildVars.BUILD_VERSION_STRING: clear any account's
    // mgReducedTrackingExhausted=true so a transient server-side issue from a
    // previous release doesn't permanently lock the user at the upstream 24h
    // TTL with a permanent exhausted-accounts footer. The native ladder index
    // is reset to 0 on the next applyReduceTrackingFingerprintToNative() call
    // anyway, so this is purely UX state. Idempotent across cold starts.
    // Called from ApplicationLoader.startApplication() after per-account
    // loadConfig() so the in-memory fields reflect on-disk truth.
    public static void maybeClearReducedTrackingExhaustedOnUpgrade() {
        SharedPreferences prefs = ApplicationLoader.applicationContext
                .getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
        String currentBuild = BuildVars.BUILD_VERSION_STRING;
        if (currentBuild == null) return;
        String lastSeen = prefs.getString("mg_lastExhaustedClearedBuild", null);
        if (currentBuild.equals(lastSeen)) return;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            try {
                UserConfig uc = UserConfig.getInstance(a);
                if (uc.mgReducedTrackingExhausted) {
                    uc.mgReducedTrackingExhausted = false;
                    uc.saveConfig(false);
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
        prefs.edit().putString("mg_lastExhaustedClearedBuild", currentBuild).apply();
    }

    public static void setUnifiedPushGateway(String gateway) {
        unifiedPushGateway = gateway;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putString("mg_unifiedPushGateway2", unifiedPushGateway)
                .apply();
    }

    public static void setUnifiedPushEndpointUrl(String url) {
        unifiedPushEndpointUrl = url != null ? url : "";
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putString("mg_unifiedPushEndpointUrl", unifiedPushEndpointUrl)
                .apply();
    }

    /** Returns true if the user is on the default gateway AND their UP endpoint uses the default ntfy.sh server.
     *  Custom-gateway users are unaffected by the public gateway's ntfy.sh block, so we skip the warning. */
    public static boolean isNtfyDefaultServer() {
        if (!unifiedPushGateway.equals("https://p2p.belloworld.it/")) {
            return false;
        }
        if (!unifiedPushEndpointUrl.isEmpty()) {
            return unifiedPushEndpointUrl.contains("ntfy.sh");
        }
        // Fallback for existing users: check the token sent to Telegram (gateway URL contains encoded endpoint)
        return pushString != null && pushString.contains("ntfy.sh");
    }

    public static synchronized void ensureWebPushKeys() {
        if (webPushPrivateKey != null && webPushPublicKey != null && webPushAuthSecret != null) {
            return;
        }
        try {
            java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("EC");
            kpg.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
            java.security.KeyPair keyPair = kpg.generateKeyPair();
            java.security.interfaces.ECPublicKey ecPub = (java.security.interfaces.ECPublicKey) keyPair.getPublic();

            // Extract raw 65-byte uncompressed P-256 point (04||X||Y)
            java.security.spec.ECPoint w = ecPub.getW();
            byte[] xb = w.getAffineX().toByteArray();
            byte[] yb = w.getAffineY().toByteArray();
            byte[] rawPub = new byte[65];
            rawPub[0] = 0x04;
            if (xb.length >= 32) System.arraycopy(xb, xb.length - 32, rawPub, 1, 32);
            else System.arraycopy(xb, 0, rawPub, 1 + (32 - xb.length), xb.length);
            if (yb.length >= 32) System.arraycopy(yb, yb.length - 32, rawPub, 33, 32);
            else System.arraycopy(yb, 0, rawPub, 33 + (32 - yb.length), yb.length);

            webPushPublicKey = rawPub;
            webPushPrivateKey = keyPair.getPrivate().getEncoded(); // PKCS#8
            byte[] secret = new byte[16];
            new java.security.SecureRandom().nextBytes(secret);
            webPushAuthSecret = secret;

            ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                    .edit()
                    .putString("mg_webPushPrivateKey", Base64.encodeToString(webPushPrivateKey, Base64.DEFAULT))
                    .putString("mg_webPushPublicKey", Base64.encodeToString(webPushPublicKey, Base64.DEFAULT))
                    .putString("mg_webPushAuthSecret", Base64.encodeToString(webPushAuthSecret, Base64.DEFAULT))
                    .apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void toggleSurfaceInStories() {
        useSurfaceInStories = !useSurfaceInStories;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putBoolean("useSurfaceInStories", useSurfaceInStories)
                .apply();
    }

    public static void togglePhotoViewerBlur() {
        photoViewerBlur = !photoViewerBlur;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putBoolean("photoViewerBlur", photoViewerBlur)
                .apply();
    }

    private static String goodHevcEncoder;
    private static HashSet<String> hevcEncoderWhitelist = new HashSet<>();
    static {
        hevcEncoderWhitelist.add("c2.exynos.hevc.encoder");
        hevcEncoderWhitelist.add("OMX.Exynos.HEVC.Encoder".toLowerCase());
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    public static String findGoodHevcEncoder() {
        if (goodHevcEncoder == null) {
            int codecCount = MediaCodecList.getCodecCount();
            for (int i = 0; i < codecCount; i++) {
                MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
                if (!codecInfo.isEncoder()) {
                    continue;
                }

                for (int k = 0; k < codecInfo.getSupportedTypes().length; k++) {
                    if (codecInfo.getSupportedTypes()[k].contains("video/hevc") && codecInfo.isHardwareAccelerated() && isWhitelisted(codecInfo)) {
                        return goodHevcEncoder = codecInfo.getName();
                    }
                }
            }
            goodHevcEncoder = "";
        }
        return TextUtils.isEmpty(goodHevcEncoder) ? null : goodHevcEncoder;
    }

    private static boolean isWhitelisted(MediaCodecInfo codecInfo) {
        if (BuildVars.DEBUG_PRIVATE_VERSION) {
            return true;
        }
        return hevcEncoderWhitelist.contains(codecInfo.getName().toLowerCase());
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            PASSCODE_TYPE_PIN,
            PASSCODE_TYPE_PASSWORD
    })
    public @interface PasscodeType {}

    public final static int SAVE_TO_GALLERY_FLAG_PEER = 1;
    public final static int SAVE_TO_GALLERY_FLAG_GROUP = 2;
    public final static int SAVE_TO_GALLERY_FLAG_CHANNELS = 4;

    @PushListenerController.PushType
    public static int pushType = PushListenerController.PUSH_TYPE_FIREBASE;
    public static String pushString = "";
    public static String pushStringSimple = "";  // Mercurygram: Simple Push (token_type=4) URL
    public static String pushStringStatus = "";

    // Mercurygram: UnifiedPush
    public static boolean disableUnifiedPush = false;
    public static String unifiedPushGateway = "https://p2p.belloworld.it/";
    public static String unifiedPushEndpointUrl = "";   // raw UP endpoint URL from last onNewEndpoint
    public static volatile byte[] webPushPrivateKey;    // PKCS#8-encoded P-256 private key
    public static volatile byte[] webPushPublicKey;     // Raw 65-byte uncompressed P-256 point (04||X||Y)
    public static volatile byte[] webPushAuthSecret;    // 16-byte random auth secret

    // Mercurygram: UI settings
    public static boolean disableSecureFlags = false;
    public static boolean removeAdsAndProxySponsor = false;
    public static boolean disableAutoUpdate = false;
    public static boolean acceptPreReleaseUpdates = false;
    public static boolean useSystemFont = false;

    // Mercurygram: Privacy
    public static boolean reduceTrackingFingerprint = false;
    public static boolean mg_useTor = false;
    public static int mg_torIdleStopMinutes = 5;

    // Mercurygram: client-side privacy/UI toggle ported from 64gram — a pure local
    // display/transport choice (no server interaction beyond *not* issuing one
    // optional RPC); see the api/terms review in AGENTS.md.
    public static boolean mg_hideStories = false;            // suppress the stories bar in the dialog list
    // Mercurygram: Translation engine selection. "default" defers to
    // upstream MessagesController.translationsAutoEnabled; "cloud" forces
    // the Telegram MTProto messages.translateText RPC; "alternative" forces
    // the non-MTProto HTTP path (TranslateAlert2.alternativeTranslate);
    // "offline" delegates each translation to dev.davidv.translator's
    // background AIDL ITranslationService (see MgAidlTranslate).
    public static final String MG_TRANSLATE_MODE_DEFAULT = "default";
    public static final String MG_TRANSLATE_MODE_CLOUD = "cloud";
    public static final String MG_TRANSLATE_MODE_ALTERNATIVE = "alternative";
    public static final String MG_TRANSLATE_MODE_OFFLINE = "offline";
    public static String mg_translateMode = MG_TRANSLATE_MODE_DEFAULT;
    public static boolean mg_translateAutoFallback = true;
    public static boolean mg_translateOfflineFormatToastShown = false;

    // Mercurygram: Alternative HTTP translation routes through a user-picked
    // Mozhi instance (https://codeberg.org/aryak/mozhi) — a multi-engine
    // privacy proxy — instead of contacting translate.googleapis.com directly.
    // The picker keeps the privacy framing of "alternative" honest: text never
    // reaches Google directly from the device, and the user can swap the
    // backend engine (DuckDuckGo / LibreTranslate / Google-via-Mozhi /
    // MyMemory / Reverso) or pin a self-hosted instance.
    public static final String MG_TRANSLATE_ALT_ENGINE_DUCKDUCKGO = "duckduckgo";
    public static final String MG_TRANSLATE_ALT_ENGINE_LIBRE      = "libre";
    public static final String MG_TRANSLATE_ALT_ENGINE_GOOGLE     = "google";
    public static final String MG_TRANSLATE_ALT_ENGINE_MYMEMORY   = "mymemory";
    public static final String MG_TRANSLATE_ALT_ENGINE_REVERSO    = "reverso";

    public static final String MG_TRANSLATE_ALT_INSTANCE_MODE_AUTO   = "auto";
    public static final String MG_TRANSLATE_ALT_INSTANCE_MODE_PINNED = "pinned";
    public static final String MG_TRANSLATE_ALT_INSTANCE_MODE_CUSTOM = "custom";

    public static final java.util.List<String> MG_TRANSLATE_ALT_DEFAULT_INSTANCES =
            java.util.Collections.unmodifiableList(java.util.Arrays.asList(
                    "https://mozhi.aryak.me",
                    "https://mozhi.pussthecat.org",
                    "https://mozhi.catsarch.com",
                    "https://translate.projectsegfau.lt",
                    "https://mozhi.ducks.party"
            ));

    public static String mg_translateAltEngine         = MG_TRANSLATE_ALT_ENGINE_DUCKDUCKGO;
    public static String mg_translateAltInstanceMode   = MG_TRANSLATE_ALT_INSTANCE_MODE_AUTO;
    public static String mg_translateAltPinnedInstance = "";
    public static String mg_translateAltCustomInstance = "";

    // Mercurygram: On-device voice-message transcription via whisper.cpp
    // (it.belloworld.mercurygram.transcribe.MgWhisperTranscriber). When enabled
    // and a model is installed, voice/round-video transcription runs locally
    // instead of via the premium-gated Telegram messages.transcribeAudio RPC —
    // private (audio never leaves the device) and available to non-premium
    // accounts. The model is downloaded / imported separately, not bundled.
    public static boolean mg_transcribeOffline = false;
    public static String mg_transcribeModel = "tiny-q8_0";
    // Transcription language: "auto" = whisper auto-detect; "device" = device
    // locale (default — the tiny/base models mis-detect short clips); otherwise an
    // ISO-639-1 code. Applies uniformly to every model.
    public static final String MG_TRANSCRIBE_LANG_AUTO = "auto";
    public static final String MG_TRANSCRIBE_LANG_DEVICE = "device";
    public static String mg_transcribeLang = MG_TRANSCRIBE_LANG_DEVICE;
    // Silero VAD: strip silence/non-speech before decoding so the tiny model
    // can't hallucinate on silent/short clips. On by default; non-fatal — used
    // only when the (downloaded) VAD model is present, else transcription runs
    // without it. See MgWhisperModel.VAD_FILE / MgWhisperTranscriber.
    public static boolean mg_transcribeVad = true;

    public static long pushStringGetTimeStart;
    public static long pushStringGetTimeEnd;
    public static boolean pushStatSent;
    public static byte[] pushAuthKey;
    public static byte[] pushAuthKeyId;
    public static boolean forceForumTabs;
    public static boolean fastWallpaperDisabled;
    public static boolean frameMetricsEnabled;

    public static String directShareHash;

    @PasscodeType
    public static int passcodeType;
    public static String passcodeHash = "";
    public static long passcodeRetryInMs;
    public static long lastUptimeMillis;
    public static int badPasscodeTries;
    public static byte[] passcodeSalt = new byte[0];
    public static boolean appLocked;
    public static int autoLockIn = 60 * 60;

    public static boolean saveIncomingPhotos;
    public static boolean allowScreenCapture;
    public static int lastPauseTime;
    public static boolean isWaitingForPasscodeEnter;
    public static boolean useFingerprintLock = true;
    public static boolean useFaceLock = true;
    public static int suggestStickers;
    public static boolean suggestAnimatedEmoji;
    public static int keepMedia = CacheByChatsController.KEEP_MEDIA_ONE_MONTH; //deprecated
    public static int lastKeepMediaCheckTime;
    public static int lastLogsCheckTime;
    public static int textSelectionHintShows;
    public static int scheduledOrNoSoundHintShows;
    public static long scheduledOrNoSoundHintSeenAt;
    public static int scheduledHintShows;
    public static long scheduledHintSeenAt;
    public static int lockRecordAudioVideoHint;
    public static boolean forwardingOptionsHintShown, replyingOptionsHintShown;
    public static boolean searchMessagesAsListUsed;
    public static boolean stickersReorderingHintUsed;
    public static int dayNightWallpaperSwitchHint;
    public static boolean storyReactionsLongPressHint;
    public static boolean storiesIntroShown;
    public static boolean disableVoiceAudioEffects;
    public static boolean forceDisableTabletMode;
    public static boolean updateStickersOrderOnSend = true;
    public static boolean bigCameraForRound;
    public static Boolean useCamera2Force;
    public static boolean useNewBlur;
    public static boolean useSurfaceInStories;
    public static boolean photoViewerBlur = true;
    public static boolean payByInvoice;
    public static int stealthModeSendMessageConfirm = 2;
    private static int lastLocalId = -210000;

    public static String storageCacheDir;

    private static String passportConfigJson = "";
    private static HashMap<String, String> passportConfigMap;
    public static int passportConfigHash;

    private static boolean configLoaded;
    private static final Object sync = new Object();
    private static final Object localIdSync = new Object();

//    public static int saveToGalleryFlags;
    public static int mapPreviewType = 2;
    public static int searchEngineType = 0;
    public static String searchEngineCustomURLQuery, searchEngineCustomURLAutocomplete;
    public static boolean chatBubbles = Build.VERSION.SDK_INT >= 30;
    public static boolean raiseToSpeak = false;
    public static boolean raiseToListen = true;
    public static boolean nextMediaTap = true;
    public static boolean recordViaSco = false;
    public static boolean customTabs = true;
    public static boolean inappBrowser = true;
    public static boolean adaptableColorInBrowser = true;
    public static boolean onlyLocalInstantView = false;
    public static boolean directShare = true;
    public static boolean inappCamera = true;
    public static boolean roundCamera16to9 = true;
    public static boolean noSoundHintShowed = false;
    public static boolean streamMedia = true;
    public static boolean streamAllVideo = false;
    public static boolean streamMkv = false;
    public static boolean saveStreamMedia = true;
    public static boolean pauseMusicOnRecord = false;
    public static boolean pauseMusicOnMedia = false;
    public static boolean noiseSupression;
    public static final boolean noStatusBar = true;
    public static boolean debugWebView;
    public static boolean sortContactsByName;
    public static boolean sortFilesByName;
    public static boolean shuffleMusic;
    public static boolean playOrderReversed;
    public static boolean hasCameraCache;
    public static boolean showNotificationsForAllAccounts = true;
    public static boolean debugVideoQualities = false;
    public static int repeatMode;
    public static boolean allowBigEmoji;
    public static boolean useSystemEmoji;
    public static boolean useSystemBoldFont;
    public static int fontSize = 16;
    public static boolean fontSizeIsDefault;
    public static int bubbleRadius = 17;
    public static int ivFontSize = 16;
    public static boolean proxyRotationEnabled;
    public static int proxyRotationTimeout;
    public static int messageSeenHintCount;
    public static int emojiInteractionsHintCount;
    public static int dayNightThemeSwitchHintCount;
    public static int callEncryptionHintDisplayedCount;
    public static boolean shadowsInSections;
    public static boolean debugViewMetrics;
    public static boolean photoHighQualityDefault;
    public static boolean photoLiveDefault;

    public static TLRPC.TL_help_appUpdate pendingAppUpdate;
    public static int pendingAppUpdateBuildVersion;
    public static long lastUpdateCheckTime;

    public static String mgPendingUpdate = null;
    public static long mgLastUpdateCheckTime;
    public static String mgUpdateApkPath = null;
    public static String mgDismissedPendingTag = null;
    // Main tag at which the user dismissed the "Tor plugin outdated"
    // cold-start prompt. Suppresses re-pop while the main install is
    // still at that tag; auto-clears when a fresh main upgrade lands so
    // the next mismatch re-prompts (see MgTorClient.maybePromptPluginUpdate).
    public static String mgDismissedPluginPromptTag = null;

    public static boolean hasEmailLogin;

    @PerformanceClass
    private static int devicePerformanceClass;
    @PerformanceClass
    private static int overrideDevicePerformanceClass;

    public static boolean drawDialogIcons;
    public static boolean useThreeLinesLayout;
    public static boolean archiveHidden;

    private static int chatSwipeAction;

    public static int distanceSystemType;
    public static int mediaColumnsCount = 3;
    public static int storiesColumnsCount = 3;
    public static int fastScrollHintCount = 3;
    public static boolean dontAskManageStorage;
    public static boolean multipleReactionsPromoShowed;

    public static boolean isFloatingDebugActive;
    public static LiteMode liteMode;

    private static final int[] LOW_SOC = {
            -1775228513, // EXYNOS 850
            802464304,  // EXYNOS 7872
            802464333,  // EXYNOS 7880
            802464302,  // EXYNOS 7870
            2067362118, // MSM8953
            2067362060, // MSM8937
            2067362084, // MSM8940
            2067362241, // MSM8992
            2067362117, // MSM8952
            2067361998, // MSM8917
            -1853602818 // SDM439
    };

    static {
        loadConfig();
    }

    public static class ProxyInfo {

        public String address;
        public int port;
        public String username;
        public String password;
        public String secret;

        public long proxyCheckPingId;
        public long ping;
        public boolean checking;
        public boolean available;
        public long availableCheckTime;
        // MG: synthetic entry owned by MgTorController. Never serialized
        // (saveProxyList skips), never user-editable/deletable, never
        // tap-selectable in ProxyListActivity. Exists only so the in-bar
        // proxy-active indicator shows while Tor routes MTProto.
        public boolean mgInternal;

        public ProxyInfo(String address, int port, String username, String password, String secret) {
            this.address = address;
            this.port = port;
            this.username = username;
            this.password = password;
            this.secret = secret;
            if (this.address == null) {
                this.address = "";
            }
            if (this.password == null) {
                this.password = "";
            }
            if (this.username == null) {
                this.username = "";
            }
            if (this.secret == null) {
                this.secret = "";
            }
        }

        public String getLink() {
            StringBuilder url = new StringBuilder(!TextUtils.isEmpty(secret) ? "https://t.me/proxy?" : "https://t.me/socks?");
            try {
                url.append("server=").append(URLEncoder.encode(address, "UTF-8")).append("&").append("port=").append(port);
                if (!TextUtils.isEmpty(username)) {
                    url.append("&user=").append(URLEncoder.encode(username, "UTF-8"));
                }
                if (!TextUtils.isEmpty(password)) {
                    url.append("&pass=").append(URLEncoder.encode(password, "UTF-8"));
                }
                if (!TextUtils.isEmpty(secret)) {
                    url.append("&secret=").append(URLEncoder.encode(secret, "UTF-8"));
                }
            } catch (UnsupportedEncodingException ignored) {}
            return url.toString();
        }
    }

    // MG: CopyOnWriteArrayList rather than ArrayList. The MG Tor plugin
    // mutates this list (publishMgInternalTorProxy / clearMgInternalTorProxy)
    // from MgTorClient's worker thread + the binder callback hop, while
    // ProxyListActivity / DialogsActivity / ProxyRotationController iterate
    // it from the UI / stage thread. ArrayList would throw
    // ConcurrentModificationException on coincident timing; CopyOnWrite
    // gives lock-free snapshot iteration at the cost of an array copy per
    // mutation (negligible — proxyList is typically <10 entries).
    public static List<ProxyInfo> proxyList = new CopyOnWriteArrayList<>();
    private static boolean proxyListLoaded;
    public static ProxyInfo currentProxy;

    public static void saveConfig() {
        synchronized (sync) {
            try {
                SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("userconfing", Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = preferences.edit();
                editor.putBoolean("saveIncomingPhotos", saveIncomingPhotos);
                editor.putString("passcodeHash1", passcodeHash);
                editor.putString("passcodeSalt", passcodeSalt.length > 0 ? Base64.encodeToString(passcodeSalt, Base64.DEFAULT) : "");
                editor.putBoolean("appLocked", appLocked);
                editor.putInt("passcodeType", passcodeType);
                editor.putLong("passcodeRetryInMs", passcodeRetryInMs);
                editor.putLong("lastUptimeMillis", lastUptimeMillis);
                editor.putInt("badPasscodeTries", badPasscodeTries);
                editor.putInt("autoLockIn", autoLockIn);
                editor.putInt("lastPauseTime", lastPauseTime);
                editor.putBoolean("useFingerprint", useFingerprintLock);
                editor.putBoolean("allowScreenCapture", allowScreenCapture);
                editor.putString("pushString2", pushString);
                editor.putString("mg_pushStringSimple", pushStringSimple);
                editor.putInt("pushType", pushType);
                editor.putBoolean("pushStatSent", pushStatSent);
                editor.putString("pushAuthKey", pushAuthKey != null ? Base64.encodeToString(pushAuthKey, Base64.DEFAULT) : "");
                editor.putInt("lastLocalId", lastLocalId);
                editor.putString("passportConfigJson", passportConfigJson);
                editor.putInt("passportConfigHash", passportConfigHash);
                editor.putBoolean("sortContactsByName", sortContactsByName);
                editor.putBoolean("sortFilesByName", sortFilesByName);
                editor.putInt("textSelectionHintShows", textSelectionHintShows);
                editor.putInt("scheduledOrNoSoundHintShows", scheduledOrNoSoundHintShows);
                editor.putLong("scheduledOrNoSoundHintSeenAt", scheduledOrNoSoundHintSeenAt);
                editor.putInt("scheduledHintShows", scheduledHintShows);
                editor.putLong("scheduledHintSeenAt", scheduledHintSeenAt);
                editor.putBoolean("forwardingOptionsHintShown", forwardingOptionsHintShown);
                editor.putBoolean("replyingOptionsHintShown", replyingOptionsHintShown);
                editor.putInt("lockRecordAudioVideoHint", lockRecordAudioVideoHint);
                editor.putString("storageCacheDir", !TextUtils.isEmpty(storageCacheDir) ? storageCacheDir : "");
                editor.putBoolean("proxyRotationEnabled", proxyRotationEnabled);
                editor.putInt("proxyRotationTimeout", proxyRotationTimeout);

                if (pendingAppUpdate != null) {
                    try {
                        SerializedData data = new SerializedData(pendingAppUpdate.getObjectSize());
                        pendingAppUpdate.serializeToStream(data);
                        String str = Base64.encodeToString(data.toByteArray(), Base64.DEFAULT);
                        editor.putString("appUpdate", str);
                        editor.putInt("appUpdateBuild", pendingAppUpdateBuildVersion);
                        data.cleanup();
                    } catch (Exception ignore) {

                    }
                } else {
                    editor.remove("appUpdate");
                }
                editor.putLong("appUpdateCheckTime", lastUpdateCheckTime);

                editor.apply();

                editor = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE).edit();
                editor.putBoolean("hasEmailLogin", hasEmailLogin);
                editor.putBoolean("floatingDebugActive", isFloatingDebugActive);
                editor.putBoolean("record_via_sco", recordViaSco);
                // Mercurygram settings
                editor.putBoolean("mg_disableUnifiedPush", disableUnifiedPush);
                editor.putString("mg_unifiedPushGateway2", unifiedPushGateway);
                editor.putBoolean("mg_disableSecureFlags", disableSecureFlags);
                editor.putBoolean("mg_removeAdsAndProxySponsor", removeAdsAndProxySponsor);
                editor.putBoolean("mg_disableAutoUpdate", disableAutoUpdate);
                editor.putBoolean("mg_acceptPreReleaseUpdates", acceptPreReleaseUpdates);
                editor.putBoolean("mg_reduceTrackingFingerprint", reduceTrackingFingerprint);
                editor.putBoolean("mg_useTor", mg_useTor);
                editor.putInt("mg_torIdleStopMinutes", mg_torIdleStopMinutes);
                editor.putString("mg_translateMode", mg_translateMode);
                editor.putBoolean("mg_translateAutoFallback", mg_translateAutoFallback);
                editor.putBoolean("mg_translateOfflineFormatToastShown", mg_translateOfflineFormatToastShown);
                editor.putString("mg_translateAltEngine", mg_translateAltEngine);
                editor.putString("mg_translateAltInstanceMode", mg_translateAltInstanceMode);
                editor.putString("mg_translateAltPinnedInstance", mg_translateAltPinnedInstance);
                editor.putString("mg_translateAltCustomInstance", mg_translateAltCustomInstance);
                editor.putBoolean("mg_transcribeOffline", mg_transcribeOffline);
                editor.putString("mg_transcribeModel", mg_transcribeModel);
                editor.putString("mg_transcribeLang", mg_transcribeLang);
                editor.putBoolean("mg_transcribeVad", mg_transcribeVad);
                editor.putString("mg_webPushPrivateKey", webPushPrivateKey != null ? Base64.encodeToString(webPushPrivateKey, Base64.DEFAULT) : "");
                editor.putString("mg_webPushPublicKey", webPushPublicKey != null ? Base64.encodeToString(webPushPublicKey, Base64.DEFAULT) : "");
                editor.putString("mg_webPushAuthSecret", webPushAuthSecret != null ? Base64.encodeToString(webPushAuthSecret, Base64.DEFAULT) : "");
                if (mgPendingUpdate != null) {
                    editor.putString("mg_pendingUpdate", mgPendingUpdate);
                } else {
                    editor.remove("mg_pendingUpdate");
                }
                editor.putLong("mg_lastUpdateCheckTime", mgLastUpdateCheckTime);
                if (mgUpdateApkPath != null) {
                    editor.putString("mg_updateApkPath", mgUpdateApkPath);
                } else {
                    editor.remove("mg_updateApkPath");
                }
                if (mgDismissedPendingTag != null) {
                    editor.putString("mg_dismissedPendingTag", mgDismissedPendingTag);
                } else {
                    editor.remove("mg_dismissedPendingTag");
                }
                if (mgDismissedPluginPromptTag != null) {
                    editor.putString("mg_dismissedPluginPromptTag", mgDismissedPluginPromptTag);
                } else {
                    editor.remove("mg_dismissedPluginPromptTag");
                }
                editor.putString("mg_unifiedPushEndpointUrl", unifiedPushEndpointUrl);
                editor.apply();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    public static int getLastLocalId() {
        int value;
        synchronized (localIdSync) {
            value = lastLocalId--;
        }
        return value;
    }

    public static void loadConfig() {
        synchronized (sync) {
            if (configLoaded || ApplicationLoader.applicationContext == null) {
                return;
            }

            BackgroundActivityPrefs.prefs = ApplicationLoader.applicationContext.getSharedPreferences("background_activity", Context.MODE_PRIVATE);

            SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("userconfing", Context.MODE_PRIVATE);
            saveIncomingPhotos = preferences.getBoolean("saveIncomingPhotos", false);
            passcodeHash = preferences.getString("passcodeHash1", "");
            appLocked = preferences.getBoolean("appLocked", false);
            passcodeType = preferences.getInt("passcodeType", 0);
            passcodeRetryInMs = preferences.getLong("passcodeRetryInMs", 0);
            lastUptimeMillis = preferences.getLong("lastUptimeMillis", 0);
            badPasscodeTries = preferences.getInt("badPasscodeTries", 0);
            autoLockIn = preferences.getInt("autoLockIn", 60 * 60);
            lastPauseTime = preferences.getInt("lastPauseTime", 0);
            useFingerprintLock = preferences.getBoolean("useFingerprint", true);
            allowScreenCapture = preferences.getBoolean("allowScreenCapture", false);
            lastLocalId = preferences.getInt("lastLocalId", -210000);
            pushString = preferences.getString("pushString2", "");
            pushStringSimple = preferences.getString("mg_pushStringSimple", "");
            pushType = preferences.getInt("pushType", PushListenerController.PUSH_TYPE_FIREBASE);
            pushStatSent = preferences.getBoolean("pushStatSent", false);
            passportConfigJson = preferences.getString("passportConfigJson", "");
            passportConfigHash = preferences.getInt("passportConfigHash", 0);
            storageCacheDir = preferences.getString("storageCacheDir", null);
            proxyRotationEnabled = preferences.getBoolean("proxyRotationEnabled", false);
            proxyRotationTimeout = preferences.getInt("proxyRotationTimeout", ProxyRotationController.DEFAULT_TIMEOUT_INDEX);
            String authKeyString = preferences.getString("pushAuthKey", null);
            if (!TextUtils.isEmpty(authKeyString)) {
                pushAuthKey = Base64.decode(authKeyString, Base64.DEFAULT);
            }

            if (passcodeHash.length() > 0 && lastPauseTime == 0) {
                lastPauseTime = (int) (SystemClock.elapsedRealtime() / 1000 - 60 * 10);
            }

            String passcodeSaltString = preferences.getString("passcodeSalt", "");
            if (passcodeSaltString.length() > 0) {
                passcodeSalt = Base64.decode(passcodeSaltString, Base64.DEFAULT);
            } else {
                passcodeSalt = new byte[0];
            }
            lastUpdateCheckTime = preferences.getLong("appUpdateCheckTime", System.currentTimeMillis());
            try {
                String update = preferences.getString("appUpdate", null);
                if (update != null) {
                    pendingAppUpdateBuildVersion = preferences.getInt("appUpdateBuild", buildVersion());
                    byte[] arr = Base64.decode(update, Base64.DEFAULT);
                    if (arr != null) {
                        SerializedData data = new SerializedData(arr);
                        pendingAppUpdate = (TLRPC.TL_help_appUpdate) TLRPC.help_AppUpdate.TLdeserialize(data, data.readInt32(false), false);
                        data.cleanup();
                    }
                }
                if (pendingAppUpdate != null) {
                    long updateTime = 0;
                    int updateVersion = 0;
                    String updateVersionString = null;
                    try {
                        PackageInfo packageInfo = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
                        updateVersion = packageInfo.versionCode;
                        updateVersionString = packageInfo.versionName;
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    if (updateVersion == 0) {
                        updateVersion = buildVersion();
                    }
                    if (updateVersionString == null) {
                        updateVersionString = BuildVars.BUILD_VERSION_STRING;
                    }
                    if (pendingAppUpdateBuildVersion != updateVersion || pendingAppUpdate.version == null || updateVersionString.compareTo(pendingAppUpdate.version) >= 0 || BuildVars.DEBUG_PRIVATE_VERSION) {
                        pendingAppUpdate = null;
                        AndroidUtilities.runOnUIThread(SharedConfig::saveConfig);
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }

            preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
            SaveToGallerySettingsHelper.load(preferences);
            mapPreviewType = preferences.getInt("mapPreviewType", 2);
            searchEngineType = preferences.getInt("searchEngineType", 0);
            raiseToListen = preferences.getBoolean("raise_to_listen", true);
            raiseToSpeak = preferences.getBoolean("raise_to_speak", false);
            nextMediaTap = preferences.getBoolean("next_media_on_tap", true);
            recordViaSco = preferences.getBoolean("record_via_sco", false);
            customTabs = preferences.getBoolean("custom_tabs", true);
            inappBrowser = preferences.getBoolean("inapp_browser", false);
            adaptableColorInBrowser = preferences.getBoolean("adaptableBrowser", false);
            onlyLocalInstantView = preferences.getBoolean("onlyLocalInstantView", BuildVars.DEBUG_PRIVATE_VERSION);
            directShare = preferences.getBoolean("direct_share", true);
            shuffleMusic = preferences.getBoolean("shuffleMusic", false);
            playOrderReversed = !shuffleMusic && preferences.getBoolean("playOrderReversed", false);
            inappCamera = preferences.getBoolean("inappCamera", true);
            hasCameraCache = preferences.contains("cameraCache");
            roundCamera16to9 = true;
            repeatMode = preferences.getInt("repeatMode", 0);
            fontSize = preferences.getInt("fons_size", AndroidUtilities.isTablet() ? 18 : 16);
            fontSizeIsDefault = !preferences.contains("fons_size");
            bubbleRadius = preferences.getInt("bubbleRadius", 17);
            ivFontSize = preferences.getInt("iv_font_size", fontSize);
            allowBigEmoji = preferences.getBoolean("allowBigEmoji", true);
            useSystemEmoji = preferences.getBoolean("useSystemEmoji", false);
            useSystemBoldFont = preferences.getBoolean("useSystemBoldFont", false);
            forceForumTabs = preferences.getBoolean("forceForumTabs", false);
            fastWallpaperDisabled = preferences.getBoolean("fastWallpaperDisabled", false);
            frameMetricsEnabled = preferences.getBoolean("frameMetricsEnabled", false);
            if (useSystemBoldFont) {
                AndroidUtilities.mediumTypeface = null;
            }
            streamMedia = preferences.getBoolean("streamMedia", true);
            saveStreamMedia = preferences.getBoolean("saveStreamMedia", true);
            pauseMusicOnRecord = preferences.getBoolean("pauseMusicOnRecord", true);
            pauseMusicOnMedia = preferences.getBoolean("pauseMusicOnMedia", false);
            forceDisableTabletMode = preferences.getBoolean("forceDisableTabletMode", false);
            streamAllVideo = preferences.getBoolean("streamAllVideo", BuildVars.DEBUG_VERSION);
            streamMkv = preferences.getBoolean("streamMkv", false);
            suggestStickers = preferences.getInt("suggestStickers", 0);
            suggestAnimatedEmoji = preferences.getBoolean("suggestAnimatedEmoji", true);
            overrideDevicePerformanceClass = preferences.getInt("overrideDevicePerformanceClass", -1);
            devicePerformanceClass = preferences.getInt("devicePerformanceClass", -1);
            sortContactsByName = preferences.getBoolean("sortContactsByName", false);
            sortFilesByName = preferences.getBoolean("sortFilesByName", false);
            noSoundHintShowed = preferences.getBoolean("noSoundHintShowed", false);
            directShareHash = preferences.getString("directShareHash2", null);
            useThreeLinesLayout = preferences.getBoolean("useThreeLinesLayout", false);
            archiveHidden = preferences.getBoolean("archiveHidden", false);
            distanceSystemType = preferences.getInt("distanceSystemType", 0);
            keepMedia = preferences.getInt("keep_media", CacheByChatsController.KEEP_MEDIA_ONE_MONTH);
            debugWebView = preferences.getBoolean("debugWebView", false);
            lastKeepMediaCheckTime = preferences.getInt("lastKeepMediaCheckTime", 0);
            lastLogsCheckTime = preferences.getInt("lastLogsCheckTime", 0);
            searchMessagesAsListUsed = preferences.getBoolean("searchMessagesAsListUsed", false);
            stickersReorderingHintUsed = preferences.getBoolean("stickersReorderingHintUsed", false);
            storyReactionsLongPressHint = preferences.getBoolean("storyReactionsLongPressHint", false);
            storiesIntroShown = preferences.getBoolean("storiesIntroShown", false);
            textSelectionHintShows = preferences.getInt("textSelectionHintShows", 0);
            scheduledOrNoSoundHintShows = preferences.getInt("scheduledOrNoSoundHintShows", 0);
            scheduledOrNoSoundHintSeenAt = preferences.getLong("scheduledOrNoSoundHintSeenAt", 0);
            scheduledHintShows = preferences.getInt("scheduledHintShows", 0);
            scheduledHintSeenAt = preferences.getLong("scheduledHintSeenAt", 0);
            forwardingOptionsHintShown = preferences.getBoolean("forwardingOptionsHintShown", false);
            replyingOptionsHintShown = preferences.getBoolean("replyingOptionsHintShown", false);
            lockRecordAudioVideoHint = preferences.getInt("lockRecordAudioVideoHint", 0);
            disableVoiceAudioEffects = preferences.getBoolean("disableVoiceAudioEffects", false);
            noiseSupression = preferences.getBoolean("noiseSupression", false);
            chatSwipeAction = preferences.getInt("ChatSwipeAction", -1);
            messageSeenHintCount = preferences.getInt("messageSeenCount", 3);
            emojiInteractionsHintCount = preferences.getInt("emojiInteractionsHintCount", 3);
            dayNightThemeSwitchHintCount = preferences.getInt("dayNightThemeSwitchHintCount", 3);
            stealthModeSendMessageConfirm = preferences.getInt("stealthModeSendMessageConfirm", 2);
            mediaColumnsCount = preferences.getInt("mediaColumnsCount", 3);
            storiesColumnsCount = preferences.getInt("storiesColumnsCount", 3);
            fastScrollHintCount = preferences.getInt("fastScrollHintCount", 3);
            dontAskManageStorage = preferences.getBoolean("dontAskManageStorage", false);
            hasEmailLogin = preferences.getBoolean("hasEmailLogin", false);
            isFloatingDebugActive = preferences.getBoolean("floatingDebugActive", false);
            updateStickersOrderOnSend = preferences.getBoolean("updateStickersOrderOnSend", true);
            dayNightWallpaperSwitchHint = preferences.getInt("dayNightWallpaperSwitchHint", 0);
            bigCameraForRound = preferences.getBoolean("bigCameraForRound", false);
            useNewBlur = preferences.getBoolean("useNewBlur", true);
            useCamera2Force = !preferences.contains("useCamera2Force_2") ? null : preferences.getBoolean("useCamera2Force_2", false);
            useSurfaceInStories = preferences.getBoolean("useSurfaceInStories", Build.VERSION.SDK_INT >= 30);
            payByInvoice = preferences.getBoolean("payByInvoice", false);
            photoViewerBlur = preferences.getBoolean("photoViewerBlur", true);
            multipleReactionsPromoShowed = preferences.getBoolean("multipleReactionsPromoShowed", false);
            callEncryptionHintDisplayedCount = preferences.getInt("callEncryptionHintDisplayedCount", 0);
            debugVideoQualities = preferences.getBoolean("debugVideoQualities", false);
            shadowsInSections = preferences.getBoolean("shadowsInSections", false);
            debugViewMetrics = preferences.getBoolean("debugViewMetrics", false);
            photoHighQualityDefault = preferences.getBoolean("photoHighQualityDefault", false);
            photoLiveDefault = preferences.getBoolean("photoLiveDefault", false);

            // Mercurygram settings
            disableUnifiedPush = preferences.getBoolean("mg_disableUnifiedPush", false);
            unifiedPushGateway = preferences.getString("mg_unifiedPushGateway2", unifiedPushGateway);
            disableSecureFlags = preferences.getBoolean("mg_disableSecureFlags", false);
            removeAdsAndProxySponsor = preferences.getBoolean("mg_removeAdsAndProxySponsor", false);
            disableAutoUpdate = preferences.getBoolean("mg_disableAutoUpdate", false);
            acceptPreReleaseUpdates = preferences.getBoolean("mg_acceptPreReleaseUpdates", false);
            useSystemFont = preferences.getBoolean("mg_useSystemFont", false);
            reduceTrackingFingerprint = preferences.getBoolean("mg_reduceTrackingFingerprint", false);
            mg_useTor = preferences.getBoolean("mg_useTor", false);
            mg_torIdleStopMinutes = preferences.getInt("mg_torIdleStopMinutes", 5);
            mg_hideStories = preferences.getBoolean("mg_hideStories", false);
            mg_translateMode = sanitizeMgTranslateMode(preferences.getString("mg_translateMode", MG_TRANSLATE_MODE_DEFAULT));
            mg_translateAutoFallback = preferences.getBoolean("mg_translateAutoFallback", true);
            mg_translateOfflineFormatToastShown = preferences.getBoolean("mg_translateOfflineFormatToastShown", false);
            mg_translateAltEngine = sanitizeMgTranslateAltEngine(preferences.getString("mg_translateAltEngine", MG_TRANSLATE_ALT_ENGINE_DUCKDUCKGO));
            mg_translateAltInstanceMode = sanitizeMgTranslateAltInstanceMode(preferences.getString("mg_translateAltInstanceMode", MG_TRANSLATE_ALT_INSTANCE_MODE_AUTO));
            mg_translateAltPinnedInstance = preferences.getString("mg_translateAltPinnedInstance", "");
            mg_translateAltCustomInstance = preferences.getString("mg_translateAltCustomInstance", "");
            mg_transcribeOffline = preferences.getBoolean("mg_transcribeOffline", false);
            mg_transcribeModel = preferences.getString("mg_transcribeModel", "tiny-q8_0");
            mg_transcribeLang = preferences.getString("mg_transcribeLang", MG_TRANSCRIBE_LANG_DEVICE);
            mg_transcribeVad = preferences.getBoolean("mg_transcribeVad", true);
            migratePerAccountSettingsV1(preferences);
            String wpPriv = preferences.getString("mg_webPushPrivateKey", "");
            if (!TextUtils.isEmpty(wpPriv)) webPushPrivateKey = Base64.decode(wpPriv, Base64.DEFAULT);
            String wpPub = preferences.getString("mg_webPushPublicKey", "");
            if (!TextUtils.isEmpty(wpPub)) webPushPublicKey = Base64.decode(wpPub, Base64.DEFAULT);
            String wpAuth = preferences.getString("mg_webPushAuthSecret", "");
            if (!TextUtils.isEmpty(wpAuth)) webPushAuthSecret = Base64.decode(wpAuth, Base64.DEFAULT);
            unifiedPushEndpointUrl = preferences.getString("mg_unifiedPushEndpointUrl", "");
            mgPendingUpdate = preferences.getString("mg_pendingUpdate", null);
            mgLastUpdateCheckTime = preferences.getLong("mg_lastUpdateCheckTime", 0);
            mgUpdateApkPath = preferences.getString("mg_updateApkPath", null);
            mgDismissedPendingTag = preferences.getString("mg_dismissedPendingTag", null);
            mgDismissedPluginPromptTag = preferences.getString("mg_dismissedPluginPromptTag", null);
            if (mgPendingUpdate != null) {
                try {
                    it.belloworld.mercurygram.MgUpdateInfo info = it.belloworld.mercurygram.MgUpdateInfo.fromJson(mgPendingUpdate);
                    if (info != null) {
                        String currentVersion = it.belloworld.mercurygram.MgUpdateChecker.currentInstallVersion();
                        if (currentVersion != null && versionBiggerOrEqual(currentVersion, info.versionName)) {
                            clearMgPendingUpdate();
                        }
                    }
                } catch (Exception ignore) {}
            }

            loadDebugConfig(preferences);

            preferences = ApplicationLoader.applicationContext.getSharedPreferences("Notifications", Activity.MODE_PRIVATE);
            showNotificationsForAllAccounts = preferences.getBoolean("AllAccounts", true);

            configLoaded = true;
        }
    }

    public static int buildVersion() {
        try {
            return ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            FileLog.e(e);
            return 0;
        }
    }

    // Copy formerly-global MG toggles into every account's UserConfig prefs on
    // first launch after the per-account split. Preserves prior behavior exactly
    // (each account inherits the old shared value). Runs once.
    private static void migratePerAccountSettingsV1(SharedPreferences mainconfig) {
        if (mainconfig.getBoolean("mg_perAccountMigrationV1Done", false)) {
            return;
        }
        boolean messageDetailsMenu = mainconfig.getBoolean("mg_messageDetailsMenu", false);
        boolean savedMessagesHistory = mainconfig.getBoolean("mg_savedMessagesHistory", false);
        boolean disableLivePhotosByDefault = mainconfig.getBoolean("mg_disableLivePhotosByDefault", false);
        boolean hideChatKeyboard = mainconfig.getBoolean("hide_chat_keyboard", false);
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            String name = a == 0 ? "userconfing" : "userconfig" + a;
            ApplicationLoader.applicationContext.getSharedPreferences(name, Activity.MODE_PRIVATE)
                    .edit()
                    .putBoolean("messageDetailsMenu", messageDetailsMenu)
                    .putBoolean("savedMessagesHistory", savedMessagesHistory)
                    .putBoolean("disableLivePhotosByDefault", disableLivePhotosByDefault)
                    .putBoolean("hideChatKeyboard", hideChatKeyboard)
                    .apply();
        }
        mainconfig.edit()
                .remove("mg_messageDetailsMenu")
                .remove("mg_savedMessagesHistory")
                .remove("mg_disableLivePhotosByDefault")
                .remove("hide_chat_keyboard")
                .putBoolean("mg_perAccountMigrationV1Done", true)
                .apply();
    }

    public static void updateTabletConfig() {
        if (fontSizeIsDefault) {
            SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
            fontSize = preferences.getInt("fons_size", AndroidUtilities.isTablet() ? 18 : 16);
            ivFontSize = preferences.getInt("iv_font_size", fontSize);
        }
    }

    public static void increaseBadPasscodeTries() {
        badPasscodeTries++;
        if (badPasscodeTries >= 3) {
            switch (badPasscodeTries) {
                case 3:
                    passcodeRetryInMs = 5000;
                    break;
                case 4:
                    passcodeRetryInMs = 10000;
                    break;
                case 5:
                    passcodeRetryInMs = 15000;
                    break;
                case 6:
                    passcodeRetryInMs = 20000;
                    break;
                case 7:
                    passcodeRetryInMs = 25000;
                    break;
                default:
                    passcodeRetryInMs = 30000;
                    break;
            }
            lastUptimeMillis = SystemClock.elapsedRealtime();
        }
        saveConfig();
    }

    public static boolean isAutoplayVideo() {
        return LiteMode.isEnabled(LiteMode.FLAG_AUTOPLAY_VIDEOS);
    }

    public static boolean isAutoplayGifs() {
        return LiteMode.isEnabled(LiteMode.FLAG_AUTOPLAY_GIFS);
    }

    public static boolean isPassportConfigLoaded() {
        return passportConfigMap != null;
    }

    public static void setPassportConfig(String json, int hash) {
        passportConfigMap = null;
        passportConfigJson = json;
        passportConfigHash = hash;
        saveConfig();
        getCountryLangs();
    }

    public static HashMap<String, String> getCountryLangs() {
        if (passportConfigMap == null) {
            passportConfigMap = new HashMap<>();
            try {
                JSONObject object = new JSONObject(passportConfigJson);
                Iterator<String> iter = object.keys();
                while (iter.hasNext()) {
                    String key = iter.next();
                    passportConfigMap.put(key.toUpperCase(), object.getString(key).toUpperCase());
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        return passportConfigMap;
    }

    public static boolean isAppUpdateAvailable() {
        if (pendingAppUpdate == null || pendingAppUpdate.document == null || !ApplicationLoader.isStandaloneBuild()) {
            return false;
        }
        int currentVersion;
        try {
            PackageInfo pInfo = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            currentVersion = pInfo.versionCode;
        } catch (Exception e) {
            FileLog.e(e);
            currentVersion = buildVersion();
        }
        return pendingAppUpdateBuildVersion == currentVersion;
    }

    public static boolean setNewAppVersionAvailable(TLRPC.TL_help_appUpdate update) {
        String updateVersionString = null;
        int versionCode = 0;
        try {
            PackageInfo packageInfo = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            versionCode = packageInfo.versionCode;
            updateVersionString = packageInfo.versionName;
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (versionCode == 0) {
            versionCode = buildVersion();
        }
        if (updateVersionString == null) {
            updateVersionString = BuildVars.BUILD_VERSION_STRING;
        }
        if (update.version == null || versionBiggerOrEqual(updateVersionString, update.version)) {
            return false;
        }
        pendingAppUpdate = update;
        pendingAppUpdateBuildVersion = versionCode;
        saveConfig();
        return true;
    }

    public static boolean isMgUpdateAvailable() {
        return mgPendingUpdate != null && !it.belloworld.mercurygram.MgUpdateChecker.isFdroidBuild();
    }

    public static it.belloworld.mercurygram.MgUpdateInfo getMgPendingUpdate() {
        return it.belloworld.mercurygram.MgUpdateInfo.fromJson(mgPendingUpdate);
    }

    public static void setMgPendingUpdate(it.belloworld.mercurygram.MgUpdateInfo info) {
        if (info == null) {
            clearMgPendingUpdate();
            return;
        }
        // Tag change → the previously downloaded APK is stale; drop it so
        // the next download replaces it cleanly instead of the side-menu
        // "Install" shortcut firing the old cached APK from disk.
        it.belloworld.mercurygram.MgUpdateInfo prev = getMgPendingUpdate();
        if (prev != null && !info.tagName.equals(prev.tagName)) {
            deleteCachedUpdateApk();
        }
        mgPendingUpdate = info.toJson();
        // A different tag arrived — the user must be re-prompted.
        if (!info.tagName.equals(mgDismissedPendingTag)) {
            mgDismissedPendingTag = null;
        }
        saveConfig();
    }

    public static void setMgDismissedPendingTag(String tag) {
        mgDismissedPendingTag = tag;
        saveConfig();
    }

    public static void setMgDismissedPluginPromptTag(String tag) {
        mgDismissedPluginPromptTag = tag;
        saveConfig();
    }

    public static void clearMgPendingUpdate() {
        deleteCachedUpdateApk();
        mgPendingUpdate = null;
        mgDismissedPendingTag = null;
        saveConfig();
    }

    private static void deleteCachedUpdateApk() {
        if (mgUpdateApkPath != null) {
            try { new java.io.File(mgUpdateApkPath).delete(); } catch (Exception ignore) {}
            mgUpdateApkPath = null;
        }
    }

    // returns a >= b. Missing trailing parts are treated as 0 so
    // "12.7.3.0" < "12.7.3.0.1" and "12.7.3.0" == "12.7.3.0.0".
    public static boolean versionBiggerOrEqual(String a, String b) {
        String[] partsA = a.split("\\.");
        String[] partsB = b.split("\\.");
        int len = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < len; i++) {
            int numA = i < partsA.length ? Integer.parseInt(partsA[i]) : 0;
            int numB = i < partsB.length ? Integer.parseInt(partsB[i]) : 0;
            if (numA != numB) return numA > numB;
        }
        return true;
    }

    public static boolean checkPasscode(String passcode) {
        if (passcodeSalt.length == 0) {
            boolean result = Utilities.MD5(passcode).equals(passcodeHash);
            if (result) {
                try {
                    passcodeSalt = new byte[16];
                    Utilities.random.nextBytes(passcodeSalt);
                    byte[] passcodeBytes = passcode.getBytes("UTF-8");
                    byte[] bytes = new byte[32 + passcodeBytes.length];
                    System.arraycopy(passcodeSalt, 0, bytes, 0, 16);
                    System.arraycopy(passcodeBytes, 0, bytes, 16, passcodeBytes.length);
                    System.arraycopy(passcodeSalt, 0, bytes, passcodeBytes.length + 16, 16);
                    passcodeHash = Utilities.bytesToHex(Utilities.computeSHA256(bytes, 0, bytes.length));
                    saveConfig();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            return result;
        } else {
            try {
                byte[] passcodeBytes = passcode.getBytes("UTF-8");
                byte[] bytes = new byte[32 + passcodeBytes.length];
                System.arraycopy(passcodeSalt, 0, bytes, 0, 16);
                System.arraycopy(passcodeBytes, 0, bytes, 16, passcodeBytes.length);
                System.arraycopy(passcodeSalt, 0, bytes, passcodeBytes.length + 16, 16);
                String hash = Utilities.bytesToHex(Utilities.computeSHA256(bytes, 0, bytes.length));
                return passcodeHash.equals(hash);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return false;
    }

    public static void clearConfig() {
        saveIncomingPhotos = false;
        appLocked = false;
        passcodeType = PASSCODE_TYPE_PIN;
        passcodeRetryInMs = 0;
        lastUptimeMillis = 0;
        badPasscodeTries = 0;
        passcodeHash = "";
        passcodeSalt = new byte[0];
        autoLockIn = 60 * 60;
        lastPauseTime = 0;
        useFingerprintLock = true;
        isWaitingForPasscodeEnter = false;
        allowScreenCapture = false;
        textSelectionHintShows = 0;
        scheduledOrNoSoundHintShows = 0;
        scheduledOrNoSoundHintSeenAt = 0;
        scheduledHintShows = 0;
        scheduledHintSeenAt = 0;
        lockRecordAudioVideoHint = 0;
        forwardingOptionsHintShown = false;
        replyingOptionsHintShown = false;
        messageSeenHintCount = 3;
        emojiInteractionsHintCount = 3;
        dayNightThemeSwitchHintCount = 3;
        stealthModeSendMessageConfirm = 2;
        dayNightWallpaperSwitchHint = 0;
        saveConfig();
    }

    public static void setMultipleReactionsPromoShowed(boolean val) {
        multipleReactionsPromoShowed = val;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("multipleReactionsPromoShowed", multipleReactionsPromoShowed);
        editor.apply();
    }

    public static void setSuggestStickers(int type) {
        suggestStickers = type;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("suggestStickers", suggestStickers);
        editor.apply();
    }

    public static void setSearchMessagesAsListUsed(boolean value) {
        searchMessagesAsListUsed = value;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("searchMessagesAsListUsed", searchMessagesAsListUsed);
        editor.apply();
    }

    public static void setStickersReorderingHintUsed(boolean value) {
        stickersReorderingHintUsed = value;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("stickersReorderingHintUsed", stickersReorderingHintUsed);
        editor.apply();
    }

    public static void setStoriesReactionsLongPressHintUsed(boolean value) {
        storyReactionsLongPressHint = value;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("storyReactionsLongPressHint", storyReactionsLongPressHint);
        editor.apply();
    }

    public static void setStoriesIntroShown(boolean isShown) {
        storiesIntroShown = isShown;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("storiesIntroShown", storiesIntroShown);
        editor.apply();
    }

    public static void increaseTextSelectionHintShowed() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("textSelectionHintShows", ++textSelectionHintShows);
        editor.apply();
    }

    public static void increaseDayNightWallpaperSiwtchHint() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("dayNightWallpaperSwitchHint", ++dayNightWallpaperSwitchHint);
        editor.apply();
    }

    public static void removeTextSelectionHint() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("textSelectionHintShows", 3);
        editor.apply();
    }

    public static void increaseScheduledOrNoSoundHintShowed() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        scheduledOrNoSoundHintSeenAt = System.currentTimeMillis();
        editor.putInt("scheduledOrNoSoundHintShows", ++scheduledOrNoSoundHintShows);
        editor.putLong("scheduledOrNoSoundHintSeenAt", scheduledOrNoSoundHintSeenAt);
        editor.apply();
    }

    public static void increaseScheduledHintShowed() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        scheduledHintSeenAt = System.currentTimeMillis();
        editor.putInt("scheduledHintShows", ++scheduledHintShows);
        editor.putLong("scheduledHintSeenAt", scheduledHintSeenAt);
        editor.apply();
    }

    public static void forwardingOptionsHintHintShowed() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        forwardingOptionsHintShown = true;
        editor.putBoolean("forwardingOptionsHintShown", forwardingOptionsHintShown);
        editor.apply();
    }

    public static void replyingOptionsHintHintShowed() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        replyingOptionsHintShown = true;
        editor.putBoolean("replyingOptionsHintShown", replyingOptionsHintShown);
        editor.apply();
    }

    public static void removeScheduledOrNoSoundHint() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("scheduledOrNoSoundHintShows", 3);
        editor.apply();
    }

    public static void removeScheduledHint() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("scheduledHintShows", 3);
        editor.apply();
    }

    public static void increaseLockRecordAudioVideoHintShowed() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("lockRecordAudioVideoHint", ++lockRecordAudioVideoHint);
        editor.apply();
    }

    public static void removeLockRecordAudioVideoHint() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("lockRecordAudioVideoHint", 3);
        editor.apply();
    }

    public static void setKeepMedia(int value) {
        keepMedia = value;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("keep_media", keepMedia);
        editor.apply();
    }

    public static void toggleUpdateStickersOrderOnSend() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("updateStickersOrderOnSend", updateStickersOrderOnSend = !updateStickersOrderOnSend);
        editor.apply();
    }

    public static void checkLogsToDelete() {
        if (!BuildVars.LOGS_ENABLED) {
            return;
        }
        int time = (int) (System.currentTimeMillis() / 1000);
        if (Math.abs(time - lastLogsCheckTime) < 60 * 60) {
            return;
        }
        lastLogsCheckTime = time;
        Utilities.cacheClearQueue.postRunnable(() -> {
            long currentTime = time - 60 * 60 * 24 * 10;
            try {
                File dir = AndroidUtilities.getLogsDir();
                if (dir == null) {
                    return;
                }
                Utilities.clearDir(dir.getAbsolutePath(), 0, currentTime, false);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            SharedPreferences preferences = MessagesController.getGlobalMainSettings();
            SharedPreferences.Editor editor = preferences.edit();
            editor.putInt("lastLogsCheckTime", lastLogsCheckTime);
            editor.apply();
        });
    }

    public static void toggleDisableVoiceAudioEffects() {
        disableVoiceAudioEffects = !disableVoiceAudioEffects;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("disableVoiceAudioEffects", disableVoiceAudioEffects);
        editor.apply();
    }

    public static void toggleNoiseSupression() {
        noiseSupression = !noiseSupression;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("noiseSupression", noiseSupression);
        editor.apply();
    }

    public static void toggleDebugWebView() {
        debugWebView = !debugWebView;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(debugWebView);
        }
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("debugWebView", debugWebView);
        editor.apply();
    }

    public static void incrementCallEncryptionHintDisplayed(int count) {
        callEncryptionHintDisplayedCount += count;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("callEncryptionHintDisplayedCount", callEncryptionHintDisplayedCount);
        editor.apply();
    }

    public static void toggleLoopStickers() {
        LiteMode.toggleFlag(LiteMode.FLAG_ANIMATED_STICKERS_CHAT);
    }

    public static void toggleBigEmoji() {
        allowBigEmoji = !allowBigEmoji;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("allowBigEmoji", allowBigEmoji);
        editor.apply();
    }

    public static void toggleUseSystemBoldFont() {
        useSystemBoldFont = !useSystemBoldFont;
        AndroidUtilities.mediumTypeface = null;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("useSystemBoldFont", useSystemBoldFont);
        editor.apply();
    }

    public static void toggleForceForumTabs() {
        forceForumTabs = !forceForumTabs;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("forceForumTabs", forceForumTabs);
        editor.apply();
    }

    public static void toggleFastWallpaperDisabled() {
        fastWallpaperDisabled = !fastWallpaperDisabled;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("fastWallpaperDisabled", fastWallpaperDisabled);
        editor.apply();
    }

    public static void toggleFrameMetricsEnabled() {
        frameMetricsEnabled = !frameMetricsEnabled;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("frameMetricsEnabled", frameMetricsEnabled);
        editor.apply();
    }

    public static void toggleSuggestAnimatedEmoji() {
        suggestAnimatedEmoji = !suggestAnimatedEmoji;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("suggestAnimatedEmoji", suggestAnimatedEmoji);
        editor.apply();
    }

    public static void setPlaybackOrderType(int type) {
        if (type == 2) {
            shuffleMusic = true;
            playOrderReversed = false;
        } else if (type == 1) {
            playOrderReversed = true;
            shuffleMusic = false;
        } else {
            playOrderReversed = false;
            shuffleMusic = false;
        }
        MediaController.getInstance().checkIsNextMediaFileDownloaded();
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("shuffleMusic", shuffleMusic);
        editor.putBoolean("playOrderReversed", playOrderReversed);
        editor.apply();
    }

    public static void setRepeatMode(int mode) {
        repeatMode = mode;
        if (repeatMode < 0 || repeatMode > 2) {
            repeatMode = 0;
        }
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("repeatMode", repeatMode);
        editor.apply();
    }

    public static void overrideDevicePerformanceClass(int performanceClass) {
        MessagesController.getGlobalMainSettings().edit().putInt("overrideDevicePerformanceClass", overrideDevicePerformanceClass = performanceClass).remove("lite_mode").apply();
        if (liteMode != null) {
            liteMode.loadPreference();
        }
    }

    public static void toggleAutoplayGifs() {
        LiteMode.toggleFlag(LiteMode.FLAG_AUTOPLAY_GIFS);
    }

    public static void setUseThreeLinesLayout(boolean value) {
        useThreeLinesLayout = value;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("useThreeLinesLayout", useThreeLinesLayout);
        editor.apply();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.dialogsNeedReload, true);
    }

    public static void toggleArchiveHidden() {
        archiveHidden = !archiveHidden;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("archiveHidden", archiveHidden);
        editor.apply();
    }

    public static void toggleAutoplayVideo() {
        LiteMode.toggleFlag(LiteMode.FLAG_AUTOPLAY_VIDEOS);
    }

    public static boolean isSecretMapPreviewSet() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        return preferences.contains("mapPreviewType");
    }

    public static void setSecretMapPreviewType(int value) {
        mapPreviewType = value;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("mapPreviewType", mapPreviewType);
        editor.apply();
    }

    public static void setSearchEngineType(int value) {
        searchEngineType = value;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("searchEngineType", searchEngineType);
        editor.apply();
    }

    public static void setNoSoundHintShowed(boolean value) {
        if (noSoundHintShowed == value) {
            return;
        }
        noSoundHintShowed = value;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("noSoundHintShowed", noSoundHintShowed);
        editor.apply();
    }

    public static void toggleRaiseToSpeak() {
        raiseToSpeak = !raiseToSpeak;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("raise_to_speak", raiseToSpeak);
        editor.apply();
    }

    public static void toggleRaiseToListen() {
        raiseToListen = !raiseToListen;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("raise_to_listen", raiseToListen);
        editor.apply();
    }

    public static void toggleNextMediaTap() {
        nextMediaTap = !nextMediaTap;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("next_media_on_tap", nextMediaTap);
        editor.apply();
    }

    public static boolean enabledRaiseTo(boolean speak) {
        return raiseToListen && (!speak || raiseToSpeak);
    }

    public static void toggleCustomTabs(boolean newValue) {
        customTabs = newValue;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("custom_tabs", customTabs);
        editor.apply();
    }

    public static void toggleInappBrowser() {
        inappBrowser = !inappBrowser;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("inapp_browser", inappBrowser);
        editor.apply();
    }

    public static void toggleBrowserAdaptableColors() {
        adaptableColorInBrowser = !adaptableColorInBrowser;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("adaptableBrowser", adaptableColorInBrowser);
        editor.apply();
    }

    public static void toggleDebugVideoQualities() {
        debugVideoQualities = !debugVideoQualities;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("debugVideoQualities", debugVideoQualities);
        editor.apply();
    }

    public static void toggleLocalInstantView() {
        onlyLocalInstantView = !onlyLocalInstantView;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("onlyLocalInstantView", onlyLocalInstantView);
        editor.apply();
    }

    public static void toggleDirectShare() {
        directShare = !directShare;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("direct_share", directShare);
        editor.apply();
        ShortcutManagerCompat.removeAllDynamicShortcuts(ApplicationLoader.applicationContext);
        MediaDataController.getInstance(UserConfig.selectedAccount).buildShortcuts();
    }

    public static void toggleStreamMedia() {
        streamMedia = !streamMedia;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("streamMedia", streamMedia);
        editor.apply();
    }

    public static void toggleSortContactsByName() {
        sortContactsByName = !sortContactsByName;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("sortContactsByName", sortContactsByName);
        editor.apply();
    }

    public static void toggleSortFilesByName() {
        sortFilesByName = !sortFilesByName;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("sortFilesByName", sortFilesByName);
        editor.apply();
    }

    public static void toggleStreamAllVideo() {
        streamAllVideo = !streamAllVideo;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("streamAllVideo", streamAllVideo);
        editor.apply();
    }

    public static void toggleStreamMkv() {
        streamMkv = !streamMkv;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("streamMkv", streamMkv);
        editor.apply();
    }

    public static void toggleSaveStreamMedia() {
        saveStreamMedia = !saveStreamMedia;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("saveStreamMedia", saveStreamMedia);
        editor.apply();
    }

    public static void togglePauseMusicOnRecord() {
        pauseMusicOnRecord = !pauseMusicOnRecord;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("pauseMusicOnRecord", pauseMusicOnRecord);
        editor.apply();
    }

    public static void togglePauseMusicOnMedia() {
        pauseMusicOnMedia = !pauseMusicOnMedia;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("pauseMusicOnMedia", pauseMusicOnMedia);
        editor.apply();
    }

    public static void toggleChatBlur() {
        LiteMode.toggleFlag(LiteMode.FLAG_CHAT_BLUR);
    }

    public static void toggleForceDisableTabletMode() {
        forceDisableTabletMode = !forceDisableTabletMode;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("forceDisableTabletMode", forceDisableTabletMode);
        editor.apply();
    }

    public static void toggleInappCamera() {
        inappCamera = !inappCamera;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("inappCamera", inappCamera);
        editor.apply();
    }

    public static void toggleRoundCamera16to9() {
        roundCamera16to9 = !roundCamera16to9;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("roundCamera16to9", roundCamera16to9);
        editor.apply();
    }

    public static void setDistanceSystemType(int type) {
        distanceSystemType = type;
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("distanceSystemType", distanceSystemType);
        editor.apply();
        LocaleController.resetImperialSystemType();
    }

    public static void loadProxyList() {
        if (proxyListLoaded) {
            return;
        }
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
        String proxyAddress = preferences.getString("proxy_ip", "");
        String proxyUsername = preferences.getString("proxy_user", "");
        String proxyPassword = preferences.getString("proxy_pass", "");
        String proxySecret = preferences.getString("proxy_secret", "");
        int proxyPort = preferences.getInt("proxy_port", 1080);

        proxyListLoaded = true;
        proxyList.clear();
        currentProxy = null;
        String list = preferences.getString("proxy_list", null);
        if (!TextUtils.isEmpty(list)) {
            byte[] bytes = Base64.decode(list, Base64.DEFAULT);
            SerializedData data = new SerializedData(bytes);
            int count = data.readInt32(false);
            if (count == -1) { // V2 or newer
                int version = data.readByte(false);

                if (version == PROXY_SCHEMA_V2) {
                    count = data.readInt32(false);

                    for (int i = 0; i < count; i++) {
                        ProxyInfo info = new ProxyInfo(
                                data.readString(false),
                                data.readInt32(false),
                                data.readString(false),
                                data.readString(false),
                                data.readString(false));

                        info.ping = data.readInt64(false);
                        info.availableCheckTime = data.readInt64(false);

                        proxyList.add(0, info);
                        if (currentProxy == null && !TextUtils.isEmpty(proxyAddress)) {
                            if (proxyAddress.equals(info.address) && proxyPort == info.port && proxyUsername.equals(info.username) && proxyPassword.equals(info.password)) {
                                currentProxy = info;
                            }
                        }
                    }
                } else {
                    FileLog.e("Unknown proxy schema version: " + version);
                }
            } else {
                for (int a = 0; a < count; a++) {
                    ProxyInfo info = new ProxyInfo(
                            data.readString(false),
                            data.readInt32(false),
                            data.readString(false),
                            data.readString(false),
                            data.readString(false));
                    proxyList.add(0, info);
                    if (currentProxy == null && !TextUtils.isEmpty(proxyAddress)) {
                        if (proxyAddress.equals(info.address) && proxyPort == info.port && proxyUsername.equals(info.username) && proxyPassword.equals(info.password)) {
                            currentProxy = info;
                        }
                    }
                }
            }
            data.cleanup();
        }
        if (currentProxy == null && !TextUtils.isEmpty(proxyAddress)) {
            // MG: never let a 127.0.0.1 proxy_ip materialize via the ad-hoc
            // fallback. The only writers of 127.0.0.1 here are
            // MgTorController (blocking stub port=1 before bootstrap, live
            // ephemeral port after bootstrap) — both belong to the in-memory
            // synthetic entry injected by publishMgInternalTorProxy(). Gating
            // only on mg_useTor would still surface "127.0.0.1:1" as a real
            // list entry if a crash landed between toggleMgUseTor(false)
            // committing the flag and MgTorController.stop() committing
            // proxy_enabled=false, wedging next launch on the dead stub with
            // no UI affordance to clear it. Any legit user-added 127.0.0.1
            // proxy lives in proxy_list (processed above) and is unaffected.
            if ("127.0.0.1".equals(proxyAddress)) {
                return;
            }
            ProxyInfo info = currentProxy = new ProxyInfo(proxyAddress, proxyPort, proxyUsername, proxyPassword, proxySecret);
            proxyList.add(0, info);
        }
    }

    // MG: invoked by MgTorController.onBootstrapReady once tor is at
    // PROGRESS=100 and a live SOCKS port is persisted. Inserts a synthetic
    // ProxyInfo into the in-memory proxyList (no persist) and sets
    // currentProxy so ConnectionsManager.isProxyEnabled() returns true and
    // LaunchActivity's drawer/proxy-active indicator updates. Idempotent.
    public static ProxyInfo publishMgInternalTorProxy(int port) {
        loadProxyList();
        clearMgInternalTorProxy();
        ProxyInfo info = new ProxyInfo("127.0.0.1", port, "", "", "");
        info.mgInternal = true;
        info.available = true;
        proxyList.add(0, info);
        currentProxy = info;
        return info;
    }

    // MG: invoked by MgTorController.stop() / onDaemonExited(). Removes
    // every mgInternal entry from the in-memory list. If currentProxy was
    // the synthetic entry, clears it — the caller (snapshot restore) sets
    // a real currentProxy if the user had one before enabling Tor.
    public static void clearMgInternalTorProxy() {
        for (int i = proxyList.size() - 1; i >= 0; i--) {
            ProxyInfo info = proxyList.get(i);
            if (info.mgInternal) {
                if (currentProxy == info) currentProxy = null;
                proxyList.remove(i);
            }
        }
    }

    public static void saveProxyList() {
        List<ProxyInfo> infoToSerialize = new ArrayList<>(proxyList);
        // MG: never persist MgTorController's synthetic entry — its address
        // is the loopback stub and its port is the ephemeral SOCKS port
        // (rebound per Tor session). Serializing would leave a stale entry
        // in proxy_list JSON after every Tor restart.
        for (int i = infoToSerialize.size() - 1; i >= 0; i--) {
            if (infoToSerialize.get(i).mgInternal) infoToSerialize.remove(i);
        }
        Collections.sort(infoToSerialize, (o1, o2) -> {
            long bias1 = SharedConfig.currentProxy == o1 ? -200000 : 0;
            if (!o1.available) {
                bias1 += 100000;
            }
            long bias2 = SharedConfig.currentProxy == o2 ? -200000 : 0;
            if (!o2.available) {
                bias2 += 100000;
            }
            return Long.compare(o1.ping + bias1, o2.ping + bias2);
        });
        SerializedData serializedData = new SerializedData();
        serializedData.writeInt32(-1);
        serializedData.writeByte(PROXY_CURRENT_SCHEMA_VERSION);
        int count = infoToSerialize.size();
        serializedData.writeInt32(count);
        for (int a = count - 1; a >= 0; a--) {
            ProxyInfo info = infoToSerialize.get(a);
            serializedData.writeString(info.address != null ? info.address : "");
            serializedData.writeInt32(info.port);
            serializedData.writeString(info.username != null ? info.username : "");
            serializedData.writeString(info.password != null ? info.password : "");
            serializedData.writeString(info.secret != null ? info.secret : "");

            serializedData.writeInt64(info.ping);
            serializedData.writeInt64(info.availableCheckTime);
        }
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
        preferences.edit().putString("proxy_list", Base64.encodeToString(serializedData.toByteArray(), Base64.NO_WRAP)).apply();
        serializedData.cleanup();
    }

    public static ProxyInfo addProxy(ProxyInfo proxyInfo) {
        loadProxyList();
        int count = proxyList.size();
        for (int a = 0; a < count; a++) {
            ProxyInfo info = proxyList.get(a);
            if (proxyInfo.address.equals(info.address) && proxyInfo.port == info.port && proxyInfo.username.equals(info.username) && proxyInfo.password.equals(info.password) && proxyInfo.secret.equals(info.secret)) {
                return info;
            }
        }
        proxyList.add(0, proxyInfo);
        saveProxyList();
        return proxyInfo;
    }

    public static boolean isProxyEnabled() {
        return MessagesController.getGlobalMainSettings().getBoolean("proxy_enabled", false) && currentProxy != null;
    }

    public static void deleteProxy(ProxyInfo proxyInfo) {
        if (currentProxy == proxyInfo) {
            currentProxy = null;
            SharedPreferences preferences = MessagesController.getGlobalMainSettings();
            boolean enabled = preferences.getBoolean("proxy_enabled", false);
            SharedPreferences.Editor editor = preferences.edit();
            editor.putString("proxy_ip", "");
            editor.putString("proxy_pass", "");
            editor.putString("proxy_user", "");
            editor.putString("proxy_secret", "");
            editor.putInt("proxy_port", 1080);
            editor.putBoolean("proxy_enabled", false);
            editor.putBoolean("proxy_enabled_calls", false);
            editor.apply();
            if (enabled) {
                ConnectionsManager.setProxySettings(false, "", 0, "", "", "");
            }
        }
        proxyList.remove(proxyInfo);
        saveProxyList();
    }

    public static void checkSaveToGalleryFiles() {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                File telegramPath = new File(Environment.getExternalStorageDirectory(), "Telegram");
                File imagePath = new File(telegramPath, "Telegram Images");
                imagePath.mkdir();
                File videoPath = new File(telegramPath, "Telegram Video");
                videoPath.mkdir();

                if (!BuildVars.NO_SCOPED_STORAGE) {
                    if (imagePath.isDirectory()) {
                        new File(imagePath, ".nomedia").delete();
                    }
                    if (videoPath.isDirectory()) {
                        new File(videoPath, ".nomedia").delete();
                    }
                } else {
                    if (imagePath.isDirectory()) {
                        AndroidUtilities.createEmptyFile(new File(imagePath, ".nomedia"));
                    }
                    if (videoPath.isDirectory()) {
                        AndroidUtilities.createEmptyFile(new File(videoPath, ".nomedia"));
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    public static int getChatSwipeAction(int currentAccount) {
        if (chatSwipeAction >= 0) {
            if (chatSwipeAction == SwipeGestureSettingsView.SWIPE_GESTURE_FOLDERS && MessagesController.getInstance(currentAccount).dialogFilters.isEmpty()) {
                return SwipeGestureSettingsView.SWIPE_GESTURE_ARCHIVE;
            }
            return chatSwipeAction;
        } else if (!MessagesController.getInstance(currentAccount).dialogFilters.isEmpty()) {
            return SwipeGestureSettingsView.SWIPE_GESTURE_FOLDERS;

        }
        return SwipeGestureSettingsView.SWIPE_GESTURE_ARCHIVE;
    }

    public static void updateChatListSwipeSetting(int newAction) {
        chatSwipeAction = newAction;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
        preferences.edit().putInt("ChatSwipeAction", chatSwipeAction).apply();
    }

    public static void updateMessageSeenHintCount(int count) {
        messageSeenHintCount = count;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
        preferences.edit().putInt("messageSeenCount", messageSeenHintCount).apply();
    }

    public static void updateEmojiInteractionsHintCount(int count) {
        emojiInteractionsHintCount = count;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
        preferences.edit().putInt("emojiInteractionsHintCount", emojiInteractionsHintCount).apply();
    }

    public static void updateDayNightThemeSwitchHintCount(int count) {
        dayNightThemeSwitchHintCount = count;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
        preferences.edit().putInt("dayNightThemeSwitchHintCount", dayNightThemeSwitchHintCount).apply();
    }

    public static void updateStealthModeSendMessageConfirm(int count) {
        stealthModeSendMessageConfirm = count;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
        preferences.edit().putInt("stealthModeSendMessageConfirm", stealthModeSendMessageConfirm).apply();
    }

    public final static int PERFORMANCE_CLASS_LOW = 0;
    public final static int PERFORMANCE_CLASS_AVERAGE = 1;
    public final static int PERFORMANCE_CLASS_HIGH = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            PERFORMANCE_CLASS_LOW,
            PERFORMANCE_CLASS_AVERAGE,
            PERFORMANCE_CLASS_HIGH
    })
    public @interface PerformanceClass {}

    @PerformanceClass
    public static int getDevicePerformanceClass() {
        if (overrideDevicePerformanceClass != -1) {
            return overrideDevicePerformanceClass;
        }
        if (devicePerformanceClass == -1) {
            devicePerformanceClass = measureDevicePerformanceClass();
        }
        return devicePerformanceClass;
    }

    public static int measureDevicePerformanceClass() {
        int androidVersion = Build.VERSION.SDK_INT;
        int cpuCount = ConnectionsManager.CPU_COUNT;
        int memoryClass = ((ActivityManager) ApplicationLoader.applicationContext.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryClass();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.SOC_MODEL != null) {
            int hash = Build.SOC_MODEL.toUpperCase().hashCode();
            for (int i = 0; i < LOW_SOC.length; ++i) {
                if (LOW_SOC[i] == hash) {
                    return PERFORMANCE_CLASS_LOW;
                }
            }
        }

        int totalCpuFreq = 0;
        int freqResolved = 0;
        for (int i = 0; i < cpuCount; i++) {
            try {
                RandomAccessFile reader = new RandomAccessFile(String.format(Locale.ENGLISH, "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i), "r");
                String line = reader.readLine();
                if (line != null) {
                    totalCpuFreq += Utilities.parseInt(line) / 1000;
                    freqResolved++;
                }
                reader.close();
            } catch (Throwable ignore) {}
        }
        int maxCpuFreq = freqResolved == 0 ? -1 : (int) Math.ceil(totalCpuFreq / (float) freqResolved);

        long ram = -1;
        try {
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            ((ActivityManager) ApplicationLoader.applicationContext.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(memoryInfo);
            ram = memoryInfo.totalMem;
        } catch (Exception ignore) {}

        int performanceClass;
        if (
            androidVersion < 21 ||
            cpuCount <= 2 ||
            memoryClass <= 100 ||
            cpuCount <= 4 && maxCpuFreq != -1 && maxCpuFreq <= 1250 ||
            cpuCount <= 4 && maxCpuFreq <= 1600 && memoryClass <= 128 && androidVersion <= 21 ||
            cpuCount <= 4 && maxCpuFreq <= 1300 && memoryClass <= 128 && androidVersion <= 24 ||
            ram != -1 && ram < 2L * 1024L * 1024L * 1024L
        ) {
            performanceClass = PERFORMANCE_CLASS_LOW;
        } else if (
            cpuCount < 8 ||
            memoryClass <= 160 ||
            maxCpuFreq != -1 && maxCpuFreq <= 2055 ||
            maxCpuFreq == -1 && cpuCount == 8 && androidVersion <= 23
        ) {
            performanceClass = PERFORMANCE_CLASS_AVERAGE;
        } else {
            performanceClass = PERFORMANCE_CLASS_HIGH;
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("device performance info selected_class = " + performanceClass + " (cpu_count = " + cpuCount + ", freq = " + maxCpuFreq + ", memoryClass = " + memoryClass + ", android version " + androidVersion + ", manufacture " + Build.MANUFACTURER + ", screenRefreshRate=" + AndroidUtilities.screenRefreshRate + ", screenMaxRefreshRate=" + AndroidUtilities.screenMaxRefreshRate + ")");
        }

        return performanceClass;
    }

    public static String performanceClassName(int perfClass) {
        switch (perfClass) {
            case PERFORMANCE_CLASS_HIGH: return "HIGH";
            case PERFORMANCE_CLASS_AVERAGE: return "AVERAGE";
            case PERFORMANCE_CLASS_LOW: return "LOW";
            default: return "UNKNOWN";
        }
    }

    public static void setMediaColumnsCount(int count) {
        if (mediaColumnsCount != count) {
            mediaColumnsCount = count;
            ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE).edit().putInt("mediaColumnsCount", mediaColumnsCount).apply();
        }
    }

    public static void setStoriesColumnsCount(int count) {
        if (storiesColumnsCount != count) {
            storiesColumnsCount = count;
            ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE).edit().putInt("storiesColumnsCount", storiesColumnsCount).apply();
        }
    }

    public static void setFastScrollHintCount(int count) {
        if (fastScrollHintCount != count) {
            fastScrollHintCount = count;
            ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE).edit().putInt("fastScrollHintCount", fastScrollHintCount).apply();
        }
    }

    public static void setDontAskManageStorage(boolean b) {
        dontAskManageStorage = b;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE).edit().putBoolean("dontAskManageStorage", dontAskManageStorage).apply();
    }

    public static boolean canBlurChat() {
        return getDevicePerformanceClass() >= (Build.VERSION.SDK_INT >= 31 ? PERFORMANCE_CLASS_AVERAGE : PERFORMANCE_CLASS_HIGH) || BuildVars.DEBUG_PRIVATE_VERSION;
    }

    public static boolean chatBlurEnabled() {
        return canBlurChat() && LiteMode.isEnabled(LiteMode.FLAG_CHAT_BLUR);
    }

    public static class BackgroundActivityPrefs {
        private static SharedPreferences prefs;

        public static long getLastCheckedBackgroundActivity() {
            return prefs.getLong("last_checked", 0);
        }

        public static void setLastCheckedBackgroundActivity(long l) {
            prefs.edit().putLong("last_checked", l).apply();
        }

        public static int getDismissedCount() {
            return prefs.getInt("dismissed_count", 0);
        }

        public static void increaseDismissedCount() {
            prefs.edit().putInt("dismissed_count", getDismissedCount() + 1).apply();
        }
    }

    private static Boolean animationsEnabled;

    public static void setAnimationsEnabled(boolean b) {
        animationsEnabled = b;
    }

    public static boolean animationsEnabled() {
        if (animationsEnabled == null) {
            animationsEnabled = MessagesController.getGlobalMainSettings().getBoolean("view_animations", true);
        }
        return animationsEnabled;
    }

    public static SharedPreferences getPreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("userconfing", Context.MODE_PRIVATE);
    }

    public static boolean deviceIsLow() {
        return getDevicePerformanceClass() == PERFORMANCE_CLASS_LOW;
    }

    public static boolean deviceIsAboveAverage() {
        return getDevicePerformanceClass() >= PERFORMANCE_CLASS_AVERAGE;
    }

    public static boolean deviceIsHigh() {
        return getDevicePerformanceClass() >= PERFORMANCE_CLASS_HIGH;
    }

    public static boolean deviceIsAverage() {
        return getDevicePerformanceClass() <= PERFORMANCE_CLASS_AVERAGE;
    }

    public static void toggleRoundCamera() {
        bigCameraForRound = !bigCameraForRound;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putBoolean("bigCameraForRound", bigCameraForRound)
                .apply();
    }

    public static void toggleUseNewBlur() {
        useNewBlur = !useNewBlur;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putBoolean("useNewBlur", useNewBlur)
                .apply();
    }

    public static boolean isUsingCamera2(int currentAccount) {
        return useCamera2Force == null ? !MessagesController.getInstance(currentAccount).androidDisableRoundCamera2 : useCamera2Force;
    }

    public static void toggleUseCamera2(int currentAccount) {
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
                .edit()
                .putBoolean("useCamera2Force_2", useCamera2Force = !isUsingCamera2(currentAccount))
                .apply();
    }


    @Deprecated
    public static int getLegacyDevicePerformanceClass() {
        if (legacyDevicePerformanceClass == -1) {
            int androidVersion = Build.VERSION.SDK_INT;
            int cpuCount = ConnectionsManager.CPU_COUNT;
            int memoryClass = ((ActivityManager) ApplicationLoader.applicationContext.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryClass();
            int totalCpuFreq = 0;
            int freqResolved = 0;
            for (int i = 0; i < cpuCount; i++) {
                try {
                    RandomAccessFile reader = new RandomAccessFile(String.format(Locale.ENGLISH, "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i), "r");
                    String line = reader.readLine();
                    if (line != null) {
                        totalCpuFreq += Utilities.parseInt(line) / 1000;
                        freqResolved++;
                    }
                    reader.close();
                } catch (Throwable ignore) {}
            }
            int maxCpuFreq = freqResolved == 0 ? -1 : (int) Math.ceil(totalCpuFreq / (float) freqResolved);

            if (androidVersion < 21 || cpuCount <= 2 || memoryClass <= 100 || cpuCount <= 4 && maxCpuFreq != -1 && maxCpuFreq <= 1250 || cpuCount <= 4 && maxCpuFreq <= 1600 && memoryClass <= 128 && androidVersion <= 21 || cpuCount <= 4 && maxCpuFreq <= 1300 && memoryClass <= 128 && androidVersion <= 24) {
                legacyDevicePerformanceClass = PERFORMANCE_CLASS_LOW;
            } else if (cpuCount < 8 || memoryClass <= 160 || maxCpuFreq != -1 && maxCpuFreq <= 2050 || maxCpuFreq == -1 && cpuCount == 8 && androidVersion <= 23) {
                legacyDevicePerformanceClass = PERFORMANCE_CLASS_AVERAGE;
            } else {
                legacyDevicePerformanceClass = PERFORMANCE_CLASS_HIGH;
            }
        }
        return legacyDevicePerformanceClass;
    }


    //DEBUG
    public static boolean drawActionBarShadow = true;

    private static void loadDebugConfig(SharedPreferences preferences) {
        drawActionBarShadow = preferences.getBoolean("drawActionBarShadow", true);
    }

    public static void saveDebugConfig() {
        SharedPreferences pref = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
        pref.edit().putBoolean("drawActionBarShadow", drawActionBarShadow);
    }



}
