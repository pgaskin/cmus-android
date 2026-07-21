package net.pgaskin.cmus.android;

import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Extracts embedded cover art from Ogg Vorbis / Ogg Opus files.
 *
 * <p>The platform {@link android.media.MediaMetadataRetriever} does not
 * surface the {@code METADATA_BLOCK_PICTURE} vorbis-comment field these
 * containers use, and cmus filters it out of its own comment allowlist
 * (comment.c {@code interesting[]}), so it never reaches the IPC tag
 * stream either. So {@link MediaControl} parses the file directly, on its
 * art executor thread, as the step between the framework extractor and
 * the folder-art fallback.
 *
 * <p>The one non-trivial part is that the Ogg comment-header packet spans
 * multiple pages once the embedded image is large — reassembly follows the
 * 255-byte lacing continuation rule across pages of the first logical
 * bitstream. Everything is bounds-checked: any malformed/truncated field
 * yields {@code null} rather than throwing into the executor.
 */
final class OggCover {
    private static final String TAG = "cmus";

    /** Cap on the reassembled comment packet, guards a hostile/corrupt file. */
    private static final int MAX_PACKET = 8 * 1024 * 1024;
    /** Cap on total bytes read while hunting the comment packet. */
    private static final long MAX_SCAN = 16L * 1024 * 1024;

    private static final String KEY = "METADATA_BLOCK_PICTURE";

    /** Fixed Ogg page header size, before the segment table. */
    private static final int OGG_PAGE_HEADER_LEN = 27;
    /** A lacing value of 255 means the packet continues in the next segment. */
    private static final int LACING_CONTINUATION = 255;
    /** Packet index of the comment header (0 = the identification header). */
    private static final int COMMENT_PACKET_INDEX = 1;
    /** Vorbis comment-header packet type byte. */
    private static final int VORBIS_COMMENT_HEADER = 0x03;
    /** FLAC PICTURE fixed fields between description and data: w, h, depth, colors. */
    private static final int FLAC_PIC_FIXED_FIELDS = 16;

    private OggCover() {}

    /**
     * @return the front-cover image bytes, or {@code null} if {@code path}
     *     isn't Ogg, carries no picture, or is malformed.
     */
    static byte[] extract(String path) {
        try (InputStream in = new BufferedInputStream(new FileInputStream(path), 1 << 16)) {
            byte[] packet = commentPacket(in);
            if (packet == null) {
                return null;
            }
            int start = commentPrefixLen(packet);
            if (start < 0) {
                return null;
            }
            return pictureFromComments(packet, start);
        } catch (IOException e) {
            Log.d(TAG, "oggcover: read failed for " + path + ": " + e);
            return null;
        } catch (RuntimeException e) {
            // defensive: never let a parse slip crash the art executor
            Log.d(TAG, "oggcover: parse failed for " + path + ": " + e);
            return null;
        }
    }

    // -- Ogg container: reassemble packet index 1 (the comment header) of the
    //    first logical bitstream --------------------------------------------

    /** One Ogg page: its bitstream serial, segment table, and body bytes. */
    private record Page(int serial, byte[] lacing, byte[] body) {}

    /** Next page, or null on clean EOF, short read, or non-Ogg sync. */
    private static Page readPage(InputStream in, long[] scanned) throws IOException {
        byte[] hdr = new byte[OGG_PAGE_HEADER_LEN];
        if (!readFully(in, hdr, OGG_PAGE_HEADER_LEN, scanned)) {
            return null; // clean EOF or short read
        }
        if (hdr[0] != 'O' || hdr[1] != 'g' || hdr[2] != 'g' || hdr[3] != 'S') {
            return null; // not Ogg (this doubles as the file sniff on page 0)
        }
        int serial = (int) u32le(hdr, 14, hdr.length);
        int segCount = hdr[26] & 0xFF;
        byte[] lacing = new byte[segCount];
        if (!readFully(in, lacing, segCount, scanned)) {
            return null;
        }
        int bodyLen = 0;
        for (int i = 0; i < segCount; i++) {
            bodyLen += lacing[i] & 0xFF;
        }
        byte[] body = new byte[bodyLen];
        if (!readFully(in, body, bodyLen, scanned)) {
            return null;
        }
        return new Page(serial, lacing, body);
    }

    private static byte[] commentPacket(InputStream in) throws IOException {
        long[] scanned = {0};
        boolean haveSerial = false;
        int serial = 0;
        int packetIndex = 0;
        ByteArrayOutputStream pkt = new ByteArrayOutputStream();

        while (true) {
            Page page = readPage(in, scanned);
            if (page == null) {
                return null; // clean EOF, short read, or not Ogg
            }
            if (!haveSerial) {
                serial = page.serial();
                haveSerial = true;
            }
            if (page.serial() == serial) {
                // Walk segments, splitting packets on any lacing value < 255.
                // Pages of other logical streams (skipped above) never break a
                // packet's segment run for our stream, so the buffer persists
                // across interleaving/continuation pages untouched.
                byte[] lacing = page.lacing();
                byte[] body = page.body();
                int off = 0;
                for (int i = 0; i < lacing.length; i++) {
                    int lv = lacing[i] & 0xFF;
                    pkt.write(body, off, lv);
                    off += lv;
                    if (pkt.size() > MAX_PACKET) {
                        return null;
                    }
                    if (lv < LACING_CONTINUATION) {
                        if (packetIndex == COMMENT_PACKET_INDEX) {
                            return pkt.toByteArray(); // comment header complete
                        }
                        packetIndex++;
                        pkt.reset();
                    }
                }
            }
            if (scanned[0] > MAX_SCAN) {
                return null;
            }
        }
    }

