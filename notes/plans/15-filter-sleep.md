# Stage 15 — Quick filter + sleep timer

Goal: the tab bar gains a left quick-filter icon that morphs the bar into a
live-filter search box focused on the library (right side becomes ✕ to
clear/exit), and a right sleep-timer icon (duration selector; icon shows
minutes left, pauses playback at expiry). One small cmus patch addition: a
`filter` event echoing the live-filter state, so the app stays a pure
mirror (the filter survives resume, and cmus can drop it behind the app's
back).

## Facts pinned down

- **`live-filter` is the exact primitive.** cmd_live_filter (0–1 args) →
  filters_set_live → lib_set_live_filter: plain text = case-insensitive
  substring match over all tags (TI_MATCH_ALL) with tree auto-expand
  (tree_expand_matching); text whose leading chars are only `!( ` up to a
  `~` parses as a full filter expression instead (expr_is_short — so
  `~a foo` works as a power-user bonus, and ordinary words can never
  accidentally parse). Bare `live-filter` clears. Unchanged text
  early-returns (strcmp0), so repeated sends are free; a failed expression
  parse returns without applying (error in the TUI, filter untouched).
  It filters the *library* only (tree + sorted), regardless of cur_view.
- **No quoting problem.** parse_command hands the command everything after
  the first space run verbatim (no quote/backslash processing, max_args 1
  keeps it whole) — `live-filter don't stop (live)` needs no escaping.
  Only leading spaces are eaten and newlines are impossible (CmusIpc.send
  rejects them). The TUI's CMD_LIVE per-keystroke behavior is replicated
  by simply sending the full command per text change.
- **The filter state must be echoed — three writers, one reader.** The
  live filter is (1) set/cleared by our commands, (2) restored at startup
  from the resume file (options.c: `live-filter` line → filters_set_live),
  and (3) *silently cleared* by lib_set_filter (activating a filters-view
  filter or `filter`/`fset` unsets it without passing through
  lib_set_live_filter). An app-side-only text box goes stale on (2) and
  (3); the stage-11 rule (cmus is the single source of truth, chrome
  mirrors echoes) needs a `filter` event.
