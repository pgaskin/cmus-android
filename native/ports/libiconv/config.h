/* Handwritten config.h for building GNU libiconv 1.19 against bionic
 * (arm64, API 34). Covers exactly the macros lib/iconv.c (and the headers
 * it includes) and libcharset/lib/localcharset.c test; the full template
 * is lib/config.h.in in the submodule.
 *
 * Deliberately not defined:
 *   ENABLE_EXTRA        - rarely-used encodings, off by default upstream
 *   ENABLE_RELOCATABLE  - not relocatable; iconv.c does not touch
 *                         relocatable.h without it
 *   USE_AIX/USE_OSF1/USE_DOS/USE_ZOS/USE_OS2 - platform alias tables
 *   STATIC              - localcharset.c knob for inlining into another
 *                         TU, not for static libs
 *   mbstate_t/mode_t/ssize_t/inline - all real types on bionic
 */

/* declaration of iconv() needs no const on the input buffer */
#define ICONV_CONST

/* aarch64-linux-android is little-endian */
#define WORDS_LITTLEENDIAN 1

/* bionic has all of these (localcharset.c uses nl_langinfo(CODESET),
 * available since API 26; the wchar functions since API 21) */
#define HAVE_LANGINFO_CODESET 1
#define HAVE_MBRTOWC 1
#define HAVE_MBSINIT 1
#define HAVE_WCRTOMB 1
