package it.belloworld.mercurygram.push;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.AppStartReceiver;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UnifiedPushReceiver;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;

/**
 * Periodic wake-up that puts push back on its feet without the user having to open the app.
 * Nothing else does that: when the distributor drops the subscription, or the keep-alive
 * service is killed, or the endpoint simply stops being delivered to, the app stays offline
 * until it is opened by hand - which is the shape of every "notifications stopped arriving for
 * a day" report.
 *
 * The alarm is inexact on purpose: an exact one needs SCHEDULE_EXACT_ALARM, and re-registering
 * at a precise minute is worth nothing. The price is that in Doze the alarm is deferred to the
 * next maintenance window, so the real spacing can be much longer than the interval asked for.
 */
public final class MgPushWatchdog {

    public static final String ACTION = "it.belloworld.mercurygram.PUSH_WATCHDOG";

    // With an endpoint in hand this only has to catch a subscription that died quietly, so once
    // an hour is enough. Without one there is no push at all and the retry is worth more.
    private static final long INTERVAL_REGISTERED_MS = AlarmManager.INTERVAL_HOUR;
    private static final long INTERVAL_UNREGISTERED_MS = AlarmManager.INTERVAL_FIFTEEN_MINUTES;
    // A distributor can lose the subscription without ever telling the connector (its own server
    // forgot the topic, its data was cleared): the saved acknowledgement and the endpoint still
    // look healthy, so ensureRegistered() has nothing to act on and push stays dead for as long
    // as the process lives. Silence this long is the only symptom available, so take a fresh
    // endpoint once a day of it. A quiet account pays one re-registration per day for that.
    private static final long SILENCE_BEFORE_REFRESH_MS = 24 * AlarmManager.INTERVAL_HOUR;
    // The alarm's own wake lock only lasts for onReceive(), and every useful thing below happens
    // after it returns, so the device could go back to sleep mid-recovery. Same 30 s safety
    // timeout the push receiver uses.
    private static final long WAKE_LOCK_TIMEOUT_MS = 30_000L;

    private static final String PREFS = "mg_push_watchdog";
    private static final String KEY_LAST_REFRESH = "lastRefresh";

    private static PowerManager.WakeLock wakeLock;

    private MgPushWatchdog() {
    }

    /**
     * (Re-)arms the alarm. Called at startup, from the boot and app-update broadcasts (a reboot
     * and an update both drop every alarm the app had) and again from every firing, so the
     * interval follows the registration state instead of being frozen at whatever it was on the
     * first launch. FLAG_UPDATE_CURRENT replaces the previous alarm rather than adding one.
     */
    public static void schedule(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        long interval = TextUtils.isEmpty(SharedConfig.pushString)
                ? INTERVAL_UNREGISTERED_MS
                : INTERVAL_REGISTERED_MS;
        alarmManager.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + interval,
                interval,
                pendingIntent(context));
    }

    /**
     * Deliberately does not start the keep-alive foreground service: starting one from a
     * background broadcast is restricted from Android 12 on, and re-asserting the registration
     * plus resuming the connection is all that is needed to bring the missed updates in.
     */
    public static void onAlarm(Context context) {
        acquireWakeLock(context);
        AndroidUtilities.runOnUIThread(() -> {
            boolean stageQueueScheduled = false;
            try {
                ApplicationLoader.postInitApplication();
                schedule(context);
                UnifiedPushListenerServiceProvider.ensureRegistered();
                refreshAfterLongSilence(context);
                Utilities.stageQueue.postRunnable(() -> {
                    try {
                        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                            if (UserConfig.getInstance(a).isClientActivated()) {
                                ConnectionsManager.onInternalPushReceived(a);
                                ConnectionsManager.getInstance(a).resumeNetworkMaybe();
                            }
                        }
                    } finally {
                        releaseWakeLock();
                    }
                });
                stageQueueScheduled = true;
            } finally {
                // postInitApplication() throwing would otherwise leave the wake lock pinned
                // until its own timeout.
                if (!stageQueueScheduled) {
                    releaseWakeLock();
                }
            }
        });
    }

    /**
     * Takes a fresh endpoint when nothing has been delivered for a full day. The first call only
     * writes the baseline: an install that never received anything has no reference point, and
     * re-registering on the very first alarm would fight the registration that is still in
     * flight.
     */
    private static void refreshAfterLongSilence(Context context) {
        if (SharedConfig.disableUnifiedPush || TextUtils.isEmpty(SharedConfig.pushString)) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long lastRefresh = prefs.getLong(KEY_LAST_REFRESH, 0);
        if (lastRefresh == 0) {
            prefs.edit().putLong(KEY_LAST_REFRESH, now).apply();
            return;
        }
        long lastSign = Math.max(lastRefresh, UnifiedPushReceiver.getLastReceivedNotification());
        if (now - lastSign < SILENCE_BEFORE_REFRESH_MS) {
            return;
        }
        prefs.edit().putLong(KEY_LAST_REFRESH, now).apply();
        UnifiedPushReceiver.log("nothing received for a day, taking a fresh endpoint");
        UnifiedPushListenerServiceProvider.reregisterCurrent();
    }

    private static synchronized void acquireWakeLock(Context context) {
        if (wakeLock == null) {
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (powerManager == null) {
                return;
            }
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mercurygram:watchdog");
            wakeLock.setReferenceCounted(true);
        }
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
    }

    private static synchronized void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (RuntimeException ignored) {
                // Already released by the timeout.
            }
        }
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, AppStartReceiver.class).setAction(ACTION);
        return PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
