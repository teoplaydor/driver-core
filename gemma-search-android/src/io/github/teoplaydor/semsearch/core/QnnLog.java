package io.github.teoplaydor.semsearch.core;

import java.util.ArrayList;
import java.util.Collection;
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
    /** op type → names of the nodes QNN refused, and the first reason given for that op type. */
    public final Map<String, Set<String>> refused = new LinkedHashMap<String, Set<String>>();
    public final Map<String, String> reasons = new HashMap<String, String>();
    /** QNN's errors and warnings, deduplicated, in order. */
    public final List<String> errors = new ArrayList<String>(), warnings = new ArrayList<String>();
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
                if (("E".equals(level) || "F".equals(level)) && q.system.size() < 6) addOnce(q.system, tag + ": " + text.trim());
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

    /**
     * ONNX Runtime's {@code session.name_based_layer_assignment} value that keeps these nodes on the CPU (it
     * matches substrings of node names; names its grammar cannot carry are left out).
     */
    public static String cpuAssignment(Collection<String> names) {
        StringBuilder sb = new StringBuilder();
        Set<String> seen = new LinkedHashSet<String>();
        for (String n : names) {
            String t = n.trim();
            if (t.isEmpty() || t.startsWith("=") || t.matches(".*[,;()].*") || !seen.add(t)) continue;
            sb.append(sb.length() == 0 ? "" : ", ").append(t);
        }
        return sb.length() == 0 ? "" : "cpu(" + sb + ")";
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
        List<String> shown = errors.isEmpty() ? warnings : errors;
        for (int i = 0; i < shown.size() && i < maxErrors; i++) sb.append("\n  QNN: ").append(shown.get(i));
        if (shown.size() > maxErrors) sb.append("\n  QNN: … ещё ").append(shown.size() - maxErrors);
        for (int i = 0; i < system.size() && i < 3; i++) sb.append("\n  ").append(system.get(i));
        return sb.toString();
    }
}
