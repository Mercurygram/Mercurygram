package it.belloworld.mercurygram;

import android.app.Activity;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocationController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;

/** Cached incoming live locations with incremental per-dialog backfill scan. */
public final class MgIncomingLiveLocationStore {

	public interface ScanListener {
		void onProgress(int percent);
	}

	private static final String PREF_ENTRIES = "mg_inLocEntries_";
	private static final String PREF_SCANNED = "mg_inLocScanned_";
	private static final String PREF_SCAN_SCHEMA = "mg_inLocScanSchema_";
	/**
	 * Schema history:
	 * <ul>
	 *   <li>1 — filtered {@code messages_v2.media = 1} (TTL), missing all live geo</li>
	 *   <li>2 — dropped media filter, but listed dialogs via {@code dialogs.uid}
	 *       (column is {@code did}) so the scan aborted with empty results</li>
	 *   <li>3 — {@code SELECT did FROM dialogs} + {@code media = -1} candidate filter</li>
	 * </ul>
	 * Live geo rows use {@code media = -1} ({@link MessagesStorage#getMessageMediaType}).
	 */
	private static final int SCAN_SCHEMA = 3;

	private static final HashMap<Integer, ArrayList<MgIncomingLiveLocation>> cacheByAccount = new HashMap<>();
	private static final HashMap<Integer, HashSet<Long>> scannedDialogsByAccount = new HashMap<>();
	private static final HashMap<Integer, Integer> scanTotalByAccount = new HashMap<>();
	private static final HashMap<Integer, Integer> scanDoneByAccount = new HashMap<>();
	private static final HashMap<Integer, Boolean> scanningByAccount = new HashMap<>();
	private static final HashMap<Integer, Boolean> pendingForceRescanByAccount = new HashMap<>();
	private static final HashMap<Integer, ScanListener> scanListenerByAccount = new HashMap<>();

	private MgIncomingLiveLocationStore() {
	}

	public static long entryKey(long dialogId, long senderId) {
		return dialogId ^ Long.rotateLeft(senderId, 21);
	}

	public static ArrayList<MgIncomingLiveLocation> getForAccount(int account) {
		ensureLoaded(account);
		ArrayList<MgIncomingLiveLocation> list = cacheByAccount.get(account);
		return list != null ? new ArrayList<>(list) : new ArrayList<>();
	}

