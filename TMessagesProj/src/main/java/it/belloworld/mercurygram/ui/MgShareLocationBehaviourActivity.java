package it.belloworld.mercurygram.ui;

import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

import it.belloworld.mercurygram.MgLiveLocationFixLog;
import it.belloworld.mercurygram.MgLiveLocationPolicy;
import it.belloworld.mercurygram.MgLiveLocationThresholds;
import it.belloworld.mercurygram.MgLiveLocationViewerLog;

public class MgShareLocationBehaviourActivity extends UniversalFragment {

	private static final int ID_GPS_SLIDER = 1;
	private static final int ID_BATTERY = 2;
	private static final int ID_VIEWER_BOOST = 3;
	private static final int ID_VIEWER_BOOST_BATTERY = 15;
	private static final int ID_DWELL = 4;
	private static final int ID_SLEEP_MAX = 5;
	private static final int ID_SLEEP_MODE = 6;
	private static final int ID_MAX_ACQUIRE = 7;
	private static final int ID_DEFAULT_PERIOD = 8;
	private static final int ID_VIEWER_LOG = 9;
	private static final int ID_VIEWER_LOG_VIEW = 10;
	private static final int ID_VIEWER_LOG_CLEAR = 11;
	private static final int ID_FIX_LOG = 12;
	private static final int ID_FIX_LOG_VIEW = 13;
	private static final int ID_FIX_LOG_CLEAR = 14;
	private static final int ID_CONFIRM_STOP = 16;

	private TextView policyDescriptionView;

	@Override
	protected CharSequence getTitle() {
		return LocaleController.getString(R.string.MercurygramShareLocationBehaviour);
	}

