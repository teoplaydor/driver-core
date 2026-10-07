package io.github.teoplaydor.semsearch.core;

import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import ai.onnxruntime.providers.NNAPIFlags;

/**
 * SigLIP 2 (FixRes, e.g. google/siglip2-base-patch16-224) as a fast photo/video embedder: a small
 * ViT for pictures and a text tower for queries, both landing in one space. Mirrors transformers.js
 * {@code SiglipVisionModel} / {@code SiglipTextModel} on the onnx-community exports: pictures are
 * resized to the processor's square size and normalised with its mean/std; text is lowercased (the
 * model was trained on lowercased text) and padded to the full 64 tokens, as in training.
 */
public final class SigLip implements Embedder {
    /** Where the vision tower runs. The text tower (one short query at a time) always stays on the CPU. */
    public enum Accel {
        CPU("Процессор"),
        CPU_FP32("Процессор, fp32"),
        XNNPACK("Процессор, XNNPACK"),
        NPU("NPU (NNAPI)"),
        NPU_FP32("NPU (NNAPI, fp32)"),
        GPU("Видеокарта (WebGPU)");

        public final String label;

        Accel(String label) {
            this.label = label;
        }

        /** Accelerators work on the full-precision graph; the int8 one is for the CPU. */
        public boolean needsFp32() {
            return this != CPU;
        }
    }

    private final OrtEnvironment env;
    private final OrtSession textSession, visionSession;
    private final HfTokenizer tokenizer;
    private final int size, maxLength, padId;
    private final float[] mean, std;
    private final float rescale;
    private final String pixelInput, visionOutput, idsInput, maskInput, textOutput;
    /** Images per vision run: fixed when an NPU needs static shapes, otherwise any. */
    private final int fixedBatch;
    private int dim = -1;
    private volatile long lastVisionMs, lastTextMs;

    /**
     * @param dir         repo snapshot (config.json, preprocessor_config.json, tokenizer*.json)
     * @param textModel   text tower, or null for a pictures-only instance (speed checks)
     * @param fixedBatch  batch size baked into the vision graph for NNAPI (it needs static shapes)
     */
    public SigLip(File dir, File textModel, File visionModel, Accel accel, int threads, int fixedBatch)
            throws IOException, OrtException {
        Map<String, Object> pre = MiniJson.obj(ModelConfig.readJson(new File(dir, "preprocessor_config.json")));
        Map<String, Object> tokCfg = jsonOrEmpty(new File(dir, "tokenizer_config.json"));
        Map<String, Object> cfg = jsonOrEmpty(new File(dir, "config.json"));
        size = squareSize(pre, cfg);
        mean = triple(pre, "image_mean", 0.5f);
        std = triple(pre, "image_std", 0.5f);
        rescale = MiniJson.bool(pre, "do_rescale", true) ? (float) MiniJson.dbl(pre, "rescale_factor", 1 / 255.0) : 1f;
        if (!MiniJson.bool(pre, "do_normalize", true)) {
            for (int i = 0; i < 3; i++) {
                mean[i] = 0f;
                std[i] = 1f;
            }
        }
        maxLength = maxLength(tokCfg, cfg);
        tokenizer = HfTokenizer.load(new File(dir, "tokenizer.json"), new File(dir, "tokenizer.bin"));
        Integer pad = tokenizer.tokenId(MiniJson.str(tokCfg, "pad_token", "<pad>"));
        padId = pad != null ? pad : 0;
        this.fixedBatch = accel == Accel.NPU || accel == Accel.NPU_FP32 ? Math.max(1, fixedBatch) : 0;

        env = OrtEnvironment.getEnvironment();
        try {
            textSession = textModel == null ? null : env.createSession(textModel.getPath(), cpuOptions(threads));
        } catch (OrtException e) {
            throw new IOException("текстовая часть " + textModel.getName() + ": " + e.getMessage(), e);
        }
        try {
            visionSession = env.createSession(visionModel.getPath(), visionOptions(accel, threads, this.fixedBatch));
        } catch (OrtException e) {
            if (textSession != null) textSession.close();
            throw new IOException("картинки " + visionModel.getName() + " (" + accel.label + "): " + e.getMessage(), e);
        }
        pixelInput = pick(visionSession.getInputNames(), "pixel_values");
        visionOutput = pickOutput(visionSession, "image_embeds", "pooler_output");
        idsInput = textSession == null ? null : pick(textSession.getInputNames(), "input_ids");
        maskInput = textSession != null && textSession.getInputNames().contains("attention_mask") ? "attention_mask" : null;
        textOutput = textSession == null ? null : pickOutput(textSession, "text_embeds", "pooler_output");
    }

    private static Map<String, Object> jsonOrEmpty(File f) throws IOException {
        if (!f.exists()) return new HashMap<String, Object>();
        Map<String, Object> m = MiniJson.obj(ModelConfig.readJson(f));
        return m != null ? m : new HashMap<String, Object>();
    }

