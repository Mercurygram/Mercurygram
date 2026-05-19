package it.belloworld.mercurygram.ui;

import android.util.LongSparseArray;
import android.util.SparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import it.belloworld.mercurygram.MgMessageHistory;

/**
 * Saved-message-history ghost machinery for one ChatActivity: keeps the
 * per-chat deleted/edited mid caches, re-injects archived (ghost) messages on
 * chat open and pagination, and diverts live deletes so ghost cells survive
 * in the thread. Extracted from ChatActivity; the host exposes the few
 * private structures the inject path must mutate via mg* accessors.
 */
public final class MgChatGhostController {

    private final ChatActivity host;

    // Mercurygram: per-chat caches for kept-deleted / edited message IDs.
    // Null until primed off the UI thread on first messagesDidLoad; consulted by
    // the long-press menu and the ghost-marking pass to avoid per-row SQLite.
    private Set<Integer> deletedMids;
    private Set<Integer> editedMids;
    // Full TLRPC.Message blobs of deleted messages, newest-first, loaded once
    // when the chat opens. Source of truth for re-injecting ghost cells on cold
    // restart and on pagination.
    private List<MgMessageHistory.Entry> ghostEntries;
    private boolean priming;
    private ArrayList<MessageObject> divertedGhosts;

    public MgChatGhostController(ChatActivity host) {
        this.host = host;
    }

    public void prime() {
        if (!host.getUserConfig().mg.savedMessagesHistory || priming
                || deletedMids != null
                || DialogObject.isEncryptedDialog(host.getDialogId())) {
            return;
        }
        priming = true;
        final int acc = host.getCurrentAccount();
        final long did = host.getDialogId();
        Utilities.globalQueue.postRunnable(() -> {
            List<MgMessageHistory.Entry> entries =
                    MgMessageHistory.getInstance().getDeletedEntries(acc, did);
            // Derive the dmids set from `entries` — both come from `deleted_messages`; a
            // separate `getMidsForDialog(false)` would scan the same table under the same
            // writeLock for no extra info.
            HashSet<Integer> dmids = new HashSet<>(entries.size());
            for (int i = 0, n = entries.size(); i < n; i++) {
                MgMessageHistory.Entry en = entries.get(i);
                if (en != null) {
                    dmids.add(en.mid);
                }
            }
            Set<Integer> emids = MgMessageHistory.getInstance()
                    .getMidsForDialog(acc, did, true);
            AndroidUtilities.runOnUIThread(() -> {
                deletedMids = dmids;
                editedMids = emids;
                ghostEntries = entries;
                priming = false;
                if (!host.mgHasAdapter()) {
                    return;
                }
                boolean any = false;
                if (!dmids.isEmpty() && !host.messages.isEmpty()) {
                    for (int i = 0; i < host.messages.size(); i++) {
                        MessageObject mo = host.messages.get(i);
                        if (mo != null && !mo.mgDeletedGhost && dmids.contains(mo.getId())) {
                            mo.mgDeletedGhost = true;
                            any = true;
                        }
                    }
                }
                // mgInjectGhosts already calls notifyDataSetChanged when it inserts anything;
                // only fire here if the in-memory flag-flip above is the only mutation.
                int injected = inject();
                if (any && injected == 0) {
                    host.mgNotifyAdapter();
                }
            });
        });
    }

    public void applyFlags(ArrayList<MessageObject> messArr) {
        if (!host.getUserConfig().mg.savedMessagesHistory || deletedMids == null
                || deletedMids.isEmpty() || messArr == null || messArr.isEmpty()) {
            return;
        }
        for (int i = 0; i < messArr.size(); i++) {
            MessageObject mo = messArr.get(i);
            if (mo != null && deletedMids.contains(mo.getId())) {
                mo.mgDeletedGhost = true;
            }
        }
    }

    // Mercurygram: cap a single inject batch. 200 layout-generating MessageObject
    // allocations on the UI thread is the worst case before user-visible jank.
    private static final int MG_GHOST_INJECT_CAP_PER_CALL = 200;

