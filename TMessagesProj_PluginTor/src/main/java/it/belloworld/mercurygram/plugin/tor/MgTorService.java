package it.belloworld.mercurygram.plugin.tor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import androidx.core.app.NotificationCompat;

// MG: AIDL-exposed Android Service wrapping MgTorController.
//
// Runs in the ":tor" process (manifest android:process). Bound by the main
// Mercurygram app via the IMgTorService.BIND intent action, gated by the
// signature-level "it.belloworld.mercurygram.plugin.tor.BIND" permission.
//
// Responsibilities (and only these):
//   - Bridge IMgTorService binder calls -> MgTorController instance.
//   - Fan-out MgTorController.Listener events to every registered
//     IMgTorCallback via RemoteCallbackList (per-recipient RemoteException
//     swallowed; broadcast continues to the next consumer).
//   - Manage foreground promotion + notification lifecycle so the daemon
//     survives main-app backgrounding. Foreground is established on start()
//     and torn down on the controller reporting Stopped.
//
// NOT responsible for: account state, blocking proxy stubs, plugin signature
// verification (the OS enforces signature-permission at bind time and main's
// MgTorClient pins cert SHA-256 independently). Anything on that list lives
// on the main side in MgTorClient.
public class MgTorService extends Service {
    private static final String TAG = "MgTorService";
    private static final String NOTIF_CHANNEL_ID = "mg-tor";
    // Stable, plugin-owned notification id. 'MgTo' as ASCII bytes; collisions
    // across notification managers in the :tor process are vanishingly
    // unlikely but a constant keeps repeated startForeground() calls
    // overwriting (not stacking) the same notification.
    private static final int NOTIF_ID = 0x4D67546F;

    private final RemoteCallbackList<IMgTorCallback> callbacks = new RemoteCallbackList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MgTorController controller;
    private MgTorController.Listener listenerBridge;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannelIfNeeded();
        // No promoteForeground here. onCreate runs in response to a bare
        // bindService — bind-only is now the default + only entry path
        // (main no longer pairs startForegroundService with bindService).
        // The plugin self-promotes to foreground from binder.start when
        // the AIDL start request lands, with the plugin process in
        // BOUND_TOP because a foreground main is holding the bind.
        controller = MgTorController.getInstance();

        listenerBridge = new MgTorController.Listener() {
            @Override
            public void onBootstrapProgress(int percent, String status) {
                broadcast(cb -> cb.onBootstrapProgress(percent, status), "onBootstrapProgress");
            }

            @Override
            public void onReady(int socksPort) {
                // Keep the foreground notification active; clients call
                // start()/stop() to drive lifecycle. We do not tear the
                // notification down here.
                // TODO: swap notification text to a "ready" variant + show the
                //  active SOCKS port. v1 stays minimal.
                broadcast(cb -> cb.onReady(socksPort), "onReady");
            }

            @Override
            public void onStopped(int exitCode, String reason) {
                broadcast(cb -> cb.onStopped(exitCode, reason), "onStopped");
                // Daemon is down; release the foreground promotion so the
                // :tor process can be reclaimed by the OS if no client
                // re-binds. A subsequent start() will re-promote.
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                } catch (Throwable t) {
                    Log.e(TAG, "stopForeground", t);
                }
            }

