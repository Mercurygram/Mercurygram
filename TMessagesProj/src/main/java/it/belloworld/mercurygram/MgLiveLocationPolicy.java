package it.belloworld.mercurygram;

import android.app.Activity;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Build;
import android.os.SystemClock;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LiveLocationDebug;
import org.telegram.messenger.SharedConfig;

import java.util.ArrayList;

/**
 * Mercurygram live-location GPS duty cycle. Replaces Telegram's 65s-on / 30s-off
 * blink, which republished stale last-known fixes on devices without Play Services.
 *
 * <p>Acquire until a <em>new</em> accurate fix, dwell (GPS still on), then sleep.
 * Optional acquire timeout from a fixed cap or the last 5 TTFFs (μ+σ). A timeout
 * keeps GPS for one dwell window; a late accurate fix in that window is recorded.
 * Watchers skip sleep outside battery saver.
 */
public final class MgLiveLocationPolicy {

	public static final float ACCURATE_METERS = 25f;
	public static final int TTFF_MIN_SAMPLES = 5;
	public static final int TTFF_KEEP = 5;
	public static final int DEFAULT_DWELL_SEC = 90;
	public static final int DEFAULT_SLEEP_MAX_SEC = 20 * 60;
	public static final int SLEEP_MAX_MIN_SEC = 5 * 60;
	public static final int SLEEP_MAX_MAX_SEC = 5 * 60 * 60;
	/** Discrete dwell-slider steps (30 s … 5 min). */
	public static final int[] DWELL_STEPS_SEC = {
			30,
			60,
			90,
			180,
			300,
	};
	/** Discrete wait-slider steps (5 min … 5 h). */
	public static final int[] SLEEP_MAX_STEPS_SEC = {
			5 * 60,
			10 * 60,
			15 * 60,
			20 * 60,
			25 * 60,
			30 * 60,
			40 * 60,
			45 * 60,
			50 * 60,
			60 * 60,
			75 * 60,
			90 * 60,
			2 * 60 * 60,
			150 * 60,
			3 * 60 * 60,
			210 * 60,
			4 * 60 * 60,
			270 * 60,
			5 * 60 * 60,
	};
	/** Linear/log 1× time anchor when always-off is “Until stopped” (inf.). */
	public static final int LINEAR_INFINITE_ANCHOR_SEC = 7 * 24 * 60 * 60;
	/** Remaining time assumed for infinite / until-stopped shares in linear/log wait. */
	public static final int INFINITE_SHARE_REMAINING_SEC = 10 * 24 * 60 * 60;

	public static final int SLEEP_MODE_FIXED = 0;
	public static final int SLEEP_MODE_LINEAR = 1;
	public static final int SLEEP_MODE_LOG = 2;

	public static final int BATTERY_SAVER_OFF = 0;
	/** Default wait multiplier for new installs (×3). */
	public static final int DEFAULT_BATTERY_SAVER_MULTIPLIER = 3;
	/** Infer timeout from last {@link #TTFF_KEEP} valid TTFFs (μ+σ). */
	public static final int MAX_ACQUIRE_FROM_HISTORY = -1;

	private static final String PREF_TTFF = "mg_liveLocTtffLog";
	private static final long MAX_FIX_AGE_SEC = 15;

	private long acquireStartElapsed;
	private boolean accurateThisSession;
	private long accurateAtElapsed;
	private long watcherHoldUntilElapsed;
	private long sleepUntilElapsed;
	private boolean lastAcquireTimedOut;
	/** Elapsed when acquire first hit the timeout without a fix; 0 if not timed out. */
	private long acquireTimedOutAtElapsed;
	private boolean sessionLateDuringDwell;
	private String sessionTrigger = MgLiveLocationFixLog.TRIGGER_OTHER;
	private boolean sessionViewerBoosted;
	private boolean sessionLogged;
	private float sessionFinalAccuracy = -1f;
	private String sessionProvider;
	private Location sessionFinalLocation;
	/** One-shot GPS acquire after process restore / APK replace (overrides sleep / always-off once). */
	private boolean forceAcquireOnce;
	/** Prefer commit() when flushing Fix Log during process teardown. */
	private boolean syncCommitNextSessionLog;

