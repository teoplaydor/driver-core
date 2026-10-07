import java.io.File;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import io.github.teoplaydor.semsearch.core.OnnxPatcher;

/**
 * The "int8 + fp16 attention" variant: every fused MultiHeadAttention is wrapped in Cast(fp16) / Cast(fp32),
 * the additive mask floored to −60000 first (−3.4e38 does not fit fp16). On ONNX Runtime the rewritten graph
 * must give the same features as the original, with padded patches still hidden.
 * usage: Fp16AttentionTest <graph with MultiHeadAttention> <out dir>
 */
public class Fp16AttentionTest {
    static int bad;

    static void check(boolean ok, String what) {
        if (!ok) bad++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    /** @param keepCasts no graph optimisations (the rewritten graph must load as written). The CPU has no fp16
     *                  attention kernel, so ONNX Runtime computes it in fp32 there; in fp16 it runs on the GPU. */
    static float[] run(OrtEnvironment env, File graph, float[] x, float[] mask, int s, int d, boolean keepCasts) throws Exception {
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        if (keepCasts) o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
        OrtSession sess = env.createSession(graph.getPath(), o);
        Map<String, OnnxTensor> in = new HashMap<String, OnnxTensor>();
        in.put("pixel_values", OnnxTensor.createTensor(env, FloatBuffer.wrap(x), new long[]{1, s, d}));
        in.put("attention_bias", OnnxTensor.createTensor(env, FloatBuffer.wrap(mask), new long[]{1, 1, s, s}));
        OrtSession.Result r = sess.run(in);
        float[] out = ((OnnxTensor) r.get(0)).getFloatBuffer().array();
        float[] copy = out.clone();
        r.close();
        for (OnnxTensor t : in.values()) t.close();
        sess.close();
        return copy;
    }

    public static void main(String[] args) throws Exception {
        File src = new File(args[0]), dst = new File(args[1], "mha.fp16attn.onnx");
        int n = OnnxPatcher.fp16Attention(src, dst);
        check(n == 2, "attention nodes rewritten: " + n);

        java.util.Map<String, Long> opsets = new java.util.TreeMap<String, Long>();
        List<OnnxPatcher.Node> nodes = OnnxPatcher.nodes(dst, opsets);
        int casts = 0, maxes = 0, constants = 0;
        for (OnnxPatcher.Node x : nodes) {
            if ("Cast".equals(x.opType)) casts++;
            if ("Max".equals(x.opType)) maxes++;
            if ("Constant".equals(x.opType)) constants++;
        }
        // per block: q, k, v, mask in + output back = 5 casts; one floored mask per block; one shared constant
        check(casts == 10 && maxes == 2 && constants == 1, "casts " + casts + ", mask floors " + maxes + ", constants " + constants);
        int at = -1;
        for (int i = 0; i < nodes.size(); i++) if ("MultiHeadAttention".equals(nodes.get(i).opType)) { at = i; break; }
        OnnxPatcher.Node mha = nodes.get(at);
        check(mha.inputs.get(0).contains("_fp16attn0_in0") && mha.inputs.get(3).isEmpty() && mha.inputs.get(4).isEmpty()
                && mha.inputs.get(5).contains("_in5") && mha.outputs.get(0).endsWith("_out0")
                && "Cast".equals(nodes.get(at + 1).opType) && nodes.get(at + 1).outputs.get(0).equals("/encoder/layers.0/self_attn/attn"),
                "node wiring: fp16 inputs, empty optional inputs kept, output cast back to its old name");
        check(OnnxPatcher.fp16Attention(new File(args[2]), new File(args[1], "none.onnx")) == 0
                && !new File(args[1], "none.onnx").exists(), "graph without fused attention: nothing written");

        int s = 24, d = 32, valid = 17;
        java.util.Random rnd = new java.util.Random(3);
        float[] x = new float[s * d];
        for (int i = 0; i < x.length; i++) x[i] = (float) rnd.nextGaussian();
        float[] mask = new float[s * s];
        for (int q = 0; q < s; q++) for (int k = valid; k < s; k++) mask[q * s + k] = -3.4e38f; // padded keys hidden
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        float[] a = run(env, src, x, mask, s, d, false);
        float[] b;
        try {
            b = run(env, dst, x, mask, s, d, true);
        } catch (Exception e) {
            check(false, "rewritten graph runs on ONNX Runtime: " + e.getMessage());
            System.out.println(bad + " FAILED");
            System.exit(1);
            return;
        }
        double maxDiff = 0, dot = 0, na = 0, nb = 0;
        boolean finite = true;
        for (int i = 0; i < valid * d; i++) { // the real patches
            maxDiff = Math.max(maxDiff, Math.abs(a[i] - b[i]));
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
            finite &= !Float.isNaN(b[i]) && !Float.isInfinite(b[i]);
        }
        for (float v : b) finite &= !Float.isNaN(v);
        double cos = dot / Math.sqrt(na * nb);
        check(finite, "no NaN/Inf with the −3.4e38 mask (floored for fp16)");
        check(cos >= 0.999, String.format("same features on the real patches: cos %.6f, max diff %.5f", cos, maxDiff));
        System.out.println(bad == 0 ? "FP16 ATTENTION OK" : bad + " FAILED");
        if (bad != 0) System.exit(1);
    }
}
