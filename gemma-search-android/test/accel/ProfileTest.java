import java.io.File;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import io.github.teoplaydor.semsearch.core.EmbeddingGemma2;
import io.github.teoplaydor.semsearch.core.ImagePreprocessor;
import io.github.teoplaydor.semsearch.core.OrtProfile;
import io.github.teoplaydor.semsearch.core.PatternSource;

/**
 * The benchmark's "what the accelerator leaves to the CPU" report: a real ONNX Runtime profile of the
 * vision encoder (only that session is profiled), summarised over the last run only — the warm-up must
 * not count — plus the summary of a GPU-shaped trace with CPU fallbacks and copies between the devices.
 */
public class ProfileTest {
    static int bad;

    static void check(boolean ok, String what) {
        if (!ok) bad++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static OrtProfile profile(File dir, File tmp, int runs) throws Exception {
        File text = new File(dir, "onnx/model.onnx"), vision = new File(dir, "onnx/vision_encoder.onnx");
        EmbeddingGemma2.profileVision = new File(tmp, "vision-profile").getPath();
        EmbeddingGemma2 m;
        try {
            m = new EmbeddingGemma2(dir, text, vision, 2);
        } finally {
            EmbeddingGemma2.profileVision = null;
        }
        List<ImagePreprocessor.Source> imgs = new ArrayList<ImagePreprocessor.Source>();
        imgs.add(new PatternSource(640, 480, 1));
        for (int i = 0; i < runs; i++) m.embedImages(imgs, 70);
        File f = m.endVisionProfiling();
        m.close();
        check(f.getName().startsWith("vision-profile") && f.length() > 0, runs + " runs: profile written to " + f.getName());
        OrtProfile p = OrtProfile.parse(f);
        f.delete();
        return p;
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        File tmp = new File(System.getProperty("java.io.tmpdir"), "profile-test-" + System.nanoTime());
        tmp.mkdirs();

        OrtProfile one = profile(dir, tmp, 1), three = profile(dir, tmp, 3);
        OrtProfile.Provider cpu1 = one.providers.get(OrtProfile.CPU), cpu3 = three.providers.get(OrtProfile.CPU);
        check(cpu1 != null && cpu1.nodes > 0, "vision nodes on the CPU: " + (cpu1 == null ? 0 : cpu1.nodes));
        check(cpu3 != null && cpu1 != null && cpu3.nodes == cpu1.nodes, "only the last run counts (warm-ups ignored)");
        check(one.runUs > 0 && cpu1 != null && cpu1.us <= one.runUs, "kernel time within the run: "
                + (cpu1 == null ? 0 : cpu1.us) + " of " + one.runUs + " µs");
        String[] left = tmp.list();
        check(left != null && left.length == 0, "the text model was not profiled (no other files)");
        String s = three.summary(4);
        System.out.println(s);
        check(s.contains("процессор:") && !s.contains("пересылок"), "CPU-only summary");
        tmp.delete();

        // A GPU run: a warm-up (ignored), then one run with ops the GPU did not take.
        String trace = "[\n"
                + ev("Session", "model_run", 0, 900, null, null) + ",\n"
                + ev("Node", "warm_kernel_time", 10, 500, "Gather", "CPUExecutionProvider") + ",\n"
                + ev("Session", "model_run", 1000, 1000, null, null) + ",\n"
                + ev("Node", "a_fence_before", 1001, 0, "MatMul", "WebGpuExecutionProvider") + ",\n"
                + ev("Node", "a_kernel_time", 1001, 50, "MatMul", "WebGpuExecutionProvider") + ",\n"
                + ev("Node", "m1_kernel_time", 1060, 20, "MemcpyToHost", "WebGpuExecutionProvider") + ",\n"
                + ev("Node", "g1_kernel_time", 1100, 300, "Gather", "CPUExecutionProvider") + ",\n"
                + ev("Node", "g2_kernel_time", 1400, 100, "Gather", "CPUExecutionProvider") + ",\n"
                + ev("Node", "r_kernel_time", 1500, 150, "Range", "CPUExecutionProvider") + ",\n"
                + ev("Node", "m2_kernel_time", 1700, 20, "MemcpyFromHost", "WebGpuExecutionProvider") + ",\n"
                + ev("Node", "b_kernel_time", 1750, 200, "Softmax", "WebGpuExecutionProvider") + "\n]";
        OrtProfile g = OrtProfile.parse(new StringReader(trace));
        String gs = g.summary(1);
        System.out.println(gs);
        check(g.runUs == 1000 && g.copies == 2, "GPU trace: last run, 2 copies");
        check(g.providers.get("WebGpuExecutionProvider").nodes == 4 && g.providers.get(OrtProfile.CPU).nodes == 3,
                "GPU trace: 4 nodes on the GPU, 3 on the CPU");
        check(gs.contains("видеокарта (WebGPU): 4 узлов") && gs.contains("процессор: 3 узлов, 0.00 с (55% прогона) — Gather ×2")
                && gs.contains(", …") && gs.contains("пересылок между процессором и ускорителем: 2"), "GPU summary");

        System.out.println(bad == 0 ? "PROFILE OK" : bad + " FAILED");
        if (bad != 0) System.exit(1);
    }

    static String ev(String cat, String name, long ts, long dur, String op, String provider) {
        String args = op == null ? "{}" : "{\"op_name\":\"" + op + "\",\"provider\":\"" + provider
                + "\",\"thread_scheduling_stats\":{\"main_thread\":{\"thread_pool_id\":1}},\"output_size\":\"8\"}";
        return "{\"cat\":\"" + cat + "\",\"pid\":1,\"tid\":2,\"dur\":" + dur + ",\"ts\":" + ts + ",\"ph\":\"X\",\"name\":\""
                + name + "\",\"args\":" + args + "}";
    }
}