    /** Length of the codec-specific prefix on the comment header, or -1. */
    private static int commentPrefixLen(byte[] p) {
        if (p.length >= 7 && (p[0] & 0xFF) == VORBIS_COMMENT_HEADER && matches(p, 1, "vorbis")) {
            return 7;
        }
        if (p.length >= 8 && matches(p, 0, "OpusTags")) {
            return 8;
        }
        return -1;
    }

    // -- vorbis comment block: vendor, then count × (len32le + "KEY=VALUE") --

    private static byte[] pictureFromComments(byte[] b, int pos) {
        int end = b.length;
        long vendorLen = u32le(b, pos, end);
        if (vendorLen < 0) {
            return null;
        }
        pos = advance(pos + 4, vendorLen, end);
        if (pos < 0) {
            return null;
        }
        long count = u32le(b, pos, end);
        if (count < 0) {
            return null;
        }
        pos += 4;

        byte[] firstAny = null; // first decodable picture, if none is a front cover
        for (long i = 0; i < count; i++) {
            long clen = u32le(b, pos, end);
            if (clen < 0) {
                return null;
            }
            pos += 4;
            if ((long) pos + clen > end) {
                return null;
            }
            int cStart = pos;
            int cLen = (int) clen;
            pos += cLen;

            byte[] block = decodePictureBlock(b, cStart, cLen);
            if (block == null) {
                continue;
            }
            byte[] data = flacPictureData(block, /*wantFront=*/true);
            if (data != null) {
                return data; // front cover (type 3) wins immediately
            }
            if (firstAny == null) {
                data = flacPictureData(block, /*wantFront=*/false);
                if (data != null) {
                    firstAny = data;
                }
            }
        }
        return firstAny;
    }

    /**
     * The decoded FLAC PICTURE block of the comment at {@code [cStart,
     * cStart+cLen)}, or null if it isn't a {@code METADATA_BLOCK_PICTURE}
     * comment or its value doesn't base64-decode.
     */
    private static byte[] decodePictureBlock(byte[] b, int cStart, int cLen) {
        int eq = indexOf(b, cStart, cLen, '=');
        if (eq < 0 || !equalsIgnoreCaseAscii(b, cStart, eq - cStart, KEY)) {
            return null;
        }
        try {
            return Base64.decode(b, eq + 1, cStart + cLen - (eq + 1), Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // -- FLAC PICTURE metadata block body (all lengths big-endian u32) ------

    /**
     * @param wantFront when true, returns data only for picture type 3
     *     (front cover); when false, returns any valid picture's data.
     */
    private static byte[] flacPictureData(byte[] b, boolean wantFront) {
        int end = b.length;
        int pos = 0;
        long type = u32be(b, pos, end);
        if (type < 0) {
            return null;
        }
        pos += 4;
        if (wantFront && type != 3) {
            return null;
        }
        long mimeLen = u32be(b, pos, end);
        if (mimeLen < 0) {
            return null;
        }
        pos = advance(pos + 4, mimeLen, end);
        if (pos < 0) {
            return null;
        }
        long descLen = u32be(b, pos, end);
        if (descLen < 0) {
            return null;
        }
        pos = advance(pos + 4, descLen, end);
        if (pos < 0) {
            return null;
        }
        if ((long) pos + FLAC_PIC_FIXED_FIELDS > end) {
            return null; // width, height, depth, colors
        }
        pos += FLAC_PIC_FIXED_FIELDS;
        long dataLen = u32be(b, pos, end);
        if (dataLen < 0) {
            return null;
        }
        pos += 4;
        if ((long) pos + dataLen > end) {
            return null;
        }
        return Arrays.copyOfRange(b, pos, pos + (int) dataLen);
    }

    // -- helpers ------------------------------------------------------------

    private static boolean readFully(InputStream in, byte[] b, int len, long[] scanned)
            throws IOException {
        int n = 0;
        while (n < len) {
            int r = in.read(b, n, len - n);
            if (r < 0) {
                return false;
            }
            n += r;
        }
        scanned[0] += len;
        return true;
    }

    /** {@code pos + len}, bounds-checked against {@code end}; -1 on overflow. */
    private static int advance(int pos, long len, int end) {
        if (len < 0 || (long) pos + len > end) {
            return -1;
        }
        return pos + (int) len;
    }

    /** Unsigned LE u32 at {@code pos}, or -1 if {@code pos + 4 > end}. */
    private static long u32le(byte[] b, int pos, int end) {
        if (pos < 0 || pos + 4 > end) {
            return -1;
        }
        return (b[pos] & 0xFFL)
                | (b[pos + 1] & 0xFFL) << 8
                | (b[pos + 2] & 0xFFL) << 16
                | (b[pos + 3] & 0xFFL) << 24;
    }

    /** Unsigned BE u32 at {@code pos}, or -1 if {@code pos + 4 > end}. */
    private static long u32be(byte[] b, int pos, int end) {
        if (pos < 0 || pos + 4 > end) {
            return -1;
        }
        return (b[pos] & 0xFFL) << 24
                | (b[pos + 1] & 0xFFL) << 16
                | (b[pos + 2] & 0xFFL) << 8
                | (b[pos + 3] & 0xFFL);
    }

    /** ASCII literal {@code s} present at {@code b[off]}. */
    private static boolean matches(byte[] b, int off, String s) {
        if (off + s.length() > b.length) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (b[off + i] != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] b, int start, int len, char c) {
        for (int i = 0; i < len; i++) {
            if (b[start + i] == c) {
                return start + i;
            }
        }
        return -1;
    }

    private static boolean equalsIgnoreCaseAscii(byte[] b, int off, int len, String s) {
        if (len != s.length()) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            if (lower(b[off + i] & 0xFF) != lower(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static int lower(int c) {
        return (c >= 'A' && c <= 'Z') ? c + 32 : c;
    }
}
