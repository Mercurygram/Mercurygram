package it.belloworld.mercurygram.plugin.tor;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

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
// PLUGIN EXTRACTION NOTE: this used to be a god-class living in the main
// :TMessagesProj module that handled BOTH the tor daemon and main-app proxy
// publication / settings UI / NotificationCenter dispatch / accounts. After
// the plugin extraction, ONLY the daemon side stays here. Main-side
// concerns (SharedConfig.mg_torProxyAddress, synthetic proxy list entries,
// ConnectionsManager.setProxySettings, snapshot/restore of the user's
// previous proxy, legacy MgOrbotHelper migration, account-aware idle stop)
// move to a new MgTorClient in main that talks to this controller over the
// AIDL bridge (IMgTorService + IMgTorCallback).
//
// Threat model (still informative for clients): defeat the passive
// auth_key_id tracking vulnerability by routing MTProto through tor. The
// privacy invariant (blocking stub before / between / after tor sessions)
// must now be enforced by MgTorClient on the main side; this plugin only
// surfaces the SOCKS port via Listener.onReady once tor reports
// "Bootstrapped 100%".
//
// Lifecycle (daemon-side only):
//   start trigger  | who calls it
//   ---------------+------------------------------------------------
//   bind+start()   | MgTorService (via IMgTorService.start)
//   push fallback  | MgTorService.requestStartForPushFallback shim
//
//   stop trigger   | who calls it
//   ---------------+------------------------------------------------
//   client stop()  | MgTorService (via IMgTorService.stop)
//   idle debounce  | this class — idleStopMinutes after onClientPausedChanged
//                  | reports zero active clients
public final class MgTorController {

    private static final String TAG = "MgTor";
    private static final long IDLE_TICK_MS = 30_000L;
    // Bounded auto-respawn on unexpected daemon exit (OOM, native crash,
    // hostile co-resident SIGNAL SHUTDOWN). The 5 s back-off matches
    // daemon.join's ceiling so a fresh start cycle never collides with a
    // teardown still draining. The 3-strike cap breaks an OOM-loop on
    // memory-starved devices.
    private static final long RESPAWN_BACKOFF_MS = 5_000L;
    private static final int MAX_UNEXPECTED_RESPAWNS = 3;

    public enum State { STOPPED, STARTING, BOOTSTRAPPING, READY, STOPPING }

    // Plugin-internal listener contract. MgTorService wraps this and forwards
    // each event over IMgTorCallback to the main app. We deliberately avoid
    // calling AIDL types here so MgTorController stays unit-testable without
    // a binder thread.
    public interface Listener {
        void onBootstrapProgress(int percent, String status);
        void onReady(int socksPort);
        void onStopped(int exitCode, String reason);
        void onError(String reason);
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

    // Plugin entrypoint. Called by MgTorService.onCreate with the Service
    // Context. Stores the application Context for asset/files access and
    // bails early if the native lib isn't loadable so MgTorService can
    // surface a clear error via onError before any client start() arrives.
    public void init(Context ctx) {
        if (ctx == null) return;
        this.appContext = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        // Defer the MgTorNative.isAvailable() probe to a worker. The first
        // access to MgTorNative triggers its static initializer →
        // System.loadLibrary("mgtor"), which on a fresh :tor process spawn
        // can spend hundreds of ms inside the linker + statically-linked
        // OpenSSL self-tests (worse under cold storage / memory pressure).
        // Running that on the Service main thread eats into the 5 s FGS-
        // start grace window that opens when MgTorClient calls
        // startForegroundService — onStartCommand can then miss its
        // promoteForeground call and the OS raises
        // ForegroundServiceDidNotStartInTimeException. The lib still loads
        // lazily on start()'s own thread (a binder pool worker, never the
        // Service main thread), so this only changes WHEN the unavailable
        // dispatchError fires for broken builds — same semantics, off the
        // critical path.
        Thread probe = new Thread(() -> {
            if (!MgTorNative.isAvailable()) {
                // libmgtor.so missing on this build/ABI. Surface via
                // listeners; start() will refuse cleanly.
                dispatchError("native library unavailable");
            }
        }, "mg-tor-lib-probe");
        probe.setDaemon(true);
        probe.start();
    }

