package io.github.teoplaydor.semsearch.core;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import ai.onnxruntime.platform.Fp16Conversions;

/**
 * On-device EmbeddingGemma 2 (ONNX export from onnx-community/embeddinggemma-2-ONNX).
 *
 * Mirrors transformers.js {@code EmbeddingGemma2Model}: the vision encoder turns each image
 * (or video frame) into soft tokens, the audio encoder the log-mel features of a clip
 * ({@link AudioFeatures}), and the text model consumes {@code input_ids},
 * {@code attention_mask} and the per-modality feature matrices, returning the mean-pooled,
 * L2-normalised {@code sentence_embedding}.
 */
public final class EmbeddingGemma2 implements Embedder, Embedder.Staged {
    /** Retrieval prompts from the model card (text inputs only; media is passed as is). */
    public static final String QUERY_PREFIX = "task: search result | query: ";
    public static final String DOCUMENT_PREFIX = "title: none | text: ";

    private final OrtEnvironment env;
    private final OrtSession textSession;
    private final OrtSession visionSession;
    /** The vision encoder elsewhere (the NPU process) instead of {@link #visionSession}. */
    private final VisionRunner visionRunner;
    /** The audio encoder, once {@link #loadAudio} found it (it is downloaded apart from the rest). */
    private volatile OrtSession audioSession;
    private final HfTokenizer tokenizer;
    private final ModelConfig cfg;
    /** With an NPU (NNAPI) the vision graph runs with fixed shapes: this many images of this many patches. */
    private int fixedBatch, fixedPatches;
    private int embeddingDim = -1;
    private volatile long lastVisionMs, lastTextMs;
    /**
     * Diagnostics: when set, the vision session of the next model created writes an ONNX Runtime profile
     * with this file prefix (see {@link #endVisionProfiling()} and OrtProfile).
     */
    public static volatile String profileVision;

    public EmbeddingGemma2(File dir, File textModel, File visionModel, int threads) throws IOException, OrtException {
        this(loadConfig(dir), loadTokenizer(dir), textModel, visionModel, threads, false);
    }

    /**
     * @param gpuVision run the vision encoder on the GPU through ONNX Runtime's WebGPU execution
     *                  provider (Vulkan on Android); unsupported ops fall back to the CPU.
     */
    public EmbeddingGemma2(ModelConfig config, HfTokenizer tok, File textModel, File visionModel, int threads,
                           boolean gpuVision) throws IOException, OrtException {
        this(config, tok, textModel, visionModel, threads, gpuVision ? VisionAccel.GPU : VisionAccel.CPU, 0, 0);
    }

    /** Where the vision encoder runs; the text model always stays on the CPU. */
    public enum VisionAccel {
        CPU, GPU,
        /** NNAPI with fp16 arithmetic allowed (NPUs are fastest in fp16); needs the fp32 graph. */
        NPU,
        /** NNAPI in strict fp32. */
        NPU_FP32
    }

    /**
     * @param batch   images per vision run for an NPU (it needs static shapes); ignored otherwise
     * @param budget  largest soft-token budget the NPU graph must take (photos and video frames are
     *                padded up to {@code budget × pool²} patches; padding is masked out by the model)
     */
    public EmbeddingGemma2(ModelConfig config, HfTokenizer tok, File textModel, File visionModel, int threads,
                           VisionAccel accel, int batch, int budget) throws IOException, OrtException {
        cfg = config;
        tokenizer = tok;
        visionRunner = null;
        resolveSpecialTokens();
        env = OrtEnvironment.getEnvironment();
        try {
            textSession = env.createSession(textModel.getPath(), options(threads, false));
        } catch (OrtException e) {
            throw new IOException("текстовая модель " + textModel.getName() + ": " + e.getMessage(), e);
        }
        boolean npu = accel == VisionAccel.NPU || accel == VisionAccel.NPU_FP32;
        if (npu) {
            fixedBatch = Math.max(1, batch);
            fixedPatches = Math.max(budget(cfg.image, budget).maxPatches(), cfg.video != null ? cfg.video.maxPatches() : 0);
        }
        try {
            OrtSession.SessionOptions o = npu ? npuOptions(visionModel, threads, accel == VisionAccel.NPU, fixedBatch, fixedPatches)
                    : options(threads, accel == VisionAccel.GPU);
            if (profileVision != null) o.enableProfiling(profileVision);
            visionSession = visionModel != null && visionModel.exists() ? env.createSession(visionModel.getPath(), o) : null;
        } catch (OrtException e) {
            textSession.close();
            throw new IOException("визуальный энкодер " + visionModel.getName() + " (" + accel + "): " + e.getMessage(), e);
        }
    }

