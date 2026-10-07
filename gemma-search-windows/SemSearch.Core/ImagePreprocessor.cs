using System;

namespace SemSearch.Core
{
    /// <summary>Platform image (System.Drawing bitmap in the app, synthetic patterns in tests).</summary>
    public interface IImageSource
    {
        int Width { get; }
        int Height { get; }

        /// <summary>ARGB pixels (row-major) resized to exactly w x h.</summary>
        int[] Argb(int w, int h);
    }

    /// <summary>
    /// Port of transformers.js Gemma4ImageProcessor: aspect-preserving resize to a multiple of
    /// pooling*patch, rescale to [0,1], patchify to [max_patches, patch*patch*3] with [col,row] ids.
    /// </summary>
    public static class ImagePreprocessor
    {
        public sealed class Patches
        {
            public float[] PixelValues;
            public long[] PositionIds;
            public int MaxPatches, PatchDim, NumSoftTokens;
        }

        /// <summary>Exact port of get_aspect_ratio_preserving_size(): returns (height, width).</summary>
        public static (int h, int w) TargetSize(int height, int width, int patchSize, int maxPatches, int pool)
        {
            double targetPx = (double)maxPatches * patchSize * patchSize;
            double factor = Math.Sqrt(targetPx / ((double)height * width));
            int sideMult = pool * patchSize;
            int th = (int)(Math.Floor(factor * height / sideMult) * sideMult);
            int tw = (int)(Math.Floor(factor * width / sideMult) * sideMult);
            if (th == 0 && tw == 0) throw new ArgumentException("Attempting to resize to a 0 x 0 image");
            int maxSide = (int)(Math.Floor((double)maxPatches / (pool * pool)) * sideMult);
            if (th == 0)
            {
                th = sideMult;
                tw = (int)Math.Min(Math.Floor((double)width / height) * sideMult, maxSide);
            }
            else if (tw == 0)
            {
                tw = sideMult;
                th = (int)Math.Min(Math.Floor((double)height / width) * sideMult, maxSide);
            }
            return (th, tw);
        }

        public static Patches Process(IImageSource src, ModelConfig.ImageParams p)
        {
            int h = src.Height, w = src.Width;
            int maxPatches = p.MaxPatches;
            if (p.DoResize) (h, w) = TargetSize(h, w, p.PatchSize, maxPatches, p.PoolingKernelSize);
            int[] px = src.Argb(w, h);
            float scale = p.DoRescale ? (float)p.RescaleFactor : 1f;
            int ps = p.PatchSize, nph = h / ps, npw = w / ps, numPatches = nph * npw;
            if (numPatches > maxPatches) throw new InvalidOperationException("too many patches: " + numPatches);
            int patchDim = ps * ps * 3;
            var o = new Patches
            {
                MaxPatches = maxPatches, PatchDim = patchDim,
                PixelValues = new float[maxPatches * patchDim], PositionIds = new long[maxPatches * 2]
            };
            for (int i = 0; i < o.PositionIds.Length; i++) o.PositionIds[i] = -1;
            int k = 0;
            for (int ph = 0; ph < nph; ph++)
                for (int pw = 0; pw < npw; pw++)
                    for (int dy = 0; dy < ps; dy++)
                    {
                        int row = (ph * ps + dy) * w + pw * ps;
                        for (int dx = 0; dx < ps; dx++)
                        {
                            int c = px[row + dx];
                            o.PixelValues[k++] = ((c >> 16) & 0xff) * scale;
                            o.PixelValues[k++] = ((c >> 8) & 0xff) * scale;
                            o.PixelValues[k++] = (c & 0xff) * scale;
                        }
                    }
            int idx = 0;
            for (int row = 0; row < nph; row++)
                for (int col = 0; col < npw; col++)
                {
                    o.PositionIds[idx++] = col;
                    o.PositionIds[idx++] = row;
                }
            o.NumSoftTokens = numPatches / (p.PoolingKernelSize * p.PoolingKernelSize);
            return o;
        }
    }

    /// <summary>Synthetic test image (smooth colours + checker edges) at any size; used for benchmarks.</summary>
    public sealed class PatternSource : IImageSource
    {
        private readonly int w, h, seed;

        public PatternSource(int w, int h, int seed)
        {
            this.w = w;
            this.h = h;
            this.seed = seed;
        }

        public int Width => w;
        public int Height => h;

        public int[] Argb(int tw, int th)
        {
            var px = new int[tw * th];
            for (int y = 0; y < th; y++)
            {
                double v = (double)y / th;
                for (int x = 0; x < tw; x++)
                {
                    double u = (double)x / tw;
                    int r = (int)(127 + 127 * Math.Sin(6.3 * u + seed));
                    int g = (int)(127 + 127 * Math.Sin(9.1 * v + 2 * seed));
                    int b = ((x / 24 + y / 24 + seed) & 1) == 0 ? 40 : 220;
                    px[y * tw + x] = unchecked((int)0xff000000) | (r << 16) | (g << 8) | b;
                }
            }
            return px;
        }
    }

    public static class VectorMath
    {
        public static void Normalize(float[] v)
        {
            double s = 0;
            foreach (float x in v) s += x * x;
            if (s <= 0) return;
            float inv = (float)(1.0 / Math.Sqrt(s));
            for (int i = 0; i < v.Length; i++) v[i] *= inv;
        }

        public static float PrefixNorm(float[] v, int dims)
        {
            double s = 0;
            for (int i = 0; i < dims; i++) s += v[i] * v[i];
            return (float)Math.Sqrt(s);
        }

        public static float Dot(float[] a, float[] b, int dims)
        {
            double s = 0;
            for (int i = 0; i < dims; i++) s += a[i] * b[i];
            return (float)s;
        }
    }
}
