// [MG] JNI bridge to whisper.cpp for on-device voice-message transcription.
//
// Kept deliberately tiny: model lifecycle (init/free) + a single blocking
// transcribe call over a mono 16 kHz float PCM buffer. All threading, audio
// decode and resampling live on the Java side (MgWhisperTranscriber /
// AudioDecoder) so this file only depends on whisper.h.
//
// The whisper.cpp engine is vendored as the `whisper` submodule and pulled in
// via add_subdirectory() in CMakeLists.txt; it therefore inherits the
// reproducible-build flags (-D__FILE__=__FILE_NAME__ etc.) set there.

#include <jni.h>
#include <string>
#include <cstdint>
#include <cmath>
#include <ctime>
#include <unistd.h>
#include <android/log.h>

#include "whisper.h"

#define LOG_TAG "MgWhisper"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// whisper emits standard UTF-8, but JNI NewStringUTF expects *modified* UTF-8:
// 4-byte sequences (supplementary-plane code points — emoji, rare CJK) are
// invalid modified UTF-8 and either corrupt the resulting String or abort the
// VM under CheckJNI. Decode UTF-8 to UTF-16 and use NewString instead. Invalid
// or truncated input is replaced with U+FFFD rather than crashing.
static jstring utf8ToJString(JNIEnv *env, const std::string &s) {
    std::u16string u16;
    u16.reserve(s.size());
    size_t i = 0, n = s.size();
    while (i < n) {
        unsigned char c = (unsigned char) s[i];
        uint32_t cp;
        int extra;
        if (c < 0x80)              { cp = c;         extra = 0; }
        else if ((c >> 5) == 0x6)  { cp = c & 0x1F;  extra = 1; }
        else if ((c >> 4) == 0xE)  { cp = c & 0x0F;  extra = 2; }
        else if ((c >> 3) == 0x1E) { cp = c & 0x07;  extra = 3; }
        else                       { cp = 0xFFFD;    extra = 0; } // invalid lead byte
        bool ok = true;
        for (int k = 1; k <= extra; k++) {
            if (i + k >= n || ((unsigned char) s[i + k] >> 6) != 0x2) { ok = false; break; }
            cp = (cp << 6) | ((unsigned char) s[i + k] & 0x3F);
        }
        if (!ok) { cp = 0xFFFD; i += 1; }       // truncated/invalid → replace, resync by 1
        else     { i += 1 + extra; }
        if (cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) cp = 0xFFFD;
        if (cp <= 0xFFFF) {
            u16.push_back((char16_t) cp);
        } else {
            cp -= 0x10000;
            u16.push_back((char16_t) (0xD800 + (cp >> 10)));
            u16.push_back((char16_t) (0xDC00 + (cp & 0x3FF)));
        }
    }
    return env->NewString(reinterpret_cast<const jchar *>(u16.data()), (jsize) u16.size());
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_it_belloworld_mercurygram_transcribe_MgWhisperNative_nativeInit(
        JNIEnv *env, jclass clazz, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) {
        return 0;
    }
    whisper_context_params cparams = whisper_context_default_params();
    // No GPU backend on Android in this build; CPU inference only.
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (ctx == nullptr) {
        LOGE("whisper_init_from_file failed");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
Java_it_belloworld_mercurygram_transcribe_MgWhisperNative_nativeTranscribe(
        JNIEnv *env, jclass clazz, jlong ctxPtr, jfloatArray pcm,
        jstring language, jboolean translate, jstring vadModelPath) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx == nullptr || pcm == nullptr) {
        return nullptr;
    }

    const jsize n = env->GetArrayLength(pcm);
    if (n <= 0) {
        return env->NewStringUTF("");
    }
    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);
    if (samples == nullptr) {
        return nullptr;
    }

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_realtime   = false;
    wparams.print_progress   = false;
    wparams.print_timestamps = false;
    wparams.print_special    = false;
    wparams.translate        = translate == JNI_TRUE;
    wparams.no_context       = true;
    wparams.single_segment   = false;
    wparams.suppress_blank   = true;
    // Cap threads at 4 — phones throttle hard above that and gain little.
    int nproc = (int) sysconf(_SC_NPROCESSORS_ONLN);
    if (nproc <= 0) nproc = 2;
    wparams.n_threads = nproc > 4 ? 4 : nproc;

    std::string lang;
    const char *langChars = nullptr;
    if (language != nullptr) {
        langChars = env->GetStringUTFChars(language, nullptr);
        if (langChars != nullptr) {
            lang = langChars;
        }
    }
    // Empty / "auto" / an unknown code -> let whisper auto-detect the spoken
    // language. NOTE: must NOT set detect_language=true here — in whisper_full
    // that flag means "detect the language and return immediately"
    // (whisper.cpp:5513), yielding 0 segments. Passing language="auto" triggers
    // the same detection inline (whisper.cpp:5501) and then transcribes.
    // whisper_lang_id() returns < 0 for codes whisper doesn't know, so an
    // unsupported device locale falls back to auto instead of failing.
    wparams.detect_language = false;
    if (lang.empty() || lang == "auto" || whisper_lang_id(lang.c_str()) < 0) {
        wparams.language = "auto";
    } else {
        wparams.language = lang.c_str();
    }

    // Peak / RMS of the input buffer — distinguishes a silent/garbage decode
    // (peak ~0) from a healthy speech signal (peak ~0.1-1.0). If the signal is
    // healthy but whisper still finds nothing, the problem is model/params.
    float peak = 0.0f;
    double sumSq = 0.0;
    for (jsize i = 0; i < n; ++i) {
        float v = samples[i];
        float a = v < 0 ? -v : v;
        if (a > peak) peak = a;
        sumSq += (double) v * v;
    }
    float rms = n > 0 ? (float) sqrt(sumSq / n) : 0.0f;
    LOGI("transcribe: samples=%d (%.1fs @16k) threads=%d lang=%s peak=%.4f rms=%.4f",
         (int) n, n / 16000.0f, wparams.n_threads,
         wparams.language ? wparams.language : "auto", peak, rms);

    // [MG] Voice Activity Detection: when a VAD model path is supplied, whisper
    // strips silence/non-speech before decoding so the tiny model can't
    // hallucinate text on silent/short clips. The VAD model is loaded once and
    // cached in the whisper state. Non-fatal: if loading/inference fails we
    // retry once without VAD so a missing/broken model never kills transcription.
    const char *vadChars = nullptr;
    bool vadEnabled = false;
    if (vadModelPath != nullptr) {
        vadChars = env->GetStringUTFChars(vadModelPath, nullptr);
        if (vadChars != nullptr && vadChars[0] != '\0' && access(vadChars, R_OK) == 0) {
            wparams.vad            = true;
            wparams.vad_model_path = vadChars;
            wparams.vad_params     = whisper_vad_default_params();
            vadEnabled = true;
        }
    }

    struct timespec t0{}, t1{};
    clock_gettime(CLOCK_MONOTONIC, &t0);
    int rc = whisper_full(ctx, wparams, samples, (int) n);
    if (rc != 0 && vadEnabled) {
        LOGE("whisper_full failed with VAD (rc=%d); retrying without VAD", rc);
        wparams.vad            = false;
        wparams.vad_model_path = nullptr;
        rc = whisper_full(ctx, wparams, samples, (int) n);
    }
    clock_gettime(CLOCK_MONOTONIC, &t1);
    const double elapsedSec = (t1.tv_sec - t0.tv_sec) + (t1.tv_nsec - t0.tv_nsec) / 1e9;
    const float audioSec = n / 16000.0f;

    if (langChars != nullptr) {
        env->ReleaseStringUTFChars(language, langChars);
    }
    if (vadChars != nullptr) {
        env->ReleaseStringUTFChars(vadModelPath, vadChars);
    }
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);

    if (rc != 0) {
        LOGE("whisper_full failed: %d", rc);
        return nullptr;
    }

    std::string out;
    const int segments = whisper_full_n_segments(ctx);
    LOGI("transcribe done: rc=%d segments=%d detectedLang=%s took=%.2fs (%.2fx realtime)",
         rc, segments, whisper_lang_str(whisper_full_lang_id(ctx)),
         elapsedSec, elapsedSec > 0 ? audioSec / elapsedSec : 0.0);
    for (int i = 0; i < segments; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text != nullptr) {
            out += text;
        }
    }
    // Trim a single leading space whisper tends to emit.
    if (!out.empty() && out[0] == ' ') {
        out.erase(0, 1);
    }
    return utf8ToJString(env, out);
}

JNIEXPORT void JNICALL
Java_it_belloworld_mercurygram_transcribe_MgWhisperNative_nativeFree(
        JNIEnv *env, jclass clazz, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx != nullptr) {
        whisper_free(ctx);
    }
}

} // extern "C"
