package it.belloworld.mercurygram.tor;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.tgnet.ConnectionsManager;
import java.util.concurrent.ConcurrentHashMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

// MG: lifecycle controller for the embedded tor daemon. Single instance.
//
// Threat model: defeat the passive auth_key_id tracking vulnerability
// (https://www.occrp.org/en/news/review-confirms-telegram-tracking-vulnerability)
// by routing MTProto through tor so the long-lived per-account auth_key_id
// is never observable to network operators alongside the user's real IP.
// Non-MTProto HTTP traffic (CDN thumbnails, ExoPlayer streams, translate,
// updater, payment forms) does NOT carry auth_key_id and is intentionally
// left direct — torifying it would add round-trip latency for no privacy
// gain against this threat.
//
// Privacy invariant: while mg_useTor is true, MTProto must never connect
// directly. start() publishes a blocking proxy stub (loopback port 1 — never
// reachable) synchronously before any other action. The real SocksPort is
// swapped in only after tor reports "Bootstrapped 100%". On idle stop the
// blocking stub is reinstated so a wake-up between stop() and the next
// start() still cannot leak.
//
// Lifecycle:
//   start trigger  | who calls it
//   ---------------+------------------------------------------------
//   user enables   | MercurygramSettingsActivity
//   cold start     | ApplicationLoader (init() at process init)
//   app resume     | onAppPausedChanged(appResumeCount>0) — from
//                  | ConnectionsManager.setAppPaused()
//   push fallback  | UnifiedPushReceiver (resumeNetworkMaybe path)
//
//   stop trigger   | who calls it
//   ---------------+------------------------------------------------
//   user disables  | MercurygramSettingsActivity
//   idle debounce  | this class — mg_torIdleStopMinutes after all of:
//                  |   appResumeCount==0, VoIPService inactive
public final class MgTorController {

    private static final String TAG = "MgTor";
    private static final int BLOCKED_PORT_PLACEHOLDER = 1;
    private static final long IDLE_TICK_MS = 30_000L;
    // Bounded auto-respawn on unexpected daemon exit (OOM, native crash,
    // hostile co-resident SIGNAL SHUTDOWN). Without this the foreground
    // user stays wedged on the blocking stub until they pause/resume the
    // app — none of the existing start triggers (app-resume,
    // UnifiedPush, user re-toggle) fire while reading a chat. The 5 s
    // back-off matches daemon.join's ceiling so a fresh start cycle
    // never collides with a teardown still draining. The 3-strike cap
    // breaks an OOM-loop on memory-starved devices.
    private static final long RESPAWN_BACKOFF_MS = 5_000L;
    private static final int MAX_UNEXPECTED_RESPAWNS = 3;

    public enum State { STOPPED, STARTING, BOOTSTRAPPING, READY, STOPPING }

    public interface ProgressListener {
        void onProgress(int percent, String tag, String summary);
        void onReady(int socksPort);
        void onFailed(String reason);
    }

    private static volatile MgTorController instance;

    public static MgTorController getInstance() {
        MgTorController local = instance;
        if (local == null) {
            synchronized (MgTorController.class) {
                local = instance;
                if (local == null) {
                    instance = local = new MgTorController();
                }
            }
        }
        return local;
    }

    public static void init(Context ctx) {
        if (ctx == null) return;
        if (SharedConfig.mg_useTor && !MgTorNative.isAvailable()) {
            // libmgtor.so missing on a build that has mg_useTor=true persisted
            // (wrong-ABI sideload, broken local build, asset strip). preInit
            // should already have caught this on a normal cold start; the
            // explicit re-check here covers the hot path (no preInit) and
            // races where the lib goes missing between cold start and init.
            bailOutUnavailable();
            return;
        }
        if (SharedConfig.mg_useTor && MgTorNative.isAvailable()) {
            // start() opens loopback ServerSockets, asset-copies geoip files,
            // and commits SharedPreferences — all blocking I/O. Defer off the
            // main thread to avoid ANRing Application.onCreate.
            Utilities.globalQueue.postRunnable(() -> {
                try { getInstance().start(); } catch (Throwable t) { FileLog.e(t); }
            });
        }
    }

