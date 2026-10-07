import io.github.teoplaydor.semsearch.core.*;

import java.io.File;

/**
 * EmbeddingGemma2 on q4 graphs: plain vs int8-compute (OnnxPatcher) must give the same embeddings,
 * and asking for the GPU on a build without WebGPU must fail cleanly (the app then falls back).
 * usage: AccelPipelineTest <dummy model dir with onnx/*_q4.onnx>
 */
public class AccelPipelineTest {
    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        ModelConfig cfg = EmbeddingGemma2.loadConfig(dir);
        HfTokenizer tok = EmbeddingGemma2.loadTokenizer(dir);
        File text = new File(dir, "onnx/model_q4.onnx"), vision = new File(dir, "onnx/vision_encoder_q4.onnx");
        File text8 = new File(dir, "onnx/model_q4.int8.onnx"), vision8 = new File(dir, "onnx/vision_encoder_q4.int8.onnx");
        int nv = OnnxPatcher.setMatMulNBitsAccuracy(vision, vision8, 4);
        int nt = OnnxPatcher.setMatMulNBitsAccuracy(text, text8, 4);
        System.out.println("patched nodes: vision " + nv + ", text " + nt);
        if (nv == 0) throw new AssertionError("vision encoder has no MatMulNBits");

        EmbeddingGemma2 plain = new EmbeddingGemma2(cfg, tok, text, vision, 2, false);
        float[] e0 = plain.embedImage(new PatternSource(640, 480, 2), 70);
        float[] q0 = plain.embedQuery("кот на диване");
        plain.close();
        EmbeddingGemma2 int8 = new EmbeddingGemma2(cfg, tok, nt > 0 ? text8 : text, vision8, 2, false);
        float[] e1 = int8.embedImage(new PatternSource(640, 480, 2), 70);
        float[] q1 = int8.embedQuery("кот на диване");
        int8.close();
        double ci = 0, cq = 0;
        for (int i = 0; i < e0.length; i++) ci += e0[i] * e1[i];
        for (int i = 0; i < q0.length; i++) cq += q0[i] * q1[i];
        System.out.printf("int8 vs plain: image cos %.5f, text cos %.5f%n", ci, cq);
        if (ci < 0.98 || cq < 0.98) throw new AssertionError("int8 drift too large");

        try {
            EmbeddingGemma2 gpu = new EmbeddingGemma2(cfg, tok, text, vision, 2, true);
            float[] eg = gpu.embedImage(new PatternSource(640, 480, 2), 70);
            gpu.close();
            double cg = 0;
            for (int i = 0; i < e0.length; i++) cg += e0[i] * eg[i];
            System.out.printf("GPU available here: cos %.5f%n", cg);
        } catch (java.io.IOException expected) {
            System.out.println("GPU unavailable, clean error: " + expected.getMessage());
        }
        System.out.println("ALL OK");
    }
}
