package net.pgaskin.cmus.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.io.File;
import java.io.IOException;

/**
 * Foreground service owning the cmus pty session so playback continues while
 * the activity is backgrounded or recreated. The service is the session's
 * one stable {@link TerminalSessionClient}; the activity registers a
 * {@link SessionCallback} to receive screen updates while attached.
 */
public class TermService extends Service implements TerminalSessionClient {
    private static final String TAG = "cmus";
    private static final String CHANNEL_ID = "term";
    private static final int NOTIFICATION_ID = 1;

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

    private final IBinder binder = new LocalBinder();
    private TerminalSession session;
    private SessionCallback callback;

    @Override
    public void onCreate() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("running")
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_IMMUTABLE))
                .build();
        startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        getSession();
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
            String[] env = {
                    "HOME=" + filesDir,
                    "TMPDIR=" + getCacheDir(),
                    "TERM=xterm-256color",
                    "TERMINFO=" + new File(filesDir, "terminfo"),
                    "CMUS_HOME=" + new File(filesDir, "cmus-home"),
                    "CMUS_LIB_DIR=" + new File(filesDir, "cmus-lib"),
                    "CMUS_DATA_DIR=" + new File(filesDir, "cmus-data"),
            };
            // 100 transcript rows is the minimum honored (cmus is an
            // altscreen app; the transcript is never scrolled)
            session = new TerminalSession(exe, filesDir.getPath(),
                    new String[]{"cmus"}, env, 100, this);
            forceMouseOption();
        }
        return session;
    }

    /**
     * Touch gestures only behave like termux (tap = click, drag = wheel
     * scroll) with cmus mouse tracking on, so force the option on every
     * start, overriding autosave/rc — mouse is a core part of this app.
     * Pushed over the cmus socket once it comes up, rather than touching
     * the user's config files.
     */
    private void forceMouseOption() {
        String socketPath = new File(getFilesDir(), "cmus-home/socket").getPath();
        Thread thread = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                try (LocalSocket socket = new LocalSocket()) {
                    socket.connect(new LocalSocketAddress(socketPath,
                            LocalSocketAddress.Namespace.FILESYSTEM));
                    socket.getOutputStream().write("set mouse=true\n"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                    // wait for the reply so the write isn't racing the close
                    socket.getInputStream().read();
                    return;
                } catch (IOException e) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
            Log.w(TAG, "cmus socket never came up; mouse option not set");
        }, "CmusMouseOption");
        thread.setDaemon(true);
        thread.start();
    }

    public void setSessionCallback(SessionCallback callback) {
        this.callback = callback;
    }

    @Override
    public void onDestroy() {
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
