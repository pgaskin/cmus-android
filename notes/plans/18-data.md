# Stage 18 — Refresh: add tracks from the Music folder

Done *before* stage 17 (Patrick's reorder); the overview's stage-18 tar
import/export moves into the settings stage as **zip**. What remains of
stage 18 is deliberately small: a **Refresh** entry in the settings
popover that toasts "Adding tracks from Music folder" and adds the shared
Music folder to the library.

## Facts pinned down

- **cmus `add <path>` is the whole importer**: cmd_add (1 arg, rest of
  line verbatim after flags — no quoting to get wrong) → view_add → an
  async worker-thread job that scans directories recursively. The library
  is keyed by filename, so re-adding is a dedupe no-op — Refresh is
  safely re-tappable, no app-side scan state. There is no
  completion event; the toast is the feedback (and tracks stream into
  the visible views as the job walks).
- **The folder**: `Environment.getExternalStoragePublicDirectory(
  DIRECTORY_MUSIC)` = /storage/emulated/0/Music. Direct-path reads of
  audio files there work with **READ_MEDIA_AUDIO** (minSdk 34 > the API
  33 cutover; audio-only is exactly what cmus needs — no SAF). The path
  prefixes under `CMUS_ANDROID_EXT`, so saved library paths stay
  portable (stage-10 pl_env).
- **Permission flow**: runtime request beside the existing
  POST_NOTIFICATIONS pattern (requestCode 0 in onCreate) — Refresh uses
  its own requestCode and *continues the action* on grant in
  onRequestPermissionsResult; denied = nothing (re-tap re-asks).
  Manifest gains the uses-permission line.
- **Idle-quit must not truncate an import (Patrick)** — and the check
  has to be a *poll*, not a diffed event: `worker_has_job()` (the same
  call behind the TUI's quit-while-jobs warning) answers "is an
  add/update job running", but cmus's idle main loop can sleep in
  select indefinitely (the stage-15 WINCH lesson), so a job *finishing*
  would never reach a flush. An `android-jobs` intent line is both the
  question and the wakeup (the android-winch pattern), answered
  immediately with a transient `{"type":"jobs","running":bool}`.

## Design

- Popover: Theme / Font / **Refresh** / Settings. Refresh → ensure
  READ_MEDIA_AUDIO (request + resume on grant) → toast "Adding tracks
  from Music folder" → `add /storage/emulated/0/Music` over IPC (the
  dir from Environment, not hardcoded).
- **cmus patch (0001 amend)**: `android-jobs` input line → immediate
  `{"type":"jobs","running":true|false}` (worker_has_job; transient,
  not snapshotted — it's a poll answer). Protocol comment updated.
- **Idle-quit guard (TermService)**: the idle-quit fire no longer quits
  directly — it re-checks its conditions, then sends `android-jobs`
  with a pending flag. The Jobs echo: conditions re-checked (the world
  may have moved during the round trip), then quit if idle, or re-post
  the fire on a poll interval (30s) while a job runs. Cancel paths
  (refocus, unpause, session death) clear the flag with the callbacks.
  CmusIpc gains the transient Jobs record.
- **TODO for later (Patrick)**: tracks deleted on disk linger in the
  library — cmus has no prune-missing command. Needs design (an
  android.c intent line access(2)-checking lib entries, or an app-side
  diff against the saved library); recorded in status.md Next.

## Verify (device, Patrick hands-on)

- Fresh install path: Refresh → permission dialog → grant → toast +
  tracks from /sdcard/Music appear; deny → nothing, re-tap re-asks.
- Re-tap: no duplicates. lib.pl entries carry CMUS_ANDROID_EXT.
- Idle-quit guard: `android-jobs` via receiver → jobs event echoes the
  running state (true during a big add, false after); idle-quit while a
  job runs → "delaying" log + re-poll, quits after the job ends (short
  idle delay for testing, like stage 10).
- patch.sh check green after the 0001 fixup+regen; clean assembleDebug.

## Commits

1. `cmus: android-jobs poll line for the idle-quit import guard (patch 0001)`
2. `app: refresh — add Music-folder tracks from the settings popover`
3. `notes: stage 18 status + architecture (+ overview reorder)`
