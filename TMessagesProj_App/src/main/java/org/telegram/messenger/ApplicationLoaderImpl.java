package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.view.ViewGroup;

import org.telegram.messenger.regular.BuildConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.MgUpdateLayout;
import org.telegram.ui.IUpdateLayout;

import java.io.File;

import it.belloworld.mercurygram.MgUpdateChecker;
import it.belloworld.mercurygram.MgUpdateInfo;
import it.belloworld.mercurygram.ui.MgUpdateAlertDialog;

public class ApplicationLoaderImpl extends ApplicationLoader {
    @Override
    public void onCreate() {
        super.onCreate();
        if (BuildConfig.BUNDLE) {
            // Play Payments policy: no in-app purchase entry points in the artifact shipped to
            // Play. Keyed on the artifact, not on the installer: the same file sideloaded from
            // Aurora Store or adb is still the Play build and must keep the restriction, while a
            // per-ABI APK from GitHub must not gain it. BUNDLE is true only for the two bundle
            // flavors, and the only app bundle published is the Play one; a future non-Play
            // bundle flavor needs a build config field of its own here.
            // Upstream's own "official app needed" sheet takes the purchase entry points' place.
            BuildVars.IS_BILLING_UNAVAILABLE = true;
            BuildVars.PLAYSTORE_APP_URL = "https://play.google.com/store/apps/details?id=" + BuildConfig.APPLICATION_ID;
        }
    }

    @Override
    protected ILocationServiceProvider onCreateLocationServiceProvider() {
        return new AndroidLocationProvider();
    }

    @Override
    protected IMapsProvider onCreateMapsProvider() {
        return new MapLibreMapsProvider();
    }

    @Override
    protected PushListenerController.IPushListenerServiceProvider onCreatePushProvider() {
        return it.belloworld.mercurygram.push.UnifiedPushListenerServiceProvider.INSTANCE;
    }

    @Override
    protected boolean isStandalone() {
        return true;
    }

    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    @Override
    protected void checkForUpdatesInternal() {
        MgUpdateChecker.checkForUpdates(false);
    }

    @Override
    public boolean checkApkInstallPermissions(final Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ApplicationLoader.applicationContext.getPackageManager().canRequestPackageInstalls()) {
            AlertsCreator.createApkRestrictedDialog(context, null).show();
            return false;
        }
        return true;
    }

    @Override
    public boolean openApkInstall(Activity activity, TLRPC.Document document) {
        File apk = MgUpdateChecker.getUpdateApkFile();
        if (apk != null) {
            MgUpdateChecker.installUpdate(activity, apk);
            return true;
        }
        return false;
    }

    @Override
    public boolean showUpdateAppPopup(Context context, TLRPC.TL_help_appUpdate update, int account) {
        if (SharedConfig.isMgUpdateAvailable()) {
            try {
                MgUpdateInfo info = SharedConfig.getMgPendingUpdate();
                if (info != null) {
                    new MgUpdateAlertDialog(context, info).show();
                    return true;
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return false;
    }

    @Override
    public IUpdateLayout takeUpdateLayout(Activity activity, ViewGroup sideMenuContainer) {
        return new MgUpdateLayout(activity, sideMenuContainer);
    }
}
