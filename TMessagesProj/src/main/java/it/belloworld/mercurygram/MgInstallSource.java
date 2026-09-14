package it.belloworld.mercurygram;

import android.Manifest;
import android.content.Context;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import org.telegram.messenger.ApplicationLoader;

import java.util.Arrays;

/**
 * Where this install came from. One source tree serves GitHub, F-Droid and
 * Google Play, so channel behaviour that a build flag cannot express (the
 * library module cannot see the app's BuildConfig, and a GitHub APK
 * sideloaded over a Play install must behave like a GitHub install) is keyed
 * on the installer package at runtime instead.
 */
public final class MgInstallSource {
    private static final String PLAY_STORE = "com.android.vending";
    private static Boolean isPlayStoreCached;
    private static Boolean declaresInstallPermissionCached;

    private MgInstallSource() {}

    /** True when Google Play installed (or last updated) this package. Fail-safe false. */
    public static boolean isPlayStore() {
        if (isPlayStoreCached != null) return isPlayStoreCached;
        Context context = ApplicationLoader.applicationContext;
        if (context == null) return false; // too early to know, do not cache
        String installer = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                InstallSourceInfo info = context.getPackageManager().getInstallSourceInfo(context.getPackageName());
                installer = info.getInstallingPackageName();
            } else {
                installer = context.getPackageManager().getInstallerPackageName(context.getPackageName());
            }
        } catch (Throwable ignore) {
        }
        isPlayStoreCached = PLAY_STORE.equals(installer);
        return isPlayStoreCached;
    }

    /**
     * True when this build still declares REQUEST_INSTALL_PACKAGES. The Play bundle
     * strips it, and that build can reach a device through something other than Play
     * (Aurora Store, adb, a split backup), where the installer package no longer says
     * "Play" but the install intent would still be refused. Checks the declaration,
     * not the user's "install unknown apps" grant: that grant is asked for at install
     * time and must not hide the updater before it is given. Fail-safe false.
     */
    public static boolean declaresInstallPermission() {
        if (declaresInstallPermissionCached != null) return declaresInstallPermissionCached;
        Context context = ApplicationLoader.applicationContext;
        if (context == null) return false; // too early to know, do not cache
        boolean declared = false;
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
            declared = info.requestedPermissions != null
                    && Arrays.asList(info.requestedPermissions)
                            .contains(Manifest.permission.REQUEST_INSTALL_PACKAGES);
        } catch (Throwable ignore) {
        }
        return declaresInstallPermissionCached = declared;
    }
}
