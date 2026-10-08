package io.github.teoplaydor.semsearch.app;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import io.github.teoplaydor.semsearch.core.OnnxPatcher;
import io.github.teoplaydor.semsearch.core.OrtProfile;
import io.github.teoplaydor.semsearch.core.QnnRuntime;

/**
 * EmbeddingGemma's vision encoder on the Snapdragon NPU (Hexagon HTP), in its own process ({@code :npu}): the
 * ONNX Runtime build with Qualcomm's QNN provider (downloaded, see QnnRuntime) cannot share a process with the
 * app's ONNX Runtime, and a driver crash here takes down only this process. Requests come through a plain
 * Binder; pixels and features travel through a memory-mapped file in the app's cache (same app, same pages).
 *
 * <p>The NPU compiles a graph for fixed shapes: one session per patch count (token budget), batch 1, compiled
 * once and kept as a QNN context next to the graph, so later starts skip the compilation.
 */
public final class NpuService extends Service {
    static final String DESCRIPTOR = "io.github.teoplaydor.semsearch.NpuService";
    static final int INIT = 1, RUN = 2, PROFILE = 3;
    static final int OK = 0, FAILED = 1;

    private static boolean loaded;
    private OrtEnvironment env;
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

    /**
     * Loads QNN's host libraries (its HTP backend opens them by name, which finds already-loaded ones), points
     * the DSP loader at this chip's skel, and then ONNX Runtime from the same directory.
     */
    private synchronized void init(String dir, File visionGraph) throws Exception {
        if (!loaded) {
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
            env = OrtEnvironment.getEnvironment();
            loaded = true;
        }
        if (graph != null && !graph.equals(visionGraph)) {
            for (OrtSession s : sessions.values()) s.close();
            sessions.clear();
        }
        libDir = dir;
        graph = visionGraph;
    }

    private OrtSession.SessionOptions options(int patches) throws Exception {
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        // basic optimisations only: later fusions would put back ops the NPU cannot run
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
        o.setIntraOpNumThreads(2);
        for (List<String> dims : OnnxPatcher.inputDims(graph).values()) {
            for (int i = 0; i < dims.size() && i < 2; i++) {
                String d = dims.get(i);
                if (d.isEmpty() || Character.isDigit(d.charAt(0)) || "?".equals(d)) continue;
                o.setSymbolicDimensionValue(d, i == 0 ? 1 : patches);
            }
        }
        Map<String, String> qnn = new HashMap<String, String>();
        qnn.put("backend_path", new File(libDir, "libQnnHtp.so").getAbsolutePath());
        qnn.put("htp_performance_mode", "burst");
        qnn.put("enable_htp_fp16_precision", "1");
        qnn.put("htp_graph_finalization_optimization_mode", "3");
        o.addQnn(qnn);
        return o;
    }

    /** The compiled NPU graph for this many patches: from the saved QNN context, or compiled now and saved. */
    private synchronized OrtSession session(int patches) throws Exception {
        OrtSession s = sessions.get(patches);
        if (s != null) return s;
        // next to the graph, so whatever stays on the CPU still finds its weights in the external data file
        File ctx = context(patches);
        if (ctx.exists()) {
            try {
                s = env.createSession(ctx.getPath(), options(patches));
            } catch (Exception stale) {
                ctx.delete();
            }
        }
        if (s == null) {
            OrtSession.SessionOptions o = options(patches);
            o.addConfigEntry("ep.context_enable", "1");
            o.addConfigEntry("ep.context_file_path", ctx.getPath());
            o.addConfigEntry("ep.context_embed_mode", "1");
            s = env.createSession(graph.getPath(), o);
        }
        sessions.put(patches, s);
        return s;
    }

    private File context(int patches) {
        return new File(graph.getParentFile(), graph.getName().replace(".onnx", "") + ".p" + patches + "_ctx.onnx");
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
        OrtSession.SessionOptions o = options(patches);
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
            return OrtProfile.parse(json).summary(6);
        } finally {
            s.close();
            if (json != null) json.delete();
            File[] left = getCacheDir().listFiles();
            if (left != null) for (File f : left) if (f.getName().startsWith("npu-profile")) f.delete();
        }
    }
}
