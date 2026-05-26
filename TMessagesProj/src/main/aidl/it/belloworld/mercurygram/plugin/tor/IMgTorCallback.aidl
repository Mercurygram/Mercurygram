// IMgTorCallback.aidl
//
// One-way callbacks from plugin → main app. `oneway` so the plugin's
// dispatcher never blocks on a slow consumer; main app callbacks land
// on a Binder pool thread and must hand off to its own executor before
// touching ConnectionsManager / settings UI state.

package it.belloworld.mercurygram.plugin.tor;

oneway interface IMgTorCallback {
    // 0..100 with a short status string (e.g. "conn_dir_pt", "handshake").
    // Mirrors the Tor control port BOOTSTRAP event fields.
    void onBootstrapProgress(int percent, String status);

    // Tor finished bootstrapping; socksPort is the loopback port ready
    // for SOCKS5 traffic. Main app publishes SharedConfig.mg_torProxyAddress
    // = "127.0.0.1:<port>" only after this callback.
    void onReady(int socksPort);

    // Tor exited (either via stop() or due to idle timeout / crash).
    // exitCode == 0 for clean shutdown. reason is a short human-readable tag.
    void onStopped(int exitCode, String reason);

    // Unrecoverable error during start or bootstrap (e.g. control-port
    // auth failure, native crash). Main app should surface this in settings.
    void onError(String reason);
}
