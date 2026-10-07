import io.github.teoplaydor.semsearch.core.EmbeddingGemma2;
import io.github.teoplaydor.semsearch.core.ImagePreprocessor;
import io.github.teoplaydor.semsearch.core.MiniJson;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Runs the Java EmbeddingGemma 2 pipeline on the dummy model and compares input ids and
 * embeddings with transformers.js (test/parity/reference.mjs).
 * usage: PipelineParityTest <model_dir> <reference.json>
 */
public class PipelineParityTest {
    static final class Pattern implements ImagePreprocessor.Source {
        final int w, h, k;
        Pattern(int w, int h, int k) { this.w = w; this.h = h; this.k = k; }
        public int width() { return w; }
        public int height() { return h; }
        public int[] argb(int tw, int th) {
            if (tw != w || th != h) throw new IllegalStateException("unexpected resize " + w + "x" + h + " -> " + tw + "x" + th);
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int r = (x * 7 + y * 13 + k * 40) % 256, g = (x * 3 + y * 5 + 50 + k * 40) % 256, b = ((x ^ y) + k * 40) % 256;
                    px[y * w + x] = 0xff000000 | (r << 16) | (g << 8) | b;
                }
            return px;
        }
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        EmbeddingGemma2 m = new EmbeddingGemma2(dir, new File(dir, "onnx/model.onnx"), new File(dir, "onnx/vision_encoder.onnx"), 2);
        List<Object> ref = MiniJson.arr(MiniJson.parse(new String(java.nio.file.Files.readAllBytes(new File(args[1]).toPath()), StandardCharsets.UTF_8)));
        int bad = 0;
        for (Object o : ref) {
            Map<String, Object> c = MiniJson.obj(o);
            String label = (String) c.get("label");
            float[] emb;
            int[] ids = null;
            if (label.startsWith("text:")) {
                String text = label.substring(5);
                ids = m.tokenizer().encode(text);
                emb = m.embedText(text, 8192);
            } else if (label.startsWith("image:")) {
                String[] wh = label.substring(6).split("x");
                emb = m.embedImage(new Pattern(Integer.parseInt(wh[0]), Integer.parseInt(wh[1]), 0), 0);
            } else {
                List<ImagePreprocessor.Source> frames = new ArrayList<ImagePreprocessor.Source>();
                for (int k = 0; k < 3; k++) frames.add(new Pattern(384, 384, k));
                emb = m.embedVideo(frames, 0);
            }
            List<Object> exp = MiniJson.arr(c.get("embedding"));
            double maxDiff = 0;
            for (int i = 0; i < emb.length; i++) maxDiff = Math.max(maxDiff, Math.abs(emb[i] - ((Number) exp.get(i)).doubleValue()));
            boolean idsOk = true;
            if (ids != null) {
                List<Object> e = MiniJson.arr(c.get("input_ids"));
                idsOk = e.size() == ids.length;
                for (int i = 0; idsOk && i < ids.length; i++) idsOk = ((Number) e.get(i)).intValue() == ids[i];
            }
            boolean ok = maxDiff < 1e-4 && emb.length == exp.size() && idsOk;
            if (!ok) bad++;
            System.out.printf("%-60s dim=%d maxDiff=%.2e ids=%s %s%n", label, emb.length, maxDiff, idsOk ? "ok" : "MISMATCH", ok ? "OK" : "FAIL");
        }
        m.close();
        System.out.println(bad == 0 ? "ALL MATCH" : bad + " FAILED");
        if (bad > 0) System.exit(1);
    }
}
