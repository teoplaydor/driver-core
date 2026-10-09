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
 * «Назад» comes back to the photo. A bent sheet: «Текст ровно» straightens its lines (off, they stay bent). A page
 * photographed sideways: turned upright by itself, «↻» turns it on; the result zooms (a {@code PhotoView}). A long
 * press in the grid chooses pictures; with documents among them, «Скан в PDF» makes them one PDF, a page each.
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

    /**
     * A sheet with lines of words lying bent on a dark cloth: bowed — more in its middle than its edges show — its lines
     * arcs, its sides bulging, the lines closer together towards the bottom (curling away there).
     */
    static Bitmap bent() {
        Bitmap tex = Bitmap.createBitmap(560, 792, Bitmap.Config.ARGB_8888);
        Canvas t = new Canvas(tex);
        t.drawColor(Color.rgb(236, 234, 228));
        Paint p = new Paint();
        p.setColor(Color.rgb(35, 35, 40));
        Random r = new Random(5);
        for (int y = 70; y < 720; y += 24) {
            int x = 50;
            while (true) {
                int w = 10 + r.nextInt(40);
                if (x + w > 510) {
                    t.drawRect(x, y, 510, y + 9, p);
                    break;
                }
                t.drawRect(x, y, x + w, y + 9, p);
                x += w + 8;
            }
        }
        Bitmap b = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.rgb(70, 52, 38));
        int n = 40;
        float[] v = new float[(n + 1) * (n + 1) * 2];
        int i = 0;
        for (int gy = 0; gy <= n; gy++) {
            double fv = (double) gy / n;
            for (int gx = 0; gx <= n; gx++) {
                double fu = (double) gx / n;
                double bow = (24 + 16 * fv + 36 * Math.sin(Math.PI * fv)) * Math.sin(Math.PI * fu);
                double bulge = 14 * Math.sin(Math.PI * fv) * (fu - 0.5) * 2;
                v[i++] = (float) (340 + 520 * fu + bulge);
                v[i++] = (float) (80 + 740 * (1.12 * fv - 0.12 * fv * fv) - bow);
            }
        }
        c.drawBitmapMesh(tex, n, n, v, 0, null, 0, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        return b;
    }

    /** How sharply the ink falls into rows (straight level lines: high), the sum of squared row counts over the square of all. */
    static double rows(Bitmap bm) {
        int w = bm.getWidth(), h = bm.getHeight();
        int[] px = new int[w * h];
        bm.getPixels(px, 0, w, 0, 0, w, h);
        double sum = 0, sq = 0;
        for (int y = 0; y < h; y++) {
            int c = 0;
            for (int x = 0; x < w; x++) if ((px[y * w + x] & 0xFF) < 128) c++;
            sum += c;
            sq += (double) c * c;
        }
        return sq / (sum * sum) * h;
    }

    static String status(View panel) throws Exception {
        return String.valueOf(((TextView) Robo.field(panel, "status")).getText());
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
        assertTrue(status, status.startsWith("Документ вырезан и выпрямлен"));
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

        // a bent sheet: flattened between its edges, its lines straightened by «Текст ровно»; off, they stay bent
        final Bitmap bentPhoto = bent();
        try {
            MainActivity.testLoader = (it, size) -> bentPhoto.copy(Bitmap.Config.ARGB_8888, true);
            ((View) Robo.textView(pills(viewer), "Скан для печати").getParent()).performClick();
            Robo.settle(300);
            final View panel2 = Robo.byName(root, "ScanPanel");
            Robo.waitFor("the bent sheet", () -> Robo.field(panel2, "result") != null && !(Boolean) Robo.field(panel2, "busy"));
            Robo.settle(300);
            final Bitmap straight = (Bitmap) Robo.field(panel2, "result");
            String on = status(panel2);
            double rowsOn = rows(straight); // (the bitmap is let go once replaced)
            System.out.println("bent, «Текст ровно»: " + on + " — rows " + rowsOn);
            UiShots.shot(a, "15d-scan-bent");
            assertTrue(on, on.startsWith("Документ вырезан и выпрямлен") && on.contains("строки выпрямлены")
                    && on.contains("интервалы между строками выровнены"));
            Robo.textView(panel2, "Текст ровно").performClick();
            Robo.waitFor("as cut", () -> !(Boolean) Robo.field(panel2, "busy") && Robo.field(panel2, "result") != straight);
            Bitmap asCut = (Bitmap) Robo.field(panel2, "result");
            String off = status(panel2);
            double rowsOff = rows(asCut);
            System.out.println("bent, as cut: " + off + " — rows " + rowsOff);
            UiShots.shot(a, "15e-scan-bent-as-cut");
            assertFalse(off, off.contains("строки"));
            assertTrue("the lines straight with it, bent without: " + rowsOn + " vs " + rowsOff, rowsOn > 1.5 * rowsOff);
            a.onBackPressed();
            Robo.settle(600);
            assertNull(Robo.byName(root, "ScanPanel"));

            // the page photographed sideways: upright by itself; «↻» a quarter turn on
            final Bitmap flat = android.graphics.BitmapFactory.decodeFile(doc.getPath());
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postRotate(90);
            final Bitmap side = Bitmap.createBitmap(flat, 0, 0, flat.getWidth(), flat.getHeight(), m, true);
            MainActivity.testLoader = (it, size) -> side.copy(Bitmap.Config.ARGB_8888, true);
            ((View) Robo.textView(pills(viewer), "Скан для печати").getParent()).performClick();
            Robo.settle(300);
            final View panel3 = Robo.byName(root, "ScanPanel");
            Robo.waitFor("the sideways page", () -> Robo.field(panel3, "result") != null && !(Boolean) Robo.field(panel3, "busy"));
            Robo.settle(300);
            final Bitmap upright = (Bitmap) Robo.field(panel3, "result");
            String turnedNote = status(panel3);
            System.out.println("sideways: " + turnedNote + " — " + upright.getWidth() + "x" + upright.getHeight());
            UiShots.shot(a, "15f-scan-sideways");
            assertTrue(turnedNote, turnedNote.contains("повёрнут на 90°"));
            assertTrue("upright: the sheet standing", upright.getHeight() > upright.getWidth());
            assertEquals("the result zooms", "PhotoView", Robo.field(panel3, "picture").getClass().getSimpleName());
            Robo.textView(panel3, "↻").performClick();
            Robo.waitFor("a quarter on", () -> !(Boolean) Robo.field(panel3, "busy") && Robo.field(panel3, "result") != upright);
            Bitmap lying = (Bitmap) Robo.field(panel3, "result");
            assertTrue("«↻»: lying", lying.getWidth() > lying.getHeight());
            a.onBackPressed();
            Robo.settle(600);
            assertNull(Robo.byName(root, "ScanPanel"));
        } finally {
            MainActivity.testLoader = null;
        }
        a.onBackPressed();
        Robo.settle(600);
        assertNull(Robo.byName(root, "Viewer"));

        // a long press chooses (no longer «similar»): the two documents and a dog, the dog un-chosen again; the PDF
        // offered for the documents, made: a page each, in the order chosen
        final Object grid = Robo.field(a, "gallery");
        List<IndexStore.Item> items = grid(a);
        float[] docVec = Robo.bag("paper document text");
        int d1 = -1, d2 = -1, dog = at(a, "dog");
        for (int i = 0; i < items.size(); i++) {
            if (!Arrays.equals(items.get(i).emb, docVec)) continue;
            if (d1 < 0) d1 = i;
            else if (d2 < 0) d2 = i;
        }
        assertTrue("two documents in the grid", d1 >= 0 && d2 >= 0 && dog >= 0);
        String sectionBefore = String.valueOf(((TextView) Robo.field(a, "section")).getText());
        a.longPress(d1);
        Robo.settle(400);
        assertTrue("choosing", (Boolean) Robo.call(grid, "choosing"));
        assertEquals("not «similar»", sectionBefore, String.valueOf(((TextView) Robo.field(a, "section")).getText()));
        final View bar = (View) Robo.field(a, "chooseBar");
        final TextView count = (TextView) Robo.field(a, "chooseCount");
        assertEquals(View.VISIBLE, bar.getVisibility());
        assertEquals("Выбрано: 1", count.getText().toString());
        a.open(d2);
        a.open(dog);
        Robo.settle(300);
        assertEquals("Выбрано: 3", count.getText().toString());
        a.open(dog);
        Robo.settle(300);
        assertEquals("a tap un-chooses", "Выбрано: 2", count.getText().toString());
        final TextView pdfButton = (TextView) Robo.field(a, "choosePdf");
        Robo.waitFor("the PDF offered", () -> pdfButton.getVisibility() == View.VISIBLE);
        assertEquals("Скан в PDF · 2", pdfButton.getText().toString());
        assertNull("no viewer opened by the taps", Robo.byName(root, "Viewer"));
        UiShots.shot(a, "15g-chosen");
        pdfButton.performClick();
        Robo.settle(300);
        assertFalse("choosing over", (Boolean) Robo.call(grid, "choosing"));
        final Object job = Robo.field(a, "pdfJob");
        Robo.waitFor("the PDF", () -> (Boolean) Robo.field(job, "done"));
        Robo.settle(300);
        UiShots.shot(a, "15h-pdf");
        assertNull("made: " + Robo.field(job, "error"), Robo.field(job, "error"));
        assertEquals(2, Robo.field(job, "pages"));
        File pdf = (File) Robo.field(job, "file");
        assertNotNull("written into the app's Documents (the stand-in gallery takes no new files)", pdf);
        byte[] bytes = java.nio.file.Files.readAllBytes(pdf.toPath());
        String text = new String(bytes, "ISO-8859-1");
        System.out.println("pdf: " + pdf + " (" + bytes.length + " bytes) — " + Robo.allText(root).replace('\n', ' '));
        assertTrue(text.startsWith("%PDF-1.4") && text.contains("/Count 2 >>") && text.trim().endsWith("%%EOF"));
        java.util.regex.Matcher box = java.util.regex.Pattern.compile("/MediaBox \\[0 0 ([0-9.]+) ([0-9.]+)\\]").matcher(text);
        assertTrue(box.find());
        assertEquals("an A4 page (the sheets are A4): width", 595.28, Double.parseDouble(box.group(1)), 0.5);
        assertEquals("height", 841.89, Double.parseDouble(box.group(2)), 2);
        assertNotNull(Robo.textView(root, "Поделиться"));
        a.onBackPressed();
        Robo.settle(600);
        assertEquals(View.GONE, bar.getVisibility());

        // «Назад» while choosing: choosing ends, nothing else
        a.longPress(dog);
        Robo.settle(300);
        assertEquals(View.VISIBLE, bar.getVisibility());
        a.onBackPressed();
        Robo.settle(400);
        assertFalse((Boolean) Robo.call(grid, "choosing"));
        assertEquals(View.GONE, bar.getVisibility());
        a.finish();
    }
}
