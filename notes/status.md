# Status log

Newest entries first. One entry per work session/stage; enough context to
pick up where things left off.

## 2026-07-18 — Stage 12: chrome B (done)

- Per [plans/12-chrome-b.md](plans/12-chrome-b.md) with five live tweaks
  from Patrick: (1) chrome absorbs the terminal's row-quantization
  remainder — a root layout listener computes
  `(wrapperH − firstRowOffset) % lineSpacing` (mirroring TerminalView's
  `rows = (h − mFontLineSpacingAndAscent) / mFontLineSpacing`) and wears
  it as padding split between the two bars on their terminal-adjoining
  edges, so the tab bar sits flush with cmus's own title row and the
  control bar with the cmdline row at any font size (the
  adding-current-extras-back trick makes the computation a fixed point
  across relayouts). (2) Icons are Material Symbols (outlined; fill1
  play/pause; the 0,-960,960,960 viewBox rides in a translateY=960
  group), fetched from google/material-design-icons. (3) The seek thumb
  advances smoothly: a per-frame postOnAnimation ticker extrapolates
  from the last Status/Position event while PLAYING; CmusSlider takes
  float progress and only redraws on ≥0.5px thumb movement, so idle
  frames cost a float compare; drag-release rebases locally so the echo
  round trip doesn't snap back. (4) **Protocol change**: positions are
  now fractional seconds (`%.3f`) in status + position events — without
  it every rebase (pause especially) jumped the extrapolated thumb back
  to the whole second. cmus side: `player_pos_exact()` wrapper in
  player.c (locked `consumer_pos / buffer_second_size()` read;
  CONFIG_ANDROID-guarded, the spec's minimal-wrapper pattern) since
  player_info.pos is int; event *cadence* still keys off the
  whole-second position_changed flag (≤1/s). 0001 amended via fixup +
  `rebase --autosquash base`, regen clean, patch.sh check green.
  (5) The bar's background is **cmdline_bg**, not the plan's
  statusline_bg — it adjoins the cmdline row, and statusline_bg reads
  as a band there; icons/sliders stay statusline_fg. Follow-up to (1):
  TerminalRenderer only paints row backgrounds from
  mFontLineSpacingAndAscent down, so the terminal's top offset px
  stayed default-bg — a black hairline between tab bar and title row;
  a non-clickable titleStrip View (win_title_bg, firstRowOffset tall)
  overlaid on the terminal's top edge closes it.
- ControlBar (horizontal LinearLayout beside a CmusSlider custom view):
  play/pause · repeat · shuffle · seek (weight 1) · volume · add-q ·
  keyboard, statusline colors, sized 3 terminal rows (icons 2) tracking
  pinch-zoom via the same Paint metrics as the renderer. Pure mirror of
  echoed cmus state (stage-11 rule): Options carries repeat/tristate
  shuffle ("&" statusline flag = albums → badge icon)/softvol — TUI
  `r`/`s` keys reach the run_parsed_command options hook, so buttons
  follow both directions. Play mapping = MediaControl's (PLAYING →
  player-pause-playback, PAUSED → player-pause, STOPPED → player-play).
  Volume button GONE unless softvol=true; tap opens a focusable
  PopupWindow above it with a vertical CmusSlider (drag sends `vol n`
  per integer change; left channel shown). The bar now takes the
  bottom+ime insets, its cmdlineBg paints the nav strip, and nav
  icon appearance follows cmdlineBg instead of winBg.
- Verified on device (Pixel 8, wifi adb + debug receiver; Patrick
  hands-on throughout for feel): play/pause round-trips from paused
  and playing with the icon following the Status echo; repeat+shuffle
  taps → statusline `C RS`, second shuffle tap → `C R&` + badge icon;
  seek drag → position event at the dragged second; smooth advance +
  drag feel + volume popup confirmed by Patrick by hand; fractional
  pause verified in the event log (extrapolation ~4.85 vs echoed
  4.833 — no jump); softvol gating + the rest of the matrix covered by
  Patrick's hands-on. Reinstalls mid-test force-stop cmus (documented
  loss window) — unsaved repeat/shuffle toggles revert, expected.
- Testing notes: Patrick was actively using the device during the run —
  injected taps can land in whatever's focused (one hit Cromite; check
  mCurrentFocus first) and device state can change between screenshots.
  tone.aac seek errors red in the cmdline are the known upstream
  raw-ADTS behavior (stage 5), not a bar bug. adb can't drag precisely
  enough to judge slider feel; that stays a hands-on item.

## 2026-07-18 — Stage 11: chrome A (done)

