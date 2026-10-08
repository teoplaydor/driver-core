import java.io.File;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import io.github.teoplaydor.semsearch.core.OnnxPatcher;
import io.github.teoplaydor.semsearch.core.QnnBuild;
import io.github.teoplaydor.semsearch.core.QnnLog;

/**
 * Getting the vision graph compiled for the NPU when QNN refuses it (QnnBuild), against a stand-in NPU that
 * cannot build one chosen node unless it is computed in double (kept on the CPU), and that loads every graph it
 * is given in ONNX Runtime, as the real one would:
 * <ul>
 * <li>tensor types and shapes come from ONNX Runtime (a copy of the graph with all tensors as outputs);</li>
 * <li>keeping nodes on the CPU in double changes no result, for every op of the real encoder (op zoo) but Erf,
 *     which has no double kernel there and stays as it is;</li>
 * <li>QNN names the node: it goes to the CPU in every layer, the second compilation works, and the report
 *     describes the node with its types and shapes;</li>
 * <li>QNN names nothing: the tiny graph compiles, a bisection over truncated graphs finds the node;</li>
 * <li>the tiny graph does not compile: the search stops and says the NPU is the problem;</li>
 * <li>a later compilation starts with the nodes kept on the CPU (also with a list from an older rewrite of the
 *     graph); the time budget ends the search;</li>
 * <li>where the NPU's result goes wrong: the first tensor that turns NaN, its node and inputs; values beyond
 *     fp16 even in fp32 are listed.</li>
 * </ul>
 * usage: QnnBuildTest <vit graph rewritten for QNN> <op zoo graph> <work dir>
 */
public class QnnBuildTest {
    static int bad;
    static OrtEnvironment env;

