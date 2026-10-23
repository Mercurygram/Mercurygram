package org.telegram.messenger;

import android.icu.util.ULocale;
import android.os.Build;
import android.text.TextUtils;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextLanguage;

// Mercurygram: MLKit went with GMS, so detection runs through the platform
// TextClassifier (API 29+) instead. It works on the device and needs no extra
// library, but not every ROM ships a classifier that can detect languages, so
// hasSupport() is backed by a probe and stays false until one has passed. The
// last probe result is kept in mainconfig, so later launches know straight away.
public class LanguageDetector {
    public interface StringCallback {
        void run(String str);
    }
    public interface ExceptionCallback {
        void run(Exception e);
    }

    private static final String PROBE_TEXT = "The quick brown fox jumps over the lazy dog and runs back into the forest.";
    private static final float MIN_CONFIDENCE = 0.5f;
    private static final int MAX_TEXT_LENGTH = 1000;

    private static volatile boolean supported = MessagesController.getGlobalMainSettings().getBoolean("mg_languageDetectorSupported", false);
    private static boolean probing;
    private static DispatchQueue queue;

    public static boolean hasSupport() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && supported;
    }

    // Called once at startup. Until the probe ends, hasSupport() answers with
    // the result of the previous launch.
    public static void probe() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        synchronized (LanguageDetector.class) {
            if (probing) {
                return;
            }
            probing = true;
        }
        getQueue().postRunnable(() -> {
            boolean result;
            try {
                result = "en".equals(detect(PROBE_TEXT));
            } catch (Throwable t) {
                FileLog.e(t, false);
                result = false;
            }
            supported = result;
            MessagesController.getGlobalMainSettings().edit().putBoolean("mg_languageDetectorSupported", result).apply();
        });
    }

    public static void detectLanguage(String text, StringCallback onSuccess, ExceptionCallback onFail) {
        detectLanguage(text, onSuccess, onFail, false);
    }

    public static void detectLanguage(String text, StringCallback onSuccess, ExceptionCallback onFail, boolean initializeFirst) {
        // Mercurygram contract: without support this MUST invoke onFail
        // synchronously and never onSuccess. TranslateController.detectStory/
        // detectPhotoLanguage dropped their !hasSupport() short-circuit and rely
        // on this onFail to mark captions UNKNOWN_LANGUAGE (so the Translate
        // affordance surfaces). Do not make this path async or silent without
        // restoring those guards.
        if (!hasSupport()) {
            if (onFail != null) {
                onFail.run(new Exception("Language detection not available"));
            }
            return;
        }
        // Like MLKit's task listeners, both callbacks run on the UI thread;
        // undetermined text succeeds with "und", as it did with MLKit.
        getQueue().postRunnable(() -> {
            final String language;
            try {
                language = detect(text);
            } catch (Exception e) {
                FileLog.e(e, false);
                AndroidUtilities.runOnUIThread(() -> {
                    if (onFail != null) {
                        onFail.run(e);
                    }
                });
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (onSuccess != null) {
                    onSuccess.run(language);
                }
            });
        });
    }

    private static String detect(String text) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || TextUtils.isEmpty(text)) {
            return TranslateController.UNKNOWN_LANGUAGE;
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            text = text.substring(0, MAX_TEXT_LENGTH);
        }
        TextClassificationManager manager = ApplicationLoader.applicationContext.getSystemService(TextClassificationManager.class);
        if (manager == null) {
            throw new IllegalStateException("TextClassificationManager not available");
        }
        TextClassifier classifier = manager.getTextClassifier();
        TextLanguage result = classifier.detectLanguage(new TextLanguage.Request.Builder(text).build());
        if (result.getLocaleHypothesisCount() == 0) {
            return TranslateController.UNKNOWN_LANGUAGE;
        }
        ULocale locale = result.getLocale(0);
        String language = locale.getLanguage();
        if (TextUtils.isEmpty(language) || result.getConfidenceScore(locale) < MIN_CONFIDENCE) {
            return TranslateController.UNKNOWN_LANGUAGE;
        }
        return language;
    }

    private static synchronized DispatchQueue getQueue() {
        if (queue == null) {
            queue = new DispatchQueue("languageDetector");
        }
        return queue;
    }
}
