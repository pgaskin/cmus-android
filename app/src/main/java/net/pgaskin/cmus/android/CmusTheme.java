package net.pgaskin.cmus.android;

import android.graphics.Color;

import com.termux.terminal.TerminalColors;
import com.termux.terminal.TextStyle;

import java.util.Map;

/**
 * The chrome-relevant cmus color options resolved to ARGB through the
 * terminal palette, so chrome painted with these matches what TerminalView
 * renders. cmus serializes each color_* option as "default" (terminal
 * default fg/bg), one of 16 names, or a bare 16-255 palette index
 * (options.c get_color). Resolution goes through the live emulator palette
 * when given one: cmus never sends OSC 4/10/11, but since stage 16 the app
 * itself rewrites entries in place of one (the Material You scheme), so
 * the static scheme is only the no-emulator fallback. Being a record,
 * {@link #equals} is the change detector for options events that touch no
 * colors — and for palette pushes that move ARGB under unchanged indexes.
 */
public record CmusTheme(int winBg, int winFg, int winTitleBg, int winTitleFg,
        int statuslineBg, int statuslineFg, int cmdlineBg, int separator) {

    /** Indices 0-15, in options.c color_enum_names order. */
    private static final String[] COLOR_NAMES = {
            "black", "red", "green", "yellow", "blue", "magenta", "cyan", "gray",
            "darkgray", "lightred", "lightgreen", "lightyellow", "lightblue",
            "lightmagenta", "lightcyan", "white",
    };

    /** palette = the live emulator copy; null falls back to the static scheme. */
    public static CmusTheme from(CmusIpc.Options options, int[] palette) {
        Map<String, String> o = options.values();
        int[] p = palette != null ? palette : TerminalColors.COLOR_SCHEME.mDefaultColors;
        return new CmusTheme(
                bg(o.get("color_win_bg"), p),
                fg(o.get("color_win_fg"), p),
                bg(o.get("color_win_title_bg"), p),
                fg(o.get("color_win_title_fg"), p),
                bg(o.get("color_statusline_bg"), p),
                fg(o.get("color_statusline_fg"), p),
                bg(o.get("color_cmdline_bg"), p),
                fg(o.get("color_separator"), p));
    }

    /** For the APPEARANCE_LIGHT_* system-bar icon flips. */
    public static boolean isLight(int color) {
        return Color.luminance(color) > 0.5f;
    }

    private static int bg(String value, int[] palette) {
        return resolve(value, TextStyle.COLOR_INDEX_BACKGROUND, palette);
    }

    private static int fg(String value, int[] palette) {
        return resolve(value, TextStyle.COLOR_INDEX_FOREGROUND, palette);
    }

    private static int resolve(String value, int defaultIndex, int[] palette) {
        int index = parseColor(value);
        return palette[index >= 0 && index <= 255 ? index : defaultIndex];
    }

    /** -1 for "default"; unknown/missing also -1 rather than crashing. */
    private static int parseColor(String value) {
        if (value == null || value.equals("default")) {
            return -1;
        }
        for (int i = 0; i < COLOR_NAMES.length; i++) {
            if (COLOR_NAMES[i].equals(value)) {
                return i;
            }
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
