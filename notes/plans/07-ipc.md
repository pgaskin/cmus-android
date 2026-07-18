# Stage 7 — cmus IPC patch (android.c unix socket)

Goal: the first real cmus patches. `android.c` — an app-facing unix
socket that pushes status/position/volume/options events as JSON and
accepts cmus command lines — plus the remote-stream removal, both as
`patches/cmus/*.patch` via patch.sh (which gets its first real
exercise). Verify from adb with `toybox nc -U` against the socket of
the running app.

## Facts pinned down (cmus @ d335e90)

- cmus already has a unix-socket server (`server.c`, `$CMUS_HOME/socket`,
  driven in stages 5–6): request/response only, plain text, no event
  push — that's the gap android.c fills. Its `read_commands` loop
  (nonblocking read, split on `\n`, `/`/`?` search prefixes, else
  `parse_command` + `run_parsed_command`) is the model for our inbound
  side; its `cmd_status` is the model for the status event's fields.
- mpris.c is purely an *event/callback* model: `mpris_fd` in the
  ui_curses select loop, `mpris_process()` on readiness, change
  notifications called from `update()` (status at ui_curses.c:1977,
  metadata at :1980), volume sites (ui_curses.c:2296,2314 +
  command_mode.c:1228,1264), loop/shuffle in options.c, seek in
  player.c. We need *fewer* hook sites than mpris (below): mpris must
  push each D-Bus property change eagerly; we flush once per main-loop
  iteration and can read `player_info.*_changed` + compare cached
  volume ourselves.
- `player_info_snapshot()` runs at the top of each main-loop iteration
  (ui_curses.c:2240) and the `*_changed` flags stay valid for the whole
  iteration; `update()` reads them without resetting. A flush hook
  placed right after `update()` sees exactly what mpris' callbacks see.
  `position` is whole seconds, so `position_changed` fires ≤1/s while
  playing — a per-change position event is naturally rate-limited.
- All user-reachable option changes (`set`, `toggle`, `colorscheme` —
  themes are files of `set` commands sourced line by line) go through
  `run_parsed_command` (command_mode.c:2931). One hook there + a dirty
  flag + the once-per-iteration flush covers "settings always
  up-to-date" including colors, coalescing a colorscheme's ~40 sets
  into one event. Options enumerate via `option_head` / `opt->get`
  (string values, `OPTION_MAX_SIZE` 4096).
- SIGPIPE is already SIG_IGN (ui_curses.c:2374) — writes to a dead
  client fail with EPIPE instead of killing cmus.
