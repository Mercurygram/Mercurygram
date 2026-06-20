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
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

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
                Intent explainIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Mercurygram/Mercurygram/blob/Mercurygram/Notifications.md"));
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
                        .setContentText("Push service: tap to learn more")
                        .build();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // dataSync type required on API 34+ to match the manifest declaration.
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
