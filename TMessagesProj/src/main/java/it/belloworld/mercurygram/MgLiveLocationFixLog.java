package it.belloworld.mercurygram;

import android.app.Activity;
import android.content.SharedPreferences;
import android.location.Location;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import java.util.ArrayList;

public final class MgLiveLocationFixLog {

	private static final String PREF_ENABLED = "mg_liveLocFixLogEnabled";
	private static final String PREF_ENTRIES = "mg_liveLocFixLogEntries";
	private static final int MAX_ENTRIES = 200;

	public static final String TRIGGER_ALWAYS_ON = "always on";
	public static final String TRIGGER_DUTY_TIMER = "duty timer";
	public static final String TRIGGER_VIEWED = "viewed";
	public static final String TRIGGER_RESTART = "restart";
	public static final String TRIGGER_OTHER = "other";

	public static final class Entry {
		public final long whenMs;
		public final long ttfMs;
		public final float accuracyM;
		public final String provider;
		/** Policy hold after accurate fix (ms); 0 if abandoned / always-on continuous. */
		public final long holdAfterFixMs;
		public final String trigger;
		public final boolean abandoned;
		/** Accurate fix arrived after acquire timeout, during the post-timeout dwell grace. */
		public final boolean lateDuringDwell;

		Entry(long whenMs, long ttfMs, float accuracyM, String provider,
				long holdAfterFixMs, String trigger, boolean abandoned, boolean lateDuringDwell) {
			this.whenMs = whenMs;
			this.ttfMs = ttfMs;
			this.accuracyM = accuracyM;
			this.provider = provider != null ? provider : "?";
			this.holdAfterFixMs = Math.max(0, holdAfterFixMs);
			this.trigger = trigger != null && !trigger.isEmpty() ? trigger : TRIGGER_OTHER;
			this.abandoned = abandoned;
			this.lateDuringDwell = lateDuringDwell;
		}
	}

	private MgLiveLocationFixLog() {
	}

	public static boolean isEnabled() {
		return prefs().getBoolean(PREF_ENABLED, false);
	}

	public static void setEnabled(boolean enabled) {
		prefs().edit().putBoolean(PREF_ENABLED, enabled).apply();
	}

	/** Legacy: fix acquired (no trigger / hold). Prefer {@link #logSession}. */
	public static void log(long ttfMs, Location location) {
		logSession(ttfMs, location, TRIGGER_OTHER, 0, false, false);
	}

	public static void logSession(long ttfMs, Location location, String trigger,
			long holdAfterFixMs, boolean abandoned) {
		logSession(ttfMs, location, trigger, holdAfterFixMs, abandoned, false);
	}

	/**
	 * @param ttfMs time from GPS-on to accurate fix, or acquire duration if abandoned
	 * @param holdAfterFixMs dwell/hold applied after fix (0 if abandoned or always-on continuous)
	 * @param abandoned true when GPS stopped without an accurate fix
	 * @param lateDuringDwell true when fix arrived after acquire timeout during dwell grace
	 */
	public static void logSession(long ttfMs, Location location, String trigger,
			long holdAfterFixMs, boolean abandoned, boolean lateDuringDwell) {
		logSession(ttfMs, location, trigger, holdAfterFixMs, abandoned, lateDuringDwell, false);
	}

	public static void logSession(long ttfMs, Location location, String trigger,
			long holdAfterFixMs, boolean abandoned, boolean lateDuringDwell, boolean syncCommit) {
		if (!isEnabled() || ttfMs < 0) {
			return;
		}
		float acc = location != null && location.hasAccuracy() ? location.getAccuracy() : -1;
		String provider = location != null ? location.getProvider() : null;
		ArrayList<Entry> entries = load();
		entries.add(0, new Entry(System.currentTimeMillis(), ttfMs, acc, provider,
				holdAfterFixMs, trigger, abandoned, lateDuringDwell));
		while (entries.size() > MAX_ENTRIES) {
			entries.remove(entries.size() - 1);
		}
		save(entries, syncCommit);
	}