- Remote streams: the only callers of http.c's API are input.c's
  remote machinery (`do_http_get` … `open_remote`, lines ~180–430;
  ip/cdio.c also calls it but isn't built). Entry points:
  `cmus_detect_ft` (cmus.c:157) classifies `is_http_url` names as
  FILE_TYPE_URL, and `ip_open` (input.c:591) branches to
  `open_remote`. `is_http_url` has ~15 other call sites but they're
  all benign display/cache checks — not touched.
- pl_env (Patrick's note in status.md): entirely env-var driven
  upstream code — `pl_env_vars` option + env vars at startup. No cmus
  patch needed ever; TermService exporting `ANDROID_DATA_DIR` etc. and
  setting the option is Java-side work for stage 8/10.

## Patch 1 — `patches/cmus/0001-add-android-ipc-socket.patch`

New files `android.c` + `android.h`, hunks in ui_curses.c +
command_mode.c. Everything guarded by `CONFIG_ANDROID` (a plain
compile definition from our CMake, *not* a `config/*.h` — the patch
must stay inert for upstream Makefile builds, which never define it or
compile android.c); android.h provides mpris.h-style no-op macros
otherwise.

### android.c

- `android_init()` (called next to `mpris_init` in `init_all`): reads
  `CMUS_ANDROID_SOCKET` from env; unset → stays disabled (same binary
  runs patch-inert outside the app). Unlink stale path, bind + listen
  SOCK_STREAM, O_NONBLOCK, fds exposed mpris-style as
  `android_fd` (listen) + `android_client_fd`. `android_free()` in
  `exit_all` closes + unlinks.
- Single client, new-connection-wins: `accept` while a client exists
  closes the old one (app restart/reconnect just works). On accept:
  mark everything dirty → the next flush sends a full snapshot.
- `android_process()` on client readability: nonblocking read into a
  line buffer, per line: `/`/`?` search prefixes and
  `parse_command` + `run_parsed_command`, mirroring server.c's
  read_commands (minus passwd/status/format_print). EOF/error → close
  client. No per-command ack; command errors show in the TUI like any
  local command (Java-side needs, if any, are stage 8's problem).
- `android_flush()` (called once per main-loop iteration, right after
  `update()`): if a client is connected, compose + write pending
  events —
  - `player_info.status_changed || file_changed || metadata_changed`
    (or connect-dirty) → **status** event;
  - `position_changed` → **position** event;
  - cached `soft_vol ? soft_vol_l/r : scale_to_percentage(volume_l/r)`
    differs (or connect-dirty) → **volume** event (replaces all four
    mpris volume hook sites and poll_mixer handling);
  - options dirty flag (or connect-dirty) → **options** event.
  Writes are write_all-style on the nonblocking fd; EAGAIN (client
  stalled with a full socket buffer — shouldn't happen with a local
  reader) and EPIPE → drop the client; it reconnects for a fresh
  snapshot.
- Outbound protocol: newline-delimited JSON, one object per line, with
  a `type` field. Strings pass through `u_to_utf8` (mpris.c precedent
  — tags aren't guaranteed UTF-8 and JSON requires it) then a small
  gbuf JSON-escape helper (`"` `\` control chars).
  - `{"type":"hello","version":VERSION}` on connect
  - `{"type":"status","status":"stopped|playing|paused","file":…,
    "duration":n,"position":n,"tags":{key:val…}}` — fields per
    server.c cmd_status; tags straight from `ti->comments`; absent
    track → file/duration/position/tags omitted
  - `{"type":"position","position":n}`
  - `{"type":"volume","left":n,"right":n}` (percent, −1 = unknown)
  - `{"type":"options","options":{name:value…}}` — every option in
    `option_head` as the string `opt->get` returns; includes all
    `color_*` for stage 11 chrome
- **Deviation from the overview** ("newline-delimited JSON both
  ways"): inbound is raw cmus command lines, newline-delimited — not
  JSON. It's exactly the proven server.c protocol, commands can't
  contain newlines, and it saves writing a JSON *parser* in C (the
  emit side is trivial, the parse side isn't). Java sends
  `player-pause\n`.

### Hook hunks

- ui_curses.c: `android_init()` in init_all; `android_free()` in
  exit_all; SELECT_ADD_FD for the two fds + `android_process()` on
  readiness in the main loop (beside the mpris_fd lines);
  `android_flush()` after the `update()` call (:2242). ~5 small hunks.
- command_mode.c: `android_options_changed()` (sets the dirty flag) at
  the end of `run_parsed_command`. 1 hunk.
- No hooks in update()/options.c/player.c — the flush-reads-flags
  design makes the mpris sites there unnecessary.

## Patch 2 — `patches/cmus/0002-remove-remote-streams.patch`

Guarded by `CONFIG_ANDROID` so the patch is inert upstream:

- cmus.c `cmus_detect_ft`: the `is_http_url(name) || is_cue_url(name)`
  branch keeps only `is_cue_url` (cue "URLs" are local) — http:// adds
  fall through to stat() and fail as invalid.
- input.c: `#ifndef CONFIG_ANDROID` around the remote machinery
  (`do_http_get` through `open_remote`, incl. base64/icy helpers),
  `#else` a stub `open_remote` returning
  `-IP_ERROR_FUNCTION_NOT_SUPPORTED` `#endif` — stale library entries
  fail cleanly at open; no http.c symbol remains referenced, so
  http.c drops from the link.

## Build / app wiring (not patches)

- native/cmus/CMakeLists.txt: add `${src}/android.c`, remove
  `${src}/http.c`, `target_compile_definitions(cmus PRIVATE
  CONFIG_ANDROID)` (core only — plugins don't need it), android.c
  added to the VERSION `set_source_files_properties` list (hello
  event).
- TermService env: `CMUS_ANDROID_SOCKET=<filesDir>/cmus-android.sock`
  (filesDir root, *not* cmus-home — keeps the stage-18 tar export free
  of socket files; well under the 108-byte sun_path limit). Java IPC
  client is stage 8; this stage only exports the var.

## Verify

- patch.sh roundtrip for real this time: commits on top of `base` in
  the submodule, `./patch.sh` regen → files in patches/cmus/; reset
  submodule → `./gradlew assembleDebug` fails via patchCheck with the
  hint → `./patch.sh` → clean build. `./patch.sh check` green.
- Build: clean assembleDebug; `nm -D` on libcmus.so shows android_*
  exported (ENABLE_EXPORTS) and no http_get/http_open.
- Device (adbd is root on the Pixel 8): install + launch, then
  `toybox nc -U /data/user/0/net.pgaskin.cmus.android/files/cmus-android.sock`:
  - connect → hello + status + volume + options lines; capture a
    session and validate every line with `jq` on the host.
  - play in the TUI → status event, position events ~1/s; `:seek`
    → position event.
  - send `player-pause\n` through nc → cmus pauses, status event back.
  - `:set softvol=true` and `:colorscheme <other>` in the TUI →
    single options events with the new values/colors (the
    always-up-to-date check).
  - second nc connect → first drops with a fresh snapshot on the new
    one; kill nc + reconnect → fresh snapshot; `:quit` with nc
    attached → clean exit, socket unlinked, no lingering service.
  - `:add http://example.com/x.mp3` → rejected as invalid; library
    still loads.

## Risks / decide at implementation

- Exact status-event field set (e.g. whether to add `aaa_mode`-style
  extras beyond cmd_status's) — finalize while writing android.c; the
  protocol comment block at the top of android.c is the single source
  of truth stage 8 codes against.
- `-Wmissing-prototypes`/`-Wredundant-decls` are in the cflags —
  android.c must keep every non-API function static and declare the
  API only in android.h.
- If the input.c `#ifndef` block turns out to leave an unused-static
  warning (icy/base64 helpers straddling the region), widen the guard
  rather than sprinkling more ifdefs.

## Commits

1. `cmus: android IPC socket (patches/cmus/0001, android.c + hooks)`
2. `cmus: drop remote stream support (patches/cmus/0002, http.c out)`
3. `app: export CMUS_ANDROID_SOCKET for the cmus pty env`
4. `notes: stage 7 status + architecture`
