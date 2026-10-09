package io.github.teoplaydor.semsearch.core;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;

/**
 * A PDF of scanned pages, written as it goes (one page in memory at a time): each page one picture filling it. Black
 * and white pages as one bit a pixel (deflated: a page of text takes a few dozen kilobytes), grey ones as a byte a pixel
 * (deflated), colour ones as the JPEG they come as. Each page as large as the picture fitted into an A4 sheet (either
 * way up) in its own proportions — an A4 scan an A4 page, a receipt a narrow one.
 */
public final class PdfPages implements Closeable {
    /** A4 in points (1/72 inch). */
    public static final double A4_W = 595.28, A4_H = 841.89;
    private static final Charset LATIN = Charset.forName("ISO-8859-1");

    private final OutputStream out;
    private long pos;
    /** Where each object starts, by its number less one (objects 1 and 2, the catalogue and the page tree, come last). */
    private final List<Long> offsets = new ArrayList<Long>();
    private final List<Integer> pages = new ArrayList<Integer>();
    private boolean closed;

    public PdfPages(OutputStream out) throws IOException {
        this.out = out;
        // a binary comment: the file is binary (mail and transfer programs leave it alone)
        write("%PDF-1.4\n%âãÏÓ\n");
        offsets.add(-1L);
        offsets.add(-1L);
    }

    /** How many pages so far. */
    public int pages() {
        return pages.size();
    }

    /** The page's size in points for a picture w×h: fitted into A4 (portrait or landscape, as the picture), its proportions. */
    public static double[] pageSize(int w, int h) {
        double bw = w <= h ? A4_W : A4_H, bh = w <= h ? A4_H : A4_W;
        double k = Math.min(bw / w, bh / h);
        return new double[]{w * k, h * k};
    }

    /**
     * A black and white page from ARGB pixels (as DocScan.scan gives them in its black and white look: a pixel dark
     * when its grey is under half).
     */
    public void addBlackWhite(int[] argb, int w, int h) throws IOException {
        int stride = (w + 7) / 8;
        byte[] bits = new byte[stride * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = argb[y * w + x];
                int grey = (((p >> 16) & 0xFF) * 77 + ((p >> 8) & 0xFF) * 150 + (p & 0xFF) * 29) >> 8;
                // DeviceGray, one bit: 1 is white
                if (grey >= 128) bits[y * stride + (x >> 3)] |= (byte) (0x80 >> (x & 7));
            }
        }
        addImage(w, h, "/ColorSpace /DeviceGray /BitsPerComponent 1 /Filter /FlateDecode", deflate(bits));
    }

    /** A grey page from ARGB pixels (their grey). */
    public void addGrey(int[] argb, int w, int h) throws IOException {
        byte[] g = new byte[w * h];
        for (int i = 0; i < g.length; i++) {
            int p = argb[i];
            g[i] = (byte) ((((p >> 16) & 0xFF) * 77 + ((p >> 8) & 0xFF) * 150 + (p & 0xFF) * 29) >> 8);
        }
        addImage(w, h, "/ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode", deflate(g));
    }

    /** A colour page from a JPEG of w×h (RGB). */
    public void addJpeg(byte[] jpeg, int w, int h) throws IOException {
        addImage(w, h, "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode", jpeg);
    }

    private void addImage(int w, int h, String format, byte[] data) throws IOException {
        if (closed) throw new IllegalStateException("closed");
        double[] size = pageSize(w, h);
        int image = begin();
        write(String.format(Locale.ROOT, "<< /Type /XObject /Subtype /Image /Width %d /Height %d %s /Length %d >>\nstream\n", w, h, format, data.length));
        write(data);
        write("\nendstream\nendobj\n");
        byte[] draw = String.format(Locale.ROOT, "q %.2f 0 0 %.2f 0 0 cm /Im0 Do Q\n", size[0], size[1]).getBytes(LATIN);
        int content = begin();
        write("<< /Length " + draw.length + " >>\nstream\n");
        write(draw);
        write("endstream\nendobj\n");
        int page = begin();
        write(String.format(Locale.ROOT, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %.2f %.2f] /Resources << /XObject << /Im0 %d 0 R >> >> "
                + "/Contents %d 0 R >>\nendobj\n", size[0], size[1], image, content));
        pages.add(page);
        out.flush();
    }

    /** The page tree, the catalogue, the cross-reference table: the file complete. The stream is not closed. */
    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        offsets.set(1, pos);
        StringBuilder kids = new StringBuilder();
        for (int p : pages) kids.append(p).append(" 0 R ");
        write("2 0 obj\n<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + pages.size() + " >>\nendobj\n");
        offsets.set(0, pos);
        write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        long xref = pos;
        StringBuilder x = new StringBuilder("xref\n0 " + (offsets.size() + 1) + "\n0000000000 65535 f \n");
        for (long o : offsets) x.append(String.format(Locale.ROOT, "%010d 00000 n \n", o));
        write(x.toString());
        write("trailer\n<< /Size " + (offsets.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        out.flush();
    }

    /** A new object begun: its number. */
    private int begin() throws IOException {
        offsets.add(pos);
        int n = offsets.size();
        write(n + " 0 obj\n");
        return n;
    }

    private void write(String s) throws IOException {
        write(s.getBytes(LATIN));
    }

    private void write(byte[] b) throws IOException {
        out.write(b);
        pos += b.length;
    }

    private static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream o = new ByteArrayOutputStream(Math.max(64, data.length / 8));
        byte[] buf = new byte[1 << 16];
        while (!d.finished()) {
            int n = d.deflate(buf);
            o.write(buf, 0, n);
        }
        d.end();
        return o.toByteArray();
    }
}
