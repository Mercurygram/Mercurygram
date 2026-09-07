package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.SystemClock;
import android.util.Log;

/**
 * Diagnostics for live-location (FGS kill vs GPS duty-cycle vs viewer boost).
 * Logcat tag: {@code mg-loc}. Also persists a small ring buffer so events survive process death.
 */
public final class LiveLocationDebug {
	public static final String TAG = "mg-loc";
	private static final String PREF = "mg_live_loc_debug";
	private static final String KEY_RING = "ring";
	private static final int MAX_LINES = 120;

	private LiveLocationDebug() {
	}

	public static void log(String message) {
		Log.d(TAG, message);
		if (BuildVars.LOGS_ENABLED) {
			FileLog.d(TAG + ": " + message);
		}
		persist(message);
	}

	public static String dumpRing() {
		try {
			return prefs().getString(KEY_RING, "");
		} catch (Throwable ignored) {
			return "";
		}
	}

	public static void clearRing() {
		try {
			prefs().edit().remove(KEY_RING).apply();
		} catch (Throwable ignored) {
		}
	}

	private static void persist(String message) {
		try {
			if (ApplicationLoader.applicationContext == null) {
				return;
			}
			SharedPreferences prefs = prefs();
			String prev = prefs.getString(KEY_RING, "");
			StringBuilder next = new StringBuilder(System.currentTimeMillis() + " " + message);
			if (prev != null && !prev.isEmpty()) {
				String[] lines = prev.split("\n", MAX_LINES);
				for (int i = 0; i < lines.length && i < MAX_LINES - 1; i++) {
					next.append('\n').append(lines[i]);
				}
			}
			prefs.edit().putString(KEY_RING, next.toString()).apply();
		} catch (Throwable ignored) {
		}
	}

	private static SharedPreferences prefs() {
		return ApplicationLoader.applicationContext.getSharedPreferences(PREF, Activity.MODE_PRIVATE);
	}

	public static String fixSummary(Location location) {
		if (location == null) {
			return "null";
		}
		long ageSec = -1;
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
			ageSec = (SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos()) / 1000000000L;
		}
		return String.format(
				java.util.Locale.US,
				"provider=%s acc=%.1f ageSec=%d",
				location.getProvider(),
				location.getAccuracy(),
				ageSec
		);
	}
}
