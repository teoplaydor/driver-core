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
 *     fp16 even in fp32 are listed;</li>
 * <li>the position logic of a Gemma 4 vision block (padding, the mask in the keys, RoPE angles, position
 *     embeddings, integer Gathers) is found and kept on the CPU without changing the result, and the NPU is
 *     left no boolean or position-made integer.</li>
 * </ul>
 * usage: QnnBuildTest <vit graph rewritten for QNN> <op zoo graph> <work dir> <Gemma 4 block rewritten for QNN>
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
        public QnnLog lastLog() {
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
        // the first tensor's measure comes last: ONNX Runtime walks up from the last leaf first, so each tensor is
        // measured as soon as it is made (in graph order every tensor lived to the end: 13 GB at 280 tokens)
        List<OnnxPatcher.Node> scanNodes = OnnxPatcher.nodes(scan, new HashMap<String, Long>());
        OnnxPatcher.Node lastMeasure = scanNodes.get(scanNodes.size() - 1);
        check(lastMeasure.outputs.get(0).equals(OnnxPatcher.RANGE_MEAN + watch.get(0)),
                "measures in reverse order: the last node measures the first tensor (" + lastMeasure.outputs + ")");
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
        String traced = QnnBuild.compareRanges(watch, cpuR, npuR, nodes, vt, OnnxPatcher.smallConstants(vit));
        check(traced.contains("откуда (вверх по графу; процессор / NPU):") && traced.contains("← Mul qnn3_/encoder/layers.1/post_layernorm/r2")
                        && traced.contains("Mul qnn3_/encoder/layers.1/post_layernorm/r_N"),
                "the way up the graph from the first bad tensor");
        String same = QnnBuild.compareRanges(watch, cpuR, cpuR, nodes, vt);
        check(same.startsWith("NPU и процессор совпадают на всех " + watch.size()), "no difference: says so");
        String over = QnnBuild.compareRanges(watch, bigR, bigR, nodes, vt);
        check(over.contains("за пределы fp16 (65504) даже на процессоре выходят: [qnn0_/encoder/layers.0/input_layernorm/abs"),
                "values beyond fp16 even in fp32 are listed: " + over.substring(over.indexOf("за пределы")));

        // values beyond fp16's comfort even in fp32: the nodes that make or take them stay on the CPU
        java.util.Set<String> over16 = QnnBuild.overflowNodes(nodes, bigR, 16000f);
        boolean firstNorm = false, quiet = QnnBuild.overflowNodes(nodes, cpuR, 16000f).isEmpty();
        for (String n : over16) firstNorm |= n.startsWith("qnn0_/encoder/layers.0/input_layernorm/abs");
        check(firstNorm && quiet && over16.size() < nodes.size(), "nodes touching values > 16000 (×100000 input): " + over16.size()
                + " of " + nodes.size() + ", none for normal input");
        // on the NPU the attention cores are always watched, the rest sampled
        List<String> nw = QnnBuild.npuWatchList(nodes, vt, 40);
        int cores = 0;
        for (String t : nw) if (t.matches("qnn\\d+_.*/(qk|probs|ctx)(_N\\d+N)?")) cores++;
        check(cores == 6 && nw.size() <= 40 + 20, "NPU watch: attention cores of both layers (" + cores + ") among " + nw.size());
        // small constants with their values, for the report
        Map<String, double[]> mk = OnnxPatcher.smallConstants(new File(args[3]));
        check(mk.containsKey("fmin") && mk.get("fmin")[0] == -10000 && mk.get("pos_inf")[0] == 65504 && mk.get("heads").length == 4
                        && mk.get("minus1")[0] == -1,
                "small constants: fmin " + Arrays.toString(mk.get("fmin")) + ", pos_inf " + Arrays.toString(mk.get("pos_inf"))
                        + ", heads " + Arrays.toString(mk.get("heads")));

        // the position logic of a Gemma 4 vision block (padding, mask in the keys, RoPE angles, position embeddings,
        // the integer Gathers) is found and kept on the CPU; the NPU is left float activations only
        File g4 = new File(dir, "g4.qnn.r4.onnx");
        Files.copy(new File(args[3]).toPath(), g4.toPath(), StandardCopyOption.REPLACE_EXISTING);
        Map<String, OnnxPatcher.TensorType> gt = QnnBuild.tensorTypes(env, g4, dims());
        List<OnnxPatcher.Node> gn = OnnxPatcher.nodes(g4, new HashMap<String, Long>());
        Set<String> posOnly = QnnBuild.positionOnlyNodes(gn, gt, OnnxPatcher.inputDims(g4).keySet());
        Set<String> bareOnly = new java.util.TreeSet<String>();
        for (String nm : posOnly) bareOnly.add(nm.replaceFirst("^/block/", "").replaceFirst("_N\\d+N$", ""));
        Set<String> wantOnly = new java.util.TreeSet<String>(Arrays.asList("eq", "eqi", "alli", "padding", "valid", "valid2", "clamped",
                "node_select", "node_select_1", "x_emb", "y_emb", "pos_emb", "pad3", "pos_emb0", "posf", "possum", "ang", "cos0", "sin0",
                "cos", "sin", "valid4", "mcol", "mh", "nz", "kidx", "kx", "ky", "ky3", "lin", "pool_emb"));
        check(bareOnly.equals(wantOnly), "position-only nodes: " + bareOnly + (bareOnly.equals(wantOnly) ? "" : " — want " + wantOnly));
        File g4cpu = new File(dir, "g4.qnn.r4.cpu.onnx");
        List<String> g4unmoved = OnnxPatcher.keepOnCpu(g4, g4cpu, posOnly, gt);
        check(g4unmoved.size() == 1 && g4unmoved.get(0).startsWith("/block/nz_N"),
                "all of them move to the CPU (Not and And as arithmetic on 0/1) but NonZero, which has no double kernel: " + g4unmoved);
        check(Arrays.equals(QnnBuild.missingKernelForTest("Error code - ORT_NOT_IMPLEMENTED - message: Could not find an implementation for "
                + "NonZero(13) node with name 'node_NonZero_1929_N97N'"), new String[]{"NonZero", "node_NonZero_1929_N97N"}),
                "a missing CPU kernel in ONNX Runtime's error is recognised (the node goes back)");
        float[] gpx = new float[24 * 12];
        long[] gpos = new long[24 * 2];
        for (int i = 0; i < 24; i++) {
            gpos[2 * i] = i < 20 ? i % 6 : -1;
            gpos[2 * i + 1] = i < 20 ? i / 6 : -1;
            for (int j = 0; j < 12; j++) gpx[i * 12 + j] = i < 20 ? (float) ((Math.sin(i * 1.3 + j) + 1) / 2) : 0;
        }
        Map<String, float[]> gfin = new HashMap<String, float[]>();
        gfin.put("pixel_values", gpx);
        Map<String, long[]> glin = new HashMap<String, long[]>();
        glin.put("pixel_position_ids", gpos);
        Map<String, long[]> gshapes = new HashMap<String, long[]>();
        gshapes.put("pixel_values", new long[]{1, 24, 12});
        gshapes.put("pixel_position_ids", new long[]{1, 24, 2});
        float[] g4ref = run(g4, gfin, glin, gshapes), g4moved = run(g4cpu, gfin, glin, gshapes);
        double g4max = 0;
        for (float v : g4ref) g4max = Math.max(g4max, Math.abs(v));
        // the rewrite itself against the export (fused attention and norms, the Gathers of rotate_half): the same
        // output; no Gather of activations with constant indices is left, Slices stand in for them, divisions of
        // whole tensors are multiplications by a reciprocal, scores are not multiplied by a scale of 1
        float[] g4orig = run(new File(new File(args[3]).getParentFile(), "g4.onnx"), gfin, glin, gshapes);
        int constGathers = 0, slices = 0, divs = 0, recips = 0, scoreMuls = 0, gelus = 0, sigmoids = 0;
        for (OnnxPatcher.Node nd : gn) {
            if (nd.opType.equals("Gelu")) gelus++;
            if (nd.opType.equals("Sigmoid") && nd.name.startsWith("qnngelu")) sigmoids++;
            if (nd.opType.equals("Gather") && nd.inputs.get(1).equals("rot_perm")) constGathers++;
            if (nd.opType.equals("Slice") && nd.name.startsWith("qnng")) slices++;
            if (nd.opType.equals("Div") && nd.name.contains("layernorm")) divs++;
            if (nd.opType.equals("Div") && nd.inputs.get(0).equals("qnn_one")) recips++;
            if (nd.opType.equals("Mul") && nd.name.contains("/scores")) scoreMuls++;
            // ONNX Runtime's QNN provider checks neither the type nor the shape of a Reciprocal: one kept on the CPU
            // (in double, of a dynamic size) went to the NPU in 0.10.4
            if (nd.opType.equals("Reciprocal")) scoreMuls += 1000;
        }
        check(maxDiff(g4orig, g4ref) / g4max < 1e-5 && constGathers == 0 && slices == 4 && recips > 0 && scoreMuls == 0,
                "rewritten block = export: relative difference " + maxDiff(g4orig, g4ref) / g4max + "; constant Gathers left " + constGathers
                        + ", Slices " + slices + ", 1 / x " + recips + ", score scaling by 1: " + scoreMuls);
        // QNN's Gelu was 40% of the NPU's time at 280 tokens: the MLP's GELU (tanh form) is x·σ(x·(a + b·x²)), the same
        check(gelus == 0 && sigmoids == 1, "GELU as x·sigmoid(x·(a + b·x²)): Gelu left " + gelus + ", sigmoids " + sigmoids);
        check(maxDiff(g4ref, g4moved) / g4max < 1e-5, "same output with the position logic on the CPU: relative difference "
                + maxDiff(g4ref, g4moved) / g4max);
        // what the NPU would get: no node outside the CPU part takes a boolean or a position-made integer
        Set<String> posDep = new HashSet<String>(Arrays.asList("pixel_position_ids"));
        for (OnnxPatcher.Node nd : gn) {
            if (nd.opType.equals("Shape")) continue;
            for (String in : nd.inputs) if (posDep.contains(in)) posDep.addAll(nd.outputs);
        }
        List<String> leaks = new ArrayList<String>();
        for (OnnxPatcher.Node nd : OnnxPatcher.nodes(g4cpu, new HashMap<String, Long>())) {
            // QNN takes neither NonZero (it says so) nor Shape
            boolean onCpu = nd.opType.equals("Constant") || nd.opType.equals("NonZero") || nd.opType.equals("Shape")
                    || nd.name.contains("_to_double") || nd.name.contains("_from_double");
            for (String in : nd.inputs) onCpu |= in.endsWith("_double");
            for (String o : nd.outputs) onCpu |= o.endsWith("_double");
            if (onCpu) continue;
            for (String in : nd.inputs) {
                OnnxPatcher.TensorType t = gt.get(in);
                if (t != null && (t.elem == OnnxPatcher.TYPE_BOOL || (t.elem != OnnxPatcher.TYPE_FLOAT && posDep.contains(in)))) {
                    leaks.add(nd.opType + " " + nd.name + " ← " + in + " " + t);
                }
            }
        }
        check(leaks.isEmpty(), "the NPU gets no boolean or position integer: " + (leaks.isEmpty() ? "none" : leaks.toString()));

        // attention in parts along the queries (QNN's compiler crashed the NPU process on the whole attention at 2520
        // patches): the same output, also with parts of unequal size (24 queries in 5); its Split kept on the CPU (a
        // border between parts of the graph on the NPU) changes nothing either
        int g4softmax = 0;
        for (OnnxPatcher.Node nd : gn) if (nd.opType.equals("Softmax")) g4softmax++;
        for (int parts : new int[]{4, 5}) {
            File g4att = new File(dir, "g4.qnn.r4.att" + parts + ".onnx");
            List<String> splits = OnnxPatcher.chunkAttention(g4, g4att, parts);
            Map<String, Integer> opCount = new HashMap<String, Integer>();
            for (OnnxPatcher.Node nd : OnnxPatcher.nodes(g4att, new HashMap<String, Long>())) {
                opCount.put(nd.opType, opCount.containsKey(nd.opType) ? opCount.get(nd.opType) + 1 : 1);
            }
            float[] att = run(g4att, gfin, glin, gshapes);
            check(splits.size() == g4softmax && opCount.get("Softmax") == parts * g4softmax && maxDiff(g4ref, att) / g4max < 1e-6,
                    "attention in " + parts + " parts: " + splits.size() + " Split, " + opCount.get("Softmax") + " Softmax, relative difference "
                            + maxDiff(g4ref, att) / g4max);
            File border = new File(dir, "g4.qnn.r4.att" + parts + ".cpu.onnx");
            List<String> unmovedSplit = OnnxPatcher.keepOnCpu(g4att, border, new HashSet<String>(splits), QnnBuild.tensorTypes(env, g4att, dims()));
            float[] bordered = run(border, gfin, glin, gshapes);
            check(unmovedSplit.isEmpty() && maxDiff(g4ref, bordered) / g4max < 1e-6,
                    "its Split on the CPU as a border: relative difference " + maxDiff(g4ref, bordered) / g4max);
        }
        // the end of the graph where shapes are known only when it runs (real tokens picked after pooling, the norm
        // and projection after them): found by its shapes and kept on the CPU — the same output; the error of a part
        // with such a tensor is recognised (0.10.2 sent a Reciprocal of it to the NPU: the compilation failed)
        File tailSrc = new File(new File(args[3]).getParentFile(), "tail.onnx"), tail = new File(dir, "tail.qnn.r5.onnx");
        OnnxPatcher.forQnn(tailSrc, tail);
        Map<String, OnnxPatcher.TensorType> tt = QnnBuild.tensorTypes(env, tail, dims());
        List<OnnxPatcher.Node> tn = OnnxPatcher.nodes(tail, new HashMap<String, Long>());
        Set<String> dynamic = QnnBuild.dynamicShapeNodes(tn);
        Set<String> dynOps = new java.util.TreeSet<String>();
        boolean reciprocal = false, before = false;
        for (OnnxPatcher.Node nd : tn) {
            if (!dynamic.contains(nd.name)) continue;
            dynOps.add(nd.opType);
            reciprocal |= nd.opType.equals("Div") && nd.inputs.get(0).equals("qnn_one");
            before |= nd.name.startsWith("/tail/embed") || nd.name.startsWith("/tail/reshape") || nd.name.startsWith("/tail/shape");
        }
        int tailRecips = 0;
        for (OnnxPatcher.Node nd : tn) if (nd.opType.equals("Reciprocal")) tailRecips++;
        check(tailRecips == 0, "no Reciprocal in the rewritten tail (QNN's provider takes it unchecked): " + tailRecips);
        check(reciprocal && !before && dynamic.size() >= 14 && dynOps.contains("MatMul") && dynOps.contains("Gather") && dynOps.contains("NonZero"),
                "sizes known only when it runs: " + dynamic.size() + " nodes " + dynOps + ", none before the selection");
        // the trap of 0.10.3: on the raw graph shape inference leaves the computed Reshape's shape unknown (in the real
        // model nearly every tensor) — taken for a dynamic size, the whole model went to the CPU
        OnnxPatcher.TensorType reshaped = tt.get("x");
        boolean unknownRaw = reshaped == null || reshaped.dims == null;
        for (int i = 0; reshaped != null && reshaped.dims != null && i < reshaped.dims.length; i++) unknownRaw |= reshaped.dims[i] < 0;
        check(unknownRaw, "the computed Reshape looks unknown to shape inference on the raw graph (" + reshaped + "), and is not taken for dynamic");
        Set<String> g4dynamic = QnnBuild.dynamicShapeNodes(gn);
        check(g4dynamic.size() == 1 && g4dynamic.iterator().next().startsWith("/block/nz_N")
                        && QnnBuild.dynamicShapeNodes(nodes).isEmpty(),
                "the Gemma 4 block: only NonZero's own size is dynamic (its count, through Shape, is not): " + g4dynamic
                        + "; the attention test graph: none");
        Set<String> tailCpu = new LinkedHashSet<String>(dynamic);
        tailCpu.addAll(QnnBuild.positionOnlyNodes(tn, tt, OnnxPatcher.inputDims(tail).keySet()));
        File tailMoved = new File(dir, "tail.qnn.r5.cpu.onnx");
        List<String> tailUnmoved = OnnxPatcher.keepOnCpu(tail, tailMoved, tailCpu, tt);
        float[] tpx = new float[24 * 12];
        long[] tpos = new long[24 * 2];
        for (int i = 0; i < 24; i++) {
            tpos[2 * i] = i < 19 ? i % 5 : -1;
            tpos[2 * i + 1] = i < 19 ? i / 5 : -1;
            for (int j = 0; j < 12; j++) tpx[i * 12 + j] = i < 19 ? (float) Math.cos(i * 0.7 + j) : 0;
        }
        Map<String, float[]> tfin = new HashMap<String, float[]>();
        tfin.put("pixel_values", tpx);
        Map<String, long[]> tlin = new HashMap<String, long[]>();
        tlin.put("pixel_position_ids", tpos);
        float[] tWant = run(tailSrc, tfin, tlin, gshapes), tGot = run(tailMoved, tfin, tlin, gshapes);
        double tMax = 0;
        for (float v : tWant) tMax = Math.max(tMax, Math.abs(v));
        check(tWant.length == 19 * 32 && tGot.length == tWant.length && maxDiff(tWant, tGot) / tMax < 1e-5
                        && tailUnmoved.size() == 1 && tailUnmoved.get(0).startsWith("/tail/nz"),
                "on the CPU (NonZero as it is): 19 real tokens, relative difference " + maxDiff(tWant, tGot) / tMax);
        check("qnn112__fused_rms_norm/node_mean_112/s".equals(QnnBuild.dynamicTensorForTest("Error code - ORT_FAIL - message: "
                + "qnn_model.cc:73 ParseGraphInputOrOutput Dynamic shape is not supported yet, for output: qnn112__fused_rms_norm/node_mean_112/s")),
                "the error of a part with such a tensor names it");
        File noAtt = new File(dir, "zoo.att.onnx");
        check(OnnxPatcher.chunkAttention(zoo, noAtt, 4).isEmpty() && OnnxPatcher.nodes(noAtt, new HashMap<String, Long>()).size()
                == OnnxPatcher.nodes(zoo, new HashMap<String, Long>()).size(), "a graph without this attention is copied as it is");

        if (bad > 0) {
            System.out.println(bad + " FAILED");
            System.exit(1);
        }
        System.out.println("QNN build: all ok");
    }
}
