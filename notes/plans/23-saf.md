# Stage 23 — Native SAF content bridge

Make cmus read music through the Storage Access Framework so the app
works with **no `MANAGE_EXTERNAL_STORAGE`** and **without depending on the
MediaProvider FUSE mount** for the Music folder. The user grants a
document tree (`ACTION_OPEN_DOCUMENT_TREE`); everything downstream —
browse, recursive add, tag scan, playback, cache freshness — flows
through `content://` URIs. The current direct-path modes survive only as
**debug toggles**. Cloud/non-seekable providers are a later increment,
not this stage's goal.

The enabling trick: once Java holds the URI,
`ContentResolver.openFileDescriptor(uri,"r").detachFd()` yields a real
kernel fd, passed to the cmus process over a unix socket via
`SCM_RIGHTS`. cmus gets a normal, seekable fd — past the open, reads and
seeks need zero per-read IPC, landing exactly on cmus's single-open-site
model.

## Scope & constraints (raised by Patrick)

The requirements and concerns Patrick set for this stage, each addressed
in the sections below:

- **Drop `MANAGE_EXTERNAL_STORAGE`, and don't depend on the MediaProvider
  FUSE mount for the Music folder** — SAF is the default access path.
- **Keep the direct-path modes as a debug toggle** if not too complicated
  (it isn't — see *Storage modes*).
- **Cloud providers are welcome but not the goal** — the design supports
  them via the same fd bridge, but non-path-like providers are a later
  increment, and **non-seekable fds just show an error for now** (Patrick)
  rather than materializing to a temp file.
- **`/saf`, not `//saf`** — a single leading slash, since `path_strip`
  collapses `//` and pseudo-paths must normalize cleanly (see *Facts*).
- **The SAF RPC transport is its own commit** — split into patch 0015
  (transport) and 0016 (VFS hooks); see *Patch layout*.
- **Fix the reopen-by-path plugins with their own upstream, non-Android
  patch (0004, inserted before the initial Android-IPC patch) before any SAF
  patch** — a generic `ip_reopen_path` reopen
  seam (see *Plugin reopen seam*).
- **Portable slot ids** — deterministic volume-id names for built-in local
  storage, user-selectable names for other providers (see *The `/saf` path
  scheme*).
- **Built-in local paths must stay portable when SAF is disabled in debug
  and storage/music is granted** — handled at the pl_env layer (a base var
  per volume, expanded to `/saf/<vol>` or `/storage/<vol>` per mode), so
  **no library migration is needed** (see *Cross-mode portability*).
- **UI**: SAF slots are managed only in Settings (explicit slot↔tree
  mapping); the menu keeps a Music-folder **Import** (FUSE direct /
  MediaProvider in SAF mode), and other dirs are imported from the file
  browser (see *UI*).
- **Slow/hung SAF ops** show a Cancel overlay after 250 ms; one press clears
  the whole in-flight batch via a self-extending 20 ms window, no counter
  (see *Cancellation & the slow-op overlay*).

## Design provenance — Patrick's ideas that shaped the plan

Beyond the requirements above, several of Patrick's planning-time ideas
changed the design; recorded here with what each unlocked:

- **`/saf`, not `//saf`** → a single leading slash keeps pseudo-paths
  canonicalizing cleanly through cmus's lexical path code (`path_strip`
  collapses `//`) — no mangling, no special-casing.
- **SAF RPC as its own commit** → the 0015-transport / 0016-VFS-hooks split,
  so the transport lands as an independently reviewable unit.
- **Fix the reopen-by-path plugins upstream, non-Android, before SAF** → the
  0004 `ip_reopen_path` seam: a behavior-preserving cmus cleanup that the
  Android patch fills with `/proc/self/fd`, keeping the SAF patch free of
  per-plugin AVIOContext/MP4-provider surgery.
- **Deterministic names for local storage, user-chosen for the rest** → the
  volume-id slots (`primary`, UUID): portable, table-free, install-stable,
  so an exported library re-resolves after one re-grant.
- **`/saf/<vol>` vs `/saf/virtual/<name>`** → made that split *structural*;
  the reserved `virtual` word removes the name-collision validation a flat
  namespace would have needed.
- **Keep local paths portable when SAF is off + a media grant exists**, and
  **"migration is a non-issue if pl_env is done right"** → moved portability
  to the pl_env layer (a base var per volume, expanded per mode), which in
  turn *eliminated* both the library-migration question and the runtime
  path-resolver / `CMUS_ANDROID_SAF_LOCAL` map I'd drafted — the on-disk
  library is mode-agnostic by construction.
- **Non-seekable → error for now** → kept scope tight; temp-file
  materialization stays a later increment.
- **Local `/saf` paths resolve via tree-grant *or* MediaStore, and MediaStore
  isn't Music-scoped** → made local resolution explicitly dual-backend (tree
  first, MediaStore fallback), and — since MediaStore indexes audio anywhere
  on the volume, not just the Music folder — the fallback resolves any indexed
  local audio path, with the audio-only-MIME companion-file gap as its known
  limit.
- **Wavpack `.wvc` via a nullable-`sidecar` reopen param** → the seam's
  `ip_reopen_path(ip_data, sidecar)` shape: `sidecar` replaces the filename to
  open and is constrained to a **same-directory** companion (no cross-dir
  opens), which is all `.wvc` needs and keeps the SAF sidecar resolution
  trivial (same grant as the input).
- **A 250 ms Cancel overlay** → the slow-op UX, which also became the escape
  hatch for a provider hanging a main-loop LIST (the View stays live while
  the pty is frozen).
- **The cancel mechanism, converged across Patrick's steps** — "fail
  everything submitted before the press (ts/seq)" → "it's really for
  un-checked read loops, so one press must clear a hung provider" → "cancel
  everything issued until a few ms after the press" → "then you don't need a
  counter" → "keep the window end ≥ 20 ms past each cancelled op's *reply*
  (not its receipt — ops block cmus-side, so a hung op's receive-time is
  stale)". Landing point: a self-extending 20 ms window that rides a hung
  loop of any length, needs no counter, and adds **zero cmus cancellation
  code** — a large simplification over the per-batch-seq scheme it replaced.
