# Status log

Newest entries first. One entry per work session/stage; enough context to
pick up where things left off.

## 2026-07-18 — Stage 6: terminal MVP (done)

- Per [plans/06-terminal.md](plans/06-terminal.md) with Patrick's
  amendments: terminal-view v0.118.3 via jitpack (exclusiveContent
  repo), termux-app submodule removed (13 → 12),
  `android.useAndroidX=true` (androidx.annotation comes transitively).
  AAR `package=` attr risk retired: resolves fine under AGP 9.
- cmus data/ → APK assets via a Copy task into
  `build/generated/cmus-assets` (AGP 9 rejects Provider srcDirs, so the
  srcDir is an eager File and the task rides on preBuild). CmusFiles
  builds the filesDir layout per plan; one deviation: the asset stamp
  is versionCode **+ APK lastUpdateTime**, since versionCode stays 1
  during development.
- TermService (mediaPlayback FGS) + MainActivity per plan, with one
  design simplification: the service is the session's only
  TerminalSessionClient and forwards to the attached activity via a
  small SessionCallback, so re-attach never needs
  `updateTerminalSessionClient`. Gotcha that cost a debug cycle:
  TerminalView needs `setFocusableInTouchMode(true)` (termux sets it
  in layout XML) — without it the view never becomes the IME target
  and key events silently go nowhere.