- Per [plans/11-chrome-a.md](plans/11-chrome-a.md) with two live tweaks
  from Patrick during device verification: tab text size **tracks the
  terminal font** (COMPLEX_UNIT_PX at fontSize, updated in onScale on
  pinch — slightly bigger than the plan's fixed 11sp), and the
  overflow-scroll fallback actually has to work — the plan's
  CENTER_HORIZONTAL layout gravity on the row inside the
  HorizontalScrollView broke it (overflow hangs off the unreachable
  left side; scrollX can't go negative). Fix: no layout gravity;
  fillViewport stretches the row so its own gravity centers the tabs
  when they fit, and overflow lays out from the left and scrolls.
- cmus patch 0001 amended (rebase --autosquash over base, regen; 0002
  first-line churn only): `{"type":"view","view":"<view_names>"}` on
  connect + change, one hook line in set_view() after the
  unchanged-early-return. CmusIpc: View record cached/replayed like the
  rest. CmusTheme record: color option string ("default" | 16 names |
  16-255) → index → ARGB via termux's static
  TerminalColors.COLOR_SCHEME (the live emulator only diverges via OSC
  4/10/11, which cmus never sends), default → indices 256/257 per
  fg/bg role; record equality = change detection.
- MainActivity: root is now vertical LinearLayout {tab bar, terminal
  wrapper}; insets split so the tab bar's bg (win_title) paints the
  status-bar strip and the wrapper's (win_bg) the nav strip + side
  margins — setStatusBarColor is a no-op under targetSdk 36
  edge-to-edge, icon appearance via setSystemBarsAppearance by
  bg luminance is the only real API. Tabs: text-only view_names,
  monospace, active = win_title_fg, inactive = same at 55% alpha; tap
  sends `view <name>` and the highlight only moves when the event
  comes back (cmus is the single source of truth, so TUI 1-7
  presses and resume land identically). Listener registration is
  per-CmusIpc-instance (attachIpc after every getSession) since
  respawn creates a fresh instance; replay makes late attach correct.
- Verified on device (Pixel 8, wifi adb): snapshot carries `view`;
  tab tap → TUI switches + highlight follows on the echo; TUI key 2 →
  highlight follows; `colorscheme gruvbox-warm` via debug receiver →
  whole chrome (tab bar + status strip + nav strip) recolors off one
  options event, seamless against the TUI title row;
  `set color_win_title_bg=white` → dark status icons (and back);
  quit in sorted → relaunch: resume restores the view, snapshot
  highlights it; landscape rotation keeps colors + highlight
  (re-registration path); narrow-screen overflow (wm size 600px)
  scrolls to `settings` and back. patch.sh check green.
- Testing notes: `pm install -r` mid-verification force-stops cmus →
  the gruvbox change from earlier wasn't in autosave (documented
  stage-10 loss window, not a bug). adb can't inject pinch; font-scale
  tracking eyeballed by Patrick. wm size overflow test needs the swipe
  y-coordinate on the bar itself (~115px raw), not the TUI title row.

## 2026-07-18 — Stage 10: lifecycle (done)

- Per [plans/10-lifecycle.md](plans/10-lifecycle.md) with one design
  change from Patrick mid-implementation: **idle-quit kills only the
  cmus process, never the app**. Session death while the activity is
  backgrounded (idle-quit, crash, anything) → service drops
  session/ipc/mediaControl refs, stops the FGS, stopSelf (the record
  lingers while the backgrounded activity holds its binding — fine);
  the task stays in recents and the activity's next onStart respawns
  via startForegroundService + getSession() + re-attach. Forced
  resume=true makes the round trip invisible. Death while *visible*
  keeps the plan's behavior: exit 0 → finish, nonzero → frozen
  terminal + toast; the termux lib supplies its own "[Process
  completed (signal N) - press Enter]" banner, so Enter closes it
  too, beside tap.
- Forced options (policy per plan): onConnected now sends mouse +
  resume + pl_env_vars; all three verified persisted in autosave
  after quit. Spawn env exports
  CMUS_ANDROID_{EXT_FILES,EXT,FILES} (names now **locked** — they're
  baked into saved libraries); lib.pl showed
  `\x1F`-substituted entries for both EXT_FILES and FILES, cache too
  (10 entries). Caveat learned: pl_env is exact-prefix, so paths
  entered via the /data/data symlink stay literal (harmless) —
  HOME/cwd use the real /data/user/0, so ~-adds and browser adds
  substitute fine.
