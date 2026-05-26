// IMgTorService.aidl
//
// Contract between Mercurygram main app and the Tor companion plugin.
// Both sides MUST keep this file byte-identical (path included).
//
// Lifecycle (typical):
//   bindService → onServiceConnected (Binder = IMgTorService.Stub.asInterface)
//   client.registerCallback(cb)
//   client.start()        // idempotent; safe to call again after start
//   ... cb.onBootstrapProgress / cb.onReady(port) ...
//   client.stop()         // soft-stop; plugin may idle-stop on its own
//   client.unregisterCallback(cb)
//   unbindService
//
// All AIDL calls are non-oneway except where noted; treat as blocking but
// short. The plugin must not perform Tor I/O on the binder thread.

package it.belloworld.mercurygram.plugin.tor;

import it.belloworld.mercurygram.plugin.tor.IMgTorCallback;

interface IMgTorService {
    // Plugin versionCode (BuildConfig.VERSION_CODE on the plugin side).
    // Shape: MG_VERSION_CODE * 10 + abiVersionCode (afatFdArm64=8 /
    // afatFdArm32=7 / afatFdX86_64=4 / afatFdX86=3) — same formula as
    // main. When bumping MIN_PLUGIN_VERSION_CODE for an AIDL break,
    // pick a value that holds across ALL four ABIs; the safest pattern
    // is to set it to (next_target_MG_VERSION_CODE * 10) so every ABI
    // of the next plugin release clears the gate. Main shows the
    // "Plugin outdated" row otherwise.
    int getPluginVersion();

    // Current SOCKS5 port published by the plugin's MgTorController, or -1
    // if the plugin has not finished bootstrapping yet. Callers should prefer
    // IMgTorCallback.onReady() over polling.
    int getSocksPort();

    // 0..100 — last bootstrap percent received from the Tor control port.
    // Returns -1 if Tor is not running.
    int getBootstrapPercent();

    // Idempotent: starts Tor if not already running. Caller is expected to
    // have called Context.startForegroundService(intent) before bindService
    // so the Service has the FGS grace window open; the plugin promotes
    // itself to foreground (notification + foregroundServiceType) inside
    // both onStartCommand AND this start() call to cover all entry paths.
    void start();

    // Soft-stop: signals Tor to shut down and clears the foreground
    // notification. The plugin's own idle-stop timer also calls this.
    void stop();

    // Idle-stop timer override. Forwarded from main's SharedConfig.mg_torIdleStopMinutes.
    // 0 disables idle-stop (plugin keeps Tor up until explicit stop()).
    void setIdleStopMinutes(int minutes);

    // Aggregate "is any main-side client active?" indicator.
    //   pausedClientCount == 0 → at least one client is foreground/active (or VoIP).
    //   pausedClientCount  > 0 → all clients are backgrounded; idle-stop timer arms.
    // The plugin runs out-of-process and can't reach into the main app's
    // per-account UserConfig or VoIPService, so the main app aggregates and
    // forwards a single int. See MgTorClient.onAppPausedChanged.
    void onClientPausedChanged(int pausedClientCount);

    // RemoteCallbackList lifecycle. Main app passes one IMgTorCallback per
    // bound connection; plugin must unregister on Service.onUnbind.
    void registerCallback(IMgTorCallback cb);
    void unregisterCallback(IMgTorCallback cb);

    // Anti-censorship transport selection. Forwarded from main's
    // SharedConfig.mg_torTransportMode, pushed before start() (same "push
    // scalars then start" pattern as setIdleStopMinutes).
    //   transportMode: 0=direct (vanilla Tor), 1=snowflake, 2=obfs4 (lyrebird).
    //   bridgeLines: newline-separated obfs4 bridge lines (mode 2 only), else null.
    // Read once when the next start() assembles its argv; the main side
    // restarts Tor when the user changes transport so the new argv takes effect.
    //
    // Appended at the END of the interface on purpose: AIDL assigns transaction
    // codes in declaration order, so keeping this last leaves every existing
    // method's code unchanged. Adding it is still an AIDL break for a NEW main
    // calling an OLD plugin (the code is absent there), which is why
    // MgTorClient.MIN_PLUGIN_MG_VERSION_CODE is bumped in the same commit.
    void setTransportConfig(int transportMode, in String bridgeLines);
}
