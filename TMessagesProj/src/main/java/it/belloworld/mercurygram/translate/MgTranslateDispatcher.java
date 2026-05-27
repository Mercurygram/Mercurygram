package it.belloworld.mercurygram.translate;

import android.widget.Toast;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.Components.TranslateAlert2;

/**
 * Central dispatcher for Mercurygram translation modes. Replaces the
 * inline four-branch dispatch that previously lived only inside
 * {@code TranslateController.pushToTranslate} so the chat translate
 * bar, the long-press Translate modal, photo captions and story
 * captions all route through the same engine when the user picks a
 * non-default {@link SharedConfig#mg_translateMode}.
 *
 * <p>Privacy invariant: the {@code "offline"} mode NEVER silently
 * falls back to Telegram cloud. When the AIDL provider is missing or
 * fails, the fallback is Alternative HTTP (gated by
 * {@link SharedConfig#mg_translateAutoFallback}) — a user who picked
 * offline did not pick Telegram-mediated translation.
 */
public final class MgTranslateDispatcher {

    private MgTranslateDispatcher() {}

    /**
     * Tells the caller what to do.
     * <ul>
     *   <li>{@link #HANDLED} — the dispatcher took ownership; {@code Result.done} will fire on
     *       success or failure. The caller MUST NOT run its upstream RPC.</li>
     *   <li>{@link #FORCE_CLOUD} — caller must run the Telegram
     *       {@code messages.translateText} RPC, ignoring upstream
     *       {@code translationsManualEnabled}/{@code translationsAutoEnabled} branching.</li>
     *   <li>{@link #PUNT_TO_UPSTREAM} — caller runs upstream logic unchanged.</li>
     * </ul>
     */
    public enum Outcome { HANDLED, FORCE_CLOUD, PUNT_TO_UPSTREAM }

    /** Result emitted by the dispatcher when {@link Outcome#HANDLED}. */
    public interface Result {
        /**
         * {@code text != null} ⇒ success. On failure {@code text == null} and
         * {@code failure != null}. {@code rateLimit} is meaningful only on the
         * Alternative HTTP arm (it maps to {@code TranslationFailedAlert1} per
         * upstream convention).
         */
        void done(@Nullable String text, boolean rateLimit, @Nullable MgAidlTranslate.Failure failure);
    }

    /**
     * Pick the dispatch path for the current {@link SharedConfig#mg_translateMode}.
     * Read the {@link Outcome} return value to decide whether to also run the
     * caller's upstream RPC.
     */
    public static Outcome dispatch(String text, @Nullable String fromLng, String toLng, Result done) {
        final String mode = SharedConfig.mg_translateMode;
        if (SharedConfig.MG_TRANSLATE_MODE_DEFAULT.equals(mode)) {
            return Outcome.PUNT_TO_UPSTREAM;
        }
        if (SharedConfig.MG_TRANSLATE_MODE_CLOUD.equals(mode)) {
            return Outcome.FORCE_CLOUD;
        }
        if (SharedConfig.MG_TRANSLATE_MODE_OFFLINE.equals(mode)
                && MgAidlTranslate.isUsable(ApplicationLoader.applicationContext)) {
            MgAidlTranslate.translate(text, toLng, (aidlText, aidlRate, aidlFailure) -> {
                if (aidlText != null) {
                    maybeShowFormatToast();
                    done.done(aidlText, false, null);
                    return;
                }
                if (SharedConfig.mg_translateAutoFallback) {
                    runAlternative(text, fromLng, toLng, done);
                } else {
                    done.done(null, false, aidlFailure != null
                            ? aidlFailure
                            : MgAidlTranslate.Failure.of(MgAidlTranslate.Reason.UNEXPECTED));
                }
            });
            return Outcome.HANDLED;
        }
        // "alternative", or "offline" with no usable provider.
        // The provider-missing downgrade preserves the privacy invariant:
        // offline-mode failure routes through Alternative HTTP, never Telegram cloud.
        runAlternative(text, fromLng, toLng, done);
        return Outcome.HANDLED;
    }

    private static void runAlternative(String text, @Nullable String fromLng, String toLng, Result done) {
        TranslateAlert2.alternativeTranslate(text, fromLng, toLng, (altText, altRate) -> {
            if (altText != null) {
                done.done(altText, false, null);
            } else {
                done.done(null, altRate, MgAidlTranslate.Failure.of(MgAidlTranslate.Reason.UNEXPECTED));
            }
        });
    }

    private static void maybeShowFormatToast() {
        if (!MgAidlTranslate.shouldShowFormatToast()) return;
        SharedConfig.setMgTranslateOfflineFormatToastShown();
        AndroidUtilities.runOnUIThread(() -> {
            if (ApplicationLoader.applicationContext == null) return;
            Toast.makeText(ApplicationLoader.applicationContext,
                    MgAidlTranslate.formatToastText(),
                    Toast.LENGTH_LONG).show();
        });
    }

    /**
     * Map an offline-mode failure to a localized bulletin string id, with the
     * language argument substituted where relevant. Returns the upstream
     * {@code TranslationFailedAlert1/2} generic strings on rate-limit / alt
     * failure so the existing UX is preserved when the offline path wasn't
     * the one that failed.
     */
    public static CharSequence mapBulletin(@Nullable MgAidlTranslate.Failure failure, boolean rateLimit) {
        if (failure == null) {
            return LocaleController.getString(rateLimit
                    ? R.string.TranslationFailedAlert1
                    : R.string.TranslationFailedAlert2);
        }
        switch (failure.reason) {
            case MODEL_NOT_INSTALLED:
                return LocaleController.formatString(R.string.MercurygramTranslateOfflineNoModel,
                        languageDisplayName(failure.language));
            case LANGUAGE_NOT_DETECTED:
                return LocaleController.getString(R.string.MercurygramTranslateOfflineNoLanguage);
            case PROVIDER_UNAVAILABLE:
                return LocaleController.getString(R.string.MercurygramTranslateOfflineProviderUnavailable);
            case UNEXPECTED:
            default:
                if (rateLimit) {
                    return LocaleController.getString(R.string.TranslationFailedAlert1);
                }
                return LocaleController.getString(R.string.MercurygramTranslateOfflineFailed);
        }
    }

    private static String languageDisplayName(@Nullable String code) {
        if (code == null || code.isEmpty()) return "";
        try {
            String display = new java.util.Locale(code).getDisplayLanguage();
            return display == null || display.isEmpty() ? code : display;
        } catch (Throwable t) {
            return code;
        }
    }
}
