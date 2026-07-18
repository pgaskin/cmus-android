#!/bin/sh
# One-time host generation for the libiconv port (rerun after bumping the
# third_party/libiconv pin; must produce an empty git diff otherwise).
#
# The git tree has no ./configure (that is an autotools bootstrap output),
# so the encoding tables are generated here with libiconv's own generator
# programs per Makefile.devel, and iconv.h comes from include/iconv.h.in
# with the configure substitutions applied by hand (values for
# bionic/static are documented inline below). config.h is handwritten
# (../config.h), not generated.
#
# Everything runs in a scratch dir; the submodule is never touched.
#
# Host prereqs: cc, gperf (table format can differ across gperf versions;
# last generated with gperf 3.2.1 — regenerating with another version may
# produce a cosmetic diff).
set -eu

cd "$(dirname "$0")"
port=$PWD
src=$(cd ../../../third_party/libiconv && pwd)

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
cd "$tmp"

mkdir -p "$port/gen/include" "$port/gen/lib"

# alias/canonical tables (Makefile.devel: lib/aliases.h lib/canonical.h
# lib/canonical_local.h; genaliases takes its output paths as arguments)
cc -O "$src/lib/genaliases.c" -o genaliases
./genaliases aliases.gperf canonical.sh canonical_local.sh
gperf -m 10 aliases.gperf > tmp.h  # canonical*.sh read tmp.h by name
sed -e 's/^\(const struct alias \)/static \1/' < tmp.h > "$port/gen/lib/aliases.h"
sh canonical.sh > "$port/gen/lib/canonical.h"
sh canonical_local.sh > "$port/gen/lib/canonical_local.h"

# converter flags (genflags includes the .def files relative to itself)
cc -O "$src/lib/genflags.c" -o genflags
./genflags > "$port/gen/lib/flags.h"

# transliteration table
cc -O "$src/lib/gentranslit.c" -o gentranslit
./gentranslit < "$src/lib/translit.def" > "$port/gen/lib/translit.h"

# iconv.h: configure substitutions for bionic/static —
#   ICONV_CONST    empty  (iconv() takes plain char**)
#   DLL_VARIABLE   empty  (static lib)
#   EILSEQ         84     (never used: bionic errno.h defines EILSEQ)
#   USE_MBSTATE_T  1      (bionic wchar.h has mbstate_t)
#   BROKEN_WCHAR_H 0
sed -e 's|@ICONV_CONST@||g' \
    -e 's|@DLL_VARIABLE@||g' \
    -e 's|@EILSEQ@|84|g' \
    -e 's|@USE_MBSTATE_T@|1|g' \
    -e 's|@BROKEN_WCHAR_H@|0|g' \
    "$src/include/iconv.h.in" > "$port/gen/include/iconv.h"

# localcharset.h: the .in has no substitutions
cp "$src/libcharset/include/localcharset.h.in" "$port/gen/include/localcharset.h"

echo "> done; check git status for unexpected diffs"