- **The volume event's diff-in-flush pattern makes the patch hook-free.**
  android_flush already runs once per main-loop iteration and diffs
  vol_l/vol_r against cached copies; `lib_live_filter` is already extern
  (lib.h:101), so the flush can strcmp0 it against a cached copy and emit
  on change — covering all three writers with **zero hunks outside
  android.c** (resume restore happens pre-loop, folded into the connect
  snapshot's dirty_all). No wrapper needed, unlike pl.c.
- **TUI representation** (for verification): update_filterline draws
  `filtered: <text>` right-aligned on the statusline row, only in
  tree/sorted.
- **Tab bar structure** (MainActivity onCreate): `tabBar` is a
  HorizontalScrollView wrapping tabRow, added directly to the root
  LinearLayout; the insets listener sets its padding
  (chromeInsets + tabExtra row-quantization remainder) and applyTheme its
  win_title background. Flanking it with icons means one wrapper
  (horizontal LinearLayout `topBar`) that takes over the padding +
  background + root slot; the scroll view keeps only its scroll job.
  The flush-with-the-TUI math is untouched (it pads the same container).
- **Sleep timer is pure app-side, and belongs in TermService.** It must
  tick while the activity is backgrounded/destroyed; the service already
  owns the IPC client and outlives the activity. `player-pause-playback`
  is the pause-only command (MediaControl's focus-loss mapping — no-op
  unless playing). postDelayed counts uptimeMillis, which stalls in deep
  doze — but active audio playback holds a partial wakelock (audioserver),
  so uptime tracks elapsed exactly when the timer matters; if the device
  dozed, nothing was playing and a late fire is a no-op (same accepted
  stance as the idle-quit comment). No persistence needed: a playing FGS
  won't be reaped, and after firing the timer is moot — force-stop loses
  it, accepted.
- **Timer + idle-quit compose correctly for free**: expiry pauses → cmus
  is (not PLAYING) && backgrounded → the existing 15-min idle-quit reaps
  it losslessly. That's the desired end state of "fall asleep to music".
- MainActivity already holds the service instance via LocalBinder
  (`service.getService()` pattern) — the timer API is plain methods, no
  new callback plumbing; prompt icon refresh rides the Status event the
  expiry pause echoes back.
- Icons: Material Symbols outlined (stage-12 pattern — google/
  material-design-icons, 0,-960,960,960 viewBox in a translateY=960
  group): `search`, `close`, `bedtime`.

## Design — cmus patch (0001 amend): filter event

- android.c only: cached `char *android_live_filter`; in android_flush,
  `if (android_dirty_all || strcmp0(lib_live_filter, android_live_filter))`
  → emit and re-cache (xstrdup/free; freed in android_free). Event:
  - `{"type":"filter","filter":"..."}` — the current live filter;
  - `{"type":"filter"}` — none (field omitted, the status-event
    convention).
  On connect the snapshot always carries it (dirty_all), so a
  resume-restored filter reaches the app before it draws chrome. String
  JSON-escaped via android_json_str like everything else. Protocol
  comment updated (it's the contract).
- Amend mechanics: fixup commit in the submodule + `git rebase
  --autosquash base`, `./patch.sh` regen, check green.

## Design — quick filter (app)

- CmusIpc: `Filter` record (String filter, null = none), cached/replayed
  like View.
- topBar = horizontal LinearLayout {filterBtn · tabBar (weight 1) ·
  sleepSlot}, plus {filterBox EditText (weight 1) · closeBtn} shown/GONE
  against the middle+right when morphed. Insets padding, tabExtra, and
  win_title bg move from tabBar to topBar. Icon buttons: square at the
  bar's height, tinted winTitleFg — 55% alpha idle, full alpha when the
  filter is active (the inactive-tab convention), ripple foreground like
  ControlBar buttons.
- filterBtn tap (closed → open): prefill from the cached Filter echo
  (watcher suppressed during prefill), cursor at end, focus + show IME;
  if the echoed view ∉ {tree, sorted}, send `view tree` (the box filters
  the library, so show it). Open → closed (tap again): collapse keeping
  the filter applied — icon stays lit. ✕: send bare `live-filter`, then
  collapse and refocus the terminal.
- TextWatcher: send `live-filter <text>` per change (bare `live-filter`
  when blank). Filter echoes are **ignored while the box is open** (the
  box is authoritative mid-edit — the mid-drag slider rule); while
  closed they drive the icon state and the next prefill.
- EditText: monospace at the terminal font size, win_title colors, single
  line, no suggestions, transparent background (no material underline),
  IME_FLAG_NO_EXTRACT_UI; imeOptions = actionSearch → hide the IME and
  refocus the terminal (selection is on the filtered list; joystick/
  KeyRow/enter drive it from here).
- KeyRow gains `&& !filterBox.hasFocus()` on its IME-visibility condition
  (its keys inject into the *terminal*; sticky modifiers over an EditText
  are nonsense), re-evaluated on box focus change.
- Session death/crash screen closes the box alongside the stage-14 dialog
  dismissal. Rotation recreates with the box closed; the filter itself is
  cmus state, the replayed echo relights the icon — acceptable.

## Design — sleep timer (app)

- TermService: `sleepDeadline` (elapsedRealtime ms, 0 = off) +
  setSleepTimer(minutes) / cancelSleepTimer() / sleepRemainingMs();
  postDelayed runnable sends `player-pause-playback` (ipc != null) and
  clears the deadline. Setting while active replaces (removeCallbacks +
  repost).
- sleepSlot: inactive = bedtime icon at 55% alpha; active = minutes-left
  TextView ("37m", monospace, tab-styled, full fg) — same slot, swapped
  visibility. While visible, MainActivity ticks it on a handler aligned
  to the deadline's next minute boundary; also refreshed on Status events
  (the expiry pause echoes back instantly → prompt revert) and onStart.
- Tap → AlertDialog list: presets 15/30/45/60/90 min + "Custom…"
  (number-input EditText dialog, the New-playlist pattern) + "Turn off
  (Nm left)" appended while active.

## Verify (device, wifi adb + logcat + debug receiver)

- Patch: `live-filter beat` via receiver → filter event + TUI
  `filtered: beat` on the statusline + tree narrowed/expanded; bare
  `live-filter` → field-less event, full library back; connect snapshot
  carries the current state; filters-view activation (`fset`/spacebar)
  → live filter silently dropped by lib_set_filter and the *event still
  fires* (the diff-in-flush point); garbage expression `~x foo` → TUI
  error, no event.
- Resume: set a filter, `:quit`, relaunch → snapshot echoes it, icon
  lit, box prefills on open, library arrives filtered.
- Box: open from queue view → view event echoes tree, tabs replaced by
  box + ✕; adb `input text` per-keystroke → TUI narrows live; blank →
  full library; `~a <artist>` expression narrows by artist only; ✕ →
  cleared + tabs restored + terminal refocused; reopen-tap collapse
  keeps the filter + lit icon; IME search action → keyboard down,
  joystick drives the filtered list; KeyRow absent while the box has
  focus, present again typing into the terminal; TUI-side
  `:live-filter x` (typed) → closed-box icon follows the echo.
- Sleep: custom 1-min timer while playing, screen off + backgrounded →
  paused at expiry (notification flips), icon reverts on reopen;
  countdown ticks (2m → 1m) while watching; cancel via "Turn off";
  preset replace while active; expiry while already paused/stopped =
  no-op; interplay: after expiry the normal idle-quit path reaps cmus
  (existing stage-10 behavior, spot-check optional).
- Crash screen: box + dialogs closed, icons quiet; relaunch clean.
- `./patch.sh check` green after regen; clean assembleDebug; icon
  sizes/feel = Patrick hands-on.

## Risks / decide at implementation

- Every keystroke's command also fires the full ~129-option options
  event (the run_parsed_command hook) — a few KB per keystroke over a
  local socket, and the app diffs cheaply; accepted, revisit only if
  typing ever visibly lags.
- Leading spaces in filter text are eaten by parse_command; interior/
  trailing spaces are fine. Cosmetic, matches the TUI.
- Tap-filter-icon-to-collapse-keeping-filter is a reading of the
  overview's "✕ to clear/exit" (✕ stays the only *clearing* exit);
  Patrick may prefer ✕-only on device. [flagged]
- Preset durations, countdown format ("37m" vs bare "37"), bedtime vs
  timer glyph, EditText cursor/handle theming — tune on device.
- The box-open echo-ignore means a debug-receiver live-filter sent while
  the box is open won't update the box text (it can't collide with TUI
  typing — the pty has no focus then); harmless, reopen resyncs.

## Commits

1. `cmus: live-filter state event over the android IPC (patch 0001)`
2. `app: quick-filter search box morphing the tab bar`
3. `app: sleep timer — service countdown, tab-bar selector + minutes-left icon`
4. `notes: stage 15 status + architecture`