            @Override
            public void onError(String reason) {
                broadcast(cb -> cb.onError(reason), "onError");
            }
        };
        // Register the bridge BEFORE init: controller.init synchronously
        // dispatches onError("native library unavailable") when
        // MgTorNative.isAvailable() returns false (broken local build /
        // wrong-ABI sideload). If we init'd first, that error would fire
        // into an empty listeners list and main would silently hang on
        // the blocking stub. preInit currently delegates to init, so
        // calling init() directly is enough — no behavioural change vs
        // the legacy preInit+init pair.
        controller.addListener(listenerBridge);
        // init() assigns appContext synchronously (so a binder.start call
        // arriving before the lib probe completes still sees a non-null
        // context) and defers the MgTorNative.isAvailable() probe to a
        // worker so the Service main thread isn't pinned in
        // System.loadLibrary("mgtor") (multi-MB libmgtor.so + OpenSSL self-
        // tests, hundreds of ms under cold storage / memory pressure) while
        // the FGS-start grace window is open.
        controller.init(getApplicationContext());
    }

    // Functional adapter: each listenerBridge method needs the same broadcast
    // shape (beginBroadcast / per-item dispatch / finishBroadcast) but throws
    // RemoteException on the wire. A throwing-Consumer mirrors that.
    private interface CallbackDispatch {
        void invoke(IMgTorCallback cb) throws RemoteException;
    }

    // Drive a per-callback fan-out, guarding both beginBroadcast (throws
    // IllegalStateException on a kill()-ed list — possible after onDestroy
    // races a late mainHandler-posted onStopped) and the per-item dispatch
    // (RemoteException for dead binders; any other Throwable from the
    // remote stub's stub thread). All errors are absorbed: a single bad
    // consumer can't take down the daemon's terminal callbacks.
    private void broadcast(CallbackDispatch op, String label) {
        final int n;
        try {
            n = callbacks.beginBroadcast();
        } catch (IllegalStateException ise) {
            // Callback list was kill()'d (Service is being torn down).
            // Nothing to broadcast to; main side will pick up the state
            // change via onServiceDisconnected.
            return;
        }
        try {
            for (int i = 0; i < n; i++) {
                try {
                    op.invoke(callbacks.getBroadcastItem(i));
                } catch (RemoteException e) {
                    // Dead binder; RemoteCallbackList drops it on the next
                    // register/unregister cycle. Continue fan-out.
                    Log.w(TAG, "callback." + label + " remote", e);
                } catch (Throwable t) {
                    Log.e(TAG, "callback." + label, t);
                }
            }
        } finally {
            try { callbacks.finishBroadcast(); } catch (IllegalStateException ignored) {}
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Bind-only design: MgTorClient no longer pairs a startForegroundService
        // with bindService, so this callback only fires if some other component
        // explicitly startService's the plugin (not a path the current main
        // exercises). Defensive promoteForeground here keeps the FGS contract
        // satisfied for any future / external start, and is a no-op if
        // binder.start has already promoted to foreground (startForeground is
        // idempotent on the same notification id).
        try { promoteForeground(true); } catch (Throwable t) { Log.e(TAG, "promoteForeground onStartCommand", t); }
        // START_NOT_STICKY: if the system kills us while Tor is up, the
        // main app's MgTorClient will rebind + start again on its next
        // userInitiated / push / resume trigger. Auto-restart with no
        // bindService would orphan the daemon (no callbacks land anywhere).
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        // controller.stop() blocks up to 5s on daemon.join (worst case: tor's
        // libevent loop slow to drain SIGNAL SHUTDOWN). Service.onDestroy runs
        // on the main looper here, so an inline call risks Service-ANR under
        // repeated teardown/restart pressure. Fan it out onto a one-shot
        // daemon thread; main returns promptly and the daemon thread carries
        // the join + listener removal in the background. The listener bridge
        // remains registered until controller.stop() finishes inside the
        // worker, so the daemon's terminal onStopped (mainHandler.post from
        // the daemon's finally block) still has a chance to broadcast into
        // the still-live RemoteCallbackList.
        final MgTorController c = controller;
        final MgTorController.Listener bridge = listenerBridge;
        Thread stopThread = new Thread(() -> {
            try { c.stop(); } catch (Throwable t) { Log.e(TAG, "controller.stop", t); }
            try { c.removeListener(bridge); } catch (Throwable t) { Log.e(TAG, "controller.removeListener", t); }
        }, "mg-tor-svc-stop");
        stopThread.setDaemon(true);
        stopThread.start();
        // Kill the callback list once the main looper drains; the broadcast
        // helpers in listenerBridge are now defensive against beginBroadcast
        // on a killed list (catch IllegalStateException), so the worst-case
        // race here is a single dropped onStopped fan-out — main side sees
        // onServiceDisconnected anyway, so no recovery is missed.
        mainHandler.post(callbacks::kill);
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable t) {
            Log.e(TAG, "stopForeground onDestroy", t);
        }
        super.onDestroy();
    }

    // onUnbind intentionally not overridden — default returns false. The
    // foreground promotion outlives bind so the Tor daemon keeps running
    // while the main app is backgrounded. Service is torn down on:
    //   - controller.stop() driven from main via IMgTorService.stop, OR
    //   - the controller's own idle-stop timer (onStopped path).

    private final IMgTorService.Stub binder = new IMgTorService.Stub() {
        @Override
        public int getPluginVersion() {
            return BuildConfig.VERSION_CODE;
        }

        @Override
        public int getSocksPort() {
            // AIDL contract: -1 when not yet bootstrapped. Controller returns
            // 0 in that case; translate so callers don't accidentally connect
            // to "127.0.0.1:0".
            int port = controller.getSocksPort();
            return port > 0 ? port : -1;
        }

        @Override
        public int getBootstrapPercent() {
            // AIDL contract: -1 when Tor is not running.
            if (controller.getState() == MgTorController.State.STOPPED) return -1;
            return controller.getLastBootstrapPercent();
        }

        @Override
        public void start() {
            // Promote to foreground BEFORE any I/O so the system doesn't kill
            // the Service mid-startup. With main bind-only (no paired
            // startForegroundService), Service.startForeground here is the
            // sole foreground promotion path; it's allowed because the
            // plugin process is in PROCESS_STATE_BOUND_TOP (main is in
            // foreground / receiver window / cold-start launch on every
            // legitimate entry).
            //
            // Pass bootstrapping=false when the daemon is already READY so a
            // no-op start (main rebind after the Service was reclaimed but
            // daemon survived in :tor's foreground notification context, or
            // main's userInitiatedStart fast path while bound + running)
            // doesn't visibly regress the notification text from the
            // post-onReady visual back to "Bootstrapping". When the state is
            // anything else (STOPPED, STARTING, BOOTSTRAPPING, STOPPING) the
            // post-start daemon will be bootstrapping again so the
            // "Bootstrapping" string is accurate.
            final boolean bootstrapping =
                    controller.getState() != MgTorController.State.READY;
            // Swallow ForegroundServiceStartNotAllowedException and friends:
            // a rare race (main backgrounded between bindService and the
            // AIDL start landing here) drops the plugin process out of
            // BOUND_TOP, after which startForeground throws. Letting it
            // propagate would crash the binder pool thread and leave main
            // hanging on the blocking stub forever. Logging + bailing keeps
            // the controller in STOPPED so a subsequent user toggle off/on
            // (with main definitely foreground) recovers cleanly.
            try { promoteForeground(bootstrapping); }
            catch (Throwable t) { Log.e(TAG, "promoteForeground binder.start", t); return; }
            controller.userInitiatedStart();
        }

        @Override
        public void stop() {
            // Foreground tear-down happens in listenerBridge.onStopped; do not
            // call stopForeground here (the daemon may take up to 5s to join,
            // and we want the notification visible during shutdown).
            controller.stop();
        }

        @Override
        public void setIdleStopMinutes(int minutes) {
            controller.setIdleStopMinutes(minutes);
        }

        @Override
        public void onClientPausedChanged(int pausedClientCount) {
            controller.onClientPausedChanged(pausedClientCount);
        }

        @Override
        public void registerCallback(IMgTorCallback cb) {
            if (cb != null) callbacks.register(cb);
        }

        @Override
        public void unregisterCallback(IMgTorCallback cb) {
            if (cb != null) callbacks.unregister(cb);
        }

        @Override
        public void setTransportConfig(int transportMode, String bridgeLines) {
            controller.setTransportConfig(transportMode, bridgeLines);
        }
    };

    private final Object foregroundLock = new Object();

    // Called from BOTH the main thread (onStartCommand) and binder pool
    // (binder.start). NotificationCompat.Builder + Resources lookups are
    // not safe to run concurrently from two threads on the same Service;
    // serialize on foregroundLock so the two paths can't tear each other's
    // Notification construction. startForeground itself is fast (~ms),
    // so the lock window is short.
    private void promoteForeground(boolean bootstrapping) {
        synchronized (foregroundLock) {
            Notification n = buildNotification(bootstrapping
                    ? R.string.MercurygramTorPluginNotificationBootstrap
                    : R.string.MercurygramTorPluginNotificationReady);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        }
    }

    private Notification buildNotification(int contentTextRes) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
                // TODO: replace android.R.drawable.stat_sys_warning with a
                //  plugin-owned drawable (small monochrome onion or similar)
                //  once the asset is added under res/drawable.
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(getString(R.string.MercurygramTorPluginNotificationTitle))
                .setContentText(getString(contentTextRes))
                .setOngoing(true)
                // IMPORTANCE_LOW maps to PRIORITY_LOW on pre-O. Keep the
                // notification silent — this is a status indicator, not a
                // user-actionable event.
                .setPriority(NotificationCompat.PRIORITY_LOW);
        // TODO: live-update the notification text with the current bootstrap
        //  percent (e.g. "Tor bootstrapping… 65%"). v1 stays static.
        // TODO: add action buttons (Stop / Open settings) once the main app
        //  exposes a deep link target.
        return b.build();
    }

    private void createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        // Channel creation is idempotent — calling it on every onCreate is the
        // documented pattern.
        NotificationChannel ch = new NotificationChannel(
                NOTIF_CHANNEL_ID,
                getString(R.string.MercurygramTorPluginNotificationChannel),
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }
}
