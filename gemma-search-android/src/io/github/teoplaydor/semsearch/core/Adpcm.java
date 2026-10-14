package io.github.teoplaydor.semsearch.core;

/**
 * IMA ADPCM as the watch sends a voice note (its recorder's 16-bit PCM, a quarter of the bytes): one stream for the
 * whole note, the state going on from byte to byte, two samples a byte, the first in the low half — the decoder of
 * the watch's createAdpcm (watch/vela/src/common/proto.js).
 */
public final class Adpcm {
    static final int[] STEP = {7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97,
            107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060,
            1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845,
            8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767};
    static final int[] INDEX = {-1, -1, -1, -1, 2, 4, 6, 8};

    private Adpcm() {
    }

    /** The samples (−1…1), two a byte. */
    public static float[] decode(byte[] b) {
        float[] out = new float[2 * b.length];
        int pred = 0, index = 0, n = 0;
        for (byte x : b) {
            for (int half = 0; half < 2; half++) {
                int c = half == 0 ? x & 0x0f : (x >> 4) & 0x0f;
                int step = STEP[index];
                int vp = step >> 3;
                if ((c & 4) != 0) vp += step;
                if ((c & 2) != 0) vp += step >> 1;
                if ((c & 1) != 0) vp += step >> 2;
                pred = (c & 8) != 0 ? pred - vp : pred + vp;
                if (pred > 32767) pred = 32767;
                else if (pred < -32768) pred = -32768;
                index += INDEX[c & 7];
                if (index < 0) index = 0;
                else if (index > 88) index = 88;
                out[n++] = pred / 32768f;
            }
        }
        return out;
    }

    /** The same, a piece at a time: the state goes on from one piece to the next (a live stream from the watch). */
    public static final class Decoder {
        private int pred, index;

        /** 16-bit samples, two a byte. */
        public short[] decode(byte[] b, int from, int n) {
            short[] out = new short[2 * n];
            int k = 0;
            for (int i = from; i < from + n; i++) {
                for (int half = 0; half < 2; half++) {
                    int c = half == 0 ? b[i] & 0x0f : (b[i] >> 4) & 0x0f;
                    int step = STEP[index];
                    int vp = step >> 3;
                    if ((c & 4) != 0) vp += step;
                    if ((c & 2) != 0) vp += step >> 1;
                    if ((c & 1) != 0) vp += step >> 2;
                    pred = (c & 8) != 0 ? pred - vp : pred + vp;
                    if (pred > 32767) pred = 32767;
                    else if (pred < -32768) pred = -32768;
                    index += INDEX[c & 7];
                    if (index < 0) index = 0;
                    else if (index > 88) index = 88;
                    out[k++] = (short) pred;
                }
            }
            return out;
        }
    }

    /** One sample's 4-bit code from {@code state} = {predictor, index} (updated), as the decoders above undo it. */
    static int encode(int sample, int[] state) {
        int pred = state[0], index = state[1];
        int step = STEP[index];
        int diff = sample - pred;
        int c = 0;
        if (diff < 0) {
            c = 8;
            diff = -diff;
        }
        int vp = step >> 3;
        if (diff >= step) {
            c |= 4;
            diff -= step;
            vp += step;
        }
        if (diff >= step >> 1) {
            c |= 2;
            diff -= step >> 1;
            vp += step >> 1;
        }
        if (diff >= step >> 2) {
            c |= 1;
            vp += step >> 2;
        }
        pred = (c & 8) != 0 ? pred - vp : pred + vp;
        if (pred > 32767) pred = 32767;
        else if (pred < -32768) pred = -32768;
        index += INDEX[c & 7];
        if (index < 0) index = 0;
        else if (index > 88) index = 88;
        state[0] = pred;
        state[1] = index;
        return c;
    }

    /** IMA ADPCM WAV blocks of this many bytes (each starts again from its first sample: a lost one stays lost). */
    public static final int BLOCK = 256;
    /** Samples a block holds: the first in its header, then two a byte. */
    public static final int BLOCK_SAMPLES = (BLOCK - 4) * 2 + 1;

    /**
     * A WAV file of IMA ADPCM (format 0x11, mono, {@link #BLOCK}-byte blocks — what players read, FFmpeg's
     * adpcm_ima_wav among them): a quarter of 16-bit PCM. The last block filled up with the last sample (the «fact»
     * chunk says how many there really are).
     */
    public static byte[] imaWav(short[] x, int rate) {
        int blocks = Math.max(1, (x.length + BLOCK_SAMPLES - 1) / BLOCK_SAMPLES);
        int data = blocks * BLOCK;
        byte[] w = new byte[60 + data];
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(w).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(w.length - 8).put(new byte[]{'W', 'A', 'V', 'E'});
        b.put(new byte[]{'f', 'm', 't', ' '}).putInt(20).putShort((short) 0x11).putShort((short) 1).putInt(rate)
                .putInt((int) ((long) rate * BLOCK / BLOCK_SAMPLES)).putShort((short) BLOCK).putShort((short) 4).putShort((short) 2)
                .putShort((short) BLOCK_SAMPLES);
        b.put(new byte[]{'f', 'a', 'c', 't'}).putInt(4).putInt(x.length);
        b.put(new byte[]{'d', 'a', 't', 'a'}).putInt(data);
        int[] st = new int[2];
        int at = 0;
        for (int k = 0; k < blocks; k++) {
            int first = at < x.length ? x[at] : x.length > 0 ? x[x.length - 1] : 0;
            st[0] = first;
            int base = b.position();
            b.putShort((short) first).put((byte) st[1]).put((byte) 0);
            at++;
            for (int j = 0; j < BLOCK - 4; j++) {
                int s0 = at < x.length ? x[at] : x.length > 0 ? x[x.length - 1] : 0;
                int s1 = at + 1 < x.length ? x[at + 1] : x.length > 0 ? x[x.length - 1] : 0;
                int lo = encode(s0, st), hi = encode(s1, st);
                w[base + 4 + j] = (byte) (lo | (hi << 4));
                at += 2;
            }
            b.position(base + BLOCK);
        }
        return w;
    }

    /** The samples of an {@link #imaWav} file (for the tests): block by block, as a player decodes it. */
    public static short[] fromImaWav(byte[] w) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(w).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int total = b.getInt(48), block = b.getShort(32) & 0xffff;
        short[] out = new short[total];
        int n = 0;
        for (int at = 60; at + 4 <= w.length && n < total; at += block) {
            int pred = b.getShort(at), index = w[at + 2] & 0xff;
            out[n++] = (short) pred;
            for (int j = 4; j < block && at + j < w.length && n < total; j++) {
                for (int half = 0; half < 2 && n < total; half++) {
                    int c = half == 0 ? w[at + j] & 0x0f : (w[at + j] >> 4) & 0x0f;
                    int step = STEP[index];
                    int vp = step >> 3;
                    if ((c & 4) != 0) vp += step;
                    if ((c & 2) != 0) vp += step >> 1;
                    if ((c & 1) != 0) vp += step >> 2;
                    pred = (c & 8) != 0 ? pred - vp : pred + vp;
                    if (pred > 32767) pred = 32767;
                    else if (pred < -32768) pred = -32768;
                    index += INDEX[c & 7];
                    if (index < 0) index = 0;
                    else if (index > 88) index = 88;
                    out[n++] = (short) pred;
                }
            }
        }
        return out;
    }
}
