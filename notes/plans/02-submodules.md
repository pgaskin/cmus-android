# Stage 2 — Submodules + patch.sh

Goal: all third-party sources pinned as submodules; vncpatch-style patch
workflow in place (no actual patches yet); build fails helpfully if patches
aren't applied.

## Submodules (under `third_party/`)

| name       | upstream                                  | pin            |
|------------|-------------------------------------------|----------------|
| cmus       | github.com/cmus/cmus                      | master         |
| termux-app | github.com/termux/termux-app              | latest release |
| ncurses    | github.com/mirror/ncurses                 | latest snapshot|
| libogg     | github.com/xiph/ogg                       | latest release |
| libvorbis  | github.com/xiph/vorbis                    | latest release |
| opus       | github.com/xiph/opus                      | latest release |
| opusfile   | github.com/xiph/opusfile                  | master         |
| flac       | github.com/xiph/flac                      | latest release |
| libmad     | github.com/tenacityteam/libmad            | master         |
| wavpack    | github.com/dbry/WavPack                   | latest release |
| faad2      | github.com/knik0/faad2                    | latest release |
| mp4v2      | github.com/enzo1982/mp4v2                 | latest release |
| libiconv   | git.savannah.gnu.org/git/libiconv.git     | v1.18 tag      |

Notes:
- libmad: tenacityteam fork is maintained **and ships CMake** — likely
  removes the need for a handwritten port (decide finally in stage 4).
- libiconv git needs gnulib to generate config headers — we won't run
  that; stage 4's port pregenerates them (same approach as ncurses).
- Pins recorded by the gitlink itself; exact versions chosen at
  implementation time (latest stable of each).

## patch.sh (repo root)

Adapted from `~/src/vncpatch/vncpatch.sh` for N submodules:

- For each dir in `patches/*/` matching a submodule name:
  - ensure tag `base` exists at the gitlink commit (create if missing);
  - if mid-`git am`: print status, exit 1;
  - if `HEAD == base` and patches exist: `git am patches/<name>/*.patch`;
  - unless `-n`: regenerate via `git format-patch --output-directory
    patches/<name>/ --no-stat --no-signature --numbered --always base`
    (delete + rewrite dir, like vncpatch).
- `./patch.sh [-n] [name…]` — optional submodule filter.
- `./patch.sh check` — exits nonzero (with hint) if any submodule with
  patches is still sitting at `base`; wired into gradle `preBuild`.
- Empty `patches/.gitkeep` for now; first real patches arrive in stage 5
  (cmus config) / stage 7 (android.c).

## Verify

- `git submodule update --init` from clean clone works; `du -sh` sane.
- `./patch.sh` and `./patch.sh check` no-op cleanly with no patches.
- `./gradlew assembleDebug` still builds (preBuild check passes).

## Commits

1. `third_party: add submodules` (+ .gitmodules with shallow=true where
   supported)
2. `patch.sh: vncpatch-style patch apply/regen + gradle check`