- Verified on device (Pixel 8/Android 16): TUI themed, `:add ~/music`
  typed through the soft keyboard, view switching, tap-to-IME with the
  terminal resizing above the keyboard (insets wrapper works); flac
  (+aac, cue) play **from the app uid** — active audioflinger track,
  position advances, pause + seek via the cmus socket — retiring the
  stage-5 aaudio-from-nonroot question. Home-away → playback continues
  under the FGS; relaunch + rotation re-attach the live session;
  `:quit` → cmus exits, autosave written, service + activity gone.
  Pinch zoom confirmed by hand by Patrick (adb can't inject multitouch).
- Flagged for later: our NDK-r28 libs + termux's NDK-21 libtermux.so
  trip Android 16's debug-only "not 16 KB aligned" dialog
  (useLegacyPackaging compresses libs, so the check can't verify
  alignment). Harmless on 4 KB-page devices; revisit if 16 KB pages
  ever matter.

## 2026-07-18 — Stage 5: cmus build (done)

- `native/cmus/CMakeLists.txt` per [plans/05-cmus.md](plans/05-cmus.md):
  the 16 config/*.h generated at configure time in the exact
  `config_header` emit format; core = cmus-y sources as an executable
  named libcmus.so with ENABLE_EXPORTS, linking ncursesw iconv dl m;
  VERSION composed from the Makefile's `_ver3` fallback + gitlink short
  sha (git describe is unusable in the submodule: shallow, and patch.sh's
  `base` tag wins `--tags`).
- 10 plugins as SHARED libs, codecs static inside, `-Wl,-z,undefs`
  overriding the NDK's `--no-undefined` (core syms resolve at dlopen from
  the executable). Two include quirks solved without patches: wavpack's
  in-tree header lives at include/wavpack.h, mirrored into gen/ under the
  installed `<wavpack/wavpack.h>` name; the `"../config/*.h"` quoted
  includes in ip/vorbis.c + ip/mp4.c resolve textually against -I dirs,
  so plugins get an empty `gen/ip` anchor dir beside gen/config.
- Flagged AGP packaging risk retired empirically: AGP packages the
  executable parked in CMAKE_LIBRARY_OUTPUT_DIRECTORY as-is (wireguard
  pattern), no POST_BUILD fallback needed. `useLegacyPackaging = true`
  set (extractNativeLibs); `cmus_deps` aggregate target retired — gradle
  builds cmus + the 10 plugin targets, statics come transitively.
- Verified: clean `./gradlew clean assembleDebug` (~12s); APK gains
  exactly libcmus.so + 10 libcmus_{ip,op}_*.so (terminfo asset
  unchanged); ET_DYN AArch64, 1268 dynsym T entries survive AGP strip;
  plugins show undefined core syms + their codec defined (145 FLAC__ in
  ip flac). On-device (Pixel 8/Android 16, wifi adb; note adbd runs as
  *root* on this device, so the shell-uid aaudio question stayed
  untested): hand-built stage-6 layout under /data/local/tmp/cmus, TUI
  renders with the pushed terminfo, all 10 plugins in /proc/pid/maps,
  flac/mp3/ogg/opus/m4a/wv/wav/aac + a cue sheet all play through
  aaudio with real-time position advance; pause works; `seek 6` on an
  8s tone "stuck" at 3 turned out to be upstream clamping absolute
  seeks to duration−5 (player.c), not a bug. Server socket driven with
  toybox `nc -U` (the socat-equivalent stage-7 baseline); `quit` exited
  cleanly, autosave written. Test layout left on-device for poking.

## 2026-07-18 — Stage 4: native deps B (done)

- libmad repointed to codeberg.org/tenacityteam/libmad @ be34ec9 (0.16.4)
  per [plans/04-native-deps-b.md](plans/04-native-deps-b.md); codeberg
  serves sha-in-want so shallow=true stays; `base` tag force-moved (no
  patches existed). libiconv bumped to v1.19 (stage-2 flag resolved).
- ncurses port: gen.sh runs the submodule's own configure (NDK clang,
  android34) + `make sources` in a scratch dir and commits outputs under
  gen/ with absolute paths sed-stripped. Deviations from plan:
  expanded.c is a real NORMAL_OBJS member (not QA-only; only link_test
  is excluded), and termcap.h/unctrl.h/ncurses_dll.h are generated from
  .in too → 9 headers + 9 sources + init_keytry.h committed. CMake list
  = the 162-object NORMAL_OBJS set (termlib+ext_tinfo+base+widechar+
  ext_funcs; ext-colors/ext-funcs on).
- libiconv port: git tree has no ./configure (autotools bootstrap
  output), so gen.sh drives the Makefile.devel generator programs
  directly (host cc + gperf -m 10; quirk: canonical*.sh read the gperf
  output via the literal filename tmp.h) and seds iconv.h.in
  substitutions by hand; config.h handwritten, minimal. localcharset.c
  is modern gnulib — nl_langinfo only, the plan's LIBDIR/charset.alias
  concern no longer exists in the code.
- Verified: clean `./gradlew clean assembleDebug` (~19s), 12 .a all
  AArch64, right symbols (initscr/resizeterm/use_default_colors,
  libiconv_open, mad_decoder_init); APK diff = exactly the terminfo
  asset; both gen.sh reruns byte-identical (sha256); patchCheck still
  gates. Flagged tic-6.6-vs-lib-6.4 risk retired with an on-device
  setupterm test: 256 colors, 65536 pairs, -x extended caps load fine.
- Host prereq note: gperf 3.2.1 (Patrick installed it mid-session;
  recorded in gen.sh comments since gperf version affects table format).

## 2026-07-18 — Stage 3: native deps A (done)

- `native/CMakeLists.txt` builds the 8 upstream-CMake deps per
  [plans/03-native-deps-a.md](plans/03-native-deps-a.md); `cmus_deps`
  custom target pulls exactly the 9 libs cmus will link (ogg vorbis
  vorbisfile opus opusfile FLAC wavpack faad mp4v2 — table's assumed
  names were all correct). Find{Ogg,Opus} shims as planned; flac turned
  out to short-circuit on `TARGET Ogg::ogg` itself, so only
  libvorbis/opusfile need them.
- Deviations from plan: added `BUILD_CXXLIBS=OFF` for flac (tidiness);
  needed `CMAKE_SKIP_INSTALL_RULES=ON` — opusfile/flac have
  unconditional `install(EXPORT)` sets that fail CMake's generate step
  because the in-tree `ogg` they link isn't in any export set.
- SDK cmake 3.30.5 installed via sdkmanager and pinned in gradle;
  ndkVersion 28.2.13676358 (r28c). wavpack aarch64 asm was a non-issue:
  its ARM64 asm is MSVC-only, plain C elsewhere.
- Verified: clean `./gradlew clean assembleDebug` from scratch (~14s);
  9 .a files under `app/.cxx`, all AArch64, real symbol content; APK
  contents unchanged; patchCheck still gates preBuild.

## 2026-07-18 — Stage 2: submodules + patch.sh (done)

- 13 submodules added under `third_party/` per
  [plans/02-submodules.md](plans/02-submodules.md); pins listed in
  architecture.md. shallow=true except libiconv (savannah can't serve
  sha-in-want fetches). Patrick reconfirmed cmus @ master mid-session.
- libiconv pinned at v1.18 as the plan explicitly says, though v1.19 is
  out — revisit at stage 4 when the port is written.
- `patch.sh` adapted from vncpatch for N submodules; `patch.sh check`
  wired into `:app:patchCheck` → `preBuild`. patches/ empty (.gitkeep).
- Verified: clean clone + `git submodule update --init` (88M), patch.sh
  no-op, dummy-patch roundtrip (regen → reset → check/gradle fail with
  hint → am reapply → regen identical modulo commit sha), assembleDebug.
- Regen quirk (inherent to vncpatch pattern): reapplying via `git am`
  rewrites the patch's first `From <sha>` line since committer/sha
  change; content is otherwise stable.

## 2026-07-18 — Stage 1: scaffolding (done)

- Overall plan written and approved → [plans/00-overview.md](plans/00-overview.md).
  Later amendments during review: NDK-native CMake build for everything (no
  autotools/configure), mpris.c used only as an example (never modified),
  smaller stages, + m4a (mp4v2) and iconv (libiconv port) in scope.
- Gradle 9.6.1 wrapper (bootstrapped from downloaded dist; no system
  gradle), AGP 9.3.0, `:app` skeleton: minSdk 34, compileSdk 36, Java 21,
  no androidx (`net.pgaskin.cmus.android`), stub MainActivity, placeholder
  adaptive icon.
- Verified: `./gradlew assembleDebug` clean build; installed + launched on
  device (wifi adb).
- Workflow reminders: stage plans committed as Patrick (Claude co-author);
  implementation commits authored as Claude unless significant direction
  from Patrick. Real device only (no emulator). Lib sources for reading go
  in `~/srctest/dl`.

## Next

Stage 7: cmus IPC patch (android.c + call-site hooks + remote-stream
removal as patches via patch.sh; verify with socat/adb) — needs its
detailed plan written and approved first. The cmus socket at
`$CMUS_HOME/socket` was driven successfully with `toybox nc -U` from
adb during stages 5–6, so the transport baseline is proven.

Note from Patrick for later stages: use cmus's own `pl_env_vars`
mechanism (pl_env.c — Patrick's upstream feature: saved library/
playlist/cache paths get their base swapped for a named env var, so
the library survives the base path moving) for the Android paths:
have TermService export env vars for the android data dir, the
external storage path, the external data path, etc., and put them in
`pl_env_vars`, so saved paths survive reinstalls / storage moves.

Workflow note: each stage runs in a fresh session — read status.md,
architecture.md, the overview plan, and the current stage plan first.
