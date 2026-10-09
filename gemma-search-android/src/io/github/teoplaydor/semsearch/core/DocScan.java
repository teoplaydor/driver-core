package io.github.teoplaydor.semsearch.core;

import java.util.Arrays;

/**
 * A photo of a document made ready to print, as a scanner would give it: the sheet found on the photo
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

    /**
     * The sheet's corners (top left, top right, bottom right, bottom left: x, y pairs, in the image's pixels), or null
     * when there is no sheet apart from its surroundings (it fills the photo, or lies on something as light).
     */
    public static float[] findPage(int[] argb, int w, int h) {
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
        float[] q = new float[8];
        double k = scale[0];
        for (int i = 0; i < 8; i++) q[i] = (float) (small[i] * k);
        // a little inside the edges: no sliver of the table along them
        inset(q, 0.004 * Math.min(w, h));
        // a sheet: a convex four-cornered shape the region fills, not a blob
        double area = Math.abs(quadArea(q));
        if (!convex(q) || area < 0.06 * w * h) return null;
        // the region with its holes (the letters) filled — all that the outside does not reach — fills the shape
        int filled = 0;
        for (boolean o : out) if (!o) filled++;
        double regionArea = filled * k * k;
        if (regionArea / area < 0.85 || regionArea / area > 1.15) return null;
        for (int i = 0; i < 4; i++) {
            double ex = q[(2 * i + 2) % 8] - q[2 * i], ey = q[(2 * i + 3) % 8] - q[2 * i + 1];
            if (Math.hypot(ex, ey) < 0.08 * Math.min(w, h)) return null;
        }
        return q;
    }

    /**
     * The corners where straight lines fitted to the region's four edges meet: the edge pixels (the region's, next to the
     * outside — not to the holes the letters make — and not on the photo's border) go to the side of the first corners
     * they are nearest; a line through each side's (total least squares, twice dropping those further than 1.5 px). A
     * side with too few pixels, or a meeting point far from the first corner, keeps the first corner.
     */
    static double[] refine(double[] q0, int[] label, int region, boolean[] out, int sw, int sh) {
        double reach = 0.06 * Math.min(sw, sh);
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
        double r = 1 / wh;
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
        int ow = Math.max(1, (int) Math.round(W * grow)), oh = Math.max(1, (int) Math.round(H * grow));
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

    /** Bilinear sample at (x, y) (pixel centres at integers), {@code fill} outside. */
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

    /** The image turned by {@code deg} (positive: counter-clockwise, which levels text falling to the right), white around. */
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
                out[y * im.w + x] = sample(im.px, im.w, im.h, sx, sy, 0xFFFFFFFF);
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
                boolean ink = v[y * w + x] < 110 || v[y * w + x] < Math.min(mean - 12, 200);
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
        Image im = page != null ? warp(argb, w, h, page, maxSide) : fit(argb, w, h, maxSide);
        if (level) im = rotate(im, skew(im.px, im.w, im.h));
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
