package net.pgaskin.cmus.android;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

public class MainActivity extends Activity implements TerminalViewClient, TermService.SessionCallback {
    private static final String TAG = "cmus";

    /** Tab order = the 1-7 keys; names are what the `view` command takes. */
    private static final String[] VIEW_NAMES = {
            "tree", "sorted", "playlist", "queue", "browser", "filters", "settings"};
    /** Inactive tab text: win_title_fg at ~55% alpha, blending toward bg. */
    private static final int INACTIVE_TAB_ALPHA = 0x8C000000;

    private TerminalView terminalView;
    private FrameLayout terminalWrapper;
    private View titleStrip;
    private HorizontalScrollView tabBar;
    private ControlBar controlBar;
    private KeyRow keyRow;
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

        fontSize = dp(13);
        minFontSize = dp(5);
        maxFontSize = dp(36);

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

        // the same injection termux's extra-keys row uses: onKeyDown emits
        // the key's escape sequence and merges readShift/Ctrl/AltKey into
        // the meta state, so sticky modifiers apply here too
        keyRow = new KeyRow(this, keyCode -> terminalView.onKeyDown(keyCode,
                new KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, 0)));
        keyRow.setFontSize(fontSize);
        keyRow.setVisibility(View.GONE); // until the IME shows

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(tabBar, new LinearLayout.LayoutParams(
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
            // the key row exists exactly while the IME does, sitting
            // directly atop it below the control bar
            boolean ime = insets.isVisible(WindowInsets.Type.ime());
            if (ime != (keyRow.getVisibility() == View.VISIBLE)) {
                keyRow.setVisibility(ime ? View.VISIBLE : View.GONE);
                if (!ime) {
                    keyRow.clearModifiers();
                }
            }
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
    private void applyChromePadding() {
        tabBar.setPadding(chromeInsets.left, chromeInsets.top, chromeInsets.right, tabExtra);
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
            // cmus is the single source of truth for the active view: taps
            // don't move the highlight until the event comes back, and
            // TUI-side 1-7 presses/resume land here the same way
            case CmusIpc.View v -> {
                viewName = v.name();
                applyTabColors();
                controlBar.onView(v.name());
            }
            case CmusIpc.Status s -> controlBar.onStatus(s);
            case CmusIpc.Position p -> controlBar.onPosition(p.position());
            case CmusIpc.Volume v -> controlBar.onVolume(v.left());
            default -> {
            }
        }
    };

    private void applyTheme() {
        tabBar.setBackgroundColor(theme.winTitleBg());
        titleStrip.setBackgroundColor(theme.winTitleBg());
        terminalWrapper.setBackgroundColor(theme.winBg());
        // cmdline_bg so the bar blends with the TUI's bottom row (default =
        // terminal bg in every bundled theme)
        controlBar.applyTheme(theme.cmdlineBg(), theme.statuslineFg());
        keyRow.applyTheme(theme.cmdlineBg(), theme.statuslineFg());
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
        for (int i = 0; i < viewTabs.length; i++) {
            boolean active = VIEW_NAMES[i].equals(viewName);
            viewTabs[i].setTextColor(active ? theme.winTitleFg()
                    : (theme.winTitleFg() & 0x00FFFFFF) | INACTIVE_TAB_ALPHA);
        }
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
            controlBar.setFontSize(fontSize);
            keyRow.setFontSize(fontSize);
            titleStrip.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ControlBar.firstRowOffset(fontSize)));
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
        // in mouse mode taps are clicks, so the keyboard needs another way
        // in; interim until the stage-12 control bar gets a toggle (text
        // selection is disabled in stage 14 anyway)
        if (terminalView.mEmulator != null && terminalView.mEmulator.isMouseTrackingActive()) {
            toggleSoftKeyboard();
            return true;
        }
        return false;
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
