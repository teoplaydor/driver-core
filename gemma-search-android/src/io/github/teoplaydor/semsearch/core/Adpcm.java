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
}
