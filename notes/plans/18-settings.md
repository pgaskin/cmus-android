# Stage 18 — Settings screen

The popover's Settings entry (toasting since stage 16) becomes a real
settings surface: five sections — app / audio / cmus / data / debug.
The settings list below is **Patrick's curated set for this stage**
(it supersedes the spec's shorter settings sketch) — implementation
commits author as Patrick with Claude co-author per the overview's
authorship rule.

Riders in the same stage (Patrick): the **default theme becomes
Material You**; the extracted libs/assets and all cmus state move into a
**dotfolder** (`filesDir/.cmus/`) so the file browser shows a clean
`$HOME` by default; the **sleep timer gains an exit-the-app mode**; and
the shared prefs + core cmus state become **framework backup/
device-transfer eligible** via scoped rules.

## Facts pinned down

- **Every curated cmus option exists in this cmus master** (options.c/
  options.h): `follow`, `display_artist_sort_name`, `ignore_duplicates`,
  `lib_sort`, `pl_sort`, `progress_bar`
  (disabled|line|shuttle|color|color_shuttle), `replaygain`
  (disabled|track|album|track-preferred|album-preferred|smart),
  `replaygain_limit` (bool), `replaygain_preamp` (float, echoed as
  `%f` → "6.000000"), `show_hidden`, `show_all_tracks`,
  `show_current_bitrate`, `show_playback_position`,
  `show_remaining_time`, `skip_track_info`, `start_view` (view name),
  `tree_width_percent` (1–100), `tree_width_max` (0 = off),
  `pause_on_output_change`, `soft_vol` (option name `softvol`).
- **The aaudio op already has all five options** (op/aaudio.c), exposed
  by output.c's op_add_options as `dsp.aaudio.<name>`:
  `performance_mode` (none|power_saving), `allowed_capture`
  (all|none|system), `sharing_mode` (shared|exclusive),
  `disable_spatialization` (bool), `min_buffer_capacity_ms` (0–1000,
  0 = device default). set_dsp_option only stores the value — the
  op's own comment: options apply at the next output-stream open
  (track change / stop-play), not live. The rows say so.
- **Upstream bug found while reading**: `op_aaudio_get_sharing_mode`
  switches on `op_aaudio_opt_performance_mode`, so the getter (and
  therefore our Options echo) always reports "shared". Needs a
  one-line fix as a new patch (upstream candidate) or the sync-back
  would clobber an `exclusive` pick on the next connect.
- **The Options event is already a full coalesced snapshot** after
  every executed command (stage 7's options-dirty hook) and is
  cached/replayed by CmusIpc — the settings screen can be a pure view
  over it, stage-11 single-source-of-truth rule: rows render only
  from echoes, taps only send `set`. An invalid `set lib_sort=…`
  errors cmus-side without changing the option, and the next echo
  snaps the row back — self-healing for free.
- **pause_on_output_change overlap (Patrick's caution)**: MediaControl
  already pauses on ACTION_AUDIO_BECOMING_NOISY (wired unplug / BT
  drop), and the system pauses via media controls in some routing
  cases. The cmus option additionally covers any device change the op
  sees (e.g. BT connect stealing the stream). Both on = a double
  pause at worst (idempotent). The row's subtitle documents this;
  becoming-noisy stays unconditional.
- **Browser start dir = getcwd()** (browser.c) and the spawn cwd is
  `filesDir`, with `show_hidden` defaulting false — so a
  `filesDir/.cmus` dotfolder is invisible in the browser by default,
  which is the point of the layout move. `$HOME` stays `filesDir`.
- **No full-state save command exists** — autosave/lib/playlists/
  cache/resume are written on the exit path only. Stage 19 already
  anticipated "a save-ish line the app triggers"; zip export needs it
  now, so the intent line lands this stage and stage 19 keeps only
  the timer.
- **Debug receiver** is FLAG_DEBUGGABLE-gated and exported=false
  (root adb reaches it; stage-10 note: multi-word `-e cmd` args need
  the whole am command quoted).
- **Material You default**: users who ever picked a colorscheme have
  `material_you` explicitly false in prefs (stage 16 writes it on
  every colorscheme echo), so flipping the *default* to true only
  affects installs that never chose — no one's explicit pick moves.
- **Backup today is unscoped**: the manifest sets no allowBackup /
  rules, so Auto Backup's default already sweeps *all* of filesDir
  (symlinked plugin tree, terminfo, socket leftovers included)
  against the 25 MB quota — the task is scoping it, not enabling it.
  With minSdk 34 > API 31, only `android:dataExtractionRules`
  matters; `fullBackupContent` is never read on these devices. Rule
  semantics: any `<include>` in a domain restricts that domain to
  exactly the listed paths.
- **cmus home filenames** (misc.c/ui_curses.c/*_mode.c): `lib.pl`,
  `playlists/` (CMUS_PLAYLIST_DIR default `$CMUS_HOME/playlists`),
  `command-history`, `search-history`, `autosave`, `cache` — plus
  `queue.pl` and `resume`, which are **not** in Patrick's backup list
  (transient session state; flagged in case `queue.pl` was meant).
- **Sleep-timer exit vs resurrection**: the sleep fire is normally a
  headless/backgrounded death, which leaves the `resurrect` pref
  true — a pocketed BT play key would resurrect playback at 3am,
  defeating a sleep *exit*. The exit path must clear `resurrect`
  explicitly.

## Design

### Settings surface

A separate **SettingsActivity** (Patrick's call), launched from the
popover's Settings entry, sitting atop MainActivity in the task.
**No cmus theming** — plain Material day/night: no androidx, so
values/themes.xml parents `Theme.Material.Light` with a values-night
override to `Theme.Material`, and the accent is **not the default
purple** — a muted blue-grey (`colorAccent`/`colorPrimary`, e.g.
light `#546E7A` / dark `#90A4AE`, Patrick tunes live). Widgets keep
their stock Material look, zero tinting code.

Hand-rolled rows (framework views, the codebase's style — the
deprecated android.preference stack stays out): ScrollView of section
headers + title/subtitle rows with Switch/SeekBar; enum rows via
AlertDialog single-choice; int/string rows via EditText dialog
(lib_sort, pl_sort, preamp) or slider (tree_width_percent, hue
rotation, zoom).

Plumbing: binds TermService and registers its own CmusIpc listener
(listeners are additive; the service→activity SessionCallback is
untouched), so rows render from the replayed/live Options events and
taps send `set` — same single-source-of-truth rule. Runtime
permission requests and SAF onActivityResult live here too.
App-pref changes (bars, joystick, zoom) apply to MainActivity when it
returns to the foreground (onStart re-reads the prefs).

### Managed cmus settings (prefs override cmus, echoes sync back)

New SharedPreferences file `cmus_opts`, keys = option names verbatim:

- **Force on connect**: TermService.onConnected, after
  mouse/resume/pl_env_vars, sends `set k=v` for every key *present* in
  `cmus_opts` (first run: empty, cmus defaults + autosave stand).
- **Sync back on echo**: for each curated key in an Options event,
  write the echoed value to `cmus_opts` when changed (idempotent
  writes, no loop) — TUI `:set` changes persist across the
  reinstall/force-stop loss window exactly like app changes.
- Managed set = the audio section + cmus section lists above,
  **except `progress_bar`** (below). `softvol` toggles keep the
  control-bar volume-button gating working for free (it already
  follows the echo).
- **`progress_bar` is app-managed, not synced back**: an app-side
  setting `auto | disabled | line | shuttle | color | color_shuttle`
  (default **auto**) in `cmus_opts` under a sentinel. Auto derives:
  control bar visible → `disabled` (the bar's seek slider replaces
  it), hidden → `line`. The service sends the derived/explicit value
  on connect and whenever bottom-bar visibility changes; TUI changes
  stand for the session but are re-asserted later (excluding it from
  sync-back is what keeps auto stable against our own echoes).
- cmus section footer row (Patrick):
  *"Other cmus settings: use the keyboard — `:set option=value`."*

### App settings section

1. **Music permission**: status row (Granted / Not granted); tap
   requests READ_MEDIA_AUDIO, or opens the app-info screen when
   permanently denied.
2. **Show top bar** (default on) · **Show bottom bar** (default on) ·
   **Show joystick** (default on): visibility toggles, applied live.
   - Top bar hidden → a **faint translucent round settings button
     overlays the terminal's top-right** (JoyDot-style alpha; below
     the status-bar inset) opening the same popover — settings stay
     reachable, per Patrick's spec. The wrapper takes over the top
     inset + status-bar strip painting (win bg); titleStrip keeps
     covering the renderer's top-offset hairline.
   - Bottom bar hidden → the popover gains a **Keyboard** entry (the
     bar's IME toggle is gone) and progress_bar auto flips to `line`.
   - The stage-12 row-quantization remainder split degrades: the
     remainder goes wholly to whichever bar is visible, or to wrapper
     padding when neither is.
3. **Material You control color hue rotation**: slider 0–359°,
   default 180 (stage 16's complement) — parameterizes
   MaterialYouTheme's rotation; change re-pushes the palette live
   (palette-push-only, zero cmus traffic, same as light/dark). Row
   only enabled while Material You is active.
4. **Zoom level** (Patrick's addition): the terminal font size —
   slider over the same clamped range as pinch-zoom, writing the
   existing `font` pref; pinch and the slider are two views of one
   value (a pinch updates the slider next time settings opens, the
   slider applies via MainActivity's onStart re-read through the same
   setFontSize path, chrome metrics included).
5. **Idle quit**: Off / 5 / 15 / 30 / 60 min (default 15; replaces
   the constant).
6. **Always resume paused** (default off): on the first Status after
   a fresh spawn, PLAYING → `player-pause-playback`; skipped when the
   resurrection pending-play flag is set (an explicit play key wins).
7. **Sleep timer action** (Patrick's addition): *Pause playback*
   (default) / *Exit app*. Exit = send `quit` (clean save path),
   clear `resurrect`, service stops with the session death as usual,
   and a visible activity finishes (finishAndRemoveTask).

### Audio settings section

`softvol` (bool) · `dsp.aaudio.performance_mode` (none|power_saving) ·
`dsp.aaudio.allowed_capture` (all|none|system) ·
`dsp.aaudio.sharing_mode` (shared|exclusive) ·
`dsp.aaudio.disable_spatialization` (bool) ·
`dsp.aaudio.min_buffer_capacity_ms` (0–1000, 0 = default) ·
`pause_on_output_change` (bool, subtitle noting the system/app overlap
above). aaudio rows' subtitle: "applies from the next track / stop".

### cmus settings section

The remaining curated list, each rendering from and writing to the
option directly: `follow`, `display_artist_sort_name`,
`ignore_duplicates`, `lib_sort`, `pl_sort`, `progress_bar` (with
Auto, per above), `replaygain`, `replaygain_limit`,
`replaygain_preamp`, `show_hidden`, `show_all_tracks`,
`show_current_bitrate`, `show_playback_position`,
`show_remaining_time`, `skip_track_info`, `start_view` (view names),
`tree_width_percent`, `tree_width_max`. Footer note row.

### Data import/export section

These are **troubleshooting / bad-state cleanup tools (Patrick), so
the resets themselves operate on files only — never cmus commands**:
a wedged cmus can't block them, and no exit-path save can rewrite
what was just deleted. (The pre-save checkbox below is the one
best-effort exception, and it can time out or be unchecked.)
The service-owned primitive is **kill→mutate→respawn**:
TerminalSession's finishIfRunning (SIGKILL — deliberately not `quit`
and not SIGHUP, both of which run save paths), wait for the
session-exit callback, run the file op, respawn (same respawn path
the activity uses; an attached terminal just shows the new
instance). Every entry confirms via dialog first.

The **partial** resets (library / playlists / autosave) would also
lose everything *else* unsaved since the last save — SIGKILL skips
the exit save path by design. So their confirm dialogs carry a
**"Save current state first" checkbox (Patrick), default checked**:
checked → send `android-save`, wait for the `saved` event (bounded
timeout, proceed anyway — a wedged cmus must never block the cleanup,
and unchecking skips the attempt entirely; note the save runs
*before* the kill, then the delete still wins). The full wipe and
zip load skip the checkbox (everything the save would write is about
to be replaced anyway).

- **Save zip**: ACTION_CREATE_DOCUMENT (`application/zip`, suggested
  `cmus-backup-<date>.zip`) → send the new `android-save` line, wait
  for its `saved` event → zip CMUS_HOME (`.cmus/home`) to the URI on
  a background thread (java.util.zip; the socket lives outside home).
  The one entry that talks to cmus (it's non-destructive and wants
  the freshest state); when the IPC is down/unresponsive it times out
  and zips what's on disk instead — export must still work against a
  wedged cmus.
- **Load zip**: ACTION_OPEN_DOCUMENT → confirm (overwrites current
  state) → kill→ **delete every existing file in home first** (a
  restore must not merge — files absent from the zip, stale
  playlists included, must not linger), then unzip (zip-slip guard:
  resolved paths must stay under home) →respawn.
- **Delete library** (not playlists): kill→ delete `lib.pl`
  →respawn (file-level, uniform with the rest; cache kept — it's a
  metadata cache keyed by file, harmless and rebuilt as needed).
- **Delete playlists**: kill→ delete `playlists/*` →respawn.
- **Delete autosave conf**: kill→ delete `autosave` →respawn (cmus
  falls back to default rc; managed opts re-force from prefs on
  connect — which is the "override cmus" behavior working as
  intended).
- **Delete all cmus data**: kill→ clear home →respawn.
- **Reset app prefs** (incl. app-managed cmus settings): confirm →
  clear both prefs files (`term`, `cmus_opts`) → finish back to
  MainActivity, which recreates (Material You default, font/zoom/
  joystick/visibility defaults return). No cmus restart: the next
  Options echo repopulates
  `cmus_opts` from the live session — resetting *app* prefs
  deliberately doesn't reset cmus's own state (the autosave delete
  above does that).

### Debug settings section

**Enable debug receiver** (default off): CmusDebugReceiver fires when
FLAG_DEBUGGABLE **or** the pref is set (release builds become
drivable on opt-in; still exported=false → root adb). While enabled,
the row expands to show the example:

    adb shell "am broadcast -n net.pgaskin.cmus.android/.CmusDebugReceiver -e cmd 'player-pause'"

(whole-command quoting per the stage-10 adb note).

### cmus patch changes

- **0001 amend**: `android-save` intent line — calls the same save
  set the exit path writes (options autosave, library, playlists,
  cache, resume) without quitting, then emits `{"type":"saved"}` (the
  export ack; also stage 19's building block — that stage keeps only
  the timer). Protocol comment updated.
- **New 0003** (upstream candidate, submit after the stage):
  `op/aaudio: fix sharing_mode getter switching on performance_mode`.

### Dotfolder layout (CmusFiles)

```
filesDir/.cmus/terminfo/       (TERMINFO)
filesDir/.cmus/data/           (CMUS_DATA_DIR)
filesDir/.cmus/lib/{ip,op}/    (CMUS_LIB_DIR)
filesDir/.cmus/home/           (CMUS_HOME)
filesDir/.cmus/assets.stamp
filesDir/.cmus/android.sock    (CMUS_ANDROID_SOCKET)
```

Migration in prepare(): rename old `cmus-home` → `.cmus/home` if the
new one doesn't exist (state preserved); delete old `cmus-lib`,
`cmus-data`, `terminfo`, `cmus-assets.stamp`, stale socket. Saved
library/playlist paths are unaffected: pl_env prefixes
(`CMUS_ANDROID_FILES` = filesDir) don't move, and music never lived
under the renamed dirs. `$HOME` (= filesDir, spawn cwd) now shows
only the hidden `.cmus` → empty browser by default; `show_hidden=true`
(a curated setting) reveals it deliberately.

### Framework backup / device transfer (Patrick's rider)

`android:dataExtractionRules="@xml/backup_rules"` with identical
`<cloud-backup>` and `<device-transfer>` sections:

- `domain="sharedpref"`: include all (`term`, `cmus_opts` — the
  managed-options sync means a restored device gets its cmus settings
  re-forced on first connect, no autosave needed for them).
- `domain="file"`: include exactly `.cmus/home/lib.pl`,
  `.cmus/home/playlists`, `.cmus/home/search-history`,
  `.cmus/home/command-history`, `.cmus/home/autosave`,
  `.cmus/home/cache` (Patrick's explicit list).

Everything else is excluded by the include-restriction semantics:
the regenerated trees (lib/data/terminfo), stamp, socket, and —
deliberately, per the list — `resume` and `queue.pl` (a restored
install starts stopped with an empty queue). The stage-10 pl_env
prefixes are what make a restored `lib.pl`/playlists valid on a
different device — saved paths carry `CMUS_ANDROID_*` vars, not
absolute bases. Restore lands at install time before first run;
CmusFiles.prepare already tolerates a pre-populated home (mkdirs +
migration rename only fires when the *old* layout exists). Torn-file
risk is acceptable: home files are only written on exit /
`android-save`, and a mid-write backup pass at worst catches a stale
set the next pass fixes (stage 19's periodic save will also keep
what backup sees fresh).

`material_you` pref default flips to **true** (TermService + every
reader). First launch = Material You + Iosevka. Existing explicit
colorscheme picks are untouched (the pref is already explicitly false
for them, see facts).

## Verify (device, Patrick hands-on)

- SettingsActivity: stock Material look following system day/night
  with the muted accent (no purple, no cmus colors); zoom slider ↔
  pinch round-trip (pinch, reopen settings → slider moved; slide →
  terminal + chrome resize on return).
- Fresh-ish install: Material You by default; `.cmus` layout built,
  old layout migrated with library/resume state intact; browser shows
  an empty home until `show_hidden=true`.
- Settings round-trips: toggle in app → TUI reflects it (`:set`
  echo); `:set` in TUI → row moves; reinstall/force-stop → managed
  values re-forced on connect from prefs. Invalid `lib_sort` text →
  row snaps back on the echo.
- aaudio: `sharing_mode=exclusive` echoes back correctly (0003);
  option applies on next track.
- Bars: each visibility combo lays out flush (row-quantization
  remainder), floating settings button appears top-right when the top
  bar hides, Keyboard popover entry when the bottom bar hides,
  progress_bar auto flips disabled↔line with the bottom bar.
- Hue rotation slider re-tints the control band live at 0/90/180/270.
- Data: export zip → inspect entries (fresh android-save contents);
  wipe-all → import → library/playlists/resume back; a restore over a
  live session leaves nothing extra behind (a playlist created after
  the export is gone — home cleared before unzip); each delete
  removes exactly its files (playlists survive a library delete);
  partial-delete checkbox: with it checked, a just-made TUI change to
  a *surviving* file (e.g. new playlist before a library delete) is
  on disk afterwards, unchecked → it's lost as expected; reset app
  prefs → defaults return, cmus session state untouched.
- Sleep timer in exit mode: fire → clean quit + service/notification
  gone + task closed; BT play key afterwards does **not** resurrect.
- Always-resume-paused: quit while playing → relaunch sits paused at
  position; media-key resurrection still starts playing.
- Debug receiver: off → broadcast ignored (release-style check via
  pref off + debuggable build override noted); on → command lands;
  example command shown in the row works verbatim.
- Backup round-trip (rooted Pixel, local transport): `bmgr
  backupnow` → uninstall → reinstall + restore → library, playlists,
  histories, autosave, cache, and app prefs back (settings/theme/zoom
  as before); resume/queue absent → starts stopped; the plugin/
  terminfo trees rebuilt fresh, not restored.
- patch.sh check green after the 0001 fixup+regen and new 0003; clean
  assembleDebug.

## Commits

1. `cmus: android-save line + saved event (0001); fix aaudio sharing_mode getter (0003)`
2. `app: move extracted assets/libs + cmus state into filesDir/.cmus`
3. `app: SettingsActivity — sections, cmus_opts sync (force + echo-back)`
4. `app: bar/joystick visibility, floating settings button, progress_bar auto`
5. `app: data import/export (zip) + deletes via kill-mutate-respawn`
6. `app: Material You default + hue rotation, sleep-timer exit, resume-paused, debug toggle`
7. `app: scoped backup/transfer rules — prefs + core cmus state`
8. `notes: stage 18 status + architecture`
