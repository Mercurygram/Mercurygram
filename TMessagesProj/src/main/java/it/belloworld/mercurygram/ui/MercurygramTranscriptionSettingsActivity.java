package it.belloworld.mercurygram.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Components.TranslateAlert2;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import it.belloworld.mercurygram.transcribe.MgWhisperLanguages;
import it.belloworld.mercurygram.transcribe.MgWhisperModel;
import it.belloworld.mercurygram.transcribe.MgWhisperTranscriber;

public class MercurygramTranscriptionSettingsActivity extends UniversalFragment {

    private static final int ID_ENABLE = 1;
    private static final int ID_MODEL = 2;
    private static final int ID_DOWNLOAD = 3;
    private static final int ID_IMPORT = 4;
    private static final int ID_DELETE = 5;
    private static final int ID_LANGUAGE = 6;
    private static final int ID_VAD = 7;

    private static final int REQUEST_PICK_MODEL = 5001;

    private volatile boolean downloading;
    private volatile int downloadPct;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.MercurygramTranscriptionTitle);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asCheck(ID_ENABLE,
                        LocaleController.getString(R.string.MercurygramTranscriptionEnable))
                .setChecked(SharedConfig.mg_transcribeOffline));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramTranscriptionEnableInfo)));

        final MgWhisperModel.Model model = MgWhisperModel.selected();
        final boolean installed = MgWhisperModel.isInstalled(model);

        items.add(UItem.asHeader(LocaleController.getString(R.string.MercurygramTranscriptionModelSection)));
        items.add(UItem.asButton(ID_MODEL,
                LocaleController.getString(R.string.MercurygramTranscriptionModelSection),
                modelLabel(model) + " · " + (installed
                        ? LocaleController.getString(R.string.MercurygramTranscriptionModelInstalled)
                        : LocaleController.getString(R.string.MercurygramTranscriptionModelNotInstalled))));

        if (downloading) {
            items.add(UItem.asButton(ID_DOWNLOAD,
                    LocaleController.getString(R.string.MercurygramTranscriptionDownloading),
                    downloadPct + "%"));
        } else if (installed) {
            items.add(UItem.asButton(ID_DELETE,
                    LocaleController.getString(R.string.MercurygramTranscriptionDelete), ""));
        } else {
            items.add(UItem.asButton(ID_DOWNLOAD,
                    LocaleController.getString(R.string.MercurygramTranscriptionDownload),
                    AndroidUtilities.formatFileSize(model.approxSize)));
        }
        if (!downloading) {
            // Hidden while a download/import is running: both write the same
            // "<model>.part" file, so a concurrent import would corrupt it.
            items.add(UItem.asButton(ID_IMPORT,
                    LocaleController.getString(R.string.MercurygramTranscriptionImport), ""));
        }

        items.add(UItem.asButton(ID_LANGUAGE,
                LocaleController.getString(R.string.MercurygramTranscriptionLanguage),
                languageLabel()));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramTranscriptionLanguageInfo)));

        items.add(UItem.asCheck(ID_VAD,
                        LocaleController.getString(R.string.MercurygramTranscriptionVad))
                .setChecked(SharedConfig.mg_transcribeVad));
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramTranscriptionVadInfo)));

        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramTranscriptionAbout)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_ENABLE:
                SharedConfig.toggleMgTranscribeOffline();
                // Disabling: drop the cached native engine so a ~40-190 MB model
                // isn't retained for the process lifetime after the user turns it off.
                if (!SharedConfig.mg_transcribeOffline) {
                    MgWhisperTranscriber.releaseEngine();
                }
                refreshList();
                break;
            case ID_MODEL:
                showModelPicker();
                break;
            case ID_DOWNLOAD:
                startDownload();
                break;
            case ID_IMPORT:
                pickModelFile();
                break;
            case ID_DELETE:
                MgWhisperModel.delete(MgWhisperModel.selected());
                refreshList();
                break;
            case ID_LANGUAGE:
                showLanguagePicker();
                break;
            case ID_VAD:
                SharedConfig.toggleMgTranscribeVad();
                // Enabling VAD with no model present: fetch the small (~885 kB)
                // Silero model in the background so the toggle takes effect.
                if (SharedConfig.mg_transcribeVad && !MgWhisperModel.isVadInstalled()) {
                    MgWhisperModel.ensureVadDownloaded();
                }
                refreshList();
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

    private void startDownload() {
        if (downloading) {
            return;
        }
        downloading = true;
        downloadPct = 0;
        refreshList();
        MgWhisperModel.download(MgWhisperModel.selected(), new MgWhisperModel.ProgressCallback() {
            @Override
            public void onProgress(long done, long total) {
                int pct = total > 0 ? (int) (done * 100 / total) : 0;
                if (pct != downloadPct) {
                    downloadPct = pct;
                    refreshList();
                }
            }

            @Override
            public void onComplete(File file) {
                downloading = false;
                refreshList();
                toast(R.string.MercurygramTranscriptionDownloaded);
            }

            @Override
            public void onError(String message) {
                downloading = false;
                refreshList();
                toast(R.string.MercurygramTranscriptionDownloadFailed);
            }
        });
    }

    private void pickModelFile() {
        if (downloading) {
            return;
        }
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            getParentActivity().startActivityForResult(intent, REQUEST_PICK_MODEL);
        } catch (Exception e) {
            toast(R.string.MercurygramTranscriptionImportFailed);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_PICK_MODEL && resultCode == android.app.Activity.RESULT_OK
                && data != null && data.getData() != null) {
            final Uri uri = data.getData();
            final Context context = ApplicationLoader.applicationContext;
            downloading = true;
            downloadPct = 0;
            refreshList();
            MgWhisperModel.importFromUri(context, MgWhisperModel.selected(), uri,
                    new MgWhisperModel.ProgressCallback() {
                        @Override
                        public void onProgress(long done, long total) {
                            int pct = total > 0 ? (int) (done * 100 / total) : 0;
                            if (pct != downloadPct) {
                                downloadPct = pct;
                                refreshList();
                            }
                        }

                        @Override
                        public void onComplete(File file) {
                            downloading = false;
                            refreshList();
                            toast(R.string.MercurygramTranscriptionImported);
                        }

                        @Override
                        public void onError(String message) {
                            downloading = false;
                            refreshList();
                            toast(R.string.MercurygramTranscriptionImportFailed);
                        }
                    });
        }
    }

    private void showModelPicker() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final MgWhisperModel.Model[] values = MgWhisperModel.Model.values();
        AtomicReference<Dialog> dialogRef = new AtomicReference<>();
        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        final MgWhisperModel.Model current = MgWhisperModel.selected();
        for (final MgWhisperModel.Model m : values) {
            RadioColorCell cell = new RadioColorCell(context);
            cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
            cell.setCheckColor(Theme.getColor(Theme.key_radioBackground),
                    Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
            cell.setTextAndValue(modelLabel(m), m == current);
            cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            linearLayout.addView(cell);
            cell.setOnClickListener(view -> {
                if (m != MgWhisperModel.selected()) {
                    SharedConfig.setMgTranscribeModel(m.id);
                    refreshList();
                }
                Dialog d = dialogRef.get();
                if (d != null) d.dismiss();
            });
        }
        Dialog dialog = new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.MercurygramTranscriptionModelSection))
                .setView(linearLayout)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        dialogRef.set(dialog);
        showDialog(dialog);
    }

    // Subtitle for the Language row: "Automatic", "Device locale (Italian)", or a
    // specific language name. Mirrors the stored sentinel set (see SharedConfig).
    private String languageLabel() {
        String v = getUserConfig().mg.transcribeLang;
        if (TextUtils.isEmpty(v) || SharedConfig.MG_TRANSCRIBE_LANG_AUTO.equals(v)) {
            return LocaleController.getString(R.string.MercurygramTranscriptionLanguageAuto);
        }
        if (SharedConfig.MG_TRANSCRIBE_LANG_DEVICE.equals(v)) {
            return deviceLocaleLabel();
        }
        String name = TranslateAlert2.languageNameCapital(v);
        return name != null ? name : v;
    }

    private static String deviceLocaleLabel() {
        String base = LocaleController.getString(R.string.MercurygramTranscriptionLanguageDevice);
        String dev = MgWhisperTranscriber.deviceLanguage();
        String name = dev != null ? TranslateAlert2.languageNameCapital(dev) : null;
        return name != null ? base + " (" + name + ")" : base;
    }

    private void showLanguagePicker() {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        // Resolve + sort the whisper-supported languages by display name once.
        final ArrayList<String[]> langs = new ArrayList<>(); // [code, label]
        for (String code : MgWhisperLanguages.CODES) {
            String name = TranslateAlert2.languageNameCapital(code);
            langs.add(new String[]{code, name != null ? name : code});
        }
        Collections.sort(langs, (a, b) -> a[1].compareToIgnoreCase(b[1]));

        final AtomicReference<Dialog> dialogRef = new AtomicReference<>();

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        final EditText search = new EditText(context);
        search.setHint(LocaleController.getString(R.string.Search));
        search.setSingleLine(true);
        search.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        search.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        search.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(8),
                AndroidUtilities.dp(20), AndroidUtilities.dp(8));
        root.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(context);
        final LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(360)));

        final String current = getUserConfig().mg.transcribeLang;
        final Runnable rebuild = () -> {
            list.removeAllViews();
            String q = search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            // Pin Automatic + Device locale on top (only when not searching).
            if (q.isEmpty()) {
                addLangCell(context, list, dialogRef,
                        LocaleController.getString(R.string.MercurygramTranscriptionLanguageAuto),
                        SharedConfig.MG_TRANSCRIBE_LANG_AUTO, current);
                addLangCell(context, list, dialogRef, deviceLocaleLabel(),
                        SharedConfig.MG_TRANSCRIBE_LANG_DEVICE, current);
            }
            for (String[] lang : langs) {
                if (!q.isEmpty() && !lang[1].toLowerCase(java.util.Locale.ROOT).contains(q) && !lang[0].contains(q)) {
                    continue;
                }
                addLangCell(context, list, dialogRef, lang[1], lang[0], current);
            }
        };
        rebuild.run();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { rebuild.run(); }
        });

        Dialog dialog = new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.MercurygramTranscriptionLanguage))
                .setView(root)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        dialogRef.set(dialog);
        showDialog(dialog);
    }

    private void addLangCell(Context context, LinearLayout parent, AtomicReference<Dialog> dialogRef,
                             String label, String value, String current) {
        RadioColorCell cell = new RadioColorCell(context);
        cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
        cell.setCheckColor(Theme.getColor(Theme.key_radioBackground),
                Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
        cell.setTextAndValue(label, value.equals(current));
        cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
        parent.addView(cell);
        cell.setOnClickListener(view -> {
            if (!value.equals(getUserConfig().mg.transcribeLang)) {
                getUserConfig().mg.transcribeLang = value;
                getUserConfig().saveConfig(false);
                refreshList();
            }
            Dialog d = dialogRef.get();
            if (d != null) {
                d.dismiss();
            }
        });
    }

    private static String modelLabel(MgWhisperModel.Model model) {
        switch (model) {
            case BASE:
                return LocaleController.getString(R.string.MercurygramTranscriptionModelBase);
            case SMALL:
                return LocaleController.getString(R.string.MercurygramTranscriptionModelSmall);
            case TINY:
            default:
                return LocaleController.getString(R.string.MercurygramTranscriptionModelTiny);
        }
    }

    private void toast(int res) {
        AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(ApplicationLoader.applicationContext,
                        LocaleController.getString(res), Toast.LENGTH_SHORT).show());
    }
}
