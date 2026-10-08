package io.github.teoplaydor.semsearch.app;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;
import io.github.teoplaydor.semsearch.core.OnnxPatcher;
import io.github.teoplaydor.semsearch.core.OrtProfile;
import io.github.teoplaydor.semsearch.core.QnnBuild;
import io.github.teoplaydor.semsearch.core.QnnLog;
import io.github.teoplaydor.semsearch.core.QnnRuntime;

/**
 * EmbeddingGemma's vision encoder on the Snapdragon NPU (Hexagon HTP), in its own process ({@code :npu}): the
 * ONNX Runtime build with Qualcomm's QNN provider (downloaded, see QnnRuntime) cannot share a process with the
 * app's ONNX Runtime, and a driver crash here takes down only this process. Requests come through a plain
 * Binder; pixels and features travel through a memory-mapped file in the app's cache (same app, same pages).
 *
 * <p>The NPU compiles a graph for fixed shapes: one session per patch count (token budget), batch 1, compiled
 * once and kept as a QNN context next to the graph, so later starts skip the compilation. When QNN cannot
 * compile it, the reasons come from the log (QnnLog) and QnnBuild finds the node to blame and keeps it on the
 * CPU, then compiles again.
 */
public final class NpuService extends Service {
    static final String DESCRIPTOR = "io.github.teoplaydor.semsearch.NpuService";
    static final int INIT = 1, RUN = 2, PROFILE = 3, SCAN = 4;
    static final int OK = 0, FAILED = 1;

    // per process: Android makes a new service object for every bind after the last unbind, while the process
    // (with the libraries loaded and ONNX Runtime's environment) stays
    private static boolean loaded;
    private static OrtEnvironment env;
    /** The first image of the last run, for SCAN (compares that run's tensors on the NPU and the CPU). */
    private static float[] lastPixels;
    private static long[] lastPositions;
    private static int lastPatches;
    private String libDir;
    private File graph;
    private final Map<Integer, OrtSession> sessions = new HashMap<Integer, OrtSession>();
    /** The vision graph on the CPU in fp32, per patch count: the NPU is checked against it, and it stands in for an image the NPU gets wrong. */
    private final Map<Integer, OrtSession> cpuSessions = new HashMap<Integer, OrtSession>();
    /** Images the NPU gave non-numbers for, computed on the CPU instead. */
    private static int fallbacks;
    /** BF16 instead of fp16 on the NPU (fp32's range); per graph and patch count, noted next to the context. */
    private boolean bf16;
    /** QNN's number for the SoC in BF16 mode: QNN EP 1.29 allows BF16 from 88 on. */
    private static final String BF16_SOC = "88";
    /** Values beyond this (fp32, on the CPU) keep their nodes on the CPU: fp16 ends at 65504. */
    private static final float SAFE_FP16 = 16000f;
    /** The NPU's features must match the CPU's this closely (cosine), or they are not used. */
    private static final float MATCH = 0.98f;
    /**
     * Patch counts above this skip the search for where the NPU goes wrong (scanReport): one more compilation of
     * the whole graph with hundreds of extra outputs, minutes at 280 tokens — the search at 70 tokens shows the
     * same nodes.
     */
    private static final int SCAN_MAX_PATCHES = 1024;
    /** Patch counts whose compilation failed in this service object: the same error again at once, not another try. */
    private final Map<Integer, String> failures = new HashMap<Integer, String>();

