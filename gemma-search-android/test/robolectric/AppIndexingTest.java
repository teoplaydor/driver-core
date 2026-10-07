import static org.junit.Assert.*;

import android.Manifest;
import android.content.ContentResolver;
import android.net.Uri;
import android.provider.MediaStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.fakes.RoboCursor;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowLooper;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.util.*;

import io.github.teoplaydor.semsearch.app.Engine;
import io.github.teoplaydor.semsearch.app.IndexStore;
import io.github.teoplaydor.semsearch.app.MainActivity;
import io.github.teoplaydor.semsearch.core.Embedder;
import io.github.teoplaydor.semsearch.core.ImagePreprocessor;
import io.github.teoplaydor.semsearch.core.VectorMath;

/**
 * Gallery indexing + Russian→English bridge on Robolectric with a stand-in model: a bag-of-words
 * "text encoder" (so "кот" and "cat" share nothing, like a model with weak cross-lingual image
 * alignment) and images that embed as known English concepts in MediaStore order.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27, manifest = "AndroidManifest.xml")
public class AppIndexingTest {
    interface Cond { boolean ok(); }

    static void waitFor(String what, Cond c) throws Exception {
        long end = System.currentTimeMillis() + 60_000;
        while (!c.ok()) {
            ShadowLooper.idleMainLooper();
            if (System.currentTimeMillis() > end) fail("timeout waiting for " + what);
            Thread.sleep(20);
        }
        ShadowLooper.idleMainLooper();
    }

    static float[] bag(String text) {
        float[] v = new float[768];
        for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) {
            if (w.isEmpty()) continue;
            Random r = new Random(w.hashCode());
            for (int i = 0; i < v.length; i++) v[i] += (float) r.nextGaussian();
        }
        VectorMath.normalize(v);
        return v;
    }

    static final class FakeEmbedder implements Embedder {
        final String[] imageConcepts;
        int next;
        FakeEmbedder(String... imageConcepts) { this.imageConcepts = imageConcepts; }
        public float[] embedQuery(String q) { return bag(q); }
        public float[] embedDocument(String t) { return bag(t); }
        public float[] embedImage(ImagePreprocessor.Source img, int budget) {
            assertTrue(img.width() > 0 && img.height() > 0);
            img.argb(32, 32);
            return bag(imageConcepts[next++]);
        }
        public float[] embedVideo(List<ImagePreprocessor.Source> frames, int budget) { throw new IllegalStateException("no video"); }
        public boolean supportsImages() { return true; }
        public boolean supportsVideo() { return true; }
        public int embeddingDim() { return 768; }
        public int defaultImageTokens() { return 280; }
        public long[] lastTimingsMs() { return new long[]{0, 0}; }
        public void close() {}
    }

    static byte[] png() throws Exception {
        java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(64, 48, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(bi, "png", out);
        return out.toByteArray();
    }

    @Test
    public void indexesGalleryAndBridgesRussianQueries() throws Exception {
        String[] concepts = {"cat on sofa", "dog in park", "red car", "sunset sea", "receipt from store"};
        ContentResolver cr = RuntimeEnvironment.application.getContentResolver();
        ShadowContentResolver scr = Shadows.shadowOf(cr);
        RoboCursor images = new RoboCursor();
        images.setColumnNames(Arrays.asList("_id", "date_added", "_display_name", "orientation"));
        Object[][] rows = new Object[concepts.length][];
        byte[] img = png();
        for (int i = 0; i < concepts.length; i++) {
            rows[i] = new Object[]{100L + i, 1700000000L - i, "IMG_" + i + ".png", 0};
            scr.registerInputStream(Uri.parse("content://media/external/images/media/" + (100 + i)), new ByteArrayInputStream(img) {
                @Override public synchronized void reset() { pos = 0; }
            });
        }
        images.setResults(rows);
        scr.setCursor(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, images);
        RoboCursor videos = new RoboCursor();
        videos.setColumnNames(Arrays.asList("_id", "date_added", "_display_name"));
        videos.setResults(new Object[][]{{7L, 1700000000L, "VID_7.mp4"}});
        scr.setCursor(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videos);
        Shadows.shadowOf(RuntimeEnvironment.application).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE);

        MainActivity a = Robolectric.setupActivity(MainActivity.class);
        Engine e = Engine.get(a);
        waitFor("store", () -> e.store() != null);
        e.attachModelForTest(new FakeEmbedder(concepts));
        waitFor("ready", e::ready);

        // The real "Start indexing" path: permission check → MediaStore → decode → embed → SQLite.
        Method req = MainActivity.class.getDeclaredMethod("requestMediaAndIndex");
        req.setAccessible(true);
        req.invoke(a);
        waitFor("indexing", () -> !e.indexing && e.idxTotal > 0);
        System.out.println("index status: " + e.idxStatus.replace('\n', ' '));
        assertEquals(5, e.store().count(IndexStore.KIND_PHOTO));
        assertEquals(0, e.store().count(IndexStore.KIND_VIDEO));
        assertTrue("first error is reported: " + e.idxStatus, e.idxStatus.contains("Первая ошибка: VID_7.mp4"));
        assertTrue(e.idxStatus, e.idxStatus.startsWith("Готово: 5 файлов, пропущено 1"));

        // Russian query through the bridge finds the right photo; without the bridge it can't.
        Object[] res = new Object[1];
        String[][] queries = {{"кот на диване", "IMG_0.png"}, {"собака в парке", "IMG_1.png"},
                {"красная машина", "IMG_2.png"}, {"чек из магазина", "IMG_4.png"}};
        for (int mode : new int[]{0, 1, 2}) {
            e.prefs().edit().putInt("bridge_mode", mode).apply();
            int correct = 0;
            for (String[] q : queries) {
                res[0] = null;
                e.search(q[0], true, true, false, (r, err) -> res[0] = err != null ? err : r);
                waitFor("search", () -> res[0] != null);
                Engine.SearchResult sr = (Engine.SearchResult) res[0];
                if (sr.hits.get(0).item.title.equals(q[1])) correct++;
                if (mode == 0 && q[0].equals("кот на диване")) {
                    assertTrue(sr.label, sr.label.contains("для фото «cat on sofa»"));
                }
            }
            System.out.println("bridge mode " + mode + ": " + correct + "/" + queries.length + " correct");
            if (mode != 2) assertEquals(queries.length, correct);
        }

        // Diagnostics report renders with an indexed gallery.
        res[0] = null;
        e.diagnose((r, err) -> res[0] = err != null ? err : r);
        waitFor("diagnose", () -> res[0] != null);
        assertTrue(String.valueOf(res[0]), res[0] instanceof String);
        String report = (String) res[0];
        assertTrue(report, report.contains("мост «cat on sofa»") && report.contains("топ-10"));
        System.out.println(report.substring(0, Math.min(400, report.length())));
        a.finish();
    }
}
