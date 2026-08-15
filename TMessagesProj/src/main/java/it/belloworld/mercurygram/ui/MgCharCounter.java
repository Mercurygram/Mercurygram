package it.belloworld.mercurygram.ui;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberTextView;

/**
 * Live character counter above the message input field, opt-in per account
 * ({@code UserConfig.mg.showCharCounter}). Useful in groups where a bot
 * enforces a message-length limit: upstream only shows a counter once one of
 * Telegram's own limits is nearly reached.
 *
 * <p>The count is the code-point count already computed by
 * {@code ChatActivityEnterView.afterTextChanged}, which is the unit the Bot API
 * reports as the message length.
 *
 * <p>State lives here, not in the upstream view: the counter is looked up by
 * tag and created on first show, so the hook in
 * {@code ChatActivityEnterView} stays a single line.
 */
public final class MgCharCounter {

    private static final String TAG = "mg_char_counter";

    private MgCharCounter() {
    }

    /**
     * @param limitVisible upstream's remaining-characters counter is showing or
     *                     is being shown by this same text change; it occupies
     *                     the same slot and takes precedence.
     */
    public static void update(FrameLayout parent, Theme.ResourcesProvider resourcesProvider, int currentAccount, int count, boolean limitVisible) {
        final boolean show = count > 0
                && !limitVisible
                && UserConfig.getInstance(currentAccount).mg.showCharCounter;
        NumberTextView view = parent.findViewWithTag(TAG);
        if (view == null) {
            if (!show) {
                return;
            }
            view = new NumberTextView(parent.getContext());
            view.setTag(TAG);
            view.setTextSize(15);
            view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            view.setTypeface(AndroidUtilities.bold());
            view.setCenterAlign(true);
            view.setVisibility(View.GONE);
            parent.addView(view, Math.min(2, parent.getChildCount()), LayoutHelper.createFrame(ChatActivityEnterView.DEFAULT_HEIGHT, 20, Gravity.BOTTOM | Gravity.RIGHT, 3, 0, 0, ChatActivityEnterView.DEFAULT_HEIGHT));
        }
        if (show) {
            view.setNumber(count, view.getVisibility() == View.VISIBLE);
            view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            view.setVisibility(View.VISIBLE);
        } else {
            view.setVisibility(View.GONE);
        }
    }
}