    /** The text model here, the vision encoder through {@code vision} (e.g. on the Snapdragon NPU). */
    public EmbeddingGemma2(ModelConfig config, HfTokenizer tok, File textModel, VisionRunner vision, int threads)
            throws IOException {
        cfg = config;
        tokenizer = tok;
        visionRunner = vision;
        visionSession = null;
        resolveSpecialTokens();
        env = OrtEnvironment.getEnvironment();
        try {
            textSession = env.createSession(textModel.getPath(), options(threads, false));
        } catch (OrtException e) {
            throw new IOException("текстовая модель " + textModel.getName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * NNAPI options for the vision encoder: basic graph optimisations only (ORT's fused kernels are not
     * NNAPI ops and would split the graph) and every symbolic input dimension fixed — batch to
     * {@code batch}, patches to {@code patches} — because NNAPI compiles static shapes only.
     */
    static OrtSession.SessionOptions npuOptions(File graph, int threads, boolean fp16, int batch, int patches)
            throws OrtException, IOException {
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
        if (threads > 0) o.setIntraOpNumThreads(threads);
        for (java.util.List<String> dims : OnnxPatcher.inputDims(graph).values()) {
            for (int i = 0; i < dims.size() && i < 2; i++) {
                String d = dims.get(i);
                if (d.isEmpty() || Character.isDigit(d.charAt(0)) || "?".equals(d)) continue;
                o.setSymbolicDimensionValue(d, i == 0 ? batch : patches);
            }
        }
        o.addNnapi(fp16 ? java.util.EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.USE_FP16,
                ai.onnxruntime.providers.NNAPIFlags.CPU_DISABLED)
                : java.util.EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.CPU_DISABLED));
        return o;
    }

    /** Test hook: the NPU's static-shape path (padding, fixed runs) on whatever provider is loaded. */
    public void staticShapesForTest(int batch, int budget) {
        fixedBatch = batch;
        fixedPatches = Math.max(budget(cfg.image, budget).maxPatches(), cfg.video != null ? cfg.video.maxPatches() : 0);
    }

    public static ModelConfig loadConfig(File dir) throws IOException {
        try {
            return ModelConfig.load(dir);
        } catch (Exception e) {
            throw new IOException("конфиги модели: " + e, e);
        }
    }

    public static HfTokenizer loadTokenizer(File dir) throws IOException {
        try {
            return HfTokenizer.load(new File(dir, "tokenizer.json"), new File(dir, "tokenizer.bin"));
        } catch (Exception e) {
            throw new IOException("токенизатор: " + e, e);
        }
    }

    /** Fills token strings / ids that only one of config.json and tokenizer_config.json provides. */
    private void resolveSpecialTokens() {
        if (cfg.imageToken == null && cfg.imageTokenId >= 0) cfg.imageToken = tokenizer.token(cfg.imageTokenId);
        if (cfg.videoToken == null && cfg.videoTokenId >= 0) cfg.videoToken = tokenizer.token(cfg.videoTokenId);
        if (cfg.boiToken == null && cfg.boiTokenId >= 0) cfg.boiToken = tokenizer.token(cfg.boiTokenId);
        if (cfg.eoiToken == null && cfg.eoiTokenId >= 0) cfg.eoiToken = tokenizer.token(cfg.eoiTokenId);
        if (cfg.imageTokenId < 0 && cfg.imageToken != null && tokenizer.tokenId(cfg.imageToken) != null) {
            cfg.imageTokenId = tokenizer.tokenId(cfg.imageToken);
        }
        if (cfg.videoTokenId < 0 && cfg.videoToken != null && tokenizer.tokenId(cfg.videoToken) != null) {
            cfg.videoTokenId = tokenizer.tokenId(cfg.videoToken);
        }
        if (cfg.audioToken == null && cfg.audioTokenId >= 0) cfg.audioToken = tokenizer.token(cfg.audioTokenId);
        if (cfg.audioTokenId < 0 && cfg.audioToken != null && tokenizer.tokenId(cfg.audioToken) != null) {
            cfg.audioTokenId = tokenizer.tokenId(cfg.audioToken);
        }
        cfg.hasVideo = cfg.hasVideo || (cfg.videoToken != null && cfg.videoTokenId >= 0 && cfg.hasVideoProcessor);
    }

    private static OrtSession.SessionOptions options(int threads, boolean gpu) throws OrtException {
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        if (threads > 0) o.setIntraOpNumThreads(threads);
        if (gpu) o.addWebGPU(new java.util.HashMap<String, String>());
        // Thread spinning stays at ORT's default (on): indexing runs back-to-back inferences, and parking
        // worker threads between ops made each photo noticeably slower.
        return o;
    }

    public ModelConfig config() { return cfg; }

    public HfTokenizer tokenizer() { return tokenizer; }

    public boolean supportsImages() {
        return (visionSession != null || visionRunner != null) && cfg.imageToken != null && cfg.imageTokenId >= 0;
    }

    public boolean supportsVideo() {
        return supportsImages() && cfg.hasVideo;
    }

    /**
     * The audio encoder (its own graph, downloaded on request): from now on the model embeds audio, and videos with
     * their sound. On the CPU.
     */
    public void loadAudio(File audioModel, int threads) throws IOException {
        if (cfg.audio == null || cfg.audioToken == null || cfg.audioTokenId < 0) {
            throw new IOException("у модели нет звуковой части (нет настроек звука или токена <|audio|>)");
        }
        try {
            OrtSession s = env.createSession(audioModel.getPath(), options(threads, false));
            OrtSession old = audioSession;
            audioSession = s;
            if (old != null) old.close();
        } catch (OrtException e) {
            throw new IOException("звуковой энкодер " + audioModel.getName() + ": " + e.getMessage(), e);
        }
    }

    public boolean supportsAudio() {
        return audioSession != null;
    }

    public int embeddingDim() { return embeddingDim; }

    public int defaultImageTokens() { return cfg.image.maxSoftTokens; }

    /** Stops a profile started through {@link #profileVision}; returns the JSON file it wrote. */
    public File endVisionProfiling() throws OrtException {
        return new File(visionSession.endProfiling());
    }

    /** {vision encoder ms, text model ms} of the last image/video embedding. */
    public long[] lastTimingsMs() { return new long[]{lastVisionMs, lastTextMs}; }

    // ------------------------------------------------------------------ text

    public float[] embedQuery(String query) throws OrtException {
        return embedText(QUERY_PREFIX + query, 512);
    }

    public float[] embedDocument(String text) throws OrtException {
        return embedText(DOCUMENT_PREFIX + text, 2048);
    }

    /** The model card's document prompt with a title: "title: {title} | text: {text}". */
    @Override
    public float[] embedDocument(String title, String text) throws OrtException {
        String t = title == null ? "" : title.replace('\n', ' ').trim();
        return embedText("title: " + (t.isEmpty() ? "none" : t) + " | text: " + text, 2048);
    }

    public float[] embedText(String text, int maxTokens) throws OrtException {
        int[] ids = tokenizer.encode(text, true, maxTokens);
        return runTextModel(ids, new float[0], 0, new float[0], 0);
    }

    // ------------------------------------------------------------------ images & video

    /**
     * @param maxSoftTokens token budget per image, or 0 for the processor default (280).
     */
    public float[] embedImage(ImagePreprocessor.Source image, int maxSoftTokens) throws OrtException {
        return embedImages(java.util.Collections.singletonList(image), maxSoftTokens)[0];
    }

    /**
     * Several photos at once: one vision-encoder run over the batch (better GPU utilisation),
     * then the text model per photo. Same results as calling {@link #embedImage} one by one.
     */
    public float[][] embedImages(List<ImagePreprocessor.Source> images, int maxSoftTokens) throws OrtException {
        return finishImages(startImages(images, maxSoftTokens));
    }

    /** A batch between the stages: all soft tokens in order, how many each photo has, the stages' times. */
    private static final class Started {
        float[] feats;
        int[] tokens;
        long visionMs, textMs;
    }

    /** The text stages running now (on the indexing pipeline's thread): close() waits for them. */
    private int textBusy;
    private boolean closed;

    public Object startImages(List<ImagePreprocessor.Source> images, int maxSoftTokens) throws OrtException {
        if (!supportsImages()) throw new IllegalStateException("vision encoder is not loaded");
        ModelConfig.ImageParams p = budget(cfg.image, maxSoftTokens);
        List<ImagePreprocessor.Patches> patches = new ArrayList<ImagePreprocessor.Patches>();
        for (ImagePreprocessor.Source img : images) patches.add(ImagePreprocessor.process(img, p));
        Started s = new Started();
        s.tokens = new int[patches.size()];
        for (int k = 0; k < s.tokens.length; k++) s.tokens[k] = patches.get(k).numSoftTokens;
        long t0 = System.nanoTime();
        s.feats = encodeVisionBatch(patches);
        s.visionMs = (System.nanoTime() - t0) / 1000000;
        return s;
    }

    public float[][] finishImages(Object started) throws OrtException {
        Started s = (Started) started;
        synchronized (this) {
            if (closed) throw new Embedder.Closed();
            textBusy++;
        }
        try {
            float[][] out = new float[s.tokens.length][];
            long textNs = 0;
            int off = 0;
            for (int k = 0; k < s.tokens.length; k++) {
                int n = s.tokens[k];
                float[] f = new float[n * cfg.hiddenSize];
                System.arraycopy(s.feats, off, f, 0, f.length);
                off += f.length;
                StringBuilder sb = new StringBuilder(cfg.boiToken == null ? "" : cfg.boiToken);
                for (int i = 0; i < n; i++) sb.append(cfg.imageToken);
                if (cfg.eoiToken != null) sb.append(cfg.eoiToken);
                int[] ids = tokenizer.encode(sb.toString());
                long t1 = System.nanoTime();
                out[k] = runTextModel(ids, f, n, new float[0], 0);
                textNs += System.nanoTime() - t1;
            }
            s.textMs = textNs / 1000000;
            lastVisionMs = s.visionMs;
            lastTextMs = s.textMs;
            return out;
        } finally {
            synchronized (this) {
                textBusy--;
                notifyAll();
            }
        }
    }

    public long[] timingsMs(Object started) {
        Started s = (Started) started;
        return new long[]{s.visionMs, s.textMs};
    }

    /** A video is a sequence of frames, each an image-like block of video soft tokens. */
    public float[] embedVideo(List<ImagePreprocessor.Source> frames, int maxSoftTokens) throws OrtException {
        return embedVideo(frames, maxSoftTokens, null);
    }

    /**
     * A video with its sound: the frames' blocks, then (after a space, as the processor joins the placeholders of a
     * video and its audio) the audio's block. {@code pcm}: mono 16 kHz samples, or null for none.
     */
    public float[] embedVideo(List<ImagePreprocessor.Source> frames, int maxSoftTokens, float[] pcm) throws OrtException {
        if (!supportsVideo()) throw new IllegalStateException("video is not supported by this model");
        ModelConfig.ImageParams p = budget(cfg.video, maxSoftTokens);
        long t0 = System.nanoTime();
        List<float[]> all = new ArrayList<float[]>();
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (ImagePreprocessor.Source f : frames) {
            ImagePreprocessor.Patches patches = ImagePreprocessor.process(f, p);
            all.add(encodeVision(patches));
            if (cfg.boiToken != null) sb.append(cfg.boiToken);
            for (int i = 0; i < patches.numSoftTokens; i++) sb.append(cfg.videoToken);
            if (cfg.eoiToken != null) sb.append(cfg.eoiToken);
            total += patches.numSoftTokens;
        }
        float[] feats = new float[total * cfg.hiddenSize];
        int off = 0;
        for (float[] a : all) {
            System.arraycopy(a, 0, feats, off, a.length);
            off += a.length;
        }
        float[] audioFeats = new float[0];
        int nAudio = 0;
        if (pcm != null && supportsAudio()) {
            AudioFeatures af = AudioFeatures.extract(pcm, cfg.audio);
            nAudio = af.softTokens();
            audioFeats = encodeAudio(af);
            sb.append(' ').append(audioBlock(nAudio));
        }
        int[] ids = tokenizer.encode(sb.toString());
        lastVisionMs = (System.nanoTime() - t0) / 1000000;
        long t1 = System.nanoTime();
        float[] emb = runTextModel(ids, new float[0], 0, feats, total, audioFeats, nAudio);
        lastTextMs = (System.nanoTime() - t1) / 1000000;
        return emb;
    }

    // ------------------------------------------------------------------ audio

    /** A clip (mono samples at the extractor's rate, 16 kHz; the first 30 s count) as one vector. */
    public float[] embedAudio(float[] pcm) throws OrtException {
        if (!supportsAudio()) throw new IllegalStateException("звуковой энкодер не загружен");
        long t0 = System.nanoTime();
        AudioFeatures af = AudioFeatures.extract(pcm, cfg.audio);
        int n = af.softTokens();
        float[] feats = encodeAudio(af);
        int[] ids = tokenizer.encode(audioBlock(n));
        lastVisionMs = (System.nanoTime() - t0) / 1000000;
        long t1 = System.nanoTime();
        float[] emb = runTextModel(ids, new float[0], 0, new float[0], 0, feats, n);
        lastTextMs = (System.nanoTime() - t1) / 1000000;
        return emb;
    }

    /** The placeholder of a clip with {@code n} soft tokens: {@code <|audio>} n × {@code <|audio|>} {@code <audio|>}. */
    private String audioBlock(int n) {
        StringBuilder sb = new StringBuilder(cfg.boaToken == null ? "" : cfg.boaToken);
        for (int i = 0; i < n; i++) sb.append(cfg.audioToken);
        if (cfg.eoaToken != null) sb.append(cfg.eoaToken);
        return sb.toString();
    }

    /** The audio encoder over a clip's features: its soft tokens ({@code n × hidden}), the valid ones only. */
    private float[] encodeAudio(AudioFeatures af) throws OrtException {
        int want = af.softTokens();
        if (want == 0) return new float[0];
        OrtSession s = audioSession;
        Map<String, OnnxTensor> in = new HashMap<String, OnnxTensor>();
        try {
            for (String name : s.getInputNames()) {
                if ("input_features".equals(name)) {
                    in.put(name, floatTensor(s, name, af.features, new long[]{1, af.frames, af.featureSize}));
                } else if ("input_features_mask".equals(name)) {
                    in.put(name, OnnxTensor.createTensor(env, new boolean[][]{af.mask}));
                } else {
                    throw new IllegalStateException("unexpected audio encoder input: " + name);
                }
            }
            OrtSession.Result r = s.run(in);
            try {
                OnnxTensor t = (OnnxTensor) (r.get("audio_features").isPresent() ? r.get("audio_features").get() : r.get(0));
                float[] data = toFloats(t);
                if (data.length != want * cfg.hiddenSize) {
                    throw new IllegalStateException("audio encoder returned " + java.util.Arrays.toString(t.getInfo().getShape())
                            + " for " + want + " soft tokens");
                }
                return data;
            } finally {
                r.close();
            }
        } finally {
            for (OnnxTensor t : in.values()) t.close();
        }
    }

    private static ModelConfig.ImageParams budget(ModelConfig.ImageParams base, int maxSoftTokens) {
        if (maxSoftTokens <= 0 || maxSoftTokens == base.maxSoftTokens) return base;
        ModelConfig.ImageParams p = new ModelConfig.ImageParams(maxSoftTokens);
        p.patchSize = base.patchSize;
        p.poolingKernelSize = base.poolingKernelSize;
        p.rescaleFactor = base.rescaleFactor;
        p.doRescale = base.doRescale;
        p.doResize = base.doResize;
        return p;
    }

    private float[] encodeVision(ImagePreprocessor.Patches p) throws OrtException {
        return encodeVisionBatch(java.util.Collections.singletonList(p));
    }

    /** Runs the vision encoder on a batch (same token budget) and returns all soft tokens in order. */
    private float[] encodeVisionBatch(List<ImagePreprocessor.Patches> ps) throws OrtException {
        if (fixedBatch > 0) return encodeFixed(ps);
        return encodeVisionRun(ps);
    }

    /**
     * Static shapes for the NPU: every image padded to {@code fixedPatches} patches (zeros at position -1,
     * exactly what the processor does for an image smaller than its budget), runs of {@code fixedBatch}
     * images (a short last run is filled with copies whose soft tokens come last and are dropped).
     */
    private float[] encodeFixed(List<ImagePreprocessor.Patches> ps) throws OrtException {
        List<ImagePreprocessor.Patches> padded = new ArrayList<ImagePreprocessor.Patches>();
        int total = 0;
        for (ImagePreprocessor.Patches p : ps) {
            padded.add(pad(p, fixedPatches));
            total += p.numSoftTokens;
        }
        float[] out = new float[total * cfg.hiddenSize];
        int off = 0;
        for (int start = 0; start < padded.size(); start += fixedBatch) {
            int count = Math.min(fixedBatch, padded.size() - start);
            List<ImagePreprocessor.Patches> run = new ArrayList<ImagePreprocessor.Patches>(padded.subList(start, start + count));
            while (run.size() < fixedBatch) run.add(run.get(run.size() - 1));
            int want = 0;
            for (int i = 0; i < count; i++) want += run.get(i).numSoftTokens;
            float[] f = encodeVisionRun(run);
            System.arraycopy(f, 0, out, off, want * cfg.hiddenSize);
            off += want * cfg.hiddenSize;
        }
        return out;
    }

    static ImagePreprocessor.Patches pad(ImagePreprocessor.Patches p, int patches) {
        if (p.maxPatches == patches) return p;
        if (p.maxPatches > patches) throw new IllegalStateException("budget larger than the NPU graph: " + p.maxPatches);
        ImagePreprocessor.Patches q = new ImagePreprocessor.Patches();
        q.maxPatches = patches;
        q.patchDim = p.patchDim;
        q.numSoftTokens = p.numSoftTokens;
        q.pixelValues = new float[patches * p.patchDim];
        System.arraycopy(p.pixelValues, 0, q.pixelValues, 0, p.pixelValues.length);
        q.positionIds = new long[patches * 2];
        java.util.Arrays.fill(q.positionIds, -1L);
        System.arraycopy(p.positionIds, 0, q.positionIds, 0, p.positionIds.length);
        return q;
    }

    private float[] encodeVisionRun(List<ImagePreprocessor.Patches> ps) throws OrtException {
        int b = ps.size();
        int maxPatches = ps.get(0).maxPatches, patchDim = ps.get(0).patchDim;
        float[] pixels;
        long[] positions;
        int expected = 0;
        if (b == 1) {
            pixels = ps.get(0).pixelValues;
            positions = ps.get(0).positionIds;
            expected = ps.get(0).numSoftTokens;
        } else {
            pixels = new float[b * maxPatches * patchDim];
            positions = new long[b * maxPatches * 2];
            for (int k = 0; k < b; k++) {
                ImagePreprocessor.Patches p = ps.get(k);
                if (p.maxPatches != maxPatches) throw new IllegalArgumentException("mixed token budgets in a batch");
                System.arraycopy(p.pixelValues, 0, pixels, k * maxPatches * patchDim, p.pixelValues.length);
                System.arraycopy(p.positionIds, 0, positions, k * maxPatches * 2, p.positionIds.length);
                expected += p.numSoftTokens;
            }
        }
        if (visionRunner != null) {
            float[] data;
            try {
                data = visionRunner.run(pixels, positions, b, maxPatches, patchDim);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage() != null ? e.getMessage() : e.toString(), e);
            }
            if (data.length != expected * cfg.hiddenSize) {
                throw new IllegalStateException("vision encoder returned " + data.length / cfg.hiddenSize + " rows for "
                        + expected + " soft tokens");
            }
            return data;
        }
        Map<String, OnnxTensor> in = new HashMap<String, OnnxTensor>();
        try {
            for (String name : visionSession.getInputNames()) {
                if ("pixel_values".equals(name)) {
                    in.put(name, floatTensor(visionSession, name, pixels, new long[]{b, maxPatches, patchDim}));
                } else if ("pixel_position_ids".equals(name) || "image_position_ids".equals(name)) {
                    in.put(name, OnnxTensor.createTensor(env, LongBuffer.wrap(positions), new long[]{b, maxPatches, 2}));
                } else {
                    throw new IllegalStateException("unexpected vision encoder input: " + name);
                }
            }
            OrtSession.Result r = visionSession.run(in);
            try {
                OnnxValue v = r.get("image_features").isPresent() ? r.get("image_features").get() : r.get(0);
                OnnxTensor t = (OnnxTensor) v;
                long[] shape = t.getInfo().getShape();
                float[] data = toFloats(t);
                long rows = data.length / cfg.hiddenSize;
                if (rows != expected && !(fixedBatch > 0 && rows >= expected)) {
                    throw new IllegalStateException("vision encoder returned " + java.util.Arrays.toString(shape)
                            + " for " + expected + " soft tokens");
                }
                return data;
            } finally {
                r.close();
            }
        } finally {
            for (OnnxTensor t : in.values()) t.close();
        }
    }

