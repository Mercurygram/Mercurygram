package it.belloworld.mercurygram.ui;

import android.content.Context;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UnifiedPushReceiver;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import org.unifiedpush.android.connector.UnifiedPush;

import java.util.ArrayList;

import it.belloworld.mercurygram.push.MgEmbeddedFcmDistributor;
import it.belloworld.mercurygram.push.UnifiedPushListenerServiceProvider;

/**
 * UnifiedPush settings, split out of {@link MercurygramSettingsActivity}: the distributor
 * list and its registration state do not fit in the value column of a single row, and
 * this screen can follow the registration live instead of showing whatever was true when
 * the list was built.
 */
public class MgUnifiedPushSettingsActivity extends UniversalFragment {

    private static final int ID_DISABLE = 1;
    private static final int ID_GATEWAY = 2;
    private static final int ID_DISTRIBUTOR = 3;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.MercurygramUnifiedPush);
    }

    @Override
    public boolean onFragmentCreate() {
        UnifiedPushListenerServiceProvider.setStateListener(this::refreshList);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        UnifiedPushListenerServiceProvider.setStateListener(null);
        super.onFragmentDestroy();
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        Context context = ApplicationLoader.applicationContext;
        items.add(MgSettingsScope.globalCheck(ID_DISABLE, LocaleController.getString(R.string.MercurygramDisableUnifiedPush))
                .setChecked(SharedConfig.disableUnifiedPush));
        items.add(UItem.asShadow(MgSettingsScope.withAllAccountsNote(
                LocaleController.getString(R.string.MercurygramDisableUnifiedPushAbout))));
        if (SharedConfig.disableUnifiedPush) {
            return;
        }

        ArrayList<String> distributors = new ArrayList<>(UnifiedPush.getDistributors(context));
        // Without Play Services the embedded FCM distributor can only answer
        // REGISTRATION_FAILED, and the connector still lists it (see isAvailable), so offering
        // it would let the user replace a working distributor with a dead one.
        if (!MgEmbeddedFcmDistributor.isAvailable(context)) {
            distributors.remove(context.getPackageName());
        }
        String acked = UnifiedPush.getAckDistributor(context);
        String saved = UnifiedPush.getSavedDistributor(context);
        String current = acked != null ? acked : saved;
        items.add(UItem.asHeader(LocaleController.getString(R.string.UnifiedPushDistributor)));
        for (String pkg : distributors) {
            UItem row = UItem.asRadio(ID_DISTRIBUTOR, MgEmbeddedFcmDistributor.label(pkg)).setChecked(pkg.equals(current));
            // The package the row stands for, so the click handlers need no parallel list.
            row.object = pkg;
            items.add(row);
        }
        CharSequence status = null;
        if (distributors.isEmpty()) {
            // "Not set" under an empty list reads as an untouched setting; nothing can be set
            // here until a distributor app is installed, so say that instead.
            status = LocaleController.getString(R.string.UnifiedPushNoDistributors);
        } else if (acked == null && saved != null) {
            // Picked, but no endpoint came back yet (or ever): staying silent here hides a
            // stuck registration behind what looks like a done setting.
            status = LocaleController.formatString("UnifiedPushDistributorWaiting",
                    R.string.UnifiedPushDistributorWaiting, MgEmbeddedFcmDistributor.label(saved));
        }
        items.add(UItem.asShadow(status));

        items.add(UItem.asHeader(LocaleController.getString(R.string.UnifiedPushGateway)));
        items.add(UItem.asButton(ID_GATEWAY, TextUtils.isEmpty(SharedConfig.unifiedPushGateway)
                ? LocaleController.getString(R.string.NotSet)
                : SharedConfig.unifiedPushGateway));
        items.add(UItem.asShadow(LocaleController.getString(SharedConfig.isNtfyDefaultServer()
                ? R.string.NtfyDefaultServerWarningRow : R.string.UnifiedPushGatewayInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_DISABLE) {
            SharedConfig.toggleDisableUnifiedPush();
            if (SharedConfig.disableUnifiedPush) {
                UnifiedPushListenerServiceProvider.applyDisabled();
            } else {
                UnifiedPushListenerServiceProvider.INSTANCE.onRequestPushToken();
            }
            refreshList();
        } else if (item.id == ID_GATEWAY) {
            showGatewayDialog();
        } else if (item.id == ID_DISTRIBUTOR) {
            selectDistributor((String) item.object);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_DISTRIBUTOR) {
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

    private void selectDistributor(String pkg) {
        Runnable select = () -> UnifiedPushListenerServiceProvider.switchDistributor(pkg);
        // The warning is not optional: keying it off getParentActivity() would silently route
        // push metadata through Google whenever the activity is gone.
        if (MgEmbeddedFcmDistributor.isSelf(ApplicationLoader.applicationContext, pkg)) {
            Context context = getParentActivity();
            if (context == null) {
                return;
            }
            showDialog(new AlertDialog.Builder(context)
                    .setTitle(LocaleController.getString(R.string.MercurygramEmbeddedFcm))
                    .setMessage(LocaleController.getString(R.string.MercurygramEmbeddedFcmWarning))
                    .setPositiveButton(LocaleController.getString(R.string.OK), (d, w) -> select.run())
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .create());
        } else {
            select.run();
        }
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
        txt += String.format("\nSaved distributor: %s", saved != null ? MgEmbeddedFcmDistributor.label(saved) : "none");
        txt += String.format("\nAcked distributor: %s", acked != null ? MgEmbeddedFcmDistributor.label(acked) : "none");
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
