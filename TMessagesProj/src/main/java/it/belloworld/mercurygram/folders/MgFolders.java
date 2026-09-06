package it.belloworld.mercurygram.folders;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesController.DialogFilter;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Mercurygram folders: ordinary {@link DialogFilter}s with a negative id that
 * live only in this device's dialog_filter tables and are never sent to
 * Telegram. Folder membership is computed client-side anyway, so the server
 * only ever stores the definition; keeping that definition off the server
 * removes the folder-count and chats-per-folder limits. Server ids are 0
 * (All chats) and >= 2, so the sign of the id is the whole discriminator.
 */
public final class MgFolders {

    private MgFolders() {}

    public static boolean isMercurygram(DialogFilter filter) {
        return filter != null && filter.id < 0;
    }

    /**
     * Whether the server reconciliation must leave this folder alone: Mercurygram
     * folders always, and the "All chats" entry while any of them exists (the
     * server only ships it alongside server folders, and the tab strip needs it).
     */
    public static boolean keepOnTrim(DialogFilter filter, List<DialogFilter> all) {
        return isMercurygram(filter) || filter.isDefault() && hasMercurygram(all);
    }

    public static boolean hasMercurygram(List<DialogFilter> filters) {
        for (int a = 0, N = filters.size(); a < N; a++) {
            if (isMercurygram(filters.get(a))) {
                return true;
            }
        }
        return false;
    }

    /** Creates the "All chats" entry when missing, so a first Mercurygram folder gets a tab strip. */
    public static void ensureDefaultFilter(AccountInstance accountInstance) {
        final MessagesController messagesController = accountInstance.getMessagesController();
        if (messagesController.dialogFiltersById.get(0) != null) {
            return;
        }
        final DialogFilter filter = new DialogFilter();
        filter.id = 0;
        filter.name = "ALL_CHATS";
        filter.color = -1;
        messagesController.dialogFilters.add(0, filter);
        messagesController.dialogFiltersById.put(0, filter);
        accountInstance.getMessagesStorage().saveDialogFilter(filter, false, false);
        accountInstance.getMessagesStorage().saveDialogFiltersOrder();
    }

    /**
     * A free id of the requested kind: the first free 2, 3, ... for server folders
     * (mirrors the loop in FilterCreateActivity's constructor, the two have to agree
     * on the id space), a random negative one for Mercurygram folders. Random
     * because the id has to be unique across every device on the account, and this
     * device only knows its own folders: two devices that each create their first
     * Mercurygram folder before they sync would both pick -1, and the pull would
     * then read the two folders as one and overwrite whichever landed second.
     * Never -1 itself: the format reserves it, because the UI code in both clients
     * uses -1 as a "no folder" placeholder.
     */
    public static int newId(MessagesController messagesController, boolean mercurygram) {
        if (!mercurygram) {
            int id = 2;
            while (messagesController.dialogFiltersById.get(id) != null) {
                id++;
            }
            return id;
        }
        int id;
        do {
            id = -2 - Utilities.random.nextInt(Integer.MAX_VALUE - 1);
        } while (messagesController.dialogFiltersById.get(id) != null);
        return id;
    }

    /**
     * Re-keys an existing folder into the other id range, so a server folder
     * becomes a Mercurygram one or a Mercurygram one is created on the server.
     * Same object, new id: the tab strip keys on the upstream {@code localId}
     * field and the selected filter is held by reference, so nothing else has to
     * change hands. Call on the UI thread before {@code saveFilterToServer},
     * which then sends a plain create for a positive id and is answered on the
     * client for a negative one. Storage work is
     * posted in order: the old rows go first, the save that follows writes the
     * new id, and the order rewrite re-keys the storage map.
     */
    public static void move(AccountInstance accountInstance, DialogFilter filter, boolean mercurygram) {
        final MessagesController messagesController = accountInstance.getMessagesController();
        final int oldId = filter.id;
        filter.id = newId(messagesController, mercurygram);
        messagesController.dialogFiltersById.remove(oldId);
        messagesController.dialogFiltersById.put(filter.id, filter);
        final DialogFilter stale = new DialogFilter(); // only its id is read: rows and storage map under oldId
        stale.id = oldId;
        accountInstance.getMessagesStorage().deleteDialogFilter(stale);
        if (oldId > 0) {
            final TLRPC.TL_messages_updateDialogFilter req = new TLRPC.TL_messages_updateDialogFilter();
            req.id = oldId;
            accountInstance.getConnectionsManager().sendRequest(req, null);
        }
        if (mercurygram) {
            ensureDefaultFilter(accountInstance);
        }
        accountInstance.getMessagesStorage().saveDialogFiltersOrder();
        messagesController.lockFiltersInternal();
    }

