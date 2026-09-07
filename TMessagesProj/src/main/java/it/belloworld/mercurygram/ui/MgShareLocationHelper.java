package it.belloworld.mercurygram.ui;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberPicker;
import org.telegram.ui.Components.ShareLocationDrawable;

import java.util.Calendar;

public final class MgShareLocationHelper {

	public static final int FOREVER_PERIOD = 0x7FFFFFFF;
	public static final int MIN_CUSTOM_SEC = 60;
	/** Max finite duration the user may target (UI picker). Chained via auto-extend. */
	public static final int MAX_CUSTOM_SEC = 99 * 24 * 60 * 60;
	/** Telegram finite live_period max per RPC: 60..86400, or forever. */
	public static final int MAX_API_PERIOD_SEC = 24 * 60 * 60;
	/** Max increase of period in a single editMessage (must not exceed current by more than 1 day). */
	public static final int MAX_EXTEND_ADD_SEC = 24 * 60 * 60;
	/** Extended expiration must remain within the next 90 days. */
	public static final int MAX_EXPIRE_FROM_NOW_SEC = 90 * 24 * 60 * 60;

	private static final int IDX_15M = 0;
	private static final int IDX_1H = 1;
	private static final int IDX_8H = 2;
	private static final int IDX_FOREVER = 3;
	private static final int IDX_CUSTOM = 4;
	private static final int IDX_UNTIL = 5;

	private MgShareLocationHelper() {
	}

	public static void openSharePeriodDialog(Activity activity, TLRPC.User user, MessagesStorage.IntCallback callback, Theme.ResourcesProvider resourcesProvider) {
		openSharePeriodDialog(activity, false, user, callback, resourcesProvider);
	}

