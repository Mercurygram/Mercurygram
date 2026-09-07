package it.belloworld.mercurygram.ui;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LocationController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.SharingLiveLocationCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.LocationActivity;
import org.telegram.ui.MainTabsActivity;

import java.util.ArrayList;
import java.util.Objects;

import it.belloworld.mercurygram.MgIncomingLiveLocation;
import it.belloworld.mercurygram.MgIncomingLiveLocationStore;
import it.belloworld.mercurygram.MgLiveLocationViewerLog;

/**
 * Hub for call ringing status and live-location / proximity services.
 * Placement: Settings always; optional extra bottom-bar tab ({@link SharedConfig#mg_servicesAsMainTab}).
 */
public class MgServicesActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate, MainTabsActivity.TabFragmentDelegate {

	private static final int ID_CALLS_RING = 1;
	private static final int ID_CALLS_PERMS = 2;
	private static final int ID_LOC_BEHAVIOUR = 4;
	private static final int ID_PLACEMENT = 5;
	private static final int ID_LOC_SHOW_ALL_MAP = 6;
	private static final int ID_SCAN_PROGRESS = 7;
	private static final int ID_OUTGOING_BASE = 1000;
	private static final int ID_INCOMING_BASE = 2000;
	private static final int ID_PROXIMITY_BASE = 3000;
	private static final int ID_VIEWER_BASE = 4000;
	private static final int ID_VIEWERS_MORE = 4500;
	private static final int ID_SLOT_MASK = 999;

	private static final int LIVE_LOC_CELL_HEIGHT_DP = 66;

	private final ArrayList<LocationController.SharingLocationInfo> outgoing = new ArrayList<>();
	private final ArrayList<MgIncomingLiveLocation> incoming = new ArrayList<>();
	private final ArrayList<LocationController.SharingLocationInfo> proximity = new ArrayList<>();
	private final ArrayList<MgLiveLocationViewerLog.Entry> viewers = new ArrayList<>();
	private boolean viewersExpanded;
	private int scanProgressPercent = 100;
	private int lastIncomingFingerprint = Integer.MIN_VALUE;
	private boolean lastScanning;

	private final boolean asMainTab;

	public MgServicesActivity() {
		this(false);
	}

	public MgServicesActivity(boolean asMainTab) {
		super();
		this.asMainTab = asMainTab;
	}

	@Override
	public ActionBar createActionBar(Context context) {
		ActionBar bar = super.createActionBar(context);
		if (asMainTab) {
			bar.setAddToContainer(false);
			bar.setOccupyStatusBar(true);
		}
		return bar;
	}

