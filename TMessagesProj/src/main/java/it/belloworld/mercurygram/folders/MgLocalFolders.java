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
 * Local folders: ordinary {@link DialogFilter}s with a negative id that live only
 * in this device's dialog_filter tables and are never sent to Telegram. Folder
 * membership is computed client-side anyway, so the server only ever stores the
 * definition; keeping that definition local removes the folder-count and
 * chats-per-folder limits. Server ids are 0 (All chats) and >= 2, so the sign
 * of the id is the whole discriminator.
 */
public final class MgLocalFolders {

    private MgLocalFolders() {}

    public static boolean isLocal(DialogFilter filter) {
        return filter != null && filter.id < 0;
    }

    /**
     * Whether the server reconciliation must leave this folder alone: local folders
     * always, and the "All chats" entry while any local folder exists (the server
     * only ships it alongside server folders, and the tab strip needs it).
     */
    public static boolean keepOnTrim(DialogFilter filter, List<DialogFilter> all) {
        return isLocal(filter) || filter.isDefault() && hasLocal(all);
    }

    public static boolean hasLocal(List<DialogFilter> filters) {
        for (int a = 0, N = filters.size(); a < N; a++) {
            if (isLocal(filters.get(a))) {
                return true;
            }
        }
        return false;
    }

    /** Creates the "All chats" entry when missing, so a first local folder gets a tab strip. */
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
     * on the id space), a random negative one for local folders. Random because a
     * local id has to be unique across every device on the account, and this device
     * only knows its own folders: two devices that each create their first local folder before they sync
     * would both pick -1, and the pull would then read the two folders as one and
     * overwrite whichever landed second.
     */
    public static int newId(MessagesController messagesController, boolean local) {
        if (!local) {
            int id = 2;
            while (messagesController.dialogFiltersById.get(id) != null) {
                id++;
            }
            return id;
        }
        int id;
        do {
            id = -1 - Utilities.random.nextInt(Integer.MAX_VALUE);
        } while (messagesController.dialogFiltersById.get(id) != null);
        return id;
    }

    /** How many of these count against the server's folder limit. */
    public static int remoteCount(List<DialogFilter> filters) {
        int count = 0;
        for (int a = 0, N = filters.size(); a < N; a++) {
            if (!isLocal(filters.get(a))) {
                count++;
            }
        }
        return count;
    }

    public static ArrayList<DialogFilter> remote(List<DialogFilter> filters) {
        return partition(filters, false);
    }

    public static ArrayList<DialogFilter> local(List<DialogFilter> filters) {
        return partition(filters, true);
    }

    private static ArrayList<DialogFilter> partition(List<DialogFilter> filters, boolean local) {
        final ArrayList<DialogFilter> out = new ArrayList<>(filters.size());
        for (int a = 0, N = filters.size(); a < N; a++) {
            if (isLocal(filters.get(a)) == local) {
                out.add(filters.get(a));
            }
        }
        return out;
    }

    /**
     * The one place a folder id is checked before it goes on the wire, hooked into
     * {@link org.telegram.tgnet.ConnectionsManager} so no folder RPC call site has
     * to remember: an order request loses its local ids, and a request about a
     * local folder is answered here with an empty response, so the caller's
     * success path still runs but nothing is sent. Returns whether the request
     * was answered and must not be sent.
     */
    public static boolean dropIfLocal(TLObject request, RequestDelegate callback) {
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
     * Applies a server-side folder order to a list that may also hold local
     * folders. Only the slots holding a folder the server actually listed are
     * reshuffled, into {@code serverOrder}; every other folder keeps its index.
     * That covers local folders and the "All chats" entry, which
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
