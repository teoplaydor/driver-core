package io.github.teoplaydor.semsearch.core;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/**
 * Getting a graph compiled for the Snapdragon NPU when QNN refuses it. QNN compiles it as a whole; when that
 * fails, the node to blame comes from QNN's errors (QnnLog) or, when they name none, from a bisection over
 * the graph (QNN gets only its first k nodes). That node, in every layer, is then computed in double precision
 * (OnnxPatcher.keepOnCpu), which keeps it on the CPU, and the graph is compiled again. Before the bisection a
 * tiny graph shows whether the NPU compiles anything at all. Every step goes into the report, with the
 * culprit's inputs and outputs (types and shapes from ONNX Runtime itself).
 */
public final class QnnBuild {
    /** The NPU side: compiling for QNN, in the NPU process on the phone (a stand-in in tests). */
    public interface Npu {
        /** Compiles the graph; {@code save}: as the QNN context to run. Null when it compiled, else the failure. */
        Failure compile(File graph, boolean save, boolean deep) throws Exception;

        /** What was logged in the last compilation that worked (null when nothing was read). */
        QnnLog lastLog();

        /** Null when QNN compiles a tiny float graph, else why not. */
        String canary() throws Exception;

        /** Types and shapes of all the graph's tensors (tensorTypes). */
        Map<String, OnnxPatcher.TensorType> types(File graph) throws Exception;

        long nowMs();
    }

    public static final class Failure {
        public final String message;
        public final QnnLog log;

        public Failure(String message, QnnLog log) {
            this.message = message == null ? "?" : message.trim();
            this.log = log != null ? log : QnnLog.parse(new ArrayList<String>());
        }

        /** QNN could not build the graph (the kind of failure a node can cause). */
        public boolean graphFailed() {
            return message.contains("finalize QNN graph") || message.contains("compose Qnn graph");
        }
    }

    public static final class Outcome {
        public boolean ok;
        /** The graph that compiled (the original or one with nodes kept on the CPU). */
        public File compiled;
        /** Nodes kept on the CPU. */
        public final Set<String> cpu = new LinkedHashSet<String>();
        public String report = "";
        /** What ONNX Runtime and QNN said in the compilation that worked. */
        public QnnLog log;
    }

    /**
     * @param cpuStart nodes a previous compilation kept on the CPU (they go there from the start)
     * @param budgetMs time for everything, the bisection included
     */
    public static Outcome run(File graph, Set<String> cpuStart, Npu npu, long budgetMs) throws Exception {
        return run(graph, cpuStart, npu, budgetMs, true);
    }

