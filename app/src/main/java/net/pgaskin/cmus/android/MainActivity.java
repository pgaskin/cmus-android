package net.pgaskin.cmus.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

public class MainActivity extends Activity implements TerminalViewClient, CmusService.SessionCallback {
    private static final String TAG = "cmus";

    /** Tab order = the 1-7 keys; names are what the `view` command takes. */
    private static final String[] VIEW_NAMES = {
            "tree", "sorted", "playlist", "queue", "browser", "filters", "settings"};
    /** Views whose tab shows only while active (rarely used; clutter). */
    private static final List<String> HIDDEN_TABS = List.of("filters", "settings");
    /** Inactive tab text: win_title_fg at ~55% alpha, blending toward bg. */
    private static final int INACTIVE_TAB_ALPHA = 0x8C000000;
    /** Protocol code for the right button (same in SGR and X10 encodings). */
    private static final int MOUSE_RIGHT_BUTTON = 2;
    /** requestPermissions code for the refresh action (0 = notifications). */
    private static final int REQUEST_REFRESH = 1;
    private static final int REQUEST_SETTINGS = 2;
    private static final String PREF_FONT = CmusService.PREF_FONT; // settings zoom slider shares it
    /** Last colorscheme name echoed by cmus (the selector highlight). */
    private static final String PREF_COLORSCHEME = "colorscheme";
    /** Selected terminal font: an asset path from FONT_ASSETS, absent = system. */
    private static final String PREF_TYPEFACE = "typeface";
    /** Joystick center as a fraction of the terminal wrapper, one pair of
     * float keys per orientation ("port"/"land" suffix); absent = the
     * original fixed corner spot. */
    private static final String PREF_JOY_X = "joy_x_";
    private static final String PREF_JOY_Y = "joy_y_";
    /** The joystick center keeps at least this far from the wrapper edges,
     * while dragging and when restoring a saved spot. */
    private static final int JOY_EDGE_DP = 64;

    /** The bundled monospace fonts (assets/fonts, license texts beside them). */
    private static final String[] FONT_NAMES = {
            "System", "Fira Mono", "IBM Plex Mono", "Iosevka", "JetBrains Mono",
            "Roboto Mono"};
    private static final String[] FONT_ASSETS = {
            null,
            "fonts/FiraMono-Regular.ttf",
            "fonts/IBMPlexMono-Regular.ttf",
            "fonts/Iosevka-Regular.ttf",
            "fonts/JetBrainsMono-Regular.ttf",
            "fonts/RobotoMono-Regular.ttf"};

