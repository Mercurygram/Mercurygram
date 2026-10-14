package it.belloworld.mercurygram.folders;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;

import java.util.List;

/**
 * Local visibility of chat folders in the chat-list tab strip.
 *
 * <p>A hidden folder keeps living everywhere else unchanged — the folder
 * settings list (where it can be brought back), the folder pickers, and, for
 * server folders, Telegram itself. Only its tab is omitted from the chat list.
 * This is presentation state on this device and this account alone; it never
 * reaches the network and never edits a folder definition.
 *
 * <p>The "All chats" entry reuses {@code UserConfig.mg.hideAllTab} (it already
 * had a setting of its own); every other folder is tracked by its id in
 * {@code UserConfig.mg.hiddenFolderIds}.
 */
public final class MgFolderVisibility {

    private MgFolderVisibility() {}

    public static boolean isHidden(UserConfig userConfig, MessagesController.DialogFilter filter) {
        if (filter == null) {
            return false;
        }
        if (filter.isDefault()) {
            return userConfig.mg.hideAllTab;
        }
        return userConfig.mg.hiddenFolderIds.contains(filter.id);
    }

    public static void setHidden(UserConfig userConfig, MessagesController.DialogFilter filter, boolean hidden) {
        if (filter == null) {
            return;
        }
        if (filter.isDefault()) {
            userConfig.mg.hideAllTab = hidden;
        } else if (hidden) {
            userConfig.mg.hiddenFolderIds.add(filter.id);
        } else {
            userConfig.mg.hiddenFolderIds.remove(filter.id);
        }
        userConfig.saveConfig(false);
    }

    /** Whether any folder of the list would show a tab. */
    public static boolean anyVisible(UserConfig userConfig, List<MessagesController.DialogFilter> filters) {
        for (int a = 0, N = filters.size(); a < N; a++) {
            if (!isHidden(userConfig, filters.get(a))) {
                return true;
            }
        }
        return false;
    }
}
