# Stage 9 — Media control (MediaControl)

Goal: `MediaControl` — MediaSession + real media notification + cover
art + headset/system keys, fed entirely by CmusIpc events, commands
going back over `ipc.send`. The stub "running" notification is
replaced. Verify: system media controls (shade/QS carousel, lockscreen)
show live state/metadata/art with a working seekbar, and media buttons
+ audio-focus interruptions drive cmus.

## Facts pinned down

- Framework `android.media.session.MediaSession` +
  `Notification.MediaStyle`, no androidx/media3 (project convention;
  minSdk 34 means no compat shims needed). On 13+ the system renders
  media controls from the session's PlaybackState **actions** +
  MediaMetadata — notification action buttons are ignored; the
  MediaStyle notification just anchors the session in the shade and
  carousel. A seekbar appears when metadata has DURATION and the state
  has ACTION_SEEK_TO.
- `PlaybackState.setState(state, positionMs, speed)` extrapolates
  client-side at `speed` — no per-second updates needed. cmus Position
  events (≤1/s, only when no Status in the same flush) are the seek
  signal; a seek while *paused* also arrives as a position-only event
  (Status is only sent on state/track/metadata change).
- Command surface (command_mode.c): `player-play` **restarts the
  current track from the beginning** when already playing/paused
  (player.c `_producer_play`), so it is not a resume;
  `player-pause` toggles; `player-pause-playback` pauses only if
  playing (ideal for onPause and focus loss); `player-next`,
  `player-prev`, `player-stop`; `seek <n>` absolute seconds (upstream
  clamps absolute seeks to duration−5, stage-5 note). CmusIpc caches
  Status, so callbacks can branch on current state.
- Status.tags is a multimap (keys may repeat, e.g. artist); duration/
  position are seconds (METADATA_KEY_DURATION wants ms); `file` is
  null when stopped with no track.
- Cover art: `MediaMetadataRetriever.getEmbeddedPicture()` off the
  status event's file path (readable — filesDir now, direct paths
  later per overview). Framework extractors cover mp3 APIC / flac
  PICTURE / m4a covr; ogg/opus METADATA_BLOCK_PICTURE is spotty and
  wavpack unsupported (setDataSource may throw) → the folder-art
  fallback does real work. Bitmaps must be downsampled (multi-MB art
  vs binder/notification limits).
- op/aaudio.c sets AAUDIO_USAGE_MEDIA + AAUDIO_CONTENT_TYPE_MUSIC and
  follows STREAM_MUSIC volume, so: audio-focus request uses matching
  AudioAttributes, and hardware volume keys already work via default
  local playback — no VolumeProvider (softvol UI is stage 12, in-app).
- Audio focus is cooperative and cmus knows nothing of it: we mirror.
  cmus starting playback without focus granted is user intent from the
  TUI — never counter it, just log. Ducking is automatic since
  Android 8 when the app doesn't handle DUCK itself.
- POST_NOTIFICATIONS is already declared + requested (stage 6);
  channel + mediaPlayback FGS exist. Media buttons (headset/BT AVRCP)
  route to the last active MediaSession's Callback via the default
  `onMediaButtonEvent`; `adb shell cmd media_session dispatch
  play-pause` simulates them without hardware.

## Design — MediaControl

One file, `app/src/main/java/net/pgaskin/cmus/android/MediaControl.java`,
implementing `CmusIpc.Listener` (TermService registers it; the DEBUG
log listener stays separate). Owns the MediaSession, the notification
content, cover-art loading, audio focus, and the becoming-noisy
receiver. Everything runs on the main thread except art decoding.

### Session + state mapping

- MediaSession created active with `setSessionActivity` →
  MainActivity PendingIntent (same intent the notification uses).
- Status event → PlaybackState: STATE_PLAYING/PAUSED/STOPPED,
  position s→ms, speed 1.0f playing / 0f otherwise; actions = PLAY |
  PAUSE | PLAY_PAUSE | SKIP_TO_NEXT | SKIP_TO_PREVIOUS | SEEK_TO |
  STOP. MediaMetadata: TITLE = tags `title` joined ", " (fallback:
  file basename), ARTIST = tags `artist` joined ", ", ALBUM,
  DURATION ms (absent → -1/omitted), ALBUM_ART when loaded.
- Position event → seek detection only: update PlaybackState iff
  |event − extrapolated position| > ~2 s (covers seeks while playing,
  and speed 0 makes any seek-while-paused trip it); otherwise ignore
  — no 1/s binder churn.
- onDisconnected: nothing — state stays; the reconnect snapshot
  re-primes everything (CmusIpc replay also primes a late-created
  MediaControl).
- `close()`: abandon focus, unregister receiver, release the session,
  shut down the art executor; idempotent.

### Callback mapping (MediaSession.Callback)

- onPlay → current state PAUSED ? `player-pause` (toggle) :
  `player-play` (stopped: start; harmless no-op with nothing to play).
