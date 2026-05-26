package it.belloworld.mercurygram;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;

import java.net.InetAddress;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

// MG: triggers a PFS temp-key rehandshake whenever observable network state
// changes — default network swap, IP rebind on the same netId, VPN toggle,
// or a long idle → foreground transition. Each new handshake yields a fresh
// auth_key_id so a passive on-path observer cannot use the id as a stable
// device fingerprint across network boundaries.
//
// Always on: rotation uses HandshakeTypeTemp only (perm key untouched, no
// logout risk) and rides on top of the natural reconnect that follows any
// real network change, so the worst-case cost is one extra handshake per
// physical event. All triggers funnel through scheduleRotation() which
// debounces with DEBOUNCE_MS to coalesce noisy callbacks during transitions
// (Android often fires onAvailable/onCapabilitiesChanged 3-5 times in quick
// succession while a network settles), and per-event primed flags skip the
// first callback after registration so app cold-start does not burn a
// handshake.
public final class MgNetworkChangeWatcher {

    private static final long DEBOUNCE_MS = 2_000L;
    private static final long IDLE_ROTATE_MS = 30L * 60L * 1000L;
    // Poll interval while the rotation is deferred waiting for the socket to
    // finish the post-foreground getDifference on the warm temp key.
    private static final long ROTATE_POLL_MS = 1_000L;
    // ponytail: hard cap so the anti-fingerprint rotation still fires if a
    // socket never reaches Connected (e.g. persistent offline); privacy over UX.
    private static final long ROTATE_MAX_DEFER_MS = 20_000L;
    // LRU cap for addressesByNetId. Android reuses netIds slowly, and a
    // heavy-roaming user (cafes, airports, transit) can otherwise grow this
    // map without bound over the process lifetime.
    private static final int NETID_CACHE_MAX = 16;

    private static volatile boolean registered = false;

    // ConnectivityManager callbacks fire on a binder thread, the foreground
    // hook fires on the UI thread, and ConnectionsManager native calls run
    // on Utilities.globalQueue. The shared mutable state below is touched
    // from multiple threads — guard reads/writes with the lock and use
    // volatile only where the lock would be overkill (single-writer flags).
    private static final Object lock = new Object();

    private static volatile Network lastDefaultNetwork = null;
    private static volatile boolean defaultPrimed = false;

