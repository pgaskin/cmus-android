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
 * Gated by the debug-settings toggle (stage 18), whose default is on only
 * in debuggable builds — release builds ship with it off; effects show as
 * events in logcat.
 */
public class CmusDebugReceiver extends BroadcastReceiver {
    private static final String TAG = "cmus";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!context.getSharedPreferences(CmusService.PREFS, Context.MODE_PRIVATE)
                .getBoolean(CmusService.PREF_DEBUG_RECEIVER,
                        (context.getApplicationInfo().flags
                                & ApplicationInfo.FLAG_DEBUGGABLE) != 0)) {
            return;
        }
        String cmd = intent.getStringExtra("cmd");
        CmusIpc ipc = CmusService.debugIpc();
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
