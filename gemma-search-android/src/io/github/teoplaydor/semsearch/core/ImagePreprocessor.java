package io.github.teoplaydor.semsearch.core;

/**
 * Port of transformers.js {@code Gemma4ImageProcessor}: aspect-ratio preserving resize to a
 * multiple of {@code pooling_kernel_size * patch_size}, rescale to [0, 1], then patchify into
 * {@code [max_patches, patch_size * patch_size * 3]} with {@code [col, row]} position ids
 * (padding rows are zero / -1).
 */
public final class ImagePreprocessor {
    private ImagePreprocessor() {}

    /** Platform image: Android Bitmap on device, BufferedImage in desktop tests. */
    public interface Source {
        int width();

        int height();

        /** Returns ARGB pixels (row-major) resized to exactly {@code w x h}. */
        int[] argb(int w, int h);
    }

    public static final class Patches {
        public float[] pixelValues;   // [maxPatches * patchDim]
        public long[] positionIds;    // [maxPatches * 2]
        public int maxPatches, patchDim, numSoftTokens;
    }

    /** Exact port of get_aspect_ratio_preserving_size() — returns {targetHeight, targetWidth}. */
    public static int[] targetSize(int height, int width, int patchSize, int maxPatches, int pool) {
        double targetPx = (double) maxPatches * patchSize * patchSize;
        double factor = Math.sqrt(targetPx / ((double) height * width));
        int sideMult = pool * patchSize;
        int th = (int) (Math.floor((factor * height) / sideMult) * sideMult);
        int tw = (int) (Math.floor((factor * width) / sideMult) * sideMult);
        if (th == 0 && tw == 0) {
            throw new IllegalArgumentException("Attempting to resize to a 0 x 0 image");
        }
        int maxSide = (int) (Math.floor((double) maxPatches / (pool * pool)) * sideMult);
        if (th == 0) {
            th = sideMult;
            tw = (int) Math.min(Math.floor((double) width / height) * sideMult, maxSide);
        } else if (tw == 0) {
            tw = sideMult;
            th = (int) Math.min(Math.floor((double) height / width) * sideMult, maxSide);
        }
        return new int[]{th, tw};
    }

    public static Patches process(Source src, ModelConfig.ImageParams p) {
        int h = src.height(), w = src.width();
        int maxPatches = p.maxPatches();
        if (p.doResize) {
            int[] t = targetSize(h, w, p.patchSize, maxPatches, p.poolingKernelSize);
            h = t[0];
            w = t[1];
        }
        int[] px = src.argb(w, h);
        float scale = p.doRescale ? (float) p.rescaleFactor : 1f;

        int ps = p.patchSize;
        int nph = h / ps, npw = w / ps;
        int numPatches = nph * npw;
        if (numPatches > maxPatches) throw new IllegalStateException("too many patches: " + numPatches);
        int patchDim = ps * ps * 3;

        Patches out = new Patches();
        out.maxPatches = maxPatches;
        out.patchDim = patchDim;
        out.pixelValues = new float[maxPatches * patchDim];
        out.positionIds = new long[maxPatches * 2];
        java.util.Arrays.fill(out.positionIds, -1L);

        // (pH, pW, dy, dx, c) order == reading HWC row by row inside each patch
        int o = 0;
        for (int ph = 0; ph < nph; ph++) {
            for (int pw = 0; pw < npw; pw++) {
                for (int dy = 0; dy < ps; dy++) {
                    int row = (ph * ps + dy) * w + pw * ps;
                    for (int dx = 0; dx < ps; dx++) {
                        int c = px[row + dx];
                        out.pixelValues[o++] = ((c >> 16) & 0xff) * scale;
                        out.pixelValues[o++] = ((c >> 8) & 0xff) * scale;
                        out.pixelValues[o++] = (c & 0xff) * scale;
                    }
                }
            }
        }
        int idx = 0;
        for (int row = 0; row < nph; row++) {
            for (int col = 0; col < npw; col++) {
                out.positionIds[idx++] = col;
                out.positionIds[idx++] = row;
            }
        }
        int pool2 = p.poolingKernelSize * p.poolingKernelSize;
        out.numSoftTokens = numPatches / pool2;
        return out;
    }
}
