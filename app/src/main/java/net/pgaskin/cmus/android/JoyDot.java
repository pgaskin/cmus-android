package net.pgaskin.cmus.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Faint minecraft-style joystick dot floating over the terminal's
 * bottom-right: tap = enter, slide up/down = repeating arrows (deeper =
 * faster), slide far left or right = tab (once per crossing). Keys inject through
 * the same TerminalView path as the key row, so its sticky modifiers merge
 * here too. Fixed dp size — it's a control, not TUI-flush chrome.
 */
public final class JoyDot extends View {
    public interface Callback {
        void sendKey(int keyCode);
    }

    // knob alphas over the terminal; faint at rest, firmer under a finger
    private static final int BASE_ALPHA = 0x2E;
    private static final int BASE_ALPHA_ACTIVE = 0x46;
    private static final int KNOB_ALPHA = 0x5C;
    private static final int KNOB_ALPHA_ACTIVE = 0x8C;
    /** Repeat cadence bounds; displacement interpolates between them. */
    private static final long REPEAT_SLOW_MS = 300;
    private static final long REPEAT_FAST_MS = 75;

    private final Callback callback;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int baseRadius = dp(20);
    private final int knobRadius = dp(12);
    /** Touches must start this close to the center; the view is bigger
     * than the dot so the knob can travel, and everything outside the
     * grab area falls through to the terminal. */
    private final int grabRadius = dp(26);
    /** How far the knob visually follows the finger (past the base). */
    private final int knobTravel = dp(44);
    /** Vertical displacement where the arrows start. */
    private final int vertThreshold = dp(15);
    /** Vertical displacement where the repeat reaches full speed. */
    private final int vertFull = dp(60);
    /** "Far left"/"far right": where tab fires... */
    private final int tabThreshold = dp(40);
    /** ...and where it re-arms on the way back (hysteresis). */
    private final int tabRearm = dp(30);
    private final int touchSlop;
    private int fg = 0xFFFFFFFF;

    private boolean tracking;
    private boolean fired;
    private boolean tabArmed;
    private int repeatDir;
    private float downX;
    private float downY;
    private float dx;
    private float dy;

    private final Runnable repeater = new Runnable() {
        @Override
        public void run() {
            sendKey(repeatDir < 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN);
            postDelayed(this, repeatInterval());
        }
    };

    public JoyDot(Context context, Callback callback) {
        super(context);
        this.callback = callback;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    /** The dot floats over the TUI window, so it tints like the chrome. */
    public void applyTheme(int fg) {
        this.fg = fg;
        invalidate();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                if (Math.hypot(e.getX() - getWidth() / 2f, e.getY() - getHeight() / 2f)
                        > grabRadius) {
                    return false; // not on the dot: the terminal's touch
                }
                downX = e.getX();
                downY = e.getY();
                dx = 0;
                dy = 0;
                tracking = true;
                fired = false;
                tabArmed = true;
                repeatDir = 0;
                invalidate();
            }
            case MotionEvent.ACTION_MOVE -> {
                if (!tracking) {
                    break;
                }
                dx = e.getX() - downX;
                dy = e.getY() - downY;
                if (Math.abs(dx) >= tabThreshold) {
                    // far left/right = tab, once per crossing; arrows
                    // pause here
                    stopRepeat();
                    if (tabArmed) {
                        tabArmed = false;
                        sendKey(KeyEvent.KEYCODE_TAB);
                    }
                } else {
                    if (Math.abs(dx) <= tabRearm) {
                        tabArmed = true;
                    }
                    int dir = dy <= -vertThreshold ? -1 : dy >= vertThreshold ? 1 : 0;
                    if (dir != repeatDir) {
                        // fire on crossing (or reversal), then repeat; the
                        // repeater re-reads the displacement per tick, so
                        // pushing deeper speeds it up without re-crossing
                        stopRepeat();
                        repeatDir = dir;
                        if (dir != 0) {
                            sendKey(dir < 0 ? KeyEvent.KEYCODE_DPAD_UP
                                    : KeyEvent.KEYCODE_DPAD_DOWN);
                            postDelayed(repeater, repeatInterval());
                        }
                    }
                }
                invalidate();
            }
            case MotionEvent.ACTION_UP -> {
                if (!fired && Math.hypot(dx, dy) <= touchSlop) {
                    sendKey(KeyEvent.KEYCODE_ENTER);
                }
                reset();
            }
            case MotionEvent.ACTION_CANCEL -> reset();
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        paint.setColor((fg & 0x00FFFFFF)
                | (tracking ? BASE_ALPHA_ACTIVE : BASE_ALPHA) << 24);
        canvas.drawCircle(cx, cy, baseRadius, paint);
        // the knob follows the finger past the base circle, up to about
        // the gesture thresholds
        float kx = dx;
        float ky = dy;
        double len = Math.hypot(kx, ky);
        if (len > knobTravel) {
            kx = (float) (kx / len * knobTravel);
            ky = (float) (ky / len * knobTravel);
        }
        paint.setColor((fg & 0x00FFFFFF)
                | (tracking ? KNOB_ALPHA_ACTIVE : KNOB_ALPHA) << 24);
        canvas.drawCircle(cx + kx, cy + ky, knobRadius, paint);
    }

    private void sendKey(int keyCode) {
        fired = true;
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        callback.sendKey(keyCode);
    }

    private long repeatInterval() {
        float t = (Math.abs(dy) - vertThreshold) / (float) (vertFull - vertThreshold);
        t = Math.max(0f, Math.min(t, 1f));
        return Math.round(REPEAT_SLOW_MS + t * (REPEAT_FAST_MS - REPEAT_SLOW_MS));
    }

    private void stopRepeat() {
        repeatDir = 0;
        removeCallbacks(repeater);
    }

    private void reset() {
        tracking = false;
        stopRepeat();
        dx = 0;
        dy = 0;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(repeater);
    }

    private int dp(int dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics()));
    }
}