- **SAF slots managed only in Settings (explicit mapping), no "Import (SAF)"
  menu item; Import stays Music-folder-only (MediaProvider mapping
  `RELATIVE_PATH`→`/saf/primary/Music` in SAF mode), other dirs via the file
  browser**, and **the correction that the popover's re-probe action is
  `update-cache` (refresh metadata), not a re-`add`** → the clean split:
  Import = Music folder, connections = other trees, Refresh metadata =
  re-probe the existing library.

## Prep tasks (do first)

Small, independent cleanups **Patrick asked for and specified**, to land
before the SAF work proper (each its own small commit). **These are Session A
(Patrick): implement and commit all of the prep tasks in one session, before
the reopen seam or any SAF work** — see *Build order → Session staging*:

- **Rename the popover item "Update cache" → "Refresh metadata"**
  (`MainActivity`: the `menu.add` label + the `switch` case; the
  `updateCache()` toast may become "Refreshing metadata"). It maps to
  cmus's `update-cache` = `cache_refresh`, which re-reads tags for
  mtime-changed files (all with `-f`) and drops vanished ones — "Refresh
  metadata" says that; "Update cache" is opaque. Pure label change, no
  behavior.
- **Explain-on-press dialogs for both data actions** (Patrick) — a shared
  pattern: first press shows a dialog with a "Don't show again" checkbox,
  then runs the action on Continue. Persist per-action as an **acked
  message-version number**, not a boolean (Patrick): show iff
  `acked_version < current_version`; checking the box acks the current
  version. Bumping a message's version re-shows it after we edit the copy.
  The two messages:
  - **Refresh metadata**: only re-reads metadata for files whose *modified
    time changed* since the last scan (not a full re-read).
  - **Import**: adds new files found in the Music folder, but **doesn't
    remove tracks whose files were deleted** from disk (the known
    linger-on-delete limitation), and **other directories are imported from
    the file browser** (grant a connection in Settings, then browse + add).
    Import always targets the Music folder, so this wording is stable.

## Facts pinned down