	public void reset() {
		flushSessionLogIfNeeded(false);
		acquireStartElapsed = 0;
		accurateThisSession = false;
		accurateAtElapsed = 0;
		watcherHoldUntilElapsed = 0;
		sleepUntilElapsed = 0;
		lastAcquireTimedOut = false;
		acquireTimedOutAtElapsed = 0;
		sessionLateDuringDwell = false;
		forceAcquireOnce = false;
		syncCommitNextSessionLog = false;
		minShareRemainingSec = 0;
		clearSessionLogState();
	}

	public static final int ALWAYS_OFF = 0;
	public static final int ALWAYS_15M = 1;
	public static final int ALWAYS_1H = 2;
	public static final int ALWAYS_8H = 3;
	public static final int ALWAYS_ALL = 4;

	private int minShareRemainingSec;

	public void setMinShareRemainingSec(int remainingSec) {
		minShareRemainingSec = remainingSec;
	}

	public void prepareImmediateAcquire() {
		sleepUntilElapsed = 0;
		lastAcquireTimedOut = false;
	}

	/**
	 * After kill / update restore: skip sleep and always-off once so we can send a fresh fix ASAP.
	 */
	public void requestForceAcquireAfterRestore() {
		forceAcquireOnce = true;
		prepareImmediateAcquire();
		LiveLocationDebug.log("policy forceAcquireOnce after restore");
	}

	public boolean consumeForceAcquireOnce() {
		if (!forceAcquireOnce) {
			return false;
		}
		forceAcquireOnce = false;
		return true;
	}

	public static int alwaysOnThresholdSec(int mode) {
		switch (mode) {
			case ALWAYS_15M:
				return 15 * 60;
			case ALWAYS_1H:
				return 60 * 60;
			case ALWAYS_8H:
				return 8 * 60 * 60;
			default:
				return 0;
		}
	}

	public static int clampToNearestStep(int sec, int[] steps) {
		if (steps == null || steps.length == 0) {
			return Math.max(0, sec);
		}
		int best = steps[0];
		int bestDelta = Integer.MAX_VALUE;
		for (int step : steps) {
			int delta = Math.abs(step - sec);
			if (delta < bestDelta) {
				bestDelta = delta;
				best = step;
			}
		}
		return best;
	}

	public static int stepIndex(int sec, int[] steps) {
		if (steps == null || steps.length == 0) {
			return 0;
		}
		int clamped = clampToNearestStep(sec, steps);
		for (int a = 0; a < steps.length; a++) {
			if (steps[a] == clamped) {
				return a;
			}
		}
		return 0;
	}

	public static int clampDwellSec(int sec) {
		return clampToNearestStep(sec, DWELL_STEPS_SEC);
	}

	public static int clampSleepMaxSec(int sec) {
		return clampToNearestStep(sec, SLEEP_MAX_STEPS_SEC);
	}

	public static int sleepMaxStepIndex(int sec) {
		return stepIndex(sec, SLEEP_MAX_STEPS_SEC);
	}

	public boolean alwaysOn() {
		if (minShareRemainingSec <= 0) {
			return false;
		}
		int lowerSec = MgLiveLocationThresholds.secondsAt(SharedConfig.mg_liveLocAlwaysOnLowerIndex);
		if (MgLiveLocationThresholds.isInfinite(lowerSec)) {
			return true;
		}
		if (lowerSec <= 0) {
			return false;
		}
		return minShareRemainingSec <= lowerSec;
	}

