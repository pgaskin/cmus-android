package net.pgaskin.cmus.android;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.Toast;

import java.util.Map;

/**
 * Compact bottom control bar: play/pause, repeat, shuffle, seek, volume
 * (popup slider, shown only while softvol is on — the aaudio op has no
 * mixer), add-selected-to-queue, keyboard toggle. Colored off the
 * statusline theme colors; a pure mirror of cmus state — taps send
 * commands and the visuals change only when the event echoes back, the
 * same single-source-of-truth rule as the tab bar. Sized in terminal rows
 * (bar 3, icons 2) so it tracks pinch-zoom with the rest of the chrome.
 */
public final class ControlBar extends LinearLayout {
    public interface Callback {
        void sendCommand(String command);

        void toggleKeyboard();
    }

    /** Inactive toggle tint: fg at ~55% alpha, same blend as inactive tabs. */
    private static final int INACTIVE_ALPHA = 0x8C000000;

    private final ImageButton playPause;
    private final ImageButton repeat;
    private final ImageButton shuffle;
    private final ImageButton volume;
    private final ImageButton queue;
    private final CmusSlider seek;
    private final CmusSlider volumeSlider;
    private final PopupWindow volumePopup;
    private final ImageButton[] buttons;

    private Toast toast;
    private CmusIpc.PlayState state = CmusIpc.PlayState.STOPPED;
    private boolean repeatOn;
    private String shuffleMode = "off";
    private String viewName = "";
    private boolean softvol;
    private int fg = 0xFFFFFFFF;
    private int barHeight;

    // seek extrapolation between the ~1/s position events: the last event
    // rebases (positions are fractional seconds, so rebasing never jumps),
    // and while PLAYING a per-frame ticker advances the thumb from it (the
    // slider only redraws on visible movement)
    private double basePosition;
    private long baseTime;
    private boolean ticking;
    private final Runnable seekTicker = new Runnable() {
        @Override
        public void run() {
            if (!ticking) {
                return;
            }
            seek.setProgress((float) (basePosition
                    + (SystemClock.elapsedRealtime() - baseTime) / 1000.0));
            postOnAnimation(this);
        }
    };

    public ControlBar(Context context, Callback callback) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        playPause = button(R.drawable.ic_play, () -> callback.sendCommand(switch (state) {
            // player-play *restarts* a loaded track, so it's only for
            // STOPPED; pausing uses the idempotent form (no toggle race) —
            // the same mapping as MediaControl
            case PLAYING -> "player-pause-playback";
            case PAUSED -> "player-pause";
            case STOPPED -> "player-play";
        }));
        // toasts predict the toggle's result from the current (echoed) state,
        // so they read as "what this tap does"
        repeat = button(R.drawable.ic_repeat, () -> {
            callback.sendCommand("toggle repeat");
            toast(repeatOn ? "Repeat off" : "Repeat on");
        });
        // toggle shuffle cycles off -> tracks -> albums -> off
        shuffle = button(R.drawable.ic_shuffle, () -> {
            callback.sendCommand("toggle shuffle");
            toast(switch (shuffleMode) {
                case "tracks" -> "Shuffling albums";
                case "albums" -> "Shuffle off";
                default -> "Shuffling tracks";
            });
        });

        seek = new CmusSlider(context, false);
        seek.setListener(new CmusSlider.Listener() {
            @Override
            public void onDrag(int progress) {
                // no live scrubbing; the release seeks
            }

            @Override
            public void onRelease(int progress) {
                callback.sendCommand("seek " + progress);
                // predict the echo so the thumb doesn't snap back for the
                // round trip; the position event rebases again anyway
                rebaseSeek(progress);
            }
        });
        addView(seek);

        volume = button(R.drawable.ic_volume, this::toggleVolumePopup);
        volume.setVisibility(GONE); // until an Options event says softvol
        // in the queue view adding makes no sense (it would duplicate the
        // selection), so the button flips to remove-from-queue — gated on
        // the echoed view so win-remove can never touch the library; in
        // the browser it flips to + = add-to-library (Patrick), the
        // browser's `a` binding
        queue = button(R.drawable.ic_queue_add, () -> {
            final String cmd, msg;
            switch (viewName) {
                case "queue" -> { cmd = "win-remove"; msg = "Removing from queue"; }
                case "browser" -> { cmd = "win-add-l"; msg = "Adding to library"; }
                default -> { cmd = "win-add-q"; msg = "Adding to queue"; }
            }
            callback.sendCommand(cmd);
            toast(msg);
        });
        ImageButton keyboard = button(R.drawable.ic_keyboard, callback::toggleKeyboard);
        buttons = new ImageButton[]{playPause, repeat, shuffle, volume, queue, keyboard};

