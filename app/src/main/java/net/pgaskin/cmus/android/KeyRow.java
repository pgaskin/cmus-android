package net.pgaskin.cmus.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Extension key row shown while the IME is visible: sticky modifiers
 * (tap = one-shot, long-press = locked) plus the named keys the soft
 * keyboard lacks, injected through TerminalView so the emulator emits the
 * proper escape sequences. Modifier state is consumed via readShift/Ctrl/
 * Alt from MainActivity's TerminalViewClient stubs — the same merge
 * TerminalView applies to IME-typed characters, so sticky ctrl + a typed
 * letter works too. Groups center when they fit and scroll from the left
 * when they don't (the tab-bar pattern).
 */
public final class KeyRow extends HorizontalScrollView {
    public interface Callback {
        void sendKey(int keyCode);
    }

    private enum Sticky {OFF, ONESHOT, LOCKED}

    private final class Modifier {
        final TextView button;
        Sticky state = Sticky.OFF;

        Modifier(String label) {
            button = key(label, false);
            button.setOnClickListener(v -> {
                state = state == Sticky.OFF ? Sticky.ONESHOT : Sticky.OFF;
                refresh();
            });
            button.setOnLongClickListener(v -> {
                state = state == Sticky.LOCKED ? Sticky.OFF : Sticky.LOCKED;
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                refresh();
                return true;
            });
        }

        boolean read() {
            if (state == Sticky.OFF) {
                return false;
            }
            if (state == Sticky.ONESHOT) {
                state = Sticky.OFF;
                // reads happen mid key-dispatch; repaint after it settles
                post(this::refresh);
            }
            return true;
        }

        void clear() {
            state = Sticky.OFF;
            refresh();
        }

        void refresh() {
            // one-shot/locked = inverted block; locked adds an underline
            button.setTextColor(state == Sticky.OFF ? fg : bg);
            button.setBackgroundColor(state == Sticky.OFF ? 0 : fg);
            button.setPaintFlags(state == Sticky.LOCKED
                    ? button.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG
                    : button.getPaintFlags() & ~Paint.UNDERLINE_TEXT_FLAG);
        }
    }

    /** Repeat cadence once the long-press timeout starts a hold. */
    private static final long REPEAT_INTERVAL_MS = 75;

    private final Callback callback;
    private final LinearLayout row;
    private final List<TextView> allKeys = new ArrayList<>();
    private final Modifier shift;
    private final Modifier ctrl;
    private final Modifier alt;
    private int fg = 0xFFFFFFFF;
    private int bg = 0xFF000000;

    private int repeatKeyCode;
    private final Runnable repeater = new Runnable() {
        @Override
        public void run() {
            callback.sendKey(repeatKeyCode);
            postDelayed(this, REPEAT_INTERVAL_MS);
        }
    };

    public KeyRow(Context context, Callback callback) {
        super(context);
        this.callback = callback;
        setHorizontalScrollBarEnabled(false);
        setFillViewport(true);

        // the row's own gravity centers the groups when they fit; overflow
        // lays out from the left and scrolls (no CENTER layout gravity on
        // the child — stage-11 lesson)
        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        shift = new Modifier("shift");
        ctrl = new Modifier("ctrl");
        alt = new Modifier("alt");
        plainKey("del", KeyEvent.KEYCODE_FORWARD_DEL, false, true);
        plainKey("esc", KeyEvent.KEYCODE_ESCAPE, false, false);
        plainKey("tab", KeyEvent.KEYCODE_TAB, false, false);
        plainKey("←", KeyEvent.KEYCODE_DPAD_LEFT, true, true);
        plainKey("↓", KeyEvent.KEYCODE_DPAD_DOWN, true, false);
        plainKey("↑", KeyEvent.KEYCODE_DPAD_UP, true, false);
        plainKey("→", KeyEvent.KEYCODE_DPAD_RIGHT, true, false);
        plainKey("home", KeyEvent.KEYCODE_MOVE_HOME, false, true);
        plainKey("end", KeyEvent.KEYCODE_MOVE_END, false, false);
        plainKey("pgup", KeyEvent.KEYCODE_PAGE_UP, true, false);
        plainKey("pgdn", KeyEvent.KEYCODE_PAGE_DOWN, true, false);
    }

    /** Key text tracks the terminal font like the tabs. */
    public void setFontSize(int px) {
        for (TextView t : allKeys) {
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        }
    }

    public void applyTheme(int bg, int fg) {
        this.bg = bg;
        this.fg = fg;
        setBackgroundColor(bg);
        for (TextView t : allKeys) {
            t.setTextColor(fg);
        }
        shift.refresh();
        ctrl.refresh();
        alt.refresh();
    }

    // consumed by MainActivity's readShiftKey/readControlKey/readAltKey —
    // TerminalView merges them into every key event and IME code point

    public boolean readShift() {
        return shift.read();
    }

    public boolean readCtrl() {
        return ctrl.read();
    }

    public boolean readAlt() {
        return alt.read();
    }

    /** On IME hide: a modifier you can't see must never eat the next key. */
    public void clearModifiers() {
        shift.clear();
        ctrl.clear();
        alt.clear();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void plainKey(String label, int keyCode, boolean repeats, boolean groupStart) {
        TextView t = key(label, groupStart);
        t.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            callback.sendKey(keyCode);
        });
        if (repeats) {
            // returning true suppresses the release click; the system
            // long-press timeout is the initial repeat delay
            t.setOnLongClickListener(v -> {
                repeatKeyCode = keyCode;
                callback.sendKey(keyCode);
                postDelayed(repeater, REPEAT_INTERVAL_MS);
                return true;
            });
            t.setOnTouchListener((v, e) -> {
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    removeCallbacks(repeater);
                }
                return false;
            });
        }
    }

    private TextView key(String label, boolean groupStart) {
        TextView t = new TextView(getContext());
        t.setText(label);
        t.setTypeface(Typeface.MONOSPACE);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(9), dp(8), dp(9), dp(8));
        t.setMinWidth(dp(40));
        TypedValue tv = new TypedValue();
        getContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, tv, true);
        // ripple as the foreground so modifier state can own the background
        t.setForeground(getContext().getDrawable(tv.resourceId));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        if (groupStart) {
            // the spec's "space" separators: gaps between the groups
            lp.setMarginStart(dp(18));
        }
        row.addView(t, lp);
        allKeys.add(t);
        return t;
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
