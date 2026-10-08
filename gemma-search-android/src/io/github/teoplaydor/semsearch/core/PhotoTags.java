package io.github.teoplaydor.semsearch.core;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Words for what a picture shows, without a model that writes text: the words of a vocabulary are embedded as
 * search queries by the same model as the pictures, and a picture gets the words it matches far better than a
 * typical picture of the gallery does — per word, against the median and the median deviation over a sample of the
 * gallery (robust: the pictures that do show a word do not widen its spread), so words that match every picture a
 * little ("people", "text") do not come first. With too few pictures for that, the words stand out against the
 * picture's own similarities to all words.
 */
public final class PhotoTags {
    /**
     * Words shown, at most. A word needs {@link #MIN_Z} deviations above a typical picture (with some 350 words, 2
     * would let a few through by chance alone), and its excess over the typical similarity — in cosine, comparable
     * between words, unlike the z-score, whose spread differs from word to word — at least {@link #RELATIVE} of the
     * best word's.
     */
    public static final int MAX = 6;
    public static final double MIN_Z = 3.0, SMALL_Z = 1.0, RELATIVE = 0.5;
    /** Below this many pictures in the gallery sample, words are scored against the picture's own similarities. */
    public static final int MIN_SAMPLE = 8;
    /** The least spread of a word's similarity taken as real (cosines). */
    static final double MIN_SPREAD = 0.02;

    public final String[] en, ru;
    public final float[][] vecs;
    private double[] mean, std;
    private int sampled;

    public PhotoTags(List<String[]> words, float[][] vecs) {
        en = new String[words.size()];
        ru = new String[words.size()];
        for (int i = 0; i < en.length; i++) {
            en[i] = words.get(i)[0];
            ru[i] = words.get(i)[1];
        }
        this.vecs = vecs;
    }

    /** Lines "english<TAB>русский"; blank lines and lines starting with # are skipped. */
    public static List<String[]> parse(InputStream in) throws IOException {
        List<String[]> out = new ArrayList<String[]>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            String l;
            while ((l = r.readLine()) != null) {
                l = l.trim();
                if (l.isEmpty() || l.startsWith("#")) continue;
                int tab = l.indexOf('\t');
                if (tab <= 0 || tab == l.length() - 1) continue;
                out.add(new String[]{l.substring(0, tab).trim(), l.substring(tab + 1).trim()});
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

    /**
     * Each word's typical similarity to these pictures (a sample of the gallery) and its spread: the median, and the
     * median absolute deviation scaled to a standard deviation (×1.4826).
     */
    public void calibrate(List<float[]> pictures) {
        sampled = pictures.size();
        mean = new double[vecs.length];
        std = new double[vecs.length];
        if (sampled < MIN_SAMPLE) return;
        double[] d = new double[sampled];
        for (int t = 0; t < vecs.length; t++) {
            for (int i = 0; i < sampled; i++) d[i] = dot(vecs[t], pictures.get(i));
            Arrays.sort(d);
            double med = d[sampled / 2];
            for (int i = 0; i < sampled; i++) d[i] = Math.abs(d[i] - med);
            Arrays.sort(d);
            mean[t] = med;
            std[t] = 1.4826 * d[sampled / 2];
        }
    }

    public int sampled() {
        return sampled;
    }

    /**
     * The words for this picture, best first (at most {@link #MAX}): {@link #MIN_Z} above a typical picture of the
     * gallery, by their excess over it, while that is at least {@link #RELATIVE} of the best word's — none when
     * nothing stands out. A word must also be above the median of the picture's own similarities. With a gallery too
     * small to calibrate on, against the picture's own mean similarity ({@link #SMALL_Z}).
     */
    public List<String> rank(float[] picture) {
        final double[] s = new double[vecs.length], z = new double[vecs.length], e = new double[vecs.length];
        for (int t = 0; t < vecs.length; t++) s[t] = dot(vecs[t], picture);
        double[] sorted = s.clone();
        Arrays.sort(sorted);
        double median = sorted[sorted.length / 2];
        if (sampled >= MIN_SAMPLE) {
            // a floor on the spread: a gallery of near-copies has none, and differences in the fourth digit mean nothing
            for (int t = 0; t < vecs.length; t++) {
                e[t] = s[t] - mean[t];
                z[t] = e[t] / Math.max(std[t], MIN_SPREAD);
            }
        } else {
            double m = 0, v = 0;
            for (double x : s) m += x;
            m /= s.length;
            for (double x : s) v += (x - m) * (x - m);
            double sd = Math.max(Math.sqrt(v / s.length), MIN_SPREAD);
            for (int t = 0; t < vecs.length; t++) {
                e[t] = s[t] - m;
                z[t] = e[t] / sd;
            }
        }
        Integer[] order = new Integer[vecs.length];
        for (int t = 0; t < order.length; t++) order[t] = t;
        Arrays.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Double.compare(e[b], e[a]);
            }
        });
        // a small gallery: no typical picture to stand out from, so the words clearly above the picture's own level
        double minZ = sampled < MIN_SAMPLE ? SMALL_Z : MIN_Z;
        List<String> out = new ArrayList<String>();
        double best = Double.NaN;
        for (int t : order) {
            if (s[t] < median || z[t] < minZ) continue;
            if (Double.isNaN(best)) best = e[t];
            if (e[t] < RELATIVE * best || out.size() >= MAX) break;
            out.add(ru[t]);
        }
        return out;
    }

    // ---------------------------------------------------------------- cache

    /**
     * The words' vectors saved before, if they are for these words and for this model: {@code probe} is the first
     * word embedded now (a model changed since gives another vector). Null otherwise.
     */
    public static float[][] readCache(File f, List<String[]> words, float[] probe) {
        if (!f.exists()) return null;
        try {
            DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new FileInputStream(f), 1 << 16));
            try {
                int n = in.readInt(), dim = in.readInt();
                if (n != words.size() || dim != probe.length) return null;
                float[][] v = new float[n][dim];
                for (int i = 0; i < n; i++) {
                    if (!in.readUTF().equals(words.get(i)[0])) return null;
                    for (int j = 0; j < dim; j++) v[i][j] = in.readFloat();
                }
                double c = dot(v[0], probe) / Math.sqrt(Math.max(1e-12, dot(v[0], v[0]) * dot(probe, probe)));
                return c >= 0.999 ? v : null;
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    public static void writeCache(File f, List<String[]> words, float[][] vecs) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(tmp), 1 << 16));
        try {
            out.writeInt(vecs.length);
            out.writeInt(vecs.length > 0 ? vecs[0].length : 0);
            for (int i = 0; i < vecs.length; i++) {
                out.writeUTF(words.get(i)[0]);
                for (float x : vecs[i]) out.writeFloat(x);
            }
        } finally {
            out.close();
        }
        if (!tmp.renameTo(f)) throw new IOException("cannot write " + f);
    }
}
