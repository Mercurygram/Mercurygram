// MG: JNI shim around the embedded tor daemon's tor_run_main() entry point
// (declared in jni/tor/src/feature/api/tor_api.h). Built into libmgtor.so
// — see CMakeLists.txt mgtor target.
//
// Threading model:
//   - Java side calls run() on a dedicated background thread (mg-tor). It
//     blocks inside tor_run_main() pumping libevent until the daemon exits.
//   - shutdown() is intentionally a no-op stub: tor 0.4.8's public
//     tor_api.h does not expose an asynchronous shutdown hook. Java drives
//     shutdown by opening a TCP socket to tor's ControlPort and sending
//     "SIGNAL SHUTDOWN\r\n" — tor's event loop catches the signal and
//     tor_run_main() returns naturally. Kept here as the JNI entry point
//     so the Java-side API stays stable even if a future tor release adds
//     a real native shutdown API.

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <android/log.h>

#if defined(__aarch64__)
#include <sys/prctl.h>
// Defensive defines: NDK r27 sysroot ships these in <sys/prctl.h>, but older
// NDKs and host cross-builds may lag. Values are the stable kernel UAPI from
// arch/arm64/include/uapi/asm/* — frozen since MTE landed in 5.10.
#ifndef PR_SET_TAGGED_ADDR_CTRL
#define PR_SET_TAGGED_ADDR_CTRL 55
#endif
#ifndef PR_TAGGED_ADDR_ENABLE
#define PR_TAGGED_ADDR_ENABLE (1UL << 0)
#endif
#ifndef PR_MTE_TCF_NONE
#define PR_MTE_TCF_NONE (0UL << 1)
#endif
#endif

#include "tor_api.h"

JNIEXPORT jint JNICALL
Java_it_belloworld_mercurygram_tor_MgTorNative_run(JNIEnv *env, jclass clazz,
                                                   jobjectArray jargv) {
    (void) clazz;

#if defined(__aarch64__)
    // MTE: tor 0.4.8 + libevent trip per-thread MTE tag-check faults on
    // MTE-always-on hardware (e.g. Pixel 10 / Android 16, where the default
    // for API-35 apps is async or sync MTE). The crash surfaces inside
    // pthread_cond_wait with SEGV_MTESERR. Suppress tag checks on the calling
    // thread and let bionic continue to tag allocations; tor-spawned worker
    // threads (cpuworker pool, libevent timer thread) are created via
    // pthread_create → clone(), which inherits PR_SET_TAGGED_ADDR_CTRL from
    // the parent thread. The rest of the process (BoringSSL, ffmpeg, dav1d,
    // libvpx, voip, JNI bridge) keeps the platform-default MTE protection
    // because this prctl is per-thread, not per-process. AndroidManifest
    // declares android:memtagMode="async" so the kernel allowed-tcf-mask
    // permits this downgrade to NONE; if a future manifest change tightens
    // it to "sync" only, the kernel rejects the call with EINVAL and we
    // log it so the failure is debuggable instead of silent.
    int mte_rc = prctl(PR_SET_TAGGED_ADDR_CTRL,
                       PR_TAGGED_ADDR_ENABLE | PR_MTE_TCF_NONE,
                       0, 0, 0);
    if (mte_rc != 0) {
        __android_log_print(ANDROID_LOG_WARN, "MgTor",
                            "PR_SET_TAGGED_ADDR_CTRL=TCF_NONE failed: errno=%d",
                            errno);
    }
#endif

    jsize argc = (*env)->GetArrayLength(env, jargv);
    if (argc <= 0) {
        return -1;
    }

    char **argv = (char **) calloc((size_t) argc, sizeof(char *));
    if (!argv) return -1;
    for (jsize i = 0; i < argc; i++) {
        jstring js = (jstring) (*env)->GetObjectArrayElement(env, jargv, i);
        const char *cs = js ? (*env)->GetStringUTFChars(env, js, NULL) : NULL;
        char *dup = strdup(cs ? cs : "");
        if (cs) (*env)->ReleaseStringUTFChars(env, js, cs);
        if (js) (*env)->DeleteLocalRef(env, js);
        // strdup can fail under OOM. Passing NULL into
        // tor_main_configuration_set_command_line crashes inside libtor's
        // argv walker — fail the whole call instead and let Java retry.
        if (!dup) {
            for (jsize k = 0; k < i; k++) free(argv[k]);
            free(argv);
            return -1;
        }
        argv[i] = dup;
    }

    tor_main_configuration_t *cfg = tor_main_configuration_new();
    if (!cfg) {
        for (jsize i = 0; i < argc; i++) free(argv[i]);
        free(argv);
        return -1;
    }
    if (tor_main_configuration_set_command_line(cfg, (int) argc, argv) != 0) {
        tor_main_configuration_free(cfg);
        for (jsize i = 0; i < argc; i++) free(argv[i]);
        free(argv);
        return -1;
    }

    // tor_api.h contract: argv contents must remain valid until
    // tor_run_main() returns and tor_main_configuration_free() is called.
    // Do NOT move these free() calls above tor_run_main().
    int rc = tor_run_main(cfg);

    tor_main_configuration_free(cfg);
    for (jsize i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    return (jint) rc;
}

JNIEXPORT void JNICALL
Java_it_belloworld_mercurygram_tor_MgTorNative_shutdown(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    // No-op: shutdown is driven from Java via control-port SIGNAL SHUTDOWN.
    // See class doc on MgTorNative.
}
