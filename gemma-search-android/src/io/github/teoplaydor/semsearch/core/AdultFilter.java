package io.github.teoplaydor.semsearch.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 18+ without a classifier model: a few phrases for nudity and sex are embedded as search queries by the photo model
 * (as the words of PhotoTags), and a picture counts as adult when its best similarity to them stands out from the
 * gallery — {@link #Z} robust deviations above the median over the gallery (the pictures that do show it do not widen
 * the spread) — and nudity is one of the main things in it: the excess is at least {@link #RELATIVE} of the picture's
 * largest excess over the ordinary vocabulary (a beach in swimsuits stands out on "nudity" a little, but far more on
 * "beach" and "swimsuit"). With too few pictures for that, against the picture's own similarities to the vocabulary.
 * Levels: 0 mild (fewer hidden), 1 normal, 2 strict (more hidden, ordinary photos with much skin among them).
 */
public final class AdultFilter {
    public static final double[] Z = {4.0, 3.0, 2.5}, RELATIVE = {1.0, 0.8, 0.6};
    public static final int MILD = 0, NORMAL = 1, STRICT = 2;
    /** Below this many pictures, the picture's own similarities decide. */
    public static final int MIN_SAMPLE = 8;
    static final double MIN_SPREAD = 0.02;

    public final String[] words;
    public final float[][] vecs;
    private double median, spread;
    private int sampled;

    public AdultFilter(List<String> words, float[][] vecs) {
        this.words = words.toArray(new String[0]);
        this.vecs = vecs;
    }

    /** One phrase per line; blank lines and lines starting with # are skipped. */
    public static List<String> parse(InputStream in) throws IOException {
        List<String> out = new ArrayList<String>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            String l;
            while ((l = r.readLine()) != null) {
                l = l.trim();
                if (!l.isEmpty() && !l.startsWith("#")) out.add(l);
            }
        } finally {
            r.close();
        }
        return out;
    }

    private static double dot(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        double s = 0;
        for (int i = 0; i < n; i++) s += a[i] * b[i];
        return s;
    }

    /** The picture's best similarity to the phrases. */
    public double best(float[] picture) {
        double b = -2;
        for (float[] v : vecs) b = Math.max(b, dot(v, picture));
        return b;
    }

    /** The typical best similarity over these pictures (a sample of the gallery) and its spread. */
    public void calibrate(List<float[]> pictures) {
        sampled = pictures.size();
        if (sampled < MIN_SAMPLE) return;
        double[] d = new double[sampled];
        for (int i = 0; i < sampled; i++) d[i] = best(pictures.get(i));
        Arrays.sort(d);
        median = d[sampled / 2];
        for (int i = 0; i < sampled; i++) d[i] = Math.abs(d[i] - median);
        Arrays.sort(d);
        spread = Math.max(MIN_SPREAD, 1.4826 * d[sampled / 2]);
    }

    public int sampled() {
        return sampled;
    }

    /** Whether the picture is 18+ at this level ({@link #MILD}, {@link #NORMAL}, {@link #STRICT}). */
    public boolean adult(float[] picture, PhotoTags tags, int level) {
        level = Math.max(0, Math.min(Z.length - 1, level));
        double b = best(picture), e, z;
        if (sampled >= MIN_SAMPLE) {
            e = b - median;
            z = e / spread;
        } else {
            double[] own = tags.own(picture);
            e = b - own[0];
            z = e / Math.max(MIN_SPREAD, own[1]);
        }
        // the vocabulary only when the phrases stand out at all (most pictures stop here)
        return z >= Z[level] && e >= RELATIVE[level] * tags.topExcess(picture);
    }
}
