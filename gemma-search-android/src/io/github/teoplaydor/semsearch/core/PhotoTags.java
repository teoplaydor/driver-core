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

    /** The picture's mean similarity to the words and its spread (the standard deviation). */
    public double[] own(float[] picture) {
        double m = 0, v = 0;
        double[] s = new double[vecs.length];
        for (int t = 0; t < vecs.length; t++) m += s[t] = dot(vecs[t], picture);
        m /= Math.max(1, s.length);
        for (double x : s) v += (x - m) * (x - m);
        return new double[]{m, Math.sqrt(v / Math.max(1, s.length))};
    }

    /**
     * The picture's largest excess over any word's typical similarity in the gallery (as {@link #rank} measures it),
     * or, with a gallery too small to calibrate on, over the picture's own mean similarity.
     */
    public double topExcess(float[] picture) {
        double top = -2, m = 0;
        for (int t = 0; t < vecs.length; t++) {
            double s = dot(vecs[t], picture);
            m += s;
            top = Math.max(top, sampled >= MIN_SAMPLE ? s - mean[t] : s);
        }
        return sampled >= MIN_SAMPLE ? top : top - m / Math.max(1, vecs.length);
    }

    /**
     * The words for this picture, best first (at most {@link #MAX}): {@link #MIN_Z} above a typical picture of the
     * gallery, by their excess over it, while that is at least {@link #RELATIVE} of the best word's — none when
     * nothing stands out. A word must also be above the median of the picture's own similarities. With a gallery too
     * small to calibrate on, against the picture's own mean similarity ({@link #SMALL_Z}).
     */
    public List<String> rank(float[] picture) {
        double[][] sc = scores(picture);
        final double[] s = sc[0], z = sc[1], e = sc[2];
        double median = sc[3][0];
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

    /**
     * Of these words (Russian, as {@link #rank} gives them; null: of all), the one the picture matches best — by its excess over a
     * typical picture of the gallery, among those above the median of the picture's own similarities — as {its
     * z-score, its excess}; null when none is. Unlike {@link #rank}, no cut relative to the picture's other words: a
     * contract held in a hand stays a contract when «рука» stands out more.
     */
    public double[] best(float[] picture, java.util.Set<String> words) {
        double[][] sc = scores(picture);
        double[] out = null;
        for (int t = 0; t < vecs.length; t++) {
            if (words != null && !words.contains(ru[t]) || sc[0][t] < sc[3][0]) continue;
            if (out == null || sc[2][t] > out[1]) out = new double[]{sc[1][t], sc[2][t], 0};
        }
        // and how many words (of all, above the median too) stand out more
        if (out != null) for (int t = 0; t < vecs.length; t++) if (sc[0][t] >= sc[3][0] && sc[2][t] > out[1]) out[2]++;
        return out;
    }

    /**
     * What makes a photo a document — something to scan and print, of any size and kind: a sheet of any paper, a
     * receipt, a ticket, a passport, a card, a book's page, a notebook, a letter, a form... (Russian, as in
     * assets/photo_tags.txt) — and what makes it a screen (a screenshot, a monitor photographed: no paper to cut out).
     */
    public static final java.util.Set<String> DOC_WORDS = new java.util.HashSet<String>(Arrays.asList("документ", "бумажный документ",
            "лист бумаги", "печатная страница", "текст", "рукописный текст", "договор", "анкета", "справка", "квитанция", "счёт",
            "чек", "билет", "посадочный талон", "паспорт", "удостоверение", "визитка", "банковская карта", "диплом",
            "бумажное письмо", "конверт", "инструкция", "страница книги", "книга", "газета", "тетрадь", "заметки", "доска",
            "меню", "прайс", "рецепт", "расписание", "таблица", "схема", "этикетка", "афиша")),
            SCREEN_WORDS = new java.util.HashSet<String>(Arrays.asList("скриншот", "веб-страница", "экран, снятый на камеру",
                    "приложение", "настройки", "переписка", "сообщение"));
    /**
     * A document word must stand out as a word shown does ({@link #MIN_Z}), by at least this much of the picture's most
     * outstanding word (a word shown needs {@link #RELATIVE}: a contract held in a hand, «рука» first), and be among
     * this many of its most outstanding words.
     */
    public static final double DOC_RELATIVE = 0.4;
    public static final int DOC_PLACE = 8;

    /**
     * Whether the picture shows a document ({@link #DOC_WORDS}): its best document word stands out ({@link #MIN_Z},
     * {@link #DOC_RELATIVE}, {@link #DOC_PLACE}) — as one of the words for it would, any of the many kinds, not only the
     * few at the top; and no screen word stands out more.
     */
    public boolean document(float[] picture) {
        double minZ = sampled < MIN_SAMPLE ? SMALL_Z : MIN_Z;
        double[] doc = best(picture, DOC_WORDS), screen = best(picture, SCREEN_WORDS), any = best(picture, null);
        if (doc == null || doc[0] < minZ || doc[2] >= DOC_PLACE || doc[1] < DOC_RELATIVE * any[1]) return false;
        return screen == null || screen[0] < minZ || screen[1] <= doc[1];
    }

    /**
     * Each word's similarity to the picture, its z-score and its excess over a typical picture of the gallery (with a
     * gallery too small to calibrate on, over the picture's own mean), and {the median of the similarities}.
     */
    private double[][] scores(float[] picture) {
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
        return new double[][]{s, z, e, {median}};
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