	public boolean alwaysOff() {
		if (minShareRemainingSec <= 0) {
			return false;
		}
		int upperSec = MgLiveLocationThresholds.secondsAt(SharedConfig.mg_liveLocAlwaysOffUpperIndex);
		if (MgLiveLocationThresholds.isInfinite(upperSec)) {
			return false;
		}
		return minShareRemainingSec >= upperSec;
	}

	/** Battery-saver profile active (multiplier enabled, including ×1) and not always-on. */
	public boolean batterySaver() {
		return SharedConfig.mg_liveLocBatterySaverMultiplier != BATTERY_SAVER_OFF && !alwaysOn();
	}

	public static boolean batterySaverEnabled() {
		return SharedConfig.mg_liveLocBatterySaverMultiplier != BATTERY_SAVER_OFF;
	}

	/** Viewer boost is skipped in battery saver unless the user opts in. */
	public boolean viewerBoostBlockedByBatterySaver() {
		return batterySaver() && !SharedConfig.mg_liveLocViewerBoostInBatterySaver;
	}

	public boolean hasAccurateFix() {
		return accurateThisSession;
	}

	public void onListenersStarted() {
		onListenersStarted(inferStartReason());
	}

	public void onListenersStarted(String trigger) {
		flushSessionLogIfNeeded(false);
		forceAcquireOnce = false;
		acquireStartElapsed = SystemClock.elapsedRealtime();
		accurateThisSession = false;
		accurateAtElapsed = 0;
		acquireTimedOutAtElapsed = 0;
		sessionLateDuringDwell = false;
		sessionTrigger = trigger != null ? trigger : MgLiveLocationFixLog.TRIGGER_OTHER;
		sessionViewerBoosted = watcherWantsGps()
				|| MgLiveLocationFixLog.TRIGGER_VIEWED.equals(sessionTrigger);
		sessionLogged = false;
		sessionFinalAccuracy = -1f;
		sessionProvider = null;
		sessionFinalLocation = null;
		LiveLocationDebug.log("policy acquire start trigger=" + sessionTrigger
				+ " timeoutMs=" + describeTimeout()
				+ " alwaysOn=" + alwaysOn()
				+ " battery=" + batterySaver()
				+ " sleepMode=" + SharedConfig.mg_liveLocSleepMode
				+ " sleepMaxSec=" + SharedConfig.mg_liveLocSleepMaxSec
				+ " battMult=" + SharedConfig.mg_liveLocBatterySaverMultiplier);
	}

	/** Why GPS would turn on now (viewer boost / always-on / duty timer / other). */
	public String inferStartReason() {
		if (forceAcquireOnce) {
			return MgLiveLocationFixLog.TRIGGER_RESTART;
		}
		if (watcherWantsGps()) {
			return MgLiveLocationFixLog.TRIGGER_VIEWED;
		}
		if (alwaysOn()) {
			return MgLiveLocationFixLog.TRIGGER_ALWAYS_ON;
		}
		if (SystemClock.elapsedRealtime() >= sleepUntilElapsed) {
			return MgLiveLocationFixLog.TRIGGER_DUTY_TIMER;
		}
		return MgLiveLocationFixLog.TRIGGER_OTHER;
	}

	/** Policy hold after an accurate fix for the current session. */
	public long holdAfterFixMsForSession() {
		int dwellSec = SharedConfig.mg_liveLocDwellSec;
		if (dwellSec < 1) {
			dwellSec = DEFAULT_DWELL_SEC;
		}
		if (sessionViewerBoosted || watcherWantsGps()) {
			return 3L * dwellSec * 1000L;
		}
		return dwellSec * 1000L;
	}

	private int dwellSec() {
		int dwellSec = SharedConfig.mg_liveLocDwellSec;
		return dwellSec < 1 ? DEFAULT_DWELL_SEC : dwellSec;
	}

