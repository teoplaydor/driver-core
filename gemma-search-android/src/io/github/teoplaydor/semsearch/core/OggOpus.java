package io.github.teoplaydor.semsearch.core;

import java.util.ArrayList;
import java.util.List;

/**
 * An Ogg Opus file taken apart (RFC 7845): its OpusHead (channels, pre-skip — what a decoder needs) and its audio
 * packets in order, put together across pages. What the Xiaomi Watch S5 records as «opus» is expected to be this
 * (its media framework writes through FFmpeg); the decoding itself is Android's (MediaCodec «audio/opus»).
 */
public final class OggOpus {
    /** The OpusHead packet as it is (a decoder's «csd-0»), the channels, the samples to skip at the start (48 kHz). */
    public final byte[] head;
    public final int channels, preSkip;
    public final List<byte[]> packets;

    private OggOpus(byte[] head, List<byte[]> packets) {
        this.head = head;
        this.channels = head[9] & 0xff;
        this.preSkip = (head[10] & 0xff) | ((head[11] & 0xff) << 8);
        this.packets = packets;
    }

    /** Does it start like an Ogg file? */
    public static boolean looksLike(byte[] b) {
        return b != null && b.length >= 4 && b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S';
    }

    /** Where an Ogg page starts at or after {@code from} (−1: none). */
    static int capture(byte[] b, int from) {
        for (int i = Math.max(0, from); i + 4 <= b.length; i++) {
            if (b[i] == 'O' && b[i + 1] == 'g' && b[i + 2] == 'g' && b[i + 3] == 'S') return i;
        }
        return -1;
    }

    /**
     * Taken apart, or null if it is not Ogg Opus (or broken before its first audio). Tolerant: the stream may start
     * later than byte 0 or have bytes between pages (a recorder's stream caught mid-way), and may lack its OpusHead —
     * then mono with no pre-skip is assumed.
     */
    public static OggOpus read(byte[] b) {
        if (b == null) return null;
        int o = capture(b, 0);
        if (o < 0) return null;
        List<byte[]> all = new ArrayList<byte[]>();
        java.io.ByteArrayOutputStream part = new java.io.ByteArrayOutputStream();
        while (o + 27 <= b.length) {
            if (!(b[o] == 'O' && b[o + 1] == 'g' && b[o + 2] == 'g' && b[o + 3] == 'S')) {
                int next = capture(b, o + 1);
                if (next < 0) break;
                o = next;
                part.reset();
                continue;
            }
            int segs = b[o + 26] & 0xff;
            int data = o + 27 + segs;
            if (data > b.length) break;
            int at = data;
            for (int i = 0; i < segs; i++) {
                int len = b[o + 27 + i] & 0xff;
                if (at + len > b.length) {
                    at = b.length;
                    break;
                }
                part.write(b, at, len);
                at += len;
                if (len < 255) {
                    // a packet ends here (one of 255 goes on in the next segment, or the next page)
                    all.add(part.toByteArray());
                    part.reset();
                }
            }
            o = at;
        }
        if (all.isEmpty()) return null;
        byte[] head = null;
        List<byte[]> audio = new ArrayList<byte[]>();
        for (byte[] p : all) {
            String tag = p.length >= 8 ? new String(p, 0, 8, java.nio.charset.StandardCharsets.ISO_8859_1) : "";
            if (tag.equals("OpusHead") && p.length >= 19) head = p;
            else if (!tag.equals("OpusTags") && p.length > 0) audio.add(p);
        }
        if (audio.isEmpty()) return null;
        return new OggOpus(head != null ? head : head(1, 0, 16000), audio);
    }

    /** Packets each after its length (u16, little-endian) — how the watch sends Opus that came without a file around it. */
    public static List<byte[]> lengthPrefixed(byte[] b) {
        List<byte[]> out = new ArrayList<byte[]>();
        int o = 0;
        while (o + 2 <= b.length) {
            int n = (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8);
            o += 2;
            if (n == 0 || o + n > b.length) break;
            byte[] p = new byte[n];
            System.arraycopy(b, o, p, 0, n);
            out.add(p);
            o += n;
        }
        return out;
    }

    /** An OpusHead for packets that came without one (RFC 7845 5.1: version 1, mapping family 0). */
    public static byte[] head(int channels, int preSkip, int inputRate) {
        byte[] h = new byte[19];
        byte[] magic = "OpusHead".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, h, 0, 8);
        h[8] = 1;
        h[9] = (byte) channels;
        h[10] = (byte) preSkip;
        h[11] = (byte) (preSkip >>> 8);
        for (int i = 0; i < 4; i++) h[12 + i] = (byte) (inputRate >>> (8 * i));
        return h;
    }

    /** How many samples (at 48 kHz) an Opus packet holds, from its first byte and its frame count (RFC 6716 3.1). */
    public static int samples48k(byte[] p) {
        if (p == null || p.length == 0) return 0;
        int toc = p[0] & 0xff, config = toc >> 3;
        int frame; // in 48 kHz samples
        if (config < 12) frame = new int[]{480, 960, 1920, 2880}[config & 3]; // SILK: 10, 20, 40, 60 ms
        else if (config < 16) frame = (config & 1) == 0 ? 480 : 960; // hybrid: 10, 20 ms
        else frame = new int[]{120, 240, 480, 960}[config & 3]; // CELT: 2.5, 5, 10, 20 ms
        int c = toc & 3;
        int frames = c == 0 ? 1 : c < 3 ? 2 : p.length > 1 ? p[1] & 0x3f : 0;
        return frame * frames;
    }
}