    private TerminalView terminalView;
    private FrameLayout terminalWrapper;
    private View titleStrip;
    private LinearLayout topBar;
    private HorizontalScrollView tabBar;
    private ImageButton filterBtn;
    private EditText filterBox;
    private ImageButton filterClose;
    private ImageButton sleepBtn;
    private TextView sleepText;
    private ImageButton settingsBtn;
    /** With the top bar hidden, settings and the sleep timer stay
     * reachable via this faint overlay row in the terminal's top-right
     * (stage 18). */
    private LinearLayout floatBar;
    private ImageButton floatSettingsBtn;
    private ImageButton floatSleepBtn;
    private TextView floatSleepText;
    private final Runnable sleepTick = this::updateSleepSlot;
    private ControlBar controlBar;
    private KeyRow keyRow;
    private JoyDot joyDot;
    // stage-18 visibility toggles, re-read from prefs on every onStart
    private boolean topBarShown = true;
    private boolean controlBarShown = true;
    private boolean joyShown = true;
    // Direct touch input (stage 21): off = the joystick becomes an on-demand
    // floating stick filling the terminal, summoned under the finger
    private boolean directTouch = true;
    /** Joystick center in wrapper px, kept clamped by applyJoyPos. */
    private float joyCx;
    private float joyCy;
    /** The joystick is in floating mode (fills the terminal, no fixed spot). */
    private boolean joyFloating;
    private final TextView[] viewTabs = new TextView[VIEW_NAMES.length];
    // bar/cutout insets and the IME inset separately, handled asymmetrically
    // around the IME animation (one layout pass, one terminal resize, no
    // flicker either way): a hide treats the IME as gone the moment it's
    // requested (imeVisible false optimistically, re-synced by the insets
    // listener) so the layout expands under the departing keyboard; a show
    // lays out fully at animation start but *translates* the bottom chrome
    // down by the not-yet-arrived IME height and rides it up per frame on
    // the keyboard's edge (onProgress), the vacated band painted by the
    // root's cmdline background — no gap, no end-of-animation jump
    private android.graphics.Insets chromeInsets = android.graphics.Insets.NONE;
    private int imeInset;
    private boolean imeAnimating;
    private LinearLayout rootLayout;
    // each bar's share of the terminal's row-quantization remainder, worn as
    // padding on its terminal-adjoining edge
    private int tabExtra;
    private int barExtra;
    private CmusService service;
    private TerminalSession session;
    private CmusIpc ipc; // the instance ipcListener is registered on
    private CmusTheme theme;
    private String viewName;
    // last echoed live filter (null = none); the box prefills from it at
    // open and ignores echoes while open (authoritative mid-edit), the
    // search icon's tint follows it always
    private String liveFilter;
    private boolean filterBoxOpen;
    private boolean filterBoxSquelch; // programmatic setText, watcher off
    private boolean imeVisible;
    // set by a long-press right-click; the Selected event it triggers is
    // the app's cue to offer the remove dialog (uptimeMillis deadline)
    private long pendingRemoveUntil;
    private AlertDialog removeDialog;
    // the settings popover / theme / font selector (one at a time); the
    // refresh re-tints its rows when the selection echo moves
    private PopupWindow selectorPopup;
    private Runnable selectorRefresh;
    // last colorscheme name echoed by cmus (null = never seen); the name is
    // app-side bookkeeping — the colors themselves live in cmus's autosave
    private String colorschemeName;
    // last seen worker-job state; the true→false edge is any import
    // finishing (Refresh, TUI :add, anything) → toast (null = none seen)
    private Boolean jobsRunning;
    private boolean bound;
    private boolean visible;
    private boolean crashScreen;
    private int fontSize;
    private int minFontSize;
    private int maxFontSize;
    // the selected terminal font, worn by all the chrome text too (the
    // blend-with-TUI rule) and by every row-metrics computation — the
    // flushness mirror is only exact measuring what the renderer measures
    private String fontAsset; // null = system monospace
    private Typeface activeTypeface = Typeface.MONOSPACE;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, android.os.IBinder binder) {
            service = ((CmusService.LocalBinder) binder).getService();
            service.setSessionCallback(MainActivity.this);
            // binding is async: this is our +1 for the visibility count
            // (onStart skipped it while service was null); a matched -1
            // comes from onStop. Launched-then-immediately-backgrounded =
            // nothing to report
            if (visible) {
                service.setActivityVisible(true);
            }
            session = service.getSession();
            terminalView.attachSession(session);
            attachIpc();
            updateSleepSlot(); // binding is async; render the real state
            if (!session.isRunning()) {
                finish();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        minFontSize = dp(5);
        maxFontSize = dp(36);
        // the service respawns a headless pty at the last attached grid, so
        // reopening at a reset font would immediately resize it (layout
        // shift + a cmus redraw); keep the pinch-zoomed size instead
        fontSize = Math.max(minFontSize, Math.min(
                getSharedPreferences(CmusService.PREFS, MODE_PRIVATE).getInt(PREF_FONT, dp(13)),
                maxFontSize));
        colorschemeName = getSharedPreferences(CmusService.PREFS, MODE_PRIVATE)
                .getString(PREF_COLORSCHEME, null);
        // restore the font before anything measures: the saved headless pty
        // grid was sized under it (the same reasoning as the font size).
        // Iosevka is the default (Patrick); "" is the explicit System pick
        String savedFont = getSharedPreferences(CmusService.PREFS, MODE_PRIVATE)
                .getString(PREF_TYPEFACE, "fonts/Iosevka-Regular.ttf");
        fontAsset = savedFont.isEmpty() ? null : savedFont;
        activeTypeface = loadTypeface(fontAsset);

        terminalView = new TerminalView(this, null);
        terminalView.setTerminalViewClient(this);
        terminalView.setTextSize(fontSize);
        terminalView.setTypeface(activeTypeface);
        terminalView.setKeepScreenOn(false);
        terminalView.setFocusable(true);
        terminalView.setFocusableInTouchMode(true);
        terminalView.setDefaultFocusHighlightEnabled(false);
        terminalView.requestFocus();

        // view-selector tab bar; text-only, colored off the cmus theme so it
        // reads as an extension of the TUI's win-title row. The scroll view
        // is the doesn't-fit fallback for narrow screens; when the tabs fit
        // they're centered
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(Gravity.CENTER);
        for (int i = 0; i < VIEW_NAMES.length; i++) {
            String name = VIEW_NAMES[i];
            TextView tab = new TextView(this);
            tab.setText(name);
            tab.setTypeface(activeTypeface, Typeface.BOLD); // stands out from the TUI text (Patrick)
            // slightly above the terminal font, tracking pinch-zoom (onScale)
            tab.setTextSize(TypedValue.COMPLEX_UNIT_PX, tabTextSize());
            tab.setPadding(dp(5), dp(8), dp(5), dp(8));
            tab.setOnClickListener(v -> sendCommand("view " + name));
            if (HIDDEN_TABS.contains(name)) {
                tab.setVisibility(View.GONE); // clutter unless active (below)
            }
            viewTabs[i] = tab;
            tabRow.addView(tab);
        }
        // fillViewport stretches the row to the viewport so its own gravity
        // centers the tabs when they fit; when they don't, the row wraps to
        // content from the left edge and scrolls (a CENTER layout gravity
        // here would instead hang overflow off the unreachable left side)
        tabBar = new HorizontalScrollView(this);
        tabBar.setHorizontalScrollBarEnabled(false);
        tabBar.setFillViewport(true);
        tabBar.addView(tabRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // the tab bar rides in a wrapper with flanking icon slots — quick
        // filter left, sleep timer right — and morphs into the live-filter
        // search box (tabs and the right slot swap for the box and ✕)
        filterBtn = topBarButton(R.drawable.ic_search, this::toggleFilterBox);
        filterBox = new EditText(this);
        filterBox.setSingleLine(true);
        filterBox.setTypeface(activeTypeface);
        // the box swaps into the tab band, so it wears the tab size too
        filterBox.setTextSize(TypedValue.COMPLEX_UNIT_PX, tabTextSize());
        filterBox.setPadding(dp(5), dp(8), dp(5), dp(8));
        filterBox.setBackground(null); // no material underline; the bar is the chrome
        // same text band as the tabs, not Material's 48dp min — the bar
        // must not change height when the box swaps in
        filterBox.setMinHeight(0);
        filterBox.setMinimumHeight(0);
        filterBox.setHint("filter");
        // raw substring match server-side; autocorrect would fight it
        filterBox.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        filterBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        filterBox.setOnEditorActionListener((v, id, ev) -> {
            // done typing: hand focus back to the terminal so the joystick
            // and key row drive the filtered list (the box stays open)
            hideImeAndFocusTerminal();
            return true;
        });
        filterBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (filterBoxSquelch) {
                    return;
                }
                // the whole rest of the line is the filter, verbatim — no
                // quoting exists to get wrong; bare live-filter clears
                String text = s.toString().trim();
                sendCommand(text.isEmpty() ? "live-filter" : "live-filter " + text);
            }
        });
        filterBox.setOnFocusChangeListener((v, f) -> updateKeyRow());
        filterBox.setVisibility(View.GONE);
        filterClose = topBarButton(R.drawable.ic_close, () -> closeFilterBox(true));
        filterClose.setVisibility(View.GONE);

        // sleep-timer slot: the bedtime icon while off, minutes-left text
        // while armed (the service owns the countdown; this only renders it)
        sleepBtn = topBarButton(R.drawable.ic_sleep, this::showSleepDialog);
        sleepText = new TextView(this);
        sleepText.setTypeface(activeTypeface);
        sleepText.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize);
        sleepText.setPadding(dp(5), dp(8), dp(5), dp(8));
        sleepText.setOnClickListener(v -> showSleepDialog());
        sleepText.setVisibility(View.GONE);

        // the spec's faint round settings icon: tap = popover fanning out
        // to the selectors (and, come stage 17, the settings screen);
        // long-press = straight to the theme selector
        settingsBtn = topBarButton(R.drawable.ic_settings, this::showSettingsPopover);
        settingsBtn.setOnLongClickListener(v -> {
            showThemeSelector();
            return true;
        });
        updateTopBarButtons();

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBaselineAligned(false);
        topBar.addView(filterBtn);
        // wrap-content height: the tabs are the bar's intrinsic height (a
        // match_parent weighted child would instead be forced to the
        // largest fixed child — the icon squares — clipping the tab text)
        topBar.addView(tabBar, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        topBar.addView(filterBox, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        topBar.addView(sleepBtn);
        topBar.addView(sleepText);
        topBar.addView(settingsBtn);
        topBar.addView(filterClose);

        // TerminalView sizes itself from raw view bounds (ignores its own
        // padding), so insets pad a wrapper instead. Edge-to-edge coloring
        // (targetSdk 36: setStatusBarColor is a no-op) = the tab bar's
        // background paints the status-bar strip and the wrapper's paints
        // the nav strip + side margins; only icon appearance is a real API
        terminalWrapper = new FrameLayout(this);
        terminalWrapper.addView(terminalView);
        // TerminalRenderer paints row backgrounds from firstRowOffset down,
        // so the terminal's top few px stay default-bg even when the title
        // row is colored; extend the win-title band over that strip so the
        // tab bar and cmus's title row meet with no seam (touches fall
        // through a non-clickable View)
        titleStrip = new View(this);
        terminalWrapper.addView(titleStrip, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ControlBar.firstRowOffset(fontSize, activeTypeface)));

        // enter/arrows/tab without reaching for the IME: the joystick dot
        // floats over the terminal's bottom-right, riding above the IME
        // automatically as the wrapper shrinks
        // the view is bigger than the dot so the knob can travel; only
        // touches starting on the dot itself are consumed
        joyDot = new JoyDot(this, new JoyDot.Callback() {
            @Override
            public void sendKey(int keyCode) {
                injectKey(keyCode);
            }

            @Override
            public void nav(boolean right) {
                // pane-aware: cmus resolves it to win-next or the
                // adjacent view (android.c's android-nav-* input lines)
                sendCommand(right ? "android-nav-right" : "android-nav-left");
            }

            @Override
            public void drag(float dx, float dy) {
                joyCx += dx;
                joyCy += dy;
                applyJoyPos();
            }

            @Override
            public void dragEnd() {
                int w = terminalWrapper.getWidth();
                int h = terminalWrapper.getHeight();
                if (w == 0 || h == 0) {
                    return;
                }
                String sfx = joyKeySuffix();
                getSharedPreferences(CmusService.PREFS, MODE_PRIVATE).edit()
                        .putFloat(PREF_JOY_X + sfx, joyCx / w)
                        .putFloat(PREF_JOY_Y + sfx, joyCy / h)
                        .apply();
            }

            @Override
            public void rightClick() {
                // the floating-mode analogue of onLongPress: no cell, no
                // mouse event (cmus mouse tracking is off) — android-selected
                // emits a Selected event for the current selection, which
                // drives the same remove dialog through onSelected
                if (crashScreen || session == null || !session.isRunning()) {
                    return;
                }
                pendingRemoveUntil = SystemClock.uptimeMillis() + 800;
                sendCommand("android-selected");
            }
        });
        // positioned from the saved per-orientation fraction (or the fixed
        // default) on every wrapper resize, so it re-derives on rotation
        // and keeps riding above the IME as the wrapper shrinks
        terminalWrapper.addView(joyDot, new FrameLayout.LayoutParams(
                dp(120), dp(120), Gravity.TOP | Gravity.START));

        // the floating sleep + settings entries for the hidden-top-bar
        // mode; the row sits below the status-bar inset because the wrapper
        // wears that inset as top padding whenever the bar is gone
        floatSettingsBtn = topBarButton(R.drawable.ic_settings, this::showSettingsPopover);
        floatSleepBtn = topBarButton(R.drawable.ic_sleep, this::showSleepDialog);
        floatSleepText = new TextView(this);
        floatSleepText.setTypeface(activeTypeface);
        floatSleepText.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize);
        floatSleepText.setPadding(dp(5), dp(8), dp(5), dp(8));
        floatSleepText.setOnClickListener(v -> showSleepDialog());
        floatSleepText.setVisibility(View.GONE);
        floatBar = new LinearLayout(this);
        floatBar.setOrientation(LinearLayout.HORIZONTAL);
        floatBar.setGravity(Gravity.CENTER_VERTICAL);
        floatBar.addView(floatSleepBtn);
        floatBar.addView(floatSleepText);
        floatBar.addView(floatSettingsBtn);
        floatBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams floatLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        floatLp.topMargin = dp(6);
        floatLp.setMarginEnd(dp(6));
        terminalWrapper.addView(floatBar, floatLp);
        terminalWrapper.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> {
                    if (r - l != or - ol || b - t != ob - ot) {
                        placeJoyDot();
                    }
                });

        controlBar = new ControlBar(this, new ControlBar.Callback() {
            @Override
            public void sendCommand(String command) {
                MainActivity.this.sendCommand(command);
            }

            @Override
            public void toggleKeyboard() {
                toggleSoftKeyboard();
            }
        });
        controlBar.setFontSize(fontSize, activeTypeface);

        keyRow = new KeyRow(this, this::injectKey);
        keyRow.setFontSize(fontSize);
        keyRow.setTypeface(activeTypeface);
        keyRow.setVisibility(View.GONE); // until the IME shows

        LinearLayout root = rootLayout = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(terminalWrapper, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(controlBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(keyRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            chromeInsets = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            imeInset = insets.getInsets(WindowInsets.Type.ime()).bottom;
            imeVisible = insets.isVisible(WindowInsets.Type.ime());
            updateKeyRow();
            applyChromePadding();
            // a show's insets land at animation start (onPrepare has
            // already run): start the chrome fully dropped so the first
            // frame doesn't flash it at its final position
            setBottomChromeRide(imeVisible && imeAnimating ? imeInset : 0);
            return WindowInsets.CONSUMED;
        });
        root.setWindowInsetsAnimationCallback(new WindowInsetsAnimation.Callback(
                WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            @Override
            public void onPrepare(WindowInsetsAnimation animation) {
                if ((animation.getTypeMask() & WindowInsets.Type.ime()) != 0) {
                    imeAnimating = true;
                }
            }

            @Override
            public WindowInsets onProgress(WindowInsets insets,
                    List<WindowInsetsAnimation> running) {
                if (imeVisible) {
                    // ride the keyboard's animated edge up
                    setBottomChromeRide(Math.max(0,
                            imeInset - insets.getInsets(WindowInsets.Type.ime()).bottom));
                }
                return insets;
            }

            @Override
            public void onEnd(WindowInsetsAnimation animation) {
                if ((animation.getTypeMask() & WindowInsets.Type.ime()) != 0) {
                    imeAnimating = false;
                    setBottomChromeRide(0);
                }
            }
        });
        // the terminal shows whole rows and leaves the remainder as a gap
        // under the last one; absorb it into the bars so the chrome stays
        // flush with the TUI's own top and bottom rows
        root.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> updateRowGapPadding());
        setContentView(root);
        applyBarVisibility();

        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 0);
        }

        Intent intent = new Intent(this, CmusService.class);
        startForegroundService(intent);
        bound = bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        // returning from SettingsActivity: its pref changes (bars, zoom)
        // apply here; no-ops when nothing moved
        applyBarVisibility();
        applyFontSize(Math.max(minFontSize, Math.min(
                getSharedPreferences(CmusService.PREFS, MODE_PRIVATE)
                        .getInt(PREF_FONT, dp(13)), maxFontSize)));
        if (service != null) {
            service.setActivityVisible(true);
            // cmus died while we were backgrounded (idle-quit or otherwise):
            // respawn it — restart the FGS (onStartCommand re-fronts it with
            // the fresh MediaControl notification), spawn, re-attach; forced
            // resume=true makes the round trip invisible
            if (!crashScreen && (session == null || !session.isRunning())) {
                startForegroundService(new Intent(this, CmusService.class));
                session = service.getSession();
                terminalView.attachSession(session);
                attachIpc(); // respawn = a fresh CmusIpc instance
            }
            updateSleepSlot(); // restart the minute tick while visible
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible = false;
        controlBar.dismissPopup(); // a showing popup would leak the window
        dismissRemoveDialog(); // same, and it's stale by the time we're back
        dismissSelectorPopup(); // same window-leak rule
        closeFilterBox(false); // the filter itself is cmus state, kept
        sleepText.removeCallbacks(sleepTick); // no ticking while invisible
        if (service != null) {
            service.setActivityVisible(false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (ipc != null) {
            ipc.removeListener(ipcListener);
        }
        if (service != null) {
            service.setSessionCallback(null);
        }
        if (bound) {
            unbindService(connection);
        }
    }

    private int dp(int dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics()));
    }

    private String joyKeySuffix() {
        return getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE ? "land" : "port";
    }

    /**
     * Direct touch off + joystick on = floating mode: the dot fills the
     * terminal wrapper and is summoned under the finger. Otherwise it's the
     * fixed corner dot placed from the saved fraction. The layout params are
     * swapped to match; placeJoyDot no-ops while floating.
     */
    private void applyJoyMode() {
        boolean floating = !directTouch && joyShown;
        joyFloating = floating;
        joyDot.setFloating(floating);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) joyDot.getLayoutParams();
        if (floating) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.leftMargin = 0;
            lp.topMargin = 0;
            joyDot.setLayoutParams(lp);
        } else {
            lp.width = dp(120);
            lp.height = dp(120);
            joyDot.setLayoutParams(lp);
            placeJoyDot();
        }
    }

    /** Derive the joystick center from the saved fraction (or the fixed
     * default corner spot) for the wrapper's current size. No-op while
     * floating (the dot fills the wrapper — no fixed spot to place). */
    private void placeJoyDot() {
        if (joyFloating) {
            return;
        }
        int w = terminalWrapper.getWidth();
        int h = terminalWrapper.getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences(CmusService.PREFS, MODE_PRIVATE);
        String sfx = joyKeySuffix();
        float fx = prefs.getFloat(PREF_JOY_X + sfx, Float.NaN);
        float fy = prefs.getFloat(PREF_JOY_Y + sfx, Float.NaN);
        // default center sits well inside the corner so a far-right tab
        // drag has room before the finger runs off the screen edge
        joyCx = Float.isNaN(fx) ? w - dp(140) : fx * w;
        joyCy = Float.isNaN(fy) ? h - dp(150) : fy * h;
        applyJoyPos();
    }

    /** Clamp the center to ≥64dp from the wrapper edges and lay the dot
     * out there (margins against TOP|START). */
    private void applyJoyPos() {
        int w = terminalWrapper.getWidth();
        int h = terminalWrapper.getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        int edge = dp(JOY_EDGE_DP);
        joyCx = Math.max(edge, Math.min(joyCx, w - edge));
        joyCy = Math.max(edge, Math.min(joyCy, h - edge));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) joyDot.getLayoutParams();
        lp.leftMargin = Math.round(joyCx) - lp.width / 2;
        lp.topMargin = Math.round(joyCy) - lp.height / 2;
        joyDot.setLayoutParams(lp);
    }

    /**
     * Inset padding plus each bar's remainder share on its terminal-adjoining
     * edge: the tab bar's extends the win-title band (flush with cmus's own
     * title row), the control bar's the statusline band, and neither moves
     * the bar's content when the remainder changes.
     */
    /**
     * The key row exists exactly while the IME does *and* the terminal owns
     * it, sitting directly atop it below the control bar — its keys inject
     * terminal sequences, so it hides while the filter box has focus.
     */
    /** The show-animation ride: bottom chrome translated onto the rising keyboard's edge. */
    private void setBottomChromeRide(int dy) {
        controlBar.setTranslationY(dy);
        keyRow.setTranslationY(dy);
    }

    private void updateKeyRow() {
        boolean want = imeVisible && !filterBox.hasFocus();
        if (want == (keyRow.getVisibility() == View.VISIBLE)) {
            return;
        }
        keyRow.setVisibility(want ? View.VISIBLE : View.GONE);
        if (!want) {
            // an invisible modifier must never eat the next key
            keyRow.clearModifiers();
        }
        applyChromePadding();
    }

    private void applyChromePadding() {
        topBar.setPadding(chromeInsets.left, chromeInsets.top, chromeInsets.right, tabExtra);
        // the bottom-most visible chrome wears the bottom inset (the IME
        // height while the IME is (still) treated as visible)
        int bottom = imeVisible ? Math.max(chromeInsets.bottom, imeInset) : chromeInsets.bottom;
        boolean rowVisible = keyRow.getVisibility() == View.VISIBLE;
        // hidden bars (stage 18) hand their inset to the wrapper: the top
        // inset pushes the terminal (and the floating settings button)
        // below the status bar, the bottom one keeps it above the nav strip
        terminalWrapper.setPadding(chromeInsets.left,
                topBarShown ? 0 : chromeInsets.top, chromeInsets.right,
                controlBarShown || rowVisible ? 0 : bottom);
        controlBar.setPadding(chromeInsets.left, barExtra, chromeInsets.right,
                rowVisible ? 0 : bottom);
        keyRow.setPadding(chromeInsets.left, 0, chromeInsets.right,
                rowVisible ? bottom : 0);
    }

    private void updateRowGapPadding() {
        // the remainder the terminal would leave with no extras (TerminalView
        // rows = (height - firstRowOffset) / lineSpacing); adding back the
        // extras currently worn by *visible* bars makes this a fixed point
        // across relayouts (a hidden bar's padding doesn't shape the wrapper)
        int spacing = ControlBar.lineSpacing(fontSize, activeTypeface);
        int avail = terminalWrapper.getHeight()
                + (topBarShown ? tabExtra : 0) + (controlBarShown ? barExtra : 0)
                - ControlBar.firstRowOffset(fontSize, activeTypeface);
        if (avail <= 0) {
            return;
        }
        int rem = avail % spacing;
        // the remainder goes to whichever bars are visible; with neither,
        // the gap stays under the terminal wearing the wrapper's win bg
        int wantTab, wantBar;
        if (topBarShown && controlBarShown) {
            wantTab = rem / 2;
            wantBar = rem - rem / 2;
        } else if (topBarShown) {
            wantTab = rem;
            wantBar = 0;
        } else if (controlBarShown) {
            wantTab = 0;
            wantBar = rem;
        } else {
            wantTab = 0;
            wantBar = 0;
        }
        if (wantTab != tabExtra || wantBar != barExtra) {
            tabExtra = wantTab;
            barExtra = wantBar;
            applyChromePadding();
        }
    }

    /**
     * The stage-18 visibility toggles, (re)read from prefs — at attach and
     * on every onStart, so a change made in SettingsActivity applies the
     * moment MainActivity returns to the front.
     */
    private void applyBarVisibility() {
        SharedPreferences prefs = getSharedPreferences(CmusService.PREFS, MODE_PRIVATE);
        topBarShown = prefs.getBoolean(CmusService.PREF_SHOW_TOP_BAR, true);
        controlBarShown = prefs.getBoolean(CmusService.PREF_SHOW_CONTROL_BAR, true);
        joyShown = prefs.getBoolean(CmusService.PREF_SHOW_JOYSTICK, true);
        directTouch = prefs.getBoolean(CmusService.PREF_DIRECT_TOUCH, true);
        if (!topBarShown && filterBoxOpen) {
            closeFilterBox(false); // the box lives in the bar; filter kept
        }
        if (!controlBarShown) {
            controlBar.dismissPopup();
        }
        topBar.setVisibility(topBarShown ? View.VISIBLE : View.GONE);
        controlBar.setVisibility(controlBarShown ? View.VISIBLE : View.GONE);
        applyJoyMode();
        joyDot.setVisibility(joyShown && !crashScreen ? View.VISIBLE : View.GONE);
        floatBar.setVisibility(!topBarShown && !crashScreen
                ? View.VISIBLE : View.GONE);
        applyChromePadding();
    }

    // theme-driven chrome

    /**
     * (Re)registers ipcListener on the service's current CmusIpc, which is a
     * fresh instance after every respawn. addListener replays the cached
     * Options + View, so colors and the tab highlight are correct however
     * late this runs; removing the listener from a dead instance is a no-op.
     */
    private void attachIpc() {
        CmusIpc current = service.getIpc();
        if (current == null || current == ipc) {
            return;
        }
        if (ipc != null) {
            ipc.removeListener(ipcListener);
        }
        ipc = current;
        ipc.addListener(ipcListener);
    }

    /** Dropped with a log when cmus is gone (frozen crash screen). */
    private void sendCommand(String command) {
        if (ipc != null) {
            ipc.send(command);
        }
    }

    /**
     * The same injection termux's extra-keys row uses (key row + joystick
     * dot): onKeyDown emits the key's escape sequence and merges
     * readShift/Ctrl/AltKey into the meta state, so the key row's sticky
     * modifiers apply to both.
     */
    private void injectKey(int keyCode) {
        terminalView.onKeyDown(keyCode,
                new KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, 0));
    }

    private final CmusIpc.Listener ipcListener = event -> {
        switch (event) {
            case CmusIpc.Options o -> {
                CmusTheme t = CmusTheme.from(o, palette());
                if (!t.equals(theme)) {
                    theme = t;
                    applyTheme();
                }
                controlBar.onOptions(o.values());
            }
            case CmusIpc.Selected s -> onSelected(s);
            // cmus is the single source of truth for the active view: taps
            // don't move the highlight until the event comes back, and
            // TUI-side 1-7 presses/resume land here the same way
            case CmusIpc.View v -> {
                viewName = v.name();
                applyTabColors();
                scrollActiveTabIntoView();
                controlBar.onView(v.name());
            }
            case CmusIpc.Filter f -> {
                liveFilter = f.filter();
                applyTabColors();
            }
            case CmusIpc.Colorscheme c -> onColorscheme(c.name());
            case CmusIpc.Jobs j -> {
                // event-driven by design (Patrick): the diffed jobs event
                // covers every import trigger, not just our refresh
                if (Boolean.TRUE.equals(jobsRunning) && !j.running()) {
                    // any worker job: Import adds and Update cache alike
                    Toast.makeText(this, "Library update finished", Toast.LENGTH_SHORT).show();
                }
                jobsRunning = j.running();
            }
            case CmusIpc.Status s -> {
                controlBar.onStatus(s);
                updateSleepSlot(); // the expiry pause echoes back as Status
            }
            case CmusIpc.Position p -> controlBar.onPosition(p.position());
            case CmusIpc.Volume v -> controlBar.onVolume(v.left());
            default -> {
            }
        }
    };

    private void applyTheme() {
        // the band the bottom chrome vacates while riding the keyboard up
        // (and any transient unpainted area) reads as the bar's own bg
        rootLayout.setBackgroundColor(theme.cmdlineBg());
        topBar.setBackgroundColor(theme.winTitleBg());
        filterBox.setTextColor(theme.winTitleFg());
        filterBox.setHintTextColor((theme.winTitleFg() & 0x00FFFFFF) | INACTIVE_TAB_ALPHA);
        titleStrip.setBackgroundColor(theme.winTitleBg());
        terminalWrapper.setBackgroundColor(theme.winBg());
        // cmdline_bg so the bar blends with the TUI's bottom row (default =
        // terminal bg in every bundled theme)
        controlBar.applyTheme(theme.cmdlineBg(), theme.statuslineFg());
        keyRow.applyTheme(theme.cmdlineBg(), theme.statuslineFg());
        joyDot.applyTheme(theme.winFg());
        // faint over the terminal like the joystick (translucent fg tint)
        int floatTint = (theme.winFg() & 0x00FFFFFF) | 0x66000000;
        floatSettingsBtn.setImageTintList(ColorStateList.valueOf(floatTint));
        floatSleepBtn.setImageTintList(ColorStateList.valueOf(floatTint));
        floatSleepText.setTextColor((theme.winFg() & 0x00FFFFFF) | 0xAA000000);
        applyTabColors();
        int appearance = 0;
        if (CmusTheme.isLight(theme.winTitleBg())) {
            appearance |= WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
        }
        // the control bar's background paints the nav-bar strip now
        if (CmusTheme.isLight(theme.cmdlineBg())) {
            appearance |= WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        }
        getWindow().getInsetsController().setSystemBarsAppearance(appearance,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
    }

    /** The tabs (and the filter box in their band) read slightly larger than the TUI text (Patrick). */
    private int tabTextSize() {
        return Math.round(fontSize * 1.15f);
    }

    /**
     * On narrow screens the tab row overflows and scrolls (stage 11); when
     * the highlight moves (the echoed View event — taps, TUI 1-7 keys,
     * resume alike), bring the active tab fully into view. Coordinates are
     * within the row, which is the scroll content; when the tabs fit, the
     * scroll range is 0 and this no-ops.
     */
    private void scrollActiveTabIntoView() {
        for (int i = 0; i < viewTabs.length; i++) {
            if (VIEW_NAMES[i].equals(viewName)) {
                TextView tab = viewTabs[i];
                tab.post(() -> { // after layout, so the edges are real
                    if (tab.getLeft() < tabBar.getScrollX()) {
                        tabBar.smoothScrollTo(tab.getLeft(), 0);
                    } else if (tab.getRight() > tabBar.getScrollX() + tabBar.getWidth()) {
                        tabBar.smoothScrollTo(tab.getRight() - tabBar.getWidth(), 0);
                    }
                });
                return;
            }
        }
    }

    private void applyTabColors() {
        if (theme == null) {
            return;
        }
        int fg = theme.winTitleFg();
        int dim = (fg & 0x00FFFFFF) | INACTIVE_TAB_ALPHA;
        for (int i = 0; i < viewTabs.length; i++) {
            viewTabs[i].setTextColor(VIEW_NAMES[i].equals(viewName) ? fg : dim);
            // the filters/settings views are reachable from the TUI (the
            // 6/7 keys) but rarely used — their tabs are clutter unless
            // active (Patrick)
            if (HIDDEN_TABS.contains(VIEW_NAMES[i])) {
                viewTabs[i].setVisibility(VIEW_NAMES[i].equals(viewName)
                        ? View.VISIBLE : View.GONE);
            }
        }
        // the search icon doubles as the active-filter indicator (the
        // active-tab convention: full fg = a filter is applied)
        filterBtn.setImageTintList(ColorStateList.valueOf(liveFilter != null ? fg : dim));
        filterClose.setImageTintList(ColorStateList.valueOf(fg));
        sleepBtn.setImageTintList(ColorStateList.valueOf(dim));
        sleepText.setTextColor(fg); // armed = active, the tab convention
        settingsBtn.setImageTintList(ColorStateList.valueOf(dim)); // always faint
    }

    /** The live emulator palette CmusTheme resolves through; null pre-attach. */
    private int[] palette() {
        return session != null && session.getEmulator() != null
                ? session.getEmulator().mColors.mCurrentColors : null;
    }

    // CmusService.SessionCallback

    @Override
    public void onTextChanged() {
        terminalView.onScreenUpdated();
    }

    @Override
    public void onPaletteChanged() {
        // the service moved ARGB under the indexes (Material You push or
        // reset): repaint the terminal and re-resolve the chrome — the
        // cached options haven't changed, the palette behind them has
        terminalView.onScreenUpdated();
        if (ipc != null && ipc.options() != null) {
            CmusTheme t = CmusTheme.from(ipc.options(), palette());
            if (!t.equals(theme)) {
                theme = t;
                applyTheme();
            }
        }
        if (selectorRefresh != null) {
            selectorRefresh.run(); // the Material entry's highlight
        }
    }

    @Override
    public void onSessionFinished() {
        // backgrounded (idle-quit or any other death): stay in recents on
        // the frozen screen; onStart respawns cmus when the user comes back
        if (!visible) {
            return;
        }
        // a crash's last output should be readable: stay on the frozen
        // terminal (service already tore down) until the user taps out
        int exitStatus = session != null ? session.getExitStatus() : 0;
        if (exitStatus == 0) {
            finish();
        } else {
            crashScreen = true;
            dismissRemoveDialog(); // there's nothing left to remove from
            dismissSelectorPopup(); // nothing left to theme either
            closeFilterBox(false); // nothing left to filter either
            joyDot.setVisibility(View.GONE); // it must not eat the tap-out
            floatBar.setVisibility(View.GONE); // same rule
            Toast.makeText(this, "cmus exited (" + exitStatus + ") — tap to close",
                    Toast.LENGTH_LONG).show();
        }
    }

    // TerminalViewClient

    @Override
    public float onScale(float scale) {
        if (scale < 0.9f || scale > 1.1f) {
            applyFontSize(Math.max(minFontSize,
                    Math.min(Math.round(fontSize * scale), maxFontSize)));
            return 1.0f;
        }
        return scale;
    }

    /**
     * Applies a terminal font size everywhere the metrics reach (pinch-zoom
     * and the settings zoom slider are two views of the same pref).
     */
    private void applyFontSize(int size) {
        if (size == fontSize) {
            return;
        }
        fontSize = size;
        terminalView.setTextSize(fontSize);
        for (TextView tab : viewTabs) {
            tab.setTextSize(TypedValue.COMPLEX_UNIT_PX, tabTextSize());
        }
        filterBox.setTextSize(TypedValue.COMPLEX_UNIT_PX, tabTextSize());
        sleepText.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize);
        updateTopBarButtons();
        controlBar.setFontSize(fontSize, activeTypeface);
        keyRow.setFontSize(fontSize);
        titleStrip.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ControlBar.firstRowOffset(fontSize, activeTypeface)));
        getSharedPreferences(CmusService.PREFS, MODE_PRIVATE).edit()
                .putInt(PREF_FONT, fontSize).apply();
    }

    @Override
    public void onSingleTapUp(MotionEvent e) {
        if (session != null && !session.isRunning()) {
            finish(); // frozen crash screen (back only backgrounds the task)
            return;
        }
        // when cmus has mouse tracking on (CmusService forces it), the tap
        // was already sent as a click by TerminalView — same gate as termux
        if (terminalView.mEmulator != null && terminalView.mEmulator.isMouseTrackingActive()) {
            return;
        }
        toggleSoftKeyboard();
    }

    private void toggleSoftKeyboard() {
        WindowInsets insets = terminalView.getRootWindowInsets();
        if (insets != null && insets.isVisible(WindowInsets.Type.ime())) {
            hideImeAndFocusTerminal(); // optimistic: layout lands at once
        } else {
            terminalView.requestFocus();
            getSystemService(InputMethodManager.class).showSoftInput(terminalView, 0);
        }
    }

    // quick filter: the box drives cmus's live-filter per keystroke; cmus
    // stays the source of truth through the filter event (the resume file
    // restores filters at startup and a filters-view activation clears
    // them behind our back), mirrored by the search icon's tint

    private void toggleFilterBox() {
        if (filterBoxOpen) {
            // collapse keeping the filter applied; ✕ is the clearing exit
            closeFilterBox(false);
            return;
        }
        if (crashScreen || session == null || !session.isRunning()) {
            return;
        }
        filterBoxOpen = true;
        tabBar.setVisibility(View.GONE);
        sleepBtn.setVisibility(View.GONE);
        sleepText.setVisibility(View.GONE);
        settingsBtn.setVisibility(View.GONE);
        filterBox.setVisibility(View.VISIBLE);
        filterClose.setVisibility(View.VISIBLE);
        filterBoxSquelch = true;
        filterBox.setText(liveFilter == null ? "" : liveFilter);
        filterBox.setSelection(filterBox.getText().length());
        filterBoxSquelch = false;
        // the filter narrows the library, so show it (tree unless already
        // on a library view); the hidden tab highlight follows the echo
        if (!"tree".equals(viewName) && !"sorted".equals(viewName)) {
            sendCommand("view tree");
        }
        filterBox.requestFocus();
        getSystemService(InputMethodManager.class).showSoftInput(filterBox, 0);
    }

    private void closeFilterBox(boolean clear) {
        if (!filterBoxOpen) {
            return;
        }
        filterBoxOpen = false;
        if (clear) {
            sendCommand("live-filter");
        }
        filterBox.setVisibility(View.GONE);
        filterClose.setVisibility(View.GONE);
        tabBar.setVisibility(View.VISIBLE);
        settingsBtn.setVisibility(View.VISIBLE);
        updateSleepSlot(); // restores whichever of icon/countdown applies
        hideImeAndFocusTerminal();
    }

    private void hideImeAndFocusTerminal() {
        // optimistic (see the insets fields): the box losing focus would
        // otherwise flash the key row in for the hide animation, and the
        // chrome would wear the stale IME inset until the insets land
        imeVisible = false;
        terminalView.requestFocus();
        getSystemService(InputMethodManager.class)
                .hideSoftInputFromWindow(terminalView.getWindowToken(), 0);
        updateKeyRow();
        applyChromePadding();
    }

    // theme selection: the settings icon's popover fans out to the
    // selectors; each selector is a centered scrollable list over the TUI
    // (no scrim), applying live. cmus stays the source of truth for
    // colorschemes — a pick sends `colorscheme <name>` and the highlight
    // only moves when the event echoes back (TUI-typed ones land the same
    // way); the name itself is app-side bookkeeping (cmus forgets it), the
    // colors live in cmus's autosave

    /** A successful `colorscheme` echo, from either side. */
    private void onColorscheme(String name) {
        colorschemeName = name;
        getSharedPreferences(CmusService.PREFS, MODE_PRIVATE).edit()
                .putString(PREF_COLORSCHEME, name).apply();
        if (selectorRefresh != null) {
            selectorRefresh.run();
        }
    }

    private void showSettingsPopover() {
        if (theme == null || crashScreen) {
            return;
        }
        // a real Material dropdown anchored to the settings icon (the
        // sub-selectors it opens — Theme/Font — stay the centered cmus-themed
        // lists). Titles double as the switch keys.
        PopupMenu menu = new PopupMenu(this, topBarShown ? settingsBtn : floatSettingsBtn);
        menu.getMenu().add("Theme");
        menu.getMenu().add("Font");
        menu.getMenu().add("Import");
        menu.getMenu().add("Update cache");
        // the control bar owns the keyboard toggle; when it's hidden the
        // menu is the way in (stage 18)
        if (!getSharedPreferences(CmusService.PREFS, MODE_PRIVATE)
                .getBoolean(CmusService.PREF_SHOW_CONTROL_BAR, true)) {
            menu.getMenu().add("Keyboard");
        }
        menu.getMenu().add("Settings");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getTitle().toString()) {
                case "Theme" -> showThemeSelector();
                case "Font" -> showFontSelector();
                case "Import" -> refreshTracks();
                case "Update cache" -> updateCache();
                case "Keyboard" -> toggleSoftKeyboard();
                case "Settings" -> startActivityForResult(
                        new Intent(this, SettingsActivity.class), REQUEST_SETTINGS);
            }
            return true;
        });
        menu.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SETTINGS
                && resultCode == SettingsActivity.RESULT_RESET_PREFS) {
            // app prefs were reset: rebuild everything from defaults
            recreate();
        }
    }

    private void showThemeSelector() {
        if (theme == null || crashScreen || session == null || !session.isRunning()) {
            return;
        }
        // Material You pinned first, then the theme files; the generated
        // scheme's highlight is service state (it flips instantly on apply
        // and on the Colorscheme echo of whatever replaces it), the files'
        // is the last echoed name
        List<String> names = themeNames();
        String[] items = new String[names.size() + 1];
        items[0] = "Material You";
        for (int i = 0; i < names.size(); i++) {
            items[i + 1] = names.get(i);
        }
        showListPopup(null, items,
                () -> {
                    if (service != null && service.materialYouActive()) {
                        return 0;
                    }
                    // no indexOf(null): immutable lists throw NPE on it
                    int i = colorschemeName == null ? -1 : names.indexOf(colorschemeName);
                    return i < 0 ? -1 : i + 1;
                }, false,
                which -> {
                    if (which == 0) {
                        if (service != null) {
                            service.applyMaterialYou();
                        }
                    } else {
                        sendCommand("colorscheme " + names.get(which - 1));
                    }
                });
    }

    // refresh (stage 18): `add <Music>` — cmus's own recursive scan job is
    // the importer, and re-adds are dedupe no-ops (the library is keyed by
    // filename), so this is safely re-tappable with no app-side state

    /**
     * The popover's Update cache: cmus's own update-cache worker job —
     * re-reads metadata for changed files and for entries added under
     * skip_track_info (their unset mtime never matches, so no -f needed;
     * -f would re-read the whole library). Completion lands as the usual
     * Jobs true→false toast.
     */
    private void updateCache() {
        if (crashScreen || session == null || !session.isRunning()) {
            return;
        }
        Toast.makeText(this, "Updating track metadata", Toast.LENGTH_SHORT).show();
        sendCommand("update-cache");
    }

    private void refreshTracks() {
        if (crashScreen || session == null || !session.isRunning()) {
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.READ_MEDIA_AUDIO},
                    REQUEST_REFRESH);
            return; // granted → onRequestPermissionsResult resumes
        }
        Toast.makeText(this, "Adding tracks from Music folder", Toast.LENGTH_SHORT).show();
        sendCommand("add " + Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MUSIC));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        if (requestCode == REQUEST_REFRESH && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            refreshTracks(); // denied = nothing; a re-tap re-asks
        }
    }

    private void showFontSelector() {
        if (theme == null || crashScreen) {
            return;
        }
        showListPopup(null, FONT_NAMES,
                () -> {
                    for (int i = 0; i < FONT_ASSETS.length; i++) {
                        if (Objects.equals(FONT_ASSETS[i], fontAsset)) {
                            return i;
                        }
                    }
                    return -1;
                }, false,
                which -> applyFont(FONT_ASSETS[which]));
    }

    /**
     * Pure app state, unlike the colorschemes (cmus knows nothing about
     * fonts): applies + persists immediately. The renderer swap resizes the
     * pty like a pinch-zoom (the winch nudge and the flushness fixed point
     * both re-run on the resulting relayout).
     */
    private void applyFont(String asset) {
        if (Objects.equals(asset, fontAsset)) {
            return;
        }
        fontAsset = asset;
        activeTypeface = loadTypeface(asset);
        // "" = System: an absent key means the Iosevka default, not System
        getSharedPreferences(CmusService.PREFS, MODE_PRIVATE).edit()
                .putString(PREF_TYPEFACE, asset == null ? "" : asset).apply();
        terminalView.setTypeface(activeTypeface);
        for (TextView tab : viewTabs) {
            tab.setTypeface(activeTypeface, Typeface.BOLD);
        }
        filterBox.setTypeface(activeTypeface);
        sleepText.setTypeface(activeTypeface);
        floatSleepText.setTypeface(activeTypeface);
        keyRow.setTypeface(activeTypeface);
        updateTopBarButtons(); // the row metrics moved with the typeface
        controlBar.setFontSize(fontSize, activeTypeface);
        titleStrip.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ControlBar.firstRowOffset(fontSize, activeTypeface)));
        if (selectorRefresh != null) {
            // moves the highlight; the open selector's rows keep their
            // construction-time face until the next open — acceptable
            selectorRefresh.run();
        }
    }

    /** null/missing asset (e.g. a renamed bundle) falls back to system. */
    private Typeface loadTypeface(String asset) {
        if (asset != null) {
            try {
                return Typeface.createFromAsset(getAssets(), asset);
            } catch (RuntimeException e) {
                Log.w(TAG, "font: failed to load " + asset, e);
            }
        }
        return Typeface.MONOSPACE;
    }

    /**
     * Union of user themes (CMUS_HOME — wins in cmd_colorscheme's search
     * order) and the bundled ones (CMUS_DATA_DIR), sorted. A name containing
     * whitespace can't be applied (`colorscheme` takes exactly one arg), so
     * it isn't offered.
     */
    private List<String> themeNames() {
        TreeSet<String> names = new TreeSet<>();
        for (File dir : new File[]{CmusFiles.home(this), CmusFiles.data(this)}) {
            File[] files = dir.listFiles();
            if (files == null) {
                continue;
            }
            for (File f : files) {
                String n = f.getName();
                if (n.endsWith(".theme") && n.indexOf(' ') < 0 && n.indexOf('\t') < 0) {
                    names.add(n.substring(0, n.length() - ".theme".length()));
                }
            }
        }
        return List.copyOf(names);
    }

    /**
     * The shared list popup: anchored dropdown (popover) or centered over
     * the terminal capped at ~60% of it (selector), win colors with a 1dp
     * separator frame, no scrim so the TUI stays visible around it. The
     * selected row is full win_fg, the rest dimmed (the tab convention);
     * selectorRefresh re-reads the supplier so echo-driven selection moves
     * without rebuilding. Picks that apply live keep the popup open.
     */
    private void showListPopup(View anchor, String[] items, IntSupplier selected,
            boolean dismissOnPick, IntConsumer onPick) {
        dismissSelectorPopup();
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        TextView[] rows = new TextView[items.length];
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        for (int i = 0; i < items.length; i++) {
            int which = i;
            TextView t = new TextView(this);
            t.setText(items[i]);
            t.setTypeface(activeTypeface);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize);
            t.setPadding(dp(14), dp(8), dp(14), dp(8));
            // ripple as the foreground; the popup bg stays the win color
            t.setForeground(getDrawable(tv.resourceId));
            t.setOnClickListener(v -> {
                if (dismissOnPick) {
                    dismissSelectorPopup();
                }
                onPick.accept(which);
            });
            rows[i] = t;
            list.addView(t, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(list);
        scroll.setBackgroundColor(theme.winBg());
        FrameLayout frame = new FrameLayout(this);
        int border = Math.max(1, dp(1));
        frame.setPadding(border, border, border, border);
        frame.setBackgroundColor(theme.separator());
        frame.addView(scroll);
        selectorRefresh = () -> {
            int sel = selected.getAsInt();
            int dim = (theme.winFg() & 0x00FFFFFF) | INACTIVE_TAB_ALPHA;
            for (int i = 0; i < rows.length; i++) {
                rows[i].setTextColor(i == sel ? theme.winFg() : dim);
            }
        };
        selectorRefresh.run();
        PopupWindow popup = new PopupWindow(frame, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true); // focusable: back dismisses
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(6));
        popup.setOnDismissListener(() -> {
            if (selectorPopup == popup) {
                selectorPopup = null;
                selectorRefresh = null;
            }
        });
        selectorPopup = popup;
        if (anchor != null) {
            popup.showAsDropDown(anchor);
            return;
        }
        frame.measure(
                View.MeasureSpec.makeMeasureSpec(
                        terminalWrapper.getWidth() * 4 / 5, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(
                        terminalWrapper.getHeight() * 3 / 5, View.MeasureSpec.AT_MOST));
        popup.setWidth(frame.getMeasuredWidth());
        popup.setHeight(frame.getMeasuredHeight());
        popup.showAtLocation(rootLayout, Gravity.CENTER, 0, 0);
    }

    private void dismissSelectorPopup() {
        if (selectorPopup != null) {
            selectorPopup.dismiss();
            selectorPopup = null;
            selectorRefresh = null;
        }
    }

    /** Icon button flanking the tab bar; sized by updateTopBarButtons. */
    private ImageButton topBarButton(int icon, Runnable action) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        b.setBackgroundResource(tv.resourceId);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    /**
     * The top-bar icons match the control bar's sizing (Patrick: same icon
     * size top and bottom): 3-terminal-row square, 2-row glyph. The bar
     * grows to them; the tab text keeps the terminal font size and centers
     * in the taller band.
     */
    private void updateTopBarButtons() {
        int line = ControlBar.lineSpacing(fontSize, activeTypeface);
        int pad = line / 2; // (3 lines - 2-line icon) / 2, the ControlBar math
        if (floatBar != null) { // built after the first call
            for (ImageButton b : new ImageButton[]{floatSleepBtn, floatSettingsBtn}) {
                b.setPadding(pad, pad, pad, pad);
                b.setLayoutParams(new LinearLayout.LayoutParams(3 * line, 3 * line));
            }
            floatSleepText.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize);
        }
        for (ImageButton b : new ImageButton[]{filterBtn, filterClose, sleepBtn, settingsBtn}) {
            b.setPadding(pad, pad, pad, pad);
            b.setLayoutParams(new LinearLayout.LayoutParams(3 * line, 3 * line));
        }
    }

    // sleep timer: the service owns the countdown (it must tick with the
    // activity gone); this renders it and hosts the duration selector

    private static final int[] SLEEP_PRESETS = {15, 30, 45, 60, 90};

    /**
     * Bedtime icon when off, minutes-left text when armed, re-posted on the
     * deadline's next minute boundary while visible. Status events land
     * here too, so the expiry pause echo reverts the slot promptly.
     */
    private void updateSleepSlot() {
        sleepText.removeCallbacks(sleepTick);
        long remaining = service != null ? service.sleepRemainingMs() : 0;
        if (filterBoxOpen) {
            return; // the slot is hidden; closeFilterBox re-renders it
        }
        if (remaining == 0) {
            sleepBtn.setVisibility(View.VISIBLE);
            sleepText.setVisibility(View.GONE);
            floatSleepBtn.setVisibility(View.VISIBLE);
            floatSleepText.setVisibility(View.GONE);
            return;
        }
        String left = (remaining + 59_999) / 60_000 + "m";
        sleepText.setText(left);
        sleepBtn.setVisibility(View.GONE);
        sleepText.setVisibility(View.VISIBLE);
        floatSleepText.setText(left);
        floatSleepBtn.setVisibility(View.GONE);
        floatSleepText.setVisibility(View.VISIBLE);
        if (visible) {
            sleepText.postDelayed(sleepTick, remaining % 60_000 + 100);
        }
    }

    private void showSleepDialog() {
        if (service == null) {
            return;
        }
        long remaining = service.sleepRemainingMs();
        int n = SLEEP_PRESETS.length;
        String[] items = new String[n + (remaining > 0 ? 2 : 1)];
        for (int i = 0; i < n; i++) {
            items[i] = SLEEP_PRESETS[i] + " minutes";
        }
        items[n] = "Custom…";
        if (remaining > 0) {
            items[n + 1] = "Turn off (" + (remaining + 59_999) / 60_000 + "m left)";
        }
        // removeDialog = "the current dialog": stop/crash dismissal covers
        // this one the same way as the item dialogs
        removeDialog = new AlertDialog.Builder(this)
                .setTitle("Sleep timer")
                .setItems(items, (d, which) -> {
                    if (which < n) {
                        setSleepTimer(SLEEP_PRESETS[which]);
                    } else if (which == n) {
                        promptSleepMinutes();
                    } else {
                        service.cancelSleepTimer();
                        updateSleepSlot();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptSleepMinutes() {
        EditText input = new EditText(this);
        input.setHint("Minutes");
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        removeDialog = new AlertDialog.Builder(this)
                .setTitle("Sleep timer")
                .setView(input)
                .setPositiveButton("Start", (d, w) -> {
                    int minutes;
                    try {
                        minutes = Integer.parseInt(input.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        return;
                    }
                    if (minutes > 0) {
                        setSleepTimer(minutes);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setSleepTimer(int minutes) {
        if (service != null) {
            service.setSleepTimer(minutes);
            updateSleepSlot();
        }
    }

    @Override
    public boolean shouldBackButtonBeMappedToEscape() {
        // back backgrounds the app; playback continues under the FGS
        return false;
    }

    @Override
    public boolean shouldEnforceCharBasedInput() {
        return false;
    }

    @Override
    public boolean shouldUseCtrlSpaceWorkaround() {
        return false;
    }

    @Override
    public boolean isTerminalViewSelected() {
        return true;
    }

    @Override
    public void copyModeChanged(boolean copyMode) {
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
        // the emulator's crash banner says "press Enter"; tap also works
        if (keyCode == KeyEvent.KEYCODE_ENTER && session != null && !session.isRunning()) {
            finish();
            return true;
        }
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        return false;
    }

    @Override
    public boolean onLongPress(MotionEvent event) {
        // always consumed: returning true is TerminalView's only entry into
        // text-selection mode, so this is also the no-text-selection switch
        TerminalEmulator emu = terminalView.mEmulator;
        if (crashScreen || emu == null || session == null || !session.isRunning()
                || !emu.isMouseTrackingActive()) {
            return true;
        }
        terminalView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        int[] cell = terminalView.getColumnAndRow(event, false);
        // long-press = right-click: no mrb_* key is bound in the default rc,
        // so cmus moves the selection to the pressed row (switching the
        // active pane if needed) and, when the click resolves to a
        // removable selection, answers with a Selected event — the cue for
        // the remove dialog. Clicks that resolve to nothing (title/status
        // rows, past the list end, command/search mode, views whose
        // win-remove prompts in the TUI) produce no event, and the pending
        // window just lapses.
        emu.sendMouseEvent(MOUSE_RIGHT_BUTTON, cell[0] + 1, cell[1] + 1, true);
        emu.sendMouseEvent(MOUSE_RIGHT_BUTTON, cell[0] + 1, cell[1] + 1, false);
        pendingRemoveUntil = SystemClock.uptimeMillis() + 800;
        return true;
    }

    /**
     * cmus only emits Selected where the offered actions run without a
     * TUI confirmation prompt, and the files are the exact set win-remove
     * would act on (marked-tracks rule included) — so the dialogs just
     * present them. Gated on a recent long-press: right-clicks are the
     * only source today, but the event is a broadcast, not an answer.
     */
    private void onSelected(CmusIpc.Selected s) {
        if (SystemClock.uptimeMillis() > pendingRemoveUntil) {
            return;
        }
        pendingRemoveUntil = 0;
        dismissRemoveDialog();
        // the playlist-view list pane: the item is the playlist itself
        if (s.playlist() != null) {
            removeDialog = new AlertDialog.Builder(this)
                    .setTitle("Remove playlist?")
                    .setMessage(s.playlist())
                    .setPositiveButton("Remove", (d, w) ->
                            sendCommand("android-pl-delete " + s.playlist()))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        if (s.files().isEmpty()) {
            return;
        }
        String view = s.view();
        removeDialog = new AlertDialog.Builder(this)
                .setTitle(s.files().size() == 1 ? "Remove?"
                        : "Remove " + s.files().size() + " tracks?")
                .setMessage(String.join("\n", s.files()))
                .setPositiveButton("Remove", (d, w) -> {
                    // a TUI-side view switch behind the dialog would
                    // retarget win-remove; cmus is the source of truth
                    if (Objects.equals(view, viewName)) {
                        sendCommand("win-remove");
                    }
                })
                .setNeutralButton("Add to playlist", (d, w) ->
                        showAddToPlaylist(s.playlists()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** The chooser behind the item dialog's "Add to playlist" action. */
    private void showAddToPlaylist(List<String> playlists) {
        String[] items = new String[playlists.size() + 1];
        for (int i = 0; i < playlists.size(); i++) {
            items[i] = playlists.get(i);
        }
        items[items.length - 1] = "New playlist…";
        removeDialog = new AlertDialog.Builder(this)
                .setTitle("Add to playlist")
                .setItems(items, (d, which) -> {
                    if (which < playlists.size()) {
                        // android-pl-add: verbatim rest-of-line name, no
                        // command-tokenizer quoting to get wrong
                        sendCommand("android-pl-add " + playlists.get(which));
                    } else {
                        promptNewPlaylist();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptNewPlaylist() {
        EditText input = new EditText(this);
        input.setHint("Playlist name");
        removeDialog = new AlertDialog.Builder(this)
                .setTitle("New playlist")
                .setView(input)
                .setPositiveButton("Create", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        // pl-create takes the rest of the line as the name;
                        // ordered writes make the add see the new list
                        sendCommand("pl-create " + name);
                        sendCommand("android-pl-add " + name);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void dismissRemoveDialog() {
        if (removeDialog != null) {
            removeDialog.dismiss();
            removeDialog = null;
        }
    }

    @Override
    public boolean readControlKey() {
        return keyRow.readCtrl();
    }

    @Override
    public boolean readAltKey() {
        return keyRow.readAlt();
    }

    @Override
    public boolean readShiftKey() {
        return keyRow.readShift();
    }

    @Override
    public boolean readFnKey() {
        return false;
    }

    @Override
    public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
        return false;
    }

    @Override
    public void onEmulatorSet() {
        // fires whenever the view resized the pty grid: nudge cmus to
        // re-check the tty size — the kernel's SIGWINCH is lost if it beats
        // cmus's handler install (attaching to a just-spawned session does)
        // and racy against its select entry; the intent line both sets the
        // resize flag and wakes the loop (dropped harmlessly when the IPC
        // isn't up yet; the connect-time nudge covers that window)
        sendCommand("android-winch");
    }

    // logging

    @Override
    public void logError(String tag, String message) {
        Log.e(TAG, message);
    }

    @Override
    public void logWarn(String tag, String message) {
        Log.w(TAG, message);
    }

    @Override
    public void logInfo(String tag, String message) {
        Log.i(TAG, message);
    }

    @Override
    public void logDebug(String tag, String message) {
        Log.d(TAG, message);
    }

    @Override
    public void logVerbose(String tag, String message) {
        Log.v(TAG, message);
    }

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {
        Log.e(TAG, message, e);
    }

    @Override
    public void logStackTrace(String tag, Exception e) {
        Log.e(TAG, "", e);
    }
}
