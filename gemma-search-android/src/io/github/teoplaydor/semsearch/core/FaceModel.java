package io.github.teoplaydor.semsearch.core;

import java.io.File;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/**
 * Faces, as OpenCV's FaceDetectorYN and FaceRecognizerSF do it (objdetect, 4.x): YuNet (opencv_zoo
 * face_detection_yunet_2023mar, MIT) finds them — a box, five landmarks and a score, decoded from its per-stride
 * outputs and thinned by NMS — each face is aligned to the 112×112 ArcFace template by the similarity transform of its
 * landmarks, and SFace (face_recognition_sface_2021dec, Apache 2.0) turns it into 128 numbers. Two faces are one
 * person when the cosine of their vectors is at least {@link #SAME} (OpenCV's threshold for SFace).
 */
public final class FaceModel implements FaceFinder {
    public static final double SAME = 0.363;
    public static final float SCORE = 0.9f, NMS = 0.3f;
    public static final int TOP_K = 5000, CROP = 112;
    static final int[] STRIDES = {8, 16, 32};
    /** Where the eyes, the nose tip and the mouth corners go in the 112×112 crop (insightface's ArcFace template). */
    static final float[][] TEMPLATE = {{38.2946f, 51.6963f}, {73.5318f, 51.5014f}, {56.0252f, 71.7366f},
            {41.5493f, 92.3655f}, {70.7299f, 92.2041f}};
    static final float[] TEMPLATE_MEAN = {56.0262f, 71.9008f};

    /** A face in the pixels of the image given: box, landmarks (right eye, left eye, nose, mouth corners), score. */
    public static final class Face {
        public float x, y, w, h, score;
        public final float[] landmarks = new float[10];
        /** SFace's vector, unit length (null until embedded). */
        public float[] emb;

        /** OpenCV's row: x, y, w, h, the landmarks, score. */
        public float[] row() {
            float[] r = new float[15];
            r[0] = x;
            r[1] = y;
            r[2] = w;
            r[3] = h;
            System.arraycopy(landmarks, 0, r, 4, 10);
            r[14] = score;
            return r;
        }
    }

    private final OrtEnvironment env;
    private final OrtSession det, rec;
    private final String detIn, recIn, recOut;
    /** The detector's input size when its graph fixes one (the image is then fitted into it), else -1. */
    private final int fixedW, fixedH;
    private final String[] detOut = new String[12];
    /** The least score a face is kept with ({@link #SCORE}, OpenCV's default, unless set). */
    private float minScore = SCORE;

    public FaceModel(File detector, File recognizer, int threads) throws OrtException {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(Math.max(1, threads));
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        det = env.createSession(detector.getPath(), o);
        rec = env.createSession(recognizer.getPath(), o);
        detIn = det.getInputNames().iterator().next();
        long[] shape = ((TensorInfo) det.getInputInfo().get(detIn).getInfo()).getShape();
        fixedH = shape.length == 4 && shape[2] > 0 ? (int) shape[2] : -1;
        fixedW = shape.length == 4 && shape[3] > 0 ? (int) shape[3] : -1;
        java.util.Set<String> outs = det.getOutputNames();
        String[] kinds = {"cls", "obj", "bbox", "kps"};
        for (int k = 0; k < 4; k++) {
            for (int s = 0; s < 3; s++) {
                String n = kinds[k] + "_" + STRIDES[s];
                if (!outs.contains(n)) throw new IllegalStateException("детектор лиц без выхода " + n + " (нужен YuNet 2023mar): " + outs);
                detOut[s + 3 * k] = n;
            }
        }
        recIn = rec.getInputNames().iterator().next();
        recOut = rec.getOutputNames().iterator().next();
    }

    /** A lower score finds faces seen worse (dim, small, turned) — and a few that are not faces. */
    public void setMinScore(float s) {
        minScore = s;
    }

    /** For the log: what the graphs take and give. */
    public String describe() throws OrtException {
        StringBuilder sb = new StringBuilder("YuNet ").append(detIn).append(java.util.Arrays.toString(
                ((TensorInfo) det.getInputInfo().get(detIn).getInfo()).getShape()));
        sb.append(", SFace ").append(recIn).append(java.util.Arrays.toString(((TensorInfo) rec.getInputInfo().get(recIn).getInfo()).getShape()))
                .append(" → ").append(recOut).append(java.util.Arrays.toString(((TensorInfo) rec.getOutputInfo().get(recOut).getInfo()).getShape()));
        return sb.toString();
    }

    // ------------------------------------------------------------------ detection

