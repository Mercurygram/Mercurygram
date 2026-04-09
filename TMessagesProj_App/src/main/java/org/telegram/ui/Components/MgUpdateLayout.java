package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.IUpdateLayout;

import java.io.File;

import it.belloworld.mercurygram.MgUpdateChecker;
import it.belloworld.mercurygram.MgUpdateInfo;

public class MgUpdateLayout extends IUpdateLayout {

    private FrameLayout updateLayout;
    private RadialProgress2 updateLayoutIcon;
    private AnimatedTextView updateTextView;
    private AnimatedTextView.AnimatedTextDrawable updateSizeTextView;

    private final Activity activity;
    private final ViewGroup sideMenuContainer;
    private boolean downloading;

    public MgUpdateLayout(Activity activity, ViewGroup sideMenuContainer) {
        super(activity, sideMenuContainer);
        this.activity = activity;
        this.sideMenuContainer = sideMenuContainer;
    }

    @Override
    public void createUpdateUI(int currentAccount) {
        if (sideMenuContainer == null || updateLayout != null) {
            return;
        }
        updateLayout = new FrameLayout(activity);
        updateLayout.setVisibility(View.INVISIBLE);
        updateLayout.setTranslationY(dp(44));
        updateLayout.setBackground(Theme.getSelectorDrawable(0x40ffffff, false));
        sideMenuContainer.addView(updateLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 44, Gravity.LEFT | Gravity.BOTTOM));
        updateLayout.setOnClickListener(v -> onUpdateClicked(currentAccount));

        updateTextView = new AnimatedTextView(activity, true, true, true) {
            @Override
            protected void onDraw(Canvas canvas) {
                updateSizeTextView.setBounds(0, 0, getMeasuredWidth() - dp(20), getMeasuredHeight());
                updateSizeTextView.draw(canvas);

                canvas.save();
                canvas.translate(dp(15), 0);
                super.onDraw(canvas);
                canvas.translate((getMeasuredWidth() - width()) / 2f - dp(30), dp(11));
                updateLayoutIcon.draw(canvas);
                canvas.restore();
            }

            @Override
            protected boolean verifyDrawable(Drawable who) {
                return super.verifyDrawable(who) || who == updateSizeTextView;
            }
        };
        updateTextView.setTextSize(dp(15));
        updateTextView.setTypeface(AndroidUtilities.bold());
        updateTextView.setTextColor(0xffffffff);
        updateTextView.setGravity(Gravity.CENTER);
        updateLayout.addView(updateTextView, LayoutHelper.createFrameMatchParent());

        updateLayoutIcon = new RadialProgress2(updateTextView);
        updateLayoutIcon.setColors(0xffffffff, 0xffffffff, Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_featuredStickers_addButton));
        updateLayoutIcon.setProgressRect(0, 0, dp(22), dp(22));
        updateLayoutIcon.setCircleRadius(dp(11));
        updateLayoutIcon.setAsMini();

        updateSizeTextView = new AnimatedTextView.AnimatedTextDrawable(true, true, true);
        updateSizeTextView.setCallback(updateTextView);
        updateSizeTextView.setTextSize(dp(14));
        updateSizeTextView.setTypeface(AndroidUtilities.bold());
        updateSizeTextView.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        updateSizeTextView.setTextColor(0xccffffff);
    }

    private void onUpdateClicked(int currentAccount) {
        if (!SharedConfig.isMgUpdateAvailable()) return;

        File apk = MgUpdateChecker.getUpdateApkFile();
        if (apk != null) {
            MgUpdateChecker.installUpdate(activity, apk);
            return;
        }

        if (downloading) return;

        MgUpdateInfo info = SharedConfig.getMgPendingUpdate();
        if (info == null) return;

        downloading = true;
        updateLayoutIcon.setIcon(MediaActionDrawable.ICON_CANCEL, true, true);
        updateTextView.setText(LocaleController.formatString(R.string.AppUpdateDownloading, 0), true);
        updateSizeTextView.setText(null, true);

        MgUpdateChecker.downloadUpdate(info, new MgUpdateChecker.ProgressCallback() {
            @Override
            public void onProgress(long downloaded, long total) {
                if (total > 0) {
                    float progress = downloaded / (float) total;
                    updateLayoutIcon.setProgress(progress, true);
                    updateTextView.setText(LocaleController.formatString(R.string.AppUpdateDownloading, (int) (progress * 100)), true);
                }
            }

            @Override
            public void onComplete(File apkFile) {
                downloading = false;
                updateLayoutIcon.setIcon(MediaActionDrawable.ICON_UPDATE, true, true);
                updateTextView.setText(LocaleController.getString(R.string.AppUpdateNow), true);
            }

            @Override
            public void onError(String error) {
                downloading = false;
                updateAppUpdateViews(currentAccount, true);
            }
        });
    }

    @Override
    public void updateAppUpdateViews(int currentAccount, boolean animated) {
        if (sideMenuContainer == null) {
            return;
        }
        if (SharedConfig.isMgUpdateAvailable()) {
            createUpdateUI(currentAccount);

            MgUpdateInfo info = SharedConfig.getMgPendingUpdate();
            File apk = MgUpdateChecker.getUpdateApkFile();
            if (apk != null) {
                updateLayoutIcon.setIcon(MediaActionDrawable.ICON_UPDATE, true, animated);
                updateTextView.setText(LocaleController.getString(R.string.AppUpdateNow), animated);
                updateSizeTextView.setText(null, animated);
            } else {
                updateLayoutIcon.setIcon(MediaActionDrawable.ICON_DOWNLOAD, true, animated);
                updateTextView.setText("Mercurygram Update", animated);
                if (info != null) {
                    updateSizeTextView.setText(AndroidUtilities.formatFileSize(info.fileSize), animated);
                }
            }

            if (updateLayout.getTag() != null) {
                return;
            }
            updateLayout.setVisibility(View.VISIBLE);
            updateLayout.setTag(1);
            if (animated) {
                updateLayout.animate().translationY(0).setInterpolator(CubicBezierInterpolator.EASE_OUT).setListener(null).setDuration(180).start();
            } else {
                updateLayout.setTranslationY(0);
            }
        } else {
            if (updateLayout == null || updateLayout.getTag() == null) {
                return;
            }
            updateLayout.setTag(null);
            if (animated) {
                updateLayout.animate().translationY(dp(44)).setInterpolator(CubicBezierInterpolator.EASE_OUT).setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (updateLayout.getTag() == null) {
                            updateLayout.setVisibility(View.INVISIBLE);
                        }
                    }
                }).setDuration(180).start();
            } else {
                updateLayout.setTranslationY(dp(44));
                updateLayout.setVisibility(View.INVISIBLE);
            }
        }
    }
}
