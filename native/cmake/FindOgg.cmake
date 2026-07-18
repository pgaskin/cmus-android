# Shim for the in-tree libogg: satisfies find_package(Ogg) in libvorbis
# and opusfile without probing the system. Usage/include dirs flow through
# the Ogg::ogg target (aliased by libogg's own CMakeLists).
if(NOT TARGET Ogg::ogg)
    message(FATAL_ERROR "FindOgg shim: add_subdirectory(libogg) must run before its consumers")
endif()
set(Ogg_FOUND TRUE)
set(OGG_FOUND TRUE)
set(OGG_LIBRARY Ogg::ogg)
set(OGG_LIBRARIES Ogg::ogg)