    private static int squareSize(Map<String, Object> pre, Map<String, Object> cfg) {
        Map<String, Object> s = MiniJson.obj(pre.get("size"));
        if (s != null) {
            long h = MiniJson.num(s, "height", -1), w = MiniJson.num(s, "width", -1);
            if (h > 0 && w > 0) return (int) Math.max(h, w);
            long e = MiniJson.num(s, "shortest_edge", -1);
            if (e > 0) return (int) e;
        }
        Map<String, Object> v = MiniJson.obj(cfg.get("vision_config"));
        return v != null ? (int) MiniJson.num(v, "image_size", 224) : 224;
    }

    private static float[] triple(Map<String, Object> m, String key, float dflt) {
        float[] out = {dflt, dflt, dflt};
        List<Object> a = MiniJson.arr(m.get(key));
        if (a != null && a.size() == 3) {
            for (int i = 0; i < 3; i++) out[i] = ((Number) a.get(i)).floatValue();
        }
        return out;
    }

    private static int maxLength(Map<String, Object> tokCfg, Map<String, Object> cfg) {
        long m = MiniJson.num(tokCfg, "model_max_length", -1);
        if (m > 0 && m <= 4096) return (int) m;
        Map<String, Object> t = MiniJson.obj(cfg.get("text_config"));
        return t != null ? (int) MiniJson.num(t, "max_position_embeddings", 64) : 64;
    }

    private static OrtSession.SessionOptions cpuOptions(int threads) throws OrtException {
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        if (threads > 0) o.setIntraOpNumThreads(threads);
        return o;
    }

    static OrtSession.SessionOptions visionOptions(Accel accel, int threads, int batch) throws OrtException {
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        switch (accel) {
            case XNNPACK: {
                // XNNPACK runs its own thread pool; ORT's would only compete with it.
                o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                o.setIntraOpNumThreads(1);
                o.addConfigEntry("session.intra_op.allow_spinning", "0");
                Map<String, String> x = new HashMap<String, String>();
                x.put("intra_op_num_threads", String.valueOf(Math.max(1, threads)));
                o.addXnnpack(x);
                break;
            }
            case NPU:
            case NPU_FP32:
                // Basic optimisations only: ORT's fused LayerNorm/GELU/attention kernels are not NNAPI ops and
                // would split the graph; the plain ops all are. NNAPI also needs every shape fixed.
                o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
                if (threads > 0) o.setIntraOpNumThreads(threads);
                for (String d : new String[]{"batch_size", "batch", "N"}) o.setSymbolicDimensionValue(d, batch);
                o.addNnapi(accel == Accel.NPU ? EnumSet.of(NNAPIFlags.USE_FP16, NNAPIFlags.CPU_DISABLED)
                        : EnumSet.of(NNAPIFlags.CPU_DISABLED));
                break;
            case GPU:
                o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                if (threads > 0) o.setIntraOpNumThreads(threads);
                o.addWebGPU(new HashMap<String, String>());
                break;
            default:
                o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                if (threads > 0) o.setIntraOpNumThreads(threads);
        }
        return o;
    }

    private static String pick(java.util.Set<String> names, String preferred) {
        if (names.contains(preferred)) return preferred;
        return names.iterator().next();
    }

    /** The pooled embedding output: the preferred names first, else the first 2-D output. */
    private static String pickOutput(OrtSession s, String... preferred) throws OrtException {
        Map<String, NodeInfo> outs = s.getOutputInfo();
        for (String p : preferred) if (outs.containsKey(p)) return p;
        for (Map.Entry<String, NodeInfo> e : outs.entrySet()) {
            if (e.getValue().getInfo() instanceof TensorInfo && ((TensorInfo) e.getValue().getInfo()).getShape().length == 2) {
                return e.getKey();
            }
        }
        throw new OrtException("нет выхода с вектором: " + outs.keySet());
    }

    public int imageSize() { return size; }

    public int maxLength() { return maxLength; }

    // ------------------------------------------------------------------ text

    /** Token ids exactly as the text tower sees them: lowercased, special tokens, padded to the full length. */
    public long[] tokenIds(String text) {
        int[] ids = tokenizer.encode(text.toLowerCase(Locale.ROOT), true, maxLength);
        long[] out = new long[maxLength];
        for (int i = 0; i < maxLength; i++) out[i] = i < ids.length ? ids[i] : padId;
        return out;
    }

