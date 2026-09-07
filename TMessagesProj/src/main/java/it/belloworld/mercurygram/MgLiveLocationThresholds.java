package it.belloworld.mercurygram;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

/**
 * Discrete remaining-time thresholds for the dual GPS policy slider.
 *
 * <p>Step table v2 replaces the long-duration tail
 * {@code [24h, 30h, 50h, 80h, inf]} with
 * {@code [24h, 32h, 48h, 96h, 168h, inf]}. Saved indices from v1 are remapped
 * via {@link #migrateIndexFromV1(int)} (old seconds → nearest new step) so
 * e.g. old inf index does not land on 168h.
 */
public final class MgLiveLocationThresholds {

	public static final int INFINITE = 0x7FFFFFFF;

	/** Pref key / version for the discrete step table (see class javadoc). */
	public static final int STEPS_VERSION = 2;
	public static final String PREF_STEPS_VERSION = "mg_liveLocThresholdStepsVersion";

	private static final int[] VALUES_SEC = {
			0,
			5 * 60,
			10 * 60,
			15 * 60,
			20 * 60,
			30 * 60,
			40 * 60,
			60 * 60,
			2 * 60 * 60,
			3 * 60 * 60,
			4 * 60 * 60,
			6 * 60 * 60,
			8 * 60 * 60,
			12 * 60 * 60,
			18 * 60 * 60,
			24 * 60 * 60,
			32 * 60 * 60,
			48 * 60 * 60,
			96 * 60 * 60,
			168 * 60 * 60,
			INFINITE,
	};

	/** v1 table used only to remap saved indices → seconds before nearest-new. */
	private static final int[] LEGACY_V1_VALUES_SEC = {
			0,
			5 * 60,
			10 * 60,
			15 * 60,
			20 * 60,
			30 * 60,
			40 * 60,
			60 * 60,
			2 * 60 * 60,
			3 * 60 * 60,
			4 * 60 * 60,
			6 * 60 * 60,
			8 * 60 * 60,
			12 * 60 * 60,
			18 * 60 * 60,
			24 * 60 * 60,
			30 * 60 * 60,
			50 * 60 * 60,
			80 * 60 * 60,
			INFINITE,
	};

	/** Coarse axis ticks drawn under the dual slider. */
	public static final int[] AXIS_TICK_SEC = {
			0,
			30 * 60,
			4 * 60 * 60,
			24 * 60 * 60,
			INFINITE,
	};

	public static final int DEFAULT_LOWER_INDEX = indexOfSeconds(30 * 60);
	public static final int DEFAULT_UPPER_INDEX = VALUES_SEC.length - 1;

	private MgLiveLocationThresholds() {
	}

	public static int count() {
		return VALUES_SEC.length;
	}

	public static int secondsAt(int index) {
		if (index < 0) {
			index = 0;
		}
		if (index >= VALUES_SEC.length) {
			index = VALUES_SEC.length - 1;
		}
		return VALUES_SEC[index];
	}

	public static int indexOfSeconds(int seconds) {
		if (seconds >= INFINITE) {
			return VALUES_SEC.length - 1;
		}
		int best = 0;
		int bestDelta = Integer.MAX_VALUE;
		for (int a = 0; a < VALUES_SEC.length; a++) {
			if (VALUES_SEC[a] == INFINITE) {
				continue;
			}
			int delta = Math.abs(VALUES_SEC[a] - seconds);
			if (delta < bestDelta) {
				bestDelta = delta;
				best = a;
			}
		}
		return best;
	}

	public static int clampIndex(int index) {
		if (index < 0) {
			return 0;
		}
		if (index >= VALUES_SEC.length) {
			return VALUES_SEC.length - 1;
		}
		return index;
	}

	/**
	 * Remap a v1 saved index through the old seconds table onto the nearest v2 step.
	 * Indices that already point at shared early steps (≤24h) stay stable.
	 */
	public static int migrateIndexFromV1(int oldIndex) {
		if (oldIndex < 0) {
			oldIndex = 0;
		}
		if (oldIndex >= LEGACY_V1_VALUES_SEC.length) {
			oldIndex = LEGACY_V1_VALUES_SEC.length - 1;
		}
		int legacySec = LEGACY_V1_VALUES_SEC[oldIndex];
		if (legacySec >= INFINITE) {
			return VALUES_SEC.length - 1;
		}
		return indexOfSeconds(legacySec);
	}