	public static void openSharePeriodDialog(Activity activity, boolean expand, TLRPC.User user, MessagesStorage.IntCallback callback, Theme.ResourcesProvider resourcesProvider) {
		if (activity == null) {
			return;
		}

		final int[] periods = new int[]{
				15 * 60,
				60 * 60,
				8 * 60 * 60,
				FOREVER_PERIOD,
				clampCustom(SharedConfig.mg_liveLocLastCustomSharePeriodSec),
				0, // share-until placeholder; resolved on confirm
		};
		final int[] selectedIndex = {indexForPeriod(SharedConfig.mg_liveLocDefaultSharePeriodSec)};
		if (selectedIndex[0] == IDX_CUSTOM) {
			periods[IDX_CUSTOM] = clampCustom(SharedConfig.mg_liveLocDefaultSharePeriodSec);
		}
		final int[] untilUnix = {0};

		LinearLayout linearLayout = new LinearLayout(activity);
		linearLayout.setOrientation(LinearLayout.VERTICAL);
		linearLayout.setPadding(0, 0, 0, AndroidUtilities.dp(4));

		TextView titleTextView = new TextView(activity);
		if (expand) {
			titleTextView.setText(LocaleController.getString(R.string.LiveLocationAlertExpandMessage));
		} else if (user != null) {
			titleTextView.setText(LocaleController.formatString(R.string.LiveLocationAlertPrivate, UserObject.getFirstName(user)));
		} else {
			titleTextView.setText(LocaleController.getString(R.string.LiveLocationAlertGroup));
		}
		int textColor = resourcesProvider != null
				? resourcesProvider.getColorOrDefault(Theme.key_dialogTextBlack)
				: Theme.getColor(Theme.key_dialogTextBlack);
		titleTextView.setTextColor(textColor);
		titleTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
		titleTextView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP);
		linearLayout.addView(titleTextView, LayoutHelper.createLinear(
				LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
				(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP,
				24, (expand ? 4 : 0), 24, 8));

		Runnable refreshLabels = () -> {
			for (int i = 0; i < linearLayout.getChildCount(); i++) {
				View child = linearLayout.getChildAt(i);
				if (!(child instanceof RadioColorCell)) {
					continue;
				}
				Object tag = child.getTag();
				if (!(tag instanceof Integer)) {
					continue;
				}
				int index = (Integer) tag;
				((RadioColorCell) child).setTextAndValue(labelForIndex(index, periods[IDX_CUSTOM], untilUnix[0]), selectedIndex[0] == index);
			}
		};

		for (int a = 0; a <= IDX_UNTIL; a++) {
			final int index = a;
			RadioColorCell cell = new RadioColorCell(activity, resourcesProvider);
			cell.heightDp = 42;
			cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
			cell.setTag(index);
			cell.setCheckColor(
					resourcesProvider != null ? resourcesProvider.getColorOrDefault(Theme.key_radioBackground) : Theme.getColor(Theme.key_radioBackground),
					resourcesProvider != null ? resourcesProvider.getColorOrDefault(Theme.key_dialogRadioBackgroundChecked) : Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
			cell.setTextAndValue(labelForIndex(index, periods[IDX_CUSTOM], untilUnix[0]), selectedIndex[0] == index);
			linearLayout.addView(cell);
			cell.setOnClickListener(v -> {
				if (index == IDX_CUSTOM) {
					openCustomPeriodPicker(activity, periods[IDX_CUSTOM], resourcesProvider, customSec -> {
						periods[IDX_CUSTOM] = customSec;
						selectedIndex[0] = IDX_CUSTOM;
						untilUnix[0] = 0;
						refreshLabels.run();
						for (int i = 0; i < linearLayout.getChildCount(); i++) {
							View child = linearLayout.getChildAt(i);
							if (child instanceof RadioColorCell) {
								((RadioColorCell) child).setChecked(child == v, true);
							}
						}
					});
					return;
				}
				if (index == IDX_UNTIL) {
					openShareUntilPicker(activity, resourcesProvider, unix -> {
						untilUnix[0] = unix;
						selectedIndex[0] = IDX_UNTIL;
						refreshLabels.run();
						for (int i = 0; i < linearLayout.getChildCount(); i++) {
							View child = linearLayout.getChildAt(i);
							if (child instanceof RadioColorCell) {
								((RadioColorCell) child).setChecked(child == v, true);
							}
						}
					});
					return;
				}
				selectedIndex[0] = index;
				untilUnix[0] = 0;
				for (int i = 0; i < linearLayout.getChildCount(); i++) {
					View child = linearLayout.getChildAt(i);
					if (child instanceof RadioColorCell) {
						((RadioColorCell) child).setChecked(child == v, true);
					}
				}
			});
		}

		AlertDialog.Builder builder = new AlertDialog.Builder(activity, resourcesProvider);
		if (expand) {
			builder.setTitle(LocaleController.getString(R.string.LiveLocationAlertExpandTitle));
		} else {
			builder.setTopImage(new ShareLocationDrawable(activity, 0),
					resourcesProvider != null ? resourcesProvider.getColorOrDefault(Theme.key_dialogTopBackground) : Theme.getColor(Theme.key_dialogTopBackground));
		}
		builder.setView(linearLayout);
		builder.setPositiveButton(LocaleController.getString(R.string.ShareFile), (dialog, which) -> {
			int index = selectedIndex[0];
			if (index == IDX_UNTIL) {
				if (untilUnix[0] <= 0) {
					Toast.makeText(activity, LocaleController.getString(R.string.MercurygramLiveLocShareUntilNeedTime), Toast.LENGTH_SHORT).show();
					return;
				}
				int nowSec = (int) (System.currentTimeMillis() / 1000L);
				int clampedEnd = clampTargetEndSec(nowSec, untilUnix[0]);
				if (clampedEnd != untilUnix[0]) {
					Toast.makeText(activity, LocaleController.getString(R.string.MercurygramLiveLocTargetClamped), Toast.LENGTH_LONG).show();
				}
				callback.run(encodeShareUntil(clampedEnd));
				return;
			}
			int period = periods[index];
			if (period != FOREVER_PERIOD && period > MAX_EXPIRE_FROM_NOW_SEC) {
				period = MAX_EXPIRE_FROM_NOW_SEC;
				Toast.makeText(activity, LocaleController.getString(R.string.MercurygramLiveLocTargetClamped), Toast.LENGTH_LONG).show();
			}
			rememberPeriod(period, index == IDX_CUSTOM || !isPresetPeriod(period));
			callback.run(period);
		});
		builder.setNeutralButton(LocaleController.getString(R.string.Cancel), null);
		builder.show();
	}

	/**
	 * Encode absolute unix end so expand flow can distinguish share-until from relative add.
	 * Values are &lt; -1 (never overlaps FOREVER or normal periods).
	 */
	public static int encodeShareUntil(int absoluteEndUnix) {
		if (absoluteEndUnix <= 0) {
			return 0;
		}
		return -absoluteEndUnix;
	}

	public static boolean isShareUntilEncoded(int value) {
		return value < -1;
	}

	public static int decodeShareUntil(int encoded) {
		return isShareUntilEncoded(encoded) ? -encoded : 0;
	}

	public static void openShareUntilPicker(Context context, Theme.ResourcesProvider resourcesProvider, MessagesStorage.IntCallback absoluteUnixCallback) {
		if (context == null) {
			return;
		}
		Calendar cal = Calendar.getInstance();
		cal.add(Calendar.HOUR_OF_DAY, 1);
		cal.set(Calendar.SECOND, 0);
		cal.set(Calendar.MILLISECOND, 0);

		DatePickerDialog dateDialog = new DatePickerDialog(context, (view, year, month, dayOfMonth) -> {
			cal.set(Calendar.YEAR, year);
			cal.set(Calendar.MONTH, month);
			cal.set(Calendar.DAY_OF_MONTH, dayOfMonth);
			TimePickerDialog timeDialog = new TimePickerDialog(context, (v, hourOfDay, minute) -> {
				cal.set(Calendar.HOUR_OF_DAY, hourOfDay);
				cal.set(Calendar.MINUTE, minute);
				cal.set(Calendar.SECOND, 0);
				int unix = (int) (cal.getTimeInMillis() / 1000L);
				int nowSec = (int) (System.currentTimeMillis() / 1000L);
				if (unix <= nowSec) {
					Toast.makeText(context, LocaleController.getString(R.string.MercurygramLiveLocShareUntilPast), Toast.LENGTH_SHORT).show();
					return;
				}
				int maxEnd = nowSec + MAX_EXPIRE_FROM_NOW_SEC;
				if (unix > maxEnd) {
					Toast.makeText(context, LocaleController.getString(R.string.MercurygramLiveLocShareUntilBeyondMax), Toast.LENGTH_LONG).show();
					unix = maxEnd;
				}
				absoluteUnixCallback.run(unix);
			}, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true);
			timeDialog.setTitle(LocaleController.getString(R.string.MercurygramLiveLocShareUntil));
			timeDialog.show();
		}, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
		dateDialog.setTitle(LocaleController.getString(R.string.MercurygramLiveLocShareUntil));
		dateDialog.getDatePicker().setMinDate(System.currentTimeMillis() - 1000L);
		dateDialog.getDatePicker().setMaxDate(System.currentTimeMillis() + (long) MAX_EXPIRE_FROM_NOW_SEC * 1000L);
		dateDialog.show();
	}

	public static void openCustomPeriodPicker(Context context, int initialSec, Theme.ResourcesProvider resourcesProvider, MessagesStorage.IntCallback callback) {
		if (context == null) {
			return;
		}
		int sec = clampCustom(initialSec);
		final int[] days = {sec / 86400};
		final int[] hours = {(sec % 86400) / 3600};
		final int[] minutes = {(sec % 3600) / 60};
		if (days[0] == 0 && hours[0] == 0 && minutes[0] == 0) {
			minutes[0] = 1;
		}

		LinearLayout container = new LinearLayout(context);
		container.setOrientation(LinearLayout.HORIZONTAL);
		container.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), 0);

		NumberPicker dayPicker = new NumberPicker(context, resourcesProvider);
		dayPicker.setMinValue(0);
		dayPicker.setMaxValue(99);
		dayPicker.setValue(Math.min(99, days[0]));
		dayPicker.setFormatter(value -> LocaleController.formatPluralString("Days", value));
		dayPicker.setAllItemsCount(100);
		dayPicker.setItemCount(5);

		NumberPicker hourPicker = new NumberPicker(context, resourcesProvider);
		hourPicker.setMinValue(0);
		hourPicker.setMaxValue(23);
		hourPicker.setValue(hours[0]);
		hourPicker.setFormatter(value -> LocaleController.formatPluralString("Hours", value));
		hourPicker.setWrapSelectorWheel(true);
		hourPicker.setAllItemsCount(24);
		hourPicker.setItemCount(5);

		NumberPicker minutePicker = new NumberPicker(context, resourcesProvider);
		minutePicker.setMinValue(0);
		minutePicker.setMaxValue(59);
		minutePicker.setValue(minutes[0]);
		minutePicker.setFormatter(value -> LocaleController.formatPluralString("Minutes", value));
		minutePicker.setWrapSelectorWheel(true);
		minutePicker.setAllItemsCount(60);
		minutePicker.setItemCount(5);

		Runnable clampMin = () -> {
			int total = dayPicker.getValue() * 86400 + hourPicker.getValue() * 3600 + minutePicker.getValue() * 60;
			if (total < MIN_CUSTOM_SEC) {
				minutePicker.setValue(1);
			} else if (total > MAX_CUSTOM_SEC) {
				dayPicker.setValue(99);
				hourPicker.setValue(0);
				minutePicker.setValue(0);
			}
		};
		NumberPicker.OnValueChangeListener listener = (picker, oldVal, newVal) -> clampMin.run();
		dayPicker.setOnValueChangedListener(listener);
		hourPicker.setOnValueChangedListener(listener);
		minutePicker.setOnValueChangedListener(listener);

		container.addView(dayPicker, LayoutHelper.createLinear(0, AndroidUtilities.dp(NumberPicker.DEFAULT_SIZE_PER_COUNT) * 5, 1f));
		container.addView(hourPicker, LayoutHelper.createLinear(0, AndroidUtilities.dp(NumberPicker.DEFAULT_SIZE_PER_COUNT) * 5, 1f));
		container.addView(minutePicker, LayoutHelper.createLinear(0, AndroidUtilities.dp(NumberPicker.DEFAULT_SIZE_PER_COUNT) * 5, 1f));

		AlertDialog dialog = new AlertDialog.Builder(context, resourcesProvider)
				.setTitle(LocaleController.getString(R.string.MercurygramLiveLocCustomPeriod))
				.setView(container)
				.setPositiveButton(LocaleController.getString(R.string.OK), (d, which) -> {
					int total = dayPicker.getValue() * 86400 + hourPicker.getValue() * 3600 + minutePicker.getValue() * 60;
					callback.run(clampCustom(total));
				})
				.setNegativeButton(LocaleController.getString(R.string.Cancel), null)
				.create();
		dialog.show();
	}

