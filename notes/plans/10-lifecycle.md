# Stage 10 — Lifecycle

Goal: the service knows when to die and nothing is ever lost when it
does. Idle-quit timer (cmus not playing + app unfocused for X min →
`quit`), forced `resume`/`pl_env_vars` so every quit — user, idle,
task-swipe, even app-process death — restores track/position/playback
state/UI position on the next launch, task-removal policy, readable
crash exits, and the process-death/restart story verified end to end.
Java-only stage: no cmus patches, patchCheck stays green.

## Facts pinned down

- **`resume=true`** (options.c, ui_curses.c exit_all/main): on exit,
  `resume_exit()` writes `$CMUS_HOME/resume` — player status, file,
  position, library track, view, live-filter, browser dir, marked/
  active playlist + row — and the play queue is autosaved; on startup
  `resume_load()` restores all of it, re-seeking with
  `start_playing = (status == PLAYING)`, so quit-while-paused comes
  back paused at position and quit-while-playing comes back playing.
  Options land in autosave at exit, so forcing the option once per
  connect self-persists exactly like `mouse`.
- **SIGHUP/SIGTERM are clean exits** (ui_curses.c sig_shutdown →
  `cmus_running = 0` → main loop falls through to exit_all): resume
  file + autosave + socket unlink all happen. cmus is the pty child,
  so the app process dying (crash, `kill -9`, lmkd) closes the pty
  master and cmus gets SIGHUP → **state survives app-process death**.
  The exception is force-stop, which SIGKILLs the whole uid — cmus
  gets no chance to save; accepted (explicit user action, standard
  app behavior).
- **Stale socket is a non-issue**: android.c unlinks the path before
  bind, so an unclean death never blocks the next spawn.
