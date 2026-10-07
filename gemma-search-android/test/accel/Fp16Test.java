import java.io.File;
import java.util.ArrayList;
import java.util.List;

import io.github.teoplaydor.semsearch.core.EmbeddingGemma2;
import io.github.teoplaydor.semsearch.core.HfRepo;
import io.github.teoplaydor.semsearch.core.ImagePreprocessor;
import io.github.teoplaydor.semsearch.core.PatternSource;

/**
 * The fp16 variant for the GPU: a half-precision vision encoder (fp16 inputs and outputs) gives the same
 * vectors as the fp32 one through the unchanged pipeline (pixel values converted to fp16, features back to
 * fp32 for the int8 text model), and the download plan / manifest carry the fp16 graph with its data.
 * usage: Fp16Test <dummy model dir with onnx/vision_encoder_fp16.onnx>
 */
public class Fp16Test {
    static int bad;

    static void check(boolean ok, String what) {
        if (!ok) bad++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static double cos(float[] a, float[] b) {
        double d = 0;
        for (int i = 0; i < a.length; i++) d += a[i] * b[i];
        return d;
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        File text = new File(dir, "onnx/model.onnx");
        EmbeddingGemma2 full = new EmbeddingGemma2(dir, text, new File(dir, "onnx/vision_encoder.onnx"), 2);
        EmbeddingGemma2 half = new EmbeddingGemma2(dir, text, new File(dir, "onnx/vision_encoder_fp16.onnx"), 2);
        List<ImagePreprocessor.Source> imgs = new ArrayList<ImagePreprocessor.Source>();
        int[][] sizes = {{768, 768}, {576, 1104}, {1152, 528}};
        for (int i = 0; i < sizes.length; i++) imgs.add(new PatternSource(sizes[i][0], sizes[i][1], i));
        for (int budget : new int[]{70, 280}) {
            float[][] a = full.embedImages(imgs, budget), b = half.embedImages(imgs, budget);
            double worst = 1;
            for (int i = 0; i < a.length; i++) worst = Math.min(worst, cos(a[i], b[i]));
            check(worst >= 0.999, String.format("budget %d: fp16 vision = fp32, worst cos %.6f", budget, worst));
        }
        full.close();
        half.close();

        List<HfRepo.RemoteFile> files = new ArrayList<HfRepo.RemoteFile>();
        java.lang.reflect.Constructor<HfRepo.RemoteFile> k = HfRepo.RemoteFile.class.getDeclaredConstructor(String.class, long.class);
        k.setAccessible(true);
        for (String f : new String[]{"config.json", "tokenizer.json", "onnx/model_q4.onnx", "onnx/model_q4.onnx_data",
                "onnx/vision_encoder.onnx", "onnx/vision_encoder.onnx_data", "onnx/vision_encoder_q4.onnx",
                "onnx/vision_encoder_q4.onnx_data", "onnx/vision_encoder_fp16.onnx", "onnx/vision_encoder_fp16.onnx_data"}) {
            files.add(k.newInstance(f, 100));
        }
        HfRepo.Plan p = HfRepo.plan(files, true, false, true);
        List<String> paths = new ArrayList<String>();
        for (HfRepo.RemoteFile f : p.files) paths.add(f.path);
        check("onnx/vision_encoder_fp16.onnx".equals(p.fp16Vision) && "onnx/vision_encoder_q4.onnx".equals(p.visionModel)
                && paths.contains("onnx/vision_encoder_fp16.onnx_data") && !paths.contains("onnx/vision_encoder.onnx")
                && p.totalBytes == 100L * paths.size(), "plan: q4 for the CPU + fp16 graph with its data, no fp32 (" + paths.size() + " files)");
        HfRepo.Plan both = HfRepo.plan(files, true, true, true);
        check(both.accelVision != null && both.fp16Vision != null && both.files.size() == paths.size() + 2, "fp16 and fp32 together");
        File m = File.createTempFile("manifest", ".json");
        HfRepo.saveManifest(p, "onnx-community/embeddinggemma-2-ONNX", m);
        HfRepo.Plan back = HfRepo.loadManifest(m);
        check("onnx/vision_encoder_fp16.onnx".equals(back.fp16Vision) && back.accelVision == null && back.files.size() == p.files.size(),
                "manifest round-trip keeps the fp16 graph");
        m.delete();
        try {
            files.remove(files.size() - 1);
            files.remove(files.size() - 1);
            HfRepo.plan(files, true, false, true);
            check(false, "repo without fp16 graph is an error");
        } catch (java.io.IOException e) {
            check(true, "repo without fp16 graph: " + e.getMessage());
        }
        System.out.println(bad == 0 ? "FP16 OK" : bad + " FAILED");
        if (bad != 0) System.exit(1);
    }
}
