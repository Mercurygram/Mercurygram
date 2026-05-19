package it.belloworld.mercurygram;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.LongSparseArray;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Mercurygram "saved message history" — keeps a local copy of messages the
 * server reports deleted, plus pre-edit versions of edited messages, so that
 * e.g. a scammer cannot retract a message before it is seen.
 *
 * <p>Stored in a dedicated SQLite database ({@code mg_message_history.db}),
 * separate from Telegram's {@code cache4.db}, so clearing the Telegram cache
 * does not wipe saved entries.</p>
 *
 * <p>ToS guard (https://core.telegram.org/api/terms §1.4): self-destructing
 * content, secret chats and TTL messages are <b>never</b> archived. The
 * feature is opt-in (per-account {@code UserConfig.mg.savedMessagesHistory}, default off).</p>
 */
public class MgMessageHistory {

    public static final int KIND_DELETED = 0;
    public static final int KIND_EDITED = 1;

    private static final String DB_NAME = "mg_message_history.db";
    private static final int DB_VERSION = 1;
    private static final String TBL_DELETED = "deleted_messages";
    private static final String TBL_EDITED = "edited_messages";
    // Cap rows kept per (account, dialog) so an active deleter cannot grow the DB unbounded.
    private static final int MAX_PER_DIALOG = 2000;

    private static final Object writeLock = new Object();
    private static volatile MgMessageHistory instance;

    private final DbHelper dbHelper;

    public static class Entry {
        public final int kind;
        public final long dialogId;
        public final int mid;
        public final long whenMs;
        public final TLRPC.Message message;

        Entry(int kind, long dialogId, int mid, long whenMs, TLRPC.Message message) {
            this.kind = kind;
            this.dialogId = dialogId;
            this.mid = mid;
            this.whenMs = whenMs;
            this.message = message;
        }
    }

    public static MgMessageHistory getInstance() {
        MgMessageHistory local = instance;
        if (local == null) {
            synchronized (MgMessageHistory.class) {
                local = instance;
                if (local == null) {
                    local = instance = new MgMessageHistory();
                }
            }
        }
        return local;
    }

    private MgMessageHistory() {
        dbHelper = new DbHelper();
    }

    private static class DbHelper extends SQLiteOpenHelper {
        DbHelper() {
            super(ApplicationLoader.applicationContext, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(android.database.sqlite.SQLiteDatabase db) {
            // A message is deleted once → PK dedups. Pre-edit versions accumulate
            // (every retraction must survive), so the edited table has no PK.
            db.execSQL("CREATE TABLE " + TBL_DELETED + " (" +
                    "account INTEGER, dialog_id INTEGER, mid INTEGER, data BLOB, when_ms INTEGER, " +
                    "PRIMARY KEY(account, dialog_id, mid))");
            db.execSQL("CREATE TABLE " + TBL_EDITED + " (" +
                    "account INTEGER, dialog_id INTEGER, mid INTEGER, data BLOB, when_ms INTEGER)");
            db.execSQL("CREATE INDEX idx_deleted_dialog ON " + TBL_DELETED + "(account, dialog_id, when_ms)");
            db.execSQL("CREATE INDEX idx_edited_dialog ON " + TBL_EDITED + "(account, dialog_id, when_ms)");
        }

        @Override
        public void onUpgrade(android.database.sqlite.SQLiteDatabase db, int oldVersion, int newVersion) {
        }
    }

    /**
     * ToS guard — a message that self-destructs, or lives in a secret chat,
     * must never be persisted nor kept as an inline ghost (api/terms §1.4
     * "preventing self-destructing content from disappearing"). Shared by the
     * archive path and the ChatActivity ghost path so the rule cannot drift.
     */
    public static boolean isExcluded(long dialogId, TLRPC.Message message) {
        return DialogObject.isEncryptedDialog(dialogId)
                || message == null
                || message.ttl != 0
                || message.destroyTime != 0;
    }

    private static TLRPC.Message deserialize(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        NativeByteBuffer nbb = null;
        try {
            nbb = new NativeByteBuffer(bytes.length);
            nbb.writeBytes(bytes);
            nbb.buffer.rewind();
            return TLRPC.Message.TLdeserialize(nbb, nbb.readInt32(false), false);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            if (nbb != null) {
                nbb.reuse();
            }
        }
    }

    /**
     * Archive messages about to be deleted by the server. Must be called
     * <b>before</b> {@code MessagesStorage.markMessagesAsDeleted}; it enqueues
     * its read on the same (serial) storage queue so it observes the rows
     * before the upstream delete runs.
     */
    public void archiveDeleted(int account, long dialogId, ArrayList<Integer> mids) {
        if (!UserConfig.getInstance(account).mg.savedMessagesHistory || mids == null || mids.isEmpty()) {
            return;
        }
        markRemote(dialogId, mids);
        archive(account, dialogId, mids, true);
    }

    // Mids the server reported deleted that the storage read has not been consumed for
    // by an open chat yet, keyed like processUpdateArray keys them (0 = any non-channel
    // dialog, -channel_id for a channel). Only these become ghosts in an open chat:
    // a delete the user made locally is not persisted, so it must not ghost either.
    private final LongSparseArray<HashSet<Integer>> remotePending = new LongSparseArray<>();

    public void markRemote(long dialogId, Collection<Integer> mids) {
        synchronized (remotePending) {
            // ponytail: unbounded for chats that are never opened; reset when it grows past 256 dialogs.
            if (remotePending.size() > 256) {
                remotePending.clear();
            }
            HashSet<Integer> set = remotePending.get(dialogId);
            if (set == null) {
                set = new HashSet<>();
                remotePending.put(dialogId, set);
            }
            set.addAll(mids);
        }
    }

    /** Removes and returns the server-deleted mids pending for this key (see {@link #markRemote}). */
    public Set<Integer> takeRemote(long dialogId) {
        synchronized (remotePending) {
            HashSet<Integer> set = remotePending.get(dialogId);
            if (set == null) {
                return Collections.emptySet();
            }
            remotePending.remove(dialogId);
            return set;
        }
    }

    /**
     * Archive the current (pre-edit) version of messages about to be replaced
     * by an edit. Must be called <b>before</b>
     * {@code MessagesStorage.putMessages(..., mode=-2, ...)}.
     */
    public void archiveEditsBefore(int account, long dialogId, ArrayList<TLRPC.Message> newMessages) {
        if (!UserConfig.getInstance(account).mg.savedMessagesHistory || newMessages == null || newMessages.isEmpty()) {
            return;
        }
        ArrayList<Integer> mids = new ArrayList<>(newMessages.size());
        for (int a = 0; a < newMessages.size(); a++) {
            TLRPC.Message m = newMessages.get(a);
            if (m != null) {
                mids.add(m.id);
            }
        }
        archive(account, dialogId, mids, false);
    }

    private void archive(int account, long dialogId, ArrayList<Integer> mids, boolean deleted) {
        if (!UserConfig.getInstance(account).mg.savedMessagesHistory || mids == null || mids.isEmpty()
                || DialogObject.isEncryptedDialog(dialogId)) {
            return;
        }
        final ArrayList<Integer> midsCopy = new ArrayList<>(mids);
        final MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            SQLiteCursor cursor = null;
            try {
                String ids = android.text.TextUtils.join(",", midsCopy);
                String where = dialogId != 0
                        ? String.format(Locale.US, "uid = %d", dialogId)
                        : "is_channel = 0";
                cursor = storage.getDatabase().queryFinalized(String.format(Locale.US,
                        "SELECT uid, data FROM messages_v2 WHERE mid IN(%s) AND %s", ids, where));
                long now = System.currentTimeMillis();
                while (cursor.next()) {
                    long uid = cursor.longValue(0);
                    byte[] bytes = cursor.byteArrayValue(1);
                    TLRPC.Message message = deserialize(bytes);
                    if (message == null || isExcluded(uid, message)) {
                        continue;
                    }
                    ContentValues cv = new ContentValues();
                    cv.put("account", account);
                    cv.put("dialog_id", uid);
                    cv.put("mid", message.id);
                    cv.put("data", bytes);
                    cv.put("when_ms", now);
                    store(deleted ? TBL_DELETED : TBL_EDITED, cv, uid);
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
        });
    }

    private void store(String table, ContentValues cv, long dialogId) {
        synchronized (writeLock) {
            try {
                android.database.sqlite.SQLiteDatabase db = dbHelper.getWritableDatabase();
                db.insertWithOnConflict(table, null, cv,
                        android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE);
                db.execSQL("DELETE FROM " + table + " WHERE account=? AND dialog_id=? AND rowid NOT IN "
                                + "(SELECT rowid FROM " + table + " WHERE account=? AND dialog_id=? "
                                + "ORDER BY when_ms DESC LIMIT " + MAX_PER_DIALOG + ")",
                        new Object[]{cv.getAsInteger("account"), dialogId,
                                cv.getAsInteger("account"), dialogId});
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    /** All saved entries (deleted + pre-edit) for a dialog, newest first. */
    public List<Entry> getEntries(int account, long dialogId) {
        return loadEntries(account, dialogId, true, true);
    }

    /**
     * Deleted-only entries with full message blobs, newest first. Drives the
     * cold-restart ghost re-injection in {@code ChatActivity}: the row is gone
     * from {@code messages_v2} once the upstream delete runs, so the only path
     * to bring the cell back is to rebuild a {@code MessageObject} from this blob.
     */
    public List<Entry> getDeletedEntries(int account, long dialogId) {
        return loadEntries(account, dialogId, true, false);
    }

    private List<Entry> loadEntries(int account, long dialogId, boolean includeDeleted, boolean includeEdited) {
        ArrayList<Entry> out = new ArrayList<>();
        synchronized (writeLock) {
            try {
                android.database.sqlite.SQLiteDatabase db = dbHelper.getReadableDatabase();
                if (includeDeleted) {
                    loadKind(db, account, dialogId, TBL_DELETED, KIND_DELETED, out);
                }
                if (includeEdited) {
                    loadKind(db, account, dialogId, TBL_EDITED, KIND_EDITED, out);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        out.sort((a, b) -> Long.compare(b.whenMs, a.whenMs));
        return out;
    }

    private void loadKind(android.database.sqlite.SQLiteDatabase db, int account, long dialogId,
                          String table, int kind, List<Entry> out) {
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT mid, when_ms, data FROM " + table
                            + " WHERE account=? AND dialog_id=? ORDER BY when_ms DESC LIMIT " + MAX_PER_DIALOG,
                    new String[]{Integer.toString(account), Long.toString(dialogId)});
            while (c.moveToNext()) {
                TLRPC.Message m = deserialize(c.getBlob(2));
                if (m != null) {
                    out.add(new Entry(kind, dialogId, c.getInt(0), c.getLong(1), m));
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    /**
     * Distinct message IDs for a dialog in the given table (deleted or edited).
     * Used to prime per-chat in-memory caches so the long-press menu and the
     * chat-load ghost pass do not hit SQLite per row.
     */
    public Set<Integer> getMidsForDialog(int account, long dialogId, boolean edited) {
        final String table = edited ? TBL_EDITED : TBL_DELETED;
        final String sql = "SELECT DISTINCT mid FROM " + table + " WHERE account=? AND dialog_id=?";
        HashSet<Integer> out = new HashSet<>();
        readCursor(sql, new String[]{Integer.toString(account), Long.toString(dialogId)}, c -> {
            while (c.moveToNext()) {
                out.add(c.getInt(0));
            }
        });
        return out;
    }

    /** Pre-edit versions of a single message, oldest first. */
    public List<Entry> getEditHistoryFor(int account, long dialogId, int mid) {
        ArrayList<Entry> out = new ArrayList<>();
        final String sql = "SELECT when_ms, data FROM " + TBL_EDITED
                + " WHERE account=? AND dialog_id=? AND mid=? ORDER BY when_ms ASC";
        readCursor(sql,
                new String[]{Integer.toString(account), Long.toString(dialogId), Integer.toString(mid)},
                c -> {
                    while (c.moveToNext()) {
                        TLRPC.Message m = deserialize(c.getBlob(1));
                        if (m != null) {
                            out.add(new Entry(KIND_EDITED, dialogId, mid, c.getLong(0), m));
                        }
                    }
                });
        return out;
    }

    private interface CursorBody {
        void consume(Cursor c) throws Exception;
    }

    private void readCursor(String sql, String[] args, CursorBody body) {
        synchronized (writeLock) {
            Cursor c = null;
            try {
                c = dbHelper.getReadableDatabase().rawQuery(sql, args);
                body.consume(c);
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (c != null) {
                    try {
                        c.close();
                    } catch (Exception ignore) {
                    }
                }
            }
        }
    }

    /** Drop saved deleted messages the user removed from a chat themselves. */
    public void forgetDeleted(int account, long dialogId, Collection<Integer> mids) {
        if (mids == null || mids.isEmpty()) {
            return;
        }
        final String ids = android.text.TextUtils.join(",", mids);
        Utilities.globalQueue.postRunnable(() -> {
            synchronized (writeLock) {
                try {
                    dbHelper.getWritableDatabase().execSQL("DELETE FROM " + TBL_DELETED
                                    + " WHERE account=? AND dialog_id=? AND mid IN (" + ids + ")",
                            new Object[]{account, dialogId});
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        });
    }

    public void clearAll() {
        synchronized (writeLock) {
            try {
                android.database.sqlite.SQLiteDatabase db = dbHelper.getWritableDatabase();
                db.delete(TBL_DELETED, null, null);
                db.delete(TBL_EDITED, null, null);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }
}
