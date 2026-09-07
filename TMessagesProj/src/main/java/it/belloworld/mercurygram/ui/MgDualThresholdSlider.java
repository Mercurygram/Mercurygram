package it.belloworld.mercurygram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

import it.belloworld.mercurygram.MgLiveLocationThresholds;

public class MgDualThresholdSlider extends View {

	public interface OnChangeListener {
		void onChanged(int leftIndex, int rightIndex);
	}

	private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint rangePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final TextPaint regionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
	private final TextPaint tickPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
	private final RectF trackRect = new RectF();
	private final Rect textBounds = new Rect();

	private int leftIndex = MgLiveLocationThresholds.DEFAULT_LOWER_INDEX;
	private int rightIndex = MgLiveLocationThresholds.DEFAULT_UPPER_INDEX;
	private int activeThumb = -1;
	private boolean dragging;
	private OnChangeListener listener;

	public MgDualThresholdSlider(Context context) {
		super(context);
		setClickable(true);
		trackPaint.setColor(Theme.getColor(Theme.key_player_progressBackground));
		rangePaint.setColor(Theme.getColor(Theme.key_switchTrackChecked));
		thumbPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
		regionPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
		regionPaint.setTextSize(AndroidUtilities.dp(11));
		regionPaint.setTextAlign(Paint.Align.CENTER);
		tickPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
		tickPaint.setTextSize(AndroidUtilities.dp(10));
		tickPaint.setTextAlign(Paint.Align.CENTER);
	}

	public void setIndices(int left, int right) {
		leftIndex = MgLiveLocationThresholds.clampIndex(left);
		rightIndex = MgLiveLocationThresholds.clampIndex(right);
		if (leftIndex > rightIndex) {
			int tmp = leftIndex;
			leftIndex = rightIndex;
			rightIndex = tmp;
		}
		invalidate();
	}

	public int getLeftIndex() {
		return leftIndex;
	}

	public int getRightIndex() {
		return rightIndex;
	}

