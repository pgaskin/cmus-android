# Stage 20 — ogg/opus embedded art

Closes the stage-9 gap: `MediaMetadataRetriever.getEmbeddedPicture()`
doesn't read the `METADATA_BLOCK_PICTURE` vorbis-comment field, so Ogg
Vorbis and Ogg Opus tracks with embedded cover art fall back to folder
art (or the placeholder).

**A planning hypothesis was tested on-device and disproved.** I first
thought the picture was already crossing IPC in `Status.tags()` (the ip
plugins do read every vorbis comment, and android.c serializes every
`ti->comments[]`). It is **not** — cmus filters comments through an
`interesting[]` allowlist before they ever reach `ti->comments`, and the
picture keys aren't on it. Verified on the Pixel 8 (device test below).
So the picture is neither in the IPC tag stream nor in cmus's cache; it
has to come from either an **app-side file parse** or a **new cmus
extraction path**.

## Facts pinned down

- **cmus drops the picture at parse time** (comment.c): `comments_add`
  runs every key through `fix_key()`, which returns non-NULL only for
  keys in the `interesting[]` allowlist (artist/album/title/genre/date/
  replaygain/musicbrainz/… — comment.c:197) or the `key_map[]` aliases;
  anything else (incl. `METADATA_BLOCK_PICTURE`, `COVERART`) → `fix_key`
  returns NULL → `comments_add` frees the value and returns 0. So the ip
  plugins' unfiltered `ov_comment`/`op_tags` read (ip/vorbis.c:308,
  ip/opus.c:257) is immediately filtered down; `ti->comments[]` never
  holds the picture. **Corollary**: it is *not* cached (cache.c `write_ti`
  writes `ti->comments`, which lacks it) and *not* sent over IPC
  (android.c:750 serializes `ti->comments`, same filtered set).
- **Device confirmation** (Pixel 8, root adb — see "Device test done"):
  an opus with an embedded 974-byte FLAC-picture block (base64 1300
  chars) added to the library and `android-save`d produced a **236-byte**
  cache whose only comment strings are `output_gain title artist album` —
  no `METADATA_BLOCK_PICTURE`. Confirms the allowlist filter empirically.
- **Current art chain** (`MediaControl.decodeArt`, art executor thread,
  MediaControl.java:395): framework `getEmbeddedPicture()` → folder art
  (`FOLDER_ART_NAMES`, cached per dir). Both decode through
  `decodeScaled(ArtDecoder)` (two-pass power-of-2 downsample to
  ≤ `ART_MAX_DIM`=640). The embedded path already decodes a `byte[]` via
  `BitmapFactory.decodeByteArray`, so any new path only has to produce
  **image bytes**; decode/scale is reused verbatim.
- **How ogg/opus miss today** (stage-9 verify): mp3 APIC + m4a covr +
  native-flac PICTURE decode via the framework; **ogg/opus return `null`**
  from `getEmbeddedPicture()` (no exception), falling through to folder
  art. wv *throws* and is caught. The insertion point is the **null**
  path.
- **FLAC PICTURE body layout** (all lengths big-endian u32, verified by
  hand-building a valid file ffprobe re-derives the attached pic from):
  picture type (4) · MIME length (4)+MIME · description length (4)+desc ·
  width (4) · height (4) · depth (4) · colors (4) · **data length (4)+
  data**. We need only the trailing data bytes; prefer picture type 3
  (front cover) when several.
- **Vorbis-comment block** (in the Ogg comment header packet, LE
  lengths): vendor length+string, then count, then count × (length+
  `KEY=VALUE`). The `METADATA_BLOCK_PICTURE` value is base64 (unwrapped)
  → `android.util.Base64.decode(s, DEFAULT)`.
- **No other art consumer**: art is MediaControl-only.

## Decision: Option A — app-side Ogg parser, no cmus change

Patrick's call (2026-07-19): the picture is read from the file app-side;
cmus is left untouched. Rejected: exposing it via a cmus patch (C) and a
third-party tag library (B). So the parser reintroduces Ogg page
reassembly — accepted as the cost of a self-contained, patch-free stage.

## Design

