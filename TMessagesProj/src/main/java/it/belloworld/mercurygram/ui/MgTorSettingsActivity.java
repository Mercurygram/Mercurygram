package it.belloworld.mercurygram.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import it.belloworld.mercurygram.MgUpdateChecker;

/**
 * Tor settings, split out of {@link MercurygramSettingsActivity} so the proxy
 * list can present the same screen: that one is reachable from the login
 * screen, and routing the login itself through Tor is the point.
 */
public class MgTorSettingsActivity extends UniversalFragment {

    private static final int ID_USE_TOR = 1;
    private static final int ID_TOR_TRANSPORT = 2;
    private static final int ID_TOR_IDLE_TIMEOUT = 3;
    private static final int ID_UPDATE_TOR_PLUGIN = 4;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.MercurygramTor);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        // F-Droid main on pre-Android-12 can't bind the plugin (plugin's
        // BIND permission uses knownSigner, API 31+ only). Both entry points
        // hide their row there, so this is only a backstop against ever
        // rendering a toggle that could not work.
        if (it.belloworld.mercurygram.tor.MgTorClient.isFdroidPreS()) return;
        items.add(UItem.asCheck(ID_USE_TOR, LocaleController.getString(R.string.MercurygramTor))
                .setChecked(SharedConfig.mg_useTor));
        if (SharedConfig.mg_useTor) {
            items.add(UItem.asButton(ID_TOR_TRANSPORT,
                    LocaleController.getString(R.string.MercurygramTorTransport),
                    transportLabel(SharedConfig.mg_torTransportMode)));
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
        // When Tor is on, the transport picker is visible above; explain
        // when to reach for Snowflake right under it.
        if (SharedConfig.mg_useTor) {
            torAbout = torAbout + "\n\n" + LocaleController.getString(R.string.MercurygramTorTransportAbout);
        }
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

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_USE_TOR:
                handleUseTorClick();
                break;
            case ID_TOR_TRANSPORT:
                handleTorTransportClick();
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

    private void handleTorTransportClick() {
        Context context = getParentActivity();
        if (context == null) return;
        final int[] choices = {
                SharedConfig.MG_TOR_TRANSPORT_DIRECT,
                SharedConfig.MG_TOR_TRANSPORT_SNOWFLAKE,
                SharedConfig.MG_TOR_TRANSPORT_OBFS4,
        };
        AtomicReference<Dialog> dialogRef = new AtomicReference<>();
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        int current = SharedConfig.mg_torTransportMode;
        for (int i = 0; i < choices.length; i++) {
            final int mode = choices[i];
            RadioColorCell cell = new RadioColorCell(context);
            cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
            cell.setCheckColor(Theme.getColor(Theme.key_radioBackground),
                    Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
            cell.setTextAndValue(transportLabel(mode), mode == current);
            cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            linearLayout.addView(cell);
            cell.setOnClickListener(v -> {
                Dialog d = dialogRef.get();
                if (d != null) d.dismiss();
                if (mode == SharedConfig.MG_TOR_TRANSPORT_OBFS4) {
                    // obfs4 has no stock bridges: the user must paste bridge
                    // lines. The dialog commits both the lines and the mode
                    // switch on OK, so picking obfs4 with no lines can't leave a
                    // dead transport selected. Re-picking obfs4 while it's the
                    // current mode reopens the editor (prefilled) for edits.
                    showObfs4BridgesDialog();
                } else {
                    SharedConfig.setMgTorTransportMode(mode);
                    refreshList();
                }
            });
        }
        Dialog dialog = new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.MercurygramTorTransport))
                .setView(linearLayout)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        dialogRef.set(dialog);
        showDialog(dialog);
    }

    private void showObfs4BridgesDialog() {
        Context context = getParentActivity();
        if (context == null) return;
        EditText editText = new EditText(context);
        // A raw EditText ignores the app theme and paints black text, which is
        // invisible on the dark dialog background. Pin the dialog foreground /
        // hint colors like Telegram's own themed inputs do.
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        editText.setHint(LocaleController.getString(R.string.MercurygramTorObfs4BridgesHint));
        editText.setText(SharedConfig.mg_torBridgeLines);
        editText.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editText.setMinLines(3);
        editText.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        int pad = AndroidUtilities.dp(4);
        editText.setPadding(pad, pad, pad, pad);
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        int side = AndroidUtilities.dp(20);
        container.setPadding(side, AndroidUtilities.dp(4), side, 0);
        container.addView(editText, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.MercurygramTorTransportObfs4))
                .setView(container)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.Save), null)
                .create();
        dialog.setOnShowListener(d -> {
            // Override the positive button so an invalid paste keeps the dialog
            // open (the default AlertDialog button always dismisses).
            View btn = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (btn == null) return;
            btn.setOnClickListener(v -> {
                String lines = normalizeObfs4Bridges(editText.getText().toString());
                if (lines.isEmpty()) {
                    Toast.makeText(context,
                            LocaleController.getString(R.string.MercurygramTorObfs4BridgesInvalid),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                SharedConfig.setMgTorBridgeLines(lines);
                SharedConfig.setMgTorTransportMode(SharedConfig.MG_TOR_TRANSPORT_OBFS4);
                refreshList();
                dialog.dismiss();
            });
        });
        showDialog(dialog);
    }

    // Keep only well-formed obfs4 bridge lines (tolerating a leading "Bridge "
    // keyword some sources prepend), one per line. Mirrors the plugin-side
    // MgTorController.parseObfs4Bridges so what the user sees saved is what the
    // daemon will actually use. Returns "" when nothing valid remains.
    private static String normalizeObfs4Bridges(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\\r?\\n")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            if (s.regionMatches(true, 0, "Bridge ", 0, 7)) {
                s = s.substring(7).trim();
            }
            if (!s.regionMatches(true, 0, "obfs4 ", 0, 6)) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString();
    }

    private static String transportLabel(int mode) {
        switch (mode) {
            case SharedConfig.MG_TOR_TRANSPORT_SNOWFLAKE:
                return LocaleController.getString(R.string.MercurygramTorTransportSnowflake);
            case SharedConfig.MG_TOR_TRANSPORT_OBFS4:
                return LocaleController.getString(R.string.MercurygramTorTransportObfs4);
            case SharedConfig.MG_TOR_TRANSPORT_DIRECT:
            default:
                return LocaleController.getString(R.string.MercurygramTorTransportDirect);
        }
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
}