    private final Binder binder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code < INIT || code > SCAN) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(DESCRIPTOR);
            try {
                switch (code) {
                    case INIT:
                        init(data.readString(), new File(data.readString()));
                        reply.writeInt(OK);
                        reply.writeInt(android.os.Process.myPid());
                        break;
                    case RUN: {
                        String io = data.readString();
                        int batch = data.readInt(), patches = data.readInt(), patchDim = data.readInt();
                        int floats = run(io, batch, patches, patchDim);
                        reply.writeInt(OK);
                        reply.writeInt(floats);
                        break;
                    }
                    default: {
                        int patches = data.readInt(), patchDim = data.readInt();
                        String s = code == PROFILE ? profile(patches, patchDim) : scan(patches, patchDim);
                        reply.writeInt(OK);
                        reply.writeString(s);
                    }
                }
            } catch (Throwable t) {
                reply.writeInt(FAILED);
                reply.writeString(t.getMessage() != null ? t.getMessage() : t.toString());
            }
            return true;
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        synchronized (this) {
            for (Map<Integer, OrtSession> m : java.util.Arrays.asList(sessions, cpuSessions)) {
                for (OrtSession s : m.values()) {
                    try {
                        s.close();
                    } catch (Exception ignored) {
                        // going away
                    }
                }
                m.clear();
            }
        }
        super.onDestroy();
    }

    private void init(String dir, File visionGraph) throws Exception {
        synchronized (NpuService.class) {
            if (!loaded) load(dir);
        }
        synchronized (this) {
            if (graph != null && !graph.equals(visionGraph)) {
                for (OrtSession s : sessions.values()) s.close();
                sessions.clear();
                for (OrtSession s : cpuSessions.values()) s.close();
                cpuSessions.clear();
                failures.clear();
            }
            libDir = dir;
            graph = visionGraph;
        }
    }

    /**
     * Loads QNN's host libraries (its HTP backend opens them by name, which finds already-loaded ones), points
     * the DSP loader at this chip's skel, and then ONNX Runtime from the same directory.
     */
    private static void load(String dir) throws Exception {
        android.system.Os.setenv("ADSP_LIBRARY_PATH", dir + ";/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;"
                + "/system/lib/rfsa/adsp;/dsp", true);
        File[] files = new File(dir).listFiles();
        if (files != null) {
            for (String name : QnnRuntime.HOST_LIBS) System.load(new File(dir, name).getAbsolutePath());
            for (File f : files) {
                if (f.getName().startsWith("libQnnHtpV") && f.getName().endsWith("Stub.so")) System.load(f.getAbsolutePath());
            }
        }
        // ONNX Runtime's core library first, from here: the JNI library's dependency then resolves to it (an
        // already-loaded soname) instead of the app's own build in the APK, whose symbol versions differ
        System.load(new File(dir, "libonnxruntime.so").getAbsolutePath());
        System.setProperty("onnxruntime.native.dir", dir);
        // QNN's messages reach ONNX Runtime's default logger at the verbose level only (sessions keep their own,
        // quieter level): this is what lets the reasons of a failed compilation be read from the log
        env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_VERBOSE, "npu");
        loaded = true;
    }

    /**
     * @param log   compilation: the session logs what QNN took and refused, and QNN its warnings and errors
     * @param deep  QNN's longest graph optimisation (faster runs), else its default
     */
    private OrtSession.SessionOptions options(File model, int patches, boolean log, boolean deep) throws Exception {
        return options(model, patches, log, deep, Collections.<String, String>emptyMap());
    }

    /** @param extra more QNN provider options (its profiling) */
    private OrtSession.SessionOptions options(File model, int patches, boolean log, boolean deep, Map<String, String> extra)
            throws Exception {
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        // basic optimisations only: later fusions would put back ops the NPU cannot run
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
        o.setIntraOpNumThreads(2);
        o.setSessionLogLevel(log ? OrtLoggingLevel.ORT_LOGGING_LEVEL_INFO : OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING);
        for (Map.Entry<String, Long> d : dims(model, patches).entrySet()) o.setSymbolicDimensionValue(d.getKey(), d.getValue());
        Map<String, String> qnn = new HashMap<String, String>();
        qnn.put("backend_path", new File(libDir, "libQnnHtp.so").getAbsolutePath());
        qnn.put("htp_performance_mode", "burst");
        if (bf16) {
            qnn.put("htp_bf16_enable", "1");
            qnn.put("soc_model", BF16_SOC);
        } else {
            qnn.put("enable_htp_fp16_precision", "1");
        }
        if (deep) qnn.put("htp_graph_finalization_optimization_mode", "3");
        qnn.putAll(extra);
        o.addQnn(qnn);
        return o;
    }

    /** Fixed sizes for the graph's symbolic dimensions: batch 1, this many patches. */
    private static Map<String, Long> dims(File model, int patches) throws IOException {
        Map<String, Long> out = new LinkedHashMap<String, Long>();
        for (List<String> dims : OnnxPatcher.inputDims(model).values()) {
            for (int i = 0; i < dims.size() && i < 2; i++) {
                String d = dims.get(i);
                if (d.isEmpty() || Character.isDigit(d.charAt(0)) || "?".equals(d)) continue;
                out.put(d, i == 0 ? 1L : (long) patches);
            }
        }
        return out;
    }

    /**
     * The compiled NPU graph for this many patches: from the saved QNN context, or compiled now (checked on this
     * image against the CPU) and saved.
     */
    private synchronized OrtSession session(int patches, float[] pixels, long[] positions, int patchDim) throws Exception {
        OrtSession s = sessions.get(patches);
        if (s != null) return s;
        if (graph == null) throw new IllegalStateException("NPU-процесс перезапущен без графа");
        if (failures.containsKey(patches)) throw new Exception(failures.get(patches));
        // next to the graph, so whatever stays on the CPU still finds its weights in the external data file
        File ctx = context(patches);
        if (ctx.exists() && ctx.lastModified() < graph.lastModified()) ctx.delete(); // made from an older graph
        // only a context that passed the check against the CPU is used (the precision note is written then): a
        // failed compilation may have left one behind
        if (ctx.exists() && !note(patches, "precision").exists()) ctx.delete();
        if (ctx.exists()) {
            bf16 = "bf16".equals(readText(note(patches, "precision")).trim());
            stage("загрузка готовой сборки под " + patches + " фрагментов");
            try {
                s = env.createSession(ctx.getPath(), options(graph, patches, false, true));
            } catch (Exception stale) {
                ctx.delete();
            }
        }
        if (s == null) {
            try {
                s = compile(patches, pixels, positions, patchDim);
            } catch (Exception e) {
                failures.put(patches, e.getMessage() != null ? e.getMessage() : e.toString());
                stage("сборка под " + patches + " фрагментов не удалась");
                throw e;
            }
        }
        sessions.put(patches, s);
        stage("прогоны на NPU, " + patches + " фрагментов (сборка готова)");
        return s;
    }

    /** The vision graph on the CPU in fp32 for this many patches (the reference, and the fallback). */
    private synchronized OrtSession cpuSession(int patches) throws Exception {
        OrtSession s = cpuSessions.get(patches);
        if (s == null) {
            OrtSession.SessionOptions o = new OrtSession.SessionOptions();
            o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
            o.setIntraOpNumThreads(2);
            for (Map.Entry<String, Long> d : dims(graph, patches).entrySet()) o.setSymbolicDimensionValue(d.getKey(), d.getValue());
            s = env.createSession(graph.getPath(), o);
            cpuSessions.put(patches, s);
        }
        return s;
    }

    /** Time for all compilations of one graph, the search for a culprit included. */
    private static final long COMPILE_BUDGET_MS = 15 * 60 * 1000;

    /**
     * Compiles the graph for the NPU and saves the QNN context. First the image runs on the CPU in fp32 with the
     * magnitude of every tensor: nodes that see values beyond SAFE_FP16 stay on the CPU (fp16 would overflow
     * there). Then QnnBuild compiles (finding nodes QNN cannot build), and the NPU's features for the image are
     * checked against the CPU's; if they differ, BF16 is tried (where QNN allows it), and if that does not help
     * either, the error says where the NPU's result goes wrong (scanReport).
     */
    private OrtSession compile(final int patches, float[] pixels, long[] positions, int patchDim) throws Exception {
        note(patches, "precision").delete();
        // the reference sessions of other detail levels go: a compilation needs the memory (they come back on demand)
        for (Integer p : new ArrayList<Integer>(cpuSessions.keySet())) {
            if (p != patches) cpuSessions.remove(p).close();
        }
        StringBuilder rep = new StringBuilder();
        Set<String> start = new LinkedHashSet<String>();
        for (String l : readText(note(patches, "cpu")).split("\n")) if (!l.trim().isEmpty()) start.add(l.trim());
        stage("сборка под " + patches + " фрагментов: типы тензоров");
        Map<String, OnnxPatcher.TensorType> types = QnnBuild.tensorTypes(env, graph, dims(graph, patches));
        List<OnnxPatcher.Node> nodes = OnnxPatcher.nodes(graph, new HashMap<String, Long>());
        // A compilation for another detail level that passed the check already knows which nodes the CPU keeps
        // (the same graph, other sizes): no scan of every tensor on the CPU, which at 280 tokens holds gigabytes
        int donor = start.isEmpty() ? checkedSibling(patches) : 0;
        if (donor > 0) {
            for (String l : readText(note(donor, "cpu")).split("\n")) if (!l.trim().isEmpty()) start.add(l.trim());
            String counts = opCounts(nodes, start);
            rep.append("на процессоре — как в проверенной сборке под ").append(donor).append(" фрагментов")
                    .append(counts.isEmpty() ? "" : ": " + counts).append(" (замер значений пропущен)\n");
        } else {
            // the patch positions' integer and boolean logic (padding, the attention mask, RoPE angles, position
            // embeddings) is computed on the CPU: the NPU got the mask wrong
            Set<String> positional = QnnBuild.positionOnlyNodes(nodes, types, OnnxPatcher.inputDims(graph).keySet());
            positional.removeAll(start);
            if (!positional.isEmpty()) {
                start.addAll(positional);
                rep.append("на процессоре счёт по позициям фрагментов (маска, RoPE, позиционные эмбеддинги): ")
                        .append(opCounts(nodes, positional)).append('\n');
            }
            stage("сборка под " + patches + " фрагментов: замер значений всех тензоров на процессоре (fp32)");
            Map<String, float[]> onCpu = ranges(graph, cpuOptions(patches), QnnBuild.watchList(nodes, types, Integer.MAX_VALUE),
                    patches, patchDim, pixels, positions);
            Set<String> big = QnnBuild.overflowNodes(nodes, onCpu, SAFE_FP16);
            big.removeAll(start);
            if (!big.isEmpty()) {
                start.addAll(big);
                rep.append("на процессоре из-за значений больше ").append((int) SAFE_FP16).append(" (в fp16 — до 65504): ")
                        .append(opCounts(nodes, big)).append('\n');
            }
        }
        stage("сборка под " + patches + " фрагментов: эталон на процессоре (fp32)");
        float[] want = encode(cpuSession(patches), pixels, positions, patches, patchDim);
        // QNN's compiler runs in this process: the reference session's memory goes back first
        OrtSession ref = cpuSessions.remove(patches);
        if (ref != null) ref.close();
        boolean big = patches > BIG_PATCHES;
        int way = big ? bigWay(patches, rep) : -1;
        while (true) {
            File source = graph;
            boolean deepFirst = true;
            Set<String> cpu = new LinkedHashSet<String>(start), borders = new LinkedHashSet<String>();
            if (big) {
                // a graph this large: attention in parts, maybe QNN's default optimisation and the graph in parts on
                // the NPU (a Split kept on the CPU between them); noted before, so that a crash moves on to the next way
                int[] w = BIG_WAYS[way];
                source = new File(graph.getParentFile(), graph.getName().replace(".onnx", "") + ".att" + w[0] + ".onnx");
                List<String> splits = OnnxPatcher.chunkAttention(graph, source, w[0]);
                for (int i = 1; i < w[2] && !splits.isEmpty(); i++) borders.add(splits.get(i * splits.size() / w[2]));
                cpu.addAll(borders);
                deepFirst = w[1] == 1;
                rep.append("способ сборки ").append(way + 1).append(" из ").append(BIG_WAYS.length).append(": ").append(wayText(w))
                        .append(splits.isEmpty() ? " (внимание в графе не найдено — целиком)" : "").append('\n');
                writeText(note(patches, "attempt"), String.valueOf(way));
            }
            try {
                OrtSession s = rounds(patches, source, cpu, borders, deepFirst, pixels, positions, patchDim, want, rep, way);
                if (s != null) return s;
                start = cpu;
                start.removeAll(borders);
                if (!big || way + 1 >= BIG_WAYS.length) break;
            } catch (Exception e) {
                // QNN's own failure is in the report already; anything else is added (what was done so far stays there)
                String m = e instanceof Reported ? "" : e.getMessage() != null ? e.getMessage() : e.toString();
                if (!big || way + 1 >= BIG_WAYS.length) {
                    String text = rep + m;
                    writeText(note(patches, "qnn"), text);
                    throw new Exception(text, e);
                }
                rep.append(m.isEmpty() ? "" : "  не собралось: " + (m.length() > 300 ? m.substring(0, 300) + "…" : m)).append('\n');
            } finally {
                if (big) {
                    note(patches, "attempt").delete(); // the process lived through this way
                    source.delete();
                }
            }
            way++;
            writeText(note(patches, "way"), String.valueOf(way));
        }
        bf16 = false;
        String where;
        if (patches > SCAN_MAX_PATCHES) {
            where = "при " + patches + " фрагментах не ищется (слишком долго) — подробный разбор даёт проверка на 70 токенах";
        } else {
            try {
                stage("сборка под " + patches + " фрагментов: поиск, где NPU портит результат");
                where = scanReport(patches, patchDim, pixels, positions, start, types);
            } catch (Exception e) {
                where = "не вышло: " + e.getMessage();
            }
        }
        String text = "NPU считает неверно — " + rep.toString().trim() + "\nГде NPU портит результат: " + where;
        writeText(note(patches, "qnn"), text);
        throw new Exception(text);
    }

    /**
     * Patch counts above this compile in a lighter way (BIG_WAYS): QNN's compiler crashed the NPU process on the
     * whole graph at 2520 patches (280 tokens), with attention of 12 × 2520² scores per layer.
     */
    static final int BIG_PATCHES = 1024;
    /**
     * Ways to compile a large graph, in order: {attention parts, QNN's longest optimisation (1) or its default (0),
     * parts of the graph on the NPU}. A way that killed the process is not tried again; one that failed otherwise
     * gives way to the next at once.
     */
    static final int[][] BIG_WAYS = {{4, 1, 1}, {4, 0, 1}, {4, 0, 4}, {8, 0, 8}};

    static String wayText(int[] w) {
        return "внимание по " + w[0] + " частям, " + (w[1] == 1 ? "оптимизация QNN 3" : "оптимизация QNN по умолчанию")
                + (w[2] > 1 ? ", граф на NPU в " + w[2] + " частях" : "");
    }

    /**
     * The way to compile this patch count now: a way still noted as started (attempt) killed the process — it goes
     * into the crash list and the next way follows; the list goes into the report.
     */
    private int bigWay(int patches, StringBuilder rep) throws Exception {
        int way = 0;
        try {
            way = Integer.parseInt(readText(note(patches, "way")).trim());
        } catch (NumberFormatException ignored) {
            // the first way
        }
        String started = readText(note(patches, "attempt")).trim();
        if (!started.isEmpty()) {
            int w = way;
            try {
                w = Integer.parseInt(started);
            } catch (NumberFormatException ignored) {
                // the one noted as current
            }
            if (w >= 0 && w < BIG_WAYS.length) {
                String crashes = readText(note(patches, "crashes"));
                writeText(note(patches, "crashes"), crashes + "способ " + (w + 1) + " (" + wayText(BIG_WAYS[w]) + ") уронил NPU-процесс\n");
            }
            way = Math.max(way, w + 1);
            writeText(note(patches, "way"), String.valueOf(way));
            note(patches, "attempt").delete();
        }
        String history = readText(note(patches, "crashes")).trim();
        if (!history.isEmpty()) rep.append(history).append('\n');
        if (way >= BIG_WAYS.length) {
            throw new Exception("NPU не собирает граф под " + patches + " фрагментов: все способы сборки роняли NPU-процесс —\n" + history);
        }
        return way;
    }

    /** QNN could not compile: why is in the report already. */
    private static final class Reported extends Exception {
        Reported() {
            super("QNN не собрал граф");
        }
    }

    /**
     * Compiles {@code source} (fp16, then BF16 when the NPU's features differ from the CPU's) and checks it: the
     * session when it matches, null when the NPU's numbers are wrong in both; throws when QNN cannot compile it.
     * {@code cpu} ends as the nodes the last compilation kept on the CPU.
     */
    private OrtSession rounds(int patches, File source, Set<String> cpu, Set<String> borders, boolean deepFirst, float[] pixels,
                              long[] positions, int patchDim, float[] want, StringBuilder rep, int way) throws Exception {
        File ctx = context(patches);
        for (int round = 0; round < 2; round++) {
            bf16 = round == 1;
            stage("сборка под " + patches + " фрагментов: QNN компилирует граф" + (bf16 ? " (BF16)" : "")
                    + (way >= 0 ? " — способ " + (way + 1) + ": " + wayText(BIG_WAYS[way]) : ""));
            QnnBuild.Outcome o = QnnBuild.run(source, cpu, npu(patches, ctx), way >= 0 ? COMPILE_BUDGET_MS / 2 : COMPILE_BUDGET_MS,
                    deepFirst);
            rep.append(bf16 ? "BF16: " : "").append(o.report);
            if (o.compiled != null && !o.compiled.equals(source)) o.compiled.delete();
            if (!o.ok) {
                if (round == 0) {
                    bf16 = false;
                    throw new Reported();
                }
                return null;
            }
            if (bf16 && o.log != null && (o.log.bf16Refused != null || o.log.supported == 0)) {
                rep.append("\n  BF16 недоступен: ").append(o.log.bf16Refused != null ? o.log.bf16Refused : "QNN не взял ни одного узла");
                ctx.delete();
                return null;
            }
            stage("сборка под " + patches + " фрагментов: проверка NPU против процессора");
            OrtSession quiet = env.createSession(ctx.getPath(), options(graph, patches, false, true));
            float c = cosine(encode(quiet, pixels, positions, patches, patchDim), want);
            rep.append(String.format(java.util.Locale.ROOT, "\n  проверка на этой картинке: совпадение с процессором %.4f", c));
            if (c >= MATCH) {
                writeText(note(patches, "qnn"), rep.toString());
                StringBuilder names = new StringBuilder();
                // the borders belong to the way (its graph), not to the model: a compilation of another size starts without them
                for (String n : o.cpu) if (!borders.contains(n)) names.append(n).append('\n');
                writeText(note(patches, "cpu"), names.toString());
                writeText(note(patches, "precision"), bf16 ? "bf16" : "fp16");
                return quiet;
            }
            quiet.close();
            ctx.delete();
            cpu.clear();
            cpu.addAll(o.cpu);
            rep.append('\n');
        }
        return null;
    }

    private QnnBuild.Npu npu(final int patches, final File ctx) {
        return new QnnBuild.Npu() {
            private QnnLog last;

            @Override
            public QnnBuild.Failure compile(File g, boolean save, boolean deep) throws Exception {
                OrtSession.SessionOptions so = options(g, patches, save, deep);
                if (save) {
                    so.addConfigEntry("ep.context_enable", "1");
                    so.addConfigEntry("ep.context_file_path", ctx.getPath());
                    so.addConfigEntry("ep.context_embed_mode", "1");
                } else {
                    so.addConfigEntry("session.disable_prepacking", "1");
                }
                LogTail tail = save ? LogTail.start() : null;
                try {
                    env.createSession(g.getPath(), so).close();
                    last = tail != null ? QnnLog.parse(tail.finish()) : null;
                    return null;
                } catch (Exception e) {
                    QnnLog log = QnnLog.parse(tail != null ? tail.finish() : Collections.<String>emptyList());
                    if (save) ctx.delete(); // written before the session failed
                    return new QnnBuild.Failure(e.getMessage() != null ? e.getMessage() : e.toString(), log);
                } finally {
                    so.close();
                }
            }

            @Override
            public QnnLog lastLog() {
                return last;
            }

            @Override
            public String canary() {
                return NpuService.this.canary();
            }

            @Override
            public Map<String, OnnxPatcher.TensorType> types(File g) throws Exception {
                return QnnBuild.tensorTypes(env, g, dims(g, patches));
            }

            @Override
            public long nowMs() {
                return android.os.SystemClock.elapsedRealtime();
            }
        };
    }

    private OrtSession.SessionOptions cpuOptions(int patches) throws Exception {
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
        o.setIntraOpNumThreads(2);
        for (Map.Entry<String, Long> d : dims(graph, patches).entrySet()) o.setSymbolicDimensionValue(d.getKey(), d.getValue());
        return o;
    }

    private static String opCounts(List<OnnxPatcher.Node> nodes, Set<String> names) {
        Map<String, Integer> m = new LinkedHashMap<String, Integer>();
        for (OnnxPatcher.Node n : nodes) if (names.contains(n.name)) m.put(n.opType, m.containsKey(n.opType) ? m.get(n.opType) + 1 : 1);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.entrySet()) sb.append(sb.length() > 0 ? ", " : "").append(e.getKey()).append(" ×").append(e.getValue());
        return sb.toString();
    }

    /** Cosine of two feature vectors; NaN when either holds a non-number or their lengths differ. */
    static float cosine(float[] a, float[] b) {
        if (a.length != b.length) return Float.NaN;
        double ab = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.length; i++) {
            ab += (double) a[i] * b[i];
            aa += (double) a[i] * a[i];
            bb += (double) b[i] * b[i];
        }
        double c = ab / Math.sqrt(aa * bb);
        return Double.isNaN(c) || Double.isInfinite(c) ? Float.NaN : (float) c;
    }

    private static boolean finite(float[] f) {
        for (float v : f) if (Float.isNaN(v) || Float.isInfinite(v)) return false;
        return true;
    }

    /** Whether QNN compiles a tiny float graph (MatMul → Add → Softmax): null if it does, else what it said. */
    private String canary() {
        File f = new File(getCacheDir(), "npu-canary.onnx");
        LogTail tail = null;
        try {
            OutputStream out = new FileOutputStream(f);
            try {
                out.write(OnnxPatcher.canaryModel());
            } finally {
                out.close();
            }
            OrtSession.SessionOptions o = options(f, 0, true, true);
            tail = LogTail.start();
            OrtSession s = env.createSession(f.getPath(), o);
            QnnLog log = QnnLog.parse(tail != null ? tail.finish() : Collections.<String>emptyList());
            tail = null;
            s.close();
            return log.supported == 0 ? "QNN не взял ни одного узла" : null;
        } catch (Exception e) {
            QnnLog log = QnnLog.parse(tail != null ? tail.finish() : Collections.<String>emptyList());
            String summary = log.summary(6);
            return e.getMessage() + (summary.isEmpty() ? "" : "\n  " + summary);
        } finally {
            f.delete();
        }
    }

    /**
     * Another patch count of this graph whose compilation passed the check against the CPU (its precision note
     * is there) and noted the nodes kept on the CPU: the largest one compiled whole, else the largest; or 0.
     */
    private int checkedSibling(int patches) {
        String base = graph.getName().replace(".onnx", "") + ".p";
        File[] files = graph.getParentFile().listFiles();
        int best = 0;
        if (files == null) return 0;
        for (File f : files) {
            String n = f.getName();
            if (!n.startsWith(base) || !n.endsWith("_precision.txt")) continue;
            try {
                int p = Integer.parseInt(n.substring(base.length(), n.length() - "_precision.txt".length()));
                if (p == patches || !note(p, "cpu").exists()) continue;
                // a count compiled whole (its nodes found by the scan) before a large one; among those the largest
                boolean small = p <= BIG_PATCHES, bestSmall = best > 0 && best <= BIG_PATCHES;
                if (best == 0 || (small && !bestSmall) || (small == bestSmall && p > best)) best = p;
            } catch (NumberFormatException ignored) {
                // another file
            }
        }
        return best;
    }

    /** Where the NPU process writes what it is doing: after a crash the app reads it (NpuVision). */
    static File stageFile(android.content.Context c) {
        return new File(c.getCacheDir(), "npu-stage.txt");
    }

    /** What the process is doing now, with its memory (resident, and the peak so far). */
    private void stage(String what) {
        long[] mem = memoryMb();
        writeText(stageFile(this), what + (mem[0] > 0 ? " · память процесса " + mem[0] + " МБ, пик " + mem[1] + " МБ" : ""));
    }

    /** {resident, peak resident} of this process in MB, from /proc/self/status (0 when unreadable). */
    static long[] memoryMb() {
        long[] out = new long[2];
        for (String l : readText(new File("/proc/self/status")).split("\n")) {
            int i = l.startsWith("VmRSS:") ? 0 : l.startsWith("VmHWM:") ? 1 : -1;
            if (i < 0) continue;
            try {
                out[i] = Long.parseLong(l.replaceAll("[^0-9]", "")) / 1024;
            } catch (NumberFormatException ignored) {
                // left at 0
            }
        }
        return out;
    }

    private File context(int patches) {
        return new File(graph.getParentFile(), graph.getName().replace(".onnx", "") + ".p" + patches + "_ctx.onnx");
    }

    /** A note kept next to the compiled context (what the compilation found). */
    private File note(int patches, String kind) {
        return new File(graph.getParentFile(), graph.getName().replace(".onnx", "") + ".p" + patches + "_" + kind + ".txt");
    }

    private static String readText(File f) {
        if (!f.exists()) return "";
        try {
            java.io.InputStream in = new java.io.FileInputStream(f);
            try {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                return new String(b.toByteArray(), "UTF-8");
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeText(File f, String text) {
        try {
            OutputStream out = new FileOutputStream(f);
            try {
                out.write(text.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (IOException ignored) {
            // only a note for the report
        }
    }

    /** {@code batch} images from the shared file, one NPU run each; the features go back into the same file. */
    private synchronized int run(String io, int batch, int patches, int patchDim) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(io, "rw");
        try {
            FileChannel ch = raf.getChannel();
            MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_WRITE, 0, raf.length());
            m.order(ByteOrder.nativeOrder());
            int pixelCount = batch * patches * patchDim;
            float[] pixels = new float[patches * patchDim];
            long[] positions = new long[patches * 2];
            // the first image: a first compilation checks the NPU on it
            m.position(0);
            m.slice().order(ByteOrder.nativeOrder()).asFloatBuffer().get(pixels);
            m.position(4 * pixelCount);
            m.slice().order(ByteOrder.nativeOrder()).asLongBuffer().get(positions);
            OrtSession s = session(patches, pixels, positions, patchDim);
            java.util.List<float[]> feats = new java.util.ArrayList<float[]>();
            int total = 0;
            for (int k = 0; k < batch; k++) {
                m.position(4 * k * patches * patchDim);
                m.slice().order(ByteOrder.nativeOrder()).asFloatBuffer().get(pixels);
                m.position(4 * pixelCount + 8 * k * patches * 2);
                m.slice().order(ByteOrder.nativeOrder()).asLongBuffer().get(positions);
                float[] f = encode(s, pixels, positions, patches, patchDim);
                if (!finite(f)) {
                    // the NPU went out of fp16's range on this image: the CPU computes it
                    f = encode(cpuSession(patches), pixels, positions, patches, patchDim);
                    fallbacks++;
                }
                if (k == 0) {
                    lastPixels = pixels.clone();
                    lastPositions = positions.clone();
                    lastPatches = patches;
                }
                feats.add(f);
                total += f.length;
            }
            if (4L * total > raf.length()) {
                raf.setLength(4L * total);
                m = ch.map(FileChannel.MapMode.READ_WRITE, 0, raf.length());
                m.order(ByteOrder.nativeOrder());
            }
            m.position(0);
            FloatBuffer out = m.slice().order(ByteOrder.nativeOrder()).asFloatBuffer();
            for (float[] f : feats) out.put(f);
            return total;
        } finally {
            raf.close();
        }
    }

    private static Map<String, OnnxTensor> inputs(OrtSession s, float[] pixels, long[] positions, int patches, int patchDim)
            throws Exception {
        Map<String, OnnxTensor> in = new HashMap<String, OnnxTensor>();
        for (String name : s.getInputNames()) {
            if ("pixel_values".equals(name)) {
                in.put(name, OnnxTensor.createTensor(env, FloatBuffer.wrap(pixels), new long[]{1, patches, patchDim}));
            } else if ("pixel_position_ids".equals(name) || "image_position_ids".equals(name)) {
                in.put(name, OnnxTensor.createTensor(env, LongBuffer.wrap(positions), new long[]{1, patches, 2}));
            } else {
                for (OnnxTensor t : in.values()) t.close();
                throw new IllegalStateException("unexpected vision encoder input: " + name);
            }
        }
        return in;
    }

    private float[] encode(OrtSession s, float[] pixels, long[] positions, int patches, int patchDim) throws Exception {
        Map<String, OnnxTensor> in = inputs(s, pixels, positions, patches, patchDim);
        try {
            OrtSession.Result r = s.run(in);
            try {
                OnnxTensor t = (OnnxTensor) (r.get("image_features").isPresent() ? r.get("image_features").get() : r.get(0));
                FloatBuffer b = t.getFloatBuffer();
                float[] a = new float[b.remaining()];
                b.get(a);
                return a;
            } finally {
                r.close();
            }
        } finally {
            for (OnnxTensor t : in.values()) t.close();
        }
    }

    /** Tensors watched by SCAN at most (each adds three small nodes to what the NPU compiles). */
    private static final int SCAN_TENSORS = 600;

    /** Where the NPU's result goes wrong for the last run's image (scanReport). */
    private synchronized String scan(int patches, int patchDim) throws Exception {
        if (lastPixels == null || lastPatches != patches) throw new IllegalStateException("нет прогона на NPU, не с чем сравнивать");
        Set<String> cpu = new LinkedHashSet<String>();
        for (String l : readText(note(patches, "cpu")).split("\n")) if (!l.trim().isEmpty()) cpu.add(l.trim());
        return scanReport(patches, patchDim, lastPixels, lastPositions, cpu,
                QnnBuild.tensorTypes(env, graph, dims(graph, patches)));
    }

    /**
     * Where the NPU's result goes wrong: the image through copies of the graph that also return the largest and
     * mean magnitude of float tensors — all of them on the CPU (fp32), the attention cores and a sample of the
     * rest on the NPU (fp16) — compared in graph order (QnnBuild.compareRanges), with the way up the graph from
     * the first tensor that breaks. Nodes kept on the CPU for the NPU stay there here too.
     */
    private String scanReport(int patches, int patchDim, float[] pixels, long[] positions, Set<String> cpu,
                              Map<String, OnnxPatcher.TensorType> types) throws Exception {
        String base = graph.getName().replace(".onnx", "");
        File variant = new File(graph.getParentFile(), base + ".scanbase.onnx");
        File scanNpu = new File(graph.getParentFile(), base + ".scan.onnx");
        try {
            File g = graph;
            if (!cpu.isEmpty()) {
                OnnxPatcher.keepOnCpu(graph, variant, cpu, types);
                g = variant;
            }
            List<OnnxPatcher.Node> nodes = OnnxPatcher.nodes(g, new HashMap<String, Long>());
            List<String> npuWatch = QnnBuild.npuWatchList(nodes, types, SCAN_TENSORS);
            Map<String, float[]> c = ranges(g, cpuOptions(patches), QnnBuild.watchList(nodes, types, Integer.MAX_VALUE),
                    patches, patchDim, pixels, positions);
            Map<String, float[]> n;
            try {
                OnnxPatcher.withRanges(g, scanNpu, npuWatch);
                n = ranges(scanNpu, null, npuWatch, patches, patchDim, pixels, positions);
            } catch (Exception e) {
                return "сравнить не вышло — NPU не собрал граф с проверками: " + e.getMessage();
            }
            return QnnBuild.compareRanges(npuWatch, c, n, nodes, types, OnnxPatcher.smallConstants(g));
        } finally {
            variant.delete();
            scanNpu.delete();
        }
    }

    /**
     * {max, mean} magnitude of each watched tensor for this image: {@code model} with ranges added (when
     * {@code cpu} options are given, a copy with the ranges is made here; else {@code model} has them and runs
     * on the NPU).
     */
    private Map<String, float[]> ranges(File model, OrtSession.SessionOptions cpu, List<String> watch, int patches,
                                        int patchDim, float[] pixels, long[] positions) throws Exception {
        File withRanges = model;
        if (cpu != null) {
            withRanges = new File(graph.getParentFile(), graph.getName().replace(".onnx", "") + ".ranges.onnx");
            OnnxPatcher.withRanges(model, withRanges, watch);
        }
        OrtSession.SessionOptions o = cpu != null ? cpu : options(withRanges, patches, false, false);
        try {
            OrtSession s = env.createSession(withRanges.getPath(), o);
            try {
                Map<String, OnnxTensor> in = inputs(s, pixels, positions, patches, patchDim);
                try {
                    OrtSession.Result r = s.run(in);
                    try {
                        return QnnBuild.readRanges(r, watch);
                    } finally {
                        r.close();
                    }
                } finally {
                    for (OnnxTensor t : in.values()) t.close();
                }
            } finally {
                s.close();
            }
        } finally {
            o.close();
            if (withRanges != model) withRanges.delete();
        }
    }

    /** One profiled run (from the saved context, so no second compilation): which nodes the NPU took. */
    private synchronized String profile(int patches, int patchDim) throws Exception {
        if (!sessions.containsKey(patches) && !context(patches).exists()) throw new IllegalStateException("NPU ещё не собран под эту детализацию");
        bf16 = "bf16".equals(readText(note(patches, "precision")).trim());
        File ctx = context(patches);
        OrtSession.SessionOptions o = options(graph, patches, false, true);
        File prefix = new File(getCacheDir(), "npu-profile");
        o.enableProfiling(prefix.getPath());
        OrtSession s = env.createSession(ctx.exists() ? ctx.getPath() : graph.getPath(), o);
        File json = null;
        try {
            int side = (int) Math.ceil(Math.sqrt(patches));
            long[] positions = new long[patches * 2];
            for (int i = 0; i < patches; i++) {
                positions[2 * i] = i % side;
                positions[2 * i + 1] = i / side;
            }
            encode(s, new float[patches * patchDim], positions, patches, patchDim);
            json = new File(s.endProfiling());
            String built = readText(note(patches, "qnn")).trim() + (fallbacks > 0 ? "\nснимков, пересчитанных на процессоре (NPU дал не числа): " + fallbacks : "");
            return (built.isEmpty() ? "" : built + "\n") + OrtProfile.parse(json).summary(6) + "\nNPU изнутри (профиль QNN): "
                    + qnnProfile(ctx.exists() ? ctx : graph, patches, patchDim, positions);
        } finally {
            s.close();
            if (json != null) json.delete();
            File[] left = getCacheDir().listFiles();
            if (left != null) for (File f : left) if (f.getName().startsWith("npu-profile")) f.delete();
        }
    }

    /**
     * QNN's own profile of two runs (a separate session: its detailed profiling slows the NPU down): where the
     * time on the NPU goes, by op (QnnLog.profile).
     */
    private String qnnProfile(File model, int patches, int patchDim, long[] positions) {
        File csv = new File(getCacheDir(), "npu-qnn-profile.csv");
        csv.delete();
        Map<String, String> extra = new HashMap<String, String>();
        extra.put("profiling_level", "detailed");
        extra.put("profiling_file_path", csv.getPath());
        try {
            OrtSession.SessionOptions o = options(graph, patches, false, true, extra);
            try {
                OrtSession s = env.createSession(model.getPath(), o);
                try {
                    for (int run = 0; run < 2; run++) encode(s, new float[patches * patchDim], positions, patches, patchDim);
                } finally {
                    s.close();
                }
            } finally {
                o.close();
            }
            List<String> lines = new ArrayList<String>(java.util.Arrays.asList(readText(csv).split("\n")));
            final Map<String, OnnxPatcher.TensorType> types = QnnBuild.tensorTypes(env, graph, dims(graph, patches));
            final Map<String, double[]> consts = OnnxPatcher.smallConstants(graph);
            return QnnLog.profile(lines, OnnxPatcher.nodes(graph, new HashMap<String, Long>()), 2, new QnnLog.Describer() {
                @Override
                public String describe(OnnxPatcher.Node n) {
                    return QnnBuild.describe(n, types, consts);
                }
            });
        } catch (Exception e) {
            return "не снят: " + e.getMessage();
        } finally {
            csv.delete();
        }
    }

    /**
     * This process's log from now on, read by {@code logcat} (an app may read its own lines): ONNX Runtime's
     * lines except its own verbose ones, QNN's messages, and warnings and errors of everything else.
     */
    private static final class LogTail {
        private final java.lang.Process proc;
        private final List<String> lines = new ArrayList<String>();
        private final String mark = "npu-log-" + System.nanoTime();
        private volatile boolean started, ended;

        static LogTail start() {
            try {
                return new LogTail();
            } catch (Exception e) {
                return null; // no logcat: the error is reported without QNN's reasons
            }
        }

        private LogTail() throws IOException, InterruptedException {
            proc = new ProcessBuilder("logcat", "-v", "tag", "--pid=" + android.os.Process.myPid(), "-T", "1",
                    "onnxruntime:V", "NpuService:V", "*:W").redirectErrorStream(true).start();
            final BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"));
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String l;
                        while ((l = r.readLine()) != null) {
                            if (l.contains(mark)) {
                                if (l.contains(mark + "-end")) ended = true;
                                else started = true;
                                continue;
                            }
                            if (!started || (l.startsWith("V/onnxruntime") && !l.contains("QnnLogging"))) continue;
                            synchronized (lines) {
                                if (lines.size() < 100000) lines.add(l);
                            }
                        }
                    } catch (IOException ignored) {
                        // logcat stopped
                    }
                }
            }, "npu-log");
            t.setDaemon(true);
            t.start();
            // logcat is reading once its own mark comes through
            for (int i = 0; i < 60 && !started; i++) {
                android.util.Log.i("NpuService", mark);
                Thread.sleep(50);
            }
            if (!started) {
                proc.destroy();
                throw new IOException("logcat не читает журнал");
            }
        }

        List<String> finish() {
            android.util.Log.i("NpuService", mark + "-end");
            long until = android.os.SystemClock.elapsedRealtime() + 3000;
            while (!ended && android.os.SystemClock.elapsedRealtime() < until) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    break;
                }
            }
            proc.destroy();
            synchronized (lines) {
                return new ArrayList<String>(lines);
            }
        }
    }
}
