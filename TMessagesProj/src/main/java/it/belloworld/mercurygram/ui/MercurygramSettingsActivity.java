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
import org.telegram.messenger.Utilities;
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
import it.belloworld.mercurygram.transcribe.MgWhisperModel;

public class MercurygramSettingsActivity extends UniversalFragment {

    private static final int ID_HIDDEN_ACCOUNTS = 0;
    private static final int ID_MESSAGE_DETAILS_MENU = 1;
    private static final int ID_HIDE_CHAT_KEYBOARD = 2;
    private static final int ID_HIDE_ALL_TAB = 3;
    private static final int ID_USE_SYSTEM_FONT = 4;
    private static final int ID_SAVED_MESSAGES_HISTORY = 5;
    private static final int ID_CLEAR_SAVED_HISTORY = 6;
    private static final int ID_HIDE_STORIES = 7;
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
    private static final int ID_USE_TOR = 41;
    private static final int ID_TOR_IDLE_TIMEOUT = 42;
    private static final int ID_UPDATE_TOR_PLUGIN = 43;
    private static final int ID_DISABLE_GLOBAL_SEARCH = 44;
    private static final int ID_TRANSLATION = 50;
    private static final int ID_TRANSCRIPTION = 51;

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
        items.add(UItem.asCheck(ID_HIDE_STORIES, LocaleController.getString(R.string.MercurygramHideStories))
                .setChecked(SharedConfig.mg_hideStories));
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

        // F-Droid main on pre-Android-12 can't bind the plugin (plugin's
        // BIND permission uses knownSigner, API 31+ only); skip the row
        // entirely rather than show a toggle that would never work.
        if (!it.belloworld.mercurygram.tor.MgTorClient.isFdroidPreS()) {
            items.add(UItem.asCheck(ID_USE_TOR, LocaleController.getString(R.string.MercurygramTor))
                    .setChecked(SharedConfig.mg_useTor));
            if (SharedConfig.mg_useTor) {
                items.add(UItem.asButton(ID_TOR_IDLE_TIMEOUT,
                        LocaleController.getString(R.string.MercurygramTorIdleTimeout),
                        idleTimeoutLabel(SharedConfig.mg_torIdleStopMinutes)));
            }
            // Manual plugin update/repair (mirrors the main "Check for updates
            // now" button). Shown whenever the plugin is installed on the
            // GitHub channel; F-Droid drives plugin updates from its catalog so
            // the row is hidden there. Subtitle reflects freshness from disk.
            if (it.belloworld.mercurygram.tor.MgTorClient.isPluginInstalled()
                    && !MgUpdateChecker.isFdroidBuild()) {
                // "needs update" = hard floor breach (blocks binding) OR soft
                // versionName drift. Including the floor breach guarantees this
                // repair row offers the install when handleUseTorClick refuses
                // to bind, even in a tag layout where the breach doesn't show
                // up as plain versionName drift.
                boolean pluginNeedsUpdate =
                        it.belloworld.mercurygram.tor.MgTorClient.isPluginUpdateRequired()
                        || it.belloworld.mercurygram.tor.MgTorClient.isPluginUpdateAvailable();
                String pluginSubtitle = pluginNeedsUpdate
                        ? LocaleController.getString(R.string.MercurygramTorPluginOutdated)
                        : LocaleController.getString(R.string.YourVersionIsLatest);
                items.add(UItem.asButton(ID_UPDATE_TOR_PLUGIN,
                        LocaleController.getString(R.string.MercurygramUpdateTorPlugin),
                        pluginSubtitle));
            }
            String torAbout = LocaleController.getString(R.string.MercurygramTorAbout);
            // Surface the unavailable-lib state so the user understands why an
            // enable tap toasts an error instead of starting bootstrap. Hidden
            // when mg_useTor=true (the toggle UI itself reflects the live state,
            // and bailOutUnavailable already flipped the flag off if the lib is
            // missing on this build).
            if (!SharedConfig.mg_useTor && !it.belloworld.mercurygram.tor.MgTorClient.isPluginInstalled()) {
                torAbout = torAbout + "\n\n" + LocaleController.getString(R.string.MercurygramTorPluginMissing);
            }
            items.add(UItem.asShadow(torAbout));
        }

