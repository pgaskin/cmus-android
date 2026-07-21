package net.pgaskin.cmus.android;

import android.content.Context;
import android.content.res.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.ToIntFunction;

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
 * traffic at all — only the ARGB behind the entries moves. CmusService owns
 * the push (it owns the emulator); {@link CmusTheme} resolves chrome colors
 * through the same live palette.
 */
final class MaterialYouTheme {
    /** First redefined palette entry (just past the 16 ANSI colors). */
    static final int BASE_INDEX = 16;

    /**
     * The tones the roles draw from, computed once per variant. Roles map to
     * these (see {@link Role}); the variants ({@link #nightTones}/
     * {@link #lightTones}) differ only in the values here, never in which
     * role gets which tone. The structure mirrors gruvbox-warm (Patrick's
     * reference): a hard-contrast window/cmdline bg (darker than the ramp's
     * end), one band tone shared by both title lines with one accent for both
     * their fgs, the statusline a shade darker with a *different* accent that
     * also colors win_cur (its progress band is the lighter band tone),
     * selections on the band tones (inactive = the darker one), and a plain
     * grey separator. M3's fixed error tones stand in for a red the ramps
     * don't have.
     */
    private static final class Tones {
        int bg;        // window + cmdline + album bg (hard contrast)
        int band;      // title lines, active selections, progress band
        int bandDark;  // statusline bg, a shade under the band
        int topBand;   // scrim over the status bar / inactive selections
        int title;     // both title-line fgs
        int status;    // statusline/cmdline fg — the complemented accent
        int cur;       // playing track in the lists
        int fg;        // unselected list rows
        int error;     // M3 fixed error tone (the ramps have no red)
        int info;      // statusline info accent
        int separator; // plain grey rule
        int dir;       // browser directory rows
        int selFg;     // win_sel_fg / win_inactive_sel_fg
        int curSelFg;  // win_cur_sel_fg / win_inactive_cur_sel_fg
    }

    /**
     * Every color role (options.c color_names, all 27) with the dynamic tone
     * it draws from. Enum order defines the palette-entry assignment
     * ({@code BASE_INDEX + ordinal}) — append-only, since the indexes end up
     * in cmus autosave. Adding a role is a single edit here: {@link
     * #commands()} and {@link #colors(Context)} both iterate this, so they
     * can't drift and no hand-aligned array can fall out of role order.
     * <p>
     * The cmus option name is the constant's name lowercased (see {@link
     * #option()}), so the names are the wire contract — renaming one changes
     * what's `set` on cmus; they mirror cmus's own color_* names by design.
     */
    private enum Role {
        CMDLINE_BG(t -> t.bg),
        // the bottom line reads in the status accent (Patrick), like the bar over it
        CMDLINE_FG(t -> t.status),
        ERROR(t -> t.error),
        INFO(t -> t.info),
        SEPARATOR(t -> t.separator),
        STATUSLINE_BG(t -> t.bandDark),
        STATUSLINE_FG(t -> t.status),
        STATUSLINE_PROGRESS_BG(t -> t.band),
        STATUSLINE_PROGRESS_FG(t -> t.status),
        TITLELINE_BG(t -> t.band),
        TITLELINE_FG(t -> t.title),
        WIN_BG(t -> t.bg),
        WIN_CUR(t -> t.cur),
        WIN_CUR_SEL_BG(t -> t.band),
        WIN_CUR_SEL_FG(t -> t.curSelFg),
        WIN_DIR(t -> t.dir),
        WIN_FG(t -> t.fg),
        // a clear step below the active pane's highlight (Patrick)
        WIN_INACTIVE_CUR_SEL_BG(t -> t.topBand),
        WIN_INACTIVE_CUR_SEL_FG(t -> t.curSelFg),
        WIN_INACTIVE_SEL_BG(t -> t.topBand),
        WIN_INACTIVE_SEL_FG(t -> t.selFg),
        WIN_SEL_BG(t -> t.band),
        // below the playing track's brightness (Patrick)
        WIN_SEL_FG(t -> t.selFg),
        WIN_TITLE_BG(t -> t.topBand),
        WIN_TITLE_FG(t -> t.title),
        TRACKWIN_ALBUM_BG(t -> t.bg),
        TRACKWIN_ALBUM_FG(t -> t.title);

        final ToIntFunction<Tones> tone;

        Role(ToIntFunction<Tones> tone) {
            this.tone = tone;
        }

