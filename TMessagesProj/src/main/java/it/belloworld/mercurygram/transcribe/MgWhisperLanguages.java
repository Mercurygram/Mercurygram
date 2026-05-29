package it.belloworld.mercurygram.transcribe;

/**
 * [MG] The set of spoken-language codes whisper.cpp can be pinned to.
 *
 * Mirrors the {@code g_lang} table in the vendored engine
 * ({@code TMessagesProj/jni/whisper/src/whisper.cpp}) — 100 entries, ISO-639-1
 * two-letter codes plus three irregulars ({@code haw}, {@code yue}). Driving the
 * language picker from this exact set guarantees every selectable entry is one
 * the engine honours; codes outside it would be silently downgraded to
 * auto-detect by the JNI bridge ({@code whisper_jni.cpp}), losing the user's
 * choice. Display names are resolved at runtime, so only the codes live here.
 *
 * Keep in sync with the engine on a whisper.cpp submodule bump.
 */
public final class MgWhisperLanguages {

    public static final String[] CODES = {
            "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr",
            "pl", "ca", "nl", "ar", "sv", "it", "id", "hi", "fi", "vi",
            "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta", "no",
            "th", "ur", "hr", "bg", "lt", "la", "mi", "ml", "cy", "sk",
            "te", "fa", "lv", "bn", "sr", "az", "sl", "kn", "et", "mk",
            "br", "eu", "is", "hy", "ne", "mn", "bs", "kk", "sq", "sw",
            "gl", "mr", "pa", "si", "km", "sn", "yo", "so", "af", "oc",
            "ka", "be", "tg", "sd", "gu", "am", "yi", "lo", "uz", "fo",
            "ht", "ps", "tk", "nn", "mt", "sa", "lb", "my", "bo", "tl",
            "mg", "as", "tt", "haw", "ln", "ha", "ba", "jw", "su", "yue",
    };

    private MgWhisperLanguages() {
    }
}
