package net.pgaskin.cmus.android;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.MenuItem;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * Displays the build-generated third-party licenses page (an asset built by
 * the {@code licensesHtml} Gradle task from the third_party submodules) in a
 * WebView. Static local HTML: JavaScript stays off; the page follows the
 * system day/night via {@code prefers-color-scheme} + algorithmic darkening.
 */
public class LicensesActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getActionBar().setDisplayHomeAsUpEnabled(true);
        setTitle("Third-party licenses");

        WebView web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(false);
        // let the page's prefers-color-scheme CSS follow the app day/night
        // (API 33+, minSdk 34 — no androidx.webkit needed)
        settings.setAlgorithmicDarkeningAllowed(true);
        // paint the WebView the theme background so there's no white flash
        // before the (theme-aware) page paints
        TypedValue bg = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorBackground, bg, true);
        web.setBackgroundColor(bg.data);
        // edge-to-edge (targetSdk 36): keep content clear of the action bar,
        // status bar, nav pill, and cutout (the dispatched top inset already
        // folds in the action bar height, per SettingsActivity)
        Ui.applySystemBarPadding(web);
        setContentView(web);
        web.loadUrl("file:///android_asset/licenses.html");
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
