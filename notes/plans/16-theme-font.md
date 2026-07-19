# Stage 16 — Theme/font selector overlay + bundled fonts

Goal: the tab bar's right side gains the faint settings icon from the spec
(tap = stage-17 settings screen, later); long-press opens the theme
selector overlay — centered, no scrim, scrollable, two columns
(colorscheme | font), applying live. Colorschemes are the bundled cmus
`.theme` files (applied via the `colorscheme` command — a new
`colorscheme` IPC event echoes the name so the app stays a mirror) plus a
generated **Material You** scheme, which instead redefines entries of the
terminal's 256-color palette to exact dynamic-color ARGB and points the
color_* roles at them with a direct `set` burst (Patrick: direct
color-setting is only for schemes like this; .theme files stay the path
for everything bundled). Fonts are a few bundled monospace ttfs plus the
system default. One small cmus patch amendment (the event); the rest is
Java.

## Facts pinned down

- **`colorscheme <name>` is the apply primitive** (command_mode.c
  cmd_colorscheme): sources `$CMUS_HOME/<name>.theme`, falling back to
  `$CMUS_DATA_DIR/<name>.theme`, error_msg if both fail. Exactly 1 arg →
  names must be space-free (all 17 bundled ones are). Theme files are
  plain `set color_<role>=<value>` lines — the ~28 roles in options.c
  color_names; 256-color themes use bare palette indexes
  (`set color_statusline_bg=234`, `…win_bg=default`, …).
- **Applying re-tints everything with zero new plumbing**: every executed
  command fires the run_parsed_command options-dirty hook → one coalesced
  Options event → CmusTheme diff → chrome + system bars recolor (the
  stage-11 machinery). A burst of `set color_*` lines behaves
  identically.
- **cmus keeps no "current theme name" state** — colorscheme just sources
  set commands and forgets the name. So the new event is transient
  (selected-event class, nothing to snapshot on connect); the app pref
  remembers the last echoed name across launches, and the echo keeps it
  honest from both directions (overlay taps and TUI `:colorscheme`).
  command_mode.c is already patched (the stage-7 options hook), so the
  hook hunk is at home there.
- **The 256-color palette is app-mutable, exactly**: the emulator's
  `TerminalColors.mCurrentColors` is a *public* int[258] (256 + the
  default-fg/bg specials) — mutating it is precisely what OSC 4 does
  (TerminalEmulator.java:2058), `reset()` restores the static
  COLOR_SCHEME, and the renderer reads it per frame, so a mutate +
  view-invalidate recolors the whole TUI with **no cmus involvement at
  all**. This kills the quantization problem: Material tones land as
  exact ARGB in redefined entries instead of nearest-index
  approximations.
