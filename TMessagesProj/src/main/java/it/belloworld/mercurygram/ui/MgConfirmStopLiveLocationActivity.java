package it.belloworld.mercurygram.ui;

import android.app.Activity;
import android.os.Bundle;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;

/**
 * Transparent trampoline so the live-location notification "Stop" action can
 * show a confirmation dialog (BroadcastReceiver cannot host UI).
 */
public class MgConfirmStopLiveLocationActivity extends Activity {

	private boolean finishing;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (!SharedConfig.mg_liveLocConfirmStop) {
			MgStopLiveLocationHelper.stopAllSharings();
			finish();
			return;
		}
		MgStopLiveLocationHelper.showConfirm(this, null,
				LocaleController.getString(R.string.StopLiveLocationAlertToTitle),
				LocaleController.getString(R.string.StopLiveLocationAlertAllText),
				() -> {
					MgStopLiveLocationHelper.stopAllSharings();
					finishSafely();
				},
				this::finishSafely);
	}

	@Override
	public void onBackPressed() {
		finishSafely();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		finishing = true;
	}

	private void finishSafely() {
		if (finishing || isFinishing()) {
			return;
		}
		finishing = true;
		finish();
	}

	@Override
	public void finish() {
		super.finish();
		overridePendingTransition(0, 0);
	}
}