	public static String formatTriggerLabel(String trigger) {
		if (TRIGGER_ALWAYS_ON.equals(trigger)) {
			return LocaleController.getString(R.string.MercurygramLiveLocFixTriggerAlwaysOn);
		}
		if (TRIGGER_DUTY_TIMER.equals(trigger)) {
			return LocaleController.getString(R.string.MercurygramLiveLocFixTriggerDutyTimer);
		}
		if (TRIGGER_VIEWED.equals(trigger)) {
			return LocaleController.getString(R.string.MercurygramLiveLocFixTriggerViewed);
		}
		if (TRIGGER_RESTART.equals(trigger)) {
			return LocaleController.getString(R.string.MercurygramLiveLocFixTriggerRestart);
		}
		return LocaleController.getString(R.string.MercurygramLiveLocFixTriggerOther);
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
			String[] fields = part.split(":", -1);
			if (fields.length < 4) {
				continue;
			}
			try {
				float acc = Float.parseFloat(fields[3]);
				String provider = fields.length > 4 ? fields[4] : "?";
				long hold = 0;
				try {
					hold = Long.parseLong(fields[2]);
				} catch (NumberFormatException ignored) {
				}
				String trigger = fields.length > 5 && !fields[5].isEmpty() ? fields[5] : TRIGGER_OTHER;
				boolean abandoned = fields.length > 6 && "1".equals(fields[6]);
				boolean late = fields.length > 7 && "1".equals(fields[7]);
				out.add(new Entry(
						Long.parseLong(fields[0]),
						Long.parseLong(fields[1]),
						acc,
						provider,
						hold,
						trigger,
						abandoned,
						late));
			} catch (NumberFormatException ignored) {
			}
		}
		return out;
	}

	public static void clear() {
		prefs().edit().remove(PREF_ENTRIES).apply();
	}

	public static String formatEntry(Entry entry) {
		String ttf = MgLiveLocationPolicy.formatDurationSec((int) Math.max(0, (entry.ttfMs + 999) / 1000));
		String trigger = formatTriggerLabel(entry.trigger);
		if (entry.abandoned) {
			return LocaleController.formatString("MercurygramLiveLocFixLogLineAbandoned",
					R.string.MercurygramLiveLocFixLogLineAbandoned,
					LocaleController.formatDateTime(entry.whenMs / 1000, true),
					trigger,
					ttf);
		}
		String acc = entry.accuracyM >= 0
				? String.format(java.util.Locale.US, "%.0f m", entry.accuracyM)
				: "?";
		String hold = entry.holdAfterFixMs > 0
				? MgLiveLocationPolicy.formatDurationSec((int) Math.max(0, (entry.holdAfterFixMs + 999) / 1000))
				: LocaleController.getString(R.string.MercurygramLiveLocFixHoldNone);
		if (entry.lateDuringDwell) {
			return LocaleController.formatString("MercurygramLiveLocFixLogLineLateDwell",
					R.string.MercurygramLiveLocFixLogLineLateDwell,
					LocaleController.formatDateTime(entry.whenMs / 1000, true),
					trigger,
					ttf,
					hold,
					acc,
					entry.provider);
		}
		return LocaleController.formatString("MercurygramLiveLocFixLogLineDetailed",
				R.string.MercurygramLiveLocFixLogLineDetailed,
				LocaleController.formatDateTime(entry.whenMs / 1000, true),
				trigger,
				ttf,
				hold,
				acc,
				entry.provider);
	}

	private static void save(ArrayList<Entry> entries, boolean syncCommit) {
		StringBuilder sb = new StringBuilder();
		for (int a = 0; a < entries.size(); a++) {
			Entry e = entries.get(a);
			if (a > 0) {
				sb.append('\n');
			}
			sb.append(e.whenMs).append(':')
					.append(e.ttfMs).append(':')
					.append(e.holdAfterFixMs).append(':')
					.append(e.accuracyM).append(':')
					.append(sanitizeField(e.provider)).append(':')
					.append(sanitizeField(e.trigger)).append(':')
					.append(e.abandoned ? '1' : '0').append(':')
					.append(e.lateDuringDwell ? '1' : '0');
		}
		SharedPreferences.Editor editor = prefs().edit().putString(PREF_ENTRIES, sb.toString());
		if (syncCommit) {
			editor.commit();
		} else {
			editor.apply();
		}
	}

	private static String sanitizeField(String value) {
		if (value == null) {
			return "";
		}
		return value.replace(':', '_').replace('\n', ' ');
	}

	private static SharedPreferences prefs() {
		return ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
	}
}
