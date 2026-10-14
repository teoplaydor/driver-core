package io.github.teoplaydor.semsearch.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A voice note made ready for a speech recognizer. A recognizer handed a whole recording stops at the first pause it
 * takes for the end of what was said — the rest of the note is lost — so the note is split into phrases at its pauses
 * and each is recognized alone ({@link #phrases}). And the sound is cleaned first ({@link #clean}): the hum and rumble
 * below speech taken out, the loudness evened out (a watch's microphone at arm's length is quiet, held to the mouth it
 * is loud), the peaks kept below full scale.
 */
public final class Utterances {
    /** Loudness measured in frames of 20 ms. */
    static final int FRAME_MS = 20;
    /** A pause this long between words ends a phrase. */
    public static final int PAUSE_MS = 650;
    /** A phrase at most this long (a recognizer's own limit is near a minute, its patience less); longer — cut at its quietest moment. */
    public static final int MAX_MS = 12000;
    /** Kept around speech: the first and last sounds of words are quiet. */
    static final int MARGIN_MS = 250;
    /** Less voiced sound than this in a phrase: a click, a knock — not speech. */
    static final int MIN_VOICED_MS = 140;
    /** Quieter than this (RMS of full scale) is never speech; a frame this much above the room's own noise is. */
    static final float FLOOR = 0.004f;
    static final double ABOVE_NOISE_DB = 9;
    /** Speech evened out to about this loudness (RMS, −20 dBFS), by a gain within these bounds, peaks below the limit. */
    static final float TARGET = 0.1f, MIN_GAIN = 0.5f, MAX_GAIN = 16f, PEAK = 0.95f;

    private Utterances() {
    }

    /** The sound without what lies below speech (a high-pass at 80 Hz) and with its speech at one loudness. */
    public static float[] clean(float[] x, int rate) {
        float[] y = new float[x.length];
        double a = 1 / (1 + 2 * Math.PI * 80.0 / rate);
        double prevX = 0, prevY = 0;
        for (int i = 0; i < x.length; i++) {
            double v = a * (prevY + x[i] - prevX);
            prevX = x[i];
            prevY = v;
            y[i] = (float) v;
        }
        // the loudness of speech: the loudest third of the frames
        double[] rms = frames(y, rate);
        if (rms.length == 0) return y;
        double[] sorted = rms.clone();
        Arrays.sort(sorted);
        int from = (int) (sorted.length * 2 / 3.0);
        double s = 0;
        for (int i = from; i < sorted.length; i++) s += sorted[i] * sorted[i];
        double loud = Math.sqrt(s / Math.max(1, sorted.length - from));
        if (loud < FLOOR) return y; // nothing said: left as it is
        float peak = 0;
        for (float v : y) peak = Math.max(peak, Math.abs(v));
        double gain = Math.max(MIN_GAIN, Math.min(MAX_GAIN, TARGET / loud));
        if (peak * gain > PEAK) gain = PEAK / peak;
        for (int i = 0; i < y.length; i++) y[i] = (float) (y[i] * gain);
        return y;
    }

    /** RMS of each 20 ms frame. */
    static double[] frames(float[] x, int rate) {
        int f = Math.max(1, rate * FRAME_MS / 1000);
        double[] out = new double[x.length / f];
        for (int k = 0; k < out.length; k++) {
            double s = 0;
            for (int i = k * f; i < (k + 1) * f; i++) s += x[i] * x[i];
            out[k] = Math.sqrt(s / f);
        }
        return out;
    }

    /**
     * Where the phrases are: {from, to} sample ranges in order, split at pauses of {@link #PAUSE_MS} and more, none
     * longer than {@link #MAX_MS} (cut at the quietest frame), with a margin around. Empty: nothing like speech.
     */
    public static List<int[]> phrases(float[] x, int rate) {
        List<int[]> out = new ArrayList<int[]>();
        double[] rms = frames(x, rate);
        int n = rms.length, f = Math.max(1, rate * FRAME_MS / 1000);
        if (n == 0) return out;
        // the room's own noise: the quietest tenth of the frames
        double[] sorted = rms.clone();
        Arrays.sort(sorted);
        double noise = sorted[n / 10];
        double thr = Math.max(FLOOR, noise * Math.pow(10, ABOVE_NOISE_DB / 20));
        boolean[] voiced = new boolean[n];
        for (int k = 0; k < n; k++) voiced[k] = rms[k] >= thr;
        int pause = PAUSE_MS / FRAME_MS, max = MAX_MS / FRAME_MS, margin = MARGIN_MS / FRAME_MS, minVoiced = MIN_VOICED_MS / FRAME_MS;
        // runs of voiced frames, joined across gaps shorter than a pause
        List<int[]> runs = new ArrayList<int[]>();
        int k = 0;
        while (k < n) {
            if (!voiced[k]) {
                k++;
                continue;
            }
            int start = k, end = k + 1, count = 0, gap = 0;
            for (int j = k; j < n; j++) {
                if (voiced[j]) {
                    end = j + 1;
                    count++;
                    gap = 0;
                } else if (++gap >= pause) {
                    break;
                }
            }
            if (count >= minVoiced) runs.add(new int[]{start, end});
            k = end + pause;
        }
        for (int[] r : runs) {
            int start = r[0];
            while (r[1] - start > max) {
                // too long without a pause: cut at the quietest frame in its second half
                int cut = start + max / 2;
                for (int j = start + max / 2; j < start + max; j++) if (rms[j] < rms[cut]) cut = j;
                out.add(span(start, cut, margin, n, f, x.length));
                start = cut;
            }
            out.add(span(start, r[1], margin, n, f, x.length));
        }
        return out;
    }

    /**
     * The phrases put together again into pieces of at most {@code maxMs} (pauses and all): for a model that takes a
     * whole stretch of speech at once and punctuates it best in whole sentences, only the room's silence before, after and
     * between long stretches left out. A phrase longer than that alone (phrases are {@link #MAX_MS} at most).
     */
    public static List<int[]> groups(List<int[]> phrases, int rate, int maxMs) {
        List<int[]> out = new ArrayList<int[]>();
        long max = (long) rate * maxMs / 1000;
        int[] cur = null;
        for (int[] p : phrases) {
            if (cur != null && p[1] - cur[0] <= max) {
                cur[1] = p[1];
            } else {
                if (cur != null) out.add(cur);
                cur = new int[]{p[0], p[1]};
            }
        }
        if (cur != null) out.add(cur);
        return out;
    }

    private static int[] span(int from, int to, int margin, int n, int f, int len) {
        return new int[]{Math.max(0, from - margin) * f, Math.min(len, Math.min(n, to + margin) * f)};
    }
}
