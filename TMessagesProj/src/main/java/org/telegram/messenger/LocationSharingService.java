/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;

public class LocationSharingService extends Service implements NotificationCenter.NotificationCenterDelegate {

	private static final String CHANNEL_ID = "mg_live_location_sharing";
	private static final int NOTIFICATION_ID = 6;
	private static final long EMPTY_SHARES_STOP_DELAY_MS = 2500;

	private NotificationCompat.Builder builder;
	private Handler handler;
	private Runnable runnable;
	private Runnable emptyStopRunnable;

	public LocationSharingService() {
		super();
		NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.liveLocationsChanged);
	}

	@Override
	public void onCreate() {
		super.onCreate();
		LiveLocationDebug.log("FGS onCreate pid=" + android.os.Process.myPid());
		handler = new Handler();
		runnable = () -> {
			handler.postDelayed(runnable, 1000);
			Utilities.stageQueue.postRunnable(() -> {
				for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
					LocationController.getInstance(a).update();
				}
			});
		};
		handler.postDelayed(runnable, 1000);
	}

	public IBinder onBind(Intent arg2) {
		return null;
	}

	public void onDestroy() {
		LiveLocationDebug.log("FGS onDestroy pid=" + android.os.Process.myPid() + " shares=" + getInfos().size());
		LocationController.flushAllForProcessDeath();
		super.onDestroy();
		if (handler != null) {
			handler.removeCallbacks(runnable);
			if (emptyStopRunnable != null) {
				handler.removeCallbacks(emptyStopRunnable);
			}
		}
		stopForeground(true);
		NotificationManagerCompat.from(ApplicationLoader.applicationContext).cancel(NOTIFICATION_ID);
		NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.liveLocationsChanged);
	}

	@Override
	public void onTaskRemoved(Intent rootIntent) {
		LiveLocationDebug.log("FGS onTaskRemoved");
		LocationController.flushAllForProcessDeath();
		super.onTaskRemoved(rootIntent);
	}

	@Override
	public void didReceivedNotification(int id, int account, Object... args) {
		if (id == NotificationCenter.liveLocationsChanged) {
			if (handler != null) {
				handler.post(() -> {
					ArrayList<LocationController.SharingLocationInfo> infos = getInfos();
					if (infos.isEmpty()) {
						stopSelf();
					} else {
						if (emptyStopRunnable != null) {
							handler.removeCallbacks(emptyStopRunnable);
							emptyStopRunnable = null;
						}
						updateNotification(true);
					}
				});
			}
		}
	}

	private ArrayList<LocationController.SharingLocationInfo> getInfos() {
		ArrayList<LocationController.SharingLocationInfo> infos = new ArrayList<>();
		for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
			ArrayList<LocationController.SharingLocationInfo> arrayList = LocationController.getInstance(a).sharingLocationsUI;
			if (!arrayList.isEmpty()) {
				infos.addAll(arrayList);
			}
		}
		return infos;
	}

	private static void ensureChannel() {
		if (Build.VERSION.SDK_INT < 26) {
			return;
		}
		NotificationManager nm = (NotificationManager) ApplicationLoader.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE);
		if (nm == null) {
			return;
		}
		NotificationChannel existing = nm.getNotificationChannel(CHANNEL_ID);
		// DEFAULT = visible but silent (no sound/vib). Upgrade old LOW/MIN/NONE channels.
		if (existing != null) {
			int importance = existing.getImportance();
			if (importance > NotificationManager.IMPORTANCE_LOW) {
				return;
			}
			try {
				nm.deleteNotificationChannel(CHANNEL_ID);
			} catch (Exception e) {
				FileLog.e(e);
			}
		}
		NotificationChannel channel = new NotificationChannel(
				CHANNEL_ID,
				LocaleController.getString(R.string.MercurygramLiveLocNotificationChannel),
				NotificationManager.IMPORTANCE_DEFAULT);
		channel.enableLights(false);
		channel.enableVibration(false);
		channel.setSound(null, null);
		channel.setShowBadge(false);
		try {
			nm.createNotificationChannel(channel);
			LiveLocationDebug.log("FGS channel created/updated id=" + CHANNEL_ID + " importance=DEFAULT");
		} catch (Exception e) {
			FileLog.e(e);
			LiveLocationDebug.log("FGS channel create failed: " + e);
		}
	}

	private void updateNotification(boolean post) {
		if (builder == null) {
			return;
		}
		String param;
		ArrayList<LocationController.SharingLocationInfo> infos = getInfos();
		String str;
		if (infos.size() == 1) {
			LocationController.SharingLocationInfo info = infos.get(0);
			long dialogId = info.messageObject.getDialogId();
			int currentAccount = info.messageObject.currentAccount;
			if (DialogObject.isUserDialog(dialogId)) {
				TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
				param = UserObject.getFirstName(user);
				str = LocaleController.getString(R.string.AttachLiveLocationIsSharing);
			} else {
				TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
				if (chat != null) {
					param = chat.title;
				} else {
					param = "";
				}
				str = LocaleController.getString(R.string.AttachLiveLocationIsSharingChat);
			}
		} else {
			param = LocaleController.formatPluralString("Chats", Math.max(infos.size(), 1));
			str = LocaleController.getString(R.string.AttachLiveLocationIsSharingChats);
		}
		String text = String.format(str, LocaleController.getString(R.string.AttachLiveLocation), param);
		builder.setContentText(text);
		if (post) {
			NotificationManagerCompat.from(ApplicationLoader.applicationContext).notify(NOTIFICATION_ID, builder.build());
		}
	}

	private void triggerShareRestore() {
		int touched = 0;
		for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
			if (!UserConfig.getInstance(a).isClientActivated()) {
				continue;
			}
			LocationController.getInstance(a);
			touched++;
		}
		LiveLocationDebug.log("FGS empty-shares restore touch accounts=" + touched);
	}

	public int onStartCommand(Intent intent, int flags, int startId) {
		final boolean empty = getInfos().isEmpty();
		LiveLocationDebug.log("FGS onStartCommand flags=" + flags + " startId=" + startId + " shares=" + getInfos().size());
		if (empty) {
			// Sticky restart / race before LocationController finished loading DB rows.
			triggerShareRestore();
		}
		try {
			ensureChannel();
			if (builder == null) {
				Intent intent2 = new Intent(ApplicationLoader.applicationContext, LaunchActivity.class);
				intent2.setAction("org.tmessages.openlocations");
				intent2.addCategory(Intent.CATEGORY_LAUNCHER);
				PendingIntent contentIntent = PendingIntent.getActivity(ApplicationLoader.applicationContext, 0, intent2, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

				builder = new NotificationCompat.Builder(ApplicationLoader.applicationContext, CHANNEL_ID);
				builder.setSmallIcon(R.drawable.live_loc);
				builder.setContentIntent(contentIntent);
				builder.setContentTitle(LocaleController.getString(R.string.AppName));
				builder.setCategory(NotificationCompat.CATEGORY_SERVICE);
				builder.setOngoing(true);
				builder.setOnlyAlertOnce(true);
				builder.setShowWhen(false);
				builder.setPriority(NotificationCompat.PRIORITY_DEFAULT);
				builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
				Intent stopIntent = new Intent(ApplicationLoader.applicationContext, it.belloworld.mercurygram.ui.MgConfirmStopLiveLocationActivity.class);
				stopIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
						| Intent.FLAG_ACTIVITY_NO_ANIMATION);
				builder.addAction(0, LocaleController.getString(R.string.StopLiveLocation), PendingIntent.getActivity(ApplicationLoader.applicationContext, 2, stopIntent, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
			}

			updateNotification(false);
			Notification notification = builder.build();
			notification.flags |= Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				startForeground(NOTIFICATION_ID, notification,
						android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
								| android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
			} else {
				startForeground(NOTIFICATION_ID, notification);
			}
			LiveLocationDebug.log("FGS startForeground ok id=" + NOTIFICATION_ID + " channel=" + CHANNEL_ID + " empty=" + empty);
		} catch (Throwable e) {
			FileLog.e(e);
			LiveLocationDebug.log("FGS startForeground failed: " + e);
		}
		if (empty && handler != null) {
			if (emptyStopRunnable != null) {
				handler.removeCallbacks(emptyStopRunnable);
			}
			emptyStopRunnable = () -> {
				emptyStopRunnable = null;
				if (getInfos().isEmpty()) {
					LiveLocationDebug.log("FGS stopSelf after empty restore wait");
					stopSelf();
				} else {
					updateNotification(true);
				}
			};
			handler.postDelayed(emptyStopRunnable, EMPTY_SHARES_STOP_DELAY_MS);
		}
		// Sticky so Android restarts the FGS after a background kill while shares remain.
		LiveLocationDebug.log("FGS returning START_STICKY");
		return Service.START_STICKY;
	}
}