	/** @return true if this callback newly satisfied the accurate-fix requirement. */
	public boolean onLocation(Location location) {
		if (location == null || acquireStartElapsed == 0) {
			return false;
		}
		if (accurateThisSession) {
			noteFinalFix(location);
			return false;
		}
		if (!isSessionAccurate(location)) {
			return false;
		}
		accurateThisSession = true;
		accurateAtElapsed = SystemClock.elapsedRealtime();
		long ttfMs = Math.max(0, accurateAtElapsed - acquireStartElapsed);
		sessionLateDuringDwell = acquireTimedOutAtElapsed > 0;
		recordTtff(ttfMs);
		noteFinalFix(location);
		// Always-on never stops via duty cycle — log the fix immediately.
		if (alwaysOn()) {
			logSessionNow(false);
		}
		LiveLocationDebug.log("policy accurate ttfMs=" + ttfMs
				+ " lateDuringDwell=" + sessionLateDuringDwell
				+ " trigger=" + sessionTrigger
				+ " holdMs=" + holdAfterFixMsForSession()
				+ " " + LiveLocationDebug.fixSummary(location));
		return true;
	}

	private void noteFinalFix(Location location) {
		if (location == null) {
			return;
		}
		if (sessionFinalLocation == null) {
			sessionFinalLocation = new Location(location);
		} else {
			sessionFinalLocation.set(location);
		}
		if (location.hasAccuracy()) {
			sessionFinalAccuracy = location.getAccuracy();
		}
		if (location.getProvider() != null) {
			sessionProvider = location.getProvider();
		}
	}

	public boolean isSessionAccurate(Location location) {
		if (location == null || !location.hasAccuracy() || location.getAccuracy() > ACCURATE_METERS) {
			return false;
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
			long ageSec = (SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos()) / 1000000000L;
			if (ageSec < 0 || ageSec > MAX_FIX_AGE_SEC) {
				return false;
			}
			long fixElapsedMs = location.getElapsedRealtimeNanos() / 1000000L;
			if (fixElapsedMs + 500 < acquireStartElapsed) {
				return false;
			}
		}
		return true;
	}

	public boolean shouldStartGps(boolean sharing, boolean started) {
		if (!sharing || started) {
			return false;
		}
		// Viewer boost must override always-off so a fresh fix can be sent.
		if (watcherWantsGps()) {
			return true;
		}
		// After kill/update: one forced acquire even in always-off / mid-sleep.
		if (forceAcquireOnce) {
			return true;
		}
		if (alwaysOff()) {
			return false;
		}
		if (alwaysOn()) {
			return true;
		}
		return SystemClock.elapsedRealtime() >= sleepUntilElapsed;
	}

	public boolean shouldStopGps(boolean sharing, boolean started) {
		if (!started || !sharing || alwaysOn()) {
			return false;
		}
		long now = SystemClock.elapsedRealtime();
		// Hold GPS for viewer boost even when baseline policy is always-off.
		if (now < watcherHoldUntilElapsed && SharedConfig.mg_liveLocViewerBoost && !viewerBoostBlockedByBatterySaver()) {
			return false;
		}
		if (alwaysOff()) {
			return true;
		}
		if (!accurateThisSession) {
			Long timeout = computeAcquireTimeoutMs();
			if (timeout != null && now - acquireStartElapsed >= timeout) {
				if (acquireTimedOutAtElapsed == 0) {
					acquireTimedOutAtElapsed = now;
					LiveLocationDebug.log("policy acquire timed out; grace dwell "
							+ dwellSec() + "s for late fix");
				}
				// Keep GPS for the “Keep GPS on after a fix” duration after timeout.
				long graceEnd = acquireTimedOutAtElapsed + dwellSec() * 1000L;
				return now >= graceEnd;
			}
			return false;
		}
		long dwellEnd = accurateAtElapsed + SharedConfig.mg_liveLocDwellSec * 1000L;
		return now >= dwellEnd;
	}

