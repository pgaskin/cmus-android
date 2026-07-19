package net.pgaskin.cmus.android;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Builds the on-disk layout cmus needs before it is spawned. Everything
 * app-managed lives under a dotfolder so the cmus file browser (cwd/$HOME =
 * filesDir, show_hidden off by default) shows a clean home:
 *
 * <pre>
 * filesDir/.cmus/terminfo/x/xterm-256color   extracted asset (TERMINFO)
 * filesDir/.cmus/data/{rc,*.theme}           extracted assets (CMUS_DATA_DIR)
 * filesDir/.cmus/home/                       autosave state (CMUS_HOME)
 * filesDir/.cmus/assets.stamp
 * filesDir/.cmus/android.sock                (CMUS_ANDROID_SOCKET; outside home
 *                                             so zip exports never carry it)
 * </pre>
 *
 * Idempotent; called by {@link CmusService} before each spawn.
 */
final class CmusFiles {
    /** APK asset tree name → extraction dir name under the dotfolder. */
    private static final String[][] ASSET_TREES = {{"terminfo", "terminfo"}, {"cmus-data", "data"}};
    private static final String STAMP_NAME = "assets.stamp";

    private CmusFiles() {}

    static File root(Context context) {
        return new File(context.getFilesDir(), ".cmus");
    }

    static File home(Context context) {
        return new File(root(context), "home");
    }

    static File data(Context context) {
        return new File(root(context), "data");
    }

    static File terminfo(Context context) {
        return new File(root(context), "terminfo");
    }

    static File socket(Context context) {
        return new File(root(context), "android.sock");
    }

    static void prepare(Context context) throws IOException {
        migrate(context);
        extractAssets(context);
        home(context).mkdirs();
    }

    /**
     * Pre-stage-18 installs had everything at the filesDir top level; move
     * the state (cmus-home) and drop the rest (regenerated under .cmus).
     */
    private static void migrate(Context context) throws IOException {
        File filesDir = context.getFilesDir();
        File oldHome = new File(filesDir, "cmus-home");
        if (oldHome.isDirectory()) {
            File home = home(context);
            if (!home.exists()) {
                root(context).mkdirs();
                if (!oldHome.renameTo(home)) {
                    throw new IOException("migrating " + oldHome + " -> " + home + " failed");
                }
            } else {
                deleteTree(oldHome);
            }
        }
        for (String stale : new String[]{"cmus-lib", "cmus-data", "terminfo",
                "cmus-assets.stamp", "cmus-android.sock"}) {
            deleteTree(new File(filesDir, stale));
        }
    }

    /**
     * Extract the terminfo + cmus-data asset trees, skipped when the stamp
     * shows they came from this exact APK (versionCode is always 1 during
     * development, so the install time is part of the stamp).
     */
    private static void extractAssets(Context context) throws IOException {
        File root = root(context);
        File stampFile = new File(root, STAMP_NAME);

        String stamp;
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            stamp = pi.getLongVersionCode() + ":" + pi.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            throw new AssertionError(e);
        }
        try {
            if (stamp.equals(new String(Files.readAllBytes(stampFile.toPath()), StandardCharsets.UTF_8))) {
                return;
            }
        } catch (IOException e) {
            // no stamp yet
        }

        root.mkdirs();
        for (String[] tree : ASSET_TREES) {
            deleteTree(new File(root, tree[1]));
            extractAssetTree(context, tree[0], new File(root, tree[1]));
        }
        try (OutputStream out = new FileOutputStream(stampFile)) {
            out.write(stamp.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void extractAssetTree(Context context, String assetPath, File dst) throws IOException {
        String[] children = context.getAssets().list(assetPath);
        if (children == null || children.length == 0) {
            // leaf: an asset file
            try (InputStream in = context.getAssets().open(assetPath);
                 OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) != -1; ) {
                    out.write(buf, 0, n);
                }
            }
            return;
        }
        if (!dst.isDirectory() && !dst.mkdirs()) {
            throw new IOException("mkdirs failed: " + dst);
        }
        for (String child : children) {
            extractAssetTree(context, assetPath + "/" + child, new File(dst, child));
        }
    }

    static void deleteTree(File file) {
        if (file.isDirectory() && !isSymlink(file)) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteTree(child);
                }
            }
        }
        file.delete();
    }

    private static boolean isSymlink(File file) {
        return Files.isSymbolicLink(file.toPath());
    }
}
