package net.pgaskin.cmus.android;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Foreground service owning the cmus pty session so playback continues while
 * the activity is backgrounded or recreated. The service is the session's
 * one stable {@link TerminalSessionClient}; the activity registers a
 * {@link SessionCallback} to receive screen updates while attached.
 */
public class TermService extends Service implements TerminalSessionClient {
    private static final String TAG = "cmus";
    static final String CHANNEL_ID = "term";
    static final int NOTIFICATION_ID = 1;
    /** From {@link MediaButtonReceiver}: respawn and continue playback. */
    static final String ACTION_MEDIA_PLAY = "net.pgaskin.cmus.android.MEDIA_PLAY";
    static final String PREFS = "term";
    private static final String PREF_COLS = "cols";
    private static final String PREF_ROWS = "rows";
    /**
     * Gates {@link MediaButtonReceiver}: true from every spawn, false after
     * a foreground TUI quit. Deliberately our own flag — clearing the
     * session's media-button receiver on release doesn't reliably clear the
     * system's per-package fallback (older archived sessions' registrations
     * linger), so the receiver checks this instead.
     */
    static final String PREF_RESURRECT = "resurrect";

    /** What the attached activity cares about; safe to leave unregistered. */
    public interface SessionCallback {
        void onTextChanged();

        void onSessionFinished();
    }

    public final class LocalBinder extends Binder {
        public TermService getService() {
            return TermService.this;
        }
    }

    // stage-17 setting replaces the constant; postDelayed counts
    // uptimeMillis, so deep doze stretches the delay — accepted (hygiene
    // feature, fires on next wake, no AlarmManager machinery)
    private static final long IDLE_QUIT_DELAY_MS = 15 * 60 * 1000;
    private static final long IDLE_QUIT_GRACE_MS = 30 * 1000;

    private static TermService instance; // for CmusDebugReceiver only

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TerminalSession session;
    private CmusIpc ipc;
    private MediaControl mediaControl;
    private SessionCallback callback;
    private String plEnvVars;
    private boolean activityVisible = true; // the activity starts the service
    private boolean idleQuitArmed;
    private boolean pendingMediaPlay;