- **cmus opens local files in exactly one place**: `open_file_locked()`,
  [input.c:474](../../third_party/cmus/input.c#L474)
  `ip->data.fd = open(ip->data.filename, O_RDONLY)`. Playback and tag
  scan both converge here (`cache.c` `ip_get_ti` → `ip_new`+`ip_open`).
  Plugins consume `ip_data->fd` ([wav](../../third_party/cmus/ip/wav.c),
  [flac](../../third_party/cmus/ip/flac.c),
  [vorbis](../../third_party/cmus/ip/vorbis.c),
  [opus](../../third_party/cmus/ip/opus.c), mad playback) and work
  unchanged with any real fd.
- **`stat()` is called on `ti->filename` in two cache paths**, so the
  single-open-site elegance does *not* extend to freshness — these need SAF
  interception (a STAT RPC) independently of OPEN:
  [cache.c:446](../../third_party/cmus/cache.c#L446) `file_get_mtime()` when a
  track is first cached, and
  [cache.c:523](../../third_party/cmus/cache.c#L523) `stat(ti->filename,&st)`
  in `cache_refresh` (the `update-cache` / Refresh-metadata path), which
  compares `ti->mtime == st.st_mtime`. On a `/saf/…` path a raw `stat()`
  returns `ENOENT`, so without a STAT hook every track reads as vanished/
  changed and Refresh metadata is broken. (Plus the wavpack `.wvc`
  `stat()`/`open()` at [wavpack.c:176-186](../../third_party/cmus/ip/wavpack.c#L176),
  handled via the reopen seam.)
- **Directory enumeration funnels through
  [load_dir.c](../../third_party/cmus/load_dir.c)** (`dir_open`→`opendir`,
  `dir_read`→`readdir`/`lstat`). Used by the browser
  ([browser.c:167](../../third_party/cmus/browser.c#L167)), the recursive
  add job ([job.c:269](../../third_party/cmus/job.c#L269)), and tab
  completion (`tabexp_file.c`). Bridging `load_dir` covers all three.
  **Verify during impl** that no caller independently `stat`/`lstat`s the
  target directory *before* `dir_open` (e.g. a browser is-it-a-dir check) —
  any such site is another `/saf` interception point beyond `load_dir`.
- **Paths are lexical, never `realpath`'d.**
  [path.c](../../third_party/cmus/path.c) `path_absolute`/
  `path_absolute_cwd`/`path_strip` only join strings and collapse
  `.`/`..`/`//` — no filesystem access. So `/saf/…` survives
  canonicalization intact, `..` navigation collapses lexically, and
  `path_strip` collapsing `//`→`/` is precisely why the scheme uses a
  single leading slash. Paths are also cmus's identity: `track_info.filename`
  is the cache/library/playlist key (hashed in `cache.c`).
- **Plugins that reopen the input by path — and the wavpack subtlety.**
  Four sites pass `filename` (or `open()` it) instead of using the fd:
  [ffmpeg.c:94](../../third_party/cmus/ip/ffmpeg.c#L94),
  [mp4.c:216](../../third_party/cmus/ip/mp4.c#L216),
  [aac.c:397](../../third_party/cmus/ip/aac.c#L397), and the mad id3 tag
  reader [mad.c:133](../../third_party/cmus/ip/mad.c#L133). **Wavpack
  *decode* does not reopen** — it drives the decoder off `ip_data->fd`
  through `WavpackStreamReader` callbacks (`.fd = ip_data->fd`,
  [wavpack.c](../../third_party/cmus/ip/wavpack.c)), so playback proper works
  with any real fd. Wavpack instead has **two secondary by-path opens**: its
  id3 comment reader
  ([wavpack.c:301](../../third_party/cmus/ip/wavpack.c#L301) `open(filename)`)
  and the **`.wvc` correction file**, which `stat()`s then `open()`s a
  *computed sibling* path — `sprintf("%sc", ip_data->filename)`
  ([wavpack.c:176-186](../../third_party/cmus/ip/wavpack.c#L176)). The by-path
  reopens and tag readers route through patch 0004's seam; the `.wvc` sidecar
  is handled as a same-directory companion via the seam's nullable-filename
  form (see *Plugin reopen seam*). All would break under a non-POSIX backend
  — but wavpack decode itself never does.
- **The Android seam already exists**: `android.c`/`android.h` (patch
  0005), the `CMUS_ANDROID_SOCKET` AF_UNIX channel, `CONFIG_ANDROID`
  branches in `input.c`, and `cache.c` already calling
  `android_state_dirty(...)`. The browser default dir is already app-pinned
  via `CMUS_ANDROID_BROWSER_DIR` (patch 0011) and the import flow
  (`refreshTracks`) already just sends `add <path>` over IPC (stage 17).
- **Three threads will issue file ops**: the main loop (browser
  `load_dir`), the worker (recursive add + tag scan via `cache.c`
  `ip_get_ti`), and the player consumer (playback `ip_open`). Pin the exact
  threads during impl; the RPC channel must be safe for all of them.

## Design

### The `/saf` path scheme — two namespaces

A pseudo-path that *looks* like a normal absolute path, so cmus's entire
`char*` machinery (dirname/basename/join, browser `..`, cache/library/
playlist keys, `pl_env`) is untouched. Slots are stable names — not
arbitrary indices — so pseudo-paths survive config export/import and
reinstall. **Two namespaces (Patrick)**, so deterministic volume ids and
user-named slots can't collide structurally (no validation rule needed):

- **`/saf/<volume-id>/<relpath>` — built-in local storage**
  (`ExternalStorageProvider`, `com.android.externalstorage.documents`).
  `<volume-id>` comes straight from the docId: `primary` for
  `/storage/emulated/0`, the volume UUID (`AAAA-BBBB`) for SD/USB.
  `<relpath>` = the volume-relative path (docId is `volume:relpath`). Pure
  string transform both ways, no table. Deterministic and portable —
  re-granting the same volume, even a *different subtree*, reproduces
  identical paths, so an imported `lib.pl` resolves after one re-grant.
- **`/saf/virtual/<name>/<relpath>` — every other provider** (cloud/MTP/…).
  `<name>` is user-chosen, bound in prefs to `{treeUri, authority}`, plus an
  app-side `docId ↔ relpath` map populated during enumeration (opaque
  docIds aren't path-like). Portable only as far as the user re-grants and
  the provider keeps docIds stable — best-effort; the cloud increment.

`virtual` is the single reserved local-namespace word (no physical volume
is named `virtual`).

Key subtlety: **slot ≠ grant.** A local slot (`primary`) may be backed by
several tree grants (multiple granted subtrees of one volume). Resolving
`/saf/primary/Music/Album/x.flac` → docId `primary:Music/Album/x.flac` →
pick any grant under `primary` whose subtree covers it →
`buildDocumentUriUsingTree`. The user manages *grants*; local paths
reference *volumes*.

**Local slots have two resolution backends, tried in order (Patrick).** A
`/saf/<vol>/…` path can be opened either through an ExternalStorageProvider
**tree grant** (above) or through **MediaStore** (`READ_MEDIA_AUDIO`, no tree
grant — the mechanism the Music-folder Import relies on). These coexist: a
user may Import Music (MediaStore-backed) *and* grant a tree for another
subtree. So the OPEN/STAT resolver for a local slot must **try tree grants
first, then fall back to MediaStore**; the fallback is keyed by
reconstructing the MediaStore row from the volume-relative path
(`RELATIVE_PATH`+`DISPLAY_NAME`). Crucially, **MediaStore is not
Music-scoped** (Patrick) — it indexes audio anywhere on the volume
(`Podcasts/`, `Download/`, …), so the fallback resolves any local *audio*
file it has indexed, not just `/saf/primary/Music/…`. Its limit is MIME:
MediaStore exposes only audio, so companion files (`.cue`, `.wvc`, cover art)
resolved *only* via the MediaStore backend can `EACCES` — same gap as the
`READ_MEDIA_AUDIO`-direct debug mode; a tree grant over the same subtree
closes it.

**Bare `/saf`** is a synthetic root whose LIST returns the local volume
slots plus a `virtual` entry (which itself lists the named connections), so
the browser home can be `/saf` and show everything without picking a
default.

Nothing real lives at `/saf` on Android, so no collision. The prefix test
is a **path-segment** match (exactly `/saf` or a `/saf/` prefix), never a
substring — a genuine `…/saffron` must not match.

### The bridge — three RPCs over a dedicated socket

| cmus site | RPC | app side |
|---|---|---|
| `load_dir.c` `dir_open`/`dir_read` | **LIST** `/saf/…` → `[{name,is_dir,size,mtime}]` | one `DocumentsContract` child query |
| `input.c:474` | **OPEN** `/saf/…` → fd via `SCM_RIGHTS` | `openFileDescriptor(uri,"r").detachFd()` |
| `cache.c` freshness | **STAT** `/saf/…` → `{size,mtime}` (free from LIST during *add*; a real RPC during *refresh*) | one `DocumentsContract` doc query, or MediaStore |

Optimizations that decide whether this is usable:

- **One round-trip per directory.** `dir_open` fetches the whole child
  listing in a single LIST; `dir_read` iterates the cached array. SAF
  queries are Binder round-trips — per-entry would make a library import
  unusable.
- **Piggyback `mtime`+`size` in LIST** (SAF's `LAST_MODIFIED`/`SIZE`
  columns) so the freshness check during a recursive *add* costs no extra RPC
  (the enumerating LIST already carries them). **But `update-cache` / Refresh
  metadata does not enumerate** — it walks the existing library file-by-file
  ([cache.c:523](../../third_party/cmus/cache.c#L523)
  `stat(ti->filename, &st)`), so there is no LIST to piggyback on and a
  per-file **STAT RPC is required** there. See *Facts* (stat sites) and
  *Refresh metadata*.
- **Reopen seam (patch 0004) filled with `/proc/self/fd/N`.** The
  open-by-path plugins already route through the upstream reopen seam (patch
  0004, below); for a `/saf/` input the SAF patch makes that seam return
  `/proc/self/fd/<bridge fd>` — an independent, seekable re-open of the
  already-open bridge fd, a plain path the decode libraries accept — so no
  per-plugin AVIOContext/MP4-provider surgery lands in the SAF patch. (Wavpack
  decode already uses the fd directly; only its id3 reader and `.wvc` sidecar
  touch the seam.)

### Plugin reopen seam — patch 0004 (upstream candidate, non-Android)

**Placement (Patrick): before the initial Android-IPC patch**, not after the
whole series. The seam is `CONFIG_ANDROID`-free, so it belongs with the
upstream candidates (0001–0003) as the new **0004**, ahead of the Android
work — which renumbers the existing Android patches (see *Patch layout*). A
consequence Patrick called out: because 0004 now precedes the patches that
*remove* remote/HTTP (0006) and CD-audio (0013), the seam is authored
against the **full upstream input set** that still has them — so its contract
must cover inputs that have no reopenable file path.

Landed *before* the SAF patches, in a generic manner (Patrick's call). The
sites that open the input (or a sibling) by name
([ffmpeg.c:94](../../third_party/cmus/ip/ffmpeg.c#L94),
[mp4.c:216](../../third_party/cmus/ip/mp4.c#L216),
[aac.c:397](../../third_party/cmus/ip/aac.c#L397),
[wavpack.c:301](../../third_party/cmus/ip/wavpack.c#L301) id3 reader + the
[`.wvc` sidecar :176-186](../../third_party/cmus/ip/wavpack.c#L176),
[mad tags mad.c:133](../../third_party/cmus/ip/mad.c#L133)) each call
`open(ip_data->filename)` or pass `filename` to their decode library.
(Wavpack *decode* is not among them — it uses `ip_data->fd` directly and is
left untouched.) This patch adds one indirection — a helper
**`ip_reopen_path(ip_data, sidecar)` returning the path to (re)open** — and
routes the by-name sites through it. Upstream that's behavior-preserving, so
it stands alone as a cmus cleanup / upstream candidate; the SAF patch (0016)
then overrides the seam for `/saf/` inputs.

- **Nullable-`sidecar` parameter (Patrick).** The seam takes a nullable
  filename that *replaces* the file to open: `sidecar == NULL` means "reopen
  the main input" (default returns `ip_data->filename`); a non-NULL `sidecar`
  is a **companion file in the same directory** as the input (default returns
  `sidecar` verbatim). **It deliberately does not support opening a file in a
  different directory** (Patrick) — the only real caller is wavpack's `.wvc`,
  which is always the input's sibling, so same-dir is sufficient and keeps
  SAF resolution trivial (the companion lives under the input's already-
  resolved grant). Wavpack calls `ip_reopen_path(ip_data, "<main>c")` for the
  correction file and `ip_reopen_path(ip_data, NULL)` for its id3 reader.
- **Errors for inputs with no reopenable path — remote (`http://`) and
  `cdda://` (Patrick).** The seam's contract is "return a path to (re)open, or
  fail." A remote stream is a socket set up by `setup_remote`
  ([input.c:278](../../third_party/cmus/input.c#L278), `ip->data.fd = sock`)
  and a CD track is a device URL — neither has a filesystem path to reopen —
  so `ip_reopen_path` returns an error for them. **Behavior-preserving
  upstream** because the sites that hit it are the *tag/sidecar* reopens
  (mad/wavpack id3, `.wvc`): today those already do `open("http://…")` and
  fail, skipping tags; an early error is the same outcome. The one input that
  legitimately reopens by URL upstream is **ffmpeg's main open**
  ([ffmpeg.c:94](../../third_party/cmus/ip/ffmpeg.c#L94)
  `avformat_open_input(filename)`) — so route the *main* open through the seam
  **only for local (`!ip_data->remote`) inputs**, leaving remote ffmpeg to
  pass `ip_data->filename` (the URL) exactly as now. That `!remote` guard on
  the main-open sites (ffmpeg/mp4/aac) is the one interaction to verify for
  the upstream-candidate claim. On Android the point is moot at runtime —
  0006/0013 (drop-remote/drop-cdda) make remote/cdda fail before any plugin
  open — but the
  seam still refuses them cleanly, and the SAF fill only ever returns
  `/proc/self/fd/N` for a `/saf/` local input.
- **Why a path, not a dup'd fd**: the tag readers need an *independent* file
  offset from the playback fd (that's why mad already opens a second handle),
  and `dup(fd)` shares the offset. A path re-open gives an independent
  description portably. On Android, `ip_reopen_path(ip_data, NULL)` returns
  `/proc/self/fd/<ip_data->fd>` — a magic-symlink re-open yielding an
  independent, seekable description of the same underlying file. So: generic
  seam upstream, `/proc/self/fd` fill on Android.
- **`.wvc` under SAF**: for a non-NULL `sidecar`, the Android seam issues a
  *fresh* bridge OPEN for the sidecar's `/saf` path (`sprintf("%sc",…)` on a
  `/saf/…` input already yields the correct sibling `/saf` path) and returns
  `/proc/self/fd/<new fd>`. Impl note: that bridge fd is a second fd whose
  lifetime must be tied to the plugin's `close` (wavpack already closes
  `priv->wvc_file.fd`) — the SAF layer must also drop the underlying bridge
  fd. On a normal filesystem the seam just returns the sibling path and the
  patch stays behavior-preserving.

### Threading — get this boundary right first

The async event/command socket (`android.sock`, patch 0005) stays as is.
The SAF bridge is a **separate RPC channel** so a blocking request from the
worker/consumer never interleaves with the main-loop command stream. It's
**correlation-id multiplexed** (each request tagged with an id; the caller
blocks on its id's response) rather than single-mutex, so the three issuer
threads (main/worker/consumer) don't head-of-line-block each other.

**Concurrency model — write lock + one dedicated reader (get this right).**
A single shared socket carrying replies *with `SCM_RIGHTS` fds* cannot have
caller threads `recvmsg` their own replies: two callers racing `recvmsg`
would steal each other's responses and, worse, each other's *passed fds*. So
the cmus side needs **one dedicated reader/dispatcher thread** that owns all
`recvmsg` on the bridge socket, reads each reply (plus any ancillary fd),
matches it to the waiting caller by correlation id, and wakes that caller
(hand off the fd + result through per-request state guarded by a condvar).
The write side is the light part: a mutex around the `sendmsg` of each
request. Per-request state (id → {done, fd, result}) is therefore needed
after all — one small table keyed by id, not the "no per-request state"
earlier drafts assumed. Pin the exact issuer threads (main/worker/consumer)
during impl; the reader thread is separate from all three.

No deadlock risk: the app services SAF RPCs on its own thread(s) and never
calls back into cmus, so a blocked cmus thread only ever waits on the app
answering autonomously (contrast the `player_pos_exact` main-loop dependency
— there is none here).

A LIST on the main loop freezes the TUI until it returns — usually a
sub-100ms query, but a slow/hung provider could stall it; the overlay
(below) is the escape hatch, since it's an Android View that stays live
while cmus's pty is frozen, and Cancel makes the blocked RPC return
promptly so the main loop resumes.

Mechanism: a second AF_UNIX connection to a `CMUS_ANDROID_SAF_SOCKET`
address (or a role-handshake on the existing address), serviced by a
dedicated app thread/pool. `SCM_RIGHTS` fd receive on the cmus side of
this socket.

Security: bind it as a **filesystem-path socket in the app-private dir**, the
same as the existing `CMUS_ANDROID_SOCKET` (which does
`strcpy(addr.sun_path, path)` from an app-set path,
[android.c:263](../../third_party/cmus/android.c#L263)) — **not** an abstract
(`\0`-prefixed) socket. Abstract sockets carry no filesystem permissions, so
any app on the device could connect and drive LIST/OPEN through the user's
grants; a private-dir path is protected by directory permissions (cmus runs
as the app's own uid). No `SO_PEERCRED` needed given the private-dir bind.

### Cancellation & the slow-op overlay (Patrick)

Any SAF op outstanding **> 250 ms** raises a visible overlay with a Cancel
button. The subtlety Patrick called out: cmus and plugins issue **sets of
reads in loops that don't check for cancellation** — enumerating a subtree,
tag-scanning a file list, a decoder's reads. Failing only the blocked op
lets the loop fire the next (also hung) one, forcing repeated presses. So
Cancel must kill the whole in-flight batch, including reads not yet issued.

- **Self-extending grace window (app-side, no counter — Patrick).** The app
  keeps a window deadline (`elapsedRealtimeNanos`). Cancel fails the
  currently-outstanding op(s) and, while the deadline is in the future,
  fails every further op that arrives; each failed op **pushes the deadline
  to its own reply-time + 20 ms** — `deadline = max(deadline, op_reply_ts +
  20ms)`, where reply-time is when the app fails the op (≈ now), **not** its
  receive-time. Anchoring to reply-time is essential because **ops block on
  the cmus side**: cmus issues them serially and doesn't send op N+1 until it
  has op N's reply, so a hung op's receive-time is stale (far in the past)
  and `receive_ts + 20ms` would already be expired — whereas failing op N is
  contemporaneous with cmus unblocking and firing op N+1, which then arrives
  ≪ 20 ms later and lands inside. So one press rides a hung loop *regardless
  of batch length* (best-effort `CancellationSignal` on the blocked
  `query`/`openFileDescriptor`), the window closing only after a ≥ 20 ms gap
  with no new op (the loop has unwound); fresh user work, on a human
  timescale, lands outside and proceeds. No seq/counter; cmus needs **no
  cancellation-specific code** — a "cancelled" RPC error is just another I/O
  failure.
- **Sole assumption**: 20 ms comfortably exceeds the gap between one op's
  reply and the next op's arrival (a single failed round-trip). Only if cmus
  did > 20 ms of work between two reads of a still-hung batch would the
  window close early and the next read re-raise the overlay — cancel again;
  fine in practice for enumeration/tag-scan loops.
- **Accepted collateral (cross-thread).** The window is process-global, not
  scoped to a thread or batch (that scoping is exactly what dropping the
  per-batch-seq scheme buys us). So an op issued by a *different* thread that
  happens to land inside the window is also failed — e.g. Cancel a hung
  worker tag-scan while a playback OPEN is in flight, and that OPEN can fail,
  spuriously failing the track. This is judged acceptable: the window is
  ~20 ms, playback past its single OPEN is zero-IPC (no per-read bridge
  traffic to catch), and the user can just replay. Noted rather than
  engineered around — re-introducing per-thread scoping would bring back the
  counter this design deliberately removed.
- **cmus side**: `ip_open` fails the track, `dir_open`/`dir_read` error
  (patch 0002 already handles browser listing errors), an add job skips/
  unwinds. cmus must tolerate a burst of these (same paths as unreadable
  files).
- **Overlay**: a View over the `TerminalView`, armed by a 250 ms-delayed
  runnable when the outstanding set goes non-empty, shown if anything is
  still pending at fire, hidden when it drains. Copy ~ "Reading from
  storage…" + Cancel. Stays responsive even when a blocked main-loop LIST
  has frozen cmus's pty.

### Cross-mode portability via pl_env (Patrick) — no migration

Portability across the SAF↔direct toggle lives at the **pl_env layer**, not
in any runtime path rewrite — so **no library migration is needed**. cmus
already stores library/cache/playlist paths as var-relative and expands them
at load; the toggle just changes what the var expands to.

> Notation note: `$VAR/relpath` below is shorthand. cmus does **not** use
> shell `$` expansion — pl_env wraps the var name between `PL_ENV_DELIMITER`
> (`\x1F`) markers and does exact prefix replace/expand
> ([pl_env.c](../../third_party/cmus/pl_env.c) `pl_env_reduce`/`pl_env_expand`,
> [pl_env.h:172](../../third_party/cmus/pl_env.h#L172)). The var *name* is
> arbitrary bytes between markers, so a volume-UUID name (`AAAA-BBBB`, with a
> hyphen) matches fine — no sanitization needed for pl_env — but the same name
> is also the real env-var passed to `getenv`, so keep it `getenv`-safe.

- One pl_env base var **per volume**, value = the volume root in the active
  mode — `/saf/<vol>` in SAF mode, `/storage/<vol>` in direct mode
  (`primary` → `/storage/emulated/0`). The volume-relative `relpath`
  (`Music/x.flac`) is identical either way, so the two forms are
  interchangeable.
- The app sets these env vars at spawn from
  `StorageManager.getStorageVolumes()` and registers them in `pl_env_vars`.
  This extends stage-10's single `CMUS_ANDROID_EXT` to one-per-volume — the
  "implemented correctly" part; get it right and migration is a non-issue.
- Result: the on-disk library stores `$CMUS_ANDROID_VOL_primary/Music/x.flac`
  regardless of mode, expanding to a `/saf/primary/…` path (→ bridge) or
  `/storage/emulated/0/…` (→ plain POSIX, branch dormant) with no rewrite.
  Existing `$CMUS_ANDROID_EXT`-wrapped libraries carry over unchanged. The
  cache is var-wrapped on disk too (**verified**: `write_ti` stores
  `pl_env_reduce(ti->filename)` [cache.c:336](../../third_party/cmus/cache.c#L336);
  `cache_entry_to_ti` restores via `pl_env_expand`
  [cache.c:135](../../third_party/cmus/cache.c#L135) — same reduce/expand as
  playlists), so it also survives a toggle (only the in-memory expanded key
  differs within a session).

So the `/saf` branch only ever sees `/saf/…` paths (SAF mode); in direct
mode paths are real and never reach it. One inherent limit of the
audio-only debug mode: `READ_MEDIA_AUDIO`'s FUSE mount exposes only
audio-MIME files, so companion files (`.cue`, wavpack `.wvc`, cover art)
can `EACCES` — all-files or SAF mode have no such gap.

### App side

- **Connections** live in prefs: for local grants, keyed by volume id with
  the auto-derived name; for others, the user name → `{treeUri, authority}`
  + docId map. `takePersistableUriPermission(READ)` on grant.
- **Browser default**: `CMUS_ANDROID_BROWSER_DIR` = `/saf` (the synthetic
  root listing connections) in SAF mode, reusing patch 0011; a real path
  or a single `/saf/<vol>` when configured otherwise.
- **Import** (the Music folder, always — Patrick): `refreshTracks` keeps
  targeting the Music folder in every mode. Direct/FUSE mode adds it by path
  (`add /storage/emulated/0/Music`, READ_MEDIA_AUDIO). SAF mode goes through
  **MediaProvider**: MediaStore enumerates the Music folder
  (`RELATIVE_PATH`/`DISPLAY_NAME` → reconstruct `/saf/primary/Music/…`) and
  the subsequent OPEN/STAT resolves through the **MediaStore backend** of the
  local-slot resolver (see *The `/saf` path scheme*) — so importing Music
  needs only READ_MEDIA_AUDIO (Play-safe), **no SAF tree grant**. (Companion
  files under MediaStore-only resolution can `EACCES` — audio-MIME only; a
  tree grant closes the gap.) Import is *not* a general picker; arbitrary
  locations arrive as SAF connections (below), browsed and added from the file
  browser.
- **Refresh metadata** (re-probe, was "Update cache"): `update-cache` walks
  the *existing* library and is source-agnostic at the command level (no
  connection iteration, no per-connection logic), so SAF-pathed entries
  re-`open` through the bridge for the tag re-read. **But it is not
  code-free on the cmus side**: `cache_refresh` first `stat()`s each
  `ti->filename` ([cache.c:523](../../third_party/cmus/cache.c#L523)) to
  decide whether the mtime changed, and a `/saf/…` path fails a raw `stat()`.
  So the `/saf` branch must service that stat via a **STAT RPC** (there is no
  enumerating LIST here to piggyback on — contrast recursive add). With the
  STAT hook in place, `cache_refresh` re-reads tags for mtime-changed files
  (all with `-f`) and drops vanished ones, exactly as on POSIX.
- **Album art**: Java-side extraction resolves the `/saf/…` pseudo-path the
  same way — real path when local-reachable, else `ContentResolver` on the
  URI.

### UI

**Settings → Storage connections** — the *only* SAF grant surface, making
the slot↔tree mapping explicit (Patrick) (the stage-18 settings screen
already hosts the SAF `onActivityResult` seam):

- A list of connections. Each row: display name (volume label — "Internal
  storage" / "SD card" — or the user name), granted subpath, provider, and
  a permission-still-valid check (persisted-perm list / `checkUriPermission`).
- **Add connection** → `ACTION_OPEN_DOCUMENT_TREE` →
  `takePersistableUriPermission` → auto-name from the volume id for local
  targets, prompt for a name otherwise.
- **Remove** → `releasePersistableUriPermission` (warn if library paths
  reference the volume — they dangle until re-granted or a direct grant
  covers them).
- **Rename** (user-named connections only).
- Storage-mode selector including the debug direct modes.

**Menu popover** (Theme / Font / **Import** / **Refresh metadata** / Sleep
timer / Settings) — **no SAF-specific menu item**; SAF slots live only in
Settings so the slot↔tree mapping stays explicit (Patrick):

- **Import** always imports the **Music folder**, mode-adaptive: direct/FUSE
  mode by path (`add /storage/emulated/0/Music`); SAF mode via MediaProvider
  — MediaStore's `RELATIVE_PATH`+`DISPLAY_NAME` map onto
  `/saf/primary/Music/…` (so Music imports need only READ_MEDIA_AUDIO, no
  tree grant). Its explain dialog adds that **other directories are imported
  from the file browser**.
- **Refresh metadata** (`update-cache`, *re-probe* — renamed in Prep tasks)
  is source-agnostic at the command level; SAF-pathed entries re-`open`
  through the bridge. Note it needs a **STAT RPC** for the per-file
  freshness `stat()` in `cache_refresh` (no LIST to piggyback on here) — see
  *The bridge* and *App side*.

So: **Import** = the Music folder, always (FUSE, or MediaProvider mapping
`RELATIVE_PATH`→`/saf/primary/Music` in SAF mode); **SAF connections**
(Settings) = the explicit slot↔tree mapping for every *other* location,
browsed via the `/saf` root and added with the existing add-to-library;
**Refresh metadata** = re-probe existing tags (`update-cache`, unchanged).

**Slow-op overlay** (see *Cancellation & the slow-op overlay*): a View over
the `TerminalView`, shown when a SAF op is outstanding > 250 ms, with a
Cancel button that fails the submitted-so-far prefix. It stays responsive
even when a blocked main-loop LIST has frozen cmus's pty.

### Storage modes

The toggle only changes what each local-volume pl_env var expands to (and
which grants exist), never the stored var-relative paths:

- **SAF** (default): var = `/saf/<vol>` → the RPC bridge; no media grant.
- **`READ_MEDIA_AUDIO`-direct** (Play-safe, debug): var = `/storage/<vol>`
  → plain POSIX; audio-only, so non-audio companions can `EACCES`.
- **all-files direct** (`MANAGE_EXTERNAL_STORAGE`, legacy/debug): var =
  `/storage/<vol>` → plain POSIX throughout.

Virtual slots (`/saf/virtual/<name>`) are SAF-only — no direct-mode form,
unaffected by the toggle. The same on-disk library resolves under any mode
with no rewrite (see Cross-mode portability).

### Patch layout — reopen seam inserted early + RPC transport split out (Patrick)

Three new cmus patches. **The reopen seam is inserted *before* the initial
Android-IPC patch** (Patrick), so it joins the upstream candidates rather
than trailing the Android work:

- **0004 — "route input-plugin reopen through an `ip_reopen_path` seam"**
  (upstream candidate, non-Android): the generic indirection above; lands
  with 0001–0003, ahead of any Android patch, behavior-preserving on a normal
  filesystem. Errors for remote/`cdda` inputs (no reopenable path); routes the
  local main-opens (ffmpeg/mp4/aac) through the seam only for `!remote` (see
  *Plugin reopen seam*).
- **0015 — "SAF RPC transport under CONFIG_ANDROID"**: the synchronous,
  correlation-id RPC socket (LIST / OPEN-with-`SCM_RIGHTS` / STAT) + the
  dedicated reader/dispatcher thread and `SCM_RIGHTS` receive in `android.c`/
  `android.h`. No file-op behavior change on its own.
- **0016 — "/saf VFS branch + file-op hooks"**: the `/saf/` two-namespace
  prefix logic wired into every interception site — OPEN at `input.c:474`,
  LIST at `load_dir.c`, and **STAT at the two `cache.c` freshness sites**
  ([:446](../../third_party/cmus/cache.c#L446) `file_get_mtime`,
  [:523](../../third_party/cmus/cache.c#L523) `cache_refresh`) — plus the fill
  of 0004's reopen seam with `/proc/self/fd/N` (and a fresh bridge OPEN for a
  non-NULL `sidecar`), consuming 0015's RPC. (Direct-mode POSIX access needs
  no code here — the pl_env var expands to a real path that bypasses the
  branch.)

**Renumbering.** Inserting the seam at position 0004 shifts every current
Android patch up by one: the Android-IPC socket 0004→**0005**, drop-remote
0005→**0006**, … drop-cdda 0012→**0013**, accept-removed-options 0013→**0014**
(patches 0001–0003 are unchanged). The two SAF patches then append after the
new 0014, so they keep the numbers **0015** (transport) and **0016** (VFS).
*This doc uses the **post-insertion** numbers throughout so each number means
one patch: android.sock is 0005 (not 0004 — that is now the seam), browser dir
0011, drop-remote 0006, drop-cdda 0013. In today's repo those still carry
their pre-insertion numbers (one lower); subtract one to find them.*

0004 is `CONFIG_ANDROID`-free (upstream candidate, joins 0001–0003 on the
submission list); 0015/0016 are `CONFIG_ANDROID`-gated; upstream Makefile
unaffected.

## Open decisions / risks (Patrick)

- **pl_env per-volume vars** are the correctness-critical piece for the
  direct toggle (extends stage-10's single `CMUS_ANDROID_EXT` to one per
  volume); done right, library migration is a non-issue (Patrick).
- **Import cost**: N files = N OPENs = N Binder `openFileDescriptor` calls
  for tag scan, plus one LIST per directory. Inherent to SAF; acceptable
  for a one-shot import, but worth a device timing on a real library
  before committing to no batching. Levers if it hurts: a bulk tag-read RPC
  (app reads tags in Java), or an opportunistic POSIX fast path for local
  volumes even in SAF mode when a media grant is also held — both dropped
  from the core for now (pl_env already covers portability).
- **Local-slot resolver is dual-backend** — *decided (Patrick): tree grant
  first, MediaStore fallback.* The OPEN/STAT resolver for `/saf/<vol>/…` tries
  ExternalStorageProvider tree grants, then MediaStore (`READ_MEDIA_AUDIO`).
  MediaStore is volume-wide (not Music-scoped), so it resolves any indexed
  local audio; its only limit is audio-MIME (companion files can `EACCES`
  under the MediaStore backend alone). Precedence and the reconstruction of
  the MediaStore row from the volume-relative path are the pieces to get right
  in the app service.
- **`cache_refresh` needs a STAT hook** — not optional. `update-cache`
  `stat()`s each library path ([cache.c:523](../../third_party/cmus/cache.c#L523));
  a `/saf` path fails a raw `stat()`, so the `/saf` branch must service it via
  a STAT RPC or Refresh metadata is broken. (Freshness during recursive *add*
  stays free from LIST columns — only the walk-the-existing-library path needs
  the RPC.)
- **Non-seekable fds** (cloud/streaming providers) — *decided (Patrick):
  error for now.* OPEN `fstat`s the returned fd and, if it's not a seekable
  regular file, fails the RPC with a distinct "not seekable" code; cmus
  surfaces it as a normal playback-open error (the track fails cleanly, no
  crash). Materialize-to-tempfile is a later increment.
- **`select()` on the fd** ([input.c:736](../../third_party/cmus/input.c#L736))
  is a no-op for a regular-file fd — verify, no change expected.

## Build order (may span sessions)

**Session staging (Patrick).** Land the work across at least three sessions,
each self-contained and committed before the next begins:

1. **Session A — prep tasks only.** Implement *and commit* the *Prep tasks*
   (the "Refresh metadata" rename + the two explain-on-press dialogs) in one
   session. They're independent of the SAF work and touch only app code, so
   they land and get committed on their own, first.
2. **Session B — the reopen seam only.** Implement *and commit* patch 0004
   (`ip_reopen_path`), rebased into place before the Android-IPC patch, in its
   own session. It's a behavior-preserving upstream candidate; verify it
   against a normal filesystem (all seam-touching codecs + the http/tag checks
   in *Verify*) before moving on.
3. **Session C onward — the rest.** The SAF transport (0015), `/saf` VFS
   (0016), and all app work follow in subsequent session(s), per the numbered
   steps below.

The numbered build order:

1. **0004** reopen seam — `ip_reopen_path(ip_data, sidecar)` indirection, the
   by-name sites routed through it (ffmpeg/mp4/aac + mad tags + wavpack id3 &
   `.wvc`; wavpack decode stays on the fd); errors for remote/`cdda` inputs,
   `!remote`-guards the ffmpeg/mp4/aac main-opens; behavior-preserving,
   upstream candidate, inserted before the Android-IPC patch (renumbering the
   existing Android patches — see *Patch layout*).
2. **0015** RPC transport — sync socket + `SCM_RIGHTS`, both ends
   (`android.c` + app service thread). Its own commit.
3. **0016** begins: `/saf/` two-namespace scheme, prefix test, volume-id ↔
   docId resolver (SAF-mode paths only).
4. **LIST** → bridged `load_dir` → browser navigates a granted tree (and
   the synthetic `/saf` root lists connections).
5. **OPEN** + `SCM_RIGHTS` → play one hand-picked `/saf/` file; fill the
   0004 seam with `/proc/self/fd/N` → the ex-open-by-path plugins
   (ffmpeg/mp4/aac + mad tags, wavpack id3) play; wavpack decode already
   worked off the fd. Then the wavpack `.wvc` sidecar via the seam's
   nullable-`sidecar` bridge OPEN.
6. Recursive `add /saf/<vol>` (freshness free from LIST columns) **and the
   STAT RPC** wiring the two `cache.c` stat sites → `update-cache` / Refresh
   metadata works.
7. App: storage-connections manager (settings, explicit slot↔tree mapping),
   volume-id slot naming, Music-folder **Import** via MediaProvider
   (`RELATIVE_PATH`→`/saf/primary/Music`), browse-and-add for other trees,
   album-art resolve.
8. Storage-mode selector + per-volume pl_env vars + debug direct modes.

(Cloud / non-seekable materialization = a later stage.)

## Verify (device, Patrick hands-on)

- Fresh install, **no** `MANAGE_EXTERNAL_STORAGE`, `READ_MEDIA_AUDIO`
  denied: grant a tree → browser lists it, tracks add, tags scan, a file
  of each codec (flac/vorbis/opus/mp3/wav/**ffmpeg/mp4/aac/wavpack**)
  plays and seeks. The reopen-seam codecs (ffmpeg/mp4/aac/wavpack, via
  `/proc/self/fd`) are the ones to watch — plus a `.wvc` wavpack file.
- Re-tap Import: no duplicates; `lib.pl` entries carry `/saf/…` and
  survive an app restart (persisted perms).
- **Refresh metadata over a SAF library**: touch a file's mtime (re-tag it),
  Refresh metadata → only that track re-reads (the STAT hook drives the
  freshness compare); nothing reads as spuriously vanished. Without the STAT
  hook this is the first thing that breaks.
- SAF-mode Import: MediaStore `RELATIVE_PATH` maps onto `/saf/primary/Music`
  with only `READ_MEDIA_AUDIO` (no tree grant); tracks add and play.
- Other directory (a granted connection): browse the `/saf` root → add to
  library from the browser; entries carry `/saf/<vol>/…`.
- Browser `..` from a subdir lands correctly; no `/saf` `ENOENT` in logs.
- Idle-quit still guards mid-import (stage 17 jobs mechanism, unchanged).
- Debug toggle → direct mode: `/saf/primary/…` library resolves via POSIX,
  still plays; toggle back to SAF, same library still plays.
- Non-seekable provider (a streaming DocumentsProvider): playback shows a
  clean error, no crash, no hang.
- Slow/hung provider: an op > 250 ms raises the overlay; one Cancel clears
  the whole in-flight batch (no repeated presses through a long enumeration
  or tag-scan) and the TUI unfreezes; a browse started afterward works.
- Timing: note import wall-clock on a real library (round-trip cost).
- Upstream-candidate check: with 0004 applied and *no* SAF/removal patches, a
  normal filesystem still plays every codec whose plugin touches the seam
  (ffmpeg/mp4/aac/mp3 + wavpack, incl. a `.wvc` correction file)
  (behavior-preserving). Plus the remote interaction: an **http stream that
  ffmpeg decodes still plays** (confirms the `!remote` guard leaves the URL
  main-open intact), and reading id3 tags off a local file still works
  (confirms the seam's `sidecar==NULL` path). These two are the only
  behaviors the seam could regress upstream.
- `patch.sh check` green after 0004/0015/0016 (and the renumbered
  0005–0014); clean `assembleDebug`.

## Commits

(Prep-task commits — the "Refresh metadata" rename + explain dialogs — land
first, per *Prep tasks*.)

1. `cmus: route input-plugin reopen through ip_reopen_path seam (patch 0004)`
2. `cmus: SAF RPC transport — sync socket + SCM_RIGHTS (patch 0015)`
3. `cmus: /saf VFS branch + file-op hooks (patch 0016)`
4. `app: SAF RPC service thread`
5. `app: storage connections — grant/persist, volume-id slots, settings manager`
6. `app: Music-folder Import via MediaProvider + browse-and-add for trees`
7. `app: route browser default and album art through /saf`
8. `app: storage-mode selector + per-volume pl_env vars + direct modes`
9. `notes: stage 23 status + architecture`
