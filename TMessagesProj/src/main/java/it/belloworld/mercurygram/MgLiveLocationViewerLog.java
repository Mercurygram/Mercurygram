package it.belloworld.mercurygram;

import android.app.Activity;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;

/**
 * Single store for live-location view notifications ({@code updateGeoLiveViewed}).
 *
 * <p>One prefs key ({@code mg_liveLocViewerLogEntries}) + a small in-process ring
 * buffer. Both Services “Being viewed by” ({@link #recentForUi}) and Share location
 * behaviour View/Clear ({@link #load}/{@link #clear}) read the same data — not two
 * backends. The enable flag only gates the diagnostic View/Clear UI.
 *
 * <p>The MTProto update only carries {@code peer} + {@code msg_id}. Docs describe
 * {@code peer} as the viewer, but in practice groups often send the chat peer —
 * viewer identity is then unavailable.
 */
public final class MgLiveLocationViewerLog {

	private static final String PREF_ENABLED = "mg_liveLocViewerLogEnabled";
	private static final String PREF_ENTRIES = "mg_liveLocViewerLogEntries";
	private static final int MAX_ENTRIES = 200;
	private static final int MAX_RECENT_MEMORY = 50;
	/** Collapsed “Being viewed by” rows in Services (no time window). */
	public static final int UI_COLLAPSED_COUNT = 5;

	private static final ArrayList<Entry> recentMemory = new ArrayList<>();

	public static final class Entry {
		public final long whenMs;
		public final int account;
		public final long dialogId;
		public final int messageId;
		/** Positive user id when known; 0 if the server did not identify the viewer. */
		public final long viewerId;

		Entry(long whenMs, int account, long dialogId, int messageId, long viewerId) {
			this.whenMs = whenMs;
			this.account = account;
			this.dialogId = dialogId;
			this.messageId = messageId;
			this.viewerId = viewerId;
		}
	}

	private MgLiveLocationViewerLog() {
	}

	public static boolean isEnabled() {
		return prefs().getBoolean(PREF_ENABLED, false);
	}

	public static void setEnabled(boolean enabled) {
		prefs().edit().putBoolean(PREF_ENABLED, enabled).apply();
	}

	public static void log(int account, long dialogId, int messageId, long viewerId) {
		Entry entry = new Entry(System.currentTimeMillis(), account, dialogId, messageId, viewerId);
		remember(entry);
		// Always persist — Services “Being viewed by” must work even when the optional
		// diagnostic log UI (View / Clear) is disabled in Share location behaviour.
		// Synchronize + commit: stageQueue can deliver bursts, and apply() was lost when
		// DEBUG builds crashed the process on the subsequent off-main NotificationCenter post.
		synchronized (MgLiveLocationViewerLog.class) {
			ArrayList<Entry> entries = load();
			entries.add(0, entry);
			while (entries.size() > MAX_ENTRIES) {
				entries.remove(entries.size() - 1);
			}
			save(entries);
		}
	}

	/**
	 * @param peer update peer (viewer user and/or dialog, depending on server)
	 * @param messageId live-location message id
	 * @param shareDialogId dialog of our matching share when known (0 if unknown)
	 */
	public static void logGeoLiveViewed(int account, TLRPC.Peer peer, int messageId, long shareDialogId) {
		if (peer == null) {
			return;
		}
		long peerDialogId = DialogObject.getPeerDialogId(peer);
		long dialogId;
		long viewerId = 0;

		if (peer instanceof TLRPC.TL_peerUser) {
			viewerId = peer.user_id;
			if (shareDialogId != 0) {
				dialogId = shareDialogId;
			} else {
				dialogId = peer.user_id;
			}
		} else {
			// Chat/channel peer: typically the dialog; viewer identity omitted by server.
			dialogId = shareDialogId != 0 ? shareDialogId : peerDialogId;
			viewerId = 0;
		}
		log(account, dialogId, messageId, viewerId);
	}

	/** Backward-compatible entry point when share dialog was not resolved. */
	public static void logGeoLiveViewed(int account, TLRPC.Peer peer, int messageId) {
		logGeoLiveViewed(account, peer, messageId, 0);
	}