    /** The faces in an image (ARGB, w×h), best first. */
    public List<Face> detect(int[] argb, int w, int h) throws OrtException {
        float scale = 1f;
        int iw = w, ih = h, padW, padH;
        int[] px = argb;
        if (fixedW > 0 && fixedH > 0) {
            // a graph of one size: the image fitted into it, the rest black (as OpenCV pads to its size)
            scale = Math.min((float) fixedW / w, (float) fixedH / h);
            iw = Math.max(1, Math.min(fixedW, Math.round(w * scale)));
            ih = Math.max(1, Math.min(fixedH, Math.round(h * scale)));
            if (iw != w || ih != h) px = resize(argb, w, h, iw, ih);
            padW = fixedW;
            padH = fixedH;
        } else {
            padW = ((iw - 1) / 32 + 1) * 32;
            padH = ((ih - 1) / 32 + 1) * 32;
        }
        // OpenCV's blobFromImage of the BGR image, padded at the right and the bottom: B, G, R planes, 0..255
        float[] blob = new float[3 * padW * padH];
        int plane = padW * padH;
        for (int y = 0; y < ih; y++) {
            for (int x = 0; x < iw; x++) {
                int p = px[y * iw + x], i = y * padW + x;
                blob[i] = p & 0xFF;
                blob[plane + i] = (p >> 8) & 0xFF;
                blob[2 * plane + i] = (p >> 16) & 0xFF;
            }
        }
        List<Face> faces = new ArrayList<Face>();
        OnnxTensor in = OnnxTensor.createTensor(env, FloatBuffer.wrap(blob), new long[]{1, 3, padH, padW});
        try {
            Map<String, OnnxTensor> feed = new HashMap<String, OnnxTensor>();
            feed.put(detIn, in);
            OrtSession.Result r = det.run(feed);
            try {
                for (int s = 0; s < 3; s++) {
                    int stride = STRIDES[s], cols = padW / stride, rows = padH / stride;
                    float[] cls = floats(r, detOut[s]), obj = floats(r, detOut[s + 3]), bbox = floats(r, detOut[s + 6]),
                            kps = floats(r, detOut[s + 9]);
                    for (int row = 0; row < rows; row++) {
                        for (int col = 0; col < cols; col++) {
                            int idx = row * cols + col;
                            if (idx >= cls.length) break;
                            float c = Math.max(0f, Math.min(1f, cls[idx])), o = Math.max(0f, Math.min(1f, obj[idx]));
                            float score = (float) Math.sqrt(c * o);
                            if (score < minScore) continue;
                            Face f = new Face();
                            float cx = (col + bbox[idx * 4]) * stride, cy = (row + bbox[idx * 4 + 1]) * stride;
                            f.w = (float) Math.exp(bbox[idx * 4 + 2]) * stride;
                            f.h = (float) Math.exp(bbox[idx * 4 + 3]) * stride;
                            f.x = cx - f.w / 2f;
                            f.y = cy - f.h / 2f;
                            for (int n = 0; n < 5; n++) {
                                f.landmarks[2 * n] = (kps[idx * 10 + 2 * n] + col) * stride;
                                f.landmarks[2 * n + 1] = (kps[idx * 10 + 2 * n + 1] + row) * stride;
                            }
                            f.score = score;
                            faces.add(f);
                        }
                    }
                }
            } finally {
                r.close();
            }
        } finally {
            in.close();
        }
        List<Face> kept = nms(faces, minScore);
        if (scale != 1f) {
            for (Face f : kept) {
                f.x /= scale;
                f.y /= scale;
                f.w /= scale;
                f.h /= scale;
                for (int i = 0; i < 10; i++) f.landmarks[i] /= scale;
            }
        }
        return kept;
    }

    private static float[] floats(OrtSession.Result r, String name) throws OrtException {
        FloatBuffer b = ((OnnxTensor) r.get(name).get()).getFloatBuffer();
        float[] a = new float[b.remaining()];
        b.get(a);
        return a;
    }

    /** OpenCV's NMSBoxes on the boxes as whole pixels (Rect2i): by score, dropping a box over {@link #NMS} IoU with a kept one. */
    static List<Face> nms(List<Face> faces, float minScore) {
        List<Face> order = new ArrayList<Face>(faces);
        Collections.sort(order, new Comparator<Face>() { // stable: equal scores keep their order, as std::stable_sort
            @Override
            public int compare(Face a, Face b) {
                return Float.compare(b.score, a.score);
            }
        });
        List<Face> kept = new ArrayList<Face>();
        for (Face f : order) {
            if (!(f.score > minScore)) continue;
            if (kept.size() >= TOP_K) break;
            boolean keep = true;
            for (Face k : kept) {
                if (iou(f, k) > NMS) {
                    keep = false;
                    break;
                }
            }
            if (keep) kept.add(f);
        }
        return kept;
    }

