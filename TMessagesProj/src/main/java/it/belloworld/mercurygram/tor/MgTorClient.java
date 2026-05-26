package it.belloworld.mercurygram.tor;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.tgnet.ConnectionsManager;

import it.belloworld.mercurygram.MgUpdateChecker;

import org.telegram.ui.ActionBar.AlertDialog;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import it.belloworld.mercurygram.plugin.tor.IMgTorCallback;
import it.belloworld.mercurygram.plugin.tor.IMgTorService;

/**
 * Main-app client for the Tor companion plugin (`:TMessagesProj_PluginTor`).
 *
 * <p>Replaces the old in-process {@code MgTorController} / {@code MgTorNative}
 * classes. The Tor daemon now lives in a separate APK; this class binds to it
 * via AIDL ({@link IMgTorService}) and re-exposes the same public surface so
 * the existing call sites (ApplicationLoader, SharedConfig, ConnectionsManager,
 * MercurygramSettingsActivity, UnifiedPushReceiver) keep compiling after a
 * pure search-and-replace.
 *
 * <p>Responsibilities owned by this class (NOT by the plugin):
 * <ul>
 *   <li>Plugin discovery + signature pin (self-cert SHA-256 match — same
 *       signing key invariant).</li>
 *   <li>Pre-bind blocking-stub privacy invariant: proxy_ip/port committed
 *       to SharedPreferences before ConnectionsManager.init runs, so cold
 *       start never targets a stale ephemeral SOCKS port.</li>
 *   <li>Snapshot/restore of the user's pre-Tor proxy config across the
 *       toggle cycle.</li>
 *   <li>One-shot MgOrbotHelper-era pref migration.</li>
 *   <li>Per-account + VoIP aggregation into a single
 *       {@code onClientPausedChanged(int)} pushed over AIDL — the plugin
 *       runs out-of-process and can't read main-app state directly.</li>
 *   <li>Bind lifecycle: bind-only (no startForegroundService — the plugin
 *       self-promotes to foreground from binder.start once main is bound,
 *       sidestepping the 5 s FGS-start window race + the FGS-start quota
 *       cost), exponential rebind backoff on disconnect, unbind on disable
 *       so the plugin's {@code :tor} process can exit.</li>
 * </ul>
 */
public final class MgTorClient {

    // The single (release) plugin package — every main flavor binds it.
    // .beta (debug) and .web (standalone) mains are signed with the same
    // release.keystore as the release plugin, so the signature-level BIND
    // permission and the self-pin both match; no suffixed plugin variant is
    // ever built, published, or downloaded. (A per-flavor suffix here once
    // pointed at a .beta package nothing installs — hence the reinstall loop.)
    private static final String PLUGIN_PACKAGE_BASE = "it.belloworld.mercurygram.plugin.tor";
    private static final String BIND_ACTION         = "it.belloworld.mercurygram.plugin.tor.BIND";

    private static String pluginPackage() {
        return PLUGIN_PACKAGE_BASE;
    }

    // Cert allowlist: Mercurygram ships under two signing keys (developer
    // keystore + F-Droid per-app key); the runtime pin accepts both for
    // BOTH main and plugin, enabling F-Droid main + GitHub plugin (and
    // every other combination) on devices where the OS-level BIND
    // permission can also span them (API 31+ via knownSigner — see
    // plugin's AndroidManifest.xml). Must stay in lockstep with both
    //   - res/values/mg_known_main_certs.xml in the plugin module
    //   - AllowedAPKSigningKeys in both F-Droid recipes (main + plugin)
    // Hex, lowercase, no colons — matches apksigner's --print-certs
    // --print-canonicalized format and the F-Droid recipe convention.
    private static final String[] ALLOWED_CERT_SHA256_HEX = {
            // Developer keystore (GitHub + reproducible F-Droid APKs).
            "1e73de100e2646be671afad2cb4bb471538e062a745ae5adbe6c7d1666fd1ee9",
            // F-Droid per-app key for it.belloworld.mercurygram.
            "feb802f2f14cee16efd9fec5d809fa3bef7a2b349b989f816d42aad9c39ef77a",
    };
    private static volatile byte[][] allowedCertSha256Cache;

    // Lowest plugin MG_VERSION_CODE whose IMgTorService AIDL surface is
    // compatible with this main build. MUST be bumped (in the same commit)
    // on every AIDL-breaking change (new method, removed method, parameter
    // type change) AND on any plugin security / blocking-bug fix that main
    // must require. This is the ONE signal for "the plugin must be at least
    // this new" — there is no cheap runtime way to auto-detect an AIDL or
    // security incompatibility, so it is encoded here by hand.
    //
    // Units are MG_VERSION_CODE (not the per-ABI versionCode): plugin
    // versionCode = MG_VERSION_CODE * 10 + abiVersionCode (abi ∈ {3,4,7,8}),
    // so every comparison divides the per-ABI value by 10 first to drop the
    // ABI offset (otherwise the floor would be satisfied differently per
    // ABI for plugins straddling the boundary).
    //
    // Checked in TWO places, both against the same floor: a disk read at
    // toggle-on (isPluginUpdateRequired — bind-independent, so a plugin too
    // old to even bind still gets caught) and the live bind in
    // handleConnected (runtime safety net).
    //
    // Bumped from 1 for the anti-censorship transport feature: main now calls
    // the new IMgTorService.setTransportConfig, absent on pre-feature plugins.
    // Value = the LOWEST MG_VERSION_CODE in the release cycle that first ships
    // this AIDL. That cycle is 12.8.1 (APP_VERSION_CODE=6916); beta.yml ships a
    // pre-stable 12.8.1.0.K first, whose MG_VERSION_CODE is 6916*100 + 0 =
    // 691600 (M=0), below the stable 12.8.1.M (M>=1) at 691601. Using 691600
    // (not 691601) so main does not flag its OWN matching pre-stable plugin as
    // outdated, while every prior-cycle plugin (APP_VERSION_CODE < 6916, thus
    // MG_VERSION_CODE < 691600) is correctly forced to update before the
    // transport can be used.
    // RELEASE-VERIFY: if APP_VERSION_CODE changes before this merges, recompute
    // to (new APP_VERSION_CODE * 100) so a feature-less same-name plugin can't
    // slip under the floor.
    private static final int  MIN_PLUGIN_MG_VERSION_CODE = 691600;
    private static final long REBIND_BACKOFF_INITIAL_MS = 1_000L;
    private static final long REBIND_BACKOFF_CAP_MS     = 60_000L;
    private static final int  BLOCKING_STUB_PORT        = 1;
    // Plugin's controller.stop() can take up to 7s (control-socket SIGNAL
    // SHUTDOWN + daemon.join 5s); 10s leaves headroom for a healthy stop +
    // oneway round-trip before the local-restore watchdog fires.
    private static final long STOP_WATCHDOG_MS          = 10_000L;
    // Cap on consecutive bind/disconnect cycles without daemon progress
    // (see consecutiveAbortiveBinds). 5 lines up roughly with the time it
    // takes scheduleRebindWithBackoff to reach REBIND_BACKOFF_CAP_MS
    // (1+2+4+8+16 s) — a healthy reconnect cycles below this; a true
    // crash loop reliably exceeds it.
    private static final int  MAX_ABORTIVE_BINDS        = 5;

    private static final MgTorClient INSTANCE = new MgTorClient();

    public static MgTorClient getInstance() { return INSTANCE; }
    private MgTorClient() {}

    // Stored on init(Context). Do NOT reach for ApplicationLoader.applicationContext directly.
    private static volatile Context appContext;
    private static volatile HandlerThread workerThread;
    private static volatile Handler workerHandler;

    private final CopyOnWriteArrayList<ProgressListener> listeners = new CopyOnWriteArrayList<>();

    private volatile State state = State.UNKNOWN;
    private volatile int bootstrapPercent = -1;
    private volatile int socksPort = -1;
    private volatile boolean bound;
    private volatile boolean bindRequested;
    private volatile long currentBackoffMs = REBIND_BACKOFF_INITIAL_MS;

    @Nullable private volatile IMgTorService service;
    @Nullable private volatile BroadcastReceiver pluginInstallReceiver;
    private volatile int lastPushedAccount = -1;
    // Set when caller wants the daemon running (user toggle, push fallback,
    // cold-start with mg_useTor=true). Consumed in handleConnected, which
    // forwards svc.start() over AIDL. Without this flag the bind path only
    // verifies the plugin and never actually starts Tor.
    private volatile boolean pendingStart;

    // Per-account appResumeCount snapshot. The plugin aggregates by single
    // int (pausedClientCount semantics), so we reduce all accounts here
    // before pushing over AIDL. Filtered through isClientActivated so a
    // deleted account whose stale entry is still in the map can't keep the
    // plugin "active" forever.
    private final ConcurrentHashMap<Integer, Integer> accountResumeCounts = new ConcurrentHashMap<>();
    // Last value we pushed to the plugin, to suppress no-op AIDL traffic.
    private volatile int lastPushedPausedCount = Integer.MIN_VALUE;

    // Monotonic counter bumped from remoteCallback.onStopped. stop()'s
    // watchdog captures this value before posting itself with delay; if
    // the counter has advanced by the time the watchdog runs, an onStopped
    // landed and ran the restore — watchdog no-ops. Drives recovery from
    // the dropped-oneway race (plugin :tor process killed between AIDL
    // stop() return and the oneway onStopped delivery).
    private volatile long onStoppedDeliveryCount;

