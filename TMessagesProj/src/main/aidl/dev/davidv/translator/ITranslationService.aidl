// Mirror of dev.davidv.translator's published AIDL surface — must remain
// byte-identical to the provider's copy at the same package path or the
// binder proxy will reject the connection. Upstream:
//   https://github.com/DavidVentura/offline-translator
package dev.davidv.translator;

import dev.davidv.translator.ITranslationCallback;

interface ITranslationService {
    void translate(String textToTranslate, String fromLanguage, String toLanguage, ITranslationCallback callback);
}