    // ------------------------------------------------------------------ text model

    private float[] runTextModel(int[] ids, float[] imageFeats, int nImage, float[] videoFeats, int nVideo)
            throws OrtException {
        return runTextModel(ids, imageFeats, nImage, videoFeats, nVideo, new float[0], 0);
    }

    private float[] runTextModel(int[] ids, float[] imageFeats, int nImage, float[] videoFeats, int nVideo,
                                 float[] audioFeats, int nAudio) throws OrtException {
        int imageCount = 0, videoCount = 0, audioCount = 0;
        for (int id : ids) {
            if (id == cfg.imageTokenId) imageCount++;
            if (id == cfg.videoTokenId) videoCount++;
            if (id == cfg.audioTokenId) audioCount++;
        }
        if (imageCount != nImage) throw new IllegalStateException("image tokens " + imageCount + " != features " + nImage);
        if (videoCount != nVideo) throw new IllegalStateException("video tokens " + videoCount + " != features " + nVideo);
        if (audioCount != nAudio) throw new IllegalStateException("audio tokens " + audioCount + " != features " + nAudio);

        long[] idsL = new long[ids.length];
        long[] mask = new long[ids.length];
        for (int i = 0; i < ids.length; i++) {
            idsL[i] = ids[i];
            mask[i] = 1;
        }
        int h = cfg.hiddenSize;
        Map<String, OnnxTensor> in = new HashMap<String, OnnxTensor>();
        try {
            for (String name : textSession.getInputNames()) {
                if ("input_ids".equals(name)) {
                    in.put(name, OnnxTensor.createTensor(env, LongBuffer.wrap(idsL), new long[]{1, ids.length}));
                } else if ("attention_mask".equals(name)) {
                    in.put(name, OnnxTensor.createTensor(env, LongBuffer.wrap(mask), new long[]{1, ids.length}));
                } else if ("image_features".equals(name)) {
                    in.put(name, floatTensor(textSession, name, imageFeats, new long[]{nImage, h}));
                } else if ("video_features".equals(name)) {
                    in.put(name, floatTensor(textSession, name, videoFeats, new long[]{nVideo, h}));
                } else if ("audio_features".equals(name)) {
                    in.put(name, floatTensor(textSession, name, audioFeats, new long[]{nAudio, h}));
                } else {
                    throw new IllegalStateException("unexpected text model input: " + name);
                }
            }
            OrtSession.Result r = textSession.run(in);
            try {
                float[] emb;
                if (r.get("sentence_embedding").isPresent()) {
                    emb = toFloats((OnnxTensor) r.get("sentence_embedding").get());
                } else {
                    // Fallback: mean-pool last_hidden_state.
                    float[] hs = toFloats((OnnxTensor) r.get(0));
                    int d = hs.length / ids.length;
                    emb = new float[d];
                    for (int t = 0; t < ids.length; t++) for (int j = 0; j < d; j++) emb[j] += hs[t * d + j];
                }
                VectorMath.normalize(emb);
                embeddingDim = emb.length;
                return emb;
            } finally {
                r.close();
            }
        } finally {
            for (OnnxTensor t : in.values()) t.close();
        }
    }

