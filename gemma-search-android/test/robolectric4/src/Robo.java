import static org.junit.Assert.fail;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import io.github.teoplaydor.semsearch.core.Embedder;
import io.github.teoplaydor.semsearch.core.ImagePreprocessor;
import io.github.teoplaydor.semsearch.core.VectorMath;

/** Shared helpers for the Robolectric app tests. */
final class Robo {
    private Robo() {}

    interface Cond {
        boolean ok() throws Exception;
    }

    /** Runs the main looper (and its clock) until the condition holds; background threads run for real. */
    static void waitFor(String what, Cond c) throws Exception {
        long end = System.currentTimeMillis() + 60_000;
        while (!c.ok()) {
            ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS);
            if (System.currentTimeMillis() > end) fail("timeout waiting for " + what);
            Thread.sleep(5);
        }
        ShadowLooper.idleMainLooper();
    }

    /** Lets animations run to the end. */
    static void settle(int ms) throws Exception {
        for (int t = 0; t < ms; t += 16) {
            ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS);
            if (t % 160 == 0) Thread.sleep(10);
        }
    }

    static List<View> views(View v, List<View> out) {
        out.add(v);
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) views(((ViewGroup) v).getChildAt(i), out);
        }
        return out;
    }

    static String allText(View root) {
        StringBuilder sb = new StringBuilder();
        for (View v : views(root, new ArrayList<View>())) {
            if (v instanceof TextView && v.isShown()) sb.append(((TextView) v).getText()).append(" | ");
        }
        return sb.toString();
    }

    /** The last shown view of a class with this simple name (package-private app classes included). */
    static View byName(View root, String simpleName) {
        View found = null;
        for (View v : views(root, new ArrayList<View>())) {
            if (v.getClass().getSimpleName().equals(simpleName) && v.isShown()) found = v;
        }
        return found;
    }

    static TextView textView(View root, String text) {
        TextView found = null;
        for (View v : views(root, new ArrayList<View>())) {
            if (v instanceof TextView && v.isShown() && text.contentEquals(((TextView) v).getText())) found = (TextView) v;
        }
        return found;
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

    // ------------------------------------------------------------------ stand-in model

    /** Bag of words: texts sharing words are close, "кот" and "cat" share nothing. */
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

    static float[] random(Random r) {
        float[] v = new float[768];
        for (int i = 0; i < v.length; i++) v[i] = (float) r.nextGaussian();
        VectorMath.normalize(v);
        return v;
    }

    /** Images embed as the given English concepts, in the order they are encoded. */
    static class FakeEmbedder implements Embedder {
        final String[] imageConcepts;
        final List<Integer> batches = new ArrayList<Integer>();
        int next;

        FakeEmbedder(String... imageConcepts) {
            this.imageConcepts = imageConcepts;
        }

        public float[] embedQuery(String q) { return bag(q); }
        public float[] embedDocument(String t) { return bag(t); }

        public synchronized float[] embedImage(ImagePreprocessor.Source img, int budget) {
            if (img.width() <= 0 || img.height() <= 0) throw new IllegalStateException("empty image");
            img.argb(32, 32);
            return bag(imageConcepts.length == 0 ? "photo" : imageConcepts[next++ % imageConcepts.length]);
        }

        public float[][] embedImages(List<ImagePreprocessor.Source> imgs, int budget) {
            batches.add(imgs.size());
            float[][] r = new float[imgs.size()][];
            for (int i = 0; i < r.length; i++) r[i] = embedImage(imgs.get(i), budget);
            return r;
        }

        public float[] embedVideo(List<ImagePreprocessor.Source> frames, int budget) { throw new IllegalStateException("no video"); }
        public boolean supportsImages() { return true; }
        public boolean supportsVideo() { return true; }
        public int embeddingDim() { return 768; }
        public int defaultImageTokens() { return 280; }
        public long[] lastTimingsMs() { return new long[]{0, 0}; }
        public void close() {}
    }
}
