package net.pgaskin.cmus.android;

import android.content.Context;
import android.content.res.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * The generated Material You colorscheme. cmus color values are palette
 * indexes, not RGB — so instead of quantizing the dynamic palette into the
 * xterm cube, the app redefines a contiguous run of the terminal's 256-color
 * palette entries to exact dynamic-color ARGB (what an OSC 4 would do) and
 * points every color_* role at its entry with a direct `set` burst. Direct
 * color-setting is reserved for generated schemes like this one; file-based
 * schemes go through the `colorscheme` command (policy per stage 16).
 * <p>
 * The role→entry assignment is stable ({@code BASE_INDEX + i}), so the
 * command burst is a constant: once cmus's autosave carries the indexes, a
 * light/dark toggle or wallpaper change is a palette re-push with no cmus
 * traffic at all — only the ARGB behind the entries moves. TermService owns
 * the push (it owns the emulator); {@link CmusTheme} resolves chrome colors
 * through the same live palette.
 */
final class MaterialYouTheme {
    /** First redefined palette entry (just past the 16 ANSI colors). */
    static final int BASE_INDEX = 16;

    /**
     * Every color role (options.c color_names, all 27) so nothing from a
     * previously applied theme bleeds through. Order defines the palette
     * entry assignment — append-only, the indexes end up in autosave.
     */
    private static final String[] ROLES = {
            "color_cmdline_bg",
            "color_cmdline_fg",
            "color_error",
            "color_info",
            "color_separator",
            "color_statusline_bg",
            "color_statusline_fg",
            "color_statusline_progress_bg",
            "color_statusline_progress_fg",
            "color_titleline_bg",
            "color_titleline_fg",
            "color_win_bg",
            "color_win_cur",
            "color_win_cur_sel_bg",
            "color_win_cur_sel_fg",
            "color_win_dir",
            "color_win_fg",
            "color_win_inactive_cur_sel_bg",
            "color_win_inactive_cur_sel_fg",
            "color_win_inactive_sel_bg",
            "color_win_inactive_sel_fg",
            "color_win_sel_bg",
            "color_win_sel_fg",
            "color_win_title_bg",
            "color_win_title_fg",
            "color_trackwin_album_bg",
            "color_trackwin_album_fg",
    };

    /** The constant `set` burst pointing every role at its entry. */
    static List<String> commands() {
        List<String> commands = new ArrayList<>(ROLES.length);
        for (int i = 0; i < ROLES.length; i++) {
            commands.add("set " + ROLES[i] + "=" + (BASE_INDEX + i));
        }
        return commands;
    }

    /**
     * Exact ARGB per role from the system dynamic ramps, in ROLES order;
     * uiMode picks the variant. M3's fixed error tones stand in for a red
     * the ramps don't have.
     */
    static int[] colors(Context context) {
        boolean night = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return night ? new int[]{
                c(context, android.R.color.system_neutral1_900), // cmdline_bg
                c(context, android.R.color.system_neutral1_100), // cmdline_fg
                0xFFF2B8B5, // error
                c(context, android.R.color.system_accent3_200), // info
                c(context, android.R.color.system_accent3_500), // separator
                c(context, android.R.color.system_neutral1_800), // statusline_bg
                c(context, android.R.color.system_accent1_200), // statusline_fg
                c(context, android.R.color.system_neutral1_700), // statusline_progress_bg
                c(context, android.R.color.system_accent1_100), // statusline_progress_fg
                c(context, android.R.color.system_neutral1_800), // titleline_bg
                c(context, android.R.color.system_accent1_200), // titleline_fg
                c(context, android.R.color.system_neutral1_900), // win_bg
                c(context, android.R.color.system_accent1_300), // win_cur
                c(context, android.R.color.system_accent2_700), // win_cur_sel_bg
                c(context, android.R.color.system_accent1_100), // win_cur_sel_fg
                c(context, android.R.color.system_accent2_200), // win_dir
                c(context, android.R.color.system_neutral1_100), // win_fg
                c(context, android.R.color.system_neutral1_800), // win_inactive_cur_sel_bg
                c(context, android.R.color.system_accent1_200), // win_inactive_cur_sel_fg
                c(context, android.R.color.system_neutral1_800), // win_inactive_sel_bg
                c(context, android.R.color.system_accent2_200), // win_inactive_sel_fg
                c(context, android.R.color.system_accent2_700), // win_sel_bg
                c(context, android.R.color.system_neutral1_50), // win_sel_fg
                c(context, android.R.color.system_neutral1_800), // win_title_bg
                c(context, android.R.color.system_accent1_200), // win_title_fg
                c(context, android.R.color.system_neutral1_900), // trackwin_album_bg
                c(context, android.R.color.system_accent2_200), // trackwin_album_fg
        } : new int[]{
                c(context, android.R.color.system_neutral1_50), // cmdline_bg
                c(context, android.R.color.system_neutral1_900), // cmdline_fg
                0xFFB3261E, // error
                c(context, android.R.color.system_accent3_700), // info
                c(context, android.R.color.system_accent3_400), // separator
                c(context, android.R.color.system_neutral1_100), // statusline_bg
                c(context, android.R.color.system_accent1_700), // statusline_fg
                c(context, android.R.color.system_neutral1_300), // statusline_progress_bg
                c(context, android.R.color.system_accent1_800), // statusline_progress_fg
                c(context, android.R.color.system_neutral1_100), // titleline_bg
                c(context, android.R.color.system_accent1_700), // titleline_fg
                c(context, android.R.color.system_neutral1_50), // win_bg
                c(context, android.R.color.system_accent1_600), // win_cur
                c(context, android.R.color.system_accent2_200), // win_cur_sel_bg
                c(context, android.R.color.system_accent1_800), // win_cur_sel_fg
                c(context, android.R.color.system_accent2_700), // win_dir
                c(context, android.R.color.system_neutral1_900), // win_fg
                c(context, android.R.color.system_neutral1_200), // win_inactive_cur_sel_bg
                c(context, android.R.color.system_accent1_700), // win_inactive_cur_sel_fg
                c(context, android.R.color.system_neutral1_200), // win_inactive_sel_bg
                c(context, android.R.color.system_accent2_700), // win_inactive_sel_fg
                c(context, android.R.color.system_accent2_200), // win_sel_bg
                c(context, android.R.color.system_neutral1_900), // win_sel_fg
                c(context, android.R.color.system_neutral1_100), // win_title_bg
                c(context, android.R.color.system_accent1_700), // win_title_fg
                c(context, android.R.color.system_neutral1_50), // trackwin_album_bg
                c(context, android.R.color.system_accent2_700), // trackwin_album_fg
        };
    }

    private static int c(Context context, int res) {
        return context.getColor(res);
    }

    private MaterialYouTheme() {
    }
}
