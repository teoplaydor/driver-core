import static org.junit.Assert.*;

import android.app.Application;
import android.view.View;
import android.widget.TextView;

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
import java.util.Arrays;
import java.util.List;

import io.github.teoplaydor.semsearch.app.Engine;
import io.github.teoplaydor.semsearch.app.IndexStore;
import io.github.teoplaydor.semsearch.app.MainActivity;

/**
 * Hiding 18+: with «Скрывать 18+» on in the settings, the nude photos leave the gallery, search and albums for the
 * folder «Скрытое»; a hidden photo comes back with «Вернуть» in the viewer and an ordinary one goes with «Скрыть»,
 * and these choices outlast the filter, the switch and a restart; off, everything is back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "ru-w411dp-h891dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HiddenTest {
    static final float[] NUDITY = Robo.bag("nudity"), NUDE_BODY = Robo.bag("nude body");

    static boolean nude(IndexStore.Item it) {
        return Arrays.equals(it.emb, NUDITY) || Arrays.equals(it.emb, NUDE_BODY);
    }

    static int nudeIn(List<IndexStore.Item> items) {
        int n = 0;
        for (IndexStore.Item it : items) if (nude(it)) n++;
        return n;
    }

    @SuppressWarnings("unchecked")
    static List<IndexStore.Item> grid(MainActivity a) throws Exception {
        return (List<IndexStore.Item>) Robo.call(Robo.field(a, "gallery"), "items");
    }

    static String section(MainActivity a) throws Exception {
        return String.valueOf(((TextView) Robo.field(a, "section")).getText());
    }

    /** Opens the viewer on this item of the grid and taps its action. */
    static void viewerAction(MainActivity a, View root, int pos, String label, String shot) throws Exception {
        Robo.call(a, "openViewer", pos);
        Robo.settle(700);
        View viewer = Robo.byName(root, "Viewer");
        assertNotNull(viewer);
        if (shot != null) UiShots.shot(a, shot);
        TextView t = Robo.textView((View) Robo.field(viewer, "actions"), label);
        assertNotNull(label + " in " + Robo.allText(viewer), t);
        ((View) t.getParent()).performClick();
        Robo.settle(900);
        assertNull("the viewer closes", Robo.byName(root, "Viewer"));
    }

    @Test
    public void hideAdult() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        File dir = new File(app.getCacheDir(), "media");
        assertTrue(dir.mkdirs());
        for (int i = 0; i < 30; i++) {
            FakeMediaStore.ROWS.add(new FakeMediaStore.Row(100 + i, false, 1700000000L - i, "IMG_" + i + ".png", 64, 48,
                    AppIndexingTest.png(dir, i % 6)));
        }
        FakeMediaStore.install();
        Shadows.shadowOf(app).grantPermissions("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED");
        final MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        final Engine e = Engine.get(a);
        Robo.waitFor("store", () -> e.store() != null);
        e.prefs().edit().putInt("photo_model", 0).putInt("photo_detail", 0).apply();
        // the model "downloaded" (an empty file list), so the screens are those of a working app
        File model = new File(a.getFilesDir(), "model");
        assertTrue(model.mkdirs());
        java.nio.file.Files.write(new File(model, "manifest.json").toPath(), "{\"repo\":\"test\",\"files\":[]}".getBytes("UTF-8"));
        // ordinary photos (people and a beach in swimsuits among them) and six nude ones
        e.attachModelForTest(new Robo.FakeEmbedder("dog", "beach", "cat", "car", "food", "city", "woman portrait",
                "swimsuit beach", "nudity", "nude body"));
        Robo.waitFor("ready", e::ready);
        e.startIndex(1000, 0);
        Robo.waitFor("indexed", () -> !e.indexing && e.idxTotal > 0);
        assertEquals(30, e.store().count(IndexStore.KIND_PHOTO));
        a.onEngineChanged();
        Robo.settle(800);
        View root = a.getWindow().getDecorView();
        assertEquals(30, grid(a).size());
        assertEquals(6, nudeIn(grid(a)));

        // the switch in the settings: the six nude photos go, nothing else
        Robo.call(a, "openSettings");
        Robo.settle(600);
        Object settings = Robo.byName(root, "SettingsPanel");
        View toggle = (View) Robo.field(settings, "adultToggle");
        assertEquals(View.GONE, ((View) ((View) Robo.field(settings, "hiddenValue")).getParent()).getVisibility());
        toggle.performClick();
        final TextView note = (TextView) Robo.field(settings, "adultNote");
        Robo.waitFor("checked", () -> String.valueOf(note.getText()).startsWith("Скрыто"));
        Robo.settle(300);
        System.out.println("settings: " + note.getText());
        assertTrue(String.valueOf(note.getText()), String.valueOf(note.getText()).startsWith("Скрыто: 6."));
        TextView hiddenValue = (TextView) Robo.field(settings, "hiddenValue");
        assertEquals("6", String.valueOf(hiddenValue.getText()));
        assertEquals(View.VISIBLE, ((View) hiddenValue.getParent()).getVisibility());
        // the card in view for the screenshot
        android.widget.ScrollView sv = (android.widget.ScrollView) ((View) Robo.field(settings, "list")).getParent();
        View card = (View) ((View) toggle.getParent()).getParent();
        sv.scrollTo(0, Math.max(0, card.getTop() - 200));
        Robo.settle(200);
        UiShots.shot(a, "09c-settings-adult");
        Robo.call(settings, "close");
        Robo.settle(600);
        assertEquals(24, grid(a).size());
        assertEquals("the gallery: no nude photo", 0, nudeIn(grid(a)));

        // search and albums: none of them either
        final List<IndexStore.Hit>[] hits = new List[1];
        e.search("nudity", true, true, false, (r, err) -> {
            List<IndexStore.Hit> all = new ArrayList<IndexStore.Hit>(r.hits);
            all.addAll(r.more);
            hits[0] = all;
        });
        Robo.waitFor("search", () -> hits[0] != null);
        for (IndexStore.Hit h : hits[0]) assertFalse("a search finds no hidden photo", nude(h.item));
        final List<Engine.Album>[] albums = new List[1];
        e.albums((r, err) -> albums[0] = r);
        Robo.waitFor("albums", () -> albums[0] != null);
        for (Engine.Album al : albums[0]) assertEquals(al.name, 0, nudeIn(al.items));

        // the folder: from the albums sheet, a padlock row
        Robo.call(a, "showAlbums");
        Robo.waitFor("albums sheet", () -> Robo.textView(Robo.byName(root, "Sheet"), "Скрытое") != null);
        Robo.settle(300);
        System.out.println("albums sheet: " + Robo.allText(Robo.byName(root, "Sheet")).replace('\n', ' '));
        ((View) Robo.textView(Robo.byName(root, "Sheet"), "Скрытое").getParent().getParent()).performClick();
        Robo.settle(700);
        assertEquals("Скрытое · 6", section(a));
        UiShots.shot(a, "07e-hidden-folder");
        assertEquals(6, nudeIn(grid(a)));
        assertEquals(6, grid(a).size());

        // «Вернуть» on one of them: out of the folder, back in the gallery
        IndexStore.Item back = grid(a).get(0);
        viewerAction(a, root, 0, "Вернуть", null);
        Robo.waitFor("folder", () -> section(a).equals("Скрытое · 5"));
        assertFalse(grid(a).contains(back));
        a.onBackPressed();
        Robo.settle(600);
        assertEquals(25, grid(a).size());
        assertTrue(grid(a).contains(back));

        // «Скрыть» on a dog: hidden by hand
        int dogAt = -1;
        float[] dog = Robo.bag("dog");
        for (int i = 0; i < grid(a).size() && dogAt < 0; i++) if (Arrays.equals(grid(a).get(i).emb, dog)) dogAt = i;
        IndexStore.Item dogItem = grid(a).get(dogAt);
        viewerAction(a, root, dogAt, "Скрыть", "07d-viewer-hide");
        Robo.waitFor("hidden by hand", () -> grid(a).size() == 24);
        assertFalse(grid(a).contains(dogItem));
        assertTrue(e.isHidden(dogItem));

        // the filter runs again (a stricter level): the choices made by hand stand
        final Integer[] n = new Integer[1];
        e.setAdultLevel(2, (r, err) -> n[0] = r);
        Robo.waitFor("strict", () -> n[0] != null);
        System.out.println("strict: " + n[0] + " hidden");
        assertTrue(e.isHidden(dogItem));
        assertFalse(e.isHidden(back));
        e.setAdultLevel(1, null);

        // after a restart (the sets read back from the settings): the same
        IndexStore fresh = new IndexStore(a);
        for (String f : new String[]{"adultAuto", "adultManual", "adultShown"}) {
            java.lang.reflect.Field fl = Engine.class.getDeclaredField(f);
            fl.setAccessible(true);
            fl.set(e, null);
        }
        Robo.call(e, "applyHidden", fresh);
        List<IndexStore.Item> again = fresh.hiddenItems();
        System.out.println("after a restart: " + again.size() + " hidden");
        assertEquals(6, again.size());
        assertEquals(5, nudeIn(again));

        // off: everything is back
        e.setHideAdult(false, null);
        Robo.settle(800);
        assertEquals(30, grid(a).size());
        assertEquals(0, e.hiddenItems().size());
        a.finish();
    }
}
