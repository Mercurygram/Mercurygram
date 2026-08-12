package it.belloworld.mercurygram.ui;

import android.text.TextUtils;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.Components.UItem;

/**
 * Scope hints for the Mercurygram settings screens. Most MG options are stored
 * per account ({@code userConfig.mg.*}); a minority live in the global
 * {@code SharedConfig} and therefore change every account on the device. Only
 * the global ones are labelled, and only when more than one account is logged
 * in, since on a single-account install the distinction means nothing.
 */
final class MgSettingsScope {

    private MgSettingsScope() {
    }

    static boolean multiAccount() {
        return UserConfig.getActivatedAccountsCount() > 1;
    }

    /** Check row backed by a global {@code SharedConfig} field. */
    static UItem globalCheck(int id, CharSequence text) {
        if (!multiAccount()) {
            return UItem.asCheck(id, text);
        }
        return UItem.asButtonCheck(id, text, LocaleController.getString(R.string.MercurygramScopeAllAccounts));
    }

    /** Appends the all-accounts note to a section footer (for non-check global rows). */
    static CharSequence withAllAccountsNote(CharSequence about) {
        if (!multiAccount()) {
            return about;
        }
        CharSequence note = LocaleController.getString(R.string.MercurygramScopeAllAccountsFooter);
        if (TextUtils.isEmpty(about)) {
            return note;
        }
        return about + "\n\n" + note;
    }
}