        items.add(UItem.asCheck(ID_DISABLE_GLOBAL_SEARCH,
                        LocaleController.getString(R.string.MercurygramDisableGlobalSearch))
                .setChecked(SharedConfig.mg_disableGlobalSearch));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramDisableGlobalSearchAbout)));

        items.add(UItem.asButton(ID_TRANSLATION,
                LocaleController.getString(R.string.MercurygramTranslationSettings),
                translationModeShortLabel()));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramTranslationRowAbout)));

        items.add(UItem.asButton(ID_TRANSCRIPTION,
                LocaleController.getString(R.string.MercurygramTranscriptionTitle),
                transcriptionShortLabel()));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramTranscriptionEnableInfo)));

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
            case ID_HIDE_STORIES:
                SharedConfig.toggleMgHideStories();
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
            case ID_USE_TOR:
                handleUseTorClick();
                break;
            case ID_TOR_IDLE_TIMEOUT:
                handleTorIdleTimeoutClick();
                break;
            case ID_UPDATE_TOR_PLUGIN:
                if (it.belloworld.mercurygram.tor.MgTorClient.isPluginUpdateRequired()
                        || it.belloworld.mercurygram.tor.MgTorClient.isPluginUpdateAvailable()) {
                    // runPluginInstall shows its own delayed spinner and toasts
                    // MercurygramTorPluginDownloadFailed on error.
                    MgUpdateChecker.runPluginInstall(this::getParentActivity);
                } else {
                    Activity parent = getParentActivity();
                    if (parent != null) {
                        Toast.makeText(parent,
                                LocaleController.getString(R.string.YourVersionIsLatest),
                                Toast.LENGTH_SHORT).show();
                    }
                }
                break;
            case ID_DISABLE_GLOBAL_SEARCH:
                SharedConfig.toggleMgDisableGlobalSearch();
                refreshList();
                break;
            case ID_TRANSCRIPTION:
                presentFragment(new MercurygramTranscriptionSettingsActivity());
                break;
            case ID_TRANSLATION:
                presentFragment(new MercurygramTranslationSettingsActivity());
                break;
        }
    }

    // Subtitle for the Voice-transcription row, mirroring translationModeShortLabel():
    // "Off" when disabled, otherwise the selected model tier so the active choice
    // is visible without opening the sub-screen.
    private static String transcriptionShortLabel() {
        if (!SharedConfig.mg_transcribeOffline) {
            return LocaleController.getString(R.string.MercurygramTranscriptionRowDisabled);
        }
        switch (MgWhisperModel.selected()) {
            case BASE:
                return LocaleController.getString(R.string.MercurygramTranscriptionModelBase);
            case SMALL:
                return LocaleController.getString(R.string.MercurygramTranscriptionModelSmall);
            case TINY:
            default:
                return LocaleController.getString(R.string.MercurygramTranscriptionModelTiny);
        }
    }

    private static String translationModeShortLabel() {
        String mode = SharedConfig.mg_translateMode;
        if (mode == null) mode = SharedConfig.MG_TRANSLATE_MODE_DEFAULT;
        switch (mode) {
            case SharedConfig.MG_TRANSLATE_MODE_CLOUD:
                return LocaleController.getString(R.string.MercurygramTranslationModeCloud);
            case SharedConfig.MG_TRANSLATE_MODE_ALTERNATIVE:
                return LocaleController.getString(R.string.MercurygramTranslationModeAlternative);
            case SharedConfig.MG_TRANSLATE_MODE_OFFLINE:
                return LocaleController.getString(R.string.MercurygramTranslationModeOffline);
            default:
                return LocaleController.getString(R.string.MercurygramTranslationModeDefault);
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

    @Override
    public void onResume() {
        super.onResume();
        // The Translation row's subtitle reflects mg_translateMode, which the
        // user can change in the sub-screen. Refresh on return so the new
        // engine label shows immediately instead of after a full reopen.
        refreshList();
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

    private void handleUseTorClick() {
        Context context = getParentActivity();
        if (context == null) return;
        if (SharedConfig.mg_useTor) {
            SharedConfig.toggleMgUseTor();
            // stop() blocks up to 5s on daemon.join + 2s on the control-port
            // SIGNAL SHUTDOWN write. Dispatch via globalQueue so the UI
            // thread never sees an ANR; globalQueue also serializes against
            // start() so a rapid off/on toggle from another caller (resume,
            // push wake) cannot race the in-flight shutdown.
            Utilities.globalQueue.postRunnable(() ->
                    it.belloworld.mercurygram.tor.MgTorClient.getInstance().stop());
            refreshList();
            return;
        }
        // Proactive compatibility gate: if an installed plugin sits below
        // the AIDL/security floor (MgTorClient.MIN_PLUGIN_MG_VERSION_CODE),
        // force the update BEFORE binding. This is a disk-only check, so it
        // catches a plugin too stale to even bind — relying on the bind to
        // discover the version would be fragile across an AIDL break. The
        // toggle stays OFF; the user updates, returns, and re-enables.
        // (Plugin absent → isPluginUpdateRequired() is false; the
        // PLUGIN_NOT_INSTALLED install flow below still handles install.)
        if (it.belloworld.mercurygram.tor.MgTorClient.isPluginUpdateRequired()) {
            promptInstallOrUpdatePlugin(context,
                    it.belloworld.mercurygram.tor.MgTorClient.State.PLUGIN_OUTDATED);
            return;
        }
        // No pre-check on isAvailable(): pre-bind the state is always
        // PLUGIN_NOT_INSTALLED, which would short-circuit every legitimate
        // first-toggle attempt. The bootstrap dialog's onState listener
        // surfaces PLUGIN_NOT_INSTALLED / OUTDATED / SIGNATURE_MISMATCH if
        // the bind that userInitiatedStart kicks off actually fails.
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.MercurygramTorEnableTitle))
                .setMessage(LocaleController.getString(R.string.MercurygramTorEnableMessage))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.MercurygramTorEnable),
                        (d, which) -> {
                            // Snapshot the user's pre-existing proxy entry
                            // BEFORE start() publishes the blocking stub —
                            // restored from stop() on toggle-off so the
                            // user's SOCKS5 / MTProto-proxy config isn't
                            // silently destroyed by the Tor cycle.
                            it.belloworld.mercurygram.tor.MgTorClient.snapshotCurrentProxy();
                            SharedConfig.toggleMgUseTor();
                            // userInitiatedStart routes through the plugin's
                            // start() path. The plugin deliberately does NOT
                            // reset unexpectedRespawnCount on a user toggle —
                            // a flapping daemon could otherwise burn battery
                            // as resume / push wake-ups keep clearing the
                            // budget. The cap auto-resets on a successful
                            // onBootstrapReady, so a prior session that hit
                            // "respawn gave up" still gets one fresh attempt
                            // per toggle-on (state==STOPPED → start runs
                            // once); a re-crash trips the cap again until
                            // the plugin process is reclaimed.
                            Utilities.globalQueue.postRunnable(() ->
                                    it.belloworld.mercurygram.tor.MgTorClient.getInstance().userInitiatedStart());
                            refreshList();
                            showTorBootstrapDialog();
                        })
                .create();
        // Positive button stays default-themed: enabling Tor is a privacy
        // enhancement, not a destructive or data-loss action. The Telegram
        // convention reserves the red emphasis for destructive paths
        // (Log out, Delete chat, Disable encryption) and Mercurygram's
        // toggle-off branch above flips mg_useTor without a confirm dialog,
        // so the activate dialog is the only Tor confirm flow — no need
        // to flag it as cautionary in red.
        showDialog(dialog);
    }

    private void showTorBootstrapDialog() {
        Context context = getParentActivity();
        if (context == null) return;
        TextView body = new TextView(context);
        body.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), AndroidUtilities.dp(8));
        body.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        body.setText(LocaleController.formatString("MercurygramTorBootstrap",
                R.string.MercurygramTorBootstrap, 0));
        // Tracks whether the dismiss was user-explicit (Cancel button, back
        // press, tap-outside) versus passive (activity recreate on rotation,
        // OOM kill, fragment teardown). Passive dismisses must NOT flip the
        // toggle off — that would silently revert mg_useTor whenever an
        // unrelated event killed the dialog during the 10–30 s cold bootstrap.
        final java.util.concurrent.atomic.AtomicBoolean userCancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.MercurygramTor))
                .setView(body)
                .setNegativeButton(LocaleController.getString(R.string.Cancel),
                        (d, which) -> userCancelled.set(true))
                .create();
        // Fires for back press AND tap-outside (default cancelable=true) —
        // both are user-explicit. Does NOT fire when dismiss() is called for
        // other reasons (activity destruction, ready/abort programmatic
        // dismiss), so it cleanly separates "user said no" from "the window
        // went away on its own".
        dialog.setOnCancelListener(d -> userCancelled.set(true));
        final AtomicReference<it.belloworld.mercurygram.tor.MgTorClient.ProgressListener> selfRef = new AtomicReference<>();
        // Flips true once onReady fires; dismiss before that with userCancelled
        // also true is treated as a user-initiated cancel and tears tor down +
        // flips the toggle back off so the user is not silently left on a
        // half-bootstrapped state.
        final java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean(false);
        // Stops the user-initiated-cancel branch from also firing the
        // settings-side toggle-off path (which itself dispatches stop()).
        final java.util.concurrent.atomic.AtomicBoolean abortHandled = new java.util.concurrent.atomic.AtomicBoolean(false);
        it.belloworld.mercurygram.tor.MgTorClient.ProgressListener listener =
                new it.belloworld.mercurygram.tor.MgTorClient.ProgressListener() {
                    @Override
                    public void onState(it.belloworld.mercurygram.tor.MgTorClient.State state) {
                        // Plugin missing / wrong-sig / outdated → drive the
                        // install-or-update flow. Without this branch the
                        // dialog would hang on "0%" forever because the
                        // controller never reaches onProgress / onReady /
                        // onFailed; PLUGIN_NOT_INSTALLED arrives only via
                        // onState.
                        if (state != it.belloworld.mercurygram.tor.MgTorClient.State.PLUGIN_NOT_INSTALLED
                                && state != it.belloworld.mercurygram.tor.MgTorClient.State.PLUGIN_OUTDATED
                                && state != it.belloworld.mercurygram.tor.MgTorClient.State.PLUGIN_SIGNATURE_MISMATCH) {
                            return;
                        }
                        abortHandled.set(true);
                        final it.belloworld.mercurygram.tor.MgTorClient.State pluginState = state;
                        AndroidUtilities.runOnUIThread(() -> {
                            it.belloworld.mercurygram.tor.MgTorClient.getInstance().removeProgressListener(selfRef.get());
                            try { dialog.dismiss(); } catch (Throwable ignored) {}
                            // Roll the user's mg_useTor flip back: nothing is
                            // routing traffic, the prior proxy snapshot needs
                            // restoring, and silently leaving mg_useTor=true
                            // would re-attempt the bind on every cold start.
                            if (SharedConfig.mg_useTor) {
                                SharedConfig.toggleMgUseTor();
                            }
                            Utilities.globalQueue.postRunnable(() ->
                                    it.belloworld.mercurygram.tor.MgTorClient.getInstance().stop());
                            refreshList();
                            promptInstallOrUpdatePlugin(context, pluginState);
                        });
                    }
                    @Override
                    public void onProgress(int percent, String tag, String summary) {
                        AndroidUtilities.runOnUIThread(() -> body.setText(
                                LocaleController.formatString("MercurygramTorBootstrap",
                                        R.string.MercurygramTorBootstrap, percent)));
                    }
                    @Override
                    public void onReady(int socksPort) {
                        ready.set(true);
                        AndroidUtilities.runOnUIThread(() -> {
                            it.belloworld.mercurygram.tor.MgTorClient.getInstance().removeProgressListener(selfRef.get());
                            try { dialog.dismiss(); } catch (Throwable ignored) {}
                        });
                    }
                    @Override
                    public void onFailed(String reason) {
                        // onBootstrapFailed in the controller already posts
                        // stop() to globalQueue and re-pins the blocking stub.
                        // Mark the abort as handled so the dismiss listener
                        // below doesn't double-dispatch a redundant stop().
                        abortHandled.set(true);
                        AndroidUtilities.runOnUIThread(() -> {
                            it.belloworld.mercurygram.tor.MgTorClient.getInstance().removeProgressListener(selfRef.get());
                            try { dialog.dismiss(); } catch (Throwable ignored) {}
                            Toast.makeText(context, reason != null ? reason : "tor failed", Toast.LENGTH_LONG).show();
                        });
                    }
                };
        selfRef.set(listener);
        it.belloworld.mercurygram.tor.MgTorClient.getInstance().addProgressListener(listener);
        dialog.setOnDismissListener(d -> {
            it.belloworld.mercurygram.tor.MgTorClient.getInstance().removeProgressListener(listener);
            if (ready.get() || abortHandled.get()) return;
            // Passive dismiss (activity recreate on rotation, fragment
            // teardown, system OOM): keep mg_useTor on and let the
            // controller's existing lifecycle drive bootstrap to completion
            // in the background. setOnCancelListener / the Cancel button
            // flip userCancelled iff the user truly opted out.
            if (!userCancelled.get()) return;
            // User dismissed (Cancel / tap-outside / back) before bootstrap
            // completed. Treat as opt-out: flip the toggle, tear down the
            // half-up daemon. Going through globalQueue keeps stop() off the
            // UI thread (5s daemon.join + 2s control-port shutdown).
            if (SharedConfig.mg_useTor) {
                SharedConfig.toggleMgUseTor();
            }
            Utilities.globalQueue.postRunnable(() ->
                    it.belloworld.mercurygram.tor.MgTorClient.getInstance().stop());
            refreshList();
        });
        showDialog(dialog);
    }

    private void promptInstallOrUpdatePlugin(Context context,
                                             it.belloworld.mercurygram.tor.MgTorClient.State state) {
        int msgRes;
        switch (state) {
            case PLUGIN_OUTDATED:
                msgRes = R.string.MercurygramTorPluginOutdated;
                break;
            case PLUGIN_SIGNATURE_MISMATCH:
                msgRes = R.string.MercurygramTorPluginSignatureMismatch;
                break;
            case PLUGIN_NOT_INSTALLED:
            default:
                msgRes = R.string.MercurygramTorInstallPlugin;
                break;
        }
        // Signature mismatch isn't fixable by installing — fall back to a
        // dismissible alert without an "Install" action.
        AlertDialog.Builder b = new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.MercurygramTor))
                .setMessage(LocaleController.getString(msgRes));
        if (state == it.belloworld.mercurygram.tor.MgTorClient.State.PLUGIN_SIGNATURE_MISMATCH) {
            b.setPositiveButton(LocaleController.getString(R.string.OK), null);
        } else {
            b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            b.setPositiveButton(LocaleController.getString(R.string.MercurygramTorInstallPlugin),
                    (d, which) -> {
                        // F-Droid channel: the plugin APK on F-Droid is signed
                        // with a different cert and there's no in-app GitHub
                        // path on that flavor — bounce to the F-Droid page.
                        if (MgUpdateChecker.isFdroidBuild()) {
                            try {
                                context.startActivity(
                                        it.belloworld.mercurygram.tor.MgTorClient.getInstance().buildPluginInstallIntent());
                            } catch (Throwable ignored) {}
                            return;
                        }
                        MgUpdateChecker.runPluginInstall(this::getParentActivity);
                    });
        }
        showDialog(b.create());
    }

    private void handleTorIdleTimeoutClick() {
        Context context = getParentActivity();
        if (context == null) return;
        final int[] choices = {0, 1, 5, 15, 60};
        AtomicReference<Dialog> dialogRef = new AtomicReference<>();
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        int current = SharedConfig.mg_torIdleStopMinutes;
        for (int i = 0; i < choices.length; i++) {
            final int minutes = choices[i];
            RadioColorCell cell = new RadioColorCell(context);
            cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
            cell.setCheckColor(Theme.getColor(Theme.key_radioBackground),
                    Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
            cell.setTextAndValue(idleTimeoutLabel(minutes), minutes == current);
            cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            linearLayout.addView(cell);
            cell.setOnClickListener(v -> {
                SharedConfig.setMgTorIdleStopMinutes(minutes);
                refreshList();
                Dialog d = dialogRef.get();
                if (d != null) d.dismiss();
            });
        }
        Dialog dialog = new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.MercurygramTorIdleTimeout))
                .setView(linearLayout)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        dialogRef.set(dialog);
        showDialog(dialog);
    }

    private static String idleTimeoutLabel(int minutes) {
        switch (minutes) {
            case 0: return LocaleController.getString(R.string.MercurygramTorIdleTimeoutOff);
            case 1: return LocaleController.getString(R.string.MercurygramTorIdleTimeout1min);
            case 5: return LocaleController.getString(R.string.MercurygramTorIdleTimeout5min);
            case 15: return LocaleController.getString(R.string.MercurygramTorIdleTimeout15min);
            case 60: return LocaleController.getString(R.string.MercurygramTorIdleTimeout60min);
            default: return Integer.toString(minutes);
        }
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
}
