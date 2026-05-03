package it.belloworld.mercurygram.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;

import it.belloworld.mercurygram.HiddenAccountHelper;

public class HiddenAccountsActivity extends UniversalFragment {

    private static final int ID_STEALTH_MODE = 1;
    private static final int ID_SETTINGS_ONLY_WHEN_HIDDEN = 2;
    private static final int ACCOUNT_ID_OFFSET = 100;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.HiddenAccounts);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        boolean hasPasscode = !SharedConfig.passcodeHash.isEmpty();
        boolean canUseHiddenAccounts = HiddenAccountHelper.canUseHiddenAccounts();
        boolean hasHiddenAccounts = HiddenAccountHelper.hasAnyHiddenAccounts();

        items.add(UItem.asHeader(LocaleController.getString(R.string.HiddenAccountsOptions)));
        items.add(UItem.asCheck(ID_STEALTH_MODE, LocaleController.getString(R.string.HiddenAccountsStealthMode))
                .setChecked(HiddenAccountHelper.isStealthModeEnabled())
                .setEnabled(!hasPasscode));
        items.add(UItem.asCheck(ID_SETTINGS_ONLY_WHEN_HIDDEN, LocaleController.getString(R.string.HiddenAccountsOnlyShowWhenSignedInAsHiddenAccount))
                .setChecked(HiddenAccountHelper.isSettingsVisibleOnlyWhenSignedInAsHiddenAccount())
                .setEnabled(hasHiddenAccounts));
        if (hasPasscode) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.HiddenAccountsPasscodeModeInfo)));
        } else if (HiddenAccountHelper.isStealthModeEnabled()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.HiddenAccountsStealthModeInfo)));
        } else {
            items.add(UItem.asShadow(LocaleController.getString(R.string.HiddenAccountsNeedsPasscodeOrStealthMode)));
        }

        items.add(UItem.asHeader(LocaleController.getString(R.string.HiddenAccountsAccounts)));
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            TLRPC.User user = UserConfig.getInstance(a).getCurrentUser();
            if (user == null) {
                continue;
            }
            String value = LocaleController.getString(HiddenAccountHelper.isAccountHidden(a) ? R.string.HiddenAccountsStateHidden : R.string.HiddenAccountsStateVisible);
            items.add(UItem.asButton(ACCOUNT_ID_OFFSET + a, R.drawable.msg2_secret, UserObject.getUserName(user), value).setEnabled(canUseHiddenAccounts || HiddenAccountHelper.isAccountHidden(a)));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.HiddenAccountsAccountsInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_STEALTH_MODE) {
            toggleStealthMode();
            return;
        }
        if (item.id == ID_SETTINGS_ONLY_WHEN_HIDDEN) {
            toggleSettingsVisibility();
            return;
        }
        if (item.id >= ACCOUNT_ID_OFFSET) {
            int account = item.id - ACCOUNT_ID_OFFSET;
            if (HiddenAccountHelper.isAccountHidden(account)) {
                showHiddenAccountActions(account);
            } else {
                startHideAccountFlow(account);
            }
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshList();
    }

    private void toggleStealthMode() {
        boolean enabled = !HiddenAccountHelper.isStealthModeEnabled();
        if (enabled && !SharedConfig.passcodeHash.isEmpty()) {
            AlertsCreator.showSimpleAlert(this, LocaleController.getString(R.string.HiddenAccountsStealthModeRequiresPasscodeOff));
            return;
        }
        if (!enabled && SharedConfig.passcodeHash.isEmpty() && HiddenAccountHelper.hasAnyHiddenAccounts()) {
            AlertsCreator.showSimpleAlert(this, LocaleController.getString(R.string.HiddenAccountsDisableStealthNeedsPasscode));
            return;
        }
        HiddenAccountHelper.setStealthModeEnabled(enabled);
        refreshAndNotify(UserConfig.selectedAccount);
    }

    private void toggleSettingsVisibility() {
        if (!HiddenAccountHelper.hasAnyHiddenAccounts()) {
            return;
        }
        HiddenAccountHelper.setSettingsVisibleOnlyWhenSignedInAsHiddenAccount(!HiddenAccountHelper.isSettingsVisibleOnlyWhenSignedInAsHiddenAccount());
        refreshAndNotify(UserConfig.selectedAccount);
    }

    private void startHideAccountFlow(int account) {
        if (!HiddenAccountHelper.canUseHiddenAccounts()) {
            AlertsCreator.showSimpleAlert(this, LocaleController.getString(R.string.HiddenAccountsNeedsPasscodeOrStealthMode));
            return;
        }
        if (!HiddenAccountHelper.canHideAccount(account)) {
            AlertsCreator.showSimpleAlert(this, LocaleController.getString(R.string.HiddenAccountsNeedVisibleAccount));
            return;
        }
        showSetUnlockCodeDialog(account, false);
    }

    private void showHiddenAccountActions(int account) {
        showCurrentCodeDialog(account, () -> showHiddenAccountActionsVerified(account));
    }

    private void showHiddenAccountActionsVerified(int account) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        CharSequence[] items = new CharSequence[] {
                LocaleController.getString(R.string.HiddenAccountsChangeUnlockCode),
                LocaleController.getString(R.string.HiddenAccountsRemoveHiddenAccount)
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(UserObject.getUserName(UserConfig.getInstance(account).getCurrentUser()));
        builder.setItems(items, (dialog, which) -> {
            if (which == 0) {
                showSetUnlockCodeDialog(account, true);
            } else if (which == 1) {
                showRemoveHiddenAccountDialog(account);
            }
        });
        showDialog(builder.create());
    }

    private void showCurrentCodeDialog(int account, Runnable onVerified) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        EditTextBoldCursor codeField = createCodeField(context, LocaleController.getString(R.string.HiddenAccountsCurrentUnlockCode));
        showCodeEntryDialog(
                context,
                LocaleController.getString(R.string.HiddenAccountsConfirmCurrentUnlockCode),
                LocaleController.getString(R.string.HiddenAccountsConfirmCurrentUnlockCodeInfo),
                LocaleController.getString(R.string.Continue),
                new EditTextBoldCursor[] {codeField},
                dialog -> {
                    if (!HiddenAccountHelper.verifyUnlockCode(account, codeField.getText().toString())) {
                        AlertsCreator.showSimpleAlert(this, LocaleController.getString(R.string.HiddenAccountsIncorrectCurrentUnlockCode));
                        return;
                    }
                    dialog.dismiss();
                    onVerified.run();
                });
    }

    private void showRemoveHiddenAccountDialog(int account) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog alertDialog = new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.HiddenAccountsRemoveHiddenAccount))
                .setMessage(LocaleController.getString(R.string.HiddenAccountsRemoveHiddenAccountConfirm))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.HiddenAccountsShowAccount), (dialog, which) -> {
                    HiddenAccountHelper.removeHiddenAccount(account);
                    if (UserConfig.selectedAccount == account) {
                        HiddenAccountHelper.clearUnlockedHiddenAccount();
                    }
                    refreshAndNotify(account);
                })
                .create();
        showDialog(alertDialog);
        TextView button = (TextView) alertDialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(getThemedColor(Theme.key_text_RedBold));
        }
    }

    private void showSetUnlockCodeDialog(int account, boolean replacing) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        EditTextBoldCursor firstField = createCodeField(context, LocaleController.getString(R.string.HiddenAccountsEnterUnlockCode));
        EditTextBoldCursor secondField = createCodeField(context, LocaleController.getString(R.string.HiddenAccountsConfirmUnlockCode));
        showCodeEntryDialog(
                context,
                LocaleController.getString(replacing ? R.string.HiddenAccountsChangeUnlockCode : R.string.HiddenAccountsHideAccount),
                LocaleController.getString(R.string.HiddenAccountsUnlockCodeInfo),
                LocaleController.getString(replacing ? R.string.Save : R.string.HiddenAccountsHideAccountAction),
                new EditTextBoldCursor[] {firstField, secondField},
                dialog -> {
                    String firstCode = firstField.getText().toString();
                    String secondCode = secondField.getText().toString();
                    int validation = HiddenAccountHelper.validateUnlockCode(account, firstCode);
                    if (validation == HiddenAccountHelper.VALIDATE_CODE_INVALID) {
                        AlertsCreator.showSimpleAlert(this, LocaleController.getString(R.string.HiddenAccountsInvalidUnlockCode));
                        return;
                    } else if (validation == HiddenAccountHelper.VALIDATE_CODE_DUPLICATE) {
                        AlertsCreator.showSimpleAlert(this, LocaleController.getString(R.string.HiddenAccountsDuplicateUnlockCode));
                        return;
                    } else if (validation == HiddenAccountHelper.VALIDATE_CODE_APP_PASSCODE) {
                        AlertsCreator.showSimpleAlert(this, LocaleController.getString(R.string.HiddenAccountsUnlockCodeMatchesAppPasscode));
                        return;
                    }
                    if (!TextUtils.equals(firstCode, secondCode)) {
                        AlertsCreator.showSimpleAlert(this, LocaleController.getString(R.string.HiddenAccountsUnlockCodesDoNotMatch));
                        return;
                    }
                    HiddenAccountHelper.setHiddenAccountCode(account, firstCode);
                    HiddenAccountHelper.clearUnlockedHiddenAccount();
                    dialog.dismiss();
                    refreshAndNotify(account);
                    if (!replacing && UserConfig.selectedAccount == account && LaunchActivity.instance != null) {
                        int fallbackAccount = HiddenAccountHelper.getFallbackVisibleAccount(account);
                        if (fallbackAccount >= 0) {
                            LaunchActivity.instance.switchToAccount(fallbackAccount, true);
                        }
                    }
                });
    }

    private void showCodeEntryDialog(Context context, CharSequence title, CharSequence message, CharSequence positiveLabel, EditTextBoldCursor[] fields, OnPositiveClickListener onPositive) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), 0);
        for (int i = 0; i < fields.length; i++) {
            int topMargin = i == 0 ? 8 : 12;
            layout.addView(fields[i], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, Gravity.FILL_HORIZONTAL, 0, topMargin, 0, 0));
        }

        AlertDialog alertDialog = new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setView(layout)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(positiveLabel, null)
                .create();
        alertDialog.setOnShowListener(dialog -> {
            fields[0].requestFocus();
            AndroidUtilities.showKeyboard(fields[0]);
            alertDialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> onPositive.onClick(alertDialog));
        });
        showDialog(alertDialog);
    }

    private interface OnPositiveClickListener {
        void onClick(AlertDialog dialog);
    }

    private EditTextBoldCursor createCodeField(Context context, CharSequence hint) {
        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setBackground(null);
        editText.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated), Theme.getColor(Theme.key_text_RedBold));
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHint(hint);
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        editText.setTransformationMethod(PasswordTransformationMethod.getInstance());
        editText.setFilters(new InputFilter[] {new InputFilter.LengthFilter(HiddenAccountHelper.CODE_LENGTH)});
        editText.setSingleLine(true);
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setCursorSize(AndroidUtilities.dp(20));
        editText.setCursorWidth(1.5f);
        editText.setPadding(0, AndroidUtilities.dp(4), 0, 0);
        return editText;
    }

    private void refreshList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void refreshAndNotify(int account) {
        ContactsController.getInstance(account).checkAppAccount();
        NotificationsController.getInstance(account).showNotifications();
        NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
        refreshList();
    }
}
