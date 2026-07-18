# Architecture

Updated each stage. See [plans/00-overview.md](plans/00-overview.md) for the
full plan and rationale; this file describes what currently exists.

## Repo layout

```
├── notes/            spec, plans/, status.md, this file
├── app/              Android app module (Java 21, no androidx/deps)
├── third_party/      13 pinned submodules (cmus, termux-app, ncurses,
│                     libogg, libvorbis, opus, opusfile, flac, libmad,
│                     wavpack, faad2, mp4v2, libiconv)
├── patches/          per-submodule patch dirs (none yet)
├── patch.sh          vncpatch-style patch apply/regenerate
├── settings.gradle   root: pluginManagement + :app
├── build.gradle      AGP 9.3.0 (apply false)
└── gradle/, gradlew  Gradle 9.6.1 wrapper
```

## Third-party sources / patches

- Submodule gitlinks always point at pristine upstream commits (shallow=true
  in .gitmodules except savannah libiconv, which can't serve sha-in-want);
  local changes live as `patches/<name>/*.patch` on top.
- `./patch.sh [-n] [name…]`: tags each gitlink commit as `base` in the
  submodule, `git am`s the patches when HEAD == base, then regenerates the
  patch files from base..HEAD (`-n` skips regen). `./patch.sh check` is
  read-only; the `:app:patchCheck` Exec task runs it before `preBuild` so
  builds fail with a hint when patches exist but aren't applied.
- Pins (2026-07-18): cmus master d335e90, termux-app v0.118.3, ncurses
  snapshot 87c2c84, libogg v1.3.6, libvorbis v1.3.7, opus v1.6.1, opusfile
  master 6dfd29e, flac 1.5.0, libmad (tenacityteam) main 0637016, wavpack
  5.9.0, faad2 2.11.2, mp4v2 v2.1.3, libiconv v1.18 (plan-pinned; v1.19
  exists).

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

Native CMake build of deps + cmus (3–5),
terminal UI via termux terminal-emulator/-view (6), cmus unix-socket IPC
(7–8), media session (9), then chrome/input/overlays/settings (10+).