    /** @param deepFirst start with QNN's longest optimisation (faster runs), else with its default (lighter) */
    public static Outcome run(File graph, Set<String> cpuStart, Npu npu, long budgetMs, boolean deepFirst) throws Exception {
        Outcome out = new Outcome();
        long deadline = npu.nowMs() + budgetMs;
        List<OnnxPatcher.Node> nodes = OnnxPatcher.nodes(graph, new HashMap<String, Long>());
        Set<String> known = new HashSet<String>();
        // a list from an older rewrite of the graph: the same nodes, other tokens
        Map<String, String> byBareName = new HashMap<String, String>();
        for (OnnxPatcher.Node n : nodes) {
            known.add(n.name);
            byBareName.put(bare(n.name), n.name);
        }
        for (String c : cpuStart) {
            if (known.contains(c)) out.cpu.add(c);
            else if (byBareName.containsKey(bare(c))) out.cpu.add(byBareName.get(bare(c)));
        }
        File variant = new File(graph.getParentFile(), graph.getName().replace(".onnx", "") + ".cpu.onnx");
        Map<String, OnnxPatcher.TensorType> types = null;
        Map<String, Integer> cpuOps = new LinkedHashMap<String, Integer>();
        StringBuilder steps = new StringBuilder(), culprits = new StringBuilder();
        Failure first = null, last = null;
        boolean deep = deepFirst, canaryDone = false;
        File current = graph;
        try {
            for (int attempt = 1; attempt <= 8; attempt++) {
                if (!out.cpu.isEmpty()) {
                    if (types == null) types = npu.types(graph);
                    List<String> unmoved = OnnxPatcher.keepOnCpu(graph, variant, out.cpu, types);
                    if (!unmoved.isEmpty()) {
                        out.cpu.removeAll(unmoved);
                        culprits.append("\n  оставить процессору нельзя (нет вычисления в double): ").append(unmoved);
                    }
                    current = variant;
                } else {
                    current = graph;
                }
                long t0 = npu.nowMs();
                Failure f = npu.compile(current, true, deep);
                steps.append(steps.length() > 0 ? "; " : "").append(attempt).append(") ")
                        .append(deep ? "оптимизация QNN 3" : "оптимизация QNN по умолчанию")
                        .append(cpuOps.isEmpty() ? "" : ", на процессоре " + ops(cpuOps)).append(", ")
                        .append((npu.nowMs() - t0 + 500) / 1000).append(" с");
                if (f == null) {
                    out.ok = true;
                    out.compiled = current;
                    out.log = npu.lastLog();
                    steps.append(" — собрано");
                    break;
                }
                steps.append(" — ").append(f.message.length() > 100 ? f.message.substring(0, 100) + "…" : f.message);
                // a node moved to the CPU in double for which the CPU has no double kernel: it goes back
                String[] missing = missingKernel(f.message);
                if (missing != null && !out.cpu.isEmpty()) {
                    int before = out.cpu.size();
                    for (OnnxPatcher.Node n : nodes) if (n.opType.equals(missing[0])) out.cpu.remove(n.name);
                    if (out.cpu.size() < before) {
                        culprits.append("\n  нет вычисления в double на процессоре: ").append(missing[0]).append(" — остаётся как был");
                        continue;
                    }
                }
                // a part of the graph with a tensor of a shape known only when it runs: its maker and users go to the CPU
                String dyn = dynamicTensor(f.message);
                if (dyn != null && npu.nowMs() < deadline) {
                    List<String> moved = new ArrayList<String>();
                    for (OnnxPatcher.Node n : nodes) {
                        if ((n.outputs.contains(dyn) || n.inputs.contains(dyn)) && !n.opType.equals("Constant") && out.cpu.add(n.name)) {
                            moved.add(n.opType + " " + n.name);
                        }
                    }
                    if (!moved.isEmpty()) {
                        if (first == null) first = f;
                        last = f;
                        culprits.append("\n  форма ").append(dyn).append(" известна только при счёте — на процессор: ").append(moved);
                        continue;
                    }
                }
                if (first == null) first = f;
                last = f;
                if (!f.graphFailed() || npu.nowMs() > deadline) break;
                List<OnnxPatcher.Node> named = new ArrayList<OnnxPatcher.Node>();
                for (OnnxPatcher.Node n : f.log.failingNodes(nodes)) if (!out.cpu.contains(n.name)) named.add(n);
                String how = "QNN назвал узел";
                if (named.isEmpty()) {
                    if (!canaryDone) {
                        canaryDone = true;
                        String canary = npu.canary();
                        steps.append("; крошечный граф (MatMul, Add, Softmax) ").append(canary == null ? "собирается" : "не собирается: " + canary);
                        if (canary != null) {
                            steps.append(" — дело не в модели, а в доступе к NPU");
                            break;
                        }
                    }
                    if (types == null) types = npu.types(graph);
                    int[] probes = {0};
                    OnnxPatcher.Node c = bisect(current, types, npu, deadline, probes);
                    steps.append("; поиск по графу: ").append(probes[0]).append(" сборок");
                    if (c == null || out.cpu.contains(c.name) || !known.contains(c.name)) {
                        steps.append(c == null ? ", кончилось время" : ", виновник не найден");
                        if (!deep) break;
                        deep = false;
                        continue;
                    }
                    named.add(c);
                    how = "поиск нашёл узел";
                }
                if (types == null) types = npu.types(graph);
                for (OnnxPatcher.Node c : named) {
                    culprits.append("\n  ").append(how).append(": ").append(describe(c, types));
                    for (String name : QnnLog.inEveryLayer(nodes, c)) {
                        if (out.cpu.add(name)) cpuOps.put(c.opType, cpuOps.containsKey(c.opType) ? cpuOps.get(c.opType) + 1 : 1);
                    }
                }
            }
        } finally {
            new File(graph.getParentFile(), graph.getName().replace(".onnx", "") + ".probe.onnx").delete();
        }
        if (!out.ok && current == variant) variant.delete();
        StringBuilder rep = new StringBuilder();
        if (first != null) {
            String summary = first.log.summary(10);
            rep.append(out.ok ? "первая сборка: " : "").append(first.message).append(summary.isEmpty() ? "" : "\n  " + summary);
        }
        rep.append(culprits);
        if (last != null && last != first && !out.ok) {
            String summary = last.log.summary(6);
            rep.append("\n  последняя попытка: ").append(last.message).append(summary.isEmpty() ? "" : "\n  " + summary);
        }
        rep.append(rep.length() > 0 ? "\n  " : "").append("сборка: ").append(steps);
        out.report = rep.toString();
        return out;
    }

