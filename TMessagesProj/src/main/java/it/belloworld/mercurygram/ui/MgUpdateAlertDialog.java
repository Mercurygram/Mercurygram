package it.belloworld.mercurygram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;

import it.belloworld.mercurygram.MgUpdateChecker;
import it.belloworld.mercurygram.MgUpdateInfo;

public class MgUpdateAlertDialog extends BottomSheet {

    private final MgUpdateInfo updateInfo;
    private FrameLayout container;
    private LinearLayout linearLayout;
    private NestedScrollView scrollView;
    private int scrollOffsetY;
    private int[] location = new int[2];
    private Drawable shadowDrawable;
    private BottomSheetCell downloadButton;
    private boolean downloading;

    public static class BottomSheetCell extends FrameLayout {

        private final View background;
        private final TextView textView;
        private final boolean hasBackground;

        public BottomSheetCell(Context context, boolean withoutBackground) {
            super(context);
            hasBackground = !withoutBackground;
            setBackground(null);

            background = new View(context);
            if (hasBackground) {
                background.setBackground(Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 4));
            }
            addView(background, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, 0, 16, withoutBackground ? 0 : 16, 16, 16));

            textView = new TextView(context);
            textView.setLines(1);
            textView.setSingleLine(true);
            textView.setGravity(Gravity.CENTER);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            if (hasBackground) {
                textView.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
                textView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            } else {
                textView.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            }
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            textView.setPadding(0, 0, 0, hasBackground ? 0 : AndroidUtilities.dp(13));
            addView(textView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(hasBackground ? 80 : 50), MeasureSpec.EXACTLY));
        }

        public void setText(String text) {
            textView.setText(text);
        }
    }

    public MgUpdateAlertDialog(Context context, MgUpdateInfo info) {
        super(context, false);
        this.updateInfo = info;
        setCanceledOnTouchOutside(false);
        setApplyTopPadding(false);
        setApplyBottomPadding(false);

        shadowDrawable = context.getResources().getDrawable(R.drawable.sheet_shadow_round).mutate();
        shadowDrawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_dialogBackground), PorterDuff.Mode.MULTIPLY));

        container = new FrameLayout(context) {
            @Override
            public void setTranslationY(float translationY) {
                super.setTranslationY(translationY);
                updateLayout();
            }

            @Override
            public boolean onInterceptTouchEvent(MotionEvent ev) {
                if (ev.getAction() == MotionEvent.ACTION_DOWN && scrollOffsetY != 0 && ev.getY() < scrollOffsetY) {
                    dismissAndRemember();
                    return true;
                }
                return super.onInterceptTouchEvent(ev);
            }

            @Override
            public boolean onTouchEvent(MotionEvent e) {
                return !isDismissed() && super.onTouchEvent(e);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                int top = (int) (scrollOffsetY - backgroundPaddingTop - getTranslationY());
                shadowDrawable.setBounds(0, top, getMeasuredWidth(), getMeasuredHeight());
                shadowDrawable.draw(canvas);
            }
        };
        container.setWillNotDraw(false);
        containerView = container;

        scrollView = new NestedScrollView(context) {
            private boolean ignoreLayout;

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int height = MeasureSpec.getSize(heightMeasureSpec);
                measureChildWithMargins(linearLayout, widthMeasureSpec, 0, heightMeasureSpec, 0);
                int contentHeight = linearLayout.getMeasuredHeight();
                int padding = (height / 5 * 2);
                int visiblePart = height - padding;
                if (contentHeight - visiblePart < AndroidUtilities.dp(90) || contentHeight < height / 2 + AndroidUtilities.dp(90)) {
                    padding = height - contentHeight;
                }
                if (padding < 0) {
                    padding = 0;
                }
                if (getPaddingTop() != padding) {
                    ignoreLayout = true;
                    setPadding(0, padding, 0, 0);
                    ignoreLayout = false;
                }
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }

            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                updateLayout();
            }

            @Override
            public void requestLayout() {
                if (ignoreLayout) return;
                super.requestLayout();
            }

            @Override
            protected void onScrollChanged(int l, int t, int oldl, int oldt) {
                super.onScrollChanged(l, t, oldl, oldt);
                updateLayout();
            }
        };
        scrollView.setFillViewport(true);
        scrollView.setWillNotDraw(false);
        scrollView.setClipToPadding(false);
        scrollView.setVerticalScrollBarEnabled(false);
        container.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP, 0, 0, 0, 130));

        linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(linearLayout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP));

        TextView titleView = new TextView(context);
        titleView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setText("Mercurygram Update");
        linearLayout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 23, 24, 23, 0));

        if (info.isPreRelease()) {
            TextView preReleaseView = new TextView(context);
            preReleaseView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            preReleaseView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            preReleaseView.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            preReleaseView.setAllCaps(true);
            preReleaseView.setLetterSpacing(0.05f);
            preReleaseView.setText(LocaleController.getString("MercurygramPreReleaseBadge", R.string.MercurygramPreReleaseBadge));
            linearLayout.addView(preReleaseView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 23, 4, 23, 0));
        }

        TextView versionView = new TextView(context);
        versionView.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        versionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        versionView.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        versionView.setText(LocaleController.formatString("AppUpdateVersionAndSize", R.string.AppUpdateVersionAndSize, info.versionName, AndroidUtilities.formatFileSize(info.fileSize)));
        linearLayout.addView(versionView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 23, 0, 23, 5));

        if (!TextUtils.isEmpty(info.changelog)) {
            TextView changelogView = new TextView(context);
            changelogView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            changelogView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            changelogView.setText(info.changelog);
            changelogView.setGravity(Gravity.LEFT | Gravity.TOP);
            linearLayout.addView(changelogView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 23, 15, 23, 0));
        }

        downloadButton = new BottomSheetCell(context, false);
        downloadButton.setText(LocaleController.formatString("AppUpdateDownloadNow", R.string.AppUpdateDownloadNow));
        downloadButton.background.setOnClickListener(v -> onDownloadClicked());
        container.addView(downloadButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 50, Gravity.LEFT | Gravity.BOTTOM, 0, 0, 0, 50));

        BottomSheetCell laterButton = new BottomSheetCell(context, true);
        laterButton.setText(LocaleController.getString("AppUpdateRemindMeLater", R.string.AppUpdateRemindMeLater));
        laterButton.background.setOnClickListener(v -> dismissAndRemember());
        container.addView(laterButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 50, Gravity.LEFT | Gravity.BOTTOM, 0, 0, 0, 0));
    }

    private void dismissAndRemember() {
        SharedConfig.setMgDismissedPendingTag(updateInfo.tagName);
        dismiss();
    }

    @Override
    public void onBackPressed() {
        dismissAndRemember();
    }

    private void onDownloadClicked() {
        if (downloading) return;

        File existing = MgUpdateChecker.getUpdateApkFile();
        if (existing != null) {
            if (getContext() instanceof android.app.Activity) {
                MgUpdateChecker.installUpdate((android.app.Activity) getContext(), existing);
            }
            dismiss();
            return;
        }

        downloading = true;
        downloadButton.setText(LocaleController.formatString("AppUpdateDownloading", R.string.AppUpdateDownloading, 0));

        MgUpdateChecker.downloadUpdate(updateInfo, new MgUpdateChecker.ProgressCallback() {
            @Override
            public void onProgress(long downloaded, long total) {
                if (total > 0) {
                    int percent = (int) (downloaded * 100 / total);
                    downloadButton.setText(LocaleController.formatString("AppUpdateDownloading", R.string.AppUpdateDownloading, percent));
                }
            }

            @Override
            public void onComplete(File apkFile) {
                downloading = false;
                downloadButton.setText(LocaleController.getString("AppUpdateNow", R.string.AppUpdateNow));
                if (getContext() instanceof android.app.Activity) {
                    MgUpdateChecker.installUpdate((android.app.Activity) getContext(), apkFile);
                }
                dismiss();
            }

            @Override
            public void onError(String error) {
                downloading = false;
                downloadButton.setText(LocaleController.formatString("AppUpdateDownloadNow", R.string.AppUpdateDownloadNow));
            }
        });
    }

    private void updateLayout() {
        if (linearLayout == null || linearLayout.getChildCount() == 0) return;
        View child = linearLayout.getChildAt(0);
        child.getLocationInWindow(location);
        int top = location[1] - AndroidUtilities.dp(24);
        int newOffset = Math.max(top, 0);
        if (scrollOffsetY != newOffset) {
            scrollOffsetY = newOffset;
            scrollView.invalidate();
        }
    }

    @Override
    protected boolean canDismissWithSwipe() {
        return false;
    }
}