	public void onStoppedForSleepOrTimeout(boolean sharing) {
		if (!sharing) {
			reset();
			return;
		}
		long now = SystemClock.elapsedRealtime();
		boolean timedOut = !accurateThisSession;
		logSessionNow(timedOut);
		lastAcquireTimedOut = timedOut;
		long sleepMs = sleepMs();
		if (timedOut) {
			sleepMs = sleepMs + sleepMs / 2;
		}
		sleepUntilElapsed = now + sleepMs;
		LiveLocationDebug.log("policy gps off timedOut=" + timedOut
				+ " lateDuringDwell=" + sessionLateDuringDwell
				+ " trigger=" + sessionTrigger
				+ " sleepMs=" + sleepMs
				+ " nextOnInMs=" + sleepMs);
		accurateThisSession = false;
		accurateAtElapsed = 0;
		acquireStartElapsed = 0;
		acquireTimedOutAtElapsed = 0;
		clearSessionLogState();
	}

	private void logSessionNow(boolean abandoned) {
		if (sessionLogged || (acquireStartElapsed == 0 && !accurateThisSession)) {
			return;
		}
		long now = SystemClock.elapsedRealtime();
		long ttfMs;
		if (accurateThisSession && accurateAtElapsed > 0) {
			ttfMs = Math.max(0, accurateAtElapsed - acquireStartElapsed);
		} else if (acquireStartElapsed > 0) {
			ttfMs = Math.max(0, now - acquireStartElapsed);
		} else {
			return;
		}
		long holdMs = abandoned || alwaysOn() ? 0 : holdAfterFixMsForSession();
		boolean sync = syncCommitNextSessionLog;
		syncCommitNextSessionLog = false;
		MgLiveLocationFixLog.logSession(ttfMs, sessionFinalLocation, sessionTrigger, holdMs,
				abandoned, sessionLateDuringDwell && !abandoned, sync);
		sessionLogged = true;
	}

	private void flushSessionLogIfNeeded(boolean abandoned) {
		if (!sessionLogged && acquireStartElapsed > 0) {
			logSessionNow(abandoned || !accurateThisSession);
		}
	}

	/**
	 * Best-effort Fix Log flush when the process is dying (update / kill).
	 * Uses commit() so the entry survives package replace.
	 */
	public void flushSessionForProcessDeath() {
		if (sessionLogged || acquireStartElapsed == 0) {
			return;
		}
		syncCommitNextSessionLog = true;
		flushSessionLogIfNeeded(false);
		LiveLocationDebug.log("policy flushed session for process death trigger=" + sessionTrigger
				+ " accurate=" + accurateThisSession);
	}

	private void clearSessionLogState() {
		sessionLogged = false;
		sessionFinalAccuracy = -1f;
		sessionProvider = null;
		sessionFinalLocation = null;
		sessionViewerBoosted = false;
		sessionLateDuringDwell = false;
		sessionTrigger = MgLiveLocationFixLog.TRIGGER_OTHER;
	}

	public void onWatcher() {
		if (!SharedConfig.mg_liveLocViewerBoost) {
			LiveLocationDebug.log("viewerSignal ignored (disabled)");
			return;
		}
		if (viewerBoostBlockedByBatterySaver()) {
			LiveLocationDebug.log("viewerSignal ignored (battery saver)");
			return;
		}
		sessionViewerBoosted = true;
		if (alwaysOn()) {
			LiveLocationDebug.log("viewerSignal always-on (gps stays up)");
			sleepUntilElapsed = 0;
			return;
		}
		long holdMs = 3L * SharedConfig.mg_liveLocDwellSec * 1000L;
		watcherHoldUntilElapsed = SystemClock.elapsedRealtime() + holdMs;
		sleepUntilElapsed = 0;
		lastAcquireTimedOut = false;
		LiveLocationDebug.log("viewerSignal holdMs=" + holdMs
				+ " alwaysOff=" + alwaysOff());
	}

	public boolean watcherWantsGps() {
		return SharedConfig.mg_liveLocViewerBoost
				&& !viewerBoostBlockedByBatterySaver()
				&& SystemClock.elapsedRealtime() < watcherHoldUntilElapsed;
	}

