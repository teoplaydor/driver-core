import ai.onnxruntime.*;
import io.github.teoplaydor.semsearch.core.OnnxPatcher;

import java.io.File;
import java.nio.FloatBuffer;
import java.util.*;

/**
 * Patches MatMulNBits accuracy_level=4 into a q4 model (test/accel/make_q4_model.py), then checks
 * that ONNX Runtime loads it, the output stays close to fp32-compute and measures the speed-up.
 * usage: AccuracyLevelTest <dir with model_q4.onnx>
 */
public class AccuracyLevelTest {
    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        File orig = new File(dir, "model_q4.onnx"), patched = new File(dir, "model_q4.acc4.onnx");
        int n = OnnxPatcher.setMatMulNBitsAccuracy(orig, patched, 4);
        System.out.println("patched MatMulNBits nodes: " + n);
        if (n != 8) throw new AssertionError("expected 8 nodes");
        // Patching twice must replace, not duplicate, the attribute.
        File twice = new File(dir, "model_q4.acc4b.onnx");
        OnnxPatcher.setMatMulNBitsAccuracy(patched, twice, 4);
        if (twice.length() != patched.length()) throw new AssertionError("re-patch changed size");

        OrtEnvironment env = OrtEnvironment.getEnvironment();
        int m = 630, d = 768;
        float[] x = new float[m * d];
        Random r = new Random(1);
        for (int i = 0; i < x.length; i++) x[i] = (float) r.nextGaussian();
        float[] y0 = null;
        double t0ms = 0;
        for (File f : new File[]{orig, patched}) {
            OrtSession.SessionOptions o = new OrtSession.SessionOptions();
            o.setIntraOpNumThreads(4);
            OrtSession s = env.createSession(f.getPath(), o);
            float[] y = null;
            long best = Long.MAX_VALUE;
            for (int it = 0; it < 6; it++) {
                OnnxTensor in = OnnxTensor.createTensor(env, FloatBuffer.wrap(x), new long[]{1, m, d});
                long t = System.nanoTime();
                OrtSession.Result res = s.run(Collections.singletonMap("x", in));
                best = Math.min(best, System.nanoTime() - t);
                FloatBuffer fb = ((OnnxTensor) res.get(0)).getFloatBuffer();
                y = new float[fb.remaining()];
                fb.get(y);
                res.close();
                in.close();
            }
            s.close();
            double ms = best / 1e6;
            if (y0 == null) {
                y0 = y;
                t0ms = ms;
                System.out.printf("%-22s %7.1f ms%n", f.getName(), ms);
            } else {
                double dot = 0, n0 = 0, n1 = 0, maxRel = 0;
                for (int i = 0; i < y.length; i++) {
                    dot += y[i] * y0[i];
                    n0 += y0[i] * y0[i];
                    n1 += y[i] * y[i];
                }
                double cos = dot / Math.sqrt(n0 * n1);
                System.out.printf("%-22s %7.1f ms  speed-up x%.2f  cosine vs fp32-compute %.5f%n", f.getName(), ms, t0ms / ms, cos);
                if (cos < 0.995) throw new AssertionError("output drifted: " + cos);
            }
        }
        System.out.println("ALL OK");
    }
}