	@Override
	public View createView(Context context) {
		View view = super.createView(context);
		if (asMainTab && actionBar != null) {
			actionBar.setAllowOverlayTitle(false);
			actionBar.setBackButtonDrawable(null);
			actionBar.setBackButtonImage(0);
			AndroidUtilities.removeFromParent(actionBar);
			if (view instanceof FrameLayout) {
				((FrameLayout) view).addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
			}
			actionBar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyMainTabListPadding());
		}
		if (asMainTab && listView != null) {
			listView.setClipToPadding(false);
			applyMainTabListPadding();
		}
		return view;
	}

	private void applyMainTabListPadding() {
		if (!asMainTab || listView == null) {
			return;
		}
		int top = ActionBar.getCurrentActionBarHeight();
		if (actionBar == null || actionBar.getOccupyStatusBar()) {
			top += AndroidUtilities.statusBarHeight;
		}
		if (actionBar != null && actionBar.getMeasuredHeight() > top) {
			top = actionBar.getMeasuredHeight();
		}
		int bottom = AndroidUtilities.dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS);
		if (listView.getPaddingTop() != top || listView.getPaddingBottom() != bottom) {
			listView.setPadding(0, top, 0, bottom);
		}
	}

	@Override
	protected CharSequence getTitle() {
		return LocaleController.getString(R.string.MercurygramServices);
	}

	@Override
	public boolean onFragmentCreate() {
		NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.liveLocationsChanged);
		rebuildOutgoingAndProximity();
		refreshIncoming();
		refreshViewers();
		return super.onFragmentCreate();
	}

	@Override
	public void onFragmentDestroy() {
		NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.liveLocationsChanged);
		super.onFragmentDestroy();
	}

	@Override
	public void didReceivedNotification(int id, int account, Object... args) {
		if (id == NotificationCenter.liveLocationsChanged) {
			rebuildOutgoingAndProximity();
			refreshIncomingFromCache();
			refreshViewers();
			refreshList();
		}
	}

	@Override
	protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
		items.add(UItem.asHeader(LocaleController.getString(R.string.MercurygramServicesCalls)));
		items.add(MgSettingsScope.globalCheck(ID_CALLS_RING, LocaleController.getString(R.string.MercurygramServicesCallsRing))
				.setChecked(SharedConfig.mg_callsRingEnabled));
		items.add(UItem.asButton(ID_CALLS_PERMS, LocaleController.getString(R.string.MercurygramServicesCallsPerms),
				callsPermissionSummary()));
		items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramServicesCallsAbout)));

		items.add(UItem.asHeader(LocaleController.getString(R.string.MercurygramServicesLocation)));
		items.add(UItem.asButton(ID_LOC_BEHAVIOUR, LocaleController.getString(R.string.MercurygramShareLocationBehaviour),
				LocaleController.getString(R.string.MercurygramShareLocationBehaviourSummary)));

		items.add(UItem.asHeader(sectionHeader(R.string.MercurygramLiveLocIncomingHeader, incoming.size())));
		if (!incoming.isEmpty()) {
			items.add(UItem.asButton(ID_LOC_SHOW_ALL_MAP, LocaleController.getString(R.string.MercurygramServicesShowAllIncomingMap),
					LocaleController.formatString("MercurygramServicesShowAllIncomingMapCount",
							R.string.MercurygramServicesShowAllIncomingMapCount, incoming.size())));
		}
		if (MgIncomingLiveLocationStore.isScanning()) {
			items.add(UItem.asShadow(ID_SCAN_PROGRESS, formatScanProgress()));
		}
		if (incoming.isEmpty() && !MgIncomingLiveLocationStore.isScanning()) {
			items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramLiveLocIncomingEmpty)));
		} else {
			for (int a = 0; a < incoming.size(); a++) {
				MgIncomingLiveLocation loc = incoming.get(a);
				items.add(IncomingLiveLocationFactory.of(stableIncomingId(loc), loc));
			}
		}

		items.add(UItem.asHeader(sectionHeader(R.string.MercurygramLiveLocOutgoingHeader, outgoing.size())));
		if (outgoing.isEmpty()) {
			items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramServicesLocationEmpty)));
		} else {
			for (int a = 0; a < outgoing.size(); a++) {
				LocationController.SharingLocationInfo info = outgoing.get(a);
				items.add(OutgoingLiveLocationFactory.of(stableOutgoingId(info), info));
			}
		}

		items.add(UItem.asHeader(sectionHeader(R.string.MercurygramLiveLocViewedByHeader, viewers.size())));
		if (viewers.isEmpty()) {
			items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramLiveLocViewedByEmpty)));
		} else {
			int shown = viewersExpanded
					? viewers.size()
					: Math.min(MgLiveLocationViewerLog.UI_COLLAPSED_COUNT, viewers.size());
			for (int a = 0; a < shown; a++) {
				MgLiveLocationViewerLog.Entry entry = viewers.get(a);
				items.add(UItem.asButton(ID_VIEWER_BASE + a,
						MgLiveLocationViewerLog.formatViewerName(entry),
						formatViewerSubtitle(entry)));
			}
			if (viewers.size() > MgLiveLocationViewerLog.UI_COLLAPSED_COUNT) {
				if (viewersExpanded) {
					items.add(UItem.asButton(ID_VIEWERS_MORE,
							LocaleController.getString(R.string.ShowLess), ""));
				} else {
					int more = viewers.size() - MgLiveLocationViewerLog.UI_COLLAPSED_COUNT;
					items.add(UItem.asButton(ID_VIEWERS_MORE,
							LocaleController.formatString("MercurygramLiveLocViewedByShowMore",
									R.string.MercurygramLiveLocViewedByShowMore, more),
							""));
				}
			}
		}

		items.add(UItem.asHeader(LocaleController.getString(R.string.MercurygramServicesProximity)));
		if (proximity.isEmpty()) {
			items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramServicesProximityEmpty)));
		} else {
			for (int a = 0; a < proximity.size(); a++) {
				LocationController.SharingLocationInfo info = proximity.get(a);
				items.add(UItem.asButton(ID_PROXIMITY_BASE + a, formatOutgoingTitle(info),
						LocaleController.formatString("MercurygramServicesProximityMeters",
								R.string.MercurygramServicesProximityMeters, info.proximityMeters)));
			}
		}

		items.add(UItem.asHeader(LocaleController.getString(R.string.MercurygramServicesPlacement)));
		items.add(MgSettingsScope.globalCheck(ID_PLACEMENT, LocaleController.getString(R.string.MercurygramServicesAlsoMainTab))
				.setChecked(SharedConfig.mg_servicesAsMainTab));
		items.add(UItem.asShadow(LocaleController.getString(R.string.MercurygramServicesAlsoMainTabAbout)));
	}

	@Override
	protected void onClick(UItem item, View view, int position, float x, float y) {
		if (item.id == ID_CALLS_RING) {
			SharedConfig.setMgCallsRingEnabled(!SharedConfig.mg_callsRingEnabled);
			refreshList();
			return;
		}
		if (item.id == ID_CALLS_PERMS) {
			openCallsPermissionHelp();
			return;
		}
		if (item.id == ID_LOC_BEHAVIOUR) {
			presentFragment(new MgShareLocationBehaviourActivity());
			return;
		}
		if (item.id == ID_LOC_SHOW_ALL_MAP) {
			openAllIncomingMap();
			return;
		}
		if (item.id == ID_VIEWERS_MORE) {
			viewersExpanded = !viewersExpanded;
			refreshList();
			return;
		}
		if (item.id == ID_PLACEMENT) {
			boolean asTab = !SharedConfig.mg_servicesAsMainTab;
			SharedConfig.setMgServicesAsMainTab(asTab);
			NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.servicesTabVisibleToggled);
			if (asMainTab && !asTab) {
				finishFragment();
			} else {
				refreshList();
			}
			return;
		}
		if (item.id >= ID_PROXIMITY_BASE && item.id < ID_PROXIMITY_BASE + proximity.size()) {
			openOutgoing(proximity.get(item.id - ID_PROXIMITY_BASE));
			return;
		}
		if (item.id >= ID_VIEWER_BASE && item.id < ID_VIEWER_BASE + viewers.size()) {
			openViewer(viewers.get(item.id - ID_VIEWER_BASE));
			return;
		}
		if (item.object instanceof MgIncomingLiveLocation) {
			MgIncomingLiveLocation loc = (MgIncomingLiveLocation) item.object;
			openIncoming(loc.account, new MessageObject(loc.account, loc.message, false, false));
			return;
		}
		if (item.object instanceof LocationController.SharingLocationInfo) {
			openOutgoing((LocationController.SharingLocationInfo) item.object);
		}
	}

	@Override
	protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
		return false;
	}

	private void openCallsPermissionHelp() {
		Activity activity = getParentActivity();
		if (activity == null) {
			return;
		}
		ArrayList<String> missing = missingCallPermissions();
		StringBuilder msg = new StringBuilder(LocaleController.getString(R.string.MercurygramServicesCallsPermsDetail));
		if (!missing.isEmpty()) {
			msg.append("\n\n");
			for (int a = 0; a < missing.size(); a++) {
				if (a > 0) {
					msg.append('\n');
				}
				msg.append("• ").append(missing.get(a));
			}
		}
		AlertDialog.Builder builder = new AlertDialog.Builder(activity)
				.setTitle(LocaleController.getString(R.string.MercurygramServicesCallsPerms))
				.setMessage(msg.toString())
				.setNegativeButton(LocaleController.getString(R.string.Close), null);
		if (!missing.isEmpty()) {
			builder.setPositiveButton(LocaleController.getString(R.string.PermissionOpenSettings), (d, w) -> {
				try {
					Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
					intent.setData(Uri.parse("package:" + ApplicationLoader.applicationContext.getPackageName()));
					activity.startActivity(intent);
				} catch (Exception ignored) {
				}
			});
			if (Build.VERSION.SDK_INT >= 33 && missingCallPermissions().contains(
					LocaleController.getString(R.string.MercurygramServicesPermNotifications))) {
				activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 912);
			}
		}
		builder.show();
	}

	private String callsPermissionSummary() {
		ArrayList<String> missing = missingCallPermissions();
		if (!SharedConfig.mg_callsRingEnabled) {
			return LocaleController.getString(R.string.MercurygramServicesCallsRingOff);
		}
		if (missing.isEmpty()) {
			return LocaleController.getString(R.string.MercurygramServicesCallsReady);
		}
		return LocaleController.formatString("MercurygramServicesCallsMissing",
				R.string.MercurygramServicesCallsMissing, missing.size());
	}

	private ArrayList<String> missingCallPermissions() {
		ArrayList<String> missing = new ArrayList<>();
		Context ctx = ApplicationLoader.applicationContext;
		if (Build.VERSION.SDK_INT >= 33
				&& ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
			missing.add(LocaleController.getString(R.string.MercurygramServicesPermNotifications));
		}
		if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
			missing.add(LocaleController.getString(R.string.MercurygramServicesPermMic));
		}
		if (Build.VERSION.SDK_INT >= 34) {
			NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
			if (nm != null && !nm.canUseFullScreenIntent()) {
				missing.add(LocaleController.getString(R.string.MercurygramServicesPermFullscreen));
			}
		}
		return missing;
	}

	private void rebuildOutgoingAndProximity() {
		outgoing.clear();
		proximity.clear();
		for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
			if (!UserConfig.getInstance(a).isClientActivated()) {
				continue;
			}
			ArrayList<LocationController.SharingLocationInfo> list = LocationController.getInstance(a).sharingLocationsUI;
			for (int b = 0; b < list.size(); b++) {
				LocationController.SharingLocationInfo info = list.get(b);
				outgoing.add(info);
				if (info.proximityMeters > 0) {
					proximity.add(info);
				}
			}
		}
	}

	private void refreshIncomingFromCache() {
		incoming.clear();
		for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
			if (UserConfig.getInstance(a).isClientActivated()) {
				incoming.addAll(MgIncomingLiveLocationStore.getForAccount(a));
			}
		}
		scanProgressPercent = MgIncomingLiveLocationStore.getScanProgressPercent();
	}

	private void refreshIncoming() {
		refreshIncomingFromCache();
		// Empty list may mean a prior scan used the broken media=1 filter or ran
		// before live-location rows were cached — force a full re-walk.
		boolean forceRescan = incoming.isEmpty();
		MgIncomingLiveLocationStore.ensureScanAllAccounts(percent -> AndroidUtilities.runOnUIThread(() ->
				onScanProgress(percent)), forceRescan);
	}

	private void onScanProgress(int percent) {
		int wasFingerprint = lastIncomingFingerprint;
		boolean wasScanning = lastScanning;
		scanProgressPercent = percent;
		refreshIncomingFromCache();
		boolean scanning = MgIncomingLiveLocationStore.isScanning();
		int fingerprint = incomingFingerprint();
		boolean structureChanged = wasFingerprint == Integer.MIN_VALUE
				|| fingerprint != wasFingerprint
				|| scanning != wasScanning;
		lastIncomingFingerprint = fingerprint;
		lastScanning = scanning;
		if (structureChanged) {
			refreshList();
		} else if (scanning) {
			updateScanProgressRow();
		}
	}

	private void updateScanProgressRow() {
		if (listView == null || listView.adapter == null) {
			return;
		}
		UniversalAdapter adapter = listView.adapter;
		UItem item = adapter.findItem(ID_SCAN_PROGRESS);
		if (item == null) {
			refreshList();
			return;
		}
		CharSequence text = formatScanProgress();
		if (TextUtils.equals(item.text, text)) {
			return;
		}
		item.text = text;
		for (int i = 0; i < adapter.getItemCount(); i++) {
			UItem at = adapter.getItem(i);
			if (at != null && at.id == ID_SCAN_PROGRESS) {
				adapter.notifyItemChanged(i);
				return;
			}
		}
	}

	private int incomingFingerprint() {
		int h = incoming.size();
		for (int a = 0; a < incoming.size(); a++) {
			MgIncomingLiveLocation loc = incoming.get(a);
			h = 31 * h + Objects.hash(loc.account, loc.dialogId, loc.getMessageId());
		}
		return h;
	}

	private void refreshViewers() {
		viewers.clear();
		viewers.addAll(MgLiveLocationViewerLog.recentForUi());
		if (viewers.size() <= MgLiveLocationViewerLog.UI_COLLAPSED_COUNT) {
			viewersExpanded = false;
		}
	}

	private String formatScanProgress() {
		return LocaleController.formatString("MercurygramLiveLocScanProgress",
				R.string.MercurygramLiveLocScanProgress, scanProgressPercent);
	}

	private static CharSequence sectionHeader(int titleRes, int count) {
		String title = LocaleController.getString(titleRes);
		if (count <= 0) {
			return title;
		}
		return LocaleController.formatString("MercurygramLiveLocSectionCount",
				R.string.MercurygramLiveLocSectionCount, title, count);
	}

	private static String formatViewerSubtitle(MgLiveLocationViewerLog.Entry entry) {
		return LocaleController.formatString("MercurygramLiveLocViewedBySubtitle",
				R.string.MercurygramLiveLocViewedBySubtitle,
				MgLiveLocationViewerLog.formatViewerChat(entry),
				LocaleController.formatDateTime(entry.whenMs / 1000, true));
	}

	private String formatOutgoingTitle(LocationController.SharingLocationInfo info) {
		MessagesController mc = MessagesController.getInstance(info.account);
		if (DialogObject.isUserDialog(info.did)) {
			TLRPC.User user = mc.getUser(info.did);
			return user != null ? UserObject.getUserName(user) : Long.toString(info.did);
		}
		TLRPC.Chat chat = mc.getChat(-info.did);
		return chat != null ? chat.title : Long.toString(-info.did);
	}

	private void openViewer(MgLiveLocationViewerLog.Entry entry) {
		if (entry == null) {
			return;
		}
		for (int a = 0; a < outgoing.size(); a++) {
			LocationController.SharingLocationInfo info = outgoing.get(a);
			if (info.account == entry.account && info.did == entry.dialogId && info.mid == entry.messageId) {
				openOutgoing(info);
				return;
			}
		}
		for (int a = 0; a < outgoing.size(); a++) {
			LocationController.SharingLocationInfo info = outgoing.get(a);
			if (info.account == entry.account && info.did == entry.dialogId) {
				openOutgoing(info);
				return;
			}
		}
	}

	private void openAllIncomingMap() {
		ArrayList<MgIncomingLiveLocation> all = new ArrayList<>(incoming);
		if (all.isEmpty()) {
			all = MgIncomingLiveLocationStore.getAllAccounts();
		}
		if (all.isEmpty() || !(getParentActivity() instanceof LaunchActivity)) {
			return;
		}
		LaunchActivity launchActivity = (LaunchActivity) getParentActivity();
		launchActivity.switchToAccount(all.get(0).account, true);
		LocationActivity locationActivity = new LocationActivity(2);
		locationActivity.setAllIncomingLiveLocations(all);
		presentFragment(locationActivity);
	}

	private void openOutgoing(LocationController.SharingLocationInfo info) {
		if (info == null || !(getParentActivity() instanceof LaunchActivity)) {
			return;
		}
		LaunchActivity launchActivity = (LaunchActivity) getParentActivity();
		launchActivity.switchToAccount(info.messageObject.currentAccount, true);
		LocationActivity locationActivity = new LocationActivity(2);
		locationActivity.setMessageObject(info.messageObject);
		final long dialogId = info.messageObject.getDialogId();
		locationActivity.setDelegate((location, live, notify, scheduleDate, payStars) ->
				SendMessagesHelper.getInstance(info.messageObject.currentAccount)
						.sendMessage(SendMessagesHelper.SendMessageParams.of(location, dialogId, null, null, null, null, notify, scheduleDate, 0)));
		presentFragment(locationActivity);
	}

	private void openIncoming(int account, MessageObject messageObject) {
		if (messageObject == null || !(getParentActivity() instanceof LaunchActivity)) {
			return;
		}
		LaunchActivity launchActivity = (LaunchActivity) getParentActivity();
		launchActivity.switchToAccount(account, true);
		LocationActivity locationActivity = new LocationActivity(2);
		locationActivity.setMessageObject(messageObject);
		final long dialogId = messageObject.getDialogId();
		locationActivity.setDelegate((location, live, notify, scheduleDate, payStars) ->
				SendMessagesHelper.getInstance(account)
						.sendMessage(SendMessagesHelper.SendMessageParams.of(location, dialogId, null, null, null, null, notify, scheduleDate, 0)));
		presentFragment(locationActivity);
	}

	private void refreshList() {
		lastIncomingFingerprint = incomingFingerprint();
		lastScanning = MgIncomingLiveLocationStore.isScanning();
		if (listView != null && listView.adapter != null) {
			listView.adapter.update(true);
		}
	}

	private static int stableIncomingId(MgIncomingLiveLocation loc) {
		return ID_INCOMING_BASE + Math.floorMod(Objects.hash(loc.account, loc.dialogId, loc.getMessageId()), ID_SLOT_MASK + 1);
	}

	private static int stableOutgoingId(LocationController.SharingLocationInfo info) {
		return ID_OUTGOING_BASE + Math.floorMod(Objects.hash(info.account, info.did, info.mid), ID_SLOT_MASK + 1);
	}

	private static final class IncomingLiveLocationFactory extends UItem.UItemFactory<SharingLiveLocationCell> {
		static {
			setup(new IncomingLiveLocationFactory());
		}

		@Override
		public SharingLiveLocationCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
			return new SharingLiveLocationCell(context, true, LIVE_LOC_CELL_HEIGHT_DP, resourcesProvider);
		}

		@Override
		public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
			if (item.object instanceof MgIncomingLiveLocation) {
				((SharingLiveLocationCell) view).setIncomingLiveLocation((MgIncomingLiveLocation) item.object);
			}
		}

		@Override
		public boolean equals(UItem a, UItem b) {
			return a.id == b.id;
		}

		@Override
		public boolean contentsEquals(UItem a, UItem b) {
			return a.object == b.object;
		}

		static UItem of(int id, MgIncomingLiveLocation loc) {
			UItem item = UItem.ofFactory(IncomingLiveLocationFactory.class);
			item.id = id;
			item.object = loc;
			return item;
		}
	}

	private static final class OutgoingLiveLocationFactory extends UItem.UItemFactory<SharingLiveLocationCell> {
		static {
			setup(new OutgoingLiveLocationFactory());
		}

		@Override
		public SharingLiveLocationCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
			return new SharingLiveLocationCell(context, false, LIVE_LOC_CELL_HEIGHT_DP, resourcesProvider);
		}

		@Override
		public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
			if (item.object instanceof LocationController.SharingLocationInfo) {
				((SharingLiveLocationCell) view).setDialog((LocationController.SharingLocationInfo) item.object);
			}
		}

		@Override
		public boolean equals(UItem a, UItem b) {
			return a.id == b.id;
		}

		@Override
		public boolean contentsEquals(UItem a, UItem b) {
			return a.object == b.object;
		}

		static UItem of(int id, LocationController.SharingLocationInfo info) {
			UItem item = UItem.ofFactory(OutgoingLiveLocationFactory.class);
			item.id = id;
			item.object = info;
			return item;
		}
	}

	@Override
	public ArrayList<ThemeDescription> getThemeDescriptions() {
		ArrayList<ThemeDescription> descriptions = super.getThemeDescriptions();
		descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{SharingLiveLocationCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
		descriptions.add(new ThemeDescription(listView, 0, new Class[]{SharingLiveLocationCell.class}, new String[]{"nameTextView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
		descriptions.add(new ThemeDescription(listView, 0, new Class[]{SharingLiveLocationCell.class}, new String[]{"distanceTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText3));
		ThemeDescription.ThemeDescriptionDelegate rebindLiveCells = () -> {
			if (listView != null && listView.adapter != null) {
				listView.adapter.update(false);
			}
		};
		descriptions.add(new ThemeDescription(null, 0, null, null, null, rebindLiveCells, Theme.key_location_liveLocationProgress));
		descriptions.add(new ThemeDescription(null, 0, null, null, null, rebindLiveCells, Theme.key_location_placeLocationBackground));
		descriptions.add(new ThemeDescription(null, 0, null, null, null, rebindLiveCells, Theme.key_location_sendLocationIcon));
		return descriptions;
	}

	@Override
	public boolean canParentTabsSlide(android.view.MotionEvent ev, boolean forward) {
		return asMainTab;
	}

	@Override
	public void onParentScrollToTop() {
		if (listView != null) {
			listView.smoothScrollToPosition(0);
		}
	}

	@Override
	public void onResume() {
		super.onResume();
		rebuildOutgoingAndProximity();
		// Re-kick scan if still empty (e.g. prior schema bug left prefs empty).
		if (incoming.isEmpty() && !MgIncomingLiveLocationStore.isScanning()) {
			refreshIncoming();
		} else {
			refreshIncomingFromCache();
		}
		refreshViewers();
		applyMainTabListPadding();
		refreshList();
	}
}
