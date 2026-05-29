package it.belloworld.mercurygram.transcribe;

import android.os.Build;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.SharedConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * [MG] On-device voice-message transcription using whisper.cpp.
 *
 * Mirrors the threading/callback shape of
 * {@link it.belloworld.mercurygram.translate.MgAidlTranslate}: a single
 * serialized worker, a lazily-loaded engine context that is cached and reused,
 * and a typed {@link Failure} surface mapped to localized bulletins by the
 * caller. All native calls happen on the worker thread; callbacks are posted to
 * the UI thread.
 */
public final class MgWhisperTranscriber {

    public enum Reason {
        MODEL_NOT_INSTALLED,
        FILE_UNAVAILABLE,
        DECODE_FAILED,
        ENGINE_FAILED,
    }

    public static final class Failure {
        public final Reason reason;

        private Failure(Reason reason) {
            this.reason = reason;
        }

        public static Failure of(Reason reason) {
            return new Failure(reason);
        }
    }

    /** Result callback: ({@code text}, {@code success}, {@code failure}). */
    public interface Result {
        /**
         * Live partial: the transcript decoded so far, as whisper emits each
         * segment. Always invoked on the UI thread, possibly many times, before
         * {@link #done}. Default no-op so callers that don't stream are unaffected.
         */
        default void onPartial(String text) {}

        void done(String text, boolean success, Failure failure);
    }

