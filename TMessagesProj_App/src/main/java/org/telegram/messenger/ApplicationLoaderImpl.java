package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
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