	public static ArrayList<MgIncomingLiveLocation> getAllAccounts() {
		ArrayList<MgIncomingLiveLocation> all = new ArrayList<>();
		for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
			if (UserConfig.getInstance(a).isClientActivated()) {
				all.addAll(getForAccount(a));
			}
		}
		return all;
	}

	public static int getScanProgressPercent() {
		int total = 0;
		int done = 0;
		for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
			if (!UserConfig.getInstance(a).isClientActivated()) {
				continue;
			}
			Integer t = scanTotalByAccount.get(a);
			Integer d = scanDoneByAccount.get(a);
			if (t != null && t > 0 && d != null) {
				total += t;
				done += Math.min(d, t);
			} else if (!Boolean.TRUE.equals(scanningByAccount.get(a))) {
				total += 1;
				done += 1;
			}
		}
		if (total <= 0) {
			return isScanning() ? 0 : 100;
		}
		return Math.min(100, done * 100 / total);
	}

	public static boolean isScanning() {
		for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
			if (Boolean.TRUE.equals(scanningByAccount.get(a))) {
				return true;
			}
		}
		return false;
	}

	public static boolean isScanning(int account) {
		return Boolean.TRUE.equals(scanningByAccount.get(account));
	}

	public static void ensureScan(int account, ScanListener listener) {
		ensureScan(account, listener, false);
	}

	public static void ensureScan(int account, ScanListener listener, boolean forceRescan) {
		if (!UserConfig.getInstance(account).isClientActivated()) {
			if (listener != null) {
				listener.onProgress(100);
			}
			return;
		}
		ensureLoaded(account);
		if (forceRescan) {
			invalidateScanned(account);
		}
		if (listener != null) {
			scanListenerByAccount.put(account, listener);
		}
		if (Boolean.TRUE.equals(scanningByAccount.get(account))) {
			if (forceRescan) {
				pendingForceRescanByAccount.put(account, true);
			}
			notifyProgress(account);
			return;
		}
		MessagesStorage.getInstance(account).getStorageQueue().postRunnable(() -> runScan(account));
	}

	public static void ensureScanAllAccounts(ScanListener listener) {
		ensureScanAllAccounts(listener, false);
	}

	public static void ensureScanAllAccounts(ScanListener listener, boolean forceRescan) {
		for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
			if (!UserConfig.getInstance(a).isClientActivated()) {
				continue;
			}
			final int account = a;
			ensureScan(account, percent -> {
				if (listener != null) {
					AndroidUtilities.runOnUIThread(() -> listener.onProgress(getScanProgressPercent()));
				}
			}, forceRescan);
		}
		if (listener != null && !isScanning()) {
			AndroidUtilities.runOnUIThread(() -> listener.onProgress(getScanProgressPercent()));
		}
	}

	/** Clears per-dialog scanned marks so the next ensureScan re-walks local history. */
	public static void invalidateScanned(int account) {
		ensureLoaded(account);
		HashSet<Long> scanned = scannedDialogsByAccount.get(account);
		if (scanned != null) {
			scanned.clear();
		}
		scanDoneByAccount.put(account, 0);
		prefs().edit().remove(PREF_SCANNED + account).apply();
	}

	public static void onNewMessages(int account, long dialogId, ArrayList<MessageObject> messages) {
		if (messages == null || messages.isEmpty()) {
			return;
		}
		long selfId = UserConfig.getInstance(account).getClientUserId();
		int now = ConnectionsManager.getInstance(account).getCurrentTime();
		boolean changed = false;
		for (int a = 0; a < messages.size(); a++) {
			MessageObject messageObject = messages.get(a);
			if (messageObject == null || messageObject.messageOwner == null) {
				continue;
			}
			TLRPC.Message message = messageObject.messageOwner;
			if (!LocationController.isActiveLiveLocation(message, now)) {
				continue;
			}
			if (MessageObject.getFromChatId(message) == selfId) {
				continue;
			}
			upsertInternal(account, dialogId, message, true);
			changed = true;
		}
		if (changed) {
			AndroidUtilities.runOnUIThread(() ->
					NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.liveLocationsChanged));
		}
	}

	private static void upsertInternal(int account, long dialogId, TLRPC.Message message, boolean persist) {
		ensureLoaded(account);
		ArrayList<MgIncomingLiveLocation> cache = cacheByAccount.get(account);
		long key = entryKey(dialogId, MessageObject.getFromChatId(message));
		for (int a = cache.size() - 1; a >= 0; a--) {
			MgIncomingLiveLocation existing = cache.get(a);
			if (entryKey(existing.dialogId, existing.senderId) == key) {
				if (LocationController.isActiveLiveLocation(existing.message, ConnectionsManager.getInstance(account).getCurrentTime())
						&& liveLocationSortKey(existing.message) >= liveLocationSortKey(message)) {
					return;
				}
				cache.remove(a);
				break;
			}
		}
		cache.add(new MgIncomingLiveLocation(account, dialogId, message));
		if (persist) {
			saveEntries(account);
		}
	}

	private static void runScan(int account) {
		scanningByAccount.put(account, true);
		pendingForceRescanByAccount.remove(account);
		ArrayList<Long> dialogIds = new ArrayList<>();
		SQLiteCursor cursor = null;
		try {
			// dialogs PK is "did" (never "uid" — that column lives on messages_v2).
			cursor = MessagesStorage.getInstance(account).getDatabase().queryFinalized(
					"SELECT did FROM dialogs ORDER BY did ASC");
			while (cursor.next()) {
				long did = cursor.longValue(0);
				if (!DialogObject.isEncryptedDialog(did)) {
					dialogIds.add(did);
				}
			}
		} catch (Exception e) {
			FileLog.e(e);
		} finally {
			if (cursor != null) {
				cursor.dispose();
			}
		}
		// Fallback if dialogs listing failed: still find live-geo candidate dialogs.
		if (dialogIds.isEmpty()) {
			cursor = null;
			try {
				cursor = MessagesStorage.getInstance(account).getDatabase().queryFinalized(
						"SELECT DISTINCT uid FROM messages_v2 WHERE media = -1");
				while (cursor.next()) {
					long did = cursor.longValue(0);
					if (!DialogObject.isEncryptedDialog(did)) {
						dialogIds.add(did);
					}
				}
			} catch (Exception e) {
				FileLog.e(e);
			} finally {
				if (cursor != null) {
					cursor.dispose();
				}
			}
		}
		HashSet<Long> scanned = scannedDialogsByAccount.get(account);
		if (scanned == null) {
			scanned = new HashSet<>();
			scannedDialogsByAccount.put(account, scanned);
		}
		int total = dialogIds.size();
		scanTotalByAccount.put(account, total);
		int done = 0;
		long selfId = UserConfig.getInstance(account).getClientUserId();
		for (int i = 0; i < dialogIds.size(); i++) {
			long did = dialogIds.get(i);
			if (scanned.contains(did)) {
				done++;
				scanDoneByAccount.put(account, done);
				continue;
			}
			scanDialog(account, did, selfId);
			scanned.add(did);
			saveScanned(account);
			done++;
			scanDoneByAccount.put(account, done);
			AndroidUtilities.runOnUIThread(() -> {
				NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.liveLocationsChanged);
				notifyProgress(account);
			});
		}
		scanDoneByAccount.put(account, total);
		saveEntries(account);
		scanningByAccount.put(account, false);
		if (Boolean.TRUE.equals(pendingForceRescanByAccount.remove(account))) {
			invalidateScanned(account);
			MessagesStorage.getInstance(account).getStorageQueue().postRunnable(() -> runScan(account));
			return;
		}
		AndroidUtilities.runOnUIThread(() -> {
			updateIncomingCount(account);
			NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.liveLocationsChanged);
			notifyProgress(account);
			scanListenerByAccount.remove(account);
		});
	}

	private static void scanDialog(int account, long did, long selfId) {
		SQLiteCursor cursor = null;
		try {
			int now = ConnectionsManager.getInstance(account).getCurrentTime();
			HashMap<Long, TLRPC.Message> bySender = new HashMap<>();
			// Candidate filter: getMessageMediaType stores live geo as media=-1.
			// media=1 is TTL photo/video only and must never be used here.
			cursor = MessagesStorage.getInstance(account).getDatabase().queryFinalized(
					"SELECT data FROM messages_v2 WHERE uid = ? AND media = -1 ORDER BY date DESC", did);
			while (cursor.next()) {
				NativeByteBuffer data = cursor.byteBufferValue(0);
				if (data == null) {
					continue;
				}
				TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
				data.reuse();
				if (!LocationController.isActiveLiveLocation(message, now)) {
					continue;
				}
				long fromId = MessageObject.getFromChatId(message);
				if (fromId == selfId) {
					continue;
				}
				TLRPC.Message existing = bySender.get(fromId);
				if (existing == null || liveLocationSortKey(message) >= liveLocationSortKey(existing)) {
					bySender.put(fromId, message);
				}
			}
			for (TLRPC.Message message : bySender.values()) {
				upsertInternal(account, did, message, false);
			}
		} catch (Exception e) {
			FileLog.e(e);
		} finally {
			if (cursor != null) {
				cursor.dispose();
			}
		}
	}

	private static void notifyProgress(int account) {
		ScanListener listener = scanListenerByAccount.get(account);
		if (listener == null) {
			return;
		}
		listener.onProgress(getScanProgressPercent());
	}

	private static void updateIncomingCount(int account) {
		ArrayList<MgIncomingLiveLocation> list = cacheByAccount.get(account);
		int count = list != null ? list.size() : 0;
		pruneExpired(account);
		list = cacheByAccount.get(account);
		LocationController.setIncomingLiveCount(account, list != null ? list.size() : count);
	}

	public static void pruneExpired(int account) {
		ArrayList<MgIncomingLiveLocation> cache = cacheByAccount.get(account);
		if (cache == null || cache.isEmpty()) {
			return;
		}
		int now = ConnectionsManager.getInstance(account).getCurrentTime();
		boolean changed = false;
		for (int a = cache.size() - 1; a >= 0; a--) {
			if (!LocationController.isActiveLiveLocation(cache.get(a).message, now)) {
				cache.remove(a);
				changed = true;
			}
		}
		if (changed) {
			saveEntries(account);
		}
	}

	private static void ensureLoaded(int account) {
		if (cacheByAccount.containsKey(account)) {
			pruneExpired(account);
			migrateScanSchemaIfNeeded(account);
			return;
		}
		ArrayList<MgIncomingLiveLocation> cache = new ArrayList<>();
		cacheByAccount.put(account, cache);
		HashSet<Long> scanned = new HashSet<>();
		scannedDialogsByAccount.put(account, scanned);
		SharedPreferences prefs = prefs();
		String scannedRaw = prefs.getString(PREF_SCANNED + account, "");
		if (!TextUtils.isEmpty(scannedRaw)) {
			for (String part : scannedRaw.split(",")) {
				if (part.isEmpty()) {
					continue;
				}
				try {
					scanned.add(Long.parseLong(part));
				} catch (NumberFormatException ignored) {
				}
			}
		}
		String entriesRaw = prefs.getString(PREF_ENTRIES + account, "");
		if (!TextUtils.isEmpty(entriesRaw)) {
			for (String part : entriesRaw.split(";")) {
				if (part.isEmpty()) {
					continue;
				}
				int c = part.indexOf(':');
				if (c <= 0) {
					continue;
				}
				try {
					long did = Long.parseLong(part.substring(0, c));
					int mid = Integer.parseInt(part.substring(c + 1));
					TLRPC.Message message = loadMessage(account, did, mid);
					if (message != null && LocationController.isActiveLiveLocation(message,
							ConnectionsManager.getInstance(account).getCurrentTime())) {
						cache.add(new MgIncomingLiveLocation(account, did, message));
					}
				} catch (NumberFormatException ignored) {
				}
			}
		}
		migrateScanSchemaIfNeeded(account);
		updateIncomingCount(account);
	}

	private static void migrateScanSchemaIfNeeded(int account) {
		SharedPreferences prefs = prefs();
		int schema = prefs.getInt(PREF_SCAN_SCHEMA + account, 1);
		if (schema >= SCAN_SCHEMA) {
			return;
		}
		HashSet<Long> scanned = scannedDialogsByAccount.get(account);
		if (scanned != null) {
			scanned.clear();
		}
		scanDoneByAccount.put(account, 0);
		prefs.edit()
				.remove(PREF_SCANNED + account)
				.putInt(PREF_SCAN_SCHEMA + account, SCAN_SCHEMA)
				.apply();
	}

	private static TLRPC.Message loadMessage(int account, long did, int mid) {
		SQLiteCursor cursor = null;
		try {
			cursor = MessagesStorage.getInstance(account).getDatabase().queryFinalized(
					"SELECT data FROM messages_v2 WHERE uid = ? AND mid = ?", did, mid);
			if (cursor.next()) {
				NativeByteBuffer data = cursor.byteBufferValue(0);
				if (data != null) {
					return TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
				}
			}
		} catch (Exception e) {
			FileLog.e(e);
		} finally {
			if (cursor != null) {
				cursor.dispose();
			}
		}
		return null;
	}

	private static void saveEntries(int account) {
		ArrayList<MgIncomingLiveLocation> cache = cacheByAccount.get(account);
		if (cache == null) {
			return;
		}
		StringBuilder sb = new StringBuilder();
		for (int a = 0; a < cache.size(); a++) {
			MgIncomingLiveLocation e = cache.get(a);
			if (a > 0) {
				sb.append(';');
			}
			sb.append(e.dialogId).append(':').append(e.message.id);
		}
		prefs().edit().putString(PREF_ENTRIES + account, sb.toString()).apply();
	}

	private static void saveScanned(int account) {
		HashSet<Long> scanned = scannedDialogsByAccount.get(account);
		if (scanned == null) {
			return;
		}
		ArrayList<Long> sorted = new ArrayList<>(scanned);
		Collections.sort(sorted);
		StringBuilder sb = new StringBuilder();
		for (int a = 0; a < sorted.size(); a++) {
			if (a > 0) {
				sb.append(',');
			}
			sb.append(sorted.get(a));
		}
		prefs().edit().putString(PREF_SCANNED + account, sb.toString()).apply();
	}

	private static int liveLocationSortKey(TLRPC.Message message) {
		return message.edit_date != 0 ? message.edit_date : message.date;
	}

	private static SharedPreferences prefs() {
		return ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
	}
}
