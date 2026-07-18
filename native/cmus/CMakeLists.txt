# cmus built from the pristine submodule: the core as an executable named
# libcmus.so (packaged like a shared lib, exec'd from nativeLibraryDir),
# plus the config headers ./configure would emit, generated here instead.

set(src "${THIRD_PARTY}/cmus")
set(gen "${CMAKE_CURRENT_BINARY_DIR}/generated")

# upstream VERSION comes from git describe with a hand-made fallback
# (Makefile:1-11); describe is unusable here (shallow clone, and patch.sh's
# `base` tag would win --tags), so compose fallback + gitlink sha, reading
# the fallback out of the Makefile so pin bumps stay correct.
file(STRINGS "${src}/Makefile" ver3 REGEX "^_ver3")
string(REGEX REPLACE ".*= *" "" ver3 "${ver3}")
execute_process(
    COMMAND git -C "${src}" rev-parse --verify --short HEAD
    OUTPUT_VARIABLE ver_sha
    OUTPUT_STRIP_TRAILING_WHITESPACE
    COMMAND_ERROR_IS_FATAL ANY)
set(cmus_version "${ver3}-${ver_sha}")

# Mirrors config_header from scripts/configure.sh (same guard style; bool
# y -> `#define V 1`, n -> `/* #define V */`, unset -> `#define V ""`).
# Sources include these as "config/<name>.h": the pristine submodule root
# is searched first (no config/ dir there), then our -I picks up gen/.
function(config_header name)
    string(TOUPPER "config_${name}_h" guard)
    list(JOIN ARGN "\n" defs)
    file(CONFIGURE OUTPUT "${gen}/config/${name}.h" @ONLY CONTENT
"#ifndef ${guard}
#define ${guard}

${defs}

#endif
")
endfunction()

# values a ./configure run on bionic with our deps would produce:
# resizeterm/use_default_colors/A_ITALIC verified in the stage-4 ncursesw,
# GNU libiconv linked in, bionic has byteswap.h/strdup/strndup and a
# 4-byte wchar_t wcwidth; DEBUG=1 is the upstream default; DATADIR/LIBDIR
# are the default-prefix placeholders (never used: the app and the verify
# steps always set CMUS_DATA_DIR/CMUS_LIB_DIR); everything else is an
# off/unprobed feature in its exact off-value form.
config_header(cdio "/* #define HAVE_CDDB */")
config_header(mpris "/* #define CONFIG_MPRIS */")
config_header(datadir "#define DATADIR \"/usr/local/share\"")
config_header(libdir "#define LIBDIR \"/usr/local/lib\"")
config_header(debug "#define DEBUG 1")
config_header(tremor "/* #define CONFIG_TREMOR */")
config_header(modplug "#define MODPLUG_API_8 \"\"")
config_header(mpc "#define MPC_SV8 \"\"")
config_header(mp4 "#define USE_MPEG4IP 0")
config_header(curses
    "#define HAVE_RESIZETERM 1"
    "#define HAVE_USE_DEFAULT_COLORS 1"
    "#define HAVE_ITALIC 1")
config_header(ffmpeg
    "#define HAVE_FFMPEG_AVCODEC_H \"\""
    "/* #define USE_FALLBACK_IP */")
config_header(utils "#define HAVE_BYTESWAP_H 1")
config_header(iconv "#define HAVE_ICONV 1")
config_header(wcwidth "#define HAVE_WCWIDTH 1")
config_header(samplerate "/* #define HAVE_SAMPLERATE */")
config_header(xmalloc
    "#define HAVE_STRDUP 1"
    "#define HAVE_STRNDUP 1")

# upstream's C standard + the check_cflags warning set (NDK clang accepts
# all of it); rtsched deliberately off = REALTIME_SCHEDULING not defined
set(cmus_cflags -std=gnu11 -pipe -Wall -Wshadow -Wcast-align
    -Wpointer-arith -Wwrite-strings -Wundef -Wmissing-prototypes
    -Wredundant-decls -Wextra -Wno-sign-compare -Wformat-security
    -Wold-style-definition -Wno-pointer-sign
    -Werror-implicit-function-declaration -Wno-unused-parameter
    -Wno-missing-field-initializers)

# core = the Makefile's cmus-y list (mpris.o off) + file/path/prog/xmalloc;
# main.c is cmus-remote's, not built (main() lives in ui_curses.c)
add_executable(cmus
    ${src}/ape.c
    ${src}/browser.c
    ${src}/buffer.c
    ${src}/cache.c
    ${src}/channelmap.c
    ${src}/cmdline.c
    ${src}/cmus.c
    ${src}/command_mode.c
    ${src}/comment.c
    ${src}/convert.c
    ${src}/cue.c
    ${src}/cue_utils.c
    ${src}/debug.c
    ${src}/discid.c
    ${src}/editable.c
    ${src}/expr.c
    ${src}/file.c
    ${src}/filters.c
    ${src}/format_print.c
    ${src}/gbuf.c
    ${src}/glob.c
    ${src}/help.c
    ${src}/history.c
    ${src}/http.c
    ${src}/id3.c
    ${src}/input.c
    ${src}/job.c
    ${src}/keys.c
    ${src}/keyval.c
    ${src}/lib.c
    ${src}/load_dir.c
    ${src}/locking.c
    ${src}/mergesort.c
    ${src}/misc.c
    ${src}/options.c
    ${src}/output.c
    ${src}/path.c
    ${src}/pcm.c
    ${src}/pl.c
    ${src}/pl_env.c
    ${src}/play_queue.c
    ${src}/player.c
    ${src}/prog.c
    ${src}/rbtree.c
    ${src}/read_wrapper.c
    ${src}/search.c
    ${src}/search_mode.c
    ${src}/server.c
    ${src}/spawn.c
    ${src}/tabexp.c
    ${src}/tabexp_file.c
    ${src}/track.c
    ${src}/track_info.c
    ${src}/tree.c
    ${src}/u_collate.c
    ${src}/uchar.c
    ${src}/ui_curses.c
    ${src}/window.c
    ${src}/worker.c
    ${src}/xmalloc.c
    ${src}/xstrjoin.c
)

# plugins are linked with undefined core symbols and resolve them from the
# executable at dlopen time -> ENABLE_EXPORTS = upstream's -Wl,--export-dynamic
set_target_properties(cmus PROPERTIES
    OUTPUT_NAME libcmus
    SUFFIX .so
    ENABLE_EXPORTS ON)

# AGP packages lib*.so from the library output dir; park the executable
# there (wireguard-android pattern). Unset on plain cmake runs.
if(CMAKE_LIBRARY_OUTPUT_DIRECTORY)
    set_target_properties(cmus PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
endif()

target_include_directories(cmus PRIVATE "${gen}")
target_compile_definitions(cmus PRIVATE HAVE_CONFIG _FILE_OFFSET_BITS=64)
target_compile_options(cmus PRIVATE ${cmus_cflags})
# pthread is in bionic libc; the GNU iconv.h from the iconv target's
# interface must (and does) precede bionic's own limited <iconv.h>
target_link_libraries(cmus PRIVATE ncursesw iconv dl m)

set_source_files_properties(
    ${src}/command_mode.c ${src}/input.c ${src}/ui_curses.c
    PROPERTIES COMPILE_DEFINITIONS VERSION="${cmus_version}")
set_source_files_properties(${src}/server.c
    PROPERTIES COMPILE_DEFINITIONS DEFAULT_PORT=3000)