    /** Whether the account can take no more server folders. */
    public static boolean serverFull(AccountInstance accountInstance) {
        final MessagesController messagesController = accountInstance.getMessagesController();
        final int count = remoteCount(messagesController.getDialogFilters());
        return count - 1 >= messagesController.dialogFiltersLimitDefault && !accountInstance.getUserConfig().isPremium() ||
            count >= messagesController.dialogFiltersLimitPremium;
    }

    /** How many of these count against the server's folder limit. */
    public static int remoteCount(List<DialogFilter> filters) {
        int count = 0;
        for (int a = 0, N = filters.size(); a < N; a++) {
            if (!isMercurygram(filters.get(a))) {
                count++;
            }
        }
        return count;
    }

    public static ArrayList<DialogFilter> remote(List<DialogFilter> filters) {
        return partition(filters, false);
    }

    public static ArrayList<DialogFilter> mercurygram(List<DialogFilter> filters) {
        return partition(filters, true);
    }

    private static ArrayList<DialogFilter> partition(List<DialogFilter> filters, boolean mercurygram) {
        final ArrayList<DialogFilter> out = new ArrayList<>(filters.size());
        for (int a = 0, N = filters.size(); a < N; a++) {
            if (isMercurygram(filters.get(a)) == mercurygram) {
                out.add(filters.get(a));
            }
        }
        return out;
    }

    /**
     * The one place a folder id is checked before it goes on the wire, hooked into
     * {@link org.telegram.tgnet.ConnectionsManager} so no folder RPC call site has
     * to remember: an order request loses its negative ids, and a request about a
     * Mercurygram folder is answered here with an empty response, so the caller's
     * success path still runs but nothing is sent. Returns whether the request
     * was answered and must not be sent.
     */
    public static boolean dropIfMercurygram(TLObject request, RequestDelegate callback) {
        if (request instanceof TLRPC.TL_messages_updateDialogFiltersOrder) {
            ((TLRPC.TL_messages_updateDialogFiltersOrder) request).order.removeIf(id -> id < 0);
            return false;
        }
        if (!(request instanceof TLRPC.TL_messages_updateDialogFilter) || ((TLRPC.TL_messages_updateDialogFilter) request).id >= 0) {
            return false;
        }
        if (callback != null) {
            callback.run(null, null);
        }
        return true;
    }

    /**
     * Applies a server-side folder order to a list that may also hold
     * Mercurygram folders. Only the slots holding a folder the server actually
     * listed are reshuffled, into {@code serverOrder}; every other folder keeps
     * its index. That covers Mercurygram folders and the "All chats" entry, which
     * {@link #keepOnTrim} keeps alive even when the server omits it — ordering
     * it as "unknown, therefore last" would drop the All chats tab to the end
     * of the strip. Renumbers {@code order} by index; returns whether any
     * order changed.
     */
    public static boolean mergeRemoteOrder(ArrayList<DialogFilter> filters, ArrayList<Integer> serverOrder) {
        final HashMap<Integer, Integer> position = new HashMap<>();
        for (int a = 0, N = serverOrder.size(); a < N; a++) {
            position.put(serverOrder.get(a), a);
        }
        final DialogFilter[] ordered = new DialogFilter[serverOrder.size()];
        for (int a = 0, N = filters.size(); a < N; a++) {
            final Integer slot = position.get(filters.get(a).id);
            if (slot != null) {
                ordered[slot] = filters.get(a);
            }
        }
        int next = 0;
        for (int a = 0, N = filters.size(); a < N; a++) {
            if (position.containsKey(filters.get(a).id)) {
                while (ordered[next] == null) {
                    next++;
                }
                filters.set(a, ordered[next++]);
            }
        }
        boolean changed = false;
        for (int a = 0, N = filters.size(); a < N; a++) {
            if (filters.get(a).order != a) {
                filters.get(a).order = a;
                changed = true;
            }
        }
        return changed;
    }
}
