# Stage 1 — Scaffolding

Goal: minimal buildable repo skeleton. `./gradlew assembleDebug` produces an
installable (empty) APK. No submodules yet (stage 2), no native code.

## Files

- `.gitignore` — `.idea/`, `.gradle/`, `build/`, `local.properties`,
  `*.iml`, `.cxx/`, `.kotlin/`, keystores.
- Gradle wrapper — recent Gradle 9.x (host JDK is 25, needs a current
  Gradle) + latest stable AGP supporting compileSdk 36. Exact versions
  picked at implementation against what resolves.
- `settings.gradle` — rootProject name + `:app` only (termux modules get
  wired when first used, stage 6).
- `build.gradle` / `gradle.properties` — AGP plugin classpath,
  `android.useAndroidX=false` (no androidx/material deps; plain framework
  APIs only, custom views throughout — keeps the app minimal).
- `app/build.gradle` — application module:
  - `namespace net.pgaskin.cmus` (adjustable), minSdk 34, target/compile
    Sdk 36
  - Java language level 21 (records, switch patterns, etc. — the max d8
    handles well); no test/androidTest source sets, no dependencies.
- `app/src/main/AndroidManifest.xml` — single `MainActivity`
  (plain `android.app.Activity`), launcher intent, no permissions yet.
- `app/src/main/java/net/pgaskin/cmus/MainActivity.java` — stub (black
  screen).
- Minimal `res/` — app name string, adaptive launcher icon (placeholder),
  black NoTitleBar theme.
- `notes/architecture.md` — initial: repo layout + pointers, updated each
  stage.
- `notes/status.md` — log started with stages 0–1.

## Verify

- `./gradlew assembleDebug` succeeds from clean checkout.
- APK installs + launches on device (adb) — black screen, no crash.

## Commit

Single commit: `scaffolding: gradle skeleton, stub app, notes`.
