# Stage 12 — Chrome B

Goal: the compact bottom control bar — play/pause, repeat, shuffle, seek
bar, volume button with a vertical slider popup (gated on softvol),
add-selected-to-queue, and keyboard toggle — colored off the statusline
theme colors and driven entirely by the existing IPC events. Java-only
stage: no cmus patch changes, every needed signal already flows.

## Facts pinned down

- **All state is already observable.** `repeat` ("true"/"false"),
  `shuffle` ("off"/"tracks"/"albums" — tristate since cmus 2.10;
  "false"/"true" are set-only aliases), and `softvol` ("true"/"false")
  arrive in every Options event. Play state/track/duration/position come
  from Status, per-second advance from Position (≤1/s while playing,
  suppressed when a status event goes out), volume from Volume
  (left/right percent, -1 = unknown). Cached-event replay on
  addListener means a late/re-attached activity reconstructs the whole
  bar (stage-11 attachIpc pattern covers respawn).
- **TUI-side toggles are visible.** Key bindings run via keys.c
  handle_key → `run_command` → `run_parsed_command`, whose tail calls
  `android_options_changed()` — so TUI `r` (toggle repeat) / `s`
  (toggle shuffle) / `:set softvol` produce coalesced Options events
  and the buttons can follow cmus rather than track local state.
- **Commands** (all existing): `toggle repeat`, `toggle shuffle`
  (cycles off→tracks→albums; cmus itself skips albums outside library
  play, options.c toggle_shuffle), `seek <n>` (absolute seconds;
  upstream clamps to duration−5, known from stages 5/9), `vol <n>`
  (both channels, percent), `win-add-q` (append selected to queue).
  Play/pause mapping reuses MediaControl's reasoning: `player-play`
  *restarts* a loaded track, so paused → `player-pause` (toggle),
  stopped → `player-play`, playing → `player-pause-playback`
  (idempotent pause, no toggle race).
- **Volume gating** (spec): the aaudio op has no mixer, so volume
  without softvol is an error — the volume button only exists when
  `softvol=true`.
- **CmusTheme is ready**: statuslineBg/statuslineFg (and cmdlineBg,
  separator) were resolved in stage 11 for exactly this bar.
- **Insets**: the bar becomes the bottom-most chrome, so it takes over
  the bottom systemBars+ime padding from the terminal wrapper and its
  background (statuslineBg) paints the nav-bar strip; nav icon
  appearance flips off statuslineBg luminance instead of winBg. The
  terminal keeps shrinking above the IME via the LinearLayout weight.

## Design — ControlBar view

- New `ControlBar extends LinearLayout` (horizontal), owned by
  MainActivity like the tab bar; MainActivity stays the single IPC
  listener and forwards events + theme. Callback interface for
  sending commands + the keyboard toggle (MainActivity already has
  `sendCommand` and `toggleSoftKeyboard`).
- Row layout, left to right: **play/pause · repeat · shuffle ·
  seek bar (weight 1) · volume · add-to-queue · keyboard**.
  ImageButtons with borderless ripple. Sizing tracks the terminal
  font like the tabs (Patrick): bar ≈ **3 terminal lines** tall,
  icons ≈ **2 lines**, where a line = Paint.getFontSpacing of
  MONOSPACE at the terminal font size (what TerminalRenderer uses);
  recomputed on pinch-zoom in onScale beside the tab text.
- Icons: hand-written VectorDrawables in res/drawable (play, pause,
  repeat, shuffle, volume, queue-add, keyboard — material-symbols-like
  paths, no new deps). Text glyphs (▶/⏸) rejected: emoji-presentation
  fallback on Android is device-dependent. Tinting at theme-apply time
  via setImageTintList: statuslineFg for active/normal, statuslineFg at
  ~55% alpha for inactive toggles (same constant as the tab bar).
