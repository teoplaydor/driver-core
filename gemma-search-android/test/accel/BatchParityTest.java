import io.github.teoplaydor.semsearch.core.*;

import java.io.File;
import java.util.*;

/**
 * embedImages (one vision-encoder run for several photos of different aspect ratios) must give
 * the same embeddings as embedImage one by one. usage: BatchParityTest <dummy model dir>
 */
public class BatchParityTest {
    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        ModelConfig cfg = EmbeddingGemma2.loadConfig(dir);
        HfTokenizer tok = EmbeddingGemma2.loadTokenizer(dir);
        String[][] variants = {{"onnx/model.onnx", "onnx/vision_encoder.onnx"},
                {"onnx/model_q4.onnx", "onnx/vision_encoder_q4.onnx"}};
        for (String[] v : variants) {
            EmbeddingGemma2 m = new EmbeddingGemma2(cfg, tok, new File(dir, v[0]), new File(dir, v[1]), 2, false);
            List<ImagePreprocessor.Source> imgs = Arrays.<ImagePreprocessor.Source>asList(
                    new PatternSource(640, 480, 1), new PatternSource(300, 900, 2), new PatternSource(1200, 500, 3),
                    new PatternSource(500, 500, 4));
            for (int budget : new int[]{70, 280}) {
                float[][] batch = m.embedImages(imgs, budget);
                double worst = 1;
                for (int i = 0; i < imgs.size(); i++) {
                    float[] one = m.embedImage(imgs.get(i), budget);
                    double c = 0;
                    for (int j = 0; j < one.length; j++) c += one[j] * batch[i][j];
                    worst = Math.min(worst, c);
                }
                System.out.printf("%s budget %d: batch of %d vs single, worst cos %.6f%n", v[1], budget, imgs.size(), worst);
                if (worst < 0.9999) throw new AssertionError("batch result differs");
                // the indexing pipeline: the text stage of the first two photos on another thread while the vision
                // stage takes the other two (both sessions run at once) — the same embeddings
                final EmbeddingGemma2 mm = m;
                final Object first = m.startImages(imgs.subList(0, 2), budget);
                java.util.concurrent.ExecutorService text = java.util.concurrent.Executors.newSingleThreadExecutor();
                java.util.concurrent.Future<float[][]> firstText = text.submit(new java.util.concurrent.Callable<float[][]>() {
                    public float[][] call() throws Exception {
                        return mm.finishImages(first);
                    }
                });
                Object second = m.startImages(imgs.subList(2, 4), budget);
                float[][] staged = new float[4][];
                float[][] f = firstText.get(), g = text.submit(() -> mm.finishImages(second)).get();
                text.shutdown();
                staged[0] = f[0];
                staged[1] = f[1];
                staged[2] = g[0];
                staged[3] = g[1];
                double worstStaged = 1;
                for (int i = 0; i < 4; i++) {
                    double c = 0;
                    for (int j = 0; j < staged[i].length; j++) c += staged[i][j] * batch[i][j];
                    worstStaged = Math.min(worstStaged, c);
                }
                System.out.printf("%s budget %d: pipeline (text on another thread during the next vision run) vs batch, worst cos %.6f%n",
                        v[1], budget, worstStaged);
                if (worstStaged < 0.9999) throw new AssertionError("pipeline result differs");
            }
            // close() waits for a text stage that is running, and a later one finds the model closed
            final EmbeddingGemma2 mc = m;
            final Object late = m.startImages(imgs.subList(0, 1), 70);
            m.close();
            boolean closedSeen = false;
            try {
                mc.finishImages(late);
            } catch (Embedder.Closed expected) {
                closedSeen = true;
            }
            System.out.println(v[0] + ": text stage after close: " + (closedSeen ? "Embedder.Closed" : "ran"));
            if (!closedSeen) throw new AssertionError("a text stage after close must say the model is closed");
        }
        System.out.println("ALL OK");
    }
}
