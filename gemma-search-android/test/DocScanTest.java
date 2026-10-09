import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Locale;
import java.util.Random;

import javax.imageio.ImageIO;

import io.github.teoplaydor.semsearch.core.DocScan;

/**
 * A document photographed as people do it, made ready to print: a sheet lying turned on a darker table, under light
 * falling off to one side — its corners found, cut out and straightened to the sheet's proportions with level text;
 * text photographed at a slant — its angle found and levelled; shading and yellowed paper — strict black and white
 * with the ink black and the paper white, grey with white paper, colour with a red stamp still red; no sheet to cut
 * when it fills the photo or lies on something as light. Writes the stages to <out dir> to look at.
 * usage: DocScanTest <out dir>
 */
public class DocScanTest {
    static int bad;

    static void check(boolean ok, String what) {
        if (!ok) bad++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static int[] px(BufferedImage b) {
        int[] p = new int[b.getWidth() * b.getHeight()];
        b.getRGB(0, 0, b.getWidth(), b.getHeight(), p, 0, b.getWidth());
        return p;
    }

    static void save(DocScan.Image im, File f) throws Exception {
        BufferedImage b = new BufferedImage(im.w, im.h, BufferedImage.TYPE_INT_RGB);
        b.setRGB(0, 0, im.w, im.h, im.px, 0, im.w);
        ImageIO.write(b, "png", f);
    }

    /** A sheet with lines of "words" (dark bars), a red stamp; {@code ink} where the words are. */
    static BufferedImage sheet(int w, int h, double slantDeg, boolean[] ink, Color paper) {
        BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = b.createGraphics();
        g.setColor(paper);
        g.fillRect(0, 0, w, h);
        g.rotate(Math.toRadians(slantDeg), w / 2.0, h / 2.0);
        g.setColor(new Color(35, 35, 40));
        Random r = new Random(4);
        for (int y = 90; y < h - 120; y += 30) {
            int x = 70;
            while (x < w - 120) {
                int ww = 12 + r.nextInt(50);
                g.fillRect(x, y, ww, 11);
                x += ww + 9 + r.nextInt(6);
            }
        }
        g.setColor(new Color(200, 30, 40));
        g.fillOval(w - 230, h - 200, 120, 120);
        g.dispose();
        if (ink != null) {
            int[] p = px(b);
            for (int i = 0; i < p.length; i++) ink[i] = DocScanLuma(p[i]) < 80;
        }
        return b;
    }

    static int DocScanLuma(int p) {
        return (((p >> 16) & 0xFF) * 77 + ((p >> 8) & 0xFF) * 150 + (p & 0xFF) * 29) >> 8;
    }

    /** Light falling off from the right to the left (×0.5 at the left edge). */
    static void shade(BufferedImage b) {
        for (int y = 0; y < b.getHeight(); y++) {
            for (int x = 0; x < b.getWidth(); x++) {
                int p = b.getRGB(x, y);
                double k = 0.5 + 0.5 * x / (double) b.getWidth();
                int r = (int) (((p >> 16) & 0xFF) * k), g = (int) (((p >> 8) & 0xFF) * k), bl = (int) ((p & 0xFF) * k);
                b.setRGB(x, y, (r << 16) | (g << 8) | bl);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args[0]);
        out.mkdirs();

        // 1. a sheet turned 9° on a dark wooden table, shaded
        int pw = 700, ph = 990;
        BufferedImage page = sheet(pw, ph, 0, null, new Color(236, 234, 228));
        BufferedImage scene = new BufferedImage(1400, 1050, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scene.createGraphics();
        Random r = new Random(1);
        for (int y = 0; y < scene.getHeight(); y += 6) {
            g.setColor(new Color(70 + r.nextInt(25), 50 + r.nextInt(15), 35 + r.nextInt(10)));
            g.fillRect(0, y, scene.getWidth(), 6);
        }
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        AffineTransform at = new AffineTransform();
        at.translate(700, 525);
        at.rotate(Math.toRadians(9));
        at.scale(0.85, 0.85);
        at.translate(-pw / 2.0, -ph / 2.0);
        g.drawImage(page, at, null);
        g.dispose();
        shade(scene);
        ImageIO.write(scene, "png", new File(out, "1-photo.png"));
        int[] sp = px(scene);
        float[] q = DocScan.findPage(sp, scene.getWidth(), scene.getHeight());
        double[][] truth = {{0, 0}, {pw, 0}, {pw, ph}, {0, ph}};
        double worst = q == null ? 1e9 : 0;
        for (int i = 0; i < 4 && q != null; i++) {
            Point2D t = at.transform(new Point2D.Double(truth[i][0], truth[i][1]), null);
            worst = Math.max(worst, Math.hypot(q[2 * i] - t.getX(), q[2 * i + 1] - t.getY()));
        }
        double diag = Math.hypot(scene.getWidth(), scene.getHeight());
        System.out.println(String.format(Locale.ROOT, "  the sheet's corners: worst %.1f px of %.0f", worst, diag));
        check(worst < 0.015 * diag, "a sheet turned on a darker table, shaded: its four corners");
        DocScan.Image flat = DocScan.warp(sp, scene.getWidth(), scene.getHeight(), q, 1400);
        save(flat, new File(out, "1-sheet.png"));
        double aspect = (double) flat.h / flat.w, skew1 = DocScan.skew(flat.px, flat.w, flat.h);
        System.out.println(String.format(Locale.ROOT, "  cut out: %dx%d (%.3f, the sheet's %.3f), text at %.2f°", flat.w, flat.h, aspect,
                (double) ph / pw, skew1));
        check(Math.abs(aspect / ((double) ph / pw) - 1) < 0.04 && Math.abs(skew1) <= 0.4,
                "cut out and straightened: the sheet's proportions, the text level");
        DocScan.Image bw1 = DocScan.process(sp, scene.getWidth(), scene.getHeight(), q, true, DocScan.BW, 1400);
        save(bw1, new File(out, "1-scan.png"));

        // 2. a sheet filling the photo, the text at a slant (3.5°, falling to the right)
        boolean[] ink = new boolean[1000 * 1400];
        BufferedImage slanted = sheet(1000, 1400, 3.5, null, new Color(240, 240, 236));
        int[] s2 = px(slanted);
        check(DocScan.findPage(s2, 1000, 1400) == null, "a sheet filling the photo: nothing to cut");
        double a2 = DocScan.skew(s2, 1000, 1400);
        DocScan.Image level = DocScan.rotate(new DocScan.Image(s2, 1000, 1400), a2);
        double after = DocScan.skew(level.px, level.w, level.h);
        System.out.println(String.format(Locale.ROOT, "  text at 3.5°: found %.2f°, after levelling %.2f°", a2, after));
        check(Math.abs(a2 - 3.5) <= 0.3 && Math.abs(after) <= 0.3, "slanted text: its angle found and levelled");
        BufferedImage other = sheet(1000, 1400, -6, null, new Color(240, 240, 236));
        double a3 = DocScan.skew(px(other), 1000, 1400);
        check(Math.abs(a3 + 6) <= 0.3, String.format(Locale.ROOT, "and the other way: -6° found as %.2f°", a3));

        // 3. shading and yellowed paper: the scanner's look
        BufferedImage yellow = sheet(1000, 1400, 0, ink, new Color(238, 226, 190));
        shade(yellow);
        int[] s3 = px(yellow);
        DocScan.Image bw = new DocScan.Image(DocScan.scan(s3, 1000, 1400, DocScan.BW), 1000, 1400);
        save(bw, new File(out, "3-bw.png"));
        long inkN = 0, inkBlack = 0, paperN = 0, paperWhite = 0;
        boolean only = true;
        for (int i = 0; i < ink.length; i++) {
            int v = bw.px[i] & 0xFFFFFF;
            only &= v == 0 || v == 0xFFFFFF;
            // the stamp is neither: left out of both counts
            int x = i % 1000, y = i / 1000;
            boolean stamp = Math.hypot(x - (1000 - 170), y - (1400 - 140)) < 66;
            if (stamp) continue;
            if (ink[i]) {
                inkN++;
                if (v == 0) inkBlack++;
            } else if (!near(ink, i, 1000, 1400, 2)) {
                paperN++;
                if (v == 0xFFFFFF) paperWhite++;
            }
        }
        System.out.println(String.format(Locale.ROOT, "  black and white: ink black %.1f%%, paper white %.2f%%", 100.0 * inkBlack / inkN,
                100.0 * paperWhite / paperN));
        check(only && inkBlack >= 0.9 * inkN && paperWhite >= 0.995 * paperN, "strict black and white: the ink black, the paper white, "
                + "shading and yellow gone");
        int[] gray = DocScan.scan(s3, 1000, 1400, DocScan.GRAY), color = DocScan.scan(s3, 1000, 1400, DocScan.COLOR);
        save(new DocScan.Image(gray, 1000, 1400), new File(out, "3-gray.png"));
        save(new DocScan.Image(color, 1000, 1400), new File(out, "3-color.png"));
        long gs = 0, gn = 0, cr = 0, cg = 0, cb = 0;
        for (int y = 40; y < 80; y++) {
            for (int x = 20; x < 980; x++) {
                gs += gray[y * 1000 + x] & 0xFF;
                gn++;
                int p = color[y * 1000 + x];
                cr += (p >> 16) & 0xFF;
                cg += (p >> 8) & 0xFF;
                cb += p & 0xFF;
            }
        }
        int stamp = color[(1400 - 140) * 1000 + 1000 - 170];
        int sr = (stamp >> 16) & 0xFF, sg = (stamp >> 8) & 0xFF;
        System.out.println(String.format(Locale.ROOT, "  grey: paper %.0f; colour: paper %.0f/%.0f/%.0f, stamp %d/%d", (double) gs / gn,
                (double) cr / gn, (double) cg / gn, (double) cb / gn, sr, sg));
        int stampBw = bw.px[(1400 - 140) * 1000 + 1000 - 170] & 0xFFFFFF;
        check(stampBw == 0, "a filled stamp is black inside too, not a ring");
        check((double) gs / gn >= 245 && Math.min((double) cr / gn, Math.min((double) cg / gn, (double) cb / gn)) >= 240 && sr > sg + 80,
                "grey and colour: white paper all over, the red stamp still red");

        // 5. an A4 sheet photographed at a slant (a pinhole camera, the sheet leaning back 40°): its sides on the photo
        // are far from 1:1.414, its real proportions come back, its lines straight
        double fpx = 1100;
        double[][] cam = project(0.210, 0.297, Math.toRadians(40), Math.toRadians(6), 0.46, fpx, 1600, 1200);
        float[] tq = new float[8];
        for (int i = 0; i < 4; i++) {
            tq[2 * i] = (float) cam[i][0];
            tq[2 * i + 1] = (float) cam[i][1];
        }
        double naive = (Math.hypot(tq[6] - tq[0], tq[7] - tq[1]) + Math.hypot(tq[4] - tq[2], tq[5] - tq[3]))
                / (Math.hypot(tq[2] - tq[0], tq[3] - tq[1]) + Math.hypot(tq[4] - tq[6], tq[5] - tq[7]));
        double real = DocScan.aspect(tq, 1600, 1200);
        System.out.println(String.format(Locale.ROOT, "  A4 at 40°: sides on the photo %.3f, real proportions %.3f", naive, real));
        check(Math.abs(naive - Math.sqrt(2)) > 0.15 && Math.abs(real - Math.sqrt(2)) < 1e-9, "a sheet at a slant: its real proportions, "
                + "not the photo's");
        double[][] card = project(0.1, 0.1, Math.toRadians(35), Math.toRadians(-10), 0.35, fpx, 1600, 1200);
        float[] cq = new float[8];
        for (int i = 0; i < 4; i++) {
            cq[2 * i] = (float) card[i][0];
            cq[2 * i + 1] = (float) card[i][1];
        }
        double sq = DocScan.aspect(cq, 1600, 1200);
        check(Math.abs(sq - 1) < 0.03, String.format(Locale.ROOT, "a square card at a slant: square (%.3f), not snapped to a paper size", sq));
        // the photo itself: the sheet rendered through the camera onto a dark table
        BufferedImage a4 = sheet(840, 1188, 0, null, new Color(238, 236, 230));
        BufferedImage slant = render(a4, tq, 1600, 1200);
        ImageIO.write(slant, "png", new File(out, "5-photo.png"));
        int[] s5 = px(slant);
        float[] q5 = DocScan.findPage(s5, 1600, 1200);
        double worst5 = q5 == null ? 1e9 : 0;
        for (int i = 0; i < 4 && q5 != null; i++) worst5 = Math.max(worst5, Math.hypot(q5[2 * i] - tq[2 * i], q5[2 * i + 1] - tq[2 * i + 1]));
        DocScan.Image flat5 = q5 == null ? null : DocScan.warp(s5, 1600, 1200, q5, 3508);
        double skew5 = flat5 == null ? 99 : DocScan.skew(flat5.px, flat5.w, flat5.h);
        if (flat5 != null) save(DocScan.process(s5, 1600, 1200, q5, true, DocScan.BW, 3508), new File(out, "5-scan.png"));
        System.out.println(String.format(Locale.ROOT, "  its corners on the photo: worst %.1f px; cut out %s, text at %.2f°", worst5,
                flat5 == null ? "-" : flat5.w + "x" + flat5.h, skew5));
        check(worst5 < 12 && flat5 != null && Math.abs((double) flat5.h / flat5.w - Math.sqrt(2)) < 0.002 && Math.abs(skew5) <= 0.4,
                "photographed at a slant: the corners on the edges (lines fitted), A4 proportions, lines level");

        // 4. a sheet on a white table: nothing apart from it to cut by
        BufferedImage white = new BufferedImage(1200, 900, BufferedImage.TYPE_INT_RGB);
        Graphics2D gw = white.createGraphics();
        gw.setColor(new Color(235, 235, 235));
        gw.fillRect(0, 0, 1200, 900);
        gw.drawImage(sheet(500, 700, 0, null, new Color(240, 240, 238)), 350, 100, null);
        gw.dispose();
        check(DocScan.findPage(px(white), 1200, 900) == null, "a sheet on something as light: nothing to cut by");

        System.out.println(bad == 0 ? "DOC SCAN OK" : bad + " FAILED");
        if (bad != 0) System.exit(1);
    }

    /**
     * A sheet (width × height, metres) lying in front of a pinhole camera (focal length {@code f} px, image w×h): leaning
     * back by {@code tilt} about its horizontal axis, turned by {@code turn} in its plane, its middle {@code dist} away.
     * Its corners on the photo: top left, top right, bottom right, bottom left.
     */
    static double[][] project(double sw, double sh, double tilt, double turn, double dist, double f, int w, int h) {
        double[][] corners = {{-sw / 2, -sh / 2}, {sw / 2, -sh / 2}, {sw / 2, sh / 2}, {-sw / 2, sh / 2}};
        double[][] out = new double[4][];
        for (int i = 0; i < 4; i++) {
            double x = corners[i][0], y = corners[i][1];
            double xr = x * Math.cos(turn) - y * Math.sin(turn), yr = x * Math.sin(turn) + y * Math.cos(turn);
            // leaning back: the top further away
            double Y = yr * Math.cos(tilt), Z = dist - yr * Math.sin(tilt);
            out[i] = new double[]{f * xr / Z + w / 2.0, f * Y / Z + h / 2.0 + 60};
        }
        return out;
    }

    /** The texture mapped onto the quad of a w×h photo (a dark table around), through the homography between them. */
    static BufferedImage render(BufferedImage tex, float[] q, int w, int h) {
        double tw = tex.getWidth(), th = tex.getHeight();
        // the homography from the photo to the texture: solved from the four corners (8×8, Gauss)
        double[][] dst = {{0, 0}, {tw, 0}, {tw, th}, {0, th}};
        double[][] a = new double[8][9];
        for (int i = 0; i < 4; i++) {
            double x = q[2 * i], y = q[2 * i + 1], u = dst[i][0], v = dst[i][1];
            a[2 * i] = new double[]{x, y, 1, 0, 0, 0, -u * x, -u * y, u};
            a[2 * i + 1] = new double[]{0, 0, 0, x, y, 1, -v * x, -v * y, v};
        }
        for (int c = 0; c < 8; c++) {
            int piv = c;
            for (int r = c + 1; r < 8; r++) if (Math.abs(a[r][c]) > Math.abs(a[piv][c])) piv = r;
            double[] t = a[c];
            a[c] = a[piv];
            a[piv] = t;
            for (int r = 0; r < 8; r++) {
                if (r == c) continue;
                double k = a[r][c] / a[c][c];
                for (int j = c; j < 9; j++) a[r][j] -= k * a[c][j];
            }
        }
        double[] m = new double[9];
        for (int i = 0; i < 8; i++) m[i] = a[i][8] / a[i][i];
        m[8] = 1;
        BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(5);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double cx = x + 0.5, cy = y + 0.5, z = m[6] * cx + m[7] * cy + 1;
                double u = (m[0] * cx + m[1] * cy + m[2]) / z, v = (m[3] * cx + m[4] * cy + m[5]) / z;
                int c;
                if (u >= 0 && v >= 0 && u < tw && v < th) {
                    c = tex.getRGB((int) u, (int) v) & 0xFFFFFF;
                    // light falling off towards the bottom
                    double k = 0.65 + 0.35 * (1 - y / (double) h);
                    c = ((int) (((c >> 16) & 0xFF) * k) << 16) | ((int) (((c >> 8) & 0xFF) * k) << 8) | (int) ((c & 0xFF) * k);
                } else {
                    int g = 55 + r.nextInt(20);
                    c = (g << 16) | ((g - 12) << 8) | (g - 22);
                }
                b.setRGB(x, y, c);
            }
        }
        return b;
    }

    static boolean near(boolean[] ink, int i, int w, int h, int r) {
        int x = i % w, y = i / w;
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                int xx = x + dx, yy = y + dy;
                if (xx >= 0 && yy >= 0 && xx < w && yy < h && ink[yy * w + xx]) return true;
            }
        }
        return false;
    }
}
