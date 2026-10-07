import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ScrollView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.TimeUnit;

import io.github.teoplaydor.semsearch.app.Engine;
import io.github.teoplaydor.semsearch.app.IndexStore;
import io.github.teoplaydor.semsearch.app.MainActivity;
import io.github.teoplaydor.semsearch.core.Embedder;
import io.github.teoplaydor.semsearch.core.ImagePreprocessor;
import io.github.teoplaydor.semsearch.core.VectorMath;

/**
 * Renders the main screens with real fonts and Skia (Robolectric native graphics) into PNGs, so the
 * design can be looked at without a phone: welcome, download, first indexing, the gallery, search
 * results, the viewer, settings and notes. Photos are drawn scenes served through MainActivity's
 * test loader; the model is a bag-of-words stand-in.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "ru-w411dp-h891dp-night-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class UiShots {
    static final File OUT = new File(System.getProperty("shot.dir", "build/shots"));

    interface Cond { boolean ok(); }

    static void waitFor(String what, Cond c) throws Exception {
        long end = System.currentTimeMillis() + 60_000;
        while (!c.ok()) {
            ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS);
            if (System.currentTimeMillis() > end) fail("timeout waiting for " + what);
            Thread.sleep(5);
        }
        ShadowLooper.idleMainLooper();
    }

    /** Lets animations finish and background thumbnail loads land. */
    static void settle(int ms) throws Exception {
        for (int t = 0; t < ms; t += 16) {
            ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS);
            if (t % 160 == 0) Thread.sleep(15);
        }
    }

    static void shot(Activity a, String name) throws Exception {
        View root = a.getWindow().getDecorView();
        Bitmap b = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        // Nothing draws frames here, so view Animations (the grid's fade-in) start on this first
        // draw and read their clock from the window's drawing time: draw once, let them run to the
        // end, then draw the frame that is saved.
        drawAt(root, b);
        ShadowLooper.idleMainLooper(1500, TimeUnit.MILLISECONDS);
        b.eraseColor(0);
        drawAt(root, b);
        OUT.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(OUT, name + ".png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
        System.out.println("shot " + name + " " + b.getWidth() + "x" + b.getHeight());
    }

    static void drawAt(View root, Bitmap b) throws Exception {
        Field ai = View.class.getDeclaredField("mAttachInfo");
        ai.setAccessible(true);
        Object info = ai.get(root);
        Field t = info.getClass().getDeclaredField("mDrawingTime");
        t.setAccessible(true);
        t.setLong(info, android.os.SystemClock.uptimeMillis());
        root.draw(new Canvas(b));
    }

    static Object call(Object o, String name, Object... args) throws Exception {
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == args.length) {
                    m.setAccessible(true);
                    return m.invoke(o, args);
                }
            }
        }
        throw new NoSuchMethodException(name);
    }

    static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    static View byName(View v, String simpleName) {
        if (v.getClass().getSimpleName().equals(simpleName)) return v;
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                View r = byName(((ViewGroup) v).getChildAt(i), simpleName);
                if (r != null) return r;
            }
        }
        return null;
    }

    static <T extends View> T find(View v, Class<T> type) {
        if (type.isInstance(v) && v.isShown()) return type.cast(v);
        if (v instanceof ViewGroup) {
            for (int i = ((ViewGroup) v).getChildCount() - 1; i >= 0; i--) {
                T r = find(((ViewGroup) v).getChildAt(i), type);
                if (r != null) return r;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ stand-in model

    static float[] bag(String text) {
        float[] v = new float[768];
        for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) {
            if (w.isEmpty()) continue;
            Random r = new Random(w.hashCode());
            for (int i = 0; i < v.length; i++) v[i] += (float) r.nextGaussian();
        }
        VectorMath.normalize(v);
        return v;
    }

    static final class FakeEmbedder implements Embedder {
        public float[] embedQuery(String q) { return bag(q); }
        public float[] embedDocument(String t) { return bag(t); }
        public float[] embedImage(ImagePreprocessor.Source img, int budget) { return bag("photo"); }
        public float[][] embedImages(List<ImagePreprocessor.Source> imgs, int budget) {
            float[][] r = new float[imgs.size()][];
            for (int i = 0; i < r.length; i++) r[i] = embedImage(imgs.get(i), budget);
            return r;
        }
        public float[] embedVideo(List<ImagePreprocessor.Source> frames, int budget) { return bag("video"); }
        public boolean supportsImages() { return true; }
        public boolean supportsVideo() { return true; }
        public int embeddingDim() { return 768; }
        public int defaultImageTokens() { return 280; }
        public long[] lastTimingsMs() { return new long[]{0, 0}; }
        public void close() {}
    }

    // ------------------------------------------------------------------ drawn "photos"

    static final String[] SCENES = {"sunset sea beach", "mountains lake", "city night", "forest path",
            "receipt paper", "cat sofa", "flowers field", "snow hills"};

    static boolean isVideo(long id) {
        return (id - 1000) % 11 == 7;
    }

    /** Proportions as a phone gallery has them: 4:3 and 3:4 shots, 16:9, squares, tall screenshots. */
    static float aspectOf(long id) {
        if (isVideo(id)) return 16 / 9f;
        switch ((int) (id % 6)) {
            case 0: return 4 / 3f;
            case 1: return 3 / 4f;
            case 2: return 16 / 9f;
            case 3: return 1f;
            case 4: return 9 / 19.5f;
            default: return 3 / 4f;
        }
    }

    /** A soft, photo-like scene per item: sky, sun, hills, sea, buildings, a receipt… */
    static Bitmap scene(IndexStore.Item it, int size) {
        int kind = (int) (it.mediaId % SCENES.length);
        float aspect = aspectOf(it.mediaId);
        int w = aspect >= 1 ? size : Math.round(size * aspect), h = aspect >= 1 ? Math.round(size / aspect) : size;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Random r = new Random(it.mediaId * 7919);
        float jitter = r.nextFloat();
        switch (kind) {
            case 0: { // sunset over the sea
                sky(c, p, w, h * 0.62f, 0xFF2B3550, 0xFFE09A6B);
                p.setShader(null);
                p.setColor(0xFFF6C58C);
                c.drawCircle(w * (0.3f + 0.4f * jitter), h * 0.58f, w * 0.09f, p);
                p.setShader(new LinearGradient(0, h * 0.6f, 0, h, 0xFF3E4C6B, 0xFF1C2333, Shader.TileMode.CLAMP));
                c.drawRect(0, h * 0.6f, w, h, p);
                p.setShader(null);
                p.setColor(0x55F6C58C);
                for (int i = 0; i < 7; i++) {
                    float y = h * (0.64f + i * 0.045f), half = w * (0.12f - i * 0.012f);
                    float cx = w * (0.3f + 0.4f * jitter);
                    c.drawRect(cx - half, y, cx + half, y + h * 0.008f, p);
                }
                break;
            }
            case 1: { // mountains and a lake
                sky(c, p, w, h * 0.6f, 0xFF7F9DB8, 0xFFCAD7DF);
                p.setShader(null);
                hill(c, p, w, h, 0.18f, 0.55f, 0xFF59697D, r, 4, 0.30f);
                hill(c, p, w, h, 0.32f, 0.62f, 0xFF3F4E5E, r, 5, 0.22f);
                p.setShader(new LinearGradient(0, h * 0.62f, 0, h, 0xFF6F8DA6, 0xFF2C3D4C, Shader.TileMode.CLAMP));
                c.drawRect(0, h * 0.62f, w, h, p);
                break;
            }
            case 2: { // city at night
                sky(c, p, w, h, 0xFF0F1626, 0xFF2D3550);
                p.setShader(null);
                float x = 0;
                while (x < w) {
                    float bw = w * (0.08f + 0.1f * r.nextFloat()), bh = h * (0.25f + 0.45f * r.nextFloat());
                    p.setColor(0xFF1A2133 + (r.nextInt(3) << 4));
                    c.drawRect(x, h - bh, x + bw, h, p);
                    p.setColor(0xCCE8C27A);
                    for (float wy = h - bh + h * 0.03f; wy < h - h * 0.03f; wy += h * 0.05f) {
                        for (float wx = x + bw * 0.15f; wx < x + bw - bw * 0.2f; wx += bw * 0.25f) {
                            if (r.nextFloat() < 0.35f) c.drawRect(wx, wy, wx + bw * 0.1f, wy + h * 0.022f, p);
                        }
                    }
                    x += bw + w * 0.01f;
                }
                break;
            }
            case 3: { // forest path
                sky(c, p, w, h, 0xFF9FB59A, 0xFF4E6248);
                p.setShader(null);
                for (int i = 0; i < 9; i++) {
                    float tx = w * r.nextFloat(), tw = w * (0.03f + 0.04f * r.nextFloat());
                    p.setColor(0xFF2F3B2C + (r.nextInt(4) << 8));
                    c.drawRect(tx, 0, tx + tw, h, p);
                }
                p.setColor(0xFFB8A27E);
                Path path = new Path();
                path.moveTo(w * 0.45f, h * 0.55f);
                path.lineTo(w * 0.55f, h * 0.55f);
                path.lineTo(w * 0.85f, h);
                path.lineTo(w * 0.15f, h);
                path.close();
                c.drawPath(path, p);
                break;
            }
            case 4: { // a receipt on a table
                p.setColor(0xFF5A4A3E);
                c.drawRect(0, 0, w, h, p);
                c.save();
                c.rotate(-6 + 12 * jitter, w / 2f, h / 2f);
                p.setColor(0xFFEDEAE3);
                RectF paper = new RectF(w * 0.28f, h * 0.08f, w * 0.72f, h * 0.94f);
                c.drawRect(paper, p);
                p.setColor(0xFF8D8A84);
                for (float y = paper.top + h * 0.08f; y < paper.bottom - h * 0.06f; y += h * 0.045f) {
                    float lw = paper.width() * (0.4f + 0.45f * r.nextFloat());
                    c.drawRect(paper.left + w * 0.04f, y, paper.left + w * 0.04f + lw, y + h * 0.012f, p);
                }
                c.restore();
                break;
            }
            case 5: { // a cat on a sofa (very abstract)
                p.setColor(0xFF6E5B57);
                c.drawRect(0, 0, w, h, p);
                p.setColor(0xFF8A6F68);
                c.drawRoundRect(new RectF(-w * 0.1f, h * 0.45f, w * 1.1f, h * 1.1f), w * 0.1f, w * 0.1f, p);
                p.setColor(0xFFD9A066);
                c.drawOval(new RectF(w * 0.25f, h * 0.42f, w * 0.75f, h * 0.72f), p);
                c.drawCircle(w * 0.68f, h * 0.42f, w * 0.12f, p);
                Path ears = new Path();
                ears.moveTo(w * 0.58f, h * 0.36f);
                ears.lineTo(w * 0.6f, h * 0.24f);
                ears.lineTo(w * 0.66f, h * 0.32f);
                ears.moveTo(w * 0.7f, h * 0.31f);
                ears.lineTo(w * 0.77f, h * 0.23f);
                ears.lineTo(w * 0.79f, h * 0.36f);
                c.drawPath(ears, p);
                break;
            }
            case 6: { // flowers in a field
                sky(c, p, w, h * 0.5f, 0xFF8FB3D1, 0xFFDCE6EC);
                p.setShader(new LinearGradient(0, h * 0.45f, 0, h, 0xFF6F8F4E, 0xFF3D5530, Shader.TileMode.CLAMP));
                c.drawRect(0, h * 0.45f, w, h, p);
                p.setShader(null);
                int[] petals = {0xFFE3B7C4, 0xFFF0D98A, 0xFFF2F0EA, 0xFFC9A6D9};
                for (int i = 0; i < 60; i++) {
                    float fy = h * (0.5f + 0.5f * r.nextFloat());
                    p.setColor(petals[r.nextInt(petals.length)]);
                    c.drawCircle(w * r.nextFloat(), fy, w * 0.008f + w * 0.02f * (fy / h - 0.4f), p);
                }
                break;
            }
            default: { // snowy hills
                sky(c, p, w, h * 0.6f, 0xFF9DB0C4, 0xFFE4E9EE);
                p.setShader(null);
                hill(c, p, w, h, 0.45f, 0.7f, 0xFFDDE4EA, r, 3, 0.18f);
                hill(c, p, w, h, 0.6f, 0.8f, 0xFFF1F4F6, r, 3, 0.12f);
                break;
            }
        }
        // a touch of vignette so it reads as a photo
        p.setShader(new LinearGradient(0, 0, 0, h, 0x14000000, 0x30000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        return b;
    }

    static void sky(Canvas c, Paint p, float w, float h, int top, int bottom) {
        p.setShader(new LinearGradient(0, 0, 0, h, top, bottom, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
    }

    static void hill(Canvas c, Paint p, int w, int h, float topFrac, float baseFrac, int color, Random r, int peaks, float amp) {
        Path path = new Path();
        path.moveTo(0, h);
        path.lineTo(0, h * baseFrac);
        for (int i = 0; i <= peaks * 2; i++) {
            float x = w * i / (peaks * 2f);
            float y = i % 2 == 1 ? h * (topFrac + amp * 0.3f * r.nextFloat()) : h * (baseFrac - amp * 0.2f * r.nextFloat());
            path.lineTo(x, y);
        }
        path.lineTo(w, h);
        path.close();
        p.setColor(color);
        c.drawPath(path, p);
    }

    // ------------------------------------------------------------------ the walk

    @Test
    public void shots() throws Exception {
        MainActivity.testLoader = new MainActivity.BitmapLoader() {
            @Override
            public Bitmap load(IndexStore.Item it, int size) {
                return scene(it, size);
            }
        };
        for (long id = 1000; id < 1100; id++) {
            float ar = aspectOf(id);
            int w = ar >= 1 ? 4000 : Math.round(4000 * ar), h = ar >= 1 ? Math.round(4000 / ar) : 4000;
            FakeMediaStore.ROWS.add(new FakeMediaStore.Row(id, isVideo(id), 0, "IMG_" + id, w, h, null));
        }
        FakeMediaStore.install();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        final Engine e = Engine.get(a);
        waitFor("store", () -> e.store() != null);
        settle(600);
        shot(a, "01-welcome");

        // downloading the model
        e.state = Engine.State.DOWNLOADING;
        e.dlTotal = 1_180L << 20;
        e.dlDone = 437L << 20;
        a.onEngineChanged();
        settle(600);
        shot(a, "02-download");

        // model in place, gallery not indexed yet
        File model = new File(a.getFilesDir(), "model");
        model.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(model, "manifest.json"))) {
            o.write("{}".getBytes("UTF-8"));
        }
        e.state = Engine.State.NO_MODEL;
        e.attachModelForTest(new FakeEmbedder());
        waitFor("ready", e::ready);
        settle(600);
        shot(a, "03-not-indexed");

        // first indexing: the grid fills as photos are processed
        IndexStore s = e.store();
        long now = System.currentTimeMillis();
        int n = 0;
        for (int i = 0; i < 14; i++, n++) addPhoto(s, n, now);
        e.indexing = true;
        e.idxTotal = 1240;
        e.idxDone = 14;
        a.onEngineChanged();
        settle(900);
        shot(a, "04-indexing");

        // the gallery
        for (; n < 60; n++) addPhoto(s, n, now);
        e.indexing = false;
        e.idxDone = 1240;
        a.onEngineChanged();
        settle(1200);
        View gallery = byName(a.getWindow().getDecorView(), "MasonryView");
        assertNotNull(gallery);
        assertEquals(60, ((List<?>) call(gallery, "items")).size());
        shot(a, "05-gallery");

        // a search
        EditText q = (EditText) field(a, "query");
        q.setText("закат на море");
        call(a, "runSearch", true);
        waitFor("results", () -> String.valueOf(((android.widget.TextView) fieldUnchecked(a, "section")).getText()).contains("·"));
        settle(1200);
        shot(a, "06-search");

        // the viewer, grown out of the second tile
        call(a, "openViewer", 1);
        settle(900);
        shot(a, "07-viewer");
        a.onBackPressed();
        settle(700);
        q.setText("");
        settle(900);

        // settings
        call(a, "openSettings");
        settle(800);
        shot(a, "08-settings");
        ScrollView sv = find(a.getWindow().getDecorView(), ScrollView.class);
        assertNotNull(sv);
        sv.scrollTo(0, sv.getChildAt(0).getHeight());
        settle(200);
        shot(a, "09-settings-end");
        a.onBackPressed();
        settle(700);

        // notes
        String[] notes = {"Пароль от домашнего Wi-Fi: Lisa2024! — сеть Keenetic-5G",
                "Записаться к стоматологу на пятницу, 10:30, клиника на Ленина 12",
                "Купить: молоко, хлеб, яйца, сыр, кофе в зёрнах",
                "Идея подарка маме: кашемировый шарф или сертификат в спа",
                "Рецепт сырников: 500 г творога, 2 яйца, 3 ложки муки, ваниль",
                "Код домофона у Саши: 47К1290",
                "Созвон с командой в четверг в 15:00 — обсудить бюджет проекта"};
        for (int i = 0; i < notes.length; i++) s.add(IndexStore.KIND_NOTE, -1, null, null, notes[i], now - i * 3_600_000L, bag(notes[i]));
        call(a, "selectFilter", 3);
        settle(900);
        shot(a, "10-notes");
        call(a, "noteEditor");
        settle(700);
        shot(a, "11-note-editor");
        a.onBackPressed();
        settle(600);

        // a note in the viewer
        call(a, "openViewer", 0);
        settle(900);
        shot(a, "12-note-viewer");
        a.onBackPressed();
        settle(600);
        a.finish();
    }

    static Object fieldUnchecked(Object o, String name) {
        try {
            return field(o, name);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    static void addPhoto(IndexStore s, int n, long now) {
        long id = 1000 + n;
        boolean video = isVideo(id);
        String concept = SCENES[(int) (id % SCENES.length)];
        long date = now - n * 5L * 3_600_000L;
        if (video) {
            s.add(IndexStore.KIND_VIDEO, id, "content://media/external/video/media/" + id, "VID_" + id + ".mp4", null, date, bag(concept));
        } else {
            s.add(IndexStore.KIND_PHOTO, id, "content://media/external/images/media/" + id, "IMG_" + id + ".jpg", null, date, bag(concept));
        }
    }
}
