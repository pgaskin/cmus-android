# Reproducible builds

Release builds are reproducible: building from a given tag yields an APK whose
contents are identical to the published one, differing only in the signature.
This is what lets F-Droid ship the developer-signed APK after independently
rebuilding and verifying it.

## The F-Droid / apksigcopier model

F-Droid rebuilds the app from source, then reattaches the developer's signature
to *its own* build using [`apksigcopier`](https://github.com/obfusk/apksigcopier).
That only works if F-Droid's rebuild carries the same ZIP entries as the
developer's release — same names, same order, same compression, same compressed
bytes — so that peeling off the signature leaves two identical archives.

The "signature" is two separate things, and both are allowed to differ:

- **v1 (JAR) signature files** — `META-INF/MANIFEST.MF` and `META-INF/*.{SF,RSA,DSA,EC}`.
  These are ordinary ZIP entries.
- **the APK Signing Block** (v2/v3/v3.1) — a block sitting between the ZIP
  entries and the central directory.

Everything else must match. The release is signed v2/v3-only
(`--v1-signing-enabled false`; minSdk 34 never uses v1), so in practice the only
signature is the APK Signing Block.

## What makes it reproducible

Three things, all small:

1. **Native code — [`native/CMakeLists.txt`](./../native/CMakeLists.txt).**
   The codec libraries use `assert()`/`__FILE__` heavily (opus alone has ~150
   such files), which bakes the *absolute path of each source file* into
   `.rodata`. Those strings survive release stripping (they aren't debug info),
   so a `.so` built at `/home/you/cmus-android/…` would differ byte-for-byte
   from one built in CI or in F-Droid's buildserver at
   `/build/net.pgaskin.cmus.android/…`. `-ffile-prefix-map=<repo-root>=.`
   rewrites those embedded paths; every environment maps its own checkout root
   to the same `.` placeholder, so the output is path-independent. (In this
   build `NDEBUG` is also set for every variant, which compiles most `assert()`s
   out entirely — but the flag is the actual guarantee and covers any remaining
   `__FILE__`.)

2. **Dependency metadata — [`app/build.gradle`](./../app/build.gradle).**
   `dependenciesInfo { includeInApk=false; includeInBundle=false }`. AGP
   otherwise embeds a Google-signed dependency blob as an extra APK Signing
   Block; it's non-deterministic and F-Droid rejects unknown signing blocks.

3. **VCS info — [`app/build.gradle`](./../app/build.gradle).**
   `release { vcsInfo.include=false }`. AGP otherwise stamps the git HEAD into
   `version-control-info.textproto`; dropping it makes the APK a pure function
   of the source, independent of git state.

Everything else was already deterministic: static `versionCode`/`versionName`,
sorted license/asset generation, AGP 9's fixed ZIP epoch (`1981-01-01`
timestamps), and all app resources are XML (no PNG cruncher).

## Pinned toolchain

Reproducing requires the same tools. All are pinned:

- NDK `28.2.13676358`, CMake `3.30.5`, build-tools `36.0.0` — `app/build.gradle`.
- AGP `9.3.0` (`build.gradle`) and the Gradle wrapper version.

Different NDK or AGP versions will produce different bytes.

## Verifying

[`check-reproducible.go`](./check-reproducible.go) compares two APKs the way
apksigcopier does: it checks that every non-signature entry matches by name,
order, compression method, and *raw compressed bytes* (what apksigcopier
copies), ignoring the v1 files and the APK Signing Block. When both APKs are
fully unsigned it also requires bit-for-bit whole-file identity.

It deliberately does **not** compare the raw byte ranges of the two archives:
`apksigner` re-aligns entries when it signs (padding in each local-header extra
field), which shifts offsets throughout the file even though no entry payload
changed. The per-entry raw-bytes comparison is alignment-independent and still
strict — a changed payload, compression method, entry, or order is caught.

```bash
# two local builds are bit-for-bit identical
go run notes/check-reproducible.go \
    a/app/build/outputs/apk/release/app-release-unsigned.apk \
    b/app/build/outputs/apk/release/app-release-unsigned.apk

# a local build reproduces a published, signed release
go run notes/check-reproducible.go \
    app/build/outputs/apk/release/app-release-unsigned.apk \
    https://github.com/pgaskin/cmus-android/releases/download/v2/cmus.apk
```

Each argument is a local `.apk` path or an `http(s)://` URL. Exit status is 0
only if the APKs are reproducible. To rebuild for comparison, clone the tag
fresh (submodules + `./patch.sh`) and run `./gradlew :app:assembleRelease`; the
unsigned APK lands in `app/build/outputs/apk/release/`.

The release workflow does this automatically: after building and signing, its
"Verify reproducible build" step rebuilds from a copy at a different absolute
path and runs `check-reproducible.go` twice — the two independent unsigned
builds must be bit-for-bit identical, and the signed APK being published must
reproduce from that rebuild. A non-reproducible build fails the release before
anything is published.

## Verified

Two release builds of the same source at two different absolute checkout paths
produced byte-identical native libraries and a byte-identical unsigned APK
(same SHA-256), confirming path-independence. A signed copy compares as
reproducible-except-signature under `check-reproducible.go`.

## For the F-Droid recipe (fdroiddata, not this repo)

To publish the developer-signed APK on the reproducible path, the fdroiddata
metadata needs a `Builds:` entry plus, for the developer-signed APK:

```yaml
Binaries: https://github.com/pgaskin/cmus-android/releases/download/v%v/cmus.apk
AllowedAPKSigningKeys: <sha256 of the signing cert, from `apksigner verify --print-certs`>
```

Notes for the recipe: install NDK `28.2.13676358` + CMake `3.30.5`, run
`git submodule update --init` and `./patch.sh` in `prebuild`, and
`gradle assembleRelease`.
