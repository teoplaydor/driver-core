package io.github.teoplaydor.semsearch.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Albums by meaning: each album is a few words of the vocabulary (PhotoTags), and a picture joins it when it stands
 * out on one of them — its best similarity to the album's words, {@link #SURE_Z} robust deviations above the median
 * over the gallery (as the search does: the pictures that do show it do not widen the spread) — and the album is
 * one of the picture's main themes: its excess over the median at least {@link #RELATIVE} of the picture's largest
 * over all albums (with many words an album also gathers chance similarities: «aquarium» ran 0.125 with a dog). A
 * picture may be in several albums; an album with fewer than {@link #MIN_SIZE} pictures is not shown.
 */
public final class Albums {
    public static final double SURE_Z = 3.5, RELATIVE = 0.5;
    public static final int MIN_SIZE = 3;
    /** Fewer pictures than this have no typical picture to stand out from: no albums. */
    public static final int MIN_GALLERY = 8;
    static final double MIN_SPREAD = 0.02;

    /** An album's name and its words (English, as in the vocabulary). */
    public static final class Def {
        public final String name;
        public final String[] words;

        public Def(String name, String[] words) {
            this.name = name;
            this.words = words;
        }
    }

    /** An album: its pictures (indices into the list given to {@link #build}), best first. */
    public static final class Album {
        public final String name;
        public final List<Integer> pictures = new ArrayList<Integer>();

        Album(String name) {
            this.name = name;
        }
    }

    /** Lines "Название<TAB>word|word"; blank lines and lines starting with # are skipped. */
    public static List<Def> parse(InputStream in) throws IOException {
        List<Def> out = new ArrayList<Def>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            String l;
            while ((l = r.readLine()) != null) {
                l = l.trim();
                if (l.isEmpty() || l.startsWith("#")) continue;
                int tab = l.indexOf('\t');
                if (tab <= 0) continue;
                String[] words = l.substring(tab + 1).trim().split("\\|");
                for (int i = 0; i < words.length; i++) words[i] = words[i].trim();
                out.add(new Def(l.substring(0, tab).trim(), words));
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
     * The albums of these pictures, biggest first. Words missing from {@code tags} are skipped; an album none of whose
     * words is there is left out.
     */
    public static List<Album> build(List<Def> defs, PhotoTags tags, List<float[]> pictures) {
        Map<String, Integer> index = new HashMap<String, Integer>();
        for (int t = 0; t < tags.en.length; t++) index.put(tags.en[t], t);
        int n = pictures.size();
        List<Album> out = new ArrayList<Album>();
        if (n < MIN_GALLERY) return out;
        // each word's similarity to each picture, once (words are shared between albums)
        Map<Integer, float[]> sims = new HashMap<Integer, float[]>();
        final double[] best = new double[n];
        double[] sorted = new double[n];
        // first every album's excess over its median and its z per picture, then the members
        List<Def> used = new ArrayList<Def>();
        List<float[]> excess = new ArrayList<float[]>(), zs = new ArrayList<float[]>();
        double[] top = new double[n];
        Arrays.fill(top, 0);
        for (Def d : defs) {
            Arrays.fill(best, -2);
            boolean any = false;
            for (String w : d.words) {
                Integer t = index.get(w);
                if (t == null) continue;
                any = true;
                float[] s = sims.get(t);
                if (s == null) {
                    s = new float[n];
                    for (int i = 0; i < n; i++) s[i] = (float) dot(tags.vecs[t], pictures.get(i));
                    sims.put(t, s);
                }
                for (int i = 0; i < n; i++) best[i] = Math.max(best[i], s[i]);
            }
            if (!any) continue;
            System.arraycopy(best, 0, sorted, 0, n);
            Arrays.sort(sorted);
            double median = sorted[n / 2];
            for (int i = 0; i < n; i++) sorted[i] = Math.abs(best[i] - median);
            Arrays.sort(sorted);
            double sd = Math.max(MIN_SPREAD, 1.4826 * sorted[n / 2]);
            float[] e = new float[n], z = new float[n];
            for (int i = 0; i < n; i++) {
                e[i] = (float) (best[i] - median);
                z[i] = (float) (e[i] / sd);
                top[i] = Math.max(top[i], e[i]);
            }
            used.add(d);
            excess.add(e);
            zs.add(z);
        }
        for (int k = 0; k < used.size(); k++) {
            final float[] e = excess.get(k);
            float[] z = zs.get(k);
            Album a = new Album(used.get(k).name);
            for (int i = 0; i < n; i++) if (z[i] >= SURE_Z && e[i] >= RELATIVE * top[i]) a.pictures.add(i);
            if (a.pictures.size() < MIN_SIZE) continue;
            Collections.sort(a.pictures, new Comparator<Integer>() {
                @Override
                public int compare(Integer x, Integer y) {
                    return Float.compare(e[y], e[x]);
                }
            });
            out.add(a);
        }
        Collections.sort(out, new Comparator<Album>() {
            @Override
            public int compare(Album x, Album y) {
                return y.pictures.size() - x.pictures.size();
            }
        });
        return out;
    }
}
