# Stage 13 — Input A

Goal: the horizontally scrolling extension key row that appears above the
soft keyboard whenever the IME is visible — shift/ctrl/alt · del/esc/tab ·
arrows · home/end/pgup/pgdn — with termux-style sticky modifiers that
apply to both the row's own keys and characters typed on the IME.
Java-only stage: no cmus patch changes, keys ride the existing pty.

## Facts pinned down

- **The injection path already exists in the lib.** termux's own extra
  keys row (termux-shared ExtraKeysView + TerminalExtraKeys) dispatches
  named keys by constructing a `KeyEvent` and calling
  `TerminalView.onKeyDown(keyCode, event)` directly — handleKeyCode →
  KeyHandler emits the escape sequence into the session. (The reference
  impl passes an ACTION_UP event; irrelevant, onKeyDown never checks the
  action.) Every key we need has a KeyHandler mapping: DPAD arrows,
  FORWARD_DEL, ESCAPE, TAB, MOVE_HOME/MOVE_END, PAGE_UP/PAGE_DOWN.
- **Sticky modifiers are already plumbed through TerminalViewClient.**
  TerminalView merges `readControlKey()/readAltKey()/readShiftKey()`
  into the effective meta state on *every* input path — hardware/injected
  key events (onKeyDown) and IME-typed code points (inputCodePoint;
  shift additionally via the input-connection uppercase mapping). So
  once MainActivity's stubs delegate to the row, sticky ctrl + a typed
  `t` = ^T with zero extra work, exactly like termux. Injected keys need
  no metaState of their own — the same merge covers them.
- **Application keypad mode is a non-issue.** cmus initializes ncurses
  with keypad(), which emits smkx (`\E[?1h\E=`); the emulator is
  therefore in application-cursor mode and KeyHandler's appMode
  sequences (`\eOH`, `\eOF`, …) are exactly the terminfo khome/kend
  strings ncurses parses. Arrows/home/end arrive as the named keys, not
  stray characters.
- **Every key is observable through default binds** (data/rc): up/down =
  win-up/down, left/right = seek ∓5, home/end = win-top/bottom,
  page_up/down = win-page-up/down, tab = win-next, delete = win-remove,
  ESC cancels command/search mode. `bind -f common ^T …` / `M-t …` via
  the debug receiver makes ctrl/alt deterministic to verify.
- **termux's sticky model** (mirror it): tap = one-shot — active until
  the next key consumes it; long-press = locked — stays on until tapped
  off; the read call auto-clears one-shot state
  (readSpecialButton(autoSetInActive=true) semantics).
- **The IME toggle point already exists**: the root insets listener
  fires on every IME show/hide; `insets.isVisible(Type.ime())` is the
  row's visibility signal — no new listener.
- Reading of the spec's "space" separators: **inter-group gaps**, not
  space keys — the IME the row coexists with has a spacebar, and the
  gaps make the four groups scannable. (Flagged below for Patrick.)

## Design — KeyRow view

- New `KeyRow`: scrollbar-less fillViewport HorizontalScrollView around
  a horizontal LinearLayout — the tab-bar pattern verbatim: the row's
  own gravity centers the groups when they fit, overflow lays out from
  the left and scrolls (stage-11 lesson: no CENTER layout gravity on
  the child).
- Four groups with a fixed dp gap between them:
  **shift ctrl alt · del esc tab · ← ↓ ↑ → · home end pgup pgdn**.
  Lowercase text labels to match the TUI/tab-bar aesthetic; arrows are
  the ←↓↑→ glyphs (MONOSPACE covers them). No new icons.
- Buttons: monospace TextViews at the terminal font size (tracks
  pinch-zoom like the tabs), dp padding for tap area, borderless
  ripple, KEYBOARD_TAP haptic on dispatch (respects the system haptics
  setting via performHapticFeedback).
- Colors: cmdline bg + statusline fg — the row adjoins the control bar
  and reads as part of the same band. Modifier states: inactive = plain
  fg text; one-shot active = inverted block (fg-colored bg, bg-colored
  text); locked = inverted + underline (pick what reads on device).
- Ordinary keys: tap → dispatch callback with the keycode. Long-press
  on arrows/pgup/pgdn auto-repeats (postDelayed ~400ms then ~75ms,
  cancel on up/cancel) — list navigation is the whole point of the row.
