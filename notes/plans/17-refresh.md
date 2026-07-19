# Stage 17 — Refresh: add tracks from the Music folder

The overview's data stage, renumbered 17 and done ahead of the settings
screen (Patrick's reorder); its tar import/export moves into the
settings stage (now 18) as **zip**. What remains here is deliberately
small: a **Refresh** entry in the settings
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
- **Idle-quit must not truncate an import (Patrick), and the app toasts
  "Import finished" for *any* import** (TUI adds included, not just
  Refresh). `worker_has_job()` (the call behind the TUI's
  quit-while-jobs warning) answers "is an add/update job running", and —
  the load-bearing find — **job_fd is in the main loop's select set**,
  so job activity itself wakes the loop and a diffed jobs event always
  reaches the next flush promptly (the stage-15 indefinite-sleep concern
  doesn't apply to jobs). Two mechanisms by Patrick's call: the *event*
  (diffed like volume, snapshotted on connect) drives the toast; the
  idle-quit guard *polls* via an `android-jobs` line — a fresh
  authoritative answer at decision time, re-polled while a job runs.

## Design

- Popover: Theme / Font / **Refresh** / Settings. Refresh → ensure
  READ_MEDIA_AUDIO (request + resume on grant) → toast "Adding tracks
  from Music folder" → `add /storage/emulated/0/Music` over IPC (the
  dir from Environment, not hardcoded).
- **cmus patch (0001 amend)**: `{"type":"jobs","running":true|false}` —
  diffed in android_flush against a cached copy (the volume pattern; on
  connect and on change), *plus* immediately as the `android-jobs`
  line's answer (which updates the same cache so nothing double-sends).
  Protocol comment updated.
- **Idle-quit guard (TermService)**: the idle-quit fire no longer quits
  directly — it re-checks its conditions, then sends `android-jobs`
  with a pending flag. Any Jobs event while pending: conditions
  re-checked (the world may have moved), then quit if no job runs, or
  re-post the fire on a poll interval (30s). Cancel paths (refocus,
  unpause, session death) clear the flag with the callbacks.
- **Import-finished toast (MainActivity)**: Jobs is cached/replayed in
  CmusIpc like Volume; the activity toasts "Import finished" on a
  true→false transition of the seen state — any import, any trigger.
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
3. `notes: stage 17 status + architecture (+ overview reorder)`