    // One-shot migration for users upgrading from the MgOrbotHelper era,
    // which persisted proxy_ip=127.0.0.1 + proxy_port=9050 + proxy_enabled=true
    // to route MTProto through a user-installed Orbot app. The embedded tor
    // daemon replaces that path; stale Orbot pref entries left on disk
    // would silently keep traffic flowing through Orbot (if still
    // installed) or break MTProto entirely (if not). Run BEFORE preInit
    // and the CM loop on cold start so we don't fight with our own stub.
    public static void migrateLegacyOrbotEntry() {
        SharedPreferences prefs = MessagesController.getGlobalMainSettings();
        if (prefs.getBoolean("mg_orbotMigrationV1Done", false)) return;
        if (!SharedConfig.mg_useTor) {
            String ip = prefs.getString("proxy_ip", "");
            int port = prefs.getInt("proxy_port", 0);
            if ("127.0.0.1".equals(ip) && port == 9050) {
                prefs.edit()
                        .putString("proxy_ip", "")
                        .putString("proxy_user", "")
                        .putString("proxy_pass", "")
                        .putString("proxy_secret", "")
                        .putInt("proxy_port", 1080)
                        .putBoolean("proxy_enabled", false)
                        .putBoolean("mg_orbotMigrationV1Done", true)
                        .commit();
                return;
            }
        }
        prefs.edit().putBoolean("mg_orbotMigrationV1Done", true).commit();
    }

    // Synchronously pin proxy_port to the unreachable loopback stub BEFORE
    // ConnectionsManager.init() reads the pref on cold start. Without this,
    // the previous session's persisted live SOCKS port (a now-dead ephemeral
    // that may have been rebound by another app) would be the first
    // native_setProxySettings target — a small but real leak window before
    // MgTorController.init() runs the real publishBlockingStub via start().
    public static void preInit() {
        if (!SharedConfig.mg_useTor) return;
        if (!MgTorNative.isAvailable()) {
            // Pinning the stub here would brick MTProto on every cold start:
            // init() can't launch tor without the native lib, no one would
            // ever swap the live SocksPort in, and MessagesController.init()
            // would loop on the dead 127.0.0.1:1 entry forever. Auto-disable
            // and clear the stub so the user falls back to direct MTProto.
            bailOutUnavailable();
            return;
        }
        SharedPreferences prefs = MessagesController.getGlobalMainSettings();
        prefs.edit()
                .putString("proxy_ip", "127.0.0.1")
                .putString("proxy_user", "")
                .putString("proxy_pass", "")
                .putString("proxy_secret", "")
                .putInt("proxy_port", BLOCKED_PORT_PLACEHOLDER)
                .putBoolean("proxy_enabled", true)
                .commit();
    }