    public float[] embedText(String text) throws OrtException {
        if (textSession == null) throw new OrtException("текстовая часть не загружена");
        long t0 = System.nanoTime();
        long[] ids = tokenIds(text);
        Map<String, OnnxTensor> in = new HashMap<String, OnnxTensor>();
        try {
            in.put(idsInput, OnnxTensor.createTensor(env, LongBuffer.wrap(ids), new long[]{1, ids.length}));
            if (maskInput != null) {
                // SigLIP is trained without a padding mask: every position attends, pads included.
                long[] ones = new long[ids.length];
                java.util.Arrays.fill(ones, 1L);
                in.put(maskInput, OnnxTensor.createTensor(env, LongBuffer.wrap(ones), new long[]{1, ones.length}));
            }
            OrtSession.Result r = textSession.run(in);
            try {
                float[] v = rows(r.get(textOutput).get(), 1)[0];
                lastTextMs = (System.nanoTime() - t0) / 1_000_000;
                return v;
            } finally {
                r.close();
            }
        } finally {
            for (OnnxTensor t : in.values()) t.close();
        }
    }

    @Override
    public float[] embedQuery(String query) throws OrtException {
        return embedText(query);
    }

    @Override
    public float[] embedDocument(String text) throws OrtException {
        return embedText(text);
    }

    // ------------------------------------------------------------------ pictures

    /** Pixel values [3, size, size] for one picture, as SiglipImageProcessor prepares them. */
    public float[] pixels(ImagePreprocessor.Source src) {
        int[] argb = src.argb(size, size);
        int plane = size * size;
        float[] out = new float[3 * plane];
        for (int i = 0; i < plane; i++) {
            int p = argb[i];
            out[i] = (((p >> 16) & 0xFF) * rescale - mean[0]) / std[0];
            out[plane + i] = (((p >> 8) & 0xFF) * rescale - mean[1]) / std[1];
            out[2 * plane + i] = ((p & 0xFF) * rescale - mean[2]) / std[2];
        }
        return out;
    }

    @Override
    public float[] embedImage(ImagePreprocessor.Source image, int ignored) throws OrtException {
        List<ImagePreprocessor.Source> one = new ArrayList<ImagePreprocessor.Source>();
        one.add(image);
        return embedImages(one, 0)[0];
    }

    @Override
    public float[][] embedImages(List<ImagePreprocessor.Source> images, int ignored) throws OrtException {
        long t0 = System.nanoTime();
        int n = images.size();
        float[][] out = new float[n][];
        int step = fixedBatch > 0 ? fixedBatch : n;
        for (int start = 0; start < n; start += step) {
            int count = Math.min(step, n - start);
            int runSize = fixedBatch > 0 ? fixedBatch : count; // a static graph wants a full batch: pad with copies
            int plane = 3 * size * size;
            float[] buf = new float[runSize * plane];
            for (int i = 0; i < runSize; i++) {
                float[] px = pixels(images.get(start + Math.min(i, count - 1)));
                System.arraycopy(px, 0, buf, i * plane, plane);
            }
            OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(buf), new long[]{runSize, 3, size, size});
            try {
                Map<String, OnnxTensor> in = new HashMap<String, OnnxTensor>();
                in.put(pixelInput, t);
                OrtSession.Result r = visionSession.run(in);
                try {
                    float[][] rows = rows(r.get(visionOutput).get(), runSize);
                    for (int i = 0; i < count; i++) out[start + i] = rows[i];
                } finally {
                    r.close();
                }
            } finally {
                t.close();
            }
        }
        lastVisionMs = (System.nanoTime() - t0) / 1_000_000 / Math.max(1, n);
        lastTextMs = 0;
        return out;
    }

    /** A video is the normalised mean of its frames. */
    @Override
    public float[] embedVideo(List<ImagePreprocessor.Source> frames, int ignored) throws OrtException {
        float[][] f = embedImages(frames, 0);
        float[] sum = new float[f[0].length];
        for (float[] v : f) for (int i = 0; i < sum.length; i++) sum[i] += v[i];
        VectorMath.normalize(sum);
        return sum;
    }

    private float[][] rows(OnnxValue v, int n) throws OrtException {
        Object o = v.getValue();
        float[][] r;
        if (o instanceof float[][]) {
            r = (float[][]) o;
        } else {
            throw new OrtException("неожиданный выход модели: " + o.getClass().getSimpleName());
        }
        if (r.length < n) throw new OrtException("модель вернула " + r.length + " векторов вместо " + n);
        for (float[] row : r) VectorMath.normalize(row);
        dim = r[0].length;
        return r;
    }

    @Override
    public boolean supportsImages() { return true; }

    @Override
    public boolean supportsVideo() { return true; }

    @Override
    public int embeddingDim() { return dim; }

    @Override
    public int defaultImageTokens() { return 0; }

    @Override
    public long[] lastTimingsMs() { return new long[]{lastVisionMs, lastTextMs}; }

    @Override
    public void close() {
        try {
            if (textSession != null) textSession.close();
        } catch (OrtException ignored) {
            // already closed
        }
        try {
            visionSession.close();
        } catch (OrtException ignored) {
            // already closed
        }
    }
}
