import static org.junit.Assert.*;

import android.app.Application;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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
 * The photo viewer: "Что на фото" lists the vocabulary's words the photo matches better than the gallery (a dog
 * photo: «собака»), a word is a search; a swipe up or down closes the viewer (the photo goes back into its tile),
 * a short one lets the photo come back to the middle.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "ru-w411dp-h891dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ViewerTest {
    static void swipe(View v, float dy) {
        long t = SystemClock.uptimeMillis();
        float x = v.getWidth() / 2f, y = v.getHeight() / 2f;
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0));
        int steps = 12;
        for (int i = 1; i <= steps; i++) {
            v.dispatchTouchEvent(MotionEvent.obtain(t, t + i * 16, MotionEvent.ACTION_MOVE, x, y + dy * i / steps, 0));
        }
        // slow at the end: the distance decides, not the speed
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + steps * 16 + 400, MotionEvent.ACTION_MOVE, x, y + dy, 0));
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + steps * 16 + 800, MotionEvent.ACTION_UP, x, y + dy, 0));
    }

    static TextView textView(View root, String text) {
        for (View v : Robo.views(root, new ArrayList<View>())) {
            if (v instanceof TextView && text.contentEquals(((TextView) v).getText())) return (TextView) v;
        }
        return null;
    }

    @Test
    public void tagsAndSwipes() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        File dir = new File(app.getCacheDir(), "media");
        assertTrue(dir.mkdirs());
        for (int i = 0; i < 18; i++) {
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
        // photos of dogs, beaches, cats, cars, food and cities (the stand-in embeds a picture as its word)
        e.attachModelForTest(new Robo.FakeEmbedder("dog", "beach", "cat", "car", "food", "city"));
        Robo.waitFor("ready", e::ready);
        e.startIndex(1000, 0);
        Robo.waitFor("indexed", () -> !e.indexing && e.idxTotal > 0);
        assertEquals(18, e.store().count(IndexStore.KIND_PHOTO));
        a.onEngineChanged();
        Robo.settle(800);
        View root = a.getWindow().getDecorView();
        @SuppressWarnings("unchecked")
        List<IndexStore.Item> shown = (List<IndexStore.Item>) Robo.call(Robo.field(a, "gallery"), "items");
        int dogAt = -1;
        float[] dog = Robo.bag("dog");
        for (int i = 0; i < shown.size() && dogAt < 0; i++) if (Arrays.equals(shown.get(i).emb, dog)) dogAt = i;
        assertTrue("a dog photo in the grid", dogAt >= 0);

        // "Что на фото": the words, «собака» among them, «кошка» not
        Robo.call(a, "openViewer", dogAt);
        Robo.settle(700);
        View viewer = Robo.byName(root, "Viewer");
        assertNotNull(viewer);
        TextView button = textView((View) Robo.field(viewer, "actions"), "Что на фото");
        assertNotNull(Robo.allText(viewer), button);
        ((View) button.getParent()).performClick();
        try {
            Robo.waitFor("words", () -> ((ViewGroup) Robo.field(viewer, "tagsRow")).getChildCount() > 0
                    || !String.valueOf(((TextView) Robo.field(viewer, "tagsNote")).getText()).startsWith("Подбираю"));
        } finally {
            System.out.println("tags panel: visible " + (((View) Robo.field(viewer, "tagsBox")).getVisibility() == View.VISIBLE) + ", note: "
                    + ((TextView) Robo.field(viewer, "tagsNote")).getText());
        }
        Robo.settle(200);
        ViewGroup row = (ViewGroup) Robo.field(viewer, "tagsRow");
        List<String> words = new ArrayList<String>();
        for (int i = 0; i < row.getChildCount(); i++) words.add(String.valueOf(((TextView) row.getChildAt(i)).getText()));
        System.out.println("what is on the dog photo: " + words + " — " + ((TextView) Robo.field(viewer, "tagsNote")).getText());
        assertTrue(words.toString(), words.size() >= 1 && words.get(0).equals("собака") && !words.contains("кошка"));
        assertTrue(new File(a.getFilesDir(), "photo_tags.bin").exists());

        // a word is a search: the viewer goes, the results come
        row.getChildAt(0).performClick();
        Robo.waitFor("search", () -> String.valueOf(((TextView) Robo.field(a, "section")).getText()).startsWith("«собака»"));
        Robo.settle(700);
        assertNull(Robo.byName(root, "Viewer"));
        System.out.println("search from the word: " + ((TextView) Robo.field(a, "section")).getText());

        // a short swipe up: the photo comes back to the middle; a long one up — the viewer closes; the same down
        a.onBackPressed();
        Robo.settle(500);
        for (float sign : new float[]{-1f, 1f}) {
            Robo.call(a, "openViewer", 0);
            Robo.settle(700);
            View v = Robo.byName(root, "Viewer");
            View pager = (View) Robo.field(v, "pager");
            Object[] pages = (Object[]) Robo.field(v, "pages");
            View page = (View) pages[1];
            swipe(pager, sign * pager.getHeight() / 12f);
            Robo.settle(500);
            assertNotNull("a short swipe " + (sign < 0 ? "up" : "down") + " leaves it open", Robo.byName(root, "Viewer"));
            assertEquals(0f, page.getTranslationY(), 0.5f);
            assertEquals(1f, page.getScaleX(), 0.01f);
            swipe(pager, sign * pager.getHeight() / 3f);
            Robo.settle(800);
            assertNull("a long swipe " + (sign < 0 ? "up" : "down") + " closes it", Robo.byName(root, "Viewer"));
        }
        a.finish();
    }
}
