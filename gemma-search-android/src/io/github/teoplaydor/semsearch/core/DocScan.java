package io.github.teoplaydor.semsearch.core;

import java.util.Arrays;

/**
 * A photo of a document made ready to print, as a scanner would give it. A sheet that is bent (held in the hand,
 * curling) too: its edges are curves, and the sheet is stretched between them ({@link #dewarp}); its lines of text are
 * found and each made straight and level, its left and right margins vertical, the lines evenly spaced where they
 * crowd together (the sheet curling away there) ({@link #straighten}). The sheet found on the photo
 * ({@link #findPage}: the largest bright region, which a sheet on a table is; its corners first where it reaches
 * furthest along the diagonals, then where straight lines fitted to its four edges meet), cut out and straightened
 * ({@link #warp}: the perspective undone, to the sheet's real proportions — {@link #aspect}, from the camera's focal
 * length the corners give away — snapped to A4 or Letter when that close), the text levelled
 * ({@link #skew}: the angle at which the dark pixels fall into the sharpest rows), and the shading and the paper's
 * colour taken away ({@link #scan}: each pixel divided by the light around it — the brightest nearby, smoothed —
 * then strict black and white by a local threshold, or grey, or colour on white paper). Pixels are ARGB ints.
 */
public final class DocScan {
    public static final int BW = 0, GRAY = 1, COLOR = 2;
    /** The largest skew looked for, degrees. */
    public static final double MAX_SKEW = 15;

    private DocScan() {
    }

    /** Pixels with their size. */
    public static final class Image {
        public final int[] px;
        public final int w, h;

        public Image(int[] px, int w, int h) {
            this.px = px;
            this.w = w;
            this.h = h;
        }
    }

    static int luma(int p) {
        return (((p >> 16) & 0xFF) * 77 + ((p >> 8) & 0xFF) * 150 + (p & 0xFF) * 29) >> 8;
    }