	public static boolean isPresetPeriod(int periodSec) {
		return periodSec == 15 * 60
				|| periodSec == 60 * 60
				|| periodSec == 8 * 60 * 60
				|| periodSec == FOREVER_PERIOD;
	}

	/** Clamp user custom/target duration for UI persistence (1 min … 99 days). */
	public static int clampCustom(int sec) {
		if (sec == FOREVER_PERIOD) {
			return FOREVER_PERIOD;
		}
		if (sec < MIN_CUSTOM_SEC) {
			return MIN_CUSTOM_SEC;
		}
		if (sec > MAX_CUSTOM_SEC) {
			return MAX_CUSTOM_SEC;
		}
		return sec;
	}

	/** Period value safe to send in a single live-location RPC. */
	public static int periodForApiStart(int targetSec) {
		if (targetSec == FOREVER_PERIOD) {
			return FOREVER_PERIOD;
		}
		if (targetSec <= 0) {
			return MIN_CUSTOM_SEC;
		}
		return Math.min(targetSec, MAX_API_PERIOD_SEC);
	}

	public static int clampTargetEndSec(int nowSec, long desiredEndSec) {
		long maxEnd = (long) nowSec + MAX_EXPIRE_FROM_NOW_SEC;
		long end = desiredEndSec;
		if (end > maxEnd) {
			end = maxEnd;
		}
		if (end <= nowSec) {
			end = nowSec + MIN_CUSTOM_SEC;
		}
		if (end > Integer.MAX_VALUE) {
			return Integer.MAX_VALUE;
		}
		return (int) end;
	}

