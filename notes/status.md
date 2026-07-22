# Status log

Newest entries first. One entry per work session/stage; enough context to
pick up where things left off.

## 2026-07-22 — Stage 23 Session B: reopen seam (patch 0004, built)

- Per [plans/23-saf.md](plans/23-saf.md) *Plugin reopen seam* / *Build order
  → Session staging* step 2: the `ip_reopen_path` seam only, its own session.
  A behavior-preserving upstream candidate, no `CONFIG_ANDROID`.
- **New helper `ip_reopen_path(ip_data, sidecar)`** (`input.c`, declared in
  `ip.h`): returns a malloc'd path a plugin should (re)open (caller frees).
  `sidecar == NULL` → reopen the main input; non-NULL `sidecar` → a
  same-directory companion. Returns `NULL` for `is_url()` inputs (remote/
  cdda) — the by-name reopens already failed on those, so it's a no-op.
- **Routed the by-name sites through it**: ffmpeg main open (guarded
  `!ip_data->remote`, so remote URLs still pass straight to
  `avformat_open_input`), mp4 main open, and the mad/aac/wavpack id3 tag
  readers (`sidecar==NULL`). Wavpack's `.wvc` correction file goes through
  the non-NULL `sidecar` form; wavpack *decode* is untouched (already off
  `ip_data->fd`).
- **Inserted as patch 0004, before the Android-IPC patch** — rebased the
  submodule stack (base→0001-0003→**seam**→old 0004-0013) and regenerated.
  Renumbering matches the plan: android.sock 0005, drop-remote 0006, browser
  dir 0011, drop-cdda 0013, accept-removed 0014. `patch.sh check` green.
- **Built clean** via `./gradlew :app:assembleDebug` (exit 0). Device/codec
  runtime verification (the *Verify* list) stays Patrick's, next session.
- Commit: `cmus: route input-plugin reopen through ip_reopen_path seam
  (patch 0004)`. Next: Session C — SAF transport (0015) + `/saf` VFS (0016).

## 2026-07-22 — Stage 23 Session A: SAF prep tasks (app-only, built)

- Per [plans/23-saf.md](plans/23-saf.md) *Prep tasks* / *Build order →
  Session staging*: the independent, app-only cleanups that land before any
  SAF work. No cmus patch, no `patch.sh` touch.
- **"Update cache" → "Refresh metadata"** (`MainActivity`): the popover
  label + its `switch` case, and the `updateCache()` toast ("Updating track
  metadata" → "Refreshing metadata"). Also updated the doc comment and the
  jobs-edge comment that named the old label. Pure rename — still
  `update-cache` = `cache_refresh` under the hood.
- **Explain-on-press dialogs for Import + Refresh metadata**: shared
  `explainThenRun(prefKey, version, title, message, action)` — shows an
  explanatory dialog with a "Don't show this again" checkbox, runs the action
  on Continue, aborts on Cancel. **Version-acked, not boolean** (Patrick):
  `PREF_ACK_IMPORT`/`PREF_ACK_REFRESH` store the highest `MSG_VERSION_*` the
  user dismissed; shown iff `acked < current`, so bumping a message's version
  after editing its copy re-surfaces it once. Both currently version 1.
- **Message copy (Patrick's exact wording):** Import = "New from the Music
  folder will be added to the library. Deleted files will not be removed.";
  Refresh = "Cached tags for files in the library with a changed modification
  time since the last scan will be updated." (Deliberately omits the plan's
  "other directories via the file browser / Settings connections" clause —
  that UI doesn't exist until the SAF sessions; bump `MSG_VERSION_IMPORT` to 2
  and extend the copy when it lands.)
- `compileDebugJavaWithJavac` green. **Not yet device-tested** — the dialog
  should show once per action, checkbox suppresses it thereafter (verify the
  ack persists across restart). Sessions B (reopen seam 0004) and C+ (SAF
  transport/VFS + app) follow.

## 2026-07-19 — UI polish: control-bar toasts + sleep timer to the settings menu (device-confirmed by Patrick)

- **Control-bar toasts** (`ControlBar`): the add/remove, shuffle, and repeat
  taps now show a toast of what they did — "Adding to queue" / "Removing from
  queue" / "Adding to library" (per the echoed view), and shuffle/repeat
  predict the toggle's next state from the current echoed one ("Shuffling
  tracks/albums", "Shuffle off"; "Repeat on/off"). One reused `Toast` field,
  cancelled before each show so rapid taps swap instantly instead of queueing.
- **Sleep timer** (`MainActivity`): dropped the bedtime icon (both top-bar
  `sleepBtn` and float `floatSleepBtn`); the timer is armed from a new "Sleep
  timer" item in the settings popover (→ `showSleepDialog`, same preset/custom/
  off dialog). The countdown text (`sleepText`/`floatSleepText`) still shows
  while armed and reopens the dialog on tap; `updateSleepSlot` now just hides
  the slot when off. `ic_sleep` is left unused.

## 2026-07-19 — More cmus cleanup: drop run/shell/status-program + CD-audio (built + device-tested)

Three CONFIG_ANDROID cmus patches + build changes; `native: more cleanup`.

- Reworked the series: the old 0008 (neuter spawn) was dropped via
  `git rebase --onto` (the `reset --hard` route is blocked by the sandbox) and
  replaced by real removals, so browser shifted 0009→0008.
- **0009** drops the run/shell commands (+ table rows) and the
  status_display_program option/invocations — the only spawn() callers. spawn.c
  stays pristine for the upstream Makefile but is dropped from the app CMake
  build (zero callers). Watch out: there were **two** extra status-program
  call sites beyond the obvious one (`spawn_status_program_inner("exiting",…)`
  at exit, and the `needs_spawn` decl/assign/use trio — gate all of it or you
  get unused-variable/implicit-decl errors).
- **0010** drops CD-audio (no cdio plugin is built): the FILE_TYPE_CDDA detect
  branch, cdda_device option, job.c add_cdda + dispatch case, input.c cdda open
  arm. That removes the last refs to discid.c (complete_cdda_url /
  get_default_cdda_device / parse_cdda_url / gen_cdda_url) so it's dropped from
  the build too.
- **0011** (migration): removing persisted options broke startup — a stale
  autosave still had `set device=/dev/cdrom` and `set status_display_program=`,
  and option_set errored `no such option`, blocking on a press-enter prompt.
  option_set now accept-and-ignores exactly those two removed names (other
  unknowns still error). Verified with the stale lines still present: no error,
  cmus reaches IPC hello, FLAC playback works.
- CMake drops spawn.c + discid.c from the executable.

## 2026-07-19 — File browser defaults to the music dir + optional all-files access (built + device-tested)

- **Patch 0009 (CONFIG_ANDROID)** pins the browser default every launch:
  `browser_init()` takes its dir from `CMUS_ANDROID_BROWSER_DIR` when set, and
  `resume_load()` skips restoring the saved `browser-dir` so the app-chosen dir
  always wins (not just first run). Both files pull `<stdlib.h>` via xmalloc.h,
  so `getenv` needs no new include.