    // Set by applyTransportChange when the user switches anti-censorship
    // transport while Tor is running. The plugin reads the transport only when
    // it assembles its start() argv, so the daemon must relaunch to pick up the
    // new value. Consumed in remoteCallback.onStopped, which fires
    // userInitiatedStart once the old daemon has cleanly reported terminal.
    private volatile boolean restartForTransportRequested;

    // Count of consecutive disconnects without an intervening successful
    // onBootstrapProgress. A crash-looping plugin (native fault, OOM at
    // start) would otherwise rebind forever; reset in onBootstrapProgress
    // (the first signal the daemon spawned and is making progress — a
    // crash-during-bind would never reach this); capped at
    // MAX_ABORTIVE_BINDS to break the loop.
    private volatile int consecutiveAbortiveBinds;

    // ---- Public API — static lifecycle helpers ----

    /**
     * One-shot migration for users upgrading from the MgOrbotHelper era,
     * which persisted proxy_ip=127.0.0.1+proxy_port=9050+proxy_enabled=true
     * to route MTProto through an Orbot install. The plugin replaces that
     * path; stale Orbot prefs left on disk would silently keep traffic
     * flowing through Orbot (if still installed) or break MTProto entirely
     * (if not). Must run BEFORE preInit on cold start so we don't fight
     * with our own blocking-stub commit.
     */
    public static void migrateLegacyOrbotEntry() {
        SharedPreferences prefs = MessagesController.getGlobalMainSettings();
        if (prefs.getBoolean("mg_orbotMigrationV1Done", false)) return;
        if (!SharedConfig.mg_useTor) {
            String ip = prefs.getString("proxy_ip", "");
            int port = prefs.getInt("proxy_port", 0);
            // Orbot historically ships two SocksPort defaults: 9050 (the
            // canonical Tor SOCKS port, used by Orbot's main entry) and
            // 9150 (Tor Browser's bundled port, exposed by some Orbot
            // builds for app-level use). Both are loopback-only by design
            // and were the only practical values the legacy MgOrbotHelper
            // ever pointed at. Match either rather than 9050 alone — the
            // 9150 case otherwise left a stale 127.0.0.1:9150 proxy
            // pinned on disk after Orbot was uninstalled.
            if ("127.0.0.1".equals(ip) && (port == 9050 || port == 9150)) {
                prefs.edit()
                        .putString("proxy_ip", "")
                        .putString("proxy_user", "")
                        .putString("proxy_pass", "")
                        .putString("proxy_secret", "")
                        .putInt("proxy_port", 1080)
                        .putBoolean("proxy_enabled", false)
                        .putBoolean("mg_orbotMigrationV1Done", true)
                        .apply();
                return;
            }
        }
        prefs.edit().putBoolean("mg_orbotMigrationV1Done", true).apply();
    }

    /**
     * Pin the 127.0.0.1:1 blocking-stub BEFORE ConnectionsManager singletons
     * exist. Writes proxy_ip/proxy_port/proxy_enabled via SharedPreferences
     * so ConnectionsManager.init() reads the stub on its first proxy_port
     * lookup — without this, cold start would target the previous session's
     * persisted live SOCKS port (now dead, possibly rebound by another app).
     * Uses apply() rather than commit(): in-process readers see the
     * SharedPreferences in-memory cache immediately, and a process crash
     * before flush is harmless because the next cold start re-pins.
     */
    public static void preInit() {
        if (!SharedConfig.mg_useTor) return;
        // F-Droid main + pre-Android-12: plugin's BIND permission needs
        // knownSigner (API 31+) for the cross-key bind to work. Without it
        // the OS refuses the bindService regardless of main's runtime
        // allowlist, so Tor is unreachable on this configuration. The
        // Settings UI hides the toggle here, leaving no recovery surface —
        // force mg_useTor off + restore prior proxy (or clear) at cold
        // start so MTProto isn't permanently wedged on the blocking stub.
        if (isFdroidPreS()) {
            try {
                if (!restoreSnapshottedProxy()) {
                    clearProxyOnDisk();
                }
            } catch (Throwable t) { FileLog.e(t); }
            try { SharedConfig.toggleMgUseTor(); }
            catch (Throwable t) { FileLog.e(t); }
            // No toast: the user has no UI to recover from this state on
            // this platform (toggle hidden by Settings activity), and the
            // existing "plugin not installed" string is misleading here
            // (it suggests installing would fix it — it wouldn't). Keep
            // the flip silent; future polish: add a dedicated "Tor not
            // supported on F-Droid + Android <12" string + Toast.
            return;
        }
        // Pin the blocking stub unconditionally when mg_useTor is set:
        // preserves the user's privacy choice across an upgrade or plugin
        // uninstall. ConnectionsManager sees 127.0.0.1:1 on first proxy
        // lookup and refuses to fall through to direct MTProto — no
        // auth_key_id leak even when the plugin is missing.
        //
        // Prior behaviour silently flipped mg_useTor=false on cold start
        // when the plugin was absent. That defeated the toggle's privacy
        // contract: a user who explicitly opted into Tor would be put
        // back on direct MTProto without consent on the next launch.
        try { commitBlockingStubToDisk(); }
        catch (Throwable t) { FileLog.e(t); }
        try { SharedConfig.publishMgInternalTorProxy(BLOCKING_STUB_PORT); }
        catch (Throwable t) { FileLog.e(t); }
        if (!isPluginInstalled()) {
            // Set state so any ProgressListener attached later (e.g. by
            // MercurygramSettingsActivity on first Settings open) gets
            // the missing-plugin signal and surfaces promptInstallOrUpdate.
            // The blocking stub keeps MTProto privacy-correct meanwhile;
            // the toast informs the user Tor needs the plugin to function.
            // TODO(mg-tor-plugin): also post a persistent system
            //   notification with install + disable actions so the user
            //   can act before opening Settings.
            INSTANCE.state = State.PLUGIN_NOT_INSTALLED;
            notifyTorPluginMissing();
        }
    }

