package io.github.teoplaydor.semsearch.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What ONNX Runtime and Qualcomm's QNN said while building an NPU session, read from the process's log
 * ({@code logcat -v tag} lines): how much of the graph QNN took, which ops it refused and why, and QNN's own
 * errors — the reasons behind "Failed to finalize QNN graph", which reach the app only through the log
 * (ONNX Runtime passes QNN's messages to its default logger at the verbose level).
 */
public final class QnnLog {
    public int partitions = -1, nodes = -1, supported = -1;
    /** QNN's error code when finalizing (compiling) a graph failed. */
    public String finalizeCode;
    /** Rules of the name-based node assignment ONNX Runtime matched (-1: none were given or it said nothing). */
    public int layeringMatched = -1;
    public String layeringError;
    /** QNN declined BF16 mode (needs a newer SoC), and the reason it gave. */
    public String bf16Refused;
    /** op type → names of the nodes QNN refused, and the first reason given for that op type. */
    public final Map<String, Set<String>> refused = new LinkedHashMap<String, Set<String>>();
    public final Map<String, String> reasons = new HashMap<String, String>();
    /**
     * QNN's errors and warnings while compiling (after ONNX Runtime split the graph), deduplicated, in order —
     * the reasons a compilation failed. Errors while QNN checked single nodes come before that: they only mean
     * the node stays on the CPU, so they are kept apart.
     */
    public final List<String> errors = new ArrayList<String>(), warnings = new ArrayList<String>();
    public final List<String> checkErrors = new ArrayList<String>();
    /** Errors of other parts of the process, e.g. the DSP loader (FastRPC). */
    public final List<String> system = new ArrayList<String>();

    private static final Pattern LINE = Pattern.compile("^([VDIWEF])/([^:]*?)\\s*(?:\\(\\s*\\d+\\))?: ?(.*)$");
    private static final Pattern PARTITIONS = Pattern.compile("Number of partitions supported by QNN EP: (\\d+)"
            + "(?:, number of nodes in the graph: (\\d+), number of nodes supported by QNN: (\\d+))?");
    private static final Pattern FINALIZE = Pattern.compile("Failed to finalize QNN graph\\. Error code: (\\d+)");
    private static final Pattern LAYERING = Pattern.compile("LayeringIndex created\\. Matched (\\d+) out of (\\d+)");
    private static final Pattern NODE = Pattern.compile("Operator type: (\\S+) Node name: (.*?) Node index: \\d+");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]{2,})\"|'([^']{2,})'");
    private static final Pattern SLASHED = Pattern.compile("(/[\\w./:-]{3,})");

    public static QnnLog parse(List<String> lines) {
        QnnLog q = new QnnLog();
        List<String> failedOps = null;
        for (String raw : lines) {
            String level = "", tag = "", text = raw;
            Matcher lm = LINE.matcher(raw);
            if (lm.matches()) {
                level = lm.group(1);
                tag = lm.group(2).trim();
                text = lm.group(3);
            }
            if (!tag.isEmpty() && !"onnxruntime".equals(tag)) {
                // the DSP loader's complaints about folders it may not watch are noise
                if (("E".equals(level) || "F".equals(level)) && !text.contains("add watcher") && q.system.size() < 6) {
                    addOnce(q.system, tag + ": " + text.trim());
                }
                continue;
            }
            boolean fromQnn = text.contains("QnnLogging");
            String msg = text.trim();
            if (msg.startsWith("[")) {
                int close = msg.indexOf("] ");
                if (close > 0) msg = msg.substring(close + 2).trim();
            }
            if (fromQnn) {
                if (msg.contains("<E>")) {
                    addOnce(q.errors, msg);
                } else if (msg.contains("<W>")) {
                    addOnce(q.warnings, msg);
                } else if (!msg.matches(".*<[IVD]>.*") && msg.toLowerCase(java.util.Locale.ROOT)
                        .matches(".*(error|fail|could not|cannot|unable|invalid|exceed|not supported|insufficient).*")) {
                    addOnce(q.errors, msg);
                }
                continue;
            }
            Matcher m;
            if (msg.startsWith("Validation FAILED")) {
                failedOps = new ArrayList<String>();
                continue;
            }
            if (msg.startsWith("Validation PASSED")) {
                failedOps = null;
                continue;
            }
            if (failedOps != null && (m = NODE.matcher(msg)).find()) {
                String op = m.group(1);
                Set<String> names = q.refused.get(op);
                if (names == null) q.refused.put(op, names = new LinkedHashSet<String>());
                names.add(m.group(2));
                failedOps.add(op);
                continue;
            }
            if (failedOps != null && msg.startsWith("REASON")) {
                String reason = msg.replaceFirst("^REASON\\s*:\\s*", "");
                for (String op : failedOps) if (!q.reasons.containsKey(op)) q.reasons.put(op, reason);
                failedOps = null;
                continue;
            }
            if ((m = PARTITIONS.matcher(msg)).find()) {
                // what QNN said so far was about single nodes (it decides which ones it takes)
                for (String e : q.errors) addOnce(q.checkErrors, e);
                q.errors.clear();
                q.warnings.clear();
                q.partitions = Integer.parseInt(m.group(1));
                if (m.group(2) != null) {
                    q.nodes = Integer.parseInt(m.group(2));
                    q.supported = Integer.parseInt(m.group(3));
                } else {
                    q.supported = 0;
                }
            } else if ((m = FINALIZE.matcher(msg)).find()) {
                q.finalizeCode = m.group(1);
            } else if ((m = LAYERING.matcher(msg)).find()) {
                q.layeringMatched = Integer.parseInt(m.group(1));
            } else if (msg.contains("could not be mapped to any available Execution Provider")) {
                q.layeringError = msg;
            } else if (msg.contains("BF16 mode is enabled but")) {
                q.bf16Refused = msg;
            }
        }
        return q;
    }

    private static void addOnce(List<String> l, String s) {
        if (s.length() > 240) s = s.substring(0, 240) + "…";
        if (!l.contains(s) && l.size() < 40) l.add(s);
    }

    /**
     * The graph's nodes QNN's errors point at: names in quotes or ONNX-style paths that are a node's name, or
     * start with one (QNN names the ops it makes for a node after it, with a suffix).
     */
    public List<OnnxPatcher.Node> failingNodes(List<OnnxPatcher.Node> graph) {
        Map<String, OnnxPatcher.Node> byName = new HashMap<String, OnnxPatcher.Node>();
        for (OnnxPatcher.Node n : graph) {
            if (n.name.isEmpty()) continue;
            byName.put(n.name, n);
            byName.put(sanitized(n.name), n);
        }
        Set<OnnxPatcher.Node> out = new LinkedHashSet<OnnxPatcher.Node>();
        for (String e : errors) {
            List<String> cands = new ArrayList<String>();
            Matcher m = QUOTED.matcher(e);
            while (m.find()) cands.add(m.group(1) != null ? m.group(1) : m.group(2));
            m = SLASHED.matcher(e);
            while (m.find()) cands.add(m.group(1));
            for (String c : cands) {
                OnnxPatcher.Node n = longestPrefix(byName, c);
                if (n == null) n = longestPrefix(byName, sanitized(c));
                if (n != null) out.add(n);
            }
        }
        return new ArrayList<OnnxPatcher.Node>(out);
    }

    private static OnnxPatcher.Node longestPrefix(Map<String, OnnxPatcher.Node> byName, String s) {
        for (int end = s.length(); end >= 3; end--) {
            OnnxPatcher.Node n = byName.get(s.substring(0, end));
            if (n != null) return n;
        }
        return null;
    }

    static String sanitized(String s) {
        return s.replaceAll("[^A-Za-z0-9]", "_");
    }

    /**
     * The same node in every layer: nodes of the same op type whose names differ from this one only in numbers
     * (a node QNN cannot build in one layer it cannot build in the others either).
     */
    public static List<String> inEveryLayer(List<OnnxPatcher.Node> graph, OnnxPatcher.Node n) {
        String key = n.name.replaceAll("\\d+", "#");
        List<String> out = new ArrayList<String>();
        for (OnnxPatcher.Node o : graph) {
            if (o.opType.equals(n.opType) && !o.name.isEmpty() && o.name.replaceAll("\\d+", "#").equals(key)) out.add(o.name);
        }
        return out;
    }

    /** Whether the graph compiles for the NPU when QNN may take only its first {@code k} nodes. */
    public interface Probe {
        boolean compiles(int k) throws Exception;
    }

    /**
     * The node that breaks the compilation, by bisection over the nodes in graph order: QNN gets the first k
     * nodes, the rest stay on the CPU. Given that all {@code n} do not compile (and none trivially do), the
     * smallest k that does not compile ends with the culprit, index k − 1.
     */
    public static int firstBreaking(int n, Probe p) throws Exception {
        int lo = 0, hi = n;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (p.compiles(mid)) lo = mid;
            else hi = mid;
        }
        return hi - 1;
    }

    /**
     * QNN's own profile of the graph on the NPU ({@code profiling_level=detailed}: the CSV ONNX Runtime writes,
     * "Msg Timestamp,Message,Time,Unit of Measurement,Timing Source,Event Level,Event Identifier"): the time of its
     * ops by op type (the graph's node a QNN op is named after) and the slowest nodes, and its overall timings,
     * per run.
     */
    public static String profile(List<String> csv, List<OnnxPatcher.Node> graph, int runs) {
        return profile(csv, graph, runs, null);
    }

    /** What a node is (its inputs' and outputs' types and shapes, small constants), for the slowest ones. */
    public interface Describer {
        String describe(OnnxPatcher.Node n);
    }

    /** @param describer describes the 3 slowest nodes on lines of their own (null: names only) */
    public static String profile(List<String> csv, List<OnnxPatcher.Node> graph, int runs, Describer describer) {
        Map<String, OnnxPatcher.Node> byName = new HashMap<String, OnnxPatcher.Node>();
        for (OnnxPatcher.Node n : graph) {
            if (n.name.isEmpty()) continue;
            byName.put(n.name, n);
            byName.put(sanitized(n.name), n);
        }
        Map<String, Double> perNode = new LinkedHashMap<String, Double>(), totals = new LinkedHashMap<String, Double>();
        Map<String, String> units = new HashMap<String, String>();
        String nodeUnit = "";
        for (String line : csv) {
            String[] f = line.split(",", 7);
            if (f.length < 7 || f[0].startsWith("Msg Timestamp")) continue;
            double v;
            try {
                v = Double.parseDouble(f[2].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            String id = f[6].trim();
            if ("NODE".equals(f[1].trim()) && !"NULL".equals(id)) {
                perNode.put(id, (perNode.containsKey(id) ? perNode.get(id) : 0) + v);
                nodeUnit = f[3].trim();
            } else if (!"NODE".equals(f[1].trim()) && (!"NULL".equals(id) || "EXECUTE".equals(f[1].trim()))) {
                String k = "NULL".equals(id) ? f[1].trim() : id;
                totals.put(k, (totals.containsKey(k) ? totals.get(k) : 0) + v);
                units.put(k, f[3].trim());
            }
        }
        StringBuilder sb = new StringBuilder();
        int r = Math.max(1, runs);
        int shown = 0;
        for (Map.Entry<String, Double> e : totals.entrySet()) {
            if (shown++ >= 6) break;
            sb.append(sb.length() > 0 ? "; " : "").append(e.getKey()).append(' ').append(Math.round(e.getValue() / r)).append(' ')
                    .append(units.get(e.getKey()).toLowerCase(java.util.Locale.ROOT));
        }
        if (perNode.isEmpty()) return sb.length() > 0 ? sb.toString() : "QNN не дал профиля по операциям";
        double all = 0;
        Map<String, Double> byOp = new HashMap<String, Double>();
        Map<String, String> opOf = new HashMap<String, String>();
        Map<String, OnnxPatcher.Node> nodeOf = new HashMap<String, OnnxPatcher.Node>();
        for (Map.Entry<String, Double> e : perNode.entrySet()) {
            OnnxPatcher.Node n = longestPrefix(byName, e.getKey());
            if (n == null) n = longestPrefix(byName, sanitized(e.getKey()));
            String op = n != null ? n.opType : "?";
            opOf.put(e.getKey(), op);
            if (n != null) nodeOf.put(e.getKey(), n);
            byOp.put(op, (byOp.containsKey(op) ? byOp.get(op) : 0) + e.getValue());
            all += e.getValue();
        }
        List<Map.Entry<String, Double>> ops = new ArrayList<Map.Entry<String, Double>>(byOp.entrySet()), nodes =
                new ArrayList<Map.Entry<String, Double>>(perNode.entrySet());
        java.util.Comparator<Map.Entry<String, Double>> desc = new java.util.Comparator<Map.Entry<String, Double>>() {
            @Override
            public int compare(Map.Entry<String, Double> a, Map.Entry<String, Double> b) {
                return Double.compare(b.getValue(), a.getValue());
            }
        };
        java.util.Collections.sort(ops, desc);
        java.util.Collections.sort(nodes, desc);
        sb.append(sb.length() > 0 ? "\n" : "").append("по операциям (").append(perNode.size()).append(" операций QNN, всего ")
                .append(Math.round(all / r)).append(' ').append(nodeUnit.toLowerCase(java.util.Locale.ROOT)).append("): ");
        for (int i = 0; i < ops.size() && i < 8; i++) {
            sb.append(i > 0 ? ", " : "").append(ops.get(i).getKey()).append(' ')
                    .append(Math.round(100 * ops.get(i).getValue() / Math.max(1e-9, all))).append('%');
        }
        sb.append("\nдольше всего: ");
        for (int i = 0; i < nodes.size() && i < 5; i++) {
            String id = nodes.get(i).getKey();
            sb.append(i > 0 ? ", " : "").append(id.length() > 60 ? "…" + id.substring(id.length() - 59) : id).append(" (")
                    .append(opOf.get(id)).append(") ").append(Math.round(100 * nodes.get(i).getValue() / Math.max(1e-9, all))).append('%');
        }
        if (describer != null) {
            // what the slowest nodes are: shapes and constants show what can be computed otherwise
            java.util.Set<OnnxPatcher.Node> told = new java.util.HashSet<OnnxPatcher.Node>();
            for (int i = 0; i < nodes.size() && told.size() < 3; i++) {
                OnnxPatcher.Node n = nodeOf.get(nodes.get(i).getKey());
                if (n == null || !told.add(n)) continue;
                String d = describer.describe(n);
                sb.append("\n  ").append(d.length() > 400 ? d.substring(0, 400) + "…" : d);
            }
        }
        return sb.toString();
    }

    /** For the report: how much QNN took, what it refused and why, and its errors. */
    public String summary(int maxErrors) {
        StringBuilder sb = new StringBuilder();
        if (supported >= 0 && nodes > 0) {
            sb.append("QNN взял ").append(supported).append(" из ").append(nodes).append(" узлов");
            if (partitions > 0) sb.append(", частей ").append(partitions);
        } else if (supported == 0) {
            sb.append("QNN не взял ни одного узла");
        }
        if (!refused.isEmpty()) {
            sb.append(sb.length() > 0 ? "; " : "").append("не взял: ");
            int k = 0;
            for (Map.Entry<String, Set<String>> e : refused.entrySet()) {
                if (k++ > 0) sb.append(", ");
                if (k > 6) {
                    sb.append("…");
                    break;
                }
                sb.append(e.getKey()).append(" ×").append(e.getValue().size());
                String r = reasons.get(e.getKey());
                if (r != null && !r.isEmpty()) sb.append(" (").append(r.length() > 120 ? r.substring(0, 120) + "…" : r).append(')');
            }
        }
        if (finalizeCode != null) sb.append(sb.length() > 0 ? "; " : "").append("код ошибки сборки ").append(finalizeCode);
        if (layeringError != null) sb.append(sb.length() > 0 ? "; " : "").append(layeringError);
        if (layeringMatched >= 0) sb.append(sb.length() > 0 ? "; " : "").append("назначение на процессор применено");
        List<String> shown = errors.isEmpty() ? warnings : errors;
        for (int i = 0; i < shown.size() && i < maxErrors; i++) sb.append("\n  QNN при сборке: ").append(shown.get(i));
        if (shown.size() > maxErrors) sb.append("\n  QNN при сборке: … ещё ").append(shown.size() - maxErrors);
        if (shown.isEmpty() && partitions >= 0) sb.append("\n  QNN при сборке ничего не сообщил");
        if (!checkErrors.isEmpty()) {
            sb.append("\n  при проверке узлов QNN сообщил ").append(checkErrors.size()).append(" раз, например: ")
                    .append(checkErrors.get(0));
        }
        for (int i = 0; i < system.size() && i < 3; i++) sb.append("\n  ").append(system.get(i));
        return sb.toString();
    }
}