    private static double iou(Face a, Face b) {
        int ax = (int) a.x, ay = (int) a.y, aw = (int) a.w, ah = (int) a.h;
        int bx = (int) b.x, by = (int) b.y, bw = (int) b.w, bh = (int) b.h;
        int x1 = Math.max(ax, bx), y1 = Math.max(ay, by), x2 = Math.min(ax + aw, bx + bw), y2 = Math.min(ay + ah, by + bh);
        double inter = x2 > x1 && y2 > y1 ? (double) (x2 - x1) * (y2 - y1) : 0;
        double union = (double) aw * ah + (double) bw * bh - inter;
        return union <= 0 ? 0 : inter / union;
    }

    // ------------------------------------------------------------------ alignment

    /**
     * The similarity transform (2×3, row-major) taking the five landmarks onto the template, least squares (OpenCV's
     * Umeyama without a reflection, which for two dimensions comes to this closed form).
     */
    static double[] similarity(float[] lm) {
        double sx = 0, sy = 0;
        for (int i = 0; i < 5; i++) {
            sx += lm[2 * i];
            sy += lm[2 * i + 1];
        }
        sx /= 5;
        sy /= 5;
        double num = 0, cross = 0, den = 0;
        for (int i = 0; i < 5; i++) {
            double px = lm[2 * i] - sx, py = lm[2 * i + 1] - sy;
            double qx = TEMPLATE[i][0] - TEMPLATE_MEAN[0], qy = TEMPLATE[i][1] - TEMPLATE_MEAN[1];
            num += px * qx + py * qy;
            cross += px * qy - py * qx;
            den += px * px + py * py;
        }
        double a = den > 0 ? num / den : 1, b = den > 0 ? cross / den : 0;
        return new double[]{a, -b, TEMPLATE_MEAN[0] - (a * sx - b * sy), b, a, TEMPLATE_MEAN[1] - (b * sx + a * sy)};
    }

