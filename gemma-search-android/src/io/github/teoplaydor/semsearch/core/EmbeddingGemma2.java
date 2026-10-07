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
 * (or video frame) into soft tokens, and the text model consumes {@code input_ids},
 * {@code attention_mask} and the per-modality feature matrices, returning the mean-pooled,
 * L2-normalised {@code sentence_embedding}.
 */
public final class EmbeddingGemma2 implements Closeable {
    /** Retrieval prompts from the model card (text inputs only; media is passed as is). */
    public static final String QUERY_PREFIX = "task: search result | query: ";
    public static final String DOCUMENT_PREFIX = "title: none | text: ";

    private final OrtEnvironment env;
    private final OrtSession textSession;
    private final OrtSession visionSession;
    private final HfTokenizer tokenizer;
    private final ModelConfig cfg;
    private int embeddingDim = -1;

    public EmbeddingGemma2(File dir, File textModel, File visionModel, int threads) throws IOException, OrtException {
        cfg = ModelConfig.load(dir);
        tokenizer = HfTokenizer.load(new File(dir, "tokenizer.json"), new File(dir, "tokenizer.bin"));
        env = OrtEnvironment.getEnvironment();
        textSession = env.createSession(textModel.getPath(), options(threads));
        visionSession = visionModel != null && visionModel.exists()
                ? env.createSession(visionModel.getPath(), options(threads)) : null;
    }

    private static OrtSession.SessionOptions options(int threads) throws OrtException {
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        if (threads > 0) o.setIntraOpNumThreads(threads);
        // Don't busy-wait between ops: saves battery on phones.
        o.addConfigEntry("session.intra_op.allow_spinning", "0");
        return o;
    }

    public ModelConfig config() { return cfg; }

    public HfTokenizer tokenizer() { return tokenizer; }

    public boolean supportsImages() {
        return visionSession != null && cfg.imageToken != null && cfg.imageTokenId >= 0;
    }

    public boolean supportsVideo() {
        return supportsImages() && cfg.hasVideo;
    }

    public int embeddingDim() { return embeddingDim; }

    // ------------------------------------------------------------------ text

    public float[] embedQuery(String query) throws OrtException {
        return embedText(QUERY_PREFIX + query, 512);
    }

    public float[] embedDocument(String text) throws OrtException {
        return embedText(DOCUMENT_PREFIX + text, 2048);
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
        if (!supportsImages()) throw new IllegalStateException("vision encoder is not loaded");
        ModelConfig.ImageParams p = budget(cfg.image, maxSoftTokens);
        ImagePreprocessor.Patches patches = ImagePreprocessor.process(image, p);
        float[] feats = encodeVision(patches);
        StringBuilder sb = new StringBuilder(cfg.boiToken == null ? "" : cfg.boiToken);
        for (int i = 0; i < patches.numSoftTokens; i++) sb.append(cfg.imageToken);
        if (cfg.eoiToken != null) sb.append(cfg.eoiToken);
        int[] ids = tokenizer.encode(sb.toString());
        return runTextModel(ids, feats, patches.numSoftTokens, new float[0], 0);
    }

    /** A video is a sequence of frames, each an image-like block of video soft tokens. */
    public float[] embedVideo(List<ImagePreprocessor.Source> frames, int maxSoftTokens) throws OrtException {
        if (!supportsVideo()) throw new IllegalStateException("video is not supported by this model");
        ModelConfig.ImageParams p = budget(cfg.video, maxSoftTokens);
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
        int[] ids = tokenizer.encode(sb.toString());
        return runTextModel(ids, new float[0], 0, feats, total);
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
        Map<String, OnnxTensor> in = new HashMap<String, OnnxTensor>();
        try {
            for (String name : visionSession.getInputNames()) {
                if ("pixel_values".equals(name)) {
                    in.put(name, floatTensor(visionSession, name, p.pixelValues, new long[]{1, p.maxPatches, p.patchDim}));
                } else if ("pixel_position_ids".equals(name) || "image_position_ids".equals(name)) {
                    in.put(name, OnnxTensor.createTensor(env, LongBuffer.wrap(p.positionIds), new long[]{1, p.maxPatches, 2}));
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
                if (rows != p.numSoftTokens) {
                    throw new IllegalStateException("vision encoder returned " + java.util.Arrays.toString(shape)
                            + " for " + p.numSoftTokens + " soft tokens");
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
        int imageCount = 0, videoCount = 0;
        for (int id : ids) {
            if (id == cfg.imageTokenId) imageCount++;
            if (id == cfg.videoTokenId) videoCount++;
        }
        if (imageCount != nImage) throw new IllegalStateException("image tokens " + imageCount + " != features " + nImage);
        if (videoCount != nVideo) throw new IllegalStateException("video tokens " + videoCount + " != features " + nVideo);

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
                    in.put(name, floatTensor(textSession, name, new float[0], new long[]{0, h}));
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
        try {
            textSession.close();
        } catch (OrtException ignored) {
        }
        if (visionSession != null) {
            try {
                visionSession.close();
            } catch (OrtException ignored) {
            }
        }
    }
}
