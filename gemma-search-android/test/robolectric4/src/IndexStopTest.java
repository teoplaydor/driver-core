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
import java.util.List;

import io.github.teoplaydor.semsearch.app.Engine;
import io.github.teoplaydor.semsearch.app.IndexStore;
import io.github.teoplaydor.semsearch.app.MainActivity;
import io.github.teoplaydor.semsearch.core.ImagePreprocessor;

/**
 * A broken model must not sink the gallery. In 0.9.9 the NPU process died on its first photo at 280 tokens and
 * every later photo failed at once: thousands of files counted as done, "0.09 s per file", all remembered as
 * failed, nothing in the index. Now the same failure file after file stops the run, and the NPU process gone
 * stops it at once; the files stay unmarked (the next run takes them).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "ru-w411dp-h891dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class IndexStopTest {
    /** Works for {@code good} photos, then fails every time with {@code error}. */
    static final class Breaking extends Robo.FakeEmbedder {
        int good;
        Exception error;

        @Override
        public float[][] embedImages(List<ImagePreprocessor.Source> imgs, int budget) {
            if (good <= 0) {
                if (error instanceof RuntimeException) throw (RuntimeException) error;
                throw new IllegalStateException(error.getMessage(), error);
            }
            good -= imgs.size();
            return super.embedImages(imgs, budget);
        }
    }

    static Exception npuCrash(String msg) throws Exception {
        java.lang.reflect.Constructor<?> k = Class.forName("io.github.teoplaydor.semsearch.app.NpuVision$Crashed")
                .getDeclaredConstructor(String.class);
        k.setAccessible(true);
        return (Exception) k.newInstance(msg);
    }

    static int streakStop() throws Exception {
        java.lang.reflect.Field f = Engine.class.getDeclaredField("FAIL_STREAK_STOP");
        f.setAccessible(true);
        return f.getInt(null);
    }

    @Test
    public void brokenModelStopsTheRunWithoutMarkingFiles() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        File dir = new File(app.getCacheDir(), "media");
        assertTrue(dir.mkdirs());
        for (int i = 0; i < 20; i++) {
            FakeMediaStore.ROWS.add(new FakeMediaStore.Row(100 + i, false, 1700000000L - i, "IMG_" + i + ".png", 64, 48,
                    AppIndexingTest.png(dir, i % 6)));
        }
        FakeMediaStore.install();
        Shadows.shadowOf(app).grantPermissions("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED");
        final MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        final Engine e = Engine.get(a);
        Robo.waitFor("store", () -> e.store() != null);
        e.prefs().edit().putInt("photo_model", 0).putInt("photo_detail", 0).apply(); // 70 tokens

        // 1. The same failure on every photo: the run stops after a few, none is remembered as failed, the
        //    detail stays as it was (a failure at 70 tokens used to switch it to the maximum).
        Breaking m = new Breaking();
        m.error = new IllegalStateException("vision encoder returned 0 rows for 70 soft tokens");
        e.attachModelForTest(m);
        Robo.waitFor("ready", e::ready);
        e.startIndex(1000, 0);
        Robo.waitFor("stopped", () -> !e.indexing && e.idxTotal > 0);
        System.out.println("broken model: " + e.idxStatus.replace('\n', ' '));
        assertTrue(e.idxStatus, e.idxStatus.startsWith("Индексация остановлена: модель не обработала "
                + streakStop() + " файлов подряд — vision encoder returned 0 rows"));
        assertTrue(e.idxStatus, e.idxStatus.contains("остальные файлы не помечены"));
        assertFalse(e.idxStatus, e.idxStatus.contains("Первая ошибка"));
        assertEquals(0, e.store().count(IndexStore.KIND_PHOTO));
        assertTrue(e.prefs().getStringSet("failed_media", new java.util.HashSet<String>()).isEmpty());
        assertEquals(0, e.prefs().getInt("photo_detail", -1));
        assertEquals(70, e.photoBudget());
        final Object[] res = new Object[1];
        e.countPending((n, err) -> res[0] = err != null ? err : n);
        Robo.waitFor("pending", () -> res[0] != null);
        assertEquals(20, res[0]); // the background job still sees every photo as new

        // 2. A few photos work, then the NPU process dies: the run stops at once (no file after it is tried),
        //    with the reason, and the photos done stay in the index.
        m = new Breaking();
        m.good = 3;
        m.error = npuCrash("NPU-процесс упал: система закрыла его из-за нехватки памяти; в это время: сборка под 2520 фрагментов");
        e.attachModelForTest(m);
        Robo.waitFor("ready", e::ready);
        e.startIndex(1000, 0);
        Robo.waitFor("stopped", () -> !e.indexing && e.idxTotal > 0);
        System.out.println("NPU process gone: " + e.idxStatus.replace('\n', ' '));
        assertTrue(e.idxStatus, e.idxStatus.startsWith("Индексация остановлена: NPU-процесс упал: система закрыла его из-за нехватки памяти"));
        assertTrue(e.idxStatus, e.idxStatus.contains("В индекс добавлено 3 из 20"));
        assertEquals(3, e.store().count(IndexStore.KIND_PHOTO));
        assertTrue(e.prefs().getStringSet("failed_media", new java.util.HashSet<String>()).isEmpty());
        assertTrue(e.prefs().getString("qnn_crash", ""), e.prefs().getString("qnn_crash", "").startsWith("NPU-процесс упал"));
        // the model is reloaded for a new NPU process (here: no model files, so none)
        Robo.waitFor("reloaded", () -> e.state != Engine.State.LOADING && e.state != Engine.State.READY);

        // 3. In the background the NPU is not crashed again; a run the user starts tries again.
        e.attachModelForTest(new Robo.FakeEmbedder("photo"));
        Robo.waitFor("ready", e::ready);
        e.loadedAccel = Engine.ACCEL_NPU_QNN;
        e.startIndexFromPrefs(true);
        Robo.waitFor("skipped", () -> !e.indexing && e.idxStatus.startsWith("Фоновая"));
        System.out.println("background after the crash: " + e.idxStatus);
        assertTrue(e.idxStatus, e.idxStatus.startsWith("Фоновая индексация пропущена: в прошлый раз упал NPU-процесс"));
        assertEquals(3, e.store().count(IndexStore.KIND_PHOTO));
        e.startIndex(1000, 0);
        Robo.waitFor("done", () -> !e.indexing && e.idxStatus.startsWith("Готово"));
        System.out.println("run by the user: " + e.idxStatus.replace('\n', ' '));
        assertEquals(20, e.store().count(IndexStore.KIND_PHOTO));
        assertFalse(e.prefs().contains("qnn_crash"));

        // 4. The NPU process dies compiling a large graph (280 tokens): the photos go back into the queue and a new
        //    process compiles the next way — twice here, then it works and every photo is indexed, none failed.
        e.store().clearMedia();
        e.prefs().edit().putInt("photo_detail", 2).apply();
        java.lang.reflect.Field pool = Engine.class.getDeclaredField("qnnPool");
        pool.setAccessible(true);
        pool.setInt(e, 3); // 280 tokens = 2520 patches
        final int[] reloads = {0};
        final String died = "NPU-процесс упал: сбой в машинном коде (ONNX Runtime или QNN); в это время: сборка под 2520 фрагментов: "
                + "QNN компилирует граф";
        Engine.reloadForTest = () -> {
            reloads[0]++;
            if (reloads[0] == 1) {
                Breaking again = new Breaking();
                again.error = npuCrash(died + " — способ 2");
                return again;
            }
            return new Robo.FakeEmbedder("photo");
        };
        Breaking first = new Breaking();
        first.error = npuCrash(died + " — способ 1");
        e.attachModelForTest(first);
        Robo.waitFor("ready", e::ready);
        e.loadedAccel = Engine.ACCEL_NPU_QNN;
        e.prefs().edit().putInt("batch", 4).apply();
        e.startIndex(1000, 0);
        Robo.waitFor("done", () -> !e.indexing && e.idxTotal > 0);
        System.out.println("NPU restarted twice: " + e.idxStatus.replace('\n', ' '));
        assertEquals(2, reloads[0]);
        assertTrue(e.idxStatus, e.idxStatus.startsWith("Готово: 20 файлов"));
        assertEquals(20, e.store().count(IndexStore.KIND_PHOTO));
        assertTrue(e.prefs().getStringSet("failed_media", new java.util.HashSet<String>()).isEmpty());
        assertFalse(e.prefs().contains("qnn_crash"));
        Engine.reloadForTest = null;
        a.finish();
    }
}
