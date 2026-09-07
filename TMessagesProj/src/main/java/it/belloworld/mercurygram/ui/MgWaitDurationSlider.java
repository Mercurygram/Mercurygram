package it.belloworld.mercurygram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

import it.belloworld.mercurygram.MgLiveLocationPolicy;

/** Single-thumb discrete duration slider (wait-between-sessions, dwell, etc.). */
public class MgWaitDurationSlider extends View {

	public interface OnChangeListener {
		void onChanged(int seconds);
	}

	private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF trackRect = new RectF();

	private int[] steps = MgLiveLocationPolicy.SLEEP_MAX_STEPS_SEC;
	private int index;
	private boolean dragging;
	private OnChangeListener listener;

	public MgWaitDurationSlider(Context context) {
		this(context, MgLiveLocationPolicy.SLEEP_MAX_STEPS_SEC);
	}

	public MgWaitDurationSlider(Context context, int[] steps) {
		super(context);
		setClickable(true);
		trackPaint.setColor(Theme.getColor(Theme.key_player_progressBackground));
		fillPaint.setColor(Theme.getColor(Theme.key_switchTrackChecked));
		thumbPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
		setSteps(steps);
	}

	public void setSteps(int[] steps) {
		if (steps == null || steps.length == 0) {
			this.steps = MgLiveLocationPolicy.SLEEP_MAX_STEPS_SEC;
		} else {
			this.steps = steps;
		}
		if (index >= this.steps.length) {
			index = this.steps.length - 1;
		}
		if (index < 0) {
			index = 0;
		}
		invalidate();
	}

	public void setSeconds(int seconds) {
		index = MgLiveLocationPolicy.stepIndex(seconds, steps);
		invalidate();
	}

	public int getSeconds() {
		return steps[index];
	}

	public void setOnChangeListener(OnChangeListener listener) {
		this.listener = listener;
	}

	@Override
	protected void onDraw(Canvas canvas) {
		int pad = AndroidUtilities.dp(16);
		int cy = getMeasuredHeight() / 2;
		int thumbR = AndroidUtilities.dp(10);
		trackRect.set(pad, cy - AndroidUtilities.dp(2), getMeasuredWidth() - pad, cy + AndroidUtilities.dp(2));
		canvas.drawRoundRect(trackRect, AndroidUtilities.dp(2), AndroidUtilities.dp(2), trackPaint);
		float x = indexToX(index);
		canvas.drawRect(pad, trackRect.top, x, trackRect.bottom, fillPaint);
		canvas.drawCircle(x, cy, thumbR, thumbPaint);
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
				updateIndex(xToIndex(x));
				return true;
			}
			case MotionEvent.ACTION_MOVE: {
				if (!dragging) {
					return false;
				}
				setDragging(true);
				updateIndex(xToIndex(x));
				return true;
			}
			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_CANCEL: {
				boolean wasDragging = dragging;
				setDragging(false);
				return wasDragging;
			}
		}
		return super.onTouchEvent(event);
	}

	private boolean isInTouchZone(float x, float y) {
		int pad = AndroidUtilities.dp(8);
		return x >= -pad && x <= getMeasuredWidth() + pad
				&& y >= -AndroidUtilities.dp(20) && y <= getMeasuredHeight() + AndroidUtilities.dp(20);
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

	private void updateIndex(int newIndex) {
		int max = steps.length - 1;
		if (newIndex < 0) {
			newIndex = 0;
		}
		if (newIndex > max) {
			newIndex = max;
		}
		if (index == newIndex) {
			return;
		}
		index = newIndex;
		invalidate();
		if (listener != null) {
			listener.onChanged(steps[index]);
		}
	}

	private float indexToX(int idx) {
		int pad = AndroidUtilities.dp(16);
		float width = getMeasuredWidth() - pad * 2f;
		int max = Math.max(1, steps.length - 1);
		return pad + width * (idx / (float) max);
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
		return Math.round(frac * (steps.length - 1));
	}
}
