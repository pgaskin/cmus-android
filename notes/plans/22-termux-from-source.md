# Stage 22 — termux from source (16 KB page-size fix, no jitpack)

Goal: build the termux terminal from the pristine `third_party/termux-app`
submodule instead of the JitPack AAR, so `libtermux.so` is compiled with our
NDK r28 (16 KB-aligned) and the JitPack dependency disappears. Fixes Android
16's "not 16 KB aligned" dialog and retires the stage-6 flagged item.

## Why this was flagged

- Android 15/16 devices with 16 KB pages require every loaded `.so` to have
  `PT_LOAD` segments aligned to `>= 16384` (`p_align 0x4000`). Our own
  NDK-r28 libs (libcmus.so + the ten codec/output plugins) were already
  `0x4000` — verified with `llvm-readelf -l`. The **only** offender was
  `libtermux.so`, shipped prebuilt inside the terminal-emulator AAR and built
  by JitPack with NDK 21 → `p_align 0x1000` (4 KB). So the whole warning came
  from one ~10 KB JNI shim we don't compile ourselves.

## Why source-into-:app (the third option stage 6 didn't take)

Stage 6 considered consuming termux as source Gradle modules and rejected it:
their AGP-4-era `build.gradle` files (Gradle-9-removed `classifier`,
`compileSdkVersion`, `project.properties.*` from termux's root
`gradle.properties`, manifest `package=` attrs AGP 9 rejects) don't build
under our toolchain. It chose the JitPack AAR to get zero patches.

This stage sidesteps that blocker entirely by **never touching termux's
build.gradle**. We pull only the pristine *source*:

- **Java** — `terminal-emulator/src/main/java` + `terminal-view/src/main/java`
  added to the `:app` main sourceSet's `java.srcDirs`. They compile in our
  namespace under our Java 21 options; no per-module Gradle, no publishing
  tasks, no manifest merge (the module manifests are package-only stubs).
- **Resources** — `terminal-view/src/main/res` (three strings + two
  selection-handle drawables) added via `res.srcDir`; they merge into the
  app's `R`. One patch is unavoidable here: two text-selection files
  `import com.termux.view.R`, which no longer exists as a separate library R.
  `patches/termux-app/0001-*` redirects them to
  `net.pgaskin.cmus.android.R`. Managed by `patch.sh` exactly like the cmus
  patches (gitlink pinned at pristine `v0.118.3`, patch `git am`'d on top,
  `preBuild` patchCheck enforces it).
- **Native** — `native/ports/termux/CMakeLists.txt` compiles the submodule's
  `terminal-emulator/src/main/jni/termux.c` into `libtermux.so` as a plain
  SHARED target in our existing CMake tree, so it inherits NDK r28's 16 KB
  alignment. Upstream Android.mk cFlags reused, minus `-Werror` (r28 clang
  warns on code that compiled clean under r21). `'termux'` added to the
  app's `externalNativeBuild` targets list. Self-contained (libc + jni.h
  only, no `-llog`); loaded by `com.termux.terminal.JNI` via
  `System.loadLibrary("termux")`.
- **Dependency** — the JitPack `com.github.termux.termux-app:terminal-view`
  line and the `jitpack.io` exclusiveContent repo in `settings.gradle` are
  gone; `androidx.annotation` (previously transitive through the AAR) becomes
  a direct `implementation`.

## Result

`assembleDebug` green; all 12 native libs in the APK are `p_align 0x4000`
including our freshly built `libtermux.so`, which still exports the five
`Java_com_termux_terminal_JNI_*` symbols and ships arm64-v8a only (the AAR's
three extra ABIs are gone). Submodule count 12 → 13; JitPack removed.
