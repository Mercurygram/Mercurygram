package it.belloworld.mercurygram;

import android.location.Location;
import android.util.SparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LocationController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.BulletinFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import it.belloworld.mercurygram.ui.MgShareLocationHelper;

/**
 * Chains live-location extends (≤ +1 day each) toward a persisted target end time.
 */
public final class MgLiveLocationExtendScheduler {

	private static final String PREF = "mg_live_loc_extend";
	private static final String KEY_TARGETS = "targets";
	private static final String KEY_PENDING = "pending";

	/** Minimum delay between chained extends. */
	private static final int MIN_CHAIN_DELAY_MS = 2500;
	private static final int MAX_FAILS = 3;
	private static final long FAIL_BACKOFF_MS = 60_000L;

	private static final Object lock = new Object();
	/** account -> (did -> targetEndUnixSec) */
	private static final SparseArray<HashMap<Long, Integer>> targets = new SparseArray<>();
	private static final SparseArray<HashMap<Long, Integer>> pending = new SparseArray<>();
	private static final HashSet<String> inFlight = new HashSet<>();
	private static final HashMap<String, Integer> failCounts = new HashMap<>();
	private static final HashMap<String, Runnable> scheduled = new HashMap<>();
	private static boolean loaded;

	private MgLiveLocationExtendScheduler() {
	}

	private static String key(int account, long did) {
		return account + ":" + did;
	}

	private static android.content.SharedPreferences prefs() {
		return ApplicationLoader.applicationContext.getSharedPreferences(PREF, 0);
	}

	private static void ensureLoaded() {
		synchronized (lock) {
			if (loaded) {
				return;
			}
			loaded = true;
			loadMap(KEY_TARGETS, targets);
			loadMap(KEY_PENDING, pending);
		}
	}

	private static void loadMap(String prefKey, SparseArray<HashMap<Long, Integer>> into) {
		String raw = prefs().getString(prefKey, "");
		if (raw == null || raw.isEmpty()) {
			return;
		}
		for (String part : raw.split(";")) {
			if (part.isEmpty()) {
				continue;
			}
			String[] bits = part.split("=");
			if (bits.length != 2) {
				continue;
			}
			String[] id = bits[0].split(":");
			if (id.length != 2) {
				continue;
			}
			try {
				int account = Integer.parseInt(id[0]);
				long did = Long.parseLong(id[1]);
				int end = Integer.parseInt(bits[1]);
				HashMap<Long, Integer> map = into.get(account);
				if (map == null) {
					map = new HashMap<>();
					into.put(account, map);
				}
				map.put(did, end);
			} catch (Exception ignored) {
			}
		}
	}

	private static void persist() {
		synchronized (lock) {
			prefs().edit()
					.putString(KEY_TARGETS, encode(targets))
					.putString(KEY_PENDING, encode(pending))
					.apply();
		}
	}

