# Architecture

Updated each stage. See [plans/00-overview.md](plans/00-overview.md) for the
full plan and rationale; this file describes what currently exists.

## Repo layout

```
├── notes/            spec, plans/, status.md, this file
├── app/              Android app module (Java 21, no androidx/deps)
├── settings.gradle   root: pluginManagement + :app
├── build.gradle      AGP 9.3.0 (apply false)
└── gradle/, gradlew  Gradle 9.6.1 wrapper
```

## App module

- `net.pgaskin.cmus.android`, minSdk 34 (Android 14), target/compileSdk 36.
- Plain framework APIs only (`android.app.Activity`, custom views later) —
  no androidx, no external Java deps.
- `MainActivity` — stub (black screen) for now.
- Theme: `Theme.Cmus` (Material NoActionBar, black, short-edges cutout);
  colors become cmus-theme-driven in a later stage.

## Build requirements

- Android SDK at `~/sdk/android` (`local.properties`, gitignored), host JDK
  25, network for gradle/AGP resolution.
- `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

## Coming next (see overview stages)

Submodules + patch.sh (2), native CMake build of deps + cmus (3–5),
terminal UI via termux terminal-emulator/-view (6), cmus unix-socket IPC
(7–8), media session (9), then chrome/input/overlays/settings (10+).
