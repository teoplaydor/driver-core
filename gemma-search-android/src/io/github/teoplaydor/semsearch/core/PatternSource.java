package io.github.teoplaydor.semsearch.core;

/** Synthetic test image (smooth colour field + edges) rendered at any size; used for speed benchmarks. */
public final class PatternSource implements ImagePreprocessor.Source {
    private final int w, h, seed;

    public PatternSource(int w, int h, int seed) {
        this.w = w;
        this.h = h;
        this.seed = seed;
    }

    @Override
    public int width() { return w; }

    @Override
    public int height() { return h; }

    @Override
    public int[] argb(int tw, int th) {
        int[] px = new int[tw * th];
        for (int y = 0; y < th; y++) {
            double v = (double) y / th;
            for (int x = 0; x < tw; x++) {
                double u = (double) x / tw;
                int r = (int) (127 + 127 * Math.sin(6.3 * u + seed));
                int g = (int) (127 + 127 * Math.sin(9.1 * v + 2 * seed));
                int b = ((x / 24 + y / 24 + seed) & 1) == 0 ? 40 : 220;
                px[y * tw + x] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }
        return px;
    }
}
