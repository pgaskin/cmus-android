#!/bin/sh
# One-time host generation for the ncurses port (rerun after bumping the
# third_party/ncurses pin; must produce an empty git diff otherwise).
#
# Runs ncurses' own configure + `make sources` out-of-tree in a scratch dir
# (the submodule itself is never touched) with the NDK clang the app build
# uses, then copies the generated sources/headers into gen/ with the
# absolute source path stripped from generated-by comments. Also compiles
# the terminfo entry we ship as an asset.
#
# Host prereqs: cc (any host compiler, for the build-time generator tools),
# tic (ncurses), and the Android NDK below.
set -eu

cd "$(dirname "$0")"
port=$PWD
root=$(cd ../../.. && pwd)
src=$root/third_party/ncurses

# keep in sync with app/build.gradle (ndkVersion, minSdk)
ndk=${ANDROID_NDK:-$HOME/sdk/android/ndk/28.2.13676358}
api=34
cc=$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android$api-clang
[ -x "$cc" ] || { echo "error: NDK clang not found at $cc (set ANDROID_NDK)" >&2; exit 1; }

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
cd "$tmp"

# Flag notes: --with-build-cc compiles the generator tools (make_hash,
# make_keys) for the host; everything else trims the build down to the
# wide-char library only. The resulting lib composition is
# termlib+ext_tinfo+base+widechar+ext_funcs (no ticlib/trace/driver
# modules), with ext-colors enabled by default for ABI 6 + widec.
# The default /usr/share/terminfo paths baked into ncurses_cfg.h are
# irrelevant at runtime: the app always sets $TERMINFO.
echo "> configure (log: $tmp/configure.log)"
CC=$cc "$src/configure" \
    --host=aarch64-linux-android \
    --with-build-cc=cc \
    --enable-widec \
    --without-shared \
    --without-progs \
    --without-cxx \
    --without-cxx-binding \
    --without-ada \
    --without-tests \
    --without-manpages \
    --disable-db-install \
    --without-debug \
    > configure.log 2>&1

echo "> make sources (log: $tmp/make.log)"
make sources > make.log 2>&1

# copy into gen/, replacing the absolute submodule path that leaks into
# generated-by comments so reruns are location-independent
copy() {
    mkdir -p "$port/gen/${2%/*}"
    sed "s|$src|third_party/ncurses|g" "$1" > "$port/gen/$2"
}

# generated headers (from *.in / MK*.sh; the rest of include/ is static
# in the submodule)
for f in curses.h term.h termcap.h unctrl.h ncurses_cfg.h ncurses_def.h \
         ncurses_dll.h hashsize.h parametrized.h; do
    copy "include/$f" "include/$f"
done

# generated library sources; this is every `.`-directory module in
# ncurses/modules except link_test (check-target only). expanded.c is a
# real member of the normal lib, not trace/QA-only.
for f in codes.c comp_captab.c comp_userdefs.c expanded.c fallback.c \
         lib_gen.c lib_keyname.c names.c unctrl.c init_keytry.h; do
    copy "ncurses/$f" "ncurses/$f"
done

# terminfo asset: just the one entry, compiled with host tic (-e drops the
# alias link farm; TERM is fixed at runtime so aliases are never looked up)
echo "> tic xterm-256color"
tic -x -1 -e xterm-256color -o "$tmp/terminfo" "$src/misc/terminfo.src"
mkdir -p "$root/app/src/main/assets/terminfo/x"
cp "$tmp/terminfo/x/xterm-256color" "$root/app/src/main/assets/terminfo/x/xterm-256color"

echo "> done; check git status for unexpected diffs"