        volumeSlider = new CmusSlider(context, true);
        volumeSlider.setMax(100);
        volumeSlider.setListener(new CmusSlider.Listener() {
            @Override
            public void onDrag(int progress) {
                callback.sendCommand("vol " + progress);
            }

            @Override
            public void onRelease(int progress) {
                callback.sendCommand("vol " + progress);
            }
        });
        // not focusable: a focusable popup takes window focus from the
        // terminal, which hides the soft keyboard; the slider only needs
        // touch, and outside-touch still dismisses (back won't, since keys
        // keep going to the activity)
        volumePopup = new PopupWindow(volumeSlider, 0, 0, false);
        volumePopup.setOutsideTouchable(true);
        volumePopup.setElevation(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6,
                getResources().getDisplayMetrics()));
    }

    /** Bar = 3 terminal rows tall, icons = 2; tracks pinch-zoom + the font. */
    public void setFontSize(int px, Typeface typeface) {
        int line = lineSpacing(px, typeface);
        barHeight = 3 * line;
        int pad = line / 2; // (3 lines - 2-line icon) / 2
        for (ImageButton b : buttons) {
            b.setPadding(pad, pad, pad, pad);
            b.setLayoutParams(new LayoutParams(barHeight, barHeight));
        }
        seek.setLayoutParams(new LayoutParams(0, barHeight, 1));
        volumePopup.dismiss();
    }

    public void applyTheme(int bg, int fg) {
        this.fg = fg;
        setBackgroundColor(bg);
        volumePopup.setBackgroundDrawable(new ColorDrawable(bg));
        volumePopup.dismiss(); // a shown popup doesn't take the new bg
        seek.setColor(fg);
        volumeSlider.setColor(fg);
        applyTints();
    }

    /** For activity teardown/backgrounding; a showing popup leaks windows. */
    public void dismissPopup() {
        volumePopup.dismiss();
    }

    public void onStatus(CmusIpc.Status s) {
        state = s.state();
        playPause.setImageResource(state == CmusIpc.PlayState.PLAYING
                ? R.drawable.ic_pause : R.drawable.ic_play);
        seek.setEnabled(s.file() != null && state != CmusIpc.PlayState.STOPPED);
        seek.setMax(Math.max(s.duration(), 0));
        rebaseSeek(Math.max(s.position(), 0));
    }

    public void onPosition(double position) {
        rebaseSeek(position);
    }

    private void rebaseSeek(double position) {
        basePosition = position;
        baseTime = SystemClock.elapsedRealtime();
        seek.setProgress((float) position);
        updateSeekTicker();
    }

    private void updateSeekTicker() {
        boolean want = state == CmusIpc.PlayState.PLAYING
                && isAttachedToWindow() && getWindowVisibility() == VISIBLE;
        if (want == ticking) {
            return;
        }
        ticking = want;
        removeCallbacks(seekTicker);
        if (want) {
            postOnAnimation(seekTicker);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateSeekTicker();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        updateSeekTicker();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateSeekTicker();
    }

    public void onVolume(int left) {
        volumeSlider.setEnabled(left >= 0);
        volumeSlider.setProgress(Math.max(left, 0));
    }

    public void onView(String name) {
        viewName = name;
        queue.setImageResource(switch (name) {
            case "queue" -> R.drawable.ic_queue_remove;
            case "browser" -> R.drawable.ic_add;
            default -> R.drawable.ic_queue_add;
        });
    }

    public void onOptions(Map<String, String> options) {
        repeatOn = "true".equals(options.get("repeat"));
        shuffleMode = options.getOrDefault("shuffle", "off");
        boolean sv = "true".equals(options.get("softvol"));
        if (sv != softvol) {
            softvol = sv;
            volume.setVisibility(sv ? VISIBLE : GONE);
            if (!sv) {
                volumePopup.dismiss();
            }
        }
        applyTints();
    }

    private void toggleVolumePopup() {
        if (volumePopup.isShowing()) {
            volumePopup.dismiss();
            return;
        }
        int h = 3 * barHeight;
        volumePopup.setWidth(barHeight);
        volumePopup.setHeight(h);
        // yoff walks back up past the popup and the anchor: popup bottom
        // lands at the anchor's top
        volumePopup.showAsDropDown(volume, 0, -(h + barHeight));
    }

    private void applyTints() {
        int dim = (fg & 0x00FFFFFF) | INACTIVE_ALPHA;
        for (ImageButton b : buttons) {
            b.setImageTintList(ColorStateList.valueOf(fg));
        }
        repeat.setImageTintList(ColorStateList.valueOf(repeatOn ? fg : dim));
        shuffle.setImageTintList(ColorStateList.valueOf("off".equals(shuffleMode) ? dim : fg));
        shuffle.setImageResource("albums".equals(shuffleMode)
                ? R.drawable.ic_shuffle_albums : R.drawable.ic_shuffle);
    }

    // cancel the previous toast so a new one shows instantly instead of
    // queueing behind it
    private void toast(String message) {
        if (toast != null) {
            toast.cancel();
        }
        toast = Toast.makeText(getContext(), message, Toast.LENGTH_SHORT);
        toast.show();
    }

    private ImageButton button(int icon, Runnable action) {
        ImageButton b = new ImageButton(getContext());
        b.setImageResource(icon);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        TypedValue tv = new TypedValue();
        getContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, tv, true);
        b.setBackgroundResource(tv.resourceId);
        b.setOnClickListener(v -> action.run());
        addView(b);
        return b;
    }

    /**
     * TerminalRenderer's row metrics, mirrored at the *active* typeface —
     * the mirror is only exact measuring what the renderer measures, and
     * since stage 16 the terminal font is selectable — so chrome sizing
     * and the row-remainder math quantize exactly like the terminal.
     */
    public static int lineSpacing(int textSizePx, Typeface typeface) {
        return (int) Math.ceil(paintFor(textSizePx, typeface).getFontSpacing());
    }

    /** The renderer's mFontLineSpacingAndAscent: the first row's top offset. */
    public static int firstRowOffset(int textSizePx, Typeface typeface) {
        Paint p = paintFor(textSizePx, typeface);
        return (int) Math.ceil(p.getFontSpacing()) + (int) Math.ceil(p.ascent());
    }

    private static Paint paintFor(int textSizePx, Typeface typeface) {
        Paint p = new Paint();
        p.setTypeface(typeface);
        p.setTextSize(textSizePx);
        return p;
    }
}