    @Override
    public void onCreate() {
        instance = this;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean spawning = session == null;
        getSession(); // creates mediaControl on first start
        startForeground(NOTIFICATION_ID, mediaControl.buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        // media-key resurrection: continue playback once the respawned cmus
        // reports its resumed state (only for a fresh spawn — a live session
        // gets its keys through the MediaSession, never this path). This
        // start is headless, so don't presume an activity: without this the
        // idle-quit timer could never arm and a paused resurrected cmus
        // would hold the FGS forever
        if (spawning && ACTION_MEDIA_PLAY.equals(intent.getAction())) {
            pendingMediaPlay = true;
            activityVisible = false;
        }
        return START_NOT_STICKY;
    }

    /** The live session, spawning cmus on first use. */
    public TerminalSession getSession() {
        if (session == null) {
            try {
                CmusFiles.prepare(this);
            } catch (IOException e) {
                throw new IllegalStateException("cmus runtime layout failed", e);
            }
            File filesDir = getFilesDir();
            String exe = new File(getApplicationInfo().nativeLibraryDir, "libcmus.so").getPath();
            List<String> env = new ArrayList<>(List.of(
                    "HOME=" + filesDir,
                    "TMPDIR=" + getCacheDir(),
                    "TERM=xterm-256color",
                    "TERMINFO=" + new File(filesDir, "terminfo"),
                    "CMUS_HOME=" + new File(filesDir, "cmus-home"),
                    "CMUS_LIB_DIR=" + new File(filesDir, "cmus-lib"),
                    "CMUS_DATA_DIR=" + new File(filesDir, "cmus-data"),
                    // app-facing IPC socket (patches/cmus/0001); filesDir
                    // root, not cmus-home, so tar exports of the config
                    // never pick up socket files. Java client is stage 8.
                    "CMUS_ANDROID_SOCKET=" + new File(filesDir, "cmus-android.sock")));
            // base-path vars for pl_env_vars (saved library/playlist/cache
            // paths keep the var, not the base, so libraries survive
            // reinstalls/storage moves). Order matters twice over: pl_env
            // takes the first var whose value prefixes the path, so more
            // specific bases go first, and the names are baked into saved
            // files forever.
            List<String> plEnv = new ArrayList<>();
            File extFiles = getExternalFilesDir(null);
            if (extFiles != null) { // null only when shared storage is unavailable
                env.add("CMUS_ANDROID_EXT_FILES=" + extFiles);
                plEnv.add("CMUS_ANDROID_EXT_FILES");
            }
            env.add("CMUS_ANDROID_EXT=" + Environment.getExternalStorageDirectory());
            plEnv.add("CMUS_ANDROID_EXT");
            env.add("CMUS_ANDROID_FILES=" + filesDir);
            plEnv.add("CMUS_ANDROID_FILES");
            plEnvVars = String.join(",", plEnv);
            // 100 transcript rows is the minimum honored (cmus is an
            // altscreen app; the transcript is never scrolled)
            session = new TerminalSession(exe, filesDir.getPath(),
                    new String[]{"cmus"}, env.toArray(new String[0]), 100, this);
            // the constructor doesn't fork — TerminalSession only spawns in
            // initializeEmulator, normally reached when a TerminalView
            // attaches and sizes it. Spawn headlessly now (media-key
            // resurrection has no activity) at the last attached size so a
            // later reopen doesn't shift layout; an attaching view merely
            // resizes the pty and cmus redraws on the WINCH
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            session.updateSize(prefs.getInt(PREF_COLS, 80), prefs.getInt(PREF_ROWS, 24), 8, 16);
            prefs.edit().putBoolean(PREF_RESURRECT, true).apply();
            ipc = new CmusIpc(new File(filesDir, "cmus-android.sock"));
            ipc.addListener(ipcListener);
            mediaControl = new MediaControl(this, ipc);
            ipc.addListener(mediaControl);
        }
        return session;
    }

    /** The IPC client; non-null once {@link #getSession} has spawned cmus. */
    public CmusIpc getIpc() {
        return ipc;
    }

    /** For {@link CmusDebugReceiver}; null unless the service is running. */
    static CmusIpc debugIpc() {
        TermService service = instance;
        return service != null ? service.ipc : null;
    }

    /**
     * Options core wrapper functionality depends on are forced on every
     * (re)connect, overriding autosave/rc (policy in architecture.md: the
     * command channel leaves user config files alone; autosave persisting
     * the forced value is the point). mouse: touch gestures only behave
     * like termux (tap = click, drag = wheel scroll) with mouse tracking
     * on. resume: every quit — user, idle-quit, task-swipe, even SIGHUP
     * from app-process death — writes track/position/playback state/view
     * for the next launch to restore. pl_env_vars: saved paths keep the
     * env var, not the base path (see the spawn env). Each command echoes
     * back as an options event, self-verifying.
     */
    private final CmusIpc.Listener ipcListener = new CmusIpc.Listener() {
        @Override
        public void onConnected() {
            Log.d(TAG, "ipc: connected");
            ipc.send("set mouse=true");
            ipc.send("set resume=true");
            ipc.send("set pl_env_vars=" + plEnvVars);
        }

        @Override
        public void onDisconnected() {
            Log.d(TAG, "ipc: disconnected");
        }

        @Override
        public void onEvent(CmusIpc.Event event) {
            switch (event) {
                case CmusIpc.Hello h -> Log.d(TAG, "ipc hello version=" + h.version());
                case CmusIpc.Status s -> {
                    Log.d(TAG, "ipc status " + s.state()
                            + " pos=" + s.position() + "/" + s.duration()
                            + " file=" + s.file() + " tags=" + s.tags());
                    updateIdleQuit(); // ipc.status() already caches s
                    if (pendingMediaPlay) {
                        pendingMediaPlay = false;
                        switch (s.state()) {
                            // resume leaves the saved track paused at
                            // position, so toggle; player-play would
                            // restart it from the beginning
                            case PAUSED -> ipc.send("player-pause");
                            case STOPPED -> ipc.send("player-play");
                            case PLAYING -> {
                            }
                        }
                    }
                }
                case CmusIpc.Position p -> Log.d(TAG, "ipc position " + p.position());
                case CmusIpc.Volume v -> Log.d(TAG, "ipc volume " + v.left() + "/" + v.right());
                case CmusIpc.View v -> Log.d(TAG, "ipc view " + v.name());
                case CmusIpc.Options o -> Log.d(TAG, "ipc options n=" + o.values().size()
                        + " mouse=" + o.values().get("mouse")
                        + " softvol=" + o.values().get("softvol")
                        + " color_win_cur=" + o.values().get("color_win_cur")
                        + " color_titleline_bg=" + o.values().get("color_titleline_bg"));
            }
        }
    };

    public void setSessionCallback(SessionCallback callback) {
        this.callback = callback;
    }

    /** Reported from the activity's onStart/onStop for the idle-quit timer. */
    public void setActivityVisible(boolean visible) {
        activityVisible = visible;
        if (!visible && session != null && session.getEmulator() != null) {
            // remember the attached size for the next headless spawn
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt(PREF_COLS, session.getEmulator().mColumns)
                    .putInt(PREF_ROWS, session.getEmulator().mRows)
                    .apply();
        }
        updateIdleQuit();
    }

    /**
     * Arms the idle-quit timer when cmus is not playing and no activity is
     * visible, cancels it when either flips back (Status events and the
     * visibility setter both land here). PAUSED counts as idle only because
     * resume=true is forced — quitting loses nothing. A missing Status
     * snapshot is not idle: never quit on state we never saw.
     */
    private void updateIdleQuit() {
        CmusIpc.Status status = ipc != null ? ipc.status() : null;
        boolean arm = status != null && status.state() != CmusIpc.PlayState.PLAYING
                && !activityVisible && session != null && session.isRunning();
        if (arm == idleQuitArmed) {
            return;
        }
        idleQuitArmed = arm;
        if (arm) {
            Log.d(TAG, "idle-quit: armed");
            mainHandler.postDelayed(idleQuit, IDLE_QUIT_DELAY_MS);
        } else {
            Log.d(TAG, "idle-quit: cancelled");
            mainHandler.removeCallbacks(idleQuit);
        }
    }

    private final Runnable idleQuit = () -> {
        idleQuitArmed = false;
        CmusIpc.Status status = ipc != null ? ipc.status() : null;
        if (status == null || status.state() == CmusIpc.PlayState.PLAYING
                || activityVisible || session == null || !session.isRunning()) {
            return;
        }
        Log.i(TAG, "idle-quit: quitting cmus");
        quitCmus();
    };

    /**
     * Lossless quit: re-force resume in case the user disabled it
     * mid-session (the connect-time force only covers the last connect).
     * Never SIGKILLs a wedged cmus — that's exactly what loses state — so
     * if the session somehow survives (send dropped while disconnected),
     * a grace recheck re-evaluates and the timer re-arms.
     */
    private void quitCmus() {
        ipc.send("set resume=true");
        ipc.send("quit");
        mainHandler.postDelayed(this::updateIdleQuit, IDLE_QUIT_GRACE_MS);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // swiping the task away: playback outliving the task is the FGS's
        // whole point, but an idle app the user closed should die now
        // (lossless via resume) instead of waiting out the idle timer
        if (session == null || !session.isRunning()) {
            stopSelf();
            return;
        }
        CmusIpc.Status status = ipc.status();
        if (status != null && status.state() == CmusIpc.PlayState.PLAYING) {
            return;
        }
        Log.i(TAG, "task removed while idle: quitting cmus");
        quitCmus();
    }

    @Override
    public void onDestroy() {
        instance = null;
        mainHandler.removeCallbacksAndMessages(null);
        if (mediaControl != null) {
            mediaControl.close();
        }
        if (ipc != null) {
            ipc.close();
        }
        if (session != null) {
            session.finishIfRunning();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // TerminalSessionClient

    @Override
    public void onTextChanged(TerminalSession changedSession) {
        if (callback != null) {
            callback.onTextChanged();
        }
    }

    @Override
    public void onSessionFinished(TerminalSession finishedSession) {
        mainHandler.removeCallbacksAndMessages(null);
        idleQuitArmed = false;
        pendingMediaPlay = false;
        // a foreground quit is the user closing the app for real — gate the
        // media-key resurrection off (background deaths keep it); before the
        // teardown below so nothing can interrupt it
        if (activityVisible) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(PREF_RESURRECT, false).apply();
        }
        if (mediaControl != null) {
            mediaControl.close(); // session released before the FGS stops
            mediaControl = null;
        }
        if (ipc != null) {
            ipc.close(); // before the socket path goes stale; stops reconnects
            ipc = null;
        }
        // dropping the session lets getSession() respawn cmus: after an
        // idle-quit (or any death while backgrounded) the activity stays in
        // recents and its next onStart restarts the service; resume=true
        // makes the round trip invisible
        session = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        if (callback != null) {
            callback.onSessionFinished();
        }
    }

    @Override
    public void onCopyTextToClipboard(TerminalSession session, String text) {
        getSystemService(ClipboardManager.class)
                .setPrimaryClip(ClipData.newPlainText(null, text));
    }

    @Override
    public void onPasteTextFromClipboard(TerminalSession session) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData clip = clipboard.getPrimaryClip();
        if (clip != null && clip.getItemCount() > 0) {
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            if (text != null && text.length() > 0 && session.getEmulator() != null) {
                session.getEmulator().paste(text.toString());
            }
        }
    }

    @Override
    public void onTitleChanged(TerminalSession changedSession) {
    }

    @Override
    public void onBell(TerminalSession session) {
    }

    @Override
    public void onColorsChanged(TerminalSession session) {
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {
    }

    @Override
    public Integer getTerminalCursorStyle() {
        return null; // default (block)
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
