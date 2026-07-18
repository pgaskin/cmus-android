# Stage 8 — Java IPC client (CmusIpc)

Goal: `CmusIpc` — the Java side of the stage-7 socket. Connects to
`$filesDir/cmus-android.sock`, parses the JSON event stream into typed
state delivered to listeners on the main thread, sends raw command
lines, reconnects on its own. TermService owns it; `set mouse=true`
moves off the legacy cmus server socket onto it. Verify: logcat shows
live status/track/volume/options; commands sent from Java take effect
with the matching events coming back.

## Facts pinned down

- The contract is the protocol comment atop android.c
  (patches/cmus/0001). Outbound: newline-delimited JSON objects, one
  per line, `type` ∈ hello/status/position/volume/options; strings are
  guaranteed valid UTF-8 (`u_to_utf8` before escaping); every event is
  complete-state and a (re)connect always starts with a full snapshot
  (hello, status, volume, options) — so the client carries no state
  across connections and reconnect fixes everything. `position` events
  are only sent when no status event goes out in the same flush.
- Inbound: raw cmus command lines (incl. `/`/`?` search prefixes), no
  acks; android.c's persistent line buffer is 4096 bytes, and an
  overlong line **drops the client** — CmusIpc must refuse commands
  ≥ 4095 bytes (and embedded newlines) rather than send them.
- Single client, new-connection-wins: cmus closes the old client when
  a new one connects. Our auto-reconnect therefore also recovers from
  a debugging `nc` stealing the connection (and steals it back).
- `LocalSocket` + `LocalSocketAddress.Namespace.FILESYSTEM` to an
  abstract path already works from Java — TermService.forceMouseOption
  is the stage-6 precedent, including the connect-poll loop (cmus
  needs a moment after spawn to bind the socket).
- Tag keys "may repeat (e.g. multiple artists)" per the contract.
  Android's org.json `JSONObject` silently keeps only the last
  duplicate key (JSONTokener parses via `put`), so it can't parse the
  `tags` object faithfully → use the streaming `android.util.JsonReader`
  (framework, handles duplicate names naturally, no dependency).
- The options event is big (~130 options; `OPTION_MAX_SIZE` 4096, and
  one arrives after *every* executed command) — parse off the main
  thread, deliver an immutable snapshot map.
- On `quit`, cmus closes + unlinks the socket, then the pty session
  ends → TermService.onSessionFinished → stopSelf. Reconnect attempts
  in that window fail on the unlinked path; close() from the service
  must stop the retry loop so shutdown is quiet.
