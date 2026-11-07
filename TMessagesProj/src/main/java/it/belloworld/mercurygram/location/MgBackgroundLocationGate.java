package it.belloworld.mercurygram.location;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocationController;
import org.telegram.messenger.UserConfig;

// Live location shares are persisted, but nothing brings their service back after a reboot or an
// app update: the controllers that read them back are only created once the app is opened. Boot and
// package replace are exempt from the background start limit, so AppStartReceiver uses them to load
// the controllers, which start the service for any share they find. Where the service cannot get a
// location from the background (while-in-use grant on Android 10+), LocationController shows its
// paused notification and restarts the service from onAppResumed.
public class MgBackgroundLocationGate {

    // own prefs file, not mainconfig: this is read on the main thread at boot and on every app
    // update, and rewritten on every service start and stop
    private static final String PREFS = "mg_locationsharing";
    private static final String KEY_ACTIVE = "active";

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
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                LocationController.getInstance(a);
            }
        }
    }
}
