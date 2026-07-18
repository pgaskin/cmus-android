# Stage 4 — Native deps B (libmad fix + ncurses/libiconv ports)

Goal: the remaining native deps. libmad turns out to need no port (see
below) — just a submodule fix + `add_subdirectory`. ncurses and libiconv
get handwritten CMake ports under `native/ports/` with committed
pregenerated files, plus the compiled terminfo asset. `cmus_deps` grows
from 9 to 12 static libs; the terminfo asset is the only APK change.

## libmad: wrong submodule content (found while planning this stage)

`github.com/tenacityteam/libmad` now silently redirects to
`tenacityteam/libid3tag` (repo rename after their Codeberg move), so the
stage-2 pin 0637016 is actually **libid3tag 0.16.2** — wrong content,
harmless so far since nothing referenced it. The real fork is
`codeberg.org/tenacityteam/libmad`.

- `git submodule set-url` → codeberg, re-pin at be34ec9 (libmad 0.16.4,
  HEAD; its tip commit is literally "Allow build with cmake 4.0").
- Its CMake is fine for stage-3-style `add_subdirectory`: `mad-static`
  target; on aarch64 (64-bit pointers) it auto-picks `FPM_64BIT`, and the
  ARM asm path is 32-bit-only, so nothing to disable. Set `EXAMPLE=OFF`.
  The unconditional `mad` SHARED target exists but is never built
  (EXCLUDE_FROM_ALL + cmus_deps).
- shallow=true if codeberg/gitea serves sha-in-want fetches, else
  shallow=false like libiconv (check at implementation).

## One-time host generation pattern (both ports)

Upstream generates these files at build time with host tools; we run that
once per submodule bump and commit the outputs:

- `native/ports/<name>/gen.sh` — documented, rerunnable, works in a
  scratch dir (never dirties the submodule), writes into
  `native/ports/<name>/gen/`.
- Rerunning must be a no-op diff (strip timestamps/paths if any leak in).
- Host prereqs: cc, NDK clang (ncurses configure), tic (terminfo),
  **gperf (not currently installed — dnf install gperf)**.

## ncurses port — `native/ports/ncurses/`

- `gen.sh`: out-of-tree `configure` from the submodule with NDK r28c
  clang (`--host=aarch64-linux-android --with-build-cc=cc --enable-widec
  --without-shared --without-progs --without-cxx --without-cxx-binding
  --without-ada --without-tests --without-manpages --disable-db-install
  --without-debug`; exact final set documented in the script), then
  `make sources`; copy into `gen/`:
  - include/: `curses.h term.h ncurses_cfg.h ncurses_def.h hashsize.h
    parametrized.h`
  - ncurses/: `codes.c comp_captab.c comp_userdefs.c fallback.c lib_gen.c
    lib_keyname.c names.c unctrl.c init_keytry.h`
  (`expanded.c`/`link_test.c` are trace/QA-only — confirm and exclude.)
- `CMakeLists.txt`: static `ncursesw` from the submodule's
  `ncurses/{base,tinfo,tty,widechar}/*.c` filtered per the
  `ncurses/modules` list (skip trace/driver/win32 modules) + the
  generated .c; includes: `gen/`, submodule `include/`, `ncurses/`.
  Non-TRACE build. cmus only needs the standard API + `resizeterm` +
  `use_default_colors` — both in base.
- **terminfo asset**: `gen.sh` compiles the submodule's
  `misc/terminfo.src` with host tic (`tic -x -1 -o`) and commits exactly
  `app/src/main/assets/terminfo/x/xterm-256color` (TERM is fixed by us at
  runtime; aliases dropped — assets can't hold the alias link farm tic
  writes, revisit if something needs them). Host tic is 6.6 vs the 6.4
  snapshot lib; compiled format is stable, but if extended-caps loading
  misbehaves, fall back to building tic from the submodule in gen.sh.
  Runtime extraction stays in stage 6.

## libiconv port — `native/ports/libiconv/`

- **Bump pin v1.18 → v1.19 first** (the stage-2 "revisit at stage 4"
  item; do it before generating so outputs match the pin).
- Sources: `lib/iconv.c` (pulls every converter via included headers) +
  `libcharset/lib/localcharset.c`; static `iconv` target. Relocatable
  support off.
- `gen.sh` outputs in `gen/`: `aliases.h canonical.h canonical_local.h`
  (compile+run `lib/genaliases.c`, pipe through `gperf -m 10` per
  Makefile.devel), `flags.h` (genflags), `translit.h` (gentranslit);
  `iconv.h` from `include/iconv.h.in` and `localcharset.h` from its .in
  (sed substitutions documented in the script).
- Handwritten bionic `config.h` (+ libcharset's) covering only what
  `iconv.c`/`localcharset.c` actually test (ICONV_CONST empty, no NLS,
  no relocatable; langinfo/O_CLOEXEC details at implementation). LIBDIR
  define pointed at a harmless path (only used for charset.alias probing).
- GNU libiconv exports `libiconv_*` with iconv.h remapping `iconv_*` —
  fine for static linking, cmus sees the standard API.

## Top-level wiring (native/CMakeLists.txt)

- libmad via `add_subdirectory` with the stage-3 conventions;
  `add_subdirectory(ports/ncurses)`, `add_subdirectory(ports/libiconv)`.
- `cmus_deps` += `mad-static ncursesw iconv` → 12 libs.

## Verify

- Clean `./gradlew clean assembleDebug`: 12 `.a` under `app/.cxx`, all
  AArch64; APK diff = exactly the terminfo asset.
- `llvm-nm` sanity: ncursesw has `initscr`/`resizeterm`/
  `use_default_colors`; iconv has `libiconv_open`; mad has
  `mad_decoder_init`. (Real link test comes free in stage 5.)
- Rerun both gen.sh → empty git diff. patchCheck still gates preBuild.

## Risks / decide at implementation

- codeberg sha-in-want → libmad shallow true/false.
- Exact ncurses configure flags, module-list filtering, and whether any
  source needs a bionic patch — that would be the first real use of
  patch.sh/patches/.
- Host tic 6.6 output vs 6.4 lib (fallback: build tic from the tree).
- Bionic config.h specifics for libiconv/localcharset.

## Commits

1. `third_party: repoint libmad to codeberg (real libmad, not libid3tag)`
2. `third_party: bump libiconv to v1.19`
3. `native: add ncurses port (gen.sh + pregenerated sources + terminfo asset)`
4. `native: add libiconv port`
5. `native: build libmad + wire ports into cmus_deps`