	/**
	 * Deduped viewers for Services, newest first, no time limit.
	 * Capped at {@link #MAX_ENTRIES} (same as persisted store).
	 */
	public static ArrayList<Entry> recentForUi() {
		ArrayList<Entry> combined = new ArrayList<>();
		synchronized (recentMemory) {
			combined.addAll(recentMemory);
		}
		combined.addAll(load());
		Collections.sort(combined, (a, b) -> Long.compare(b.whenMs, a.whenMs));
		ArrayList<Entry> out = new ArrayList<>();
		HashSet<String> seen = new HashSet<>();
		for (int i = 0; i < combined.size(); i++) {
			Entry e = combined.get(i);
			String key = e.account + ":" + e.dialogId + ":" + e.viewerId + ":" + e.messageId;
			if (!seen.add(key)) {
				continue;
			}
			out.add(e);
			if (out.size() >= MAX_ENTRIES) {
				break;
			}
		}
		return out;
	}

	public static String formatViewerName(Entry entry) {
		return resolveViewerName(MessagesController.getInstance(entry.account), entry);
	}

	public static String formatViewerChat(Entry entry) {
		return resolveDialogTitle(MessagesController.getInstance(entry.account), entry.dialogId);
	}

	private static void remember(Entry entry) {
		synchronized (recentMemory) {
			recentMemory.add(0, entry);
			while (recentMemory.size() > MAX_RECENT_MEMORY) {
				recentMemory.remove(recentMemory.size() - 1);
			}
		}
	}

	public static ArrayList<Entry> load() {
		String raw = prefs().getString(PREF_ENTRIES, "");
		if (raw == null || raw.isEmpty()) {
			return new ArrayList<>();
		}
		ArrayList<Entry> out = new ArrayList<>();
		for (String part : raw.split("\n")) {
			if (part.isEmpty()) {
				continue;
			}
			String[] fields = part.split(":");
			if (fields.length < 5) {
				continue;
			}
			try {
				out.add(new Entry(
						Long.parseLong(fields[0]),
						Integer.parseInt(fields[1]),
						Long.parseLong(fields[2]),
						Integer.parseInt(fields[3]),
						Long.parseLong(fields[4])));
			} catch (NumberFormatException ignored) {
			}
		}
		return out;
	}

	public static void clear() {
		synchronized (recentMemory) {
			recentMemory.clear();
		}
		synchronized (MgLiveLocationViewerLog.class) {
			prefs().edit().remove(PREF_ENTRIES).commit();
		}
	}

	public static String formatEntry(Entry entry) {
		MessagesController mc = MessagesController.getInstance(entry.account);
		String chat = resolveDialogTitle(mc, entry.dialogId);
		String viewer = resolveViewerName(mc, entry);
		return LocaleController.formatString("MercurygramLiveLocViewerLogLineNamed",
				R.string.MercurygramLiveLocViewerLogLineNamed,
				LocaleController.formatDateTime(entry.whenMs / 1000, true),
				viewer,
				chat,
				entry.messageId);
	}

	private static String resolveDialogTitle(MessagesController mc, long dialogId) {
		if (DialogObject.isUserDialog(dialogId)) {
			TLRPC.User user = mc.getUser(dialogId);
			return user != null ? UserObject.getUserName(user) : Long.toString(dialogId);
		}
		TLRPC.Chat c = mc.getChat(-dialogId);
		return c != null ? c.title : Long.toString(-dialogId);
	}

	private static String resolveViewerName(MessagesController mc, Entry entry) {
		if (entry.viewerId > 0) {
			TLRPC.User user = mc.getUser(entry.viewerId);
			if (user != null) {
				return UserObject.getUserName(user);
			}
			return Long.toString(entry.viewerId);
		}
		// Legacy entries stored negative chat id as "viewerId" — treat as unknown.
		if (entry.viewerId < 0) {
			return LocaleController.getString(R.string.MercurygramLiveLocViewerUnknown);
		}
		// 1:1 share with only dialog known: the peer is the viewer.
		if (DialogObject.isUserDialog(entry.dialogId)) {
			TLRPC.User user = mc.getUser(entry.dialogId);
			if (user != null) {
				return UserObject.getUserName(user);
			}
			return Long.toString(entry.dialogId);
		}
		return LocaleController.getString(R.string.MercurygramLiveLocViewerUnknown);
	}

	private static void save(ArrayList<Entry> entries) {
		StringBuilder sb = new StringBuilder();
		for (int a = 0; a < entries.size(); a++) {
			Entry e = entries.get(a);
			if (a > 0) {
				sb.append('\n');
			}
			sb.append(e.whenMs).append(':')
					.append(e.account).append(':')
					.append(e.dialogId).append(':')
					.append(e.messageId).append(':')
					.append(e.viewerId);
		}
		prefs().edit().putString(PREF_ENTRIES, sb.toString()).commit();
	}

	private static SharedPreferences prefs() {
		return ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
	}
}
