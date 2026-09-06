package it.belloworld.mercurygram.folders;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
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
 * Keeps Mercurygram folders in step across devices through one JSON document in
 * Saved Messages, edited in place. Wire format in MercurygramFolders.md at the
 * repo root; that spec is what mdesktop and bots read, so change both together.
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
 * change or launch; the relative order of the already-present folders is
 * neither re-applied from the blob nor pushed on its own, only new folders land
 * (at the end), which is why "the cloud already holds this" is decided on an
 * order-insensitive key. A device that has never synced keeps the Mercurygram
 * folders it already had and pushes the union, so the feature landing does not
 * wipe folders made before it.
 */
public class MgFolderSync implements NotificationCenter.NotificationCenterDelegate {

    public static final String FILE_NAME = "mercurygram-folders.json";
    public static final String CAPTION_TAG = "#mercurygram_folders";
    private static final int FORMAT_VERSION = 1;
    private static final int PUSH_DELAY_MS = 3000;
    /** Anything this big is not a folder blob; do not download or read it. */
    private static final long MAX_BLOB_BYTES = 4L * 1024 * 1024;
    /** A folder icon is one emoji, and a folder title holds a handful of custom ones. */
    private static final int MAX_EMOTICON_LENGTH = 32;
    private static final int MAX_TITLE_ENTITIES = 32;

    private static final MgFolderSync[] instances = new MgFolderSync[UserConfig.MAX_ACCOUNT_COUNT];