- **App**: `CmusService` sets `CMUS_ANDROID_BROWSER_DIR` at spawn = the storage
  root (`Environment.getExternalStorageDirectory()`) when all-files access is
  granted, else the shared Music folder (`DIRECTORY_MUSIC`). Import
  (`refreshTracks`) is unchanged — still just Music.
- **All-files access**: added `MANAGE_EXTERNAL_STORAGE` to the manifest and an
  "All files access" row in Settings → App (below Music-folder permission) that
  opens the system per-app screen (`ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`)
  and reflects `Environment.isExternalStorageManager()`. Framed as optional:
  reaches non-audio folders and may work around storage quirks on some devices.
- **Why cmus can read external storage without MediaStore**: `/storage/emulated/0`
  is a FUSE mount served by MediaProvider that enforces permission per-app; the
  zygote-forked cmus process, in its own mount namespace, is recognized as the
  READ_MEDIA_AUDIO holder, so audio files and their dirs are readable by path.
  (Confirmed on device: cmus's `mnt` ns ≠ shell's; `/proc/<pid>/root/storage`
  shows the real tree. A `run-as` test gives a false EACCES — wrong namespace.)
  A comment on this lives at the env-var site in CmusService.
- Device-tested (arm64): with a saved `browser-dir` of `filesDir`, relaunch
  forced it to `/storage/emulated/0/Music`; after `appops … MANAGE_EXTERNAL_STORAGE
  allow`, relaunch forced it to `/storage/emulated/0`.

## 2026-07-19 — App hardening: drop cmus socket, logcat debug, neuter spawn (built + device-tested, authored as Claude)

Three CONFIG_ANDROID-gated cmus patches (upstream Makefile unaffected):

- **0006 — drop the cmus-remote socket server.** The app has its own IPC
  socket (0001); the built-in UNIX/TCP server is unused surface. Gated every
  `server_*` use in ui_curses.c behind `#ifndef CONFIG_ANDROID` (the two
  main-loop client loops + `fd_high` seed, `server_init`/`server_exit`, the
  `--listen` flag enum/option/case, the default `cmus_socket_path`), dropped
  `server.c` + its `DEFAULT_PORT` from the CMake build — leaves the link like
  http.c. Device: app restored + played a tagged mp3, no regression.
- **0007 — debug output to logcat (tag `cmus`).** Under CONFIG_ANDROID
  `_debug_print` → `__android_log_print` at debug level, gated by a flag
  `debug_init()` reads once from `CMUS_ANDROID_DEBUG_LOG` (startup-read so
  early logs get through, e.g. `main: charset = 'UTF-8'`); `_debug_bug` →
  error level always, then exit(127). Strips the trailing `\n` d_print
  callers add. cmus links `-llog`. App: `PREF_DEBUG_LOG` → conditional
  `CMUS_ANDROID_DEBUG_LOG=1` env at spawn; a **debuggable-only** settings
  toggle (Debug section), **default off even in debug builds** (verbose),
  subtitle notes it applies on the next app restart. Device: toggled on via
  prefs, restart → `D cmus` lines with function prefixes streamed; off (the
  baseline) → none.
- **0008 — neuter spawn().** `spawn()` returns `EPERM` instead of
  `fork()`+`execvp()` — no external processes (status display programs, the
  run/shell commands); all three callers already handle `-1`. Build-verified.

## 2026-07-19 — Single libcmus.so: plugins linked into the binary (built + device-tested, authored as Claude)

- Goal: one `libcmus.so` with every input/output plugin linked in, instead of
  the core executable + 10 `libcmus_{ip,op}_*.so` dlopen()ed at runtime.
- Blocker was symbol collision: every ip plugin exports the same globals
  (`ip_ops`/`ip_priority`/`ip_extensions`/`ip_mime_types`/`ip_options`/
  `ip_abi_version`), every op plugin the same `op_*` set. Plugin internals are
  otherwise all `static` (audited), and `nomad_*` is unique to mad — so the six
  ABI names are the only clash.
- New cmus patch `0005` (Android-only, gated on `CONFIG_STATIC_PLUGINS`; the
  upstream Makefile leaves the macro unset and keeps the dlopen path):
  - `static_plugins.{c,h}`: `static_{ip,op}_plugins[]` tables of function/name
    pointers, built from the renamed ABI symbols via extern decls + macros.
  - `input.c`/`output.c`: `ip_load_plugins`/`op_load_plugins` grow a
    `#ifdef CONFIG_STATIC_PLUGINS` branch that walks the tables (setting
    `plugin_dir = "built-in"` for the dumps) instead of scanning
    `CMUS_LIB_DIR/{ip,op}/*.so`. No dlopen/dlsym/dlclose on this path;
    `op_exit_plugins` never dlclose()d, so nothing else changed.
- Build (`native/cmus/CMakeLists.txt`): each plugin is now a per-plugin OBJECT
  lib whose fixed ABI symbols are `-D`-renamed to `<name>_ip_ops` etc.
  (`cmake_parse`-free: `cmus_plugin(kind name "libs" sources…)`); codec static
  libs link onto the object lib (header dirs) and onto `cmus` (final link);
  `$<TARGET_OBJECTS>` folds them into the executable. Dropped ENABLE_EXPORTS
  and `-z undefs`; added `static_plugins.c` + `CONFIG_STATIC_PLUGINS` to cmus.
- App: `CmusFiles.linkPlugins` (the `.cmus/lib/{ip,op}/NAME.so` symlink tree)
  and `lib()` deleted — it would now always throw ("no ip plugins", since there
  are no `libcmus_ip_*.so` to link); `CMUS_LIB_DIR` env dropped from
  `CmusService`; `Os`/`ErrnoException` imports removed.
- Verified on device (arm64): APK carries only `libcmus.so` + `libtermux.so`
  (no plugin .so); `nm` shows all 9 ip + aaudio ABI symbols + the tables inside
  `libcmus.so`; app launches with no "incompatible plugin"/"missing symbol";
  pressed play on the restored `song.opus` → PLAYING→STOPPED after exactly its
  3 s (opus input + aaudio output both resolving from the one image).

## 2026-07-19 — Stage 22: termux from source, 16 KB page-size fix (built, authored as Claude)

Per [plans/22-termux-from-source.md](plans/22-termux-from-source.md).

- The stage-6 flagged "not 16 KB aligned" dialog traced to exactly one lib:
  `libtermux.so` from the JitPack terminal-emulator AAR was NDK-21-built
  (`p_align 0x1000`); our own NDK-r28 libs were already `0x4000`
  (`llvm-readelf -l` verified). So the whole warning was one ~10 KB JNI shim.
- Fix = build all of termux from the re-added `third_party/termux-app`
  submodule (pinned pristine at `v0.118.3` = `5b657c6`), the third option
  stage 6 rejected: **not** as Gradle modules (their AGP-4-era build.gradle
  is the blocker stage 6 hit), but source straight into `:app` —
  `java.srcDirs` for terminal-emulator + terminal-view, `res.srcDir` for
  terminal-view's res, and `native/ports/termux/CMakeLists.txt` compiling
  `termux.c` into `libtermux.so` in our CMake tree (NDK r28 → 16 KB aligned).
  `'termux'` added to the app's cmake targets.
