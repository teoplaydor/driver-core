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
 * compile it, the reasons come from the log (see QnnLog). Nodes QNN names in its errors are kept on the CPU (the
 * same node in every layer) and the compilation is tried again; when it names none, a tiny graph tells whether
 * the NPU compiles anything at all, and a bisection over the graph finds the node that breaks it.
 */
public final class NpuService extends Service {
    static final String DESCRIPTOR = "io.github.teoplaydor.semsearch.NpuService";
    static final int INIT = 1, RUN = 2, PROFILE = 3;
    static final int OK = 0, FAILED = 1;

    // per process: Android makes a new service object for every bind after the last unbind, while the process
    // (with the libraries loaded and ONNX Runtime's environment) stays
    private static boolean loaded;
    private static OrtEnvironment env;
    private String libDir;
    private File graph;
    private final Map<Integer, OrtSession> sessions = new HashMap<Integer, OrtSession>();

    private final Binder binder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code < INIT || code > PROFILE) return super.onTransact(code, data, reply, flags);
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
                        String s = profile(patches, patchDim);
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
            for (OrtSession s : sessions.values()) {
                try {
                    s.close();
                } catch (Exception ignored) {
                    // going away
                }
            }
            sessions.clear();
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
     * @param cpu   ONNX Runtime's name-based node assignment keeping nodes on the CPU ("" for none)
     * @param log   compilation: the session logs what QNN took and refused, and QNN its warnings and errors
     * @param deep  QNN's longest graph optimisation (faster runs), else its default
     */
    private OrtSession.SessionOptions options(int patches, String cpu, boolean log, boolean deep) throws Exception {
        return options(graph, patches, cpu, log, deep);
    }

    private OrtSession.SessionOptions options(File model, int patches, String cpu, boolean log, boolean deep) throws Exception {
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        // basic optimisations only: later fusions would put back ops the NPU cannot run
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
        o.setIntraOpNumThreads(2);
        o.setSessionLogLevel(log ? OrtLoggingLevel.ORT_LOGGING_LEVEL_INFO : OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING);
        for (List<String> dims : OnnxPatcher.inputDims(model).values()) {
            for (int i = 0; i < dims.size() && i < 2; i++) {
                String d = dims.get(i);
                if (d.isEmpty() || Character.isDigit(d.charAt(0)) || "?".equals(d)) continue;
                o.setSymbolicDimensionValue(d, i == 0 ? 1 : patches);
            }
        }
        if (!cpu.isEmpty()) o.addConfigEntry("session.name_based_layer_assignment", cpu);
        Map<String, String> qnn = new HashMap<String, String>();
        qnn.put("backend_path", new File(libDir, "libQnnHtp.so").getAbsolutePath());
        qnn.put("htp_performance_mode", "burst");
        qnn.put("enable_htp_fp16_precision", "1");
        if (deep) qnn.put("htp_graph_finalization_optimization_mode", "3");
        o.addQnn(qnn);
        return o;
    }

    /** The compiled NPU graph for this many patches: from the saved QNN context, or compiled now and saved. */
    private synchronized OrtSession session(int patches) throws Exception {
        OrtSession s = sessions.get(patches);
        if (s != null) return s;
        // next to the graph, so whatever stays on the CPU still finds its weights in the external data file
        File ctx = context(patches);
        if (ctx.exists() && ctx.lastModified() < graph.lastModified()) ctx.delete(); // made from an older graph
        if (ctx.exists()) {
            try {
                s = env.createSession(ctx.getPath(), options(patches, "", false, true));
            } catch (Exception stale) {
                ctx.delete();
            }
        }
        if (s == null) s = compile(patches);
        sessions.put(patches, s);
        return s;
    }

    /** Time for all compilations of one graph, the bisection included. */
    private static final long COMPILE_BUDGET_MS = 15 * 60 * 1000;

    /**
     * Compiles the graph for the NPU and saves the QNN context. When QNN cannot build the graph, the nodes its
     * errors name are kept on the CPU (the same node in every layer) and it is tried again; when it names none,
     * a tiny graph shows whether the NPU compiles anything at all, and a bisection finds the node that breaks
     * the graph. The error says what QNN said and what was tried.
     */
    private OrtSession compile(int patches) throws Exception {
        File ctx = context(patches);
        long deadline = android.os.SystemClock.elapsedRealtime() + COMPILE_BUDGET_MS;
        List<OnnxPatcher.Node> nodes = null;
        LinkedHashSet<String> cpu = new LinkedHashSet<String>();
        Map<String, Integer> cpuOps = new LinkedHashMap<String, Integer>();
        boolean deep = true, canaryChecked = false;
        StringBuilder tries = new StringBuilder();
        for (int attempt = 1; ; attempt++) {
            String what = (deep ? "оптимизация QNN 3" : "оптимизация QNN по умолчанию")
                    + (cpuOps.isEmpty() ? "" : ", на процессоре " + ops(cpuOps));
            OrtSession.SessionOptions o = options(patches, QnnLog.cpuAssignment(cpu), true, deep);
            o.addConfigEntry("ep.context_enable", "1");
            o.addConfigEntry("ep.context_file_path", ctx.getPath());
            o.addConfigEntry("ep.context_embed_mode", "1");
            LogTail tail = LogTail.start();
            long t0 = android.os.SystemClock.elapsedRealtime();
            OrtSession s = null;
            Exception failure = null;
            try {
                s = env.createSession(graph.getPath(), o);
            } catch (Exception e) {
                failure = e;
            }
            QnnLog log = QnnLog.parse(tail != null ? tail.finish() : Collections.<String>emptyList());
            long sec = (android.os.SystemClock.elapsedRealtime() - t0 + 500) / 1000;
            tries.append(tries.length() > 0 ? "; " : "").append(attempt).append(") ").append(what).append(", ").append(sec).append(" с");
            if (failure == null) {
                writeText(note(patches, "qnn"), "сборка: " + tries + "\n" + log.summary(4));
                try {
                    // runs log quietly: the same graph again, from the context just saved
                    OrtSession quiet = env.createSession(ctx.getPath(), options(patches, "", false, deep));
                    s.close();
                    return quiet;
                } catch (Exception e) {
                    return s;
                }
            }
            String msg = failure.getMessage() != null ? failure.getMessage() : failure.toString();
            tries.append(" — ").append(msg.length() > 120 ? msg.substring(0, 120) + "…" : msg.trim());
            boolean graphFailed = msg.contains("finalize QNN graph") || msg.contains("compose Qnn graph");
            boolean timeLeft = android.os.SystemClock.elapsedRealtime() < deadline;
            if (graphFailed && timeLeft && attempt < 8) {
                if (nodes == null) nodes = OnnxPatcher.nodes(graph, new HashMap<String, Long>());
                int before = cpu.size();
                for (OnnxPatcher.Node n : log.failingNodes(nodes)) keepOnCpu(nodes, n, cpu, cpuOps);
                if (cpu.size() > before) continue;
                if (!canaryChecked) {
                    canaryChecked = true;
                    String canary = canary();
                    tries.append("; крошечный граф (MatMul, Add, Softmax): ").append(canary == null ? "собирается" : "не собирается — " + canary);
                    if (canary != null) {
                        throw new Exception(msg.trim() + "\n  NPU не собирает даже крошечный граф, дело не в модели: " + canary
                                + "\n  " + log.summary(8) + "\n  попытки: " + tries);
                    }
                }
                int culprit = bisect(nodes, cpu, patches, deadline, tries);
                if (culprit >= 0) {
                    OnnxPatcher.Node c = nodes.get(culprit);
                    tries.append(" → ломает узел №").append(culprit + 1).append(" из ").append(nodes.size()).append(": ")
                            .append(c.opType).append(' ').append(c.name);
                    keepOnCpu(nodes, c, cpu, cpuOps);
                    if (cpu.size() > before) continue;
                }
                if (deep) {
                    deep = false;
                    continue;
                }
            }
            String summary = log.summary(10);
            throw new Exception(msg.trim() + (summary.isEmpty() ? "" : "\n  " + summary)
                    + (attempt == 1 && tries.indexOf(";") < 0 ? "\n  сборка шла " + sec + " с" : "\n  попытки: " + tries));
        }
    }

    /** The node, in every layer, goes to the CPU. */
    private static void keepOnCpu(List<OnnxPatcher.Node> nodes, OnnxPatcher.Node n, Set<String> cpu, Map<String, Integer> cpuOps) {
        for (String name : QnnLog.inEveryLayer(nodes, n)) {
            if (cpu.add(name)) cpuOps.put(n.opType, cpuOps.containsKey(n.opType) ? cpuOps.get(n.opType) + 1 : 1);
        }
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
            OrtSession.SessionOptions o = options(f, 0, "", true, true);
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
     * Bisection over the graph's nodes in order: QNN may take only the first k (the others, and those already
     * kept off the NPU, stay on the CPU). Returns the index of the node whose addition breaks the compilation,
     * or -1 when the time ran out.
     */
    private int bisect(final List<OnnxPatcher.Node> nodes, final Set<String> cpu, final int patches, final long deadline,
                       final StringBuilder tries) {
        final int[] steps = {0};
        final long t0 = android.os.SystemClock.elapsedRealtime();
        try {
            int c = QnnLog.firstBreaking(nodes.size(), new QnnLog.Probe() {
                @Override
                public boolean compiles(int k) throws Exception {
                    if (android.os.SystemClock.elapsedRealtime() > deadline) throw new java.util.concurrent.TimeoutException();
                    steps[0]++;
                    List<String> off = new ArrayList<String>(cpu);
                    for (int i = k; i < nodes.size(); i++) off.add(nodes.get(i).name);
                    OrtSession.SessionOptions o = options(patches, QnnLog.cpuAssignment(off), false, false);
                    o.addConfigEntry("session.disable_prepacking", "1");
                    try {
                        env.createSession(graph.getPath(), o).close();
                        return true;
                    } catch (Exception e) {
                        return false;
                    }
                }
            });
            tries.append("; поиск: ").append(steps[0]).append(" сборок, ")
                    .append((android.os.SystemClock.elapsedRealtime() - t0 + 500) / 1000).append(" с");
            return c;
        } catch (Exception e) {
            tries.append("; поиск прерван после ").append(steps[0]).append(" сборок — кончилось время");
            return -1;
        }
    }

    private static String ops(Map<String, Integer> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            sb.append(sb.length() > 0 ? ", " : "").append(e.getKey()).append(" ×").append(e.getValue());
        }
        return sb.toString();
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
        OrtSession s = session(patches);
        RandomAccessFile raf = new RandomAccessFile(io, "rw");
        try {
            FileChannel ch = raf.getChannel();
            MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_WRITE, 0, raf.length());
            m.order(ByteOrder.nativeOrder());
            int pixelCount = batch * patches * patchDim;
            float[] pixels = new float[patches * patchDim];
            long[] positions = new long[patches * 2];
            java.util.List<float[]> feats = new java.util.ArrayList<float[]>();
            int total = 0;
            for (int k = 0; k < batch; k++) {
                m.position(4 * k * patches * patchDim);
                m.slice().order(ByteOrder.nativeOrder()).asFloatBuffer().get(pixels);
                m.position(4 * pixelCount + 8 * k * patches * 2);
                m.slice().order(ByteOrder.nativeOrder()).asLongBuffer().get(positions);
                float[] f = encode(s, pixels, positions, patches, patchDim);
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

    private float[] encode(OrtSession s, float[] pixels, long[] positions, int patches, int patchDim) throws Exception {
        Map<String, OnnxTensor> in = new HashMap<String, OnnxTensor>();
        try {
            for (String name : s.getInputNames()) {
                if ("pixel_values".equals(name)) {
                    in.put(name, OnnxTensor.createTensor(env, FloatBuffer.wrap(pixels), new long[]{1, patches, patchDim}));
                } else if ("pixel_position_ids".equals(name) || "image_position_ids".equals(name)) {
                    in.put(name, OnnxTensor.createTensor(env, LongBuffer.wrap(positions), new long[]{1, patches, 2}));
                } else {
                    throw new IllegalStateException("unexpected vision encoder input: " + name);
                }
            }
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

    /** One profiled run (from the saved context, so no second compilation): which nodes the NPU took. */
    private synchronized String profile(int patches, int patchDim) throws Exception {
        session(patches); // makes sure the context exists
        File ctx = context(patches);
        OrtSession.SessionOptions o = options(patches, "", false, true);
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
            String built = readText(note(patches, "qnn")).trim();
            return (built.isEmpty() ? "" : built + "\n") + OrtProfile.parse(json).summary(6);
        } finally {
            s.close();
            if (json != null) json.delete();
            File[] left = getCacheDir().listFiles();
            if (left != null) for (File f : left) if (f.getName().startsWith("npu-profile")) f.delete();
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
