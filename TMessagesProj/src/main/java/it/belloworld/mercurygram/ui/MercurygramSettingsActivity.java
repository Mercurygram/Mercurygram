package it.belloworld.mercurygram.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UnifiedPushReceiver;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import org.unifiedpush.android.connector.UnifiedPush;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import it.belloworld.mercurygram.HiddenAccountHelper;
import it.belloworld.mercurygram.MgUpdateChecker;
import it.belloworld.mercurygram.push.MgEmbeddedFcmDistributor;
import it.belloworld.mercurygram.push.UnifiedPushListenerServiceProvider;

public class MercurygramSettingsActivity extends UniversalFragment {

    private static final int ID_HIDDEN_ACCOUNTS = 0;
    private static final int ID_MESSAGE_DETAILS_MENU = 1;
    private static final int ID_HIDE_CHAT_KEYBOARD = 2;
    private static final int ID_HIDE_ALL_TAB = 3;
    private static final int ID_USE_SYSTEM_FONT = 4;
    private static final int ID_SEND_LARGE_PHOTOS = 10;
    private static final int ID_REAR_ROUND_VIDEOS = 11;
    private static final int ID_DISABLE_LIVE_PHOTOS = 12;
    private static final int ID_DISABLE_AUTO_UPDATE = 20;
    private static final int ID_ACCEPT_PRERELEASES = 21;
    private static final int ID_CHECK_FOR_UPDATES_NOW = 22;
    private static final int ID_DISABLE_UNIFIED_PUSH = 30;
    private static final int ID_UNIFIED_PUSH_DISTRIBUTOR = 31;
    private static final int ID_UNIFIED_PUSH_GATEWAY = 32;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.MercurygramSettings);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (HiddenAccountHelper.shouldShowSettingsEntry(currentAccount)) {
            int hiddenCount = 0;
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (HiddenAccountHelper.isAccountHidden(a)) {
                    hiddenCount++;
                }
            }
            String value = hiddenCount > 0 ? Integer.toString(hiddenCount) : LocaleController.getString(R.string.PasswordOff);
            items.add(UItem.asButton(ID_HIDDEN_ACCOUNTS, R.drawable.msg2_secret, LocaleController.getString(R.string.HiddenAccounts), value));
            items.add(UItem.asShadow(null));
        }

        items.add(UItem.asHeader(LocaleController.getString(R.string.MercurygramSettingsGeneral)));
        items.add(UItem.asCheck(ID_MESSAGE_DETAILS_MENU, LocaleController.getString(R.string.MercurygramMessageDetailsMenu))
                .setChecked(getUserConfig().mg.messageDetailsMenu));
        items.add(UItem.asCheck(ID_HIDE_CHAT_KEYBOARD, LocaleController.getString(R.string.HideChatKeyboard))
                .setChecked(getUserConfig().mg.hideChatKeyboard));
        items.add(UItem.asCheck(ID_HIDE_ALL_TAB, LocaleController.getString(R.string.HideAllTab))
                .setChecked(getUserConfig().mg.hideAllTab));
        items.add(UItem.asCheck(ID_USE_SYSTEM_FONT, LocaleController.getString(R.string.MercurygramUseSystemFont))
                .setChecked(SharedConfig.useSystemFont));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramUseSystemFontAbout)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.MercurygramSettingsMedia)));
        items.add(UItem.asCheck(ID_SEND_LARGE_PHOTOS, LocaleController.getString(R.string.SendLargePhotos))
                .setChecked(getUserConfig().mg.sendLargePhotos));
        items.add(UItem.asCheck(ID_REAR_ROUND_VIDEOS, LocaleController.getString(R.string.RearRoundVideos))
                .setChecked(getUserConfig().mg.rearRoundCamera));
        items.add(UItem.asCheck(ID_DISABLE_LIVE_PHOTOS, LocaleController.getString(R.string.MercurygramDisableLivePhotos))
                .setChecked(getUserConfig().mg.disableLivePhotosByDefault));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramDisableLivePhotosAbout)));

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
        items.add(UItem.asCheck(ID_DISABLE_UNIFIED_PUSH, LocaleController.getString(R.string.MercurygramDisableUnifiedPush))
                .setChecked(SharedConfig.disableUnifiedPush));
        if (!SharedConfig.disableUnifiedPush) {
            String acked = UnifiedPush.getAckDistributor(ApplicationLoader.applicationContext);
            String saved = UnifiedPush.getSavedDistributor(ApplicationLoader.applicationContext);
            CharSequence value;
            if (acked != null) {
                value = distributorLabel(acked);
            } else if (saved != null) {
                // Picked, but no endpoint came back yet (or ever): saying "Not set" here hides a
                // stuck registration behind what looks like an untouched setting.
                value = LocaleController.formatString("UnifiedPushDistributorWaiting",
                        R.string.UnifiedPushDistributorWaiting, distributorLabel(saved));
            } else {
                value = LocaleController.getString(R.string.NotSet);
            }
            items.add(UItem.asButton(ID_UNIFIED_PUSH_DISTRIBUTOR,
                    LocaleController.getString(R.string.UnifiedPushDistributor), value));
            items.add(UItem.asButton(ID_UNIFIED_PUSH_GATEWAY,
                    LocaleController.getString(R.string.UnifiedPushGateway),
                    SharedConfig.unifiedPushGateway));
            if (SharedConfig.isNtfyDefaultServer()) {
                items.add(UItem.asShadow(LocaleController.getString(R.string.NtfyDefaultServerWarningRow)));
            }
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramDisableUnifiedPushAbout)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_HIDDEN_ACCOUNTS:
                presentFragment(new HiddenAccountsActivity());
                break;
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
            case ID_USE_SYSTEM_FONT:
                SharedConfig.toggleUseSystemFont();
                refreshList();
                break;
            case ID_SEND_LARGE_PHOTOS:
                getUserConfig().mg.sendLargePhotos = !getUserConfig().mg.sendLargePhotos;
                AndroidUtilities.photoSize = null;
                getUserConfig().saveConfig(false);
                refreshList();
                break;
            case ID_REAR_ROUND_VIDEOS:
                getUserConfig().mg.rearRoundCamera = !getUserConfig().mg.rearRoundCamera;
                getUserConfig().saveConfig(false);
                refreshList();
                break;
            case ID_DISABLE_LIVE_PHOTOS:
                getUserConfig().mg.disableLivePhotosByDefault = !getUserConfig().mg.disableLivePhotosByDefault;
                getUserConfig().saveConfig(false);
                MediaController.refreshLivePhotoDefault();
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
            case ID_DISABLE_UNIFIED_PUSH:
                SharedConfig.toggleDisableUnifiedPush();
                if (SharedConfig.disableUnifiedPush) {
                    UnifiedPushListenerServiceProvider.applyDisabled();
                } else {
                    UnifiedPushListenerServiceProvider.INSTANCE.onRequestPushToken();
                }
                refreshList();
                break;
            case ID_UNIFIED_PUSH_DISTRIBUTOR:
                showDistributorDialog();
                break;
            case ID_UNIFIED_PUSH_GATEWAY:
                showGatewayDialog();
                break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_UNIFIED_PUSH_DISTRIBUTOR) {
            showUnifiedPushStatsDialog();
            return true;
        }
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

    /** The embedded FCM distributor is our own package: show what it is, not the application id. */
    private static CharSequence distributorLabel(CharSequence distributor) {
        return MgEmbeddedFcmDistributor.isSelf(ApplicationLoader.applicationContext, distributor.toString())
                ? LocaleController.getString(R.string.MercurygramEmbeddedFcm)
                : distributor;
    }

    private void showDistributorDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AtomicReference<Dialog> dialogRef = new AtomicReference<>();
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        List<String> distributors = new ArrayList<>(UnifiedPush.getDistributors(ApplicationLoader.applicationContext));
        // Without Play Services the embedded FCM distributor can only answer
        // REGISTRATION_FAILED, and the connector still lists it (see isAvailable), so offering
        // it would let the user replace a working distributor with a dead one.
        if (!MgEmbeddedFcmDistributor.isAvailable(ApplicationLoader.applicationContext)) {
            distributors.remove(ApplicationLoader.applicationContext.getPackageName());
        }
        CharSequence[] entries = distributors.toArray(new CharSequence[0]);
        String current = UnifiedPush.getAckDistributor(ApplicationLoader.applicationContext);
        if (current == null) {
            current = UnifiedPush.getSavedDistributor(ApplicationLoader.applicationContext);
        }
        for (int i = 0; i < entries.length; ++i) {
            final int index = i;
            boolean embeddedFcm = MgEmbeddedFcmDistributor.isSelf(ApplicationLoader.applicationContext, entries[index].toString());
            RadioColorCell cell = new RadioColorCell(context);
            cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
            cell.setCheckColor(Theme.getColor(Theme.key_radioBackground), Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
            cell.setTextAndValue(distributorLabel(entries[index]), entries[index].equals(current));
            cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            linearLayout.addView(cell);
            cell.setOnClickListener(v -> {
                Runnable select = () -> {
                    UnifiedPushListenerServiceProvider.switchDistributor(entries[index].toString());
                    refreshList();
                    Dialog d = dialogRef.get();
                    if (d != null) d.dismiss();
                };
                if (embeddedFcm) {
                    showDialog(new AlertDialog.Builder(context)
                            .setTitle(LocaleController.getString(R.string.MercurygramEmbeddedFcm))
                            .setMessage(LocaleController.getString(R.string.MercurygramEmbeddedFcmWarning))
                            .setPositiveButton(LocaleController.getString(R.string.OK), (d, w) -> select.run())
                            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                            .create());
                } else {
                    select.run();
                }
            });
        }
        Dialog dialog = new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.UnifiedPushDistributor))
                .setView(linearLayout)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        dialogRef.set(dialog);
        showDialog(dialog);
    }

    private void showUnifiedPushStatsDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        String txt;
        if (UnifiedPushReceiver.getNumOfReceivedNotifications() == 0) {
            txt = "You never received notifications with UnifiedPush since Mercurygram was started.";
        } else {
            long ago = (SystemClock.elapsedRealtime() - UnifiedPushReceiver.getLastReceivedNotification()) / 1000;
            long total = UnifiedPushReceiver.getNumOfReceivedNotifications();
            long ok = UnifiedPushReceiver.getNumDecryptSuccess();
            long fail = UnifiedPushReceiver.getNumDecryptFailed();
            txt = String.format("Last push: %ds ago\nReceived: %d (decrypted: %d, fallback: %d)",
                    ago, total, ok, fail);
        }
        txt += String.format("\n\nWebPush keys: %s", SharedConfig.webPushPublicKey != null ? "present" : "not generated");
        txt += String.format("\nCurrent endpoint: %s", SharedConfig.pushString);
        String failure = UnifiedPushReceiver.getLastRegistrationFailure();
        if (failure != null) {
            txt += String.format("\nLast registration failure: %s", failure);
        }
        String saved = UnifiedPush.getSavedDistributor(ApplicationLoader.applicationContext);
        String acked = UnifiedPush.getAckDistributor(ApplicationLoader.applicationContext);
        txt += String.format("\nSaved distributor: %s", saved != null ? distributorLabel(saved) : "none");
        txt += String.format("\nAcked distributor: %s", acked != null ? distributorLabel(acked) : "none");
        String events = UnifiedPushReceiver.getEventLog();
        if (!events.isEmpty()) {
            txt += "\n\nEvents:\n" + events;
        }
        showDialog(new AlertDialog.Builder(context)
                .setTitle("UnifiedPush Notifications")
                .setMessage(txt)
                .setNegativeButton(LocaleController.getString(R.string.OK), null)
                .create());
    }

    private void showGatewayDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        EditText editText = new EditText(context);
        editText.setText(SharedConfig.unifiedPushGateway);
        editText.setSelectAllOnFocus(true);
        new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.UnifiedPushGateway))
                .setView(editText)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.OK), (dialog, which) -> {
                    SharedConfig.setUnifiedPushGateway(editText.getText().toString().trim());
                    refreshList();
                })
                .show();
    }
}
