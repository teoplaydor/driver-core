import static org.junit.Assert.*;

import android.app.Application;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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
import java.util.List;

import io.github.teoplaydor.semsearch.app.Engine;
import io.github.teoplaydor.semsearch.app.IndexStore;
import io.github.teoplaydor.semsearch.app.MainActivity;

/**
 * «Назад» goes back in time: to where the user was before each tap — the viewer on the photo «Похожие» was asked
 * from, the search the photo was opened from, the albums sheet an album was picked in, the settings the hidden folder
 * was opened from — and only then to the gallery.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "ru-w411dp-h891dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NavTest {
    static String section(MainActivity a) throws Exception {
        View s = (View) Robo.field(a, "section");
        return s.getVisibility() == View.VISIBLE ? String.valueOf(((TextView) s).getText()) : "";
    }

    static String label(MainActivity a) throws Exception {
        return (String) Robo.field(a, "resultsLabel");
    }

    static IndexStore.Item shown(View root) throws Exception {
        View v = Robo.byName(root, "Viewer");
        return v == null ? null : (IndexStore.Item) Robo.call(v, "current");
    }

    @SuppressWarnings("unchecked")
    static List<IndexStore.Item> grid(MainActivity a) throws Exception {
        return (List<IndexStore.Item>) Robo.call(Robo.field(a, "gallery"), "items");
    }

    static void tapAction(View root, String label) throws Exception {
        View viewer = Robo.byName(root, "Viewer");
        ((View) Robo.textView((View) Robo.field(viewer, "actions"), label).getParent()).performClick();
    }

    static void back(MainActivity a, int ms) throws Exception {
        a.onBackPressed();
        Robo.settle(ms);
    }

    @Test
    public void backGoesBackInTime() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        File dir = new File(app.getCacheDir(), "media");
        assertTrue(dir.mkdirs());
        for (int i = 0; i < 24; i++) {
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
        File model = new File(a.getFilesDir(), "model");
        assertTrue(model.mkdirs());
        java.nio.file.Files.write(new File(model, "manifest.json").toPath(), "{\"repo\":\"test\",\"files\":[]}".getBytes("UTF-8"));
        e.attachModelForTest(new Robo.FakeEmbedder("dog", "beach", "cat", "car", "food", "city"));
        Robo.waitFor("ready", e::ready);
        e.startIndex(1000, 0);
        Robo.waitFor("indexed", () -> !e.indexing && e.idxTotal > 0);
        a.onEngineChanged();
        Robo.settle(800);
        final View root = a.getWindow().getDecorView();
        assertNull(label(a));

        // the gallery → a photo → «Похожие»; «Назад»: the photo again, then the gallery
        Robo.call(a, "openViewer", 3);
        Robo.settle(700);
        IndexStore.Item photo = shown(root);
        tapAction(root, "Похожие");
        Robo.waitFor("similar", () -> section(a).startsWith("Похожие ·"));
        Robo.settle(700);
        assertNull(Robo.byName(root, "Viewer"));
        back(a, 900);
        assertEquals("«Назад» from «Похожие»: the photo it was asked from", photo, shown(root));
        assertNull("over the gallery", label(a));
        back(a, 700);
        assertNull(Robo.byName(root, "Viewer"));
        assertNull(label(a));

        // a search → a photo of it → one of its words; «Назад»: the photo over the first search, then that search, then the gallery
        Robo.call(a, "openSearch");
        EditText q = (EditText) Robo.field(a, "query");
        q.setText("собака");
        Robo.call(a, "runSearch", true);
        Robo.waitFor("search", () -> section(a).startsWith("«собака» ·"));
        Robo.settle(700);
        String dogs = section(a);
        Robo.call(a, "openViewer", 0);
        Robo.settle(700);
        IndexStore.Item dog = shown(root);
        Robo.call(a, "searchFor", "кошка");
        Robo.waitFor("second search", () -> section(a).startsWith("«кошка»"));
        Robo.settle(700);
        back(a, 900);
        assertEquals("the photo the word was tapped on", dog, shown(root));
        assertEquals(dogs, section(a));
        assertEquals("собака", q.getText().toString());
        back(a, 700);
        assertNull(Robo.byName(root, "Viewer"));
        assertEquals("the first search", dogs, section(a));
        back(a, 700);
        assertNull("then the gallery", label(a));

        // the albums sheet → an album; «Назад»: the sheet again, then the gallery
        Robo.call(a, "showAlbums");
        Robo.waitFor("albums", () -> Robo.textView(Robo.byName(root, "Sheet"), "Собаки") != null);
        Robo.settle(300);
        ((View) Robo.textView(Robo.byName(root, "Sheet"), "Собаки").getParent().getParent()).performClick();
        Robo.settle(800);
        assertEquals("Альбом «Собаки» · 4", section(a));
        back(a, 300);
        Robo.waitFor("the sheet again", () -> Robo.byName(root, "Sheet") != null
                && Robo.textView(Robo.byName(root, "Sheet"), "Собаки") != null);
        assertNull(label(a));
        back(a, 600);
        assertNull(Robo.byName(root, "Sheet"));

        // the settings → «Скрытое»; «Назад»: the settings again
        e.setHideAdult(true, null);
        Robo.settle(500);
        Robo.call(a, "openSettings");
        Robo.settle(700);
        Object settings = Robo.byName(root, "SettingsPanel");
        ((View) ((View) Robo.field(settings, "hiddenValue")).getParent()).performClick();
        Robo.settle(900);
        assertNull(Robo.byName(root, "SettingsPanel"));
        assertEquals("Скрытое · 0", section(a));
        back(a, 900);
        assertNotNull("«Назад» from the hidden folder: the settings", Robo.byName(root, "SettingsPanel"));
        assertNull(label(a));
        back(a, 600);
        assertNull(Robo.byName(root, "SettingsPanel"));
        assertTrue("nothing left to go back to", ((List<?>) Robo.field(a, "places")).isEmpty());
        a.finish();
    }
}
