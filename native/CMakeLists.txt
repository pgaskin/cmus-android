cmake_minimum_required(VERSION 3.30)
project(cmus_android C CXX)

# AGP configures the debug variant with CMAKE_BUILD_TYPE=Debug, i.e. -O0
# for cmus and every codec — 3-4x the decode CPU of termux's optimized
# packages, very noticeable during playback. Decoding is the whole point,
# so optimize native code in every variant (trailing -O2 wins over the
# toolchain's earlier -O0).
string(APPEND CMAKE_C_FLAGS_DEBUG " -O2 -DNDEBUG")
string(APPEND CMAKE_CXX_FLAGS_DEBUG " -O2 -DNDEBUG")

# Find modules here shim find_package(Ogg)/find_package(Opus) onto the
# in-tree targets; prepended so they win over the copies the submodules
# append (which would probe the system).
list(PREPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake")

# some of the third-party libs declare an old cmake version, but it's mostly
# harmless, so just silence it (we can patch it properly if it becomes necessary
# later)
set(CMAKE_WARN_DEPRECATED OFF CACHE BOOL "" FORCE)

set(THIRD_PARTY "${CMAKE_CURRENT_SOURCE_DIR}/../third_party")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)

# we only import projects for their static libs, so neuter install/export so
# cmake doesn't warn about them
macro(install)
endmacro()
macro(export)
endmacro()

# libogg
set(INSTALL_DOCS OFF CACHE BOOL "" FORCE)
set(INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(INSTALL_CMAKE_PACKAGE_MODULE OFF CACHE BOOL "" FORCE)
add_subdirectory("${THIRD_PARTY}/libogg" libogg EXCLUDE_FROM_ALL)

# libvorbis (find_package(Ogg) -> shim)
add_subdirectory("${THIRD_PARTY}/libvorbis" libvorbis EXCLUDE_FROM_ALL)

# HACK: stop opusfile from trying to determine its own version from git (we do a
# shallow clone) so we can override it with a dummy one so it doesn't emit
# warnings
set(CMAKE_DISABLE_FIND_PACKAGE_Git TRUE)

# opus
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_PACKAGE_VERSION 0.0)
add_subdirectory("${THIRD_PARTY}/opus" opus EXCLUDE_FROM_ALL)

# opusfile (find_package(Ogg/Opus) -> shims; no-HTTP also drops OpenSSL)
set(OP_DISABLE_HTTP ON CACHE BOOL "" FORCE)
set(OP_DISABLE_EXAMPLES ON CACHE BOOL "" FORCE)
set(OP_DISABLE_DOCS ON CACHE BOOL "" FORCE)
set(OPUSFILE_PACKAGE_VERSION 0.0)
add_subdirectory("${THIRD_PARTY}/opusfile" opusfile EXCLUDE_FROM_ALL)

unset(CMAKE_DISABLE_FIND_PACKAGE_Git)

# flac (sees TARGET Ogg::ogg itself, no find_package needed)
set(WITH_OGG ON CACHE BOOL "" FORCE)
set(BUILD_CXXLIBS OFF CACHE BOOL "" FORCE)
set(BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_DOCS OFF CACHE BOOL "" FORCE)
set(INSTALL_MANPAGES OFF CACHE BOOL "" FORCE)
add_subdirectory("${THIRD_PARTY}/flac" flac EXCLUDE_FROM_ALL)

# wavpack (no aarch64 asm outside MSVC, so its asm options don't apply)
set(WAVPACK_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(WAVPACK_ENABLE_LIBICONV OFF CACHE BOOL "" FORCE)
add_subdirectory("${THIRD_PARTY}/wavpack" wavpack EXCLUDE_FROM_ALL)

# faad2 (also defines faad_drm/_fixed variants; only faad gets built)
set(FAAD_BUILD_CLI OFF CACHE BOOL "" FORCE)
add_subdirectory("${THIRD_PARTY}/faad2" faad2 EXCLUDE_FROM_ALL)

# mp4v2
set(BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(BUILD_UTILS OFF CACHE BOOL "" FORCE)
add_subdirectory("${THIRD_PARTY}/mp4v2" mp4v2 EXCLUDE_FROM_ALL)
target_compile_options(mp4v2 PRIVATE -Wno-macro-redefined)

# libmad (aarch64 -> FPM_64BIT; the ARM asm/ASO path is 32-bit-only; the
# unconditional `mad` SHARED target is never built thanks to
# EXCLUDE_FROM_ALL + cmus_deps)
set(EXAMPLE OFF CACHE BOOL "" FORCE)
add_subdirectory("${THIRD_PARTY}/libmad" libmad EXCLUDE_FROM_ALL)

# handwritten ports (pregenerated sources under native/ports/*/gen/)
add_subdirectory(ports/ncurses)
add_subdirectory(ports/libiconv)

# terminal-emulator JNI, built from the termux-app submodule (see the port)
add_subdirectory(ports/termux)

# cmus core + plugins + generated config headers; gradle builds these
# targets, pulling exactly the needed static deps transitively and keeping
# strays (vorbisenc, opusurl, faad_drm, ...) out of the build
add_subdirectory(cmus)