    /**
     * Rebuild ghost cells from the archive DB on chat open / pagination, reading
     * the cached entry list primed by {@link #prime()}. Idempotent
     * (dedups against {@code host.mgMessagesDict0()}), bounded to {@link #MG_GHOST_INJECT_CAP_PER_CALL}
     * per call, only acts inside the current date window so it does not race
     * pagination state ({@code minDate/maxDate/endReached/forwardEndReached} are
     * deliberately not mutated — ghosts are a UI overlay, not real history).
     *
     * @return number of ghosts inserted this call (0 means nothing to do).
     */
    public int inject() {
        final List<MgMessageHistory.Entry> entries = ghostEntries;
        if (!host.getUserConfig().mg.savedMessagesHistory || host.getChatMode() != ChatActivity.MODE_DEFAULT
                || DialogObject.isEncryptedDialog(host.getDialogId())
                || host.isThreadChat() || host.isTopic
                || !host.mgAdapterUsable()
                || entries == null || entries.isEmpty()) {
            return 0;
        }
        final int curMinDate = host.mgGhostWindowMin();
        final int curMaxDate = host.mgGhostWindowMax();

        LongSparseArray<MessageObject.GroupedMessages> touchedGroups = null;
        int injected = 0;

        for (int e = 0; e < entries.size() && injected < MG_GHOST_INJECT_CAP_PER_CALL; e++) {
            MgMessageHistory.Entry entry = entries.get(e);
            TLRPC.Message m = entry == null ? null : entry.message;
            if (m == null || MgMessageHistory.isExcluded(host.getDialogId(), m)) {
                continue;
            }
            if (host.mgMessagesDict0().indexOfKey(m.id) >= 0) {
                continue;
            }
            if (m.date < curMinDate || m.date > curMaxDate) {
                continue;
            }

            MessageObject mo = new MessageObject(
                    host.getCurrentAccount(), m,
                    host.getMessagesController().getUsers(),
                    host.getMessagesController().getChats(),
                    true, true);
            mo.mgDeletedGhost = true;
            mo.stableId = ChatActivity.lastStableId++;

            int placeToPaste = host.messages.size();
            for (int b = 0, sz = host.messages.size(); b < sz; b++) {
                MessageObject lm = host.messages.get(b);
                if (lm == null || lm.type < 0 || lm.messageOwner == null || lm.messageOwner.date <= 0) {
                    continue;
                }
                if ((lm.messageOwner.id > 0 && m.id > 0 && lm.messageOwner.id < m.id)
                        || lm.messageOwner.date < m.date) {
                    placeToPaste = b;
                    break;
                }
            }

            host.messages.add(placeToPaste, mo);
            host.mgMessagesDict0().put(m.id, mo);

            ArrayList<MessageObject> dayArray = host.mgMessagesByDays().get(mo.dateKey);
            if (dayArray == null) {
                dayArray = new ArrayList<>();
                host.mgMessagesByDays().put(mo.dateKey, dayArray);
                host.mgMessagesByDaysSorted().put(mo.dateKeyInt, dayArray);
                TLRPC.Message dateMsg = new TLRPC.TL_message();
                dateMsg.message = LocaleController.formatDateChat(m.date);
                dateMsg.id = 0;
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(((long) m.date) * 1000);
                cal.set(Calendar.HOUR_OF_DAY, 0);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                dateMsg.date = (int) (cal.getTimeInMillis() / 1000);
                MessageObject dateObj = new MessageObject(host.getCurrentAccount(), dateMsg, false, false);
                dateObj.type = MessageObject.TYPE_DATE;
                dateObj.contentType = 1;
                dateObj.isDateObject = true;
                dateObj.stableId = host.mgStableIdForDate(mo.dateKeyInt);
                // mo was just inserted at placeToPaste; the date header sits one slot
                // older (higher index in newest-first storage = above mo in the UI).
                host.messages.add(placeToPaste + 1, dateObj);
            }
            dayArray.add(mo);

            if (mo.hasValidGroupId()) {
                MessageObject.GroupedMessages g = host.mgGroupedMessagesMap().get(mo.getGroupIdForUse());
                if (g == null) {
                    g = new MessageObject.GroupedMessages();
                    g.reversed = host.reversed;
                    g.groupId = mo.getGroupId();
                    host.mgGroupedMessagesMap().put(g.groupId, g);
                }
                g.messages.add(mo);
                if (touchedGroups == null) {
                    touchedGroups = new LongSparseArray<>();
                }
                touchedGroups.put(g.groupId, g);
            }

            injected++;
        }

        if (touchedGroups != null) {
            for (int i = 0, n = touchedGroups.size(); i < n; i++) {
                MessageObject.GroupedMessages g = touchedGroups.valueAt(i);
                // calculate() hands out tile geometry by list index, so the appended ghost
                // has to go back where its id belongs. Always oldest first, the order
                // messagesDidLoad builds groups in: calculate() walks the list backwards on
                // its own when the group is reversed, so this must not depend on `reversed`.
                Collections.sort(g.messages, (a, b) -> Integer.compare(a.getId(), b.getId()));
                g.calculate();
            }
        }
        if (injected > 0) {
            host.mgNotifyAdapter();
        }
        return injected;
    }

