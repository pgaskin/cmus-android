package net.pgaskin.cmus.android;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;
import java.util.Map;

/**
 * The app-managed cmus options (stage 18): Patrick's curated settings-screen
 * set, stored in their own prefs file keyed by option name verbatim.
 * <p>
 * The prefs *override* cmus: every key present is re-forced with a `set` on
 * each (re)connect (after autosave has loaded, so it wins), and every Options
 * echo writes the curated keys back — so a TUI `:set` persists across the
 * reinstall/force-stop loss window exactly like an app change, and the
 * settings screen renders purely from echoes (single-source-of-truth rule).
 * A first run has no keys and forces nothing.
 * <p>
 * {@code progress_bar} is the one exception: an app-side setting (the extra
 * "auto" value derives disabled/line from the control bar's visibility —
 * its seek slider replaces cmus's own line) that is *not* synced back, so
 * our own auto-writes can't overwrite the auto pref with a literal value.
 */
final class CmusSettings {
    static final String PREFS = "cmus_opts";

    /** progress_bar's app-side pref (values: auto + the cmus enum). */
    static final String PREF_PROGRESS_BAR = "progress_bar";
    static final String PROGRESS_AUTO = "auto";

    /**
     * The curated managed set — audio + cmus sections of the settings
     * screen. progress_bar is deliberately absent (see above); so is
     * softvol's volume (not an option).
     */
    static final List<String> MANAGED = List.of(
            // audio
            "softvol",
            "dsp.aaudio.performance_mode",
            "dsp.aaudio.allowed_capture",
            "dsp.aaudio.sharing_mode",
            "dsp.aaudio.disable_spatialization",
            "dsp.aaudio.min_buffer_capacity_ms",
            "pause_on_output_change",
            // cmus
            "follow",
            "display_artist_sort_name",
            "ignore_duplicates",
            "lib_sort",
            "pl_sort",
            "replaygain",
            "replaygain_limit",
            "replaygain_preamp",
            "show_hidden",
            "show_all_tracks",
            "show_current_bitrate",
            "show_playback_position",
            "show_remaining_time",
            "skip_track_info",
            "start_view",
            "tree_width_percent",
            "tree_width_max");

    private CmusSettings() {}

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * The connect-time force: `set k=v` for every stored key, then the
     * (derived) progress_bar. Values are exactly what a previous echo (or
     * the settings screen) stored, so cmus re-parses its own getter output.
     */
    static void forceAll(Context context, CmusIpc ipc) {
        SharedPreferences prefs = prefs(context);
        for (String key : MANAGED) {
            String value = prefs.getString(key, null);
            if (value != null) {
                ipc.send("set " + key + "=" + value);
            }
        }
        ipc.send("set progress_bar=" + effectiveProgressBar(context));
    }

    /**
     * Echo → prefs. Idempotent (no write when unchanged), so the echo of
     * our own force/set never loops.
     */
    static void syncBack(Context context, Map<String, String> options) {
        SharedPreferences prefs = prefs(context);
        SharedPreferences.Editor edit = null;
        for (String key : MANAGED) {
            String value = options.get(key);
            if (value != null && !value.equals(prefs.getString(key, null))) {
                if (edit == null) {
                    edit = prefs.edit();
                }
                edit.putString(key, value);
            }
        }
        if (edit != null) {
            edit.apply();
        }
    }

    /**
     * The progress_bar value to send: auto (the default) hides cmus's line
     * while the app's control bar (with its own seek slider) is visible and
     * shows `line` when it isn't; explicit picks pass through.
     */
    static String effectiveProgressBar(Context context) {
        String pref = prefs(context).getString(PREF_PROGRESS_BAR, PROGRESS_AUTO);
        if (!PROGRESS_AUTO.equals(pref)) {
            return pref;
        }
        boolean controlBar = context.getSharedPreferences(TermService.PREFS, Context.MODE_PRIVATE)
                .getBoolean(TermService.PREF_SHOW_CONTROL_BAR, true);
        return controlBar ? "disabled" : "line";
    }
}
