# Architecture

Updated each stage. See [plans/00-overview.md](plans/00-overview.md) for the
full plan and rationale; this file describes what currently exists.

## Repo layout

```
├── notes/            spec, plans/, status.md, this file
├── app/              Android app module (Java 21; termux terminal libs
│                     via jitpack, androidx transitively)
├── native/           CMake source root (AGP externalNativeBuild)
│   ├── CMakeLists.txt  upstream-CMake deps via add_subdirectory
│   ├── cmake/          Find{Ogg,Opus}.cmake shims → in-tree targets
│   ├── cmus/           cmus core + plugin targets + generated config/*.h
│   └── ports/          handwritten CMake ports (ncurses, libiconv), each
│                       with gen.sh + committed gen/ outputs
├── third_party/      12 pinned submodules (cmus, ncurses, libogg,
│                     libvorbis, opus, opusfile, flac, libmad, wavpack,
│                     faad2, mp4v2, libiconv)
├── patches/          per-submodule patch dirs (none yet)
├── patch.sh          vncpatch-style patch apply/regenerate
├── settings.gradle   root: pluginManagement + :app
├── build.gradle      AGP 9.3.0 (apply false)
└── gradle/, gradlew  Gradle 9.6.1 wrapper
```

## Third-party sources / patches

- Submodule gitlinks always point at pristine upstream commits (shallow=true
  in .gitmodules except savannah libiconv, which can't serve sha-in-want);
  local changes live as `patches/<name>/*.patch` on top.
- `./patch.sh [-n] [name…]`: tags each gitlink commit as `base` in the
  submodule, `git am`s the patches when HEAD == base, then regenerates the
  patch files from base..HEAD (`-n` skips regen). `./patch.sh check` is
  read-only; the `:app:patchCheck` Exec task runs it before `preBuild` so
  builds fail with a hint when patches exist but aren't applied.
- Pins (2026-07-18): cmus master d335e90, ncurses snapshot 87c2c84,
  libogg v1.3.6, libvorbis v1.3.7, opus v1.6.1, opusfile master 6dfd29e,
  flac 1.5.0, libmad 0.16.4 be34ec9 (the codeberg tenacityteam fork —
  the github URL silently redirects to libid3tag), wavpack 5.9.0,
  faad2 2.11.2, mp4v2 v2.1.3, libiconv v1.19.
- termux terminal-view/-emulator v0.118.3 are a gradle dep from jitpack
  (`com.github.termux.termux-app`, exclusiveContent-filtered; the
  com.termux custom-domain group serves no artifacts), not a submodule —
  their AGP-4-era build.gradle never enters our build.

## Native build

- `native/CMakeLists.txt` is the CMake project AGP drives
  (`externalNativeBuild`, SDK cmake pinned 3.30.5 — not 4.x, which
  rejects the <3.5 minimums in libvorbis/wavpack/mp4v2; ndk r28c
  28.2.13676358, arm64-v8a only, default c++_static STL).
- Upstream-CMake deps (libogg, libvorbis, opus, opusfile, flac, wavpack,
  faad2, mp4v2, libmad) added with `add_subdirectory(... EXCLUDE_FROM_ALL)`,
  forced static, programs/tests/docs off; gradle builds the cmus
  executable + 10 plugin targets, whose transitive deps are exactly the
  12 static libs (ogg vorbis vorbisfile opus opusfile FLAC wavpack faad
  mp4v2 mad-static ncursesw iconv), keeping strays (vorbisenc, opusurl,
  faad_drm, the shared mad…) out.
- `native/cmake/Find{Ogg,Opus}.cmake` (prepended to CMAKE_MODULE_PATH)
  satisfy find_package in libvorbis/opusfile with the in-tree targets.
  `CMAKE_SKIP_INSTALL_RULES=ON` because opusfile/flac install(EXPORT)
  sets otherwise fail generate on the unexported in-tree ogg.
- `native/ports/{ncurses,libiconv}`: handwritten CMake over pristine
  submodule sources + committed pregenerated files in `gen/`, produced by
  each port's documented, rerunnable `gen.sh` (host prereqs: cc, tic,
  gperf, the NDK; reruns must be byte-identical, rerun after pin bumps).
  ncursesw is the configured NORMAL_OBJS module set (wide-char,
  ext-colors, no trace/ticlib/driver); iconv is lib/iconv.c +
  localcharset.c with a handwritten bionic config.h.
- `native/cmus/`: cmus core (the Makefile's cmus-y set, mpris off) as an
  executable named `libcmus.so` — ENABLE_EXPORTS so plugins resolve core
  symbols from it at dlopen — parked in CMAKE_LIBRARY_OUTPUT_DIRECTORY,
  which AGP packages as-is; 9 ip plugins (flac vorbis opus mad wavpack
  aac mp4 wav cue) + op/aaudio as `libcmus_{ip,op}_*.so` SHARED libs
  with codecs linked statically and `-z undefs` overriding the NDK's
  `--no-undefined`. The config/*.h that upstream `./configure` would
  emit are generated at CMake configure time in the same format
  (values: bionic + our deps; DEBUG=1; rtsched off); VERSION = the
  Makefile's `_ver3` fallback + gitlink short sha.
- Static libs land under `app/.cxx/` only. The APK carries the 11 cmus
  libs + termux's libtermux.so (extracted at install:
  `useLegacyPackaging` for exec + plugin symlinks), the
  `assets/terminfo/x/xterm-256color` compiled by ncurses' gen.sh with
  host tic, and `assets/cmus-data/*` (rc + 17 themes) copied by a
  gradle task from the pristine cmus submodule.

## App module

- `net.pgaskin.cmus.android`, minSdk 34 (Android 14), target/compileSdk 36.
- Framework APIs + the termux terminal libs; androidx allowed where it
  makes sense (currently only androidx.annotation, transitively).
- `TermService` — mediaPlayback foreground service owning the cmus
  `TerminalSession`: runs `CmusFiles.prepare`, spawns
  `nativeLibraryDir/libcmus.so` in a pty (env: HOME/TMPDIR/TERM/
  TERMINFO/CMUS_{HOME,LIB_DIR,DATA_DIR}), and is the session's one
  stable `TerminalSessionClient`, forwarding to the attached activity.
  Stub "running" notification (real media notification is stage 9);
  session exit → stopSelf + finish the activity.
- `CmusFiles` — idempotent per-spawn filesDir layout: extracts the
  terminfo + cmus-data assets (stamped by versionCode + APK install
  time), rebuilds `cmus-lib/{ip,op}/NAME.so` symlinks into
  nativeLibraryDir, creates `cmus-home/`.
- `MainActivity` — `TerminalView` (focusableInTouchMode — required for
  IME/keys; set in code, easy to miss) in a FrameLayout wrapper that
  consumes systemBars+cutout+ime insets as padding (TerminalView sizes
  from raw bounds; targetSdk 36 = enforced edge-to-edge, adjustResize
  is a no-op). Tap toggles the IME, pinch scales the font (5–36dp),
  back backgrounds the app (playback continues under the FGS);
  rotation recreates the activity and re-attaches the live session.
- Theme: `Theme.Cmus` (Material NoActionBar, black, short-edges cutout);
  colors become cmus-theme-driven in a later stage.

## Build requirements

- Android SDK at `~/sdk/android` (`local.properties`, gitignored) with
  ndk;28.2.13676358 + cmake;3.30.5 installed, host JDK 25, network for
  gradle/AGP resolution.
- `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

## Coming next (see overview stages)

cmus unix-socket IPC (7: android.c patch, 8: Java client), media
session (9), then lifecycle/chrome/input/overlays/settings (10+).
