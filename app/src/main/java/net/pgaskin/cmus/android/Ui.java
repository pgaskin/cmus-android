package net.pgaskin.cmus.android;

import android.content.Context;
import android.graphics.Insets;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowInsets;

/**
 * Small view/pixel/color helpers shared by the activities and the custom
 * views, so density conversion, alpha packing, the edge-to-edge inset
 * padding, and the borderless-ripple attr lookup each live in one place
 * rather than being copied per class.
 */
final class Ui {
    private Ui() {}

    /** dp → px for the context's current display metrics. */
    static int dp(Context ctx, float dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics()));
    }

    /** Replace a color's alpha with an 8-bit value (0..255), keeping its RGB. */
    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /**
     * Pad a view by the system bars + display cutout (edge-to-edge, targetSdk
     * 35+). The dispatched top inset already folds in the action bar height,
     * so this keeps content clear of the bar, nav pill, and cutout. Not for
     * views that also ride the IME animation (they consume insets themselves).
     */
    static void applySystemBarPadding(View v) {
        v.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    /** The theme's borderless-ripple drawable resource (0 if unset). */
    static int selectableBorderlessRes(Context ctx) {
        TypedValue tv = new TypedValue();
        ctx.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, tv, true);
        return tv.resourceId;
    }
}
