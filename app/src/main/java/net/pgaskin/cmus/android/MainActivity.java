package net.pgaskin.cmus.android;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;

import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

public class MainActivity extends Activity implements TerminalViewClient, TermService.SessionCallback {
    private static final String TAG = "cmus";

    private TerminalView terminalView;
    private TermService service;
    private boolean bound;
    private int fontSize;
    private int minFontSize;
    private int maxFontSize;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, android.os.IBinder binder) {
            service = ((TermService.LocalBinder) binder).getService();
            service.setSessionCallback(MainActivity.this);
            TerminalSession session = service.getSession();
            terminalView.attachSession(session);
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

        // TerminalView sizes itself from raw view bounds (ignores its own
        // padding), so insets pad a wrapper instead
        FrameLayout root = new FrameLayout(this);
        root.addView(terminalView);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets in = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            v.setPadding(in.left, in.top, in.right, in.bottom);
            return WindowInsets.CONSUMED;
        });
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
    protected void onDestroy() {
        super.onDestroy();
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

    // TermService.SessionCallback

    @Override
    public void onTextChanged() {
        terminalView.onScreenUpdated();
    }

    @Override
    public void onSessionFinished() {
        finish();
    }

    // TerminalViewClient

    @Override
    public float onScale(float scale) {
        if (scale < 0.9f || scale > 1.1f) {
            fontSize = Math.max(minFontSize, Math.min(Math.round(fontSize * scale), maxFontSize));
            terminalView.setTextSize(fontSize);
            return 1.0f;
        }
        return scale;
    }

    @Override
    public void onSingleTapUp(MotionEvent e) {
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
        return false;
    }

    @Override
    public boolean readAltKey() {
        return false;
    }

    @Override
    public boolean readShiftKey() {
        return false;
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
