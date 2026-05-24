package it.belloworld.mercurygram.tor;

import org.telegram.messenger.FileLog;

// MG: JNI shim around the embedded tor daemon. The native implementation lives
// in TMessagesProj/jni/tor_jni.c and is linked into libmgtor.so (NOT
// libtmessages.so) so its statically-linked OpenSSL copy cannot collide with
// libtmessages.so's BoringSSL — both crypto libs export the same C ABI names
// (EVP_*, SSL_*, BIO_*), so co-linking would silently let the dynamic linker
// pick one per call site. Separate .so + dlopen scope keeps the two worlds
// disjoint.
//
// The .so is loaded eagerly in the static initializer; if it isn't present
// (older sideloads, broken local build) the class still loads and
// isAvailable() returns false so callers can gracefully degrade.
public final class MgTorNative {

    private static volatile boolean libraryLoaded;

    static {
        try {
            System.loadLibrary("mgtor");
            libraryLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            FileLog.e("MgTor: libmgtor.so not present", e);
        }
    }

    private MgTorNative() {}

    public static boolean isAvailable() {
        return libraryLoaded;
    }

    // Block until tor exits (graceful shutdown or fatal error). Returns tor's
    // exit code. Call on a dedicated background thread — the underlying
    // tor_run_main() pumps libevent on the caller.
    public static native int run(String[] argv);

    // Signal tor to shut down (calls tor_api_shutdown_event_loop() internally).
    // Idempotent; the daemon thread's run() returns shortly after.
    public static native void shutdown();
}