	/**
	 * Clamp an extend addition so the server accepts it: +at most 1 day, and
	 * expiration stays within 90 days from now. Returns FOREVER_PERIOD unchanged.
	 */
	public static int clampExtendAddition(int currentPeriod, int messageDate, int nowSec, int addSec) {
		if (addSec == FOREVER_PERIOD) {
			return FOREVER_PERIOD;
		}
		if (currentPeriod == FOREVER_PERIOD || addSec <= 0) {
			return 0;
		}
		int add = Math.min(MAX_EXTEND_ADD_SEC, addSec);
		long maxPeriodByExpire = (long) nowSec + MAX_EXPIRE_FROM_NOW_SEC - (long) messageDate;
		if (maxPeriodByExpire <= currentPeriod) {
			return 0;
		}
		long maxAdd = Math.min((long) MAX_EXTEND_ADD_SEC, maxPeriodByExpire - currentPeriod);
		if (add > maxAdd) {
			add = (int) maxAdd;
		}
		return Math.max(0, add);
	}

	/** Safe int add that saturates instead of wrapping. */
	public static int saturatingAdd(int a, int b) {
		long sum = (long) a + (long) b;
		if (sum > Integer.MAX_VALUE) {
			return Integer.MAX_VALUE;
		}
		if (sum < Integer.MIN_VALUE) {
			return Integer.MIN_VALUE;
		}
		return (int) sum;
	}

