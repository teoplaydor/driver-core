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
    }

    /**
     * @param cpuStart nodes a previous compilation kept on the CPU (they go there from the start)
     * @param budgetMs time for everything, the bisection included
     */
    public static Outcome run(File graph, Set<String> cpuStart, Npu npu, long budgetMs) throws Exception {
        Outcome out = new Outcome();
        long deadline = npu.nowMs() + budgetMs;
        List<OnnxPatcher.Node> nodes = OnnxPatcher.nodes(graph, new HashMap<String, Long>());
        Set<String> known = new HashSet<String>();
        for (OnnxPatcher.Node n : nodes) known.add(n.name);
        for (String c : cpuStart) if (known.contains(c)) out.cpu.add(c);
        File variant = new File(graph.getParentFile(), graph.getName().replace(".onnx", "") + ".cpu.onnx");
        Map<String, OnnxPatcher.TensorType> types = null;
        Map<String, Integer> cpuOps = new LinkedHashMap<String, Integer>();
        StringBuilder steps = new StringBuilder(), culprits = new StringBuilder();
        Failure first = null, last = null;
        boolean deep = true, canaryDone = false;
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
                    steps.append(" — собрано");
                    break;
                }
                steps.append(" — ").append(f.message.length() > 100 ? f.message.substring(0, 100) + "…" : f.message);
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

    private static String ops(Map<String, Integer> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            sb.append(sb.length() > 0 ? ", " : "").append(e.getKey()).append(" ×").append(e.getValue());
        }
        return sb.toString();
    }

    /** "Gather name: float[2048,768], int64[1,630] → float[1,630,768] (axis=0)". */
    public static String describe(OnnxPatcher.Node n, Map<String, OnnxPatcher.TensorType> types) {
        StringBuilder sb = new StringBuilder(n.domain.isEmpty() ? "" : n.domain + ":").append(n.opType).append(' ').append(n.name).append(": ");
        for (int i = 0; i < n.inputs.size(); i++) {
            String in = n.inputs.get(i);
            OnnxPatcher.TensorType t = types.get(in);
            sb.append(i > 0 ? ", " : "").append(in.isEmpty() ? "—" : t != null ? t.toString() : "?");
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
