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
├── patches/          per-submodule patch dirs (cmus: 2)
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
- patches/cmus: 0001 adds the app IPC socket (android.c/android.h + hook
  hunks in ui_curses.c/command_mode.c — including a view event out of
  set_view() — + a player_pos_exact() wrapper in player.c/h reading the
  fractional position under player_lock, since player_info.pos is whole
  seconds and the app's seek bar animates between events; a keys.c hook
  in normal_mode_mouse emitting the `selected` event on right-clicks
  that resolve to a list row [the win-remove target set via the shared
  _for_each_sel machinery + playlist names, or the playlist-name
  variant for the playlist view's list pane — only where the offered
  action is prompt-free, since yes_no_query blocks the main loop on the
  pty]; a pl_android_for_each_name wrapper in pl.c/h [struct playlist
  is private]; a `filter` event mirroring the library live filter,
  diffed against a cached copy in android_flush instead of hooked —
  lib_live_filter has writers that bypass every command (the resume
  file restores it at startup, lib_set_filter silently clears it on
  filters-view activation), so the volume event's diff-in-flush
  pattern covers them all with no hunks outside android.c; a
  `colorscheme` event from cmd_colorscheme's success path only
  (transient like `selected` — cmus doesn't retain the name, the colors
  ride the options echo; the app's theme-selector highlight); and
  android-nav-left/right + android-pl-add/delete + android-winch
  app-intent input lines resolved inside android.c [pane-aware
  joystick navigation; verbatim-name playlist add/delete; a tty-size
  re-check the app sends after every pty resize — a SIGWINCH can beat
  cmus's handler install or its select entry, so update_size() sets
  the flag and the line itself is the wakeup]. player_pos_exact
  *trylocks* the player mutexes, falling back to the whole-second
  snapshot position: it runs in the main loop every flush, and at a
  track boundary the consumer thread holds consumer_mutex across its
  blocking get_next callback that only the main loop can answer — a
  blocking lock there deadlocked the whole player (stage-16 fix).
  Everything guarded by CONFIG_ANDROID so the patched tree
  still builds with the upstream Makefile), 0002 removes remote-stream
  support (input.c remote machinery behind `#ifndef CONFIG_ANDROID`,
  cmus_detect_ft's http branch out) so http.c drops from the link. The
  protocol comment atop android.c is the contract the Java client codes
  against. Amending an existing patch = fixup commit in the submodule +
  `git rebase --autosquash base`, then ./patch.sh regen.
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
- `native/cmus/`: cmus core (the Makefile's cmus-y set, mpris off,
  http.c out, + the patch's android.c; `CONFIG_ANDROID` set as a plain
  compile definition on the core only) as an
  executable named `libcmus.so` — ENABLE_EXPORTS so plugins resolve core
  symbols from it at dlopen — parked in CMAKE_LIBRARY_OUTPUT_DIRECTORY,
  which AGP packages as-is; 9 ip plugins (flac vorbis opus mad wavpack
  aac mp4 wav cue) + op/aaudio as `libcmus_{ip,op}_*.so` SHARED libs
  with codecs linked statically and `-z undefs` overriding the NDK's
  `--no-undefined`. The config/*.h that upstream `./configure` would
  emit are generated at CMake configure time in the same format
  (values: bionic + our deps; DEBUG=1; rtsched off); VERSION = the
  Makefile's `_ver3` fallback + gitlink short sha (resolved from the
  parent repo — the submodule HEAD is the patched tip, whose sha changes
  on every patch regen).
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
  TERMINFO/CMUS_{HOME,LIB_DIR,DATA_DIR} +
  `CMUS_ANDROID_SOCKET=<filesDir>/cmus-android.sock`, the app IPC
  socket — filesDir root so tar exports of cmus-home never pick up
  socket files, + `CMUS_ANDROID_{EXT_FILES,EXT,FILES}`, the pl_env
  base vars — names permanent, most-specific first), and is the
  session's one stable `TerminalSessionClient`, forwarding to the
  attached activity. The spawn is headless (TerminalSession only
  forks in initializeEmulator, so getSession sizes the pty itself
  from the prefs-saved last attached size; a TerminalView attaching
  later just resizes it). Idle-quit: when cmus is not PLAYING and no
  activity is visible for 15 min (constant until stage 17), sends
  `set resume=true` + `quit` — killing only cmus, never the task.
  Session death tears down ipc/mediaControl/FGS and drops the
  session ref so getSession can respawn; the backgrounded activity
  stays in recents and respawns on refocus. onTaskRemoved: playing →
  survive; idle → immediate lossless quit. Sleep timer: an
  elapsedRealtime deadline + postDelayed fire sending
  `player-pause-playback` (pause-only, resume-friendly, a no-op unless
  playing; uptimeMillis timing is safe because active audio holds a
  partial wakelock, and a dozed device wasn't playing — the idle-quit
  stance); after the expiry pause a backgrounded app falls into the
  normal idle-quit. Session death clears the deadline
  (removeCallbacksAndMessages silently drops the fire).
  Owns the `CmusIpc` client (created with the session, closed on
  session exit/destroy): forces `set mouse=true` + `set resume=true`
  + `set pl_env_vars=…` plus an `android-winch` size re-check on every
  (re)connect (an attach can resize the pty before cmus installs its
  WINCH handler; the connect is after init by definition) and logs
  every event at DEBUG. Owns the Material You scheme (it owns the
  emulator and outlives the activity): pushes the MaterialYouTheme
  entries into `mColors.mCurrentColors` on spawn and on
  onConfigurationChanged while active (light/dark + wallpaper changes,
  headless included — palette-push only, no cmus traffic), re-forces
  the constant `set` burst on every connect while active (stale
  autosave after a force-stop), resets palette + pref on any
  Colorscheme echo (a sourced theme replaces the generated one), and
  raises SessionCallback.onPaletteChanged so the activity repaints the
  terminal and re-resolves chrome when ARGB moves under unchanged
  indexes. Policy (Patrick): overriding cmus settings that core wrapper
  functionality depends on is desired — force them over the socket on
  (re)connect (never touch user config files; autosave persisting the
  forced value is fine). resume makes every quit lossless (track,
  position, play state, view — even app-process death: pty SIGHUP is
  a clean cmus exit, though force-stop's uid SIGKILL still loses
  since-last-save state until the planned periodic-save patch);
  pl_env makes saved library/cache paths portable across
  reinstalls/storage moves. Owns a `MediaControl` beside it
  (registered as a second IPC listener, closed the same way); the
  FGS notification is MediaControl's media notification. Session
  exit → stopSelf + finish the activity.
- `CmusIpc` — client for the android.c socket (the protocol comment
  there is the contract): sealed `Event` records
  (Hello/Status/Position/Volume/View/Filter/Options + transient Selected
  and Colorscheme — right-click resolutions and sourced-theme names,
  not cached/replayed) parsed with JsonReader
  (JSONObject drops duplicate tag keys), listener callbacks on the
  main thread with cached-state replay for late attachers, `send()`
  for raw command lines (rejects newline/overlong), self-reconnecting
  (100ms → 1s; every connect gets a full snapshot, so no state
  crosses connections). Positions are fractional seconds (double);
  position events still tick at whole-second changes (≤1/s).
- `CmusTheme` — chrome-relevant color_* options resolved to ARGB (record
  built from an Options event + the live palette; equality = change
  detection, including palette pushes moving ARGB under unchanged
  indexes). Value grammar is options.c get_color ("default" | 16 names |
  bare 16-255); resolution goes through the live emulator palette
  (`mColors.mCurrentColors`) — cmus never sends OSC 4/10/11, but since
  stage 16 the *app* rewrites entries in place of one (Material You), so
  the static `TerminalColors.COLOR_SCHEME` is only the no-emulator
  fallback — with "default" → the terminal default-fg/bg indices per
  role.
- `MaterialYouTheme` — the generated Material You colorscheme: palette
  entries 16–42 get exact dynamic-color ARGB (no xterm-cube
  quantization) and the 27 color_* roles point at them via a *constant*
  `set` burst (`commands()`), so a light/dark or wallpaper change is a
  palette re-push with zero cmus traffic — the autosaved indexes don't
  move. Role structure mirrors gruvbox-warm (hard-contrast bg, one band
  tone for titles/selections with the statusline a half-mix darker and
  the top bar darker still, one shared title accent, and a
  *complemented* status accent — the five system ramps are all
  harmonized near the seed hue, so a different hue is synthesized by
  180° HSV rotation; list hierarchy playing &gt; selected &gt;
  half-desaturated unselected). Direct color-setting is reserved for
  generated schemes; file-based themes go through `colorscheme`.
- `MediaControl` — framework MediaSession + MediaStyle notification +
  cover art + audio focus, a pure mirror of CmusIpc events (13+
  renders system controls from PlaybackState actions + metadata; the
  seekbar comes from DURATION + ACTION_SEEK_TO and the system
  extrapolates position itself — Position events are only a seek
  detector, >2s off the extrapolation). Controls map to commands:
  onPlay = `player-pause` toggle when paused (player-play *restarts*
  a loaded track) / `player-play` from stopped, onPause/focus-loss/
  becoming-noisy = `player-pause-playback`, next/prev/seek/stop
  likewise. Art: MediaMetadataRetriever embedded → folder
  {cover,folder,front,album}.{jpg,jpeg,png} (per-dir cache, ogg has
  no framework art support and wv none at all) on an executor,
  ≤640px, stale-track guard. Focus: request on PLAYING (attributes
  match op/aaudio: USAGE_MEDIA/CONTENT_TYPE_MUSIC), abandon on
  STOPPED, hold across PAUSED, transient-loss resume flag; denied or
  taken → never counter the TUI user.
- `MediaButtonReceiver` — media-key resurrection: registered as the
  session's setMediaButtonBroadcastReceiver fallback, so once the
  session is gone a play/play-pause/headset key restarts the FGS
  headlessly and unpauses the resume-restored track. Gated by the
  `resurrect` pref (true on spawn, false on a foreground TUI quit) —
  the system-side registration can't be cleared (null NPEs
  server-side on Android 16, archived registrations linger).
- `CmusDebugReceiver` — FLAG_DEBUGGABLE-gated broadcast receiver
  forwarding `-e cmd <cmus command>` from (root) adb through
  `CmusIpc.send`; permanent debug tool.
- `CmusFiles` — idempotent per-spawn filesDir layout: extracts the
  terminfo + cmus-data assets (stamped by versionCode + APK install
  time), rebuilds `cmus-lib/{ip,op}/NAME.so` symlinks into
  nativeLibraryDir, creates `cmus-home/`.
- `MainActivity` — vertical LinearLayout: top bar over a
  FrameLayout-wrapped `TerminalView` (focusableInTouchMode — required
  for IME/keys; set in code, easy to miss; sizes from raw bounds, so
  insets pad the wrappers; a `JoyDot` overlays its bottom-right) over
  a `ControlBar` over a `KeyRow` (GONE
  unless the IME is visible *and* the terminal owns it — its keys
  inject terminal sequences, so it hides while the filter box has
  focus; sticky modifier state cleared on hide). The top bar is the
  view-selector tab bar flanked by a quick-filter icon (left) and the
  sleep-timer slot (right); the tab scroller is the bar's intrinsic
  height (wrap_content — a weighted match_parent child would be forced
  to the icon squares' height, clipping the tabs). IME transitions are
  handled asymmetrically around the animation (one layout pass, one
  pty resize, no flicker): a hide is optimistic (imeVisible cleared at
  request time, IME inset tracked apart from bar insets, so the layout
  expands under the departing keyboard — also what stops the key row
  flashing in when the box loses focus), while a show lays out fully
  at animation start but translates the bottom chrome down and rides
  it up on the keyboard's animated edge (WindowInsetsAnimation
  onProgress; the vacated band wears the root's cmdline bg). Every pty
  grid change (onEmulatorSet) sends `android-winch` so cmus's redraw
  is deterministic (dropped harmlessly pre-connect; the connect-time
  nudge covers that).
  Quick filter: the search icon morphs the bar — tabs + sleep slot
  swap for a monospace EditText (tab-band height, no Material
  min-height) + ✕ — driving `live-filter` per keystroke (verbatim
  rest-of-line, blank = clear; trimmed). Opening from a non-library
  view sends `view tree`. The box prefills from the cached Filter echo
  and ignores echoes while open (authoritative mid-edit, the mid-drag
  slider rule); the icon's tint is the always-on indicator (full fg =
  filter applied — resume-restored filters light it at launch, a
  filters-view clear dims it). ✕ clears and collapses; tapping the
  icon again collapses keeping the filter; IME search action hands
  focus back to the terminal; closed on onStop/crash (the filter
  itself is cmus state). Sleep slot: bedtime icon when off,
  minutes-left text when armed (ticked on the deadline's minute
  boundary while visible, refreshed by Status events so the expiry
  pause reverts it promptly); tap = preset list (15–90 min) +
  Custom… + Turn off; the service owns the countdown.
  Settings icon (faint, rightmost): tap = popover (Theme / Font /
  Settings — the last toasts until stage 17), long-press = theme
  selector directly. The selectors are centered scrollable list popups
  over the TUI (win colors, separator frame, no scrim, ~60% height cap,
  closed on onStop/crash), sharing one PopupWindow slot + a refresh
  lambda that re-tints rows when the selection moves. Theme column:
  Material You pinned first (service state), then the sorted union of
  cmus-home/cmus-data theme files (whitespace names skipped —
  `colorscheme` takes one arg); picks send `colorscheme <name>` and the
  highlight follows the echoed Colorscheme event (name persisted in a
  pref). Font column: System + the five bundled fonts (assets/fonts,
  Iosevka the default; pref stores "" for an explicit System pick);
  picks apply + persist immediately — setTypeface rebuilds the renderer
  through the pinch-zoom resize path, and the active typeface threads
  through every chrome text site and the ControlBar metrics statics
  (the flushness mirror measures what the renderer measures). Tabs are
  bold at ~1.15× the terminal font (the filter box too), the active tab
  scrolls fully into view on the View echo, and the filters/settings
  tabs are hidden unless active.
  Long-press = right-click (BUTTON3 press+release at the pressed cell;
  always consumed, which is also what keeps the lib's text-selection
  mode permanently unreachable): cmus moves its selection natively and
  answers with a Selected event when the click resolved to something
  actionable — inside an 800ms post-press window that opens the item
  dialog (files list; Remove → win-remove re-checked against the
  echoed view / Add to playlist → chooser of the event's playlist
  names + New playlist… → pl-create + android-pl-add / Cancel), or
  the remove-playlist dialog for the playlist-name variant
  (android-pl-delete). The dialog is dismissed on stop/crash. The
  pinch-zoomed font size persists in prefs (restored clamped on
  create, so reopening matches the saved headless pty grid).
  Insets split for edge-to-edge coloring
  (targetSdk 36: setStatusBarColor is a no-op): the tab bar consumes
  top+sides so its background (win_title_bg) paints the status-bar
  strip; the wrapper consumes sides only; the control bar consumes
  sides+bottom+ime so its background (cmdline_bg — it adjoins the
  cmdline row) paints the nav strip; icon appearance via
  setSystemBarsAppearance by the adjacent bar's bg luminance. A root layout listener additionally absorbs the
  terminal's row-quantization remainder
  (`(wrapperH − firstRowOffset) % lineSpacing`, the TerminalRenderer
  formulas mirrored in ControlBar statics) as padding split between
  the two bars on their terminal-adjoining edges, so chrome sits
  flush with the TUI's top/bottom rows at any font size; a thin
  titleStrip View overlaid on the terminal's top firstRowOffset px
  (win_title_bg, touches fall through) covers the band the renderer
  never paints (row backgrounds start at mFontLineSpacingAndAscent). Everything
  re-tints on Options events through a CmusTheme (until the first
  one: the black Theme.Cmus). Tab bar: text-only view_names in
  monospace at the terminal font size (tracks pinch-zoom), in a
  scrollbar-less HorizontalScrollView — fillViewport + row gravity
  center the tabs when they fit, overflow lays out left and scrolls
  (a CENTER layout gravity would strand overflow off the unreachable
  left). Active tab = win_title_fg, inactive 55% alpha; tap sends
  `view <name>`, the highlight moves only on the echoed View event
  (cmus is the source of truth — TUI 1-7 keys and resume land the
  same way). The IPC listener re-registers per CmusIpc instance
  (attachIpc after every getSession; respawn = fresh instance, replay
  covers late attach); Status/Position/Volume/Options forward to the
  control bar. Tap toggles the IME, pinch scales the font (5–36dp),
  back backgrounds the app (playback continues under the FGS);
  rotation recreates the activity and re-attaches the live session.
  Reports onStart/onStop to the service (idle-quit + size saving);
  onStart re-attaches or respawns a dead session. Session death while
  visible: exit 0 → finish, nonzero → frozen terminal + the emulator's
  "[Process completed]" banner + toast, tap or Enter closes; while
  backgrounded: stays in recents for the respawn.
- `ControlBar` — compact bottom control bar (play/pause · repeat ·
  shuffle · seek · volume · add-to-queue · keyboard toggle), Material
  Symbols icons, cmdline bg + statusline fg, sized 3 terminal rows (icons 2)
  from the same Paint metrics TerminalRenderer uses, tracking
  pinch-zoom. Pure mirror of echoed cmus state: repeat/tristate
  shuffle (albums = badge icon)/softvol from Options (TUI toggles
  arrive via the run_parsed_command options hook), play state from
  Status with MediaControl's command mapping (player-pause-playback /
  player-pause / player-play), volume from Volume events. The seek
  thumb extrapolates between the ~1/s fractional position events with
  a per-frame ticker while PLAYING (runs only attached+visible);
  drag-release sends `seek n` and rebases locally to skip the echo
  round trip. Volume button exists only while softvol=true and opens
  a PopupWindow with a vertical slider (`vol n` per integer step).
  The queue button is three-state on the echoed View: win-add-q
  normally, win-remove (remove icon) in the queue, and + = win-add-l
  (the browser's `a` binding) in the browser.
  `CmusSlider` is the shared flat one-color slider (track/fill/block
  thumb, horizontal or vertical, float progress, redraws only on
  ≥0.5px thumb movement, ignores external updates mid-drag).
- `KeyRow` — extension key row shown while the IME is visible, sitting
  directly atop it (below the control bar, so the terminal-flushness
  padding math is untouched): monospace text keys at the terminal font
  size in four gap-separated groups — shift/ctrl/alt · del/esc/tab ·
  ←↓↑→ · home/end/pgup/pgdn — in control-bar colors (cmdline bg,
  statusline fg), the tab bar's centered-or-scroll overflow pattern.
  Keys inject via `TerminalView.onKeyDown` (the termux extra-keys
  path; KeyHandler emits appMode sequences, which match terminfo since
  cmus's keypad() sends smkx). Sticky modifiers termux-style: tap =
  one-shot (consumed on read), long-press = locked (inverted block,
  underlined when locked); MainActivity's readShift/Control/AltKey
  delegate to the row, and TerminalView merges them into injected keys
  and IME-typed characters alike. Arrows/page keys auto-repeat on
  hold.
- `JoyDot` — faint minecraft-style joystick over the terminal's
  bottom-right (120dp view, center 80/90dp in from the corner; only
  touches starting within 39dp of center are grabbed, the rest fall
  through; knob follows the finger to 44dp; win_fg at low alpha via
  applyTheme; hidden on the crash screen). Tap = enter; slide up/down
  past 15dp = repeating arrows (300→75ms, scaled by displacement to
  60dp); slide far left/right past 40dp (30dp hysteresis) =
  directional nav repeating 750→250ms — sends android-nav-left/right
  over IPC, which cmus resolves pane-aware (win-next toward the inner
  pane in tree/playlist, left/right-view -n at the edges and in
  single-pane views, empty track pane skipped). Keys inject through
  MainActivity's shared injectKey, so KeyRow's sticky modifiers merge
  here too; arrows pause while navigating.
- Theme: `Theme.Cmus` (Material NoActionBar, black, short-edges cutout)
  as the pre-first-Options fallback; live chrome colors come from
  CmusTheme above.

## Build requirements

- Android SDK at `~/sdk/android` (`local.properties`, gitignored) with
  ndk;28.2.13676358 + cmake;3.30.5 installed, host JDK 25, network for
  gradle/AGP resolution.
- `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

## Coming next (see overview stages)

Settings screen (17), then data import/export (18), polish/verify (19).