	public static boolean isInfinite(int seconds) {
		return seconds >= INFINITE;
	}

	/**
	 * Compact snap / policy labels: {@code 24h}, {@code 32h}, {@code 2d}, {@code 4d},
	 * {@code 7d}, or localized infinite / zero.
	 */
	public static String formatSeconds(int seconds) {
		if (isInfinite(seconds)) {
			return LocaleController.getString(R.string.MercurygramLiveLocThresholdInfinite);
		}
		if (seconds <= 0) {
			return LocaleController.getString(R.string.MercurygramLiveLocThresholdZero);
		}
		return formatSnapLabel(seconds);
	}

	/** Axis tick labels: {@code 0min}, {@code 30min}, {@code 4h}, {@code 1d}, {@code inf.}. */
	public static String formatAxisTick(int seconds) {
		if (isInfinite(seconds)) {
			return LocaleController.getString(R.string.MercurygramLiveLocThresholdInfShort);
		}
		if (seconds <= 0) {
			return "0min";
		}
		if (seconds == 24 * 60 * 60) {
			return "1d";
		}
		return formatSnapLabel(seconds);
	}

	/**
	 * Snap display: hours below 48h, exact day multiples as {@code Nd} from 2d up
	 * (so 168h → {@code 7d}, 48h → {@code 2d}).
	 */
	public static String formatSnapLabel(int seconds) {
		if (isInfinite(seconds)) {
			return LocaleController.getString(R.string.MercurygramLiveLocThresholdInfShort);
		}
		if (seconds >= 2 * 24 * 60 * 60 && seconds % (24 * 60 * 60) == 0) {
			return (seconds / (24 * 60 * 60)) + "d";
		}
		return MgLiveLocationPolicy.formatDurationSec(seconds);
	}

	public static String buildPolicyDescription(int lowerIndex, int upperIndex) {
		int lowerSec = secondsAt(lowerIndex);
		int upperSec = secondsAt(upperIndex);
		StringBuilder sb = new StringBuilder();
		if (lowerSec > 0 && !isInfinite(lowerSec)) {
			sb.append(LocaleController.formatString("MercurygramLiveLocPolicyAlwaysOn",
					R.string.MercurygramLiveLocPolicyAlwaysOn, formatSeconds(lowerSec)));
		} else if (isInfinite(lowerSec)) {
			sb.append(LocaleController.getString(R.string.MercurygramLiveLocPolicyAlwaysOnAll));
		}
		if (!isInfinite(upperSec)) {
			if (sb.length() > 0) {
				sb.append('\n');
			}
			sb.append(LocaleController.formatString("MercurygramLiveLocPolicyAlwaysOff",
					R.string.MercurygramLiveLocPolicyAlwaysOff, formatSeconds(upperSec)));
		}
		boolean overlap = lowerIndex >= upperIndex;
		if (lowerSec <= 0 && isInfinite(upperSec)) {
			sb.append(LocaleController.getString(R.string.MercurygramLiveLocPolicyAlwaysDutyCycle));
		} else if (!overlap && !(lowerSec <= 0 && isInfinite(upperSec))) {
			if (sb.length() > 0) {
				sb.append('\n');
			}
			sb.append(LocaleController.getString(R.string.MercurygramLiveLocPolicyDutyCycle));
		}
		return sb.toString();
	}

	public static int migrateLegacyAlwaysOnMode(int mode) {
		switch (mode) {
			case MgLiveLocationPolicy.ALWAYS_ALL:
				return VALUES_SEC.length - 1;
			case MgLiveLocationPolicy.ALWAYS_8H:
				return indexOfSeconds(8 * 60 * 60);
			case MgLiveLocationPolicy.ALWAYS_1H:
				return indexOfSeconds(60 * 60);
			case MgLiveLocationPolicy.ALWAYS_15M:
				return indexOfSeconds(15 * 60);
			default:
				return 0;
		}
	}
}
