import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.teoplaydor.semsearch.core.EmbeddingGemma2;
import io.github.teoplaydor.semsearch.core.ImagePreprocessor;
import io.github.teoplaydor.semsearch.core.OnnxPatcher;
import io.github.teoplaydor.semsearch.core.PatternSource;

/**
 * The NPU path of EmbeddingGemma2 (NNAPI needs static shapes): symbolic input dimensions are read from
 * the graph without loading it, every image is padded to the largest budget's patch count and runs are
 * filled up to the fixed batch. All of that must give exactly the plain results: photos of different
 * proportions and budgets, a short last run, video frames (their own, smaller budget).
 */
public class NpuShapesTest {
    static double cos(float[] a, float[] b) {
        double d = 0;
        for (int i = 0; i < a.length; i++) d += a[i] * b[i];
        return d;
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        int bad = 0;
        Map<String, List<String>> dims = OnnxPatcher.inputDims(new File(dir, "onnx/vision_encoder.onnx"));
        System.out.println("vision inputs: " + dims);
        if (!dims.containsKey("pixel_values") || !"batch".equals(dims.get("pixel_values").get(0))
                || !"patches".equals(dims.get("pixel_values").get(1))) {
            System.out.println("FAIL input dims");
            bad++;
        }

        File text = new File(dir, "onnx/model.onnx"), vision = new File(dir, "onnx/vision_encoder.onnx");
        EmbeddingGemma2 plain = new EmbeddingGemma2(dir, text, vision, 2);
        EmbeddingGemma2 fixed = new EmbeddingGemma2(dir, text, vision, 2);
        List<ImagePreprocessor.Source> imgs = new ArrayList<ImagePreprocessor.Source>();
        int[][] sizes = {{768, 768}, {576, 1104}, {1152, 528}, {640, 480}, {300, 900}};
        for (int i = 0; i < sizes.length; i++) imgs.add(new PatternSource(sizes[i][0], sizes[i][1], i));
        for (int budget : new int[]{70, 140, 280}) {
            fixed.staticShapesForTest(3, 280); // graph compiled for the largest budget, runs of 3
            float[][] want = new float[imgs.size()][];
            for (int i = 0; i < imgs.size(); i++) want[i] = plain.embedImage(imgs.get(i), budget);
            float[][] got = fixed.embedImages(imgs, budget); // 5 photos: a run of 3 and a padded run of 2
            double worst = 1;
            for (int i = 0; i < imgs.size(); i++) worst = Math.min(worst, cos(want[i], got[i]));
            boolean ok = worst >= 0.999999;
            if (!ok) bad++;
            System.out.printf("%s budget %d: 5 photos padded to 2520 patches, runs of 3, worst cos %.7f%n",
                    ok ? "ok  " : "FAIL", budget, worst);
        }
        List<ImagePreprocessor.Source> frames = new ArrayList<ImagePreprocessor.Source>();
        for (int k = 0; k < 3; k++) frames.add(new PatternSource(384, 384, 10 + k));
        double v = cos(plain.embedVideo(frames, 0), fixed.embedVideo(frames, 0));
        boolean ok = v >= 0.999999;
        if (!ok) bad++;
        System.out.printf("%s video: 3 frames padded to the photo graph, cos %.7f%n", ok ? "ok  " : "FAIL", v);
        plain.close();
        fixed.close();

        // File plan on a listing shaped like onnx-community/embeddinggemma-2-ONNX: 4-bit graphs for the CPU,
        // plus (for the NPU) the fp32 vision encoder with its external data.
        List<io.github.teoplaydor.semsearch.core.HfRepo.RemoteFile> files = new ArrayList<io.github.teoplaydor.semsearch.core.HfRepo.RemoteFile>();
        for (String f : new String[]{"config.json", "tokenizer.json", "tokenizer_config.json", "preprocessor_config.json",
                "processor_config.json", "onnx/model_q4.onnx", "onnx/model_q4.onnx_data", "onnx/model.onnx", "onnx/model.onnx_data",
                "onnx/vision_encoder.onnx", "onnx/vision_encoder.onnx_data", "onnx/vision_encoder_q4.onnx",
                "onnx/vision_encoder_q4.onnx_data", "onnx/vision_encoder_fp16.onnx", "onnx/vision_encoder_fp16.onnx_data"}) {
            java.lang.reflect.Constructor<io.github.teoplaydor.semsearch.core.HfRepo.RemoteFile> k =
                    io.github.teoplaydor.semsearch.core.HfRepo.RemoteFile.class.getDeclaredConstructor(String.class, long.class);
            k.setAccessible(true);
            files.add(k.newInstance(f, 100));
        }
        io.github.teoplaydor.semsearch.core.HfRepo.Plan cpu = io.github.teoplaydor.semsearch.core.HfRepo.plan(files, true);
        io.github.teoplaydor.semsearch.core.HfRepo.Plan npu = io.github.teoplaydor.semsearch.core.HfRepo.plan(files, true, true);
        List<String> np = new ArrayList<String>();
        for (io.github.teoplaydor.semsearch.core.HfRepo.RemoteFile f : npu.files) np.add(f.path);
        boolean planOk = "onnx/vision_encoder_q4.onnx".equals(cpu.visionModel) && cpu.accelVision == null
                && "onnx/vision_encoder_q4.onnx".equals(npu.visionModel) && "onnx/vision_encoder.onnx".equals(npu.accelVision)
                && np.contains("onnx/vision_encoder.onnx_data") && !np.contains("onnx/vision_encoder_fp16.onnx")
                && npu.files.size() == cpu.files.size() + 2;
        if (!planOk) bad++;
        System.out.println((planOk ? "ok   " : "FAIL ") + "plan for the NPU: + fp32 vision encoder and its data (" + npu.files.size() + " files)");
        System.out.println(bad == 0 ? "ALL OK" : bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }
}
