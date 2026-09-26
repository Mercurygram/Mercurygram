package it.belloworld.mercurygram;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.TelegramQRCodeWriter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_update;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LoginActivity;
import org.telegram.ui.TwoStepVerificationActivity;

import java.util.HashMap;

/**
 * Log in by scanning a QR code with an already logged-in device, like Telegram
 * Desktop does (auth.exportLoginToken).
 *
 * The token is shown as tg://login?token=... and exported again right before it
 * expires, or as soon as the server pushes updateLoginToken, which means the other
 * device accepted it: the export then answers loginTokenSuccess, or
 * loginTokenMigrateTo when the account lives on another DC.
 */
public final class MgQrLogin {

    private static MgQrLogin active;

    private final LoginActivity fragment;
    private final int currentAccount;
    private AlertDialog dialog;
    private ImageView imageView;
    private Bitmap bitmap;
    private int requestId;
    private final Runnable refresh = this::export;

    private MgQrLogin(LoginActivity fragment, int currentAccount) {
        this.fragment = fragment;
        this.currentAccount = currentAccount;
    }

    /** Adds the entry link under the phone field and returns the height it takes, in dp. */
    public static int addButton(LinearLayout parent, LoginActivity fragment, int currentAccount) {
        TextView button = new TextView(parent.getContext());
        button.setText(getString(R.string.MercurygramQrLogin));
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        button.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        button.setPadding(dp(4), dp(8), dp(4), dp(8));
        button.setOnClickListener(v -> show(fragment, currentAccount));
        parent.addView(button, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 16, 8, 16, 0));
        return 44;
    }

    /** MessagesController.processUpdates hook for updateShort: the other device accepted the token. */
    public static void onUpdate(TLRPC.Update update, int account) {
        if (!(update instanceof TL_update.TL_updateLoginToken)) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (active != null && active.currentAccount == account) {
                active.cancelPending();
                active.export();
            }
        });
    }

    private static void show(LoginActivity fragment, int currentAccount) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        if (active != null) {
            active.finish();
        }
        active = new MgQrLogin(fragment, currentAccount);
        active.showDialog(activity);
        active.export();
    }

    private void showDialog(Activity activity) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);

        TextView message = new TextView(activity);
        message.setText(getString(R.string.MercurygramQrLoginInfo));
        message.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        message.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        layout.addView(message, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 0));

        imageView = new ImageView(activity);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        layout.addView(imageView, LayoutHelper.createLinear(240, 240, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 8));

        dialog = new AlertDialog.Builder(activity)
                .setTitle(getString(R.string.MercurygramQrLogin))
                .setView(layout)
                .setNegativeButton(getString(R.string.Cancel), null)
                .create();
        dialog.setOnDismissListener(d -> finish());
        fragment.showDialog(dialog);
    }

    private void export() {
        if (active != this) {
            return;
        }
        TLRPC.TL_auth_exportLoginToken req = new TLRPC.TL_auth_exportLoginToken();
        req.api_id = BuildVars.APP_ID;
        req.api_hash = BuildVars.APP_HASH;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig config = UserConfig.getInstance(a);
            if (config.isClientActivated()) {
                req.except_ids.add(config.getClientUserId());
            }
        }
        send(req);
    }

    private void send(TLObject req) {
        requestId = ConnectionsManager.getInstance(currentAccount).sendRequest(req,
                (response, error) -> AndroidUtilities.runOnUIThread(() -> onResponse(response, error)),
                ConnectionsManager.RequestFlagFailOnServerErrors | ConnectionsManager.RequestFlagWithoutLogin);
    }

    private void onResponse(TLObject response, TLRPC.TL_error error) {
        if (active != this) {
            return;
        }
        requestId = 0;
        if (error != null) {
            finish();
            if (error.text != null && error.text.contains("SESSION_PASSWORD_NEEDED")) {
                showPasswordPage();
            } else {
                BulletinFactory.of(fragment).showForError(error);
            }
        } else if (response instanceof TLRPC.TL_auth_loginToken) {
            TLRPC.TL_auth_loginToken token = (TLRPC.TL_auth_loginToken) response;
            render(token.token);
            int left = token.expires - ConnectionsManager.getInstance(currentAccount).getCurrentTime() - 5;
            AndroidUtilities.runOnUIThread(refresh, Math.max(left, 5) * 1000L);
        } else if (response instanceof TLRPC.TL_auth_loginTokenMigrateTo) {
            TLRPC.TL_auth_loginTokenMigrateTo migrate = (TLRPC.TL_auth_loginTokenMigrateTo) response;
            ConnectionsManager.getInstance(currentAccount).setDefaultDatacenterId(migrate.dc_id);
            TLRPC.TL_auth_importLoginToken req = new TLRPC.TL_auth_importLoginToken();
            req.token = migrate.token;
            send(req);
        } else if (response instanceof TLRPC.TL_auth_loginTokenSuccess) {
            finish();
            TLRPC.auth_Authorization auth = ((TLRPC.TL_auth_loginTokenSuccess) response).authorization;
            if (auth instanceof TLRPC.TL_auth_authorization) {
                fragment.onAuthSuccess((TLRPC.TL_auth_authorization) auth);
            }
        }
    }

    /** Same rendering as QRCodeBottomSheet.createQR, which is tied to its sheet. */
    private void render(byte[] token) {
        String link = "tg://login?token=" + Base64.encodeToString(token, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        try {
            HashMap<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 0);
            bitmap = new TelegramQRCodeWriter().encode(link, 768, 768, hints, bitmap);
            imageView.setImageBitmap(bitmap);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Same as the passkey path in LoginActivity.PhoneView: 2FA accounts still need their password. */
    private void showPasswordPage() {
        ConnectionsManager.getInstance(currentAccount).sendRequest(new TL_account.getPassword(), (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null) {
                BulletinFactory.of(fragment).showForError(error);
                return;
            }
            TL_account.Password password = (TL_account.Password) response;
            if (!TwoStepVerificationActivity.canHandleCurrentPassword(password, true)) {
                AlertsCreator.showUpdateAppAlert(fragment.getParentActivity(), getString(R.string.UpdateAppAlert), true);
                return;
            }
            Bundle bundle = new Bundle();
            SerializedData data = new SerializedData(password.getObjectSize());
            password.serializeToStream(data);
            bundle.putString("password", Utilities.bytesToHex(data.toByteArray()));
            fragment.setPage(LoginActivity.VIEW_PASSWORD, true, bundle, false);
        }), ConnectionsManager.RequestFlagFailOnServerErrors | ConnectionsManager.RequestFlagWithoutLogin);
    }

    private void cancelPending() {
        AndroidUtilities.cancelRunOnUIThread(refresh);
        if (requestId != 0) {
            ConnectionsManager.getInstance(currentAccount).cancelRequest(requestId, true);
            requestId = 0;
        }
    }

    private void finish() {
        if (active != this) {
            return;
        }
        active = null;
        cancelPending();
        dialog.dismiss();
    }
}
