package it.belloworld.mercurygram.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LocationController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.GraySectionCell;
import org.telegram.ui.Cells.SharingLiveLocationCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PickerBottomLayout;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.ContentPreviewViewer;
import org.telegram.ui.LocationActivity;

import java.util.ArrayList;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import it.belloworld.mercurygram.MgIncomingLiveLocation;

public class MgAllLiveLocationsAlert extends BottomSheet implements NotificationCenter.NotificationCenterDelegate {

	public interface Delegate {
		void openOutgoingShare(LocationController.SharingLocationInfo info);

		void openIncomingShare(int account, MessageObject messageObject);
	}

	public static void show(org.telegram.ui.ActionBar.BaseFragment fragment,
			MgOutgoingOpen outgoingOpen,
			MgIncomingOpen incomingOpen,
			Theme.ResourcesProvider resourcesProvider) {
		if (fragment == null || fragment.getParentActivity() == null) {
			return;
		}
		fragment.showDialog(new MgAllLiveLocationsAlert(fragment.getParentActivity(), new Delegate() {
			@Override
			public void openOutgoingShare(LocationController.SharingLocationInfo info) {
				outgoingOpen.open(info);
			}

			@Override
			public void openIncomingShare(int account, MessageObject messageObject) {
				incomingOpen.open(account, messageObject);
			}
		}, resourcesProvider));
	}

	public interface MgOutgoingOpen {
		void open(LocationController.SharingLocationInfo info);
	}

	public interface MgIncomingOpen {
		void open(int account, MessageObject messageObject);
	}

	private static final int TYPE_HEADER = 1;
	private static final int TYPE_OUTGOING = 2;
	private static final int TYPE_INCOMING = 3;

	private final Delegate delegate;
	private RecyclerListView listView;
	private ListAdapter adapter;
	private Drawable shadowDrawable;
	private int scrollOffsetY;
	private boolean ignoreLayout;

	private final ArrayList<LocationController.SharingLocationInfo> outgoing = new ArrayList<>();
	private final ArrayList<MgIncomingLiveLocation> incoming = new ArrayList<>();
	private boolean loadingIncoming;

	public MgAllLiveLocationsAlert(Context context, Delegate delegate, Theme.ResourcesProvider resourcesProvider) {
		super(context, false, resourcesProvider);
		this.delegate = delegate;
		NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.liveLocationsChanged);
		fixNavigationBar();
		rebuildOutgoing();
		loadIncoming();

