package net.pgaskin.cmus.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The stage-18 settings screen: app / audio / cmus / data / debug sections
 * (Patrick's curated set). Deliberately *not* cmus-themed — stock Material
 * day/night with a muted accent (Theme.Cmus.Settings).
 * <p>
 * The cmus rows follow the single-source-of-truth rule: they render only
 * from the latest Options echo (replayed on attach) and taps only send
 * `set`; the echo also lands in {@link CmusSettings}'s prefs via the
 * service, which re-forces them on every connect. An invalid entry (bad
 * lib_sort key) errors cmus-side without changing the option, so the row
 * snaps back on the next echo.
 * <p>
 * The data section is troubleshooting tooling: file-level only, through the
 * service's kill→mutate→respawn primitive (never cmus commands — a wedged
 * cmus can't block them and no exit save can rewrite a delete). The partial
 * resets offer a best-effort android-save first (checkbox, default on).
 */
public class SettingsActivity extends Activity {
    private static final int REQUEST_PERMISSION = 1;
    private static final int REQUEST_EXPORT = 2;
    private static final int REQUEST_IMPORT = 3;
    /** MainActivity recreates itself when settings returns this. */
    static final int RESULT_RESET_PREFS = RESULT_FIRST_USER;

    private static final long SAVE_TIMEOUT_MS = 5_000;

    private CmusService service;
    private CmusIpc ipc;
    private boolean bound;
    private boolean started;
    private LinearLayout list;
    private Map<String, String> opts = Map.of();
    private final List<Runnable> refreshers = new ArrayList<>();
    private boolean refreshing; // programmatic widget updates don't send

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((CmusService.LocalBinder) binder).getService();
            if (started) {
                service.setActivityVisible(true); // the +1 onStart skipped
            }
            attachIpc();
            refreshAll();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    private final CmusIpc.Listener ipcListener = event -> {
        if (event instanceof CmusIpc.Options o) {
            opts = o.values();
            refreshAll();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getActionBar().setDisplayHomeAsUpEnabled(true);
        setTitle("Settings");

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(8), 0, dp(24));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Edge-to-edge (targetSdk 35+) no longer pads the content window for
        // the system bars — android:id/content fills the whole window and the
        // action bar paints over its top, clipping the first rows. The insets
        // dispatched here already fold in the action bar height, so applying
        // them as padding drops the content below the bar and above the nav
        // pill (plus the cutout in landscape).
        Ui.applySystemBarPadding(scroll);
        setContentView(scroll);

        buildAppSection();
        buildAudioSection();
        buildCmusSection();
        buildDataSection();
        buildDebugSection();
        buildAboutSection();

        bound = bindService(new Intent(this, CmusService.class), connection,
                Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        if (service != null) {
            service.setActivityVisible(true);
            attachIpc(); // a reset while stopped respawned a fresh CmusIpc
        }
        refreshAll();
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        if (service != null) {
            service.setActivityVisible(false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (ipc != null) {
            ipc.removeListener(ipcListener);
        }
        if (bound) {
            unbindService(connection);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** (Re)registers on the service's current CmusIpc (fresh per respawn). */
    private void attachIpc() {
        CmusIpc current = service != null ? service.getIpc() : null;
        if (current == ipc) {
            return;
        }
        if (ipc != null) {
            ipc.removeListener(ipcListener);
        }
        ipc = current;
        if (ipc != null) {
            ipc.addListener(ipcListener); // replays the cached Options
        }
    }

    private void refreshAll() {
        refreshing = true;
        try {
            for (Runnable r : refreshers) {
                r.run();
            }
        } finally {
            refreshing = false;
        }
    }

    private void sendSet(String key, String value) {
        if (ipc != null) {
            ipc.send("set " + key + "=" + value);
        } else {
            Toast.makeText(this, "cmus is not running", Toast.LENGTH_SHORT).show();
        }
    }

    private SharedPreferences appPrefs() {
        return CmusService.prefs(this);
    }

    // sections

    private void buildAppSection() {
        header("App");

        // music permission status; tap requests, or opens app info once
        // it's permanently denied (no rationale + still denied)
        TextView permSubtitle = new TextView(this);
        addRow(row("Music folder permission", permSubtitle, null, () -> {
            if (!hasMusicPermission()) {
                requestPermissions(new String[]{
                        android.Manifest.permission.READ_MEDIA_AUDIO}, REQUEST_PERMISSION);
            }
        }));
        refreshers.add(() -> permSubtitle.setText(hasMusicPermission()
                ? "Granted. Import can add tracks from the Music folder."
                : "Not granted. Tap to allow reading the Music folder."));

        // optional all-files access; opens the browser at the storage root
        // instead of just Music (import is unaffected). Tap opens the system
        // per-app screen; status refreshes on return
        TextView allFilesSubtitle = new TextView(this);
        addRow(row("All files access", allFilesSubtitle, null, () -> startActivity(
                new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.fromParts("package", getPackageName(), null)))));
        refreshers.add(() -> allFilesSubtitle.setText(Environment.isExternalStorageManager()
                ? "Granted. The browser opens at the storage root and can reach any folder."
                : "Optional. Tap to browse folders outside Music; may also work around "
                        + "storage issues on some devices."));

        switchPrefRow("Show top bar", "The view tabs, filter, and sleep timer. When "
                        + "hidden, the sleep timer and settings are shown as floating buttons.",
                CmusService.PREF_SHOW_TOP_BAR, true, null);
        switchPrefRow("Show bottom bar", "Playback controls and seek bar",
                CmusService.PREF_SHOW_CONTROL_BAR, true, () -> {
                    if (service != null) {
                        service.applyProgressBar(); // auto derives from this
                    }
                });
        switchPrefRow("Show joystick", "The floating navigation dot",
                CmusService.PREF_SHOW_JOYSTICK, true, null);
        switchPrefRow("Direct touch input",
                "Tap and long-press act on the item you touch. When off, touch summons "
                        + "the joystick under your finger and long-press removes the selection.",
                CmusService.PREF_DIRECT_TOUCH, true, () -> {
                    if (service != null) {
                        service.applyMouse();
                    }
                });

        // terminal zoom: the same value pinch-zoom writes, same clamp
        int minFont = dp(5), maxFont = dp(36);
        seekRow("Zoom level", "Terminal font size. Pinch zooming changes this too.",
                minFont, maxFont,
                () -> clamp(appPrefs().getInt(CmusService.PREF_FONT, dp(13)), minFont, maxFont),
                v -> appPrefs().edit().putInt(CmusService.PREF_FONT, v).apply(),
                v -> String.valueOf(v));

        seekRow("Material You hue rotation",
                "How far the status and control bar accent is rotated away from the "
                        + "wallpaper color. Only used while the Material You theme is active.",
                0, 359,
                () -> appPrefs().getInt(CmusService.PREF_HUE_ROTATION, 180),
                v -> {
                    appPrefs().edit().putInt(CmusService.PREF_HUE_ROTATION, v).apply();
                    if (service != null) {
                        service.materialSettingChanged(); // live re-push
                    }
                },
                v -> v + "°");

        int[] idleChoices = {0, 5, 15, 30, 60};
        String[] idleLabels = {"Off", "5 minutes", "15 minutes", "30 minutes", "60 minutes"};
        TextView idleSubtitle = new TextView(this);
        addRow(row("Idle quit", idleSubtitle, null, () -> {
            int cur = indexOf(idleChoices, appPrefs().getInt(CmusService.PREF_IDLE_QUIT_MIN, 15));
            new AlertDialog.Builder(this)
                    .setTitle("Idle quit")
                    .setSingleChoiceItems(idleLabels, cur, (d, which) -> {
                        appPrefs().edit()
                                .putInt(CmusService.PREF_IDLE_QUIT_MIN, idleChoices[which]).apply();
                        if (service != null) {
                            service.idleQuitSettingChanged();
                        }
                        refreshAll();
                        d.dismiss();
                    })
                    .show();
        }));
        refreshers.add(() -> {
            int min = appPrefs().getInt(CmusService.PREF_IDLE_QUIT_MIN, 15);
            idleSubtitle.setText(min == 0 ? "Off. cmus keeps running in the background."
                    : "Quit cmus after " + min + " minutes paused in the background. "
                            + "Everything is saved first.");
        });

        switchPrefRow("Always resume paused",
                "Start paused at the saved position even if cmus was playing when it quit",
                CmusService.PREF_RESUME_PAUSED, false, null);

        TextView sleepSubtitle = new TextView(this);
        addRow(row("Sleep timer action", sleepSubtitle, null, () -> {
            boolean exit = appPrefs().getBoolean(CmusService.PREF_SLEEP_EXIT, false);
            new AlertDialog.Builder(this)
                    .setTitle("Sleep timer action")
                    .setSingleChoiceItems(
                            new String[]{"Pause playback", "Exit the app"}, exit ? 1 : 0,
                            (d, which) -> {
                                appPrefs().edit()
                                        .putBoolean(CmusService.PREF_SLEEP_EXIT, which == 1).apply();
                                refreshAll();
                                d.dismiss();
                            })
                    .show();
        }));
        refreshers.add(() -> sleepSubtitle.setText(
                appPrefs().getBoolean(CmusService.PREF_SLEEP_EXIT, false)
                        ? "Exit the app entirely instead of just pausing"
                        : "Pause playback"));
    }

    private void buildAudioSection() {
        header("Audio");
        boolOptRow("Software volume", "cmus scales samples itself and the volume "
                + "button appears in the bottom bar", "softvol");
        enumOptRow("AAudio performance mode", "dsp.aaudio.performance_mode",
                new String[]{"none", "power_saving"}, RESTART_NOTE);
        enumOptRow("AAudio allowed capture", "dsp.aaudio.allowed_capture",
                new String[]{"all", "none", "system"}, RESTART_NOTE);
        enumOptRow("AAudio sharing mode", "dsp.aaudio.sharing_mode",
                new String[]{"shared", "exclusive"}, RESTART_NOTE);
        boolOptRow("AAudio disable spatialization", RESTART_NOTE,
                "dsp.aaudio.disable_spatialization");
        textOptRow("AAudio min buffer capacity",
                "Milliseconds from 0 to 1000, where 0 keeps the device default. "
                        + RESTART_NOTE + ".",
                "dsp.aaudio.min_buffer_capacity_ms",
                InputType.TYPE_CLASS_NUMBER);
        boolOptRow("Pause on output change",
                "cmus pauses when the output device changes. The system already "
                        + "pauses on unplug and sometimes via media controls, so both "
                        + "may fire, which is harmless.", "pause_on_output_change");
    }

    /** All aaudio options apply at the next output-stream open. */
    private static final String RESTART_NOTE = "Applies from the next track or stop/play";

    private void buildCmusSection() {
        header("cmus");
        boolOptRow("Follow playing track", "Selection follows track changes", "follow");
        boolOptRow("Artist sort name", "Display artistsort tags in the library",
                "display_artist_sort_name");
        boolOptRow("Ignore duplicates", "Hide duplicate tracks in the library",
                "ignore_duplicates");
        textOptRow("Library sort keys", "Sort keys for the library, like albumartist "
                + "date album tracknumber title", "lib_sort", InputType.TYPE_CLASS_TEXT);
        textOptRow("Playlist sort keys", "Sort keys for playlists. Leave empty to keep "
                + "manual order.", "pl_sort", InputType.TYPE_CLASS_TEXT);

        // progress_bar is app-managed (auto = hidden while the bottom bar's
        // own seek slider is visible, `line` otherwise), never synced back
        String[] pbValues = {CmusSettings.PROGRESS_AUTO,
                "disabled", "line", "shuttle", "color", "color_shuttle"};
        TextView pbSubtitle = new TextView(this);
        addRow(row("Progress bar", pbSubtitle, null, () -> {
            String cur = CmusSettings.prefs(this)
                    .getString(CmusSettings.PREF_PROGRESS_BAR, CmusSettings.PROGRESS_AUTO);
            new AlertDialog.Builder(this)
                    .setTitle("Progress bar")
                    .setSingleChoiceItems(pbValues, indexOf(pbValues, cur), (d, which) -> {
                        CmusSettings.prefs(this).edit()
                                .putString(CmusSettings.PREF_PROGRESS_BAR, pbValues[which]).apply();
                        if (service != null) {
                            service.applyProgressBar();
                        }
                        refreshAll();
                        d.dismiss();
                    })
                    .show();
        }));
        refreshers.add(() -> {
            String cur = CmusSettings.prefs(this)
                    .getString(CmusSettings.PREF_PROGRESS_BAR, CmusSettings.PROGRESS_AUTO);
            pbSubtitle.setText(CmusSettings.PROGRESS_AUTO.equals(cur)
                    ? "auto, which hides it while the bottom bar is shown and uses "
                            + "line otherwise" : cur);
        });

        enumOptRow("ReplayGain", "replaygain", new String[]{"disabled", "track", "album",
                "track-preferred", "album-preferred", "smart"}, null);
        boolOptRow("ReplayGain limit", "Prevent clipping by limiting the gain",
                "replaygain_limit");
        textOptRow("ReplayGain preamp", "dB gain applied on top of ReplayGain",
                "replaygain_preamp",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | InputType.TYPE_NUMBER_FLAG_SIGNED);
        boolOptRow("Show hidden files", "Show dotfiles in the file browser", "show_hidden");
        boolOptRow("Show all tracks", "Show every artist's tracks under the "
                + "special All Tracks entry", "show_all_tracks");
        boolOptRow("Show current bitrate", "In the status line", "show_current_bitrate");
        boolOptRow("Show playback position", "In the status line", "show_playback_position");
        boolOptRow("Show remaining time", "Count down instead of up", "show_remaining_time");
        boolOptRow("Skip track info", "Don't read metadata when adding tracks. Adds get "
                + "much faster for huge libraries, but tracks show as bare filenames "
                + "until :update-cache loads their tags.", "skip_track_info");
        enumOptRow("Start view", "start_view",
                new String[]{"tree", "sorted", "playlist", "queue", "browser",
                        "filters", "settings"}, null);
        textOptRow("Tree width percent", "Left pane width in the library view, from 1 to 100",
                "tree_width_percent", InputType.TYPE_CLASS_NUMBER);
        textOptRow("Tree width max", "Column cap for the left pane, where 0 means no cap",
                "tree_width_max", InputType.TYPE_CLASS_NUMBER);

        TextView note = new TextView(this);
        note.setText("Other cmus settings can be changed from the keyboard "
                + "with :set option=value");
        note.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        note.setAlpha(0.6f);
        note.setPadding(dp(16), dp(8), dp(16), dp(8));
        list.addView(note);
    }

    private void buildDataSection() {
        header("Data");

        addRow(row("Export backup", subtitle("Save the library, playlists, and cmus "
                        + "settings as a zip. App settings are not included."),
                null, () -> {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/zip")
                            .putExtra(Intent.EXTRA_TITLE, "cmus-backup-"
                                    + new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                                            .format(new Date()) + ".zip");
                    startActivityForResult(intent, REQUEST_EXPORT);
                }));
        addRow(row("Import backup", subtitle("Replace all cmus data with a saved zip"),
                null, () -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/zip");
                    startActivityForResult(intent, REQUEST_IMPORT);
                }));

        addRow(row("Delete library", subtitle("Remove every track from the library. "
                        + "Playlists and the audio files on disk are kept."), null,
                () -> confirmPartialReset("Delete library",
                        "Remove all tracks from the library? Playlists and the audio "
                                + "files on disk are kept.",
                        () -> new File(CmusFiles.home(this), "lib.pl").delete())));
        addRow(row("Delete playlists", subtitle("Remove every playlist. The library is kept."),
                null,
                () -> confirmPartialReset("Delete playlists",
                        "Remove all playlists? The library is kept.",
                        () -> CmusFiles.deleteTree(new File(CmusFiles.home(this), "playlists")))));
        addRow(row("Delete saved settings", subtitle("Remove cmus's autosaved options "
                        + "and key bindings. The settings on this screen are applied "
                        + "again on top."), null,
                () -> confirmPartialReset("Delete saved settings",
                        "Remove cmus's autosaved configuration? Options and key bindings "
                                + "return to defaults, and the settings on this screen "
                                + "are applied again on top.",
                        () -> new File(CmusFiles.home(this), "autosave").delete())));
        addRow(row("Delete all cmus data", subtitle("The library, playlists, settings, "
                        + "cache, and resume state"), null,
                () -> confirm("Delete all cmus data",
                        "Remove the library, playlists, saved settings, cache, and resume "
                                + "state? Audio files on disk are not touched.",
                        () -> resetWithFileOp(() -> {
                            CmusFiles.deleteTree(CmusFiles.home(this));
                        }))));
        addRow(row("Reset app preferences", subtitle("Theme, fonts, layout, and the "
                        + "app-managed cmus settings on this screen"), null,
                () -> confirm("Reset app preferences",
                        "Reset every app preference (theme, font, zoom, layout, joystick "
                                + "position) and the app-managed cmus settings? cmus's own "
                                + "current state is not touched.",
                        () -> {
                            appPrefs().edit().clear().apply();
                            CmusSettings.prefs(this).edit().clear().apply();
                            if (service != null) {
                                // the default theme is Material You; re-assert
                                // so the running session matches the reset
                                service.applyMaterialYou();
                                service.idleQuitSettingChanged();
                                service.applyProgressBar();
                            }
                            setResult(RESULT_RESET_PREFS); // MainActivity recreates
                            finish();
                        })));
    }

    private void buildDebugSection() {
        header("Debug");
        TextView example = new TextView(this);
        example.setTypeface(Typeface.MONOSPACE);
        example.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        example.setPadding(dp(16), 0, dp(16), dp(12));
        example.setTextIsSelectable(true);
        example.setText("adb shell \"am broadcast -n net.pgaskin.cmus.android/"
                + ".CmusDebugReceiver -e cmd 'player-pause'\"");
        Switch sw = switchPrefRow("Debug command receiver",
                "Let adb (root) send cmus commands through the app",
                CmusService.PREF_DEBUG_RECEIVER,
                CmusService.isDebuggableBuild(this), null);
        list.addView(example);
        refreshers.add(() -> example.setVisibility(sw.isChecked() ? View.VISIBLE : View.GONE));

        switchPrefRow("IPC event logging",
                "Log every cmus IPC event at info level with the logcat tag cmus",
                CmusService.PREF_IPC_LOG,
                CmusService.isDebuggableBuild(this), () -> {
                    if (service != null) {
                        service.ipcLogSettingChanged();
                    }
                });

        // read once at spawn (verbose, so off by default even here) — only
        // offered on debuggable builds
        if (CmusService.isDebuggableBuild(this)) {
            switchPrefRow("cmus debug logging",
                    "Log cmus d_print output at debug level with the logcat tag "
                            + "cmus. Applies on the next app restart.",
                    CmusService.PREF_DEBUG_LOG, false, null);
        }
    }

    private void buildAboutSection() {
        header("About");

        String repo = "https://github.com/pgaskin/cmus-android";
        addRow(row("Source code", subtitle(repo), null,
                () -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(repo)))));

        addRow(row("Third-party licenses",
                subtitle("License texts for the bundled open-source components"),
                null, () -> startActivity(new Intent(this, LicensesActivity.class))));

        String version;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            version = "?";
        }
        addRow(row("Version", subtitle(version), null, null));
    }

    // data-section flows

    /**
     * The partial resets (library / playlists / autosave): confirm with the
     * best-effort "save first" checkbox — SIGKILL skips the exit save, so
     * without it everything else unsaved since the last save is lost too.
     * The save is bounded; a wedged cmus never blocks the cleanup.
     */
    private void confirmPartialReset(String title, String message, Runnable fileOp) {
        CheckBox save = new CheckBox(this);
        save.setText("Save current state first");
        save.setChecked(true);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(save);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setView(wrap)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Delete", (d, w) -> {
                    if (save.isChecked() && service != null) {
                        service.saveState(SAVE_TIMEOUT_MS, () -> resetWithFileOp(fileOp));
                    } else {
                        resetWithFileOp(fileOp);
                    }
                })
                .show();
    }

    private void confirm(String title, String message, Runnable action) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> action.run())
                .show();
    }

    /** kill→mutate→respawn through the service, with a re-attach after. */
    private void resetWithFileOp(Runnable fileOp) {
        if (service == null) {
            Toast.makeText(this, "Service not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        // ensure started semantics for the respawn's startForeground
        startForegroundService(new Intent(this, CmusService.class));
        Toast.makeText(this, "Restarting cmus…", Toast.LENGTH_SHORT).show();
        service.resetData(fileOp, () -> {
            attachIpc(); // fresh CmusIpc after the respawn
            Toast.makeText(this, "Done", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        switch (requestCode) {
            case REQUEST_EXPORT -> {
                // fresh files first (bounded; a dead cmus exports as-is)
                Toast.makeText(this, "Exporting…", Toast.LENGTH_SHORT).show();
                Runnable zip = () -> new Thread(() -> {
                    try {
                        zipHome(uri);
                        runOnUiThread(() -> Toast.makeText(this,
                                "Backup saved", Toast.LENGTH_SHORT).show());
                    } catch (IOException e) {
                        runOnUiThread(() -> Toast.makeText(this,
                                "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                    }
                }, "cmus-export").start();
                if (service != null) {
                    service.saveState(SAVE_TIMEOUT_MS, zip);
                } else {
                    zip.run();
                }
            }
            case REQUEST_IMPORT -> confirm("Import backup",
                    "Replace the current library, playlists, and cmus settings with "
                            + "this backup? Everything not in the backup is deleted.",
                    () -> resetWithFileOp(() -> {
                        try {
                            unzipHome(uri);
                        } catch (IOException e) {
                            throw new RuntimeException("import failed", e);
                        }
                    }));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        if (requestCode == REQUEST_PERMISSION) {
            if (!hasMusicPermission() && !shouldShowRequestPermissionRationale(
                    android.Manifest.permission.READ_MEDIA_AUDIO)) {
                // permanently denied: the system dialog no longer shows
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)));
            }
            refreshAll();
        }
    }

    private boolean hasMusicPermission() {
        return checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    // zip import/export (CMUS_HOME only; the socket lives outside it)

    private void zipHome(Uri uri) throws IOException {
        File home = CmusFiles.home(this);
        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt");
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zipTree(zip, home, "");
        }
    }

    private static void zipTree(ZipOutputStream zip, File dir, String prefix)
            throws IOException {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        byte[] buf = new byte[8192];
        for (File f : files) {
            String name = prefix + f.getName();
            if (f.isDirectory()) {
                zipTree(zip, f, name + "/");
            } else if (f.isFile()) {
                // isFile skips cmus's own server socket ($CMUS_HOME/socket)
                // — opening a socket file throws, and it's not state
                zip.putNextEntry(new ZipEntry(name));
                try (InputStream in = new java.io.FileInputStream(f)) {
                    for (int n; (n = in.read(buf)) != -1; ) {
                        zip.write(buf, 0, n);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    /**
     * Restore = replace, not merge: every existing file in home is deleted
     * first so nothing absent from the zip (stale playlists) lingers.
     * Runs on the reset thread with cmus dead.
     */
    private void unzipHome(Uri uri) throws IOException {
        File home = CmusFiles.home(this);
        CmusFiles.deleteTree(home);
        if (!home.mkdirs()) {
            throw new IOException("recreating " + home + " failed");
        }
        String base = home.getCanonicalPath() + "/";
        byte[] buf = new byte[8192];
        try (InputStream in = getContentResolver().openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                File dst = new File(home, entry.getName());
                if (!dst.getCanonicalPath().startsWith(base)) {
                    throw new IOException("bad zip entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    dst.mkdirs();
                    continue;
                }
                File parent = dst.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("mkdirs failed: " + parent);
                }
                try (OutputStream out = new java.io.FileOutputStream(dst)) {
                    for (int n; (n = zip.read(buf)) != -1; ) {
                        out.write(buf, 0, n);
                    }
                }
            }
        }
    }

    // row builders (hand-rolled preference-style rows; stock Material look)

    private void header(String text) {
        TextView header = new TextView(this);
        header.setText(text);
        header.setTextAppearance(android.R.style.TextAppearance_Material_Body2);
        header.setTextColor(getColor(R.color.settings_accent));
        header.setPadding(dp(16), dp(20), dp(16), dp(4));
        list.addView(header);
    }

    private TextView subtitle(String text) {
        TextView subtitle = new TextView(this);
        subtitle.setText(text);
        return subtitle;
    }

    private LinearLayout row(String title, TextView subtitle, View widget, Runnable onClick) {
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextAppearance(android.R.style.TextAppearance_Material_Subhead);
        texts.addView(titleView);
        if (subtitle != null) {
            subtitle.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            subtitle.setAlpha(0.6f);
            texts.addView(subtitle);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setMinimumHeight(dp(48));
        row.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (widget != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(dp(16));
            row.addView(widget, lp);
        }
        if (onClick != null) {
            TypedValue tv = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            row.setBackgroundResource(tv.resourceId);
            row.setOnClickListener(v -> onClick.run());
        }
        return row;
    }

    private void addRow(LinearLayout row) {
        list.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** An app-pref bool row applied immediately (widget = Switch). */
    private Switch switchPrefRow(String title, String subtitleText, String key,
            boolean def, Runnable onChanged) {
        Switch sw = new Switch(this);
        addRow(row(title, subtitle(subtitleText), sw, sw::toggle));
        sw.setOnCheckedChangeListener((v, checked) -> {
            if (refreshing) {
                return;
            }
            appPrefs().edit().putBoolean(key, checked).apply();
            if (onChanged != null) {
                onChanged.run();
            }
            refreshAll(); // dependent rows (debug example) follow
        });
        refreshers.add(() -> sw.setChecked(appPrefs().getBoolean(key, def)));
        return sw;
    }

    /** A cmus bool option row: renders from the echo, toggles via `set`. */
    private void boolOptRow(String title, String subtitleText, String key) {
        Switch sw = new Switch(this);
        addRow(row(title, subtitleText != null ? subtitle(subtitleText) : null, sw, sw::toggle));
        sw.setOnCheckedChangeListener((v, checked) -> {
            if (refreshing) {
                return;
            }
            sendSet(key, checked ? "true" : "false");
        });
        refreshers.add(() -> sw.setChecked("true".equals(opts.get(key))));
    }

    /** A cmus enum option row: subtitle = current value, tap = chooser. */
    private void enumOptRow(String title, String key, String[] values, String note) {
        TextView subtitle = new TextView(this);
        addRow(row(title, subtitle, null, () -> new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(values, indexOf(values, opts.get(key)), (d, which) -> {
                    sendSet(key, values[which]);
                    d.dismiss();
                })
                .show()));
        refreshers.add(() -> {
            String cur = opts.get(key);
            subtitle.setText(cur == null ? "unknown"
                    : (note == null ? cur : cur + " (" + note.toLowerCase(Locale.US) + ")"));
        });
    }

    /** A cmus free-text/number option row via an edit dialog. */
    private void textOptRow(String title, String subtitleText, String key, int inputType) {
        TextView subtitle = new TextView(this);
        addRow(row(title, subtitle, null, () -> {
            EditText edit = new EditText(this);
            edit.setInputType(inputType);
            edit.setText(opts.get(key));
            edit.setSelection(edit.getText().length());
            LinearLayout wrap = new LinearLayout(this);
            wrap.setPadding(dp(20), dp(8), dp(20), 0);
            wrap.addView(edit, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setView(wrap)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, (d, w) ->
                            sendSet(key, edit.getText().toString()))
                    .show();
        }));
        refreshers.add(() -> {
            String cur = opts.get(key);
            subtitle.setText(cur == null || cur.isEmpty() ? subtitleText
                    : subtitleText + " (now " + cur + ")");
        });
    }

    /** An int app-pref slider row with a live value label. */
    private void seekRow(String title, String subtitleText, int min, int max,
            java.util.function.IntSupplier get, java.util.function.IntConsumer set,
            java.util.function.IntFunction<String> label) {
        TextView value = new TextView(this);
        value.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        SeekBar seek = new SeekBar(this);
        seek.setMin(min);
        seek.setMax(max);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                value.setText(label.apply(progress));
                if (fromUser) {
                    set.accept(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextAppearance(android.R.style.TextAppearance_Material_Subhead);
        TextView subtitle = subtitle(subtitleText);
        subtitle.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitle.setAlpha(0.6f);
        LinearLayout seekLine = new LinearLayout(this);
        seekLine.setOrientation(LinearLayout.HORIZONTAL);
        seekLine.setGravity(Gravity.CENTER_VERTICAL);
        seekLine.addView(seek, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.setMarginStart(dp(12));
        seekLine.addView(value, vlp);
        texts.addView(titleView);
        texts.addView(subtitle);
        texts.addView(seekLine);
        texts.setPadding(dp(16), dp(12), dp(16), dp(12));
        list.addView(texts, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        refreshers.add(() -> seek.setProgress(clamp(get.getAsInt(), min, max)));
    }

    // small helpers

    private int dp(int dp) {
        return Ui.dp(this, dp);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(v, max));
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return i;
            }
        }
        return -1;
    }
}
