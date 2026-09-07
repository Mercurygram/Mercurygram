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
import android.os.SystemClock;

/**
 * Keep-alive push restart plus boot/replace restore of live-location sharing FGS.
 */
public class AppStartReceiver extends BroadcastReceiver {

	// NotificationsService.onDestroy re-broadcasts org.telegram.start on every death, so a service
	// that keeps dying right after being started would restart itself in a tight loop. One attempt
	// per 10s keeps the keep-alive semantics and bounds the loop; a process death resets it.
	private static final long SELF_RESTART_MIN_INTERVAL = 10_000L;
	private static long lastSelfRestart = -SELF_RESTART_MIN_INTERVAL;

	@Override
	public void onReceive(Context context, Intent intent) {
		if (intent == null) {
			return;
		}
		final String action = intent.getAction();
		final boolean deviceBoot = Intent.ACTION_BOOT_COMPLETED.equals(action)
				|| "android.intent.action.QUICKBOOT_POWERON".equals(action)
				|| "com.htc.intent.action.QUICKBOOT_POWERON".equals(action);
		final boolean packageReplaced = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
		final boolean selfRestart = "org.telegram.start".equals(action);

		if (selfRestart) {
			final long now = SystemClock.elapsedRealtime();
			if (now - lastSelfRestart < SELF_RESTART_MIN_INTERVAL) {
				return;
			}
			lastSelfRestart = now;
		} else if (!deviceBoot && !packageReplaced) {
			return;
		}

		final PendingResult pendingResult = goAsync();
		AndroidUtilities.runOnUIThread(() -> {
			try {
				if (deviceBoot || packageReplaced) {
					ApplicationLoader.postInitApplication();
				}
				if (deviceBoot) {
					SharedConfig.loadConfig();
					if (SharedConfig.passcodeHash.length() > 0) {
						SharedConfig.appLocked = true;
						SharedConfig.saveConfig();
					}
				}
				ApplicationLoader.startPushService();
				if (deviceBoot || packageReplaced) {
					restoreLiveLocationSharing();
					LiveLocationDebug.log("boot/start action=" + action + " restored live-location controllers");
				}
			} catch (Throwable e) {
				FileLog.e(e);
				LiveLocationDebug.log("boot/start failed action=" + action + " err=" + e);
			} finally {
				try {
					pendingResult.finish();
				} catch (Throwable ignored) {
				}
			}
		});
	}

	/**
	 * Touch {@link LocationController} for each activated account so persisted
	 * shares are loaded and {@link LocationSharingService} is started.
	 * Also schedules a delayed ensure in case load raced ahead of permission/context.
	 */
	private static void restoreLiveLocationSharing() {
		int restored = 0;
		for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
			if (!UserConfig.getInstance(a).isClientActivated()) {
				continue;
			}
			LocationController.getInstance(a);
			restored++;
		}
		LiveLocationDebug.log("boot restore LocationController accounts=" + restored);
		AndroidUtilities.runOnUIThread(LocationController::ensureSharingServiceRunning, 1500);
		// After shares load from DB, force one GPS acquire (also covers slow load after replace).
		AndroidUtilities.runOnUIThread(LocationController::requestAcquireAfterRestoreAll, 2500);
		AndroidUtilities.runOnUIThread(LocationController::ensureSharingServiceRunning, 4000);
	}
}