- One patch, managed by `patch.sh` like cmus: `patches/termux-app/0001-*`
  redirects two `import com.termux.view.R` (which no longer exists as a
  library R) to `net.pgaskin.cmus.android.R`. Gitlink stays pristine; the
  submodule working tree sits at the patched HEAD (same as cmus). Careful
  ordering: `git submodule add` had staged the default-branch tip, so the
  gitlink was re-`git add`ed at the checked-out `v0.118.3` before patching.
- JitPack gone: the `com.github.termux.termux-app` dep and the `jitpack.io`
  exclusiveContent repo removed; `androidx.annotation` (was transitive) is
  now a direct `implementation 'androidx.annotation:annotation:1.3.0'`.
- `assembleDebug` green; all 12 APK libs `p_align 0x4000`, our libtermux.so
  still exports the five `Java_com_termux_terminal_JNI_*` symbols, arm64-v8a
  only. **Not yet device-verified** — needs a run to confirm the terminal
  (pty spawn, selection handles, strings) still works from source.

## 2026-07-19 — Settings screen fixes (two minor, device-verified)

Unrelated follow-ups after stage 21; authored as Claude.

- **Settings top clipped under the action bar**: targetSdk 36 enforces
  edge-to-edge, so the platform stopped padding the content window for
  the system bars — `android:id/content` (the ScrollView) filled the
  whole window `[0,0][w,h]` and the action bar painted over the first
  rows (uiautomator-confirmed; worse on the shorter-status-bar / lower-
  density device, barely visible on the other — which is why it looked
  device-specific). Fix: an OnApplyWindowInsetsListener on the ScrollView
  applies the dispatched systemBars|displayCutout insets as padding. The
  dispatched top inset **already folds in the action bar height** (a
  first attempt adding `actionBarSize` on top double-padded by ~one
  status bar — dropped it). Verified on both devices: first row clears
  the bar, last clears the nav pill.
- **Settings icon → real PopupMenu**: the gear's fan-out list was a
  hand-rolled anchored PopupWindow (shared showListPopup); it's now
  `android.widget.PopupMenu`, a stock Material dropdown. The sub-selectors
  it opens (Theme, Font) stay the centered cmus-themed showListPopup
  lists — only the top-level menu changed. Titles double as the switch
  keys.
- **Submodule gitlink slip caught here**: the stage-21 `git add
  third_party/cmus` had committed the gitlink at the *patched* HEAD, but
  the vncpatch pattern pins it at the pristine base (`d335e90`; patches
  live in `patches/cmus/`). `patch.sh check` reads the index gitlink as
  `base` and errors when it equals HEAD → the build's preBuild check
  failed. Rebased the four stage-21 commits so none move the pointer (the
  cmus commit touches only patch files); check green again. Stage-21
  hashes changed (unpushed).

## 2026-07-19 — Stage 21: direct touch toggle + floating joystick (done, device-verified)