    // Called when mg_useTor=true is persisted but the native lib isn't loadable.
    // Flips the toggle off and erases the persisted proxy entry so subsequent
    // ConnectionsManager reads see direct-MTProto config, not the blocking stub.
    // Snapshot restore is attempted first so a user who had a real proxy before
    // enabling Tor doesn't silently lose it.
    private static void bailOutUnavailable() {
        boolean wasOn = SharedConfig.mg_useTor;
        if (wasOn) {
            SharedConfig.toggleMgUseTor();
        }
        if (!restoreSnapshottedProxy()) {
            MessagesController.getGlobalMainSettings().edit()
                    .putString("proxy_ip", "")
                    .putString("proxy_user", "")
                    .putString("proxy_pass", "")
                    .putString("proxy_secret", "")
                    .putInt("proxy_port", 1080)
                    .putBoolean("proxy_enabled", false)
                    .commit();
            try {
                ConnectionsManager.setProxySettings(false, "", 0, "", "", "");
            } catch (Throwable ignored) {}
        }
        // Surface the auto-disable so the user understands the toggle flipped
        // itself off (wrong-ABI sideload, lib stripped, manifest mismatch).
        // Without this the privacy preference quietly vanishes between
        // launches. Toast.makeText with applicationContext queues until a
        // foreground activity exists, which is the right behaviour for a
        // cold-start path that runs before LaunchActivity is up.
        if (wasOn) {
            android.content.Context appCtx = ApplicationLoader.applicationContext;
            if (appCtx != null) {
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        android.widget.Toast.makeText(
                                appCtx,
                                org.telegram.messenger.LocaleController.getString(
                                        org.telegram.messenger.R.string.MercurygramTorUnavailable),
                                android.widget.Toast.LENGTH_LONG).show();
                    } catch (Throwable t) { FileLog.e(t); }
                });
            }
        }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<ProgressListener> listeners = new CopyOnWriteArrayList<>();
    private final Object lifecycleLock = new Object();
    private final Runnable idleTicker = this::idleCheck;
    // Last appResumeCount seen per account. setAppPaused() is per-instance, so
    // each account reports independently; tor stays up while ANY account is
    // foreground and goes down only when all are paused (debounced).
    private final ConcurrentHashMap<Integer, Integer> accountResumeCounts = new ConcurrentHashMap<>();

    private volatile State state = State.STOPPED;
    private Thread daemonThread;
    private MgTorBootstrap bootstrap;
    private Thread bootstrapThread;
    private int socksPort;
    private int controlPort;
    private File cookieFile;
    private long idleSinceMs = -1L;
    private int lastBootstrapPercent;
    // Number of back-to-back unexpected daemon exits without a successful
    // bootstrap in between. Reset on every onBootstrapReady. Capped at
    // MAX_UNEXPECTED_RESPAWNS to break OOM-loops on low-memory devices.
    private int unexpectedRespawnCount;

    private MgTorController() {}

    public State getState() { return state; }
    public int getSocksPort() { return socksPort; }
    public int getLastBootstrapPercent() { return lastBootstrapPercent; }

    // Filter accountResumeCounts through isClientActivated so a deleted account
    // whose stale entry stayed in the map can't keep tor "any-active" alive
    // forever (idle stop never fires, daemon stays up). UserConfig.clearConfig
    // flips currentUser to null synchronously on logout, so this check
    // correctly drops removed accounts on the next idleCheck/pause tick.
    private boolean anyAccountActive() {
        for (java.util.Map.Entry<Integer, Integer> e : accountResumeCounts.entrySet()) {
            Integer count = e.getValue();
            if (count == null || count <= 0) continue;
            int account = e.getKey();
            if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) continue;
            if (UserConfig.getInstance(account).isClientActivated()) return true;
        }
        return false;
    }

    public void addProgressListener(ProgressListener l) { listeners.addIfAbsent(l); }
    public void removeProgressListener(ProgressListener l) { listeners.remove(l); }

    // Called from ConnectionsManager.setAppPaused() (the MG hook). Drives the
    // debounced idle stop and the resume-time restart. start() is dispatched
    // to globalQueue to keep the setAppPaused call site cheap.
    public void onAppPausedChanged(int account, int appResumeCount) {
        if (!SharedConfig.mg_useTor) {
            cancelIdleStop();
            return;
        }
        accountResumeCounts.put(account, appResumeCount);
        if (anyAccountActive()) {
            cancelIdleStop();
            if (state == State.STOPPED) {
                // User-driven event (app resume) — reset the respawn budget
                // so a prior session's gave-up state doesn't silently wedge
                // the resume.
                Utilities.globalQueue.postRunnable(this::userInitiatedStart);
            }
        } else {
            scheduleIdleStop();
        }
    }

    // Called from UnifiedPushReceiver fallback path when an incoming push needs
    // MTProto. Idempotent — if tor is already up, returns immediately.
    // Routes via userInitiatedStart so a prior session's gave-up state (3
    // OOM-loops) doesn't silently swallow incoming push wake-ups. The cap
    // still holds across consecutive failures within this push cycle because
    // each unexpected exit re-increments unexpectedRespawnCount.
    public void requestStartForPushFallback() {
        if (!SharedConfig.mg_useTor) return;
        cancelIdleStop();
        if (state == State.STOPPED) {
            Utilities.globalQueue.postRunnable(this::userInitiatedStart);
        }
    }

    // User-initiated start path (toggle on, app resume) — resets the
    // auto-respawn budget so a prior session's 3-crash gave-up state does not
    // immediately silence the next legitimate launch. Push-fallback and the
    // auto-respawn callsite keep calling start() directly so the cap still
    // holds across consecutive failures within one user-driven session.
    public void userInitiatedStart() {
        unexpectedRespawnCount = 0;
        start();
    }

    public void start() {
        synchronized (lifecycleLock) {
            if (state != State.STOPPED) return;
            state = State.STARTING;
        }
        cancelIdleStop();
        lastBootstrapPercent = 0;

        // Synchronously block direct MTProto traffic BEFORE the daemon thread
        // starts. Subsequent native_resumeNetwork() calls will hit the
        // unreachable stub and fail safely instead of leaking.
        try {
            publishBlockingStub();
        } catch (Throwable t) {
            FileLog.e(t);
        }

        Context ctx = ApplicationLoader.applicationContext;
        File dataDir = new File(ctx.getFilesDir(), "tor");
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            failStart("could not create data dir: " + dataDir);
            return;
        }
        File geoip = ensureGeoIpAsset(ctx, "geoip");
        File geoip6 = ensureGeoIpAsset(ctx, "geoip6");
        // Stale cookie from a previous session would let MgTorBootstrap auth
        // before the new daemon rewrites it — delete preemptively so the
        // bootstrap waits for the fresh 32 bytes.
        File cookie = new File(dataDir, "control_auth_cookie");
        if (cookie.exists() && !cookie.delete()) {
            FileLog.e(TAG + " could not delete stale cookie: " + cookie);
        }
        cookieFile = cookie;

        final int sport, cport;
        try {
            sport = pickFreeLoopbackPort();
            cport = pickFreeLoopbackPort();
        } catch (IOException e) {
            failStart("port pick: " + e.getMessage());
            return;
        }
        socksPort = sport;
        controlPort = cport;

        List<String> argv = new ArrayList<>();
        argv.add("tor");
        argv.add("--SocksPort"); argv.add("127.0.0.1:" + sport);
        argv.add("--ControlPort"); argv.add("127.0.0.1:" + cport);
        // Cookie auth gates the control port against any co-resident app
        // with INTERNET permission that could otherwise loopback-connect,
        // AUTHENTICATE with no credential, and issue SIGNAL SHUTDOWN /
        // NEWNYM / hijack SOCKS / GETINFO leak our circuit state.
        argv.add("--CookieAuthentication"); argv.add("1");
        argv.add("--CookieAuthFile"); argv.add(cookie.getAbsolutePath());
        argv.add("--DataDirectory"); argv.add(dataDir.getAbsolutePath());
        if (geoip != null) { argv.add("--GeoIPFile"); argv.add(geoip.getAbsolutePath()); }
        if (geoip6 != null) { argv.add("--GeoIPv6File"); argv.add(geoip6.getAbsolutePath()); }
        argv.add("--AvoidDiskWrites"); argv.add("1");
        argv.add("--ClientOnly"); argv.add("1");
        argv.add("--SafeLogging"); argv.add("1");
        argv.add("--Log"); argv.add("notice stderr");
        argv.add("--RunAsDaemon"); argv.add("0");
        argv.add("--DisableNetwork"); argv.add("0");
        final String[] argvArr = argv.toArray(new String[0]);

        daemonThread = new Thread(() -> {
            try {
                int rc = MgTorNative.run(argvArr);
                if (org.telegram.messenger.BuildVars.LOGS_ENABLED) {
                    FileLog.d(TAG + " native exit rc=" + rc);
                }
            } catch (Throwable t) {
                FileLog.e(t);
            } finally {
                onDaemonExited();
            }
        }, "mg-tor");
        daemonThread.setDaemon(true);
        daemonThread.start();

        synchronized (lifecycleLock) {
            state = State.BOOTSTRAPPING;
        }
        bootstrap = new MgTorBootstrap(cport, cookie, new MgTorBootstrap.Listener() {
            @Override
            public void onBootstrapProgress(int percent, String tag, String summary) {
                lastBootstrapPercent = percent;
                AndroidUtilities.runOnUIThread(() -> {
                    for (ProgressListener l : listeners) {
                        try { l.onProgress(percent, tag, summary); } catch (Throwable t) { FileLog.e(t); }
                    }
                });
            }
            @Override
            public void onBootstrapReady() { MgTorController.this.onBootstrapReady(); }
            @Override
            public void onBootstrapFailed(String reason) { MgTorController.this.onBootstrapFailed(reason); }
        });
        bootstrapThread = new Thread(() -> {
            // Spin briefly until the control port binds (tor opens it during
            // configure_backtrace_handler / event-loop init — usually <250ms).
            for (int i = 0; i < 40 && state == State.BOOTSTRAPPING; i++) {
                try { Thread.sleep(250); } catch (InterruptedException ignored) { return; }
                try (java.net.Socket probe = new java.net.Socket()) {
                    probe.connect(new java.net.InetSocketAddress("127.0.0.1", cport), 250);
                    break;
                } catch (IOException ignored) {}
            }
            MgTorBootstrap b = bootstrap;
            if (b != null) b.run();
        }, "mg-tor-boot");
        bootstrapThread.setDaemon(true);
        bootstrapThread.start();
    }

    public void stop() {
        Thread daemon;
        synchronized (lifecycleLock) {
            if (state == State.STOPPED || state == State.STOPPING) return;
            state = State.STOPPING;
            daemon = daemonThread;
        }
        cancelIdleStop();

        MgTorBootstrap b = bootstrap;
        if (b != null) {
            b.cancel();
            bootstrap = null;
        }
        // Drop the synthetic proxy list entry — its port is now dead.
        try { SharedConfig.clearMgInternalTorProxy(); } catch (Throwable t) { FileLog.e(t); }
        // Reinstate the blocking stub so a wake-up arriving after stop() and
        // before the next start() cannot fall through to direct MTProto.
        if (SharedConfig.mg_useTor) {
            try { publishBlockingStub(); } catch (Throwable t) { FileLog.e(t); }
        } else {
            try {
                if (!restoreSnapshottedProxy()) {
                    // No snapshot exists — clear the persisted proxy entry
                    // explicitly. Without this commit the blocking stub
                    // (proxy_enabled=true, proxy_port=1) written by the
                    // last publishBlockingStub stays on disk and the next
                    // cold start has ConnectionsManager.init() loop on the
                    // dead loopback until something else clears it.
                    MessagesController.getGlobalMainSettings().edit()
                            .putString("proxy_ip", "")
                            .putString("proxy_user", "")
                            .putString("proxy_pass", "")
                            .putString("proxy_secret", "")
                            .putInt("proxy_port", 1080)
                            .putBoolean("proxy_enabled", false)
                            .commit();
                    ConnectionsManager.setProxySettings(false, "", 0, "", "", "");
                }
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
            } catch (Throwable t) { FileLog.e(t); }
        }
        // tor 0.4.8's public C API has no async shutdown hook. Drive shutdown
        // by sending "SIGNAL SHUTDOWN" over the already-authenticated control
        // port; tor's event loop catches it and tor_run_main() returns.
        sendControlShutdown(controlPort, cookieFile);
        try { MgTorNative.shutdown(); } catch (Throwable t) { FileLog.e(t); }

        if (daemon != null && Thread.currentThread() != daemon) {
            try { daemon.join(5_000); } catch (InterruptedException ignored) {}
        }
    }

    private static void sendControlShutdown(int port, File cookieFile) {
        if (port <= 0) return;
        String cookieHex = readCookieHex(cookieFile);
        String authLine = cookieHex.isEmpty()
                ? "AUTHENTICATE\r\n"
                : "AUTHENTICATE " + cookieHex + "\r\n";
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2_000);
            java.io.OutputStream out = s.getOutputStream();
            out.write((authLine + "SIGNAL SHUTDOWN\r\nQUIT\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            out.flush();
        } catch (java.io.IOException e) {
            // Control port may already be down (daemon mid-shutdown) — ignore.
        }
    }

    private static String readCookieHex(File cookieFile) {
        if (cookieFile == null || cookieFile.length() < 32) return "";
        try (FileInputStream fis = new FileInputStream(cookieFile)) {
            byte[] buf = new byte[32];
            int read = 0;
            while (read < buf.length) {
                int n = fis.read(buf, read, buf.length - read);
                if (n < 0) break;
                read += n;
            }
            if (read != buf.length) return "";
            StringBuilder sb = new StringBuilder(buf.length * 2);
            for (byte b : buf) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private void onDaemonExited() {
        boolean wasUnexpected;
        synchronized (lifecycleLock) {
            wasUnexpected = state != State.STOPPING;
            state = State.STOPPED;
        }
        socksPort = 0;
        controlPort = 0;
        // Unexpected exit (OOM, native crash, control-port SIGNAL SHUTDOWN
        // issued by a hostile co-resident app) while mg_useTor is still
        // true: re-pin the blocking stub so MTProto doesn't fall through to
        // the dead ephemeral port persisted by the prior onBootstrapReady.
        // Without this, the privacy invariant only holds along the planned
        // stop() path.
        if (wasUnexpected && SharedConfig.mg_useTor) {
            try { publishBlockingStub(); } catch (Throwable t) { FileLog.e(t); }
        }
        // Drop the synthetic proxy entry on any exit path — leaving it would
        // misrepresent "MTProto routing via Tor" while the daemon is dead.
        try {
            SharedConfig.clearMgInternalTorProxy();
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        } catch (Throwable t) { FileLog.e(t); }
        // Planned stop: nothing more to do. State is STOPPED, ready for the
        // next user/resume/push trigger to start fresh.
        if (!wasUnexpected) return;
        if (!SharedConfig.mg_useTor) return;
        // No account foreground: existing onAppPausedChanged restart path
        // will fire on the next resume — no need to wake the daemon while
        // the user is gone.
        if (!anyAccountActive()) return;
        unexpectedRespawnCount++;
        if (unexpectedRespawnCount > MAX_UNEXPECTED_RESPAWNS) {
            AndroidUtilities.runOnUIThread(() -> {
                Context appCtx = ApplicationLoader.applicationContext;
                if (appCtx == null) return;
                android.widget.Toast.makeText(
                        appCtx,
                        org.telegram.messenger.LocaleController.getString(
                                org.telegram.messenger.R.string.MercurygramTorRespawnGaveUp),
                        android.widget.Toast.LENGTH_LONG).show();
            });
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            if (!SharedConfig.mg_useTor) return;
            if (state != State.STOPPED) return;
            try { start(); } catch (Throwable t) { FileLog.e(t); }
        }, RESPAWN_BACKOFF_MS);
    }

    private void onBootstrapReady() {
        synchronized (lifecycleLock) {
            if (state != State.BOOTSTRAPPING) return;
            state = State.READY;
        }
        // Reaching READY clears the crash budget: only sequential failures
        // without a successful bootstrap should consume the cap.
        unexpectedRespawnCount = 0;
        final int port = socksPort;
        AndroidUtilities.runOnUIThread(() -> {
            // Re-check on the UI thread: stop() (user-disable, idle-stop) can
            // land between the state flip above and this dispatch. Persisting
            // the live SOCKS port then would route traffic via Tor after the
            // user already opted out — silently overriding their click.
            if (!SharedConfig.mg_useTor || state != State.READY) {
                return;
            }
            try {
                persistProxyPort(port);
                ConnectionsManager.setProxySettings(true, "127.0.0.1", port, "", "", "");
                // Surface a synthetic, non-editable entry in the proxy list
                // so the drawer/proxy-active indicator updates and the user
                // can verify "I'm proxied" without poking logs. Not persisted
                // (saveProxyList skips mgInternal entries) — the ephemeral
                // SOCKS port would be stale next session.
                SharedConfig.publishMgInternalTorProxy(port);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
            } catch (Throwable t) { FileLog.e(t); }
            for (ProgressListener l : listeners) {
                try { l.onReady(port); } catch (Throwable t) { FileLog.e(t); }
            }
        });
    }

    private void onBootstrapFailed(String reason) {
        if (org.telegram.messenger.BuildVars.LOGS_ENABLED) {
            FileLog.d(TAG + " bootstrap failed: " + reason);
        }
        AndroidUtilities.runOnUIThread(() -> {
            for (ProgressListener l : listeners) {
                try { l.onFailed(reason); } catch (Throwable t) { FileLog.e(t); }
            }
        });
        // Tear down the half-up daemon so the state machine returns to STOPPED
        // and MTProto can recover via the next app-resume / push wake / user
        // re-toggle. Without this stop() the state stays BOOTSTRAPPING, the
        // blocking stub stays pinned, and MTProto is wedged until process
        // restart. onDaemonExited's wasUnexpected branch re-pins the stub for
        // us, so the privacy invariant still holds across the teardown.
        Utilities.globalQueue.postRunnable(this::stop);
    }

    private void scheduleIdleStop() {
        if (!SharedConfig.mg_useTor) return;
        if (state == State.STOPPED || state == State.STOPPING) return;
        if (idleSinceMs < 0) idleSinceMs = System.currentTimeMillis();
        mainHandler.removeCallbacks(idleTicker);
        mainHandler.postDelayed(idleTicker, IDLE_TICK_MS);
    }

    private void cancelIdleStop() {
        idleSinceMs = -1L;
        mainHandler.removeCallbacks(idleTicker);
    }

    private void idleCheck() {
        if (!SharedConfig.mg_useTor) { cancelIdleStop(); return; }
        if (state == State.STOPPED || state == State.STOPPING) { cancelIdleStop(); return; }
        if (VoIPService.getSharedInstance() != null) {
            idleSinceMs = System.currentTimeMillis();
            mainHandler.postDelayed(idleTicker, IDLE_TICK_MS);
            return;
        }
        // mg_torIdleStopMinutes==0 = "Never (keep running)" sentinel — drop
        // the ticker entirely (saves a wakeup every IDLE_TICK_MS for the full
        // session). setMgTorIdleStopMinutes re-arms via resumeIdleTickerIfNeeded
        // on a later non-zero choice.
        int minutes = SharedConfig.mg_torIdleStopMinutes;
        if (minutes <= 0) {
            cancelIdleStop();
            return;
        }
        long thresholdMs = minutes * 60L * 1000L;
        long now = System.currentTimeMillis();
        if (idleSinceMs > 0 && now - idleSinceMs >= thresholdMs) {
            // stop() blocks up to 5s on daemon.join — dispatch off the
            // main looper to avoid jank on the idle tick.
            Utilities.globalQueue.postRunnable(this::stop);
            return;
        }
        mainHandler.postDelayed(idleTicker, IDLE_TICK_MS);
    }

    // Called from SharedConfig.setMgTorIdleStopMinutes when the user picks a
    // non-zero idle threshold after previously selecting "Never (keep running)".
    // idleCheck cancels the ticker on minutes==0, so a transition back to a
    // finite idle requires a re-arm — without it the user's new pick would
    // silently no-op until the next app-pause toggled idle scheduling on.
    public void resumeIdleTickerIfNeeded() {
        if (!SharedConfig.mg_useTor) return;
        if (state == State.STOPPED || state == State.STOPPING) return;
        if (SharedConfig.mg_torIdleStopMinutes <= 0) return;
        if (!anyAccountActive()) scheduleIdleStop();
    }

    private void publishBlockingStub() {
        persistProxyPort(BLOCKED_PORT_PLACEHOLDER);
        ConnectionsManager.setProxySettings(true, "127.0.0.1", BLOCKED_PORT_PLACEHOLDER, "", "", "");
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }

    private void persistProxyPort(int port) {
        // Mirrors MgOrbotHelper.activateProxy's persistence shape so that on
        // cold start MessagesController reads back a sane proxy entry and
        // MTProto attempts the (blocking-stub or live) port immediately.
        SharedPreferences globalSettings = MessagesController.getGlobalMainSettings();
        globalSettings.edit()
                .putString("proxy_ip", "127.0.0.1")
                .putString("proxy_user", "")
                .putString("proxy_pass", "")
                .putString("proxy_secret", "")
                .putInt("proxy_port", port)
                .putBoolean("proxy_enabled", true)
                .commit();
    }

    // Capture the user's current proxy config BEFORE the first publishBlockingStub
    // overwrites it. Called from MercurygramSettingsActivity at the user-initiated
    // enable. Idempotent within an enable cycle — re-calling at cold-start (where
    // proxy_ip is already 127.0.0.1) is a no-op.
    public static void snapshotCurrentProxy() {
        SharedPreferences prefs = MessagesController.getGlobalMainSettings();
        if (prefs.getBoolean("mg_tor_savedProxy_present", false)) return;
        String ip = prefs.getString("proxy_ip", "");
        // Don't snapshot our own stub. If init() runs before this method (it
        // shouldn't on a fresh enable, but guard anyway) the snapshot would
        // capture the loopback stub and "restore" the user back to it.
        if ("127.0.0.1".equals(ip)) return;
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

    // Restore + clear the snapshot. Called from stop()'s mg_useTor==false
    // branch (user-initiated disable). Returns true if a snapshot was
    // restored, false if there was none (caller falls back to clearing the
    // proxy entry, matching the prior behavior).
    private static boolean restoreSnapshottedProxy() {
        SharedPreferences prefs = MessagesController.getGlobalMainSettings();
        if (!prefs.getBoolean("mg_tor_savedProxy_present", false)) return false;
        String ip = prefs.getString("mg_tor_savedProxy_ip", "");
        int port = prefs.getInt("mg_tor_savedProxy_port", 1080);
        String user = prefs.getString("mg_tor_savedProxy_user", "");
        String pass = prefs.getString("mg_tor_savedProxy_pass", "");
        String secret = prefs.getString("mg_tor_savedProxy_secret", "");
        boolean enabled = prefs.getBoolean("mg_tor_savedProxy_enabled", false);
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
        return true;
    }

    private void failStart(String reason) {
        onBootstrapFailed(reason);
        onDaemonExited();
    }

    private static int pickFreeLoopbackPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return s.getLocalPort();
        }
    }

    private static File ensureGeoIpAsset(Context ctx, String name) {
        File out = new File(new File(ctx.getFilesDir(), "tor"), name);
        if (out.exists() && out.length() > 0) return out;
        try (InputStream in = ctx.getAssets().open("tor/" + name);
             OutputStream o = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return out;
        } catch (IOException e) {
            // Assets bundled by jni/build_tor.sh (next session). Without them
            // tor still boots, just without country-aware circuit selection.
            return null;
        }
    }
}
