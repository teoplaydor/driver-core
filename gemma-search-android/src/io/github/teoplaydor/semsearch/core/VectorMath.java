package io.github.teoplaydor.semsearch.core;

/** Small helpers for normalised embedding vectors and Matryoshka truncation. */
public final class VectorMath {
    private VectorMath() {}

    public static void normalize(float[] v) {
        double s = 0;
        for (float x : v) s += x * x;
        if (s <= 0) return;
        float inv = (float) (1.0 / Math.sqrt(s));
        for (int i = 0; i < v.length; i++) v[i] *= inv;
    }

    /**
     * Cosine similarity over the first {@code dims} components (Matryoshka truncation).
     * {@code bNorm} is the precomputed norm of b's prefix.
     */
    public static float cosinePrefix(float[] a, float aNorm, float[] b, int bOff, float bNorm, int dims) {
        double s = 0;
        for (int i = 0; i < dims; i++) s += a[i] * b[bOff + i];
        float d = aNorm * bNorm;
        return d > 0 ? (float) (s / d) : 0f;
    }

    public static float prefixNorm(float[] v, int off, int dims) {
        double s = 0;
        for (int i = 0; i < dims; i++) {
            float x = v[off + i];
            s += x * x;
        }
        return (float) Math.sqrt(s);
    }

    public static byte[] toBytes(float[] v) {
        byte[] b = new byte[v.length * 4];
        for (int i = 0; i < v.length; i++) {
            int bits = Float.floatToIntBits(v[i]);
            b[i * 4] = (byte) bits;
            b[i * 4 + 1] = (byte) (bits >> 8);
            b[i * 4 + 2] = (byte) (bits >> 16);
            b[i * 4 + 3] = (byte) (bits >> 24);
        }
        return b;
    }

    public static float[] fromBytes(byte[] b) {
        float[] v = new float[b.length / 4];
        for (int i = 0; i < v.length; i++) {
            int bits = (b[i * 4] & 0xff) | ((b[i * 4 + 1] & 0xff) << 8) | ((b[i * 4 + 2] & 0xff) << 16)
                    | ((b[i * 4 + 3] & 0xff) << 24);
            v[i] = Float.intBitsToFloat(bits);
        }
        return v;
    }
}
