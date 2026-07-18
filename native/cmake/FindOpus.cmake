# Shim for the in-tree opus: satisfies find_package(Opus) in opusfile
# without probing the system. Usage/include dirs flow through the
# Opus::opus target (aliased by opus' own CMakeLists).
if(NOT TARGET Opus::opus)
    message(FATAL_ERROR "FindOpus shim: add_subdirectory(opus) must run before its consumers")
endif()
set(Opus_FOUND TRUE)
set(OPUS_FOUND TRUE)
set(OPUS_LIBRARY Opus::opus)
set(OPUS_LIBRARIES Opus::opus)