- Modifiers: tap toggles one-shot; long-press locks; any tap while
  active or locked clears. Exposes `readShift()/readCtrl()/readAlt()`
  with consume-on-read for one-shot (locked survives reads); consuming
  posts a visual refresh so the block un-inverts right after the key.

## Design — MainActivity wiring

- Root becomes {tab bar, terminal wrapper (weight 1), control bar,
  **key row**} — the row sits bottom-most, directly atop the IME
  (termux position), so the control bar keeps adjoining the terminal
  and the row-quantization barExtra logic is untouched.
- Insets: when the row is visible it takes over the sides+bottom(+ime)
  padding from the control bar (one branch in applyChromePadding off
  row visibility); GONE restores today's layout exactly. Its cmdlineBg
  matches the control bar's, so nothing flashes during the IME
  animation over the nav strip.
- Visibility: the insets listener toggles GONE/VISIBLE on
  `isVisible(Type.ime())`. On hide, clear all sticky state (one-shot
  *and* locked) — a modifier you can't see must never eat the next
  key.
- `readControlKey/readAltKey/readShiftKey` stubs → `keyRow.readX()`;
  `readFnKey()` stays false (no fn key in scope). Key dispatch callback
  → `terminalView.onKeyDown(keyCode, new KeyEvent(...))` with
  metaState 0 (the readX merge supplies the modifiers).
- applyTheme() tints the row beside the control bar; onScale updates
  its text size. Crash screen: the row goes inert like the rest of the
  chrome (onKeyDown on a dead session is a no-op).

## Verify (device, wifi adb + logcat + debug receiver)

- Keyboard button → IME + row appear together, row directly above the
  IME in control-bar colors; terminal resizes above; toggle off → row
  gone, layout byte-identical to stage 12 (insets handoff).
- Arrows: ↑/↓ move the selection; ←/→ seek ∓5 with the TUI and the
  control-bar seek thumb agreeing; long-press ↓ (input swipe with
  duration) → repeated scroll.
- home/end/pgup/pgdn → win-top/bottom/page motions (proves the appMode
  sequences match terminfo through the whole stack).
- tab → win-next flips panes in the tree view; esc cancels a `:`
  command line; del in the **queue** view removes the selected entry
  (not the library — win-remove there deletes the tracked entry).
- Sticky shift: `:` command mode, tap shift, IME `a` → `A`, next `a`
  lowercase (one-shot consumed, block un-inverts); long-press shift →
  `AAA` until tapped off (locked survives reads).
- Sticky ctrl: `bind -f common ^T view queue` via debug receiver, tap
  ctrl + IME `t` → queue view, tab highlight follows the echo.
- Sticky alt: `bind -f common M-t view browser`, alt + `t` → browser.
- Modifier + row key: shift/ctrl states also merge into injected keys
  (spot-check one, e.g. ctrl+arrow reaching cmus as a distinct
  sequence in the emulator, even if unbound).
- Hide the IME with shift locked → reopen: state cleared.
- Overflow: `wm size 600px` → row scrolls (swipe on the row's own y),
  full width → groups centered. Pinch-zoom → key text tracks the font.
- Rotation mid-IME: recreation follows the insets like the rest of the
  chrome; respawn path unaffected (row has no IPC state).
- `./patch.sh check` green (no cmus changes); clean assembleDebug.

## Risks / decide at implementation

- **"space" reading** — if Patrick meant literal space keys between the
  groups (tree expand/collapse without reaching for the IME spacebar),
  adding a `space` key to the del/esc/tab group is a two-line change.
- One-shot consume-on-read: TerminalView can consult readShiftKey on
  more than one path for a single IME character (input-connection
  uppercase mapping vs inputCodePoint). termux ships these exact
  semantics, so it should hold; if a double-read eats the one-shot
  early on some IME, switch to consume-on-dispatch.
- Locked-vs-active visual and lowercase labels at small fonts —
  eyeball on device.
- Touch targets at the 5dp font floor: if unusable, clamp the row's
  text size to a dp floor independent of the terminal font (the row is
  a keyboard, not chrome that must stay flush with TUI rows).
- Per-control visibility/enable toggles are stage 17 (settings); the
  row is unconditional-with-IME this stage.

## Commits

1. `app: KeyRow — extension key row widget with sticky modifiers`
2. `app: show the key row above the IME; sticky modifiers feed TerminalView`
3. `notes: stage 13 status + architecture`
