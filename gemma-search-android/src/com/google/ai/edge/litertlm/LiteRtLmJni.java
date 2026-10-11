package com.google.ai.edge.litertlm;

/**
 * The LiteRT-LM JNI functions the app uses (embeddings; text generation for answers), declared as in LiteRT-LM 0.18's
 * kotlin/java/com/google/ai/edge/litertlm/LiteRtLmJni.kt (Apache 2.0). The native library binds them by
 * name ({@code Java_com_google_ai_edge_litertlm_LiteRtLmJni_<method>}), so the class and method names and
 * the argument types must stay exactly these. The library is not in the APK: the phone downloads it from
 * Google Maven and loads it by path (see app/LiteRt) before the first call.
 */
public final class LiteRtLmJni {
    private LiteRtLmJni() {}

    /** @return a native engine handle; throws {@link LiteRtLmJniException} on failure */
    public static native long nativeCreateEmbeddingEngine(int modelFd, String modelPath, String backend,
                                                          String visionBackend, String audioBackend, String cacheDir,
                                                          String mainNpuNativeLibraryDir, String visionNpuNativeLibraryDir,
                                                          String audioNpuNativeLibraryDir, int mainBackendNumThreads,
                                                          int audioBackendNumThreads, int maxInputLength,
                                                          int visionTokensPerImage, int activationDataType);

    public static native void nativeDeleteEmbeddingEngine(long embeddingEnginePointer);

    public static native EmbeddingResponse nativeComputeEmbedding(long embeddingEnginePointer, InputData[] inputData,
                                                                  Boolean normalize, Boolean insertSpecialTokens,
                                                                  Integer outputSize, Integer visionTokensPerImage);

    // ------------------------------------------------------------------ generation (Gemma)

    /** @return a native engine handle for a generative model; throws {@link LiteRtLmJniException} on failure */
    public static native long nativeCreateEngine(String modelPath, String backend, String visionBackend, String audioBackend,
                                                 int maxNumTokens, int maxNumImages, String cacheDir, boolean enableBenchmark,
                                                 Boolean enableSpeculativeDecoding, String mainNpuNativeLibraryDir,
                                                 String visionNpuNativeLibraryDir, String audioNpuNativeLibraryDir,
                                                 int mainBackendNumThreads, int audioBackendNumThreads,
                                                 int maxVisionTokensPerImage, int activationDataType);

    public static native void nativeDeleteEngine(long enginePointer);

    /** @param samplerConfig null: the model's own sampling */
    public static native long nativeCreateSession(long enginePointer, SamplerConfig samplerConfig, String loraPath,
                                                  String audioLoraPath, Boolean enableSpeculativeDecoding);

    public static native void nativeDeleteSession(long sessionPointer);

    /** The model's answer to the input (the session puts it in the model's chat template). */
    public static native String nativeGenerateContent(long sessionPointer, InputData[] inputData);

    public static native void nativeCancelProcess(long sessionPointer);

    public static native EmbeddingResponse[] nativeComputeEmbeddingBatch(long embeddingEnginePointer,
                                                                         InputData[][] inputDataBatch, Boolean normalize,
                                                                         Boolean insertSpecialTokens, Integer outputSize,
                                                                         Integer visionTokensPerImage);
}
