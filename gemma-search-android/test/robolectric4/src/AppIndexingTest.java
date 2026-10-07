import static org.junit.Assert.*;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;

import io.github.teoplaydor.semsearch.app.Engine;
import io.github.teoplaydor.semsearch.app.IndexStore;
import io.github.teoplaydor.semsearch.app.MainActivity;

/**
 * Gallery indexing + Russian→English bridge with a stand-in model: a bag-of-words "text encoder"
 * (so "кот" and "cat" share nothing, like a model with weak cross-lingual image alignment) and
 * images that embed as known English concepts in MediaStore order. Real PNG files are decoded
 * through the content resolver; the video can't be decoded here and must be reported, not fatal.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "ru-w411dp-h891dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AppIndexingTest {
    static File png(File dir, int i) throws Exception {
        Bitmap b = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888);
        new Canvas(b).drawColor(Color.rgb(40 * i, 90, 160));
        File f = new File(dir, "img" + i + ".png");
        try (FileOutputStream o = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
        return f;
    }

    @Test
    public void indexesGalleryAndBridgesRussianQueries() throws Exception {
        String[] concepts = {"cat on sofa", "dog in park", "red car", "sunset sea", "receipt from store"};
        Application app = RuntimeEnvironment.getApplication();
        File dir = new File(app.getCacheDir(), "media");
        assertTrue(dir.mkdirs());
        for (int i = 0; i < concepts.length - 1; i++) {
            FakeMediaStore.ROWS.add(new FakeMediaStore.Row(100 + i, false, 1700000000L - i, "IMG_" + i + ".png", 64, 48, png(dir, i)));
        }
        // the receipt is a screenshot: auto detail gives it the most detailed budget for its small text
        FakeMediaStore.ROWS.add(new FakeMediaStore.Row(104, false, 1700000000L - 4, "Screenshot_20260101_120000.png", 64, 48,
                png(dir, 4), "Screenshots"));
        FakeMediaStore.ROWS.add(new FakeMediaStore.Row(7, true, 1700000000L, "VID_7.mp4", 1920, 1080, null));
        FakeMediaStore.install();
        Shadows.shadowOf(app).grantPermissions("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED");

        final MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        final Engine e = Engine.get(a);
        Robo.waitFor("store", () -> e.store() != null);
        e.prefs().edit().putInt("photo_model", 0).apply(); // the stand-in plays EmbeddingGemma 2 (batches, budgets)
        Robo.FakeEmbedder fake = new Robo.FakeEmbedder(concepts);
        e.attachModelForTest(fake);
        Robo.waitFor("ready", e::ready);

        // Photos go through the model in batches of 3 (the benchmark's "пачка"); a batch takes one detail
        // level, so with auto detail the screenshot runs on its own at 280 tokens, the photos at 70.
        e.prefs().edit().putInt("batch", 3).apply();
        // The real "Start indexing" path: permission check → MediaStore → decode → embed → SQLite.
        Robo.call(a, "requestMediaAndIndex");
        Robo.waitFor("indexing", () -> !e.indexing && e.idxTotal > 0);
        System.out.println("index status: " + e.idxStatus.replace('\n', ' '));
        assertEquals(5, e.store().count(IndexStore.KIND_PHOTO));
        System.out.println("vision batches: " + fake.batches);
        System.out.println("vision budgets: " + fake.budgets);
        assertEquals(Arrays.asList(3, 1, 1), fake.batches);
        assertEquals(Arrays.asList(70, 70, 280), fake.budgets);
        assertEquals(0, e.store().count(IndexStore.KIND_VIDEO));
        assertTrue("first error is reported: " + e.idxStatus, e.idxStatus.contains("Первая ошибка: VID_7.mp4"));
        assertTrue(e.idxStatus, e.idxStatus.startsWith("Готово: 5 файлов, пропущено 1"));

        // The broken video is remembered: the background job does not count it as new...
        final Object[] res = new Object[1];
        e.countPending((n, err) -> res[0] = err != null ? err : n);
        Robo.waitFor("pending", () -> res[0] != null);
        assertEquals(0, res[0]);
        // ...while a new photo is.
        FakeMediaStore.ROWS.add(new FakeMediaStore.Row(200, false, 1700000100L, "IMG_new.png", 64, 48, png(dir, 9)));
        res[0] = null;
        e.countPending((n, err) -> res[0] = err != null ? err : n);
        Robo.waitFor("pending", () -> res[0] != null);
        assertEquals(1, res[0]);
        FakeMediaStore.ROWS.remove(FakeMediaStore.ROWS.size() - 1);

        // Russian query through the bridge finds the right photo; without the bridge it can't.
        String[][] queries = {{"кот на диване", "IMG_0.png"}, {"собака в парке", "IMG_1.png"},
                {"красная машина", "IMG_2.png"}, {"чек из магазина", "Screenshot_20260101_120000.png"}};
        for (int mode : new int[]{0, 1, 2}) {
            e.prefs().edit().putInt("bridge_mode", mode).apply();
            int correct = 0;
            for (String[] q : queries) {
                res[0] = null;
                e.search(q[0], true, true, false, (r, err) -> res[0] = err != null ? err : r);
                Robo.waitFor("search", () -> res[0] != null);
                assertTrue(String.valueOf(res[0]), res[0] instanceof Engine.SearchResult);
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
        Robo.waitFor("diagnose", () -> res[0] != null);
        assertTrue(String.valueOf(res[0]), res[0] instanceof String);
        String report = (String) res[0];
        assertTrue(report, report.contains("мост «cat on sofa»") && report.contains("топ-10"));

        // A photo deleted from the phone leaves the index on the next run (full gallery access).
        FakeMediaStore.ROWS.remove(0);
        e.startIndexFromPrefs(false);
        Robo.waitFor("prune", () -> !e.indexing && e.store().count(IndexStore.KIND_PHOTO) == 4);
        assertFalse(e.store().hasMedia(IndexStore.KIND_PHOTO, 100));
        a.finish();
    }
}
