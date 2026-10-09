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