    // Optional pre-init hook retained for symmetry with the legacy main-side
    // API. The plugin has no SharedPreferences to pre-pin (the blocking-stub
    // invariant moved to MgTorClient on the main side), so this is now a
    // no-op aside from the unavailable-lib early surface. Kept so MgTorService
    // can call it from onCreate before the first start() request lands.
    public void preInit(Context ctx) {
        init(ctx);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object lifecycleLock = new Object();
    private final Runnable idleTicker = this::idleCheck;

    // Background worker for blocking lifecycle transitions (start/stop, daemon
    // join). Replaces the main app's Utilities.globalQueue which is not
    // available in the plugin module. Lazily started on first use.
    private volatile HandlerThread workerThread;
    private volatile Handler workerHandler;

    // Application Context captured in init(). Used for getFilesDir() (tor data
    // directory) and getAssets() (geoip files). Volatile so a late init()
    // on a worker thread is visible to subsequent start() reads.
    private volatile Context appContext;

    // Aggregate "anyone needs Tor up?" counter pushed from MgTorService via
    // onClientPausedChanged. The plugin doesn't know about main-app accounts;
    // the main side aggregates per-account state and sends a single integer.
    private volatile int pausedClientCount;

    // Idle-stop threshold in minutes. 0 = "Never (keep running)". Pushed from
    // MgTorService via setIdleStopMinutes (forwarded from
    // SharedConfig.mg_torIdleStopMinutes on the main side).
    private volatile int idleStopMinutes;

    // Anti-censorship transport. Pushed from MgTorService.setTransportConfig
    // (forwarded from SharedConfig.mg_torTransportMode on the main side).
    // These int values are wire contract with the main app; keep in sync with
    // SharedConfig.MG_TOR_TRANSPORT_* on the main side.
    public static final int TRANSPORT_DIRECT = 0;     // vanilla Tor, no bridges
    public static final int TRANSPORT_SNOWFLAKE = 1;  // domain-fronted WebRTC
    public static final int TRANSPORT_OBFS4 = 2;      // lyrebird obfs4 bridges
    private volatile int transportMode = TRANSPORT_DIRECT;
    // obfs4 user-supplied bridge lines (newline-separated); unused for
    // direct/snowflake. Parsed by parseObfs4Bridges when start() assembles argv.
    private volatile String bridgeLines;

    // Stock Snowflake rendezvous bridges. Broker URL, domain-front CDNs, STUN
    // set and uTLS profile all ride in the Bridge line (modern Snowflake keeps
    // config out of the ClientTransportPlugin exec args). Copied verbatim from
    // the snowflake submodule's client/torrc at tag v2.14.1. Re-sync these on
    // every submodule bump: the broker/front values drift over time as CDNs
    // change. Two bridges for rendezvous redundancy.
    private static final String[] SNOWFLAKE_BRIDGES = {
        "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 "
            + "fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 "
            + "url=https://1098762253.rsc.cdn77.org/ fronts=www.cdn77.com,www.phpmyadmin.net "
            + "ice=stun:stun.antisip.com:3478,stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,"
            + "stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478,"
            + "stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443 utls-imitate=hellorandomizedalpn",
        "snowflake 192.0.2.4:80 8838024498816A039FCBBAB14E6F40A0843051FA "
            + "fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA "
            + "url=https://1098762253.rsc.cdn77.org/ fronts=www.cdn77.com,www.phpmyadmin.net "
            + "ice=stun:stun.antisip.com:3478,stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,"
            + "stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478,"
            + "stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443 utls-imitate=hellorandomizedalpn",
    };

    private volatile State state = State.STOPPED;
    private Thread daemonThread;
    // bootstrap is mutated outside lifecycleLock in start()/stop() and read
    // outside the lock in stop(). Without volatile a JMM-permitted stale
    // read in stop() could either miss the freshly-created bootstrap
    // (skip cancel → leak its socket + thread) or cancel a previous
    // session's stale instance. start()/stop() are called from worker +
    // binder threads, so cross-thread visibility is required.
    private volatile MgTorBootstrap bootstrap;
    private Thread bootstrapThread;
    // socksPort + controlPort are written in start() outside lifecycleLock
    // and read by binder pool threads via MgTorService.binder.getSocksPort
    // (no lock, no volatile). Without volatile the binder reader can
    // legally observe 0 (the post-onDaemonExited cleared value) or the
    // previous session's port; main would then route MTProto through a
    // stale port that another app may have rebound. volatile gives the
    // happens-before edge writes-to-reads needs.
    private volatile int socksPort;
    private volatile int controlPort;
    private File cookieFile;
    // idleSinceMs is written from worker (scheduleIdleStop /
    // cancelIdleStop, also invoked from binder pool via
    // onClientPausedChanged) and read from the main looper inside
    // idleCheck. Non-volatile long writes are not guaranteed atomic on
    // 32-bit ABIs (JLS §17.7); the plugin ships armeabi-v7a + x86 so a
    // torn read would compute now-idleSinceMs against garbage and either
    // tear tor down on the next tick or never tear it down.
    private volatile long idleSinceMs = -1L;
    private int lastBootstrapPercent;
    // Number of back-to-back unexpected daemon exits without a successful
    // bootstrap in between. Reset on every onBootstrapReady.
    private int unexpectedRespawnCount;

    private MgTorController() {}

    public State getState() { return state; }
    public int getSocksPort() { return socksPort; }
    public int getLastBootstrapPercent() { return lastBootstrapPercent; }

    public void addListener(Listener l) {
        if (l != null) listeners.addIfAbsent(l);
    }
    public void removeListener(Listener l) {
        if (l != null) listeners.remove(l);
    }

    // Forwarded from the AIDL setIdleStopMinutes. Stored unconditionally; the
    // idleCheck loop reads it each tick so a change takes effect immediately.
    public void setIdleStopMinutes(int minutes) {
        idleStopMinutes = Math.max(0, minutes);
        // A user transitioning from "Never (keep running)" (0) back to a finite
        // threshold needs the ticker re-armed: idleCheck cancels it on 0 so
        // without this the next non-zero pick would silently no-op until the
        // next client-paused event toggled idle scheduling on.
        if (idleStopMinutes > 0 && state != State.STOPPED && state != State.STOPPING
                && pausedClientCount > 0) {
            scheduleIdleStop();
        }
    }

    // Forwarded from the AIDL setTransportConfig. Read once per start() when the
    // argv is assembled, so a change only takes effect on the next daemon start
    // (the main side restarts Tor when the user switches transport). Stored
    // unconditionally; direct mode leaves the argv untouched.
    public void setTransportConfig(int mode, String bridgeLines) {
        this.transportMode = (mode < TRANSPORT_DIRECT || mode > TRANSPORT_OBFS4)
                ? TRANSPORT_DIRECT : mode;
        this.bridgeLines = bridgeLines;
    }

    // Called from MgTorService when the main app updates its aggregated
    // "no clients are active" count. pausedClientCount == 0 means at least
    // one client is foreground/active; > 0 means everyone is paused and the
    // idle timer should run.
    //
    // TODO(plugin-extract): the legacy API took (currentAccount, appResumeCount)
    // and the main side aggregated per-account here. Account aggregation now
    // lives on the main side (MgTorClient); the plugin only sees the
    // aggregate. If we later need per-client state (multiple bound apps),
    // revisit this signature.
    public void onClientPausedChanged(int pausedClientCount) {
        this.pausedClientCount = Math.max(0, pausedClientCount);
        if (this.pausedClientCount == 0) {
            cancelIdleStop();
            if (state == State.STOPPED) {
                // Client just came back to foreground; if the previous
                // session gave up on respawn, reset the budget so this
                // user-driven event isn't silenced.
                postWorker(this::userInitiatedStart);
            }
        } else {
            scheduleIdleStop();
        }
    }

    // Called from MgTorService when an incoming push needs MTProto routed
    // through tor. Idempotent — if tor is already up, returns immediately.
    // Routes via userInitiatedStart so a prior session's gave-up state (3
    // OOM-loops) doesn't silently swallow incoming push wake-ups.
    public void requestStartForPushFallback() {
        cancelIdleStop();
        if (state == State.STOPPED) {
            postWorker(this::userInitiatedStart);
        }
    }

    // User-initiated start path (toggle on, client resume, push wake-up).
    // The respawn cap is NOT reset here: every AIDL start() — including
    // push fallback and app-resume — routes through this method, and an
    // unconditional reset would defeat the cap (a flapping daemon could
    // burn battery indefinitely as push wake-ups keep clearing the budget).
    // The cap auto-resets on every successful onBootstrapReady, so the
    // happy path is unaffected; once MAX_UNEXPECTED_RESPAWNS is hit, the
    // user must toggle off (clearing the cached state via main) and back
    // on, OR wait for the next process restart, before auto-respawn kicks
    // in again.
    public void userInitiatedStart() {
        start();
    }

    public void start() {
        synchronized (lifecycleLock) {
            if (state != State.STOPPED) return;
            state = State.STARTING;
            // Reset visible state under the same lock as the STARTING flip
            // so a binder pool thread reading lastBootstrapPercent /
            // socksPort / controlPort during STARTING cannot observe the
            // PREVIOUS session's stale values. Without this:
            //   - getBootstrapPercent (state != STOPPED, returns
            //     lastBootstrapPercent) reports the prior 100, and main's
            //     adoptRunningPluginState thinks tor is already up and
            //     skips svc.start();
            //   - getSocksPort reads a stale port that another process
            //     may have rebound, and main routes MTProto through it.
            // Clearing under the lock makes the STARTING window report
            // 0% / no-port instead, which is correct for "starting up".
            lastBootstrapPercent = 0;
            socksPort = 0;
            controlPort = 0;
            cookieFile = null;
        }
        cancelIdleStop();

        Context ctx = appContext;
        if (ctx == null) {
            failStart("controller not initialized (call init(Context) first)");
            return;
        }
        if (!MgTorNative.isAvailable()) {
            failStart("native library unavailable");
            return;
        }

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
            Log.e(TAG, "could not delete stale cookie: " + cookie);
        }

        final int sport, cport;
        try {
            sport = pickFreeLoopbackPort();
            cport = pickFreeLoopbackPort();
        } catch (IOException e) {
            failStart("port pick: " + e.getMessage());
            return;
        }

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
        // Anti-censorship transport. Direct mode leaves the argv untouched
        // (unchanged behaviour for existing users). Snowflake registers the
        // pluggable-transport exec + stock rendezvous bridges so Tor can
        // bootstrap where direct connections are DPI-blocked.
        if (transportMode == TRANSPORT_SNOWFLAKE) {
            File sf = new File(ctx.getApplicationInfo().nativeLibraryDir, "libsnowflake.so");
            if (!sf.exists()) {
                // The transport binary ships in the plugin APK; a missing file
                // means a packaging regression. Fail loudly rather than silently
                // falling back to a direct connection that would hang forever
                // behind the censor the user is trying to defeat.
                failStart("snowflake transport binary missing: " + sf.getAbsolutePath());
                return;
            }
            argv.add("--UseBridges"); argv.add("1");
            argv.add("--ClientTransportPlugin");
            argv.add("snowflake exec " + sf.getAbsolutePath()
                    + " -log " + new File(dataDir, "snowflake.log").getAbsolutePath());
            for (String bridge : SNOWFLAKE_BRIDGES) {
                argv.add("--Bridge"); argv.add(bridge);
            }
        } else if (transportMode == TRANSPORT_OBFS4) {
            File pt = new File(ctx.getApplicationInfo().nativeLibraryDir, "libobfs4proxy.so");
            if (!pt.exists()) {
                // Same packaging-regression guard as snowflake above.
                failStart("obfs4 transport binary missing: " + pt.getAbsolutePath());
                return;
            }
            // Unlike snowflake, obfs4 has no stock bridges: each obfs4 bridge
            // carries a per-bridge cert/iat-mode, obtained out-of-band by the
            // user (e.g. Telegram's @GetBridgesBot) and pushed over AIDL. With
            // no lines there is nothing to connect to, so fail loudly rather
            // than start a UseBridges daemon that can never build a circuit.
            List<String> bridges = parseObfs4Bridges(bridgeLines);
            if (bridges.isEmpty()) {
                failStart("obfs4 selected but no bridge lines configured");
                return;
            }
            argv.add("--UseBridges"); argv.add("1");
            argv.add("--ClientTransportPlugin");
            // lyrebird registers the obfs4 transport; Tor sets the TOR_PT_*
            // env (incl. the managed state dir under DataDirectory) when it
            // execs the managed proxy, so no extra args are needed here.
            argv.add("obfs4 exec " + pt.getAbsolutePath());
            for (String bridge : bridges) {
                argv.add("--Bridge"); argv.add(bridge);
            }
        }
        // Force the Vanilla scheduler. Tor's default KIST scheduler calls
        // ioctl(fd, SIOCOUTQNSD=0x894b) on every TCP connection to query the
        // kernel's TCP not-sent queue depth — Android's SELinux policy for
        // untrusted_app denies that ioctl on tcp_socket, generating ~2
        // audit denials per second per active circuit. Tor falls back to
        // Vanilla automatically on EACCES, but the audit-log spam pins
        // CPU on the auditd path and pollutes any crash investigation.
        // Pinning Vanilla up-front skips the KIST attempt entirely.
        argv.add("--Schedulers"); argv.add("Vanilla");
        final String[] argvArr = argv.toArray(new String[0]);

        daemonThread = new Thread(() -> {
            // -1 sentinel so a thrown native call (UnsatisfiedLinkError,
            // runtime exception inside tor_run_main) is NOT reported as
            // exitCode=0 ("clean shutdown") to AIDL listeners.
            int rc = -1;
            try {
                rc = MgTorNative.run(argvArr);
                Log.d(TAG, "native exit rc=" + rc);
            } catch (Throwable t) {
                Log.e(TAG, "native run threw", t);
                rc = -1;
            } finally {
                onDaemonExited(rc);
            }
        }, "mg-tor");
        daemonThread.setDaemon(true);
        // Flip to BOOTSTRAPPING BEFORE starting the daemon thread. A native
        // run that fails immediately (e.g. argv parse error) would otherwise
        // race onDaemonExited (sees state=STARTING → wasUnexpected, sets
        // STOPPED) and the synchronized block below would then overwrite
        // STOPPED with BOOTSTRAPPING — leaving the state machine wedged
        // claiming we're bootstrapping while the daemon is dead.
        //
        // Publish socksPort / controlPort / cookieFile under the same lock
        // as the state flip so stop()'s in-lock snapshot can never observe
        // the half-written triple. start()'s earlier STARTING-flip cleared
        // these to 0/null under the lock, so any binder reader during the
        // STARTING window correctly reports "starting / no port".
        synchronized (lifecycleLock) {
            socksPort = sport;
            controlPort = cport;
            cookieFile = cookie;
            state = State.BOOTSTRAPPING;
        }
        daemonThread.start();
        bootstrap = new MgTorBootstrap(cport, cookie, new MgTorBootstrap.Listener() {
            @Override
            public void onBootstrapProgress(int percent, String tag, String summary) {
                lastBootstrapPercent = percent;
                // Prefer the human-readable SUMMARY ("Connecting to a
                // directory server") over the compact TAG ("conn_dir") so
                // the bootstrap dialog renders useful text. Fall back to
                // tag when summary is empty (tor 0.4.x's earliest progress
                // events occasionally omit it).
                final String status = (summary != null && !summary.isEmpty()) ? summary : tag;
                runOnMainThread(() -> {
                    for (Listener l : listeners) {
                        try { l.onBootstrapProgress(percent, status); } catch (Throwable t) { Log.e(TAG, "listener.onBootstrapProgress", t); }
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
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ie) {
                    // Restore the interrupt so any future blocking call on
                    // this thread observes the cancellation request.
                    Thread.currentThread().interrupt();
                    return;
                }
                try (java.net.Socket probe = new java.net.Socket()) {
                    probe.connect(new java.net.InetSocketAddress("127.0.0.1", cport), 250);
                    break;
                } catch (IOException ignored) {}
            }
            // If the daemon exited during the spin (argv error, fast OOM),
            // state is now STOPPED. Don't run the bootstrap against a dead
            // control port — onDaemonExited has already surfaced onStopped.
            MgTorBootstrap b = bootstrap;
            if (b != null && state == State.BOOTSTRAPPING) b.run();
        }, "mg-tor-boot");
        bootstrapThread.setDaemon(true);
        bootstrapThread.start();
    }

    public void stop() {
        Thread daemon;
        int snapshotControlPort;
        File snapshotCookieFile;
        synchronized (lifecycleLock) {
            if (state == State.STOPPED || state == State.STOPPING) return;
            state = State.STOPPING;
            daemon = daemonThread;
            // Snapshot the control-port + cookie inside the lock so a
            // concurrent userInitiatedStart on the worker can't tear a
            // half-written controlPort with a stale cookieFile (or vice
            // versa) into our shutdown command. These fields are otherwise
            // mutated by start()'s post-launch wiring outside the lock.
            snapshotControlPort = controlPort;
            snapshotCookieFile = cookieFile;
        }
        cancelIdleStop();

        MgTorBootstrap b = bootstrap;
        if (b != null) {
            b.cancel();
            bootstrap = null;
        }
        // tor 0.4.8's public C API has no async shutdown hook. Drive shutdown
        // by sending "SIGNAL SHUTDOWN" over the already-authenticated control
        // port; tor's event loop catches it and tor_run_main() returns.
        sendControlShutdown(snapshotControlPort, snapshotCookieFile);
        try { MgTorNative.shutdown(); } catch (Throwable t) { Log.e(TAG, "MgTorNative.shutdown", t); }

        if (daemon != null && Thread.currentThread() != daemon) {
            try {
                daemon.join(5_000);
            } catch (InterruptedException ie) {
                // stop() is reachable from binder pool threads via
                // MgTorService.binder.stop(); a binder worker retired by
                // the pool gets interrupted and must observe the request
                // after this call returns. Restoring the flag preserves
                // standard JLS semantics for any caller polling
                // Thread.currentThread().isInterrupted().
                Thread.currentThread().interrupt();
            }
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

    // Split the newline-separated obfs4 bridge blob pushed over AIDL into
    // individual "--Bridge" argv values. Blank lines are dropped; a leading
    // "Bridge " keyword (some sources, incl. @GetBridgesBot, prefix each line
    // that way for torrc paste) is stripped so the value starts at the
    // transport name. Lines that don't name the obfs4 transport are skipped so
    // a stray comment or a differently-typed bridge can't wedge the daemon.
    // Final validation of cert/iat-mode is left to lyrebird at connect time.
    private static List<String> parseObfs4Bridges(String blob) {
        List<String> out = new ArrayList<>();
        if (blob == null) return out;
        for (String raw : blob.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.regionMatches(true, 0, "Bridge ", 0, 7)) {
                line = line.substring(7).trim();
            }
            if (!line.regionMatches(true, 0, "obfs4 ", 0, 6)) continue;
            out.add(line);
        }
        return out;
    }

    private void onDaemonExited(int exitCode) {
        boolean wasUnexpected;
        synchronized (lifecycleLock) {
            wasUnexpected = state != State.STOPPING;
            state = State.STOPPED;
            // Clear ports + cookie under the same lock as the STOPPED flip
            // so stop()'s in-lock snapshot — or a binder reader observing
            // state==STOPPED — never sees a port that was just released.
            socksPort = 0;
            controlPort = 0;
            cookieFile = null;
        }

        final String reason = wasUnexpected ? "unexpected exit" : "stopped";
        runOnMainThread(() -> {
            for (Listener l : listeners) {
                try { l.onStopped(exitCode, reason); } catch (Throwable t) { Log.e(TAG, "listener.onStopped", t); }
            }
        });

        // Planned stop: nothing more to do. State is STOPPED, ready for the
        // next user/resume/push trigger to start fresh.
        if (!wasUnexpected) return;
        // No clients active: existing onClientPausedChanged restart path will
        // fire on the next resume — no need to wake the daemon while nobody
        // is around.
        // TODO(plugin-extract): the legacy main-side path also re-pinned the
        // blocking proxy stub here so MTProto couldn't fall through to direct
        // traffic on a crash. That invariant now lives on the main side in
        // MgTorClient (which observes onStopped above and re-pins its own
        // stub). Verify MgTorClient covers this when it lands.
        if (pausedClientCount > 0) return;
        unexpectedRespawnCount++;
        if (unexpectedRespawnCount > MAX_UNEXPECTED_RESPAWNS) {
            // TODO(plugin-extract): legacy code showed a Toast here via
            // LocaleController. Surface the gave-up state via Listener.onError
            // instead and let MgTorClient render the user-visible toast on the
            // main side.
            dispatchError("respawn gave up after " + MAX_UNEXPECTED_RESPAWNS + " crashes");
            return;
        }
        postWorkerDelayed(() -> {
            if (state != State.STOPPED) return;
            try { start(); } catch (Throwable t) { Log.e(TAG, "respawn start", t); }
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
        runOnMainThread(() -> {
            // Re-check on the main thread: stop() can land between the state
            // flip above and this dispatch.
            if (state != State.READY) return;
            for (Listener l : listeners) {
                try { l.onReady(port); } catch (Throwable t) { Log.e(TAG, "listener.onReady", t); }
            }
        });
    }

    private void onBootstrapFailed(String reason) {
        Log.d(TAG, "bootstrap failed: " + reason);
        dispatchError(reason);
        // Tear down the half-up daemon so the state machine returns to STOPPED
        // and the next start trigger can recover. onDaemonExited's
        // wasUnexpected branch fires onStopped for us.
        postWorker(this::stop);
    }

    private void scheduleIdleStop() {
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
        if (state == State.STOPPED || state == State.STOPPING) { cancelIdleStop(); return; }
        // TODO(plugin-extract): legacy code held tor up while VoIPService was
        // active. The plugin can't reach into the main app to inspect a call;
        // the main side must instead translate "call active" into
        // onClientPausedChanged(0) for the duration of the call so the idle
        // timer is naturally paused.
        // idleStopMinutes == 0 = "Never (keep running)" sentinel — drop
        // the ticker entirely (saves a wakeup every IDLE_TICK_MS for the full
        // session). setIdleStopMinutes re-arms via scheduleIdleStop on a later
        // non-zero choice.
        int minutes = idleStopMinutes;
        if (minutes <= 0) {
            cancelIdleStop();
            return;
        }
        long thresholdMs = minutes * 60L * 1000L;
        long now = System.currentTimeMillis();
        if (idleSinceMs > 0 && now - idleSinceMs >= thresholdMs) {
            // stop() blocks up to 5s on daemon.join — dispatch off the
            // main looper to avoid jank on the idle tick.
            postWorker(this::stop);
            return;
        }
        mainHandler.postDelayed(idleTicker, IDLE_TICK_MS);
    }

    private void failStart(String reason) {
        Log.e(TAG, "start failed: " + reason);
        dispatchError(reason);
        // Force the state machine back to STOPPED. onBootstrapFailed/onDaemonExited
        // path isn't safe here because the daemon thread never started.
        // Clear ports + cookie under the same lock so a concurrent stop()
        // snapshot can't observe a port that was about to be published.
        synchronized (lifecycleLock) {
            state = State.STOPPED;
            socksPort = 0;
            controlPort = 0;
            cookieFile = null;
        }
        final String r = reason;
        runOnMainThread(() -> {
            for (Listener l : listeners) {
                try { l.onStopped(-1, r); } catch (Throwable t) { Log.e(TAG, "listener.onStopped", t); }
            }
        });
    }

    private void dispatchError(final String reason) {
        runOnMainThread(() -> {
            for (Listener l : listeners) {
                try { l.onError(reason); } catch (Throwable t) { Log.e(TAG, "listener.onError", t); }
            }
        });
    }

    private void runOnMainThread(Runnable r) {
        mainHandler.post(r);
    }

    private Handler workerHandler() {
        Handler h = workerHandler;
        if (h != null) return h;
        synchronized (lifecycleLock) {
            if (workerHandler == null) {
                HandlerThread t = new HandlerThread("mg-tor-worker");
                t.setDaemon(true);
                t.start();
                workerThread = t;
                workerHandler = new Handler(t.getLooper());
            }
            return workerHandler;
        }
    }

    private void postWorker(Runnable r) {
        workerHandler().post(r);
    }

    private void postWorkerDelayed(Runnable r, long delayMs) {
        workerHandler().postDelayed(r, delayMs);
    }

    private static int pickFreeLoopbackPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return s.getLocalPort();
        }
    }

    private static File ensureGeoIpAsset(Context ctx, String name) {
        File dir = new File(ctx.getFilesDir(), "tor");
        File out = new File(dir, name);
        if (out.exists() && out.length() > 0) return out;
        // Write to .tmp + atomic rename so an ENOSPC / interrupted copy can't
        // leave a truncated file that the next start would accept (out.exists()
        // && length>0 short-circuits the re-copy) and feed to tor as a corrupt
        // GeoIP database.
        File tmp = new File(dir, name + ".tmp");
        if (tmp.exists() && !tmp.delete()) {
            Log.e(TAG, "could not delete stale tmp: " + tmp);
            return null;
        }
        try (InputStream in = ctx.getAssets().open("tor/" + name);
             OutputStream o = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        } catch (IOException e) {
            // Assets bundled by jni/build_tor.sh. Without them tor still
            // boots, just without country-aware circuit selection.
            tmp.delete();
            return null;
        }
        if (!tmp.renameTo(out)) {
            tmp.delete();
            return null;
        }
        return out;
    }
}