- Verified on device (Pixel 8, wifi adb root; idle delay temporarily
  45s for testing, shipped at 15 min): resume round-trips paused
  position/view across quit; quit-while-playing resumes playing.
  Idle-quit: arms only when (not PLAYING) && backgrounded; cancel on
  refocus and on unpause-from-shade; rotation recreation = 27ms
  armed/cancelled flicker, no quit; fire at exactly the delay →
  session/FGS/notification gone, task stays, refocus respawns
  seamlessly. Task swipe: playing → survives (FGS point proven);
  idle → immediate lossless quit, everything gone. `kill -9` of the
  app pid while playing → pty SIGHUP → cmus wrote resume + autosave
  + lib.pl (8ms apart) but Android's cgroup kill raced the tail of
  exit_all (socket unlink missed — harmless, android.c unlinks
  before bind); relaunch resumed *playing* at position. `pm install
  -r` (≈ force-stop: uid-wide SIGKILL, no pty grace) lost the
  since-last-save state entirely — the documented loss window, which
  Patrick's periodic-save note below closes. Crash path (`kill
  -ABRT` cmus): frozen TUI + banner + toast, activity stays resumed,
  tap closes everything. patch.sh check green (Java-only stage).
- Second design addition from Patrick: **media keys resurrect a
  background-quit cmus**. MediaControl registers a
  setMediaButtonBroadcastReceiver fallback (MediaButtonReceiver);
  after the session dies, a play/play-pause/headset key restarts the
  FGS (`Background started FGS: Allowed`, MEDIA_SESSION_CALLBACK
  temp-allowlist), which spawns cmus **headlessly** and unpauses the
  resume-restored track via a pending-play flag consumed by the first
  Status event. Two framework gotchas cost debug cycles: (1)
  TerminalSession's constructor doesn't fork — only initializeEmulator
  (normally reached via TerminalView attach) does — so getSession now
  calls session.updateSize itself at the last attached size (saved to
  prefs on every activity onStop, per Patrick, to avoid layout shift
  on reopen; the attaching view just resizes the pty). (2)
  `setMediaButtonBroadcastReceiver(null)` **crashes** on Android 16
  (server-side NPE in MediaButtonReceiverHolder.create) and stale
  archived-session registrations linger anyway, so foreground quits
  are gated by a `resurrect` pref (set true on spawn, false on
  visible-session death, checked in the receiver) instead of clearing
  the registration. Headless receiver starts also mark
  activityVisible=false so the idle timer can reap a paused
  resurrected cmus. Verified: swipe-quit → MEDIA_PLAY → playing at
  saved position on a 61×60 pty (last real size); TUI `:quit` in
  foreground → key logged + ignored; activity attach onto a headless
  session renders full-size.
- Testing notes: adb-shell quoting eats multi-word `-e cmd` args
  unless the *whole* am command is one quoted string — bare `seek`/
  `add`/`view` land argument-less and the TUI shows "error: not
  enough arguments" (that's what those errors were). softvol volume
  had drifted to 0 during earlier stages; `vol 100` restored.
  Leftover on device: ext.flac (tagged.flac copy) in the external
  files music dir, in the library as a pl_env test entry.

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

Stage 13: input A (extension key row when the IME is visible:
horizontally scrolling shift/ctrl/alt · space · del/esc/tab · space ·
arrows · space · home/end/pgup/pgdn, termux-style sticky modifiers) —
needs its detailed plan written and approved first. The control bar's
keyboard button and MainActivity's IME toggling/insets are the
integration points; TerminalViewClient's readControlKey/readAltKey/
readShiftKey stubs in MainActivity are where sticky modifiers land.

Note for later (Patrick; stage 18 data import, or wherever imports
land first): inhibit the idle-quit timer while media is being
imported — a long add/import scan with the app backgrounded and
nothing playing looks exactly like idle, and quitting mid-import
would truncate it. TermService.updateIdleQuit is the gate point;
cmus-side job activity could come from the running import command's
lifecycle (the importer knows when it started/finished) rather than
anything cmus reports.

Future feature (Patrick, needs a cmus patch): periodically save cmus
state — resume file, autosave/library, cache — during runtime, not
just at exit, to protect against unexpected exits that skip the
SIGHUP path entirely (force-stop SIGKILLs the whole uid, battery
death, panics). Likely a small timer in the android.c patch or a
`save`-ish IPC command the app triggers; stage 10 documents the loss
window it would close.

Note for later (stage 19 polish at the latest): ogg/opus embedded
art — the framework extractor doesn't read METADATA_BLOCK_PICTURE,
so MediaControl falls back to folder art for those; implement a
small Java-side parser (base64 FLAC PICTURE block in the vorbis
comment header) as another step in MediaControl's art chain.

Pending verification (hardware, Patrick): Bluetooth — headset keys
+ AVRCP metadata, BT-drop/unplug becoming-noisy pause, and
transient-loss auto-resume via a real call/assistant (stage 9
shipped these untested beyond the adb-drivable paths).

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
