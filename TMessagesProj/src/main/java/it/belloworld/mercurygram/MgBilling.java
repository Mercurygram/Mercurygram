package it.belloworld.mercurygram;

import org.telegram.messenger.BuildVars;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.Premium.PremiumNotAvailableBottomSheet;
import org.telegram.ui.LaunchActivity;

/**
 * Guard for the purchase paths that do not go through
 * {@link org.telegram.ui.PremiumPreviewFragment#buyPremium} or
 * {@link org.telegram.ui.Stars.StarsController}, which check
 * {@link BuildVars#IS_BILLING_UNAVAILABLE} themselves. Those paths build a Telegram invoice
 * directly, so on the build shipped to Google Play they would sell digital goods outside Play
 * Billing.
 *
 * Only Telegram's own goods are gated. Bot and mini-app invoices, t.me/$slug links and
 * fragment.com links are third-party merchant payments that the official app ships on Play too.
 */
public class MgBilling {

    /** True when the purchase was blocked; shows upstream's "official app needed" sheet. */
    public static boolean blockPurchase(BaseFragment fragment) {
        if (!BuildVars.IS_BILLING_UNAVAILABLE) {
            return false;
        }
        // A fragment without a parent layout cannot host a dialog (callers hand over throwaway
        // fragments that only carry an activity and a theme), so the sheet goes to whatever is
        // on screen instead of being swallowed.
        if (fragment == null || fragment.getParentLayout() == null) {
            fragment = LaunchActivity.getSafeLastFragment();
        }
        if (fragment != null && fragment.getParentActivity() != null) {
            fragment.showDialog(new PremiumNotAvailableBottomSheet(fragment));
        }
        return true;
    }
}
