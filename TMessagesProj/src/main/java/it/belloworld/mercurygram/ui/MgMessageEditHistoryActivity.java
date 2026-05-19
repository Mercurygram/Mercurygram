package it.belloworld.mercurygram.ui;

import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.List;

import it.belloworld.mercurygram.MgMessageHistory;

/**
 * Mercurygram — per-message edit-history viewer. Lists the current server
 * version on top followed by each archived pre-edit version, newest first.
 */
public class MgMessageEditHistoryActivity extends UniversalFragment {

    private final TLRPC.Message currentMessage;
    private final long dialogId;
    private final int messageId;

    private final ArrayList<Row> rows = new ArrayList<>();
    private boolean loaded;

    private static class Row {
        final CharSequence body;
        final String meta;
        final CharSequence detail;
        final String title;

        Row(CharSequence body, String meta, CharSequence detail, String title) {
            this.body = body;
            this.meta = meta;
            this.detail = detail;
            this.title = title;
        }
    }

    public MgMessageEditHistoryActivity(MessageObject current) {
        this.currentMessage = current != null ? current.messageOwner : null;
        this.dialogId = current != null ? current.getDialogId() : 0;
        this.messageId = current != null ? current.getId() : 0;
    }

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.MercurygramEditHistoryTitle);
    }

    @Override
    public boolean onFragmentCreate() {
        loadEntries();
        return super.onFragmentCreate();
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (!loaded) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.Loading)));
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            items.add(UItem.asButton(i, r.body, r.meta));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id < 0 || item.id >= rows.size() || getParentActivity() == null) {
            return;
        }
        Row r = rows.get(item.id);
        new AlertDialog.Builder(getParentActivity())
                .setTitle(r.title)
                .setMessage(r.detail)
                .setPositiveButton(LocaleController.getString(R.string.Copy), (d, w) ->
                        AndroidUtilities.addToClipboard(r.detail))
                .setNegativeButton(LocaleController.getString(R.string.Close), null)
                .show();
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void loadEntries() {
        final int account = currentAccount;
        final long did = dialogId;
        final int mid = messageId;
        Utilities.globalQueue.postRunnable(() -> {
            List<MgMessageHistory.Entry> entries =
                    MgMessageHistory.getInstance().getEditHistoryFor(account, did, mid);
            ArrayList<Row> built = new ArrayList<>(entries.size() + 1);

            String currentLabel = LocaleController.getString(R.string.MercurygramEditHistoryCurrent);
            int editDate = currentMessage != null ? currentMessage.edit_date : 0;
            String currentTs = editDate != 0
                    ? LocaleController.getInstance().getFormatterStats().format(editDate * 1000L)
                    : "";
            String currentBody = textOf(currentMessage);
            built.add(new Row(currentBody,
                    currentLabel + (currentTs.isEmpty() ? "" : " · " + currentTs),
                    currentBody,
                    currentLabel));

            // entries are oldest-first; show newest revision right under "current".
            for (int i = entries.size() - 1; i >= 0; i--) {
                MgMessageHistory.Entry e = entries.get(i);
                String label = LocaleController.formatString(R.string.MercurygramEditHistoryRevision,
                        i + 1, entries.size());
                String when = LocaleController.getInstance().getFormatterStats().format(e.whenMs);
                String body = textOf(e.message);
                built.add(new Row(body, label + " · " + when, body, label));
            }

            AndroidUtilities.runOnUIThread(() -> {
                rows.clear();
                rows.addAll(built);
                loaded = true;
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            });
        });
    }

    private static String textOf(TLRPC.Message m) {
        if (m == null) {
            return "";
        }
        if (m.message != null && !m.message.isEmpty()) {
            return m.message;
        }
        return LocaleController.getString(R.string.MercurygramSavedMessagesEmpty);
    }
}
