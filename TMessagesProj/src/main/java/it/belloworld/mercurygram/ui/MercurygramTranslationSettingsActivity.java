package it.belloworld.mercurygram.ui;

import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

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
import java.util.concurrent.atomic.AtomicReference;

import it.belloworld.mercurygram.translate.MgAidlTranslate;

public class MercurygramTranslationSettingsActivity extends UniversalFragment {

    private static final int ID_MODE = 1;
    private static final int ID_AUTO_FALLBACK = 2;
    private static final int ID_INSTALL_TRANSLATOR = 3;

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
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramTranslationAbout)));

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

            items.add(UItem.asCheck(ID_AUTO_FALLBACK,
                            LocaleController.getString(R.string.MercurygramTranslationAutoFallback))
                    .setChecked(SharedConfig.mg_translateAutoFallback));
            items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramTranslationAutoFallbackAbout)));
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
}
