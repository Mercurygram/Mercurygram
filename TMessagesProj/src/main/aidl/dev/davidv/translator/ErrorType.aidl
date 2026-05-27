// Mirror of dev.davidv.translator's published AIDL surface — see
// ITranslationService.aidl for the rationale.
package dev.davidv.translator;

enum ErrorType {
    COULD_NOT_DETECT_LANGUAGE,
    DETECTED_BUT_UNAVAILABLE,
    UNEXPECTED,
}