    /** Registers observers for every slot; work is gated on the account being logged in. */
    public static void startAll() {
        AndroidUtilities.runOnUIThread(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (instances[a] == null) {
                    instances[a] = new MgFolderSync(a);
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

    private MgFolderSync(int account) {
        this.account = account;
        NotificationCenter center = NotificationCenter.getInstance(account);
        center.addObserver(this, NotificationCenter.dialogFiltersUpdated);
        center.addObserver(this, NotificationCenter.didReceiveNewMessages);
        center.addObserver(this, NotificationCenter.replaceMessagesObjects);
        center.addObserver(this, NotificationCenter.messageReceivedByServer);
        center.addObserver(this, NotificationCenter.messageSendError);
        center.addObserver(this, NotificationCenter.messagesDeleted);
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
        } else if (id == NotificationCenter.messagesDeleted) {
            if (message != null && (Long) args[1] == 0L && !(Boolean) args[2]
                    && ((List<Integer>) args[0]).contains(message.id)) {
                // Posted for a local-only clear too, so the search decides:
                // it adopts the document again if the server still has it.
                message = null;
                started = false;
                find();
            }
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
        if (searching) {
            return;
        }
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
                // Nothing in the cloud: the push guard must not believe it
                // already holds this set, or nothing would ever be sent.
                lastPushed = null;
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
        /** Folder id to icon: this client has no icon of its own, see emoticons(). */
        final JSONObject emoticons = new JSONObject();
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
                    final JSONObject object = folders.getJSONObject(i);
                    final DialogFilter folder = fromJson(object);
                    final String emoticon = object.optString("emoticon", "");
                    if (!emoticon.isEmpty() && emoticon.length() <= MAX_EMOTICON_LENGTH) {
                        blob.emoticons.put(String.valueOf(folder.id), emoticon);
                    }
                    blob.folders.add(folder);
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
            for (DialogFilter old : MgFolders.mercurygram(mc.getDialogFilters())) {
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
                MgFolders.ensureDefaultFilter(ai());
            }
            for (DialogFilter f : cloud) {
                final DialogFilter existing = mc.dialogFiltersById.get(f.id);
                if (existing == null) {
                    mc.addFilter(f, false);
                    ai().getMessagesStorage().saveDialogFilter(f, false, true);
                } else {
                    existing.name = f.name;
                    existing.entities = f.entities; // whole-title replacement: stale ones point into the old title
                    existing.title_noanimate = f.title_noanimate;
                    existing.flags = f.flags;
                    existing.color = f.color;
                    // The blob cannot name a secret chat (no Bot API id), so the
                    // ones this device holds are carried over, or a pull from
                    // another device would drop them out of the folder here.
                    carryEncrypted(existing.alwaysShow, f.alwaysShow);
                    carryEncrypted(existing.neverShow, f.neverShow);
                    for (int i = 0; i < existing.pinnedDialogs.size(); i++) {
                        final long did = existing.pinnedDialogs.keyAt(i);
                        if (DialogObject.isEncryptedDialog(did)) {
                            f.pinnedDialogs.put(did, f.pinnedDialogs.size());
                        }
                    }
                    existing.alwaysShow = f.alwaysShow;
                    existing.neverShow = f.neverShow;
                    existing.pinnedDialogs = f.pinnedDialogs;
                    existing.pendingUnreadCount = -1;
                    mc.onFilterUpdate(existing);
                    ai().getMessagesStorage().saveDialogFilter(existing, false, true);
                }
            }
            userConfig.mg.folderSyncUpdated = updated;
            userConfig.mg.folderEmoticons = blob.emoticons.toString();
            userConfig.saveConfig(false);
            lastPushed = syncKey(cloud, blob.emoticons); // a first sync kept extra folders, so this pushes the union
            mc.sortDialogs(null);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogFiltersUpdated);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload, true);
        } else if (updated == userConfig.mg.folderSyncUpdated) {
            lastPushed = syncKey(cloud, blob.emoticons);
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
        final ArrayList<DialogFilter> folders = MgFolders.mercurygram(ai().getMessagesController().getDialogFilters());
        if (folders.isEmpty() && userConfig.mg.folderSyncUpdated == 0) {
            return; // a fresh device with nothing yet never clears the cloud copy
        }
        // Serialized here rather than off the UI thread: a DialogFilter and its
        // chat lists are UI-thread state.
        final JSONObject emoticons = emoticons(userConfig, folders);
        final String canonical = syncKey(folders, emoticons);
        if (canonical.equals(lastPushed)) {
            return;
        }
        final int updated = Math.max(ai().getConnectionsManager().getCurrentTime(), userConfig.mg.folderSyncUpdated + 1);
        final String json = toJson(folders, updated, emoticons).toString();
        // External cache dir: FileUploadOperation refuses files under /data/data.
        // One directory per push: the file name is the document name the other
        // devices match on, so it cannot vary, and a follow-up push would
        // otherwise rewrite the file an upload is still reading.
        final File dir = new File(new File(AndroidUtilities.getCacheDir(), "mg_folders_" + account), String.valueOf(updated));
        final File file = new File(dir, FILE_NAME);
        final String caption = caption(folders);
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
        userConfig.mg.folderEmoticons = emoticons.toString(); // dropped with the folder
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

    private static void carryEncrypted(List<Long> from, List<Long> to) {
        for (Long did : from) {
            if (DialogObject.isEncryptedDialog(did) && !to.contains(did)) {
                to.add(did);
            }
        }
    }

    /** Secret chats live on this device alone and have no Bot API id: never in the blob. */
    private static ArrayList<Long> syncable(List<Long> dids) {
        final ArrayList<Long> out = new ArrayList<>(dids.size());
        for (Long did : dids) {
            if (!DialogObject.isEncryptedDialog(did)) {
                out.add(did);
            }
        }
        return out;
    }

    private static String caption(List<DialogFilter> folders) {
        final StringBuilder sb = new StringBuilder(CAPTION_TAG);
        for (DialogFilter f : folders) {
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
    private static String syncKey(List<DialogFilter> folders, JSONObject emoticons) {
        return root(foldersJson(sortedById(folders), emoticons), 0).toString();
    }

    /**
     * Folder icons, by folder id. This client derives a folder's icon from its
     * flags and name and has nowhere to keep one, but mdesktop lets the user pick
     * it, so the ones read from the cloud are kept here and written back
     * unchanged: without that, the first folder change made here would strip
     * every icon off the document. Ids that no longer name a folder are dropped.
     */
    private static JSONObject emoticons(UserConfig userConfig, List<DialogFilter> folders) {
        final JSONObject result = new JSONObject();
        try {
            final JSONObject saved = new JSONObject(userConfig.mg.folderEmoticons);
            for (DialogFilter f : folders) {
                final String emoticon = saved.optString(String.valueOf(f.id), "");
                if (!emoticon.isEmpty()) {
                    result.put(String.valueOf(f.id), emoticon);
                }
            }
        } catch (Exception ignored) {
            // No icons yet, or a pref from a build that did not write them.
        }
        return result;
    }

    /**
     * Sorted by id, so the "does the cloud already hold this" key does not depend
     * on the order the folders sit in: a pull leaves the relative order of
     * already-present folders alone, and comparing the serialized order instead
     * would have two devices that disagree on it push at each other forever.
     */
    private static ArrayList<DialogFilter> sortedById(List<DialogFilter> folders) {
        final ArrayList<DialogFilter> sorted = new ArrayList<>(folders);
        Collections.sort(sorted, (a, b) -> Integer.compare(b.id, a.id));
        return sorted;
    }

    static JSONObject toJson(List<DialogFilter> folders, int updated, JSONObject emoticons) {
        return root(foldersJson(folders, emoticons), updated);
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

    private static JSONArray foldersJson(List<DialogFilter> filters, JSONObject emoticons) {
        try {
            final JSONArray folders = new JSONArray();
            for (DialogFilter f : filters) {
                final JSONObject o = new JSONObject();
                o.put("id", f.id);
                o.put("title", f.name == null ? "" : f.name);
                final JSONArray entities = titleEntitiesJson(f);
                if (entities.length() > 0) {
                    o.put("title_entities", entities);
                }
                if (f.title_noanimate) {
                    o.put("title_noanimate", true);
                }
                final String emoticon = emoticons.optString(String.valueOf(f.id), "");
                if (!emoticon.isEmpty()) {
                    o.put("emoticon", emoticon);
                }
                o.put("color", f.color);
                final JSONObject flags = new JSONObject();
                for (int i = 0; i < FLAG_NAMES.length; i++) {
                    flags.put(FLAG_NAMES[i], (f.flags & FLAG_BITS[i]) != 0);
                }
                o.put("flags", flags);
                o.put("include", new JSONArray(syncable(f.alwaysShow)));
                o.put("exclude", new JSONArray(syncable(f.neverShow)));
                final ArrayList<Long> pinned = new ArrayList<>();
                for (int i = 0; i < f.pinnedDialogs.size(); i++) {
                    if (!DialogObject.isEncryptedDialog(f.pinnedDialogs.keyAt(i))) {
                        pinned.add(f.pinnedDialogs.keyAt(i));
                    }
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

    /**
     * The custom emojis in the title, the only entity kind a folder title holds.
     * Their document ids mean the same emoji on every device; the offsets of any
     * other kind would point into a title no reader can rebuild.
     */
    private static JSONArray titleEntitiesJson(DialogFilter f) throws Exception {
        final JSONArray result = new JSONArray();
        for (TLRPC.MessageEntity entity : f.entities) {
            if (!(entity instanceof TLRPC.TL_messageEntityCustomEmoji)) {
                continue;
            }
            final JSONObject o = new JSONObject();
            o.put("offset", entity.offset);
            o.put("length", entity.length);
            // A decimal string, not a number: a document id uses the full 64
            // bits, and a JSON number is a double in some readers, which would
            // round the ones past 2^53 into a different emoji.
            o.put("document_id", String.valueOf(((TLRPC.TL_messageEntityCustomEmoji) entity).document_id));
            result.put(o);
        }
        return result;
    }

    /**
     * An entity that does not fit the title is left out rather than the document
     * refused: the title it points into is the one the writer had, and a folder
     * list with one bad entity is still a folder list. Offsets are UTF-16 units,
     * as in MTProto and in a Java String.
     */
    private static void readTitleEntities(DialogFilter f, JSONArray entities) {
        for (int i = 0; entities != null && i < entities.length() && f.entities.size() < MAX_TITLE_ENTITIES; i++) {
            final JSONObject o = entities.optJSONObject(i);
            if (o == null) {
                continue;
            }
            final int offset = o.optInt("offset", -1);
            final int length = o.optInt("length");
            long documentId = 0;
            try {
                // Long.parseLong, not optLong: this one reads a string through
                // a double and would round away the low bits of a document id.
                documentId = Long.parseLong(o.optString("document_id", "0"));
            } catch (NumberFormatException ignored) {
            }
            if (offset < 0 || length <= 0 || offset + length > f.name.length() || documentId == 0) {
                continue;
            }
            final TLRPC.TL_messageEntityCustomEmoji entity = new TLRPC.TL_messageEntityCustomEmoji();
            entity.offset = offset;
            entity.length = length;
            entity.document_id = documentId;
            f.entities.add(entity);
        }
    }

    static DialogFilter fromJson(JSONObject o) throws Exception {
        final DialogFilter f = new DialogFilter();
        f.id = o.getInt("id");
        if (f.id >= -1) {
            // Server range, or the reserved -1 (see MercurygramFolders.md).
            throw new IllegalArgumentException("Mercurygram folder id must be <= -2: " + f.id);
        }
        f.name = o.optString("title", "");
        f.color = o.optInt("color", -1);
        f.title_noanimate = o.optBoolean("title_noanimate");
        f.entities = new ArrayList<>();
        readTitleEntities(f, o.optJSONArray("title_entities"));
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