	public static String formatPeriodLabel(int periodSec) {
		if (periodSec == FOREVER_PERIOD) {
			return LocaleController.getString(R.string.SendLiveLocationForever);
		}
		if (periodSec == 15 * 60) {
			return LocaleController.getString(R.string.SendLiveLocationFor15m);
		}
		if (periodSec == 60 * 60) {
			return LocaleController.getString(R.string.SendLiveLocationFor1h);
		}
		if (periodSec == 8 * 60 * 60) {
			return LocaleController.getString(R.string.SendLiveLocationFor8h);
		}
		return LocaleController.formatString("MercurygramLiveLocCustomPeriodValue",
				R.string.MercurygramLiveLocCustomPeriodValue,
				formatCustomDuration(periodSec));
	}

	public static String formatCustomDuration(int sec) {
		if (sec != FOREVER_PERIOD) {
			if (sec < MIN_CUSTOM_SEC) {
				sec = MIN_CUSTOM_SEC;
			} else if (sec > MAX_CUSTOM_SEC) {
				sec = MAX_CUSTOM_SEC;
			}
		}
		int days = sec / 86400;
		int hours = (sec % 86400) / 3600;
		int minutes = (sec % 3600) / 60;
		StringBuilder sb = new StringBuilder();
		if (days > 0) {
			sb.append(LocaleController.formatPluralString("Days", days));
		}
		if (hours > 0) {
			if (sb.length() > 0) {
				sb.append(' ');
			}
			sb.append(LocaleController.formatPluralString("Hours", hours));
		}
		if (minutes > 0 || sb.length() == 0) {
			if (sb.length() > 0) {
				sb.append(' ');
			}
			sb.append(LocaleController.formatPluralString("Minutes", minutes));
		}
		return sb.toString();
	}

	public static String formatUntilLabel(int unixSec) {
		if (unixSec <= 0) {
			return LocaleController.getString(R.string.MercurygramLiveLocShareUntil);
		}
		long ms = ((long) unixSec) * 1000L;
		String when = LocaleController.getInstance().getFormatterDayMonth().format(new java.util.Date(ms))
				+ " "
				+ LocaleController.getInstance().getFormatterDay().format(new java.util.Date(ms));
		return LocaleController.formatString("MercurygramLiveLocShareUntilValue",
				R.string.MercurygramLiveLocShareUntilValue, when);
	}

	/** Preset periods only (for settings quick list without opening custom). */
	public static int[] sharePeriodChoicesSec() {
		return new int[]{
				15 * 60,
				60 * 60,
				8 * 60 * 60,
				FOREVER_PERIOD,
		};
	}

	private static String labelForIndex(int index, int customSec, int untilUnix) {
		switch (index) {
			case IDX_15M:
				return LocaleController.getString(R.string.SendLiveLocationFor15m);
			case IDX_1H:
				return LocaleController.getString(R.string.SendLiveLocationFor1h);
			case IDX_8H:
				return LocaleController.getString(R.string.SendLiveLocationFor8h);
			case IDX_FOREVER:
				return LocaleController.getString(R.string.SendLiveLocationForever);
			case IDX_UNTIL:
				return formatUntilLabel(untilUnix);
			case IDX_CUSTOM:
			default:
				return LocaleController.formatString("MercurygramLiveLocCustomPeriodValue",
						R.string.MercurygramLiveLocCustomPeriodValue,
						formatCustomDuration(customSec));
		}
	}

	private static int indexForPeriod(int periodSec) {
		if (periodSec == 15 * 60) {
			return IDX_15M;
		}
		if (periodSec == 60 * 60) {
			return IDX_1H;
		}
		if (periodSec == 8 * 60 * 60) {
			return IDX_8H;
		}
		if (periodSec == FOREVER_PERIOD) {
			return IDX_FOREVER;
		}
		return IDX_CUSTOM;
	}

	private static void rememberPeriod(int periodSec, boolean custom) {
		SharedConfig.setMgLiveLocDefaultSharePeriodSec(periodSec);
		if (custom || !isPresetPeriod(periodSec)) {
			SharedConfig.setMgLiveLocLastCustomSharePeriodSec(clampCustom(periodSec));
		}
	}
}
