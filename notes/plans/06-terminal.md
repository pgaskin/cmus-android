# Stage 6 — terminal MVP (termux view + service + cmus in a pty)

Goal: cmus running inside the app. Termux terminal-emulator/-view
consumed as a gradle dep, TerminalView in MainActivity, a foreground
service owning the cmus pty session, the in-app runtime layout (plugin
symlinks, terminfo + data extraction), IME toggle. Verify: usable TUI
in-app, flac plays through aaudio from the app uid (retires the
stage-5 open question).

## Amendments from Patrick (deviations from the overview)

- termux libs are imported as a **gradle dep from JitPack**, not as
  source modules via settings.gradle projectDir redirection. This
  sidesteps their AGP-4-era build.gradle (Gradle-9-removed `classifier`,
  `compileSdkVersion`, `project.properties.*` from their root
  gradle.properties, manifest `package=` attrs AGP 9 rejects for source
  builds) with zero patches. The termux-app submodule is removed
  (13 → 12 submodules).
- androidx is now allowed where it makes sense (the terminal-view AAR
  pulls androidx.annotation transitively; later stages may use more,
  e.g. media for stage 9 — decide there).

## Facts pinned down (termux-app @ v0.118.3)

- JitPack coordinates (verified while drafting: poms + AARs all
  HTTP 200): group `com.github.termux.termux-app`, artifacts
  `terminal-view`/`terminal-emulator`, version `v0.118.3`;
  terminal-view's pom depends on terminal-emulator, so one dep pulls
  both. The tag was already built on JitPack (2025-07-06, commit
  matches our pin, jdk11 + NDK 21.1.6352462 per jitpack.yml). Note the
  termux custom-domain group `com.termux.termux-app` does NOT serve
  artifacts — use the com.github one.
- terminal-emulator's AAR ships `libtermux.so` (218-line forkpty JNI,
  loaded via `System.loadLibrary("termux")`; inspected the AAR: all
  4 ABIs present incl. arm64-v8a — our `abiFilters` keeps just ours;
  `useLegacyPackaging` extracts it like the rest). terminal-view adds
  `androidx.annotation:1.3.0` (runtime scope in the pom). Library
  minSdk 24 merges fine under our 34.
- API surface: `new TerminalSession(shellPath, cwd, args, env,
  transcriptRows, TerminalSessionClient)`;
  `updateTerminalSessionClient()` exists for activity re-attach.
  `TerminalView.attachSession/setTerminalViewClient/setTextSize/
  onScreenUpdated`; clients are plain interfaces (keys, scale, taps,
  clipboard, bell, logging) — no subclassing needed for the MVP.
- Plugin scan (stage 5): cmus reads `$CMUS_LIB_DIR/{ip,op}/*.so`,
  name = filename minus `.so`; `misc.c` wants CMUS_{HOME,LIB_DIR,
  DATA_DIR} + TERMINFO/TERM in env — the adb-shell layout from the
  stage-5 verify is exactly what the app now builds itself.
- targetSdk 36 ⇒ edge-to-edge is enforced and `adjustResize` is a
  no-op: the activity must consume WindowInsets (systemBars + cutout +
  ime) itself and pad/resize the terminal view.

## Gradle / repo changes

- settings.gradle `dependencyResolutionManagement`: add jitpack as an
  `exclusiveContent` repo filtered to group `com.github.termux.termux-app`
  (keeps FAIL_ON_PROJECT_REPOS honest).
- app: `implementation 'com.github.termux.termux-app:terminal-view:v0.118.3'`.
- Remove the termux-app submodule (`git submodule deinit`, `git rm`,
  .gitmodules entry); patches/ never had a termux dir. architecture.md
  pin list updated at the end of the stage.
- cmus `data/` (rc + 17 *.theme) becomes an APK asset without copying
  into the repo: a gradle Copy task
  `third_party/cmus/data → build/generated/cmus-assets/cmus-data/`
  registered as an assets srcDir (submodule stays pristine; wired
  before merge-assets).

## Runtime layout (Java, before spawn)

Helper class (e.g. `CmusFiles`), idempotent, called by the service:

- `filesDir/terminfo/x/xterm-256color` + `filesDir/cmus-data/*` ←
  asset extraction, redone when versionCode (stored marker) changes.
- `filesDir/cmus-lib/{ip,op}/<name>.so` → `Os.symlink` to
  `nativeLibraryDir/libcmus_{ip,op}_<name>.so`; wiped + recreated on
  every service start (cheap, immune to nativeLibraryDir moving
  between installs).
- `filesDir/cmus-home/` created empty (cmus autosaves into it).

## Service + activity

- **TermService** (foreground, `mediaPlayback` type; manifest gains
  FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK,
  POST_NOTIFICATIONS): owns the TerminalSession. Spawns
  `nativeLibraryDir/libcmus.so`, cwd = filesDir, env HOME=filesDir,
  TMPDIR=cacheDir, TERM=xterm-256color, TERMINFO, CMUS_HOME,
  CMUS_LIB_DIR, CMUS_DATA_DIR. Small transcript (altscreen app).
  Local Binder handing the session to the activity. Minimal
  notification ("cmus running", channel; real media notification is
  stage 9). `onSessionFinished` → stopForeground/stopSelf (+ finish
  the activity if attached). Starting a mediaPlayback FGS from a
  foreground activity needs no runtime grant; POST_NOTIFICATIONS is
  requested once in the activity so the notification shows.
- **MainActivity**: layout = TerminalView; binds + starts the service,
  attaches the session, `setTextSize` to a sane dp-derived default.
  TerminalViewClient impl: single tap toggles the IME
  (InputMethodManager), `onScale` adjusts font size (pinch zoom,
  clamped), back button NOT mapped to escape (back = background the
  app; playback continues under the FGS), long-press left at default
  (text selection; disabled later in stage 14). Insets listener pads
  for bars/cutout/IME; `onTextChanged` → `onScreenUpdated()`.
  Rotation: activity re-binds and re-attaches (session lives in the
  service); `updateTerminalSessionClient` on re-attach.

## Verify

- Clean `./gradlew clean assembleDebug`: APK gains libtermux.so
  (arm64 only), com.termux classes, `assets/cmus-data/*`; native cmus
  libs + terminfo asset unchanged.
- On device: install + launch → cmus TUI renders themed, `:` command
  line and view switching (1–7) work from the soft keyboard; tap
  toggles IME; pinch zoom.
- Push test tracks (flac + mp3) into `filesDir/music` (adbd runs as
  root on this device; `run-as` is the fallback), `:add ~/music`, play
  flac → audio audible from the app uid, position advances, pause +
  seek work. This is the real aaudio test flagged in stage 5.
- Home/recents-away while playing → audio continues (FGS); relaunch →
  view re-attaches to the live session. Rotate → session survives.
  `:quit` → service + activity exit cleanly, autosave written in
  cmus-home.

## Risks / decide at implementation

- AAR manifests carry `package=` attrs: fine for dependencies under
  AGP 9 (the restriction is on source modules) — confirm at first
  sync, it fails fast if not.
- IME-over-terminal behavior with enforced edge-to-edge (insets
  listener vs. resize glitches) needs on-device eyeballing.
- Whether the stub notification is acceptable UX for now — it is, it
  gets replaced in stage 9.

## Commits

1. `app: termux terminal libs via jitpack; drop termux-app submodule`
2. `app: cmus runtime layout (plugin symlinks, terminfo, data assets)`
3. `app: terminal UI + foreground service, cmus in a pty`
4. `notes: stage 6 status + architecture`
