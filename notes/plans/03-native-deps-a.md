# Stage 3 — Native deps A (upstream-CMake libs)

Goal: AGP-driven CMake tree compiling the deps that ship usable CMake —
libogg, libvorbis, opus, opusfile, flac, wavpack, faad2, mp4v2 — as
arm64-v8a static libs. No cmus, no ports (ncurses/libmad/libiconv are
stage 4); nothing new packaged into the APK yet.

## Toolchain

- `ndkVersion 28.2.13676358` (r28c; already installed in `~/sdk/android`).
- CMake: **SDK cmake 3.30.x** via sdkmanager (exact patch version = whatever
  sdkmanager lists), pinned with `version` in gradle. Not host cmake / not
  4.x: CMake 4 dropped compat with `cmake_minimum_required` < 3.5, which
  breaks libvorbis (2.8.12), mp4v2 (3.0), wavpack (3.2). Fallback if a 3.x
  SDK cmake is somehow unavailable: any 4.x + global
  `-DCMAKE_POLICY_VERSION_MINIMUM=3.5`.
- `abiFilters 'arm64-v8a'`; platform level comes from minSdk 34 via AGP.
- `ANDROID_STL` left at default `c++_static` — mp4v2 is C++, the rest is C.

## Layout / CMake tree

```
native/
├── CMakeLists.txt    project; per-dep options (CACHE FORCE) before each
│                     add_subdirectory(../third_party/<x> <x> EXCLUDE_FROM_ALL)
└── cmake/
    ├── FindOgg.cmake   shims: alias in-tree targets to Ogg::ogg / Opus::opus
    └── FindOpus.cmake  and set *_FOUND etc.
```

- `native/` is the CMake source root (per overview); sources live outside
  it, so each `add_subdirectory` passes an explicit binary dir.
- **find_package shims**: libvorbis does `find_package(Ogg REQUIRED)`,
  opusfile needs Ogg + Opus, flac needs Ogg for `WITH_OGG`. All three only
  *append* their own `cmake/` module dirs, so `native/cmake/` prepended to
  `CMAKE_MODULE_PATH` wins with Find modules that point at the in-tree
  targets instead of probing the system.
- `EXCLUDE_FROM_ALL` everywhere + one `add_custom_target(cmus_deps)`
  depending on exactly the libs cmus will link (ogg, vorbis, vorbisfile,
  opus, opusfile, FLAC, wavpack, faad, mp4v2 — exact target names checked
  at implementation); gradle builds only `cmus_deps`, which keeps
  vorbisenc and other stray targets out.

Per-dep options (set as CACHE FORCE; global `BUILD_SHARED_LIBS=OFF`,
`BUILD_TESTING=OFF`):

| dep       | options                                                       |
|-----------|---------------------------------------------------------------|
| libogg    | INSTALL_DOCS, INSTALL_PKG_CONFIG_MODULE, INSTALL_CMAKE_PACKAGE_MODULE → OFF |
| libvorbis | (globals only)                                                |
| opus      | OPUS_BUILD_PROGRAMS=OFF, OPUS_BUILD_TESTING=OFF; NEON intrinsics auto |
| opusfile  | OP_DISABLE_HTTP=ON (also drops the OpenSSL dep), OP_DISABLE_EXAMPLES=ON, OP_DISABLE_DOCS=ON |
| flac      | WITH_OGG=ON, BUILD_PROGRAMS/EXAMPLES/DOCS=OFF, INSTALL_MANPAGES=OFF; its optional Iconv probe finds nothing on NDK (only the flac tool uses it) |
| wavpack   | WAVPACK_BUILD_PROGRAMS=OFF, WAVPACK_ENABLE_LIBICONV=OFF; legacy stays OFF, threads/DSD stay ON; install/docs options auto-off as subproject |
| faad2     | FAAD_BUILD_CLI=OFF; FAAD_APPLY_DRC kept at default ON         |
| mp4v2     | BUILD_SHARED=OFF, BUILD_UTILS=OFF                             |

## Gradle wiring (app/build.gradle)

```gradle
android {
    ndkVersion '28.2.13676358'
    externalNativeBuild { cmake { path '../native/CMakeLists.txt'; version '3.30.x' } }
    defaultConfig {
        externalNativeBuild { cmake { targets 'cmus_deps' } }
        ndk { abiFilters 'arm64-v8a' }
    }
}
```

## Verify

- `./gradlew assembleDebug` configures + builds `cmus_deps`; all 9 `.a`
  files exist under `app/.cxx/`, `llvm-readelf -h` says AArch64.
- APK contents unchanged (static libs only, no jniLibs).
- patchCheck still gates preBuild; clean `./gradlew clean assembleDebug`
  from scratch works.

## Risks / decide at implementation

- Exact SDK cmake 3.30.x patch version available from sdkmanager.
- faad2/mp4v2/wavpack library target names (table above assumed).
- wavpack aarch64 asm: if its NEON asm misbuilds under NDK, disable via
  its asm option and note it.
- If AGP refuses a bare custom target in `targets`, fall back to listing
  the 9 library targets there directly.

## Commits

1. `native: build upstream-CMake deps as arm64 static libs` (CMake tree +
   gradle wiring)
