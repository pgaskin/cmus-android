# Stage 11 — Chrome A

Goal: the chrome starts existing, and it matches the cmus theme from the
first pixel. Theme color extraction from IPC options (color_* strings →
ARGB via the terminal palette), a text-only view-selector tab bar at the
top, and Android status/nav bar region coloring under enforced
edge-to-edge — all updating live when the theme changes. Needs one small
cmus patch addition: a `view` event, because the current view isn't
observable through the existing protocol and the tab bar must follow
TUI-side `1`–`7` presses and the resume-restored view.

## Facts pinned down

- **color_* serialization** (options.c get_color/set_color): each of the
  26 color options round-trips as `default` (-1), one of 16 names
  (`black red green yellow blue magenta cyan gray` = 0–7,
  `darkgray lightred lightgreen lightyellow lightblue lightmagenta
  lightcyan white` = 8–15), or the bare decimal `16`–`255`. The 13
  `color_*_attr` options (bold/reverse/…) are separate and ignored this
  stage.
- **Palette mapping**: termux `TerminalColors.COLOR_SCHEME.mDefaultColors`
  is the xterm-256 table (indices 0–255) + `COLOR_INDEX_FOREGROUND` 256 /
  `COLOR_INDEX_BACKGROUND` 257 / cursor 258. The live emulator's `mColors`
  starts as a copy and only OSC 4/10/11 mutate it — cmus never sends
  those, so the static scheme is exactly what TerminalView renders and we
  can resolve colors without touching the emulator. `default` resolves to
  index 257 in bg roles, 256 in fg roles.
- **TUI vertical anatomy** (what chrome must blend with): topmost row =
  window title line (`color_win_title_*`); the list area = `color_win_*`;
  bottom rows = titleline/statusline/cmdline. So chrome above the
  terminal adjoins win_title and chrome/insets below adjoin cmdline —
  which is `default` (= terminal bg) in every bundled theme.
- **Edge-to-edge** (targetSdk 36): `setStatusBarColor`/`setNavigationBarColor`
  are deprecated no-ops; the bars are transparent and we already pad the
  inset regions ourselves (stage 6 wrapper), so "coloring the system
  bars" = painting the views behind them. Icon contrast is the one real
  API: `WindowInsetsController.setSystemBarsAppearance` with
  `APPEARANCE_LIGHT_{STATUS,NAVIGATION}_BARS`, chosen by bar-color
  luminance.
- **Current view**: `cur_view` (ui_curses.c) mutates only through
  `set_view()` — startup `set_view(start_view)`, the `1`–`7` keys, and
  the `view` command all funnel there; no option or existing event
  exposes it. `view_names` (options.c) =
  `tree sorted playlist queue browser filters settings`, and the `view`
  command accepts names or 1–7 — names are the protocol-stable choice.
- **android.c is ready for a new event**: dirty flags + one flush per
  main-loop iteration, `android_dirty_all` on connect makes the snapshot
  include it for free. The flush runs after `update()`, so a view set
  this iteration is visible when the event is composed.
- **CmusIpc replays cached events** to late listeners on the main thread,
  so the activity can attach whenever it likes and immediately get
  Options (+ the new View). The activity already holds the bound service;
  it just needs a `getIpc()` getter. One wrinkle: session respawn creates
  a *new* CmusIpc instance, so the activity must re-register its listener
  after every `getSession()` and track which instance it registered on
  (double-add on the same instance = duplicate callbacks).

## Design — cmus patch (0001 amended)

- `{"type":"view","view":"tree|sorted|playlist|queue|browser|filters|settings"}`
  — on connect (snapshot) and on change. Name from `view_names[cur_view]`.
- android.c/h: `android_view_changed()` setter (same shape as
  `android_options_dirty`); flush emits the event on
  `android_dirty_all || view dirty`. Protocol comment updated — it's the
  contract.
- One new hook hunk: `set_view()` calls `android_view_changed()` after
  the early-return-if-unchanged guard. Startup ordering doesn't matter
  (connect snapshot always carries the current value).
- `./patch.sh` regen: 0001 grows, 0002 untouched (expect the usual
  `From <sha>` churn only).

## Design — CmusIpc

- New `record View(String name) implements Event`, parsed from the
  `view` field; cached + replayed like Status/Volume/Options, `view()`
  getter beside the others. The name stays a String (forward-compatible;
  the UI maps known names to tabs and ignores unknowns).

## Design — theme colors (new `CmusTheme`)

- A record of resolved ARGB ints built from an `Options` event:
  `winBg winFg winTitleBg winTitleFg statuslineBg statuslineFg cmdlineBg
  separator` (statusline/cmdline/separator resolved now because stage 12's
  bottom bar wants them; only win/winTitle are consumed this stage).
