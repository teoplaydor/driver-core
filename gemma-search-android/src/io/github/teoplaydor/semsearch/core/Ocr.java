package io.github.teoplaydor.semsearch.core;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Text read on a picture (OCR) with PaddleOCR's models on ONNX Runtime, the way RapidOCR runs them: the detector
 * marks where text is (DB: a probability map, its regions as rotated boxes, each widened a little), each box is cut
 * out straight and read by the recognizer (CTC over its alphabet). Lines in reading order: top to bottom, left to
 * right within a row.
 */
public final class Ocr implements Closeable {
    /** One piece of text: its words, how sure, where (four corners: top-left, top-right, bottom-right, bottom-left). */
    public static final class Line {
        public String text;
        public float score;
        public float[] box = new float[8];
    }

    // RapidOCR 3.10 defaults (config.yaml): detection
    static final int LIMIT_SIDE = 736, MAX_SIDE = 2000;
    static final float THRESH = 0.3f, BOX_THRESH = 0.5f, UNCLIP = 1.6f, TEXT_SCORE = 0.5f;
    static final int MIN_SIZE = 3, MAX_CANDIDATES = 1000, ROW_DY = 10;
    // recognition
    static final int REC_H = 48, REC_W = 320;

    private final OrtEnvironment env;
    private final OrtSession det, rec;
    private final String detIn, recIn;
    /** The alphabet: blank first, the space last. */
    private final String[] chars;
    /** The longest side a picture is read at (smaller: faster, small print lost). */
    private int maxSide = MAX_SIDE;

    /** @param dict the alphabet, one per line (null: the recognizer's own copy, «character» in its metadata) */
    public Ocr(File detector, File recognizer, File dict, int threads) throws Exception {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(Math.max(1, threads));
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        det = env.createSession(detector.getPath(), o);
        rec = env.createSession(recognizer.getPath(), o);
        detIn = det.getInputNames().iterator().next();
        recIn = rec.getInputNames().iterator().next();
        // the alphabet from its file: the model's own copy (metadata) loses its emoji on the way through JNI
        List<String> alphabet = dict != null ? readDict(dict) : null;
        if (alphabet == null || alphabet.isEmpty()) {
            Map<String, String> meta = rec.getMetadata().getCustomMetadata();
            if (meta != null && meta.containsKey("character")) alphabet = new ArrayList<String>(Arrays.asList(meta.get("character").split("\r?\n", -1)));
        }
        if (alphabet == null) throw new IllegalStateException("нет алфавита распознавания");
        // trailing empty line of a split: not a character
        while (!alphabet.isEmpty() && alphabet.get(alphabet.size() - 1).isEmpty() && alphabet.size() > 1) alphabet.remove(alphabet.size() - 1);
        List<String> all = new ArrayList<String>();
        all.add("");
        all.addAll(alphabet);
        all.add(" ");
        chars = all.toArray(new String[0]);
    }

    public void setMaxSide(int side) {
        maxSide = Math.max(320, side);
    }

