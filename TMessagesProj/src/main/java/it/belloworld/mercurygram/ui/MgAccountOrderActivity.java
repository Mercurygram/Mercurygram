package it.belloworld.mercurygram.ui;

import android.content.Context;
import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.SettingsActivity;

import java.util.ArrayList;

import it.belloworld.mercurygram.HiddenAccountHelper;

public class MgAccountOrderActivity extends UniversalFragment {

    private int orderId;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.MercurygramAccountOrder);
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        listView.listenReorder(this::whenReordered);
        listView.allowReorder(true);
        return view;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        ArrayList<Integer> accounts = new ArrayList<>();
        HiddenAccountHelper.collectVisibleAccountNumbers(accounts);
        orderId = adapter.reorderSectionStart();
        for (int i = 0; i < accounts.size(); i++) {
            items.add(SettingsActivity.AccountCell.Factory.of(i, accounts.get(i)));
        }
        adapter.reorderSectionEnd();
        items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramAccountOrderInfo)));
    }

    private void whenReordered(int id, ArrayList<UItem> items) {
        if (id != orderId) {
            return;
        }
        ArrayList<Integer> order = new ArrayList<>();
        for (UItem item : items) {
            order.add(item.intValue);
        }
        HiddenAccountHelper.setVisibleAccountOrder(order);
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