- Per [plans/21-touch-joystick.md](plans/21-touch-joystick.md). One cmus
  change (amended into 0001) + app wiring; implementation authored as
  Claude (Patrick's call for this stage). Device-verified by Patrick.
- **"Direct touch input"** app switch (App section, default on): the
  `mouse` option, forced true unconditionally until now, becomes a pref
  in the `term` file. onConnected forces `set mouse=<pref>`; a new
  `TermService.applyMouse()` re-sends it live when the switch flips.
  `mouse` stays *out* of `CmusSettings.MANAGED` — it's app touch
  behaviour, not a synced-back option, and staying forced means autosave
  can't strand it. The tap/long-press branches already gate on the
  emulator's `isMouseTrackingActive()` (which follows the option), so
  `mouse=false` makes terminal taps toggle the keyboard and terminal
  long-press inert *for free* — this stage only adds the floating stick.
- **Coordinate problem, and the fix**: a right-click needs a button
  report, but a `mouse=false` cmus never enables tracking, so an SGR/X10
  sequence would be parsed as stray key bytes (random binds), not a
  click. Patrick's steer — *the right-click acts on the current
  selection regardless of touch position* — dissolved it: new
  coordinate-free **`android-selected`** intent line (0001) calls the
  existing `android_selected_event()`, which reads `cur_view` + the live
  cursor/marked set and (via `android_for_each_sel`) already emits
  nothing in browser/filters/settings and the playlist list-pane's
  non-editable side — the exact gating the mouse right-click relied on.
  So it's safe from any view and feeds the unchanged
  pendingRemove→onSelected dialog.
- **Floating joystick** (Direct touch off + joystick on): JoyDot flips to
  MATCH_PARENT filling the wrapper, invisible at rest, summoned under the
  finger — the gesture origin is the press point, not the view center
  (onDraw + the DOWN hit-test are the only geometry changes; dx/dy were
  already origin-relative). The 2s reposition-drag arm is repurposed to a
  right-click at the platform long-press timeout (`rightClicked` flag
  short-circuits any later slide so a held finger can't also fire
  arrows). MainActivity.applyBarVisibility computes `!directTouch &&
  joyShown`, calls `joyDot.setFloating`, swaps the layout params, and
  `placeJoyDot` no-ops while floating. floatBar sits above the stick
  (added later to the wrapper) so the hidden-top-bar buttons still work.
- **Trade-offs (accepted on device)**: with the stick owning the whole
  terminal, a plain tap is Enter — tap-to-toggle-keyboard moves to the
  control-bar button / popover — and **pinch-zoom is unavailable** in
  floating mode (it's inherent to "touch anywhere summons the stick";
  the settings Zoom slider covers it). Flagged post-plan, Patrick kept
  the simple design.
- `./patch.sh check` green (0001 amend + regen, 0002–0004 headers only);
  clean assembleDebug (Java + native cmus).

## 2026-07-19 — Stage 20: ogg/opus embedded art (done, device-verified)

- Per [plans/20-ogg-opus-art.md](plans/20-ogg-opus-art.md). App-only, no
  cmus change — Patrick's call after a planning hypothesis was tested and
  disproved on-device (below).
- **Disproved "the picture is already over IPC"**: my first read said the
  ip plugins pass all vorbis comments through and android.c serializes
  them, so `METADATA_BLOCK_PICTURE` would already be in `Status.tags()`.
  Wrong — **comment.c filters every key through an `interesting[]`
  allowlist** (`fix_key` → NULL for unlisted keys → `comments_add` frees
  the value), and the picture keys aren't on it. So it's dropped at parse
  time: never in `ti->comments`, hence never cached (cache.c `write_ti`)
  and never in the IPC `tags`. **Device-proven** (Pixel 8, root adb): a
  hand-built opus with an embedded 974 B FLAC-picture block (base64 1300
  chars) added to the library + `android-save`d → a **236-byte** cache
  whose only comment strings are output_gain/title/artist/album. That
  ruled out both the read-from-tags route and the cmus-patch route
  (Patrick picked the self-contained app parser over a cmus patch / a tag
  library).
- **`OggCover`** (new class, art-executor thread, no deps beyond
  `android.util.Base64`): sniff `OggS` → **reassemble the comment-header
  packet (packet index 1) across Ogg pages** (255-byte lacing
  continuation, locked to the first BOS serial to skip any other
  logical stream, 8 MB packet / 16 MB scan caps) → strip `\x03vorbis` /
  `OpusTags` prefix → walk the LE vorbis-comment block →
  `METADATA_BLOCK_PICTURE` (case-insensitive) → base64-decode → parse the
  FLAC picture block (8 big-endian fields), **front-cover (type 3)
  preferred, else first decodable**. Everything bounds-checked; any
  malformed/truncated field → null, never an exception into the executor.
  The load-bearing bit is the cross-page reassembly — embedded art makes
  that packet span pages. Inserted in `MediaControl.decodeArt` between the
  framework-null path and the folder-art fallback (reuses the existing
  `decodeScaled` byte[] decode).
- **Device-verified** (Pixel 8, root adb, `dumpsys media_session`
  metadata size deltas — opus is where the framework returns null, so any
  art there can *only* come from OggCover): single-page 300×300 PNG cover
  → art set (size 4→5); **multi-page ~15-page 700×700 JPEG cover → art
  set** (reassembly works); no-embedded opus → no art (size 2); no-
  embedded + folder `cover.jpg` → folder art (size 3, fallback intact).
  `decodeScaled` returning non-null confirms the extracted bytes decode
  as a valid image (byte-correct extraction, not garbage). Zero OggCover
  error logs. patch.sh check green (no cmus changes); clean assembleDebug.
- **Test assets left on device** for re-testing (Patrick's request):
  `…/files/.cmus/home/{arttest.opus (single-page PNG), bigart.opus
  (multi-page JPEG), noart.opus, fdir/song.opus+cover.jpg}`. Host-side
  copies + cover images + the METADATA_BLOCK_PICTURE base64 in the
  session scratchpad. ffmpeg's opus muxer rejects a PNG attached_pic
  stream, so the tag was hand-built (which also validated the exact FLAC
  block layout; ffprobe re-derives the attached pic from it) and injected
  via `-metadata` / an ffmetadata file for the big one (argv limit).
- Open questions (defaults shipped): front-cover preference (yes); legacy
  `COVERART`/`COVERARTMIME` raw-image field (skipped — rare); framework
  attempt stays first, OggCover only on its null.

## 2026-07-19 — Stage 19: continuous state save (implemented; committed pre-verification at Patrick's direction)

- Per [plans/19-continuous-save.md](plans/19-continuous-save.md) — the
  cadences/buckets/atomicity are **Patrick's decisions** (see the plan);
  committed before final tests at his direction, device pass his.
- **Two stage-18 bugs found while planning**: (1) the android_save_state
  comment claimed every callee is write-tmp-and-rename — wrong:
  `do_cmus_save` (lib.pl/queue.pl/playlists) wrote in place with
  O_TRUNC; (2) it called `cache_close()` with the worker live, but
  job.c mutates the cache hash under cache_mutex and upstream only
  reaches cache_close after job_exit — a save mid-import could walk
  the table mid-mutation. Both fixed (0004 below; cache_lock around
  the cache save in 0001's android_save_state).
- **0001 amendments**: `android-save [kind…]` (resume settings library
  cache playlist queue history; bare = everything, so stage-18 flows
  unchanged), saved ack grows `"what"`, and a coalesced
  `{"type":"dirty","what":[…]}` event — per-kind mutation counters
  (atomic; the cache bumps on the worker), announced when counter
  differs from both last-announced and the value snapshotted *before*
  the last save's writes (mid-save mutations re-announce; edge-
  triggered = one event per kind per save cycle, so app debounces are
  arm-on-first-change with bounded staleness), re-announced in the
  connect snapshot, emitted *before* the jobs diff so a client arms
  before it sees a completion edge. Hook sites: editable.c chokepoints
  (do_editable_add/remove_track/move_sel/sort/rand/update_track,
  classified lib→skip / pq→queue / else playlist — lib.pl saves from
  the filename hash in canonical ti_cmp order, so lib editable
  order never moves the file; the hash_insert/hash_remove hooks in
  lib.c are the exact library writers), pl.c meta ops
  (create/delete/rename/import), cache.c (do_cache_remove_ti + the
  two runtime add_ti sites — never the startup loader, so a plain
  spawn stays cache-clean), history_add_line (runtime-only; loads use
  history_add_tail). Known accepted churn: the startup restore's
  autoload jobs dirty library/queue/playlist → one consolidated
  small-file save after the restore's jobs edge per spawn (the
  multi-MB cache write never joins it).
- **0004 (new, upstream candidate)**: do_cmus_save writes
  `<filename>.tmp` + rename (the "-" stdout path unchanged; unlink on
  failure). Riders from Patrick's mid-session catch: pl_load_all
  skips `*.tmp` (an interrupted save's leftover must not load as a
  phantom playlist) and new playlist names ending in .tmp are
  rejected — saving playlist X writes X.tmp, which would clobber a
  playlist literally named X.tmp. No fsync (matches the other
  writers; rename fully covers SIGKILL, battery death was ruled out
  of scope).
- **StateSaver** (service-owned, third IPC listener beside
  MediaControl, per-CmusIpc lifecycle, all main-thread): buckets
  playlist+queue 5s / history 5s / settings 5s / library+cache 15s /
  resume 15s; full save at playback boundaries (track change, pause,
  stop) when ≥15 min since the last. Triggers: Dirty events for the
  content kinds; app-side Options-map diff (the options event fires
  per command, changed or not) + Volume changes for settings; Status
  transitions for resume (never position ticks — the 15s also pushes
  the write off the track-boundary moment). **Deferral rider found
  during implementation**: playlist/queue/library/cache buckets hold
  while a worker job runs and the jobs false edge flushes them — a
  save mid-*restore* would write a partial file over a complete one
  (memory fills *from* the file), and a force-stop right then
  persists the truncation; mid-*import* saves are consistent
  supersets, so the boundary full save doesn't defer (and the 15-min
  guard starts at spawn, so it can't land in the restore window).
  All android-save traffic funnels through StateSaver's FIFO ack
  queue (one socket, ordered replies): TermService.saveState
  delegates, so the zip-export/pre-reset bounded waits can't be
  satisfied by a stray periodic ack (the old single-slot
  pendingSavedDone machinery is gone). Disconnect clears queue+timers
  (saveNow timeouts still release callers); the connect snapshot's
  re-announcements rebuild pending state.
- CmusIpc: Saved gains `what`, new transient Dirty record; both logged
  by the service's IPC logger.
- Open questions from the plan Patrick hasn't ruled on (defaults
  shipped): queue folded into the playlist bucket; no resume save for
  long uninterrupted tracks beyond boundaries; seek/view/filter don't
  arm the resume bucket; no on-background save; cache rides the 15s
  track-edit debounce (can land mid-playback — his stutter listen
  will judge); fset edits invisible until a full save; no fsync.
