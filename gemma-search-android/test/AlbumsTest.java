import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import io.github.teoplaydor.semsearch.core.Albums;
import io.github.teoplaydor.semsearch.core.PhotoTags;

/**
 * Albums by meaning: every album's words are in the vocabulary; over a gallery of scenes (words embedded as bags of
 * their words, as the app's tests do) each album gathers its scene's pictures and none of another scene; a picture
 * may be in several albums; a gallery too small has none. usage: AlbumsTest <assets/photo_tags.txt> <assets/albums.txt>
 */
public class AlbumsTest {
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
        double n = 0;
        for (float x : v) n += x * x;
        for (int i = 0; i < v.length; i++) v[i] /= Math.sqrt(n);
        return v;
    }

    public static void main(String[] args) throws Exception {
        List<String[]> vocab = PhotoTags.parse(new FileInputStream(args[0]));
        List<Albums.Def> defs = Albums.parse(new FileInputStream(args[1]));
        Set<String> en = new HashSet<String>();
        for (String[] w : vocab) en.add(w[0]);
        List<String> missing = new ArrayList<String>();
        Set<String> names = new HashSet<String>();
        for (Albums.Def d : defs) {
            names.add(d.name);
            for (String w : d.words) if (!en.contains(w)) missing.add(d.name + ": " + w);
        }
        check(defs.size() >= 40 && missing.isEmpty() && names.size() == defs.size(), defs.size() + " albums, every word in the vocabulary "
                + (missing.isEmpty() ? "" : missing.toString()) + ", names unique");

        float[][] vecs = new float[vocab.size()][];
        for (int i = 0; i < vecs.length; i++) vecs[i] = bag(vocab.get(i)[0]);
        PhotoTags tags = new PhotoTags(vocab, vecs);
        String[] scenes = {"sunset sea beach", "mountains lake", "city night", "forest path", "receipt paper", "cat sofa",
                "flowers field", "snow hills", "dog puppy", "pizza food"};
        List<float[]> gallery = new ArrayList<float[]>();
        List<String> sceneOf = new ArrayList<String>();
        for (int i = 0; i < 80; i++) {
            gallery.add(bag(scenes[i % scenes.length]));
            sceneOf.add(scenes[i % scenes.length]);
        }
        List<Albums.Album> albums = Albums.build(defs, tags, gallery);
        StringBuilder sb = new StringBuilder();
        for (Albums.Album a : albums) {
            Set<String> got = new HashSet<String>();
            for (int i : a.pictures) got.add(sceneOf.get(i));
            sb.append("\n    ").append(a.name).append(" ").append(a.pictures.size()).append(" ").append(got);
        }
        System.out.println("  albums:" + sb);
        check(only(albums, sceneOf, "Море и пляж", "sunset sea beach") && only(albums, sceneOf, "Закаты и рассветы", "sunset sea beach"),
                "«Море и пляж», «Закаты и рассветы»: the sunset at sea, all 8 of them, nothing else");
        check(only(albums, sceneOf, "Собаки", "dog puppy") && only(albums, sceneOf, "Кошки", "cat sofa")
                        && only(albums, sceneOf, "Чеки и билеты", "receipt paper") && only(albums, sceneOf, "Ночной город", "city night")
                        && only(albums, sceneOf, "Еда", "pizza food"),
                "«Собаки», «Кошки», «Чеки и билеты», «Ночной город», «Еда»: their scenes only");
        // every album holds one scene, and one its words name (a chance similarity in a gallery of ten distinct
        // vectors put the dogs into «Рыбы и море» while the least spread taken as real was 0.01)
        boolean pure = true;
        for (Albums.Album a : albums) {
            Set<String> got = new HashSet<String>();
            for (int i : a.pictures) got.add(sceneOf.get(i));
            pure &= !((a.name.equals("Рыбы и море") || a.name.equals("Горы")) && got.contains("dog puppy"));
        }
        check(pure, "no album by chance: «Рыбы и море» has no dogs");
        check(albums.size() >= 10 && albums.get(0).pictures.size() >= albums.get(albums.size() - 1).pictures.size(),
                albums.size() + " albums, biggest first");
        check(Albums.build(defs, tags, gallery.subList(0, 5)).isEmpty(), "5 pictures: no albums");

        System.out.println(bad == 0 ? "ALBUMS OK" : bad + " FAILED");
        if (bad != 0) System.exit(1);
    }

    /** The album exists and holds exactly the pictures of this scene. */
    static boolean only(List<Albums.Album> albums, List<String> sceneOf, String name, String scene) {
        for (Albums.Album a : albums) {
            if (!a.name.equals(name)) continue;
            int want = 0;
            for (String s : sceneOf) if (s.equals(scene)) want++;
            for (int i : a.pictures) if (!sceneOf.get(i).equals(scene)) return false;
            return a.pictures.size() == want;
        }
        return false;
    }
}
