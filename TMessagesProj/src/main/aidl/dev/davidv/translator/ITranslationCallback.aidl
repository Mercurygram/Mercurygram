// Mirror of dev.davidv.translator's published AIDL surface — see
// ITranslationService.aidl for the rationale.
package dev.davidv.translator;

import dev.davidv.translator.TranslationError;

oneway interface ITranslationCallback {
    void onTranslationResult(String translatedText);
    void onTranslationError(in TranslationError error);
}
