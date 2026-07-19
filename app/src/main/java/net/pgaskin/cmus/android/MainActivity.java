package net.pgaskin.cmus.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
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
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.util.List;
import java.util.Objects;

public class MainActivity extends Activity implements TerminalViewClient, TermService.SessionCallback {
    private static final String TAG = "cmus";

    /** Tab order = the 1-7 keys; names are what the `view` command takes. */
    private static final String[] VIEW_NAMES = {
            "tree", "sorted", "playlist", "queue", "browser", "filters", "settings"};
    /** Inactive tab text: win_title_fg at ~55% alpha, blending toward bg. */
    private static final int INACTIVE_TAB_ALPHA = 0x8C000000;
    /** Protocol code for the right button (same in SGR and X10 encodings). */
    private static final int MOUSE_RIGHT_BUTTON = 2;
    private static final String PREF_FONT = "font";

    private TerminalView terminalView;
    private FrameLayout terminalWrapper;
    private View titleStrip;
    private LinearLayout topBar;
    private HorizontalScrollView tabBar;
    private ImageButton filterBtn;
    private EditText filterBox;
    private ImageButton filterClose;
    private ControlBar controlBar;
    private KeyRow keyRow;
    private JoyDot joyDot;
    private final TextView[] viewTabs = new TextView[VIEW_NAMES.length];
    private android.graphics.Insets chromeInsets = android.graphics.Insets.NONE;
    // each bar's share of the terminal's row-quantization remainder, worn as
    // padding on its terminal-adjoining edge
    private int tabExtra;
    private int barExtra;
    private TermService service;
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
    private boolean bound;
    private boolean visible;
    private boolean crashScreen;
    private int fontSize;
    private int minFontSize;
    private int maxFontSize;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, android.os.IBinder binder) {
            service = ((TermService.LocalBinder) binder).getService();
            service.setSessionCallback(MainActivity.this);
            // binding is async; the service defaults to visible, so only the
            // launched-then-immediately-backgrounded window needs correcting
            service.setActivityVisible(visible);
            session = service.getSession();
            terminalView.attachSession(session);
            attachIpc();
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
                getSharedPreferences(TermService.PREFS, MODE_PRIVATE).getInt(PREF_FONT, dp(13)),
                maxFontSize));

        terminalView = new TerminalView(this, null);
        terminalView.setTerminalViewClient(this);
        terminalView.setTextSize(fontSize);
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
            tab.setTypeface(Typeface.MONOSPACE);
            // matches the terminal font size, including pinch-zoom (onScale)
            tab.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize);
            tab.setPadding(dp(5), dp(8), dp(5), dp(8));
            tab.setOnClickListener(v -> sendCommand("view " + name));
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
        filterBox.setTypeface(Typeface.MONOSPACE);
        filterBox.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize);
        filterBox.setPadding(dp(5), dp(8), dp(5), dp(8));
        filterBox.setBackground(null); // no material underline; the bar is the chrome
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

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.addView(filterBtn);
        topBar.addView(tabBar, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        topBar.addView(filterBox, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
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
                ViewGroup.LayoutParams.MATCH_PARENT, ControlBar.firstRowOffset(fontSize)));

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
        });
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(
                dp(120), dp(120), Gravity.BOTTOM | Gravity.END);
        // center sits well inside the corner so a far-right tab drag has
        // room before the finger runs off the screen edge
        dotLp.setMargins(0, 0, dp(80), dp(90));
        terminalWrapper.addView(joyDot, dotLp);

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
        controlBar.setFontSize(fontSize);

        keyRow = new KeyRow(this, this::injectKey);
        keyRow.setFontSize(fontSize);
        keyRow.setVisibility(View.GONE); // until the IME shows

        LinearLayout root = new LinearLayout(this);
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
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            imeVisible = insets.isVisible(WindowInsets.Type.ime());
            updateKeyRow();
            applyChromePadding();
            return WindowInsets.CONSUMED;
        });
        // the terminal shows whole rows and leaves the remainder as a gap
        // under the last one; absorb it into the bars so the chrome stays
        // flush with the TUI's own top and bottom rows
        root.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> updateRowGapPadding());
        setContentView(root);

        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 0);
        }

        Intent intent = new Intent(this, TermService.class);
        startForegroundService(intent);
        bound = bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        if (service != null) {
            service.setActivityVisible(true);
            // cmus died while we were backgrounded (idle-quit or otherwise):
            // respawn it — restart the FGS (onStartCommand re-fronts it with
            // the fresh MediaControl notification), spawn, re-attach; forced
            // resume=true makes the round trip invisible
            if (!crashScreen && (session == null || !session.isRunning())) {
                startForegroundService(new Intent(this, TermService.class));
                session = service.getSession();
                terminalView.attachSession(session);
                attachIpc(); // respawn = a fresh CmusIpc instance
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible = false;
        controlBar.dismissPopup(); // a showing popup would leak the window
        dismissRemoveDialog(); // same, and it's stale by the time we're back
        closeFilterBox(false); // the filter itself is cmus state, kept
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
        terminalWrapper.setPadding(chromeInsets.left, 0, chromeInsets.right, 0);
        // the bottom-most visible chrome wears the bottom inset (the IME
        // height while the key row is visible)
        boolean rowVisible = keyRow.getVisibility() == View.VISIBLE;
        controlBar.setPadding(chromeInsets.left, barExtra, chromeInsets.right,
                rowVisible ? 0 : chromeInsets.bottom);
        keyRow.setPadding(chromeInsets.left, 0, chromeInsets.right,
                rowVisible ? chromeInsets.bottom : 0);
    }

    private void updateRowGapPadding() {
        // the remainder the terminal would leave with no extras (TerminalView
        // rows = (height - firstRowOffset) / lineSpacing); adding the current
        // extras back makes this a fixed point across relayouts
        int spacing = ControlBar.lineSpacing(fontSize);
        int avail = terminalWrapper.getHeight() + tabExtra + barExtra
                - ControlBar.firstRowOffset(fontSize);
        if (avail <= 0) {
            return;
        }
        int rem = avail % spacing;
        if (rem / 2 != tabExtra || rem - rem / 2 != barExtra) {
            tabExtra = rem / 2;
            barExtra = rem - rem / 2;
            applyChromePadding();
        }
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
                CmusTheme t = CmusTheme.from(o);
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
                controlBar.onView(v.name());
            }
            case CmusIpc.Filter f -> {
                liveFilter = f.filter();
                applyTabColors();
            }
            case CmusIpc.Status s -> controlBar.onStatus(s);
            case CmusIpc.Position p -> controlBar.onPosition(p.position());
            case CmusIpc.Volume v -> controlBar.onVolume(v.left());
            default -> {
            }
        }
    };

    private void applyTheme() {
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

    private void applyTabColors() {
        if (theme == null) {
            return;
        }
        int fg = theme.winTitleFg();
        int dim = (fg & 0x00FFFFFF) | INACTIVE_TAB_ALPHA;
        for (int i = 0; i < viewTabs.length; i++) {
            viewTabs[i].setTextColor(VIEW_NAMES[i].equals(viewName) ? fg : dim);
        }
        // the search icon doubles as the active-filter indicator (the
        // active-tab convention: full fg = a filter is applied)
        filterBtn.setImageTintList(ColorStateList.valueOf(liveFilter != null ? fg : dim));
        filterClose.setImageTintList(ColorStateList.valueOf(fg));
    }

    // TermService.SessionCallback

    @Override
    public void onTextChanged() {
        terminalView.onScreenUpdated();
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
            closeFilterBox(false); // nothing left to filter either
            joyDot.setVisibility(View.GONE); // it must not eat the tap-out
            Toast.makeText(this, "cmus exited (" + exitStatus + ") — tap to close",
                    Toast.LENGTH_LONG).show();
        }
    }

    // TerminalViewClient

    @Override
    public float onScale(float scale) {
        if (scale < 0.9f || scale > 1.1f) {
            fontSize = Math.max(minFontSize, Math.min(Math.round(fontSize * scale), maxFontSize));
            terminalView.setTextSize(fontSize);
            for (TextView tab : viewTabs) {
                tab.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize);
            }
            filterBox.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize);
            filterBtn.setLayoutParams(topBarButtonParams());
            filterClose.setLayoutParams(topBarButtonParams());
            controlBar.setFontSize(fontSize);
            keyRow.setFontSize(fontSize);
            titleStrip.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ControlBar.firstRowOffset(fontSize)));
            getSharedPreferences(TermService.PREFS, MODE_PRIVATE).edit()
                    .putInt(PREF_FONT, fontSize).apply();
            return 1.0f;
        }
        return scale;
    }

    @Override
    public void onSingleTapUp(MotionEvent e) {
        if (session != null && !session.isRunning()) {
            finish(); // frozen crash screen (back only backgrounds the task)
            return;
        }
        // when cmus has mouse tracking on (TermService forces it), the tap
        // was already sent as a click by TerminalView — same gate as termux
        if (terminalView.mEmulator != null && terminalView.mEmulator.isMouseTrackingActive()) {
            return;
        }
        toggleSoftKeyboard();
    }

    private void toggleSoftKeyboard() {
        terminalView.requestFocus();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        WindowInsets insets = terminalView.getRootWindowInsets();
        if (insets != null && insets.isVisible(WindowInsets.Type.ime())) {
            imm.hideSoftInputFromWindow(terminalView.getWindowToken(), 0);
        } else {
            imm.showSoftInput(terminalView, 0);
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
        hideImeAndFocusTerminal();
    }

    private void hideImeAndFocusTerminal() {
        terminalView.requestFocus();
        getSystemService(InputMethodManager.class)
                .hideSoftInputFromWindow(terminalView.getWindowToken(), 0);
    }

    /** Icon button flanking the tab bar, sized to the tab text band. */
    private ImageButton topBarButton(int icon, Runnable action) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setPadding(dp(4), dp(4), dp(4), dp(4));
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        b.setBackgroundResource(tv.resourceId);
        b.setOnClickListener(v -> action.run());
        b.setLayoutParams(topBarButtonParams());
        return b;
    }

    /** Square ≈ the tab text band (font size + the tabs' padding). */
    private LinearLayout.LayoutParams topBarButtonParams() {
        int side = fontSize + dp(16);
        return new LinearLayout.LayoutParams(side, side);
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