    // Defer to the main looper: preInit runs from ApplicationLoader before
    // any Activity is up; runOnUIThread queues the toast to fire as soon as
    // the looper starts processing messages, no race with Toast's looper
    // requirement.
    private static void notifyTorPluginMissing() {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                Context ctx = appContext != null ? appContext : ApplicationLoader.applicationContext;
                if (ctx == null) return;
                Toast.makeText(ctx,
                        LocaleController.getString(R.string.MercurygramTorPluginMissing),
                        Toast.LENGTH_LONG).show();
            } catch (Throwable t) { FileLog.e(t); }
        });
    }

    private static void commitBlockingStubToDisk() {
        SharedPreferences prefs = MessagesController.getGlobalMainSettings();
        // .apply() (async): preInit() reaches here on the Application init
        // thread, where a synchronous SharedPreferences.commit() on a slow
        // device blocks UI startup (StrictMode disk-write violation in
        // debug builds; ANR risk under storage contention). In-process
        // readers — including the imminent ConnectionsManager.init() — see
        // the in-memory pref cache immediately, so the cold-start privacy
        // invariant is upheld without a synchronous flush. A crash between
        // the write and the writer thread flushing is harmless: mg_useTor
        // is already on disk (preInit early-returns when it isn't), so
        // the next cold start runs preInit again and re-pins the stub.
        prefs.edit()
                .putString("proxy_ip", "127.0.0.1")
                .putString("proxy_user", "")
                .putString("proxy_pass", "")
                .putString("proxy_secret", "")
                .putInt("proxy_port", BLOCKING_STUB_PORT)
                .putBoolean("proxy_enabled", true)
                .apply();
    }

    /** Wire up the client; if {@link SharedConfig#mg_useTor} also start binding. */
    public static void init(Context appContext) {
        if (appContext == null) return;
        MgTorClient.appContext = appContext.getApplicationContext();
        ensureWorker();
        if (SharedConfig.mg_useTor) {
            INSTANCE.pendingStart = true;
            getInstance().scheduleBind(0L);
        }
    }

    /**
     * Capture the user's current proxy config BEFORE the first
     * commitBlockingStubToDisk overwrites it. Called from the settings
     * activity at user-initiated enable. Idempotent within an enable cycle —
     * re-calling when proxy_ip is already 127.0.0.1 is a no-op (we don't
     * snapshot the blocking stub).
     */
    public static void snapshotCurrentProxy() {
        SharedPreferences prefs = MessagesController.getGlobalMainSettings();
        if (prefs.getBoolean("mg_tor_savedProxy_present", false)) return;
        String ip = prefs.getString("proxy_ip", "");
        if ("127.0.0.1".equals(ip)) return;
        // .commit() (sync): the caller in MercurygramSettingsActivity
        // immediately follows with SharedConfig.toggleMgUseTor (also commit()).
        // If this write only apply()'d, an OS-kill between the toggle's
        // commit return and the writer thread flushing this snapshot would
        // leave mg_useTor=true on disk with no mg_tor_savedProxy_* keys;
        // the next cold start's commitBlockingStubToDisk would then overwrite
        // the user's pre-existing proxy_ip / proxy_port permanently.
        prefs.edit()
                .putBoolean("mg_tor_savedProxy_present", true)
                .putString("mg_tor_savedProxy_ip", ip)
                .putInt("mg_tor_savedProxy_port", prefs.getInt("proxy_port", 1080))
                .putString("mg_tor_savedProxy_user", prefs.getString("proxy_user", ""))
                .putString("mg_tor_savedProxy_pass", prefs.getString("proxy_pass", ""))
                .putString("mg_tor_savedProxy_secret", prefs.getString("proxy_secret", ""))
                .putBoolean("mg_tor_savedProxy_enabled", prefs.getBoolean("proxy_enabled", false))
                .commit();
    }

    /**
     * Restore + clear the snapshot. Returns true if a snapshot was restored,
     * false if there was none (caller falls back to clearing the proxy entry).
     */
    private static boolean restoreSnapshottedProxy() {
        SharedPreferences prefs = MessagesController.getGlobalMainSettings();
        if (!prefs.getBoolean("mg_tor_savedProxy_present", false)) return false;
        String ip = prefs.getString("mg_tor_savedProxy_ip", "");
        int port = prefs.getInt("mg_tor_savedProxy_port", 1080);
        String user = prefs.getString("mg_tor_savedProxy_user", "");
        String pass = prefs.getString("mg_tor_savedProxy_pass", "");
        String secret = prefs.getString("mg_tor_savedProxy_secret", "");
        boolean enabled = prefs.getBoolean("mg_tor_savedProxy_enabled", false);
        // .commit() (sync) for the same crash-window reason as clearProxyOnDisk.
        prefs.edit()
                .putString("proxy_ip", ip)
                .putInt("proxy_port", port)
                .putString("proxy_user", user)
                .putString("proxy_pass", pass)
                .putString("proxy_secret", secret)
                .putBoolean("proxy_enabled", enabled)
                .remove("mg_tor_savedProxy_present")
                .remove("mg_tor_savedProxy_ip")
                .remove("mg_tor_savedProxy_port")
                .remove("mg_tor_savedProxy_user")
                .remove("mg_tor_savedProxy_pass")
                .remove("mg_tor_savedProxy_secret")
                .remove("mg_tor_savedProxy_enabled")
                .commit();
        ConnectionsManager.setProxySettings(enabled, ip, port, user, pass, secret);
        // clearMgInternalTorProxy nulled SharedConfig.currentProxy when the
        // synthetic entry was active; without re-binding it to the user's
        // real ProxyInfo, isProxyEnabled() lies (returns false even though
        // disk says enabled) and the drawer's proxy-active indicator goes
        // dark until the user manually re-taps the entry.
        SharedConfig.currentProxy = enabled ? findProxyInList(ip, port, user, pass, secret) : null;
        return true;
    }

    @Nullable
    private static SharedConfig.ProxyInfo findProxyInList(String ip, int port,
                                                          String user, String pass, String secret) {
        SharedConfig.loadProxyList();
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            if (info.mgInternal) continue;
            if (info.port == port
                    && safeEq(info.address, ip)
                    && safeEq(info.username, user)
                    && safeEq(info.password, pass)
                    && safeEq(info.secret, secret)) {
                return info;
            }
        }
        return null;
    }

    private static boolean safeEq(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    private static void clearProxyOnDisk() {
        SharedPreferences prefs = MessagesController.getGlobalMainSettings();
        // .commit() (sync) — SharedConfig.toggleMgUseTor flips mg_useTor with
        // commit(); if this write only apply()'d, a crash between the two
        // would leave mg_useTor=false on disk with proxy_ip=127.0.0.1:1
        // pinned, wedging MTProto on the blocking stub on next cold start
        // with no settings affordance (the Tor toggle is OFF) to recover.
        prefs.edit()
                .putString("proxy_ip", "")
                .putString("proxy_user", "")
                .putString("proxy_pass", "")
                .putString("proxy_secret", "")
                .putInt("proxy_port", 1080)
                .putBoolean("proxy_enabled", false)
                .commit();
    }

    /**
     * F-Droid build running on Android <12: the plugin's BIND permission
     * uses knownSigner (API 31+) to allowlist both signing keys; on older
     * devices the OS treats the protectionLevel as plain "signature" and
     * refuses cross-key binds. The Settings UI hides the Tor toggle in
     * this configuration, and preInit force-disables mg_useTor so a stale
     * pre-upgrade flag doesn't leave MTProto wedged on the blocking stub.
     */
    public static boolean isFdroidPreS() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                && MgUpdateChecker.isFdroidBuild();
    }

    /** True iff the plugin is installed, signature-verified, and bound (or known-bindable). */
    public static boolean isAvailable() {
        if (appContext == null) return false;
        State s = INSTANCE.state;
        if (s == State.PLUGIN_NOT_INSTALLED
                || s == State.PLUGIN_SIGNATURE_MISMATCH
                || s == State.PLUGIN_OUTDATED) {
            return false;
        }
        return INSTANCE.bound;
    }

    /**
     * Synchronous: is the plugin APK installed on this device? Does not
     * touch the bind state machine — useful in the settings UI to decide
     * whether to surface the "plugin missing" hint when Tor is OFF (no
     * bind happens then, so isAvailable() would return false even if the
     * plugin is sitting installed and ready).
     */
    public static boolean isPluginInstalled() {
        Context ctx = appContext != null ? appContext : ApplicationLoader.applicationContext;
        if (ctx == null) return false;
        try {
            ctx.getPackageManager().getPackageInfo(pluginPackage(), 0);
            return true;
        } catch (PackageManager.NameNotFoundException nf) {
            return false;
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    /**
     * Installed plugin's per-ABI versionCode read straight from
     * PackageManager, or -1 when the plugin is absent / unreadable. No
     * bind — so a plugin too stale to even bind is still inspectable.
     */
    public static long installedPluginVersionCode() {
        Context ctx = appContext != null ? appContext : ApplicationLoader.applicationContext;
        if (ctx == null) return -1;
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(pluginPackage(), 0);
            return androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pi);
        } catch (PackageManager.NameNotFoundException nf) {
            return -1;
        } catch (Throwable t) {
            FileLog.e(t);
            return -1;
        }
    }

    /**
     * True iff the plugin is installed AND below the compatibility floor
     * ({@link #MIN_PLUGIN_MG_VERSION_CODE}). Disk-only — no service bind —
     * so the toggle-on path can force an update BEFORE attempting to
     * connect, even when an AIDL break would make that bind fail. Returns
     * false when the plugin is absent (the PLUGIN_NOT_INSTALLED install
     * flow handles that) so callers gate connect, not install.
     */
    public static boolean isPluginUpdateRequired() {
        long vc = installedPluginVersionCode();
        return vc >= 0 && (vc / 10) < MIN_PLUGIN_MG_VERSION_CODE;
    }

    /**
     * Soft "a newer plugin exists" signal — installed plugin's versionName is
     * behind main (disk-only compare via {@link MgUpdateChecker#isPluginOutdated}).
     * Distinct from {@link #isPluginUpdateRequired()} (hard floor breach that
     * blocks binding). GitHub channel only — F-Droid drives plugin updates from
     * its catalog. Used by the manual "Update Tor plugin" settings row.
     */
    public static boolean isPluginUpdateAvailable() {
        return isPluginInstalled()
                && !MgUpdateChecker.isFdroidBuild()
                && MgUpdateChecker.isPluginOutdated(pluginPackage());
    }

    // Fires at most once per process so a backgrounded-then-resumed
    // LaunchActivity doesn't re-stack the prompt on every onResume
    // (checkAppUpdate(false) is wired there, not strictly cold-start).
    private static volatile boolean pluginPromptShownThisSession;

    /**
     * Cold-start hook: prompts the user to update the installed Tor
     * plugin when its versionName is strictly behind the running main
     * tag. Main + plugin share MG_BUILD_TAG release-for-release on the
     * GitHub channel, so any drift behind means a same-MG_VERSION_CODE
     * bump (e.g. 12.7.3.2.7 → .2.8) left the plugin behind during the
     * main APK upgrade. A plugin ahead of main (dev iteration, beta
     * rollback) is NOT flagged — see MgUpdateChecker.isPluginOutdated.
     *
     * <p>Silent on F-Droid (catalog drives plugin updates), when the
     * plugin is absent (preInit's PLUGIN_NOT_INSTALLED path handles
     * that), when a main update is already pending (user will install
     * main first and re-trigger this check), when the user dismissed
     * the prompt for the current main tag, and after the first
     * invocation per process (so a Settings re-open or onResume bounce
     * doesn't double-prompt).
     *
     * <p>Must be invoked on the UI thread (AlertDialog.show contract).
     * The Activity reference is held only long enough to construct the
     * Builder; install/error callbacks lazily resolve a live Activity
     * via LaunchActivity.instance so an in-flight download can't pin a
     * destroyed Activity into its closure.
     */
    public static void maybePromptPluginUpdate(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (pluginPromptShownThisSession) return;
        if (MgUpdateChecker.isFdroidBuild()) return;
        // Only nag users who are actually relying on Tor — a plugin
        // sitting installed-but-unused (left over from prior toggle-on
        // experiments) doesn't warrant interrupting the cold start.
        if (!SharedConfig.mg_useTor) return;
        Context ctx = appContext != null ? appContext : ApplicationLoader.applicationContext;
        if (ctx == null) return;

        // Hard floor breach: the installed plugin is below
        // MIN_PLUGIN_MG_VERSION_CODE, so the bind is rejected and Tor can't
        // route at all (traffic wedges on the 127.0.0.1:1 stub). Force the
        // update — bypass the per-tag dismissal AND the pending-main-update
        // gate (a main update won't fix the *plugin*), keeping only the
        // once-per-process guard so it doesn't re-stack on every onResume.
        // It re-prompts each launch until the plugin is updated.
        if (isPluginUpdateRequired()) {
            pluginPromptShownThisSession = true;
            new AlertDialog.Builder(activity)
                    .setTitle(LocaleController.getString(R.string.MercurygramTor))
                    .setMessage(LocaleController.getString(R.string.MercurygramTorPluginOutdated))
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .setPositiveButton(LocaleController.getString(R.string.MercurygramTorInstallPlugin),
                            (d, w) -> MgUpdateChecker.runPluginInstall(MgTorClient::liveLaunchActivity))
                    .create().show();
            return;
        }

        // Soft nag (cosmetic versionName drift, AIDL-compatible): suppressible
        // per main tag, and skipped while a main update is pending — installing
        // main first will refresh this check after the upgrade.
        if (SharedConfig.isMgUpdateAvailable()) return;
        if (!MgUpdateChecker.isPluginOutdated(pluginPackage())) return;
        final String mainTag = MgUpdateChecker.currentInstallVersion();
        if (mainTag == null || mainTag.equals(SharedConfig.mgDismissedPluginPromptTag)) return;

        pluginPromptShownThisSession = true;
        AlertDialog.Builder b = new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.MercurygramTor))
                .setMessage(LocaleController.getString(R.string.MercurygramTorPluginOutdated))
                .setNegativeButton(LocaleController.getString(R.string.Cancel),
                        (d, w) -> SharedConfig.setMgDismissedPluginPromptTag(mainTag))
                .setPositiveButton(LocaleController.getString(R.string.MercurygramTorInstallPlugin),
                        (d, w) -> MgUpdateChecker.runPluginInstall(MgTorClient::liveLaunchActivity));
        b.setOnCancelListener(d -> SharedConfig.setMgDismissedPluginPromptTag(mainTag));
        b.create().show();
    }

    private static Activity liveLaunchActivity() {
        Activity la = org.telegram.ui.LaunchActivity.instance;
        return (la != null && !la.isFinishing() && !la.isDestroyed()) ? la : null;
    }

    // ---- Public API — instance lifecycle ----

    /**
     * Soft-stop. While {@link SharedConfig#mg_useTor} is still on, the
     * plugin's onStopped callback will re-pin the blocking stub. When
     * mg_useTor has been flipped off, the same callback restores the
     * snapshotted user proxy (if any) so disabling Tor doesn't silently
     * destroy a user's pre-existing SOCKS5 / MTProto-proxy config, AND the
     * client tears down the binding + stopService the plugin so its `:tor`
     * process can be reclaimed by the OS instead of lingering until OOM.
     */
    public void stop() {
        ensureWorker();
        workerHandler.post(() -> {
            final long beforeStop = onStoppedDeliveryCount;
            pendingStart = false;
            IMgTorService s = service;
            boolean remoteAccepted = false;
            if (s != null) {
                try {
                    s.stop();
                    remoteAccepted = true;
                } catch (RemoteException e) {
                    // Plugin :tor process died mid-AIDL. No onStopped
                    // callback will ever fire, so the restore path below
                    // is the only thing standing between the user's
                    // toggle-off click and disk staying pinned at
                    // 127.0.0.1:1 forever.
                    FileLog.e(e);
                }
            }
            if (SharedConfig.mg_useTor) return;
            if (!remoteAccepted) {
                // Dead binder / never-bound state — onStopped will never
                // fire, restore right now.
                restoreOnDisable();
                return;
            }
            // AIDL stop() returned OK: the happy path is remoteCallback.
            // onStopped landing and running the restore. But the oneway
            // callback can be silently dropped if :tor is killed (LMK,
            // OOM, force-stop) between the AIDL return and the dispatcher
            // delivering the transaction — disk would then stay pinned at
            // 127.0.0.1:1 while mg_useTor=false, wedging MTProto on the
            // unreachable stub with no UI affordance to recover (the Tor
            // toggle reads OFF). Watchdog: if onStoppedDeliveryCount
            // hasn't moved by STOP_WATCHDOG_MS, restore defensively. All
            // restore paths are idempotent so a late onStopped racing the
            // watchdog is harmless.
            workerHandler.postDelayed(() -> {
                if (SharedConfig.mg_useTor) return;
                if (onStoppedDeliveryCount != beforeStop) return;
                restoreOnDisable();
            }, STOP_WATCHDOG_MS);
        });
    }

    // Idempotent: restoreSnapshottedProxy() clears its snapshot keys on
    // success, so a second call from remoteCallback.onStopped → else branch
    // is a no-op. clearProxyOnDisk() is also idempotent.
    private void restoreOnDisable() {
        try {
            if (!restoreSnapshottedProxy()) {
                clearProxyOnDisk();
                ConnectionsManager.setProxySettings(false, "", 0, "", "", "");
            }
            SharedConfig.clearMgInternalTorProxy();
        } catch (Throwable t) { FileLog.e(t); }
        teardownPluginService();
    }

    /**
     * Unbind + stopService the plugin so the plugin's `:tor` process can
     * exit. Bind-only entry path: unbinding alone tears the Service down
     * because we never opened a start-count, but stopService stays as a
     * belt-and-braces hedge against the OS lingering the Service object
     * after the binding drops (e.g. through an unfinished foreground
     * promotion the daemon's own onStopped hadn't yet released).
     */
    private void teardownPluginService() {
        Context ctx = appContext;
        if (ctx == null) return;
        // Unregister the callback BEFORE unbinding. The plugin's
        // RemoteCallbackList only evicts entries via linkToDeath (which
        // fires on remote process death, not on a graceful in-process
        // unbind from a still-alive main app). Without the explicit
        // unregisterCallback, each bind/unbind cycle within the same main
        // process lifetime accumulates a fresh IMgTorCallback.Stub on the
        // plugin side; every subsequent listener dispatch then fans out
        // to every stale stub too, multiplying both work and the strong
        // references holding this MgTorClient alive.
        IMgTorService s = service;
        if (s != null) {
            try { s.unregisterCallback(remoteCallback); } catch (Throwable ignored) {}
        }
        // Try-unbind first so the plugin Service can shutdown cleanly once
        // the start-count drops; stopService is best-effort and silent if
        // the package was meanwhile uninstalled.
        try { ctx.unbindService(serviceConnection); } catch (Throwable ignored) {}
        try {
            Intent i = new Intent(BIND_ACTION);
            i.setPackage(pluginPackage());
            ctx.stopService(i);
        } catch (Throwable t) { FileLog.e(t); }
        // Drop the plugin-install watcher: the user opted out (or hit a
        // terminal failure), so a future install shouldn't auto-trigger a
        // bind they no longer want. registerPluginInstallReceiver re-arms
        // it on the next bindNow that needs it.
        BroadcastReceiver r = pluginInstallReceiver;
        if (r != null) {
            try { ctx.unregisterReceiver(r); } catch (Throwable ignored) {}
            pluginInstallReceiver = null;
        }
        bindRequested = false;
        bound = false;
        service = null;
        lastPushedPausedCount = Integer.MIN_VALUE;
    }

    /** Toggle-on path from settings: snapshot, pin stub, start binding, prompt install if missing. */
    public void userInitiatedStart() {
        ensureWorker();
        workerHandler.post(() -> {
            snapshotCurrentProxy();
            try { commitBlockingStubToDisk(); } catch (Throwable t) { FileLog.e(t); }
            try { SharedConfig.publishMgInternalTorProxy(BLOCKING_STUB_PORT); }
            catch (Throwable t) { FileLog.e(t); }
            // Reset the abortive-bind counter on every user-initiated start
            // so the toggle off/on cycle is a working recovery handle from
            // a previous "plugin crash loop" ERROR. requestStartForPushFallback
            // does NOT reset because push wake-ups are not user intent and
            // would otherwise burn Android 14+ FGS-start quota on every
            // wake while the plugin is still broken.
            consecutiveAbortiveBinds = 0;
            pendingStart = true;
            // Already bound from a prior cold-start init? Drive the AIDL start
            // directly; otherwise scheduleBind which consumes pendingStart in
            // handleConnected once onServiceConnected fires. No re-issue of
            // startForegroundService — bind-only design: the plugin's
            // binder.start runs on a binder pool thread with the plugin
            // process in BOUND_TOP (main is foreground here, just tapped the
            // toggle), so its internal promoteForeground call can directly
            // Service.startForeground without a pre-opened FGS-start grace
            // window.
            IMgTorService s = service;
            if (s != null && bound) {
                pushTransportConfig(s);
                try { s.start(); pendingStart = false; }
                catch (RemoteException e) { FileLog.e(e); }
            } else {
                scheduleBind(0L);
            }
            // TODO(mg-tor-plugin): if plugin not installed, surface PLUGIN_NOT_INSTALLED for the install row.
        });
    }

    /** UnifiedPush wake-up fallback. Bind+start if not already running; caller holds the wake lock. */
    public void requestStartForPushFallback() {
        ensureWorker();
        workerHandler.post(() -> {
            if (!SharedConfig.mg_useTor) return;
            // Don't keep retrying after the plugin crash-loop cap was hit:
            // every cycle rebinds + reconnects only to disconnect again,
            // pinning the wake lock for nothing. The user toggle off/on path
            // (userInitiatedStart) resets the counter, so this failure mode
            // is recoverable on user intent.
            if (consecutiveAbortiveBinds >= MAX_ABORTIVE_BINDS) return;
            pendingStart = true;
            IMgTorService s = service;
            if (s != null && bound) {
                // Bind-only: no startForegroundService re-issue needed.
                // Push fallback runs inside a UnifiedPush BroadcastReceiver
                // window (~10 s), which Android treats as a foreground
                // process state for the duration; the plugin process is
                // therefore BOUND_TOP and Service.startForeground inside
                // binder.start is allowed without prior precursor.
                pushTransportConfig(s);
                try { s.start(); pendingStart = false; }
                catch (RemoteException e) { FileLog.e(e); }
            } else {
                scheduleBind(0L);
            }
        });
    }

    /**
     * Hook from ConnectionsManager.setAppPaused. Aggregates per-account state
     * + VoIP, then forwards a single int to the plugin via AIDL. Plugin's
     * MgTorController interprets 0 = at least one active client, >0 = all
     * paused → arm idle-stop timer.
     */
    public void onAppPausedChanged(int currentAccount, int appResumeCount) {
        // Always update the per-account snapshot, even when mg_useTor is
        // OFF. Toggling Tor off mid-session and back on later would
        // otherwise leave accountResumeCounts pinned at the pre-disable
        // values; the next push would aggregate a phantom active account
        // and idle-stop would never arm. Gate only the AIDL push on
        // mg_useTor (the plugin can't be bound when Tor is off).
        accountResumeCounts.put(currentAccount, appResumeCount);
        if (!SharedConfig.mg_useTor) return;
        // ConnectionsManager.setAppPaused is invoked from UI threads
        // (BubbleActivity, ExternalActionActivity, LoginActivity, VoIPService).
        // Hop the per-account walk + AIDL push to the worker so the UI thread
        // doesn't pay for MAX_ACCOUNT_COUNT UserConfig.isClientActivated() +
        // VoIPService.getSharedInstance() on every foreground/background transition.
        ensureWorker();
        workerHandler.post(() -> {
            // App foreground is a bind-recovery trigger: after a long
            // background the plugin's idle-stop drops its foreground
            // promotion (no stopSelf), so the OS can reclaim the :tor
            // process. The resulting onServiceDisconnected can climb to the
            // MAX_ABORTIVE_BINDS cap and stop scheduling rebinds, leaving the
            // client unbound with no way back — pushAggregatedClientPaused
            // below early-returns when !bound. ensureBoundIfEnabled re-binds
            // (only when a client is actually active) so resume recovers Tor
            // without a relaunch. Compute the aggregate once and feed both
            // consumers so the per-account walk + VoIP probe runs a single
            // time per transition (and both see one consistent snapshot).
            final int aggregate = computeAggregatePaused();
            ensureBoundIfEnabled(aggregate);
            pushAggregatedClientPaused(aggregate);
        });
    }

    /**
     * Re-arm path: when the user switches mg_torIdleStopMinutes from 0
     * ("Never") back to a finite value, the plugin's setIdleStopMinutes
     * itself arms the ticker only if at least one client is currently paused.
     * To honour the new threshold immediately, recompute and re-push the
     * aggregate (effectively a no-op if nothing changed).
     */
    public void resumeIdleTickerIfNeeded() {
        if (!SharedConfig.mg_useTor) return;
        ensureWorker();
        workerHandler.post(() -> {
            lastPushedPausedCount = Integer.MIN_VALUE;
            pushAggregatedClientPaused();
        });
    }

    /**
     * Push the current {@link SharedConfig#mg_torIdleStopMinutes} value over
     * AIDL so the plugin's idle ticker reflects the user's choice without
     * waiting for the next bind cycle. No-op when not bound — the next
     * handleConnected() pushes the live value as part of the bind sequence.
     */
    public void pushIdleStopMinutesIfBound() {
        ensureWorker();
        workerHandler.post(() -> {
            IMgTorService s = service;
            if (s == null || !bound) return;
            try { s.setIdleStopMinutes(SharedConfig.mg_torIdleStopMinutes); }
            catch (RemoteException e) { FileLog.e(e); }
        });
    }

    /**
     * Push the current anti-censorship transport selection
     * ({@link SharedConfig#mg_torTransportMode} plus the obfs4
     * {@link SharedConfig#mg_torBridgeLines}) over AIDL. The plugin reads it only when it next assembles
     * its start() argv, so this is pushed right before every start() and on
     * every bind. A live transport change while Tor is running needs a daemon
     * restart (stop + start) for the new argv to take effect; the settings
     * screen drives that. Callers hold the worker thread; the handleConnected
     * version gate guarantees a bound plugin has this AIDL method.
     */
    private void pushTransportConfig(IMgTorService svc) {
        if (svc == null) return;
        try { svc.setTransportConfig(SharedConfig.mg_torTransportMode, SharedConfig.mg_torBridgeLines); }
        catch (RemoteException e) { FileLog.e(e); }
    }

    /**
     * Apply a transport-mode change made in settings while Tor may be running.
     * No-op when Tor is off or the plugin isn't bound: the next
     * userInitiatedStart picks up {@link SharedConfig#mg_torTransportMode}
     * anyway. When bound, push the new value and stop the daemon; the blocking
     * stub stays pinned across the gap (onStopped re-pins it while mg_useTor is
     * on) and onStopped relaunches the daemon with the fresh argv.
     */
    public void applyTransportChange() {
        if (!SharedConfig.mg_useTor) return;
        ensureWorker();
        workerHandler.post(() -> {
            IMgTorService s = service;
            if (s == null || !bound) return;
            pushTransportConfig(s);
            restartForTransportRequested = true;
            try {
                s.stop();
            } catch (RemoteException e) {
                FileLog.e(e);
                restartForTransportRequested = false;
            }
        });
    }

    // Aggregate per-account foreground state + VoIP into the plugin's
    // paused-count contract: 0 = at least one client active, 1 = all paused.
    // VoIP active overrides per-account state — a call must keep tor up even
    // if every account reports paused. Always invoked on workerHandler.
    private int computeAggregatePaused() {
        boolean anyActive = VoIPService.getSharedInstance() != null;
        if (!anyActive) {
            for (Map.Entry<Integer, Integer> e : accountResumeCounts.entrySet()) {
                Integer count = e.getValue();
                if (count == null || count <= 0) continue;
                int acct = e.getKey();
                if (acct < 0 || acct >= UserConfig.MAX_ACCOUNT_COUNT) continue;
                try {
                    if (UserConfig.getInstance(acct).isClientActivated()) {
                        anyActive = true;
                        break;
                    }
                } catch (Throwable t) { FileLog.e(t); }
            }
        }
        return anyActive ? 0 : 1;
    }

    // App-foreground bind-recovery: when Tor is enabled but the binding was
    // lost (plugin :tor process reclaimed after an idle-stop dropped its
    // foreground promotion, possibly after the abortive-bind cap latched the
    // client into ERROR), nothing else re-binds on resume — onAppPausedChanged
    // only pushed the paused-aggregate, which no-ops while unbound. Kick a
    // fresh bind here. Always invoked on workerHandler. `aggregate` is the
    // caller's computeAggregatePaused() snapshot (0 = a client is active).
    private void ensureBoundIfEnabled(int aggregate) {
        if (!SharedConfig.mg_useTor) return;
        // Already bound (pushAggregatedClientPaused handles the live case) or a
        // bind/backoff is already in flight — don't race it.
        if (bound || bindRequested) return;
        // Only recover-bind when a client is actually active. onAppPausedChanged
        // fires on background transitions too; recovering while everyone is
        // paused would resurrect Tor in the background and defeat idle-stop.
        if (aggregate != 0) return;
        // A genuine user-foreground with an active client is fresh intent, so
        // clear the terminal abortive-bind latch (mirrors userInitiatedStart)
        // and the sticky ERROR (mirrors addProgressListener) — but leave the
        // genuine terminal plugin states (NOT_INSTALLED / SIGNATURE_MISMATCH /
        // OUTDATED) for the bind path to re-derive.
        consecutiveAbortiveBinds = 0;
        if (state == State.ERROR) updateState(State.UNKNOWN);
        // Re-arm so handleConnected fires svc.start() on the rebind instead of
        // landing as a bare probe.
        pendingStart = true;
        scheduleBind(0L);
    }

    // Always invoked on workerHandler. Callers post first if they're on
    // another thread.
    private void pushAggregatedClientPaused() {
        pushAggregatedClientPaused(computeAggregatePaused());
    }

    // Takes a pre-computed aggregate to avoid a redundant account walk.
    private void pushAggregatedClientPaused(int aggregate) {
        if (aggregate == lastPushedPausedCount) return;
        lastPushedPausedCount = aggregate;
        IMgTorService s = service;
        if (s == null || !bound) return;
        try { s.onClientPausedChanged(aggregate); }
        catch (RemoteException e) { FileLog.e(e); }
    }

    // ---- Progress listeners ----

    // Signatures preserved from the pre-plugin MgTorController so the settings
    // activity's anonymous subclass keeps compiling untouched. All methods are
    // default no-ops so callers only override what they need.
    public interface ProgressListener {
        default void onState(State state) {}
        default void onProgress(int percent, String tag, String summary) {}
        default void onReady(int socksPort) {}
        default void onFailed(String reason) {}
    }

    public void addProgressListener(ProgressListener l) {
        if (l == null) return;
        listeners.addIfAbsent(l);
        // A terminal "plugin missing" / "wrong-sig" / "outdated" / "crash loop"
        // state from a prior bind attempt is sticky — teardownPluginService
        // clears bind state but not the State enum. A user who installed
        // (or replaced) the plugin since then would otherwise see the
        // bootstrap dialog dismiss immediately on listener-replay and re-
        // surface promptInstallOrUpdatePlugin even though the plugin is now
        // present. Re-probe the PackageManager synchronously and clear the
        // stale terminal so the listener replay sees UNKNOWN; the next bind
        // cycle drives state through BOUND_IDLE / STARTING / BOOTSTRAPPING.
        // Done under the listener attach so it covers both the dialog open
        // path and any other late listener that races a fresh install.
        State cur = state;
        if ((cur == State.PLUGIN_NOT_INSTALLED
                || cur == State.PLUGIN_OUTDATED
                || cur == State.PLUGIN_SIGNATURE_MISMATCH
                || cur == State.ERROR)
                && isPluginInstalled()) {
            updateState(State.UNKNOWN);
            cur = State.UNKNOWN;
        }
        // Replay current state immediately so the UI renders the right row without an event.
        final State capture = cur;
        AndroidUtilities.runOnUIThread(() -> {
            try { l.onState(capture); } catch (Throwable t) { FileLog.e(t); }
        });
    }

    public void removeProgressListener(ProgressListener l) {
        if (l == null) return;
        listeners.remove(l);
    }

    // ---- Plugin lifecycle / state ----

    public enum State {
        /** Starting state before any bind/install probe has happened. The
         *  bootstrap-dialog listener treats this as "unknown, keep waiting"
         *  rather than dismissing + showing the install prompt. */
        UNKNOWN,
        PLUGIN_NOT_INSTALLED,
        PLUGIN_OUTDATED,
        PLUGIN_SIGNATURE_MISMATCH,
        BOUND_IDLE,
        BOOTSTRAPPING,
        READY,
        ERROR,
    }

    public State getState() { return state; }
    public int getBootstrapPercent() { return bootstrapPercent; }
    public int getSocksPort() { return socksPort; }

    /**
     * Where to send the user to install / update the plugin.
     * Route by main's own signing cert as the distribution-channel signal:
     *  - main signed with the developer release cert → GitHub releases.
     *    Use the tag matching this main's versionName (set by
     *    gradle/mg-version.gradle) instead of /releases/latest, which
     *    GitHub server-filters to non-prerelease — a 5-dotted prerelease
     *    main would otherwise land on a stable-only page with a
     *    versionCode-mismatched plugin APK.
     *  - otherwise → F-Droid plugin page. Either channel of the dual-key
     *    allowlist accepts the bind, so cross-channel installs work on
     *    API 31+ — but staying on the same channel keeps versionCodes in
     *    lockstep release-for-release.
     */
    public Intent buildPluginInstallIntent() {
        Uri uri;
        if (MgUpdateChecker.isFdroidBuild()) {
            uri = Uri.parse("https://f-droid.org/packages/" + PLUGIN_PACKAGE_BASE + "/");
        } else {
            String tag = MgUpdateChecker.currentInstallVersion();
            uri = (tag != null && !tag.isEmpty())
                    ? Uri.parse("https://github.com/Mercurygram/Mercurygram/releases/tag/" + tag)
                    : Uri.parse("https://github.com/Mercurygram/Mercurygram/releases");
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    // ---- Internal: worker thread + bind / rebind ----

    private static void ensureWorker() {
        if (workerHandler != null) return;
        synchronized (MgTorClient.class) {
            if (workerHandler == null) {
                HandlerThread t = new HandlerThread("MgTorClient-bind");
                t.start();
                workerThread = t;
                workerHandler = new Handler(t.getLooper());
            }
        }
    }

    private void scheduleBind(long delayMs) {
        ensureWorker();
        workerHandler.postDelayed(this::bindNow, delayMs);
    }

    private void bindNow() {
        Context ctx = appContext;
        if (ctx == null || bindRequested) return;

        Intent i = new Intent(BIND_ACTION);
        i.setPackage(pluginPackage());
        // Bind-only — no startForegroundService. The plugin Service enters
        // foreground inside binder.start() from a binder pool thread; that
        // path is allowed without a paired Context.startForegroundService()
        // because the plugin process is in PROCESS_STATE_BOUND_TOP whenever
        // a foreground main holds the binding (and main is in foreground on
        // every legitimate entry: user toggle, cold-start Application init,
        // UnifiedPush BroadcastReceiver window). Skipping
        // startForegroundService here also eliminates the 5 s
        // ForegroundServiceDidNotStartInTimeException race on slow cold-
        // spawn of the :tor process (libmgtor + OpenSSL self-tests > 5 s
        // would otherwise expire the FGS-start grace window), and saves
        // the FGS-start quota on Android 14+ for paths that genuinely
        // need it (none currently).
        try {
            boolean ok = ctx.bindService(i, serviceConnection, Context.BIND_AUTO_CREATE);
            if (!ok) {
                updateState(State.PLUGIN_NOT_INSTALLED);
                // bindService==false means no ServiceConnection registration
                // happened, so unbindService here would throw
                // IllegalArgumentException. No paired startForegroundService
                // to clean up either — bind-only entry.
                bindRequested = false;
                // Watch for the plugin appearing so the user installing from
                // the prompt doesn't need an app restart to recover.
                registerPluginInstallReceiver();
                return;
            }
            bindRequested = true;
        } catch (SecurityException se) {
            // Signature-permission denial — main and plugin disagree on
            // signing identity. Cannot heal across reschedules: the OS
            // re-checks signatures on every bindService against the same
            // installed APKs, so scheduleRebindWithBackoff would loop
            // every 60 s burning battery + (paired) FGS-start quota for
            // the process lifetime. Treat as terminal; the plugin install
            // receiver is left armed for the PACKAGE_REPLACED case (key
            // rotation requires a coordinated double-release that ships
            // an updated plugin APK).
            FileLog.e(se);
            updateState(State.PLUGIN_SIGNATURE_MISMATCH);
            // pre-S OS treats `signature|knownSigner` as strict signature,
            // so any cross-key combination (F-Droid main + dev plugin OR
            // dev main + F-Droid plugin) deterministically lands here on
            // Android 11. preInit's isFdroidPreS() proactively force-rolls
            // mg_useTor for the F-Droid-main case; mirror the recovery
            // here for the converse (dev-main pre-S) so the user isn't
            // silently pinned on 127.0.0.1:1 with no UI affordance.
            // S+ deliberately falls through to terminalBindFailure with
            // mg_useTor preserved — at API 31+ knownSigner works, so a
            // SecurityException there is a real install mismatch the
            // user needs to resolve, not an OS limitation we can paper over.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                try {
                    if (!restoreSnapshottedProxy()) {
                        clearProxyOnDisk();
                        ConnectionsManager.setProxySettings(false, "", 0, "", "", "");
                    }
                    SharedConfig.clearMgInternalTorProxy();
                    if (SharedConfig.mg_useTor) SharedConfig.toggleMgUseTor();
                } catch (Throwable t) { FileLog.e(t); }
            }
            terminalBindFailure();
            registerPluginInstallReceiver();
        } catch (Throwable t) {
            FileLog.e(t);
            updateState(State.PLUGIN_NOT_INSTALLED);
            registerPluginInstallReceiver();
        }
    }

    private void scheduleRebindWithBackoff() {
        long delay = currentBackoffMs;
        currentBackoffMs = Math.min(currentBackoffMs * 2, REBIND_BACKOFF_CAP_MS);
        scheduleBind(delay);
    }

    // Listen for PACKAGE_ADDED/REPLACED for the plugin so a fresh install
    // (typically the user finishing the install-plugin prompt) triggers an
    // immediate rebind. Without this, the user would need to restart the
    // app — MTProto stays wedged on the blocking stub in the meantime.
    // One-shot: unregistered as soon as the targeted package event fires.
    private void registerPluginInstallReceiver() {
        if (pluginInstallReceiver != null) return;
        Context ctx = appContext;
        if (ctx == null) return;
        final String wantedPkg = pluginPackage();
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (intent == null || intent.getData() == null) return;
                String pkg = intent.getData().getSchemeSpecificPart();
                if (!wantedPkg.equals(pkg)) return;
                try { c.unregisterReceiver(this); } catch (Throwable ignored) {}
                pluginInstallReceiver = null;
                if (SharedConfig.mg_useTor) {
                    pendingStart = true;
                    scheduleBind(0L);
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                ctx.registerReceiver(r, f);
            }
            pluginInstallReceiver = r;
        } catch (Throwable t) { FileLog.e(t); }
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            workerHandler.post(() -> handleConnected(binder));
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            workerHandler.post(() -> {
                service = null;
                bound = false;
                // Explicitly unbind before rescheduling. Without this, a
                // stale ServiceConnection registration accumulates against
                // the LoadedApk service map and a subsequent bindService
                // may either short-circuit silently or trigger
                // "Service not registered" mismatches on unbind later.
                Context ctx = appContext;
                if (ctx != null) {
                    try { ctx.unbindService(serviceConnection); } catch (Throwable ignored) {}
                }
                bindRequested = false;
                lastPushedPausedCount = Integer.MIN_VALUE;
                if (++consecutiveAbortiveBinds >= MAX_ABORTIVE_BINDS) {
                    // Crash loop: a healthy plugin reinstall fires
                    // PACKAGE_REPLACED → registerPluginInstallReceiver
                    // path → fresh bind. Cap is recoverable, not permanent.
                    pendingStart = false;
                    updateState(State.ERROR);
                    dispatchError("plugin crash loop");
                    registerPluginInstallReceiver();
                    return;
                }
                // If the user still wants Tor running, re-arm pendingStart
                // so handleConnected fires svc.start() on the rebind.
                // Without this re-arm, a disconnect (e.g. PACKAGE_REPLACED
                // on plugin update) leaves the rebind as a probe — the
                // plugin would come back up but Tor would never restart
                // because main never calls binder.start().
                if (SharedConfig.mg_useTor) pendingStart = true;
                scheduleRebindWithBackoff();
            });
        }
        // onBindingDied fires (not onServiceDisconnected) when the binding
        // is permanently invalid — plugin uninstalled mid-session, or
        // hosting process crash-loops past Android's threshold. Without this
        // override the wrapper would stay bound=true with a dead handle and
        // never reschedule a rebind.
        @Override public void onBindingDied(ComponentName name) {
            workerHandler.post(() -> {
                Context ctx = appContext;
                if (ctx != null) {
                    try { ctx.unbindService(serviceConnection); } catch (Throwable ignored) {}
                }
                service = null;
                bound = false;
                bindRequested = false;
                lastPushedPausedCount = Integer.MIN_VALUE;
                if (++consecutiveAbortiveBinds >= MAX_ABORTIVE_BINDS) {
                    pendingStart = false;
                    updateState(State.ERROR);
                    dispatchError("plugin crash loop");
                    registerPluginInstallReceiver();
                    return;
                }
                if (SharedConfig.mg_useTor) {
                    pendingStart = true;
                    // Plugin likely uninstalled — also arm the install
                    // receiver so a reinstall triggers an immediate rebind
                    // even if our backoff hasn't fired yet.
                    registerPluginInstallReceiver();
                }
                scheduleRebindWithBackoff();
            });
        }
        // onNullBinding fires when the Service's onBind returned null —
        // the plugin actively refused us (authorization check, build
        // misconfig). Treat as a terminal error rather than retry-forever.
        @Override public void onNullBinding(ComponentName name) {
            workerHandler.post(() -> {
                Context ctx = appContext;
                if (ctx != null) {
                    try { ctx.unbindService(serviceConnection); } catch (Throwable ignored) {}
                    // Belt-and-braces stopService: bind-only entry means
                    // unbind alone should tear the Service down, but if the
                    // plugin's binder.start had already self-promoted to
                    // foreground via Service.startForeground before
                    // onNullBinding fired (race window between binder
                    // connect and the null-binding signal), stopService
                    // forces the OS to release the still-foreground Service
                    // and reclaim the :tor process.
                    try {
                        Intent i = new Intent(BIND_ACTION);
                        i.setPackage(pluginPackage());
                        ctx.stopService(i);
                    } catch (Throwable t) { FileLog.e(t); }
                }
                service = null;
                bound = false;
                bindRequested = false;
                pendingStart = false;
                updateState(State.ERROR);
            });
        }
    };

    private void handleConnected(IBinder binder) {
        IMgTorService svc = IMgTorService.Stub.asInterface(binder);
        if (svc == null) { updateState(State.ERROR); return; }
        // Race guard: a user toggle-off (or any other teardown path) can
        // post stop() → restoreOnDisable → teardownPluginService BEFORE
        // the onServiceConnected workerHandler hop lands here. teardown
        // clears bindRequested and unbinds the ServiceConnection; if we
        // still proceeded we would resurrect service/bound=true on a
        // binding the OS has already invalidated, register a new callback
        // on the plugin Service main just unregistered, and the next
        // userInitiatedStart fast path would call svc.start() on a stale
        // binder. Bail out and defensively re-tear-down the Service in
        // case bindService had a side effect we didn't expect.
        if (!bindRequested || !SharedConfig.mg_useTor) {
            Context ctx = appContext;
            if (ctx != null) {
                try { ctx.unbindService(serviceConnection); } catch (Throwable ignored) {}
                if (!SharedConfig.mg_useTor) {
                    try {
                        Intent stopI = new Intent(BIND_ACTION);
                        stopI.setPackage(pluginPackage());
                        ctx.stopService(stopI);
                    } catch (Throwable t) { FileLog.e(t); }
                }
            }
            return;
        }
        // Reset backoff on every successful bind so the next disconnect
        // retries promptly. Without this, a built-up cap from prior
        // disconnects (60s) would punish a fresh, healthy reconnect.
        currentBackoffMs = REBIND_BACKOFF_INITIAL_MS;
        if (!verifyPluginSignature()) {
            updateState(State.PLUGIN_SIGNATURE_MISMATCH);
            terminalBindFailure();
            return;
        }
        try {
            int v = svc.getPluginVersion();
            // v is the per-ABI versionCode; /10 drops the ABI offset to
            // compare in MG_VERSION_CODE units (see MIN_PLUGIN_MG_VERSION_CODE).
            if (v / 10 < MIN_PLUGIN_MG_VERSION_CODE) {
                updateState(State.PLUGIN_OUTDATED);
                terminalBindFailure();
                return;
            }
        } catch (RemoteException e) {
            FileLog.e(e);
            updateState(State.ERROR);
            safeUnbind();
            return;
        }

        // Register the callback BEFORE publishing service/bound so an
        // external reader (isAvailable, push fallback) observing
        // bound=true never sees a service whose callback list is not yet
        // populated. teardownPluginService relies on `service` to call
        // s.unregisterCallback before unbinding, so the error path below
        // sets `service` locally before calling teardownPluginService.
        try {
            svc.registerCallback(remoteCallback);
        } catch (RemoteException e) {
            // Drop both the binding AND the plugin Service. If the plugin's
            // binder.start had already self-promoted to foreground (race
            // between connect + the failing registerCallback), the bare
            // unbind would otherwise leave the foreground notification
            // pinned until OS reclaim. teardownPluginService unbinds +
            // stopService to drain that path; pendingStart is preserved so
            // the next bindNow re-attempts.
            FileLog.e(e);
            service = svc;
            updateState(State.ERROR);
            teardownPluginService();
            scheduleRebindWithBackoff();
            return;
        }
        service = svc;
        bound = true;
        updateState(State.BOUND_IDLE);
        try { svc.setIdleStopMinutes(SharedConfig.mg_torIdleStopMinutes); }
        catch (RemoteException e) { FileLog.e(e); }
        pushTransportConfig(svc);
        // Force a re-push so the plugin gets the current paused-aggregate as
        // soon as it's bound (lastPushedPausedCount may match a value from a
        // prior bind that the plugin's new instance has not seen).
        lastPushedPausedCount = Integer.MIN_VALUE;
        pushAggregatedClientPaused();

        // Plugin :tor process can outlive a main-app death (LMK reclaim,
        // crash + restart). On reconnect, the freshly-registered callback
        // missed any onBootstrapProgress / onReady events fired before the
        // bind landed, so synthesize them locally from the plugin's current
        // state. Without this main would stay BOUND_IDLE with socksPort=-1
        // forever, leaving ConnectionsManager wedged on the 127.0.0.1:1
        // blocking stub even though Tor is running.
        boolean alreadyRunning = adoptRunningPluginState(svc);

        if (pendingStart && SharedConfig.mg_useTor) {
            pendingStart = false;
            // svc.start() is documented idempotent — safe to call even
            // when the daemon is already up — but skipping it when the
            // plugin's already past STARTING avoids a redundant
            // promoteForeground notification flicker.
            if (!alreadyRunning) {
                try { svc.start(); } catch (RemoteException e) { FileLog.e(e); }
            }
        }
    }

    private boolean adoptRunningPluginState(IMgTorService svc) {
        try {
            int port = svc.getSocksPort();
            if (port > 0) {
                updateState(State.READY);
                dispatchReady(port);
                publishLiveProxy(port);
                return true;
            }
            int pct = svc.getBootstrapPercent();
            if (pct >= 0) {
                // Mid-bootstrap; subsequent genuine onBootstrapProgress
                // callbacks will refine the percent. Status string is
                // null because the plugin's getter exposes only the
                // numeric percent.
                updateState(State.BOOTSTRAPPING);
                dispatchProgress(pct, null);
                return true;
            }
        } catch (RemoteException e) { FileLog.e(e); }
        return false;
    }

    private void safeUnbind() {
        Context ctx = appContext;
        if (ctx == null) return;
        try { ctx.unbindService(serviceConnection); } catch (Throwable ignored) {}
        bindRequested = false;
        bound = false;
        service = null;
    }

    // Used on PLUGIN_SIGNATURE_MISMATCH / PLUGIN_OUTDATED: the plugin is
    // unusable as-is, so unbind + stopService so its foreground notification
    // and :tor process don't linger, AND clear pendingStart so the next
    // push wake-up / userInitiatedStart doesn't re-bind against the same
    // broken Service (flickering the notification on every retry).
    private void terminalBindFailure() {
        pendingStart = false;
        teardownPluginService();
    }

    // ---------------------------------------------------------------------
    // Internal: signature pinning
    // ---------------------------------------------------------------------

    private boolean verifyPluginSignature() {
        Context ctx = appContext;
        if (ctx == null) return false;
        byte[][] allowed = allowedCertSha256();
        try {
            // Sanity: main must itself be signed by one of the allowed keys.
            // If not, main was rebuilt from source by a third party — refuse
            // to bind regardless of plugin signature, because the runtime
            // allowlist would no longer constrain who can claim to be us.
            byte[] own = singleSignerSha256(ctx.getPackageManager(), ctx.getPackageName());
            if (!containsCert(allowed, own)) return false;
            byte[] actual = singleSignerSha256(ctx.getPackageManager(), pluginPackage());
            return containsCert(allowed, actual);
        } catch (PackageManager.NameNotFoundException nf) {
            updateState(State.PLUGIN_NOT_INSTALLED);
            return false;
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    private static byte[][] allowedCertSha256() {
        byte[][] cached = allowedCertSha256Cache;
        if (cached != null) return cached;
        byte[][] out = new byte[ALLOWED_CERT_SHA256_HEX.length][];
        for (int i = 0; i < ALLOWED_CERT_SHA256_HEX.length; i++) {
            out[i] = decodeHex(ALLOWED_CERT_SHA256_HEX[i]);
        }
        allowedCertSha256Cache = out;
        return out;
    }

    private static boolean containsCert(byte[][] allowed, byte[] candidate) {
        if (candidate == null) return false;
        for (byte[] a : allowed) {
            if (a != null && Arrays.equals(a, candidate)) return true;
        }
        return false;
    }

    private static byte[] decodeHex(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }

    // Returns SHA-256 of the package's single current signing cert, or null
    // if the package is multi-signer (refused — Mercurygram is single-signer)
    // or its signing info is unavailable. NEVER walks
    // getSigningCertificateHistory(): rotation lineage would let a leaked
    // rotated-away cert validate against the pin.
    @Nullable
    private static byte[] singleSignerSha256(PackageManager pm, String pkg)
            throws PackageManager.NameNotFoundException, NoSuchAlgorithmException {
        Signature[] sigs;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            SigningInfo si = pi.signingInfo;
            if (si == null || si.hasMultipleSigners()) return null;
            sigs = si.getApkContentsSigners();
        } else {
            @SuppressWarnings("deprecation")
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
            sigs = pi.signatures;
        }
        if (sigs == null || sigs.length != 1) return null;
        return MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray());
    }

    // ---------------------------------------------------------------------
    // Internal: state dispatch
    // ---------------------------------------------------------------------

    private interface ListenerOp {
        void apply(ProgressListener l) throws Throwable;
    }

    private void dispatch(ListenerOp op) {
        for (ProgressListener l : listeners) {
            AndroidUtilities.runOnUIThread(() -> {
                try { op.apply(l); } catch (Throwable t) { FileLog.e(t); }
            });
        }
    }

    private void updateState(@NonNull State next) {
        state = next;
        dispatch(l -> l.onState(next));
    }

    private void dispatchProgress(int percent, String status) {
        bootstrapPercent = percent;
        // AIDL gives one status string; old ProgressListener split it into tag+summary.
        // Pass the status twice so consumers that only show one of them still render.
        dispatch(l -> l.onProgress(percent, status, status));
    }

    private void dispatchReady(int port) {
        socksPort = port;
        bootstrapPercent = 100;
        dispatch(l -> l.onReady(port));
    }

    private void dispatchError(String reason) {
        dispatch(l -> l.onFailed(reason));
    }

    // ---------------------------------------------------------------------
    // Internal: remote callback (binder-pool thread → worker thread)
    // ---------------------------------------------------------------------

    // Callbacks land on a Binder pool thread; every method hops to workerHandler before touching state.
    private final IMgTorCallback.Stub remoteCallback = new IMgTorCallback.Stub() {
        @Override public void onBootstrapProgress(int percent, String status) {
            workerHandler.post(() -> {
                // Daemon is making progress — process is alive and tor is
                // running. Clear the crash-loop counter so a transient
                // disconnect tomorrow gets the full rebind budget again.
                consecutiveAbortiveBinds = 0;
                updateState(State.BOOTSTRAPPING);
                dispatchProgress(percent, status);
            });
        }
        @Override public void onReady(int port) {
            workerHandler.post(() -> {
                // Reaching READY is the strongest signal the bind cycle was
                // successful; reset the abortive-bind counter so the next
                // disconnect (idle-stop process reclaim, plugin upgrade)
                // doesn't push us toward MAX_ABORTIVE_BINDS on a healthy
                // lifecycle.
                consecutiveAbortiveBinds = 0;
                updateState(State.READY);
                dispatchReady(port);
                publishLiveProxy(port);
            });
        }
        @Override public void onStopped(int exitCode, String reason) {
            workerHandler.post(() -> {
                onStoppedDeliveryCount++;
                bootstrapPercent = -1;
                socksPort = -1;
                // A clean onStopped (idle-stop, user stop, planned shutdown)
                // means the bind cycle was healthy — daemon spawned, ran,
                // and reported its terminal event. The subsequent
                // onServiceDisconnected when the :tor process is reclaimed
                // by the OS must not count toward MAX_ABORTIVE_BINDS;
                // without this reset a normal idle/reclaim/resume loop
                // would trip the "plugin crash loop" guard after 5 cycles.
                consecutiveAbortiveBinds = 0;
                updateState(State.BOUND_IDLE);
                // If mg_useTor is still on, revert to the blocking stub on
                // disk + native + in-memory so MTProto can't fall through to
                // direct between this stop and the next bootstrap.
                try {
                    if (SharedConfig.mg_useTor) {
                        commitBlockingStubToDisk();
                        SharedConfig.publishMgInternalTorProxy(BLOCKING_STUB_PORT);
                        ConnectionsManager.setProxySettings(true, "127.0.0.1", BLOCKING_STUB_PORT, "", "", "");
                        // Transport switched while running: the old daemon has
                        // now cleanly reported terminal, so relaunch with the
                        // fresh argv. userInitiatedStart re-pushes the transport
                        // config and calls start() on the still-bound service.
                        if (restartForTransportRequested) {
                            restartForTransportRequested = false;
                            userInitiatedStart();
                        }
                    } else {
                        SharedConfig.clearMgInternalTorProxy();
                        if (!restoreSnapshottedProxy()) {
                            clearProxyOnDisk();
                            ConnectionsManager.setProxySettings(false, "", 0, "", "", "");
                        }
                        // Daemon is down + user opted out: release the
                        // binding so the plugin's :tor process can exit.
                        teardownPluginService();
                    }
                } catch (Throwable t) { FileLog.e(t); }
            });
        }
        @Override public void onError(String reason) {
            workerHandler.post(() -> {
                updateState(State.ERROR);
                // Plugin signals the respawn cap with a stable English
                // literal; render the localized toast on the main side so
                // the user knows to toggle Tor off+on to retry. Without
                // this, MTProto sits wedged on the blocking stub with no
                // visible feedback after 3 consecutive native crashes.
                if (reason != null && reason.startsWith("respawn gave up")) {
                    AndroidUtilities.runOnUIThread(() -> {
                        try {
                            Context ctx = appContext != null ? appContext : ApplicationLoader.applicationContext;
                            if (ctx == null) return;
                            Toast.makeText(ctx,
                                    LocaleController.getString(R.string.MercurygramTorRespawnGaveUp),
                                    Toast.LENGTH_LONG).show();
                        } catch (Throwable t) { FileLog.e(t); }
                    });
                }
                dispatchError(reason);
            });
        }
    };

    /** Swap the blocking stub for the live SOCKS port and push it to ConnectionsManager. */
    private void publishLiveProxy(int port) {
        // publishMgInternalTorProxy mutates SharedConfig.proxyList and
        // SharedConfig.currentProxy. Defer it INSIDE the UI hop so the
        // user-toggle-off path (which clears proxyList + currentProxy on
        // its own UI-thread run) can't be silently overridden by a
        // worker-thread write that landed first. Same UI hop also ensures
        // the persistProxyPortToDisk + ConnectionsManager.setProxySettings
        // pair fire together with the in-memory list mutation, so no
        // intermediate state is observable to a UI re-render that lands
        // between them.
        AndroidUtilities.runOnUIThread(() -> {
            // Re-check on the UI thread: stop() (user-disable, idle-stop) can
            // land between the worker-thread READY flip and this dispatch.
            // Pushing the live SOCKS port after the user already opted out
            // would silently override their click.
            if (!SharedConfig.mg_useTor || state != State.READY) return;
            try {
                SharedConfig.publishMgInternalTorProxy(port);
                persistProxyPortToDisk(port);
                ConnectionsManager.setProxySettings(true, "127.0.0.1", port, "", "", "");
                lastPushedAccount = 0;
            } catch (Throwable t) { FileLog.e(t); }
        });
    }

    private static void persistProxyPortToDisk(int port) {
        SharedPreferences prefs = MessagesController.getGlobalMainSettings();
        prefs.edit()
                .putString("proxy_ip", "127.0.0.1")
                .putString("proxy_user", "")
                .putString("proxy_pass", "")
                .putString("proxy_secret", "")
                .putInt("proxy_port", port)
                .putBoolean("proxy_enabled", true)
                .apply();
    }
}
