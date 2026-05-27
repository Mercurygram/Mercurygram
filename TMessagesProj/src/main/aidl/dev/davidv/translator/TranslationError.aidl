// Mirror of dev.davidv.translator's published AIDL surface — see
// ITranslationService.aidl for the rationale.
package dev.davidv.translator;

import dev.davidv.translator.ErrorType;

parcelable TranslationError {
    ErrorType type;
    @nullable String language;
    @nullable String message;
}
