# Stage 21 — Direct touch input toggle + on-demand floating joystick

Goal: a **"Direct touch input"** app setting for cmus's `mouse` option
(default on, today's forced-true behaviour). When it's **off and the
joystick is on**, the joystick stops living in its fixed bottom-right
corner and becomes an **on-demand floating stick**: invisible at rest,
it materialises under the finger wherever a touch lands on the terminal,
and its rest-in-place **long-press fires a right-click instead of the
reposition drag** (repositioning is meaningless for a stick that appears
wherever you press). The right-click acts on **cmus's current selection
regardless of where the touch landed** (Patrick) — so it needs no cell
coordinates and works with `mouse=false`, via a new coordinate-free
`android-selected` intent line. One-commit-ish cmus change (amend 0001)
plus app wiring; no new views.

## Facts pinned down

- **`mouse` is force-set true today, unconditionally.**
  TermService.onConnected sends `set mouse=true` (TermService.java:261)
  right after connect, overriding autosave — the same forcing stance as
  `resume`/`pl_env_vars`. The toggle just makes that value a pref: `set
  mouse=<pref>` on connect and on change. `mouse` is **not** in
  CmusSettings.MANAGED and stays out — it's an app-touch-behaviour pref
  (lives in the `term` prefs beside `show_joystick`), not a synced-back
  cmus option; keeping it forced means autosave can't strand it.
- **Everything app-side already gates on the emulator's tracking state,
  not a pref.** onSingleTapUp and onLongPress both branch on
  `terminalView.mEmulator.isMouseTrackingActive()`
  (MainActivity.java:1020, :1579) — which follows cmus's DECSET state,
  i.e. the `mouse` option. So `set mouse=false` already makes terminal
  taps fall through to `toggleSoftKeyboard()` and terminal long-press a
  no-op **for free**; this stage only adds the floating stick on top.
- **A `mouse=false` cmus must never receive a mouse sequence.** With the
  option off cmus never calls the ncurses mouse enable, so
  `isMouseTrackingActive()` is false and an SGR/X10 button sequence would
  be parsed as stray key bytes (random binds), not a click. That rules
  out "send BUTTON3 anyway" for the floating long-press — hence the
  coordinate-free intent line below.
- **`android_selected_event()` already reads the current selection, not a
  click point** (android.c:642). It emits the `selected` event from
  `cur_view` + the live cursor/marked set via `android_for_each_sel`
  (android.c:347), and the playlist **list-pane** variant (the visible
  playlist's name) is special-cased inside it. `android_for_each_sel`
  returns 0 for browser/filters/settings and the playlist list pane's
  non-editable side, so calling the event directly emits **nothing** in
  the views where win-remove would prompt — exactly the gating the mouse
  right-click relied on (it's only *called* today from normal_mode_mouse
  for MRB_CLICK/MRB_CLICK_SEL, keys.c:819). Calling it from a new intent
  line is therefore safe in every view and needs no coordinates.
- **The app's remove flow is already event-driven and coordinate-free.**
  onLongPress sets `pendingRemoveUntil` and the `selected` event drives
  onSelected → the Remove / Remove-playlist dialog (MainActivity.java:1594,
  :1605). The floating right-click reuses this verbatim: open the pending
  window, send `android-selected`, let the event raise the dialog.
- **JoyDot already tracks a gesture origin and clamps a knob to it.**
  down records downX/downY, MOVE computes dx/dy from them, onDraw draws
  base+knob at the **view centre** (getWidth()/2, JoyDot.java:226) with
  the knob offset by (dx,dy). Floating mode only needs onDraw to use the
  **touch origin** instead of the centre, and DOWN to accept any point
  (today it rejects touches past `grabRadius` from centre so the terminal
  gets them — JoyDot.java:139). Everything downstream (arrows, nav,
  repeat cadence, tap=Enter) is unchanged.
- **The dot's fixed vs floating footprint is one layout-param swap.**
  It's a 120dp square added to terminalWrapper (MainActivity.java:422)
  and re-placed from the saved per-orientation fraction on every wrapper
  resize (placeJoyDot, :609). Floating mode = MATCH_PARENT filling the
  wrapper and no placeJoyDot; fixed mode = today's 120dp + placeJoyDot.
- **Z-order already protects the floating overlay's neighbours.** In the
  wrapper the add order is terminalView → titleStrip → joyDot → floatBar,
  so floatBar (the hidden-top-bar settings/sleep overlay) draws and
  hit-tests **above** a MATCH_PARENT joyDot — its buttons keep working; a
  press on them never summons the stick (floatBar consumes it first). The
  terminalView sits **below** joyDot, so in floating mode the stick takes
  the terminal's touches, which is the point.
- **The reposition drag is the thing being displaced.** Fixed mode arms a
  2 s centre-hold (DRAG_HOLD_MS, JoyDot.java:52) that flips the touch into
  a move-the-dot drag persisted to `joy_x/y_<orient>`. Floating mode has
  nowhere to persist to, so that timer is repurposed to the right-click at
  a normal long-press timeout.

## Design

### "Direct touch input" setting

- New `TermService.PREF_DIRECT_TOUCH = "direct_touch"` (in the `term`
  prefs, beside the visibility toggles), default **true**.
- TermService.onConnected: `set mouse=` from the pref instead of the
  hard-coded `true`. New `applyMouse()` (mirrors applyProgressBar) sends
  `set mouse=<pref>` live when `ipc != null`.
- SettingsActivity buildAppSection, right after **Show joystick**:
  `switchPrefRow("Direct touch input", "<subtitle>",
  PREF_DIRECT_TOUCH, true, () -> { if (service != null) service.applyMouse(); })`.
  Subtitle: *"Tap and long-press act on the item you touch. When off,
  touch summons the joystick under your finger."*
- MainActivity.applyBarVisibility reads `directTouch` beside `joyShown`
  and pushes the joystick mode (below). The `set mouse` echo already
  flows to the emulator, so the tap/long-press branches re-gate
  themselves the moment the option changes — no MainActivity restart.

### Floating joystick mode

`floating = !directTouch && joyShown`, computed in applyBarVisibility and
handed to `joyDot.setFloating(boolean)`; MainActivity swaps the layout
params and visibility to match:

- **Fixed** (directTouch on, or the default): today's 120dp dot,
  placeJoyDot from the saved fraction, VISIBLE when `joyShown &&
  !crashScreen`. Untouched.
- **Floating** (directTouch off, joystick on): joyDot layout params →
  MATCH_PARENT (fill the wrapper), placeJoyDot skipped, VISIBLE when
  `!crashScreen` but **drawing nothing at rest**. It covers the terminal
  to catch the summoning touch; floatBar stays above it.

JoyDot gains a `floating` flag and an origin (originX/originY):

- **setFloating(boolean)**: stores the flag, resets any in-flight
  gesture, invalidates. In fixed mode the origin stays the view centre
  (visual no-op).
- **onDraw**: draw base+knob at the origin. Fixed mode origin = centre
  (unchanged). Floating mode: **skip drawing entirely unless tracking** —
  invisible at rest.
- **DOWN**: fixed mode keeps the `grabRadius`-from-centre test (touches
  off the dot fall through, `return false`). Floating mode accepts any
  point, sets origin = (getX,getY), and from here the existing dx/dy math
  is already origin-relative.
- **Long-press**: fixed mode keeps the 2 s DRAG_HOLD → reposition drag.
  Floating mode arms the same still-held timer at
  `ViewConfiguration.getLongPressTimeout()` and, on fire, calls a new
  `callback.rightClick()` + LONG_PRESS haptic, sets `fired = true` (so
  the release doesn't also send Enter), and does **not** enter `dragging`.
- tap (release within slop, nothing fired) = Enter, slides = arrows,
  far-left/right = nav — all unchanged. UP/CANCEL hides the stick
  (floating) exactly as it stops today (fixed).

### MainActivity wiring

- `directTouch` field, read in applyBarVisibility; call
  `joyDot.setFloating(!directTouch && joyShown)` and set the layout
  params / visibility per the mode, then applyChromePadding as today.
- placeJoyDot / applyJoyPos early-return (or aren't called) in floating
  mode — no saved-fraction placement for a full-wrapper view.
- New JoyDot.Callback.rightClick(): the floating analogue of onLongPress
  — if the session's alive, open the pending-remove window
  (`pendingRemoveUntil = uptimeMillis() + 800`) and
  `sendCommand("android-selected")`. The `selected` event then drives the
  existing onSelected dialog. No coordinates, no emulator mouse event.
- Known trade-off (documented, not fixed): in floating mode a plain
  terminal tap is the joystick's Enter, so **tap-to-toggle-keyboard is
  gone** while the stick owns the terminal — the keyboard is reached via
  the control bar's key button (or, with the bottom bar hidden, the
  popover's Keyboard entry from stage 18). Deliberate: the whole terminal
  is the stick.

### cmus patch change (amend 0001)

- New intent line **`android-selected`** in android_run_line
  (android.c:420) → `android_selected_event()`. It's app state cmus owns
  (the live selection), same class as the other `android-*` lines. Add it
  to the protocol comment block (android.c:126) with a note that it emits
  the same `selected` event as a list right-click but for the *current*
  selection, so the app can offer remove without a mouse event — the
  floating-joystick / `mouse=false` path.

## Verify (device, Patrick hands-on + wifi adb/logcat/debug receiver)

- Settings: "Direct touch input" default on; toggling it echoes `set
  mouse=` in logcat and flips terminal behaviour live (no relaunch).
- Direct touch **on** (default): unchanged — tap selects the touched row,
  drag scrolls, terminal long-press → right-click → remove dialog; the
  joystick sits in its saved bottom-right spot with the 2 s reposition
  drag intact.
- Direct touch **off**, joystick **on**: terminal is blank of a dot;
  press anywhere → stick appears under the finger; slide up/down →
  repeating arrows (faster deeper); far left/right → nav (pane→view);
  quick tap → Enter (win-activate). Release → dot vanishes.
- Floating long-press: press-and-hold still → right-click. In tree /
  sorted / queue / playlist track-pane → Remove dialog naming the current
  selection; playlist **list pane** → Remove-playlist dialog; browser /
  filters / settings and an empty list → no dialog (android-selected
  emitted nothing), no wedge. Remove acts on the joystick-moved cursor,
  **not** where the finger landed (move selection with the stick, then
  long-press elsewhere → the selected row is the target).
- `:`/search mode + floating long-press → android_selected_event's own
  view gating still applies; no stray remove.
- Direct touch off, joystick **off**: terminal taps toggle the keyboard
  (emulator tracking off path), no dot — a valid if bare config.
- floatBar (hide the top bar): the settings/sleep buttons top-right still
  tap through; pressing them does not summon the stick.
- Crash screen (`kill -ABRT`): dot hidden in both modes, tap still
  closes; relaunch clean.
- Rotation / IME: floating stick, being MATCH_PARENT, needs no
  re-placement and rides the shrinking wrapper above the IME; fixed mode
  re-derives from the saved fraction as before.
- `./patch.sh check` green after the 0001 amend + regen; clean
  assembleDebug.

## Risks / decide at implementation

- **Floating long-press timeout / haptic feel** — start at
  getLongPressTimeout(); if it collides with the start of an up/down
  slide (fire a right-click when the user meant to scroll), gate it on
  "still within touch slop" like the fixed drag-arm already does, and/or
  lengthen it. Tune on device.
- **Tap-to-keyboard loss in floating mode** — flagged above; if Patrick
  wants it back, a two-finger tap or an edge zone could keep it, but the
  clean design gives the whole terminal to the stick.
- **Summon vs scroll fling** — a fast flick to summon-and-slide should
  feel like the fixed stick's slide; the origin-relative dx/dy makes this
  automatic, but verify the first arrow doesn't fire on the summon jump
  (the vertThreshold from the touch origin should already prevent it).
- If any view emits a `selected` event from `android-selected` that the
  app doesn't expect (it shouldn't — same gating as the mouse path), the
  pending-window + onSelected view re-check drops it harmlessly.

## Commits

1. `cmus: android-selected intent line — selected event for the current selection (0001)`
2. `app: Direct touch input toggle drives cmus mouse; applyMouse live path`
3. `app: floating on-demand joystick when direct touch is off (long-press = right-click)`
4. `notes: stage 21 status + architecture`