- Verified: clean assembleDebug + patch.sh check green (committed at
  that point per Patrick); device pass pending — kill -9 staged-point
  state survival, torn-write loop, event-flow logs, export-during-
  saves, and the boundary-save stutter listen (explicitly Patrick's).

## 2026-07-19 — Stage 18: settings screen + riders (implemented; Patrick's device pass pending)

- Per [plans/18-settings.md](plans/18-settings.md) (committed and amended
  live this session — SettingsActivity instead of an overlay, stock
  Material day/night with a muted blue-grey accent instead of purple,
  file-only resets, zoom setting, pre-save checkbox, backup rules,
  IPC-log toggle, floating sleep slot — all Patrick's calls mid-session).
  Commit split differs from the plan's list (work interleaved): cmus
  patches, dotfolder, core sync+primitives, SettingsActivity, visibility,
  backup rules, then follow-ups.
- **cmus patch**: 0001 gained the `android-save` line + `saved` event —
  exit_all's save set (resume, autosave, queue, lib, playlists,
  histories, cache) via a new `android_save_state()` in ui_curses.c (all
  callees are write-tmp-and-rename, repeat-safe; cmd/search histories
  are statics, so command_mode/search_mode grew minimal `*_save`
  wrappers). New **0003** (upstream candidate, not yet submitted):
  `op_aaudio_get_sharing_mode` switched on `op_aaudio_opt_performance_mode`
  and always echoed "shared" — found while planning; without the fix the
  settings sync-back would clobber an `exclusive` pick on reconnect.
- **Dotfolder**: everything app-managed now under `filesDir/.cmus/`
  ({terminfo,data,home,lib,assets.stamp,android.sock}); browser at $HOME
  shows a clean dir unless show_hidden. Migration renames old cmus-home
  in (verified live: filter/softvol state survived), deletes the rest.
- **Sync (the load-bearing design)**: `CmusSettings` — curated options
  in a `cmus_opts` prefs file; every stored key forced with `set` on
  each connect (prefs override cmus), every Options echo synced back
  idempotently. progress_bar excluded from sync-back: app-managed with
  `auto` (control bar visible → disabled, hidden → line). Settings rows
  render only from echoes (stage-11 rule; bad lib_sort input snaps
  back). Verified on device: first echo populated all 24 keys;
  `set dsp.aaudio.sharing_mode=exclusive` → pref updated (0003 proof);
  after an autosave delete the fresh session's autosave carried
  sharing_mode=exclusive + progress_bar=disabled + softvol=true — the
  override contract holds through a settings wipe.
- **SettingsActivity**: app / audio / cmus / data / debug, hand-rolled
  rows, stock Material day/night (values-night parent swap, framework
  has no DayNight; accent #546E7A / night #90A4AE — tune live if
  wanted). Binds the service, own IPC listener (re-attached after
  resets), reports into the service's new visible-activity *count*
  (Main↔Settings transitions overlap, a bool would end wrong).
- **Data tools** (file-only, Patrick's troubleshooting stance):
  TermService.resetData = kill→mutate→respawn (SIGKILL deliberately;
  file op on a worker thread; FGS kept). Partial deletes carry the
  default-on "save current state first" checkbox → bounded (5s)
  android-save. Zip export = android-save → Saved ack → zip on a worker
  thread (cmus's server socket in home is skipped — found live, a
  FileInputStream on it would fail the export); import clears home
  before unzip (replace, never merge; zip-slip guarded). Verified the
  whole delete-saved-settings cycle on device via injected taps: saved
  → SIGKILL → respawn+reconnect in ~150ms, exactly `autosave` gone.
  **Caveat**: deleting autosave resets softvol_state to `0 0` (silent
  until vol is raised) — inherent to a defaults reset; I restored
  `vol 100` on the device after testing.
- Popover rename (Patrick): Refresh → **Import**, plus a new **Update
  cache** entry sending plain `update-cache` (no -f: changed files and
  skip_track_info entries refresh without re-reading the whole
  library); the jobs-edge toast is now the neutral "Library update
  finished" since it fires for both.
- **Riders**: Material You default (explicit colorscheme picks already
  store false, untouched); hue-rotation setting (0–359°, 180 default)
  re-pushed live; sleep-timer exit mode (clears `resurrect` so a BT key
  can't undo the sleep); always-resume-paused (first-Status pause,
  media-key resurrection wins); idle-quit minutes pref (0 = off);
  debug receiver pref-gated for release builds (row shows the adb
  example); IPC event logging toggle at INFO (default on only in
  debuggable builds); zoom slider sharing the font pref with pinch
  (applyFontSize factored, applied on onStart); visibility toggles for
  top bar / bottom bar / joystick — hidden bars hand insets to the
  wrapper, the row-remainder goes to whichever bars remain, popover
  gains Keyboard when the bottom bar hides, and a faint overlay row
  (sleep slot + settings) floats top-right when the top bar hides
  (verified on device: layout correct, tabs return on re-enable).
  Backup: dataExtractionRules scoping prefs +
  home/{lib.pl,playlists,search-history,command-history,autosave,cache}
  (resume/queue.pl deliberately out — restored installs start stopped).
- Verified by me on device (Pixel 8, wifi adb root, injected taps):
  migration, sync round-trips, android-save ack + fresh files,
  progress_bar auto in autosave, the reset cycle, top-bar-hidden
  layout + floating button, settings screen rendering with live
  values, IPC info logs. patch.sh check green; clean assembleDebug.
- **Left for Patrick (his call — bulk of the settings matrix)**: every
  row's round-trip feel, audio options against real playback, zip
  export/import through SAF, delete library/playlists/all, reset app
  prefs → recreate, sleep exit + resume-paused end-to-end, hue/zoom
  slider feel, day/night + accent look, Keyboard popover entry,
  floating sleep slot, `bmgr` backup round-trip, 0003 upstream
  submission.

## 2026-07-19 — Joystick: hold-to-drag repositioning + nav angle gate (done)

- JoyDot: resting on the center 2s (Patrick tuned down from 3) fires a
  LONG_PRESS haptic and the touch becomes a reposition drag. JoyDot only
  reports raw-coordinate deltas (the view moves under the finger);
  MainActivity owns placement: center kept as a fraction of the terminal
  wrapper, saved per orientation (joy_x/y_port + joy_x/y_land in the
  term prefs), restored via a wrapper layout-change listener (rotation +
  IME resize; replaces the old fixed BOTTOM|END margins — the unsaved
  default is the same 140/150dp corner spot), center clamped ≥64dp from
  wrapper edges during drag and on restore.
- Nav trigger was too easy to hit from a sloppy vertical drag: engaging
  left/right now also needs a 2:1 horizontal pull (~27° cone) on top of
  the 40dp threshold; the distance hysteresis alone still holds it once
  engaged. Verified on-device by Patrick.

## 2026-07-19 — Fixes: volume popup keyboard + debug-build decode CPU (done)

- Volume popup made non-focusable (ControlBar): a focusable PopupWindow
  took window focus and hid the soft keyboard. Outside-tap still
  dismisses; back now goes to the activity instead.
- Debug-variant decode CPU (15-20% vs termux's 5% on the producer
  thread): AGP builds the debug variant with CMAKE_BUILD_TYPE=Debug, so
  cmus + all codecs compiled at -O0. native/CMakeLists.txt now appends
  `-O2 -DNDEBUG` to the Debug flags (a gradle `arguments
  -DCMAKE_BUILD_TYPE=...` override was ignored by AGP 9). Not
  player_pos_exact, which was the initial suspect — it only runs on the
  main loop and trylocks. Verified fixed on-device by Patrick.

## 2026-07-19 — Stage 17: Music-folder refresh + import guard (done)

- Per [plans/17-refresh.md](plans/17-refresh.md), same session as stage
  16. This is the overview's data stage renumbered 17 and done ahead of
  the settings screen (Patrick's reorder; its tar import/export moves
  into the settings stage — now 18 — as **zip**): the popover
  **Refresh** action plus the import guard designed with Patrick
  mid-session.
- **Refresh:** popover gains Refresh (Theme / Font / Refresh / Settings)
  → READ_MEDIA_AUDIO (runtime request resuming the action on grant;
  manifest + direct paths, no SAF) → toast "Adding tracks from Music
  folder" → `add /storage/emulated/0/Music` (from Environment, not
  hardcoded). cmus's own recursive add job is the importer; the library
  is keyed by filename so re-taps dedupe — no app-side scan state. The
  path prefixes under CMUS_ANDROID_EXT (stage-10 pl_env portability).
- **Jobs over IPC (0001), two mechanisms by Patrick's call:** the
  load-bearing find is that **job_fd is in cmus's main-loop select
  set** — job activity itself wakes the loop, so a
  `{"type":"jobs","running":bool}` event *diffed in the flush* (volume
  pattern, snapshotted on connect, cached/replayed in CmusIpc) is
  reliably prompt, unlike the stage-15 WINCH case. That event drives an
  **"Import finished" toast on the true→false edge — any import, any
  trigger** (TUI `:add` included). The **idle-quit guard polls
  instead**: the fire sends `android-jobs` (answered immediately; also
  updates the diff cache) and quits only on a fresh running=false,
  re-polling every 30s while an import runs — never truncating a scan
  (closes the stage-15 note). The pipeline stays armed through the
  poll/defer cycles so a leftover re-poll can't couple with a fresh
  arming and quit ~30s after backgrounding; every async step re-checks
  the idle conditions, and cancel paths clear the pending flag.
- **TODO (Patrick, recorded below):** tracks deleted on disk linger in
  the library — cmus has no prune-missing command; needs design.
- Verified on device (Pixel 8; Patrick hands-on with his own tracks):
  permission flow, toast, import lands, finished-toast fires — "it
  works correctly". patch.sh check green after the 0001 fixup+regen;
  clean assembleDebug.

## 2026-07-19 — Stage 16: theme/font selector + bundled fonts (done)

- Per [plans/16-theme-font.md](plans/16-theme-font.md) with two Patrick
  redesigns mid-stage: (1) the settings icon's *tap* opens a popover
  (Theme / Font / Settings — Settings toasts until stage 18) instead of
  the planned single two-column overlay; the selectors are separate
  centered single-column overlays (long-press still jumps straight to
  the theme selector). (2) The Material You palette was restyled live
  against **gruvbox-warm** as the structural reference (see below).
- **colorscheme event (0001):** `{"type":"colorscheme","name":...}` from
  cmd_colorscheme's success path only (failed lookup = error + no
  event); transient like `selected` (cmus doesn't retain the name — the
  colors ride the coalesced options echo). The overlay highlight moves
  only on the echo (TUI `:colorscheme` lands identically); the name
  persists in a pref for the next launch.