        /** cmus option name: the constant name lowercased (WIN_TITLE_FG → color_win_title_fg). */
        String option() {
            return "color_" + name().toLowerCase(Locale.ROOT);
        }
    }

    /** The constant `set` burst pointing every role at its entry. */
    static List<String> commands() {
        Role[] roles = Role.values();
        List<String> commands = new ArrayList<>(roles.length);
        for (int i = 0; i < roles.length; i++) {
            commands.add("set " + roles[i].option() + "=" + (BASE_INDEX + i));
        }
        return commands;
    }

    /**
     * Exact ARGB per role from the system dynamic ramps, in {@link Role}
     * order (so entry {@code BASE_INDEX + i} gets {@code colors()[i]});
     * uiMode picks the variant.
     */
    static int[] colors(Context context) {
        boolean night = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        // the control-color rotation is a setting (stage 18); 180 = the
        // original complement
        int rotation = CmusService.prefs(context)
                .getInt(CmusService.PREF_HUE_ROTATION, 180);
        Tones t = night ? nightTones(context, rotation) : lightTones(context, rotation);
        Role[] roles = Role.values();
        int[] out = new int[roles.length];
        for (int i = 0; i < roles.length; i++) {
            out[i] = roles[i].tone.applyAsInt(t);
        }
        return out;
    }

    private static Tones nightTones(Context context, int rotation) {
        Tones t = new Tones();
        t.bg = mix(c(context, android.R.color.system_neutral1_900), 0xFF000000);
        t.band = c(context, android.R.color.system_neutral1_800); // bottom title, active sel
        t.bandDark = mix(t.band, c(context, android.R.color.system_neutral1_900));
        // the top band reads as a scrim over the status bar: darker than the
        // bottom bands, pulled toward the window bg (Patrick)
        t.topBand = mix(t.band, t.bg);
        // darker and more saturated than the pastel ramp end: the title fgs
        // must read apart from the near-white list text (Patrick)
        t.title = c(context, android.R.color.system_accent1_400);
        // the ramps' accent2/3 stay in accent1's warm neighborhood by design,
        // so a "different hue" (Patrick) can't come from them: complement the
        // ramp tone instead — gruvbox-warm's orange titles vs teal statusline,
        // wallpaper-derived. Scoped to the status/cmdline band (and the control
        // bar riding it); the list's playing-track colors stay in the ramp family
        t.status = rotate(c(context, android.R.color.system_accent3_200), rotation);
        t.cur = c(context, android.R.color.system_accent3_300); // playing track — a saturated step
        // dimmer than the sel/cur fgs but on the same warm ramp, not grey: the
        // unselected list rows sit below the highlights in brightness only (Patrick)
        t.fg = mix(c(context, android.R.color.system_accent1_200),
                c(context, android.R.color.system_neutral1_200)); // half-desaturated
        t.error = 0xFFF2B8B5;
        t.info = c(context, android.R.color.system_accent2_200);
        t.separator = c(context, android.R.color.system_neutral2_400);
        t.dir = c(context, android.R.color.system_accent2_200);
        t.selFg = c(context, android.R.color.system_accent1_100);
        t.curSelFg = c(context, android.R.color.system_accent3_100);
        return t;
    }

    private static Tones lightTones(Context context, int rotation) {
        Tones t = new Tones();
        t.bg = mix(c(context, android.R.color.system_neutral1_50), 0xFFFFFFFF);
        t.band = c(context, android.R.color.system_neutral1_100);
        t.bandDark = mix(t.band, c(context, android.R.color.system_neutral1_200));
        t.topBand = mix(t.band, t.bg); // the light mirror: lighter, toward bg
        t.title = c(context, android.R.color.system_accent1_700); // reads apart from the near-black list text
        t.status = rotate(c(context, android.R.color.system_accent3_700), rotation);
        t.cur = c(context, android.R.color.system_accent3_600);
        t.fg = mix(c(context, android.R.color.system_accent1_600),
                c(context, android.R.color.system_neutral1_600)); // dimmer + half-desaturated, same ramp
        t.error = 0xFFB3261E;
        t.info = c(context, android.R.color.system_accent2_700);
        t.separator = c(context, android.R.color.system_neutral2_500);
        t.dir = c(context, android.R.color.system_accent2_700);
        t.selFg = c(context, android.R.color.system_accent1_700);
        t.curSelFg = c(context, android.R.color.system_accent3_800);
        return t;
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
