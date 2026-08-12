package it.belloworld.mercurygram.ui;

import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import it.belloworld.mercurygram.translate.MgAidlTranslate;

public class MercurygramTranslationSettingsActivity extends UniversalFragment {

    private static final int ID_MODE = 1;
    private static final int ID_AUTO_FALLBACK = 2;
    private static final int ID_INSTALL_TRANSLATOR = 3;
    private static final int ID_ALT_ENGINE = 4;
    private static final int ID_ALT_INSTANCE = 5;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.MercurygramTranslationSettings);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.MercurygramTranslationEngine)));
        items.add(UItem.asButton(ID_MODE,
                LocaleController.getString(R.string.MercurygramTranslationMode),
                modeLabel(SharedConfig.mg_translateMode)));
        items.add(UItem.asShadow(MgSettingsScope.withAllAccountsNote(
                LocaleController.getString(R.string.MercurygramTranslationAbout))));

        if (SharedConfig.MG_TRANSLATE_MODE_OFFLINE.equals(SharedConfig.mg_translateMode)) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.MercurygramTranslationOfflineSection)));
            if (!MgAidlTranslate.isProviderInstalled(ApplicationLoader.applicationContext)) {
                items.add(UItem.asButton(ID_INSTALL_TRANSLATOR,
                        LocaleController.getString(R.string.MercurygramTranslationInstallApp),
                        ""));
                items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramTranslationOfflineNoApp)));
            } else {
                items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramTranslationOfflineProviderReady)));
            }

            items.add(MgSettingsScope.globalCheck(ID_AUTO_FALLBACK,
                            LocaleController.getString(R.string.MercurygramTranslationAutoFallback))
                    .setChecked(SharedConfig.mg_translateAutoFallback));
            items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramTranslationAutoFallbackAbout)));
        }

        // Alternative HTTP backend section — visible whenever the alternative
        // path is reachable: in "alternative" mode it's the primary backend,
        // in "offline" mode it's the auto-fallback chain. Hidden for "default"
        // and "cloud" because those paths never call alternativeTranslate.
        if (!SharedConfig.MG_TRANSLATE_MODE_DEFAULT.equals(SharedConfig.mg_translateMode)
                && !SharedConfig.MG_TRANSLATE_MODE_CLOUD.equals(SharedConfig.mg_translateMode)) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.MercurygramTranslationAlternativeSection)));
            items.add(UItem.asButton(ID_ALT_ENGINE,
                    LocaleController.getString(R.string.MercurygramTranslationAlternativeEngine),
                    engineLabel(SharedConfig.mg_translateAltEngine)));
            items.add(UItem.asButton(ID_ALT_INSTANCE,
                    LocaleController.getString(R.string.MercurygramTranslationAlternativeInstance),
                    instanceLabel()));
            items.add(UItem.asShadow(MgSettingsScope.withAllAccountsNote(
                    LocaleController.getString(R.string.MercurygramTranslationAlternativeOperatorDisclosure))));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_MODE:
                showModePicker();
                break;
            case ID_AUTO_FALLBACK:
                SharedConfig.toggleMgTranslateAutoFallback();
                refreshList();
                break;
            case ID_INSTALL_TRANSLATOR:
                Context ctx = getParentActivity();
                if (ctx != null) {
                    Browser.openUrl(ctx, MgAidlTranslate.getFdroidInstallUrl());
                }
                break;
            case ID_ALT_ENGINE:
                showEnginePicker();
                break;
            case ID_ALT_INSTANCE:
                showInstancePicker();
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

    private void showModePicker() {
        Context context = getParentActivity();
        if (context == null) return;
        final String[] values = {
                SharedConfig.MG_TRANSLATE_MODE_DEFAULT,
                SharedConfig.MG_TRANSLATE_MODE_CLOUD,
                SharedConfig.MG_TRANSLATE_MODE_ALTERNATIVE,
                SharedConfig.MG_TRANSLATE_MODE_OFFLINE,
        };
        AtomicReference<Dialog> dialogRef = new AtomicReference<>();
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        String current = SharedConfig.mg_translateMode != null
                ? SharedConfig.mg_translateMode : SharedConfig.MG_TRANSLATE_MODE_DEFAULT;
        for (String v : values) {
            RadioColorCell cell = new RadioColorCell(context);
            cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
            cell.setCheckColor(Theme.getColor(Theme.key_radioBackground),
                    Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
            cell.setTextAndValue(modeLabel(v), v.equals(current));
            cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            linearLayout.addView(cell);
            cell.setOnClickListener(view -> {
                if (!v.equals(SharedConfig.mg_translateMode)) {
                    SharedConfig.setMgTranslateMode(v);
                    refreshList();
                }
                Dialog d = dialogRef.get();
                if (d != null) d.dismiss();
            });
        }
        Dialog dialog = new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.MercurygramTranslationMode))
                .setView(linearLayout)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        dialogRef.set(dialog);
        showDialog(dialog);
    }

    private void showEnginePicker() {
        Context context = getParentActivity();
        if (context == null) return;
        final String[] values = {
                SharedConfig.MG_TRANSLATE_ALT_ENGINE_DUCKDUCKGO,
                SharedConfig.MG_TRANSLATE_ALT_ENGINE_LIBRE,
                SharedConfig.MG_TRANSLATE_ALT_ENGINE_GOOGLE,
                SharedConfig.MG_TRANSLATE_ALT_ENGINE_MYMEMORY,
                SharedConfig.MG_TRANSLATE_ALT_ENGINE_REVERSO,
        };
        AtomicReference<Dialog> dialogRef = new AtomicReference<>();
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        String current = SharedConfig.mg_translateAltEngine != null
                ? SharedConfig.mg_translateAltEngine : SharedConfig.MG_TRANSLATE_ALT_ENGINE_DUCKDUCKGO;
        for (String v : values) {
            RadioColorCell cell = new RadioColorCell(context);
            cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
            cell.setCheckColor(Theme.getColor(Theme.key_radioBackground),
                    Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
            cell.setTextAndValue(engineLabel(v), v.equals(current));
            cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            linearLayout.addView(cell);
            cell.setOnClickListener(view -> {
                if (!v.equals(SharedConfig.mg_translateAltEngine)) {
                    SharedConfig.setMgTranslateAltEngine(v);
                    refreshList();
                }
                Dialog d = dialogRef.get();
                if (d != null) d.dismiss();
            });
        }
        Dialog dialog = new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.MercurygramTranslationAlternativeEngine))
                .setView(linearLayout)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        dialogRef.set(dialog);
        showDialog(dialog);
    }

    private void showInstancePicker() {
        Context context = getParentActivity();
        if (context == null) return;
        final List<String> defaults = SharedConfig.MG_TRANSLATE_ALT_DEFAULT_INSTANCES;
        // Selection model:
        //   AUTO              → "Auto" row
        //   PINNED + matching → that default row
        //   PINNED + unknown  → none (user previously pinned a URL we no
        //                       longer offer; surface as Custom selection
        //                       so they can change it)
        //   CUSTOM            → "Custom URL…" row
        final String mode = SharedConfig.mg_translateAltInstanceMode;
        final String pinned = SharedConfig.mg_translateAltPinnedInstance == null
                ? "" : SharedConfig.mg_translateAltPinnedInstance;

        AtomicReference<Dialog> dialogRef = new AtomicReference<>();
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);

        // Auto row
        addInstanceRow(context, linearLayout, dialogRef,
                LocaleController.getString(R.string.MercurygramTranslationAlternativeInstanceAuto),
                SharedConfig.MG_TRANSLATE_ALT_INSTANCE_MODE_AUTO.equals(mode),
                () -> {
                    SharedConfig.setMgTranslateAltInstanceMode(SharedConfig.MG_TRANSLATE_ALT_INSTANCE_MODE_AUTO);
                    refreshList();
                });

        // One row per default instance
        for (final String url : defaults) {
            final boolean checked = SharedConfig.MG_TRANSLATE_ALT_INSTANCE_MODE_PINNED.equals(mode)
                    && url.equals(pinned);
            addInstanceRow(context, linearLayout, dialogRef,
                    hostLabel(url),
                    checked,
                    () -> {
                        SharedConfig.setMgTranslateAltPinnedInstance(url);
                        SharedConfig.setMgTranslateAltInstanceMode(SharedConfig.MG_TRANSLATE_ALT_INSTANCE_MODE_PINNED);
                        refreshList();
                    });
        }

        // Custom URL row — always opens the edit dialog; commit happens there
        final String customLabel = SharedConfig.MG_TRANSLATE_ALT_INSTANCE_MODE_CUSTOM.equals(mode)
                && SharedConfig.mg_translateAltCustomInstance != null
                && !SharedConfig.mg_translateAltCustomInstance.isEmpty()
                ? hostLabel(SharedConfig.mg_translateAltCustomInstance)
                : LocaleController.getString(R.string.MercurygramTranslationAlternativeInstanceCustom);
        addInstanceRow(context, linearLayout, dialogRef,
                customLabel,
                SharedConfig.MG_TRANSLATE_ALT_INSTANCE_MODE_CUSTOM.equals(mode),
                this::showCustomInstanceDialog);

        Dialog dialog = new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.MercurygramTranslationAlternativeInstance))
                .setView(linearLayout)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        dialogRef.set(dialog);
        showDialog(dialog);
    }

    private void addInstanceRow(Context context, LinearLayout parent,
                                AtomicReference<Dialog> dialogRef,
                                String text, boolean checked, Runnable onPick) {
        RadioColorCell cell = new RadioColorCell(context);
        cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
        cell.setCheckColor(Theme.getColor(Theme.key_radioBackground),
                Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
        cell.setTextAndValue(text, checked);
        cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
        parent.addView(cell);
        cell.setOnClickListener(view -> {
            Dialog d = dialogRef.get();
            if (d != null) d.dismiss();
            onPick.run();
        });
    }

    private void showCustomInstanceDialog() {
        Context context = getParentActivity();
        if (context == null) return;
        EditText editText = new EditText(context);
        editText.setSingleLine(true);
        editText.setHint(LocaleController.getString(R.string.MercurygramTranslationAlternativeInstanceCustomHint));
        editText.setText(SharedConfig.mg_translateAltCustomInstance);
        editText.setSelectAllOnFocus(true);
        new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.MercurygramTranslationAlternativeInstanceCustom))
                .setView(editText)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.OK), (dialog, which) -> {
                    String url = editText.getText().toString().trim();
                    while (url.endsWith("/")) {
                        url = url.substring(0, url.length() - 1);
                    }
                    if (!isValidHttpUrl(url)) {
                        Toast.makeText(context,
                                LocaleController.getString(R.string.MercurygramTranslationAlternativeInstanceInvalid),
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    SharedConfig.setMgTranslateAltCustomInstance(url);
                    SharedConfig.setMgTranslateAltInstanceMode(SharedConfig.MG_TRANSLATE_ALT_INSTANCE_MODE_CUSTOM);
                    refreshList();
                })
                .show();
    }

    private static boolean isValidHttpUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        if (!url.startsWith("https://") && !url.startsWith("http://")) return false;
        try {
            java.net.URI parsed = new java.net.URI(url);
            return parsed.getHost() != null && !parsed.getHost().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static String modeLabel(String mode) {
        if (mode == null) mode = SharedConfig.MG_TRANSLATE_MODE_DEFAULT;
        switch (mode) {
            case SharedConfig.MG_TRANSLATE_MODE_CLOUD:
                return LocaleController.getString(R.string.MercurygramTranslationModeCloud);
            case SharedConfig.MG_TRANSLATE_MODE_ALTERNATIVE:
                return LocaleController.getString(R.string.MercurygramTranslationModeAlternative);
            case SharedConfig.MG_TRANSLATE_MODE_OFFLINE:
                return LocaleController.getString(R.string.MercurygramTranslationModeOffline);
            case SharedConfig.MG_TRANSLATE_MODE_DEFAULT:
            default:
                return LocaleController.getString(R.string.MercurygramTranslationModeDefault);
        }
    }

    private static String engineLabel(String engine) {
        if (engine == null) engine = SharedConfig.MG_TRANSLATE_ALT_ENGINE_DUCKDUCKGO;
        switch (engine) {
            case SharedConfig.MG_TRANSLATE_ALT_ENGINE_LIBRE:
                return LocaleController.getString(R.string.MercurygramTranslationAlternativeEngineLibre);
            case SharedConfig.MG_TRANSLATE_ALT_ENGINE_GOOGLE:
                return LocaleController.getString(R.string.MercurygramTranslationAlternativeEngineGoogle);
            case SharedConfig.MG_TRANSLATE_ALT_ENGINE_MYMEMORY:
                return LocaleController.getString(R.string.MercurygramTranslationAlternativeEngineMyMemory);
            case SharedConfig.MG_TRANSLATE_ALT_ENGINE_REVERSO:
                return LocaleController.getString(R.string.MercurygramTranslationAlternativeEngineReverso);
            case SharedConfig.MG_TRANSLATE_ALT_ENGINE_DUCKDUCKGO:
            default:
                return LocaleController.getString(R.string.MercurygramTranslationAlternativeEngineDuckDuckGo);
        }
    }

    private static String instanceLabel() {
        final String mode = SharedConfig.mg_translateAltInstanceMode;
        if (SharedConfig.MG_TRANSLATE_ALT_INSTANCE_MODE_PINNED.equals(mode)
                && SharedConfig.mg_translateAltPinnedInstance != null
                && !SharedConfig.mg_translateAltPinnedInstance.isEmpty()) {
            return hostLabel(SharedConfig.mg_translateAltPinnedInstance);
        }
        if (SharedConfig.MG_TRANSLATE_ALT_INSTANCE_MODE_CUSTOM.equals(mode)
                && SharedConfig.mg_translateAltCustomInstance != null
                && !SharedConfig.mg_translateAltCustomInstance.isEmpty()) {
            return hostLabel(SharedConfig.mg_translateAltCustomInstance);
        }
        return LocaleController.getString(R.string.MercurygramTranslationAlternativeInstanceAuto);
    }

    private static String hostLabel(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            String host = new java.net.URI(url).getHost();
            return host == null || host.isEmpty() ? url : host;
        } catch (Exception e) {
            return url;
        }
    }
}
