package it.belloworld.mercurygram.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import org.unifiedpush.android.connector.UnifiedPush;

import java.util.ArrayList;

import it.belloworld.mercurygram.MgUpdateChecker;
import it.belloworld.mercurygram.push.MgEmbeddedFcmDistributor;

public class MercurygramSettingsActivity extends UniversalFragment {

    private static final int ID_MESSAGE_DETAILS_MENU = 1;
    private static final int ID_HIDE_CHAT_KEYBOARD = 2;
    private static final int ID_HIDE_ALL_TAB = 3;
    private static final int ID_REAR_ROUND_VIDEOS = 11;
    private static final int ID_DISABLE_AUTO_UPDATE = 20;
    private static final int ID_ACCEPT_PRERELEASES = 21;
    private static final int ID_CHECK_FOR_UPDATES_NOW = 22;
    private static final int ID_UNIFIED_PUSH = 30;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.MercurygramSettings);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.MercurygramSettingsGeneral)));
        items.add(UItem.asCheck(ID_MESSAGE_DETAILS_MENU, LocaleController.getString(R.string.MercurygramMessageDetailsMenu))
                .setChecked(getUserConfig().mg.messageDetailsMenu));
        items.add(UItem.asCheck(ID_HIDE_CHAT_KEYBOARD, LocaleController.getString(R.string.HideChatKeyboard))
                .setChecked(getUserConfig().mg.hideChatKeyboard));
        items.add(UItem.asCheck(ID_HIDE_ALL_TAB, LocaleController.getString(R.string.HideAllTab))
                .setChecked(getUserConfig().mg.hideAllTab));
        items.add(UItem.asShadow(LocaleController.getString(R.string.HideAllTabAbout)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.MercurygramSettingsMedia)));
        items.add(UItem.asCheck(ID_REAR_ROUND_VIDEOS, LocaleController.getString(R.string.RearRoundVideos))
                .setChecked(getUserConfig().mg.rearRoundCamera));
        items.add(UItem.asShadow(null));

        if (!MgUpdateChecker.isFdroidBuild()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.MercurygramSettingsUpdates)));
            items.add(UItem.asCheck(ID_DISABLE_AUTO_UPDATE, LocaleController.getString(R.string.MercurygramDisableAutoUpdate))
                    .setChecked(SharedConfig.disableAutoUpdate));
            items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramDisableAutoUpdateAbout)));

            // Hidden on the .beta package — that channel already follows
            // /releases unconditionally, so the toggle would be meaningless.
            if (!MgUpdateChecker.isBetaChannel()) {
                items.add(UItem.asCheck(ID_ACCEPT_PRERELEASES,
                                LocaleController.getString(R.string.MercurygramAcceptPreReleaseUpdates))
                        .setChecked(SharedConfig.acceptPreReleaseUpdates));
                items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramAcceptPreReleaseUpdatesAbout)));
            }

            String checkSubtitle = SharedConfig.mgLastUpdateCheckTime > 0
                    ? LocaleController.formatString("MercurygramCheckForUpdatesLastChecked",
                            R.string.MercurygramCheckForUpdatesLastChecked,
                            LocaleController.formatDateTime(SharedConfig.mgLastUpdateCheckTime / 1000, true))
                    : LocaleController.getString(R.string.MercurygramCheckForUpdatesNever);
            items.add(UItem.asButton(ID_CHECK_FOR_UPDATES_NOW,
                    LocaleController.getString(R.string.MercurygramCheckForUpdatesNow), checkSubtitle));
            items.add(UItem.asShadow(null));
        }

        items.add(UItem.asHeader(LocaleController.getString(R.string.MercurygramSettingsNotifications)));
        CharSequence pushValue;
        if (SharedConfig.disableUnifiedPush) {
            pushValue = LocaleController.getString(R.string.NotificationsOff);
        } else {
            String distributor = UnifiedPush.getAckDistributor(ApplicationLoader.applicationContext);
            if (distributor == null) {
                distributor = UnifiedPush.getSavedDistributor(ApplicationLoader.applicationContext);
            }
            pushValue = distributor != null
                    ? MgEmbeddedFcmDistributor.label(distributor)
                    : LocaleController.getString(R.string.NotSet);
        }
        items.add(UItem.asButton(ID_UNIFIED_PUSH, LocaleController.getString(R.string.MercurygramUnifiedPush), pushValue));
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_MESSAGE_DETAILS_MENU:
                getUserConfig().mg.messageDetailsMenu = !getUserConfig().mg.messageDetailsMenu;
                getUserConfig().saveConfig(false);
                refreshList();
                break;
            case ID_HIDE_CHAT_KEYBOARD:
                getUserConfig().mg.hideChatKeyboard = !getUserConfig().mg.hideChatKeyboard;
                getUserConfig().saveConfig(false);
                refreshList();
                break;
            case ID_HIDE_ALL_TAB:
                getUserConfig().mg.hideAllTab = !getUserConfig().mg.hideAllTab;
                getUserConfig().saveConfig(false);
                refreshList();
                break;
            case ID_REAR_ROUND_VIDEOS:
                getUserConfig().mg.rearRoundCamera = !getUserConfig().mg.rearRoundCamera;
                getUserConfig().saveConfig(false);
                refreshList();
                break;
            case ID_DISABLE_AUTO_UPDATE:
                SharedConfig.toggleDisableAutoUpdate();
                refreshList();
                break;
            case ID_ACCEPT_PRERELEASES:
                handleAcceptPreReleasesClick();
                break;
            case ID_CHECK_FOR_UPDATES_NOW:
                MgUpdateChecker.checkForUpdates(true);
                showCheckingForUpdatesToast(getParentActivity());
                refreshList();
                break;
            case ID_UNIFIED_PUSH:
                presentFragment(new MgUnifiedPushSettingsActivity());
                break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void refreshList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void showCheckingForUpdatesToast(Context context) {
        Toast.makeText(context,
                LocaleController.getString(R.string.MercurygramCheckForUpdatesToast),
                Toast.LENGTH_SHORT).show();
    }

    private void handleAcceptPreReleasesClick() {
        // Post-stable betas (X.Y.Z.W.K) share MG_VC with X.Y.Z.W and can be
        // rolled back; toggling off triggers the download of that stable.
        if (SharedConfig.acceptPreReleaseUpdates) {
            MgUpdateChecker.setPreReleaseOptIn(false);
            // Fetching the stable is a network round trip, so the toast is the
            // only immediate feedback -- and only when a rollback really
            // started: a stable or a pre-stable X.Y.Z.0.K has none to return
            // to, and the unchecked row is the whole outcome there.
            if (MgUpdateChecker.checkForDowngradeToStable()) {
                showCheckingForUpdatesToast(getParentActivity());
            }
            refreshList();
            return;
        }
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.MercurygramAcceptPreReleaseUpdatesWarningTitle))
                .setMessage(LocaleController.getString(R.string.MercurygramAcceptPreReleaseUpdatesWarningMessage))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.MercurygramAcceptPreReleaseUpdatesEnable),
                        (d, which) -> {
                            MgUpdateChecker.setPreReleaseOptIn(true);
                            // Opting in is only meaningful once a pre-release is
                            // actually offered, and the periodic check is up to an
                            // hour away (never, with auto-update off), so force one
                            // now -- same forced call the "check now" row makes.
                            MgUpdateChecker.checkForUpdates(true);
                            showCheckingForUpdatesToast(context);
                            refreshList();
                        })
                .create();
        showDialog(dialog);
        TextView positive = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setTextColor(getThemedColor(Theme.key_text_RedBold));
        }
    }
}
