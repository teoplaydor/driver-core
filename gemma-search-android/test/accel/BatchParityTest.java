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
            }
            m.close();
        }
        System.out.println("ALL OK");
    }
}