    private static final Map<Integer, Set<InetAddress>> addressesByNetId =
            new LinkedHashMap<Integer, Set<InetAddress>>(NETID_CACHE_MAX, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, Set<InetAddress>> eldest) {
                    return size() > NETID_CACHE_MAX;
                }
            };
    // null = not seen yet. Boolean object so the very first callback after
    // registration only seeds state, regardless of the current VPN bit.
    private static Boolean lastDefaultHasVpn = null;

    private static volatile long pausedAtElapsedMs = 0L;

    // elapsedRealtime of the first rotateAllAccounts attempt in the current
    // deferral cycle; 0 = no deferral in progress. Touched only on the main
    // handler thread (rotateRunnable), so no lock needed.
    private static long deferStartMs = 0L;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable rotateRunnable = MgNetworkChangeWatcher::rotateAllAccounts;

    private MgNetworkChangeWatcher() {}

    public static void init(Context context) {
        if (registered) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    onDefaultAvailable(network);
                }
                // No onLost handler: a brief blip (airplane-mode toggle,
                // captive-portal recheck) drops the same network briefly,
                // then onAvailable restores it. Treating onLost as
                // "lastNetwork = null" would make the same physical
                // network look new and waste a rotation. Network.equals
                // compares netId, which the OS reuses across blips, so
                // the lastNetwork check in onDefaultAvailable correctly
                // suppresses these.

                @Override
                public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities caps) {
                    onDefaultCapabilities(caps);
                }

                @Override
                public void onLinkPropertiesChanged(@NonNull Network network, @NonNull LinkProperties props) {
                    onDefaultLinkProperties(network, props);
                }
            });
            // Commit registered=true as soon as the default callback is
            // live: if the VPN registration below throws (vendor build,
            // future framework limit on simultaneous callbacks), the catch
            // must not let a subsequent init() try to register the default
            // callback again — that throws IllegalArgumentException.
            registered = true;
            // Dedicated VPN listener — registerDefaultNetworkCallback only
            // fires when the default network changes, and a VPN coming up
            // over the same Wi-Fi may keep the same default network for a
            // moment. Matching TRANSPORT_VPN catches the transition itself.
            // No priming flag: registerNetworkCallback(TRANSPORT_VPN) only
            // fires an initial onAvailable if a VPN is *already* active at
            // registration time. With no VPN active, the first event we
            // see IS the user-initiated VPN-up — exactly the rotation
            // trigger we want. Worst case: an Always-On VPN already up at
            // cold start costs one redundant handshake (debounced).
            NetworkRequest vpnRequest = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .build();
            cm.registerNetworkCallback(vpnRequest, new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    scheduleRotation();
                }

                @Override
                public void onLost(@NonNull Network network) {
                    scheduleRotation();
                }
            });
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static void onDefaultAvailable(Network network) {
        boolean wasPrimed = defaultPrimed;
        defaultPrimed = true;
        if (!wasPrimed) {
            // first callback after registerDefaultNetworkCallback — seed
            // lastDefaultNetwork and skip; no actual network change happened.
            lastDefaultNetwork = network;
            return;
        }
        if (network.equals(lastDefaultNetwork)) {
            return;
        }
        lastDefaultNetwork = network;
        scheduleRotation();
    }

    private static void onDefaultCapabilities(NetworkCapabilities caps) {
        boolean hasVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        synchronized (lock) {
            if (lastDefaultHasVpn == null) {
                lastDefaultHasVpn = hasVpn;
                return;
            }
            if (lastDefaultHasVpn == hasVpn) {
                return;
            }
            lastDefaultHasVpn = hasVpn;
        }
        scheduleRotation();
    }

    private static void onDefaultLinkProperties(Network network, LinkProperties props) {
        int netId = networkHash(network);
        Set<InetAddress> current = new HashSet<>();
        for (LinkAddress addr : props.getLinkAddresses()) {
            InetAddress a = addr.getAddress();
            if (a == null) continue;
            // skip link-local + loopback noise — they churn independently
            // of any IP visible to the network path.
            if (a.isLinkLocalAddress() || a.isLoopbackAddress()) continue;
            current.add(a);
        }
        boolean rotate;
        synchronized (lock) {
            Set<InetAddress> previous = addressesByNetId.get(netId);
            if (previous == null) {
                addressesByNetId.put(netId, current);
                return;
            }
            // rotate when any previously visible address is gone — pure
            // additions (e.g. a new SLAAC temporary IPv6 alongside the old)
            // do not rotate, since the wire-visible source IP only shifts
            // once the kernel drops the old one.
            Set<InetAddress> removed = new HashSet<>(previous);
            removed.removeAll(current);
            rotate = !removed.isEmpty();
            addressesByNetId.put(netId, current);
        }
        if (rotate) {
            scheduleRotation();
        }
    }

    // Called from LaunchActivity.onPause / onResume around the existing
    // ApplicationLoader.mainInterfacePaused writes. Rotates when the app
    // comes back to the foreground after > IDLE_ROTATE_MS in background —
    // covers silent IP rebinds during doze that produce no NetworkCallback.
    public static void onForegroundStateChanged(boolean paused) {
        if (paused) {
            pausedAtElapsedMs = SystemClock.elapsedRealtime();
            return;
        }
        long pausedAt = pausedAtElapsedMs;
        pausedAtElapsedMs = 0L;
        if (pausedAt == 0L) return;
        if (SystemClock.elapsedRealtime() - pausedAt < IDLE_ROTATE_MS) return;
        scheduleRotation();
    }

    private static void scheduleRotation() {
        handler.removeCallbacks(rotateRunnable);
        handler.postDelayed(rotateRunnable, DEBOUNCE_MS);
    }

    private static int networkHash(Network network) {
        // Network.hashCode() is the netId on every supported Android
        // version; using it avoids touching Network.toString() which
        // allocates.
        return network.hashCode();
    }

    private static void rotateAllAccounts() {
        // Skip when Tor is on: every MTProto byte already leaves the device
        // through the Tor SOCKS port and exits at a Tor relay whose IP is
        // unrelated to the user's network. Passive on-path observers on the
        // user's own network never see auth_key_id at all, so rotating it
        // adds no privacy benefit and would only burn a fresh PFS handshake
        // per non-CDN DC across the multi-hop circuit on every Wi-Fi ↔ cell
        // handoff. When mg_useTor is true and bootstrap hasn't completed yet
        // the rotation would also bounce off the blocking stub on 127.0.0.1:1
        // and retry, wasting handshakes for no gain.
        if (SharedConfig.mg_useTor) return;
        // Defer the temp-key wipe until the socket has finished the
        // post-foreground getDifference on the *warm* key. Clearing it mid-sync
        // forces a fresh PFS handshake before the diff can go out, so a chat
        // opened from a notification shows stale content for the length of that
        // handshake. Wait until every active account is back at
        // ConnectionStateConnected (connected and no longer Updating), capped
        // by ROTATE_MAX_DEFER_MS so the rotation still happens if a socket
        // never settles.
        long now = SystemClock.elapsedRealtime();
        if (deferStartMs == 0L) deferStartMs = now;
        if (now - deferStartMs < ROTATE_MAX_DEFER_MS && anyAccountSyncing()) {
            handler.postDelayed(rotateRunnable, ROTATE_POLL_MS);
            return;
        }
        deferStartMs = 0L;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            try {
                UserConfig uc = UserConfig.getInstance(a);
                // isClientActivated returns currentUser != null. clearConfig()
                // is the first call in the logout sequence (sets currentUser
                // to null synchronously before performLogout runs), so a
                // network change racing with logout post-clearConfig is
                // correctly skipped here. Pre-clearConfig is a UI-tap window
                // microseconds wide and the rotation is HandshakeTypeTemp
                // only (no perm-key risk), so a spurious hit is bounded to
                // one wasted handshake against a soon-to-be-removed DC.
                if (uc.isClientActivated() && uc.getCurrentUser() != null) {
                    ConnectionsManager.getInstance(a).rotateTempAuthKeys();
                }
            } catch (Throwable t) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e(t);
                }
            }
        }
    }

    // True while any active account's socket has not reached
    // ConnectionStateConnected. getConnectionState() reports Updating (not
    // Connected) for the whole getDifference window, so "!= Connected" covers
    // Connecting, WaitingForNetwork, ConnectingToProxy and Updating: every
    // state where wiping the temp key would stall the foreground diff.
    private static boolean anyAccountSyncing() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            try {
                UserConfig uc = UserConfig.getInstance(a);
                if (uc.isClientActivated() && uc.getCurrentUser() != null
                        && ConnectionsManager.getInstance(a).getConnectionState()
                            != ConnectionsManager.ConnectionStateConnected) {
                    return true;
                }
            } catch (Throwable ignored) {
                // treat an unreadable account as not syncing
            }
        }
        return false;
    }
}