One new pure-Java class `OggCover` (own file) run on the `CmusArt`
art-executor thread; bounds-checked throughout so any malformed/truncated
field returns `null` rather than letting an exception escape into the
executor. The FLAC-block parse is a private helper inside it.

- **`OggCover.extract(String path)` → `byte[]`** (front-cover image
  bytes, or null):
  1. **Sniff** the first 4 bytes; bail unless `"OggS"`. (Content sniff,
     not extension; and it only runs on the framework-null path, so
     mp3/flac/m4a never reach it.)
  2. **Reassemble the comment header packet** (packet index 1 of the
     first logical bitstream): stream Ogg pages with a buffered reader,
     lock onto the **first BOS page's serial** (ignore other serials —
     defends against multiplexed/chained streams), concatenate segments
     honouring the 255-byte lacing continuation rule. This is the
     load-bearing part: embedded art makes this packet span multiple
     pages. Cap the reassembled packet and total bytes scanned at a
     sanity bound (**8 MB**) → bail past it (no OOM/spin on corrupt
     files).
  3. **Strip the codec prefix**: `\x03"vorbis"` (7 B) or `"OpusTags"`
     (8 B); anything else → null.
  4. **Walk the vorbis-comment block** (LE lengths): skip vendor, iterate
     `count` comments, split on first `=`, match key case-insensitively
     against `METADATA_BLOCK_PICTURE`; collect all matches.
  5. Base64-decode each match and parse the FLAC picture block (private
     helper: the 8 big-endian fields → trailing picture data), **prefer
     picture type 3** (front cover), else the first that decodes; return
     its data bytes.

**Integration** (`MediaControl.decodeArt`, unchanged elsewhere): between
the framework-null path and the folder fallback —
```
byte[] ogg = OggCover.extract(path);       // null unless Ogg w/ picture
if (ogg != null) {
    Bitmap bmp = decodeScaled(o -> BitmapFactory.decodeByteArray(ogg, 0, ogg.length, o));
    if (bmp != null) return bmp;
}
// folder art (existing)
```
Runs only on a track (`artFile`) change, on the single-thread executor;
folder-art cache untouched; no per-track picture cache (each track's is
its own).

## Open questions / cases flagged to Patrick

1. **Front-cover preference** (FLAC type 3 over first): default yes.
2. **Legacy `COVERART`/`COVERARTMIME`** (deprecated raw-image base64):
   skip? Rare; a few lines if wanted.
3. **Placement vs framework**: keep framework first, new path on its null.

## Device test done (this planning session)

- Built a test opus on the host: `sine` → libopus, cover = solid-magenta
  300×300 PNG hand-wrapped into a FLAC picture block, base64'd, injected
  as `-metadata METADATA_BLOCK_PICTURE=…` (ffmpeg's opus muxer rejects a
  PNG attached_pic stream, so the tag is built by hand — which also
  validated the exact block layout). ffprobe re-derives the attached pic
  from it. **Kept on-device at
  `…/files/.cmus/home/arttest.opus`** (u0_a279, restorecon'd) for
  stage-20 verification — scratch copy + `cover.png` + `mbp.b64` in the
  session scratchpad.
- Added it to an (empty) library via the debug receiver, `android-save
  cache library`, pulled the cache → 236 bytes, no picture key. Filter
  confirmed. Cleaned the probe's cache/lib.pl; left the opus in place.

## Verify (device, at implementation)

- ogg + opus with embedded `METADATA_BLOCK_PICTURE` (distinct image from
  any folder art) → QS/notification shows the embedded cover; embedded
  wins over a sibling `cover.jpg`; no-embedded ogg still folder; mp3/
  flac/m4a unchanged; wv still folder. `dumpsys media_session` art size/
  hash to distinguish embedded vs folder. Large-cover case exercises the
  multi-page reassembly (A) or the big-blob channel (C).
- patch.sh check green (A: no cmus changes; C: after 0001 fixup+regen);
  clean assembleDebug.

## Commits

1. `notes: stage 20 plan — ogg/opus embedded art via app-side parser`
2. `app: parse ogg/opus METADATA_BLOCK_PICTURE cover art (OggCover)`
3. `notes: stage 20 status + architecture`