    /** Brightness, averaged down so that the longer side is at most {@code max} (box filter); {@code out[0]} the scale. */
    static int[] smallGray(int[] argb, int w, int h, int max, double[] scale, int[] size) {
        int k = Math.max(1, (Math.max(w, h) + max - 1) / max);
        int sw = Math.max(1, w / k), sh = Math.max(1, h / k);
        int[] g = new int[sw * sh];
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int s = 0;
                for (int dy = 0; dy < k; dy++) for (int dx = 0; dx < k; dx++) s += luma(argb[(y * k + dy) * w + x * k + dx]);
                g[y * sw + x] = s / (k * k);
            }
        }
        scale[0] = k;
        size[0] = sw;
        size[1] = sh;
        return g;
    }

    static int otsu(int[] g) {
        int[] hist = new int[256];
        for (int v : g) hist[v]++;
        long total = g.length, sum = 0;
        for (int i = 0; i < 256; i++) sum += (long) i * hist[i];
        long sumB = 0, wB = 0;
        double best = -1;
        int t = 128;
        for (int i = 0; i < 256; i++) {
            wB += hist[i];
            if (wB == 0) continue;
            long wF = total - wB;
            if (wF == 0) break;
            sumB += (long) i * hist[i];
            double mB = (double) sumB / wB, mF = (double) (sum - sumB) / wF;
            double between = (double) wB * wF * (mB - mF) * (mB - mF);
            if (between > best) {
                best = between;
                t = i;
            }
        }
        return t;
    }

    // ------------------------------------------------------------------ the sheet

    /** A sheet on the photo: its corners and its edges as curves (a bent sheet's edges are not straight). */
    public static final class Sheet {
        /** Top left, top right, bottom right, bottom left: x, y pairs, in the photo's pixels. */
        public final float[] corners;
        /** The edges (top, right, bottom, left) as polynomials in the analysis grid (PolyFit), {@code k} photo pixels each. */
        final double[][] edges;
        final double k;

        Sheet(float[] corners, double[][] edges, double k) {
            this.corners = corners;
            this.edges = edges;
            this.k = k;
        }

        /** A point of edge {@code side} (0 top, 1 right, 2 bottom, 3 left) at photo coordinate {@code t} along it. */
        double[] edge(int side, double t) {
            double[] p = edges[side];
            double v = k * polyAt(p, t / k);
            return side % 2 == 0 ? new double[]{t, v} : new double[]{v, t};
        }
    }

    /**
     * The sheet's corners (top left, top right, bottom right, bottom left: x, y pairs, in the image's pixels), or null
     * when there is no sheet apart from its surroundings (it fills the photo, or lies on something as light).
     */
    public static float[] findPage(int[] argb, int w, int h) {
        Sheet s = findSheet(argb, w, h);
        return s == null ? null : s.corners;
    }

    /** The sheet with its edges as curves (see {@link #findPage}), or null. */
    public static Sheet findSheet(int[] argb, int w, int h) {
        double[] scale = new double[1];
        int[] size = new int[2];
        int[] g = smallGray(argb, w, h, 800, scale, size);
        int sw = size[0], sh = size[1];
        int t = otsu(g);
        // the two sides of the threshold must differ: a sheet on a darker table
        long lo = 0, hi = 0, nlo = 0, nhi = 0;
        for (int v : g) {
            if (v > t) {
                hi += v;
                nhi++;
            } else {
                lo += v;
                nlo++;
            }
        }
        if (nlo == 0 || nhi == 0 || (double) hi / nhi - (double) lo / nlo < 40) return null;
        boolean[] light = new boolean[g.length];
        for (int i = 0; i < g.length; i++) light[i] = g[i] > t;
        // paper is smooth: a light patterned cloth next to the sheet, as light as paper in the shade, is not — without
        // this the sheet's edge would run along the cloth's pattern; then thin bridges cut (opened by two pixels)
        boolean[] bright = open(smooth(g, light, sw, sh), sw, sh, 2);
        // the largest bright region (4-connected)
        int[] label = new int[g.length];
        int[] stack = new int[g.length];
        int bestLabel = 0, bestSize = 0, next = 0;
        for (int i = 0; i < g.length; i++) {
            if (!bright[i] || label[i] != 0) continue;
            next++;
            int n = 0, top = 0;
            stack[top++] = i;
            label[i] = next;
            while (top > 0) {
                int p = stack[--top];
                n++;
                int x = p % sw, y = p / sw;
                if (x > 0 && bright[p - 1] && label[p - 1] == 0) {
                    label[p - 1] = next;
                    stack[top++] = p - 1;
                }
                if (x < sw - 1 && bright[p + 1] && label[p + 1] == 0) {
                    label[p + 1] = next;
                    stack[top++] = p + 1;
                }
                if (y > 0 && bright[p - sw] && label[p - sw] == 0) {
                    label[p - sw] = next;
                    stack[top++] = p - sw;
                }
                if (y < sh - 1 && bright[p + sw] && label[p + sw] == 0) {
                    label[p + sw] = next;
                    stack[top++] = p + sw;
                }
            }
            if (n > bestSize) {
                bestSize = n;
                bestLabel = next;
            }
        }
        // a sheet may be small on the photo (a fifth of it, less when the letters take up much of it)
        if (bestSize < g.length / 25) return null;
        // back out to its edge: the smoothness test (a window) and the opening took a few pixels off it
        for (int it = 0; it < 3; it++) {
            java.util.List<Integer> grow = new java.util.ArrayList<Integer>();
            for (int i = 0; i < g.length; i++) {
                if (label[i] == bestLabel || !light[i]) continue;
                int x = i % sw, y = i / sw;
                if (x > 0 && label[i - 1] == bestLabel || x < sw - 1 && label[i + 1] == bestLabel
                        || y > 0 && label[i - sw] == bestLabel || y < sh - 1 && label[i + sw] == bestLabel) grow.add(i);
            }
            for (int i : grow) label[i] = bestLabel;
        }
        // its corners: furthest along the two diagonals
        double minS = 1e9, maxS = -1e9, minD = 1e9, maxD = -1e9;
        int[] c = new int[8];
        int touch = 0;
        boolean left = false, right = false, topB = false, bottom = false;
        for (int i = 0; i < g.length; i++) {
            if (label[i] != bestLabel) continue;
            int x = i % sw, y = i / sw;
            left |= x == 0;
            right |= x == sw - 1;
            topB |= y == 0;
            bottom |= y == sh - 1;
            double s = x + y, d = x - y;
            if (s < minS) {
                minS = s;
                c[0] = x;
                c[1] = y;
            }
            if (d > maxD) {
                maxD = d;
                c[2] = x;
                c[3] = y;
            }
            if (s > maxS) {
                maxS = s;
                c[4] = x;
                c[5] = y;
            }
            if (d < minD) {
                minD = d;
                c[6] = x;
                c[7] = y;
            }
        }
        touch = (left ? 1 : 0) + (right ? 1 : 0) + (topB ? 1 : 0) + (bottom ? 1 : 0);
        if (touch >= 3) return null; // the sheet fills the photo: nothing to cut
        double[] small = new double[8];
        for (int i = 0; i < 8; i++) small[i] = c[i] + 0.5;
        boolean[] out = outside(label, bestLabel, sw, sh);
        small = refine(small, label, bestLabel, out, sw, sh);
        double k = scale[0];
        // the edges as curves through the edge pixels (a bent sheet), a little inside (no sliver of the table along
        // them); the corners where they meet
        java.util.List<double[]>[] sides = sidePoints(small, label, bestLabel, out, sw, sh, 0.1 * Math.min(sw, sh));
        double[][] edges = new double[4][];
        double in = 0.004 * Math.min(w, h) / k + 0.5;
        for (int sd = 0; sd < 4 && edges != null; sd++) {
            // a side mostly along the photo's border (the sheet runs off it): no edge to follow there, a straight one
            // between its corners — a curve through the few edge pixels near them would swing anywhere
            double len = Math.hypot(small[(2 * sd + 2) % 8] - small[2 * sd], small[(2 * sd + 3) % 8] - small[2 * sd + 1]);
            if (sides[sd].size() < 0.5 * len) {
                java.util.List<double[]> chord = new java.util.ArrayList<double[]>();
                for (int i = 0; i <= 10; i++) {
                    chord.add(new double[]{small[2 * sd] + (small[(2 * sd + 2) % 8] - small[2 * sd]) * i / 10,
                            small[2 * sd + 1] + (small[(2 * sd + 3) % 8] - small[2 * sd + 1]) * i / 10});
                }
                sides[sd] = chord;
            }
            edges[sd] = sides[sd].size() >= 11 ? polyFit(sides[sd], sd % 2 == 1, sides[sd].size() >= 60 ? 3 : 1, 1.5) : null;
            if (edges[sd] == null) {
                edges = null;
                break;
            }
            edges[sd][2] += sd == 0 || sd == 3 ? in : -in;
        }
        float[] q = new float[8];
        if (edges != null) {
            double[] meet = meetings(edges, small);
            for (int i = 0; i < 8; i++) q[i] = (float) (meet[i] * k);
        } else {
            for (int i = 0; i < 8; i++) q[i] = (float) (small[i] * k);
            inset(q, 0.004 * Math.min(w, h));
        }
        // a sheet: a convex four-cornered shape the region fills, not a blob
        double area = Math.abs(quadArea(q));
        if (!convex(q) || area < 0.06 * w * h) return null;
        // the region with its holes (the letters) filled — all that the outside does not reach — fills the shape
        int filled = 0;
        for (boolean o : out) if (!o) filled++;
        double regionArea = filled * k * k;
        if (regionArea / area < 0.85 || regionArea / area > 1.25) return null; // bowed edges hold more than the corners
        for (int i = 0; i < 4; i++) {
            double ex = q[(2 * i + 2) % 8] - q[2 * i], ey = q[(2 * i + 3) % 8] - q[2 * i + 1];
            if (Math.hypot(ex, ey) < 0.08 * Math.min(w, h)) return null;
        }
        return new Sheet(q, edges, k);
    }

    /**
     * The light pixels that are smooth: their 5×5 neighbourhood varies (standard deviation) no more than paper does —
     * the light pixels' typical variation (most of them paper) two and a half times over, and a few levels.
     */
    static boolean[] smooth(int[] g, boolean[] light, int w, int h) {
        int n = w * h;
        long[] s1 = new long[(w + 1) * (h + 1)], s2 = new long[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            long r1 = 0, r2 = 0;
            for (int x = 0; x < w; x++) {
                int v = g[y * w + x];
                r1 += v;
                r2 += (long) v * v;
                s1[(y + 1) * (w + 1) + x + 1] = s1[y * (w + 1) + x + 1] + r1;
                s2[(y + 1) * (w + 1) + x + 1] = s2[y * (w + 1) + x + 1] + r2;
            }
        }
        float[] sd = new float[n];
        int[] hist = new int[256];
        int count = 0;
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - 2), y1 = Math.min(h, y + 3);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - 2), x1 = Math.min(w, x + 3);
                int a = y1 * (w + 1) + x1, b = y0 * (w + 1) + x1, c = y1 * (w + 1) + x0, d = y0 * (w + 1) + x0;
                double m = (x1 - x0) * (y1 - y0);
                double mean = (s1[a] - s1[b] - s1[c] + s1[d]) / m, sq = (s2[a] - s2[b] - s2[c] + s2[d]) / m;
                sd[y * w + x] = (float) Math.sqrt(Math.max(0, sq - mean * mean));
                if (light[y * w + x]) {
                    hist[Math.min(255, (int) sd[y * w + x])]++;
                    count++;
                }
            }
        }
        int median = 0;
        for (int acc = 0; median < 255 && (acc += hist[median]) < count / 2; median++) {
        }
        double limit = 2.5 * (median + 0.5) + 3;
        boolean[] out = new boolean[n];
        for (int i = 0; i < n; i++) out[i] = light[i] && sd[i] <= limit;
        return out;
    }

    /** The mask eroded and dilated {@code r} times (3×3 each): bridges and specks up to 2r pixels wide go. */
    static boolean[] open(boolean[] m, int w, int h, int r) {
        boolean[] e = m;
        for (int i = 0; i < r; i++) e = erode(e, w, h);
        for (int i = 0; i < r; i++) e = dilate(e, w, h);
        return e;
    }

    private static boolean[] erode(boolean[] m, int w, int h) {
        boolean[] e = new boolean[m.length];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                boolean all = true;
                for (int dy = -1; dy <= 1 && all; dy++) for (int dx = -1; dx <= 1 && all; dx++) all = m[(y + dy) * w + x + dx];
                e[y * w + x] = all;
            }
        }
        return e;
    }

    private static boolean[] dilate(boolean[] e, int w, int h) {
        boolean[] d = new boolean[e.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean any = false;
                for (int dy = -1; dy <= 1 && !any; dy++) {
                    for (int dx = -1; dx <= 1 && !any; dx++) {
                        int yy = y + dy, xx = x + dx;
                        any = yy >= 0 && xx >= 0 && yy < h && xx < w && e[yy * w + xx];
                    }
                }
                d[y * w + x] = any;
            }
        }
        return d;
    }

    /** Where the edge curves meet, from the corners {@code start}: alternating along the two curves of each corner. */
    static double[] meetings(double[][] edges, double[] start) {
        double[] q = start.clone();
        for (int i = 0; i < 4; i++) {
            // corner i: the side before it (left of top-left, …) and the side after it
            double[] a = edges[(i + 3) % 4], b = edges[i];
            boolean aIsXofY = (i + 3) % 4 % 2 == 1;
            double x = start[2 * i], y = start[2 * i + 1];
            for (int it = 0; it < 30; it++) {
                if (aIsXofY) {
                    x = polyAt(a, y);
                    y = polyAt(b, x);
                } else {
                    y = polyAt(a, x);
                    x = polyAt(b, y);
                }
            }
            if (Double.isNaN(x) || Double.isNaN(y) || Math.hypot(x - start[2 * i], y - start[2 * i + 1]) > 50) continue;
            q[2 * i] = x;
            q[2 * i + 1] = y;
        }
        return q;
    }

    /**
     * A polynomial through the points — y of x, or x of y when {@code xOfY} — of degree {@code deg}: {centre, scale,
     * a0, a1, …} with the variable taken as (t − centre) / scale; least squares, twice without the points further than
     * {@code trim} from it. Null with too few points.
     */
    static double[] polyFit(java.util.List<double[]> pts, boolean xOfY, int deg, double trim) {
        java.util.List<double[]> use = pts;
        double[] p = null;
        for (int pass = 0; pass < 3; pass++) {
            int n = use.size();
            if (n < deg + 4) return p;
            double lo = 1e18, hi = -1e18;
            for (double[] q : use) {
                double t = xOfY ? q[1] : q[0];
                lo = Math.min(lo, t);
                hi = Math.max(hi, t);
            }
            double c = (lo + hi) / 2, sc = Math.max(1e-6, (hi - lo) / 2);
            int m = deg + 1;
            double[][] a = new double[m][m + 1];
            for (double[] q : use) {
                double t = ((xOfY ? q[1] : q[0]) - c) / sc, v = xOfY ? q[0] : q[1];
                double[] pw = new double[m];
                pw[0] = 1;
                for (int j = 1; j < m; j++) pw[j] = pw[j - 1] * t;
                for (int r = 0; r < m; r++) {
                    for (int j = 0; j < m; j++) a[r][j] += pw[r] * pw[j];
                    a[r][m] += pw[r] * v;
                }
            }
            double[] coef = solve(a, m);
            if (coef == null) return p;
            p = new double[m + 2];
            p[0] = c;
            p[1] = sc;
            System.arraycopy(coef, 0, p, 2, m);
            java.util.List<double[]> near = new java.util.ArrayList<double[]>();
            for (double[] q : use) if (Math.abs(polyAt(p, xOfY ? q[1] : q[0]) - (xOfY ? q[0] : q[1])) <= trim) near.add(q);
            if (near.size() == use.size()) break;
            use = near;
        }
        return p;
    }

    static double polyAt(double[] p, double t) {
        double u = (t - p[0]) / p[1], v = 0;
        for (int j = p.length - 1; j >= 2; j--) v = v * u + p[j];
        return v;
    }

    /** Gaussian elimination of an m×(m+1) system; null when singular. */
    static double[] solve(double[][] a, int m) {
        for (int c = 0; c < m; c++) {
            int piv = c;
            for (int r = c + 1; r < m; r++) if (Math.abs(a[r][c]) > Math.abs(a[piv][c])) piv = r;
            if (Math.abs(a[piv][c]) < 1e-12) return null;
            double[] t = a[c];
            a[c] = a[piv];
            a[piv] = t;
            for (int r = 0; r < m; r++) {
                if (r == c) continue;
                double f = a[r][c] / a[c][c];
                for (int j = c; j <= m; j++) a[r][j] -= f * a[c][j];
            }
        }
        double[] x = new double[m];
        for (int i = 0; i < m; i++) x[i] = a[i][m] / a[i][i];
        return x;
    }

    /**
     * The corners where straight lines fitted to the region's four edges meet: the edge pixels (the region's, next to the
     * outside — not to the holes the letters make — and not on the photo's border) go to the side of the first corners
     * they are nearest; a line through each side's (total least squares, twice dropping those further than 1.5 px). A
     * side with too few pixels, or a meeting point far from the first corner, keeps the first corner.
     */
    static double[] refine(double[] q0, int[] label, int region, boolean[] out, int sw, int sh) {
        java.util.List<double[]>[] sides = sidePoints(q0, label, region, out, sw, sh, 0.06 * Math.min(sw, sh));
        double[][] lines = new double[4][];
        for (int s = 0; s < 4; s++) lines[s] = sides[s].size() >= 20 ? fitLine(sides[s]) : null;
        double[] q = q0.clone();
        for (int i = 0; i < 4; i++) {
            double[] a = lines[(i + 3) % 4], b = lines[i]; // corner i joins the side before it and the side after it
            if (a == null || b == null) continue;
            double det = a[0] * b[1] - a[1] * b[0];
            if (Math.abs(det) < 1e-6) continue;
            double x = (a[2] * b[1] - a[1] * b[2]) / det, y = (a[0] * b[2] - a[2] * b[0]) / det;
            if (Math.hypot(x - q0[2 * i], y - q0[2 * i + 1]) > 0.05 * Math.max(sw, sh)) continue;
            q[2 * i] = x;
            q[2 * i + 1] = y;
        }
        return q;
    }

    /**
     * The region's edge pixels next to the outside (not the letters' holes, not on the photo's border), each given to
     * the side of the quad {@code q0} it is nearest (within {@code reach}): top, right, bottom, left.
     */
    @SuppressWarnings("unchecked")
    static java.util.List<double[]>[] sidePoints(double[] q0, int[] label, int region, boolean[] out, int sw, int sh, double reach) {
        java.util.List<double[]>[] sides = new java.util.List[4];
        for (int s = 0; s < 4; s++) sides[s] = new java.util.ArrayList<double[]>();
        for (int y = 1; y < sh - 1; y++) {
            for (int x = 1; x < sw - 1; x++) {
                int p = y * sw + x;
                if (label[p] != region || !(out[p - 1] || out[p + 1] || out[p - sw] || out[p + sw])) continue;
                double px = x + 0.5, py = y + 0.5;
                int bestSide = -1;
                double bestD = reach;
                for (int s = 0; s < 4; s++) {
                    int a = s, b = (s + 1) % 4;
                    double d = segmentDistance(px, py, q0[2 * a], q0[2 * a + 1], q0[2 * b], q0[2 * b + 1]);
                    if (d < bestD) {
                        bestD = d;
                        bestSide = s;
                    }
                }
                if (bestSide >= 0) sides[bestSide].add(new double[]{px, py});
            }
        }
        return sides;
    }

    /** The outside of the region: the pixels not in it that the photo's border reaches through pixels not in it. */
    static boolean[] outside(int[] label, int region, int sw, int sh) {
        int n = sw * sh;
        boolean[] out = new boolean[n];
        int[] stack = new int[n];
        int top = 0;
        for (int x = 0; x < sw; x++) {
            for (int y : new int[]{0, sh - 1}) {
                int i = y * sw + x;
                if (label[i] != region && !out[i]) {
                    out[i] = true;
                    stack[top++] = i;
                }
            }
        }
        for (int y = 0; y < sh; y++) {
            for (int x : new int[]{0, sw - 1}) {
                int i = y * sw + x;
                if (label[i] != region && !out[i]) {
                    out[i] = true;
                    stack[top++] = i;
                }
            }
        }
        while (top > 0) {
            int p = stack[--top], x = p % sw, y = p / sw;
            int[] nb = {x > 0 ? p - 1 : -1, x < sw - 1 ? p + 1 : -1, y > 0 ? p - sw : -1, y < sh - 1 ? p + sw : -1};
            for (int q : nb) {
                if (q >= 0 && !out[q] && label[q] != region) {
                    out[q] = true;
                    stack[top++] = q;
                }
            }
        }
        return out;
    }

    static double segmentDistance(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    /** A line n·p = c (unit n) through the points: total least squares, twice dropping the points 1.5 px off it. */
    static double[] fitLine(java.util.List<double[]> pts) {
        java.util.List<double[]> use = pts;
        double[] line = null;
        for (int pass = 0; pass < 3 && use.size() >= 10; pass++) {
            double mx = 0, my = 0;
            for (double[] p : use) {
                mx += p[0];
                my += p[1];
            }
            mx /= use.size();
            my /= use.size();
            double sxx = 0, syy = 0, sxy = 0;
            for (double[] p : use) {
                double dx = p[0] - mx, dy = p[1] - my;
                sxx += dx * dx;
                syy += dy * dy;
                sxy += dx * dy;
            }
            // the direction of the most spread; the normal across it
            double angle = 0.5 * Math.atan2(2 * sxy, sxx - syy);
            double nx = -Math.sin(angle), ny = Math.cos(angle);
            line = new double[]{nx, ny, nx * mx + ny * my};
            java.util.List<double[]> near = new java.util.ArrayList<double[]>();
            for (double[] p : use) if (Math.abs(nx * p[0] + ny * p[1] - line[2]) <= 1.5) near.add(p);
            if (near.size() == use.size()) break;
            use = near;
        }
        return line;
    }

    /** Each corner moved towards the middle by {@code d} pixels. */
    static void inset(float[] q, double d) {
        double cx = (q[0] + q[2] + q[4] + q[6]) / 4, cy = (q[1] + q[3] + q[5] + q[7]) / 4;
        for (int i = 0; i < 4; i++) {
            double dx = cx - q[2 * i], dy = cy - q[2 * i + 1], len = Math.hypot(dx, dy);
            if (len <= d) continue;
            q[2 * i] += (float) (dx / len * d * Math.sqrt(2));
            q[2 * i + 1] += (float) (dy / len * d * Math.sqrt(2));
        }
    }

    // ------------------------------------------------------------------ the sheet's real proportions

    /** Paper the result snaps to (height / width, portrait): A-series (A4), US Letter, US Legal. */
    static final double[] PAPER = {Math.sqrt(2), 11 / 8.5, 14 / 8.5};

    /**
     * The sheet's real height / width from its corners on a photo (w×h, the principal point at the middle): the
     * camera's focal length from the two vanishing points the corners give, then the rectangle's sides measured with it
     * (Zhang & He, «Whiteboard scanning and image enhancement», 2007). Seen straight on (no vanishing point to go by),
     * the sides as they are. Close to a paper size (either way up; see {@link #snap}), that size.
     */
    public static double aspect(float[] q, int w, int h) {
        double u0 = w / 2.0, v0 = h / 2.0;
        // m1 top left, m2 top right, m3 bottom left, m4 bottom right
        double[] m1 = {q[0] - u0, q[1] - v0, 1}, m2 = {q[2] - u0, q[3] - v0, 1}, m4 = {q[4] - u0, q[5] - v0, 1},
                m3 = {q[6] - u0, q[7] - v0, 1};
        double k2 = dot(cross(m1, m4), m3) / dot(cross(m2, m4), m3), k3 = dot(cross(m1, m4), m2) / dot(cross(m3, m4), m2);
        double[] n2 = {k2 * m2[0] - m1[0], k2 * m2[1] - m1[1], k2 * m2[2] - m1[2]};
        double[] n3 = {k3 * m3[0] - m1[0], k3 * m3[1] - m1[1], k3 * m3[2] - m1[2]};
        double wh;
        double f2 = Math.abs(n2[2] * n3[2]) < 1e-9 ? -1 : -(n2[0] * n3[0] + n2[1] * n3[1]) / (n2[2] * n3[2]);
        double size = Math.max(w, h);
        if (f2 > Math.pow(0.3 * size, 2) && f2 < Math.pow(10 * size, 2)) {
            wh = Math.sqrt((n2[0] * n2[0] + n2[1] * n2[1]) / f2 + n2[2] * n2[2]) / Math.sqrt((n3[0] * n3[0] + n3[1] * n3[1]) / f2 + n3[2] * n3[2]);
        } else {
            // as good as straight on: the sides' own lengths
            double top = Math.hypot(q[2] - q[0], q[3] - q[1]), bottom = Math.hypot(q[4] - q[6], q[5] - q[7]);
            double left = Math.hypot(q[6] - q[0], q[7] - q[1]), right = Math.hypot(q[4] - q[2], q[5] - q[3]);
            wh = (top + bottom) / (left + right);
        }
        return snap(1 / wh);
    }

    /** Whether height / width {@code r} is (close to) a paper size's (see {@link #snap}). */
    static boolean isPaper(double r) {
        if (snap(r) != r) return true;
        for (double p : PAPER) if (r == p || r == 1 / p) return true;
        return false;
    }

    /**
     * Height / width {@code r} as a paper size's when close to it (either way up): A4 within 5% — the paper nearly every
     * sheet here is, and a pile under the sheet or a curl easily puts its corners that far off — the others within
     * 3.5% (Letter's window and A4's do not meet).
     */
    static double snap(double r) {
        for (int i = 0; i < PAPER.length; i++) {
            double p = PAPER[i], tol = i == 0 ? 0.05 : 0.035;
            if (Math.abs(r / p - 1) < tol) return p;
            if (Math.abs(r * p - 1) < tol) return 1 / p;
        }
        return r;
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static double quadArea(float[] q) {
        double a = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            a += q[2 * i] * q[2 * j + 1] - q[2 * j] * q[2 * i + 1];
        }
        return a / 2;
    }

    static boolean convex(float[] q) {
        int sign = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4, k = (i + 2) % 4;
            double cross = (q[2 * j] - q[2 * i]) * (q[2 * k + 1] - q[2 * j + 1]) - (q[2 * j + 1] - q[2 * i + 1]) * (q[2 * k] - q[2 * j]);
            int s = cross > 0 ? 1 : cross < 0 ? -1 : 0;
            if (s == 0) return false;
            if (sign == 0) sign = s;
            else if (s != sign) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ straightening

    /**
     * The homography taking (0,0), (W,0), (W,H), (0,H) to the four corners: 9 numbers, row-major, h[8] = 1.
     */
    static double[] homography(float[] q, double W, double H) {
        double[][] src = {{0, 0}, {W, 0}, {W, H}, {0, H}};
        double[][] a = new double[8][9];
        for (int i = 0; i < 4; i++) {
            double x = src[i][0], y = src[i][1], u = q[2 * i], v = q[2 * i + 1];
            a[2 * i] = new double[]{x, y, 1, 0, 0, 0, -u * x, -u * y, u};
            a[2 * i + 1] = new double[]{0, 0, 0, x, y, 1, -v * x, -v * y, v};
        }
        // Gaussian elimination with partial pivoting
        for (int col = 0; col < 8; col++) {
            int piv = col;
            for (int r = col + 1; r < 8; r++) if (Math.abs(a[r][col]) > Math.abs(a[piv][col])) piv = r;
            double[] tmp = a[col];
            a[col] = a[piv];
            a[piv] = tmp;
            for (int r = 0; r < 8; r++) {
                if (r == col || a[col][col] == 0) continue;
                double f = a[r][col] / a[col][col];
                for (int k = col; k < 9; k++) a[r][k] -= f * a[col][k];
            }
        }
        double[] hm = new double[9];
        for (int i = 0; i < 8; i++) hm[i] = a[i][8] / a[i][i];
        hm[8] = 1;
        return hm;
    }

    /** The least pixels along the sheet's longer side: below, it is enlarged (smooth letter edges in black and white). */
    public static final int MIN_SIDE = 2480;

    /**
     * The sheet cut out and straightened to its real proportions ({@link #aspect}), as many pixels along its longer side
     * as the photo has along that side's longer edge, at least {@link #MIN_SIDE} (enlarged: thresholding at a finer grid
     * gives letters smooth edges instead of steps) and at most {@code maxSide}; bilinear.
     */
    public static Image warp(int[] argb, int w, int h, float[] q, int maxSide) {
        int[] size = outputSize(argb, w, h, q, maxSide);
        int ow = size[0], oh = size[1];
        double[] m = homography(q, ow, oh);
        int[] out = new int[ow * oh];
        for (int y = 0; y < oh; y++) {
            for (int x = 0; x < ow; x++) {
                double cx = x + 0.5, cy = y + 0.5;
                double z = m[6] * cx + m[7] * cy + m[8];
                double sx = (m[0] * cx + m[1] * cy + m[2]) / z - 0.5, sy = (m[3] * cx + m[4] * cy + m[5]) / z - 0.5;
                out[y * ow + x] = sample(argb, w, h, sx, sy, 0xFFFFFFFF);
            }
        }
        return new Image(out, ow, oh);
    }

    /**
     * A bent sheet cut out and flattened: the perspective of its corners undone (as {@link #warp}), and what is left of
     * its edges' bending — the curves, carried into the straightened frame, still bowed — taken out by stretching the
     * sheet between them (a Coons patch: each point moved by its edges' bending, more by the nearer one). A sheet
     * with no curves (corners set by hand) is {@link #warp}ed.
     */
    public static Image dewarp(int[] argb, int w, int h, Sheet sheet, int maxSide) {
        float[] q = sheet.corners;
        if (sheet.edges == null) return warp(argb, w, h, q, maxSide);
        int[] size = outputSize(argb, w, h, q, maxSide);
        int ow = size[0], oh = size[1];
        double[] m = homography(q, ow, oh), inv = invert(m);
        // each edge in the straightened frame: how far it bows from the frame's side, along it
        double[] top = new double[ow], bottom = new double[ow], left = new double[oh], right = new double[oh];
        bowing(sheet, 0, q[0], q[2], inv, ow, oh, top);
        bowing(sheet, 2, q[6], q[4], inv, ow, oh, bottom);
        bowing(sheet, 3, q[1], q[7], inv, ow, oh, left);
        bowing(sheet, 1, q[3], q[5], inv, ow, oh, right);
        int[] out = new int[ow * oh];
        for (int y = 0; y < oh; y++) {
            double v = (y + 0.5) / oh;
            for (int x = 0; x < ow; x++) {
                double u = (x + 0.5) / ow;
                double cx = x + 0.5 + (1 - u) * left[y] + u * right[y], cy = y + 0.5 + (1 - v) * top[x] + v * bottom[x];
                double z = m[6] * cx + m[7] * cy + m[8];
                double sx = (m[0] * cx + m[1] * cy + m[2]) / z - 0.5, sy = (m[3] * cx + m[4] * cy + m[5]) / z - 0.5;
                out[y * ow + x] = sample(argb, w, h, sx, sy, 0xFFFFFFFF);
            }
        }
        return new Image(out, ow, oh);
    }

    /**
     * Edge {@code side} of the sheet (from photo coordinate {@code from} to {@code to} along it) in the straightened
     * frame: its distance from the frame's side at each pixel along it (0 at the corners), into {@code dev}.
     */
    static void bowing(Sheet sheet, int side, double from, double to, double[] inv, int ow, int oh, double[] dev) {
        int n = 96;
        double[] along = new double[n + 1], off = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            double[] pt = sheet.edge(side, from + (to - from) * i / n);
            double z = inv[6] * pt[0] + inv[7] * pt[1] + inv[8];
            double rx = (inv[0] * pt[0] + inv[1] * pt[1] + inv[2]) / z, ry = (inv[3] * pt[0] + inv[4] * pt[1] + inv[5]) / z;
            if (side % 2 == 0) {
                along[i] = rx;
                off[i] = side == 0 ? ry : ry - oh;
            } else {
                along[i] = ry;
                off[i] = side == 3 ? rx : rx - ow;
            }
        }
        // no more than the bending at the corners (0 there by construction; the fit's rounding taken out)
        double o0 = off[0], o1 = off[n];
        for (int i = 0; i <= n; i++) off[i] -= o0 + (o1 - o0) * i / n;
        for (int j = 0; j < dev.length; j++) {
            double t = j + 0.5;
            int k = 0;
            while (k < n - 1 && along[k + 1] < t) k++;
            double a0 = along[k], a1 = along[k + 1];
            double f = a1 == a0 ? 0 : Math.max(0, Math.min(1, (t - a0) / (a1 - a0)));
            dev[j] = off[k] + f * (off[k + 1] - off[k]);
        }
    }

    static double[] invert(double[] m) {
        double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], hh = m[7], i = m[8];
        double A = e * i - f * hh, B = -(d * i - f * g), C = d * hh - e * g;
        double det = a * A + b * B + c * C;
        return new double[]{A / det, -(b * i - c * hh) / det, (b * f - c * e) / det, B / det, (a * i - c * g) / det,
                -(a * f - c * d) / det, C / det, -(a * hh - b * g) / det, (a * e - b * d) / det};
    }

    /** The cut-out sheet's size: its real proportions, the photo's pixels along it, within MIN_SIDE…maxSide. */
    static int[] outputSize(int[] argb, int w, int h, float[] q, int maxSide) {
        double top = Math.hypot(q[2] - q[0], q[3] - q[1]), bottom = Math.hypot(q[4] - q[6], q[5] - q[7]);
        double leftS = Math.hypot(q[6] - q[0], q[7] - q[1]), rightS = Math.hypot(q[4] - q[2], q[5] - q[3]);
        double ratio = aspect(q, w, h); // height / width
        double W, H;
        if (ratio >= 1) {
            H = Math.min(maxSide, Math.max(Math.max(leftS, rightS), Math.max(top, bottom) * ratio));
            W = H / ratio;
        } else {
            W = Math.min(maxSide, Math.max(Math.max(top, bottom), Math.max(leftS, rightS) / ratio));
            H = W * ratio;
        }
        double grow = Math.max(W, H) < Math.min(MIN_SIDE, maxSide) ? Math.min(MIN_SIDE, maxSide) / Math.max(W, H) : 1;
        return new int[]{Math.max(1, (int) Math.round(W * grow)), Math.max(1, (int) Math.round(H * grow))};
    }

    // ------------------------------------------------------------------ the lines of text

    /**
     * The page with its lines straightened, and what was done: lines found, their largest bend (pixels), whether the
     * text's vertical edges were stood upright ({@code margins}), how
     * far the closest lines were spread out to space them evenly (the widest spacing over the narrowest; 1: not at all).
     */
    public static final class Straight {
        public final Image image;
        public final int lines;
        public final double bend;
        public final boolean margins;
        public final double spread;

        Straight(Image image, int lines, double bend, boolean margins, double spread) {
            this.image = image;
            this.lines = lines;
            this.bend = bend;
            this.margins = margins;
            this.spread = spread;
        }
    }

    /** A smooth surface over the page (see {@link #bend}): uniform cubic B-splines, nx across and ny down. */
    private static final class Bend {
        int nx, ny;
        double x0, x1, y0, y1;
        double[] c;

        /** The 16 controls at (x, y) and their weights. */
        void weights(double x, double y, int[] idx, double[] w) {
            double hx = (x1 - x0) / (nx - 3), hy = (y1 - y0) / (ny - 3);
            double u = Math.max(0, Math.min(nx - 3, (x - x0) / hx)), v = Math.max(0, Math.min(ny - 3, (y - y0) / hy));
            int i = Math.min(nx - 4, (int) u), j = Math.min(ny - 4, (int) v);
            double[] bu = cubic(u - i), bv = cubic(v - j);
            int k = 0;
            for (int b = 0; b < 4; b++) {
                for (int a = 0; a < 4; a++) {
                    idx[k] = (j + b) * nx + i + a;
                    w[k++] = bu[a] * bv[b];
                }
            }
        }

        double at(double x, double y) {
            int[] idx = new int[16];
            double[] w = new double[16];
            weights(x, y, idx, w);
            double s = 0;
            for (int k = 0; k < 16; k++) s += c[idx[k]] * w[k];
            return s;
        }

        private static double[] cubic(double t) {
            double t2 = t * t, t3 = t2 * t;
            return new double[]{(1 - t) * (1 - t) * (1 - t) / 6, (3 * t3 - 6 * t2 + 4) / 6, (-3 * t3 + 3 * t2 + 3 * t + 1) / 6, t3 / 6};
        }
    }

    /**
     * The sideways shift that stands the text's vertical edges upright (see {@link #upright}), on a grid over the page
     * (analysis scale), bilinear between its nodes.
     */
    private static final class Upright {
        int gw, gh;
        double cell;
        float[] d;

        double at(double x, double y) {
            double gx = Math.max(0, Math.min(gw - 1.001, x / cell)), gy = Math.max(0, Math.min(gh - 1.001, y / cell));
            int i = (int) gx, j = (int) gy;
            double fx = gx - i, fy = gy - j;
            double a = d[j * gw + i], b = d[j * gw + i + 1], c = d[(j + 1) * gw + i], e = d[(j + 1) * gw + i + 1];
            return (a + fx * (b - a)) * (1 - fy) + (c + fx * (e - c)) * fy;
        }
    }

    /**
     * How far apart the lines are down the page, relative: its logarithm a polynomial of the row (its constant
     * arbitrary), kept to lo…hi; the top of it; the widest spacing over the narrowest.
     */
    private static final class Spacing {
        double[] g;
        double lo, hi, top, spread;

        /** How much row t is spread out: the widest spacing over the spacing there. */
        double stretch(double t) {
            return Math.exp(top - polyAt(g, Math.max(lo, Math.min(hi, t))));
        }
    }

    /**
     * One line of text found: its middle y as a polynomial of x, where it starts and ends, its row once level, its
     * letters' height (the median; NaN when too few were seen).
     */
    private static final class Line {
        double[] mid;
        double start, end, row, height = Double.NaN;
        /** The middle of its own ink, column by column. */
        java.util.List<double[]> pts;
        /** A rule (a form's line, an underline), not letters. */
        boolean rule;
    }

    /**
     * The lines of text made straight and level, the margins vertical: the lines are found (the ink, black and white,
     * letters joined along a row), a polynomial through each line's middle; each column of the page is then moved up or
     * down so that every line lies on one row (between lines, as the lines around say: a polynomial over the page's
     * height, per column), and each point moved sideways so that the lines' starts (and ends, when the text is
     * justified) stand one above the other, the text's vertical edges upright (see {@link #upright}); where the lines come closer together than elsewhere (the sheet curling
     * or leaning away there), the rows are spread out to the widest spacing (see {@link #spacing}), the page growing
     * taller. Fewer than six lines, or a bend of more than a twenty-fifth of the page with fewer than twenty (a few
     * long strokes bent by far: a table's rules on a page turned sideways): the page as it is.
     */
    public static Straight straighten(Image im) {
        int W = im.w, H = im.h;
        double k = Math.min(1.0, 1600.0 / Math.max(W, H));
        int aw = Math.max(1, (int) Math.round(W * k)), ah = Math.max(1, (int) Math.round(H * k));
        int[] a = k < 1 ? FaceModel.resize(im.px, W, H, aw, ah) : im.px;
        int[] bw = scan(a, aw, ah, BW);
        int n = aw * ah;
        boolean[] ink = new boolean[n];
        for (int i = 0; i < n; i++) ink[i] = (bw[i] & 0xFF) == 0;
        // the letters' height: the median of the small ink blobs
        int[] lab = new int[n];
        int[] stack = new int[n];
        java.util.List<Integer> heights = new java.util.ArrayList<Integer>();
        java.util.List<double[]> letters = new java.util.ArrayList<double[]>(); // middle x, middle y, height
        int next = 0;
        for (int i = 0; i < n; i++) {
            if (!ink[i] || lab[i] != 0) continue;
            next++;
            int top = 0, y0 = i / aw, y1 = y0, x0 = i % aw, x1 = x0, area = 0;
            stack[top++] = i;
            lab[i] = next;
            while (top > 0) {
                int p = stack[--top], x = p % aw, y = p / aw;
                area++;
                y0 = Math.min(y0, y);
                y1 = Math.max(y1, y);
                x0 = Math.min(x0, x);
                x1 = Math.max(x1, x);
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx, yy = y + dy;
                        if (xx < 0 || yy < 0 || xx >= aw || yy >= ah) continue;
                        int q = yy * aw + xx;
                        if (ink[q] && lab[q] == 0) {
                            lab[q] = next;
                            stack[top++] = q;
                        }
                    }
                }
            }
            int bh = y1 - y0 + 1;
            if (area >= 3 && bh >= 2 && bh <= ah / 15 && x1 - x0 + 1 <= aw / 10) {
                heights.add(bh);
                letters.add(new double[]{(x0 + x1 + 1) / 2.0, (y0 + y1 + 1) / 2.0, bh});
            }
        }
        Straight none = new Straight(im, 0, 0, false, 1);
        if (heights.size() < 20) return none;
        java.util.Collections.sort(heights);
        double charH = heights.get(heights.size() / 2);
        int step = (int) Math.max(1, Math.round(charH / 2));
        java.util.List<Line> lines = trackLines(ink, aw, ah, charH, step);
        joinPieces(lines, charH, aw, step);
        // a page's text: enough lines (a few long strokes — a table's rules, a photo's edges — are not to be bent by)
        if (lines.size() < 6) return none;
        java.util.Collections.sort(lines, new java.util.Comparator<Line>() {
            @Override
            public int compare(Line x, Line y) {
                return Double.compare(x.row, y.row);
            }
        });
        // each line's letters: the blobs whose middles are on it
        for (Line l : lines) {
            java.util.List<Double> hs = new java.util.ArrayList<Double>();
            for (double[] b : letters) {
                if (b[0] >= l.start && b[0] <= l.end && Math.abs(b[1] - polyAt(l.mid, b[0])) <= 0.6 * charH) hs.add(b[2]);
            }
            if (hs.size() >= 4) {
                java.util.Collections.sort(hs);
                l.height = hs.get(hs.size() / 2);
            }
        }
        // how far each point is from where it goes, as a smooth surface through the lines (the largest of it: the
        // bend; too large for so few lines, not to be trusted)
        int G = 64;
        Bend surface = bend(lines, aw, charH, lines.get(0).row, lines.get(lines.size() - 1).row);
        if (surface == null) return none;
        double bend = 0;
        for (int j = 0; j < G; j++) {
            for (int i = 0; i <= 40; i++) {
                double t = lines.get(0).row + (lines.get(lines.size() - 1).row - lines.get(0).row) * i / 40;
                bend = Math.max(bend, Math.abs(surface.at((j + 0.5) * aw / G, t)));
            }
        }
        if (bend > ah / 25.0 && lines.size() < 20) return new Straight(im, 0, 0, false, 1);
        double tFirst = lines.get(0).row, tLast = lines.get(lines.size() - 1).row;
        // the text's vertical edges: lines starting (ending) one under another, where the sheet leaned or curled
        Upright up = upright(lines, aw, ah, charH, (tFirst + tLast) / 2);
        boolean margins = up != null;
        // the rows spread out: the height above each row (analysis scale) once the lines are evenly spaced
        Spacing spc = spacing(lines, charH, ah);
        int H2 = H;
        double[] U = null;
        if (spc != null) {
            U = new double[ah + 1];
            for (int r = 0; r < ah; r++) U[r + 1] = U[r] + spc.stretch(r + 0.5);
            // the height: spread to the widest spacing when that makes a paper size (the bottom curled away: the
            // corners gave too short a sheet); else the height the cut gave, when that is one (parts of the sheet
            // nearer the camera than its corners look wider spaced: spreading to them overshoots) — the rows
            // spread out within it; else as spread
            double grown = U[ah] / k / W, cut = (double) H / W;
            H2 = (int) Math.round(W * (isPaper(grown) ? snap(grown) : isPaper(cut) ? snap(cut) : grown));
        }
        // straight, upright and evenly spaced already: the page as it is (not resampled for nothing)
        if (bend / k < 1.5 && !margins && U == null) return new Straight(im, lines.size(), bend / k, false, 1);
        int[] out = new int[W * H2];
        double[] sh = new double[G];
        int r = 0;
        for (int y = 0; y < H2; y++) {
            double Ya;
            if (U == null) {
                Ya = (y + 0.5) * k;
            } else {
                double Ua = (y + 0.5) / H2 * U[ah];
                while (r < ah - 1 && U[r + 1] <= Ua) r++;
                Ya = r + (Ua - U[r]) / (U[r + 1] - U[r]);
            }
            double t = Math.max(tFirst, Math.min(tLast, Ya));
            for (int j = 0; j < G; j++) sh[j] = surface.at((j + 0.5) * aw / G, t);
            for (int x = 0; x < W; x++) {
                double Xa = (x + 0.5) * k;
                double xs = up == null ? Xa : Xa + up.at(Xa, t);
                double gj = xs / aw * G - 0.5;
                int j0 = (int) Math.floor(gj);
                double f = gj - j0;
                double s0 = sh[Math.max(0, Math.min(G - 1, j0))], s1 = sh[Math.max(0, Math.min(G - 1, j0 + 1))];
                double ys = Ya + s0 + f * (s1 - s0);
                out[y * W + x] = sampleEdge(im.px, W, H, xs / k - 0.5, ys / k - 0.5);
            }
        }
        return new Straight(new Image(out, W, H2), lines.size(), bend / k, margins, spc == null ? 1 : spc.spread);
    }

    /**
     * How far apart the lines are down the page, when that drifts smoothly — the lines coming closer together where the
     * sheet curls or leans away. The distances between neighbouring lines are taken in runs that keep one distance and
     * one letter size (a list, a paragraph; a heading, a blank line or bigger letters start a new run), and a drift
     * shared by all of them is fitted — the logarithm of the distance a polynomial of the row, each run with its own
     * spacing on top — so that a form whose lists are set further apart than its paragraphs, or a heading's wider
     * spacing, is no drift at all; only the distances changing within the runs are. Null when they hardly change
     * (under 5%), change implausibly much (over 35%: something else is wrong), when there are too few, or the drift
     * misses them.
     */
    static Spacing spacing(java.util.List<Line> lines, double charH, int ah) {
        java.util.List<java.util.List<double[]>> runs = new java.util.ArrayList<java.util.List<double[]>>();
        java.util.List<double[]> run = null;
        double last = 0;
        for (int i = 1; i < lines.size(); i++) {
            Line a = lines.get(i - 1), b = lines.get(i);
            double d = b.row - a.row;
            boolean sameLetters = Double.isNaN(a.height) || Double.isNaN(b.height)
                    || Math.abs(a.height - b.height) <= 0.25 * Math.max(a.height, b.height);
            if (d < 1.2 * charH || d > 4 * charH || !sameLetters) {
                run = null;
                continue;
            }
            if (run == null || d < last / 1.15 || d > last * 1.15) {
                run = new java.util.ArrayList<double[]>();
                runs.add(run);
            }
            run.add(new double[]{(a.row + b.row) / 2, Math.log(d)});
            last = d;
        }
        int n0 = 0;
        double lo = 1e18, hi = -1e18, longest = 0;
        for (java.util.List<double[]> r : runs) {
            if (r.size() < 3) continue;
            n0 += r.size();
            lo = Math.min(lo, r.get(0)[0]);
            hi = Math.max(hi, r.get(r.size() - 1)[0]);
            longest = Math.max(longest, r.get(r.size() - 1)[0] - r.get(0)[0]);
        }
        if (n0 < 8 || hi - lo < 0.4 * ah) return null;
        double c = (lo + hi) / 2, sc = (hi - lo) / 2;
        int deg = n0 >= 12 && longest >= 0.3 * ah ? 2 : 1;
        double[] g = null;
        int kept = 0;
        for (int pass = 0; pass < 3; pass++) {
            // each run's own means taken out, what is left fitted by the polynomial's terms (no constant)
            double[][] m = new double[deg][deg + 1];
            for (java.util.List<double[]> r : runs) {
                if (r.size() < 3) continue;
                double[] mb = new double[deg + 1];
                for (double[] q : r) {
                    double u = (q[0] - c) / sc, pw = 1;
                    for (int j = 0; j < deg; j++) mb[j] += (pw *= u) / r.size();
                    mb[deg] += q[1] / r.size();
                }
                for (double[] q : r) {
                    double u = (q[0] - c) / sc, pw = 1;
                    double[] v = new double[deg];
                    for (int j = 0; j < deg; j++) v[j] = (pw *= u) - mb[j];
                    for (int j = 0; j < deg; j++) {
                        for (int k = 0; k < deg; k++) m[j][k] += v[j] * v[k];
                        m[j][deg] += v[j] * (q[1] - mb[deg]);
                    }
                }
            }
            double[] coef = solve(m, deg);
            if (coef == null) return null;
            g = new double[deg + 3];
            g[0] = c;
            g[1] = sc;
            System.arraycopy(coef, 0, g, 3, deg);
            // the distances the drift misses by more than 5% left out, and runs left too short
            kept = 0;
            boolean dropped = false;
            for (int ri = 0; ri < runs.size(); ri++) {
                java.util.List<double[]> r = runs.get(ri);
                if (r.size() < 3) continue;
                double off = 0;
                for (double[] q : r) off += (q[1] - polyAt(g, q[0])) / r.size();
                java.util.List<double[]> near = new java.util.ArrayList<double[]>();
                for (double[] q : r) if (Math.abs(q[1] - polyAt(g, q[0]) - off) <= 0.05) near.add(q);
                if (near.size() != r.size()) dropped = true;
                runs.set(ri, near);
                if (near.size() >= 3) kept += near.size();
            }
            if (!dropped) break;
        }
        if (kept < 0.75 * n0) return null;
        double gMin = 1e18, gMax = -1e18;
        for (int i = 0; i <= 32; i++) {
            double v = polyAt(g, lo + (hi - lo) * i / 32);
            gMin = Math.min(gMin, v);
            gMax = Math.max(gMax, v);
        }
        double spread = Math.exp(gMax - gMin);
        if (spread < 1.05 || spread > 1.35) return null;
        Spacing sp = new Spacing();
        sp.g = g;
        sp.lo = lo;
        sp.hi = hi;
        sp.top = gMax;
        sp.spread = spread;
        return sp;
    }

    /**
     * The lines of text, followed across the page: the page cut into upright strips three letters wide; in each, the
     * ink counted row by row (smoothed over a third of a letter), and a line's middle wherever that count peaks — the most
     * within half a letter either way, enough ink, and dropping to under six tenths of it within a letter and a bit
     * on both sides (a valley: lines set close, their letters touching, still part there), a rule (a form's line, an
     * underline: the rows with at least half the most ink there no more than 0.45 of a letter's height, and holding a
     * row's worth of ink across the strip — a slanting rule spreads over a few rows) told from letters; then the peaks of each strip
     * joined to the lines of their kind coming from the left — the nearest within three tenths of a letter (and more the
     * further it is carried, up to half) of where a line, carried on at its slope, gets to (two strips may be skipped: a gap between words, a field left blank; a
     * line of letters does not go on along the rule under it). A line: four peaks or
     * more over an eighth of the page's width; its middle a polynomial through them, its start and end where its ink
     * begins and ends (followed out from its first and last peaks, across gaps no wider than between words).
     */
    static java.util.List<Line> trackLines(boolean[] ink, int aw, int ah, double charH, double step) {
        int sw = (int) Math.max(8, Math.round(3 * charH)), ns = (aw + sw - 1) / sw;
        int r = (int) Math.max(1, Math.round(0.15 * charH)), half = (int) Math.max(1, Math.round(0.5 * charH));
        int deep = (int) Math.max(2, Math.round(1.2 * charH));
        java.util.List<java.util.List<double[]>> chains = new java.util.ArrayList<java.util.List<double[]>>();
        java.util.List<Integer> lastStrip = new java.util.ArrayList<Integer>();
        java.util.List<Boolean> chainRule = new java.util.ArrayList<Boolean>();
        int[] prof = new int[ah];
        double[] sm = new double[ah];
        for (int s = 0; s < ns; s++) {
            int x0 = s * sw, x1 = Math.min(aw, x0 + sw);
            for (int y = 0; y < ah; y++) {
                int c = 0;
                for (int x = x0; x < x1; x++) if (ink[y * aw + x]) c++;
                prof[y] = c;
            }
            for (int y = 0; y < ah; y++) {
                double t = 0;
                for (int d = -r; d <= r; d++) {
                    int yy = y + d;
                    if (yy >= 0 && yy < ah) t += prof[yy];
                }
                sm[y] = t / (2 * r + 1);
            }
            double minInk = 0.06 * (x1 - x0);
            java.util.List<Double> peaks = new java.util.ArrayList<Double>();
            java.util.List<Boolean> rule = new java.util.ArrayList<Boolean>();
            java.util.List<double[]> cand = new java.util.ArrayList<double[]>();
            for (int y = 1; y < ah - 1; y++) {
                double v = sm[y];
                if (v < minInk) continue;
                boolean top = true;
                for (int d = -half; d <= half && top; d++) {
                    int yy = y + d;
                    if (d != 0 && yy >= 0 && yy < ah && (sm[yy] > v || sm[yy] == v && d < 0)) top = false;
                }
                if (!top) continue;
                double lowUp = v, lowDown = v;
                for (int d = 1; d <= deep; d++) {
                    if (y - d >= 0) lowUp = Math.min(lowUp, sm[y - d]);
                    if (y + d < ah) lowDown = Math.min(lowDown, sm[y + d]);
                }
                if (lowUp > 0.6 * v || lowDown > 0.6 * v) continue;
                // a rule (a form's line, an underline) or letters: how many rows hold half the most ink there
                int most = 0, at = y;
                for (int d = -r; d <= r; d++) {
                    if (y + d >= 0 && y + d < ah && prof[y + d] > most) {
                        most = prof[y + d];
                        at = y + d;
                    }
                }
                int lo = at, hi = at, sum = 0;
                while (lo > 0 && prof[lo - 1] * 2 >= most) lo--;
                while (hi < ah - 1 && prof[hi + 1] * 2 >= most) hi++;
                for (int yy = lo; yy <= hi; yy++) sum += prof[yy];
                boolean isRule = hi - lo + 1 <= Math.max(3, 0.45 * charH) && sum >= 0.9 * (x1 - x0);
                // its place between the rows: a rule's, a parabola through the peak and its neighbours; letters', the
                // middle of the rows about as full (their count is flat across a line's middle)
                double pos;
                if (isRule) {
                    double a = sm[y - 1], c = sm[y + 1], den = a - 2 * v + c;
                    pos = y + 0.5 + (den < 0 ? 0.5 * (a - c) / den : 0);
                } else {
                    int pl = y, ph = y;
                    while (pl > 0 && sm[pl - 1] >= 0.8 * v) pl--;
                    while (ph < ah - 1 && sm[ph + 1] >= 0.8 * v) ph++;
                    pos = (pl + ph + 1) / 2.0;
                }
                cand.add(new double[]{pos, v, isRule ? 1 : 0});
            }
            // one peak to a line: the strongest of those of a kind closer than 0.8 of a letter (a rule's, 0.3)
            java.util.Collections.sort(cand, new java.util.Comparator<double[]>() {
                @Override
                public int compare(double[] p, double[] q) {
                    return Double.compare(q[1], p[1]);
                }
            });
            java.util.List<double[]> kept = new java.util.ArrayList<double[]>();
            for (double[] c : cand) {
                boolean free = true;
                for (double[] k : kept) {
                    if (k[2] == c[2] && Math.abs(k[0] - c[0]) < (c[2] == 1 ? 0.3 : 0.8) * charH) free = false;
                }
                if (free) kept.add(c);
            }
            java.util.Collections.sort(kept, new java.util.Comparator<double[]>() {
                @Override
                public int compare(double[] p, double[] q) {
                    return Double.compare(p[0], q[0]);
                }
            });
            for (double[] k : kept) {
                peaks.add(k[0]);
                rule.add(k[2] == 1);
            }
            double cx = (x0 + x1) / 2.0;
            // the peaks joined to the lines coming from the left: nearest first, each once
            java.util.List<double[]> pairs = new java.util.ArrayList<double[]>();
            for (int ci = 0; ci < chains.size(); ci++) {
                if (s - lastStrip.get(ci) > 3) continue;
                java.util.List<double[]> ch = chains.get(ci);
                double[] last = ch.get(ch.size() - 1);
                double slope = 0;
                if (ch.size() >= 2) {
                    double[] prev = ch.get(Math.max(0, ch.size() - 3));
                    slope = (last[1] - prev[1]) / Math.max(1, last[0] - prev[0]);
                }
                double pred = last[1] + slope * (cx - last[0]);
                for (int pi = 0; pi < peaks.size(); pi++) {
                    // letters go on with letters, a rule with a rule (one runs just under the other in a form)
                    if (rule.get(pi) != chainRule.get(ci)) continue;
                    double d = Math.abs(peaks.get(pi) - pred);
                    // further to carry it, less sure where it gets to
                    if (d <= Math.min(0.5 * charH, 0.3 * charH + 0.02 * (cx - last[0]))) pairs.add(new double[]{d, ci, pi});
                }
            }
            java.util.Collections.sort(pairs, new java.util.Comparator<double[]>() {
                @Override
                public int compare(double[] p, double[] q) {
                    return Double.compare(p[0], q[0]);
                }
            });
            boolean[] chainUsed = new boolean[chains.size()], peakUsed = new boolean[peaks.size()];
            for (double[] p : pairs) {
                int ci = (int) p[1], pi = (int) p[2];
                if (chainUsed[ci] || peakUsed[pi]) continue;
                chainUsed[ci] = peakUsed[pi] = true;
                chains.get(ci).add(new double[]{cx, peaks.get(pi)});
                lastStrip.set(ci, s);
            }
            for (int pi = 0; pi < peaks.size(); pi++) {
                if (peakUsed[pi]) continue;
                java.util.List<double[]> ch = new java.util.ArrayList<double[]>();
                ch.add(new double[]{cx, peaks.get(pi)});
                chains.add(ch);
                lastStrip.add(s);
                chainRule.add(rule.get(pi));
            }
        }
        java.util.List<Line> lines = new java.util.ArrayList<Line>();
        for (int ci = 0; ci < chains.size(); ci++) {
            java.util.List<double[]> ch = chains.get(ci);
            if (ch.size() < 4 || ch.get(ch.size() - 1)[0] - ch.get(0)[0] < 0.125 * aw) continue;
            double[] mid = polyFit(ch, false, ch.size() >= 8 ? 3 : ch.size() >= 5 ? 2 : 1, 0.4 * charH);
            if (mid == null) continue;
            Line l = new Line();
            l.mid = mid;
            l.pts = ch;
            l.rule = chainRule.get(ci);
            // where its ink begins and ends: from its first (last) peak outwards along it, as far as there is ink near
            // its middle with gaps no wider than between words
            l.start = ch.get(0)[0];
            l.end = ch.get(ch.size() - 1)[0];
            int first = edgeOf(ink, aw, ah, l, (int) l.start, -1, charH), last = edgeOf(ink, aw, ah, l, (int) l.end, 1, charH);
            l.start = first;
            l.end = last + 1;
            l.row = rowOf(l, step);
            lines.add(l);
        }
        return lines;
    }

    /** From column {@code from} along the line in direction {@code dir}: the last column with ink near its middle. */
    private static int edgeOf(boolean[] ink, int aw, int ah, Line l, int from, int dir, double charH) {
        int edge = from, gap = 0, maxGap = (int) Math.round(1.6 * charH);
        for (int x = from; x >= 0 && x < aw; x += dir) {
            if (inkNear(ink, aw, ah, x, carried(l, x, aw), 0.5 * charH)) {
                edge = x;
                gap = 0;
            } else if (++gap > maxGap) {
                break;
            }
        }
        return edge;
    }

    private static boolean inkNear(boolean[] ink, int aw, int ah, int x, double y, double band) {
        int y0 = (int) Math.max(0, Math.floor(y - band)), y1 = (int) Math.min(ah - 1, Math.ceil(y + band));
        for (int yy = y0; yy <= y1; yy++) if (ink[yy * aw + x]) return true;
        return false;
    }

    /**
     * How far down (up) each point of the page is to be moved for the lines to lie level: a smooth surface — cubic
     * B-splines, ten across the page and one per four lines down it (from five to fourteen) — through the lines'
     * traces, each line its own row on top (its trace less the surface: one height all along). Least squares, with
     * the surface's bending kept small (second differences of its controls: where there are no lines it stays calm,
     * between them it cannot fold) and its controls themselves a little (a shift shared by a whole row is left to the
     * rows). Three times, each without the trace points it misses by more than three tenths of a letter and the lines
     * that lose half of theirs: a trace that slipped onto the next line, a rule taken for letters, does not bend the
     * page.
     */
    static Bend bend(java.util.List<Line> lines, int aw, double charH, double top, double bottom) {
        Bend b = new Bend();
        b.nx = 10;
        b.ny = (int) Math.max(5, Math.min(14, Math.round(lines.size() / 4.0) + 3));
        b.x0 = 0;
        b.x1 = aw;
        b.y0 = top;
        b.y1 = Math.max(top + 1, bottom);
        int m = b.nx * b.ny;
        java.util.List<java.util.List<double[]>> use = new java.util.ArrayList<java.util.List<double[]>>();
        java.util.List<Double> rows = new java.util.ArrayList<Double>();
        for (Line l : lines) {
            if (l.pts.size() < 3) continue;
            use.add(new java.util.ArrayList<double[]>(l.pts));
            rows.add(l.row);
        }
        double miss = 0.3 * charH;
        int[] idx = new int[16];
        double[] w = new double[16];
        for (int pass = 0; pass < 3; pass++) {
            double[][] a = new double[m][m + 1];
            int count = 0;
            for (int li = 0; li < use.size(); li++) {
                java.util.List<double[]> pts = use.get(li);
                if (pts.size() < 3) continue;
                double r = rows.get(li);
                // the line's own row taken out: its points less their mean, the basis less its mean
                double[] mean = new double[m + 1];
                for (double[] q : pts) {
                    b.weights(q[0], r, idx, w);
                    for (int k = 0; k < 16; k++) mean[idx[k]] += w[k] / pts.size();
                    mean[m] += q[1] / pts.size();
                }
                java.util.List<Integer> nz = new java.util.ArrayList<Integer>();
                for (int k = 0; k < m; k++) if (mean[k] != 0) nz.add(k);
                double[] row = new double[m];
                for (double[] q : pts) {
                    b.weights(q[0], r, idx, w);
                    for (int k : nz) row[k] = -mean[k];
                    for (int k = 0; k < 16; k++) row[idx[k]] += w[k];
                    double v = q[1] - mean[m];
                    for (int k : nz) {
                        if (row[k] == 0) continue;
                        for (int kk : nz) a[k][kk] += row[k] * row[kk];
                        a[k][m] += row[k] * v;
                    }
                    for (int k : nz) row[k] = 0;
                    count++;
                }
            }
            if (count < 30) return null;
            // calm: second differences across and down, and a little of the controls themselves
            double lam = 0.02 * count / m, ridge = 1e-3 * count / m;
            for (int j = 0; j < b.ny; j++) {
                for (int i = 1; i < b.nx - 1; i++) addSecond(a, j * b.nx + i - 1, j * b.nx + i, j * b.nx + i + 1, lam);
            }
            for (int i = 0; i < b.nx; i++) {
                for (int j = 1; j < b.ny - 1; j++) addSecond(a, (j - 1) * b.nx + i, j * b.nx + i, (j + 1) * b.nx + i, lam);
            }
            for (int k = 0; k < m; k++) a[k][k] += ridge;
            b.c = solve(a, m);
            if (b.c == null) return null;
            // the points the surface misses, and the lines that lose half of theirs, out
            boolean dropped = false;
            for (int li = 0; li < use.size(); li++) {
                java.util.List<double[]> pts = use.get(li);
                if (pts.size() < 3) continue;
                double r = rows.get(li), off = 0;
                for (double[] q : pts) off += (q[1] - b.at(q[0], r)) / pts.size();
                java.util.List<double[]> near = new java.util.ArrayList<double[]>();
                for (double[] q : pts) if (Math.abs(q[1] - b.at(q[0], r) - off) <= miss) near.add(q);
                if (near.size() < 0.5 * pts.size()) near.clear();
                if (near.size() != pts.size()) dropped = true;
                use.set(li, near);
            }
            if (!dropped) break;
        }
        return b;
    }

    private static void addSecond(double[][] a, int p, int q, int r, double lam) {
        int[] k = {p, q, r};
        double[] c = {1, -2, 1};
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) a[k[i]][k[j]] += lam * c[i] * c[j];
    }

    /** A line's row once level: the mean of its middle along it. */
    private static double rowOf(Line l, double step) {
        double sum = 0;
        int m = 0;
        for (double x = l.start; x < l.end; x += step) {
            sum += polyAt(l.mid, x);
            m++;
        }
        return m == 0 ? polyAt(l.mid, (l.start + l.end) / 2) : sum / m;
    }

    /**
     * Pieces of one line of text joined into it: a line with wide gaps (a form's label and what is filled in, a tab)
     * falls apart into pieces, and each piece levelled to its own row would put a step into the line where the sheet
     * leans. Two pieces one after the other along the page (the gap under a third of its width) whose middles, each
     * carried on straight across the gap (its slope over its last stretch), meet within half a letter are one line.
     */
    static void joinPieces(java.util.List<Line> lines, double charH, int aw, double step) {
        boolean joined = true;
        while (joined) {
            joined = false;
            java.util.Collections.sort(lines, new java.util.Comparator<Line>() {
                @Override
                public int compare(Line x, Line y) {
                    return Double.compare(x.start, y.start);
                }
            });
            outer:
            for (int i = 0; i < lines.size(); i++) {
                Line a = lines.get(i);
                for (int j = i + 1; j < lines.size(); j++) {
                    Line b = lines.get(j);
                    double gap = b.start - a.end;
                    if (gap < -0.02 * aw || gap > 0.3 * aw) continue;
                    double at = (a.end + b.start) / 2;
                    if (Math.abs(carried(a, at, aw) - carried(b, at, aw)) > 0.5 * charH) continue;
                    java.util.List<double[]> pts = new java.util.ArrayList<double[]>(a.pts);
                    pts.addAll(b.pts);
                    java.util.Collections.sort(pts, new java.util.Comparator<double[]>() {
                        @Override
                        public int compare(double[] x, double[] y) {
                            return Double.compare(x[0], y[0]);
                        }
                    });
                    double[] mid = polyFit(pts, false, pts.size() >= 30 ? 3 : 2, 0.4 * charH);
                    if (mid == null) continue;
                    double resid = 0;
                    for (double[] pt : pts) resid += Math.abs(polyAt(mid, pt[0]) - pt[1]);
                    if (resid / pts.size() > 0.35 * charH) continue;
                    Line l = new Line();
                    l.mid = mid;
                    l.pts = pts;
                    l.start = Math.min(a.start, b.start);
                    l.end = Math.max(a.end, b.end);
                    l.row = rowOf(l, step);
                    lines.remove(j);
                    lines.set(i, l);
                    joined = true;
                    break outer;
                }
            }
        }
        java.util.Collections.sort(lines, new java.util.Comparator<Line>() {
            @Override
            public int compare(Line x, Line y) {
                return Double.compare(x.row, y.row);
            }
        });
    }

    /** A line's middle carried on straight beyond its ends (the slope over its last tenth of the page's width). */
    private static double carried(Line l, double x, int aw) {
        double d = 0.1 * aw;
        if (x > l.end) {
            double e = l.end, s = Math.max(l.start, e - d), y1 = polyAt(l.mid, e), y0 = polyAt(l.mid, s);
            return y1 + (e > s ? (y1 - y0) / (e - s) : 0) * (x - e);
        }
        if (x < l.start) {
            double s = l.start, e = Math.min(l.end, s + d), y0 = polyAt(l.mid, s), y1 = polyAt(l.mid, e);
            return y0 - (e > s ? (y1 - y0) / (e - s) : 0) * (s - x);
        }
        return polyAt(l.mid, x);
    }

    /**
     * How far each point of the page is to be moved sideways for the text's vertical edges to stand upright: the
     * starts of lines one under another (a left-aligned block, a form's labels, a tab stop, the rules under the
     * fields) and their ends (justified text), which lean where the sheet leaned or curled — and differently in
     * different parts of it: a corner bent, the rest flat. The starts (ends) are chained into runs down the page — each
     * next line's within a quarter of a letter of the run's last (a lean moves them less from line to line; a paragraph
     * set further in, more), no more than three lines' distance below it — and a
     * straight line fitted through each run (once more without the starts it misses by a third of a letter): its lean
     * there. The lean at each point of the page is the runs' leans weighed by how near they are (a Gaussian of the
     * distance: across, a sixth of the page's width; down, three lines beyond the run's ends; a run counts by its
     * lines) against no lean at all (as half a line right there): near a run, its lean; far from all, none. The shift is the lean summed down each column, naught at the
     * text's middle row. Null when it hardly moves anything (under a pixel), straightens the runs too little, there
     * are no runs, or it would move things implausibly far.
     */
    static Upright upright(java.util.List<Line> lines, int aw, int ah, double charH, double midRow) {
        java.util.List<Double> ds = new java.util.ArrayList<Double>();
        for (int i = 1; i < lines.size(); i++) {
            double d = lines.get(i).row - lines.get(i - 1).row;
            if (d > charH) ds.add(d);
        }
        java.util.Collections.sort(ds);
        double pitch = ds.isEmpty() ? 3 * charH : ds.get(ds.size() / 2), tol = Math.max(1.5, 0.25 * charH);
        double miss = Math.max(1.5, 0.3 * charH);
        java.util.List<java.util.List<double[]>> runs = new java.util.ArrayList<java.util.List<double[]>>();
        for (int side = 0; side < 2; side++) {
            java.util.List<java.util.List<double[]>> open = new java.util.ArrayList<java.util.List<double[]>>();
            for (Line l : lines) {
                double x = side == 0 ? l.start : l.end;
                java.util.List<double[]> best = null;
                double bd = tol;
                for (java.util.List<double[]> r : open) {
                    double[] last = r.get(r.size() - 1);
                    if (l.row - last[1] > 3 * pitch) continue;
                    double d = Math.abs(last[0] - x);
                    if (d <= bd) {
                        bd = d;
                        best = r;
                    }
                }
                if (best == null) {
                    best = new java.util.ArrayList<double[]>();
                    open.add(best);
                }
                best.add(new double[]{x, l.row});
            }
            for (java.util.List<double[]> r : open) if (r.size() >= 3) runs.add(r);
        }
        // each run's lean: x = a + b·y through its starts, once more without those it misses
        java.util.List<double[]> leans = new java.util.ArrayList<double[]>(); // middle x, first row, last row, lean, lines
        java.util.List<java.util.List<double[]>> kept = new java.util.ArrayList<java.util.List<double[]>>();
        for (java.util.List<double[]> r : runs) {
            java.util.List<double[]> use = r;
            double[] fit = null;
            for (int pass = 0; pass < 2 && use.size() >= 3; pass++) {
                fit = lineThrough(use);
                java.util.List<double[]> near = new java.util.ArrayList<double[]>();
                for (double[] q : use) if (Math.abs(q[0] - fit[0] - fit[1] * q[1]) <= miss) near.add(q);
                if (near.size() == use.size()) break;
                use = near;
                fit = null;
            }
            if (fit == null || use.size() < 3) continue;
            double y0 = use.get(0)[1], y1 = use.get(use.size() - 1)[1];
            if (y1 - y0 < 1.5 * pitch) continue;
            leans.add(new double[]{fit[0] + fit[1] * (y0 + y1) / 2, y0, y1, fit[1], use.size()});
            kept.add(use);
        }
        if (leans.isEmpty()) return null;
        Upright up = new Upright();
        up.cell = Math.max(8, aw / 48.0);
        up.gw = (int) Math.ceil(aw / up.cell) + 1;
        up.gh = (int) Math.ceil(ah / up.cell) + 1;
        up.d = new float[up.gw * up.gh];
        double sigmaX = aw / 6.0, sigmaY = 3 * pitch;
        double[] lean = new double[up.gh];
        int ref = (int) Math.max(0, Math.min(up.gh - 1, Math.round(midRow / up.cell)));
        for (int i = 0; i < up.gw; i++) {
            double x = i * up.cell;
            for (int j = 0; j < up.gh; j++) {
                double y = j * up.cell, sw = 0.5, sb = 0;
                for (double[] l : leans) {
                    double dx = (x - l[0]) / sigmaX, dy = (y < l[1] ? l[1] - y : y > l[2] ? y - l[2] : 0) / sigmaY;
                    double w = l[4] * Math.exp(-(dx * dx + dy * dy) / 2);
                    sw += w;
                    sb += w * l[3];
                }
                lean[j] = sb / sw;
            }
            // summed down the column (trapezoids), naught at the middle row
            double acc = 0;
            up.d[i] = 0;
            for (int j = 1; j < up.gh; j++) {
                acc += up.cell * (lean[j - 1] + lean[j]) / 2;
                up.d[j * up.gw + i] = (float) acc;
            }
            float zero = up.d[ref * up.gw + i];
            for (int j = 0; j < up.gh; j++) up.d[j * up.gw + i] -= zero;
        }
        // what the shift leaves of the starts' wandering within their runs, against what there was
        java.util.List<java.util.List<double[]>> left = new java.util.ArrayList<java.util.List<double[]>>();
        for (java.util.List<double[]> r : kept) {
            java.util.List<double[]> rr = new java.util.ArrayList<double[]>();
            for (double[] q : r) rr.add(new double[]{q[0] - up.at(q[0], q[1]), q[1]});
            left.add(rr);
        }
        double most = 0;
        for (float v : up.d) most = Math.max(most, Math.abs(v));
        double before = spread(kept), after = spread(left);
        if (most < 1 || most > 0.06 * aw || after > 0.8 * before) return null;
        return up;
    }

    /** x = a + b·y through the points (least squares): {a, b}. */
    private static double[] lineThrough(java.util.List<double[]> pts) {
        double my = 0, mx = 0;
        for (double[] q : pts) {
            mx += q[0] / pts.size();
            my += q[1] / pts.size();
        }
        double syy = 0, sxy = 0;
        for (double[] q : pts) {
            syy += (q[1] - my) * (q[1] - my);
            sxy += (q[1] - my) * (q[0] - mx);
        }
        double b = syy == 0 ? 0 : sxy / syy;
        return new double[]{mx - b * my, b};
    }

    /** How far the runs' points wander from their runs' means (root mean square). */
    private static double spread(java.util.List<java.util.List<double[]>> runs) {
        double s = 0;
        int n = 0;
        for (java.util.List<double[]> r : runs) {
            if (r.size() < 3) continue;
            double mean = 0;
            for (double[] q : r) mean += q[0] / r.size();
            for (double[] q : r) {
                s += (q[0] - mean) * (q[0] - mean);
                n++;
            }
        }
        return n == 0 ? 0 : Math.sqrt(s / n);
    }

    /**
     * Bilinear sample with the image's edge carried on beyond it: what a page moved or turned uncovers is the paper at
     * its edge, not white (next to pure white the paper reads as dark: a black frame in black and white).
     */
    static int sampleEdge(int[] px, int w, int h, double x, double y) {
        return sample(px, w, h, Math.max(0, Math.min(w - 1, x)), Math.max(0, Math.min(h - 1, y)), 0xFFFFFFFF);
    }

    static int sample(int[] px, int w, int h, double x, double y, int fill) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        if (x0 < -1 || y0 < -1 || x0 >= w || y0 >= h) return fill;
        double ax = x - x0, ay = y - y0;
        double r = 0, g = 0, b = 0;
        for (int dy = 0; dy < 2; dy++) {
            for (int dx = 0; dx < 2; dx++) {
                int sx = x0 + dx, sy = y0 + dy;
                int p = sx < 0 || sy < 0 || sx >= w || sy >= h ? fill : px[sy * w + sx];
                double kk = (dx == 0 ? 1 - ax : ax) * (dy == 0 ? 1 - ay : ay);
                r += kk * ((p >> 16) & 0xFF);
                g += kk * ((p >> 8) & 0xFF);
                b += kk * (p & 0xFF);
            }
        }
        return 0xFF000000 | ((int) Math.round(r) << 16) | ((int) Math.round(g) << 8) | (int) Math.round(b);
    }

    // ------------------------------------------------------------------ which way up

    /**
     * How many quarter turns clockwise bring the page's text upright (0–3). Its lines are looked for as they are and
     * turned a quarter ({@link #trackLines}): the way that finds clearly more of them (by their length) is the way
     * they run. Then which end is the start: the lines' starts keep to a margin and their ends do not (left-aligned
     * text, a paragraph's short last line) — when more of the long lines end at one edge than start at the other, the
     * page is the other way round. Upside down is only taken when that is plain (a third of the lines and three or
     * more); sideways, the way that tells (by two lines or more), else a quarter turn back. Turned wrong, «Повернуть»
     * puts it right.
     */
    public static int quarterTurns(Image im) {
        double k = Math.min(1.0, 1600.0 / Math.max(im.w, im.h));
        int w = Math.max(1, (int) Math.round(im.w * k)), h = Math.max(1, (int) Math.round(im.h * k));
        int[] small = k < 1 ? FaceModel.resize(im.px, im.w, im.h, w, h) : im.px;
        int[] bw = scan(small, w, h, BW);
        boolean[] ink = new boolean[w * h];
        for (int i = 0; i < ink.length; i++) ink[i] = (bw[i] & 0xFF) == 0;
        double charH = letterHeight(ink, w, h);
        if (Double.isNaN(charH)) return 0;
        int step = (int) Math.max(1, Math.round(charH / 2));
        java.util.List<Line> flat = trackLines(ink, w, h, charH, step);
        java.util.List<Line> side = trackLines(turnMask(ink, w, h, 1), h, w, charH, step);
        double a = textLength(flat), b = textLength(side);
        // sideways: the way its lines start at a margin; when that does not tell, a quarter turn back (a page lying
        // across a phone held upright is turned that way more often)
        if (b > 1.5 * a && b > 2 * h) {
            int score = alignment(side, h);
            return score >= 2 ? 1 : score <= -2 ? 3 : 3;
        }
        int score = alignment(flat, w), n = 0;
        for (Line l : flat) if (!l.rule && l.end - l.start >= 0.15 * w && l.end - l.start <= 0.7 * w) n++;
        return score < 0 && -score >= Math.max(3, 0.3 * n) ? 2 : 0;
    }

    /** The total length of the lines of letters (not rules). */
    private static double textLength(java.util.List<Line> lines) {
        double s = 0;
        for (Line l : lines) if (!l.rule) s += l.end - l.start;
        return s;
    }

    /**
     * Of the lines of letters that do not run across the page (from 15% to 70% of its width: not a justified line, not
     * a field with its rule out to the margin): how many start where two others start too (a list, a form's labels, a
     * paragraph's lines, within 1% of the width) less how many end where two others end — text is set from the left,
     * its ends fall where they may.
     */
    private static int alignment(java.util.List<Line> lines, int w) {
        java.util.List<Double> starts = new java.util.ArrayList<Double>(), ends = new java.util.ArrayList<Double>();
        for (Line l : lines) {
            double len = l.end - l.start;
            if (l.rule || len < 0.15 * w || len > 0.7 * w) continue;
            starts.add(l.start);
            ends.add(l.end);
        }
        return together(starts, 0.01 * w) - together(ends, 0.01 * w);
    }

    /** How many of the values have two others within {@code tol}. */
    private static int together(java.util.List<Double> v, double tol) {
        int n = 0;
        for (int i = 0; i < v.size(); i++) {
            int near = 0;
            for (int j = 0; j < v.size(); j++) if (j != i && Math.abs(v.get(i) - v.get(j)) <= tol) near++;
            if (near >= 2) n++;
        }
        return n;
    }

    /** The median height of the small ink blobs (letters), or NaN with fewer than twenty. */
    static double letterHeight(boolean[] ink, int w, int h) {
        int n = w * h;
        boolean[] seen = new boolean[n];
        int[] stack = new int[n];
        java.util.List<Integer> heights = new java.util.ArrayList<Integer>();
        for (int i = 0; i < n; i++) {
            if (!ink[i] || seen[i]) continue;
            int top = 0, y0 = i / w, y1 = y0, x0 = i % w, x1 = x0, area = 0;
            stack[top++] = i;
            seen[i] = true;
            while (top > 0) {
                int p = stack[--top], x = p % w, y = p / w;
                area++;
                y0 = Math.min(y0, y);
                y1 = Math.max(y1, y);
                x0 = Math.min(x0, x);
                x1 = Math.max(x1, x);
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx, yy = y + dy;
                        if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                        int q = yy * w + xx;
                        if (ink[q] && !seen[q]) {
                            seen[q] = true;
                            stack[top++] = q;
                        }
                    }
                }
            }
            // either way the page lies: the smaller side (a word run together in black and white is as thick as its
            // letters are tall)
            int bh = Math.min(y1 - y0 + 1, x1 - x0 + 1);
            if (area >= 3 && bh >= 2 && bh <= Math.min(w, h) / 15) heights.add(bh);
        }
        if (heights.size() < 20) return Double.NaN;
        java.util.Collections.sort(heights);
        return heights.get(heights.size() / 2);
    }

    /** A mask turned {@code turns} quarter turns clockwise (its width and height swap when odd). */
    static boolean[] turnMask(boolean[] m, int w, int h, int turns) {
        turns = ((turns % 4) + 4) % 4;
        if (turns == 0) return m;
        boolean[] out = new boolean[m.length];
        int ow = turns % 2 == 1 ? h : w;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int nx, ny;
                if (turns == 1) {
                    nx = h - 1 - y;
                    ny = x;
                } else if (turns == 2) {
                    nx = w - 1 - x;
                    ny = h - 1 - y;
                } else {
                    nx = y;
                    ny = w - 1 - x;
                }
                out[ny * ow + nx] = m[y * w + x];
            }
        }
        return out;
    }

    /** The image turned {@code turns} quarter turns clockwise. */
    public static Image turn(Image im, int turns) {
        turns = ((turns % 4) + 4) % 4;
        if (turns == 0) return im;
        int w = im.w, h = im.h, ow = turns % 2 == 1 ? h : w, oh = turns % 2 == 1 ? w : h;
        int[] out = new int[im.px.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int nx, ny;
                if (turns == 1) {
                    nx = h - 1 - y;
                    ny = x;
                } else if (turns == 2) {
                    nx = w - 1 - x;
                    ny = h - 1 - y;
                } else {
                    nx = y;
                    ny = w - 1 - x;
                }
                out[ny * ow + nx] = im.px[y * w + x];
            }
        }
        return new Image(out, ow, oh);
    }

    // ------------------------------------------------------------------ the text's angle

    /**
     * The angle of the text lines, degrees (positive: falling to the right), within ±{@link #MAX_SKEW}: the one at which
     * the dark pixels, projected across, fall into the sharpest rows (the largest sum of squared row counts). 0 when
     * there is too little text to tell.
     */
    public static double skew(int[] argb, int w, int h) {
        double[] scale = new double[1];
        int[] size = new int[2];
        int[] g = smallGray(argb, w, h, 1000, scale, size);
        int sw = size[0], sh = size[1];
        int t = Math.min(otsu(g), 160);
        int n = 0;
        for (int v : g) if (v <= t) n++;
        if (n < 200 || n > g.length / 2) return 0;
        int step = Math.max(1, n / 60000);
        float[] xs = new float[n / step + 1], ys = new float[n / step + 1];
        int m = 0, seen = 0;
        for (int i = 0; i < g.length; i++) {
            if (g[i] > t) continue;
            if (seen++ % step != 0) continue;
            xs[m] = i % sw - sw / 2f;
            ys[m] = i / sw - sh / 2f;
            m++;
        }
        double best = 0, bestScore = -1;
        for (double a = -MAX_SKEW; a <= MAX_SKEW + 1e-9; a += 0.5) {
            double s = sharpness(xs, ys, m, a, sw, sh);
            if (s > bestScore) {
                bestScore = s;
                best = a;
            }
        }
        double coarse = best;
        for (double a = coarse - 0.5; a <= coarse + 0.5 + 1e-9; a += 0.05) {
            double s = sharpness(xs, ys, m, a, sw, sh);
            if (s > bestScore) {
                bestScore = s;
                best = a;
            }
        }
        return Math.abs(best) < 0.05 ? 0 : best;
    }

    private static double sharpness(float[] xs, float[] ys, int m, double deg, int sw, int sh) {
        double r = Math.toRadians(deg), sin = Math.sin(r), cos = Math.cos(r);
        int off = sw + sh;
        int[] hist = new int[2 * off + 1];
        for (int i = 0; i < m; i++) {
            int row = (int) Math.round(ys[i] * cos - xs[i] * sin) + off;
            if (row >= 0 && row < hist.length) hist[row]++;
        }
        double s = 0;
        for (int c : hist) s += (double) c * c;
        return s;
    }

    /** The image turned by {@code deg} (positive: counter-clockwise, which levels text falling to the right), its edges carried on into the corners it uncovers. */
    public static Image rotate(Image im, double deg) {
        if (deg == 0) return im;
        double r = Math.toRadians(deg), sin = Math.sin(r), cos = Math.cos(r);
        double cx = im.w / 2.0, cy = im.h / 2.0;
        int[] out = new int[im.px.length];
        for (int y = 0; y < im.h; y++) {
            for (int x = 0; x < im.w; x++) {
                double dx = x + 0.5 - cx, dy = y + 0.5 - cy;
                // the source of an output pixel: turned back
                double sx = cos * dx - sin * dy + cx - 0.5, sy = sin * dx + cos * dy + cy - 0.5;
                out[y * im.w + x] = sampleEdge(im.px, im.w, im.h, sx, sy);
            }
        }
        return new Image(out, im.w, im.h);
    }

    // ------------------------------------------------------------------ the scanner's look

    /**
     * The light falling on the paper at each pixel of one plane (0..255 values): the brightest of each 1/32-of-the-side
     * cell (the paper between the letters), the brightest of its neighbours too (a cell all ink: a picture, a stamp),
     * smoothed, back at full size.
     */
    static int[] light(int[] plane, int w, int h) {
        int cell = Math.max(4, Math.max(w, h) / 32);
        int cw = (w + cell - 1) / cell, ch = (h + cell - 1) / cell;
        float[] mx = new float[cw * ch];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = plane[y * w + x], i = (y / cell) * cw + x / cell;
                if (v > mx[i]) mx[i] = v;
            }
        }
        float[] sm = new float[mx.length];
        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw; x++) {
                float best = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int yy = y + dy, xx = x + dx;
                        if (yy >= 0 && xx >= 0 && yy < ch && xx < cw) best = Math.max(best, mx[yy * cw + xx]);
                    }
                }
                sm[y * cw + x] = best;
            }
        }
        for (int pass = 0; pass < 2; pass++) sm = blur3(sm, cw, ch);
        int[] full = new int[w * h];
        for (int y = 0; y < h; y++) {
            double fy = (y + 0.5) / cell - 0.5;
            int y0 = (int) Math.floor(fy);
            double ay = fy - y0;
            int ya = Math.max(0, Math.min(ch - 1, y0)), yb = Math.max(0, Math.min(ch - 1, y0 + 1));
            for (int x = 0; x < w; x++) {
                double fx = (x + 0.5) / cell - 0.5;
                int x0 = (int) Math.floor(fx);
                double ax = fx - x0;
                int xa = Math.max(0, Math.min(cw - 1, x0)), xb = Math.max(0, Math.min(cw - 1, x0 + 1));
                double v = (1 - ay) * ((1 - ax) * sm[ya * cw + xa] + ax * sm[ya * cw + xb])
                        + ay * ((1 - ax) * sm[yb * cw + xa] + ax * sm[yb * cw + xb]);
                full[y * w + x] = Math.max(1, (int) Math.round(v));
            }
        }
        return full;
    }

    private static float[] blur3(float[] a, int w, int h) {
        float[] o = new float[a.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float s = 0;
                int n = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int yy = y + dy, xx = x + dx;
                        if (yy >= 0 && xx >= 0 && yy < h && xx < w) {
                            s += a[yy * w + xx];
                            n++;
                        }
                    }
                }
                o[y * w + x] = s / n;
            }
        }
        return o;
    }

    /**
     * The scanner's look: {@link #BW} strict black and white (each pixel's brightness against the light on the paper,
     * black when well below its neighbourhood's mean), {@link #GRAY} the same light taken away but the greys kept,
     * {@link #COLOR} colours kept on white paper.
     */
    public static int[] scan(int[] argb, int w, int h, int mode) {
        int n = w * h;
        int[] out = new int[n];
        if (mode == COLOR) {
            // each channel against its own light: the paper's tint goes, the colours stay
            int[] plane = new int[n];
            for (int c = 0; c < 3; c++) {
                int sh = 16 - 8 * c;
                for (int i = 0; i < n; i++) plane[i] = (argb[i] >> sh) & 0xFF;
                int[] L = light(plane, w, h);
                for (int i = 0; i < n; i++) out[i] |= norm(plane[i], L[i]) << sh;
            }
            for (int i = 0; i < n; i++) out[i] |= 0xFF000000;
            return out;
        }
        // brightness against the light on the paper (in place: one plane and its light, for a large page)
        int[] v = new int[n];
        for (int i = 0; i < n; i++) v[i] = luma(argb[i]);
        int[] L = light(v, w, h);
        for (int i = 0; i < n; i++) v[i] = norm(v[i], L[i]);
        L = null;
        if (mode == GRAY) {
            // paper white, ink darker
            for (int i = 0; i < n; i++) {
                int g = v[i] >= 225 ? 255 : (int) Math.max(0, Math.round((v[i] - 40) * 255.0 / 185));
                out[i] = 0xFF000000 | (g << 16) | (g << 8) | g;
            }
            return out;
        }
        // strict black and white: below the neighbourhood's mean by a margin and not paper-light — or dark anyway (a
        // filled area wider than the neighbourhood: a stamp, a photo, a bold heading, black inside too); the sums fit
        // an int (255 × a few million)
        int[] integral = new int[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            int row = 0;
            for (int x = 0; x < w; x++) {
                row += v[y * w + x];
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + row;
            }
        }
        int r = Math.max(4, Math.max(w, h) / 50);
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - r), y1 = Math.min(h, y + r + 1);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - r), x1 = Math.min(w, x + r + 1);
                long s = (long) integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1] - integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0];
                double mean = (double) s / ((x1 - x0) * (y1 - y0));
                boolean ink = v[y * w + x] < 110 || v[y * w + x] < Math.min(mean - 12, 215);
                out[y * w + x] = ink ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        return out;
    }

    private static int norm(int v, int light) {
        return Math.min(255, v * 255 / Math.max(1, light));
    }

    /** The whole way: the sheet (when found and wanted), levelled (when wanted), in the scanner's look. */
    public static Image process(int[] argb, int w, int h, float[] page, boolean level, int mode, int maxSide) {
        Image im = page != null ? clearRim(warp(argb, w, h, page, maxSide)) : fit(argb, w, h, maxSide);
        return finish(im, level, mode);
    }

    /** The same with a bent sheet: flattened between its curved edges. */
    public static Image process(int[] argb, int w, int h, Sheet sheet, boolean level, int mode, int maxSide) {
        Image im = sheet != null ? clearRim(dewarp(argb, w, h, sheet, maxSide)) : fit(argb, w, h, maxSide);
        return finish(im, level, mode);
    }

    /** How deep from the edge of a cut-out sheet the surroundings are looked for: of its shorter side. */
    static final double RIM = 0.035;

    /**
     * A cut-out sheet with what is left of its surroundings painted the paper's colour: the table, a folder or the
     * shadow just outside its edges, and the edges of the sheets under it in a pile (dark lines along the edge) — they
     * would print as a black frame. On a smaller copy in black and white: the dark joined to the image's edge within
     * {@link #RIM} of it, and on from there through solid dark (a wedge of table at a corner, not letters) up to three
     * times as deep, and all of any dark joined to the edge that stays within that; and thin dark lines along an edge
     * within that strip (a twentieth of the side long or more, three
     * times as long as across, thin all along: straight or curved); and what that walls off from the page near its edge (a thumb holding the sheet: its
     * outline goes, and the skin inside it). Letters near the edge are left (not joined to it, not that long).
     */
    public static Image clearRim(Image im) {
        // twice: with the surroundings painted over, what was next to them (a shadow's edge) can stand out in turn
        return clearRimOnce(clearRimOnce(im));
    }

    private static Image clearRimOnce(Image im) {
        int W = im.w, H = im.h;
        double k = Math.min(1.0, 2000.0 / Math.max(W, H));
        int sw = Math.max(1, (int) Math.round(W * k)), sh = Math.max(1, (int) Math.round(H * k));
        int[] small = k < 1 ? FaceModel.resize(im.px, W, H, sw, sh) : im.px;
        int[] bw = scan(small, sw, sh, BW);
        int n = sw * sh;
        boolean[] ink = new boolean[n];
        for (int i = 0; i < n; i++) ink[i] = (bw[i] & 0xFF) == 0;
        int band = Math.max(2, (int) Math.round(RIM * Math.min(sw, sh))), deep = 3 * band;
        boolean[] rim = new boolean[n];
        int[] queue = new int[n];
        int qh = 0, qt = 0;
        for (int x = 0; x < sw; x++) {
            for (int y : new int[]{0, sh - 1}) {
                int i = y * sw + x;
                if (ink[i] && !rim[i]) {
                    rim[i] = true;
                    queue[qt++] = i;
                }
            }
        }
        for (int y = 0; y < sh; y++) {
            for (int x : new int[]{0, sw - 1}) {
                int i = y * sw + x;
                if (ink[i] && !rim[i]) {
                    rim[i] = true;
                    queue[qt++] = i;
                }
            }
        }
        while (qh < qt) {
            int p = queue[qh++], x = p % sw, y = p / sw;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int xx = x + dx, yy = y + dy;
                    if (xx < 0 || yy < 0 || xx >= sw || yy >= sh) continue;
                    int q = yy * sw + xx;
                    if (!ink[q] || rim[q]) continue;
                    int depth = Math.min(Math.min(xx, sw - 1 - xx), Math.min(yy, sh - 1 - yy));
                    if (depth > deep || depth > band && !solid(ink, sw, sh, xx, yy)) continue;
                    rim[q] = true;
                    queue[qt++] = q;
                }
            }
        }
        // whatever dark touches the edge and stays near it (no deeper than three strips: a thumb's outline, a strip of
        // table) goes whole; what runs on into the page (a table cut by the edge) stays
        boolean[] seenEdge = new boolean[n];
        int[] comp = new int[n];
        for (int i = 0; i < n; i++) {
            if (!ink[i] || rim[i] || seenEdge[i] || depth(i, sw, sh) > 0) continue;
            int cn = 0, most = 0;
            qh = 0;
            qt = 0;
            queue[qt++] = i;
            seenEdge[i] = true;
            while (qh < qt) {
                int p = queue[qh++], x = p % sw, y = p / sw;
                comp[cn++] = p;
                most = Math.max(most, depth(p, sw, sh));
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx, yy = y + dy;
                        if (xx < 0 || yy < 0 || xx >= sw || yy >= sh) continue;
                        int q = yy * sw + xx;
                        if (ink[q] && !seenEdge[q]) {
                            seenEdge[q] = true;
                            queue[qt++] = q;
                        }
                    }
                }
            }
            if (most <= deep) for (int j = 0; j < cn; j++) rim[comp[j]] = true;
        }
        // dark lines along an edge, within the strip: a sheet's edge in a pile, its shadow
        int[] lab = new int[n];
        int next = 0;
        for (int i = 0; i < n; i++) {
            if (!ink[i] || rim[i] || lab[i] != 0 || depth(i, sw, sh) > band) continue;
            next++;
            qh = 0;
            qt = 0;
            queue[qt++] = i;
            lab[i] = next;
            int x0 = sw, x1 = -1, y0 = sh, y1 = -1;
            while (qh < qt) {
                int p = queue[qh++], x = p % sw, y = p / sw;
                x0 = Math.min(x0, x);
                x1 = Math.max(x1, x);
                y0 = Math.min(y0, y);
                y1 = Math.max(y1, y);
                // a faint line breaks up in black and white: its pieces joined across small gaps along the edge
                boolean side = Math.min(x, sw - 1 - x) <= band, end = Math.min(y, sh - 1 - y) <= band;
                int gy = side ? 4 : 1, gx = end ? 4 : 1;
                for (int dy = -gy; dy <= gy; dy++) {
                    for (int dx = -gx; dx <= gx; dx++) {
                        int xx = x + dx, yy = y + dy;
                        if (xx < 0 || yy < 0 || xx >= sw || yy >= sh) continue;
                        int q = yy * sw + xx;
                        if (ink[q] && !rim[q] && lab[q] == 0 && depth(q, sw, sh) <= band) {
                            lab[q] = next;
                            queue[qt++] = q;
                        }
                    }
                }
            }
            // long and thin, along the edge it is near: a twentieth of the side or more, three times as long as across,
            // and thin all along (its pixels no more than its length times a sixth of the strip: a curve — a thumb's
            // outline — is wide across but thin)
            int bw0 = x1 - x0 + 1, bh0 = y1 - y0 + 1;
            double thin = Math.max(2, band / 6.0);
            boolean nearTopBottom = Math.min(y0, sh - 1 - y1) <= band, nearSides = Math.min(x0, sw - 1 - x1) <= band;
            boolean along = nearTopBottom && bw0 >= Math.max(sw / 20, 3 * bh0) && qt <= bw0 * thin
                    || nearSides && bh0 >= Math.max(sh / 20, 3 * bw0) && qt <= bh0 * thin;
            if (along) for (int j = 0; j < qt; j++) rim[queue[j]] = true;
        }
        // what the rim walls off from the page (inside a thumb's outline, between the rim and the edge) goes with it:
        // the page's middle flooded through all but the rim, near the edge what it does not reach is rim
        boolean[] reach = new boolean[n];
        qh = 0;
        qt = 0;
        for (int i = 0; i < n; i++) {
            if (!rim[i] && depth(i, sw, sh) > deep) {
                reach[i] = true;
                queue[qt++] = i;
            }
        }
        while (qh < qt) {
            int p = queue[qh++], x = p % sw, y = p / sw;
            int[] nb = {x > 0 ? p - 1 : -1, x < sw - 1 ? p + 1 : -1, y > 0 ? p - sw : -1, y < sh - 1 ? p + sw : -1};
            for (int q : nb) {
                if (q >= 0 && !reach[q] && !rim[q]) {
                    reach[q] = true;
                    queue[qt++] = q;
                }
            }
        }
        int found = 0;
        for (int i = 0; i < n; i++) {
            if (!reach[i] && depth(i, sw, sh) <= deep) rim[i] = true;
            if (rim[i]) found++;
        }
        if (found == 0) return im;
        // the paper's colour there, as the paper around it is (in the shade by a hand, lit at the other side): the
        // paper's mean colour in cells of eight pixels, the cells of the rim filled in from their neighbours (a few
        // dozen rounds of each the mean of the four around it) — painted over in one colour, the shaded paper next to
        // it would show a step, and black and white draws it as a line
        int f = 8, gw = (sw + f - 1) / f, gh = (sh + f - 1) / f;
        double[][] col = new double[3][gw * gh];
        int[] cnt = new int[gw * gh];
        for (int i = 0; i < n; i++) {
            if (ink[i] || rim[i]) continue;
            int c = (i / sw / f) * gw + (i % sw) / f, p = small[i];
            col[0][c] += (p >> 16) & 0xFF;
            col[1][c] += (p >> 8) & 0xFF;
            col[2][c] += p & 0xFF;
            cnt[c]++;
        }
        boolean[] known = new boolean[gw * gh];
        double[] mean = new double[3];
        int nk = 0;
        for (int c = 0; c < gw * gh; c++) {
            known[c] = cnt[c] >= f;
            for (int ch = 0; ch < 3; ch++) {
                if (known[c]) {
                    col[ch][c] /= cnt[c];
                    mean[ch] += col[ch][c];
                }
            }
            if (known[c]) nk++;
        }
        for (int c = 0; c < gw * gh; c++) for (int ch = 0; ch < 3; ch++) if (!known[c]) col[ch][c] = nk == 0 ? 255 : mean[ch] / nk;
        for (int round = 0; round < 60; round++) {
            for (int c = 0; c < gw * gh; c++) {
                if (known[c]) continue;
                int gx = c % gw, gy = c / gw;
                for (int ch = 0; ch < 3; ch++) {
                    double sum = 0;
                    int m = 0;
                    if (gx > 0) { sum += col[ch][c - 1]; m++; }
                    if (gx < gw - 1) { sum += col[ch][c + 1]; m++; }
                    if (gy > 0) { sum += col[ch][c - gw]; m++; }
                    if (gy < gh - 1) { sum += col[ch][c + gw]; m++; }
                    col[ch][c] = sum / m;
                }
            }
        }
        // the rim and two cells around it (the copy is coarser; a faint line's fringe goes too)
        boolean[] paint = dilate(dilate(rim, sw, sh), sw, sh);
        int[] out = Arrays.copyOf(im.px, im.px.length);
        for (int y = 0; y < H; y++) {
            int cy = Math.min(sh - 1, (int) (y * k));
            for (int x = 0; x < W; x++) {
                int cx = Math.min(sw - 1, (int) (x * k));
                if (paint[cy * sw + cx]) {
                    // between the cells' middles, in proportion
                    double gx = Math.max(0, Math.min(gw - 1.001, x * k / f - 0.5)), gy = Math.max(0, Math.min(gh - 1.001, y * k / f - 0.5));
                    int i0 = (int) gx, j0 = (int) gy;
                    double fx = gx - i0, fy = gy - j0;
                    int c00 = j0 * gw + i0, c10 = c00 + (i0 + 1 < gw ? 1 : 0), c01 = c00 + (j0 + 1 < gh ? gw : 0), c11 = c01 + (i0 + 1 < gw ? 1 : 0);
                    int p = 0xFF000000;
                    for (int ch = 0; ch < 3; ch++) {
                        double v = (col[ch][c00] * (1 - fx) + col[ch][c10] * fx) * (1 - fy) + (col[ch][c01] * (1 - fx) + col[ch][c11] * fx) * fy;
                        p |= (int) Math.max(0, Math.min(255, Math.round(v))) << (16 - 8 * ch);
                    }
                    out[y * W + x] = p;
                }
            }
        }
        return new Image(out, W, H);
    }

    /** How far a pixel is from the image's nearest edge. */
    private static int depth(int i, int w, int h) {
        int x = i % w, y = i / w;
        return Math.min(Math.min(x, w - 1 - x), Math.min(y, h - 1 - y));
    }

    /** Dark all around: the pixel and its eight neighbours (a filled area, not a letter's stroke). */
    private static boolean solid(boolean[] ink, int w, int h, int x, int y) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int xx = x + dx, yy = y + dy;
                if (xx < 0 || yy < 0 || xx >= w || yy >= h || !ink[yy * w + xx]) return false;
            }
        }
        return true;
    }

    private static Image finish(Image im, boolean level, int mode) {
        im = turn(im, quarterTurns(im));
        if (level) {
            im = rotate(im, skew(im.px, im.w, im.h));
            im = straighten(im).image;
        }
        return new Image(scan(im.px, im.w, im.h, mode), im.w, im.h);
    }

    /** The image as it is, scaled down to {@code maxSide} when larger. */
    public static Image fit(int[] argb, int w, int h, int maxSide) {
        if (Math.max(w, h) <= maxSide) return new Image(Arrays.copyOf(argb, argb.length), w, h);
        double k = (double) maxSide / Math.max(w, h);
        int nw = Math.max(1, (int) Math.round(w * k)), nh = Math.max(1, (int) Math.round(h * k));
        return new Image(FaceModel.resize(argb, w, h, nw, nh), nw, nh);
    }
}
