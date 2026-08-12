package it.belloworld.mercurygram.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Toast;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

import it.belloworld.mercurygram.emoji.MgEmojiPack;

public class MercurygramEmojiSettingsActivity extends UniversalFragment {

    private static final int ID_ENABLE = 1;
    private static final int ID_IMPORT = 2;
    private static final int ID_REMOVE = 3;

    private static final int REQUEST_PICK_PACK = 5101;

    private volatile boolean importing;
    private volatile int importedSoFar;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.MercurygramEmojiTitle);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(MgSettingsScope.globalCheck(ID_ENABLE, LocaleController.getString(R.string.MercurygramEmojiEnable))
                .setChecked(SharedConfig.mg_useCustomEmojiPack));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramEmojiEnableInfo)));

        if (importing) {
            // Don't probe installedCount() here: it walks packDir() (still the
            // OLD pack — the importer writes to a .tmp dir until it renames on
            // completion), and importedSoFar already carries live progress.
            items.add(UItem.asButton(ID_IMPORT,
                    LocaleController.getString(R.string.MercurygramEmojiImporting),
                    importedSoFar > 0 ? Integer.toString(importedSoFar) : ""));
        } else {
            final int installed = MgEmojiPack.installedCount();
            String status = installed > 0
                    ? LocaleController.formatString("MercurygramEmojiInstalled",
                            R.string.MercurygramEmojiInstalled, installed)
                    : LocaleController.getString(R.string.MercurygramEmojiNotInstalled);
            items.add(UItem.asButton(ID_IMPORT,
                    LocaleController.getString(R.string.MercurygramEmojiImport), status));
            if (installed > 0) {
                items.add(UItem.asButton(ID_REMOVE,
                        LocaleController.getString(R.string.MercurygramEmojiRemove), ""));
            }
        }

        items.add(UItem.asShadow(MgSettingsScope.withAllAccountsNote(
                LocaleController.getString(R.string.MercurygramEmojiAbout))));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_ENABLE:
                SharedConfig.toggleMgUseCustomEmojiPack();
                refreshList();
                break;
            case ID_IMPORT:
                pickPackFile();
                break;
            case ID_REMOVE:
                MgEmojiPack.remove();
                org.telegram.messenger.Emoji.clearEmojiCache();
                refreshList();
                break;
        }
    }

    private void pickPackFile() {
        if (importing) {
            return;
        }
        if (getParentActivity() == null) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            getParentActivity().startActivityForResult(intent, REQUEST_PICK_PACK);
        } catch (Exception e) {
            toast(R.string.MercurygramEmojiImportFailed);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_PICK_PACK && resultCode == android.app.Activity.RESULT_OK
                && data != null && data.getData() != null) {
            final Uri uri = data.getData();
            final Context context = ApplicationLoader.applicationContext;
            importing = true;
            importedSoFar = 0;
            refreshList();
            MgEmojiPack.importFromUri(context, uri, new MgEmojiPack.ProgressCallback() {
                @Override
                public void onProgress(int done) {
                    importedSoFar = done;
                    refreshList();
                }

                @Override
                public void onComplete(int count) {
                    importing = false;
                    // A freshly imported pack is useless if the toggle is off —
                    // turn it on so the import "just works", and repaint.
                    if (!SharedConfig.mg_useCustomEmojiPack) {
                        SharedConfig.toggleMgUseCustomEmojiPack();
                    } else {
                        org.telegram.messenger.Emoji.clearEmojiCache();
                    }
                    refreshList();
                    toast(R.string.MercurygramEmojiImported);
                }

                @Override
                public void onError(String message) {
                    importing = false;
                    refreshList();
                    toast(R.string.MercurygramEmojiImportFailed);
                }
            });
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void toast(int resId) {
        if (getParentActivity() != null) {
            Toast.makeText(getParentActivity(), LocaleController.getString(resId), Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }
}
