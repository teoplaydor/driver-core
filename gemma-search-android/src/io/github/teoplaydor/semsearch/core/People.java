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
 * the gallery is theirs when its best cosine with the named ones is at least {@link #NAMED} and above its best with
 * the ones said not to be them, and no other person comes within {@link #MARGIN} of it (a face that could be either
 * is nobody's). The faces nobody took are grouped: the largest and clearest first, a face joins a group when its mean
 * cosine with the group's first {@link #REPS} faces (its clearest, fixed — a running mean would drift from person to
 * person through lookalikes) is at least {@link #GROUP}; a group in {@link #MIN_PHOTOS} photos or more is offered as
 * someone unnamed. Levels: 0 mild, 1 normal, 2 strict (SFace's cosine for one person: OpenCV takes 0.363 on LFW;
 * a gallery has children, relatives, bad light — so higher). A pet or anything else is the photos the user marked: a
 * photo is in it when its best cosine with the marked ones stands out from the gallery ({@link #SURE_Z} robust
 * deviations above the median, as the search does) and beats its best with the photos taken out of it. A photo may be
 * in any number of these.
 */
public final class People {
    public static final double[] NAMED = {0.40, 0.45, 0.52}, GROUP = {0.45, 0.52, 0.60};
    public static final double MARGIN = 0.03, SURE_Z = 3.5, MORE_Z = 2.0;
    public static final int MILD = 0, NORMAL = 1, STRICT = 2;
    public static final int MIN_PHOTOS = 3, MAX_CLUSTERED = 6000, MAX_GROUPS = 600, MORE_MAX = 300, MIN_GALLERY = 8, REPS = 8;
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

    private static int level(int level) {
        return Math.max(0, Math.min(NAMED.length - 1, level));
    }

    /** Which person each face is (an index into {@code persons}), or -1. */
    public static int[] assign(List<Face> faces, List<Person> persons, int level) {
        double named = NAMED[level(level)];
        int[] out = new int[faces.size()];
        for (int i = 0; i < out.length; i++) {
            float[] f = faces.get(i).emb;
            int who = -1;
            double top = -2, second = -2;
            for (int p = 0; p < persons.size(); p++) {
                Person pp = persons.get(p);
                if (pp.yes.isEmpty()) continue;
                double s = best(f, pp.yes);
                if (s < named || s <= best(f, pp.no)) continue;
                if (s > top) {
                    second = top;
                    top = s;
                    who = p;
                } else if (s > second) {
                    second = s;
                }
            }
            out[i] = who >= 0 && top - second >= MARGIN ? who : -1;
        }
        return out;
    }

    /** An unnamed someone: their faces (indices into the list given), the most typical first, and their first faces. */
    public static final class Cluster {
        public final List<Integer> faces = new ArrayList<Integer>();
        final List<float[]> reps = new ArrayList<float[]>();
        float[] sum;
        public int photos;
    }

    /** The mean cosine of a face with a group's first faces. */
    private static double likeness(float[] f, Cluster c) {
        double s = 0;
        for (float[] r : c.reps) s += dot(f, r);
        return s / c.reps.size();
    }

    /**
     * Groups the faces with {@code taken[i] < 0} whose shorter side is at least {@code minSize} pixels; the groups in
     * {@link #MIN_PHOTOS} photos or more, most photos first. Smaller faces, and those past {@link #MAX_CLUSTERED}, only
     * join a group offered, and only a little more alike ({@code GROUP + 0.05}).
     */
    public static List<Cluster> clusters(final List<Face> faces, int[] taken, int minSize, int level) {
        final double group = GROUP[level(level)];
        List<Integer> order = new ArrayList<Integer>(), small = new ArrayList<Integer>();
        for (int i = 0; i < faces.size(); i++) {
            if (taken[i] >= 0) continue;
            (faces.get(i).size >= minSize ? order : small).add(i);
        }
        Collections.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                Face x = faces.get(a), y = faces.get(b);
                return Double.compare(y.size * (double) y.score, x.size * (double) x.score);
            }
        });
        List<Cluster> all = new ArrayList<Cluster>();
        List<float[]> means = new ArrayList<float[]>();
        List<Integer> rest = new ArrayList<Integer>(small);
        for (int k = 0; k < order.size(); k++) {
            int i = order.get(k);
            if (k >= MAX_CLUSTERED) {
                rest.add(i);
                continue;
            }
            float[] f = faces.get(i).emb;
            Cluster c = join(f, all, means, group);
            if (c != null) {
                add(c, f, i);
                means.set(all.indexOf(c), unit(c.sum));
            } else if (all.size() < MAX_GROUPS) {
                Cluster n = new Cluster();
                n.sum = new float[f.length];
                add(n, f, i);
                all.add(n);
                means.add(unit(n.sum));
            } else {
                rest.add(i);
            }
        }
        List<Cluster> out = new ArrayList<Cluster>();
        List<float[]> shownMeans = new ArrayList<float[]>();
        for (int c = 0; c < all.size(); c++) {
            if (photos(faces, all.get(c).faces) < MIN_PHOTOS) continue;
            out.add(all.get(c));
            shownMeans.add(means.get(c));
        }
        for (int i : rest) {
            Cluster c = join(faces.get(i).emb, out, shownMeans, group + 0.05);
            if (c != null) c.faces.add(i);
        }
        for (final Cluster c : out) {
            c.photos = photos(faces, c.faces);
            // the most typical first (those name a person when the group gets a name)
            final java.util.Map<Integer, Double> like = new java.util.HashMap<Integer, Double>();
            for (int i : c.faces) like.put(i, likeness(faces.get(i).emb, c));
            Collections.sort(c.faces, new Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    return Double.compare(like.get(b), like.get(a));
                }
            });
        }
        Collections.sort(out, new Comparator<Cluster>() {
            @Override
            public int compare(Cluster a, Cluster b) {
                return b.photos - a.photos;
            }
        });
        return out;
    }

    private static void add(Cluster c, float[] f, int i) {
        c.faces.add(i);
        if (c.reps.size() < REPS) c.reps.add(f);
        for (int d = 0; d < f.length; d++) c.sum[d] += f[d];
    }

    /**
     * The group a face joins: among the three whose means are nearest (and not far: {@code group - 0.1}), the one it is
     * most like by its first faces, when at least {@code group}; else none.
     */
    private static Cluster join(float[] f, List<Cluster> groups, List<float[]> means, double group) {
        int[] cand = {-1, -1, -1};
        double[] cs = {-2, -2, -2};
        for (int c = 0; c < means.size(); c++) {
            double s = dot(f, means.get(c));
            if (s < group - 0.1) continue;
            for (int k = 0; k < 3; k++) {
                if (s > cs[k]) {
                    for (int m = 2; m > k; m--) {
                        cs[m] = cs[m - 1];
                        cand[m] = cand[m - 1];
                    }
                    cs[k] = s;
                    cand[k] = c;
                    break;
                }
            }
        }
        Cluster best = null;
        double top = group;
        for (int c : cand) {
            if (c < 0) continue;
            double l = likeness(f, groups.get(c));
            if (l >= top) {
                top = l;
                best = groups.get(c);
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
