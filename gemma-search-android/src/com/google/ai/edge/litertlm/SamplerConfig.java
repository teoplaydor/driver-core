package com.google.ai.edge.litertlm;

/**
 * How a session samples the next token, as LiteRT-LM 0.18's Config.kt {@code SamplerConfig}: the native session reads
 * it through these getters by name ({@code getTopK()I}, {@code getTopP()D}, {@code getTemperature()D},
 * {@code getSeed()I}), so they must stay so.
 */
public final class SamplerConfig {
    private final int topK;
    private final double topP;
    private final double temperature;
    private final int seed;

    public SamplerConfig(int topK, double topP, double temperature, int seed) {
        if (topK <= 0) throw new IllegalArgumentException("topK should be positive, but got " + topK);
        if (topP < 0 || topP > 1) throw new IllegalArgumentException("topP should between 0 and 1, but got " + topP);
        if (temperature < 0) throw new IllegalArgumentException("temperature should be non-negative, but got " + temperature);
        this.topK = topK;
        this.topP = topP;
        this.temperature = temperature;
        this.seed = seed;
    }

    public int getTopK() {
        return topK;
    }

    public double getTopP() {
        return topP;
    }

    public double getTemperature() {
        return temperature;
    }

    public int getSeed() {
        return seed;
    }
}
