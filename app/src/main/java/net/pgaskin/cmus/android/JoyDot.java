package net.pgaskin.cmus.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Faint minecraft-style joystick dot floating over the terminal's
 * bottom-right: tap = enter, slide up/down = repeating arrows (deeper =
 * faster), slide far left or right = directional navigation — panes then
 * views, resolved by cmus — repeating slowly while held (deeper = faster
 * here too). Keys inject through
 * the same TerminalView path as the key row, so its sticky modifiers merge
 * here too. Fixed dp size — it's a control, not TUI-flush chrome.
 * Resting on the center for 3s vibrates and turns the rest of the touch
 * into a reposition drag; the owner moves/clamps/persists via the drag
 * callbacks (this view only reports raw deltas).
 * <p>
 * In <b>floating</b> mode (direct touch off, stage 21) the view instead
 * fills the terminal, stays invisible at rest, and materialises under the
 * finger wherever a touch lands — the gesture origin is that press, not
 * the view center. There the rest-in-place hold fires a right-click
 * (callback.rightClick) instead of the reposition drag, since a stick that
 * appears on demand has nowhere to be repositioned to.
 */
public final class JoyDot extends View {
    public interface Callback {
        void sendKey(int keyCode);

        /** Far horizontal slide: go this way (pane, then adjacent view). */
        void nav(boolean right);

        /** Reposition drag engaged: the finger moved by (dx, dy) raw px. */
        void drag(float dx, float dy);

        /** Reposition drag released: persist wherever the dot ended up. */
        void dragEnd();

        /** Floating-mode long-press: right-click the current selection. */
        void rightClick();
    }

    // knob alphas over the terminal; faint at rest, firmer under a finger
    private static final int BASE_ALPHA = 0x2E;
    private static final int BASE_ALPHA_ACTIVE = 0x46;
    private static final int KNOB_ALPHA = 0x5C;
    private static final int KNOB_ALPHA_ACTIVE = 0x8C;
    /** Repeat cadence bounds; displacement interpolates between them. */
    private static final long REPEAT_SLOW_MS = 300;
    private static final long REPEAT_FAST_MS = 75;
    /** Nav (pane/view) repeat bounds — much slower, it changes context. */
    private static final long NAV_SLOW_MS = 750;
    private static final long NAV_FAST_MS = 250;
    /** Resting on the center this long arms the reposition drag. */
    private static final long DRAG_HOLD_MS = 2000;

    private final Callback callback;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int baseRadius = dp(20);
    private final int knobRadius = dp(12);
    /** Touches must start this close to the center; the view is bigger
     * than the dot so the knob can travel, and everything outside the
     * grab area falls through to the terminal. */
    private final int grabRadius = dp(39);
    /** How far the knob visually follows the finger (past the base). */
    private final int knobTravel = dp(44);
    /** Vertical displacement where the arrows start. */
    private final int vertThreshold = dp(15);
    /** Vertical displacement where the repeat reaches full speed. */
    private final int vertFull = dp(60);
    /** "Far left"/"far right": where the nav gesture fires... */
    private final int navThreshold = dp(40);
    /** ...and where it lets go on the way back (hysteresis). */
    private final int navRearm = dp(30);
    /** Nav displacement where its repeat reaches full speed. */
    private final int navFull = dp(100);
    private final int touchSlop;
    private final int longPressTimeout = ViewConfiguration.getLongPressTimeout();
    private int fg = 0xFFFFFFFF;

    /** Direct touch off: fill the terminal, invisible until touched, and
     * the hold fires a right-click instead of a reposition drag. */
    private boolean floating;
    private boolean tracking;
    private boolean fired;
    private boolean dragging;
    /** Floating hold fired a right-click: ignore the rest of the touch. */
    private boolean rightClicked;
    private int repeatDir;
    private int navDir;
    private float downX;
    private float downY;
    private float dx;
    private float dy;
    /** Raw coords, since the view itself moves under a reposition drag. */
    private float lastRawX;
    private float lastRawY;

    private final Runnable repeater = new Runnable() {
        @Override
        public void run() {
            sendKey(repeatDir < 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN);
            postDelayed(this, repeatInterval());
        }
    };

    private final Runnable navRepeater = new Runnable() {
        @Override
        public void run() {
            fireNav();
            postDelayed(this, navInterval());
        }
    };