    private OnnxTensor floatTensor(OrtSession s, String name, float[] data, long[] shape) throws OrtException {
        NodeInfo info = s.getInputInfo().get(name);
        OnnxJavaType type = info != null && info.getInfo() instanceof TensorInfo
                ? ((TensorInfo) info.getInfo()).type : OnnxJavaType.FLOAT;
        if (type == OnnxJavaType.FLOAT16) {
            return OnnxTensor.createTensor(env,
                    Fp16Conversions.convertFloatBufferToFp16Buffer(FloatBuffer.wrap(data)), shape, OnnxJavaType.FLOAT16);
        }
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape);
    }

    private static float[] toFloats(OnnxTensor t) {
        FloatBuffer b = t.getFloatBuffer();
        if (b == null) throw new IllegalStateException("unsupported output type " + t.getInfo().type);
        float[] a = new float[b.remaining()];
        b.get(a);
        return a;
    }

    @Override
    public void close() {
        // a text stage on the pipeline's thread finishes first: its session must not go away under it
        synchronized (this) {
            closed = true;
            boolean interrupted = false;
            while (textBusy > 0) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        try {
            textSession.close();
        } catch (OrtException ignored) {
        }
        if (visionRunner != null) visionRunner.close();
        if (audioSession != null) {
            try {
                audioSession.close();
            } catch (OrtException ignored) {
            }
        }
        if (visionSession != null) {
            try {
                visionSession.close();
            } catch (OrtException ignored) {
            }
        }
    }
}
