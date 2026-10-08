import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import io.github.teoplaydor.semsearch.core.People;

/**
 * People and pets: faces of a few people (each a direction in face space with the spread of real photos, cosine to
 * their own mean well above 0.363) — a named person gets all their faces and no one else's, two people on one photo
 * put it in both, a lookalike taken for them is out once one of the lookalike's faces is said not to be them; the
 * unnamed ones come as groups, one per person, the one in two photos not offered; a pet by examples: its photos, the
 * ones of a lookalike pet go once one of them is taken out, and no others.
 * usage: PeopleTest
 */
public class PeopleTest {
    static int bad;

    static void check(boolean ok, String what) {
        if (!ok) bad++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static float[] unit(float[] v) {
        double n = 0;
        for (float x : v) n += x * x;
        for (int i = 0; i < v.length; i++) v[i] /= Math.sqrt(n);
        return v;
    }

    static float[] dir(Random r, int d) {
        float[] v = new float[d];
        for (int i = 0; i < d; i++) v[i] = (float) r.nextGaussian();
        return unit(v);
    }

    /** A face of someone: their direction plus noise (cosine to the direction about {@code c}). */
    static float[] face(Random r, float[] who, double c) {
        float[] n = dir(r, who.length), v = new float[who.length];
        double s = Math.sqrt(1 - c * c) / c;
        for (int i = 0; i < v.length; i++) v[i] = (float) (who[i] + s * n[i]);
        return unit(v);
    }

    static List<People.Face> lookalikes(Random r, float[] look, long from, int n) {
        List<People.Face> out = new ArrayList<People.Face>();
        for (int k = 0; k < n; k++) out.add(new People.Face(1000 + from + k, 7000 + k, 0.1f, 0.1f, 0.2f, 0.2f, 90, 0.95f, face(r, look, 0.85)));
        return out;
    }

    public static void main(String[] args) {
        Random r = new Random(5);
        int d = 128;
        float[][] who = new float[6][];
        for (int i = 0; i < who.length; i++) who[i] = dir(r, d);
        // photos: person p in photos; photo 100 has persons 0 and 1; person 5 in two photos only
        List<People.Face> faces = new ArrayList<People.Face>();
        // person 0's lookalike: near enough that some of their faces pass for person 0's
        float[] look = new float[d];
        float[] other = dir(r, d);
        for (int i = 0; i < d; i++) look[i] = (float) (0.62 * who[0][i] + 0.78 * other[i]);
        unit(look);
        List<Integer> truth = new ArrayList<Integer>();
        long id = 1;
        int[] count = {12, 9, 7, 5, 4, 2};
        for (int p = 0; p < who.length; p++) {
            for (int k = 0; k < count[p]; k++) {
                faces.add(new People.Face(id++, p * 1000 + k, 0.1f, 0.1f, 0.2f, 0.2f, 60 + r.nextInt(80), 0.95f, face(r, who[p], 0.75)));
                truth.add(p);
            }
        }
        for (int p = 0; p < 2; p++) {
            faces.add(new People.Face(id++, 100, 0.1f + 0.5f * p, 0.1f, 0.2f, 0.2f, 70, 0.95f, face(r, who[p], 0.75)));
            truth.add(p);
        }
        int lookFrom = faces.size();
        for (int k = 0; k < 6; k++) {
            faces.add(new People.Face(id++, 7000 + k, 0.1f, 0.1f, 0.2f, 0.2f, 90, 0.95f, face(r, look, 0.85)));
            truth.add(7);
        }
        double across = -1;
        for (int i = 0; i < lookFrom; i++) {
            for (int j = i + 1; j < lookFrom; j++) {
                double c = 0;
                for (int k = 0; k < d; k++) c += faces.get(i).emb[k] * faces.get(j).emb[k];
                if (!truth.get(i).equals(truth.get(j))) across = Math.max(across, c);
            }
        }
        System.out.println(String.format(Locale.ROOT, "  %d faces of %d people and a lookalike; the largest cosine across people %.3f",
                faces.size(), who.length, across));

        // nobody named: the groups (without the lookalike)
        List<People.Face> plain = faces.subList(0, lookFrom);
        faces = new ArrayList<People.Face>(plain);
        int[] none = new int[faces.size()];
        Arrays.fill(none, -1);
        List<People.Cluster> cl = People.clusters(faces, none, 40);
        StringBuilder sb = new StringBuilder();
        boolean pure = true;
        Set<Integer> found = new HashSet<Integer>();
        for (People.Cluster c : cl) {
            Set<Integer> ps = new HashSet<Integer>();
            for (int i : c.faces) ps.add(truth.get(i));
            pure &= ps.size() == 1;
            found.addAll(ps);
            sb.append(' ').append(ps).append('×').append(c.photos);
        }
        System.out.println("  groups:" + sb);
        check(pure && found.equals(new HashSet<Integer>(Arrays.asList(0, 1, 2, 3, 4))) && cl.size() == 5 && cl.get(0).photos == 13,
                "unnamed: one group per person, most photos first, nobody mixed; the one in two photos not offered");

        // the lookalike back in; person 0 named from two faces: all of theirs, no one else's but the lookalike's
        faces = new ArrayList<People.Face>(plain);
        faces.addAll(lookalikes(r, look, lookFrom, truth.size() - lookFrom));
        People.Person p0 = new People.Person();
        p0.yes.add(faces.get(0).emb);
        p0.yes.add(faces.get(1).emb);
        People.Person p1 = new People.Person();
        p1.yes.add(faces.get(12).emb);
        int[] before = People.assign(faces, Arrays.asList(p0, p1));
        int taken = 0;
        for (int i = lookFrom; i < faces.size(); i++) if (before[i] == 0) taken++;
        System.out.println("  the lookalike taken for person 0: " + taken + " of " + (faces.size() - lookFrom) + " faces");
        // one of them said not to be person 0
        int wrong = lookFrom;
        for (int i = lookFrom; i < faces.size(); i++) if (before[i] == 0) wrong = i;
        p0.no.add(faces.get(wrong).emb);
        int[] a = People.assign(faces, Arrays.asList(p0, p1));
        int got0 = 0, got1 = 0, others = 0;
        Set<Long> photos0 = new HashSet<Long>(), photos1 = new HashSet<Long>();
        for (int i = 0; i < a.length; i++) {
            if (a[i] == 0 && truth.get(i) == 0) got0++;
            if (a[i] == 1 && truth.get(i) == 1) got1++;
            if (a[i] >= 0 && truth.get(i) != a[i]) others++;
            if (a[i] == 0) photos0.add(faces.get(i).photo);
            if (a[i] == 1) photos1.add(faces.get(i).photo);
        }
        System.out.println("  named: person 0 " + got0 + " of 13 faces, person 1 " + got1 + " of 10, others " + others);
        check(taken > 0 && got0 == 13 && got1 == 10 && others == 0, "a named person: all their faces, no one else's — the lookalike "
                + "out after one of their faces is said not to be them");
        check(photos0.contains(100L) && photos1.contains(100L), "two people on one photo: it is in both");
        List<People.Cluster> rest = People.clusters(faces, a, 40);
        boolean noNamed = true;
        for (People.Cluster c : rest) for (int i : c.faces) noNamed &= a[i] < 0;
        check(rest.size() == 4 && noNamed, "the unnamed after naming two: the other three and the lookalike (" + rest.size() + ")");

        // a pet by examples: Gemma-like vectors of scenes; the cat photos, not the dogs; a dog taken out stays out
        int dim = 256;
        float[] cat = dir(r, dim), dog = dir(r, dim), cat2 = dir(r, dim);
        // another cat that looks much like this one
        for (int j = 0; j < dim; j++) cat2[j] = (float) (0.7 * cat[j] + 0.7 * cat2[j]);
        unit(cat2);
        List<float[]> gallery = new ArrayList<float[]>();
        List<String> kind = new ArrayList<String>();
        for (int i = 0; i < 60; i++) {
            float[] base = dir(r, dim);
            String k = i < 8 ? "cat" : i < 14 ? "dog" : i < 17 ? "cat2" : "other";
            float[] c = k.equals("cat") ? cat : k.equals("dog") ? dog : k.equals("cat2") ? cat2 : null;
            float[] v = new float[dim];
            for (int j = 0; j < dim; j++) v[j] = (float) (c == null ? base[j] : 0.8 * c[j] + 0.6 * base[j]);
            gallery.add(unit(v));
            kind.add(k);
        }
        People.Members first = People.byExamples(gallery, new HashSet<Integer>(Arrays.asList(0, 1)), new HashSet<Integer>());
        Set<String> kinds0 = new HashSet<String>();
        for (int i : first.sure) kinds0.add(kind.get(i));
        System.out.println("  the cat by two examples: " + first.sure.size() + " photos " + kinds0);
        // the lookalike cat's first photo taken out
        People.Members m = People.byExamples(gallery, new HashSet<Integer>(Arrays.asList(0, 1)), new HashSet<Integer>(Arrays.asList(14)));
        Set<String> kinds = new HashSet<String>();
        for (int i : m.sure) kinds.add(kind.get(i));
        System.out.println("  after taking one of the other cat out: " + m.sure + " " + kinds + ", less sure " + m.more.size());
        check(kinds0.contains("cat2") && m.sure.size() == 8 && kinds.equals(new HashSet<String>(Arrays.asList("cat")))
                        && m.sure.get(0) == 0 && m.sure.get(1) == 1,
                "a pet by examples: the marked first, then its other photos; a lookalike pet goes with one of its photos taken out; no dogs");
        check(People.byExamples(gallery.subList(0, 5), new HashSet<Integer>(Arrays.asList(0)), new HashSet<Integer>()).sure.size() == 1,
                "a gallery too small to tell: only the marked photo");

        System.out.println(bad == 0 ? "PEOPLE OK" : bad + " FAILED");
        if (bad != 0) System.exit(1);
    }
}
