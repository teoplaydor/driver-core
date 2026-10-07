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
}