	/**
	 * Base wait from mode + max-wait slider, then optional battery-saver multiplier.
	 * {@link #alwaysOn()} sessions do not sleep via the duty cycle.
	 */
	public long sleepMs() {
		int maxSec = clampSleepMaxSec(SharedConfig.mg_liveLocSleepMaxSec);
		long baseMs = baseSleepMs(maxSec);
		int mult = SharedConfig.mg_liveLocBatterySaverMultiplier;
		if (batterySaver() && mult > 1) {
			baseMs *= mult;
		}
		return Math.max(0, baseMs);
	}

	private long baseSleepMs(int maxSec) {
		int mode = SharedConfig.mg_liveLocSleepMode;
		if (mode == SLEEP_MODE_LINEAR) {
			return linearSleepMs(maxSec);
		}
		if (mode == SLEEP_MODE_LOG) {
			return logSleepMs(maxSec);
		}
		return maxSec * 1000L;
	}

	/** Always-off upper as linear/log time scale; inf. → 7 days. */
	private static int waitTimeAnchorSec() {
		int anchorSec = MgLiveLocationThresholds.secondsAt(SharedConfig.mg_liveLocAlwaysOffUpperIndex);
		if (MgLiveLocationThresholds.isInfinite(anchorSec) || anchorSec <= 0) {
			return LINEAR_INFINITE_ANCHOR_SEC;
		}
		return anchorSec;
	}

	/** Finite remaining for wait extrapolation; infinite shares → 10 days. */
	private int waitRemainingSec() {
		int remaining = Math.max(0, minShareRemainingSec);
		if (MgLiveLocationThresholds.isInfinite(remaining)) {
			return INFINITE_SHARE_REMAINING_SEC;
		}
		return remaining;
	}

	private long linearSleepMs(int maxSec) {
		int anchorSec = waitTimeAnchorSec();
		int remaining = waitRemainingSec();
		double fraction = Math.min(1.0, remaining / (double) anchorSec);
		return Math.round(fraction * maxSec * 1000.0);
	}

	private long logSleepMs(int maxSec) {
		int anchorSec = waitTimeAnchorSec();
		double remainingMin = waitRemainingSec() / 60.0;
		double anchorMin = anchorSec / 60.0;
		double denom = Math.log(anchorMin + 1.0);
		if (denom <= 0) {
			return 0;
		}
		double fraction = Math.log(remainingMin + 1.0) / denom;
		if (fraction < 0) {
			fraction = 0;
		}
		if (fraction > 1) {
			fraction = 1;
		}
		return Math.round(fraction * maxSec * 1000.0);
	}

	public static Long computeAcquireTimeoutMs() {
		int capSec = SharedConfig.mg_liveLocMaxAcquireSec;
		if (capSec == 0) {
			return null;
		}
		if (capSec == MAX_ACQUIRE_FROM_HISTORY) {
			return timeoutFromTtffHistoryMs();
		}
		if (capSec > 0) {
			return Math.max(capSec * 1000L, 1000L);
		}
		return null;
	}

	/** μ + σ of the last {@link #TTFF_KEEP} valid TTFFs, or null if fewer samples. */
	public static Long timeoutFromTtffHistoryMs() {
		ArrayList<long[]> samples = loadTtffSamples();
		if (samples.size() < TTFF_MIN_SAMPLES) {
			return null;
		}
		int n = Math.min(TTFF_KEEP, samples.size());
		double sum = 0;
		for (int i = samples.size() - n; i < samples.size(); i++) {
			sum += samples.get(i)[1];
		}
		double mean = sum / n;
		double varSum = 0;
		for (int i = samples.size() - n; i < samples.size(); i++) {
			double d = samples.get(i)[1] - mean;
			varSum += d * d;
		}
		double std = Math.sqrt(varSum / n);
		long timeout = Math.round(mean + std);
		return Math.max(timeout, 1000L);
	}