    /** Armed on DOWN, cancelled the moment the finger leaves the middle;
     * firing flips the rest of this touch into a reposition drag (fixed) or
     * a right-click on the current selection (floating). */
    private final Runnable dragArm = new Runnable() {
        @Override
        public void run() {
            if (!tracking || fired || Math.hypot(dx, dy) > touchSlop) {
                return;
            }
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (floating) {
                fired = true;      // the release must not also send Enter
                rightClicked = true; // ...nor any later slide fire arrows
                callback.rightClick();
            } else {
                dragging = true;
                dx = 0;
                dy = 0;
            }
            invalidate();
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

    /** Switch between the fixed corner dot and the on-demand floating stick
     * (the owner also resizes the view to match). Any in-flight gesture is
     * dropped so a mode flip mid-touch can't strand a repeat. */
    public void setFloating(boolean floating) {
        if (this.floating == floating) {
            return;
        }
        this.floating = floating;
        reset();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                // fixed mode consumes only touches on the dot (the rest are
                // the terminal's); floating mode owns the whole terminal and
                // summons the stick at the press point
                if (!floating && Math.hypot(e.getX() - getWidth() / 2f,
                        e.getY() - getHeight() / 2f) > grabRadius) {
                    return false;
                }
                downX = e.getX();
                downY = e.getY();
                dx = 0;
                dy = 0;
                tracking = true;
                fired = false;
                dragging = false;
                rightClicked = false;
                repeatDir = 0;
                navDir = 0;
                lastRawX = e.getRawX();
                lastRawY = e.getRawY();
                // fixed: a long centre-hold repositions; floating: a normal
                // long-press right-clicks the current selection
                postDelayed(dragArm, floating ? longPressTimeout : DRAG_HOLD_MS);
                invalidate();
            }
            case MotionEvent.ACTION_MOVE -> handleMove(e);
            case MotionEvent.ACTION_UP -> {
                if (dragging) {
                    callback.dragEnd();
                } else if (!fired && Math.hypot(dx, dy) <= touchSlop) {
                    sendKey(KeyEvent.KEYCODE_ENTER);
                }
                reset();
            }
            case MotionEvent.ACTION_CANCEL -> reset();
        }
        return true;
    }

    private void handleMove(MotionEvent e) {
        if (!tracking) {
            return;
        }
        if (dragging) {
            callback.drag(e.getRawX() - lastRawX, e.getRawY() - lastRawY);
            lastRawX = e.getRawX();
            lastRawY = e.getRawY();
            return;
        }
        if (rightClicked) {
            return; // right-click fired: the stick waits for release
        }
        dx = e.getX() - downX;
        dy = e.getY() - downY;
        if (Math.hypot(dx, dy) > touchSlop) {
            removeCallbacks(dragArm); // left the middle: no re-arm
        }
        updateNav();
        // nav pauses the arrows while it's engaged
        if (navDir != 0) {
            stopRepeat();
        } else {
            updateRepeat();
        }
        invalidate();
    }

    /** Far left/right pull → directional nav, held with distance hysteresis. */
    private void updateNav() {
        // hysteresis holds the state between rearm and threshold
        int nd = dx >= navThreshold ? 1 : dx <= -navThreshold ? -1
                : Math.abs(dx) > navRearm ? navDir : 0;
        // engaging nav also needs a clearly horizontal pull (2:1 over the
        // vertical wander, ~27° off axis) — it was too easy to hit from a
        // sloppy vertical drag; once engaged, the distance hysteresis holds it
        if (nd != 0 && navDir == 0 && Math.abs(dx) < 2 * Math.abs(dy)) {
            nd = 0;
        }
        if (nd != navDir) {
            removeCallbacks(navRepeater);
            navDir = nd;
            if (nd != 0) {
                fireNav();
                postDelayed(navRepeater, navInterval());
            }
        }
    }

    /** Up/down pull → repeating arrows, deeper = faster (the repeater re-reads dy). */
    private void updateRepeat() {
        int dir = dy <= -vertThreshold ? -1 : dy >= vertThreshold ? 1 : 0;
        if (dir != repeatDir) {
            // fire on crossing (or reversal), then repeat; the repeater
            // re-reads the displacement per tick, so pushing deeper speeds it
            // up without re-crossing
            stopRepeat();
            repeatDir = dir;
            if (dir != 0) {
                sendKey(dir < 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN);
                postDelayed(repeater, repeatInterval());
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // floating: nothing at rest, and the stick sits at the press point;
        // fixed: always the view center (its corner spot)
        if (floating && !tracking) {
            return;
        }
        float cx = floating ? downX : getWidth() / 2f;
        float cy = floating ? downY : getHeight() / 2f;
        paint.setColor(Ui.withAlpha(fg, tracking ? BASE_ALPHA_ACTIVE : BASE_ALPHA));
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
        paint.setColor(Ui.withAlpha(fg, tracking ? KNOB_ALPHA_ACTIVE : KNOB_ALPHA));
        canvas.drawCircle(cx + kx, cy + ky, knobRadius, paint);
    }

    private void sendKey(int keyCode) {
        fired = true;
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        callback.sendKey(keyCode);
    }

    private void fireNav() {
        fired = true;
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        callback.nav(navDir > 0);
    }

    private long repeatInterval() {
        return lerpCadence(Math.abs(dy), vertThreshold, vertFull, REPEAT_SLOW_MS, REPEAT_FAST_MS);
    }

    private long navInterval() {
        return lerpCadence(Math.abs(dx), navThreshold, navFull, NAV_SLOW_MS, NAV_FAST_MS);
    }

    /** Interpolate the repeat delay: threshold→slow, full→fast, clamped. */
    private static long lerpCadence(float displacement, float threshold, float full,
            long slowMs, long fastMs) {
        float t = (displacement - threshold) / (full - threshold);
        t = Math.max(0f, Math.min(t, 1f));
        return Math.round(slowMs + t * (fastMs - slowMs));
    }

    private void stopRepeat() {
        repeatDir = 0;
        removeCallbacks(repeater);
    }

    private void reset() {
        tracking = false;
        dragging = false;
        rightClicked = false;
        stopRepeat();
        navDir = 0;
        removeCallbacks(navRepeater);
        removeCallbacks(dragArm);
        dx = 0;
        dy = 0;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(repeater);
        removeCallbacks(navRepeater);
        removeCallbacks(dragArm);
    }

    private int dp(int dp) {
        return Ui.dp(getContext(), dp);
    }
}
