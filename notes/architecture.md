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
├── patches/          per-submodule patch dirs (cmus: 4)
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
  ride the options echo; the app's theme-selector highlight); a `jobs`
  event (worker_has_job diffed in the flush — reliable because job_fd
  is in the main-loop select set, so job activity is its own wakeup —
  plus an immediate answer to the `android-jobs` poll line, the
  idle-quit guard's fresh check); a `dirty` event announcing unsaved
  state-file changes per kind (library/cache/playlist/queue/history —
  per-kind mutation counters bumped from hooks in editable.c/lib.c/
  pl.c/cache.c/history.c, atomic since the cache mutates on the
  worker; a kind announces when its counter differs from both the
  last-announced value and the one snapshotted *before* the last
  save's writes, so mid-save mutations re-announce and each kind
  emits once per save cycle; re-announced in the connect snapshot,
  emitted before the jobs diff so a client arms its debounce before
  a completion edge; the startup loaders deliberately don't bump the
  cache); the `android-save [kind…]` line (granular subsets of the
  exit save set; bare = everything) acked by a `saved` event carrying
  `what`, with the cache written under cache_lock — the worker is
  live here, unlike the exit path (stage-19 fix); and
  android-nav-left/right + android-selected + android-pl-add/delete +
  android-winch app-intent input lines resolved inside android.c
  [pane-aware joystick navigation; a coordinate-free `selected` event
  for the *current* selection (stage 21, the floating-joystick /
  mouse=false path — no button report can reach a non-tracking cmus),
  reusing android_selected_event so it emits nothing exactly where the
  right-click did; verbatim-name playlist add/delete; a tty-size
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
  cmus_detect_ft's http branch out) so http.c drops from the link,
  0003 (upstream candidate) fixes op/aaudio's sharing_mode getter,
  0004 (upstream candidate, un-gated) makes do_cmus_save atomic —
  write `<filename>.tmp` + rename like every other state writer
  (lib.pl/queue.pl/playlists were the only in-place O_TRUNC writers),
  with pl_load_all skipping `*.tmp` leftovers and .tmp-suffixed
  playlist names rejected (saving playlist X writes X.tmp, which
  would clobber a playlist named X.tmp), 0005 (Android-only, gated on
  CONFIG_STATIC_PLUGINS) links every input/output plugin into the cmus
  binary instead of dlopen()ing them: each plugin's fixed ABI symbols
  (ip_ops/ip_priority/… — identical across plugins) are renamed to
  `<name>_ip_ops` etc. by the build so they stop colliding, and a new
  static_plugins.c gathers them into static_{ip,op}_plugins[] tables that
  ip_load_plugins/op_load_plugins walk in place of the CMUS_LIB_DIR scan
  (the upstream Makefile leaves the macro unset and keeps the dlopen
  path). The rest are app-hardening/cleanup, all gated on CONFIG_ANDROID so
  the upstream Makefile is unaffected: 0006 drops the built-in cmus-remote
  socket server (every server_* reference in ui_curses.c behind #ifndef
  CONFIG_ANDROID — the select set, server_init/exit, the --listen flag and
  its default socket path) so server.c leaves the link like http.c did —
  the app has its own IPC socket; 0007 routes debug output to logcat (tag
  `cmus`): d_print at debug level, gated by a flag debug_init() reads once
  from CMUS_ANDROID_DEBUG_LOG so early logs get through, and _debug_bug at
  error level always; 0008 pins the file browser's default dir: browser_init()
  reads CMUS_ANDROID_BROWSER_DIR and resume_load() skips restoring the saved
  browser-dir so the app-chosen dir wins every launch (the app sets the music
  folder, or the storage root when all-files access is granted); 0009 drops
  the only spawn() callers — the run/shell commands + their table rows and the
  status_display_program option/invocations — so cmus runs no external
  processes (spawn.c is left pristine for the upstream Makefile but the app
  build no longer compiles it); 0010 drops CD-audio (no cdio plugin is built,
  so cdda:// can never play): the FILE_TYPE_CDDA detect branch, the
  cdda_device option, job.c's add_cdda + dispatch, and input.c's cdda open
  path, which removes the last discid.c references so it too leaves the build;
  0011 makes option_set accept-and-ignore the specific removed option names
  (device, status_display_program) so an autosave/rc from an older build
  doesn't error on their stale `set` lines (other unknowns still error). The
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
- `native/cmus/`: cmus core (the Makefile's cmus-y set, mpris off, and
  http.c/server.c/spawn.c/discid.c out — server by 0006, the last two by
  0009/0010 which strip their callers, + the patch's android.c;
  `CONFIG_ANDROID` set as a plain compile definition on the core only; links
  `-llog` for the logcat debug output of patch 0007) as an
  executable named `libcmus.so`, parked in CMAKE_LIBRARY_OUTPUT_DIRECTORY,
  which AGP packages as-is; the 9 ip plugins (flac vorbis opus mad wavpack
  aac mp4 wav cue) + op/aaudio are compiled straight into that binary
  (patch 0005 / CONFIG_STATIC_PLUGINS) as per-plugin OBJECT libs whose
  fixed ABI symbols are `-D`-renamed to `<name>_ip_ops` etc. to avoid
  collisions, with the codec static libs linked onto the object lib (for
  their header dirs) and onto cmus (for the link) — so there's a single
  `libcmus.so`, no `libcmus_{ip,op}_*.so`, and no ENABLE_EXPORTS/`-z
  undefs`/dlopen. The config/*.h that upstream `./configure` would
  emit are generated at CMake configure time in the same format
  (values: bionic + our deps; DEBUG=1; rtsched off); VERSION = the
  Makefile's `_ver3` fallback + gitlink short sha (resolved from the
  parent repo — the submodule HEAD is the patched tip, whose sha changes
  on every patch regen).
- Static libs land under `app/.cxx/` only. The APK carries the single
  `libcmus.so` + termux's libtermux.so (extracted at install:
  `useLegacyPackaging` so the exec can be spawned by path), the
  `assets/terminfo/x/xterm-256color` compiled by ncurses' gen.sh with
  host tic, and `assets/cmus-data/*` (rc + 17 themes) copied by a
  gradle task from the pristine cmus submodule.

## App module

- `net.pgaskin.cmus.android`, minSdk 34 (Android 14), target/compileSdk 36.
- Framework APIs + the termux terminal libs; androidx allowed where it
  makes sense (currently only androidx.annotation, transitively).
- `CmusService` — mediaPlayback foreground service owning the cmus
  `TerminalSession`: runs `CmusFiles.prepare`, spawns
  `nativeLibraryDir/libcmus.so` in a pty (env: HOME/TMPDIR/TERM/
  TERMINFO/CMUS_{HOME,DATA_DIR} pointing into the
  `filesDir/.cmus/` dotfolder (stage 18; CMUS_LIB_DIR dropped with the
  plugin .so files — patch 0005 builds them into the binary) +
  `CMUS_ANDROID_SOCKET=<filesDir>/.cmus/android.sock`, the app IPC
  socket — beside home, not in it, so zip exports of the config never
  pick up socket files, + `CMUS_ANDROID_{EXT_FILES,EXT,FILES}`, the
  pl_env base vars — names permanent, most-specific first, + a conditional
  `CMUS_ANDROID_DEBUG_LOG=1` when the debuggable-only debug-logging toggle
  is on, read once at startup by patch 0007 so a change needs a respawn, +
  `CMUS_ANDROID_BROWSER_DIR` = the storage root when the optional all-files
  access (MANAGE_EXTERNAL_STORAGE, granted from settings) is on else the
  shared Music folder, pinned as the browser default every launch by 0009;
  cmus reads these dirs directly — MediaProvider's FUSE mount honors the
  app's READ_MEDIA_AUDIO per-process, so no MediaStore is needed — while
  import still targets only Music),
  and is the
  session's one stable `TerminalSessionClient`, forwarding to the
  attached activity. The spawn is headless (TerminalSession only
  forks in initializeEmulator, so getSession sizes the pty itself
  from the prefs-saved last attached size; a TerminalView attaching
  later just resizes it). Idle-quit: when cmus is not PLAYING and no
  activity is visible (a *count* — Main/Settings transitions overlap)
  for the pref'd minutes (default 15, 0 = off; stage 18), sends
  `set resume=true` + `quit` — killing only cmus, never the task. The
  fire never quits mid-import (it would truncate the scan): it asks
  cmus about worker jobs first (`android-jobs`, answered immediately)
  and re-polls every 30s while one runs; the pipeline stays armed
  through the poll/defer cycles, every async step re-checks the idle
  conditions, and cancel paths clear the pending flag.
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
  (removeCallbacksAndMessages silently drops the fire). The stage-18
  sleep-timer-action pref flips the fire to a full exit instead:
  clear `resurrect` (a pocketed BT play key must not undo the sleep),
  `set resume=true` + `quit` — the normal session-death teardown, a
  visible activity finishing through its usual path.
  Owns the `CmusIpc` client (created with the session, closed on
  session exit/destroy): forces `set mouse=<Direct-touch-input pref>`
  (default true; stage 21) + `set resume=true`
  + `set pl_env_vars=…` plus an `android-winch` size re-check on every
  (re)connect (an attach can resize the pty before cmus installs its
  WINCH handler; the connect is after init by definition), then
  forces the `CmusSettings`-managed options, and logs every event —
  at INFO behind the stage-18 debug toggle (default on in debuggable
  builds only), so steady-state logcat stays quiet. Owns the Material You scheme — the *default* theme since stage 18
  (an explicit colorscheme pick stores the pref false, so existing
  picks stand) — (it owns the emulator and outlives the activity): pushes the MaterialYouTheme
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
  a clean cmus exit; exits that skip every save path — force-stop's
  uid SIGKILL, battery death — are bounded by `StateSaver`'s
  continuous saves since stage 19);
  pl_env makes saved library/cache paths portable across
  reinstalls/storage moves. Owns a `MediaControl` beside it
  (registered as a second IPC listener, closed the same way); the
  FGS notification is MediaControl's media notification. Owns a
  `StateSaver` the same way (third listener, per-CmusIpc lifecycle);
  CmusService.saveState (the export/pre-reset bounded save) delegates
  into its FIFO ack queue. Session
  exit → stopSelf + finish the activity.
- `CmusIpc` — client for the android.c socket (the protocol comment
  there is the contract): sealed `Event` records
  (Hello/Status/Position/Volume/View/Filter/Options/Jobs + transient
  Selected, Colorscheme, Dirty and Saved — right-click resolutions,
  sourced-theme names, unsaved-change announcements and the
  android-save ack with its `what` echo, not cached/replayed — Dirty
  needs no replay because the connect snapshot re-announces) parsed with JsonReader
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
  HSV rotation — 180° complement by default, the degrees are a
  stage-18 setting re-pushed live; list hierarchy playing &gt; selected &gt;
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
  likewise. Art chain (on an executor, ≤640px, stale-track guard):
  MediaMetadataRetriever embedded → `OggCover` (ogg/opus
  METADATA_BLOCK_PICTURE, stage 20 — the framework ignores it and cmus
  filters it out of its comment allowlist, so neither the retriever nor
  the IPC tags carry it; OggCover reassembles the Ogg comment packet
  across pages + parses the FLAC picture block) → folder
  {cover,folder,front,album}.{jpg,jpeg,png} (per-dir cache; wv has no
  framework art support at all). Focus: request on PLAYING (attributes
  match op/aaudio: USAGE_MEDIA/CONTENT_TYPE_MUSIC), abandon on
  STOPPED, hold across PAUSED, transient-loss resume flag; denied or
  taken → never counter the TUI user.
- `StateSaver` — debounced continuous state saves (stage 19; cadences
  and buckets are Patrick's, in the class comment and the plan):
  playlist+queue/history/settings 5s, library+cache/resume 15s, full
  save at playback boundaries ≥15 min apart. Content kinds trigger off
  cmus's Dirty events (the only view of TUI-side edits; edge-triggered
  per save cycle, so timers are arm-on-first-change with bounded
  staleness — a continuous edit stream can't postpone a save forever);
  settings off an app-side Options-map diff (the echo fires per
  command, changed or not) + Volume changes (softvol_state); resume
  off Status transitions only, never position ticks. Playlist/queue/
  library/cache buckets defer while a worker job runs and the jobs
  false edge flushes them — a save mid-restore writes a *partial*
  file over a complete one (memory fills from the file; a force-stop
  then persists the truncation), whereas mid-import saves are
  consistent supersets, so the boundary full save doesn't defer (and
  its 15-min clock starts at spawn, clear of the restore window).
  Every android-save funnels through its FIFO ack queue (one socket,
  ordered replies): saveNow's bounded waits get their own ack, never
  a stray periodic one. Main-thread only; closed with its CmusIpc
  (disconnect clears queue+timers, the snapshot re-announcement
  rebuilds them). Known accepted churn: the startup restore dirties
  library/queue/playlist, one consolidated small-file save per spawn
  at the restore's jobs edge (the loaders never bump the cache kind,
  keeping the multi-MB write out).
- `MediaButtonReceiver` — media-key resurrection: registered as the
  session's setMediaButtonBroadcastReceiver fallback, so once the
  session is gone a play/play-pause/headset key restarts the FGS
  headlessly and unpauses the resume-restored track. Gated by the
  `resurrect` pref (true on spawn, false on a foreground TUI quit) —
  the system-side registration can't be cleared (null NPEs
  server-side on Android 16, archived registrations linger).
- `CmusDebugReceiver` — broadcast receiver forwarding
  `-e cmd <cmus command>` from (root) adb through `CmusIpc.send`,
  gated by the stage-18 debug-settings toggle (default on only in
  debuggable builds, like the IPC-log toggle — release ships with
  both off; the settings row shows the example command).
- `CmusFiles` — idempotent per-spawn layout under `filesDir/.cmus/`
  ({terminfo,data,home,assets.stamp,android.sock} — a dotfolder so
  the file browser at $HOME = filesDir shows a clean home unless
  show_hidden; stage 18): extracts the terminfo + cmus-data assets
  (stamped by versionCode + APK install time), creates
  `home/` (plugins are built into libcmus.so, so there's no lib/ tree to
  stage); migrates pre-18 installs (old cmus-home renamed in — state
  kept, pl_env prefixes don't move — the regenerated trees deleted).
- `CmusSettings` — the app-managed cmus options (Patrick's curated
  settings-screen set) in a `cmus_opts` prefs file keyed by option
  name: every stored key is re-forced with `set` on each (re)connect
  (prefs override cmus — autosave loads first, prefs win) and every
  Options echo is synced back idempotently (TUI `:set` persists
  across the force-stop loss window; our own echoes don't loop).
  `progress_bar` is the exception: app-managed with an `auto` value
  (control bar visible → disabled — its slider replaces cmus's line —
  hidden → line), never synced back so auto survives its own writes.
- `SettingsActivity` — stage 18: app / audio / cmus / data / debug
  sections, stock Material day/night (values-night parent swap; muted
  blue-grey accent, deliberately not cmus-themed — Patrick), launched
  from the popover. Hand-rolled rows; the cmus rows render only from
  the replayed/live Options echoes and taps only send `set` (bad
  input snaps back on the next echo); app rows write prefs applied by
  MainActivity.onStart on return. Binds CmusService with its own IPC
  listener (re-attached after resets — respawn = fresh CmusIpc) and
  reports visibility into the service's count. Data section
  (troubleshooting, file-level only): zip export (`android-save` →
  Saved ack, bounded 5s, then CMUS_HOME zipped on a worker thread,
  cmus's server socket skipped) / zip import (home cleared first —
  restore replaces, never merges; zip-slip guarded) / delete
  library|playlists|autosave|everything / reset app prefs (clears
  term + cmus_opts; RESULT_RESET_PREFS → MainActivity recreates),
  all through CmusService.resetData = kill→mutate→respawn (SIGKILL
  deliberately — no exit save can rewrite a delete, a wedged cmus
  can't block; file op on a worker thread; the respawn keeps the
  FGS), the partial deletes behind a default-on "save current state
  first" checkbox (bounded android-save — SIGKILL would otherwise
  lose everything else unsaved). Deleting autosave resets cmus state
  like softvol volume to defaults (softvol_state 0) — inherent.
  Backup: `dataExtractionRules` scopes Auto Backup/device-transfer to
  the shared prefs + `.cmus/home/{lib.pl,playlists,search-history,
  command-history,autosave,cache}` (resume/queue.pl deliberately out:
  a restored install starts stopped; pl_env keeps restored paths
  valid, the cmus_opts force re-applies managed settings).
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
  itself is cmus state). Sleep slot: empty when off, minutes-left text
  when armed (ticked on the deadline's minute boundary while visible,
  refreshed by Status events so the expiry pause reverts it promptly);
  the timer is armed from the settings menu now (no icon), and tapping
  the countdown reopens the dialog = preset list (15–90 min) + Custom…
  + Turn off; the service owns the countdown.
  Settings icon (faint, rightmost): tap = popover (Theme / Font /
  Import / Update cache / Sleep timer / Settings, + Keyboard while the
  bottom bar is hidden — its IME toggle went with it), long-press =
  theme selector directly. Update cache sends plain `update-cache` (no -f: changed
  files and skip_track_info entries refresh, the whole library isn't
  re-read); any worker job's true→false edge toasts "Library update
  finished".
  Stage-18 visibility toggles: top bar / bottom bar / joystick prefs
  re-applied on every onStart (returning from settings) — hidden bars
  hand their insets to the terminal wrapper, the row-quantization
  remainder goes to whichever bars remain, and a faint fg-tinted
  overlay row (the sleep slot's minutes text when armed, plus the
  settings button anchoring the same popover) floats over the terminal's
  top-right whenever the top bar is hidden (hidden with the joystick
  on the crash screen). Zoom (the font pref) is shared by
  pinch and the settings slider through one applyFontSize. Import
  (stage 17's Refresh, renamed in 18): READ_MEDIA_AUDIO
  (runtime request resuming the action on grant) → "Adding tracks from
  Music folder" toast → `add` of the shared Music dir — cmus's own
  recursive add job imports, re-taps dedupe (library keyed by
  filename). The diffed Jobs event's true→false edge toasts "Import
  finished" for *any* import, whatever triggered it. The selectors are centered scrollable list popups
  over the TUI (win colors, separator frame, no scrim, ~60% height cap,
  closed on onStop/crash), sharing one PopupWindow slot + a refresh
  lambda that re-tints rows when the selection moves. Theme column:
  Material You pinned first (service state), then the sorted union of
  the home/data theme files (whitespace names skipped —
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
  (the browser's `a` binding) in the browser. The queue/shuffle/repeat
  taps also show a toast of what they did ("Adding to queue" /
  "Removing from queue" / "Adding to library"; the shuffle/repeat ones
  predict the toggle's next state from the echoed one) — a single
  reused Toast, cancelled before re-show so rapid taps read instantly.
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
- `JoyDot` — faint minecraft-style joystick over the terminal (120dp
  view; only touches starting within 39dp of center are grabbed, the
  rest fall through; knob follows the finger to 44dp; win_fg at low
  alpha via applyTheme; hidden on the crash screen). Tap = enter;
  slide up/down past 15dp = repeating arrows (300→75ms, scaled by
  displacement to 60dp); slide far left/right past 40dp (30dp
  hysteresis; engaging also needs a 2:1-horizontal pull, ~27° cone) =
  directional nav repeating 750→250ms — sends android-nav-left/right
  over IPC, which cmus resolves pane-aware (win-next toward the inner
  pane in tree/playlist, left/right-view -n at the edges and in
  single-pane views, empty track pane skipped). Keys inject through
  MainActivity's shared injectKey, so KeyRow's sticky modifiers merge
  here too; arrows pause while navigating. Resting on the center 2s
  vibrates and turns the touch into a reposition drag (JoyDot reports
  raw deltas; MainActivity moves/clamps/persists): center saved as a
  wrapper-fraction per orientation (joy_x/y_port/land, default center
  140/150dp in from the bottom-right corner), re-derived on every
  wrapper resize (rotation, IME), center clamped ≥64dp from the
  wrapper edges while dragging and on restore.
  Floating mode (Direct touch input off + joystick on, stage 21):
  JoyDot fills the wrapper (MATCH_PARENT, placeJoyDot no-ops),
  draws nothing at rest, and is summoned under the finger wherever a
  terminal touch lands — the gesture origin is that press, not the view
  center, so every threshold/knob is measured from it. Tap/slides/nav
  are the same; the rest-in-place hold, which repositions the fixed dot,
  instead fires a right-click at the platform long-press timeout →
  `android-selected` (acts on the joystick-moved selection regardless of
  where the finger landed) → the same 800ms-window onSelected dialog.
  The stick owns the whole terminal there, so a plain tap is Enter, not
  the tap-to-toggle-keyboard (still on the control-bar button / popover),
  and pinch-zoom is unavailable (settings Zoom slider instead); floatBar
  stays above the stick, so the hidden-top-bar buttons still work.
  MainActivity.applyBarVisibility swaps the mode + layout params from the
  pref on attach/onStart, and CmusService.applyMouse pushes cmus `mouse`
  live when the settings switch flips.
- Theme: `Theme.Cmus` (Material NoActionBar, black, short-edges cutout)
  as the pre-first-Options fallback; live chrome colors come from
  CmusTheme above.

## Build requirements

- Android SDK at `~/sdk/android` (`local.properties`, gitignored) with
  ndk;28.2.13676358 + cmake;3.30.5 installed, host JDK 25, network for
  gradle/AGP resolution.
- `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

## Coming next (see overview stages)

Ogg/opus embedded art (20), polish/verify (21). Upstream submissions
pending: 0003 (aaudio sharing_mode getter), 0004 (atomic playlist
saves).