    /**
     * Live-delete diversion: flags messages that must survive as ghosts,
     * records their ids in the primed cache, and returns the delete list
     * with those ids filtered out. Call updateDivertedRows() after the
     * host has processed the remaining deletes. Only deletes the server
     * reported (the ones the archive is persisting) become ghosts; a delete
     * the user made is a real delete. {@code channelId} is the notification's
     * second argument, the negated key the archive was marked under.
     */
    public ArrayList<Integer> divertDeletes(ArrayList<Integer> markAsDeletedMessages, ArrayList<MessageObject> resolved, long channelId, boolean movedToScheduled) {
        divertedGhosts = null;
        ArrayList<MessageObject> mgGhosts = null;
        ArrayList<Integer> mgDeleteList = markAsDeletedMessages;
        if (host.getUserConfig().mg.savedMessagesHistory && host.getChatMode() == ChatActivity.MODE_DEFAULT && !movedToScheduled) {
            Set<Integer> remote = MgMessageHistory.getInstance().takeRemote(-channelId);
            for (int i = 0; i < resolved.size(); i++) {
                MessageObject mo = resolved.get(i);
                if (!mo.scheduled && remote.contains(mo.getId()) && !MgMessageHistory.isExcluded(host.getDialogId(), mo.messageOwner)) {
                    mo.mgDeletedGhost = true;
                    if (mgGhosts == null) {
                        mgGhosts = new ArrayList<>();
                    }
                    mgGhosts.add(mo);
                }
            }
            if (mgGhosts != null) {
                HashSet<Integer> ghostIds = new HashSet<>(mgGhosts.size());
                for (int i = 0; i < mgGhosts.size(); i++) {
                    ghostIds.add(mgGhosts.get(i).getId());
                }
                if (deletedMids != null) {
                    deletedMids.addAll(ghostIds);
                }
                mgDeleteList = new ArrayList<>(markAsDeletedMessages.size());
                for (int i = 0; i < markAsDeletedMessages.size(); i++) {
                    int mid = markAsDeletedMessages.get(i);
                    if (!ghostIds.contains(mid)) {
                        mgDeleteList.add(mid);
                    }
                }
            }
        }
        divertedGhosts = mgGhosts;
        return mgDeleteList;
    }

    /**
     * Delete pressed on ghosts: drop them from the archive and the chat without
     * the upstream confirmation (a ghost is a local copy of an already deleted
     * message, there is nothing to delete on the server). Real messages in the
     * same selection are left for the upstream alert.
     *
     * @return true when nothing but ghosts was selected, so the caller is done.
     */
    public boolean deleteGhosts(MessageObject single, MessageObject.GroupedMessages group, SparseArray<MessageObject>[] selected) {
        ArrayList<MessageObject> candidates = new ArrayList<>();
        if (single != null) {
            if (group != null) {
                candidates.addAll(group.messages);
            } else {
                candidates.add(single);
            }
        } else {
            for (int i = 0; i < selected[0].size(); i++) {
                candidates.add(selected[0].valueAt(i));
            }
        }
        ArrayList<Integer> ghostIds = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            MessageObject mo = candidates.get(i);
            if (mo != null && mo.mgDeletedGhost) {
                ghostIds.add(mo.getId());
            }
        }
        if (ghostIds.isEmpty()) {
            return false;
        }
        if (deletedMids != null) {
            deletedMids.removeAll(ghostIds);
        }
        if (ghostEntries != null) {
            ghostEntries.removeIf(en -> ghostIds.contains(en.mid));
        }
        MgMessageHistory.getInstance().forgetDeleted(host.getCurrentAccount(), host.getDialogId(), ghostIds);
        host.mgProcessDeletedMessages(ghostIds);
        return ghostIds.size() == candidates.size() && (single != null || selected[1].size() == 0);
    }

    public void updateDivertedRows() {
        if (divertedGhosts == null) {
            return;
        }
        for (int i = 0; i < divertedGhosts.size(); i++) {
            host.mgUpdateRow(divertedGhosts.get(i));
        }
        divertedGhosts = null;
    }

    /** replaceMessagesObjects hook: record freshly edited mids that the
     * archive keeps a pre-edit version of. */
    public void onReplaceMessages(long did, ArrayList<MessageObject> messageObjects) {
        if (did == host.getDialogId() && host.getUserConfig().mg.savedMessagesHistory
                && editedMids != null && !DialogObject.isEncryptedDialog(host.getDialogId())) {
            for (int i = 0; i < messageObjects.size(); i++) {
                MessageObject mo = messageObjects.get(i);
                if (mo != null && mo.messageOwner != null && mo.messageOwner.edit_date != 0
                        && !MgMessageHistory.isExcluded(host.getDialogId(), mo.messageOwner)) {
                    editedMids.add(mo.getId());
                }
            }
        }
    }

    /** Long-press menu gate for the "Edit history" item. */
    public boolean isEditHistoryCandidate(MessageObject selectedObject) {
        return host.getUserConfig().mg.savedMessagesHistory
                && selectedObject != null
                && selectedObject.messageOwner != null
                && selectedObject.messageOwner.edit_date != 0
                && editedMids != null
                && editedMids.contains(selectedObject.getId());
    }
}
