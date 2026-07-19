# Stage 19 — Continuous state save

Closes the stage-10 loss window for exits that skip the pty SIGHUP
entirely (force-stop's uid SIGKILL, battery death, kernel panics):
cmus state is saved incrementally during runtime instead of only at
exit. The save cadence, granularity buckets, and the atomic-save patch
are **Patrick's decisions** (stated up front in his stage brief):

- **Playlists**: saved with a **5s debounce** whenever playlist
  changes are made.
- **Resume state only**: saved with a **15s debounce** after
  play/pause or the track changes — not continuously during playback,
  to avoid stuttering.
- **Library + cache only**: saved when an import/update-cache
  completes, or with a **15s debounce** after tracks are changed.
- **Autosave (settings)**: saved with a **5s debounce** whenever
  settings change.
- **Command/search history**: saved with a **5s debounce**.
- **Everything**: saved between track playback (to avoid stutter) if
  **15 min** have elapsed since the last full save.
- The debounced updates are **driven by events emitted from IPC**
  (cmus tells the app what became dirty; app actions alone can't see
  TUI-side edits).
- A cmus patch makes saves **atomic (write-tmp + rename)** so a kill
  mid-write can't tear a state file.

## Facts pinned down

- **`do_cmus_save` is NOT atomic** (cmus.c:270): it opens the target
  with `O_CREAT|O_WRONLY|O_TRUNC` and writes in place — lib.pl,
  queue.pl, and every playlists/* file can be torn by a SIGKILL
  mid-write. The stage-18 comment atop `android_save_state` claiming
  "every callee is a plain write-tmp-and-rename writer" is **wrong**
  for these three; resume (options.c resume_exit), autosave
  (options_exit), both histories (history.c), and the cache (cache.c)
  do already write `*.tmp` + rename. So the atomic patch = fix
  `do_cmus_save` (skip the `"-"` stdout path); it's un-gated and an
  **upstream candidate** like 0003 — upstream has the same torn-write
  exposure and already uses the tmp+rename pattern everywhere else.
- **Latent stage-18 race (must fix)**: `android_save_state` calls
  `cache_close()` with the worker thread live. Upstream only reaches
  cache_close via `cmus_exit()` **after `job_exit()`** joins the
  worker; job.c mutates the cache hash table under `cache_mutex`
  (job.c:180/532/566), and `cache_close` → `get_track_infos` walks it
  bare. A zip-export save during an import can walk the table
  mid-mutation. Fix: take `cache_lock()`/`cache_unlock()` around the
  cache portion of the android save.
- **Library/playlist/queue mutations are main-thread only**: worker
  add jobs buffer track_infos and push them as job *results*
  (job.c flush_ti_buffer → job_push_result), consumed by the main
  loop, which runs the add callbacks (lib_add_track etc.). So dirty
  hooks for lib/pl/queue are plain ints on the main thread — no
  atomics. Only the **cache** is mutated from the worker (under
  cache_mutex), so its dirty counter is the one needing `__atomic`
  ops (job activity wakes the main loop — job_fd is in the select
  set — so the flush sees bumps promptly, the stage-17 find).
- **The Options event fires after every executed command**, not only
  when an option changed (android_options_changed at the end of
  run_parsed_command, unconditional). So "settings changed" must be
  detected app-side by diffing the echoed options map — cheap, and
  most option values are getter-backed live state anyway (the save
  captures whatever is current; detection only decides *when*).
- **history_add_line (history.c:117) is the single chokepoint** for
  both command and search history — one hook covers every TUI-typed
  `:` command and `/`/`?` search. IPC-sent commands bypass history
  entirely, so without this hook the app can't see history changes.
- Existing events already cover the rest of the triggers: Status
  (play/pause/track change → resume bucket), Jobs true→false edge
  (import *and* update-cache completion — both are worker jobs),
  Volume (softvol_state is autosave state), View/Filter (resume-file
  state, see open questions).

## Design

### cmus patch 0001 amendments

- **Granular save**: `android-save [kind…]`, kinds `resume settings
  library cache playlist queue history`, bare line = everything
  (backward compatible — the stage-18 zip export and delete-checkbox
  flows keep working unchanged). android_save_state(kinds) dispatches:
  resume_exit / options_exit / cmus_save(lib) / cache under
  cache_lock / pl_save / cmus_save(queue) / commands_save +
  search_mode_save.
- **saved event grows `"what":["kind",…]`** echoing what was written.
  Ack matching stays FIFO (one socket, ordered), but `what` makes it
  checkable.
- **New `{"type":"dirty","what":["kind",…]}` event**, coalesced per
  flush. Per-kind monotonic mutation counters; the flush emits a kind
  when its counter differs from both the last-announced and the
  last-saved value, then marks it announced; android_save_state
  records the counter *read before writing the files* as saved — a
  mutation landing mid-save leaves counter > saved, so the kind
  re-announces on the next flush and the app schedules another save
  (that's the careful-race-condition core). Edge-triggered per save
  cycle: at most one dirty event per kind between saves, so the app's
  "debounce" is arm-on-first-change — bounded staleness; a continuous
  edit stream can't postpone the save forever. On connect, kinds with
  counter ≠ saved re-announce in the snapshot (reconnect loses no
  dirt).
- **Counter bump sites**: editable.c mutation entry points classified
  by editable (lib_editable → library, pq_editable → queue, else
  playlist) + lib_add_track (tree adds bypass editable_add) + pl.c
  metadata ops (create/delete/rename/import) + cache.c entry
  add/update (atomic, worker) + history_add_line (history). Audit for
  stragglers during implementation; the exit-set files are the ground
  truth for what each kind covers.
- Fix the cache_lock race + the wrong stage-18 comment (above).

### cmus patch 0004 (new, upstream candidate)

- do_cmus_save: write `<filename>.tmp`, then rename over the target
  (matching resume.tmp/cache.tmp style). Not CONFIG_ANDROID-gated.
  No fsync, matching the upstream pattern in cache/options/history —
  rename-replace fully closes the SIGKILL torn-write window (the
  stated goal); battery-death durability would need fsync-before-
  rename everywhere and is deliberately out (open question below).

### App: StateSaver (new class, ~the MediaControl pattern)

- Owned by TermService, registered as an IPC listener per CmusIpc
  instance (attachIpc after every getSession); all callbacks and
  timers on the main thread (Handler.postDelayed) — no Java-side
  locking. Cleared wholesale on session death; the connect snapshot's
  dirty re-announcement rebuilds pending state, so nothing survives a
  respawn to act on stale assumptions.
- **Buckets → timers** (Patrick's cadences above):
  - Dirty playlist/queue → 5s → `android-save playlist queue`.
  - Dirty history → 5s → `android-save history`.
  - Dirty library/cache → 15s → `android-save library cache`, but
    **deferred while Jobs.running** (saving a half-imported library
    is churn; cmus-side it's safe — main-thread serial + cache_lock —
    just pointless); the Jobs true→false edge fires it immediately
    (that's also the import/update-cache-completion rule).
  - Settings: on every Options event, diff the options map against
    the previous echo (ignore the first after connect — that's the
    snapshot); real change or Volume change → 5s →
    `android-save settings`.
  - Resume: Status events only — play/pause transitions and track
    (file) changes → 15s → `android-save resume`. No saves keyed to
    position ticks: the 15s delay also pushes the write away from the
    track-boundary moment when the producer thread is busy opening
    the next file (Patrick's no-stutter constraint).
  - Full: on a track-change Status while playing, or on a transition
    into paused/stopped — if ≥15 min since the last full save →
    `android-save` (all kinds). Fired *at* boundaries, never
    mid-track (Patrick: the cache is the multi-MB write; between
    tracks is where it can't stutter). A full save clears every
    bucket timer.
- **Serialization**: every android-save in the app goes through
  StateSaver — a FIFO queue of in-flight requests matched to Saved
  acks in order (one socket, cmus processes serially; exact
  matching). The stage-18 zip-export and delete-checkbox bounded
  saves move onto `StateSaver.saveNow(kinds, timeout, callback)` so
  a periodic ack can never satisfy the export's wait (the race the
  old shared-Saved-listener scheme would have had). Export/resetData
  suppress the periodic timers while in flight (atomic renames mean
  a concurrent save couldn't tear the zip's reads, but mixed-
  generation exports are avoidable for free); session death clears
  queue + timers. No ack timeout for periodic saves: a wedged cmus
  just stops being saved, and reconnect self-heals via the
  re-announced dirt.

## Open questions / cases flagged to Patrick

1. **Queue edits** weren't in the brief — folded into the playlist
   bucket (queue.pl is exit-set state too). Confirm.
2. **Long tracks**: resume position only saves on play/pause/track
   change + the 15-min-at-boundary full save — an hour-long track
   playing uninterrupted goes stale ~an hour. Option: also save
   resume (tiny file) on a plain 15-min timer while playing.
3. **Other resume-file writers**: seek, view changes, live-filter
   changes, browser-dir all live in the resume file but don't arm the
   resume bucket under the brief's rule. Arming it on View/Filter
   events (+ seek-detected Position jumps) is nearly free — include?
4. **Backgrounding save**: onStop is the last moment before most
   force-stops/system kills. A full (or small-files) save when the
   activity backgrounds while not playing would close the window
   tightest. Include?
5. **Cache size vs the 15s track-edit debounce**: with a big library
   the cache rewrite is the one multi-MB write, and the 15s debounce
   can land mid-playback — the same stutter the full save avoids by
   waiting for boundaries. Option: the 15s debounce saves library
   only, cache waits for the jobs edge / full save / paused.
6. **fset filter definitions** don't surface in the options diff, so
   filters-view edits leave autosave stale until the next full save.
   Acceptable, or add a tiny settings-dirty hook in filters.c?
7. **fsync**: rename-replace without fsync can still lose *both* file
   versions on power loss (data not yet flushed when the rename
   commits). Matching upstream (no fsync) keeps saves cheap and fully
   covers SIGKILL; add fsync-before-rename if battery death is a real
   target.

## Verify (device)

- Kill-based: `kill -9` (uid-style SIGKILL via `am force-stop`) at
  staged points — right after a playlist edit + 5s, after a TUI `:set`
  + 5s, after a track change + 15s, mid-import — relaunch and confirm
  exactly the debounced state survived; force-stop *mid-save* (tight
  loop) and confirm files are always whole (old or new, never torn).
- Event flow via IPC logs: dirty what=playlist on a TUI edit, one
  event per save cycle (no per-flush spam during an import), saved
  what echoes, jobs-edge save after import/update-cache, no
  library/cache save while a job runs.
- Zip export during heavy periodic-save activity: ack matching holds
  (export gets its own full-save ack, not a periodic one).
- Stutter: 15-min full save at a track boundary with a large cache
  while listening (Patrick's ears).
- patch.sh check green after the 0001 fixup+regen + new 0004; clean
  assembleDebug.

## Commits

1. `cmus: granular android-save + dirty events + cache lock fix (patch 0001)`
2. `cmus: atomic playlist/library saves via tmp+rename (patch 0004, upstream candidate)`
3. `app: StateSaver — debounced continuous state saves off IPC dirty events`
4. `notes: stage 19 status + architecture`