- pl_env (Patrick's note in status.md): explicitly "for later stages"
  — needs the external-storage path story (READ_MEDIA_AUDIO is stage
  18) and a decision about when the option must be set relative to
  library load. Out of scope here; slot it with stage 10 (lifecycle)
  at the earliest.

## Design — CmusIpc

One file, `app/src/main/java/net/pgaskin/cmus/android/CmusIpc.java`.
Modern-Java shape per the overview: a sealed event hierarchy consumed
with switch patterns.

### API

```java
public final class CmusIpc implements AutoCloseable {
    public sealed interface Event permits Hello, Status, Position, Volume, Options {}
    public record Hello(String version) implements Event {}
    public record Status(PlayState state, String file, int duration,
                         int position, Map<String, List<String>> tags) implements Event {}
    public record Position(int position) implements Event {}
    public record Volume(int left, int right) implements Event {}   // percent, -1 unknown
    public record Options(Map<String, String> values) implements Event {}
    public enum PlayState { STOPPED, PLAYING, PAUSED }

    public interface Listener {
        void onEvent(Event event);
        default void onConnected() {}
        default void onDisconnected() {}
    }

    public CmusIpc(File socketPath) { ... }   // starts connecting immediately
    public void addListener(Listener l)       // main thread; replays cached state
    public void removeListener(Listener l)
    public void send(String command)          // any thread; drop + log if disconnected
    public Status status(); Volume volume(); Options options();  // last known, null before first event
    @Override public void close()             // stop reconnecting, close socket
}
```

- **Threading**: one reader thread per connection (blocking readLine +
  JsonReader parse), writes posted to a single `HandlerThread` (socket
  writes never touch the main thread; tiny writes, cmus reads
  promptly). Parsed events and connect/disconnect transitions are
  posted to the main-thread Handler; the state cache is updated there
  right before listener dispatch, so getters and listeners always
  agree. `close()` shuts the socket down (`shutdownInput`/`Output`
  then close — plain close doesn't reliably unblock a LocalSocket
  read) and quits the writer thread.
- **Parsing**: `BufferedReader(InputStreamReader(in, UTF_8))` per
  line; per line a `JsonReader` over a `StringReader`, switch on
  `type`, `skipValue()` for unknown fields *and* unknown types
  (forward compatibility with future android.c additions). `tags`
  accumulates into a `LinkedHashMap<String, List<String>>` preserving
  order and duplicates. Absent file/duration/position/tags (stopped,
  no track) → `file` null, numbers -1, empty tags. Maps are wrapped
  unmodifiable before delivery. A malformed line is a bug in
  android.c, not the network: log it loudly, drop the connection,
  reconnect (fresh snapshot beats silently skewed state).
- **State cache + replay**: last Status/Volume/Options kept (Hello →
  logged + kept as version; Position folded into nothing — it's
  transient). `addListener` immediately posts `onConnected` (if
  connected) and the cached Status/Volume/Options in snapshot order,
  so late attachers (the activity in stages 9/11+) start fully
  populated without waiting for change events.
- **Reconnect**: connect attempts every 100 ms for the first ~10 s
  (spawn race, same budget as forceMouseOption), then every 1 s
  forever until close(). Connection loss (EOF, EPIPE, parse error) →
  `onDisconnected` → same loop. No state carries over; the snapshot
  re-primes everything.
- **send()**: rejects strings containing `\n` or ≥ 4095 UTF-8 bytes
  (IllegalArgumentException — a caller bug, not a runtime condition);
  when disconnected, logs and drops (events are complete-state, a
  queued command against a dead cmus is meaningless).

## Design — TermService wiring

- `getSession()` creates the `CmusIpc` right after the
  `TerminalSession` (socket path = the same
  `filesDir/cmus-android.sock` it already exports); exposed via a
  `getIpc()` accessor for later stages. `close()` in `onDestroy` and
  in `onSessionFinished` (whichever comes first; close is idempotent).
- **forceMouseOption is deleted**: a service-owned Listener sends
  `set mouse=true` from `onConnected` — every (re)connect, idempotent.
  This retires the last consumer of the legacy `cmus-home/socket`
  connect-poll and is self-verifying: the command triggers the
  options-dirty flag, so the returned options event must show
  `mouse=true`.
- The same service listener logs every event at DEBUG (`cmus` tag) —
  status/position/volume one-liners; options as the count plus a few
  interesting keys (mouse, softvol, colorscheme-sensitive color_*) so
  logcat stays readable. This is the stage's observable output; later
  stages replace eyeballing logcat with real consumers.
- **CmusDebugReceiver** (new, small): manifest-registered,
  `exported=false`, no-ops unless `ApplicationInfo.FLAG_DEBUGGABLE` —
  forwards a string extra to `CmusIpc.send`. Lets adb (root on the
  Pixel 8) drive arbitrary commands through the *Java* write path:
  `am broadcast -n net.pgaskin.cmus.android/.CmusDebugReceiver -e cmd
  player-pause`. Kept permanently as a debug tool for later stages.

## Verify (device, wifi adb + logcat)

- Launch: logcat shows connect → hello (version matches the stage-7
  VERSION format), status, volume, options(~130); then the mouse
  command's echo — a second options event with `mouse=true`. Tap =
  click still works (mouse forced via the new path).
- Play a track in the TUI: status event; position events ~1/s while
  playing, none while paused.
- Through CmusDebugReceiver: `player-pause` → cmus pauses + status
  event back; `seek +5` → position event; `colorscheme <other>` → one
  coalesced options event with changed color_* (spot-check
  color_win_cur/title_bg, not color_win_bg — gruvbox note from stage
  7); `vol 50` → volume event; an overlong/newline command → the
  receiver's send throws, logged, client *not* dropped.
- Reconnect: `toybox nc -U` steals the connection → logcat shows
  onDisconnected, then auto-reconnect (stealing it back, nc gets EOF)
  with a full fresh snapshot.
- Rotate + relaunch the activity: no IPC churn (service-owned);
  `:quit` in the TUI → clean shutdown, no reconnect log spam after
  onSessionFinished, service gone.
- Clean `./gradlew assembleDebug`; `./patch.sh check` untouched/green
  (no cmus changes this stage).

## Risks / decide at implementation

- LocalSocket teardown quirks: if shutdown-then-close still leaves the
  reader blocked on some path, fall back to closing the underlying
  FileDescriptor; the reader's IOException path already handles the
  fallout.
- If root `am broadcast` can't reach the non-exported receiver after
  all, flip it to `exported=true` with the DEBUGGABLE gate as the only
  guard (debug builds only on this device; acceptable).
- Exact DEBUG-log format and which option keys get logged — decide
  while writing; keep one event per line for jq-ability of logcat.
- Whether Hello deserves a public accessor (version string) now or
  when something needs it — lean minimal.

## Overview open item due this stage

The overview slates the bundled-font shortlist decision for stage 8
(implementation lands stage 16). Proposal to confirm with Patrick:
Iosevka (the default), JetBrains Mono, Fira Mono, IBM Plex Mono,
Roboto Mono, and Spleen or Cozette (ttf-packaged bitmap look) as the
Terminus-style option.

## Commits

1. `app: CmusIpc — typed events + commands over the android socket`
2. `app: wire CmusIpc into TermService (mouse option, event log, debug receiver)`
3. `notes: stage 8 status + architecture`
