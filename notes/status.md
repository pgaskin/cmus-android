# Status log

Newest entries first. One entry per work session/stage; enough context to
pick up where things left off.

## 2026-07-18 — Stage 9: media control (done)

- Per [plans/09-media.md](plans/09-media.md), no design deviations.
  MediaControl (a CmusIpc.Listener the service registers beside its
  debug logger): framework MediaSession/MediaStyle, PlaybackState off
  Status events (position extrapolated by the system from
  position+speed; Position events only checked against the
  extrapolation and applied when >2s off, so no per-second binder
  churn), metadata title/artist/album/duration off the tags multimap
  (values joined ", ", filename fallback title), art on a
  single-thread executor (embedded via MediaMetadataRetriever →
  {cover,folder,front,album}.{jpg,jpeg,png} in the track dir, cached
  per dir, power-of-2 downsample to ≤640px, stale-track guard),
  onPlay = `player-pause` toggle when paused / `player-play` only
  from stopped (player-play *restarts* a loaded track), onPause =
  `player-pause-playback`, audio focus mirrored (request on PLAYING,
  abandon on STOPPED, hold across PAUSED; LOSS→pause,
  LOSS_TRANSIENT→pause+resume-flag, denied→log only — TUI playback
  is user intent), becoming-noisy → pause. Stub "running"
  notification replaced; channel stays `term`.
