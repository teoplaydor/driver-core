import static org.junit.Assert.*;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import io.github.teoplaydor.semsearch.app.Engine;
import io.github.teoplaydor.semsearch.app.IndexStore;
import io.github.teoplaydor.semsearch.app.MainActivity;

/**
 * A document photographed on a table: the viewer offers «Скан для печати» for it (its words are a document's) and not
 * for a dog; the scan cuts the sheet out to its proportions in strict black and white, colour on request, and saves it;
 * «Назад» comes back to the photo.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "ru-w411dp-h891dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScanTest {
    /** A sheet (A4 proportions) with lines of words, turned 7° on a dark table. */
    static File photo(File dir) throws Exception {
        Bitmap b = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.rgb(70, 52, 38));
        Paint p = new Paint();
        c.save();
        c.translate(600, 450);
        c.rotate(7);
        c.translate(-280, -396);
        p.setColor(Color.rgb(236, 234, 228));
        c.drawRect(0, 0, 560, 792, p);
        p.setColor(Color.rgb(35, 35, 40));
        Random r = new Random(2);
        for (int y = 70; y < 700; y += 24) {
            int x = 50;
            while (x < 480) {
                int w = 10 + r.nextInt(40);
                c.drawRect(x, y, x + w, y + 9, p);
                x += w + 8;
            }
        }
        c.restore();
        File f = new File(dir, "doc.png");
        try (FileOutputStream o = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
        return f;
    }

    static View pills(View viewer) throws Exception {
        return (View) Robo.field(viewer, "facesRow");
    }

    @SuppressWarnings("unchecked")
    static List<IndexStore.Item> grid(MainActivity a) throws Exception {
        return (List<IndexStore.Item>) Robo.call(Robo.field(a, "gallery"), "items");
    }

    static int at(MainActivity a, String concept) throws Exception {
        float[] v = Robo.bag(concept);
        List<IndexStore.Item> g = grid(a);
        for (int i = 0; i < g.size(); i++) if (Arrays.equals(g.get(i).emb, v)) return i;
        return -1;
    }

    @Test
    public void documentScan() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        File dir = new File(app.getCacheDir(), "media");
        assertTrue(dir.mkdirs());
        File doc = photo(dir);
        for (int i = 0; i < 12; i++) {
            FakeMediaStore.ROWS.add(new FakeMediaStore.Row(100 + i, false, 1700000000L - i, "IMG_" + i + ".png", 1200, 900, doc));
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
        // what the photo model sees: a paper document with text on two of them, other things on the rest
        e.attachModelForTest(new Robo.FakeEmbedder("paper document text", "dog", "beach", "cat", "car", "city"));
        Robo.waitFor("ready", e::ready);
        e.startIndex(1000, 0);
        Robo.waitFor("indexed", () -> !e.indexing && e.idxTotal > 0);
        a.onEngineChanged();
        Robo.settle(800);
        final View root = a.getWindow().getDecorView();

        // a dog: no scan offered
        Robo.call(a, "openViewer", at(a, "dog"));
        Robo.settle(700);
        final View dogViewer = Robo.byName(root, "Viewer");
        Robo.waitFor("the dog's row", () -> Robo.textView(pills(dogViewer), "Кто это") != null);
        Robo.settle(1500);
        assertNull("no scan for a dog", Robo.textView(pills(dogViewer), "Скан для печати"));
        a.onBackPressed();
        Robo.settle(600);

        // the document: offered, opened
        int d = at(a, "paper document text");
        Robo.call(a, "openViewer", d);
        Robo.settle(700);
        final View viewer = Robo.byName(root, "Viewer");
        Robo.waitFor("the scan offered", () -> Robo.textView(pills(viewer), "Скан для печати") != null);
        Robo.settle(300);
        UiShots.shot(a, "15a-document-offer");
        ((View) Robo.textView(pills(viewer), "Скан для печати").getParent()).performClick();
        Robo.settle(300);
        final View panel = Robo.byName(root, "ScanPanel");
        assertNotNull(panel);
        Robo.waitFor("the scan", () -> Robo.field(panel, "result") != null && !(Boolean) Robo.field(panel, "busy"));
        Robo.settle(300);
        String status = String.valueOf(((TextView) Robo.field(panel, "status")).getText());
        Bitmap bw = (Bitmap) Robo.field(panel, "result");
        System.out.println("scan: " + status + " — " + bw.getWidth() + "x" + bw.getHeight());
        UiShots.shot(a, "15b-scan");
        double aspect = (double) bw.getHeight() / bw.getWidth();
        assertTrue(status, status.startsWith("Лист вырезан и выпрямлен"));
        assertEquals("the sheet's proportions", 792.0 / 560, aspect, 0.06);
        assertTrue("a small sheet enlarged to a print grid: " + bw.getHeight(), Math.max(bw.getWidth(), bw.getHeight()) >= 2480);
        int[] px = new int[bw.getWidth() * bw.getHeight()];
        bw.getPixels(px, 0, bw.getWidth(), 0, 0, bw.getWidth(), bw.getHeight());
        int black = 0, white = 0;
        for (int p : px) {
            if ((p & 0xFFFFFF) == 0) black++;
            else if ((p & 0xFFFFFF) == 0xFFFFFF) white++;
        }
        assertEquals("strict black and white", px.length, black + white);
        assertTrue("the words are there: " + black, black > px.length / 50 && black < px.length / 3);

        // the corners by hand: the editor over the photo, a corner dragged, «Готово»
        Robo.textView(panel, "Края…").performClick();
        Robo.settle(400);
        View editor = (View) Robo.field(panel, "editor");
        assertEquals(View.VISIBLE, editor.getVisibility());
        UiShots.shot(a, "15c-scan-edges");
        float[] was = (float[]) Robo.call(editor, "corners");
        Robo.call(editor, "setCorner", 0, was[0] + 20f, was[1] + 20f);
        Robo.textView(panel, "Готово").performClick();
        Robo.waitFor("by hand", () -> !(Boolean) Robo.field(panel, "busy")
                && String.valueOf(((TextView) Robo.field(panel, "status")).getText()).startsWith("Края заданы вручную"));
        assertEquals(View.GONE, editor.getVisibility());
        final Bitmap byHand = (Bitmap) Robo.field(panel, "result");

        // colour on request
        ((TextView[]) Robo.field(panel, "modeChips"))[2].performClick();
        Robo.waitFor("colour", () -> !(Boolean) Robo.field(panel, "busy") && Robo.field(panel, "result") != byHand);
        Bitmap color = (Bitmap) Robo.field(panel, "result");
        int[] cp = new int[color.getWidth() * color.getHeight()];
        color.getPixels(cp, 0, color.getWidth(), 0, 0, color.getWidth(), color.getHeight());
        int grey = 0;
        for (int p : cp) if ((p & 0xFFFFFF) != 0 && (p & 0xFFFFFF) != 0xFFFFFF) grey++;
        assertTrue("colour keeps the shades", grey > cp.length / 100);

        // saved (here into the app's Pictures: the stand-in gallery takes no new files)
        Robo.textView(panel, "Сохранить").performClick();
        Robo.waitFor("saved", () -> Robo.field(panel, "savedFile") != null);
        File saved = (File) Robo.field(panel, "savedFile");
        System.out.println("saved: " + saved + " (" + saved.length() + " bytes)");
        assertTrue(saved.length() > 1000 && saved.getName().endsWith(".jpg"));

        // «Назад»: the photo again
        a.onBackPressed();
        Robo.settle(600);
        assertNull(Robo.byName(root, "ScanPanel"));
        assertNotNull("the viewer under it", Robo.byName(root, "Viewer"));
        a.onBackPressed();
        Robo.settle(600);
        a.finish();
    }
}
