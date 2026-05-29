package it.belloworld.mercurygram.transcribe;

/**
 * [MG] Thin JNI surface to whisper.cpp (see {@code TMessagesProj/jni/whisper_jni.cpp}).
 *
 * The native code is compiled into {@code libtmessages.so}, which is already
 * loaded by the app's native loader before any of these methods are called, so
 * this class does no {@code System.loadLibrary} of its own.
 *
 * All methods are blocking and must be called off the main thread
 * ({@link MgWhisperTranscriber} owns a dedicated worker thread). A context
 * handle returned by {@link #nativeInit} is an opaque native pointer; pass it
 * back to {@link #nativeTranscribe}/{@link #nativeFree} and never dereference it.
 */
public final class MgWhisperNative {

    private MgWhisperNative() {
    }

    /**
     * Loads a ggml Whisper model from disk.
     *
     * @param modelPath absolute path to the {@code .bin} model file
     * @return opaque native context handle, or {@code 0} on failure
     */
    public static native long nativeInit(String modelPath);

    /**
     * Transcribes a mono 16 kHz float PCM buffer (samples in [-1, 1]).
     *
     * @param ctx          handle from {@link #nativeInit}
     * @param pcm16k       mono 16 kHz PCM samples
     * @param language     ISO language code, or {@code null}/{@code "auto"} to auto-detect
     * @param translate    when true, output is translated to English by the model
     * @param vadModelPath absolute path to a Silero VAD ggml model to strip silence
     *                     before decoding, or {@code null}/empty to disable VAD.
     *                     Non-fatal: a missing/unreadable path or a VAD failure
     *                     falls back to plain transcription.
     * @return transcribed text (possibly empty), or {@code null} on failure
     */
    public static native String nativeTranscribe(long ctx, float[] pcm16k, String language, boolean translate, String vadModelPath);

    /**
     * Frees a context handle. Safe to call with {@code 0}.
     */
    public static native void nativeFree(long ctx);
}