- Resolution: option string → index (`default` → -1, name table, or
  parseInt) → `TerminalColors.COLOR_SCHEME.mDefaultColors[idx]`, with -1
  falling back per role to index 257 (bg) / 256 (fg). Unparseable or
  missing → same fallback (can't happen from real cmus; don't crash on
  it).
- Record equality = free change detection: rebuild on every Options
  event, re-apply only when changed (options events are already
  coalesced cmus-side).
- Small helper: `isLight(int color)` via `Color.luminance() > 0.5` for
  the system-bar appearance flags.

## Design — MainActivity layout + tab bar

- Root becomes a vertical LinearLayout: **tab bar** on top, the existing
  terminal FrameLayout wrapper below with weight 1. Insets split: the tab
  bar consumes top + left/right (statusBars+cutout) as its own padding so
  its background paints the status-bar strip; the terminal wrapper keeps
  left/right/bottom + ime as today, its background painting the nav-bar
  strip and side margins.
- Tab bar: 7 text-only tabs (TextViews in a LinearLayout inside a
  scrollbar-less HorizontalScrollView as the doesn't-fit fallback),
  monospace (Typeface.MONOSPACE until the stage-16 font work), compact
  (~12sp, tight vertical padding). Labels from the view names (whether to
  relabel `tree` → `library` is an eyeball call on device).
- Colors: tab bar bg = winTitleBg; active tab = winTitleFg; inactive
  tabs = winTitleFg at ~55% alpha. Terminal wrapper bg = winBg (adjoins
  the list rows on the sides; the bottom strip adjoins cmdline, which is
  terminal-default bg in all bundled themes — if a custom theme makes
  this ugly, revisit then). Status-bar appearance from winTitleBg
  luminance, nav from winBg.
- Behavior: tap → `ipc.send("view <name>")`; the highlight moves only
  when the View event comes back — cmus is the single source of truth,
  no optimistic local state, and TUI-side switches/cold-start resume come
  through the same path. Until the first Options event the chrome stays
  on the current black `Theme.Cmus` (the snapshot arrives within ms of
  connect; no flash handling).
- Wiring: `TermService.getIpc()` getter; the activity registers one
  listener (Options → re-tint, View → highlight) after every
  `getSession()`, guarded by instance identity for the respawn path;
  removed in onDestroy. Crash/frozen screen keeps the last colors; tab
  taps against a dead ipc drop with a log (existing send() behavior).

## Verify (device, wifi adb + logcat + debug receiver)

- Cold start, default colorscheme: tab bar present and blending with the
  TUI title row; status/nav strips colored; tap each tab → TUI view
  follows; `1`–`7` in the TUI → highlight follows.
- `colorscheme night` / `gruvbox` / `zenburn` via the debug receiver →
  entire chrome recolors live off one coalesced options event (remember
  gruvbox sets color_win_bg=default — check win_title/statusline when
  eyeballing).
- `set color_win_title_bg=white` → status-bar icons flip dark
  (APPEARANCE_LIGHT_STATUS_BARS); back to a dark theme → flip light.
- Quit with a non-tree view active → relaunch: resume restores the view
  and the snapshot's view event highlights the right tab.
- Rotation and IME-open keep insets/colors/highlight right;
  background-quit → refocus respawn re-registers the listener (colors +
  view correct on the new ipc instance).
- `toybox nc -U` protocol check: view event in the connect snapshot and
  on change, valid JSON.
- `./patch.sh check` green after regen, patches stable modulo the known
  first-line churn; clean `./gradlew assembleDebug`.

## Risks / decide at implementation

- Tab labels + sizing on narrow portrait (raw view_names ≈ 45 chars of
  monospace; scroll vs shrink vs shorter labels) — decide on device.
- Bottom-strip color uses winBg, not cmdlineBg — correct for every
  bundled theme; noted above as a revisit-if-ever-visible.
- Attr options (bold/reverse on win_title etc.) ignored; if a theme
  leans on reverse-video for the title row the tab bar won't mirror it —
  acceptable until the theme-selector stage.
- Luminance threshold 0.5 for icon flips — cheap to tune.
- TerminalView's own leftover margin (grid quantization remainder inside
  the wrapper) shows the wrapper's winBg behind the last cells — should
  already read as seamless; check while eyeballing.

## Commits

1. `cmus: current-view event in the android IPC protocol (patch 0001)`
2. `app: CmusIpc view event + CmusTheme color resolution`
3. `app: view-selector tab bar + theme-colored chrome and system bars`
4. `notes: stage 11 status + architecture`