    static void check(boolean ok, String what) {
        if (!ok) bad++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static Map<String, Long> dims() {
        Map<String, Long> d = new HashMap<String, Long>();
        d.put("batch", 1L);
        d.put("patches", 24L);
        return d;
    }

    static OrtSession load(File g) throws Exception {
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        for (Map.Entry<String, Long> e : dims().entrySet()) o.setSymbolicDimensionValue(e.getKey(), e.getValue());
        return env.createSession(g.getPath(), o);
    }

    static float[] run(File g, Map<String, float[]> fin, Map<String, long[]> lin, Map<String, long[]> shapes) throws Exception {
        OrtSession s = load(g);
        Map<String, OnnxTensor> in = new HashMap<String, OnnxTensor>();
        for (Map.Entry<String, float[]> e : fin.entrySet()) in.put(e.getKey(), OnnxTensor.createTensor(env, FloatBuffer.wrap(e.getValue()), shapes.get(e.getKey())));
        for (Map.Entry<String, long[]> e : lin.entrySet()) in.put(e.getKey(), OnnxTensor.createTensor(env, LongBuffer.wrap(e.getValue()), shapes.get(e.getKey())));
        OrtSession.Result r = s.run(in);
        float[] out = ((OnnxTensor) r.get(0)).getFloatBuffer().array().clone();
        r.close();
        for (OnnxTensor t : in.values()) t.close();
        s.close();
        return out;
    }

    static double maxDiff(float[] a, float[] b) {
        double m = 0;
        for (int i = 0; i < a.length; i++) m = Math.max(m, Math.abs(a[i] - b[i]));
        return a.length == b.length ? m : Double.MAX_VALUE;
    }

    static final String ORT = "[V:onnxruntime:, qnn_backend_manager.cc:466 QnnLogging] ";

    /** QNN as seen on the phone, minus the hardware: one node it cannot build unless it runs in double. */
    static final class FakeNpu implements QnnBuild.Npu {
        final String culprit;
        final boolean names, canaryFails;
        long clock;
        int compiles, probes;

        FakeNpu(String culprit, boolean names, boolean canaryFails) {
            this.culprit = culprit;
            this.names = names;
            this.canaryFails = canaryFails;
        }

        @Override
        public QnnBuild.Failure compile(File g, boolean save, boolean deep) throws Exception {
            clock += 20000;
            if (save) compiles++;
            else probes++;
            try {
                load(g).close();
            } catch (Exception e) {
                return new QnnBuild.Failure("graph does not load: " + e.getMessage(), null);
            }
            for (OnnxPatcher.Node n : OnnxPatcher.nodes(g, new HashMap<String, Long>())) {
                if (!n.name.equals(culprit)) continue;
                for (String in : n.inputs) if (in.endsWith("_double")) return null;
                List<String> log = new ArrayList<String>(Arrays.asList(
                        "I/onnxruntime: [I:onnxruntime:, qnn_execution_provider.cc:1152 GetCapability] Number of partitions supported by QNN EP: 1, number of nodes in the graph: 88, number of nodes supported by QNN: 88",
                        "V/onnxruntime: " + ORT + "QnnDsp <E> graph_prepare.cc:217::ERROR:could not create op: q::Softmax",
                        "V/onnxruntime: " + ORT + "QnnDsp <E> Failed to finalize graph (id: 1) with err 1002",
                        "E/onnxruntime: [E:onnxruntime:, qnn_model.cc:387 FinalizeGraphs] Failed to finalize QNN graph. Error code: 1002"));
                if (names) log.add(2, "V/onnxruntime: " + ORT + "QnnDsp <E> \"" + culprit + "_reshape\" generated: could not create op");
                return new QnnBuild.Failure("Error code - ORT_FAIL - message: Failed to finalize QNN graph.", QnnLog.parse(log));
            }
            return null;
        }

        @Override
        public String canary() {
            return canaryFails ? "Error code - ORT_FAIL - message: Failed to finalize QNN graph." : null;
        }

        @Override
        public Map<String, OnnxPatcher.TensorType> types(File g) throws Exception {
            return QnnBuild.tensorTypes(env, g, dims());
        }

        @Override
        public long nowMs() {
            return clock;
        }
    }

    /** {max, mean} magnitudes of the watched tensors of the vision graph for one input (CPU). */
    static Map<String, float[]> ranges(File scan, List<String> watch, float[] px, float[] mask) throws Exception {
        OrtSession s = load(scan);
        Map<String, OnnxTensor> in = new HashMap<String, OnnxTensor>();
        in.put("pixel_values", OnnxTensor.createTensor(env, FloatBuffer.wrap(px), new long[]{1, 24, 32}));
        in.put("attention_bias", OnnxTensor.createTensor(env, FloatBuffer.wrap(mask), new long[]{1, 1, 24, 24}));
        OrtSession.Result r = s.run(in);
        Map<String, float[]> out = QnnBuild.readRanges(r, watch);
        r.close();
        for (OnnxTensor t : in.values()) t.close();
        s.close();
        return out;
    }

    public static void main(String[] args) throws Exception {
        env = OrtEnvironment.getEnvironment();
        File dir = new File(args[2]);
        dir.mkdirs();

        // types and shapes from ONNX Runtime; the op zoo computes the same in double on the CPU
        File zoo = new File(dir, "zoo.onnx");
        Files.copy(new File(args[1]).toPath(), zoo.toPath(), StandardCopyOption.REPLACE_EXISTING);
        Map<String, OnnxPatcher.TensorType> zt = QnnBuild.tensorTypes(env, zoo, dims());
        check(String.valueOf(zt.get("zoo/g1".replace("zoo/", ""))).equals("float[1,24,32]") && String.valueOf(zt.get("imax")).equals("int64[1,1]")
                        && String.valueOf(zt.get("gt")).equals("bool[1,24,32]") && String.valueOf(zt.get("table")).equals("float[64,32]")
                        && String.valueOf(zt.get("ids")).equals("int64[1,24]"),
                "types from ONNX Runtime: g1 " + zt.get("g1") + ", imax " + zt.get("imax") + ", gt " + zt.get("gt") + ", table " + zt.get("table") + ", ids " + zt.get("ids"));
        List<OnnxPatcher.Node> zn = OnnxPatcher.nodes(zoo, new HashMap<String, Long>());
        Set<String> all = new LinkedHashSet<String>();
        Set<String> ops = new java.util.TreeSet<String>();
        for (OnnxPatcher.Node n : zn) {
            all.add(n.name);
            ops.add(n.opType);
        }
        File zooCpu = new File(dir, "zoo.cpu.onnx");
        List<String> unmoved = OnnxPatcher.keepOnCpu(zoo, zooCpu, all, zt);
        check(unmoved.equals(Arrays.asList("zoo/er")), "every node moved but Erf (no double kernel on the CPU): " + ops + ", not " + unmoved);
        all.removeAll(unmoved);
        int moved = 0;
        for (OnnxPatcher.Node n : OnnxPatcher.nodes(zooCpu, new HashMap<String, Long>())) {
            for (String in : n.inputs) if (in.endsWith("_double") && all.contains(n.name)) {
                moved++;
                break;
            }
        }
        check(moved == all.size(), moved + " of " + all.size() + " nodes compute in double");
        float[] x = new float[24 * 32];
        long[] ids = new long[24];
        for (int i = 0; i < x.length; i++) x[i] = (float) Math.sin(i * 0.29) * 1.5f;
        for (int i = 0; i < ids.length; i++) ids[i] = (i * 7) % 64;
        Map<String, float[]> fin = new HashMap<String, float[]>();
        fin.put("x", x);
        Map<String, long[]> lin = new HashMap<String, long[]>();
        lin.put("ids", ids);
        Map<String, long[]> shapes = new HashMap<String, long[]>();
        shapes.put("x", new long[]{1, 24, 32});
        shapes.put("ids", new long[]{1, 24});
        String err = null;
        double zd = Double.MAX_VALUE, zmax = 0;
        try {
            float[] zr = run(zoo, fin, lin, shapes);
            for (float v : zr) zmax = Math.max(zmax, Math.abs(v));
            zd = maxDiff(zr, run(zooCpu, fin, lin, shapes)) / zmax;
        } catch (Exception e) {
            err = e.getMessage();
        }
        check(err == null && zd < 1e-6, "op zoo in double on the CPU: max relative difference " + zd + " (values up to " + zmax + ")"
                + (err != null ? " — " + err : ""));

        // the vision graph: scenarios with a stand-in NPU
        File vit = new File(dir, "vit.qnn.r2.onnx");
        Files.copy(new File(args[0]).toPath(), vit.toPath(), StandardCopyOption.REPLACE_EXISTING);
        List<OnnxPatcher.Node> nodes = OnnxPatcher.nodes(vit, new HashMap<String, Long>());
        String softmax = null, oproj1 = null;
        for (OnnxPatcher.Node n : nodes) {
            if (n.opType.equals("Softmax") && softmax == null) softmax = n.name;
            if (n.name.startsWith("/encoder/layers.1/o_proj")) oproj1 = n.name;
        }
        float[] px = new float[24 * 32], mask = new float[24 * 24];
        for (int i = 0; i < px.length; i++) px[i] = (float) Math.cos(i * 0.41) * 2;
        Map<String, float[]> vin = new HashMap<String, float[]>();
        vin.put("pixel_values", px);
        vin.put("attention_bias", mask);
        Map<String, long[]> vshapes = new HashMap<String, long[]>();
        vshapes.put("pixel_values", new long[]{1, 24, 32});
        vshapes.put("attention_bias", new long[]{1, 1, 24, 24});
        float[] ref = run(vit, vin, new HashMap<String, long[]>(), vshapes);

        FakeNpu a = new FakeNpu(softmax, true, false);
        QnnBuild.Outcome oa = QnnBuild.run(vit, new HashSet<String>(), a, 15 * 60 * 1000);
        System.out.println("  " + oa.report.replace("\n", "\n  "));
        check(oa.ok && a.compiles == 2 && a.probes == 0 && oa.cpu.size() == 2 && oa.cpu.contains(softmax),
                "QNN names the node: kept on the CPU in both layers " + oa.cpu + ", " + a.compiles + " compilations");
        check(oa.report.contains("QNN назвал узел: Softmax " + softmax + ": float[1,4,24,24] → float[1,4,24,24] (axis=-1)")
                && oa.report.contains("первая сборка: Error code - ORT_FAIL - message: Failed to finalize QNN graph.")
                && oa.report.contains("QNN при сборке: QnnDsp <E> \"" + softmax), "report describes the node and QNN's errors");
        double da = oa.compiled == null ? Double.MAX_VALUE : maxDiff(ref, run(oa.compiled, vin, new HashMap<String, long[]>(), vshapes));
        check(da < 1e-5, "the compiled graph gives the same features: max difference " + da);
        check(!new File(dir, "vit.qnn.r2.types.onnx").exists() && !new File(dir, "vit.qnn.r2.probe.onnx").exists(), "no leftovers");

        FakeNpu b = new FakeNpu(oproj1, false, false);
        QnnBuild.Outcome ob = QnnBuild.run(vit, new HashSet<String>(), b, 15 * 60 * 1000);
        System.out.println("  " + ob.report.replace("\n", "\n  "));
        check(ob.ok && ob.cpu.contains(oproj1) && ob.cpu.size() == 2 && b.probes <= 7 && b.compiles == 2,
                "QNN names nothing: bisection finds " + ob.cpu + " in " + b.probes + " probe compilations");
        check(ob.report.contains("крошечный граф (MatMul, Add, Softmax) собирается") && ob.report.contains("поиск нашёл узел: MatMul " + oproj1),
                "report: the tiny graph compiles, the search found the node");

        FakeNpu c = new FakeNpu(softmax, false, true);
        QnnBuild.Outcome oc = QnnBuild.run(vit, new HashSet<String>(), c, 15 * 60 * 1000);
        check(!oc.ok && c.probes == 0 && oc.report.contains("дело не в модели"), "the tiny graph fails too: stop, the NPU is the problem");

        FakeNpu d = new FakeNpu(softmax, true, false);
        QnnBuild.Outcome od = QnnBuild.run(vit, oa.cpu, d, 15 * 60 * 1000);
        check(od.ok && d.compiles == 1, "a later compilation starts with the nodes kept on the CPU: " + d.compiles + " compilation");

        FakeNpu e = new FakeNpu(oproj1, false, false);
        QnnBuild.Outcome oe = QnnBuild.run(vit, new HashSet<String>(), e, 30000);
        check(!oe.ok && oe.report.contains("кончилось время"), "the time budget ends the search");

        // a list saved for an older rewrite of the graph (other tokens) still applies
        Set<String> older = new LinkedHashSet<String>();
        for (String n : oa.cpu) older.add(n.replaceFirst("_N\\d+N$", "_N999N"));
        FakeNpu f = new FakeNpu(softmax, true, false);
        QnnBuild.Outcome of = QnnBuild.run(vit, older, f, 15 * 60 * 1000);
        check(of.ok && f.compiles == 1 && of.cpu.equals(oa.cpu), "a list from an older graph (other tokens) is matched by name: " + f.compiles + " compilation");

        // where the NPU's result goes wrong: magnitudes of every float tensor, on the CPU and a stand-in NPU whose
        // values turn to NaN from one tensor on
        Map<String, OnnxPatcher.TensorType> vt = QnnBuild.tensorTypes(env, vit, dims());
        List<String> watch = QnnBuild.watchList(nodes, vt, 600);
        File scan = new File(dir, "vit.scan.onnx");
        OnnxPatcher.withRanges(vit, scan, watch);
        Map<String, float[]> cpuR = ranges(scan, watch, px, mask);
        float[] big = new float[px.length];
        for (int i = 0; i < big.length; i++) big[i] = px[i] * 100000;
        Map<String, float[]> bigR = ranges(scan, watch, big, mask);
        String broken = null;
        for (String t : watch) if (t.contains("layers.1/post_layernorm/rms")) broken = t;
        Map<String, float[]> npuR = new HashMap<String, float[]>();
        boolean after = false;
        for (String t : watch) {
            after |= t.equals(broken);
            npuR.put(t, after ? new float[]{Float.NaN, Float.NaN} : cpuR.get(t).clone());
        }
        check(watch.size() > 50 && cpuR.size() == watch.size() && cpuR.get(watch.get(0))[0] > 0, watch.size() + " tensors watched, all read back");
        String cmp = QnnBuild.compareRanges(watch, cpuR, npuR, nodes, vt);
        System.out.println("  " + cmp.replace("\n", "\n  "));
        check(cmp.startsWith("на NPU первым портится " + broken + " — NPU max NaN") && cmp.contains("его делает Sqrt ")
                && cmp.contains("вход ") && cmp.contains("за пределы fp16 (65504) даже на процессоре выходят: ничего"),
                "the first NaN tensor, its node and inputs");
        String same = QnnBuild.compareRanges(watch, cpuR, cpuR, nodes, vt);
        check(same.startsWith("NPU и процессор совпадают на всех " + watch.size()), "no difference: says so");
        String over = QnnBuild.compareRanges(watch, bigR, bigR, nodes, vt);
        check(over.contains("за пределы fp16 (65504) даже на процессоре выходят: [qnn0_/encoder/layers.0/input_layernorm/abs"),
                "values beyond fp16 even in fp32 are listed: " + over.substring(over.indexOf("за пределы")));

        if (bad > 0) {
            System.out.println(bad + " FAILED");
            System.exit(1);
        }
        System.out.println("QNN build: all ok");
    }
}
