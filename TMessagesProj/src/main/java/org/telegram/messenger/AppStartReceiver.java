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

import it.belloworld.mercurygram.location.MgBackgroundLocationGate;
import it.belloworld.mercurygram.push.MgPushWatchdog;

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
        if (MgPushWatchdog.ACTION.equals(action)) {
            // [MG] the periodic push watchdog: it re-registers and resumes the connection, and
            // deliberately leaves the keep-alive service alone (see MgPushWatchdog.onAlarm)
            MgPushWatchdog.onAlarm(context);
            return;
        }
        final boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        final boolean selfRestart = "org.telegram.start".equals(action);
        if (selfRestart) {
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
            if (!selfRestart) {
                // [MG] a reboot and an app update both wipe the app's alarms, and this is the
                // only path that runs afterwards when the keep-alive service is off: without it
                // the watchdog stays disarmed until something else initialises the app
                MgPushWatchdog.schedule(context);
                // [MG] boot and app update are exempt from the background start limit, so this is
                // where a persisted live location share gets its foreground service back
                MgBackgroundLocationGate.onSystemStart();
            }
        });
    }
}