- **Consequence for CmusTheme**: its stage-11 assumption ("the live copy
  only diverges via OSC 4/10/11, which cmus never sends") ends the moment
  the app writes the palette — chrome resolution must move from the
  static COLOR_SCHEME to the live palette. The emulator is recreated per
  spawn (initializeEmulator), so overrides must be re-pushed on every
  (re)spawn.
- **Font is a renderer swap**: `TerminalView.setTypeface` (v0.118.3
  TerminalView.java:519) rebuilds the TerminalRenderer — the exact
  setTextSize path pinch-zoom already exercises (updateSize → pty resize
  → onEmulatorSet → the app's android-winch nudge). Bold is
  `setFakeBoldText`, italic `setTextSkewX` (TerminalRenderer.java:
  229–231), so **one regular ttf per bundled font suffices**; Android's
  per-glyph fallback covers missing glyphs (CJK titles etc.).
- **The flushness math must follow the typeface**:
  `ControlBar.lineSpacing`/`firstRowOffset` statics mirror the renderer's
  row metrics with a hardcoded MONOSPACE paint (ControlBar.java:286–298);
  the mirror is only exact measuring the *active* typeface. Chrome text
  hardcodes Typeface.MONOSPACE in five places (tabs, filterBox,
  sleepText, KeyRow keys, ControlBar) — all should render in the
  selected font anyway (blend-with-TUI rule).
- **Dynamic palette**: `android.R.color.system_accent1/2/3_*` +
  `system_neutral1/2_*` tone ramps via getColor. Wallpaper-palette and
  light/dark changes are configuration changes; TermService's
  onConfigurationChanged fires immediately even with the activity gone,
  and a Material update is palette-push-only (below) — no cmus traffic —
  so the service handles it completely, headless included.

## Design — cmus patch (0001 amend): colorscheme event

- `{"type":"colorscheme","name":"night"}` emitted when cmd_colorscheme
  **successfully** sources a file (failed lookup = error + no event, so
  the highlight can't move to a theme that didn't apply). One
  CONFIG_ANDROID-guarded hook line in cmd_colorscheme calling
  `android_colorscheme(arg)`; android.c JSON-escapes and sends like
  `selected`. Transient — not cached, not in the connect snapshot (no
  cmus-side state exists). Protocol comment updated (it's the contract).
- Amend mechanics: fixup commit + `git rebase --autosquash base`,
  `./patch.sh` regen, check green.

## Design — settings icon + overlay

- Settings icon (Material Symbols `settings`, stage-12 fetch pattern) at
  the top bar's right edge, after sleepSlot — always 55% alpha (the
  spec's "small faint round" icon; it has no active state). Part of the
  filter morph's swapped-out set, like sleepSlot. Long-press = theme
  overlay. Tap = **also the overlay for now** — dead taps read as
  broken; stage 17 rebinds tap to the settings screen. [flagged]
- Overlay: PopupWindow centered over the terminal wrapper — no dim (the
  TUI stays visible around it, per spec), focusable (back dismisses),
  outside-touch dismisses; also closed on onStop and the crash screen
  like the stage-14/15 dialogs. Content: two vertical ScrollView columns
  side by side, capped at ~60% of the wrapper height; win_bg background,
  1dp separator-color frame, entries in the active typeface at the
  terminal font size — active entry win_sel_bg/fg, inactive win_fg at
  55% (the tab convention). Paddings/tones tuned on device.
- Colorscheme column: "Material You" pinned first, then the union of
  `cmus-home/*.theme` (user-dropped, wins per cmd_colorscheme's search
  order) and `cmus-data/*.theme` from filesDir, deduped, sorted. Tap →
  `colorscheme <name>` over IPC; the *echo* moves the highlight (CmusIpc
  gains the transient Colorscheme record; MainActivity persists the name
  in a pref for next launch — autosave persists the colors themselves,
  stage-11 rule: cmus is the source of truth, the name rides along).
  Any colorscheme echo also clears materialActive and resets the
  palette overrides (below).
- Font column: "System" + the bundled fonts. Tap applies + persists
  immediately, overlay stays open.

## Design — Material You (palette redefinition + direct set burst)

- `MaterialYouTheme` generates role → exact ARGB from the dynamic ramps
  (uiMode picks dark/light variants). Starting mapping (dark; light
  mirrors the ramps, e.g. neutral1_900 ↔ neutral1_50) — tuned on device:
  - win_bg/cmdline_bg = neutral1_900 · win_fg/cmdline_fg = neutral1_100
  - win_title_bg/statusline_bg = neutral1_800 · their fg = accent1_200
  - titleline_fg = accent1_200 · statusline_progress_fg = accent1_200
  - win_cur = accent1_300 · sel_bg = accent2_700 · sel_fg = neutral1_50
    (inactive variants one step dimmer) · win_dir = accent2_200
  - separator = accent3_500 · error/info keep 196/220
- The distinct ARGB values land in a contiguous run of redefined palette
  entries starting at index 16 (stable role→index assignment); applying
  Material = push the overrides into `mColors.mCurrentColors` +
  invalidate, then send the ~28 `set color_<role>=<index>` lines over
  IPC (every role, so nothing from a previous theme bleeds through) and
  set the materialActive pref. TermService owns the palette push (it
  owns the session; re-pushed on every spawn and attach while active).
- **Light/dark or wallpaper change while active = palette-push only**:
  the autosaved/echoed indexes don't change, just their ARGB —
  regenerate, re-push, invalidate, re-tint chrome. Service
  onConfigurationChanged handles it, foreground or not, zero cmus
  traffic. Relaunch likewise: autosave restores the indexes, the spawn
  re-push restores their meaning.
- Leaving Material (any colorscheme echo): clear materialActive,
  `mColors.reset()` + invalidate — the sourced theme's Options echo
  re-tints chrome as usual. Manual TUI `set color_*` tinkering while
  active just works (the roles are ordinary options; our entries keep
  rendering until the next colorscheme) — highlight staleness there
  accepted. [flagged]
- CmusTheme resolves through the live palette (service-owned model
  passed alongside the Options event) instead of the static
  COLOR_SCHEME — one constructor change, same record-equality diffing.

## Design — bundled fonts

- `assets/fonts/<name>.ttf` + license files beside them (all OFL);
  shortlist per the overview: JetBrains Mono, Fira Mono, IBM Plex Mono
  (regular weights — fake bold/skew cover the rest). Downloaded from
  upstream releases at implementation, committed. ~1MB APK bump. Final
  list = Patrick's call. [flagged]
- Pref `typeface` (asset name, absent = system): MainActivity resolves
  it to `activeTypeface` (createFromAsset, cached) in onCreate
  **before** terminal attach, so the restored font meets the prefs-saved
  grid the headless pty was sized with (the stage-14 font-size pattern).
- Threading: ControlBar.lineSpacing/firstRowOffset gain a Typeface
  param; the five MONOSPACE call sites take activeTypeface (setter on
  KeyRow/ControlBar invalidating their metrics, direct on the top-bar
  views). Font change = setTypeface on the terminal + re-set everywhere
  + persist; the renderer rebuild resizes the pty and the existing
  layout listener re-runs the flushness fixed point at the new metrics.

## Verify (device, wifi adb + logcat + debug receiver)

- Patch: `colorscheme night` via receiver → event with the name + one
  Options echo; TUI `:colorscheme gruvbox` → same; `colorscheme
  nonexistent` → TUI error, **no** event; connect snapshot unchanged.
- Overlay: long-press settings → centered two-column overlay, TUI
  visible around it; scroll both columns; outside tap / back dismiss;
  `night` tap → chrome + status/nav strips + TUI recolor on one echo,
  highlight moves on the echo; quit → relaunch keeps colors (autosave)
  and highlight (pref).
- Material You: apply → TUI + chrome recolor in *exact* dynamic tones
  (spot-check a redefined entry's rendered ARGB against
  system_accent1_200); QS dark-toggle → recolors live with **no** IPC
  traffic in the log (palette-push path), backgrounded toggle included
  (recheck on return); wallpaper change follows; relaunch while active →
  colors restored (autosaved indexes + spawn re-push); `:colorscheme
  night` in the TUI → palette reset + highlight moves + relaunch stays
  night.
- Fonts: each font applied live → terminal + tabs + KeyRow + ControlBar
  all re-render in it, top/bottom flushness holds (uiautomator bounds at
  a couple of font sizes), grid change redraws cmus via the existing
  winch nudge; relaunch → font restored with matching headless grid;
  pinch-zoom still tracks; System restores MONOSPACE.
- Filter morph hides the settings icon; crash screen closes the overlay.
- `./patch.sh check` green after regen; clean assembleDebug; icon
  placement, overlay feel, Material tones, font shortlist = Patrick
  hands-on.

## Risks / decide at implementation

- Tap-opens-overlay is an interim binding until stage 17's settings
  screen; Patrick may prefer a dead tap. [flagged]
- Redefined entries 16+ shadow those xterm-cube colors while Material is
  active — cmus only paints via the color_* roles, all of which Material
  sets, so nothing else can reference the shadowed entries; a user
  hand-`set`ting a color to a low index while active sees a Material
  tone. Accepted (reset on leaving).
- The Material role mapping + light-variant tones will need on-device
  iteration; ~28 set lines per first-apply is trivial socket traffic
  (stage-15 stance), and palette-only updates are free.
- Terminus-style bitmap-look font from the overview's shortlist is
  dropped unless Patrick wants it (TTF renderings of it hint poorly at
  arbitrary dp sizes).

## Commits

1. `cmus: colorscheme event over the android IPC (patch 0001)`
2. `app: settings icon + theme selector overlay (bundled colorschemes)`
3. `app: Material You scheme — palette redefinition + set-color burst`
4. `app: bundled fonts + font selector column`
5. `notes: stage 16 status + architecture`
