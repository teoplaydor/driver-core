import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

import javax.imageio.ImageIO;

import io.github.teoplaydor.semsearch.core.DocScan;
import io.github.teoplaydor.semsearch.core.PdfPages;

/**
 * Scans as a PDF: pages in black and white (a bit a pixel), grey and colour (JPEG) one after another; the file well
 * formed (every cross-reference entry at its object, the page tree counting them), each page in its picture's
 * proportions fitted into A4 (an A4 scan an A4 page, a receipt a narrow one), the black and white picture coming back
 * pixel for pixel, a page of text a few dozen kilobytes.
 * usage: PdfPagesTest <out dir>
 */
public class PdfPagesTest {
    static int bad;
    static final Charset LATIN = Charset.forName("ISO-8859-1");

    static void check(boolean ok, String what) {
        if (!ok) bad++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    /** A page of "words" (dark bars), w×h, in black and white as DocScan gives it. */
    static int[] textPage(int w, int h, long seed) {
        BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = b.createGraphics();
        g.setColor(new Color(240, 238, 232));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(30, 30, 35));
        Random r = new Random(seed);
        for (int y = w / 10; y < h - w / 10; y += 30) {
            int x = w / 12;
            while (x < w - w / 12) {
                int ww = 12 + r.nextInt(50);
                g.fillRect(x, y, Math.min(ww, w - w / 12 - x), 11);
                x += ww + 9;
            }
        }
        g.dispose();
        int[] px = b.getRGB(0, 0, w, h, null, 0, w);
        return DocScan.scan(px, w, h, DocScan.BW);
    }

    public static void main(String[] args) throws Exception {
        int[] a4 = textPage(1754, 2480, 1), receipt = textPage(600, 2000, 2), grey = DocScan.scan(textPage(1240, 1754, 3), 1240, 1754, DocScan.GRAY);
        BufferedImage colour = new BufferedImage(1754, 1240, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = colour.createGraphics();
        g.setColor(new Color(200, 60, 40));
        g.fillRect(0, 0, 1754, 1240);
        g.dispose();
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(colour, "jpg", jpeg);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PdfPages pdf = new PdfPages(bytes);
        pdf.addBlackWhite(a4, 1754, 2480);
        int afterFirst = bytes.size();
        pdf.addBlackWhite(receipt, 600, 2000);
        pdf.addGrey(grey, 1240, 1754);
        pdf.addJpeg(jpeg.toByteArray(), 1754, 1240);
        check(pdf.pages() == 4, "four pages added");
        pdf.close();
        byte[] file = bytes.toByteArray();
        File out = new File(args[0], "scans.pdf");
        out.getParentFile().mkdirs();
        FileOutputStream fo = new FileOutputStream(out);
        fo.write(file);
        fo.close();
        String s = new String(file, LATIN);
        System.out.println("  " + file.length / 1024 + " KB, the first (A4, text) page " + afterFirst / 1024 + " KB — " + out);
        check(s.startsWith("%PDF-1.4\n") && s.endsWith("%%EOF\n"), "a PDF from start to end");
        check(afterFirst < 200 * 1024, "a black and white A4 page of text: a few dozen kilobytes (" + afterFirst / 1024 + " KB)");

        // the cross-reference table: every entry where its object starts
        int sx = s.lastIndexOf("startxref\n");
        int xref = Integer.parseInt(s.substring(sx + 10, s.indexOf('\n', sx + 10)).trim());
        check(s.startsWith("xref\n", xref), "startxref points at the table");
        String[] lines = s.substring(xref).split("\n");
        int count = Integer.parseInt(lines[1].split(" ")[1]);
        boolean all = lines[2].equals("0000000000 65535 f ");
        for (int i = 1; i < count; i++) {
            long off = Long.parseLong(lines[2 + i].substring(0, 10));
            all &= s.startsWith(i + " 0 obj\n", (int) off) && lines[2 + i].length() == 19;
        }
        check(all && count == 1 + 2 + 4 * 3, "every entry of the table at its object (" + (count - 1) + " objects), each 20 bytes");
        check(s.contains("/Type /Pages /Kids [") && s.contains("/Count 4 >>") && s.contains("/Root 1 0 R"), "the page tree counts four pages, "
                + "the catalogue is the root");

        // the pages' sizes: A4 for the A4 scan, narrow for the receipt, landscape for the wide colour one
        Matcher m = Pattern.compile("/MediaBox \\[0 0 ([0-9.]+) ([0-9.]+)\\]").matcher(s);
        java.util.List<double[]> boxes = new java.util.ArrayList<double[]>();
        while (m.find()) boxes.add(new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))});
        boolean sizes = boxes.size() == 4 && Math.abs(boxes.get(0)[0] - 595.28) < 1 && Math.abs(boxes.get(0)[1] - 841.89) < 2
                && Math.abs(boxes.get(1)[1] - 841.89) < 1 && Math.abs(boxes.get(1)[0] / boxes.get(1)[1] - 0.3) < 0.01
                && boxes.get(3)[0] > boxes.get(3)[1];
        StringBuilder bs = new StringBuilder();
        for (double[] b : boxes) bs.append(String.format(java.util.Locale.ROOT, " %.0f×%.0f", b[0], b[1]));
        System.out.println("  pages (points):" + bs);
        check(sizes, "pages in their pictures' proportions, fitted into A4: A4, a narrow receipt, a landscape page");

        // the first picture back, bit for bit
        int st = s.indexOf("stream\n") + 7, len = Integer.parseInt(s.substring(s.indexOf("/Length ") + 8, s.indexOf(" >>", s.indexOf("/Length "))));
        Inflater inf = new Inflater();
        inf.setInput(file, st, len);
        int stride = (1754 + 7) / 8;
        byte[] bits = new byte[stride * 2480];
        int got = inf.inflate(bits);
        boolean same = got == bits.length && inf.finished();
        for (int y = 0; y < 2480 && same; y++) {
            for (int x = 0; x < 1754 && same; x++) {
                boolean white = (bits[y * stride + (x >> 3)] & (0x80 >> (x & 7))) != 0;
                same = white == ((a4[y * 1754 + x] & 0xFF) >= 128);
            }
        }
        check(same, "the black and white picture back pixel for pixel");
        System.out.println(bad == 0 ? "PDF OK" : bad + " FAILED");
        if (bad != 0) System.exit(1);
    }
}
