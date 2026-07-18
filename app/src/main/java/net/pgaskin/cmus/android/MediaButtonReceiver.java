package net.pgaskin.cmus.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;

/**
 * Resurrects cmus from a play media key after the MediaSession is gone
 * (idle-quit or any other death while backgrounded): the system routes keys
 * to the live session while one exists and falls back to this receiver
 * ({@link android.media.session.MediaSession#setMediaButtonBroadcastReceiver}),
 * which restarts the FGS with {@link TermService#ACTION_MEDIA_PLAY} — forced
 * resume=true makes playback continue from the saved position. A foreground
 * TUI quit flips {@link TermService#PREF_RESURRECT} off, so an
 * explicitly-quit app never comes back from a stray headset reconnect (the
 * system-side registration itself can't be cleared: null NPEs server-side,
 * and older archived sessions' registrations linger anyway).
 */
public class MediaButtonReceiver extends BroadcastReceiver {
    private static final String TAG = "cmus";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            return;
        }
        KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent.class);
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN) {
            return;
        }
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (!context.getSharedPreferences(TermService.PREFS, Context.MODE_PRIVATE)
                        .getBoolean(TermService.PREF_RESURRECT, false)) {
                    Log.d(TAG, "media button ignored, resurrect disabled (foreground quit)");
                    return;
                }
                Log.i(TAG, "media button resurrect: " + event.getKeyCode());
                context.startForegroundService(new Intent(context, TermService.class)
                        .setAction(TermService.ACTION_MEDIA_PLAY));
            }
            default -> {
                // pause/stop/next/prev with no cmus running: nothing to do
            }
        }
    }
}