		shadowDrawable = context.getResources().getDrawable(R.drawable.sheet_shadow_round).mutate();
		shadowDrawable.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_dialogBackground), PorterDuff.Mode.MULTIPLY));

		containerView = new FrameLayout(context) {
			@Override
			public boolean onInterceptTouchEvent(MotionEvent ev) {
				if (ev.getAction() == MotionEvent.ACTION_DOWN && scrollOffsetY != 0 && ev.getY() < scrollOffsetY) {
					dismiss();
					return true;
				}
				return super.onInterceptTouchEvent(ev);
			}

			@Override
			public boolean onTouchEvent(MotionEvent e) {
				return !isDismissed() && super.onTouchEvent(e);
			}

			@Override
			protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
				int height = MeasureSpec.getSize(heightMeasureSpec);
				if (Build.VERSION.SDK_INT >= 21) {
					height -= AndroidUtilities.statusBarHeight;
				}
				int contentSize = AndroidUtilities.dp(48 + 8) + AndroidUtilities.dp(56) + 1 + computeItemCount() * AndroidUtilities.dp(54);
				int padding;
				if (contentSize < (height / 5 * 3)) {
					padding = AndroidUtilities.dp(8);
				} else {
					padding = (height / 5 * 2);
					if (contentSize < height) {
						padding -= (height - contentSize);
					}
				}
				if (listView.getPaddingTop() != padding) {
					ignoreLayout = true;
					listView.setPadding(0, padding, 0, AndroidUtilities.dp(8));
					ignoreLayout = false;
				}
				super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(Math.min(contentSize, height), MeasureSpec.EXACTLY));
			}

			@Override
			protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
				super.onLayout(changed, left, top, right, bottom);
				updateLayout();
			}

			@Override
			public void requestLayout() {
				if (ignoreLayout) {
					return;
				}
				super.requestLayout();
			}

			@Override
			protected void onDraw(Canvas canvas) {
				shadowDrawable.setBounds(0, scrollOffsetY - backgroundPaddingTop, getMeasuredWidth(), getMeasuredHeight());
				shadowDrawable.draw(canvas);
			}
		};
		containerView.setWillNotDraw(false);
		containerView.setPadding(backgroundPaddingLeft, 0, backgroundPaddingLeft, 0);

		listView = new RecyclerListView(context) {
			@Override
			public boolean onInterceptTouchEvent(MotionEvent event) {
				boolean result = ContentPreviewViewer.getInstance().onInterceptTouchEvent(event, listView, 0, null, resourcesProvider);
				return super.onInterceptTouchEvent(event) || result;
			}

			@Override
			public void requestLayout() {
				if (ignoreLayout) {
					return;
				}
				super.requestLayout();
			}
		};
		listView.setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.VERTICAL, false));
		listView.setAdapter(adapter = new ListAdapter(context));
		listView.setVerticalScrollBarEnabled(false);
		listView.setClipToPadding(false);
		listView.setGlowColor(getThemedColor(Theme.key_dialogScrollGlow));
		listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
			@Override
			public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
				updateLayout();
			}
		});
		listView.setOnItemClickListener((view, position) -> {
			Object entry = adapter.getEntry(position);
			if (entry instanceof LocationController.SharingLocationInfo) {
				delegate.openOutgoingShare((LocationController.SharingLocationInfo) entry);
				dismiss();
			} else if (entry instanceof MgIncomingLiveLocation) {
				MgIncomingLiveLocation loc = (MgIncomingLiveLocation) entry;
				MessageObject messageObject = new MessageObject(loc.account, loc.message, false, false);
				delegate.openIncomingShare(loc.account, messageObject);
				dismiss();
			}
		});
		containerView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT, 0, 0, 0, 48));

		View shadow = new View(context);
		shadow.setBackgroundResource(R.drawable.header_shadow_reverse);
		containerView.addView(shadow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 3, Gravity.BOTTOM | Gravity.LEFT, 0, 0, 0, 48));

		PickerBottomLayout pickerBottomLayout = new PickerBottomLayout(context, false);
		pickerBottomLayout.setBackgroundColor(getThemedColor(Theme.key_dialogBackground));
		containerView.addView(pickerBottomLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.LEFT | Gravity.BOTTOM));
		pickerBottomLayout.cancelButton.setPadding(AndroidUtilities.dp(18), 0, AndroidUtilities.dp(18), 0);
		pickerBottomLayout.cancelButton.setTextColor(getThemedColor(Theme.key_text_RedBold));
		pickerBottomLayout.cancelButton.setText(LocaleController.getString(R.string.StopAllLocationSharings));
		pickerBottomLayout.cancelButton.setOnClickListener(v -> {
			MgStopLiveLocationHelper.confirmStopAll(
					MgStopLiveLocationHelper.activityOf(getContext()),
					resourcesProvider,
					this::dismiss);
		});
		pickerBottomLayout.doneButtonTextView.setTextColor(getThemedColor(Theme.key_dialogTextBlue2));
		pickerBottomLayout.doneButtonTextView.setText(LocaleController.getString(R.string.Close).toUpperCase());
		pickerBottomLayout.doneButton.setPadding(AndroidUtilities.dp(18), 0, AndroidUtilities.dp(18), 0);
		pickerBottomLayout.doneButton.setOnClickListener(v -> dismiss());
		pickerBottomLayout.doneButtonBadgeTextView.setVisibility(View.GONE);
	}

	private void rebuildOutgoing() {
		outgoing.clear();
		for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
			outgoing.addAll(LocationController.getInstance(a).sharingLocationsUI);
		}
	}

	private void loadIncoming() {
		if (loadingIncoming) {
			return;
		}
		loadingIncoming = true;
		incoming.clear();
		int accounts = 0;
		for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
			if (UserConfig.getInstance(a).isClientActivated()) {
				accounts++;
			}
		}
		if (accounts == 0) {
			loadingIncoming = false;
			return;
		}
		final int pendingAccounts = accounts;
		final int[] pending = {pendingAccounts};
		for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
			if (!UserConfig.getInstance(a).isClientActivated()) {
				continue;
			}
			LocationController.getInstance(a).loadAllIncomingLiveLocations(list -> {
				incoming.addAll(list);
				if (--pending[0] == 0) {
					loadingIncoming = false;
					if (adapter != null) {
						adapter.notifyDataSetChanged();
					}
				}
			});
		}
	}

	private int computeItemCount() {
		int count = 0;
		if (!outgoing.isEmpty()) {
			count += 1 + outgoing.size();
		}
		if (!incoming.isEmpty()) {
			count += 1 + incoming.size();
		}
		if (count == 0) {
			count = 1;
		}
		return count;
	}

	@Override
	protected boolean canDismissWithSwipe() {
		return false;
	}

	@SuppressLint("NewApi")
	private void updateLayout() {
		if (listView.getChildCount() <= 0) {
			listView.setTopGlowOffset(scrollOffsetY = listView.getPaddingTop());
			containerView.invalidate();
			return;
		}
		View child = listView.getChildAt(0);
		RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findContainingViewHolder(child);
		int top = child.getTop() - AndroidUtilities.dp(8);
		int newOffset = top > 0 && holder != null && holder.getAdapterPosition() == 0 ? top : 0;
		if (scrollOffsetY != newOffset) {
			listView.setTopGlowOffset(scrollOffsetY = newOffset);
			containerView.invalidate();
		}
	}

	@Override
	public void didReceivedNotification(int id, int account, Object... args) {
		if (id == NotificationCenter.liveLocationsChanged) {
			rebuildOutgoing();
			loadIncoming();
			if (outgoing.isEmpty() && incoming.isEmpty() && !loadingIncoming) {
				dismiss();
			} else if (adapter != null) {
				adapter.notifyDataSetChanged();
			}
		}
	}

	@Override
	public void dismiss() {
		super.dismiss();
		NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.liveLocationsChanged);
	}

	private class ListAdapter extends RecyclerListView.SelectionAdapter {

		private final Context context;

		ListAdapter(Context context) {
			this.context = context;
		}

		Object getEntry(int position) {
			int p = 0;
			if (!outgoing.isEmpty()) {
				if (position == p) {
					return null;
				}
				p++;
				if (position < p + outgoing.size()) {
					return outgoing.get(position - p);
				}
				p += outgoing.size();
			}
			if (!incoming.isEmpty()) {
				if (position == p) {
					return null;
				}
				p++;
				if (position < p + incoming.size()) {
					return incoming.get(position - p);
				}
			}
			return null;
		}

		@Override
		public int getItemCount() {
			return computeItemCount();
		}

		@Override
		public int getItemViewType(int position) {
			int p = 0;
			if (!outgoing.isEmpty()) {
				if (position == p++) {
					return TYPE_HEADER;
				}
				if (position < p + outgoing.size()) {
					return TYPE_OUTGOING;
				}
				p += outgoing.size();
			}
			if (!incoming.isEmpty()) {
				if (position == p++) {
					return TYPE_HEADER;
				}
				if (position < p + incoming.size()) {
					return TYPE_INCOMING;
				}
			}
			return TYPE_HEADER;
		}

		@Override
		public boolean isEnabled(RecyclerView.ViewHolder holder) {
			return holder.getItemViewType() == TYPE_OUTGOING || holder.getItemViewType() == TYPE_INCOMING;
		}

		@Override
		public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
			View view;
			switch (viewType) {
				case TYPE_OUTGOING:
				case TYPE_INCOMING:
					// distance=true required for setIncomingLiveLocation dialog subtitle
					view = new SharingLiveLocationCell(context, true, 54, resourcesProvider);
					break;
				case TYPE_HEADER:
				default:
					view = new GraySectionCell(context, resourcesProvider);
					break;
			}
			return new RecyclerListView.Holder(view);
		}

		@Override
		public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
			if (holder.getItemViewType() == TYPE_HEADER) {
				GraySectionCell cell = (GraySectionCell) holder.itemView;
				int p = 0;
				if (!outgoing.isEmpty()) {
					if (position == p) {
						cell.setText(LocaleController.getString(R.string.MercurygramLiveLocOutgoingHeader));
						return;
					}
					p += 1 + outgoing.size();
				}
				cell.setText(LocaleController.getString(R.string.MercurygramLiveLocIncomingHeader));
			} else if (holder.getItemViewType() == TYPE_OUTGOING) {
				SharingLiveLocationCell cell = (SharingLiveLocationCell) holder.itemView;
				LocationController.SharingLocationInfo info = (LocationController.SharingLocationInfo) getEntry(position);
				cell.setDialog(info);
			} else if (holder.getItemViewType() == TYPE_INCOMING) {
				SharingLiveLocationCell cell = (SharingLiveLocationCell) holder.itemView;
				MgIncomingLiveLocation loc = (MgIncomingLiveLocation) getEntry(position);
				cell.setIncomingLiveLocation(loc);
			}
		}
	}
}
