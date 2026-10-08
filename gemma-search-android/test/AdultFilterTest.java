import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import io.github.teoplaydor.semsearch.core.AdultFilter;
import io.github.teoplaydor.semsearch.core.PhotoTags;

/**
 * 18+: the phrases parse and name no people as such; over a gallery of scenes (words embedded as bags of their words,
 * as the app's tests do) the nude pictures are found at every level and no ordinary one at the normal level; a beach
 * in swimsuits that leans a little towards "nudity" is not hidden — it is mainly a beach; with a gallery too small
 * to calibrate on, the picture's own similarities decide.
 * usage: AdultFilterTest <assets/photo_tags.txt> <assets/adult.txt>
 */
public class AdultFilterTest {
    static int bad;

    static void check(boolean ok, String what) {
        if (!ok) bad++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static float[] bag(String text) {
        float[] v = new float[768];
        for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) {
            if (w.isEmpty()) continue;
            Random r = new Random(w.hashCode());
            for (int i = 0; i < v.length; i++) v[i] += (float) r.nextGaussian();
        }
        return norm(v);
    }

    static float[] norm(float[] v) {
        double n = 0;
        for (float x : v) n += x * x;
        for (int i = 0; i < v.length; i++) v[i] /= Math.sqrt(n);
        return v;
    }

    static float[] mix(String a, double wa, String b, double wb) {
        float[] x = bag(a), y = bag(b), v = new float[x.length];
        for (int i = 0; i < v.length; i++) v[i] = (float) (wa * x[i] + wb * y[i]);
        return norm(v);
    }

    public static void main(String[] args) throws Exception {
        List<String[]> vocab = PhotoTags.parse(new FileInputStream(args[0]));
        List<String> phrases = AdultFilter.parse(new FileInputStream(args[1]));
        List<String> people = Arrays.asList("person", "people", "man", "woman", "child", "children", "baby", "teenager", "girl", "boy");
        boolean noPeople = true;
        for (String p : phrases) for (String w : p.split(" ")) noPeople &= !people.contains(w);
        check(phrases.size() >= 8 && phrases.contains("nudity") && noPeople,
                phrases.size() + " phrases, none naming people as such (a dressed woman would match «naked woman» through «woman»)");

        float[][] tv = new float[vocab.size()][];
        for (int i = 0; i < tv.length; i++) tv[i] = bag(vocab.get(i)[0]);
        PhotoTags tags = new PhotoTags(vocab, tv);
        float[][] av = new float[phrases.size()][];
        for (int i = 0; i < av.length; i++) av[i] = bag(phrases.get(i));
        AdultFilter f = new AdultFilter(phrases, av);

        String[] scenes = {"sunset sea beach", "mountains lake", "city night", "cat sofa", "dog puppy", "pizza food", "swimsuit beach",
                "shirtless man gym", "woman portrait", "child toys"};
        String[] nude = {"nudity", "erotic photo bed", "nude body"};
        List<float[]> gallery = new ArrayList<float[]>();
        List<Boolean> isNude = new ArrayList<Boolean>();
        for (int i = 0; i < 70; i++) {
            gallery.add(bag(scenes[i % scenes.length]));
            isNude.add(false);
        }
        for (int i = 0; i < 9; i++) {
            gallery.add(bag(nude[i % nude.length]));
            isNude.add(true);
        }
        tags.calibrate(gallery);
        f.calibrate(gallery);
        for (int level = 0; level < 3; level++) {
            int found = 0, wrong = 0;
            for (int i = 0; i < gallery.size(); i++) {
                boolean a = f.adult(gallery.get(i), tags, level);
                if (a && isNude.get(i)) found++;
                if (a && !isNude.get(i)) wrong++;
            }
            System.out.println("  level " + level + ": " + found + " of 9 nude pictures, " + wrong + " of 70 ordinary");
            check(found == 9 && (level == AdultFilter.STRICT || wrong == 0), "level " + level + ": every nude picture"
                    + (level == AdultFilter.STRICT ? "" : ", no ordinary one"));
        }
        // a beach in swimsuits that leans towards "nudity" a little: it stands out on it, but it is mainly a beach
        float[] swim = mix("swimsuit beach", 0.9, "nudity", 0.3), bedroom = mix("nudity", 0.8, "bed", 0.5);
        System.out.println(String.format(Locale.ROOT, "  swimsuits: best %.3f, top word excess %.3f; nude on a bed: best %.3f, top %.3f",
                f.best(swim), tags.topExcess(swim), f.best(bedroom), tags.topExcess(bedroom)));
        check(!f.adult(swim, tags, AdultFilter.NORMAL) && !f.adult(swim, tags, AdultFilter.STRICT),
                "a beach in swimsuits with a little skin: not hidden, even when strict");
        check(f.adult(bedroom, tags, AdultFilter.MILD), "nude on a bed: hidden, even when mild");

        // a gallery of five: no typical picture yet, the picture's own similarities to the vocabulary decide
        AdultFilter few = new AdultFilter(phrases, av);
        PhotoTags fewTags = new PhotoTags(vocab, tv);
        List<float[]> small = Arrays.asList(bag("dog puppy"), bag("sunset sea beach"), bag("swimsuit beach"), bag("nudity"), bag("nude body"));
        few.calibrate(small);
        fewTags.calibrate(small);
        boolean[] got = new boolean[small.size()];
        for (int i = 0; i < got.length; i++) got[i] = few.adult(small.get(i), fewTags, AdultFilter.NORMAL);
        System.out.println("  a gallery of five: " + Arrays.toString(got));
        check(few.sampled() == 5 && Arrays.equals(got, new boolean[]{false, false, false, true, true}), "a gallery of five: the two nude ones");

        System.out.println(bad == 0 ? "ADULT FILTER OK" : bad + " FAILED");
        if (bad != 0) System.exit(1);
    }
}