	public void setOnChangeListener(OnChangeListener listener) {
		this.listener = listener;
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		int height = AndroidUtilities.dp(72);
		setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height);
	}

	@Override
	protected void onDraw(Canvas canvas) {
		int pad = AndroidUtilities.dp(16);
		int cy = getMeasuredHeight() / 2;
		int thumbR = AndroidUtilities.dp(10);
		float trackTop = cy - AndroidUtilities.dp(2);
		float trackBottom = cy + AndroidUtilities.dp(2);
		trackRect.set(pad, trackTop, getMeasuredWidth() - pad, trackBottom);
		canvas.drawRoundRect(trackRect, AndroidUtilities.dp(2), AndroidUtilities.dp(2), trackPaint);

		float lx = indexToX(leftIndex);
		float rx = indexToX(rightIndex);
		canvas.drawRect(Math.min(lx, rx), trackRect.top, Math.max(lx, rx), trackRect.bottom, rangePaint);
		canvas.drawCircle(lx, cy, thumbR, thumbPaint);
		if (rightIndex != leftIndex) {
			canvas.drawCircle(rx, cy, thumbR, thumbPaint);
		}

		drawRegionLabels(canvas, pad, lx, rx, cy - AndroidUtilities.dp(18));
		drawAxisTicks(canvas, pad, cy + AndroidUtilities.dp(18));
	}

	private void drawRegionLabels(Canvas canvas, int pad, float lx, float rx, float baseline) {
		float left = pad;
		float right = getMeasuredWidth() - pad;
		float midL = Math.min(lx, rx);
		float midR = Math.max(lx, rx);
		String alwaysOn = LocaleController.getString(R.string.MercurygramLiveLocRegionAlwaysOn);
		String duty = LocaleController.getString(R.string.MercurygramLiveLocRegionDutyCycle);
		String alwaysOff = LocaleController.getString(R.string.MercurygramLiveLocRegionAlwaysOff);
		drawRegionCentered(canvas, alwaysOn, left, midL, baseline);
		drawRegionCentered(canvas, duty, midL, midR, baseline);
		drawRegionCentered(canvas, alwaysOff, midR, right, baseline);
	}

	private void drawRegionCentered(Canvas canvas, String text, float x0, float x1, float baseline) {
		float width = x1 - x0;
		if (width < AndroidUtilities.dp(28) || text == null || text.length() == 0) {
			return;
		}
		regionPaint.getTextBounds(text, 0, text.length(), textBounds);
		if (textBounds.width() > width - AndroidUtilities.dp(4)) {
			return;
		}
		canvas.drawText(text, (x0 + x1) / 2f, baseline, regionPaint);
	}

	private void drawAxisTicks(Canvas canvas, int pad, float baseline) {
		int[] ticks = MgLiveLocationThresholds.AXIS_TICK_SEC;
		for (int a = 0; a < ticks.length; a++) {
			int sec = ticks[a];
			int index = MgLiveLocationThresholds.isInfinite(sec)
					? MgLiveLocationThresholds.count() - 1
					: MgLiveLocationThresholds.indexOfSeconds(sec);
			float x = indexToX(index);
			String label = MgLiveLocationThresholds.formatAxisTick(sec);
			if (a == 0) {
				tickPaint.setTextAlign(Paint.Align.LEFT);
				canvas.drawText(label, pad, baseline, tickPaint);
			} else if (a == ticks.length - 1) {
				tickPaint.setTextAlign(Paint.Align.RIGHT);
				canvas.drawText(label, getMeasuredWidth() - pad, baseline, tickPaint);
			} else {
				tickPaint.setTextAlign(Paint.Align.CENTER);
				canvas.drawText(label, x, baseline, tickPaint);
			}
		}
		tickPaint.setTextAlign(Paint.Align.CENTER);
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		float x = event.getX();
		float y = event.getY();
		switch (event.getActionMasked()) {
			case MotionEvent.ACTION_DOWN: {
				if (!isInTouchZone(x, y)) {
					return false;
				}
				setDragging(true);
				float lx = indexToX(leftIndex);
				float rx = indexToX(rightIndex);
				float dl = Math.abs(x - lx);
				float dr = Math.abs(x - rx);
				if (leftIndex == rightIndex) {
					activeThumb = x <= lx ? 0 : 1;
				} else if (dl <= dr) {
					activeThumb = 0;
				} else {
					activeThumb = 1;
				}
				updateIndex(activeThumb, xToIndex(x));
				return true;
			}
			case MotionEvent.ACTION_MOVE: {
				if (!dragging || activeThumb < 0) {
					return dragging;
				}
				setDragging(true);
				int idx = xToIndex(x);
				if (leftIndex == rightIndex && activeThumb == 0 && idx > rightIndex) {
					activeThumb = 1;
				} else if (leftIndex == rightIndex && activeThumb == 1 && idx < leftIndex) {
					activeThumb = 0;
				}
				updateIndex(activeThumb, idx);
				return true;
			}
			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_CANCEL: {
				boolean wasDragging = dragging;
				activeThumb = -1;
				setDragging(false);
				return wasDragging;
			}
		}
		return super.onTouchEvent(event);
	}

	private boolean isInTouchZone(float x, float y) {
		int pad = AndroidUtilities.dp(8);
		int cy = getMeasuredHeight() / 2;
		return x >= -pad && x <= getMeasuredWidth() + pad
				&& y >= cy - AndroidUtilities.dp(24) && y <= cy + AndroidUtilities.dp(24);
	}

	private void setDragging(boolean value) {
		if (dragging == value) {
			if (value) {
				disallowParentIntercept(true);
			}
			return;
		}
		dragging = value;
		disallowParentIntercept(value);
	}

	private void disallowParentIntercept(boolean disallow) {
		ViewParent parent = getParent();
		while (parent != null) {
			parent.requestDisallowInterceptTouchEvent(disallow);
			parent = parent.getParent();
		}
	}

	private void updateIndex(int thumb, int index) {
		index = MgLiveLocationThresholds.clampIndex(index);
		if (thumb == 0) {
			leftIndex = index;
			if (leftIndex > rightIndex) {
				rightIndex = leftIndex;
			}
		} else {
			rightIndex = index;
			if (rightIndex < leftIndex) {
				leftIndex = rightIndex;
			}
		}
		invalidate();
		if (listener != null) {
			listener.onChanged(leftIndex, rightIndex);
		}
	}

	private float indexToX(int index) {
		int pad = AndroidUtilities.dp(16);
		float width = getMeasuredWidth() - pad * 2f;
		int max = MgLiveLocationThresholds.count() - 1;
		return pad + width * (index / (float) max);
	}

	private int xToIndex(float x) {
		int pad = AndroidUtilities.dp(16);
		float width = getMeasuredWidth() - pad * 2f;
		if (width <= 0) {
			return 0;
		}
		float frac = (x - pad) / width;
		if (frac < 0) {
			frac = 0;
		}
		if (frac > 1) {
			frac = 1;
		}
		return Math.round(frac * (MgLiveLocationThresholds.count() - 1));
	}
}