    /** The face's 112×112 crop (ARGB), as OpenCV's alignCrop: warpAffine, bilinear, black outside the image. */
    public static int[] align(int[] argb, int w, int h, float[] landmarks) {
        double[] m = similarity(landmarks);
        // the inverse: from the crop back into the image
        double det = m[0] * m[4] - m[1] * m[3];
        double i00 = m[4] / det, i01 = -m[1] / det, i10 = -m[3] / det, i11 = m[0] / det;
        double i02 = -(i00 * m[2] + i01 * m[5]), i12 = -(i10 * m[2] + i11 * m[5]);
        int[] out = new int[CROP * CROP];
        for (int y = 0; y < CROP; y++) {
            for (int x = 0; x < CROP; x++) {
                double fx = i00 * x + i01 * y + i02, fy = i10 * x + i11 * y + i12;
                int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
                double ax = fx - x0, ay = fy - y0;
                double r = 0, g = 0, bl = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int sx = x0 + dx, sy = y0 + dy;
                        if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue;
                        double k = (dx == 0 ? 1 - ax : ax) * (dy == 0 ? 1 - ay : ay);
                        int p = argb[sy * w + sx];
                        r += k * ((p >> 16) & 0xFF);
                        g += k * ((p >> 8) & 0xFF);
                        bl += k * (p & 0xFF);
                    }
                }
                out[y * CROP + x] = 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(bl);
            }
        }
        return out;
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    // ------------------------------------------------------------------ recognition

    /** SFace's vector of an aligned crop, unit length: RGB planes, 0..255 (OpenCV's blobFromImage with swapRB). */
    public float[] embed(int[] crop) throws OrtException {
        int plane = CROP * CROP;
        float[] blob = new float[3 * plane];
        for (int i = 0; i < plane; i++) {
            int p = crop[i];
            blob[i] = (p >> 16) & 0xFF;
            blob[plane + i] = (p >> 8) & 0xFF;
            blob[2 * plane + i] = p & 0xFF;
        }
        OnnxTensor in = OnnxTensor.createTensor(env, FloatBuffer.wrap(blob), new long[]{1, 3, CROP, CROP});
        try {
            Map<String, OnnxTensor> feed = new HashMap<String, OnnxTensor>();
            feed.put(recIn, in);
            OrtSession.Result r = rec.run(feed);
            try {
                float[] v = floats(r, recOut);
                double n = 0;
                for (float x : v) n += x * x;
                n = Math.sqrt(n);
                if (n > 0) for (int i = 0; i < v.length; i++) v[i] /= n;
                return v;
            } finally {
                r.close();
            }
        } finally {
            in.close();
        }
    }

    /** The faces of an image with their vectors; faces smaller than {@code minSize} pixels (the shorter side) are left out. */
    /**
     * The faces of an image with their vectors; faces smaller than {@code minSize} pixels (the shorter side) are left
     * out. A dim or flat image is looked at once more with its levels stretched ({@link #enhance}): the faces found
     * only there join, their vectors from the stretched pixels.
     */
    @Override
    public List<Face> faces(int[] argb, int w, int h, int minSize) throws OrtException {
        List<Face> out = new ArrayList<Face>();
        for (Face f : detect(argb, w, h)) {
            if (Math.min(f.w, f.h) < minSize) continue;
            f.emb = embed(align(argb, w, h, f.landmarks));
            out.add(f);
        }
        int[] bright = enhance(argb);
        if (bright != null) {
            List<Face> more = new ArrayList<Face>();
            for (Face f : detect(bright, w, h)) {
                if (Math.min(f.w, f.h) < minSize) continue;
                boolean seen = false;
                for (Face o : out) seen |= iou(f, o) > NMS;
                if (seen) continue;
                f.emb = embed(align(bright, w, h, f.landmarks));
                more.add(f);
            }
            out.addAll(more);
            Collections.sort(out, new Comparator<Face>() {
                @Override
                public int compare(Face a, Face b) {
                    return Float.compare(b.score, a.score);
                }
            });
        }
        return out;
    }

    /**
     * The image with its levels stretched (the 1st to the 99th percentile of brightness to the full range, and
     * lightened when dark), or null when it is bright and contrasty enough as it is.
     */
    public static int[] enhance(int[] argb) {
        int[] hist = new int[256];
        long sum = 0;
        for (int p : argb) {
            int y = (((p >> 16) & 0xFF) * 77 + ((p >> 8) & 0xFF) * 150 + (p & 0xFF) * 29) >> 8;
            hist[y]++;
            sum += y;
        }
        int n = argb.length;
        if (n == 0) return null;
        double mean = (double) sum / n;
        int lo = 0, hi = 255;
        for (int c = 0; lo < 255 && (c += hist[lo]) < n / 100; lo++) {
        }
        for (int c = 0; hi > 0 && (c += hist[hi]) < n / 100; hi--) {
        }
        if (hi - lo >= 160 && mean >= 90) return null;
        double range = Math.max(16, hi - lo);
        double m = Math.max(0.02, Math.min(0.98, (mean - lo) / range));
        // a dark picture: its middle grey to about 0.45
        double gamma = mean < 90 ? Math.max(0.35, Math.min(1.0, Math.log(0.45) / Math.log(m))) : 1.0;
        int[] lut = new int[256];
        for (int v = 0; v < 256; v++) {
            double x = Math.max(0, Math.min(1, (v - lo) / range));
            lut[v] = (int) Math.round(255 * Math.pow(x, gamma));
        }
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int p = argb[i];
            out[i] = 0xFF000000 | (lut[(p >> 16) & 0xFF] << 16) | (lut[(p >> 8) & 0xFF] << 8) | lut[p & 0xFF];
        }
        return out;
    }

    public static double cosine(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) s += a[i] * b[i];
        return s;
    }

    // ------------------------------------------------------------------ pixels

    /** Bilinear resize of ARGB pixels (pixel centres aligned, as OpenCV's INTER_LINEAR). */
    public static int[] resize(int[] src, int w, int h, int nw, int nh) {
        int[] out = new int[nw * nh];
        double sx = (double) w / nw, sy = (double) h / nh;
        for (int y = 0; y < nh; y++) {
            double fy = (y + 0.5) * sy - 0.5;
            int y0 = (int) Math.floor(fy);
            double ay = fy - y0;
            int ya = Math.max(0, Math.min(h - 1, y0)), yb = Math.max(0, Math.min(h - 1, y0 + 1));
            for (int x = 0; x < nw; x++) {
                double fx = (x + 0.5) * sx - 0.5;
                int x0 = (int) Math.floor(fx);
                double ax = fx - x0;
                int xa = Math.max(0, Math.min(w - 1, x0)), xb = Math.max(0, Math.min(w - 1, x0 + 1));
                int p00 = src[ya * w + xa], p01 = src[ya * w + xb], p10 = src[yb * w + xa], p11 = src[yb * w + xb];
                int c = 0xFF000000;
                for (int sh = 0; sh <= 16; sh += 8) {
                    double v = (1 - ay) * ((1 - ax) * ((p00 >> sh) & 0xFF) + ax * ((p01 >> sh) & 0xFF))
                            + ay * ((1 - ax) * ((p10 >> sh) & 0xFF) + ax * ((p11 >> sh) & 0xFF));
                    c |= clamp(v) << sh;
                }
                out[y * nw + x] = c;
            }
        }
        return out;
    }

    @Override
    public void close() {
        try {
            det.close();
        } catch (OrtException ignored) {
            // closing anyway
        }
        try {
            rec.close();
        } catch (OrtException ignored) {
            // closing anyway
        }
    }
}
