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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import io.github.teoplaydor.semsearch.app.Engine;
import io.github.teoplaydor.semsearch.app.IndexStore;
import io.github.teoplaydor.semsearch.app.MainActivity;
import io.github.teoplaydor.semsearch.core.Embedder;
import io.github.teoplaydor.semsearch.core.ImagePreprocessor;
import io.github.teoplaydor.semsearch.core.PatternSource;

/**
 * The indexing pipeline: with the vision encoder off the CPU (here: the NPU), the text model of one batch runs on
 * its own thread while the vision encoder takes the next batch. Every photo gets into the index; a vision stage
 * that fails sends its batch the usual way, a text stage that finds the model closed puts its photos back into
 * the queue (neither marks a photo as failed). The accelerator check measures the pipeline as indexing runs it:
 * the time per photo is the larger stage, not their sum.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "ru-w411dp-h891dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PipelineTest {
    /** Each stage takes its time; records when and on which thread it ran. */
    static final class Staged extends Robo.FakeEmbedder implements Embedder.Staged {
        final long visionMs, textMs;
        final List<long[]> visions = Collections.synchronizedList(new ArrayList<long[]>()),
                texts = Collections.synchronizedList(new ArrayList<long[]>());
        final Set<String> visionThreads = Collections.synchronizedSet(new java.util.HashSet<String>()),
                textThreads = Collections.synchronizedSet(new java.util.HashSet<String>());
        int failVisionAt = -1, closedTextAt = -1;
        int visionCalls, textCalls, plain;

        Staged(long visionMs, long textMs) {
            super("photo");
            this.visionMs = visionMs;
            this.textMs = textMs;
        }

        static void sleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public float[][] embedImages(List<ImagePreprocessor.Source> imgs, int budget) {
            synchronized (this) {
                plain++;
            }
            return super.embedImages(imgs, budget);
        }

        public Object startImages(List<ImagePreprocessor.Source> imgs, int budget) {
            int call;
            synchronized (this) {
                call = visionCalls++;
            }
            if (call == failVisionAt) throw new IllegalStateException("vision stage failed once");
            visionThreads.add(Thread.currentThread().getName());
            long s = System.currentTimeMillis();
            sleep(visionMs);
            float[][] r = super.embedImages(imgs, budget); // reads the bitmaps: they are alive during this stage only
            visions.add(new long[]{s, System.currentTimeMillis()});
            return r;
        }

        public float[][] finishImages(Object started) {
            int call;
            synchronized (this) {
                call = textCalls++;
            }
            if (call == closedTextAt) throw new Embedder.Closed();
            textThreads.add(Thread.currentThread().getName());
            long s = System.currentTimeMillis();
            sleep(textMs);
            texts.add(new long[]{s, System.currentTimeMillis()});
            return (float[][]) started;
        }

        public long[] timingsMs(Object started) {
            return new long[]{visionMs, textMs};
        }

        boolean overlapped() {
            synchronized (visions) {
                synchronized (texts) {
                    for (long[] t : texts) for (long[] v : visions) if (v[0] < t[1] && t[0] < v[1]) return true;
                }
            }
            return false;
        }
    }

    static Engine.State ready(Engine e, Staged m) throws Exception {
        e.attachModelForTest(m);
        Robo.waitFor("ready", e::ready);
        e.loadedAccel = Engine.ACCEL_NPU_QNN;
        return e.state;
    }

    static void indexAll(Engine e) throws Exception {
        e.store().clearMedia();
        e.startIndex(1000, 0);
        Robo.waitFor("done", () -> !e.indexing && e.idxTotal > 0);
    }

    @Test
    public void textOfOneBatchRunsWithTheVisionOfTheNext() throws Exception {
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
        e.prefs().edit().putInt("photo_model", 0).putInt("photo_detail", 0).putInt("batch", 2).apply();

        // 1. The pipeline: 10 batches of 2, text stages on their own thread, overlapping the next vision stage
        Staged m = new Staged(150, 150);
        ready(e, m);
        indexAll(e);
        System.out.println("pipeline: " + e.idxStatus.replace('\n', ' ') + "; vision on " + m.visionThreads + ", text on " + m.textThreads);
        assertTrue(e.idxStatus, e.idxStatus.startsWith("Готово: 20 файлов"));
        assertEquals(20, e.store().count(IndexStore.KIND_PHOTO));
        assertEquals(10, m.visions.size());
        assertEquals(10, m.texts.size());
        assertEquals(0, m.plain);
        assertTrue(m.textThreads + " vs " + m.visionThreads, Collections.disjoint(m.textThreads, m.visionThreads));
        assertTrue("a text stage ran during a vision stage", m.overlapped());
        assertTrue(e.idxStatus, e.idxStatus.contains(" с (одновременно со следующей картинкой)"));
        assertTrue(e.prefs().getStringSet("failed_media", new java.util.HashSet<String>()).isEmpty());

        // 2. A vision stage fails once: that batch goes the usual way (both stages here), the rest through the pipeline
        m = new Staged(20, 20);
        m.failVisionAt = 2;
        ready(e, m);
        indexAll(e);
        System.out.println("vision stage failed once: " + e.idxStatus.replace('\n', ' ') + "; usual way " + m.plain);
        assertTrue(e.idxStatus, e.idxStatus.startsWith("Готово: 20 файлов"));
        assertEquals(20, e.store().count(IndexStore.KIND_PHOTO));
        assertEquals(1, m.plain);
        assertTrue(e.prefs().getStringSet("failed_media", new java.util.HashSet<String>()).isEmpty());

        // 3. A text stage finds the model closed (it was replaced): its photos go back into the queue, none failed
        m = new Staged(20, 20);
        m.closedTextAt = 1;
        ready(e, m);
        indexAll(e);
        System.out.println("text stage on a closed model: " + e.idxStatus.replace('\n', ' '));
        assertTrue(e.idxStatus, e.idxStatus.startsWith("Готово: 20 файлов"));
        assertEquals(20, e.store().count(IndexStore.KIND_PHOTO));
        assertEquals(11, m.visions.size());
        assertTrue(e.prefs().getStringSet("failed_media", new java.util.HashSet<String>()).isEmpty());

        // 4. The accelerator check measures the pipeline: with both stages at 120 ms a batch of 2 is done every
        //    ~120 ms (60 ms per photo), not every 240 ms
        m = new Staged(120, 120);
        Class<?> measureClass = Class.forName("io.github.teoplaydor.semsearch.app.Engine$Measure");
        java.lang.reflect.Constructor<?> mk = measureClass.getDeclaredConstructor();
        mk.setAccessible(true);
        Object r = mk.newInstance();
        java.lang.reflect.Method mp = Engine.class.getDeclaredMethod("measurePipeline", Embedder.Staged.class, List.class,
                int.class, int.class, measureClass);
        mp.setAccessible(true);
        List<ImagePreprocessor.Source> imgs = new ArrayList<ImagePreprocessor.Source>();
        for (int k = 0; k < 2; k++) imgs.add(new PatternSource(64, 48, 2 + k));
        mp.invoke(e, m, imgs, 2, 70, r);
        java.lang.reflect.Field per = measureClass.getDeclaredField("perPhotoMs");
        per.setAccessible(true);
        java.lang.reflect.Method time = measureClass.getDeclaredMethod("time");
        time.setAccessible(true);
        String shown = (String) time.invoke(r);
        System.out.println("check in the pipeline: " + shown + ", " + m.visions.size() + " vision runs");
        assertTrue(shown, per.getDouble(r) >= 55 && per.getDouble(r) < 95);
        assertTrue(shown, shown.endsWith("(0.06 + 0.06, конвейер)"));
        assertEquals(4, m.visions.size());
        a.finish();
    }
}
