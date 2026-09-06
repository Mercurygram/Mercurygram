package it.belloworld.mercurygram.folders;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesController.DialogFilter;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Keeps local folders in step across devices through one JSON document in
 * Saved Messages, edited in place. Wire format in LocalFolders.md at the repo
 * root; that spec is what mdesktop and bots read, so change both together.
 *
 * Flow: the first dialogFiltersUpdated after launch searches Saved Messages for
 * the document and pulls it (a fresh install adopts the cloud copy before it
 * could push an empty one); every later dialogFiltersUpdated schedules a push,
 * which is skipped when the serialized set already matches what the cloud
 * holds. Incoming messages / edits from other devices in Saved Messages are
 * matched by file name and pulled. Last writer wins by the "updated" field.
 *
 * Known limits, by design: no merge of a folder's contents (last writer wins as
 * a whole); a push that fails on the wire is retried only on the next folder
 * change or launch; the relative order of already-present local folders is
 * neither re-applied from the blob nor pushed on its own, only new folders land
 * (at the end), which is why "the cloud already holds this" is decided on an
 * order-insensitive key; custom-emoji title entities are not synced. A device
 * that has never synced keeps the local folders it already had and pushes the
 * union, so the feature landing does not wipe folders made before it.
 */
public class MgLocalFolderSync implements NotificationCenter.NotificationCenterDelegate {

    public static final String FILE_NAME = "mercurygram-folders.json";
    public static final String CAPTION_TAG = "#mercurygram_folders";
    private static final int FORMAT_VERSION = 1;
    private static final int PUSH_DELAY_MS = 3000;
    /** Anything this big is not a folder blob; do not download or read it. */
    private static final long MAX_BLOB_BYTES = 4L * 1024 * 1024;

    private static final MgLocalFolderSync[] instances = new MgLocalFolderSync[UserConfig.MAX_ACCOUNT_COUNT];

