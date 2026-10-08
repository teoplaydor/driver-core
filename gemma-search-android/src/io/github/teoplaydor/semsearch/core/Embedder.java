package io.github.teoplaydor.semsearch.core;

import java.io.Closeable;
import java.util.List;

/** What the app needs from an embedding model (EmbeddingGemma2 on device, a fake in tests). */
public interface Embedder extends Closeable {
    float[] embedQuery(String query) throws Exception;

    float[] embedDocument(String text) throws Exception;

    /** @param maxSoftTokens token budget per image, or 0 for the model default. */
    float[] embedImage(ImagePreprocessor.Source image, int maxSoftTokens) throws Exception;

    /** Batch version of {@link #embedImage}: same results, one vision-encoder run. */
    float[][] embedImages(List<ImagePreprocessor.Source> images, int maxSoftTokens) throws Exception;

    float[] embedVideo(List<ImagePreprocessor.Source> frames, int maxSoftTokens) throws Exception;

    boolean supportsImages();

    boolean supportsVideo();

    int embeddingDim();

    int defaultImageTokens();

    /** {vision ms, text ms} of the last image/video embedding (for the indexing status line). */
    long[] lastTimingsMs();

    @Override
    void close();

    /**
     * Photos embedded in two stages that can run for different photos at once: the vision encoder (on an
     * accelerator — the NPU process, the GPU) and the text model (on the CPU). {@code finishImages(startImages(x))}
     * is {@code embedImages(x)}; while the text stage of one batch runs on another thread, the next batch's vision
     * stage runs (the indexing pipeline).
     */
    interface Staged {
        /** The vision stage: the photos' soft tokens, for {@link #finishImages}. */
        Object startImages(List<ImagePreprocessor.Source> images, int maxSoftTokens) throws Exception;

        /** The text stage; safe on another thread than {@link #startImages}, and close() waits for it. */
        float[][] finishImages(Object started) throws Exception;

        /** {vision ms, text ms} of a batch (the text stage's after it finished). */
        long[] timingsMs(Object started);
    }

    /** The model was closed before a stage on another thread ran: nothing is wrong with the photos. */
    final class Closed extends IllegalStateException {
        public Closed() {
            super("модель закрыта");
        }
    }
}
