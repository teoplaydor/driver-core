import java.io.File;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import io.github.teoplaydor.semsearch.core.OnnxPatcher;

/**
 * The graph for the Snapdragon NPU: QNN cannot run com.microsoft:MultiHeadAttention or
 * SimplifiedLayerNormalization, so OnnxPatcher.forQnn spells them out in plain ops. On ONNX Runtime the
 * rewritten graph must give the same features (both with and without a scale attribute, with a −3.4e38
 * padding mask and with large activations, where the RMS norm's squares would overflow fp16 unscaled),
 * keep no fused node, and stay loadable with fixed input shapes as the NPU needs them.
 * usage: QnnRewriteTest <vit block graph> <out dir>
 */
public class QnnRewriteTest {
    static int bad;

    static void check(boolean ok, String what) {
        if (!ok) bad++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static float[] run(OrtEnvironment env, File graph, float[] x, float[] mask, int s, int d, boolean fixed) throws Exception {
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        if (fixed) {
            o.setSymbolicDimensionValue("batch", 1);
            o.setSymbolicDimensionValue("patches", s);
        }
        OrtSession sess = env.createSession(graph.getPath(), o);
        Map<String, OnnxTensor> in = new HashMap<String, OnnxTensor>();
        in.put("pixel_values", OnnxTensor.createTensor(env, FloatBuffer.wrap(x), new long[]{1, s, d}));
        in.put("attention_bias", OnnxTensor.createTensor(env, FloatBuffer.wrap(mask), new long[]{1, 1, s, s}));
        OrtSession.Result r = sess.run(in);
        float[] out = ((OnnxTensor) r.get(0)).getFloatBuffer().array().clone();
        r.close();
        for (OnnxTensor t : in.values()) t.close();
        sess.close();
        return out;
    }

    public static void main(String[] args) throws Exception {
        File src = new File(args[0]), dst = new File(args[1], "vit.qnn.onnx");
        int[] n = OnnxPatcher.forQnn(src, dst);
        check(n[0] == 2 && n[1] == 4, "rewritten: attention " + n[0] + ", RMS norm " + n[1]);
        Map<String, Long> opsets = new TreeMap<String, Long>();
        List<OnnxPatcher.Node> nodes = OnnxPatcher.nodes(dst, opsets);
        int fused = 0, softmax = 0, reduce = 0;
        for (OnnxPatcher.Node x : nodes) {
            if (x.opType.equals("MultiHeadAttention") || x.opType.equals("SimplifiedLayerNormalization")) fused++;
            if (x.opType.equals("Softmax")) softmax++;
            if (x.opType.equals("ReduceMean")) reduce++;
        }
        check(fused == 0 && softmax == 2 && reduce == 4, "no fused nodes left; Softmax ×" + softmax + ", ReduceMean ×" + reduce);
        String summary = OnnxPatcher.graphSummary(dst);
        check(summary.contains("по частям — MatMul → Softmax ×2 → MatMul"), "attention now spelled out");

        OrtEnvironment env = OrtEnvironment.getEnvironment();
        int s = 24, d = 32, valid = 19;
        for (float amp : new float[]{1f, 400f}) {
            java.util.Random rnd = new java.util.Random(5);
            float[] x = new float[s * d];
            for (int i = 0; i < x.length; i++) x[i] = (float) rnd.nextGaussian() * amp;
            float[] mask = new float[s * s];
            for (int q = 0; q < s; q++) for (int k = valid; k < s; k++) mask[q * s + k] = -3.4e38f;
            float[] a = run(env, src, x, mask, s, d, false), b = run(env, dst, x, mask, s, d, true);
            double dot = 0, na = 0, nb = 0, maxRel = 0;
            boolean finite = true;
            for (int i = 0; i < valid * d; i++) {
                dot += a[i] * b[i];
                na += a[i] * a[i];
                nb += b[i] * b[i];
                maxRel = Math.max(maxRel, Math.abs(a[i] - b[i]) / (1e-3 + Math.abs(a[i])));
                finite &= !Float.isNaN(b[i]) && !Float.isInfinite(b[i]);
            }
            double cos = dot / Math.sqrt(na * nb);
            check(finite && cos >= 0.99999, String.format("activations ×%.0f: same features with fixed shapes, cos %.7f, "
                    + "max relative diff %.2e", amp, cos, maxRel));
        }
        System.out.println(bad == 0 ? "QNN REWRITE OK" : bad + " FAILED");
        if (bad != 0) System.exit(1);
    }
}