    /** Registers observers for every slot; work is gated on the account being logged in. */
    public static void startAll() {
        AndroidUtilities.runOnUIThread(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (instances[a] == null) {
                    instances[a] = new MgLocalFolderSync(a);
                }
            }
        });
    }

    private final int account;
    private boolean started;          // cloud checked once since launch / login
    private boolean searching;        // the discovery search is in flight
    private boolean pushing;          // an upload or edit is in flight
    private TLRPC.Message message;    // the cloud document message, once known
    private String lastPushed;        // canonical JSON the cloud is believed to hold
    private String pushedBefore;      // lastPushed as it was before the in-flight push
    private int updatedBefore;        // folderSyncUpdated as it was before the in-flight push
    private String pendingAttachName; // the blob download in flight, if any
    private final Runnable pushRunnable = this::push;
    private final Runnable pushTimeout = () -> pushing = false;

    private MgLocalFolderSync(int account) {
        this.account = account;
        NotificationCenter center = NotificationCenter.getInstance(account);
        center.addObserver(this, NotificationCenter.dialogFiltersUpdated);
        center.addObserver(this, NotificationCenter.didReceiveNewMessages);
        center.addObserver(this, NotificationCenter.replaceMessagesObjects);
        center.addObserver(this, NotificationCenter.messageReceivedByServer);
        center.addObserver(this, NotificationCenter.messageSendError);
        center.addObserver(this, NotificationCenter.fileLoaded);
        center.addObserver(this, NotificationCenter.fileLoadFailed);
        center.addObserver(this, NotificationCenter.appDidLogout);
        if (ai().getUserConfig().isClientActivated() && ai().getMessagesController().dialogFiltersLoaded) {
            find();
        }
    }

    private AccountInstance ai() {
        return AccountInstance.getInstance(account);
    }

    private long selfId() {
        return ai().getUserConfig().getClientUserId();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void didReceivedNotification(int id, int acc, Object... args) {
        if (id == NotificationCenter.appDidLogout) {
            started = searching = pushing = false;
            message = null;
            lastPushed = null;
            pendingAttachName = null;
            AndroidUtilities.cancelRunOnUIThread(pushRunnable);
            AndroidUtilities.cancelRunOnUIThread(pushTimeout);
            return;
        }
        if (!ai().getUserConfig().isClientActivated()) {
            return;
        }
        if (id == NotificationCenter.dialogFiltersUpdated) {
            if (!started) {
                find();
            } else {
                schedulePush();
            }
        } else if (id == NotificationCenter.didReceiveNewMessages || id == NotificationCenter.replaceMessagesObjects) {
            if ((long) args[0] != selfId()) {
                return;
            }
            for (MessageObject obj : (List<MessageObject>) args[1]) {
                if (isCloudDocument(obj.messageOwner) && !obj.isSending() && !obj.isEditing() && !obj.isSendError()) {
                    onCloudMessage(obj.messageOwner);
                }
            }
        } else if (id == NotificationCenter.messageReceivedByServer) {
            TLRPC.Message msg = (TLRPC.Message) args[2];
            if ((long) args[3] == selfId() && isCloudDocument(msg)) {
                onCloudMessage(msg);
            }
        } else if (id == NotificationCenter.messageSendError) {
            if (pushing && (message == null || (Integer) args[0] == message.id)) {
                rollback();
                // Forget the message id - an edit fails exactly like this when the
                // document was deleted from Saved Messages - so the next
                // dialogFiltersUpdated searches again and sends a fresh document.
                message = null;
                started = false;
            }
            pushing = false;
        } else if (id == NotificationCenter.fileLoaded || id == NotificationCenter.fileLoadFailed) {
            if (args.length > 0 && args[0].equals(pendingAttachName)) {
                pendingAttachName = null;
                if (id == NotificationCenter.fileLoaded) {
                    pull();
                }
            }
        }
    }

    private static boolean isCloudDocument(TLRPC.Message msg) {
        return msg != null && FILE_NAME.equals(FileLoader.getDocumentFileName(MessageObject.getDocument(msg)));
    }

    /** Adopts the newest known cloud message (own or from another device) and reads it. */
    private void onCloudMessage(TLRPC.Message msg) {
        pushing = false;
        AndroidUtilities.cancelRunOnUIThread(pushTimeout);
        if (message == null || msg.id >= message.id) {
            message = msg;
            pull();
        }
    }

    private void find() {
        started = searching = true;
        TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
        req.peer = new TLRPC.TL_inputPeerSelf();
        req.q = CAPTION_TAG;
        req.filter = new TLRPC.TL_inputMessagesFilterDocument();
        req.limit = 5;
        ai().getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            searching = false;
            TLRPC.Message found = null;
            if (response instanceof TLRPC.messages_Messages) {
                for (TLRPC.Message m : ((TLRPC.messages_Messages) response).messages) {
                    if (isCloudDocument(m) && (found == null || m.id > found.id)) {
                        found = m;
                    }
                }
            }
            if (found != null) {
                onCloudMessage(found);
            } else if (error == null) {
                schedulePush();
            } else {
                started = false; // retry on the next dialogFiltersUpdated
            }
        }));
    }

    private void pull() {
        final TLRPC.Message msg = message;
        final TLRPC.Document document = MessageObject.getDocument(msg);
        if (document == null || document.size > MAX_BLOB_BYTES) {
            return; // anyone can drop a file with that name into Saved Messages
        }
        final File file = ai().getFileLoader().getPathToAttach(document, true);
        if (!file.exists()) {
            final String attachName = FileLoader.getAttachFileName(document);
            if (attachName.equals(pendingAttachName)) {
                return; // already downloading this one
            }
            pendingAttachName = attachName;
            ai().getFileLoader().loadFile(document, msg, FileLoader.PRIORITY_NORMAL_UP, 1);
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            // readRes, not Files.readAllBytes: java.nio.file is API 26+ and this
            // build's desugaring does not cover it (minSdk 24).
            final String json = AndroidUtilities.readRes(file);
            final Blob blob = json == null ? null : Blob.parse(json);
            if (blob != null) {
                AndroidUtilities.runOnUIThread(() -> apply(blob));
            }
        });
    }

    /** The parsed cloud document: parsing runs off the UI thread, applying on it. */
    private static final class Blob {
        final ArrayList<DialogFilter> folders = new ArrayList<>();
        int updated;

        static Blob parse(String json) {
            final Blob blob = new Blob();
            try {
                final JSONObject root = new JSONObject(json);
                if (root.optInt("version") != FORMAT_VERSION) {
                    return null;
                }
                blob.updated = root.optInt("updated");
                final JSONArray folders = root.getJSONArray("folders");
                for (int i = 0; i < folders.length(); i++) {
                    blob.folders.add(fromJson(folders.getJSONObject(i)));
                }
            } catch (Exception e) {
                FileLog.e(e);
                return null;
            }
            return blob;
        }
    }

    private void apply(Blob blob) {
        final ArrayList<DialogFilter> cloud = blob.folders;
        final int updated = blob.updated;
        final UserConfig userConfig = ai().getUserConfig();
        if (updated > userConfig.mg.folderSyncUpdated) {
            final MessagesController mc = ai().getMessagesController();
            // A device that never synced holds folders the cloud copy cannot know
            // about (they predate the feature): keep them and push the union below,
            // instead of reading their absence from the blob as a deletion.
            final boolean firstSync = userConfig.mg.folderSyncUpdated == 0;
            for (DialogFilter old : MgLocalFolders.local(mc.getDialogFilters())) {
                boolean keep = firstSync;
                for (DialogFilter f : cloud) {
                    keep |= f.id == old.id;
                }
                if (!keep) {
                    mc.removeFilter(old);
                    ai().getMessagesStorage().deleteDialogFilter(old);
                }
            }
            if (!cloud.isEmpty()) {
                MgLocalFolders.ensureDefaultFilter(ai());
            }
            for (DialogFilter f : cloud) {
                final DialogFilter existing = mc.dialogFiltersById.get(f.id);
                if (existing == null) {
                    mc.addFilter(f, false);
                    ai().getMessagesStorage().saveDialogFilter(f, false, true);
                } else {
                    existing.name = f.name;
                    existing.entities = f.entities; // the blob carries no title entities; stale ones point into the old title
                    existing.flags = f.flags;
                    existing.color = f.color;
                    existing.alwaysShow = f.alwaysShow;
                    existing.neverShow = f.neverShow;
                    existing.pinnedDialogs = f.pinnedDialogs;
                    existing.pendingUnreadCount = -1;
                    mc.onFilterUpdate(existing);
                    ai().getMessagesStorage().saveDialogFilter(existing, false, true);
                }
            }
            userConfig.mg.folderSyncUpdated = updated;
            userConfig.saveConfig(false);
            lastPushed = syncKey(cloud); // a first sync kept extra folders, so this pushes the union
            mc.sortDialogs(null);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogFiltersUpdated);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload, true);
        } else if (updated == userConfig.mg.folderSyncUpdated) {
            lastPushed = syncKey(cloud);
        }
        schedulePush();
    }

    private void schedulePush() {
        AndroidUtilities.cancelRunOnUIThread(pushRunnable);
        AndroidUtilities.runOnUIThread(pushRunnable, PUSH_DELAY_MS);
    }

    private void push() {
        if (!started || !ai().getUserConfig().isClientActivated()) {
            return;
        }
        if (searching) {
            // Pushing while the discovery search is in flight would send a second
            // document instead of editing the one the search is about to return;
            // the search schedules a push itself when it lands.
            return;
        }
        if (pushing) {
            schedulePush();
            return;
        }
        final UserConfig userConfig = ai().getUserConfig();
        final ArrayList<DialogFilter> local = MgLocalFolders.local(ai().getMessagesController().getDialogFilters());
        if (local.isEmpty() && userConfig.mg.folderSyncUpdated == 0) {
            return; // a fresh device with nothing yet never clears the cloud copy
        }
        // Serialized here rather than off the UI thread: a DialogFilter and its
        // chat lists are UI-thread state.
        final String canonical = syncKey(local);
        if (canonical.equals(lastPushed)) {
            return;
        }
        final int updated = Math.max(ai().getConnectionsManager().getCurrentTime(), userConfig.mg.folderSyncUpdated + 1);
        final String json = toJson(local, updated).toString();
        // External cache dir: FileUploadOperation refuses files under /data/data.
        // One directory per push: the file name is the document name the other
        // devices match on, so it cannot vary, and a follow-up push would
        // otherwise rewrite the file an upload is still reading.
        final File dir = new File(new File(AndroidUtilities.getCacheDir(), "mg_folders_" + account), String.valueOf(updated));
        final File file = new File(dir, FILE_NAME);
        final String caption = caption(local);
        MessageObject editing = null;
        if (message != null) {
            editing = new MessageObject(account, message, false, false);
            editing.editingMessage = caption;
            editing.editingMessageEntities = new ArrayList<>();
        }
        final MessageObject editingFinal = editing;
        pushing = true;
        pushedBefore = lastPushed;
        updatedBefore = userConfig.mg.folderSyncUpdated;
        lastPushed = canonical;
        userConfig.mg.folderSyncUpdated = updated;
        userConfig.saveConfig(false);
        Utilities.globalQueue.postRunnable(() -> {
            try {
                dir.mkdirs();
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> {
                    rollback();
                    pushing = false;
                });
                return;
            }
            prune(dir);
            AndroidUtilities.runOnUIThread(() -> {
                AndroidUtilities.runOnUIThread(pushTimeout, 60_000);
                SendMessagesHelper.prepareSendingDocument(ai(), file.getAbsolutePath(), file.getAbsolutePath(), null, caption, "application/json", selfId(), null, null, null, null, editingFinal, false, 0, null, null, false);
            });
        });
    }

    /**
     * Gives back the state the in-flight push claimed. Keeping it would have the
     * session believe the cloud holds this set, and ignore a remote blob whose
     * "updated" is below the bumped value.
     */
    private void rollback() {
        lastPushed = pushedBefore;
        final UserConfig userConfig = ai().getUserConfig();
        userConfig.mg.folderSyncUpdated = updatedBefore;
        userConfig.saveConfig(false);
    }

    /** Drops every push directory but the one just written. */
    private static void prune(File keep) {
        final File[] dirs = keep.getParentFile().listFiles();
        if (dirs == null) {
            return;
        }
        for (File d : dirs) {
            if (!d.equals(keep)) {
                new File(d, FILE_NAME).delete();
                d.delete();
            }
        }
    }

    private static String caption(List<DialogFilter> local) {
        final StringBuilder sb = new StringBuilder(CAPTION_TAG);
        for (DialogFilter f : local) {
            final String line = "\n📁 " + f.name + " (" + f.alwaysShow.size() + " chats)";
            if (sb.length() + line.length() > 1024) {
                break;
            }
            sb.append(line);
        }
        return sb.toString();
    }

    private static final String[] FLAG_NAMES = {"contacts", "non_contacts", "groups", "broadcasts", "bots", "exclude_muted", "exclude_read", "exclude_archived"};
    private static final int[] FLAG_BITS = {
        MessagesController.DIALOG_FILTER_FLAG_CONTACTS, MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS,
        MessagesController.DIALOG_FILTER_FLAG_GROUPS, MessagesController.DIALOG_FILTER_FLAG_CHANNELS,
        MessagesController.DIALOG_FILTER_FLAG_BOTS, MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_MUTED,
        MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_READ, MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_ARCHIVED,
    };

    /** "Does the cloud already hold this set" key: the document minus {@code updated}. */
    private static String syncKey(List<DialogFilter> local) {
        return root(foldersJson(sortedById(local)), 0).toString();
    }

    /**
     * Sorted by id, so the "does the cloud already hold this" key does not depend
     * on the order the folders sit in: a pull leaves the relative order of
     * already-present folders alone, and comparing the serialized order instead
     * would have two devices that disagree on it push at each other forever.
     */
    private static ArrayList<DialogFilter> sortedById(List<DialogFilter> local) {
        final ArrayList<DialogFilter> sorted = new ArrayList<>(local);
        Collections.sort(sorted, (a, b) -> Integer.compare(b.id, a.id));
        return sorted;
    }

    static JSONObject toJson(List<DialogFilter> local, int updated) {
        return root(foldersJson(local), updated);
    }

    private static JSONObject root(JSONArray folders, int updated) {
        try {
            final JSONObject root = new JSONObject();
            root.put("format", "mercurygram-folders");
            root.put("version", FORMAT_VERSION);
            root.put("updated", updated);
            root.put("folders", folders);
            return root;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static JSONArray foldersJson(List<DialogFilter> local) {
        try {
            final JSONArray folders = new JSONArray();
            for (DialogFilter f : local) {
                final JSONObject o = new JSONObject();
                o.put("id", f.id);
                o.put("title", f.name == null ? "" : f.name);
                o.put("color", f.color);
                final JSONObject flags = new JSONObject();
                for (int i = 0; i < FLAG_NAMES.length; i++) {
                    flags.put(FLAG_NAMES[i], (f.flags & FLAG_BITS[i]) != 0);
                }
                o.put("flags", flags);
                o.put("include", new JSONArray(f.alwaysShow));
                o.put("exclude", new JSONArray(f.neverShow));
                final ArrayList<Long> pinned = new ArrayList<>();
                for (int i = 0; i < f.pinnedDialogs.size(); i++) {
                    pinned.add(f.pinnedDialogs.keyAt(i));
                }
                Collections.sort(pinned, (a, b) -> Integer.compare(f.pinnedDialogs.get(a), f.pinnedDialogs.get(b)));
                o.put("pinned", new JSONArray(pinned));
                folders.put(o);
            }
            return folders;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static DialogFilter fromJson(JSONObject o) throws Exception {
        final DialogFilter f = new DialogFilter();
        f.id = o.getInt("id");
        if (f.id >= 0) {
            throw new IllegalArgumentException("local folder id must be negative: " + f.id);
        }
        f.name = o.optString("title", "");
        f.color = o.optInt("color", -1);
        f.entities = new ArrayList<>();
        final JSONObject flags = o.optJSONObject("flags");
        for (int i = 0; flags != null && i < FLAG_NAMES.length; i++) {
            if (flags.optBoolean(FLAG_NAMES[i])) {
                f.flags |= FLAG_BITS[i];
            }
        }
        final JSONArray include = o.optJSONArray("include");
        for (int i = 0; include != null && i < include.length(); i++) {
            f.alwaysShow.add(include.getLong(i));
        }
        final JSONArray exclude = o.optJSONArray("exclude");
        for (int i = 0; exclude != null && i < exclude.length(); i++) {
            f.neverShow.add(exclude.getLong(i));
        }
        final JSONArray pinned = o.optJSONArray("pinned");
        for (int i = 0; pinned != null && i < pinned.length(); i++) {
            final long did = pinned.getLong(i);
            if (!f.alwaysShow.contains(did)) {
                f.alwaysShow.add(did);
            }
            f.pinnedDialogs.put(did, i);
        }
        f.pendingUnreadCount = f.unreadCount = -1;
        return f;
    }
}
