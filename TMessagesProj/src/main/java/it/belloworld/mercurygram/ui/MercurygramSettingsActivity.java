package it.belloworld.mercurygram.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import it.belloworld.mercurygram.MgMessageHistory;
import it.belloworld.mercurygram.MgUpdateChecker;

public class MercurygramSettingsActivity extends UniversalFragment {

    private static final int ID_HIDDEN_ACCOUNTS = 0;
    private static final int ID_MESSAGE_DETAILS_MENU = 1;
    private static final int ID_HIDE_CHAT_KEYBOARD = 2;
    private static final int ID_HIDE_ALL_TAB = 3;
    private static final int ID_USE_SYSTEM_FONT = 4;
    private static final int ID_SAVED_MESSAGES_HISTORY = 5;
    private static final int ID_CLEAR_SAVED_HISTORY = 6;
    private static final int ID_SEND_LARGE_PHOTOS = 10;
    private static final int ID_REAR_ROUND_VIDEOS = 11;
    private static final int ID_DISABLE_LIVE_PHOTOS = 12;
    private static final int ID_DISABLE_AUTO_UPDATE = 20;
    private static final int ID_ACCEPT_PRERELEASES = 21;
    private static final int ID_CHECK_FOR_UPDATES_NOW = 22;
    private static final int ID_DISABLE_UNIFIED_PUSH = 30;
    private static final int ID_UNIFIED_PUSH_DISTRIBUTOR = 31;
    private static final int ID_UNIFIED_PUSH_GATEWAY = 32;
    private static final int ID_REDUCE_TRACKING_FINGERPRINT = 40;

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
                .setChecked(getUserConfig().messageDetailsMenu));
        items.add(UItem.asCheck(ID_HIDE_CHAT_KEYBOARD, LocaleController.getString(R.string.HideChatKeyboard))
                .setChecked(getUserConfig().hideChatKeyboard));
        items.add(UItem.asCheck(ID_HIDE_ALL_TAB, LocaleController.getString(R.string.HideAllTab))
                .setChecked(getUserConfig().hideAllTab));
        items.add(UItem.asCheck(ID_USE_SYSTEM_FONT, LocaleController.getString(R.string.MercurygramUseSystemFont))
                .setChecked(SharedConfig.useSystemFont));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramUseSystemFontAbout)));

        items.add(UItem.asCheck(ID_SAVED_MESSAGES_HISTORY, LocaleController.getString(R.string.MercurygramSavedMessagesHistory))
                .setChecked(getUserConfig().savedMessagesHistory));
        if (getUserConfig().savedMessagesHistory) {
            items.add(UItem.asButton(ID_CLEAR_SAVED_HISTORY, LocaleController.getString(R.string.MercurygramClearSavedHistory), ""));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramSavedMessagesHistoryAbout)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.MercurygramSettingsMedia)));
        items.add(UItem.asCheck(ID_SEND_LARGE_PHOTOS, LocaleController.getString(R.string.SendLargePhotos))
                .setChecked(getUserConfig().sendLargePhotos));
        items.add(UItem.asCheck(ID_REAR_ROUND_VIDEOS, LocaleController.getString(R.string.RearRoundVideos))
                .setChecked(getUserConfig().rearRoundCamera));
        items.add(UItem.asCheck(ID_DISABLE_LIVE_PHOTOS, LocaleController.getString(R.string.MercurygramDisableLivePhotos))
                .setChecked(getUserConfig().disableLivePhotosByDefault));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramDisableLivePhotosAbout)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.MercurygramSettingsPrivacy)));
        items.add(UItem.asCheck(ID_REDUCE_TRACKING_FINGERPRINT,
                        LocaleController.getString(R.string.MercurygramReduceTrackingFingerprint))
                .setChecked(SharedConfig.reduceTrackingFingerprint));
        String reduceAbout = LocaleController.getString(R.string.MercurygramReduceTrackingFingerprintAbout);
        // If any active account got force-disabled out of reduced mode by the
        // ladder-exhaustion path (native onReducedTempKeyExhausted), call it
        // out so the user can see the toggle is "on" while specific accounts
        // are actually running standard 24h temp keys.
        String exhaustedNames = collectExhaustedAccountNames();
        if (SharedConfig.reduceTrackingFingerprint && exhaustedNames != null) {
            reduceAbout = reduceAbout + "\n\n" + LocaleController.formatString(
                    "MercurygramReduceTrackingFingerprintExhaustedFooter",
                    R.string.MercurygramReduceTrackingFingerprintExhaustedFooter,
                    exhaustedNames);
        }
        items.add(UItem.asShadow(reduceAbout));

        if (!MgUpdateChecker.isFdroidBuild()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.MercurygramSettingsUpdates)));
            items.add(UItem.asCheck(ID_DISABLE_AUTO_UPDATE, LocaleController.getString(R.string.MercurygramDisableAutoUpdate))
                    .setChecked(SharedConfig.disableAutoUpdate));
            items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramDisableAutoUpdateAbout)));

            // Hidden on the .beta package — that channel already follows
            // /releases unconditionally, so the toggle would be meaningless.
            if (!MgUpdateChecker.isBetaChannel()) {
                boolean effective = MgUpdateChecker.isOnPreReleaseInstall() || SharedConfig.acceptPreReleaseUpdates;
                items.add(UItem.asCheck(ID_ACCEPT_PRERELEASES,
                                LocaleController.getString(R.string.MercurygramAcceptPreReleaseUpdates))
                        .setChecked(effective));
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
            String distributor = UnifiedPush.getAckDistributor(ApplicationLoader.applicationContext);
            items.add(UItem.asButton(ID_UNIFIED_PUSH_DISTRIBUTOR,
                    LocaleController.getString(R.string.UnifiedPushDistributor),
                    distributor != null ? distributor : LocaleController.getString(R.string.NotSet)));
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
                getUserConfig().messageDetailsMenu = !getUserConfig().messageDetailsMenu;
                getUserConfig().saveConfig(false);
                refreshList();
                break;
            case ID_HIDE_CHAT_KEYBOARD:
                getUserConfig().hideChatKeyboard = !getUserConfig().hideChatKeyboard;
                getUserConfig().saveConfig(false);
                refreshList();
                break;
            case ID_HIDE_ALL_TAB:
                getUserConfig().hideAllTab = !getUserConfig().hideAllTab;
                getUserConfig().saveConfig(false);
                refreshList();
                break;
            case ID_USE_SYSTEM_FONT:
                SharedConfig.toggleUseSystemFont();
                refreshList();
                break;
            case ID_SAVED_MESSAGES_HISTORY:
                getUserConfig().savedMessagesHistory = !getUserConfig().savedMessagesHistory;
                getUserConfig().saveConfig(false);
                refreshList();
                break;
            case ID_CLEAR_SAVED_HISTORY:
                confirmClearSavedHistory();
                break;
            case ID_SEND_LARGE_PHOTOS:
                getUserConfig().sendLargePhotos = !getUserConfig().sendLargePhotos;
                AndroidUtilities.photoSize = null;
                getUserConfig().saveConfig(false);
                refreshList();
                break;
            case ID_REAR_ROUND_VIDEOS:
                getUserConfig().rearRoundCamera = !getUserConfig().rearRoundCamera;
                getUserConfig().saveConfig(false);
                refreshList();
                break;
            case ID_DISABLE_LIVE_PHOTOS:
                getUserConfig().disableLivePhotosByDefault = !getUserConfig().disableLivePhotosByDefault;
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
                Toast.makeText(getParentActivity(),
                        LocaleController.getString(R.string.MercurygramCheckForUpdatesToast),
                        Toast.LENGTH_SHORT).show();
                refreshList();
                break;
            case ID_DISABLE_UNIFIED_PUSH:
                confirmAndRestartForUnifiedPushToggle();
                break;
            case ID_UNIFIED_PUSH_DISTRIBUTOR:
                showDistributorDialog();
                break;
            case ID_UNIFIED_PUSH_GATEWAY:
                showGatewayDialog();
                break;
            case ID_REDUCE_TRACKING_FINGERPRINT:
                handleReduceTrackingFingerprintClick();
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

    private void handleAcceptPreReleasesClick() {
        // Post-stable betas (X.Y.Z.W.K) share MG_VC with X.Y.Z.W and can be
        // rolled back; toggling off triggers the download of that stable.
        if (SharedConfig.acceptPreReleaseUpdates) {
            SharedConfig.toggleAcceptPreReleaseUpdates();
            if (MgUpdateChecker.isOnPreReleaseInstall()) {
                MgUpdateChecker.checkForDowngradeToStable();
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
                            SharedConfig.toggleAcceptPreReleaseUpdates();
                            refreshList();
                        })
                .create();
        showDialog(dialog);
        TextView positive = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setTextColor(getThemedColor(Theme.key_text_RedBold));
        }
    }

    private void confirmClearSavedHistory() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.MercurygramClearSavedHistory))
                .setMessage(LocaleController.getString(R.string.MercurygramClearSavedHistoryConfirm))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> {
                    MgMessageHistory.getInstance().clearAll();
                    refreshList();
                })
                .show();
    }

    private void confirmAndRestartForUnifiedPushToggle() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.MercurygramDisableUnifiedPush))
                .setMessage(LocaleController.getString(R.string.MercurygramUnifiedPushRestartConfirm))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.MercurygramRestart), (dialog, which) -> {
                    SharedConfig.toggleDisableUnifiedPush();
                    Activity activity = AndroidUtilities.findActivity(context);
                    if (activity != null) {
                        PackageManager pm = activity.getPackageManager();
                        Intent intent = pm.getLaunchIntentForPackage(activity.getPackageName());
                        activity.finishAffinity();
                        activity.startActivity(intent);
                        System.exit(0);
                    }
                })
                .show();
    }

    private void showDistributorDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AtomicReference<Dialog> dialogRef = new AtomicReference<>();
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        List<String> distributors = UnifiedPush.getDistributors(ApplicationLoader.applicationContext);
        CharSequence[] entries = distributors.toArray(new CharSequence[0]);
        String current = UnifiedPush.getAckDistributor(ApplicationLoader.applicationContext);
        for (int i = 0; i < entries.length; ++i) {
            final int index = i;
            RadioColorCell cell = new RadioColorCell(context);
            cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
            cell.setCheckColor(Theme.getColor(Theme.key_radioBackground), Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
            cell.setTextAndValue(entries[index], entries[index].equals(current));
            cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            linearLayout.addView(cell);
            cell.setOnClickListener(v -> {
                SharedConfig.setUnifiedPushEndpointUrl("");
                UnifiedPush.saveDistributor(ApplicationLoader.applicationContext, entries[index].toString());
                UnifiedPush.register(ApplicationLoader.applicationContext, "default", "Mercurygram WebPush", null);
                refreshList();
                Dialog d = dialogRef.get();
                if (d != null) d.dismiss();
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

    private void handleReduceTrackingFingerprintClick() {
        if (SharedConfig.reduceTrackingFingerprint) {
            SharedConfig.toggleReduceTrackingFingerprint();
            refreshList();
            return;
        }
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.MercurygramReduceTrackingFingerprintWarningTitle))
                .setMessage(LocaleController.getString(R.string.MercurygramReduceTrackingFingerprintWarningMessage))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.MercurygramReduceTrackingFingerprintEnable),
                        (d, which) -> {
                            // Fresh enable cycle: clear any stale per-account
                            // exhaustion flag so the footer doesn't shame the
                            // user with a result from a prior cycle. Native
                            // ladder state is already cleared by the toggle's
                            // setReducedTempKeyMode(false→true) path.
                            clearReducedTrackingExhaustedFlags();
                            SharedConfig.toggleReduceTrackingFingerprint();
                            refreshList();
                        })
                .create();
        showDialog(dialog);
        TextView positive = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setTextColor(getThemedColor(Theme.key_text_RedBold));
        }
    }

    private static String collectExhaustedAccountNames() {
        StringBuilder sb = null;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig uc = UserConfig.getInstance(a);
            if (!uc.isClientActivated() || !uc.mgReducedTrackingExhausted) continue;
            String name = uc.getCurrentUser() != null
                    ? org.telegram.messenger.UserObject.getFirstName(uc.getCurrentUser())
                    : "#" + (a + 1);
            if (sb == null) sb = new StringBuilder(name);
            else sb.append(", ").append(name);
        }
        return sb == null ? null : sb.toString();
    }

    private static void clearReducedTrackingExhaustedFlags() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig uc = UserConfig.getInstance(a);
            if (!uc.mgReducedTrackingExhausted) continue;
            uc.mgReducedTrackingExhausted = false;
            uc.saveConfig(false);
        }
    }
}
