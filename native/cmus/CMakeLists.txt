# cmus built from the pristine submodule: the core as an executable named
# libcmus.so (packaged like a shared lib, exec'd from nativeLibraryDir),
# plus the config headers ./configure would emit, generated here instead.

set(src "${THIRD_PARTY}/cmus")
set(gen "${CMAKE_CURRENT_BINARY_DIR}/generated")

# upstream VERSION comes from git describe with a hand-made fallback
# (Makefile:1-11); describe is unusable here (shallow clone, and patch.sh's
# `base` tag would win --tags), so compose fallback + gitlink sha, reading
# the fallback out of the Makefile so pin bumps stay correct. The gitlink
# is resolved from the parent repo: the submodule's HEAD is the patched
# tip since stage 7, whose sha changes on every patch regen.
file(STRINGS "${src}/Makefile" ver3 REGEX "^_ver3")
string(REGEX REPLACE ".*= *" "" ver3 "${ver3}")
execute_process(
    COMMAND git -C "${THIRD_PARTY}/.." rev-parse --verify --short=7
        ":third_party/cmus"
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
    ${src}/android.c
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
    ${src}/editable.c
    ${src}/expr.c
    ${src}/file.c
    ${src}/filters.c
    ${src}/format_print.c
    ${src}/gbuf.c
    ${src}/glob.c
    ${src}/help.c
    ${src}/history.c
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
    ${src}/static_plugins.c
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

# plugins are compiled into the executable (CONFIG_STATIC_PLUGINS below), so
# there's nothing to dlopen and no need to export the core symbols they used to
# resolve at load time -> ENABLE_EXPORTS (--export-dynamic) stays off.
set_target_properties(cmus PROPERTIES
    OUTPUT_NAME libcmus
    SUFFIX .so)

# AGP packages lib*.so from the library output dir; park the executable
# there (wireguard-android pattern). Unset on plain cmake runs.
if(CMAKE_LIBRARY_OUTPUT_DIRECTORY)
    set_target_properties(cmus PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
endif()

target_include_directories(cmus PRIVATE "${gen}")
# CONFIG_ANDROID enables the patches' app-only code (android.c IPC socket,
# remote streams out); deliberately a plain definition, not a config/*.h,
# so the patched tree still builds unchanged with the upstream Makefile.
# Core only -- plugins don't need it. CONFIG_STATIC_PLUGINS swaps the
# dlopen()/dlsym() plugin scan for the compiled-in static_plugins.c tables.
target_compile_definitions(cmus PRIVATE HAVE_CONFIG _FILE_OFFSET_BITS=64
    CONFIG_ANDROID CONFIG_STATIC_PLUGINS)
target_compile_options(cmus PRIVATE ${cmus_cflags})
# pthread is in bionic libc; the GNU iconv.h from the iconv target's
# interface must (and does) precede bionic's own limited <iconv.h>
target_link_libraries(cmus PRIVATE ncursesw iconv dl m log)

set_source_files_properties(
    ${src}/android.c ${src}/command_mode.c ${src}/input.c ${src}/ui_curses.c
    PROPERTIES COMPILE_DEFINITIONS VERSION="${cmus_version}")

# ip/vorbis.c + ip/mp4.c include "../config/*.h"; relative to the source
# that misses (pristine tree), and against -I dirs the lookup is textual
# (<dir>/../config/...), so plugins get an empty anchor dir beside
# gen/config that makes it land on the generated headers.
file(MAKE_DIRECTORY "${gen}/ip")

# Each plugin is compiled into the cmus executable rather than a standalone
# dlopen()ed .so (CONFIG_STATIC_PLUGINS). Its fixed ABI symbols (ip_ops,
# ip_priority, ... / op_pcm_ops, ...) would collide across plugins linked into
# one image, so rename them per-plugin to <name>_<sym>; static_plugins.c
# declares the renamed symbols and builds the registration tables the loaders
# walk. codec deps (per the Makefile's ld_dl lines) are linked onto the plugin
# object lib for their header search paths and onto cmus for the final link.
function(cmus_plugin kind name libs)
    set(target cmus_${kind}_${name})
    add_library(${target} OBJECT ${ARGN})
    target_include_directories(${target} PRIVATE "${gen}" "${gen}/ip")
    target_compile_definitions(${target} PRIVATE HAVE_CONFIG _FILE_OFFSET_BITS=64)
    target_compile_options(${target} PRIVATE ${cmus_cflags})
    if(kind STREQUAL "ip")
        target_compile_definitions(${target} PRIVATE
            ip_ops=${name}_ip_ops
            ip_priority=${name}_ip_priority
            ip_extensions=${name}_ip_extensions
            ip_mime_types=${name}_ip_mime_types
            ip_options=${name}_ip_options
            ip_abi_version=${name}_ip_abi_version)
    else()
        target_compile_definitions(${target} PRIVATE
            op_pcm_ops=${name}_op_pcm_ops
            op_pcm_options=${name}_op_pcm_options
            op_priority=${name}_op_priority
            op_abi_version=${name}_op_abi_version
            op_mixer_ops=${name}_op_mixer_ops
            op_mixer_options=${name}_op_mixer_options)
    endif()
    # PRIVATE on an OBJECT lib brings in the codec's INTERFACE include dirs at
    # compile time without linking anything; cmus does the actual link below.
    target_link_libraries(${target} PRIVATE ${libs})
    target_link_libraries(cmus PRIVATE ${libs})
    target_sources(cmus PRIVATE $<TARGET_OBJECTS:${target}>)
endfunction()

# cmus includes <wavpack/wavpack.h> (installed layout); the in-tree header
# is include/wavpack.h -- mirror it under the installed name
file(MAKE_DIRECTORY "${gen}/compat/wavpack")
file(COPY_FILE "${THIRD_PARTY}/wavpack/include/wavpack.h"
    "${gen}/compat/wavpack/wavpack.h" ONLY_IF_DIFFERENT)

cmus_plugin(ip flac "FLAC" "${src}/ip/flac.c")
cmus_plugin(ip vorbis "vorbisfile;m" "${src}/ip/vorbis.c")
cmus_plugin(ip opus "opusfile" "${src}/ip/opus.c")
cmus_plugin(ip mad "mad-static;iconv;m" "${src}/ip/mad.c" "${src}/ip/nomad.c")
cmus_plugin(ip wavpack "wavpack" "${src}/ip/wavpack.c")
target_include_directories(cmus_ip_wavpack PRIVATE "${gen}/compat")
cmus_plugin(ip aac "faad" "${src}/ip/aac.c")
cmus_plugin(ip mp4 "mp4v2;faad" "${src}/ip/mp4.c")
target_compile_options(cmus_ip_mp4 PRIVATE -Wno-undef -Wno-macro-redefined)
cmus_plugin(ip wav "" "${src}/ip/wav.c")
cmus_plugin(ip cue "m" "${src}/ip/cue.c")
cmus_plugin(op aaudio "aaudio" "${src}/op/aaudio.c")
