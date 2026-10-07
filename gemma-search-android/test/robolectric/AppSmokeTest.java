import static org.junit.Assert.*;

import android.view.View;
import android.view.ViewGroup;
import android.widget.GridView;
import android.widget.ListView;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.lang.reflect.Method;
import java.util.*;

import io.github.teoplaydor.semsearch.app.Engine;
import io.github.teoplaydor.semsearch.app.IndexStore;
import io.github.teoplaydor.semsearch.app.MainActivity;

/**
 * Smoke test of the real app code on a Robolectric Android runtime (no model: ONNX Runtime's
 * native library can't run inside Robolectric's sandbox; the ML path is covered by
 * PipelineParityTest on the desktop JVM). Builds the UI, walks all tabs and the "no model"
 * flows, then fills the SQLite index directly and drives "similar" search into the result grid
 * and the notes list.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27, manifest = "AndroidManifest.xml")
public class AppSmokeTest {
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

    static List<View> views(View v, List<View> out) {
        out.add(v);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) views(((ViewGroup) v).getChildAt(i), out);
        return out;
    }

    static String allText(View root) {
        StringBuilder sb = new StringBuilder();
        for (View v : views(root, new ArrayList<View>())) {
            if (v instanceof TextView && v.isShown()) sb.append(((TextView) v).getText()).append(" | ");
        }
        return sb.toString();
    }

    static Object call(Object o, String name, Object... args) throws Exception {
        for (Method m : o.getClass().getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == args.length) {
                m.setAccessible(true);
                return m.invoke(o, args);
            }
        }
        throw new NoSuchMethodException(name);
    }

    static void layout(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2200, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 2200);
    }

    @Test
    public void uiAndIndexWithoutModel() throws Exception {
        MainActivity a = Robolectric.setupActivity(MainActivity.class);
        Engine e = Engine.get(a);
        waitFor("store", () -> e.store() != null);
        View root = a.getWindow().getDecorView();
        assertEquals(Engine.State.NO_MODEL, e.state);
        assertTrue(allText(root).contains("Модель не скачана"));

        // Walk every tab; each must render.
        for (int i = 0; i < 4; i++) {
            call(a, "selectTab", i);
            layout(root);
            System.out.println("tab " + i + ": " + allText(root).substring(0, Math.min(160, allText(root).length())));
        }

        // "No model" flows must not crash and must explain what to do.
        call(a, "selectTab", 2);
        call(a, "addSamples");
        assertEquals("Сначала скачайте модель", ShadowToast.getTextOfLatestToast());
        call(a, "requestMediaAndIndex");
        assertEquals("Сначала скачайте модель", ShadowToast.getTextOfLatestToast());

        // Fill the index directly (as the indexer would) and drive the UI from it.
        IndexStore s = e.store();
        Random rnd = new Random(1);
        String[] notes = {"Пароль от Wi-Fi", "Рецепт сырников", "Запись к стоматологу"};
        for (String n : notes) s.add(IndexStore.KIND_NOTE, -1, null, null, n, System.currentTimeMillis(), vec(rnd));
        s.add(IndexStore.KIND_PHOTO, 42, "content://media/external/images/media/42", "IMG_42.jpg", null, 0, vec(rnd));
        s.add(IndexStore.KIND_VIDEO, 7, "content://media/external/video/media/7", "VID_7.mp4", null, 0, vec(rnd));
        assertTrue(s.hasMedia(IndexStore.KIND_PHOTO, 42));
        call(a, "refresh");
        call(a, "selectTab", 1);
        layout(root);
        assertTrue(allText(root), allText(root).contains("Фото: 1   Видео: 1   Заметки: 3"));

        // Notes list shows the three notes.
        call(a, "selectTab", 2);
        layout(root);
        ListView list = null;
        for (View v : views(root, new ArrayList<View>())) if (v instanceof ListView) list = (ListView) v;
        assertNotNull(list);
        assertEquals(3, list.getAdapter().getCount());

        // "Similar" search from a note → result grid with notes + media tiles.
        call(a, "runSimilar", s.notes().get(0));
        GridView grid = null;
        for (View v : views(root, new ArrayList<View>())) if (v instanceof GridView) grid = (GridView) v;
        final GridView g = grid;
        assertNotNull(g);
        waitFor("results", () -> g.getAdapter().getCount() == 4);
        layout(root);
        for (int i = 0; i < g.getAdapter().getCount(); i++) g.getAdapter().getView(i, null, g);
        String txt = allText(root);
        assertTrue(txt, txt.contains("похожие · топ-4 из 5"));
        System.out.println("status: " + txt.substring(txt.indexOf("похожие"), txt.indexOf("похожие") + 40));

        // Matryoshka dims setting is honoured by the store.
        assertEquals(5, s.search(vec(new Random(2)), 128, true, true, true, 10, -1).size());
        assertEquals(3, s.search(vec(new Random(2)), 256, false, false, true, 10, -1).size());

        // Delete + clear media.
        e.deleteItem(s.notes().get(0));
        waitFor("delete", () -> s.count(IndexStore.KIND_NOTE) == 2);
        e.clearMediaIndex();
        waitFor("clear", () -> s.count(IndexStore.KIND_PHOTO) == 0 && s.count(IndexStore.KIND_VIDEO) == 0);
        assertFalse(s.hasMedia(IndexStore.KIND_PHOTO, 42));
        assertEquals(2, s.count(IndexStore.KIND_NOTE));
        a.finish();
    }

    static float[] vec(Random r) {
        float[] v = new float[768];
        for (int i = 0; i < v.length; i++) v[i] = (float) r.nextGaussian();
        io.github.teoplaydor.semsearch.core.VectorMath.normalize(v);
        return v;
    }
}
