package com.google.ai.edge.litertlm;

/** Built by the native library through its {@code (float[])} constructor. */
public final class EmbeddingResponse {
    private final float[] embedding;

    public EmbeddingResponse(float[] embedding) {
        this.embedding = embedding;
    }

    public float[] getEmbedding() {
        return embedding;
    }
}
