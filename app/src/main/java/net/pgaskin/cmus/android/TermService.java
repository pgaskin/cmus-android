package net.pgaskin.cmus.android;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.termux.terminal.TerminalColors;
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
    /** The generated Material You scheme is the active one (stage 16). */
    static final String PREF_MATERIAL = "material_you";

    /** What the attached activity cares about; safe to leave unregistered. */
    public interface SessionCallback {
        void onTextChanged();

        void onSessionFinished();

        /**
         * The app rewrote (or reset) the live terminal palette — the
         * Material You scheme redefines entries in place of anything
         * OSC-4-ish, so cmus never knows: the view needs an invalidate and
         * the chrome a re-resolve through the new palette.
         */
        void onPaletteChanged();
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
    /** Re-poll cadence while a worker job defers an idle-quit. */
    private static final long IDLE_QUIT_JOBS_POLL_MS = 30 * 1000;

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
    // an idle-quit fire asked cmus about worker jobs (android-jobs) and
    // the answer decides: quit, or re-poll while an import runs — quitting
    // mid-import truncates it (Patrick). Any Jobs event while set counts.
    private boolean idleQuitPendingJobs;
    private boolean pendingMediaPlay;
    private boolean materialActive;

    @Override
    public void onCreate() {
        instance = this;
        materialActive = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(PREF_MATERIAL, false);
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
                    "TERMINFO=" + CmusFiles.terminfo(this),
                    "CMUS_HOME=" + CmusFiles.home(this),
                    "CMUS_LIB_DIR=" + CmusFiles.lib(this),
                    "CMUS_DATA_DIR=" + CmusFiles.data(this),
                    // app-facing IPC socket (patches/cmus/0001); beside
                    // home, not in it, so zip exports of the config never
                    // pick up socket files
                    "CMUS_ANDROID_SOCKET=" + CmusFiles.socket(this)));
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
            if (materialActive) {
                // a fresh emulator starts on the stock palette; the
                // autosave-restored color_* indexes need their entries back
                pushPalette();
            }
            ipc = new CmusIpc(CmusFiles.socket(this));
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
            // an attach can resize the pty before cmus installs its WINCH
            // handler (the signal is silently lost and the nudge from
            // onEmulatorSet is dropped pre-connect): re-check once per
            // connect, which is after cmus's init by definition
            ipc.send("android-winch");
            if (materialActive) {
                // material is a direct-set scheme with no theme file behind
                // it: re-force the role indexes so a stale autosave (a
                // force-stop skips every save path) can't leave them
                // pointing elsewhere — same forcing stance as the above
                for (String cmd : MaterialYouTheme.commands()) {
                    ipc.send(cmd);
                }
            }
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
                case CmusIpc.Selected s -> Log.d(TAG, "ipc selected view=" + s.view()
                        + " files=" + s.files() + " playlists=" + s.playlists()
                        + " playlist=" + s.playlist());
                case CmusIpc.Volume v -> Log.d(TAG, "ipc volume " + v.left() + "/" + v.right());
                case CmusIpc.View v -> Log.d(TAG, "ipc view " + v.name());
                case CmusIpc.Filter f -> Log.d(TAG, "ipc filter " + f.filter());
                case CmusIpc.Jobs j -> {
                    Log.d(TAG, "ipc jobs running=" + j.running());
                    if (idleQuitPendingJobs) {
                        // the poll answer (or a diffed transition — either
                        // is authoritative) continues the idle-quit fire
                        idleQuitPendingJobs = false;
                        if (!idleQuitConditionsHold()) {
                            idleQuitArmed = false;
                            return;
                        }
                        if (j.running()) {
                            Log.i(TAG, "idle-quit: import running, delaying");
                            mainHandler.postDelayed(idleQuit, IDLE_QUIT_JOBS_POLL_MS);
                        } else {
                            // pipeline complete; the grace re-check can
                            // re-arm fresh if the quit is dropped
                            idleQuitArmed = false;
                            Log.i(TAG, "idle-quit: quitting cmus");
                            quitCmus();
                        }
                    }
                }
                case CmusIpc.Colorscheme c -> {
                    Log.d(TAG, "ipc colorscheme " + c.name());
                    if (materialActive) {
                        // a sourced theme file replaces the generated
                        // scheme (from either side — the app's selector or
                        // a TUI :colorscheme): back to the stock palette
                        materialActive = false;
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putBoolean(PREF_MATERIAL, false).apply();
                        pushPalette();
                    }
                }
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

    // Material You (stage 16): the app redefines terminal palette entries
    // to exact dynamic-color ARGB and points the color_* roles at them —
    // the service owns the push because it owns the emulator and outlives
    // the activity (a light/dark toggle lands here even headless)

    /**
     * The theme selector's Material You pick: push the entries, then the
     * constant `set` burst pointing every role at them. The burst echoes
     * back as one coalesced options event, so the chrome re-tints through
     * the usual path; leaving the scheme is the Colorscheme echo of
     * whatever replaces it.
     */
    public void applyMaterialYou() {
        materialActive = true;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_MATERIAL, true).apply();
        pushPalette();
        if (ipc != null) {
            for (String cmd : MaterialYouTheme.commands()) {
                ipc.send(cmd);
            }
        }
    }

    /** For the selector highlight. */
    public boolean materialYouActive() {
        return materialActive;
    }

    /**
     * Writes the Material entries into the live emulator palette — exactly
     * what an OSC 4 would do, which is why CmusTheme resolves through the
     * live copy — or resets it to stock when the scheme is switched away.
     * No cmus traffic: the autosaved color_* indexes keep pointing at the
     * entries, only the ARGB behind them moves.
     */
    private void pushPalette() {
        if (session != null && session.getEmulator() != null) {
            TerminalColors colors = session.getEmulator().mColors;
            if (materialActive) {
                int[] argb = MaterialYouTheme.colors(this);
                System.arraycopy(argb, 0, colors.mCurrentColors,
                        MaterialYouTheme.BASE_INDEX, argb.length);
                Log.d(TAG, String.format("material: title=#%06x status=#%06x band=#%06x top=#%06x bg=#%06x",
                        argb[10] & 0xFFFFFF, argb[6] & 0xFFFFFF, argb[9] & 0xFFFFFF,
                        argb[23] & 0xFFFFFF, argb[11] & 0xFFFFFF));
            } else {
                colors.reset();
            }
        }
        if (callback != null) {
            callback.onPaletteChanged();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // light/dark toggles and wallpaper-palette changes land here even
        // with the activity gone; a Material update is palette-push only
        // (stable indexes — cmus never hears about it)
        if (materialActive) {
            pushPalette();
        }
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
        boolean arm = idleQuitConditionsHold();
        if (arm == idleQuitArmed) {
            return;
        }
        idleQuitArmed = arm;
        if (arm) {
            Log.d(TAG, "idle-quit: armed");
            mainHandler.postDelayed(idleQuit, IDLE_QUIT_DELAY_MS);
        } else {
            Log.d(TAG, "idle-quit: cancelled");
            idleQuitPendingJobs = false; // a stale poll answer must not quit
            mainHandler.removeCallbacks(idleQuit);
        }
    }

    // sleep timer (stage 15): app-side countdown, expiry pauses (never
    // stops — resume-friendly, and a no-op unless playing). postDelayed
    // counts uptimeMillis (the idle-quit stance above): while the timer
    // matters something is playing and active audio holds a partial
    // wakelock, so uptime tracks elapsed; if the device dozed, nothing
    // was playing and the late no-op fire is fine. After the expiry
    // pause, a backgrounded app falls into the normal idle-quit path.
    private long sleepDeadline; // elapsedRealtime ms; 0 = off

    private final Runnable sleepFire = () -> {
        sleepDeadline = 0;
        Log.i(TAG, "sleep timer: pausing");
        if (ipc != null) {
            ipc.send("player-pause-playback");
        }
    };

    /** (Re)starts the countdown. */
    public void setSleepTimer(int minutes) {
        mainHandler.removeCallbacks(sleepFire);
        sleepDeadline = SystemClock.elapsedRealtime() + minutes * 60_000L;
        mainHandler.postDelayed(sleepFire, minutes * 60_000L);
        Log.d(TAG, "sleep timer: set " + minutes + "m");
    }

    public void cancelSleepTimer() {
        mainHandler.removeCallbacks(sleepFire);
        sleepDeadline = 0;
        Log.d(TAG, "sleep timer: cancelled");
    }

    /** Remaining ms, floored at 1 while armed; 0 = no timer. */
    public long sleepRemainingMs() {
        return sleepDeadline == 0 ? 0
                : Math.max(1, sleepDeadline - SystemClock.elapsedRealtime());
    }

    // armed stays true through the poll round trip and any import-deferred
    // re-polls — the pipeline is one arming, and only the cancel path (or
    // the quit itself) ends it; otherwise a leftover re-poll could couple
    // with a fresh arming and quit ~30s after backgrounding
    private final Runnable idleQuit = () -> {
        if (!idleQuitConditionsHold()) {
            idleQuitArmed = false;
            return;
        }
        // never quit mid-import (it would truncate the scan): ask cmus
        // about worker jobs first — a fresh authoritative answer at
        // decision time (the poll line is also the main-loop wakeup); the
        // Jobs echo continues below in the listener
        idleQuitPendingJobs = true;
        ipc.send("android-jobs");
    };

    /** The idle-quit preconditions, re-checked at every async step. */
    private boolean idleQuitConditionsHold() {
        CmusIpc.Status status = ipc != null ? ipc.status() : null;
        return status != null && status.state() != CmusIpc.PlayState.PLAYING
                && !activityVisible && session != null && session.isRunning();
    }

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
        idleQuitPendingJobs = false;
        sleepDeadline = 0; // the fire above was just dropped with the rest
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