	private static String describeTimeout() {
		int capSec = SharedConfig.mg_liveLocMaxAcquireSec;
		if (capSec == 0) {
			return "none";
		}
		if (capSec == MAX_ACQUIRE_FROM_HISTORY) {
			Long t = timeoutFromTtffHistoryMs();
			return t == null ? "history(<" + TTFF_MIN_SAMPLES + " samples)" : "history=" + t;
		}
		return Long.toString(capSec * 1000L);
	}

	public static void recordTtff(long ttfMs) {
		if (ttfMs < 0) {
			return;
		}
		ArrayList<long[]> samples = loadTtffSamples();
		samples.add(new long[]{System.currentTimeMillis(), ttfMs});
		pruneTtff(samples);
		saveTtffSamples(samples);
	}

	private static ArrayList<long[]> loadTtffSamples() {
		ArrayList<long[]> out = new ArrayList<>();
		SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
		String raw = prefs.getString(PREF_TTFF, "");
		if (raw == null || raw.length() == 0) {
			return out;
		}
		for (String part : raw.split(";")) {
			int c = part.indexOf(':');
			if (c <= 0) {
				continue;
			}
			try {
				long wall = Long.parseLong(part.substring(0, c));
				long ttf = Long.parseLong(part.substring(c + 1));
				out.add(new long[]{wall, ttf});
			} catch (NumberFormatException ignored) {
			}
		}
		pruneTtff(out);
		return out;
	}

	/** Keep only the newest {@link #TTFF_KEEP} valid samples. */
	private static void pruneTtff(ArrayList<long[]> samples) {
		while (samples.size() > TTFF_KEEP) {
			samples.remove(0);
		}
	}

	private static void saveTtffSamples(ArrayList<long[]> samples) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < samples.size(); i++) {
			if (i > 0) {
				sb.append(';');
			}
			sb.append(samples.get(i)[0]).append(':').append(samples.get(i)[1]);
		}
		ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE)
				.edit()
				.putString(PREF_TTFF, sb.toString())
				.apply();
	}

	/** Compact duration for settings labels, e.g. {@code 90s}, {@code 5min}, {@code 1h30min}. */
	public static String formatDurationSec(int sec) {
		if (sec <= 0) {
			return "0";
		}
		int hours = sec / 3600;
		int minutes = (sec % 3600) / 60;
		int seconds = sec % 60;
		StringBuilder sb = new StringBuilder();
		if (hours > 0) {
			sb.append(hours).append('h');
		}
		if (minutes > 0) {
			sb.append(minutes).append("min");
		}
		if (seconds > 0 || sb.length() == 0) {
			sb.append(seconds).append('s');
		}
		return sb.toString();
	}

	public static String formatBatterySaverMultiplier(int multiplier) {
		if (multiplier == BATTERY_SAVER_OFF) {
			return org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.MercurygramLiveLocBatterySaverDisable);
		}
		if (multiplier == 1) {
			return org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.MercurygramLiveLocBatterySaverNormal);
		}
		return org.telegram.messenger.LocaleController.formatString("MercurygramLiveLocBatterySaverMult",
				org.telegram.messenger.R.string.MercurygramLiveLocBatterySaverMult, multiplier);
	}

	public static String formatSleepMode(int mode) {
		switch (mode) {
			case SLEEP_MODE_LINEAR:
				return org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.MercurygramLiveLocSleepModeLinear);
			case SLEEP_MODE_LOG:
				return org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.MercurygramLiveLocSleepModeLog);
			default:
				return org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.MercurygramLiveLocSleepModeFixed);
		}
	}

	public static String formatMaxAcquire(int sec) {
		if (sec == MAX_ACQUIRE_FROM_HISTORY) {
			return org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.MercurygramLiveLocMaxAcquireFromHistory);
		}
		if (sec <= 0) {
			return org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.MercurygramLiveLocMaxAcquireOff);
		}
		return formatDurationSec(sec);
	}
}
