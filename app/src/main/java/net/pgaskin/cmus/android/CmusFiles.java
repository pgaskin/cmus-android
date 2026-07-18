package net.pgaskin.cmus.android;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.system.ErrnoException;
import android.system.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Builds the on-disk layout cmus needs under filesDir before it is spawned:
 *
 * <pre>
 * filesDir/terminfo/x/xterm-256color   extracted asset (TERMINFO)
 * filesDir/cmus-data/{rc,*.theme}      extracted assets (CMUS_DATA_DIR)
 * filesDir/cmus-lib/{ip,op}/NAME.so    symlinks into nativeLibraryDir (CMUS_LIB_DIR)
 * filesDir/cmus-home/                  autosave state (CMUS_HOME)
 * </pre>
 *
 * Idempotent; called by {@link TermService} before each spawn.
 */
final class CmusFiles {
    private static final String[] ASSET_TREES = {"terminfo", "cmus-data"};
    private static final String STAMP_NAME = "cmus-assets.stamp";

    private CmusFiles() {}

    static void prepare(Context context) throws IOException {
        extractAssets(context);
        linkPlugins(context);
        new File(context.getFilesDir(), "cmus-home").mkdirs();
    }

    /**
     * Extract the terminfo + cmus-data asset trees, skipped when the stamp
     * shows they came from this exact APK (versionCode is always 1 during
     * development, so the install time is part of the stamp).
     */
    private static void extractAssets(Context context) throws IOException {
        File filesDir = context.getFilesDir();
        File stampFile = new File(filesDir, STAMP_NAME);

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

        for (String tree : ASSET_TREES) {
            deleteTree(new File(filesDir, tree));
            extractAssetTree(context, tree, new File(filesDir, tree));
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

    /**
     * cmus scans CMUS_LIB_DIR/{ip,op}/*.so and takes the plugin name from the
     * filename, so give it symlinks named vorbis.so etc. pointing at the
     * libcmus_ip_vorbis.so AGP extracted. Rebuilt from scratch on every spawn:
     * cheap, and immune to nativeLibraryDir moving between installs.
     */
    private static void linkPlugins(Context context) throws IOException {
        File libDir = new File(context.getFilesDir(), "cmus-lib");
        deleteTree(libDir);

        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        for (String kind : new String[]{"ip", "op"}) {
            File kindDir = new File(libDir, kind);
            if (!kindDir.mkdirs()) {
                throw new IOException("mkdirs failed: " + kindDir);
            }
            String prefix = "libcmus_" + kind + "_";
            String[] libs = nativeDir.list((dir, name) -> name.startsWith(prefix) && name.endsWith(".so"));
            if (libs == null || libs.length == 0) {
                throw new IOException("no " + kind + " plugins in " + nativeDir);
            }
            for (String lib : libs) {
                String name = lib.substring(prefix.length());
                try {
                    Os.symlink(new File(nativeDir, lib).getPath(), new File(kindDir, name).getPath());
                } catch (ErrnoException e) {
                    throw new IOException("symlink " + name + " failed", e);
                }
            }
        }
    }

    private static void deleteTree(File file) {
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
