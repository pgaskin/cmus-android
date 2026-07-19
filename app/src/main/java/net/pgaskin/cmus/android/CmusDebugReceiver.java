package net.pgaskin.cmus.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.util.Log;

/**
 * Debug tool: forwards a command string through the Java IPC write path so
 * adb (root) can drive it:
 *
 *   am broadcast -n net.pgaskin.cmus.android/.CmusDebugReceiver \
 *       -e cmd player-pause
 *
 * No-op unless the build is debuggable or the debug-settings toggle is on
 * (stage 18); effects show as events in logcat.
 */
public class CmusDebugReceiver extends BroadcastReceiver {
    private static final String TAG = "cmus";

    @Override
    public void onReceive(Context context, Intent intent) {
        if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0
                && !context.getSharedPreferences(TermService.PREFS, Context.MODE_PRIVATE)
                        .getBoolean(TermService.PREF_DEBUG_RECEIVER, false)) {
            return;
        }
        String cmd = intent.getStringExtra("cmd");
        CmusIpc ipc = TermService.debugIpc();
        if (cmd == null || ipc == null) {
            Log.w(TAG, "debug command ignored (cmd=" + cmd + ", service running=" + (ipc != null) + ")");
            return;
        }
        try {
            ipc.send(cmd);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "debug command rejected: " + e.getMessage());
        }
    }
}