	@Override
	protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
		items.add(UItem.asCustom(ID_GPS_SLIDER, buildSliderCell()));
		items.add(UItem.asShadow(null));
		items.add(MgSettingsScope.globalCheck(ID_VIEWER_BOOST, LocaleController.getString(R.string.MercurygramLiveLocViewerBoost))
				.setChecked(SharedConfig.mg_liveLocViewerBoost));
		items.add(MgSettingsScope.globalCheck(ID_VIEWER_BOOST_BATTERY, LocaleController.getString(R.string.MercurygramLiveLocViewerBoostInBatterySaver))
				.setChecked(SharedConfig.mg_liveLocViewerBoostInBatterySaver)
				.setEnabled(SharedConfig.mg_liveLocViewerBoost));
		items.add(UItem.asShadow(SharedConfig.mg_liveLocViewerBoost
				? LocaleController.getString(R.string.MercurygramLiveLocViewerBoostAbout)
						+ "\n\n" + LocaleController.getString(R.string.MercurygramLiveLocViewerBoostInBatterySaverAbout)
				: LocaleController.getString(R.string.MercurygramLiveLocViewerBoostAbout)));
		items.add(UItem.asHeader(LocaleController.getString(R.string.MercurygramLiveLocDutyCycleHeader)));
		items.add(UItem.asButton(ID_SLEEP_MAX, LocaleController.getString(R.string.MercurygramLiveLocSleepMax),
				MgLiveLocationPolicy.formatDurationSec(SharedConfig.mg_liveLocSleepMaxSec)));
		items.add(UItem.asButton(ID_SLEEP_MODE, LocaleController.getString(R.string.MercurygramLiveLocSleepMode),
				MgLiveLocationPolicy.formatSleepMode(SharedConfig.mg_liveLocSleepMode)));
		items.add(UItem.asShadow(sleepModeAbout()));
		items.add(UItem.asButton(ID_BATTERY, LocaleController.getString(R.string.MercurygramLiveLocBatterySaver),
				MgLiveLocationPolicy.formatBatterySaverMultiplier(SharedConfig.mg_liveLocBatterySaverMultiplier)));
		items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramLiveLocBatterySaverAbout)));
		items.add(UItem.asButton(ID_MAX_ACQUIRE, LocaleController.getString(R.string.MercurygramLiveLocMaxAcquire),
				MgLiveLocationPolicy.formatMaxAcquire(SharedConfig.mg_liveLocMaxAcquireSec)));
		items.add(UItem.asButton(ID_DWELL, LocaleController.getString(R.string.MercurygramLiveLocDwell),
				MgLiveLocationPolicy.formatDurationSec(SharedConfig.mg_liveLocDwellSec)));
		items.add(UItem.asShadow(MgSettingsScope.withAllAccountsNote(
				LocaleController.getString(R.string.MercurygramLiveLocAbout))));
		items.add(UItem.asHeader(LocaleController.getString(R.string.MercurygramLiveLocShareHeader)));
		items.add(UItem.asButton(ID_DEFAULT_PERIOD, LocaleController.getString(R.string.MercurygramLiveLocDefaultPeriod),
				MgShareLocationHelper.formatPeriodLabel(SharedConfig.mg_liveLocDefaultSharePeriodSec)));
		items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramLiveLocDefaultPeriodAbout)));
		items.add(MgSettingsScope.globalCheck(ID_CONFIRM_STOP, LocaleController.getString(R.string.MercurygramLiveLocConfirmStop))
				.setChecked(SharedConfig.mg_liveLocConfirmStop));
		items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramLiveLocConfirmStopAbout)));
		items.add(UItem.asHeader(LocaleController.getString(R.string.MercurygramLiveLocViewerLogHeader)));
		items.add(MgSettingsScope.globalCheck(ID_VIEWER_LOG, LocaleController.getString(R.string.MercurygramLiveLocViewerLogEnable))
				.setChecked(MgLiveLocationViewerLog.isEnabled()));
		if (MgLiveLocationViewerLog.isEnabled()) {
			items.add(UItem.asButton(ID_VIEWER_LOG_VIEW, LocaleController.getString(R.string.MercurygramLiveLocViewerLogView), ""));
			items.add(UItem.asButton(ID_VIEWER_LOG_CLEAR, LocaleController.getString(R.string.MercurygramLiveLocViewerLogClear), ""));
		}
		items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramLiveLocViewerLogAbout)));
		items.add(UItem.asHeader(LocaleController.getString(R.string.MercurygramLiveLocFixLogHeader)));
		items.add(MgSettingsScope.globalCheck(ID_FIX_LOG, LocaleController.getString(R.string.MercurygramLiveLocFixLogEnable))
				.setChecked(MgLiveLocationFixLog.isEnabled()));
		if (MgLiveLocationFixLog.isEnabled()) {
			items.add(UItem.asButton(ID_FIX_LOG_VIEW, LocaleController.getString(R.string.MercurygramLiveLocFixLogView), ""));
			items.add(UItem.asButton(ID_FIX_LOG_CLEAR, LocaleController.getString(R.string.MercurygramLiveLocFixLogClear), ""));
		}
		items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramLiveLocFixLogAbout)));
	}

	private String sleepModeAbout() {
		switch (SharedConfig.mg_liveLocSleepMode) {
			case MgLiveLocationPolicy.SLEEP_MODE_LINEAR:
				return LocaleController.getString(R.string.MercurygramLiveLocSleepModeLinearAbout);
			case MgLiveLocationPolicy.SLEEP_MODE_LOG:
				return LocaleController.getString(R.string.MercurygramLiveLocSleepModeLogAbout);
			default:
				return LocaleController.getString(R.string.MercurygramLiveLocSleepModeFixedAbout);
		}
	}

	private View buildSliderCell() {
		Context context = getContext();
		LinearLayout layout = new LinearLayout(context);
		layout.setOrientation(LinearLayout.VERTICAL);
		// ~30% less vertical padding around the slider vs prior 8/4/8 margins.
		layout.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(6), AndroidUtilities.dp(16), AndroidUtilities.dp(6));

		TextView title = new TextView(context);
		title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
		title.setTextSize(16);
		title.setText(LocaleController.getString(R.string.MercurygramLiveLocGpsPolicy));
		layout.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

		MgDualThresholdSlider slider = new MgDualThresholdSlider(context);
		slider.setIndices(SharedConfig.mg_liveLocAlwaysOnLowerIndex, SharedConfig.mg_liveLocAlwaysOffUpperIndex);
		slider.setOnChangeListener((left, right) -> {
			SharedConfig.setMgLiveLocThresholdIndices(left, right);
			updatePolicyDescription();
		});
		layout.addView(slider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

		policyDescriptionView = new TextView(context);
		policyDescriptionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
		policyDescriptionView.setTextSize(14);
		layout.addView(policyDescriptionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
		updatePolicyDescription();
		return layout;
	}

	private void updatePolicyDescription() {
		if (policyDescriptionView != null) {
			policyDescriptionView.setText(MgLiveLocationThresholds.buildPolicyDescription(
					SharedConfig.mg_liveLocAlwaysOnLowerIndex,
					SharedConfig.mg_liveLocAlwaysOffUpperIndex));
		}
	}

	@Override
	protected void onClick(UItem item, View view, int position, float x, float y) {
		switch (item.id) {
			case ID_BATTERY:
				showBatterySaverPicker();
				break;
			case ID_VIEWER_BOOST:
				SharedConfig.setMgLiveLocViewerBoost(!SharedConfig.mg_liveLocViewerBoost);
				refreshList();
				break;
			case ID_VIEWER_BOOST_BATTERY:
				if (SharedConfig.mg_liveLocViewerBoost) {
					SharedConfig.setMgLiveLocViewerBoostInBatterySaver(!SharedConfig.mg_liveLocViewerBoostInBatterySaver);
					refreshList();
				}
				break;
			case ID_DWELL:
				showDurationSliderDialog(
						R.string.MercurygramLiveLocDwell,
						MgLiveLocationPolicy.DWELL_STEPS_SEC,
						SharedConfig.mg_liveLocDwellSec,
						SharedConfig::setMgLiveLocDwellSec);
				break;
			case ID_SLEEP_MAX:
				showDurationSliderDialog(
						R.string.MercurygramLiveLocSleepMax,
						MgLiveLocationPolicy.SLEEP_MAX_STEPS_SEC,
						SharedConfig.mg_liveLocSleepMaxSec,
						SharedConfig::setMgLiveLocSleepMaxSec);
				break;
			case ID_SLEEP_MODE:
				showSleepModePicker();
				break;
			case ID_MAX_ACQUIRE:
				showIntPicker(R.string.MercurygramLiveLocMaxAcquire,
						new int[]{0, MgLiveLocationPolicy.MAX_ACQUIRE_FROM_HISTORY, 45, 90, 180, 600},
						SharedConfig.mg_liveLocMaxAcquireSec, SharedConfig::setMgLiveLocMaxAcquireSec, true);
				break;
			case ID_DEFAULT_PERIOD:
				showDefaultPeriodPicker();
				break;
			case ID_CONFIRM_STOP:
				SharedConfig.setMgLiveLocConfirmStop(!SharedConfig.mg_liveLocConfirmStop);
				refreshList();
				break;
			case ID_VIEWER_LOG:
				MgLiveLocationViewerLog.setEnabled(!MgLiveLocationViewerLog.isEnabled());
				refreshList();
				break;
			case ID_VIEWER_LOG_VIEW:
				showViewerLogDialog();
				break;
			case ID_VIEWER_LOG_CLEAR:
				MgLiveLocationViewerLog.clear();
				AndroidUtilities.runOnUIThread(() -> Toast.makeText(getContext(), R.string.MercurygramLiveLocViewerLogCleared, Toast.LENGTH_SHORT).show());
				break;
			case ID_FIX_LOG:
				MgLiveLocationFixLog.setEnabled(!MgLiveLocationFixLog.isEnabled());
				refreshList();
				break;
			case ID_FIX_LOG_VIEW:
				showFixLogDialog();
				break;
			case ID_FIX_LOG_CLEAR:
				MgLiveLocationFixLog.clear();
				AndroidUtilities.runOnUIThread(() -> Toast.makeText(getContext(), R.string.MercurygramLiveLocFixLogCleared, Toast.LENGTH_SHORT).show());
				break;
		}
	}

	private void showDurationSliderDialog(int titleRes, int[] steps, int currentSec, IntConsumer setter) {
		Context context = getParentActivity();
		if (context == null) {
			return;
		}
		LinearLayout layout = new LinearLayout(context);
		layout.setOrientation(LinearLayout.VERTICAL);
		layout.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(4));

		TextView valueView = new TextView(context);
		valueView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
		valueView.setTextSize(16);
		valueView.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
		valueView.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
		valueView.setText(MgLiveLocationPolicy.formatDurationSec(currentSec));
		layout.addView(valueView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

		MgWaitDurationSlider slider = new MgWaitDurationSlider(context, steps);
		slider.setSeconds(currentSec);
		slider.setOnChangeListener(sec -> {
			setter.accept(sec);
			valueView.setText(MgLiveLocationPolicy.formatDurationSec(sec));
		});
		layout.addView(slider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, AndroidUtilities.dp(48), 0, 0, 0, 8));

		Dialog dialog = new AlertDialog.Builder(context)
				.setTitle(LocaleController.getString(titleRes))
				.setView(layout)
				.setPositiveButton(LocaleController.getString(R.string.OK), null)
				.setOnDismissListener(d -> refreshList())
				.create();
		showDialog(dialog);
	}

	private void showBatterySaverPicker() {
		Context context = getParentActivity();
		if (context == null) {
			return;
		}
		int[] choices = {
				1, 2, 3, 5, 8,
				MgLiveLocationPolicy.BATTERY_SAVER_OFF
		};
		AtomicReference<Dialog> dialogRef = new AtomicReference<>();
		LinearLayout linearLayout = new LinearLayout(context);
		linearLayout.setOrientation(LinearLayout.VERTICAL);
		int current = SharedConfig.mg_liveLocBatterySaverMultiplier;
		for (int value : choices) {
			final int chosen = value;
			RadioColorCell cell = new RadioColorCell(context);
			cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
			cell.setCheckColor(Theme.getColor(Theme.key_radioBackground), Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
			cell.setTextAndValue(MgLiveLocationPolicy.formatBatterySaverMultiplier(chosen), chosen == current);
			cell.setOnClickListener(v -> {
				SharedConfig.setMgLiveLocBatterySaverMultiplier(chosen);
				refreshList();
				Dialog d = dialogRef.get();
				if (d != null) {
					d.dismiss();
				}
			});
			linearLayout.addView(cell);
		}
		Dialog dialog = new AlertDialog.Builder(context)
				.setTitle(LocaleController.getString(R.string.MercurygramLiveLocBatterySaver))
				.setView(linearLayout)
				.setNegativeButton(LocaleController.getString(R.string.Cancel), null)
				.create();
		dialogRef.set(dialog);
		showDialog(dialog);
	}

	private void showSleepModePicker() {
		Context context = getParentActivity();
		if (context == null) {
			return;
		}
		int[] modes = {
				MgLiveLocationPolicy.SLEEP_MODE_FIXED,
				MgLiveLocationPolicy.SLEEP_MODE_LINEAR,
				MgLiveLocationPolicy.SLEEP_MODE_LOG
		};
		AtomicReference<Dialog> dialogRef = new AtomicReference<>();
		LinearLayout linearLayout = new LinearLayout(context);
		linearLayout.setOrientation(LinearLayout.VERTICAL);
		int current = SharedConfig.mg_liveLocSleepMode;
		for (int mode : modes) {
			final int chosen = mode;
			RadioColorCell cell = new RadioColorCell(context);
			cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
			cell.setCheckColor(Theme.getColor(Theme.key_radioBackground), Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
			cell.setTextAndValue(MgLiveLocationPolicy.formatSleepMode(chosen), chosen == current);
			cell.setOnClickListener(v -> {
				SharedConfig.setMgLiveLocSleepMode(chosen);
				refreshList();
				Dialog d = dialogRef.get();
				if (d != null) {
					d.dismiss();
				}
			});
			linearLayout.addView(cell);
		}
		Dialog dialog = new AlertDialog.Builder(context)
				.setTitle(LocaleController.getString(R.string.MercurygramLiveLocSleepMode))
				.setView(linearLayout)
				.setNegativeButton(LocaleController.getString(R.string.Cancel), null)
				.create();
		dialogRef.set(dialog);
		showDialog(dialog);
	}

	private void showFixLogDialog() {
		ArrayList<MgLiveLocationFixLog.Entry> entries = MgLiveLocationFixLog.load();
		StringBuilder sb = new StringBuilder();
		if (entries.isEmpty()) {
			sb.append(LocaleController.getString(R.string.MercurygramLiveLocFixLogEmpty));
		} else {
			for (int a = 0; a < entries.size(); a++) {
				if (a > 0) {
					sb.append("\n\n");
				}
				sb.append(MgLiveLocationFixLog.formatEntry(entries.get(a)));
			}
		}
		Context context = getParentActivity();
		if (context == null) {
			return;
		}
		new AlertDialog.Builder(context)
				.setTitle(LocaleController.getString(R.string.MercurygramLiveLocFixLogView))
				.setMessage(sb.toString())
				.setPositiveButton(LocaleController.getString(R.string.Close), null)
				.show();
	}

	private void showViewerLogDialog() {
		ArrayList<MgLiveLocationViewerLog.Entry> entries = MgLiveLocationViewerLog.load();
		StringBuilder sb = new StringBuilder();
		if (entries.isEmpty()) {
			sb.append(LocaleController.getString(R.string.MercurygramLiveLocViewerLogEmpty));
		} else {
			for (int a = 0; a < entries.size(); a++) {
				if (a > 0) {
					sb.append("\n\n");
				}
				sb.append(MgLiveLocationViewerLog.formatEntry(entries.get(a)));
			}
		}
		Context context = getParentActivity();
		if (context == null) {
			return;
		}
		new AlertDialog.Builder(context)
				.setTitle(LocaleController.getString(R.string.MercurygramLiveLocViewerLogView))
				.setMessage(sb.toString())
				.setPositiveButton(LocaleController.getString(R.string.Close), null)
				.show();
	}

	private void showDefaultPeriodPicker() {
		Context context = getParentActivity();
		if (context == null) {
			return;
		}
		int[] periods = MgShareLocationHelper.sharePeriodChoicesSec();
		AtomicReference<Dialog> dialogRef = new AtomicReference<>();
		LinearLayout linearLayout = new LinearLayout(context);
		linearLayout.setOrientation(LinearLayout.VERTICAL);
		int current = SharedConfig.mg_liveLocDefaultSharePeriodSec;
		boolean currentIsCustom = !MgShareLocationHelper.isPresetPeriod(current);
		for (int period : periods) {
			final int chosen = period;
			RadioColorCell cell = new RadioColorCell(context);
			cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
			cell.setCheckColor(Theme.getColor(Theme.key_radioBackground), Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
			cell.setTextAndValue(MgShareLocationHelper.formatPeriodLabel(chosen), chosen == current);
			cell.setOnClickListener(v -> {
				SharedConfig.setMgLiveLocDefaultSharePeriodSec(chosen);
				refreshList();
				Dialog d = dialogRef.get();
				if (d != null) {
					d.dismiss();
				}
			});
			linearLayout.addView(cell);
		}
		RadioColorCell customCell = new RadioColorCell(context);
		customCell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
		customCell.setCheckColor(Theme.getColor(Theme.key_radioBackground), Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
		int customSec = currentIsCustom ? current : SharedConfig.mg_liveLocLastCustomSharePeriodSec;
		customCell.setTextAndValue(MgShareLocationHelper.formatPeriodLabel(
				MgShareLocationHelper.clampCustom(customSec)), currentIsCustom);
		customCell.setOnClickListener(v -> {
			Dialog d = dialogRef.get();
			if (d != null) {
				d.dismiss();
			}
			MgShareLocationHelper.openCustomPeriodPicker(context, customSec, null, chosen -> {
				SharedConfig.setMgLiveLocDefaultSharePeriodSec(chosen);
				SharedConfig.setMgLiveLocLastCustomSharePeriodSec(chosen);
				refreshList();
			});
		});
		linearLayout.addView(customCell);
		Dialog dialog = new AlertDialog.Builder(context)
				.setTitle(LocaleController.getString(R.string.MercurygramLiveLocDefaultPeriod))
				.setView(linearLayout)
				.setNegativeButton(LocaleController.getString(R.string.Cancel), null)
				.create();
		dialogRef.set(dialog);
		showDialog(dialog);
	}

	private void showIntPicker(int titleRes, int[] choices, int current, IntConsumer setter, boolean maxAcquireLabels) {
		Context context = getParentActivity();
		if (context == null) {
			return;
		}
		AtomicReference<Dialog> dialogRef = new AtomicReference<>();
		LinearLayout linearLayout = new LinearLayout(context);
		linearLayout.setOrientation(LinearLayout.VERTICAL);
		for (int value : choices) {
			final int chosen = value;
			RadioColorCell cell = new RadioColorCell(context);
			cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
			cell.setCheckColor(Theme.getColor(Theme.key_radioBackground), Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
			String label = maxAcquireLabels
					? MgLiveLocationPolicy.formatMaxAcquire(chosen)
					: (chosen == 0
					? LocaleController.getString(R.string.MercurygramLiveLocMaxAcquireOff)
					: MgLiveLocationPolicy.formatDurationSec(chosen));
			cell.setTextAndValue(label, chosen == current);
			cell.setOnClickListener(v -> {
				setter.accept(chosen);
				refreshList();
				Dialog d = dialogRef.get();
				if (d != null) {
					d.dismiss();
				}
			});
			linearLayout.addView(cell);
		}
		Dialog dialog = new AlertDialog.Builder(context)
				.setTitle(LocaleController.getString(titleRes))
				.setView(linearLayout)
				.setNegativeButton(LocaleController.getString(R.string.Cancel), null)
				.create();
		dialogRef.set(dialog);
		showDialog(dialog);
	}

	@Override
	protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
		return false;
	}

	private void refreshList() {
		if (listView != null && listView.adapter != null) {
			listView.adapter.update(true);
		}
	}
}
