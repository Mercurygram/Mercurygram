package it.belloworld.mercurygram;

import java.util.ArrayList;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.Components.FilterTabsView;

/**
 * Default-launch-folder helpers shared by DialogsActivity's first-build
 * auto-select and its back-press handler, so both use one match rule.
 */
public final class MgDefaultFolder {

    private MgDefaultFolder() {}

    // Mercurygram: index into getDialogFilters() of the configured default
    // launch folder, or -1 when unset, deleted, or locked. Single source of
    // the match rule shared by the back-press helper and the first-build
    // auto-select so they can never disagree on which folder opens.
    public static int folderIndex(UserConfig userConfig, MessagesController messagesController) {
        final int defaultFolderId = userConfig.mg.defaultFolderId;
        if (defaultFolderId == 0) {
            return -1;
        }
        final ArrayList<MessagesController.DialogFilter> filters = messagesController.getDialogFilters();
        for (int a = 0, N = filters.size(); a < N; a++) {
            final MessagesController.DialogFilter filter = filters.get(a);
            if (filter.id == defaultFolderId && !filter.locked) {
                return a;
            }
        }
        return -1;
    }

    // Mercurygram: FilterTabsView stableId (DialogFilter.localId) of the configured
    // default launch folder, or -1 when unset, deleted, or locked.
    public static int stableId(UserConfig userConfig, MessagesController messagesController) {
        final int idx = folderIndex(userConfig, messagesController);
        if (idx < 0) {
            return -1;
        }
        return messagesController.getDialogFilters().get(idx).localId;
    }

    // Mercurygram: true when the current tab is where a back press should land
    // before exiting: the configured default launch folder when valid (and the
    // All-chats tab is shown), otherwise the first tab. Gates the back handler so
    // it consumes the press only while redirecting to that tab; once already on
    // it the press falls through to exit, instead of re-selecting forever.
    public static boolean isOnBackLandingTab(UserConfig userConfig, MessagesController messagesController, FilterTabsView filterTabsView) {
        if (filterTabsView == null) {
            return true;
        }
        if (!userConfig.mg.hideAllTab) {
            final int defaultStableId = stableId(userConfig, messagesController);
            if (defaultStableId >= 0) {
                return filterTabsView.getCurrentTabStableId() == defaultStableId;
            }
        }
        return filterTabsView.isFirstTabSelected();
    }
}
