import static org.junit.Assert.*;

import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Random;

import io.github.teoplaydor.semsearch.app.Engine;
import io.github.teoplaydor.semsearch.app.IndexStore;
import io.github.teoplaydor.semsearch.app.MainActivity;

/**
 * The real Activity on an Android 14 runtime: "no model" flows, the gallery filters, "similar"
 * search into the grid, the viewer, settings, notes (add, delete through the viewer), back
 * navigation, Matryoshka dims and clearing the index. ONNX Runtime can't run in Robolectric, so
 * the model is a stand-in; the ML path is covered by PipelineParityTest on the desktop JVM.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "ru-w411dp-h891dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AppFlowTest {
    @SuppressWarnings("unchecked")
    static List<IndexStore.Item> shown(MainActivity a) throws Exception {
        return (List<IndexStore.Item>) Robo.call(Robo.byName(a.getWindow().getDecorView(), "MasonryView"), "items");
    }

    @Test
    public void galleryFlows() throws Exception {
        FakeMediaStore.ROWS.add(new FakeMediaStore.Row(42, false, 1700000000L, "IMG_42.jpg", 3000, 4000, null));
        FakeMediaStore.ROWS.add(new FakeMediaStore.Row(7, true, 1700000000L, "VID_7.mp4", 1920, 1080, null));
        FakeMediaStore.install();
        final MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        final Engine e = Engine.get(a);
        Robo.waitFor("store", () -> e.store() != null);
        Robo.settle(300);
        View root = a.getWindow().getDecorView();
        assertEquals(Engine.State.NO_MODEL, e.state);
        assertTrue(Robo.allText(root), Robo.allText(root).contains("Скачать модель"));

        // "no model" flows explain what to do and don't crash
        Robo.call(a, "noteEditor");
        assertEquals("Сначала скачайте модель", ShadowToast.getTextOfLatestToast());
        Robo.call(a, "requestMediaAndIndex");
        assertEquals("Сначала скачайте модель", ShadowToast.getTextOfLatestToast());
        Robo.call(a, "runBenchmark");
        assertEquals("Сначала скачайте модель", ShadowToast.getTextOfLatestToast());
        EditText q = (EditText) Robo.field(a, "query");
        q.setText("кот");
        Robo.call(a, "runSearch", true);
        assertEquals("Сначала скачайте модель", ShadowToast.getTextOfLatestToast());
        q.setText("");

        // model in place (EmbeddingGemma 2 for everything, as on an existing install)
        e.prefs().edit().putInt("photo_model", 0).apply();
        File model = new File(a.getFilesDir(), "model");
        assertTrue(model.mkdirs());
        try (FileOutputStream o = new FileOutputStream(new File(model, "manifest.json"))) {
            o.write("{}".getBytes("UTF-8"));
        }
        e.attachModelForTest(new Robo.FakeEmbedder());
        Robo.waitFor("ready", e::ready);
        Robo.settle(300);
        assertTrue(Robo.allText(root), Robo.allText(root).contains("Галерея ещё не проиндексирована"));

        // fill the index as the indexer would, drive the gallery from it
        IndexStore s = e.store();
        Random rnd = new Random(1);
        String[] notes = {"Пароль от Wi-Fi", "Рецепт сырников", "Запись к стоматологу"};
        for (int i = 0; i < notes.length; i++) s.add(IndexStore.KIND_NOTE, -1, null, null, notes[i], 1_700_000_000_000L + i, Robo.random(rnd));
        s.add(IndexStore.KIND_PHOTO, 42, "content://media/external/images/media/42", "IMG_42.jpg", null, 1_700_000_000_000L, Robo.random(rnd));
        s.add(IndexStore.KIND_VIDEO, 7, "content://media/external/video/media/7", "VID_7.mp4", null, 1_600_000_000_000L, Robo.random(rnd));
        a.onEngineChanged();
        Robo.settle(400);
        assertEquals(2, shown(a).size()); // "Все": photos and videos, newest first
        assertEquals("IMG_42.jpg", shown(a).get(0).title);
        int[] expect = {1, 1, 3};
        for (int f = 1; f <= 3; f++) {
            Robo.call(a, "selectFilter", f);
            Robo.settle(300);
            assertEquals("filter " + f, expect[f - 1], shown(a).size());
        }
        assertEquals(IndexStore.KIND_NOTE, shown(a).get(0).kind);
        // pictures are laid out in their proportions: the 3:4 photo is taller than wide
        Robo.call(a, "selectFilter", 1);
        Robo.settle(300);
        View tile = Robo.byName(root, "Tile");
        assertNotNull(tile);
        assertEquals(4f / 3f, tile.getHeight() / (float) tile.getWidth(), 0.02f);
        Robo.call(a, "selectFilter", 0);
        Robo.settle(300);

        // "similar" from a note: everything else, as a grid with a label
        Robo.call(a, "runSimilar", s.notes().get(0));
        Robo.waitFor("results", () -> String.valueOf(((TextView) Robo.field(a, "section")).getText()).startsWith("Похожие ·"));
        Robo.settle(300);
        assertEquals(4, shown(a).size());
        assertEquals("Похожие · 4", ((TextView) Robo.field(a, "section")).getText().toString());

        // the viewer opens over the grid and closes with back
        Robo.call(a, "openViewer", 0);
        Robo.settle(600);
        assertNotNull(Robo.byName(root, "Viewer"));
        a.onBackPressed();
        Robo.settle(600);
        assertNull(Robo.byName(root, "Viewer"));
        // back again leaves the results for the gallery
        a.onBackPressed();
        Robo.settle(400);
        assertEquals(2, shown(a).size());
        assertEquals(View.GONE, ((TextView) Robo.field(a, "section")).getVisibility());

        // settings slide in and out
        Robo.call(a, "openSettings");
        Robo.settle(500);
        assertNotNull(Robo.byName(root, "SettingsPanel"));
        assertTrue(Robo.allText(root), Robo.allText(root).contains("Новые фото — автоматически"));
        a.onBackPressed();
        Robo.settle(500);
        assertNull(Robo.byName(root, "SettingsPanel"));

        // notes: delete one through the viewer's confirm sheet
        Robo.call(a, "selectFilter", 3);
        Robo.settle(300);
        IndexStore.Item first = shown(a).get(0);
        Robo.call(a, "openViewer", 0);
        Robo.settle(600);
        Robo.call(a, "delete", first);
        Robo.settle(500);
        TextView confirm = null;
        for (View v : Robo.views(Robo.byName(root, "Sheet"), new java.util.ArrayList<View>())) {
            if (v instanceof TextView && "Удалить".contentEquals(((TextView) v).getText())) confirm = (TextView) v;
        }
        assertNotNull(confirm);
        confirm.performClick();
        Robo.waitFor("delete", () -> s.count(IndexStore.KIND_NOTE) == 2);
        Robo.settle(700);
        assertNull(Robo.byName(root, "Viewer"));
        assertEquals(2, shown(a).size());

        // a new note through the engine shows up in the notes tab
        final Object[] added = new Object[1];
        e.addNote("Код домофона: 47К1290", (it, err) -> added[0] = err != null ? err : it);
        Robo.waitFor("note", () -> added[0] != null);
        assertTrue(String.valueOf(added[0]), added[0] instanceof IndexStore.Item);
        Robo.call(a, "showRecent", false);
        assertEquals(3, shown(a).size());

        // Matryoshka dims are honoured by the store
        assertEquals(5, s.search(Robo.random(new Random(2)), 128, true, true, true, 10, -1).size());
        assertEquals(3, s.search(Robo.random(new Random(2)), 256, false, false, true, 10, -1).size());

        // clearing the media index keeps the notes
        e.clearMediaIndex();
        Robo.waitFor("clear", () -> s.count(IndexStore.KIND_PHOTO) == 0 && s.count(IndexStore.KIND_VIDEO) == 0);
        assertFalse(s.hasMedia(IndexStore.KIND_PHOTO, 42));
        assertEquals(3, s.count(IndexStore.KIND_NOTE));

        // Regression (0.6.0): the automatic first accelerator check of the fast model runs without a
        // callback, and posting its report crashed the app right after the model loaded. Whatever happens
        // inside (here ONNX Runtime can't even start), it must end quietly with a report and an error state.
        e.prefs().edit().putInt("photo_model", 1).apply();
        File fast = new File(a.getFilesDir(), "siglip-b16");
        assertTrue(fast.mkdirs());
        try (FileOutputStream o = new FileOutputStream(new File(fast, "manifest.json"))) {
            o.write(("{\"repo\":\"test\",\"text\":\"onnx/text_model_quantized.onnx\","
                    + "\"vision\":\"onnx/vision_model_quantized.onnx\",\"files\":[]}").getBytes("UTF-8"));
        }
        e.checkFast(null);
        Robo.waitFor("check and reload", () -> e.state == Engine.State.ERROR);
        Robo.settle(500);
        assertNotNull(e.fastReport());
        System.out.println("auto-check report: " + e.fastReport().replace('\n', ' '));

        // Two crashes in a row while loading: no automatic third attempt, an explanation instead.
        e.prefs().edit().putInt("crash_streak", 2).putString("died_during", "загрузка SigLIP 2 B/16 (Процессор)").apply();
        e.state = Engine.State.NO_MODEL;
        e.ensureLoaded();
        Robo.settle(200);
        assertEquals(Engine.State.ERROR, e.state);
        assertTrue(e.status, e.status.contains("закрывалось при загрузке"));
        assertTrue(e.errorDetails, e.errorDetails.contains("Шаг в момент сбоя: загрузка SigLIP 2 B/16"));
        a.finish();
    }
}