    static List<String> readDict(File f) throws Exception {
        List<String> out = new ArrayList<String>();
        if (f == null) throw new IllegalStateException("нет алфавита распознавания");
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            String l;
            while ((l = r.readLine()) != null) out.add(l);
        } finally {
            r.close();
        }
        return out;
    }

    /** The size the picture is read at: its longest side at most {@link #maxSide}. */
    public int[] readSize(int w, int h) {
        double s = Math.max(w, h) > maxSide ? (double) maxSide / Math.max(w, h) : 1;
        return new int[]{Math.max(1, (int) (w * s)), Math.max(1, (int) (h * s))};
    }

    /** The text on the picture, line by line (in reading order). */
    public List<Line> read(ImagePreprocessor.Source image) throws OrtException {
        int[] size = readSize(image.width(), image.height());
        int w = size[0], h = size[1];
        int[] px = image.argb(w, h);
        List<float[]> boxes = detect(px, w, h);
        List<Line> out = new ArrayList<Line>();
        for (float[] b : boxes) {
            Crop c = crop(px, w, h, b);
            Line l = recognize(c);
            if (l.score < TEXT_SCORE || l.text.trim().isEmpty()) continue;
            l.box = b;
            out.add(l);
        }
        return out;
    }

    /** The lines as text: pieces in a row joined by a space, rows by a new line. */
    public static String text(List<Line> lines) {
        StringBuilder b = new StringBuilder();
        float lastY = Float.NaN, lastH = 0;
        for (Line l : lines) {
            float y = (l.box[1] + l.box[7]) / 2, hh = Math.abs(l.box[7] - l.box[1]);
            if (b.length() > 0) b.append(!Float.isNaN(lastY) && Math.abs(y - lastY) < Math.max(lastH, hh) * 0.5f ? " " : "\n");
            b.append(l.text.trim());
            lastY = y;
            lastH = hh;
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ detection

    /** Where the text is: boxes (8 floats) in the picture's pixels, in reading order. */
    List<float[]> detect(int[] px, int w, int h) throws OrtException {
        double ratio = Math.min(w, h) < LIMIT_SIDE ? (double) LIMIT_SIDE / Math.min(w, h) : 1.0;
        int rh = (int) (h * ratio), rw = (int) (w * ratio);
        rh = (int) (Math.rint(rh / 32.0) * 32);
        rw = (int) (Math.rint(rw / 32.0) * 32);
        if (rh <= 0 || rw <= 0) return new ArrayList<float[]>();
        float[] blob = new float[3 * rh * rw];
        int[] scaled = Resize.bilinear(px, w, h, rw, rh);
        int plane = rh * rw;
        for (int i = 0; i < plane; i++) {
            int p = scaled[i];
            // BGR, as the models were trained (OpenCV images)
            blob[i] = ((p & 0xFF) / 255f - 0.5f) / 0.5f;
            blob[plane + i] = (((p >> 8) & 0xFF) / 255f - 0.5f) / 0.5f;
            blob[2 * plane + i] = (((p >> 16) & 0xFF) / 255f - 0.5f) / 0.5f;
        }
        float[] prob;
        OnnxTensor in = OnnxTensor.createTensor(env, FloatBuffer.wrap(blob), new long[]{1, 3, rh, rw});
        try {
            Map<String, OnnxTensor> feed = new HashMap<String, OnnxTensor>();
            feed.put(detIn, in);
            OrtSession.Result r = det.run(feed);
            try {
                FloatBuffer fb = ((OnnxTensor) r.get(0)).getFloatBuffer();
                prob = new float[fb.remaining()];
                fb.get(prob);
            } finally {
                r.close();
            }
        } finally {
            in.close();
        }
        return boxes(prob, rw, rh, w, h);
    }

    /** DB post-processing: the map thresholded and dilated, each region's rotated box scored, widened, scaled back. */
    static List<float[]> boxes(float[] prob, int pw, int ph, int destW, int destH) {
        boolean[] mask = new boolean[pw * ph];
        for (int i = 0; i < mask.length; i++) mask[i] = prob[i] > THRESH;
        // dilation with a 2×2 kernel (anchor at its centre: the pixel and those above and to the left)
        boolean[] dil = new boolean[mask.length];
        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) {
                boolean v = mask[y * pw + x];
                if (!v && x > 0) v = mask[y * pw + x - 1];
                if (!v && y > 0) v = mask[(y - 1) * pw + x] || x > 0 && mask[(y - 1) * pw + x - 1];
                dil[y * pw + x] = v;
            }
        }
        List<float[]> out = new ArrayList<float[]>();
        List<Float> scores = new ArrayList<Float>();
        int[] label = new int[mask.length];
        int[] stack = new int[mask.length];
        int regions = 0;
        for (int start = 0; start < dil.length && regions < MAX_CANDIDATES; start++) {
            if (!dil[start] || label[start] != 0) continue;
            regions++;
            // the region (8-connected), its extreme pixels per row for the hull
            int sp = 0;
            stack[sp++] = start;
            label[start] = regions;
            Map<Integer, int[]> rows = new HashMap<Integer, int[]>();
            while (sp > 0) {
                int p = stack[--sp];
                int x = p % pw, y = p / pw;
                int[] r = rows.get(y);
                if (r == null) rows.put(y, new int[]{x, x});
                else {
                    if (x < r[0]) r[0] = x;
                    if (x > r[1]) r[1] = x;
                }
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= ph) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        if (nx < 0 || nx >= pw || dx == 0 && dy == 0) continue;
                        int q = ny * pw + nx;
                        if (dil[q] && label[q] == 0) {
                            label[q] = regions;
                            stack[sp++] = q;
                        }
                    }
                }
            }
            List<double[]> pts = new ArrayList<double[]>();
            for (Map.Entry<Integer, int[]> e : rows.entrySet()) {
                pts.add(new double[]{e.getValue()[0], e.getKey()});
                if (e.getValue()[1] != e.getValue()[0]) pts.add(new double[]{e.getValue()[1], e.getKey()});
            }
            double[] rect = Geometry.minAreaRect(Geometry.hull(pts));
            if (Math.min(rect[2], rect[3]) < MIN_SIZE) continue;
            double[][] box = Geometry.miniBox(rect);
            float score = scoreFast(prob, pw, ph, box);
            if (score < BOX_THRESH) continue;
            // widened by area × ratio / perimeter on every side (what a round offset of the box comes to)
            double area = rect[2] * rect[3], perim = 2 * (rect[2] + rect[3]);
            double d = area * UNCLIP / perim;
            double[] big = {rect[0], rect[1], rect[2] + 2 * d, rect[3] + 2 * d, rect[4]};
            if (Math.min(big[2], big[3]) < MIN_SIZE + 2) continue;
            double[][] b = Geometry.miniBox(big);
            float[] f = new float[8];
            for (int k = 0; k < 4; k++) {
                f[2 * k] = (float) Math.min(Math.max(Math.rint(b[k][0] / pw * destW), 0), destW);
                f[2 * k + 1] = (float) Math.min(Math.max(Math.rint(b[k][1] / ph * destH), 0), destH);
            }
            out.add(f);
            scores.add(score);
        }
        // order the corners clockwise from the top-left, keep inside the picture, drop the slivers
        List<float[]> kept = new ArrayList<float[]>();
        for (float[] f : out) {
            float[] o = Geometry.clockwise(f);
            for (int k = 0; k < 4; k++) {
                o[2 * k] = (int) Math.min(Math.max(o[2 * k], 0), destW - 1);
                o[2 * k + 1] = (int) Math.min(Math.max(o[2 * k + 1], 0), destH - 1);
            }
            int rw = (int) Math.hypot(o[0] - o[2], o[1] - o[3]), rh = (int) Math.hypot(o[0] - o[6], o[1] - o[7]);
            if (rw <= 3 || rh <= 3) continue;
            kept.add(o);
        }
        return sorted(kept);
    }

    /** Top to bottom; a row (tops within {@link #ROW_DY}) left to right. */
    static List<float[]> sorted(List<float[]> boxes) {
        List<float[]> byY = new ArrayList<float[]>(boxes);
        Collections.sort(byY, new Comparator<float[]>() {
            @Override
            public int compare(float[] a, float[] b) {
                return Float.compare(a[1], b[1]);
            }
        });
        final int[] row = new int[byY.size()];
        for (int i = 1; i < byY.size(); i++) row[i] = row[i - 1] + (byY.get(i)[1] - byY.get(i - 1)[1] >= ROW_DY ? 1 : 0);
        Integer[] idx = new Integer[byY.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        final List<float[]> src = byY;
        Arrays.sort(idx, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                if (row[a] != row[b]) return row[a] - row[b];
                return Float.compare(src.get(a)[0], src.get(b)[0]);
            }
        });
        List<float[]> out = new ArrayList<float[]>();
        for (Integer i : idx) out.add(byY.get(i));
        return out;
    }

    /** The mean probability inside the box (pixels whose centres are in it). */
    static float scoreFast(float[] prob, int w, int h, double[][] box) {
        double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
        for (double[] p : box) {
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]);
            maxY = Math.max(maxY, p[1]);
        }
        int x0 = (int) Math.max(0, Math.min(w - 1, Math.floor(minX))), x1 = (int) Math.max(0, Math.min(w - 1, Math.ceil(maxX)));
        int y0 = (int) Math.max(0, Math.min(h - 1, Math.floor(minY))), y1 = (int) Math.max(0, Math.min(h - 1, Math.ceil(maxY)));
        // the polygon at integer corners, as OpenCV fills it
        double[][] poly = new double[4][];
        for (int k = 0; k < 4; k++) poly[k] = new double[]{(int) box[k][0], (int) box[k][1]};
        double sum = 0;
        int n = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                if (!Geometry.inside(poly, x, y)) continue;
                sum += prob[y * w + x];
                n++;
            }
        }
        return n == 0 ? 0 : (float) (sum / n);
    }

    // ------------------------------------------------------------------ recognition

    static final class Crop {
        int[] px;
        int w, h;
    }

    /** The box cut out upright (a perspective warp, bicubic, edges repeated); a tall one turned to lie. */
    static Crop crop(int[] px, int w, int h, float[] b) {
        int cw = (int) Math.max(Math.hypot(b[0] - b[2], b[1] - b[3]), Math.hypot(b[4] - b[6], b[5] - b[7]));
        int ch = (int) Math.max(Math.hypot(b[0] - b[6], b[1] - b[7]), Math.hypot(b[2] - b[4], b[3] - b[5]));
        cw = Math.max(1, cw);
        ch = Math.max(1, ch);
        double[] m = Geometry.perspective(new double[]{0, 0, cw, 0, cw, ch, 0, ch},
                new double[]{b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7]});
        int[] out = new int[cw * ch];
        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw; x++) {
                double den = m[6] * x + m[7] * y + m[8];
                double sx = (m[0] * x + m[1] * y + m[2]) / den, sy = (m[3] * x + m[4] * y + m[5]) / den;
                out[y * cw + x] = Resize.bicubicAt(px, w, h, sx, sy);
            }
        }
        Crop c = new Crop();
        if (ch * 1.0 / cw >= 1.5) {
            // np.rot90: a quarter turn counter-clockwise
            int[] r = new int[cw * ch];
            for (int y = 0; y < ch; y++) for (int x = 0; x < cw; x++) r[(cw - 1 - x) * ch + y] = out[y * cw + x];
            c.px = r;
            c.w = ch;
            c.h = cw;
        } else {
            c.px = out;
            c.w = cw;
            c.h = ch;
        }
        return c;
    }

    /** One piece read: resized to the recognizer's height (padded to its width), CTC best path, its mean score. */
    Line recognize(Crop c) throws OrtException {
        double ratio = c.w / (double) c.h;
        double maxRatio = Math.max(REC_W / (double) REC_H, ratio);
        int imgW = (int) (REC_H * maxRatio);
        int rw = (int) Math.ceil(REC_H * ratio) > imgW ? imgW : (int) Math.ceil(REC_H * ratio);
        rw = Math.max(1, rw);
        int[] scaled = Resize.bilinear(c.px, c.w, c.h, rw, REC_H);
        float[] blob = new float[3 * REC_H * imgW];
        int plane = REC_H * imgW;
        for (int y = 0; y < REC_H; y++) {
            for (int x = 0; x < rw; x++) {
                int p = scaled[y * rw + x], i = y * imgW + x;
                blob[i] = ((p & 0xFF) / 255f - 0.5f) / 0.5f;
                blob[plane + i] = (((p >> 8) & 0xFF) / 255f - 0.5f) / 0.5f;
                blob[2 * plane + i] = (((p >> 16) & 0xFF) / 255f - 0.5f) / 0.5f;
            }
        }
        float[] logits;
        long[] shape;
        OnnxTensor in = OnnxTensor.createTensor(env, FloatBuffer.wrap(blob), new long[]{1, 3, REC_H, imgW});
        try {
            Map<String, OnnxTensor> feed = new HashMap<String, OnnxTensor>();
            feed.put(recIn, in);
            OrtSession.Result r = rec.run(feed);
            try {
                OnnxTensor t = (OnnxTensor) r.get(0);
                shape = t.getInfo().getShape();
                FloatBuffer fb = t.getFloatBuffer();
                logits = new float[fb.remaining()];
                fb.get(logits);
            } finally {
                r.close();
            }
        } finally {
            in.close();
        }
        if (shape[2] != chars.length) {
            throw new IllegalStateException("алфавит (" + (chars.length - 2) + ") не подходит модели (" + (shape[2] - 2) + ")");
        }
        return decode(logits, (int) shape[1], (int) shape[2], chars);
    }

    /** CTC best path: the most likely class per step, repeats and blanks dropped; the score is the kept steps' mean. */
    static Line decode(float[] p, int steps, int classes, String[] chars) {
        StringBuilder b = new StringBuilder();
        double sum = 0;
        int n = 0, prev = -1;
        for (int t = 0; t < steps; t++) {
            int best = 0;
            float bv = p[t * classes];
            for (int k = 1; k < classes; k++) {
                float v = p[t * classes + k];
                if (v > bv) {
                    bv = v;
                    best = k;
                }
            }
            if (best != prev && best != 0) {
                b.append(best < chars.length ? chars[best] : "");
                sum += bv;
                n++;
            }
            prev = best;
        }
        Line l = new Line();
        l.text = b.toString();
        l.score = n == 0 ? 0 : (float) (sum / n);
        return l;
    }

    @Override
    public void close() {
        try {
            det.close();
        } catch (OrtException ignored) {
            // closed
        }
        try {
            rec.close();
        } catch (OrtException ignored) {
            // closed
        }
    }

    // ------------------------------------------------------------------ pixels

    /** Resampling as OpenCV does it (pixel centres at +0.5): bilinear, and bicubic with a = −0.75. */
    static final class Resize {
        private Resize() {
        }

        static int[] bilinear(int[] src, int sw, int sh, int dw, int dh) {
            if (sw == dw && sh == dh) return src;
            int[] out = new int[dw * dh];
            double sx = sw / (double) dw, sy = sh / (double) dh;
            for (int y = 0; y < dh; y++) {
                double fy = (y + 0.5) * sy - 0.5;
                int y0 = (int) Math.floor(fy);
                double ay = fy - y0;
                if (y0 < 0) {
                    y0 = 0;
                    ay = 0;
                }
                int y1 = Math.min(y0 + 1, sh - 1);
                if (y0 >= sh - 1) {
                    y0 = sh - 1;
                    y1 = sh - 1;
                    ay = 0;
                }
                for (int x = 0; x < dw; x++) {
                    double fx = (x + 0.5) * sx - 0.5;
                    int x0 = (int) Math.floor(fx);
                    double ax = fx - x0;
                    if (x0 < 0) {
                        x0 = 0;
                        ax = 0;
                    }
                    int x1 = Math.min(x0 + 1, sw - 1);
                    if (x0 >= sw - 1) {
                        x0 = sw - 1;
                        x1 = sw - 1;
                        ax = 0;
                    }
                    int a = src[y0 * sw + x0], bq = src[y0 * sw + x1], c = src[y1 * sw + x0], d = src[y1 * sw + x1];
                    int px = 0xFF000000;
                    for (int sh8 = 0; sh8 < 24; sh8 += 8) {
                        double top = ((a >> sh8) & 0xFF) * (1 - ax) + ((bq >> sh8) & 0xFF) * ax;
                        double bot = ((c >> sh8) & 0xFF) * (1 - ax) + ((d >> sh8) & 0xFF) * ax;
                        int v = (int) Math.round(top * (1 - ay) + bot * ay);
                        px |= Math.max(0, Math.min(255, v)) << sh8;
                    }
                    out[y * dw + x] = px;
                }
            }
            return out;
        }

        private static double cubic(double x) {
            double a = -0.75, t = Math.abs(x);
            if (t <= 1) return ((a + 2) * t - (a + 3)) * t * t + 1;
            if (t < 2) return ((a * t - 5 * a) * t + 8 * a) * t - 4 * a;
            return 0;
        }

        /** The picture at a point, bicubic, edges repeated. */
        static int bicubicAt(int[] src, int w, int h, double x, double y) {
            int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
            double fx = x - x0, fy = y - y0;
            double[] wx = new double[4], wy = new double[4];
            for (int i = 0; i < 4; i++) {
                wx[i] = cubic(fx - (i - 1));
                wy[i] = cubic(fy - (i - 1));
            }
            int px = 0xFF000000;
            for (int sh8 = 0; sh8 < 24; sh8 += 8) {
                double v = 0;
                for (int j = 0; j < 4; j++) {
                    int yy = Math.max(0, Math.min(h - 1, y0 + j - 1));
                    double row = 0;
                    for (int i = 0; i < 4; i++) {
                        int xx = Math.max(0, Math.min(w - 1, x0 + i - 1));
                        row += ((src[yy * w + xx] >> sh8) & 0xFF) * wx[i];
                    }
                    v += row * wy[j];
                }
                px |= Math.max(0, Math.min(255, (int) Math.round(v))) << sh8;
            }
            return px;
        }
    }

    // ------------------------------------------------------------------ geometry

    static final class Geometry {
        private Geometry() {
        }

        /** The convex hull (Andrew's monotone chain), counter-clockwise. */
        static List<double[]> hull(List<double[]> pts) {
            List<double[]> p = new ArrayList<double[]>(pts);
            Collections.sort(p, new Comparator<double[]>() {
                @Override
                public int compare(double[] a, double[] b) {
                    return a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]);
                }
            });
            if (p.size() < 3) return p;
            double[][] h = new double[2 * p.size()][];
            int k = 0;
            for (double[] q : p) {
                while (k >= 2 && cross(h[k - 2], h[k - 1], q) <= 0) k--;
                h[k++] = q;
            }
            for (int i = p.size() - 2, t = k + 1; i >= 0; i--) {
                double[] q = p.get(i);
                while (k >= t && cross(h[k - 2], h[k - 1], q) <= 0) k--;
                h[k++] = q;
            }
            return new ArrayList<double[]>(Arrays.asList(h).subList(0, k - 1));
        }

        private static double cross(double[] o, double[] a, double[] b) {
            return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
        }

        /** The smallest rectangle around the hull: {cx, cy, w, h, angle} (rotating the hull's edges). */
        static double[] minAreaRect(List<double[]> hull) {
            if (hull.size() == 1) return new double[]{hull.get(0)[0], hull.get(0)[1], 0, 0, 0};
            if (hull.size() == 2) {
                double[] a = hull.get(0), b = hull.get(1);
                return new double[]{(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, Math.hypot(b[0] - a[0], b[1] - a[1]), 0,
                        Math.atan2(b[1] - a[1], b[0] - a[0])};
            }
            double best = Double.MAX_VALUE;
            double[] r = null;
            int n = hull.size();
            for (int i = 0; i < n; i++) {
                double[] a = hull.get(i), b = hull.get((i + 1) % n);
                double ang = Math.atan2(b[1] - a[1], b[0] - a[0]);
                double c = Math.cos(ang), s = Math.sin(ang);
                double minU = 1e18, maxU = -1e18, minV = 1e18, maxV = -1e18;
                for (double[] q : hull) {
                    double u = q[0] * c + q[1] * s, v = -q[0] * s + q[1] * c;
                    minU = Math.min(minU, u);
                    maxU = Math.max(maxU, u);
                    minV = Math.min(minV, v);
                    maxV = Math.max(maxV, v);
                }
                double area = (maxU - minU) * (maxV - minV);
                if (area < best) {
                    best = area;
                    double cu = (minU + maxU) / 2, cv = (minV + maxV) / 2;
                    r = new double[]{cu * c - cv * s, cu * s + cv * c, maxU - minU, maxV - minV, ang};
                }
            }
            return r;
        }

        /** The rectangle's corners in DB's order: by x, then the upper of the left pair first, the upper of the right next. */
        static double[][] miniBox(double[] rect) {
            double c = Math.cos(rect[4]), s = Math.sin(rect[4]);
            double hw = rect[2] / 2, hh = rect[3] / 2;
            double[][] p = new double[4][];
            int k = 0;
            for (double[] d : new double[][]{{-hw, -hh}, {hw, -hh}, {hw, hh}, {-hw, hh}}) {
                p[k++] = new double[]{rect[0] + d[0] * c - d[1] * s, rect[1] + d[0] * s + d[1] * c};
            }
            Arrays.sort(p, new Comparator<double[]>() {
                @Override
                public int compare(double[] a, double[] b) {
                    return Double.compare(a[0], b[0]);
                }
            });
            double[] i1, i2, i3, i4;
            if (p[1][1] > p[0][1]) {
                i1 = p[0];
                i4 = p[1];
            } else {
                i1 = p[1];
                i4 = p[0];
            }
            if (p[3][1] > p[2][1]) {
                i2 = p[2];
                i3 = p[3];
            } else {
                i2 = p[3];
                i3 = p[2];
            }
            return new double[][]{i1, i2, i3, i4};
        }

        /** Corners top-left, top-right, bottom-right, bottom-left (by x, then by y in each pair). */
        static float[] clockwise(float[] f) {
            float[][] p = {{f[0], f[1]}, {f[2], f[3]}, {f[4], f[5]}, {f[6], f[7]}};
            Arrays.sort(p, new Comparator<float[]>() {
                @Override
                public int compare(float[] a, float[] b) {
                    return Float.compare(a[0], b[0]);
                }
            });
            float[] tl = p[0][1] <= p[1][1] ? p[0] : p[1], bl = p[0][1] <= p[1][1] ? p[1] : p[0];
            float[] tr = p[2][1] <= p[3][1] ? p[2] : p[3], br = p[2][1] <= p[3][1] ? p[3] : p[2];
            return new float[]{tl[0], tl[1], tr[0], tr[1], br[0], br[1], bl[0], bl[1]};
        }

        /** Whether (x, y) is inside the polygon or on its edge. */
        static boolean inside(double[][] poly, double x, double y) {
            boolean in = false;
            int n = poly.length;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                double xi = poly[i][0], yi = poly[i][1], xj = poly[j][0], yj = poly[j][1];
                // on the edge
                double cr = (xj - xi) * (y - yi) - (yj - yi) * (x - xi);
                if (Math.abs(cr) < 1e-9 && x >= Math.min(xi, xj) - 1e-9 && x <= Math.max(xi, xj) + 1e-9
                        && y >= Math.min(yi, yj) - 1e-9 && y <= Math.max(yi, yj) + 1e-9) return true;
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) in = !in;
            }
            return in;
        }

        /** The 3×3 homography taking {@code dst} corners to {@code src} ones (row-major, last element 1). */
        static double[] perspective(double[] dst, double[] src) {
            double[][] a = new double[8][9];
            for (int i = 0; i < 4; i++) {
                double x = dst[2 * i], y = dst[2 * i + 1], u = src[2 * i], v = src[2 * i + 1];
                a[2 * i] = new double[]{x, y, 1, 0, 0, 0, -u * x, -u * y, u};
                a[2 * i + 1] = new double[]{0, 0, 0, x, y, 1, -v * x, -v * y, v};
            }
            // Gaussian elimination with partial pivoting
            for (int c = 0; c < 8; c++) {
                int piv = c;
                for (int r = c + 1; r < 8; r++) if (Math.abs(a[r][c]) > Math.abs(a[piv][c])) piv = r;
                double[] t = a[c];
                a[c] = a[piv];
                a[piv] = t;
                if (Math.abs(a[c][c]) < 1e-12) continue;
                for (int r = 0; r < 8; r++) {
                    if (r == c) continue;
                    double f = a[r][c] / a[c][c];
                    for (int k = c; k < 9; k++) a[r][k] -= f * a[c][k];
                }
            }
            double[] m = new double[9];
            for (int i = 0; i < 8; i++) m[i] = Math.abs(a[i][i]) < 1e-12 ? 0 : a[i][8] / a[i][i];
            m[8] = 1;
            return m;
        }
    }
}
