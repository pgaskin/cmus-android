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
    private final CmusSlider seek;
    private final CmusSlider volumeSlider;
    private final PopupWindow volumePopup;
    private final ImageButton[] buttons;

    private CmusIpc.PlayState state = CmusIpc.PlayState.STOPPED;
    private boolean repeatOn;
    private String shuffleMode = "off";
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
        repeat = button(R.drawable.ic_repeat, () -> callback.sendCommand("toggle repeat"));
        shuffle = button(R.drawable.ic_shuffle, () -> callback.sendCommand("toggle shuffle"));

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
        ImageButton addQueue = button(R.drawable.ic_queue_add,
                () -> callback.sendCommand("win-add-q"));
        ImageButton keyboard = button(R.drawable.ic_keyboard, callback::toggleKeyboard);
        buttons = new ImageButton[]{playPause, repeat, shuffle, volume, addQueue, keyboard};

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
        volumePopup = new PopupWindow(volumeSlider, 0, 0, true);
        volumePopup.setOutsideTouchable(true);
        volumePopup.setElevation(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6,
                getResources().getDisplayMetrics()));
    }

    /** Bar = 3 terminal rows tall, icons = 2; tracks pinch-zoom. */
    public void setFontSize(int px) {
        int line = lineSpacing(px);
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
     * TerminalRenderer's row metrics, mirrored (MONOSPACE paint — what
     * TerminalView keeps through setTextSize) so chrome sizing and the
     * row-remainder math quantize exactly like the terminal.
     */
    public static int lineSpacing(int textSizePx) {
        return (int) Math.ceil(paintFor(textSizePx).getFontSpacing());
    }

    /** The renderer's mFontLineSpacingAndAscent: the first row's top offset. */
    public static int firstRowOffset(int textSizePx) {
        Paint p = paintFor(textSizePx);
        return (int) Math.ceil(p.getFontSpacing()) + (int) Math.ceil(p.ascent());
    }

    private static Paint paintFor(int textSizePx) {
        Paint p = new Paint();
        p.setTypeface(Typeface.MONOSPACE);
        p.setTextSize(textSizePx);
        return p;
    }
}