- **Theme selector:** Material You pinned first, then the sorted union
  of `cmus-home/*.theme` (wins per cmd_colorscheme's search order) and
  `cmus-data/*.theme`; whitespace names skipped (`colorscheme` takes
  exactly 1 arg). Picks send `colorscheme <name>`; the popup stays open
  for browsing (win colors, 1dp separator frame, no scrim, ~60% height
  cap, outside-tap/back dismiss, closed on onStop/crash).
- **Material You** = palette redefinition + constant set burst: entries
  16–42 get exact dynamic-color ARGB (`mCurrentColors` is public — it's
  what OSC 4 mutates; `reset()` restores stock), the 27 roles point at
  them via `set color_<role>=<16+i>`. Light/dark + wallpaper changes are
  palette-push-only (service onConfigurationChanged, headless included —
  zero cmus traffic, the autosaved indexes don't move); every connect
  re-forces the burst while active (stale-autosave self-heal); any
  colorscheme echo clears the pref + resets the palette. CmusTheme now
  resolves through the live emulator palette (the app itself broke the
  "cmus never sends OSC 4" assumption), with onPaletteChanged re-deriving
  chrome when ARGB moves under unchanged indexes.
- **Material tuning (Patrick, ~8 rounds live):** gruvbox-warm structure —
  hard-contrast bg (neutral1_900 half-mixed toward black), one band tone
  for the bottom title + active selections, statusline a half-step
  darker, the top bar band darker still (band mixed toward bg); both
  title fgs share accent1_400 (darker/saturated, apart from list text);
  the status/cmdline band + control bar wear the **complement** of
  accent3_200 — all five system ramps are harmonized warm by design
  ("tonal spot"), so a genuinely different hue must be synthesized (180°
  HSV rotation, tone kept; wallpaper-derived, verified `#bd817f` rose
  titles vs `#86b8ef` blue status on device). List hierarchy: playing =
  accent3_300 (saturated) > selected = accent1_100 > unselected =
  accent1_200 half-mixed with neutral1_200 (same hue, dimmer, half
  desaturated — Patrick rejected plain grey); inactive-pane highlight
  bgs a clear step darker than the active pane's. Tabs are bold.
- **Fonts:** five bundled regulars + System — Fira Mono, IBM Plex Mono,
  Iosevka (10.8 MB — the big glyph set is most of the APK bump),
  JetBrains Mono, Roboto Mono (Iosevka + Roboto Mono added by Patrick
  mid-stage; **Iosevka is the default**, the pref stores "" for an
  explicit System pick), licenses beside them in assets/fonts. Fake
  bold/skew cover styles (renderer-verified). `TerminalView.setTypeface`
  rides the pinch-zoom resize path; the
  ControlBar.lineSpacing/firstRowOffset mirror statics take the active
  typeface (the flushness fixed point is only exact measuring what the
  renderer measures), threaded through every chrome text site;
  pref-restored before attach so the saved headless grid matches.
  Top-bar polish (Patrick): icons at the control bar's 3-row/2-row-glyph
  spec, tabs bold at ~1.15× the terminal font (filter box too — it
  shares the band), the active tab scrolls fully into view on the
  echoed View event (narrow-screen overflow), and the rarely-used
  filters/settings tabs are hidden unless active. The control bar's
  queue slot became three-state: + = win-add-l in the browser (its `a`
  binding), remove in the queue, add-to-queue elsewhere.
- **Deadlock fix (0001), found by Patrick mid-test:** stage 12's
  `player_pos_exact()` took player_lock in the main loop's per-flush
  path — but at a track boundary the consumer thread holds
  consumer_mutex across its blocking get_next callback
  (cmus_get_next_track cond-waits), which only the main loop can answer.
  Frozen TUI + 4 threads in futex_wait, pinned by `debuggerd -b`
  backtraces (main: android_flush → player_pos_exact →
  cmus_mutex_lock; consumer: cmus_get_next_track → cond_wait). Now
  trylocks both mutexes, falling back to the snapshot's whole-second
  position for the contended iteration. Latent since stage 12; this
  stage's per-command event traffic widened the window.
- Also: selector NPE on first use (immutable `List.indexOf(null)`
  throws — the never-echoed colorscheme name), fixed with a null guard.
- Verified on device (Pixel 8, wifi adb + logcat + debug receiver;
  Patrick hands-on throughout, live-directing every build): theme picks
  re-tint TUI + chrome + system bars on one echo with the highlight
  following; Material indexes visible in the options echoes
  (color_win_cur=28 etc.); palette values logged on push; fonts applied
  live and restored across relaunch; light/dark + persistence + fonts +
  selectors all Patrick-confirmed — "it works" / "everything works".
  patch.sh check green after both 0001 fixup+regen rounds; clean
  assembleDebug. 24 ffmpeg-generated tagged tracks (3 artists × 2
  albums, flac/mp3/ogg) pushed to ~/music (chown + restorecon after
  root push) and added for list-rendering judgment.
- Testing notes: `debuggerd -b <pid>` gives userspace backtraces of a
  wedged native process on the rooted device (kernel wchan/futex first
  narrowed it); ImmutableCollections throws NPE on `indexOf(null)` —
  guard any nullable lookup key; the dynamic ramps (accent2/3) never
  leave the seed hue's neighborhood, so "different hue" needs
  synthesis; reinstalls force-stop cmus as always (documented loss
  window — the connect-time material re-force is what covers it).

## 2026-07-19 — Stage 15: quick filter + sleep timer (done)

- Per [plans/15-filter-sleep.md](plans/15-filter-sleep.md) — the plan's
  designs held (cmus `filter` event via the volume-style diff-in-flush,
  zero hooks; box morph; service-owned sleep countdown) — plus a
  verification round of Patrick live-testing every build that produced
  four real fixes, one of them a cmus patch addition.
- **Quick filter:** search icon (left of the tabs) morphs the bar into a
  monospace EditText + ✕ driving `live-filter` per keystroke (verbatim
  rest-of-line — parse_command has no quoting; blank = clear; plain text
  = substring match, `~`-prefixed = expression per expr_is_short).
  Opening from a non-library view sends `view tree`. The box prefills
  from the cached echo and ignores echoes while open; the icon tint is
  the always-on indicator. ✕ clears+collapses, icon-tap collapses
  keeping the filter, IME search action refocuses the terminal, KeyRow
  hides while the box has focus.
- **Filter event (0001):** `{"type":"filter","filter":...}` diffed in
  android_flush against a cached copy — hook-free by design, because
  lib_live_filter has command-bypassing writers: the resume file
  restores it at startup (verified: quit → relaunch snapshot carries
  it) and lib_set_filter silently clears it on filters-view/`filter`
  activation (verified via `filter duration<9999` → null echo with no
  live-filter command involved).
- **Sleep timer:** TermService owns an elapsedRealtime deadline +
  postDelayed `player-pause-playback` (pause-only; uptimeMillis timing
  safe — active audio holds a partial wakelock, a dozed device wasn't
  playing; expiry + backgrounded feeds the normal idle-quit). Tab bar's
  right slot: bedtime icon ↔ minutes-left text (minute-boundary tick +
  Status-event refresh); tap = 15–90 min presets + Custom… + Turn off.
  Session death zeroes the deadline (removeCallbacksAndMessages had
  already dropped the fire silently).
- **Patrick's live-testing fixes:** (1) top bar: the weighted
  match_parent tab scroller was forced to the icon squares' height
  (LinearLayout's weighted remeasure), clipping the tabs → wrap_content
  makes the tabs the intrinsic height; the EditText drops Material's
  48dp min-height so the morph doesn't change bar height. (2) IME
  close flickered: the box losing focus flashed the KeyRow in for the
  hide animation → hides are optimistic (imeVisible cleared at request,
  IME inset split from bar insets so the stale inset can't land on the
  control bar); layout expands under the departing keyboard in one
  pass. (3) IME open left a gap where the keyboard would land (layout
  jumps at animation start) → show lays out immediately but the bottom
  chrome translates down and rides the keyboard's animated edge up
  (WindowInsetsAnimation onProgress), vacated band painted by the
  root's cmdlineBg. (4) **Missed-SIGWINCH band** (win_title-styled
  blank cells below a stale 42-row grid after install-restart, and
  sometimes on filter close): the kernel's WINCH is *lost* if the
  attach resize beats cmus's sigaction (default disposition ignores
  it) and *stranded* if it lands between the needs_to_resize check and
  select entry (sleeps until the next wakeup — indefinitely when
  idle). New `android-winch` intent line (0001) calls the
  already-exported update_size(); the app sends it from onEmulatorSet
  (every pty grid change) and on every IPC connect. Patrick: "that
  worked" / "it all works".
- Verified on device (Pixel 8, wifi adb + logcat + debug receiver;
  Patrick hands-on throughout): snapshot carries `filter` (null and
  set); per-keystroke echoes (b→be→bea→beat); ✕ → null echo + tabs +
  terminal height restored (bounds via uiautomator dump); resume
  round-trip; silent-clear echo; sleep 1m set→fire→pause logged with
  countdown text rendered and slot reverting; morph keeps the exact
  132–204 px band both states. patch.sh check green after both 0001
  fixup+regen rounds; clean assembleDebug.
- Testing notes: uiautomator dump + a hand-rolled PNG decoder (no PIL
  on host) were the workhorses — the band's color matching win_title_bg
  is what cracked the WINCH diagnosis; prefs showed the spawn grid
  67×42 saved from an IME-open onStop, which is why only
  install-restarts reproduced it. adb `input text` types into the
  focused EditText directly. clangd's phantom android.c errors again —
  build is the arbiter.

## 2026-07-18 — Stage 14: input B (done)

- Per [plans/14-input-b.md](plans/14-input-b.md), but with a mid-stage
  redesign and several feature additions from Patrick live-testing each
  build — the plan's app-side screen-reading flow shipped, worked, and
  was then replaced the same session (see below). What stands:
- **Long-press = right-click.** onLongPress always returns true (that's
  TerminalView's only entry into text-selection mode, so selection is
  permanently disabled, and the interim long-press keyboard toggle is
  retired) and, when mouse tracking is on, sends BUTTON3 press+release
  (raw protocol code 2 — the lib's public sendMouseEvent takes it) at
  the pressed cell. No mrb_* key is bound in the default rc, so cmus's
  reaction is moving the selection to that row / switching panes.
- **Patrick's redesign: the confirm dialog names the actual files, not
  the rendered row text** — so patch 0001 grew a `selected` event
  instead of the plan's terminal-buffer reading. keys.c hooks
  normal_mode_mouse after the selection has moved (KEY_MRB_CLICK |
  _SEL only — the bar/title variants excluded); android.c emits the
  exact win-remove target set via the same per-view _for_each_sel the
  multi-track commands use (marked-tracks rule included, so a marked
  set or a tree artist/album lists every file), but only where
  cmd_win_remove is prompt-free — elsewhere (browser, filters,
  settings) yes_no_query would block cmus's main loop reading the pty.
  The app arms an 800ms window at long-press; a Selected inside it =
  the click resolved to a removable selection → dialog. Every
  app-side guard from the plan (pane-split mirror, cmdline-prefix
  mode sniff, blank-row check) became unnecessary: no event, no
  dialog. Remove re-checks the echoed view at confirm.
- **Playlist actions (Patrick, added during testing):** the item
  dialog gained a neutral "Add to playlist" → chooser of the event's
  `playlists` names + "New playlist…" (EditText → `pl-create` +
  `android-pl-add`, ordered writes). Long-pressing a playlist *name*
  (playlist view list pane) emits the event's playlist variant → a
  remove-playlist dialog → `android-pl-delete`. The verbatim-name
  intent lines dodge command-tokenizer quoting; android-pl-add marks
  the target by name, adds the selection, and restores the user's
  mark (cmus can only add to the *marked* playlist);
  android-pl-delete = pl_delete_by_name — both prompt-free, unlike
  their win-* equivalents. pl_android_for_each_name is the minimal
  pl.c/pl.h wrapper (struct playlist + pl_head are private;
  player_pos_exact precedent).
- **JoyDot** (shaped by ~5 rounds of Patrick's hands-on): faint
  two-circle virtual stick over the terminal's bottom-right — 120dp
  view, grabs only touches starting within 39dp of center (rest fall
  through to the terminal), knob follows the finger to 44dp, center
  80/90dp in from the corner so a far-right drag has room. Tap =
  enter; vertical past 15dp = repeating arrows (300→75ms scaled to
  60dp); far left/right past 40dp (30dp hysteresis) = **directional
  nav, repeating 750→250ms** scaled by displacement: android-nav-left/
  right intent lines that cmus resolves with the pane state only it
  has — win-next toward the inner pane in tree/playlist, left/right-
  view -n at that side's edge or in single-pane views (no wrap), and
  an empty track pane is skipped straight to the view switch
  (pl_win_next refuses to enter it — the "can't go right out of an
  empty playlist" fix). Keys ride the shared injectKey path, so
  KeyRow's sticky modifiers merge; arrows pause while navigating; dot
  hidden on the crash screen.
- **Font size persists** (prefs "font", saved per pinch, restored
  clamped) — reopening used to reset to 13dp and immediately resize
  the headless pty spawned at the saved grid.
- Verified on device (Pixel 8, wifi adb + logcat + debug receiver;
  Patrick hands-on throughout, testing each build live): nav
  view-switching watched via echoed view events (playlist left-pane
  slide → sorted; empty-playlist far-right fix confirmed by Patrick),
  dialogs/filenames/playlist add+remove/joystick feel/grab area all
  Patrick-confirmed — "it all works correctly". patch.sh check green
  after each of the four 0001 fixup+regen rounds; clean assembleDebug.
- Testing notes: every reinstall force-stops cmus (documented loss
  window). adb long-press = `input swipe x y x y 600`; joystick
  slides = swipes from the dot center. clangd flags android.c with
  phantom errors (no CONFIG_ANDROID in IDE flags — android.h's no-op
  macros poison the parse); the real build is the arbiter.

## 2026-07-18 — Stage 13: input A (done)

- Per [plans/13-input-a.md](plans/13-input-a.md), no design deviations
  (the "space" separators shipped as inter-group gaps per the plan's
  reading). Java-only stage, no cmus patch changes.
- KeyRow (scrollbar-less fillViewport HorizontalScrollView, the tab-bar
  overflow pattern): monospace lowercase text keys at the terminal font
  size in four gap-separated groups — shift/ctrl/alt · del/esc/tab ·
  ←↓↑→ · home/end/pgup/pgdn — cmdline bg + statusline fg like the
  control bar it adjoins, ripple as the *foreground* so modifier state
  can own the background. Sticky modifiers termux-style: tap = one-shot
  (consumed on read, repaint posted since reads happen mid-dispatch),
  long-press = locked (survives reads); active = inverted block, locked
  adds underline. Arrows/pgup/pgdn auto-repeat on hold (long-press
  starts, 75ms cadence, released on up/cancel + row detach).
- Wiring: row bottom-most in the root layout (directly atop the IME,
  below the control bar, so the terminal-flushness barExtra math is
  untouched); the existing insets listener toggles it on
  isVisible(Type.ime()) and clears all sticky state on hide (an
  invisible modifier must never eat the next key); the bottom(+ime)
  inset moves to whichever chrome is bottom-most visible. Keys inject
  via TerminalView.onKeyDown(keyCode, ACTION_UP event) — the exact
  path termux's extra-keys row uses — and MainActivity's
  readShift/Control/AltKey stubs now delegate to the row, which is
  what merges sticky modifiers into injected keys *and* IME-typed
  characters (sticky ctrl + typed t = ^T for free). Application
  keypad mode was verified a non-issue at plan time: cmus's keypad()
  emits smkx, so KeyHandler's appMode sequences are the terminfo
  strings ncurses expects.
- Verified on device (Pixel 8, wifi adb install; Patrick hands-on for
  the interaction pass — keys, sticky behavior, layout confirmed
  working by hand). `./patch.sh check` green; clean assembleDebug.
  Reinstall force-stopped cmus as always (documented loss window).

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
  overlaid on the terminal's top edge closes it. And Patrick's "seek
  release is flaky" turned out to be upstream player.c clamping
  *absolute* seeks to duration−5 (stage-5/9 notes) — on the 8s test
  tracks 63% of the bar snapped back to 3s; 0001 now relaxes the
  clamp to duration under CONFIG_ANDROID (seeking to eof just ends
  the track), verified `seek 6` → 6.0 where it used to echo 3.0.
  MediaControl's notification seekbar gains the same reach. Post-stage
  addition (Patrick): in the queue view the add-to-queue button flips
  to remove-from-queue (playlist_remove icon, `win-remove`) — driven
  by the echoed View event and gated on view == queue so it can never
  remove library entries; win-add-q there would only duplicate.
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

Stage 18: settings screen (the popover's Settings entry currently
toasts; control visibility toggles, curated cmus settings — aaudio op
options, pause_on_output_change, softvol —, idle-quit minutes, plus the
**zip** import/export of CMUS_HOME that moved here from the data
stage) — needs its detailed plan written and approved first.

TODO from Patrick (stage 17 follow-up, wherever it fits): tracks
deleted on disk linger in the library — cmus has no prune-missing
command. Needs design: maybe an android.c intent line
access(2)-checking lib entries (worker job, like update-cache), or an
app-side diff of the saved library against the filesystem.

After 19 (continuous state save — done above) comes stage 20
(ogg/opus embedded art — a new stage from Patrick), then stage 21
polish. 0004 joins 0003 on the upstream-submission list.

Note for stage 21 polish: Iosevka-Regular.ttf is 10.8 MB (most of the
APK bump); a pyftsubset pass over the bundled fonts could reclaim most
of it if size ever matters.

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

Workflow note: each stage runs in a fresh session — read status.md,
architecture.md, the overview plan, and the current stage plan first.