    // Single-thread executor: whisper inference is CPU-heavy and the engine
    // context is not thread-safe, so requests are serialized.
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "MgWhisper");
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        }
    });

    // Engine state — only touched on WORKER.
    private static long ctx = 0;
    private static String loadedModelPath = null;
    // Identity stamp (lastModified + length) of the file backing the loaded ctx.
    // Lets ensureEngine() detect a model file that was replaced in place (delete
    // then re-download/import a different blob into the same slot) and reload,
    // instead of silently reusing the stale in-memory model.
    private static long loadedModelStamp = 0;

    // Cached result of the one-time CPU capability probe (see deviceSupported()).
    private static volatile Boolean cpuSupported = null;

    private MgWhisperTranscriber() {
    }

    /** True when offline transcription is enabled, a model is present and the CPU can run the engine. */
    public static boolean isUsable() {
        return SharedConfig.mg_transcribeOffline && MgWhisperModel.isSelectedInstalled() && deviceSupported();
    }

    /**
     * True when the running CPU can execute the whisper.cpp build.
     *
     * The arm64 lib is compiled with {@code armv8.2-a+dotprod+fp16}
     * (see jni/CMakeLists.txt) for a large speedup on the q8_0 GEMM, so it
     * contains instructions that fault on pre-ARMv8.2 cores. Gate the feature on
     * a {@code /proc/cpuinfo} probe for {@code asimddp} (dotprod) +
     * {@code asimdhp} (fp16) so such devices never load the engine and fall back
     * to Telegram's server transcription instead of crashing with SIGILL. Other
     * ABIs (armeabi-v7a, x86, x86_64) use the NDK baseline → always supported.
     */
    public static boolean deviceSupported() {
        Boolean v = cpuSupported;
        if (v != null) {
            return v;
        }
        boolean ok = probeCpu();
        cpuSupported = ok;
        return ok;
    }

    private static boolean probeCpu() {
        String[] abis = Build.SUPPORTED_ABIS;
        String primary = (abis != null && abis.length > 0) ? abis[0] : "";
        if (!"arm64-v8a".equals(primary)) {
            return true;
        }
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("Features")) {
                    if (line.contains("asimddp") && line.contains("asimdhp")) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    /**
     * Transcribes the voice/round-video message off-thread. {@code done} is
     * always invoked on the UI thread exactly once.
     */
    public static void transcribe(MessageObject messageObject, Result done) {
        if (messageObject == null || messageObject.messageOwner == null) {
            post(done, null, false, Failure.of(Reason.FILE_UNAVAILABLE));
            return;
        }
        final MgWhisperModel.Model model = MgWhisperModel.selected();
        if (!MgWhisperModel.isInstalled(model)) {
            post(done, null, false, Failure.of(Reason.MODEL_NOT_INSTALLED));
            return;
        }
        final String modelPath = MgWhisperModel.absolutePathFor(model);
        final String lang = effectiveLang(SharedConfig.mg_transcribeLang);
        // VAD strips silence so the tiny model can't hallucinate; non-fatal —
        // only when enabled AND the (downloaded) VAD model is present, else null
        // and the JNI shim transcribes without it. deviceSupported() is already
        // guaranteed by isUsable() at the call site.
        final String vadPath =
                (SharedConfig.mg_transcribeVad && MgWhisperModel.isVadInstalled())
                        ? MgWhisperModel.vadAbsolutePath() : null;
        // On-by-default safety net: if VAD is on but the model isn't here yet
        // (e.g. model imported via SAF rather than downloaded), fetch it in the
        // background so the next transcription benefits.
        if (SharedConfig.mg_transcribeVad && !MgWhisperModel.isVadInstalled()) {
            MgWhisperModel.ensureVadDownloaded();
        }

        WORKER.execute(() -> {
            try {
                // Resolve the on-disk audio path on the worker, not the UI thread:
                // getPathToMessage() + File stats are avoidable main-thread I/O on tap.
                final String audioPath = resolveAudioPath(messageObject);
                if (audioPath == null) {
                    post(done, null, false, Failure.of(Reason.FILE_UNAVAILABLE));
                    return;
                }
                if (!ensureEngine(modelPath)) {
                    post(done, null, false, Failure.of(Reason.ENGINE_FAILED));
                    return;
                }
                float[] pcm;
                try {
                    pcm = AudioDecoder.decodeTo16kMono(audioPath);
                } catch (Exception e) {
                    FileLog.e(e);
                    post(done, null, false, Failure.of(Reason.DECODE_FAILED));
                    return;
                }
                if (pcm == null || pcm.length == 0) {
                    post(done, null, false, Failure.of(Reason.DECODE_FAILED));
                    return;
                }
                // Live: forward each whisper segment to the UI as it lands. Fires
                // on this worker thread; we only hop to the UI thread and return,
                // so whisper inference is never blocked.
                final MgWhisperNative.SegmentCallback segCb = (partial) -> {
                    final String t = partial == null ? "" : partial.trim();
                    if (!t.isEmpty()) {
                        AndroidUtilities.runOnUIThread(() -> done.onPartial(t));
                    }
                };
                String text = MgWhisperNative.nativeTranscribe(ctx, pcm, lang, false, vadPath, segCb);
                if (text == null) {
                    post(done, null, false, Failure.of(Reason.ENGINE_FAILED));
                    return;
                }
                final String result = text.trim();
                post(done, result, true, null);
            } catch (Throwable t) {
                FileLog.e(t);
                post(done, null, false, Failure.of(Reason.ENGINE_FAILED));
            }
        });
    }

    /** Loads (or reloads) the engine context for {@code modelPath}. Worker-thread only. */
    private static boolean ensureEngine(String modelPath) {
        long stamp = modelStamp(modelPath);
        if (ctx != 0 && TextUtils.equals(modelPath, loadedModelPath) && stamp == loadedModelStamp) {
            return true;
        }
        if (ctx != 0) {
            MgWhisperNative.nativeFree(ctx);
            ctx = 0;
            loadedModelPath = null;
            loadedModelStamp = 0;
        }
        long handle = MgWhisperNative.nativeInit(modelPath);
        if (handle == 0) {
            return false;
        }
        ctx = handle;
        loadedModelPath = modelPath;
        loadedModelStamp = stamp;
        return true;
    }

    /**
     * Frees the cached engine context (if any) on the worker thread. Called when
     * the feature is disabled or the selected model is deleted, so a large native
     * model (~40-190 MB) is not retained for the whole process lifetime. Safe to
     * call at any time; queued behind any in-flight transcription on WORKER.
     */
    public static void releaseEngine() {
        WORKER.execute(() -> {
            if (ctx != 0) {
                MgWhisperNative.nativeFree(ctx);
                ctx = 0;
                loadedModelPath = null;
                loadedModelStamp = 0;
            }
        });
    }

    /** Cheap file-identity stamp so a model replaced in place forces a reload. */
    private static long modelStamp(String path) {
        File f = new File(path);
        return f.lastModified() * 31 + f.length();
    }

    /**
     * Resolves the language code actually passed to whisper, uniformly for every
     * model. The user setting is one of three forms:
     * <ul>
     *   <li>{@code "auto"} (or empty) → let whisper auto-detect;</li>
     *   <li>{@code "device"} → the device-locale language (the default — tiny/base
     *       mis-detect short clips, so pinning the device language is safer);</li>
     *   <li>an ISO-639-1 code → pinned verbatim.</li>
     * </ul>
     * whisper falls back to auto on its own if the code turns out to be one it
     * doesn't support (see whisper_jni.cpp).
     */
    private static String effectiveLang(String configured) {
        if (TextUtils.isEmpty(configured) || SharedConfig.MG_TRANSCRIBE_LANG_AUTO.equals(configured)) {
            return SharedConfig.MG_TRANSCRIBE_LANG_AUTO;
        }
        if (SharedConfig.MG_TRANSCRIBE_LANG_DEVICE.equals(configured)) {
            String code = deviceLanguage();
            return code != null ? code : SharedConfig.MG_TRANSCRIBE_LANG_AUTO;
        }
        return configured;
    }

    /** ISO-639-1 language of the current device locale, with Android's legacy code remaps. */
    public static String deviceLanguage() {
        String code = java.util.Locale.getDefault().getLanguage();
        if (TextUtils.isEmpty(code)) {
            return null;
        }
        code = code.toLowerCase(java.util.Locale.ROOT);
        // Android still emits obsolete ISO codes for a few languages; map them
        // to the modern codes whisper uses.
        switch (code) {
            case "in": return "id"; // Indonesian
            case "iw": return "he"; // Hebrew
            case "ji": return "yi"; // Yiddish
            default:   return code;
        }
    }

    private static String resolveAudioPath(MessageObject messageObject) {
        String attachPath = messageObject.messageOwner.attachPath;
        if (!TextUtils.isEmpty(attachPath)) {
            File f = new File(attachPath);
            if (f.exists() && f.length() > 0) {
                return f.getAbsolutePath();
            }
        }
        File f = FileLoader.getInstance(messageObject.currentAccount)
                .getPathToMessage(messageObject.messageOwner);
        if (f != null && f.exists() && f.length() > 0) {
            return f.getAbsolutePath();
        }
        return null;
    }

    private static void post(Result done, String text, boolean success, Failure failure) {
        AndroidUtilities.runOnUIThread(() -> done.done(text, success, failure));
    }
}
