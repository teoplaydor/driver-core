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
        boolean[] bright = new boolean[g.length];
        for (int i = 0; i < g.length; i++) bright[i] = g[i] > t;
        // thin bridges cut (a light pattern on the cloth touching the sheet): opened by a pixel
        bright = open(bright, sw, sh);
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
            edges[sd] = sides[sd].size() >= 20 ? polyFit(sides[sd], sd % 2 == 1, sides[sd].size() >= 60 ? 3 : 1, 1.5) : null;
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

    /** The mask eroded and dilated by one pixel (3×3): bridges and specks one pixel wide go. */
    static boolean[] open(boolean[] m, int w, int h) {
        boolean[] e = new boolean[m.length], d = new boolean[m.length];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                boolean all = true;
                for (int dy = -1; dy <= 1 && all; dy++) for (int dx = -1; dx <= 1 && all; dx++) all = m[(y + dy) * w + x + dx];
                e[y * w + x] = all;
            }
        }
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
     * the sides as they are. Within 3.5% of a paper size (either way up), that size.
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

    /** Height / width {@code r} as a paper size's when within 3.5% of it (either way up). */
    static double snap(double r) {
        for (double p : PAPER) {
            if (Math.abs(r / p - 1) < 0.035) return p;
            if (Math.abs(r * p - 1) < 0.035) return 1 / p;
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
     * The page with its lines straightened, and what was done: lines found, their largest bend (pixels), margins, how
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
    }

    /**
     * The lines of text made straight and level, the margins vertical: the lines are found (the ink, black and white,
     * letters joined along a row), a polynomial through each line's middle; each column of the page is then moved up or
     * down so that every line lies on one row (between lines, as the lines around say: a polynomial over the page's
     * height, per column), and each row moved and stretched sideways so that the lines' starts (and ends, when the text
     * is justified) stand one above the other; where the lines come closer together than elsewhere (the sheet curling
     * or leaning away there), the rows are spread out to the widest spacing (see {@link #spacing}), the page growing
     * taller. Fewer than three lines: the page as it is.
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
        // letters joined along their rows: gaps up to 1.6 letters wide filled
        boolean[] row = ink.clone();
        int gap = (int) Math.max(2, Math.round(1.6 * charH));
        for (int y = 0; y < ah; y++) {
            int last = -1;
            for (int x = 0; x < aw; x++) {
                if (!ink[y * aw + x]) continue;
                if (last >= 0 && x - last > 1 && x - last <= gap) for (int f = last + 1; f < x; f++) row[y * aw + f] = true;
                last = x;
            }
        }
        java.util.Arrays.fill(lab, 0);
        java.util.List<Line> lines = new java.util.ArrayList<Line>();
        next = 0;
        int step = (int) Math.max(1, Math.round(charH / 2));
        for (int i = 0; i < n; i++) {
            if (!row[i] || lab[i] != 0) continue;
            next++;
            int top = 0, y0 = i / aw, y1 = y0, x0 = i % aw, x1 = x0;
            stack[top++] = i;
            lab[i] = next;
            while (top > 0) {
                int p = stack[--top], x = p % aw, y = p / aw;
                y0 = Math.min(y0, y);
                y1 = Math.max(y1, y);
                x0 = Math.min(x0, x);
                x1 = Math.max(x1, x);
                int[] nb = {x > 0 ? p - 1 : -1, x < aw - 1 ? p + 1 : -1, y > 0 ? p - aw : -1, y < ah - 1 ? p + aw : -1};
                for (int q : nb) {
                    if (q >= 0 && row[q] && lab[q] == 0) {
                        lab[q] = next;
                        stack[top++] = q;
                    }
                }
            }
            int lw = x1 - x0 + 1;
            if (lw < 0.12 * aw) continue;
            // a line's middle, column by column (its own ink only)
            java.util.List<double[]> pts = new java.util.ArrayList<double[]>();
            for (int x = x0; x <= x1; x += step) {
                double sy = 0;
                int cnt = 0;
                for (int y = y0; y <= y1; y++) {
                    int p = y * aw + x;
                    if (ink[p] && lab[p] == next) {
                        sy += y;
                        cnt++;
                    }
                }
                if (cnt > 0) pts.add(new double[]{x + 0.5, sy / cnt + 0.5});
            }
            if (pts.size() < 8) continue;
            // a text line rises and falls by less than its letters' height over a short way: a tall blob (a picture,
            // a table's frame) is not one
            double[] mid = polyFit(pts, false, pts.size() >= 30 ? 3 : 2, 0.4 * charH);
            if (mid == null) continue;
            double resid = 0;
            for (double[] pt : pts) resid += Math.abs(polyAt(mid, pt[0]) - pt[1]);
            if (resid / pts.size() > 0.35 * charH) continue;
            Line l = new Line();
            l.mid = mid;
            l.start = x0;
            l.end = x1 + 1;
            double sum = 0;
            int m = 0;
            for (double x = x0; x <= x1; x += step) {
                sum += polyAt(mid, x);
                m++;
            }
            l.row = sum / m;
            lines.add(l);
        }
        if (lines.size() < 3) return none;
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
        // per column of a grid: how far the lines are from their rows, as a polynomial of the row
        int G = 48;
        double[][] shift = new double[G][];
        double bend = 0;
        int deg = Math.min(3, lines.size() - 1);
        for (int j = 0; j < G; j++) {
            double X = (j + 0.5) * aw / G;
            java.util.List<double[]> pts = new java.util.ArrayList<double[]>();
            for (Line l : lines) {
                double d = polyAt(l.mid, Math.max(l.start, Math.min(l.end, X))) - l.row;
                pts.add(new double[]{l.row, d});
                bend = Math.max(bend, Math.abs(d));
            }
            shift[j] = polyFit(pts, false, deg, 0.5 * charH);
        }
        double tFirst = lines.get(0).row, tLast = lines.get(lines.size() - 1).row, tMid = (tFirst + tLast) / 2;
        // the margins: the lines starting (ending) furthest left (right), when there are enough of them in a line
        double[] leftM = margin(lines, true, aw), rightM = margin(lines, false, aw);
        boolean margins = leftM != null || rightM != null;
        double lRef = leftM == null ? 0 : polyAt(leftM, tMid), rRef = rightM == null ? 0 : polyAt(rightM, tMid);
        // the rows spread out: the height above each row (analysis scale) once the lines are evenly spaced
        Spacing spc = spacing(lines, charH, ah);
        int H2 = H;
        double[] U = null;
        if (spc != null) {
            U = new double[ah + 1];
            for (int r = 0; r < ah; r++) U[r + 1] = U[r] + spc.stretch(r + 0.5);
            H2 = (int) Math.round(W * snap(U[ah] / k / W));
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
            for (int j = 0; j < G; j++) sh[j] = shift[j] == null ? 0 : polyAt(shift[j], t);
            double L = leftM == null ? 0 : polyAt(leftM, t), R = rightM == null ? 0 : polyAt(rightM, t);
            boolean both = leftM != null && rightM != null && rRef - lRef > 0.3 * aw;
            for (int x = 0; x < W; x++) {
                double Xa = (x + 0.5) * k;
                double xs = both ? L + (Xa - lRef) * (R - L) / (rRef - lRef) : leftM != null ? Xa + L - lRef : rightM != null ? Xa + R - rRef : Xa;
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
     * The left (or right) margin as a line of the row: through the starts (ends) of the lines within 4% of the page's
     * width of the furthest left (right), when there are at least four and they keep to a line; null otherwise (ragged
     * text, too few lines) or when it is already vertical.
     */
    static double[] margin(java.util.List<Line> lines, boolean left, int aw) {
        double edge = left ? 1e18 : -1e18;
        for (Line l : lines) edge = left ? Math.min(edge, l.start) : Math.max(edge, l.end);
        java.util.List<double[]> pts = new java.util.ArrayList<double[]>();
        for (Line l : lines) {
            double v = left ? l.start : l.end;
            if (Math.abs(v - edge) <= 0.04 * aw) pts.add(new double[]{l.row, v});
        }
        if (pts.size() < 4) return null;
        double[] p = polyFit(pts, false, 1, 0.01 * aw);
        if (p == null) return null;
        double spanT = pts.get(pts.size() - 1)[0] - pts.get(0)[0];
        double drift = Math.abs(p[3]) * 2; // across the lines' span: the slope × 2 (the variable runs over −1…1)
        return drift < 1 || spanT < 0.2 * aw ? null : p;
    }

    /** Bilinear sample at (x, y) (pixel centres at integers), {@code fill} outside. */
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
     * times as deep; and thin dark lines along an edge within that strip (a twentieth of the side long or more, six
     * times as long as thick). Letters near the edge are left (not joined to it, not that long).
     */
    public static Image clearRim(Image im) {
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
            // long and thin, along the edge it is near: a twentieth of the side or more, six times as long as thick
            int bw0 = x1 - x0 + 1, bh0 = y1 - y0 + 1;
            boolean nearTopBottom = Math.min(y0, sh - 1 - y1) <= band, nearSides = Math.min(x0, sw - 1 - x1) <= band;
            boolean along = nearTopBottom && bh0 <= band / 2 + 1 && bw0 >= Math.max(sw / 20, 6 * bh0)
                    || nearSides && bw0 <= band / 2 + 1 && bh0 >= Math.max(sh / 20, 6 * bw0);
            if (along) for (int j = 0; j < qt; j++) rim[queue[j]] = true;
        }
        int found = 0;
        for (boolean b : rim) if (b) found++;
        if (found == 0) return im;
        // the paper's colour: the median of the light pixels near the edges, outside the rim
        java.util.List<Integer> rs = new java.util.ArrayList<Integer>(), gs = new java.util.ArrayList<Integer>(),
                bs = new java.util.ArrayList<Integer>();
        for (int i = 0; i < n; i += 3) {
            if (ink[i] || rim[i] || depth(i, sw, sh) > deep) continue;
            int p = small[i];
            rs.add((p >> 16) & 0xFF);
            gs.add((p >> 8) & 0xFF);
            bs.add(p & 0xFF);
        }
        int paper = 0xFFFFFFFF;
        if (rs.size() > 20) {
            java.util.Collections.sort(rs);
            java.util.Collections.sort(gs);
            java.util.Collections.sort(bs);
            int m = rs.size() / 2;
            paper = 0xFF000000 | rs.get(m) << 16 | gs.get(m) << 8 | bs.get(m);
        }
        int[] out = Arrays.copyOf(im.px, im.px.length);
        for (int y = 0; y < H; y++) {
            int cy = Math.min(sh - 1, (int) (y * k));
            for (int x = 0; x < W; x++) {
                int cx = Math.min(sw - 1, (int) (x * k));
                // the rim and two cells around it (the copy is coarser; a faint line's fringe goes too)
                boolean on = false;
                for (int dy = -2; dy <= 2 && !on; dy++) {
                    for (int dx = -2; dx <= 2 && !on; dx++) {
                        int xx = cx + dx, yy = cy + dy;
                        on = xx >= 0 && yy >= 0 && xx < sw && yy < sh && rim[yy * sw + xx];
                    }
                }
                if (on) out[y * W + x] = paper;
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
