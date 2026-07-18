# Static GNU libiconv built from the pristine submodule sources plus the
# pregenerated tables/headers in gen/ (see gen.sh) and the handwritten
# bionic config.h in this directory. lib/iconv.c pulls in every converter
# via included headers; localcharset.c is the libcharset half.
#
# Exports libiconv_* symbols with iconv.h remapping iconv_* onto them —
# fine for static linking, cmus sees the standard API.

set(ic "${THIRD_PARTY}/libiconv")

add_library(iconv STATIC
    "${ic}/lib/iconv.c"
    "${ic}/libcharset/lib/localcharset.c"
)

target_include_directories(iconv
    PUBLIC
        "${CMAKE_CURRENT_SOURCE_DIR}/gen/include"
    PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}"
        "${CMAKE_CURRENT_SOURCE_DIR}/gen/lib"
)
