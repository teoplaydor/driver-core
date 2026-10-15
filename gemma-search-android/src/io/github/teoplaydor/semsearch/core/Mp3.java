package io.github.teoplaydor.semsearch.core;

import java.io.ByteArrayOutputStream;

/**
 * A small MP3 encoder for speech (MPEG-2 audio Layer III, ISO/IEC 13818-3): mono, 16 kHz, a constant bitrate, long
 * blocks only, no psychoacoustic model — the spectrum quantized with one step chosen so the frame's bits fit, no
 * scalefactors, no bit reservoir (every frame stands alone, so a file can be cut anywhere between frames). What any
 * MP3 player plays: the watch's, which takes no WAV with ADPCM.
 *
 * The steps as the standard's informative annex gives them: the polyphase analysis filterbank (32 subbands, its window
 * C = D / 32), the frequency inversion of the odd subbands, the 36-point MDCT with the sine window, the aliasing
 * butterflies; then the quantizer (x^¾ with the 0.4054 rounding), the Huffman tables chosen region by region, the
 * frame — header, side information, main data.
 */
public final class Mp3 {
    public static final int RATE = 16000;
    /** The samples of a frame (one granule in MPEG-2). */
    public static final int FRAME = 576;
    /** MPEG-2 Layer III bitrates, kbit/s, by index. */
    static final int[] KBPS = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};

    private Mp3() {
    }

    /**
     * {@code x} (−1…1 at {@link #RATE}) as an MP3 file at {@code kbps}; the sound starts about 33 ms in (the encoder's
     * delay) and the end is flushed out with silence.
     */
    public static byte[] encode(float[] x, int kbps) {
        int bi = -1;
        for (int i = 1; i < KBPS.length; i++) if (KBPS[i] == kbps) bi = i;
        if (bi < 0) throw new IllegalArgumentException("no MPEG-2 Layer III bitrate " + kbps);
        int frameBytes = 72 * kbps * 1000 / RATE;
        Encoder e = new Encoder();
        int frames = (x.length + 2 * FRAME + FRAME - 1) / FRAME;
        ByteArrayOutputStream out = new ByteArrayOutputStream(frames * frameBytes);
        float[] g = new float[FRAME];
        for (int f = 0; f < frames; f++) {
            for (int i = 0; i < FRAME; i++) {
                int j = f * FRAME + i;
                g[i] = j < x.length ? x[j] : 0;
            }
            byte[] b = e.frame(g, bi, frameBytes);
            out.write(b, 0, b.length);
        }
        return out.toByteArray();
    }

    /** How long the file plays, ms (its frames). */
    public static int durationMs(byte[] mp3, int kbps) {
        int frameBytes = 72 * kbps * 1000 / RATE;
        return (int) ((long) (mp3.length / frameBytes) * FRAME * 1000 / RATE);
    }

    // ------------------------------------------------------------------------------------------------ the encoder

    /** The analysis window C[i]. */
    static final double[] C = new double[512];
    /** The matrixing M[i][k] = cos((2i + 1)(k − 16)π / 64). */
    static final double[][] M = new double[32][64];
    /** The MDCT with its window and the 1/9 that makes the decoder's IMDCT give the same back: [k][i]. */
    static final double[][] MDCT = new double[18][36];
    /** The aliasing butterflies. */
    static final double[] CS = new double[8], CA = new double[8];
    /** A starting split of the big values into regions, by how many scalefactor bands they cover (the standard's). */
    static final int[][] SUBDIVIDE = {{0, 0}, {0, 0}, {0, 0}, {0, 0}, {0, 0}, {0, 1}, {1, 1}, {1, 1}, {1, 2}, {2, 2}, {2, 3}, {2, 3},
            {3, 4}, {3, 4}, {3, 4}, {4, 5}, {4, 5}, {4, 6}, {5, 6}, {5, 6}, {5, 7}, {6, 7}, {6, 7}};
    /** Above this line the spectrum is left out (7 kHz): the bits go where speech is. */
    static final int TOP = FRAME * 7000 / (RATE / 2);

    static {
        for (int i = 0; i < 512; i++) C[i] = Mp3Tables.WINDOW[i] / 65536.0 / 32.0;
        for (int i = 0; i < 32; i++) {
            for (int k = 0; k < 64; k++) M[i][k] = Math.cos((2 * i + 1) * (k - 16) * Math.PI / 64);
        }
        for (int k = 0; k < 18; k++) {
            for (int i = 0; i < 36; i++) {
                MDCT[k][i] = Math.sin(Math.PI / 36 * (i + 0.5)) * Math.cos(Math.PI / 72 * (2 * i + 1 + 18) * (2 * k + 1)) / 9.0;
            }
        }
        double[] c = {-0.6, -0.535, -0.33, -0.185, -0.095, -0.041, -0.0142, -0.0037};
        for (int i = 0; i < 8; i++) {
            double sq = Math.sqrt(1 + c[i] * c[i]);
            CS[i] = 1 / sq;
            CA[i] = c[i] / sq;
        }
    }

    /** One stream's state: the filterbank's last 512 samples, the last granule's subband samples. */
    static final class Encoder {
        private final double[] fifo = new double[512];
        private int at;
        private final double[][] prev = new double[32][18];
        private final double[][] cur = new double[32][18];
        private final double[] y = new double[64];
        private final double[] xr = new double[FRAME];
        private final double[] xr34 = new double[FRAME];
        private final int[] ix = new int[FRAME];

        /** One frame of 576 samples. */
        byte[] frame(float[] pcm, int bitrateIndex, int frameBytes) {
            analyse(pcm);
            int budget = (frameBytes - 4 - 9) * 8;
            // the finest step whose spectrum fits the frame
            int lo = 0, hi = 255;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                Side s = quantize(mid);
                if (s != null && s.bits <= budget) hi = mid;
                else lo = mid + 1;
            }
            // quantized again at the step found (the lines are one array, the last tried may have been another step)
            Side best = quantize(lo);
            if (best == null || best.bits > budget) best = quantize(255);
            Bits b = new Bits(frameBytes);
            // header: sync, MPEG-2, Layer III, no CRC, the bitrate, 16 kHz, no padding, mono
            b.put(0x7ff, 11);
            b.put(2, 2);
            b.put(1, 2);
            b.put(1, 1);
            b.put(bitrateIndex, 4);
            b.put(2, 2);
            b.put(0, 1);
            b.put(0, 1);
            b.put(3, 2);
            b.put(0, 2);
            b.put(0, 1);
            b.put(0, 1);
            b.put(0, 2);
            // side information (MPEG-2, mono): nothing from frames before, one granule
            b.put(0, 8);
            b.put(0, 1);
            b.put(best.bits, 12);
            b.put(best.bigValues, 9);
            b.put(best.gain, 8);
            b.put(0, 9); // scalefac_compress: no scalefactors
            b.put(0, 1); // long blocks
            for (int r = 0; r < 3; r++) b.put(best.table[r], 5);
            b.put(best.region0, 4);
            b.put(best.region1, 3);
            b.put(0, 1); // scalefac_scale
            b.put(best.count1Table, 1);
            // main data: the big values region by region, then the quadruples
            int start = b.bits();
            write(b, best);
            if (b.bits() - start != best.bits) throw new IllegalStateException("bits " + (b.bits() - start) + " != " + best.bits);
            return b.bytes();
        }

        /** The 18 subband samples of each of the 32 subbands, then the MDCT, then the butterflies — into xr. */
        private void analyse(float[] pcm) {
            for (int slot = 0; slot < 18; slot++) {
                for (int i = 0; i < 32; i++) {
                    at = (at + 511) & 511;
                    fifo[at] = pcm[slot * 32 + i];
                }
                // fifo[at] is X[0] (the newest), fifo[at + i] is X[i]
                for (int i = 0; i < 64; i++) {
                    double s = 0;
                    for (int j = 0; j < 8; j++) {
                        int n = i + 64 * j;
                        s += C[n] * fifo[(at + n) & 511];
                    }
                    y[i] = s;
                }
                for (int sb = 0; sb < 32; sb++) {
                    double s = 0;
                    double[] m = M[sb];
                    for (int k = 0; k < 64; k++) s += m[k] * y[k];
                    // the odd subbands' frequency inversion undone (every odd sample of an odd subband)
                    cur[sb][slot] = (sb & 1) == 1 && (slot & 1) == 1 ? -s : s;
                }
            }
            for (int sb = 0; sb < 32; sb++) {
                for (int k = 0; k < 18; k++) {
                    double s = 0;
                    double[] w = MDCT[k];
                    for (int i = 0; i < 18; i++) s += w[i] * prev[sb][i] + w[i + 18] * cur[sb][i];
                    xr[sb * 18 + k] = s;
                }
                System.arraycopy(cur[sb], 0, prev[sb], 0, 18);
            }
            for (int sb = 1; sb < 32; sb++) {
                for (int i = 0; i < 8; i++) {
                    int u = sb * 18 - 1 - i, d = sb * 18 + i;
                    double bu = xr[u], bd = xr[d];
                    xr[u] = bu * CS[i] + bd * CA[i];
                    xr[d] = bd * CS[i] - bu * CA[i];
                }
            }
            for (int i = 0; i < FRAME; i++) {
                if (i >= TOP) xr[i] = 0;
                xr34[i] = Math.pow(Math.abs(xr[i]), 0.75);
            }
        }

        /** The spectrum quantized with this global gain and how it would be coded; null — a value too big for any table. */
        private Side quantize(int gain) {
            double q = Math.pow(2, -(gain - 210) * 3.0 / 16);
            for (int i = 0; i < FRAME; i++) {
                int v = (int) (xr34[i] * q + 0.4054);
                if (v > 8191 + 15) return null;
                ix[i] = xr[i] < 0 ? -v : v;
            }
            return plan(ix, gain);
        }
    }

    /** How a granule is coded. */
    static final class Side {
        int gain, bits, bigValues, region0, region1, count1Table;
        final int[] table = new int[3];
        /** Where the regions end (lines), where the quadruples end. */
        int end0, end1, end2, count1End;
        int[] ix;
    }

    /** The regions, tables and bits for these quantized lines. */
    static Side plan(int[] ix, int gain) {
        Side s = new Side();
        s.gain = gain;
        s.ix = ix;
        int n = FRAME;
        while (n > 0 && ix[n - 1] == 0) n--;
        n = (n + 1) & ~1;
        int i = n;
        while (i >= 4 && Math.abs(ix[i - 1]) <= 1 && Math.abs(ix[i - 2]) <= 1 && Math.abs(ix[i - 3]) <= 1 && Math.abs(ix[i - 4]) <= 1) i -= 4;
        // the quadruples go from i to n (n − i a multiple of 4)
        s.count1End = n;
        s.bigValues = i / 2;
        int big = i;
        int[] sfb = Mp3Tables.SFB_16K;
        if (big > 0) {
            int anz = 0;
            while (anz < sfb.length - 1 && sfb[anz] < big) anz++;
            int r0 = SUBDIVIDE[anz][0];
            for (; r0 > 0; r0--) if (sfb[r0 + 1] <= big) break;
            int r1 = SUBDIVIDE[anz][1];
            for (; r1 > 0; r1--) if (sfb[r0 + 1 + r1 + 1] <= big) break;
            s.region0 = r0;
            s.region1 = r1;
            s.end0 = Math.min(big, sfb[r0 + 1]);
            s.end1 = Math.min(big, sfb[r0 + r1 + 2]);
        } else {
            s.end0 = 0;
            s.end1 = 0;
        }
        s.end2 = big;
        int bits = 0;
        int[] from = {0, s.end0, s.end1}, to = {s.end0, s.end1, s.end2};
        for (int r = 0; r < 3; r++) {
            int[] tb = choose(ix, from[r], to[r]);
            s.table[r] = tb[0];
            bits += tb[1];
        }
        int a = 0, b = 0;
        for (int k = big; k < n; k += 4) {
            int p = (ix[k] != 0 ? 8 : 0) | (ix[k + 1] != 0 ? 4 : 0) | (ix[k + 2] != 0 ? 2 : 0) | (ix[k + 3] != 0 ? 1 : 0);
            int signs = Integer.bitCount(p);
            a += Mp3Tables.LEN[32][p] + signs;
            b += Mp3Tables.LEN[33][p] + signs;
        }
        s.count1Table = b < a ? 1 : 0;
        bits += Math.min(a, b);
        s.bits = bits;
        return s;
    }

    /** The cheapest table for lines from…to (pairs): {table, bits}. */
    static int[] choose(int[] ix, int from, int to) {
        if (to <= from) return new int[]{0, 0};
        int max = 0;
        for (int i = from; i < to; i++) max = Math.max(max, Math.abs(ix[i]));
        if (max == 0) return new int[]{0, 0};
        int[] candidates;
        if (max <= 15) {
            candidates = max == 1 ? new int[]{1, 2, 3, 5, 6, 7, 8, 9, 10, 11, 12, 13, 15} : max == 2 ? new int[]{2, 3, 5, 6, 7, 8, 9, 10, 11, 12, 13, 15}
                    : max == 3 ? new int[]{5, 6, 7, 8, 9, 10, 11, 12, 13, 15} : max <= 5 ? new int[]{7, 8, 9, 10, 11, 12, 13, 15}
                    : max <= 7 ? new int[]{10, 11, 12, 13, 15} : new int[]{13, 15};
        } else {
            int a = 16, b = 24;
            while (a < 23 && (1 << Mp3Tables.LINBITS[a]) - 1 < max - 15) a++;
            while (b < 31 && (1 << Mp3Tables.LINBITS[b]) - 1 < max - 15) b++;
            candidates = new int[]{a, b};
        }
        int bestT = -1, bestBits = Integer.MAX_VALUE;
        for (int t : candidates) {
            int bits = count(ix, from, to, t);
            if (bits < bestBits) {
                bestBits = bits;
                bestT = t;
            }
        }
        return new int[]{bestT, bestBits};
    }

    /** Bits for the pairs from…to with table t (codes, linbits, signs). */
    static int count(int[] ix, int from, int to, int t) {
        int xlen = Mp3Tables.XLEN[t], lin = Mp3Tables.LINBITS[t];
        int[] len = Mp3Tables.LEN[t];
        int bits = 0;
        for (int i = from; i < to; i += 2) {
            int x = Math.abs(ix[i]), y = Math.abs(ix[i + 1]);
            if (lin > 0) {
                if (x >= 15) {
                    bits += lin;
                    x = 15;
                }
                if (y >= 15) {
                    bits += lin;
                    y = 15;
                }
            }
            bits += len[x * xlen + y] + (x != 0 ? 1 : 0) + (y != 0 ? 1 : 0);
        }
        return bits;
    }

    /** The main data: the pairs of each region with its table, then the quadruples. */
    static void write(Bits b, Side s) {
        int[] ix = s.ix;
        int[] from = {0, s.end0, s.end1}, to = {s.end0, s.end1, s.end2};
        for (int r = 0; r < 3; r++) {
            int t = s.table[r];
            if (t == 0) continue;
            int xlen = Mp3Tables.XLEN[t], lin = Mp3Tables.LINBITS[t];
            int[] code = Mp3Tables.CODE[t], len = Mp3Tables.LEN[t];
            for (int i = from[r]; i < to[r]; i += 2) {
                int vx = ix[i], vy = ix[i + 1];
                int x = Math.abs(vx), y = Math.abs(vy);
                int cx = lin > 0 && x >= 15 ? 15 : x, cy = lin > 0 && y >= 15 ? 15 : y;
                int p = cx * xlen + cy;
                b.put(code[p], len[p]);
                if (lin > 0 && cx == 15) b.put(x - 15, lin);
                if (x != 0) b.put(vx < 0 ? 1 : 0, 1);
                if (lin > 0 && cy == 15) b.put(y - 15, lin);
                if (y != 0) b.put(vy < 0 ? 1 : 0, 1);
            }
        }
        int t = 32 + s.count1Table;
        for (int k = s.end2; k < s.count1End; k += 4) {
            int p = (ix[k] != 0 ? 8 : 0) | (ix[k + 1] != 0 ? 4 : 0) | (ix[k + 2] != 0 ? 2 : 0) | (ix[k + 3] != 0 ? 1 : 0);
            b.put(Mp3Tables.CODE[t][p], Mp3Tables.LEN[t][p]);
            for (int j = 0; j < 4; j++) if (ix[k + j] != 0) b.put(ix[k + j] < 0 ? 1 : 0, 1);
        }
    }

    /** Bits written most significant first into a frame of a set size (the rest zeros). */
    static final class Bits {
        private final byte[] buf;
        private int pos;

        Bits(int bytes) {
            buf = new byte[bytes];
        }

        void put(int value, int n) {
            for (int i = n - 1; i >= 0; i--) {
                if (((value >>> i) & 1) != 0) buf[pos >> 3] |= (byte) (0x80 >>> (pos & 7));
                pos++;
            }
        }

        int bits() {
            return pos;
        }

        byte[] bytes() {
            return buf;
        }
    }
}
