package io.github.teoplaydor.semsearch.core;

import com.google.ai.edge.litertlm.EmbeddingResponse;
import com.google.ai.edge.litertlm.InputData;
import com.google.ai.edge.litertlm.LiteRtLmJni;

import java.io.File;
import java.util.List;

/**
 * EmbeddingGemma 2 through Google's LiteRT-LM runtime and its {@code .litertlm} bundle: the same model as
 * the ONNX path, run by Google's own mobile kernels (GPU through OpenCL/WebGPU, or the CPU). Inputs mirror
 * the ONNX path: the retrieval prompts for text, pictures as encoded bytes — the runtime resizes and
 * patchifies them to the requested token budget and wraps them in {@code <bos> <start_of_image> … <eos>}.
 * The native library must be loaded before the first instance is created.
 */
public final class LiteRtEmbedder implements Embedder {
    public static final String GPU = "GPU", CPU = "CPU";

    /** Turns a picture into PNG or JPEG bytes (Bitmap.compress on the phone, ImageIO in tests). */
    public interface ImageEncoder {
        byte[] encode(ImagePreprocessor.Source image) throws Exception;
    }

    private long handle;
    private final ImageEncoder encoder;
    private final int batch;
    /** Created without a budget (the bundle's default signature): per-call budgets are not passed either. */
    private final boolean fixedBudget;
    private int dim = -1;
    private volatile long lastMs;

    /**
     * @param maxVisionTokens the largest soft-token budget that will be asked for (selects the encoder signature)
     * @param batch           pictures per native call while indexing
     */
    public LiteRtEmbedder(File model, String backend, int threads, int maxVisionTokens, File cacheDir, int batch,
                          ImageEncoder encoder) {
        this.encoder = encoder;
        this.batch = Math.max(1, batch);
        this.fixedBudget = maxVisionTokens <= 0;
        handle = LiteRtLmJni.nativeCreateEmbeddingEngine(-1, model.getPath(), backend, backend, "",
                cacheDir != null ? cacheDir.getPath() : "", "", "", "",
                CPU.equals(backend) && threads > 0 ? threads : -1, -1, -1, maxVisionTokens > 0 ? maxVisionTokens : -1, -1);
        if (handle == 0) throw new IllegalStateException("LiteRT-LM: движок не создан");
    }

    @Override
    public float[] embedQuery(String query) {
        return text(EmbeddingGemma2.QUERY_PREFIX + query);
    }

    @Override
    public float[] embedDocument(String text) {
        return text(EmbeddingGemma2.DOCUMENT_PREFIX + text);
    }

    /** Too long for the runtime's largest text signature: retry with the beginning only. */
    private float[] text(String s) {
        RuntimeException first = null;
        for (int limit : new int[]{Integer.MAX_VALUE, 4000, 1500}) {
            if (limit != Integer.MAX_VALUE && s.length() <= limit) continue;
            try {
                return run(new InputData[]{new InputData.Text(s.length() > limit ? s.substring(0, limit) : s)}, 0);
            } catch (RuntimeException e) {
                if (first == null) first = e;
            }
        }
        throw first;
    }

    @Override
    public float[] embedImage(ImagePreprocessor.Source image, int maxSoftTokens) throws Exception {
        return embedImages(java.util.Collections.singletonList(image), maxSoftTokens)[0];
    }

    @Override
    public float[][] embedImages(List<ImagePreprocessor.Source> images, int maxSoftTokens) throws Exception {
        long t0 = System.nanoTime();
        InputData[][] in = new InputData[images.size()][];
        for (int i = 0; i < in.length; i++) in[i] = new InputData[]{new InputData.Image(encoder.encode(images.get(i)))};
        float[][] out = new float[in.length][];
        for (int from = 0; from < in.length; from += batch) {
            int n = Math.min(batch, in.length - from);
            if (n == 1) {
                out[from] = run(in[from], maxSoftTokens);
                continue;
            }
            InputData[][] part = new InputData[n][];
            System.arraycopy(in, from, part, 0, n);
            EmbeddingResponse[] r;
            synchronized (this) {
                r = LiteRtLmJni.nativeComputeEmbeddingBatch(checkOpen(), part, Boolean.TRUE, null, null, budget(maxSoftTokens));
            }
            if (r == null || r.length != n) throw new IllegalStateException("LiteRT-LM: пачка вернула не то число векторов");
            for (int k = 0; k < n; k++) out[from + k] = finish(r[k]);
        }
        lastMs = (System.nanoTime() - t0) / 1000000;
        return out;
    }

    /** Frames as consecutive pictures of one input (the runtime has no separate video type). */
    @Override
    public float[] embedVideo(List<ImagePreprocessor.Source> frames, int maxSoftTokens) throws Exception {
        long t0 = System.nanoTime();
        InputData[] in = new InputData[frames.size()];
        for (int i = 0; i < in.length; i++) in[i] = new InputData.Image(encoder.encode(frames.get(i)));
        float[] e = run(in, maxSoftTokens > 0 ? maxSoftTokens : 70);
        lastMs = (System.nanoTime() - t0) / 1000000;
        return e;
    }

    private synchronized float[] run(InputData[] in, int maxSoftTokens) {
        return finish(LiteRtLmJni.nativeComputeEmbedding(checkOpen(), in, Boolean.TRUE, null, null, budget(maxSoftTokens)));
    }

    private Integer budget(int maxSoftTokens) {
        return maxSoftTokens > 0 && !fixedBudget ? Integer.valueOf(maxSoftTokens) : null;
    }

    private float[] finish(EmbeddingResponse r) {
        if (r == null || r.getEmbedding() == null || r.getEmbedding().length == 0) {
            throw new IllegalStateException("LiteRT-LM: пустой вектор");
        }
        float[] e = r.getEmbedding().clone();
        VectorMath.normalize(e);
        dim = e.length;
        return e;
    }

    private long checkOpen() {
        if (handle == 0) throw new IllegalStateException("LiteRT-LM: движок закрыт");
        return handle;
    }

    @Override
    public boolean supportsImages() { return true; }

    @Override
    public boolean supportsVideo() { return true; }

    @Override
    public int embeddingDim() { return dim > 0 ? dim : 768; }

    @Override
    public int defaultImageTokens() { return 280; }

    /** The runtime does not split picture and text time: all of it is reported as the picture's. */
    @Override
    public long[] lastTimingsMs() { return new long[]{lastMs, 0}; }

    @Override
    public synchronized void close() {
        if (handle != 0) {
            LiteRtLmJni.nativeDeleteEmbeddingEngine(handle);
            handle = 0;
        }
    }
}
