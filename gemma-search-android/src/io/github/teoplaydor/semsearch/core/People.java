package io.github.teoplaydor.semsearch.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * People and pets in the gallery. A person is the faces the user named (and those said not to be them): a face of
 * the gallery is theirs when its best cosine with the named ones is at least {@link FaceModel#SAME} and above its best
 * with the ones said not to be them — the person with the highest such cosine, when several qualify. The faces nobody
 * took are grouped (each joins the group whose mean is nearest, from {@link #CLUSTER} on; the largest and clearest
 * faces first, so that groups start from good faces) and a group in {@link #MIN_PHOTOS} photos or more is offered as
 * someone unnamed. A pet or anything else is the photos the user marked: a photo is in it when its best cosine with the
 * marked ones stands out from the gallery ({@link #SURE_Z} robust deviations above the median, as the search does)
 * and beats its best with the photos taken out of it. A photo may be in any number of these.
 */
public final class People {
    public static final double CLUSTER = 0.42, SURE_Z = 3.5, MORE_Z = 2.0;
    public static final int MIN_PHOTOS = 3, MAX_CLUSTERED = 6000, MAX_GROUPS = 600, MORE_MAX = 300, MIN_GALLERY = 8;
    static final double MIN_SPREAD = 0.02;

    /** A face of the gallery: its photo (a key), its box (fractions of the photo), its shorter side in pixels, its vector. */
    public static final class Face {
        public final long id, photo;
        public final float x, y, w, h, score;
        public final int size;
        public final float[] emb;

        public Face(long id, long photo, float x, float y, float w, float h, int size, float score, float[] emb) {
            this.id = id;
            this.photo = photo;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.size = size;
            this.score = score;
            this.emb = emb;
        }
    }

    /** A named person: the faces that are them and those said not to be. */
    public static final class Person {
        public final List<float[]> yes = new ArrayList<float[]>(), no = new ArrayList<float[]>();
    }

    static double dot(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) s += a[i] * b[i];
        return s;
    }

    static double cos(float[] a, float[] b) {
        double n = Math.sqrt(dot(a, a) * dot(b, b));
        return n > 0 ? dot(a, b) / n : 0;
    }

    private static double best(float[] f, List<float[]> refs) {
        double b = -2;
        for (float[] r : refs) b = Math.max(b, dot(f, r));
        return b;
    }

    /** Which person each face is (an index into {@code persons}), or -1. */
    public static int[] assign(List<Face> faces, List<Person> persons) {
        int[] out = new int[faces.size()];
        for (int i = 0; i < out.length; i++) {
            float[] f = faces.get(i).emb;
            int who = -1;
            double top = -2;
            for (int p = 0; p < persons.size(); p++) {
                Person pp = persons.get(p);
                if (pp.yes.isEmpty()) continue;
                double s = best(f, pp.yes);
                if (s < FaceModel.SAME || s <= best(f, pp.no)) continue;
                if (s > top) {
                    top = s;
                    who = p;
                }
            }
            out[i] = who;
        }
        return out;
    }

    /** An unnamed someone: their faces (indices into the list given) and their mean, unit length. */
    public static final class Cluster {
        public final List<Integer> faces = new ArrayList<Integer>();
        public float[] mean;
        public int photos;
    }

    /**
     * Groups the faces with {@code taken[i] < 0} whose shorter side is at least {@code minSize} pixels; the groups in
     * {@link #MIN_PHOTOS} photos or more, most photos first, each face best first.
     */
    public static List<Cluster> clusters(final List<Face> faces, int[] taken, int minSize) {
        List<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < faces.size(); i++) if (taken[i] < 0 && faces.get(i).size >= minSize) order.add(i);
        Collections.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                Face x = faces.get(a), y = faces.get(b);
                return Double.compare(y.size * (double) y.score, x.size * (double) x.score);
            }
        });
        List<float[]> sums = new ArrayList<float[]>(), means = new ArrayList<float[]>();
        List<Cluster> all = new ArrayList<Cluster>();
        List<Integer> rest = new ArrayList<Integer>();
        for (int k = 0; k < order.size(); k++) {
            int i = order.get(k);
            float[] f = faces.get(i).emb;
            if (k >= MAX_CLUSTERED) {
                rest.add(i);
                continue;
            }
            int c = nearest(f, means);
            if (c >= 0 && dot(f, means.get(c)) >= CLUSTER) {
                all.get(c).faces.add(i);
                float[] s = sums.get(c);
                for (int d = 0; d < s.length; d++) s[d] += f[d];
                means.set(c, unit(s));
            } else if (all.size() < MAX_GROUPS) {
                Cluster n = new Cluster();
                n.faces.add(i);
                all.add(n);
                sums.add(f.clone());
                means.add(unit(f));
            } else {
                rest.add(i);
            }
        }
        List<Cluster> out = new ArrayList<Cluster>();
        List<float[]> shown = new ArrayList<float[]>();
        for (int c = 0; c < all.size(); c++) {
            Cluster cl = all.get(c);
            if (photos(faces, cl.faces) < MIN_PHOTOS) continue;
            cl.mean = means.get(c);
            out.add(cl);
            shown.add(cl.mean);
        }
        // the faces past the limit: into a shown group when near enough
        for (int i : rest) {
            int c = nearest(faces.get(i).emb, shown);
            if (c >= 0 && dot(faces.get(i).emb, shown.get(c)) >= CLUSTER) out.get(c).faces.add(i);
        }
        for (Cluster cl : out) cl.photos = photos(faces, cl.faces);
        Collections.sort(out, new Comparator<Cluster>() {
            @Override
            public int compare(Cluster a, Cluster b) {
                return b.photos - a.photos;
            }
        });
        return out;
    }

    private static int nearest(float[] f, List<float[]> means) {
        int best = -1;
        double top = -2;
        for (int c = 0; c < means.size(); c++) {
            double s = dot(f, means.get(c));
            if (s > top) {
                top = s;
                best = c;
            }
        }
        return best;
    }

    private static float[] unit(float[] v) {
        double n = Math.sqrt(dot(v, v));
        float[] u = new float[v.length];
        for (int i = 0; i < v.length; i++) u[i] = (float) (n > 0 ? v[i] / n : 0);
        return u;
    }

    private static int photos(List<Face> faces, List<Integer> idx) {
        Set<Long> p = new HashSet<Long>();
        for (int i : idx) p.add(faces.get(i).photo);
        return p.size();
    }

    // ------------------------------------------------------------------ pets and the rest, by examples

    /** The photos of a pet or thing: the sure ones (the marked first), and the less sure, best first. */
    public static final class Members {
        public final List<Integer> sure = new ArrayList<Integer>(), more = new ArrayList<Integer>();
    }

    /**
     * Members of the gallery ({@code gallery}: the photos' vectors) for the marked photos {@code yes} and those taken
     * out {@code no} (indices into the gallery).
     */
    public static Members byExamples(List<float[]> gallery, Set<Integer> yes, Set<Integer> no) {
        Members m = new Members();
        int n = gallery.size();
        List<Integer> marked = new ArrayList<Integer>(yes);
        Collections.sort(marked);
        m.sure.addAll(marked);
        if (yes.isEmpty()) return m;
        final double[] s = new double[n];
        double[] neg = new double[n];
        List<Double> others = new ArrayList<Double>();
        for (int i = 0; i < n; i++) {
            float[] g = gallery.get(i);
            double b = -2, bn = -2;
            for (int j : yes) if (j != i) b = Math.max(b, cos(g, gallery.get(j)));
            for (int j : no) if (j != i) bn = Math.max(bn, cos(g, gallery.get(j)));
            s[i] = b;
            neg[i] = bn;
            if (!yes.contains(i)) others.add(b);
        }
        if (others.size() < MIN_GALLERY) return m;
        double[] sorted = new double[others.size()];
        for (int i = 0; i < sorted.length; i++) sorted[i] = others.get(i);
        Arrays.sort(sorted);
        double median = sorted[sorted.length / 2];
        for (int i = 0; i < sorted.length; i++) sorted[i] = Math.abs(sorted[i] - median);
        Arrays.sort(sorted);
        double sd = Math.max(MIN_SPREAD, 1.4826 * sorted[sorted.length / 2]);
        List<Integer> sure = new ArrayList<Integer>(), more = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) {
            if (yes.contains(i) || no.contains(i) || s[i] <= neg[i]) continue;
            double z = (s[i] - median) / sd;
            if (z >= SURE_Z) sure.add(i);
            else if (z >= MORE_Z) more.add(i);
        }
        Comparator<Integer> byScore = new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Double.compare(s[b], s[a]);
            }
        };
        Collections.sort(sure, byScore);
        Collections.sort(more, byScore);
        m.sure.addAll(sure);
        m.more.addAll(more.size() > MORE_MAX ? more.subList(0, MORE_MAX) : more);
        return m;
    }
}
