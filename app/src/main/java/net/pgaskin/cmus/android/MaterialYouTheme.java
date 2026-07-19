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
     * uiMode picks the variant. The role structure mirrors gruvbox-warm
     * (Patrick's reference): a hard-contrast window/cmdline bg (darker than
     * the ramp's end), one band tone shared by both title lines with one
     * accent for both their fgs, the statusline a shade darker with a
     * *different* accent that also colors win_cur (its progress band is the
     * lighter band tone), selections on the band tones (inactive = the
     * darker one), and a plain grey separator. M3's fixed error tones stand
     * in for a red the ramps don't have.
     */
    static int[] colors(Context context) {
        boolean night = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        // the control-color rotation is a setting (stage 18); 180 = the
        // original complement
        int rotation = context.getSharedPreferences(TermService.PREFS, Context.MODE_PRIVATE)
                .getInt(TermService.PREF_HUE_ROTATION, 180);
        if (night) {
            int bg = mix(c(context, android.R.color.system_neutral1_900), 0xFF000000);
            int band = c(context, android.R.color.system_neutral1_800); // bottom title, active sel
            int bandDark = mix(band, c(context, android.R.color.system_neutral1_900));
            // the top band reads as a scrim over the status bar: darker
            // than the bottom bands, pulled toward the window bg (Patrick)
            int topBand = mix(band, bg);
            // darker and more saturated than the pastel ramp end: the title
            // fgs must read apart from the near-white list text (Patrick)
            int title = c(context, android.R.color.system_accent1_400); // both title fgs
            // the ramps' accent2/3 stay in accent1's warm neighborhood by
            // design, so a "different hue" (Patrick) can't come from them:
            // complement the ramp tone instead — gruvbox-warm's orange
            // titles vs teal statusline, wallpaper-derived. Scoped to the
            // status/cmdline band (and the control bar riding it); the
            // list's playing-track colors stay in the ramp family
            int status = rotate(c(context, android.R.color.system_accent3_200), rotation);
            int cur = c(context, android.R.color.system_accent3_300); // playing track in the lists — a saturated step
            // dimmer than the sel/cur fgs but on the same warm ramp, not
            // grey: the unselected list rows sit below the highlights in
            // brightness only (Patrick)
            int fg = mix(c(context, android.R.color.system_accent1_200),
                    c(context, android.R.color.system_neutral1_200)); // half-desaturated
            return new int[]{
                    bg, // cmdline_bg
                    status, // cmdline_fg — the bottom line reads in the
                            // status accent (Patrick), like the bar over it
                    0xFFF2B8B5, // error
                    c(context, android.R.color.system_accent2_200), // info
                    c(context, android.R.color.system_neutral2_400), // separator
                    bandDark, // statusline_bg
                    status, // statusline_fg
                    band, // statusline_progress_bg
                    status, // statusline_progress_fg
                    band, // titleline_bg
                    title, // titleline_fg
                    bg, // win_bg
                    cur, // win_cur
                    band, // win_cur_sel_bg
                    c(context, android.R.color.system_accent3_100), // win_cur_sel_fg
                    c(context, android.R.color.system_accent2_200), // win_dir
                    fg, // win_fg
                    topBand, // win_inactive_cur_sel_bg — a clear step
                             // below the active pane's highlight (Patrick)
                    c(context, android.R.color.system_accent3_100), // win_inactive_cur_sel_fg
                    topBand, // win_inactive_sel_bg
                    c(context, android.R.color.system_accent1_100), // win_inactive_sel_fg
                    band, // win_sel_bg
                    c(context, android.R.color.system_accent1_100), // win_sel_fg — below the
                            // playing track's brightness (Patrick)
                    topBand, // win_title_bg
                    title, // win_title_fg
                    bg, // trackwin_album_bg
                    title, // trackwin_album_fg
            };
        }
        int bg = mix(c(context, android.R.color.system_neutral1_50), 0xFFFFFFFF);
        int band = c(context, android.R.color.system_neutral1_100);
        int bandDark = mix(band, c(context, android.R.color.system_neutral1_200));
        int topBand = mix(band, bg); // the light mirror: lighter, toward bg
        int title = c(context, android.R.color.system_accent1_700); // reads apart from the near-black list text
        int status = rotate(c(context, android.R.color.system_accent3_700), rotation);
        int cur = c(context, android.R.color.system_accent3_600);
        int fg = mix(c(context, android.R.color.system_accent1_600),
                c(context, android.R.color.system_neutral1_600)); // dimmer + half-desaturated, same ramp
        return new int[]{
                bg, // cmdline_bg
                status, // cmdline_fg — the status accent, like the bar over it
                0xFFB3261E, // error
                c(context, android.R.color.system_accent2_700), // info
                c(context, android.R.color.system_neutral2_500), // separator
                bandDark, // statusline_bg
                status, // statusline_fg
                band, // statusline_progress_bg
                status, // statusline_progress_fg
                band, // titleline_bg
                title, // titleline_fg
                bg, // win_bg
                cur, // win_cur
                band, // win_cur_sel_bg
                c(context, android.R.color.system_accent3_800), // win_cur_sel_fg
                c(context, android.R.color.system_accent2_700), // win_dir
                fg, // win_fg
                topBand, // win_inactive_cur_sel_bg
                c(context, android.R.color.system_accent3_800), // win_inactive_cur_sel_fg
                topBand, // win_inactive_sel_bg
                c(context, android.R.color.system_accent1_700), // win_inactive_sel_fg
                band, // win_sel_bg
                c(context, android.R.color.system_accent1_700), // win_sel_fg
                topBand, // win_title_bg
                title, // win_title_fg
                bg, // trackwin_album_bg
                title, // trackwin_album_fg
        };
    }

    private static int c(Context context, int res) {
        return context.getColor(res);
    }

    /**
     * Hue rotated, tone kept — a same-brightness genuinely-other hue (the
     * ramps never leave the seed's neighborhood, so this is synthesized).
     * 180° is the original complement; the degrees are a setting now.
     */
    private static int rotate(int argb, int degrees) {
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(argb, hsv);
        hsv[0] = (((hsv[0] + degrees) % 360f) + 360f) % 360f;
        return android.graphics.Color.HSVToColor(hsv);
    }

    /** 50/50 per-channel blend — half-steps the 100-tone ramps can't express. */
    private static int mix(int a, int b) {
        return 0xFF000000
                | (((a >> 16 & 0xFF) + (b >> 16 & 0xFF)) / 2) << 16
                | (((a >> 8 & 0xFF) + (b >> 8 & 0xFF)) / 2) << 8
                | ((a & 0xFF) + (b & 0xFF)) / 2;
    }

    private MaterialYouTheme() {
    }
}
