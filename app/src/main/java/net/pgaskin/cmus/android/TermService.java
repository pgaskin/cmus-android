package net.pgaskin.cmus.android;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Environment;
import android.os.IBinder;
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

    private static TermService instance; // for CmusDebugReceiver only

    private final IBinder binder = new LocalBinder();
    private TerminalSession session;
    private CmusIpc ipc;
    private MediaControl mediaControl;
    private SessionCallback callback;
    private String plEnvVars;

    @Override
    public void onCreate() {
        instance = this;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        getSession(); // creates mediaControl on first start
        startForeground(NOTIFICATION_ID, mediaControl.buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
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
                case CmusIpc.Status s -> Log.d(TAG, "ipc status " + s.state()
                        + " pos=" + s.position() + "/" + s.duration()
                        + " file=" + s.file() + " tags=" + s.tags());
                case CmusIpc.Position p -> Log.d(TAG, "ipc position " + p.position());
                case CmusIpc.Volume v -> Log.d(TAG, "ipc volume " + v.left() + "/" + v.right());
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

    @Override
    public void onDestroy() {
        instance = null;
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
        if (mediaControl != null) {
            mediaControl.close(); // session released before the FGS stops
        }
        if (ipc != null) {
            ipc.close(); // before the socket path goes stale; stops reconnects
        }
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
