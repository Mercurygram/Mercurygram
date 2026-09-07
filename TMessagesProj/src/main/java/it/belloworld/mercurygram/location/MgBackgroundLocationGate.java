package it.belloworld.mercurygram.location;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LocationController;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.Components.PermissionRequest;
import org.telegram.ui.LaunchActivity;

// Android 10 gives a foreground service location only while the app itself counts as in use, unless
// ACCESS_BACKGROUND_LOCATION is granted. Live location asks for that permission at most once a day
// and keeps sharing when it is refused, so plenty of installs only have the while-in-use grant. A
// share restored after a reboot is started from a broadcast receiver with nothing on screen, so on
// those installs the service would run and its notification would claim the share is live while no
// fix ever arrives. Hold the restore back instead, ask for the app to be opened, and let the resume
// hook in LaunchActivity start the service once the app is genuinely in the foreground.
public class MgBackgroundLocationGate {

    private static final int NOTIFICATION_ID = 6001;

    // own prefs file, not mainconfig: this is read on the main thread at boot and on every app
    // update, and rewritten on every service start and stop
    private static final String PREFS = "mg_locationsharing";
    private static final String KEY_ACTIVE = "active";

    private static volatile boolean deferred;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // cheap "is anything shared" marker, kept by startService/stopService, which every mutation
    // path already goes through
    public static boolean isSharingActive() {
        return prefs().getBoolean(KEY_ACTIVE, false);
    }

    public static void setSharingActive(boolean active) {
        if (isSharingActive() != active) {
            prefs().edit().putBoolean(KEY_ACTIVE, active).apply();
        }
    }

    // boot or app update: the controllers load the persisted shares and start the service back up.
    // postInitApplication() is a full app start (native libs, every account's config, storage,
    // network), so it stays gated on the marker - a boot with nothing shared must not drag the
    // whole app up.
    public static void onSystemStart() {
        if (!isSharingActive()) {
            return;
        }
        ApplicationLoader.postInitApplication();
        forEachAccount();
    }

    public static boolean canStartService() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !ApplicationLoader.mainInterfacePaused) {
            return true;
        }
        return PermissionRequest.hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
    }

    public static void deferService(Context context) {
        deferred = true;
        Intent intent = new Intent(context, LaunchActivity.class);
        intent.setAction("org.tmessages.openlocations");
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        NotificationsController.checkOtherNotificationsChannel();
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NotificationsController.OTHER_NOTIFICATIONS_CHANNEL)
                .setSmallIcon(R.drawable.live_loc)
                .setWhen(System.currentTimeMillis())
                .setContentTitle(LocaleController.getString(R.string.MercurygramLiveLocationPaused))
                .setContentText(LocaleController.getString(R.string.MercurygramLiveLocationPausedInfo))
                .setContentIntent(PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setAutoCancel(true);
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build());
    }

    public static void onAppForeground() {
        if (!deferred) {
            return;
        }
        deferred = false;
        NotificationManagerCompat.from(ApplicationLoader.applicationContext).cancel(NOTIFICATION_ID);
        forEachAccount();
    }

    private static void forEachAccount() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                LocationController.getInstance(a).resumeDeferredSharing();
            }
        }
    }
}