    private static final java.util.regex.Pattern MISSING_KERNEL =
            java.util.regex.Pattern.compile("Could not find an implementation for (\\w+)\\(\\d+\\) node with name '([^']*)'");

    /** {op type, node name} when ONNX Runtime found no kernel for a node (a double one, after keepOnCpu); else null. */
    static String[] missingKernel(String message) {
        java.util.regex.Matcher m = MISSING_KERNEL.matcher(message);
        return m.find() ? new String[]{m.group(1), m.group(2)} : null;
    }

    public static String[] missingKernelForTest(String message) {
        return missingKernel(message);
    }

    /** A node's name without its unique token (OnnxPatcher.forQnn). */
    static String bare(String name) {
        return name.replaceFirst("_N\\d+N$", "");
    }

    private static String ops(Map<String, Integer> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            sb.append(sb.length() > 0 ? ", " : "").append(e.getKey()).append(" ×").append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * Where the NPU's result goes wrong, from the largest and mean magnitude of every watched tensor ({max, mean})
     * on the NPU and on the CPU (OnnxPatcher.withRanges), in graph order: the first tensor that is infinite or
     * NaN on the NPU, with the node that makes it and its inputs on both — or, without one, the first whose mean
     * magnitude is off by more than 10% — and the tensors that leave fp16's range even in fp32.
     */
    public static String compareRanges(List<String> order, Map<String, float[]> cpu, Map<String, float[]> npu,
                                       List<OnnxPatcher.Node> nodes, Map<String, OnnxPatcher.TensorType> types) {
        return compareRanges(order, cpu, npu, nodes, types, new HashMap<String, double[]>());
    }

    /**
     * As above, and how the first bad tensor is made: the nodes up the graph from it (up to six steps, thirty
     * nodes), with the values of small constants and the magnitudes on the CPU ({@code cpu} may watch more
     * tensors than {@code order}) and the NPU.
     */
    public static String compareRanges(List<String> order, Map<String, float[]> cpu, Map<String, float[]> npu,
                                       List<OnnxPatcher.Node> nodes, Map<String, OnnxPatcher.TensorType> types,
                                       Map<String, double[]> consts) {
        Map<String, OnnxPatcher.Node> producer = new HashMap<String, OnnxPatcher.Node>();
        for (OnnxPatcher.Node n : nodes) for (String o : n.outputs) producer.put(o, n);
        StringBuilder sb = new StringBuilder();
        String bad = null, off = null;
        for (String t : order) {
            float[] n = npu.get(t), c = cpu.get(t);
            if (n == null || c == null) continue;
            if (bad == null && !(finite(n[0]) && finite(n[1]))) bad = t;
            if (off == null && finite(c[1]) && Math.abs(n[1] - c[1]) > 0.1 * Math.max(Math.abs(c[1]), 1e-3)) off = t;
        }
        String first = bad != null ? bad : off;
        if (first == null) {
            sb.append("NPU и процессор совпадают на всех ").append(order.size()).append(" проверенных тензорах");
        } else {
            sb.append(bad != null ? "на NPU первым портится " : "первое расхождение NPU с процессором (больше 10%): ").append(first)
                    .append(" — NPU ").append(range(npu.get(first))).append(", процессор ").append(range(cpu.get(first)));
            OnnxPatcher.Node p = producer.get(first);
            if (p != null) {
                sb.append("\n  его делает ").append(describe(p, types, consts));
                for (String in : p.inputs) {
                    if (in.isEmpty() || consts.containsKey(in)) continue;
                    sb.append("\n  вход ").append(in).append(": NPU ").append(npu.containsKey(in) ? range(npu.get(in)) : "—")
                            .append(", процессор ").append(cpu.containsKey(in) ? range(cpu.get(in)) : "—");
                }
                sb.append("\n  откуда (вверх по графу; процессор / NPU):");
                Set<OnnxPatcher.Node> seen = new HashSet<OnnxPatcher.Node>();
                seen.add(p);
                trace(p, 1, producer, types, consts, cpu, npu, seen, sb);
            }
        }
        List<String> over = new ArrayList<String>();
        for (String t : order) {
            float[] c = cpu.get(t);
            if (c != null && c[0] > 65504f) over.add(t + " (" + String.format(java.util.Locale.ROOT, "%.3g", c[0]) + ")");
        }
        sb.append("\n  за пределы fp16 (65504) даже на процессоре выходят: ")
                .append(over.isEmpty() ? "ничего" : over.size() <= 5 ? over.toString() : over.subList(0, 5) + " и ещё " + (over.size() - 5));
        return sb.toString();
    }

    /** Float tensors (not constants) in graph order, at most {@code cap} of them, evenly spread. */
    public static List<String> watchList(List<OnnxPatcher.Node> nodes, Map<String, OnnxPatcher.TensorType> types, int cap) {
        List<String> watch = new ArrayList<String>();
        for (OnnxPatcher.Node n : nodes) {
            if ("Constant".equals(n.opType)) continue;
            for (String o : n.outputs) {
                OnnxPatcher.TensorType t = types.get(o);
                if (t != null && t.elem == OnnxPatcher.TYPE_FLOAT && t.dims.length > 0) watch.add(o);
            }
        }
        if (watch.size() <= cap) return watch;
        List<String> every = new ArrayList<String>();
        for (int i = 0; i < cap; i++) every.add(watch.get((int) ((long) i * (watch.size() - 1) / (cap - 1))));
        return every;
    }

    /**
     * As watchList, but the attention core of every layer (the tensors our rewrite of MultiHeadAttention makes:
     * q, k, v, scores, mask, probabilities, context) is always watched; the rest fills up to {@code cap}.
     */
    public static List<String> npuWatchList(List<OnnxPatcher.Node> nodes, Map<String, OnnxPatcher.TensorType> types, int cap) {
        List<String> all = watchList(nodes, types, Integer.MAX_VALUE);
        Set<String> keep = new LinkedHashSet<String>();
        for (String t : all) {
            if (t.startsWith("qnn") && t.matches(".*/(q|k|v|qk|scores|mask|masked|probs|ctx)(_N\\d+N)?$")) keep.add(t);
        }
        // the inputs of the attention core too (what MultiHeadAttention got)
        Map<String, OnnxPatcher.Node> byOutput = new HashMap<String, OnnxPatcher.Node>();
        for (OnnxPatcher.Node n : nodes) for (String o : n.outputs) byOutput.put(o, n);
        for (OnnxPatcher.Node n : nodes) {
            if (n.opType.equals("Reshape") && n.name.matches("qnn\\d+_.*/(q4|k4|v4)(_N\\d+N)?") && all.contains(n.inputs.get(0))) keep.add(n.inputs.get(0));
        }
        int rest = Math.max(0, cap - keep.size());
        List<String> others = new ArrayList<String>();
        for (String t : all) if (!keep.contains(t)) others.add(t);
        Set<String> chosen = new HashSet<String>(keep);
        if (others.size() <= rest) chosen.addAll(others);
        else for (int i = 0; i < rest; i++) chosen.add(others.get((int) ((long) i * (others.size() - 1) / Math.max(1, rest - 1))));
        List<String> out = new ArrayList<String>();
        for (String t : all) if (chosen.contains(t)) out.add(t);
        return out;
    }

    /**
     * Nodes with an output whose size is known only when the graph runs: after a NonZero (the image's real tokens
     * selected from the pooled ones) and what follows — the last norm, the projection. QNN builds static shapes
     * only; most of its ops refuse such a node, but not all (Reciprocal does not check), and a part of the graph
     * with such an input or output does not compile.
     *
     * <p>Found by the graph's structure, not by ONNX Runtime's shape inference on the raw graph: there most shapes
     * are computed (Shape → Gather → Concat → Reshape) and unknown until constant folding, which makes them fixed
     * for fixed inputs. A size depends on the data only where an op's output count does (NonZero, Compress,
     * Unique, NonMaxSuppression); it passes on through data, and through the inputs that give a shape (Reshape's
     * target, Expand's, Slice's bounds…) when their values come from such a size. Shape and Size stop it: their
     * own shape is fixed.
     */
    public static Set<String> dynamicShapeNodes(List<OnnxPatcher.Node> nodes) {
        Set<String> sized = new HashSet<String>(), valued = new HashSet<String>(), out = new LinkedHashSet<String>();
        for (OnnxPatcher.Node n : nodes) {
            boolean dyn = DATA_SIZED.contains(n.opType), val = false;
            if (!dyn) {
                int[] shapeIn = SHAPE_INPUTS.get(n.opType);
                for (int i = 0; i < n.inputs.size(); i++) {
                    String in = n.inputs.get(i);
                    if (in.isEmpty()) continue;
                    boolean isShape = false;
                    if (shapeIn != null) for (int k : shapeIn) isShape |= k == i;
                    if (n.opType.equals("Shape") || n.opType.equals("Size")) {
                        val |= sized.contains(in) || valued.contains(in);
                    } else if (sized.contains(in) && !isShape) {
                        dyn = true;
                    } else if (isShape && (sized.contains(in) || valued.contains(in))) {
                        dyn = true;
                    } else if (valued.contains(in)) {
                        val = true;
                    }
                }
            }
            if (dyn) {
                sized.addAll(n.outputs);
                out.add(n.name);
            } else if (val) {
                valued.addAll(n.outputs);
            }
        }
        return out;
    }

    /** Ops whose output size depends on the values of their input. */
    private static final Set<String> DATA_SIZED = new HashSet<String>(java.util.Arrays.asList("NonZero", "Compress", "Unique",
            "NonMaxSuppression"));
    /** Inputs that give an op's output shape by their values. */
    private static final Map<String, int[]> SHAPE_INPUTS = new HashMap<String, int[]>();

    static {
        SHAPE_INPUTS.put("Reshape", new int[]{1});
        SHAPE_INPUTS.put("Expand", new int[]{1});
        SHAPE_INPUTS.put("Tile", new int[]{1});
        SHAPE_INPUTS.put("Range", new int[]{0, 1, 2});
        SHAPE_INPUTS.put("ConstantOfShape", new int[]{0});
        SHAPE_INPUTS.put("Slice", new int[]{1, 2, 3, 4});
        SHAPE_INPUTS.put("Pad", new int[]{1});
        SHAPE_INPUTS.put("Resize", new int[]{2, 3});
        SHAPE_INPUTS.put("Upsample", new int[]{1});
        SHAPE_INPUTS.put("TopK", new int[]{1});
        SHAPE_INPUTS.put("Split", new int[]{1});
        SHAPE_INPUTS.put("OneHot", new int[]{1});
        SHAPE_INPUTS.put("Squeeze", new int[]{1});
        SHAPE_INPUTS.put("Unsqueeze", new int[]{1});
        for (String r : new String[]{"ReduceMax", "ReduceMin", "ReduceMean", "ReduceSum", "ReduceProd", "ReduceL1", "ReduceL2",
                "ReduceLogSum", "ReduceLogSumExp", "ReduceSumSquare"}) {
            SHAPE_INPUTS.put(r, new int[]{1});
        }
    }

    private static final java.util.regex.Pattern DYNAMIC =
            java.util.regex.Pattern.compile("Dynamic shape is not supported yet, for output: (\\S+)");

    /** The tensor ONNX Runtime's QNN provider names as of a dynamic shape (an input or an output of a part), or null. */
    static String dynamicTensor(String message) {
        java.util.regex.Matcher m = DYNAMIC.matcher(message);
        return m.find() ? m.group(1) : null;
    }

    public static String dynamicTensorForTest(String message) {
        return dynamicTensor(message);
    }

    /**
     * Nodes whose values depend on the graph's integer inputs (the patch positions) and not on its float ones (the
     * pixels): padding, the attention mask, RoPE angles, position embeddings, pooling indices. They are integer
     * and boolean logic the NPU gets wrong (seen: the mask in the keys came out empty) and cheap — once per image
     * on the CPU, in fp32. A Shape of a tensor depends on its shape only (fixed here), not its values.
     */
    public static Set<String> positionOnlyNodes(List<OnnxPatcher.Node> nodes, Map<String, OnnxPatcher.TensorType> types,
                                                java.util.Collection<String> graphInputs) {
        Set<String> pixels = new HashSet<String>(), positions = new HashSet<String>();
        for (String in : graphInputs) {
            OnnxPatcher.TensorType t = types.get(in);
            if (t != null && (t.elem == OnnxPatcher.TYPE_FLOAT || t.elem == OnnxPatcher.TYPE_FLOAT16)) pixels.add(in);
            else positions.add(in);
        }
        Set<String> out = new LinkedHashSet<String>();
        for (OnnxPatcher.Node n : nodes) {
            if ("Shape".equals(n.opType) || "Size".equals(n.opType) || "Constant".equals(n.opType)) continue;
            boolean px = false, pos = false;
            for (String in : n.inputs) {
                px |= pixels.contains(in);
                pos |= positions.contains(in);
            }
            if (px) pixels.addAll(n.outputs);
            if (pos) positions.addAll(n.outputs);
            if (pos && !px) out.add(n.name);
        }
        return out;
    }

    /**
     * Nodes that make or take a tensor whose values, in fp32 on the CPU, go beyond {@code limit}: in fp16 (65504
     * at most) they would overflow or come close, so they are better kept on the CPU.
     */
    public static Set<String> overflowNodes(List<OnnxPatcher.Node> nodes, Map<String, float[]> cpu, float limit) {
        Set<String> big = new HashSet<String>();
        for (Map.Entry<String, float[]> e : cpu.entrySet()) {
            float m = e.getValue()[0];
            if (Float.isNaN(m) || m > limit) big.add(e.getKey());
        }
        Set<String> out = new LinkedHashSet<String>();
        for (OnnxPatcher.Node n : nodes) {
            if ("Constant".equals(n.opType)) continue;
            boolean touches = false;
            for (String t : n.inputs) touches |= big.contains(t);
            for (String t : n.outputs) touches |= big.contains(t);
            if (touches) out.add(n.name);
        }
        return out;
    }

    /** {max, mean} magnitude of each watched tensor from a run of a graph made by OnnxPatcher.withRanges. */
    public static Map<String, float[]> readRanges(OrtSession.Result r, List<String> watch) throws Exception {
        Map<String, float[]> out = new HashMap<String, float[]>();
        for (String t : watch) {
            float max = ((ai.onnxruntime.OnnxTensor) r.get(OnnxPatcher.RANGE_MAX + t).get()).getFloatBuffer().get(0);
            float mean = ((ai.onnxruntime.OnnxTensor) r.get(OnnxPatcher.RANGE_MEAN + t).get()).getFloatBuffer().get(0);
            out.put(t, new float[]{max, mean});
        }
        return out;
    }

    private static void trace(OnnxPatcher.Node n, int depth, Map<String, OnnxPatcher.Node> producer,
                              Map<String, OnnxPatcher.TensorType> types, Map<String, double[]> consts,
                              Map<String, float[]> cpu, Map<String, float[]> npu, Set<OnnxPatcher.Node> seen, StringBuilder sb) {
        if (depth > 6) return;
        for (String in : n.inputs) {
            OnnxPatcher.Node p = producer.get(in);
            if (p == null || "Constant".equals(p.opType) || seen.size() >= 30 || !seen.add(p)) continue;
            sb.append("\n  ");
            for (int i = 0; i < depth; i++) sb.append("  ");
            sb.append("← ").append(describe(p, types, consts)).append(" | ").append(cpu.containsKey(in) ? range(cpu.get(in)) : "—")
                    .append(" / ").append(npu.containsKey(in) ? range(npu.get(in)) : "—");
            trace(p, depth + 1, producer, types, consts, cpu, npu, seen, sb);
        }
    }

    private static boolean finite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    private static String range(float[] r) {
        return r == null ? "—" : String.format(java.util.Locale.ROOT, "max %.4g, среднее %.4g", r[0], r[1]);
    }

    /** "Gather name: float[2048,768], int64[1,630] → float[1,630,768] (axis=0)". */
    public static String describe(OnnxPatcher.Node n, Map<String, OnnxPatcher.TensorType> types) {
        return describe(n, types, new HashMap<String, double[]>());
    }

    /** As above, with the values of small constant inputs ("=[-10000]"). */
    public static String describe(OnnxPatcher.Node n, Map<String, OnnxPatcher.TensorType> types, Map<String, double[]> consts) {
        StringBuilder sb = new StringBuilder(n.domain.isEmpty() ? "" : n.domain + ":").append(n.opType).append(' ').append(n.name).append(": ");
        for (int i = 0; i < n.inputs.size(); i++) {
            String in = n.inputs.get(i);
            OnnxPatcher.TensorType t = types.get(in);
            sb.append(i > 0 ? ", " : "").append(in.isEmpty() ? "—" : t != null ? t.toString() : "?");
            double[] c = consts.get(in);
            if (c != null) {
                sb.append("=[");
                for (int j = 0; j < c.length; j++) sb.append(j > 0 ? "," : "").append(String.format(java.util.Locale.ROOT, "%.4g", c[j]));
                sb.append(']');
            }
        }
        sb.append(" →");
        for (String o : n.outputs) {
            OnnxPatcher.TensorType t = types.get(o);
            sb.append(' ').append(t != null ? t.toString() : "?");
        }
        if (!n.attrs.isEmpty()) {
            sb.append(" (");
            int k = 0;
            for (Map.Entry<String, Object> a : n.attrs.entrySet()) {
                Object v = a.getValue();
                sb.append(k++ > 0 ? ", " : "").append(a.getKey()).append('=')
                        .append(v instanceof long[] ? java.util.Arrays.toString((long[]) v) : String.valueOf(v));
            }
            sb.append(')');
        }
        return sb.toString();
    }

    /**
     * The node whose addition breaks the compilation: QNN compiles graphs of the first k nodes (outputs: the
     * float tensors later nodes need). Null when the time ran out.
     */
    static OnnxPatcher.Node bisect(final File graph, final Map<String, OnnxPatcher.TensorType> types, final Npu npu,
                                   final long deadline, final int[] probes) throws Exception {
        final List<OnnxPatcher.Node> nodes = OnnxPatcher.nodes(graph, new HashMap<String, Long>());
        final File probe = new File(graph.getParentFile(), graph.getName().replace(".onnx", "").replace(".cpu", "") + ".probe.onnx");
        try {
            int c = QnnLog.firstBreaking(nodes.size(), new QnnLog.Probe() {
                @Override
                public boolean compiles(int k) throws Exception {
                    if (npu.nowMs() > deadline) throw new java.util.concurrent.TimeoutException();
                    List<String> outputs = frontier(nodes, k, types);
                    if (outputs.isEmpty()) return true;
                    probes[0]++;
                    OnnxPatcher.subgraph(graph, probe, k, outputs);
                    Failure f = npu.compile(probe, false, false);
                    return f == null || !f.graphFailed();
                }
            });
            return nodes.get(c);
        } catch (java.util.concurrent.TimeoutException e) {
            return null;
        }
    }

    /** Float tensors made by the first k nodes that later nodes use (or the graph returns). */
    static List<String> frontier(List<OnnxPatcher.Node> nodes, int k, Map<String, OnnxPatcher.TensorType> types) {
        Set<String> later = new HashSet<String>();
        for (int i = k; i < nodes.size(); i++) later.addAll(nodes.get(i).inputs);
        Set<String> made = new LinkedHashSet<String>();
        for (int i = 0; i < k; i++) made.addAll(nodes.get(i).outputs);
        Set<String> graphOutputs = new HashSet<String>();
        if (k == nodes.size()) graphOutputs.addAll(made);
        List<String> out = new ArrayList<String>();
        for (String t : made) {
            if (t.isEmpty() || !(later.contains(t) || graphOutputs.contains(t))) continue;
            OnnxPatcher.TensorType tt = types.get(t);
            if (tt != null && tt.elem == OnnxPatcher.TYPE_FLOAT) out.add(t);
        }
        return out;
    }

    /**
     * Types and shapes of every tensor in the graph, from ONNX Runtime: a copy of the graph whose outputs are
     * all its tensors, without types, which ONNX Runtime infers when it loads it (on the CPU, never run).
     */
    public static Map<String, OnnxPatcher.TensorType> tensorTypes(OrtEnvironment env, File graph, Map<String, Long> dims)
            throws Exception {
        Map<String, OnnxPatcher.TensorType> out = new HashMap<String, OnnxPatcher.TensorType>(OnnxPatcher.initializerTypes(graph));
        List<String> all = new ArrayList<String>();
        for (OnnxPatcher.Node n : OnnxPatcher.nodes(graph, new HashMap<String, Long>())) {
            for (String o : n.outputs) if (!o.isEmpty()) all.add(o);
        }
        File f = new File(graph.getParentFile(), graph.getName().replace(".onnx", "") + ".types.onnx");
        try {
            OnnxPatcher.subgraph(graph, f, -1, all);
            OrtSession.SessionOptions o = new OrtSession.SessionOptions();
            o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
            for (Map.Entry<String, Long> d : dims.entrySet()) o.setSymbolicDimensionValue(d.getKey(), d.getValue());
            OrtSession s = env.createSession(f.getPath(), o);
            try {
                put(out, s.getInputInfo());
                put(out, s.getOutputInfo());
            } finally {
                s.close();
                o.close();
            }
        } finally {
            f.delete();
        }
        return out;
    }

    private static void put(Map<String, OnnxPatcher.TensorType> out, Map<String, NodeInfo> infos) {
        for (Map.Entry<String, NodeInfo> e : infos.entrySet()) {
            if (!(e.getValue().getInfo() instanceof TensorInfo)) continue;
            TensorInfo t = (TensorInfo) e.getValue().getInfo();
            out.put(e.getKey(), new OnnxPatcher.TensorType(elemType(t.onnxType), t.getShape()));
        }
    }

    /** ONNX's TensorProto data type of an ONNX Runtime Java type (the Java enum numbers them its own way). */
    static int elemType(TensorInfo.OnnxTensorType t) {
        String[] onnx = {"UNDEFINED", "FLOAT", "UINT8", "INT8", "UINT16", "INT16", "INT32", "INT64", "STRING", "BOOL", "FLOAT16",
                "DOUBLE", "UINT32", "UINT64", "COMPLEX64", "COMPLEX128", "BFLOAT16"};
        String name = t.name().replace("ONNX_TENSOR_ELEMENT_DATA_TYPE_", "");
        for (int i = 0; i < onnx.length; i++) if (onnx[i].equals(name)) return i;
        return 0;
    }
}
