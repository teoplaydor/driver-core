import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import io.github.teoplaydor.semsearch.core.FaceModel;
import io.github.teoplaydor.semsearch.core.MiniJson;

/**
 * FaceModel against OpenCV's FaceDetectorYN / FaceRecognizerSF on stand-in YuNet and SFace graphs (same inputs and
 * outputs as the real ones; make_face_models.py, reference_faces.py): the same faces (boxes, landmarks, scores, after
 * NMS), the same aligned crops — for detected faces and for hand-placed landmarks, upright, tilted, small, mirrored,
 * half outside the image — the same vectors; a graph of one input size gives the faces of the fitted image (fitted as
 * cv2.resize does).
 * usage: FaceModelTest <models dir> <reference.json>
 */
public class FaceModelTest {
    static int bad;

    static void check(boolean ok, String what) {
        if (!ok) bad++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static int[] argb(BufferedImage b) {
        int[] px = new int[b.getWidth() * b.getHeight()];
        b.getRGB(0, 0, b.getWidth(), b.getHeight(), px, 0, b.getWidth());
        return px;
    }

    static float[] floats(Object list) {
        List<Object> l = MiniJson.arr(list);
        float[] f = new float[l.size()];
        for (int i = 0; i < f.length; i++) f[i] = ((Number) l.get(i)).floatValue();
        return f;
    }

    /** Mean and largest difference of two crops' channels. */
    static double[] diff(int[] a, int[] b) {
        double sum = 0, max = 0;
        for (int i = 0; i < a.length; i++) {
            for (int sh = 0; sh <= 16; sh += 8) {
                double d = Math.abs(((a[i] >> sh) & 0xFF) - ((b[i] >> sh) & 0xFF));
                sum += d;
                max = Math.max(max, d);
            }
        }
        return new double[]{sum / (3.0 * a.length), max};
    }

    /** Largest difference of the rows (coordinates and score), in the order given. */
    static double rowsDiff(List<float[]> a, List<float[]> b) {
        if (a.size() != b.size()) return Double.MAX_VALUE;
        double m = 0;
        for (int i = 0; i < a.size(); i++) for (int k = 0; k < 15; k++) m = Math.max(m, Math.abs(a.get(i)[k] - b.get(i)[k]));
        return m;
    }

    public static void main(String[] args) throws Exception {
        File models = new File(args[0]);
        List<Object> ref = MiniJson.arr(MiniJson.parse(new String(Files.readAllBytes(new File(args[1]).toPath()), "UTF-8")));
        FaceModel fm = new FaceModel(new File(models, "yunet.onnx"), new File(models, "sface.onnx"), 2);
        FaceModel fixed = new FaceModel(new File(models, "yunet_fixed.onnx"), new File(models, "sface.onnx"), 2);
        System.out.println("  " + fm.describe() + " | fixed: " + fixed.describe());
        int faces = 0, crops = 0;
        double worstRow = 0, worstMean = 0, worstMax = 0, worstCos = 1, worstFixed = 0, worstResize = 0, worstResizeMax = 0;
        boolean sameCount = true, fixedCount = true;
        for (Object o : ref) {
            Map<String, Object> e = MiniJson.obj(o);
            BufferedImage img = ImageIO.read(new File(MiniJson.str(e, "image", "")));
            int w = img.getWidth(), h = img.getHeight();
            int[] px = argb(img);
            List<FaceModel.Face> got = fm.detect(px, w, h);
            List<Object> want = MiniJson.arr(e.get("faces"));
            sameCount &= got.size() == want.size();
            List<float[]> gotRows = new ArrayList<float[]>(), wantRows = new ArrayList<float[]>();
            for (FaceModel.Face f : got) gotRows.add(f.row());
            for (Object wo : want) wantRows.add(floats(MiniJson.obj(wo).get("row")));
            worstRow = Math.max(worstRow, rowsDiff(gotRows, wantRows));
            // crops and vectors of the faces OpenCV found (its rows, so that a decoding difference does not hide here)
            for (Object wo : want) {
                Map<String, Object> wf = MiniJson.obj(wo);
                float[] row = floats(wf.get("row"));
                float[] lm = new float[10];
                System.arraycopy(row, 4, lm, 0, 10);
                int[] crop = FaceModel.align(px, w, h, lm);
                double[] d = diff(crop, argb(ImageIO.read(new File(MiniJson.str(wf, "crop", "")))));
                worstMean = Math.max(worstMean, d[0]);
                worstMax = Math.max(worstMax, d[1]);
                worstCos = Math.min(worstCos, FaceModel.cosine(fm.embed(crop), floats(wf.get("emb"))));
                faces++;
            }
            for (Object ao : MiniJson.arr(e.get("aligned"))) {
                Map<String, Object> a = MiniJson.obj(ao);
                int[] crop = FaceModel.align(px, w, h, floats(a.get("landmarks")));
                double[] d = diff(crop, argb(ImageIO.read(new File(MiniJson.str(a, "crop", "")))));
                worstMean = Math.max(worstMean, d[0]);
                worstMax = Math.max(worstMax, d[1]);
                worstCos = Math.min(worstCos, FaceModel.cosine(fm.embed(crop), floats(a.get("emb"))));
                crops++;
            }
            // a graph of one size: the image fitted into it — the faces of the fitted image, scaled back
            float scale = Math.min(320f / w, 320f / h);
            int iw = Math.max(1, Math.min(320, Math.round(w * scale))), ih = Math.max(1, Math.min(320, Math.round(h * scale)));
            int[] small = FaceModel.resize(px, w, h, iw, ih);
            double[] rd = diff(small, argb(ImageIO.read(new File(MiniJson.str(e, "small", "")))));
            worstResize = Math.max(worstResize, rd[0]);
            worstResizeMax = Math.max(worstResizeMax, rd[1]);
            List<FaceModel.Face> gf = fixed.detect(px, w, h);
            List<float[]> fr = new ArrayList<float[]>(), wr = new ArrayList<float[]>();
            for (FaceModel.Face f : gf) fr.add(f.row());
            for (FaceModel.Face f : fm.detect(small, iw, ih)) {
                float[] r = f.row();
                for (int k = 0; k < 14; k++) r[k] /= scale;
                wr.add(r);
            }
            fixedCount &= fr.size() == wr.size() && !fr.isEmpty();
            double fd = rowsDiff(fr, wr);
            worstFixed = Math.max(worstFixed, fd);
            System.out.println(String.format(java.util.Locale.ROOT, "  %s %dx%d: %d faces (OpenCV %d); fitted into 320×320: %d, "
                            + "resize vs OpenCV mean %.3f",
                    new File(MiniJson.str(e, "image", "")).getName(), w, h, got.size(), want.size(), gf.size(), rd[0]));
        }
        check(sameCount && worstRow < 2e-3, "detection: the same faces as OpenCV after NMS, in its order (rows within "
                + String.format(java.util.Locale.ROOT, "%.5f", worstRow) + ")");
        // OpenCV's warpAffine samples at 1/32 pixel (fixed point): on a sharp edge that is a few levels
        check(worstMean < 0.3 && worstMax <= 8, String.format(java.util.Locale.ROOT,
                "alignment of %d faces and %d hand-placed landmark sets: crops as OpenCV's (mean %.3f, largest %.0f levels "
                        + "— its 1/32 pixel grid on sharp edges)", faces, crops, worstMean, worstMax));
        check(worstCos > 0.9999, String.format(java.util.Locale.ROOT, "SFace: vectors as OpenCV's (cosine ≥ %.6f)", worstCos));
        check(fixedCount && worstFixed < 1e-3, String.format(java.util.Locale.ROOT,
                "a graph of one size: the faces of the image fitted into it, scaled back (within %.5f px)", worstFixed));
        check(worstResize < 0.6 && worstResizeMax <= 2, String.format(java.util.Locale.ROOT,
                "the fitting resize as cv2.resize INTER_LINEAR (mean %.3f, largest %.0f levels)", worstResize, worstResizeMax));

        // faces(): the vectors are unit length and a face under the size limit is left out
        Map<String, Object> e0 = MiniJson.obj(ref.get(0));
        BufferedImage img = ImageIO.read(new File(MiniJson.str(e0, "image", "")));
        List<FaceModel.Face> all = fm.faces(argb(img), img.getWidth(), img.getHeight(), 0);
        List<FaceModel.Face> big = fm.faces(argb(img), img.getWidth(), img.getHeight(), 30);
        double n = 0;
        for (float x : all.get(0).emb) n += x * x;
        int under = 0;
        for (FaceModel.Face f : all) if (Math.min(f.w, f.h) < 30) under++;
        check(Math.abs(n - 1) < 1e-4 && big.size() == all.size() - under && under > 0,
                "faces(): unit vectors; " + under + " of " + all.size() + " under 30 px left out");
        fm.close();
        fixed.close();
        System.out.println(bad == 0 ? "FACES OK" : bad + " FAILED");
        if (bad != 0) System.exit(1);
    }
}
