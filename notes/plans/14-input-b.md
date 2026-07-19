# Stage 14 — Input B

Goal: long-press sends a right-click and (in the track-list views) offers a
native confirm dialog naming the pressed row before `win-remove`; the faint
minecraft-style joystick dot bottom-right (tap = enter, slide up/down =
repeating arrows, slide far left = tab); text selection disabled for good;
gesture polish (retire the interim long-press keyboard toggle, persist the
pinch-zoomed font size). Java-only stage: no cmus patch changes.

## Facts pinned down

- **One seam covers both long-press features.** TerminalView's gesture
  listener calls `mClient.onLongPress(event)` and only falls through to
  `startTextSelectionMode` when it returns false (v0.118.3
  TerminalView.java:250–255, the lib's *only* selection entry point). So
  MainActivity returning true unconditionally simultaneously disables text
  selection and gives us the long-press hook. The interim
  keyboard-on-long-press toggle there is retired (the control bar's
  keyboard button replaced it in stage 12, as planned since stage 6).
- **Right-click injection needs no lib changes.**
  `TerminalEmulator.sendMouseEvent(button, col, row, pressed)` is public
  and takes the raw protocol button code — right button = 2 in both the
  SGR and legacy X10 encodings (the lib defines constants only for
  left/wheel, but passes the int straight through);
  `TerminalView.getColumnAndRow(event, false)` is the public version of
  the exact coordinate math the lib's own `sendMouseEventCode` uses. Send
  press+release like the lib's left-click tap, gated on
  `isMouseTrackingActive()` the same way (cmus is an altscreen app, so
  screen rows are protocol rows; no scrollback offset exists).
- **A right-click's only effect in cmus is moving the selection.** cmus's
  mousemask includes BUTTON3 press/click (options.c update_mouse);
  mevent_to_key maps it to the `mrb_click*` bindable names — and the
  default rc binds none of them. Before dispatch,
  normal_mode_mouse_handle (keys.c) moves the selection to the clicked
  row (window_set_sel) and switches the active pane in the two-pane
  views; clicks on the title row (y=0), the status/command rows
  (y≥LINES−2), the tree separator column, or past the end of a list
  return NULL and change nothing. That selection-move is exactly the
  primitive the confirm-then-remove flow needs.
- **win-remove is prompt-free only in the track-list views.**
  cmd_win_remove: tree (artist/album/track), sorted, playlist *track
  pane*, and queue remove immediately; playlist *list pane* ("Delete
  selected playlist?"), browser ("Delete file?"), filters, and settings
  (binding removal) all go through yes_no_query — which **blocks cmus's
  main loop on getch()** until a key arrives on the pty, with the prompt
  only visible in the TUI cmdline. Triggering those over IPC (possibly
  with no keyboard up) would wedge cmus behind an invisible prompt, and
  our dialog would double-confirm anyway. So the app-side remove flow
  covers exactly {tree, sorted, playlist:track-pane, queue}; elsewhere
  long-press still right-clicks (selection moves natively) and removal
  stays the native TUI path — KeyRow's del key → cmus's own [y/N] in the
  cmdline, answered on the IME (verified working in stage 13).
- **The dialog names the row by reading the terminal buffer — no cmus
  patch.** There's no selection-query channel in the IPC protocol, but
  the app owns the screen: `TerminalBuffer.getSelectedText(x1, y, x2, y)`
  returns the rendered text of the pressed row — the same row the
  right-click just selected, formatted by cmus itself (what you see is
  what you remove). Blank text ⇒ the press landed past the list end and
  the selection didn't move ⇒ no dialog.
- **The two-pane split is mirrorable app-side.** resize_tree_view:
  `tree_win_w = (int)(w * tree_width_percent/100.0f)`, clamped to
  tree_width_max when nonzero and to ≥3; `track_win_x = tree_win_w + 1`;
  the same value serves the tree and playlist views (keys.c). Both
  options arrive in Options events, so the app can trim the row text to
  the pressed pane, skip the tree separator column, and tell the
  playlist's list pane (no dialog) from its track pane (dialog).
- **Command/search mode is detectable from the screen too.** In those
  modes mouse events go to command/search_mode_mouse (no list-selection
  move), but an IPC `win-remove` executes regardless of input mode — the
  dialog could remove a stale selection. The cmdline (last row) renders
  the `:` / `/` / `?` prefix exactly while one of those modes is active;
  checking its first character is a sufficient guard.
- **Font size isn't persisted.** onCreate resets to dp(13); the service
  *does* persist the last attached pty size for headless respawn sizing,
  so reopening after a pinch-zoom session spawns at the old grid and
  immediately resizes (layout flash + a resize cmus redraw). Persisting
  fontSize in the same prefs closes the loop.
- View names for gating, as echoed in View events (options.c):
  `tree sorted playlist queue browser filters settings` — MainActivity
  already tracks the echoed `viewName` (stage 11) and the queue-button
  flip (stage 12) established the gate-on-echoed-view pattern.

## Design — long-press → right-click → confirm → win-remove

- MainActivity.onLongPress: **always return true**. If the crash screen
  is up, the session is dead, or mouse tracking is off → consume and do
  nothing (no selection mode ever). Otherwise: LONG_PRESS haptic,
  compute (col,row) with getColumnAndRow, send BUTTON3 press+release
  through mEmulator (cmus moves selection / switches pane natively),
  then offer the dialog iff *all* guards pass:
  - row ∈ [1, rows−3] (list area only — title/status/cmdline excluded);
  - echoed view ∈ {tree, sorted, playlist, queue}; for playlist, col in
    the track pane (col ≥ trackWinX); for tree, col not on the
    separator, and the pane boundaries used to trim the row text;
  - cmdline row's first char ∉ {`:`, `/`, `?`} (not in command/search
    mode);
  - pane-trimmed row text non-blank (press landed on a real row).
- Dialog: framework AlertDialog — message = the trimmed row text,
  positive button "Remove" → `win-remove` over IPC, negative "Cancel".
  At confirm, re-check the echoed view still matches the one captured at
  press (a TUI view switch behind the dialog drops the action);
  dismissed on session death alongside the rest of the crash handling.
- trackWinX mirror: small static helper (ControlBar-statics pattern)
  computing the split from cols + tree_width_percent + tree_width_max
  out of the cached Options — same float expression as the C for
  identical truncation.

## Design — joystick dot (JoyDot view)

- Custom View overlaid in terminalWrapper (beside titleStrip), gravity
  BOTTOM|END, ~16dp margin, ~76dp square touch area. Faint virtual-stick
  look: base circle (~20dp radius) + knob (~12dp) that tracks the finger
  clamped to the base radius; statusline fg at low alpha (base fainter,
  knob stronger — exact alphas eyeballed on device), re-tinted in
  applyTheme with the rest of the chrome. Fixed dp size (it's a control,
  not TUI-flush chrome); rides above the IME automatically since the
  wrapper shrinks.
- Gestures (plain onTouchEvent; no detector needed):
  - down: record origin; nothing fires.
  - vertical: |dy| past ~0.75×radius → DPAD_UP/DOWN — fire once on
    crossing, then repeat at an interval interpolating ~300ms → ~75ms as
    displacement grows toward ~3×radius (deeper = faster, the joystick
    feel); direction may reverse mid-gesture; re-crossing re-fires
    immediately.
  - horizontal: dx past ~2×radius left ("far left") → TAB once, re-armed
    with hysteresis (finger back inside ~1.5×radius); vertical repeat is
    suppressed while in the tab zone.
  - up: within touch slop and nothing fired → ENTER. up/cancel/detach →
    all repeats stop.
- Injection: the same callback KeyRow uses —
  `terminalView.onKeyDown(keyCode, ACTION_UP event)` — so KeyRow's
  sticky modifiers merge into dot keys for free. KEYBOARD_TAP haptic per
  dispatched key (matches KeyRow).
- Hidden on the crash screen (it must not eat the tap-to-close); it has
  no IPC state, so respawn/rotation need nothing.

## Design — polish

- fontSize saved to prefs on every onScale change and restored (clamped)
  in onCreate — reopen renders at the size the pty was saved at, no
  resize flash.
- No KeyRow changes (haptics/ripple exist since stage 13).

## Verify (device, wifi adb + logcat + debug receiver)

- Library tree: long-press a track → selection jumps to it + dialog
  showing that row's text; Remove → gone from the TUI; Cancel → intact.
  Long-press an artist in the left pane → dialog names the artist row;
  pane trim = no track-pane text bleeding in. adb long-press =
  `input swipe x y x y 600`.
- sorted + queue: same flow; queue removal leaves the library intact.
- playlist: track pane → dialog; list pane → selection moves, no dialog.
- browser/filters/settings: long-press moves the selection only; KeyRow
  del in browser still prompts in the TUI cmdline (native path intact).
- Title row, status row, cmdline row, blank area below a short list:
  no dialog (click sent, harmless).
- `:` command mode, long-press a list row → no dialog (guard); esc →
  works again.
- Text selection unreachable: long-press everywhere with mouse on, then
  `set mouse=false` via the receiver → long-press does nothing, no
  selection handles; `set mouse=true` restores the flow.
- Tree pane math: `set tree_width_percent=61` (odd value) → pane trim
  and separator skip still line up with the TUI split.
- Joystick: tap → enter (win-activate plays the selection); hold
  up/down → repeating scroll, visibly faster with displacement; slide
  far left → tab switches panes in tree; hysteresis = one tab per
  crossing; sticky ctrl (KeyRow) + a dot key spot-check (modifier
  merge). Feel/alpha tuning = Patrick hands-on (adb can't judge it).
- Crash screen (`kill -ABRT` cmus): dot hidden, no dialogs, tap/Enter
  still closes; relaunch clean.
- Font persist: Patrick pinches to a new size → relaunch → same size,
  no resize flash on the headless re-attach; rotation unaffected.
- `./patch.sh check` green (no cmus changes); clean assembleDebug.

## Risks / decide at implementation

- **Dialog text is the raw rendered row** — includes the duration
  column, tree indent, play markers. That's "remove exactly what you
  see", and it's the zero-patch design; if Patrick prefers clean tag
  metadata (title/artist), that's a cmus-patch selected-item event for
  a later stage. [flagged]
- The float pane-split mirror must truncate exactly like the C — same
  expression, verified with odd tree_width_percent values; worst case
  is a one-column trim error, cosmetic.
- The cmdline-mode guard reads a rendered prefix char; an error message
  starting with `:` would false-positive-skip the dialog. Cosmetic —
  the retry works.
- Dot thresholds, repeat cadence, alphas, and whether the dot should
  hide while the IME/KeyRow is up (arrow duplication) — ship visible
  with the numbers above, tune on device.
- If BUTTON3 turns out not to reach cmus through the emulator's mouse
  encoding on device (all code paths say it does), fall back to
  emitting the SGR sequence directly via session write — but verify the
  clean path first.

## Commits

1. `app: long-press = right-click; confirm dialog removes the pressed row`
2. `app: joystick dot — enter/arrows/tab gestures bottom-right`
3. `app: persist the pinch-zoomed font size across launches`
4. `notes: stage 14 status + architecture`