- onPause → `player-pause-playback`.
- onSkipToNext/Previous → `player-next` / `player-prev`.
- onSeekTo(ms) → `seek <ms/1000>`.
- onStop → `player-stop`.
No onMediaButtonEvent override — the default dispatch to the above is
exactly right, sticky play/pause included.

### Cover art

- Single-thread executor, keyed by the status file path. On Status
  with a changed file: submit embedded-picture load; null/throw →
  folder art in dirname: {cover,folder,front,album}.{jpg,jpeg,png},
  case-insensitive, first match; decode with inJustDecodeBounds +
  inSampleSize to ≤640px. Post to main; apply only if the current
  file still matches (stale guard). Cache the last result
  (path → bitmap, plus dir → folder-art bitmap so album-internal
  track changes don't redecode); no metadata art until the load
  lands (system shows its placeholder).
- No art found → no ALBUM_ART key (system placeholder), not the app
  icon.

### Notification

- `Notification.MediaStyle().setMediaSession(token)` on the existing
  FGS notification id; content title/text mirror track title/artist
  (shade fallback text), small icon, contentIntent → MainActivity,
  ongoing while the service runs. Re-`notify()` only on status/
  metadata/art change, never on position.
- TermService.onStartCommand calls startForeground with
  `mediaControl.buildNotification()` (empty state pre-snapshot is
  fine; events fill it in milliseconds later).

### Audio focus + noisy

- AudioFocusRequest AUDIOFOCUS_GAIN, AudioAttributes USAGE_MEDIA +
  CONTENT_TYPE_MUSIC (matching aaudio), listener on the main handler.
- Transition into PLAYING → request (denied → log only, see facts);
  into STOPPED → abandon. Keep holding focus while PAUSED so
  transient interruptions resume correctly.
- LOSS → `player-pause-playback`, clear resume flag.
  LOSS_TRANSIENT(_CAN_DUCK handled as GAIN-noop/ignore) →
  `player-pause-playback`, set resume flag. GAIN → if resume flag and
  state is PAUSED, `player-pause`; clear flag. A user unpausing via
  TUI while interrupted just works (state events keep us honest).
- ACTION_AUDIO_BECOMING_NOISY receiver (registered while service
  lives) → `player-pause-playback`.

## Design — TermService wiring

- `getSession()` creates MediaControl right after CmusIpc and
  registers it as a listener; onStartCommand reorders slightly so
  startForeground uses the MediaStyle notification. Stub notification
  text/code deleted; channel stays `term` (rename is cosmetic churn —
  decide at implementation).
- `onDestroy`/`onSessionFinished` → `mediaControl.close()` beside
  `ipc.close()` (session released before stopSelf so the carousel
  entry dies with the service).

## Verify (device, wifi adb + logcat + hands-on)

- Play in the TUI → shade/QS/lockscreen controls show title/artist/
  album/art, seekbar advancing; pause in TUI → button flips. Stopped
  → controls show stopped state, notification still present (FGS).
- From system controls: play/pause/next/prev/seekbar-drag all drive
  cmus (TUI reacts + events in logcat); seek near track end clamps
  (duration−5); seek while paused works (position-only event trips
  the threshold).
- `cmd media_session dispatch play-pause` (headset-key path) toggles;
  BT headset buttons + AVRCP metadata hands-on by Patrick if handy.
- Art: flac/mp3/m4a embedded art shows; ogg/opus falls back to folder
  art; wv (retriever throws) survives to folder art; artless track →
  placeholder, no crash; rapid track skips → no stale art (guard).
- Focus: play in another media app → cmus pauses (status event);
  no auto-resume on permanent loss. Simulated transient (e.g. phone
  call or assistant) → pause + resume on end. Unplug/BT-disconnect →
  pause (becoming-noisy; hands-on).
- Rotation/relaunch: no session churn (service-owned). `nc -U` steal
  → reconnect snapshot leaves controls correct. `:quit` → notification
  + carousel entry gone, no leaks/reconnect spam, service gone.
- Clean `./gradlew assembleDebug`; `./patch.sh check` green (no cmus
  changes this stage).

## Risks / decide at implementation

- Seek-detection threshold (~2 s) — tune against real drift; whole-
  second positions + client extrapolation should stay well under it.
- Art cache shape: last-track + last-dir pair vs a small LRU — start
  minimal, grow only if skipping around an album feels redecodey.
- Whether the carousel needs `notify()` after ALBUM_ART lands or
  re-setting metadata suffices — empirically on-device.
- Multi-valued artist join separator (", " vs "; ") — cosmetic.
- If `cmd media_session dispatch` is missing/renamed on this build,
  fall back to `input keyevent KEYCODE_MEDIA_*`.
- STOPPED PlaybackState in the carousel can look odd (some launchers
  drop it); if it renders badly, keep STATE_PAUSED at 0 with no
  metadata instead — decide on sight.

## Commits

1. `app: MediaControl — MediaSession/notification/art/focus off CmusIpc`
2. `app: wire MediaControl into TermService, drop stub notification`
3. `notes: stage 9 status + architecture`
