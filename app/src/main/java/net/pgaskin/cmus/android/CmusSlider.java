package net.pgaskin.cmus.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

/**
 * Minimal flat slider matching the TUI aesthetic (a framework SeekBar is
 * Material-styled and has no real vertical mode): a thin track, a filled
 * span, and a block thumb, all in one theme color. External progress
 * updates are ignored mid-drag so event echoes don't fight the finger.
 */
public final class CmusSlider extends View {
    public interface Listener {
        /** Fired on integer progress changes while dragging. */
        void onDrag(int progress);

        /** Finger up; progress is final. */
        void onRelease(int progress);
    }

    private final boolean vertical;
    private final Paint paint = new Paint();
    private Listener listener;
    private int max;
    private float progress;
    private float drawnPos = Float.NaN; // thumb position last drawn
    private int color = 0xFFFFFFFF;
    private boolean dragging;

    public CmusSlider(Context context, boolean vertical) {
        super(context);
        this.vertical = vertical;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setColor(int color) {
        this.color = color;
        invalidate();
    }

    public void setMax(int max) {
        this.max = Math.max(max, 0);
        progress = Math.min(progress, this.max);
        invalidate();
    }

    /**
     * Ignored while the user is dragging. Takes fractions so a caller can
     * animate between whole-unit events; only redraws when the thumb
     * actually moves a visible amount, so per-frame calls are cheap.
     */
    public void setProgress(float progress) {
        if (dragging) {
            return;
        }
        this.progress = Math.max(0, Math.min(progress, max));
        float pos = positionForProgress();
        if (Float.isNaN(drawnPos) || Math.abs(pos - drawnPos) >= 0.5f) {
            invalidate();
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled) {
            dragging = false;
        }
        invalidate();
    }

    // geometry: the thumb travels between inset() and length()-inset() along
    // the axis; vertical runs bottom-to-top

    private int inset() {
        return dp(14);
    }

    private int length() {
        return vertical ? getHeight() : getWidth();
    }

    private float positionForProgress() {
        float f = max > 0 ? progress / max : 0;
        float travel = length() - 2 * inset();
        return vertical ? length() - inset() - f * travel : inset() + f * travel;
    }

    private int progressForPosition(float pos) {
        float travel = length() - 2 * inset();
        float f = vertical ? (length() - inset() - pos) / travel : (pos - inset()) / travel;
        return clamp(Math.round(f * max));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        boolean active = isEnabled() && max > 0;
        float track = dp(2);
        float thumb = dp(3);
        float in = inset();
        float end = length() - in;
        float mid = (vertical ? getWidth() : getHeight()) / 2f;
        float thumbLen = (vertical ? getWidth() : getHeight()) / 2f;
        float pos = positionForProgress();
        drawnPos = pos;

        paint.setColor((color & 0x00FFFFFF) | (active ? 0x55000000 : 0x30000000));
        if (vertical) {
            canvas.drawRect(mid - track / 2, in, mid + track / 2, end, paint);
        } else {
            canvas.drawRect(in, mid - track / 2, end, mid + track / 2, paint);
        }
        if (!active) {
            return;
        }
        paint.setColor(color);
        if (vertical) {
            canvas.drawRect(mid - track / 2, pos, mid + track / 2, end, paint);
            canvas.drawRect(mid - thumbLen / 2, pos - thumb / 2,
                    mid + thumbLen / 2, pos + thumb / 2, paint);
        } else {
            canvas.drawRect(in, mid - track / 2, pos, mid + track / 2, paint);
            canvas.drawRect(pos - thumb / 2, mid - thumbLen / 2,
                    pos + thumb / 2, mid + thumbLen / 2, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled() || max <= 0) {
            return false;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                dragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                drag(e);
            }
            case MotionEvent.ACTION_MOVE -> drag(e);
            case MotionEvent.ACTION_UP -> {
                drag(e);
                dragging = false;
                if (listener != null) {
                    listener.onRelease(Math.round(progress));
                }
            }
            case MotionEvent.ACTION_CANCEL -> dragging = false;
            default -> {
                return false;
            }
        }
        return true;
    }

    private void drag(MotionEvent e) {
        int p = progressForPosition(vertical ? e.getY() : e.getX());
        if (p != Math.round(progress)) {
            progress = p;
            invalidate();
            if (listener != null) {
                listener.onDrag(p);
            }
        }
    }

    private int clamp(int p) {
        return Math.max(0, Math.min(p, max));
    }

    private int dp(float dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics()));
    }
}