- Button state = pure mirror of cmus, no optimistic local state
  (stage-11 rule): tap sends the command, the icon changes when the
  event echoes back.
  - play/pause: pause icon while PLAYING else play icon; command per
    the mapping above (needs the cached Status' state at tap time).
  - repeat: active tint when repeat=true; tap → `toggle repeat`.
  - shuffle: inactive when off, active when tracks, active + "albums"
    distinguisher when albums (small badge or overlaid "A"; pick
    whatever reads at 24dp on device); tap → `toggle shuffle`.
  - add-to-queue: momentary; tap → `win-add-q` (acts on the selection
    in the current view; errors surface in the TUI per protocol).
  - keyboard: momentary; tap → toggleSoftKeyboard(). The stage-11
    long-press interim stays as-is (stage 14 repurposes long-press).

## Design — sliders (seek + volume)

- One custom `CmusSlider` view, horizontal/vertical parameter, used by
  both. Rationale: framework SeekBar needs layer-list drawable surgery
  to not look Material against the TUI, and its vertical story is a
  rotation hack; a flat themed track + block thumb is ~100 lines of
  onDraw/onTouchEvent, matches the aesthetic exactly, and both
  orientations share it. Colors: track = statuslineFg at low alpha,
  filled portion + thumb = statuslineFg. API: max/progress setters, a
  drag listener (start / change / release), disabled state.
- **Seek bar**: max = duration, progress = position, from Status;
  Position events update progress. While the user drags, incoming
  updates are ignored (drag flag); release sends `seek <progress>`.
  No local ticker — 1s event granularity is fine at bar width. No
  track (Status without file) or STOPPED → disabled at zero.
- **Volume popup**: volume button toggles a PopupWindow anchored above
  the button (statuslineBg background, ~40×160dp) holding a vertical
  CmusSlider, 0–100. Progress from Volume events (use left; channels
  only diverge via `vol L R`, and setting rewrites both). Drag sends
  `vol <n>` on each integer change — echoes are suppressed by the drag
  flag, socket cost is nil. -1 (unknown) → slider disabled. Dismiss on
  outside touch; dismiss (don't orphan) on theme re-apply and respawn.
- softvol gating: volume button GONE unless options has softvol=true;
  flips live on the Options event (popup dismissed if open).

## Design — MainActivity wiring

- Root LinearLayout gains the bar: {tab bar, terminal wrapper (weight
  1), control bar}. Insets listener: tab bar keeps top+sides; terminal
  wrapper keeps sides only; control bar takes sides + bottom
  (systemBars+cutout+ime union, as today's wrapper bottom).
- ipcListener switch grows Status/Position/Volume arms forwarding to
  the bar; the Options arm additionally feeds repeat/shuffle/softvol
  (raw option strings) to the bar beside the CmusTheme rebuild.
  applyTheme() tints the bar + popup and switches the nav-bar
  appearance source from winBg to statuslineBg.
- Crash screen / dead ipc: sendCommand already drops with a log; the
  bar just goes stale like the tab bar — fine, the activity is on its
  way out or respawning.

## Verify (device, wifi adb + logcat + debug receiver)

- Bar renders in statusline colors, nav strip matches, icons legible;
  `colorscheme night` / `gruvbox` / `zenburn` via debug receiver →
  bar + popup + nav strip recolor off one Options event;
  `set color_statusline_bg=white` → nav icons flip dark.
- Play/pause from all three states (incl. stopped → player-play
  restart-from-nothing) with the icon following the Status echo;
  pause from the TUI (`c`) → icon follows.
- Repeat/shuffle: taps cycle and the TUI statusline flags agree; TUI
  `r`/`s` → buttons follow; shuffle shows the albums state on the
  second tap (library play).
- Seek: drag mid-track → audible jump, TUI + bar position agree;
  near-end drag clamps to duration−5; steady playback advances the
  bar ~1/s; dragging during playback doesn't stutter (drag flag).
- Volume: cold state softvol=false → no button; `set softvol=true` →
  button appears; slider drag audibly changes volume, TUI statusline
  vol tracks; TUI `+`/`-` → slider follows; `vol 30 70` split → bar
  shows left, setting from the slider re-merges channels.
- add-to-queue: select a track in library, tap → appears in queue
  view (tab tap to check); with nothing sensible selected the TUI
  shows the error, app doesn't care.
- Keyboard button toggles the IME both ways; terminal resizes; the
  bar rides above the keyboard (ime inset).
- Rotation keeps bar state; background-quit → refocus respawn rebuilds
  the full bar from the replayed snapshot (fresh ipc instance path).
- Stopped cold start: seek bar disabled at zero, play button starts
  playback of the resume-restored track.
- `./patch.sh check` green (no cmus changes); clean assembleDebug.

## Risks / decide at implementation

- Icon look (vector paths drawn by hand) vs the TUI at various font
  scales — eyeball on device.
- Shuffle albums-state distinguisher at icon size — pick on device.
- Volume popup vs inline slider: popup per spec ("button toggling a
  vertical slider"); if PopupWindow anchoring fights the IME or
  insets, fall back to an inline expanding vertical slider overlaying
  the terminal's right edge.
- Seek-while-paused: protocol wording says position events flow on
  seek; if a paused seek turns out not to emit one, the release
  handler already sets the bar to the dragged value — self-healing,
  just verify.
- Bar always visible this stage; per-control visibility toggles are
  stage 17 (settings).

## Commits

1. `app: control-bar icons + CmusSlider widget`
2. `app: bottom control bar — playback/seek/volume/queue/keyboard off CmusIpc`
3. `notes: stage 12 status + architecture`
