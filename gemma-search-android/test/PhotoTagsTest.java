import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import io.github.teoplaydor.semsearch.core.PhotoTags;

/**
 * "Что на фото": the vocabulary file parses (no duplicates), and a picture gets the words it matches better than
 * the gallery — not a word every picture matches a little ("photo"), not a word it matches less than its median;
 * with a small gallery the picture's own similarities decide; the cache holds only for the same words and model.
 * usage: PhotoTagsTest <assets/photo_tags.txt> <tmp dir>
 */
public class PhotoTagsTest {
    static int bad;

    static void check(boolean ok, String what) {
        if (!ok) bad++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static float[] unit(Random r, int d) {
        float[] v = new float[d];
        double n = 0;
        for (int i = 0; i < d; i++) {
            v[i] = (float) r.nextGaussian();
            n += v[i] * v[i];
        }
        for (int i = 0; i < d; i++) v[i] /= Math.sqrt(n);
        return v;
    }

    /** A text as the sum of its words' random directions (texts sharing words are close). */
    static float[] bag(String text) {
        float[] v = new float[768];
        for (String w : text.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}]+")) {
            if (w.isEmpty()) continue;
            Random r = new Random(w.hashCode());
            for (int i = 0; i < v.length; i++) v[i] += (float) r.nextGaussian();
        }
        double n = 0;
        for (float x : v) n += x * x;
        for (int i = 0; i < v.length; i++) v[i] /= Math.sqrt(n);
        return v;
    }

    /** a·x + b·y + … normalised */
    static float[] mix(float[][] parts, double[] w) {
        float[] v = new float[parts[0].length];
        for (int k = 0; k < parts.length; k++) for (int i = 0; i < v.length; i++) v[i] += (float) (w[k] * parts[k][i]);
        double n = 0;
        for (float x : v) n += x * x;
        for (int i = 0; i < v.length; i++) v[i] /= Math.sqrt(n);
        return v;
    }

    public static void main(String[] args) throws Exception {
        List<String[]> vocab = PhotoTags.parse(new FileInputStream(args[0]));
        java.util.Set<String> en = new java.util.HashSet<String>(), ru = new java.util.HashSet<String>();
        boolean unique = true;
        for (String[] w : vocab) unique &= en.add(w[0]) & ru.add(w[1]);
        check(vocab.size() >= 300 && unique && en.contains("dog") && ru.contains("собака") && ru.contains("скриншот"),
                "vocabulary: " + vocab.size() + " words, unique both ways");
        check(PhotoTags.parse(new ByteArrayInputStream("# c\n\ndog\tсобака\nbad line\n cat \t кошка \n".getBytes("UTF-8"))).size() == 2,
                "comments, blank and malformed lines skipped, spaces trimmed");

        // a toy space: words are directions; every picture leans on "photo" a little
        int d = 768;
        Random r = new Random(1);
        String[][] named = {{"photo", "фото"}, {"dog", "собака"}, {"beach", "пляж"}, {"cat", "кошка"}, {"city", "город"},
                {"food", "еда"}, {"snow", "снег"}, {"car", "машина"}};
        // and words no picture here shows, as most of the real vocabulary for any one picture
        String[][] w = new String[named.length + 40][];
        for (int i = 0; i < w.length; i++) w[i] = i < named.length ? named[i] : new String[]{"word" + i, "слово" + i};
        float[][] vec = new float[w.length][];
        for (int i = 0; i < w.length; i++) vec[i] = unit(r, d);
        PhotoTags tags = new PhotoTags(Arrays.asList(w), vec);
        List<float[]> gallery = new ArrayList<float[]>();
        for (int i = 0; i < 60; i++) {
            int k = 1 + i % (named.length - 1);
            gallery.add(mix(new float[][]{vec[0], vec[k], unit(r, d)}, new double[]{0.8, 0.6, 0.3}));
        }
        tags.calibrate(gallery);
        float[] dogOnBeach = mix(new float[][]{vec[0], vec[1], vec[2], unit(r, d)}, new double[]{0.8, 0.6, 0.55, 0.3});
        List<String> got = tags.rank(dogOnBeach);
        System.out.println("  dog on a beach: " + got);
        check(got.size() == 2 && got.containsAll(Arrays.asList("собака", "пляж")), "a dog on a beach: собака, пляж — not «фото», which every picture matches, "
                + "nor a word no picture shows");
        List<String> plain = tags.rank(mix(new float[][]{vec[0], unit(r, d)}, new double[]{0.8, 0.3}));
        System.out.println("  a plain picture: " + plain);
        boolean specific = false;
        for (int i = 1; i < named.length; i++) specific |= plain.contains(named[i][1]);
        check(!specific && plain.size() <= 1, "a picture with nothing particular: no particular word (at most the generic one, as it "
                + "shows nothing else)");

        PhotoTags few = new PhotoTags(Arrays.asList(w), vec);
        few.calibrate(gallery.subList(0, 3));
        List<String> fewGot = few.rank(dogOnBeach);
        System.out.println("  with 3 pictures in the gallery: " + fewGot);
        check(few.sampled() == 3 && fewGot.contains("собака") && fewGot.contains("пляж"),
                "a small gallery: the picture's own similarities decide");

        // a gallery of near-copies: no spread to stand out from — no word on noise in the fourth digit
        PhotoTags copies = new PhotoTags(Arrays.asList(w), vec);
        List<float[]> same = new ArrayList<float[]>();
        float[] base = mix(new float[][]{vec[0], unit(r, d)}, new double[]{0.8, 0.3});
        for (int i = 0; i < 20; i++) same.add(base);
        copies.calibrate(same);
        float[] near = base.clone();
        for (int i = 0; i < near.length; i++) near[i] += (float) (r.nextGaussian() * 1e-4);
        check(copies.rank(near).isEmpty(), "a gallery of copies, a near-copy: no word (" + copies.rank(near) + ")");

        // the real vocabulary over a gallery of scenes, words embedded as bags of their words (as the app's tests do):
        // every word of the scene comes, the phrase made of them first — however their spreads differ (a z-score
        // cut relative to the best word let «город» alone through for a night city)
        float[][] bags = new float[vocab.size()][];
        for (int i = 0; i < bags.length; i++) bags[i] = bag(vocab.get(i)[0]);
        PhotoTags real = new PhotoTags(vocab, bags);
        String[] scenes = {"sunset sea beach", "mountains lake", "city night", "forest path", "receipt paper", "cat sofa",
                "flowers field", "snow hills"};
        List<float[]> sceneGallery = new ArrayList<float[]>();
        for (int n = 0; n < 60; n++) sceneGallery.add(bag(scenes[n % scenes.length]));
        real.calibrate(sceneGallery);
        List<String> sea = real.rank(bag("sunset sea beach")), night = real.rank(bag("city night"));
        System.out.println("  sunset sea beach: " + sea + "; city night: " + night);
        check(sea.size() == 3 && sea.containsAll(Arrays.asList("закат", "море", "пляж")), "a sunset at sea on a beach: закат, море, пляж");
        check(night.size() >= 3 && night.get(0).equals("ночной город") && night.containsAll(Arrays.asList("город", "ночь")),
                "a city at night: ночной город first, город and ночь too");

        // documents of any kind — not only a sheet of paper — and not a screen; in a gallery where a third of the
        // pictures are documents too (their typical similarity raised)
        String[] docScenes = {"sunset sea beach", "contract paper", "city night", "receipt", "cat sofa", "passport",
                "flowers field", "snow hills", "book page"};
        List<float[]> docGallery = new ArrayList<float[]>();
        for (int n = 0; n < 63; n++) docGallery.add(bag(docScenes[n % docScenes.length]));
        real.calibrate(docGallery);
        String[] docs = {"contract hand", "receipt table", "business card table", "passport", "ticket", "book page", "envelope",
                "id card", "handwriting notebook", "printed page"};
        String[] notDocs = {"screenshot web page", "photo of a screen chat", "dog", "cat sofa", "sunset sea beach", "city night",
                "woman portrait", "man smile", "children park", "family dinner", "person face"};
        StringBuilder missed = new StringBuilder(), taken = new StringBuilder();
        for (String d0 : docs) if (!real.document(bag(d0))) missed.append(" «").append(d0).append("»");
        for (String n0 : notDocs) if (real.document(bag(n0))) taken.append(" «").append(n0).append("»");
        check(missed.length() == 0, "documents of any kind found: a contract in a hand, a receipt, a business card, a passport, a "
                + "ticket, a book's page, an envelope, an ID card, a notebook, a printed page" + (missed.length() > 0 ? " — missed:" + missed : ""));
        check(taken.length() == 0, "no screen, no dog, no sea, no person taken for one" + (taken.length() > 0 ? " — taken:" + taken : ""));

        File cache = new File(args[1], "photo_tags.bin");
        cache.delete();
        PhotoTags.writeCache(cache, Arrays.asList(w), vec);
        float[][] back = PhotoTags.readCache(cache, Arrays.asList(w), vec[0]);
        check(back != null && Arrays.equals(back[3], vec[3]), "the cache reads back");
        check(PhotoTags.readCache(cache, Arrays.asList(w), unit(r, d)) == null, "another model (the first word's vector differs): not used");
        List<String[]> other = new ArrayList<String[]>(Arrays.asList(w));
        other.set(4, new String[]{"town", "город"});
        check(PhotoTags.readCache(cache, other, vec[0]) == null, "other words: not used");

        System.out.println(bad == 0 ? "PHOTO TAGS OK" : bad + " FAILED");
        if (bad != 0) System.exit(1);
    }
}
