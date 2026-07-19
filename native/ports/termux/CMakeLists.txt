# libtermux.so: the terminal-emulator JNI (pty spawn, waitFor, window size),
# built from the pristine termux-app submodule instead of the prebuilt AAR .so.
# NDK r28 emits 16 KB-aligned LOAD segments, so this clears Android 16's "not
# 16 KB aligned" check that the AAR's NDK-r21 build (4 KB-aligned) tripped.
# Loaded by com.termux.terminal.JNI via System.loadLibrary("termux").
set(jni "${THIRD_PARTY}/termux-app/terminal-emulator/src/main/jni")

add_library(termux SHARED "${jni}/termux.c")

# upstream Android.mk cFlags, minus -Werror: NDK r28's newer clang warns on
# code the pinned tree compiled clean under the r21 toolchain jitpack used.
target_compile_options(termux PRIVATE
    -std=c11 -Wall -Wextra -Os -fno-stack-protector)
target_link_options(termux PRIVATE -Wl,--gc-sections)
