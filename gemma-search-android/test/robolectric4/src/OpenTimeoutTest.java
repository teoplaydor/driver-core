import static org.junit.Assert.*;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import io.github.teoplaydor.semsearch.app.Engine;
import io.github.teoplaydor.semsearch.app.IndexStore;
import io.github.teoplaydor.semsearch.app.MainActivity;

/**
 * A file that never opens must not hold indexing forever (0.10.11 stood at "8 из 108" with no load, on the first
 * video): a photo whose decoding hangs and a video whose frames never come count as unreadable after the time
 * allowed, the photos queued behind the hung one are decoded by a new thread, and the run ends.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "ru-w411dp-h891dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class OpenTimeoutTest {
    @Test
    public void hungFilesAreSkipped() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        File dir = new File(app.getCacheDir(), "media");
        assertTrue(dir.mkdirs());
        for (int i = 0; i < 6; i++) {
            FakeMediaStore.ROWS.add(new FakeMediaStore.Row(100 + i, false, 1700000000L - i, "IMG_" + i + ".png", 64, 48,
                    AppIndexingTest.png(dir, i)));
        }
        for (int i = 0; i < 2; i++) {
            FakeMediaStore.ROWS.add(new FakeMediaStore.Row(200 + i, true, 1690000000L - i, "VID_" + i + ".mp4", 64, 48,
                    AppIndexingTest.png(dir, i)));
        }
        FakeMediaStore.install();
        Shadows.shadowOf(app).grantPermissions("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED");
        final MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        final Engine e = Engine.get(a);
        Robo.waitFor("store", () -> e.store() != null);
        e.prefs().edit().putInt("photo_model", 0).putInt("photo_detail", 0).putInt("batch", 2).apply();
        java.lang.reflect.Field timeout = Engine.class.getDeclaredField("openTimeoutS");
        timeout.setAccessible(true);
        timeout.setInt(null, 2);
        Engine.hangForTest = new HashSet<String>(Arrays.asList("IMG_2.png", "VID_0.mp4"));
        try {
            e.attachModelForTest(new Robo.FakeEmbedder("photo"));
            Robo.waitFor("ready", e::ready);
            long t0 = System.currentTimeMillis();
            e.startIndex(1000, 1000);
            Robo.waitFor("done", () -> !e.indexing && e.idxTotal > 0);
            long took = System.currentTimeMillis() - t0;
            System.out.println("with a hung photo and a hung video: " + e.idxStatus.replace('\n', ' ') + " — " + took + " ms");
            assertTrue(e.idxStatus, e.idxStatus.startsWith("Готово: 5 файлов, пропущено 3"));
            assertTrue(e.idxStatus, e.idxStatus.contains("IMG_2.png: файл не открылся за 2 с"));
            assertEquals(5, e.store().count(IndexStore.KIND_PHOTO));
            Set<String> failed = e.prefs().getStringSet("failed_media", new HashSet<String>());
            // the hung photo and video, and the other video, which the stand-in model cannot embed
            assertEquals(new HashSet<String>(Arrays.asList(IndexStore.KIND_PHOTO + ":102", IndexStore.KIND_VIDEO + ":200",
                    IndexStore.KIND_VIDEO + ":201")), failed);
            assertTrue(took + " ms", took < 30_000);
            String journal = new String(java.nio.file.Files.readAllBytes(new File(a.getCacheDir(), "journal.txt").toPath()), "UTF-8");
            assertTrue(journal, journal.contains("IMG_2.png не открылся за 2 с — декодер заменён")
                    && journal.contains("кадры видео VID_0.mp4 не получены за 2 с"));
            // videos that never open, one after another: after three the rest wait for another run, unmarked
            e.store().clearMedia();
            e.prefs().edit().remove("failed_media").apply();
            FakeMediaStore.ROWS.clear();
            Set<String> hung = new HashSet<String>();
            for (int i = 0; i < 6; i++) {
                FakeMediaStore.ROWS.add(new FakeMediaStore.Row(300 + i, true, 1680000000L - i, "CLIP_" + i + ".mp4", 64, 48,
                        AppIndexingTest.png(dir, i)));
                hung.add("CLIP_" + i + ".mp4");
            }
            Engine.hangForTest = hung;
            long t1 = System.currentTimeMillis();
            e.startIndex(1000, 1000);
            Robo.waitFor("videos", () -> !e.indexing && e.idxTotal > 0);
            System.out.println("videos that never open: " + e.idxStatus.replace('\n', ' ') + " — " + (System.currentTimeMillis() - t1) + " ms");
            assertTrue(e.idxStatus, e.idxStatus.startsWith("Готово: 0 файлов, пропущено 3")
                    && e.idxStatus.contains("3 видео подряд не открылись — остальные 3 в этот раз пропущены"));
            assertEquals(3, e.prefs().getStringSet("failed_media", new HashSet<String>()).size());
        } finally {
            Engine.hangForTest = null;
            timeout.setInt(null, 60);
        }
        a.finish();
    }
}
