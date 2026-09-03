/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;

public class NotificationsService extends Service {

    // [TF] Re-applied foreground push service (lost in the UnifiedPush rebase, orig commit f3fc060b915).
    // Without startForeground() the keep-alive Service is a no-op on Oreo+: it gets reaped seconds after
    // the app leaves the foreground, so the "Keep-Alive Service" toggle delivers nothing. See Notifications.md.
    private static final String CHANNEL_ID = "push_service_channel";

    @Override
    public void onCreate() {
        super.onCreate();
        ApplicationLoader.postInitApplication();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                // IMPORTANCE_LOW: silent, no heads-up — keeps the OS battery-warning nag to a minimum (Notifications.md).
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Push Notifications Service", NotificationManager.IMPORTANCE_LOW);
                notificationManager.createNotificationChannel(channel);
                // Tapping used to open Notifications.md, which reads as "your notifications are broken";
                // misleading here, since this notification only exists because the user turned Keep-Alive on.
                // Send them where they can actually silence or hide it instead. API 26+, same gate as this block.
                Intent explainIntent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                        .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    piFlags |= PendingIntent.FLAG_IMMUTABLE; // mandatory on API 31+ (orig 2019 patch passed 0 -> crash on S+)
                }
                PendingIntent explainPendingIntent = PendingIntent.getActivity(this, 0, explainIntent, piFlags);
                Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentIntent(explainPendingIntent)
                        .setShowWhen(false)
                        .setOngoing(true)
                        .setSmallIcon(R.drawable.notification)
                        .setContentTitle(LocaleController.getString(R.string.MercurygramKeepAliveNotification))
                        .setContentText(LocaleController.getString(R.string.MercurygramKeepAliveNotificationHide))
                        .build();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // specialUse, not dataSync: from API 35 a dataSync service is capped at 6h a day
                    // (a quota shared with the live-location service) and cannot be started from
                    // BOOT_COMPLETED, which left the keep-alive dead after every reboot.
                    startForeground(9999, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(9999, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                } else {
                    startForeground(9999, notification);
                }
            } catch (Throwable ignore) {
                // A startForeground failure (quota, U+ restrictions) must not crash the process.
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void onDestroy() {
        super.onDestroy();
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        if (preferences.getBoolean("pushService", true)) {
            Intent intent = new Intent("org.telegram.start");
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        }
    }
}
