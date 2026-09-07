package it.belloworld.mercurygram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LocationController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

/**
 * Optional confirmation before intentionally stopping outgoing live-location sharing.
 */
public final class MgStopLiveLocationHelper {

	private MgStopLiveLocationHelper() {
	}

	public static boolean shouldConfirm() {
		return SharedConfig.mg_liveLocConfirmStop;
	}

	public static void stopAllSharings() {
		for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
			LocationController.getInstance(a).removeAllLocationSharings();
		}
	}

	public static void confirmStopAll(Activity activity, Theme.ResourcesProvider resourcesProvider, Runnable onStopped) {
		Runnable stop = () -> {
			stopAllSharings();
			if (onStopped != null) {
				onStopped.run();
			}
		};
		if (!shouldConfirm() || activity == null || activity.isFinishing()) {
			stop.run();
			return;
		}
		showConfirm(activity, resourcesProvider,
				LocaleController.getString(R.string.StopLiveLocationAlertToTitle),
				LocaleController.getString(R.string.StopLiveLocationAlertAllText),
				stop);
	}

	public static void confirmStopSharing(Activity activity, Theme.ResourcesProvider resourcesProvider,
			int account, long dialogId, Runnable onStopped) {
		Runnable stop = () -> {
			LocationController.getInstance(account).removeSharingLocation(dialogId);
			if (onStopped != null) {
				onStopped.run();
			}
		};
		if (!shouldConfirm() || activity == null || activity.isFinishing()) {
			stop.run();
			return;
		}
		showConfirm(activity, resourcesProvider,
				LocaleController.getString(R.string.StopLiveLocationAlertToTitle),
				messageForDialog(account, dialogId),
				stop);
	}

	public static CharSequence messageForDialog(int account, long dialogId) {
		MessagesController mc = MessagesController.getInstance(account);
		if (DialogObject.isChatDialog(dialogId)) {
			TLRPC.Chat chat = mc.getChat(-dialogId);
			if (chat != null) {
				return AndroidUtilities.replaceTags(LocaleController.formatString(
						"StopLiveLocationAlertToGroupText", R.string.StopLiveLocationAlertToGroupText, chat.title));
			}
		} else if (DialogObject.isUserDialog(dialogId)) {
			TLRPC.User user = mc.getUser(dialogId);
			if (user != null) {
				return AndroidUtilities.replaceTags(LocaleController.formatString(
						"StopLiveLocationAlertToUserText", R.string.StopLiveLocationAlertToUserText, UserObject.getFirstName(user)));
			}
		}
		return LocaleController.getString(R.string.StopLiveLocationAlertAllText);
	}

	public static void showConfirm(Activity activity, Theme.ResourcesProvider resourcesProvider,
			CharSequence title, CharSequence message, Runnable onConfirm) {
		showConfirm(activity, resourcesProvider, title, message, onConfirm, null);
	}

	public static void showConfirm(Activity activity, Theme.ResourcesProvider resourcesProvider,
			CharSequence title, CharSequence message, Runnable onConfirm, Runnable onDismiss) {
		if (activity == null || activity.isFinishing()) {
			if (onConfirm != null) {
				onConfirm.run();
			}
			return;
		}
		final boolean[] confirmed = {false};
		AlertDialog.Builder builder = new AlertDialog.Builder(activity, resourcesProvider);
		builder.setTitle(title);
		builder.setMessage(message);
		builder.setPositiveButton(LocaleController.getString(R.string.Stop), (dialogInterface, i) -> {
			confirmed[0] = true;
			if (onConfirm != null) {
				onConfirm.run();
			}
		});
		builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
		builder.setOnDismissListener(dialog -> {
			if (onDismiss != null && !confirmed[0]) {
				onDismiss.run();
			}
		});
		AlertDialog alertDialog = builder.create();
		alertDialog.show();
		TextView button = (TextView) alertDialog.getButton(DialogInterface.BUTTON_POSITIVE);
		if (button != null) {
			int color = resourcesProvider != null
					? resourcesProvider.getColorOrDefault(Theme.key_text_RedBold)
					: Theme.getColor(Theme.key_text_RedBold);
			button.setTextColor(color);
		}
	}

	/** Resolve an Activity from a Context (activity or dialog context). */
	public static Activity activityOf(Context context) {
		while (context instanceof android.content.ContextWrapper) {
			if (context instanceof Activity) {
				return (Activity) context;
			}
			context = ((android.content.ContextWrapper) context).getBaseContext();
		}
		return null;
	}
}
