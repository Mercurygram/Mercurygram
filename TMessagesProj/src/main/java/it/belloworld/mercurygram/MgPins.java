package it.belloworld.mercurygram;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Pinned chats in the All chats list past the handful the server takes.
 *
 * The list is not folder backed - the All chats tab renders as the plain dialogs
 * list, ordered by the server-owned pinned / pinnedNum fields of each dialog -
 * so the Mercurygram folders trick does not apply as is. Instead the order is
 * kept here as a plain list of dialog ids, top first, in the per-account
 * settings, and written back onto the dialogs right before every sort. Those two
 * fields stay the display truth, so the pin icon, the drag handle, the action
 * bar counters and the pinned-run scans upstream all keep working untouched.
 *
 * The first few pins still go to the server, so other clients keep a sane subset;
 * see the cut in MessagesController.reorderPinnedDialogs. The request is sent
 * with force set, so the server unpins the chats left out of it, which is exactly
 * what is wanted: past that point the pin is this device's business and lives in
 * the list below.
 *
 * Known limit: an unpin made on another device while this one was offline long
 * enough to miss the update arrives only as a wholesale refresh, which merely
 * clears the flags, and the next sort revives the pin from this list. It stays
 * until the user unpins it here.
 */
public final class MgPins {

    private MgPins() {}

    /** The All chats pin cap: the one the archive and the folder tabs already use. */
    public static int maxPinned(int currentAccount) {
        final MessagesController messagesController = MessagesController.getInstance(currentAccount);
        return UserConfig.getInstance(currentAccount).isPremium()
            ? messagesController.maxFolderPinnedDialogsCountPremium
            : messagesController.maxFolderPinnedDialogsCountDefault;
    }

    /** What the server itself takes: the rest of the list is this device's business. */
    public static int serverMaxPinned(int currentAccount) {
        final MessagesController messagesController = MessagesController.getInstance(currentAccount);
        return UserConfig.getInstance(currentAccount).isPremium()
            ? messagesController.maxPinnedDialogsCountPremium
            : messagesController.maxPinnedDialogsCountDefault;
    }

    /** The archive row and the chats inside it: those pins stay the server's business. */
    private static boolean skip(TLRPC.Dialog dialog) {
        return dialog instanceof TLRPC.TL_dialogFolder || dialog.folder_id != 0;
    }

    /** Restores this device's pin order, and adopts pins it does not know yet. Call before sorting. */
    public static void apply(int currentAccount, List<TLRPC.Dialog> allDialogs) {
        final UserConfig userConfig = UserConfig.getInstance(currentAccount);
        if (apply(userConfig.mg.mainPins, allDialogs)) {
            userConfig.saveConfig(false);
        }
    }

    /**
     * Writes {@code order} onto the dialogs and returns whether it grew. A dialog
     * pinned but not listed was pinned elsewhere (or on the server before this
     * device ever ran the feature), so it joins the top, highest pinnedNum first,
     * which is the order it is displayed in. An id whose dialog is not loaded or
     * has moved to the archive is left in place and skipped: the numbers only
     * matter relative to each other, so a gap changes nothing, and dropping such
     * an id would lose the pin of a chat that is merely not loaded yet.
     */
    static boolean apply(ArrayList<Long> order, List<TLRPC.Dialog> dialogs) {
        final HashSet<Long> known = new HashSet<>(order);
        // Only the pinned chats are ever looked up, so the map stays at the size of the pin list, not of the whole chat list: this runs on every sort.
        final HashMap<Long, TLRPC.Dialog> byId = new HashMap<>();
        final ArrayList<TLRPC.Dialog> adopted = new ArrayList<>();
        for (int a = 0, N = dialogs.size(); a < N; a++) {
            final TLRPC.Dialog dialog = dialogs.get(a);
            if (skip(dialog)) {
                continue;
            }
            if (known.contains(dialog.id)) {
                byId.put(dialog.id, dialog);
            } else if (dialog.pinned) {
                byId.put(dialog.id, dialog);
                adopted.add(dialog);
            }
        }
        Collections.sort(adopted, (dialog1, dialog2) -> Integer.compare(dialog2.pinnedNum, dialog1.pinnedNum));
        for (int a = 0, N = adopted.size(); a < N; a++) {
            order.add(a, adopted.get(a).id);
        }
        for (int a = 0, N = order.size(); a < N; a++) {
            final TLRPC.Dialog dialog = byId.get(order.get(a));
            if (dialog == null) {
                continue;
            }
            dialog.pinned = true;
            dialog.pinnedNum = N - a;
        }
        return !adopted.isEmpty();
    }

    /** A pin or unpin in the All chats list. */
    public static void set(int currentAccount, TLRPC.Dialog dialog, boolean pinned) {
        if (skip(dialog)) {
            return;
        }
        final UserConfig userConfig = UserConfig.getInstance(currentAccount);
        userConfig.mg.mainPins.remove((Long) dialog.id);
        if (pinned) {
            userConfig.mg.mainPins.add(0, dialog.id);
        }
        userConfig.saveConfig(false);
    }

    /**
     * The user dragged one pinned chat past another. Not written out here: a drag
     * fires this once per row crossed, so the caller saves once the drag is over.
     */
    public static void swap(int currentAccount, TLRPC.Dialog fromDialog, TLRPC.Dialog toDialog) {
        if (skip(fromDialog) || skip(toDialog)) {
            return;
        }
        final ArrayList<Long> mainPins = UserConfig.getInstance(currentAccount).mg.mainPins;
        final int from = mainPins.indexOf(fromDialog.id);
        final int to = mainPins.indexOf(toDialog.id);
        if (from < 0 || to < 0) {
            return;
        }
        Collections.swap(mainPins, from, to);
    }

    /** Writes out the order the drag left behind. */
    public static void saveOrder(int currentAccount) {
        UserConfig.getInstance(currentAccount).saveConfig(false);
    }
}
