package io.github.teoplaydor.semsearch.core;

import java.io.Closeable;

/**
 * Runs EmbeddingGemma's vision encoder somewhere other than this process's ONNX Runtime session — the
 * Snapdragon NPU lives in a separate process with its own ONNX Runtime build (QNN), see app/NpuVision.
 */
public interface VisionRunner extends Closeable {
    /**
     * @param pixels    {@code [batch, patches, patchDim]} pixel values
     * @param positions {@code [batch, patches, 2]} patch positions, -1 for padding
     * @return the image features of all images, {@code [soft tokens, hidden]} row-major, padding rows included
     *         only if the encoder returns them
     */
    float[] run(float[] pixels, long[] positions, int batch, int patches, int patchDim) throws Exception;

    @Override
    void close();
}
