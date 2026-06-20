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

public class AppStartReceiver extends BroadcastReceiver {

    // NotificationsService.onDestroy re-broadcasts org.telegram.start on every death, so a service
    // that keeps dying right after being started would restart itself in a tight loop. One attempt
    // per 10s keeps the keep-alive semantics and bounds the loop; a process death resets it.
    private static final long SELF_RESTART_MIN_INTERVAL = 10_000L;
    private static long lastSelfRestart = -SELF_RESTART_MIN_INTERVAL;

    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        // Telegram-FOSS: the keep-alive service is the only push transport when no distributor
        // answers, so every event that can kill it has to bring it back - the service's own death
        // (org.telegram.start) and the app update, which kills the process and leaves nothing of
        // the new APK running. The self-restart only lands below API 31: an ordinary app broadcast
        // carries no temporary allowlist, so startForegroundService() from a backgrounded process
        // throws and is swallowed. BOOT_COMPLETED and MY_PACKAGE_REPLACED are exempt and do start.
        final String action = intent.getAction();
        final boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        if ("org.telegram.start".equals(action)) {
            final long now = SystemClock.elapsedRealtime();
            if (now - lastSelfRestart < SELF_RESTART_MIN_INTERVAL) {
                return;
            }
            lastSelfRestart = now;
        } else if (!boot && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (boot) {
                SharedConfig.loadConfig();
                if (SharedConfig.passcodeHash.length() > 0) {
                    SharedConfig.appLocked = true;
                    SharedConfig.saveConfig();
                }
            }
            ApplicationLoader.startPushService();
        });
    }
}
