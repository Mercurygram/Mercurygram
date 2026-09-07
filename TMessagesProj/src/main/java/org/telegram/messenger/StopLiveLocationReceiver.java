/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import it.belloworld.mercurygram.ui.MgConfirmStopLiveLocationActivity;
import it.belloworld.mercurygram.ui.MgStopLiveLocationHelper;

public class StopLiveLocationReceiver extends BroadcastReceiver {

	@Override
	public void onReceive(Context context, Intent intent) {
		if (SharedConfig.mg_liveLocConfirmStop) {
			Intent activityIntent = new Intent(context, MgConfirmStopLiveLocationActivity.class);
			activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
					| Intent.FLAG_ACTIVITY_NO_ANIMATION);
			context.startActivity(activityIntent);
			return;
		}
		MgStopLiveLocationHelper.stopAllSharings();
	}
}