- **pl_env** (pl_env.c/h — Patrick's upstream feature):
  `set pl_env_vars=VAR1,VAR2,…` + env vars at spawn; library/playlist
  save (cmus.c save_playlist_cb) and load (job.c handle_line) and
  cache write/read (cache.c) swap a path's base for the first listed
  var whose value prefixes it (saved form:
  `\x1FVARNAME\x1F/rest/of/path`), so saved libraries survive the
  base path moving (reinstall, storage move, multi-user path
  changes). First match wins → **most-specific var first**, and the
  **names are baked into saved files forever** — choose once. An
  unset var degrades safely: cache_get_ti returns a dummy track
  rather than dropping entries, and player errors say which var is
  missing. The resume file's own paths are *not* substituted
  (upstream scope; a storage move loses only the resume-track ref,
  harmless).
- **Idle-quit inputs already exist**: CmusIpc caches Status (play
  state) with main-thread listener callbacks; "app unfocused" is just
  MainActivity onStart/onStop reported through the existing binder.
  Config-change recreation opens a brief unfocused gap (old activity
  fully stops before the new one starts) — irrelevant at minute
  scale, the timer just gets cancelled on the new onStart.
- **Handler.postDelayed counts uptimeMillis**, which pauses in deep
  doze — the quit can fire late when the device sleeps. Accepted:
  this is a hygiene feature, firing on next wake is fine, and it
  avoids AlarmManager/exact-alarm machinery entirely. While PLAYING
  no timer is armed at all (zero overhead in the common case).
- **onTaskRemoved** is delivered to a started service when the user
  swipes the task from recents; the FGS + session survive unless we
  act.
- TerminalSession exposes the child's exit status after
  onSessionFinished (`getExitStatus()`); today the activity
  `finish()`es unconditionally, so cmus crash output on screen is
  unreadable.
- START_NOT_STICKY already prevents zombie service restarts with no
  session; after `quit` only Android's frozen empty cached process
  remains (stage 6/9 behavior, relaunch re-inits clean).

## Policy (Patrick, this stage): forced settings

Overriding cmus settings that core wrapper functionality depends on
is **desired**, not rude: force them over the socket on every
(re)connect (config files are never touched; autosave persisting the
forced value is exactly the point). `mouse` (stage 6) set the
pattern; this stage adds `resume` and `pl_env_vars`, and future
stages should do likewise without agonizing. Recorded in
architecture.md.

## Design — TermService

### Spawn env + forced options

- Env adds (beside the existing CMUS_* vars):
  - `CMUS_ANDROID_EXT_FILES` = `getExternalFilesDir(null)` (skipped
    if null/unavailable) — listed first: it lives *under* the
    external root.
  - `CMUS_ANDROID_EXT` = `Environment.getExternalStorageDirectory()`.
  - `CMUS_ANDROID_FILES` = `getFilesDir()`.
- onConnected sends (after `set mouse=true`): `set resume=true`,
  `set pl_env_vars=CMUS_ANDROID_EXT_FILES,CMUS_ANDROID_EXT,CMUS_ANDROID_FILES`
  (minus any skipped var). Bootstrap is self-correcting: the first
  run's library was loaded pre-substitution, but exit saves
  substituted paths + the option in autosave, and every later
  startup expands correctly before we even connect.

### Idle-quit timer

- State: `activityVisible` (new binder-side setter called from
  MainActivity onStart/onStop) + the cached IPC Status. Idle =
  cached state != PLAYING (PAUSED counts: resume makes it lossless;
  null Status counts as not-idle — don't quit on a snapshot we never
  got).
- One Runnable on the main handler. Arm when (idle && !visible)
  becomes true, cancel when either flips back; transitions come from
  the existing IPC listener (Status events) + the visibility setter.
- On fire: re-check both conditions and connectedness, then
  `set resume=true` (belt-and-braces if the user disabled it
  mid-session — see policy) + `quit`. Session exit then drives the
  existing teardown path untouched (close ipc/mediaControl,
  stopForeground, stopSelf). If the session is somehow still running
  after a short grace (send dropped, cmus wedged) → log + re-arm;
  never SIGKILL (that's what loses state).
- Default: 15 min constant (`IDLE_QUIT_DELAY`), the stage-17 setting
  will replace it. Verified with a temporarily shortened debug value.

### onTaskRemoved

- PLAYING → do nothing (playback outliving the task is the FGS's
  whole point; parity with every music app).
- Not playing → immediate `set resume=true` + `quit` (user closed an
  idle app; lossless), or straight to stopSelf if the session is
  already dead.

### Crash-visible exits (SessionCallback change)

- onSessionFinished: service teardown unchanged, but the activity
  now branches on `session.getExitStatus()`: 0 → `finish()` as
  today; nonzero → stay open on the frozen terminal screen with a
  toast ("cmus exited (code N)") so the last output is readable;
  back/relaunch proceeds normally (service already stopped; next
  launch spawns fresh). No-activity-attached case unchanged.

## Design — MainActivity

- onStart/onStop → `service.setActivityVisible(true/false)` (guarded
  for the unbound window; the service also treats "never reported" as
  visible since the activity is what started it).
- onSessionFinished handling per above (query exit status via the
  bound service's session).

## Verify (device, wifi adb + logcat)

- **resume**: play → seek mid-track → pause → `:quit` → relaunch:
  paused at the same position, same view/selection; `:quit` while
  playing → relaunch resumes *playing* at position (upstream
  semantics, wanted). `$CMUS_HOME/resume` present; autosave shows
  resume=true + pl_env_vars persisted.
- **pl_env**: push tracks to the external files dir (permissionless),
  `:add`, quit → lib.pl / cache contain `\x1FCMUS_ANDROID_EXT_FILES\x1F…`
  entries; relaunch → tracks load + play. Sanity: filesDir tracks get
  CMUS_ANDROID_FILES, nothing double-substituted.
- **idle-quit** (shortened debug delay): pause → home → timer fires →
  logcat shows quit; notification/session/service gone; relaunch →
  paused position + view restored. Cancel paths: return to app before
  fire → cancelled; play from the shade while backgrounded →
  cancelled; STOPPED + backgrounded also quits. While PLAYING
  backgrounded → nothing armed (log silence).
- **task removal**: swipe from recents while playing → playback +
  controls survive; swipe while paused → clean quit, everything gone,
  relaunch restores.
- **process death**: `kill -9` the *app* pid while playing → cmus
  gets SIGHUP, exits clean (resume file fresh, socket unlinked);
  relaunch → resumes playing at position. Force-stop → cmus
  SIGKILLed, no save (observe + accept). Stale-socket relaunch works
  (bind unlinks).
- **crash exit**: `kill -ABRT` the cmus pid with the activity open →
  frozen terminal + toast, no auto-finish; back → gone; relaunch
  clean. Same kill while backgrounded → service stops quietly.
- Rotation across an armed timer → no spurious quit. Clean
  `./gradlew assembleDebug`; `./patch.sh check` green (no cmus
  changes).

## Risks / decide at implementation

- Idle delay default (15 min) — cheap to change; PAUSED-counts-as-idle
  is safe only because resume is forced, revisit if that ever changes.
- pl_env var names are permanent once libraries exist — bikeshed now
  or never (CMUS_ANDROID_{EXT_FILES,EXT,FILES} proposed).
- Whether `getExternalFilesDir` null-at-spawn is worth handling beyond
  skipping the var (storage-unavailable is near-mythical on modern
  devices).
- Crash-screen UX is deliberately minimal (frozen screen + toast); a
  proper "[process exited]" banner can ride a chrome stage if wanted.
- Doze-drifted idle-quit firing hours late on a sleeping device —
  accepted; AlarmManager only if real usage shows it mattering.
- The force-stop/SIGKILL loss window stays open this stage; Patrick's
  planned future feature (status.md note) — periodic state save via a
  cmus patch — is what closes it.

## Commits

1. `app: force resume + pl_env_vars, add spawn env base-path vars`
2. `app: idle-quit timer, task-removal policy, crash-visible exits`
3. `notes: stage 10 status + architecture (forced-settings policy)`