	private static String encode(SparseArray<HashMap<Long, Integer>> src) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < src.size(); i++) {
			int account = src.keyAt(i);
			HashMap<Long, Integer> map = src.valueAt(i);
			if (map == null) {
				continue;
			}
			for (Map.Entry<Long, Integer> e : map.entrySet()) {
				if (sb.length() > 0) {
					sb.append(';');
				}
				sb.append(account).append(':').append(e.getKey()).append('=').append(e.getValue());
			}
		}
		return sb.toString();
	}

	private static void put(SparseArray<HashMap<Long, Integer>> into, int account, long did, int endSec) {
		HashMap<Long, Integer> map = into.get(account);
		if (map == null) {
			map = new HashMap<>();
			into.put(account, map);
		}
		map.put(did, endSec);
	}

	private static Integer get(SparseArray<HashMap<Long, Integer>> from, int account, long did) {
		HashMap<Long, Integer> map = from.get(account);
		return map == null ? null : map.get(did);
	}

	private static void remove(SparseArray<HashMap<Long, Integer>> from, int account, long did) {
		HashMap<Long, Integer> map = from.get(account);
		if (map != null) {
			map.remove(did);
			if (map.isEmpty()) {
				from.remove(account);
			}
		}
	}

	/** Remember target end before the share message exists (new share). */
	public static void setPendingTargetEndSec(int account, long dialogId, int targetEndSec) {
		ensureLoaded();
		synchronized (lock) {
			put(pending, account, dialogId, targetEndSec);
		}
		persist();
	}

	/** Convenience: target end = now + durationSec (clamped to server max). */
	public static int setPendingTargetFromDuration(int account, long dialogId, int durationSec) {
		int now = ConnectionsManager.getInstance(account).getCurrentTime();
		int end = MgShareLocationHelper.clampTargetEndSec(now, now + (long) durationSec);
		setPendingTargetEndSec(account, dialogId, end);
		return end;
	}

	/** Active share: keep extending until this absolute end (unix seconds). */
	public static void setTargetEndSec(int account, long dialogId, int targetEndSec) {
		ensureLoaded();
		synchronized (lock) {
			put(targets, account, dialogId, targetEndSec);
			remove(pending, account, dialogId);
			failCounts.remove(key(account, dialogId));
		}
		persist();
		scheduleCheck(account, dialogId, 0);
	}

	public static void cancel(int account, long dialogId) {
		ensureLoaded();
		String k = key(account, dialogId);
		synchronized (lock) {
			remove(targets, account, dialogId);
			remove(pending, account, dialogId);
			failCounts.remove(k);
			inFlight.remove(k);
		}
		cancelScheduled(k);
		persist();
	}

	public static void cancelAllForAccount(int account) {
		ensureLoaded();
		HashSet<Long> dids = new HashSet<>();
		synchronized (lock) {
			HashMap<Long, Integer> t = targets.get(account);
			if (t != null) {
				dids.addAll(t.keySet());
			}
			HashMap<Long, Integer> p = pending.get(account);
			if (p != null) {
				dids.addAll(p.keySet());
			}
		}
		for (Long did : dids) {
			cancel(account, did);
		}
	}

	public static void onSharingStarted(int account, LocationController.SharingLocationInfo info) {
		if (info == null) {
			return;
		}
		ensureLoaded();
		Integer pendingEnd;
		synchronized (lock) {
			pendingEnd = get(pending, account, info.did);
			if (pendingEnd != null) {
				remove(pending, account, info.did);
				put(targets, account, info.did, pendingEnd);
			}
		}
		if (pendingEnd != null) {
			persist();
		}
		Integer target = getTargetEnd(account, info.did);
		if (target == null) {
			return;
		}
		if (info.period == MgShareLocationHelper.FOREVER_PERIOD) {
			cancel(account, info.did);
			return;
		}
		scheduleCheck(account, info.did, MIN_CHAIN_DELAY_MS);
	}

	public static void onSharingStopped(int account, long dialogId) {
		cancel(account, dialogId);
	}

	/** After locations load / process start: resume any unfinished chains. */
	public static void rescheduleAll() {
		ensureLoaded();
		HashSet<String> keys = new HashSet<>();
		synchronized (lock) {
			for (int i = 0; i < targets.size(); i++) {
				int account = targets.keyAt(i);
				HashMap<Long, Integer> map = targets.valueAt(i);
				if (map == null) {
					continue;
				}
				for (Long did : map.keySet()) {
					keys.add(key(account, did));
				}
			}
		}
		for (String k : keys) {
			String[] bits = k.split(":");
			try {
				scheduleCheck(Integer.parseInt(bits[0]), Long.parseLong(bits[1]), MIN_CHAIN_DELAY_MS);
			} catch (Exception ignored) {
			}
		}
	}

	public static Integer getTargetEnd(int account, long dialogId) {
		ensureLoaded();
		synchronized (lock) {
			return get(targets, account, dialogId);
		}
	}

	private static void cancelScheduled(String k) {
		Runnable r;
		synchronized (lock) {
			r = scheduled.remove(k);
		}
		if (r != null) {
			AndroidUtilities.cancelRunOnUIThread(r);
		}
	}

	private static void scheduleCheck(int account, long dialogId, long delayMs) {
		String k = key(account, dialogId);
		cancelScheduled(k);
		Runnable r = () -> {
			synchronized (lock) {
				scheduled.remove(k);
			}
			tryExtend(account, dialogId);
		};
		synchronized (lock) {
			scheduled.put(k, r);
		}
		AndroidUtilities.runOnUIThread(r, Math.max(0, delayMs));
	}

	private static void tryExtend(int account, long dialogId) {
		Integer targetEnd;
		synchronized (lock) {
			targetEnd = get(targets, account, dialogId);
		}
		if (targetEnd == null) {
			return;
		}
		LocationController.SharingLocationInfo info = LocationController.getInstance(account).getSharingLocationInfo(dialogId);
		if (info == null) {
			cancel(account, dialogId);
			return;
		}
		if (info.period == MgShareLocationHelper.FOREVER_PERIOD) {
			cancel(account, dialogId);
			return;
		}
		int now = ConnectionsManager.getInstance(account).getCurrentTime();
		if (targetEnd <= now) {
			cancel(account, dialogId);
			return;
		}
		int currentEnd = info.stopTime;
		if (currentEnd >= targetEnd) {
			cancel(account, dialogId);
			return;
		}
		int remainingToTarget = targetEnd - currentEnd;
		int messageDate = info.messageObject != null && info.messageObject.messageOwner != null
				? info.messageObject.messageOwner.date
				: now;
		// Next step is min(24h, remaining): full-day chunks while remaining > 24h,
		// then the final remainder (≤24h). Always apply soon — do not defer the
		// remainder until near expiry (that left e.g. 50h stuck at 48h).
		int addSec = MgShareLocationHelper.clampExtendAddition(info.period, messageDate, now, remainingToTarget);
		if (addSec <= 0) {
			cancel(account, dialogId);
			AndroidUtilities.runOnUIThread(() -> {
				try {
					BulletinFactory.global()
							.createSimpleBulletin(R.raw.error,
									LocaleController.getString(R.string.MercurygramLiveLocExtendLimit))
							.show();
				} catch (Exception ignored) {
				}
			});
			return;
		}

		String k = key(account, dialogId);
		synchronized (lock) {
			if (inFlight.contains(k)) {
				return;
			}
			inFlight.add(k);
		}
		sendExtend(account, info, addSec, targetEnd);
	}

	private static void sendExtend(int account, LocationController.SharingLocationInfo info, int addSec, int targetEnd) {
		Location lastKnown = LocationController.getInstance(account).getLastKnownLocation();
		if (lastKnown == null) {
			String k = key(account, info.did);
			synchronized (lock) {
				inFlight.remove(k);
			}
			scheduleCheck(account, info.did, FAIL_BACKOFF_MS);
			return;
		}
		TLRPC.TL_messages_editMessage req = new TLRPC.TL_messages_editMessage();
		req.peer = MessagesController.getInstance(account).getInputPeer(info.did);
		req.id = info.mid;
		req.flags |= 16384;
		req.media = new TLRPC.TL_inputMediaGeoLive();
		req.media.stopped = false;
		req.media.geo_point = new TLRPC.TL_inputGeoPoint();
		req.media.geo_point.lat = AndroidUtilities.fixLocationCoord(lastKnown.getLatitude());
		req.media.geo_point._long = AndroidUtilities.fixLocationCoord(lastKnown.getLongitude());
		req.media.geo_point.accuracy_radius = (int) lastKnown.getAccuracy();
		if (req.media.geo_point.accuracy_radius != 0) {
			req.media.geo_point.flags |= 1;
		}
		if (info.lastSentProximityMeters != info.proximityMeters) {
			req.media.proximity_notification_radius = info.proximityMeters;
			req.media.flags |= 8;
		}
		req.media.heading = LocationController.getHeading(lastKnown);
		req.media.flags |= 4;
		final int newPeriod = MgShareLocationHelper.saturatingAdd(info.period, addSec);
		final int newStop = MgShareLocationHelper.saturatingAdd(info.stopTime, addSec);
		req.media.period = newPeriod;
		req.media.flags |= 2;

		ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
			String k = key(account, info.did);
			synchronized (lock) {
				inFlight.remove(k);
			}
			if (error != null) {
				FileLog.e("MgLiveLocationExtendScheduler extend failed: " + error.code + " " + error.text);
				int fails;
				synchronized (lock) {
					Integer prev = failCounts.get(k);
					fails = (prev == null ? 0 : prev) + 1;
					failCounts.put(k, fails);
				}
				if (fails >= MAX_FAILS) {
					cancel(account, info.did);
				} else {
					scheduleCheck(account, info.did, FAIL_BACKOFF_MS * fails);
				}
				return;
			}
			info.period = newPeriod;
			info.stopTime = newStop;
			if (info.messageObject != null && info.messageObject.messageOwner != null && info.messageObject.messageOwner.media != null) {
				info.messageObject.messageOwner.media.period = info.period;
				MessagesStorage.getInstance(account).replaceMessageIfExists(info.messageObject.messageOwner, null, null, true);
			}
			LocationController.getInstance(account).persistSharingLocationInfo(info);
			if (response instanceof TLRPC.Updates) {
				MessagesController.getInstance(account).processUpdates((TLRPC.Updates) response, false);
			}
			NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.liveLocationsChanged);
			synchronized (lock) {
				failCounts.remove(k);
			}
			if (info.stopTime >= targetEnd) {
				cancel(account, info.did);
			} else {
				scheduleCheck(account, info.did, MIN_CHAIN_DELAY_MS);
			}
		}));
	}

	/**
	 * Manual extend path (user picked add / share-until): compute first-step addition
	 * and register the target so the scheduler finishes the chain.
	 *
	 * @return first addition to apply now (0 if none)
	 */
	public static int beginExtendToward(int account, LocationController.SharingLocationInfo info, int targetEndSec) {
		if (info == null) {
			return 0;
		}
		int now = ConnectionsManager.getInstance(account).getCurrentTime();
		targetEndSec = MgShareLocationHelper.clampTargetEndSec(now, targetEndSec);
		if (targetEndSec <= info.stopTime) {
			return 0;
		}
		int messageDate = info.messageObject != null && info.messageObject.messageOwner != null
				? info.messageObject.messageOwner.date
				: now;
		int remaining = targetEndSec - info.stopTime;
		int addSec = MgShareLocationHelper.clampExtendAddition(info.period, messageDate, now, remaining);
		if (addSec <= 0) {
			return 0;
		}
		// Register target without immediately scheduling — caller sends the first RPC,
		// then onSharingStarted-style schedule continues after local stopTime updates.
		ensureLoaded();
		synchronized (lock) {
			put(targets, account, info.did, targetEndSec);
			remove(pending, account, info.did);
			failCounts.remove(key(account, info.did));
		}
		persist();
		return addSec;
	}

	/** Call after a manual first-step extend succeeded so chaining continues. */
	public static void continueAfterManualExtend(int account, long dialogId) {
		scheduleCheck(account, dialogId, MIN_CHAIN_DELAY_MS);
	}
}