- Trivial plan resolutions: notification notify() runs on every
  status (events are main-thread messages, always after
  onStartCommand's startForeground — no guard needed); largeIcon
  also set for the shade fallback; STOPPED renders fine in the QS
  card, kept as-is.
- Verified on device (Pixel 8, root adb; ffmpeg-generated tagged/art
  test tracks + albumdir with cover.jpg pushed to ~/music): QS/shade
  card with title/artist, art-tinted background, live seekbar;
  `cmd media_session dispatch play-pause` toggles both ways (headset
  path); seekbar tap → seek (3→21 on a 30s flac; near-end tap
  clamped to duration−5 upstream); steady playback leaves the
  PlaybackState `updated` stamp untouched across dumps (no churn)
  while `seek +15` bumps it; art: mp3 APIC + m4a covr + flac
  embedded ok, ogg + wv (retriever throws, logged) fall back to
  folder art, artless → placeholder, all confirmed via
  dumpsys media_session metadata size; focus: Cromite playing an
  http mp3 → cmus pauses (no auto-resume on permanent loss, our
  stack entry dropped by the framework — matches the focusHeld
  bookkeeping), TUI-unpause takes focus back (Cromite pauses);
  player-stop → STOPPED + focus abandoned; `nc -U` steal →
  instant reconnect, controls fully repopulated from the snapshot;
  `quit` → one disconnect log, session + notification gone (dumpsys
  archive sections still list them — grep "Current"/live sections),
  service gone, relaunch re-inits clean. patch.sh check green (no
  cmus changes).
- Left for Patrick's hands-on (hardware): BT headset keys + AVRCP
  metadata, unplug/BT-drop becoming-noisy pause, transient-loss
  auto-resume via a real call/assistant.

## 2026-07-18 — Stage 8: Java IPC client (done)

- Per [plans/08-ipc-client.md](plans/08-ipc-client.md), no design
  deviations. CmusIpc: sealed Event hierarchy (Hello/Status/Position/
  Volume/Options records) parsed with android.util.JsonReader
  (org.json's JSONObject silently drops duplicate keys), callbacks on
  the main thread; cached Status/Volume/Options replayed to late
  listeners; reader thread + write HandlerThread; reconnect every
  100ms for ~10s then 1s until close(); send() drops+logs when
  disconnected, throws on newline/overlong commands (android.c's
  4096-byte line buffer drops the client on overflow).
- forceMouseOption deleted: a service-owned listener sends
  `set mouse=true` from onConnected — every (re)connect, and the
  options event echoes mouse=true back, self-verifying — retiring the
  last consumer of the legacy cmus-home/socket poll. The service logs
  every event at DEBUG (tag `cmus`). CmusDebugReceiver
  (exported=false, FLAG_DEBUGGABLE-gated) forwards `-e cmd` broadcasts
  from root adb through the Java write path; kept as a permanent
  debug tool.
- Verified on device (Pixel 8, root adb + logcat): connect snapshot
  (hello v2.12.0-<sha>, status, volume, options n=129) + mouse echo;
  player-play/pause via broadcast → status events, position ~1/s only
  while playing; `seek +3` on flac → position-only event;
  `colorscheme night` → one coalesced options event with changed
  color_*; `vol 50` → volume event; 5001-byte command rejected
  client-side with the connection surviving; `toybox nc -U` stealing
  the socket → disconnected + instant steal-back reconnect with a
  fresh snapshot (nc gets the EOF); `quit` → one disconnect log, no
  reconnect spam, socket unlinked, service gone. patch.sh check green
  (no cmus changes this stage).
- Learned: cmus itself refuses duplicate tag keys (comment.c
  comments_add, "don't add duplicates" — first wins), so the
  contract's "may repeat" can't actually occur through the standard
  ips; the Map<String, List<String>> multimap stays as
  protocol-faithful hedging. Seeking the raw-ADTS tone.aac yields a
  status event at pos=0 (upstream ip/aac seek behavior, not an IPC
  issue); flac seeks cleanly.
- Reviewed github.com/Endg4meZer0/cmus 41e2557 (Patrick's pointer:
  fixes MPRIS Seeked signals reporting the *old* position by moving
  mpris_seeked() from player_seek() into update() behind a new
  position_seeked snapshot flag): not a bug we share — android_flush
  runs after player_info_snapshot()/update(), so positions we send
  are always post-snapshot. If it merges upstream, expect trivial
  0001 context churn in ui_curses.c around update() at the next pin
  bump; its position_seeked flag could also distinguish seeks from
  natural advance should stage 9's MediaSession want that.

## 2026-07-18 — Stage 7: cmus IPC patch (done)

- Per [plans/07-ipc.md](plans/07-ipc.md), no deviations from the plan's
  design. patches/cmus/0001 = android.c/android.h + 6 small hunks
  (ui_curses.c init/free/select-loop/flush, command_mode.c options-dirty
  hook at the end of run_parsed_command); 0002 = remote-stream removal
  (input.c machinery + the timeout/pl-mime statics behind `#ifndef
  CONFIG_ANDROID` with a stub open_remote, cmus_detect_ft keeps only
  is_cue_url). The protocol block atop android.c is the stage-8
  contract: JSON events out (hello/status/position/volume/options; one
  flush per main-loop iteration off the player_info snapshot flags, a
  position event only when no status event is sent), raw command lines
  in. Single client, new-connection-wins, full snapshot on connect.
- Implementation notes: inbound uses a persistent 4 KB line buffer
  (server.c's local buffer can drop a partial line between reads;
  overlong line = client dropped); the write path treats EAGAIN as a
  dead client rather than write_all's busy-loop; `config/*.h` in a
  comment trips -Wcomment, mind the wording. CMake follow-on fix: the
  VERSION sha now resolves the gitlink via the parent repo (`git -C
  third_party/.. rev-parse ":third_party/cmus"`) — submodule HEAD is
  now the patched tip, whose sha changes every regen.
- patch.sh got its first real exercise: regen → reset → gradle fails
  via patchCheck with the hint → reapply → regen stable modulo the
  known `From <sha>` first-line churn. TermService exports
  `CMUS_ANDROID_SOCKET=<filesDir>/cmus-android.sock`.
- Verified on device (Pixel 8, `toybox nc -U` from adb root + host jq):
  connect snapshot hello/status/volume/options all valid JSON (129
  options incl. all color_*); play → status event + position ~1/s;
  seek → position event (a seek racing player-play in the same write
  is ignored by the player, upstream behavior, don't chase it);
  `player-pause` inbound pauses with the status event back;
  `set softvol=true` → volume + options events; `colorscheme gruvbox`
  → one coalesced options event (note gruvbox sets color_win_bg=default
  — check color_win_cur/title_bg when eyeballing theme changes);
  second connect drops the first (clean EOF) with a fresh snapshot on
  the new one; `add http://…` rejected, library intact (`save -l -`
  over the old socket is a handy library dump); `quit` sent through
  the android socket → clean EOF, socket unlinked, autosave written,
  service gone (only the frozen empty app process remains, as in
  stage 6). nm: 6 android_* T-syms exported, zero http syms left.

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
- Post-verify fix from Patrick's hands-on: touch gestures now work like
  termux — TermService forces `set mouse=true` over the cmus socket on
  every spawn (Patrick: overriding user config is correct, mouse is a
  core part of the app; socket write leaves user files alone), so tap =
  click and drag/fling = wheel scroll, handled natively by TerminalView
  when mouse tracking is on. Tap toggles the IME only when tracking is
  off (termux's gate); interim keyboard access in mouse mode =
  long-press (control-bar toggle lands stage 12, selection dies stage
  14). Confirmed working by Patrick.
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

Stage 10: lifecycle (idle-quit timer, service/activity edge cases,
process restart behavior) — needs its detailed plan written and
approved first. Idle-quit per the overview: cmus stopped + app
unfocused for X min → send `quit`, stop service; CmusIpc's cached
Status has the play state, MediaControl's session already tracks
engagement. This is also the earliest slot for Patrick's pl_env
note below (external-storage path story permitting).

Note from Patrick for later stages (stage 10 at the earliest — needs
the external-storage path story): use cmus's own `pl_env_vars`
mechanism (pl_env.c — Patrick's upstream feature: saved library/
playlist/cache paths get their base swapped for a named env var, so
the library survives the base path moving) for the Android paths:
have TermService export env vars for the android data dir, the
external storage path, the external data path, etc., and put them in
`pl_env_vars`, so saved paths survive reinstalls / storage moves.

Idea from Patrick for later (theme selector, ~stage 16): a generated
Material You colorscheme — Java reads the system dynamic color palette
(android.R.color system_accent/neutral tones) and produces a cmus
.theme from it (written to cmus-data or applied via `set color_*` over
IPC), offered alongside the bundled themes and updating when the
system palette changes.

Workflow note: each stage runs in a fresh session — read status.md,
architecture.md, the overview plan, and the current stage plan first.
