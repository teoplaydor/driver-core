import io.github.teoplaydor.semsearch.core.PdfText;
import io.github.teoplaydor.semsearch.core.TextExtract;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.Deflater;

/**
 * The text of documents for their meaning. Made by real programs (test/files/make_docs.py): LibreOffice's Word,
 * OpenDocument text / sheet / slides, RTF, EPUB, Excel and PDF, python-pptx's PowerPoint, fpdf2's PDF (an embedded
 * TrueType font and Helvetica), the LibreOffice PDF with compressed object streams — each must hold its phrases, a PDF's
 * words must be pdftotext's (poppler). Made here: text in Windows-1251 and UTF-16, HTML with a script, FictionBook,
 * RTF with \'hh and \\u escapes, and PDFs a reader meets in the wild: Cyrillic through /Differences glyph names, words
 * apart by TJ kerning and by Td moves only, text in a form XObject, LZW / ASCII85 / ASCIIHex streams, a wrong
 * cross-reference table, an encrypted file (no text), a scan (no text).
 * usage: TextExtractTest <dir with make_docs.py's files>
 */
public class TextExtractTest {
    static int bad;

    static void check(boolean ok, String what) {
        if (!ok) bad++;
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    static String text(byte[] data, String name) throws Exception {
        return TextExtract.extract(new ByteArrayInputStream(data), TextExtract.type(name, null), TextExtract.MAX_CHARS);
    }

    static String file(File dir, String name) throws Exception {
        File f = new File(dir, name);
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            return TextExtract.extract(in, TextExtract.type(name, null), TextExtract.MAX_CHARS);
        }
    }

    static boolean has(String s, String... phrases) {
        if (s == null) return false;
        for (String p : phrases) if (!s.contains(p)) return false;
        return true;
    }

    /** The share of pdftotext's words that are in ours. */
    static double sameWords(String ours, String theirs) {
        Set<String> a = words(ours), b = words(theirs);
        if (b.isEmpty()) return 0;
        int n = 0;
        for (String w : b) if (a.contains(w)) n++;
        return n / (double) b.size();
    }

    static Set<String> words(String s) {
        Set<String> out = new HashSet<String>();
        for (String w : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (w.length() > 1) out.add(w);
        return out;
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        String[] contract = {"Договор поставки № 17/2024", "кофемашины модели «Ромашка-3000»", "Фильтр для воды",
                "гарантия два года", "The supplier ships coffee machines to the warehouse in Kazan."};
        for (String n : new String[]{"contract.docx", "contract.odt", "contract.rtf", "contract.epub", "contract.pdf", "objstm.pdf"}) {
            String s = file(dir, n);
            if (s == null) {
                System.out.println("skip " + n + " (no LibreOffice)");
                continue;
            }
            check(has(s, contract), n + ": " + s.length() + " chars, the contract's phrases");
        }
        String[] stock = {"Товар", "Количество", "Кофемашина", "Фильтр для воды", "Самара"};
        for (String n : new String[]{"stock.xlsx", "stock.ods"}) {
            String s = file(dir, n);
            if (s != null) check(has(s, stock), n + ": the cells' text");
        }
        for (String n : new String[]{"deck.pptx", "deck.odp"}) {
            String s = file(dir, n);
            if (s != null) check(has(s, "Отчёт о продажах", "Выручка выросла на 12 процентов", "Открыть магазин в Самаре")
                    && s.indexOf("Отчёт") < s.indexOf("План на весну"), n + ": the slides' text, in order");
        }
        String receipt = file(dir, "receipt.pdf");
        check(has(receipt, "Квитанция об оплате электроэнергии за октябрь", "Лицевой счёт указан на обороте",
                "Electricity bill for October, amount due 2345 roubles."), "receipt.pdf (fpdf2: DejaVu embedded, Helvetica)");
        for (String n : new String[]{"contract.pdf", "objstm.pdf", "receipt.pdf"}) {
            File ref = new File(dir, n + ".txt");
            String ours = file(dir, n);
            if (ref.exists() && ours != null) {
                double same = sameWords(ours, new String(java.nio.file.Files.readAllBytes(ref.toPath()), StandardCharsets.UTF_8));
                check(same >= 0.98, String.format(Locale.ROOT, "%s: %.0f%% of pdftotext's words", n, same * 100));
            }
        }

        // plain text in the encodings one meets
        String ru = "Список покупок: молоко, хлеб, сыр.\nЗвонить маме в субботу.";
        check(text(ru.getBytes("windows-1251"), "list.txt").equals(ru), "Windows-1251 text");
        byte[] u16 = ru.getBytes(StandardCharsets.UTF_16LE);
        byte[] bom = new byte[u16.length + 2];
        bom[0] = (byte) 0xFF;
        bom[1] = (byte) 0xFE;
        System.arraycopy(u16, 0, bom, 2, u16.length);
        check(text(bom, "list.txt").equals(ru), "UTF-16 with a BOM");
        check(text(ru.getBytes(StandardCharsets.UTF_8), "notes.md").equals(ru), "UTF-8 Markdown");
        byte[] cut = Arrays.copyOf(ru.getBytes(StandardCharsets.UTF_8), 22); // inside "к" of "покупок"
        check(text(cut, "list.txt").startsWith("Список пок") && !text(cut, "list.txt").contains("�"),
                "a cut in the middle of a character: no garbage");
        check(text(new byte[]{1, 2, 0, 3, 0, 0, 5}, "data.txt").isEmpty(), "a binary file: no text");
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 2000; i++) longText.append("слово ");
        check(text(longText.toString().getBytes(StandardCharsets.UTF_8), "long.txt").length() <= TextExtract.MAX_CHARS,
                "at most " + TextExtract.MAX_CHARS + " characters");

        String html = "<html><head><title>Рецепт</title><style>p{color:red}</style><script>var x='<p>нет</p>';</script></head>"
                + "<body><h1>Блины</h1><p>Мука&nbsp;200 г, молоко &laquo;домашнее&raquo; &#8212; 0,5 л</p><!-- <p>скрыто</p> --></body></html>";
        String h = text(html.getBytes(StandardCharsets.UTF_8), "recipe.html");
        check(has(h, "Рецепт", "Блины", "Мука 200 г, молоко «домашнее» — 0,5 л") && !h.contains("нет") && !h.contains("скрыто")
                && !h.contains("color"), "HTML: entities, no script, style or comment: " + h.replace('\n', '|'));

        String fb2 = "<?xml version=\"1.0\" encoding=\"windows-1251\"?><FictionBook><description><title-info><author><first-name>Иван"
                + "</first-name><last-name>Петров</last-name></author><book-title>Лесные сказки</book-title></title-info></description>"
                + "<body><section><title><p>Глава первая</p></title><p>Жил-был ёжик.</p><empty-line/><p>Он любил грибы.</p></section></body>"
                + "<binary id=\"c.jpg\">QUJD</binary></FictionBook>";
        String f = text(fb2.getBytes("windows-1251"), "tales.fb2");
        check(has(f, "Лесные сказки", "Иван Петров", "Глава первая", "Жил-был ёжик.", "Он любил грибы.") && !f.contains("QUJD"),
                "FictionBook in Windows-1251: title, author, text, no pictures");

        String rtf = "{\\rtf1\\ansi\\ansicpg1251\\deff0{\\fonttbl{\\f0 Times New Roman;}}{\\*\\generator Writer;}"
                + "\\pard \\'cf\\'f0\\'e8\\'e2\\'e5\\'f2, \\u1084?\\u1080?\\u1088?!\\par\\pard Second\\tab line\\par}";
        String r = text(rtf.getBytes(StandardCharsets.ISO_8859_1), "a.rtf");
        check(r.equals("Привет, мир!\nSecond line"), "RTF: \\'hh in its code page, \\uN without the fallback, no tables: " + r);

        // PDFs a reader meets
        check(PdfText.extract(pdfCyrillicDifferences(), 6000).trim().equals("Привет мир"), "PDF: Cyrillic through /Differences (afii names)");
        String spaced = PdfText.extract(pdfSpacing(), 6000);
        check(spaced.contains("Words apart by kerning") && spaced.contains("Each word placed on its own")
                && spaced.contains("Second line"), "PDF: words by TJ kerning and by Td moves, lines: " + spaced.replace('\n', '|'));
        check(PdfText.extract(pdfFiltersAndForm(), 6000).contains("Inside a form")
                && PdfText.extract(pdfFiltersAndForm(), 6000).contains("Hex and LZW"), "PDF: LZW, ASCII85, ASCIIHex, a form XObject");
        check(PdfText.extract(pdfEncrypted(), 6000).isEmpty(), "PDF: encrypted — no text (its page as a picture instead)");
        check(PdfText.extract(pdfScan(), 6000).trim().isEmpty() && PdfText.pages(pdfScan()) == 1, "PDF: a scan — no text, one page");
        check(PdfText.pages(pdfSpacing()) == 2, "PDF: pages counted");
        check(PdfText.extract("not a pdf at all".getBytes(StandardCharsets.US_ASCII), 100).isEmpty()
                && PdfText.extract(Arrays.copyOf(pdfSpacing(), 300), 100) != null, "PDF: garbage and a cut file do not throw");

        System.out.println(bad == 0 ? "TEXT EXTRACT OK" : bad + " FAILED");
        if (bad != 0) System.exit(1);
    }

    // ------------------------------------------------------------------ hand-made PDFs

    /** A PDF from its objects (1-based), with a cross-reference table (wrong offsets when {@code badXref}). */
    static byte[] pdf(boolean badXref, String trailerExtra, Object... objects) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write("%PDF-1.4\n%âã\n".getBytes(StandardCharsets.ISO_8859_1));
        long[] at = new long[objects.length];
        for (int i = 0; i < objects.length; i++) {
            at[i] = b.size();
            b.write(((i + 1) + " 0 obj\n").getBytes(StandardCharsets.ISO_8859_1));
            Object o = objects[i];
            if (o instanceof byte[]) b.write((byte[]) o);
            else b.write(((String) o).getBytes(StandardCharsets.ISO_8859_1));
            b.write("\nendobj\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        long xref = b.size();
        StringBuilder x = new StringBuilder("xref\n0 " + (objects.length + 1) + "\n0000000000 65535 f \n");
        for (long a : at) x.append(String.format(Locale.ROOT, "%010d 00000 n \n", badXref ? a + 7 : a));
        x.append("trailer\n<< /Size ").append(objects.length + 1).append(" /Root 1 0 R ").append(trailerExtra).append(" >>\nstartxref\n")
                .append(xref).append("\n%%EOF\n");
        b.write(x.toString().getBytes(StandardCharsets.ISO_8859_1));
        return b.toByteArray();
    }

    static byte[] stream(String dict, byte[] data) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(("<< " + dict + " /Length " + data.length + " >>\nstream\n").getBytes(StandardCharsets.ISO_8859_1));
        b.write(data);
        b.write("\nendstream".getBytes(StandardCharsets.ISO_8859_1));
        return b.toByteArray();
    }

    static byte[] deflate(byte[] d) {
        Deflater z = new Deflater();
        z.setInput(d);
        z.finish();
        byte[] buf = new byte[d.length + 64];
        int n = z.deflate(buf);
        return Arrays.copyOf(buf, n);
    }

    static byte[] latin(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    static byte[] pdfCyrillicDifferences() throws Exception {
        // codes 192.. named afii10033 (П), afii10082 (р)… as a Type 1 font with a custom encoding would
        String content = "BT /F1 12 Tf 72 700 Td <C0C1C2C3C4C5> Tj ( ) Tj <C6C2C1> Tj ET";
        return pdf(false, "",
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
                stream("", latin(content)),
                "<< /Type /Font /Subtype /Type1 /BaseFont /Custom /Encoding << /Type /Encoding /BaseEncoding /WinAnsiEncoding "
                        + "/Differences [192 /afii10033 /afii10082 /afii10074 /afii10067 /afii10070 /afii10084 /afii10078 /afii10088] >> >>");
    }

    static byte[] pdfSpacing() throws Exception {
        // a TJ array with kerning gaps for spaces; words placed one by one with Td (no spaces in the strings);
        // a new line by T*; and a second page
        String c1 = "BT /F1 10 Tf 72 700 Td [(Words) -300 (apart) -280 (by) -310 (kerning)] TJ ET\n"
                + "BT /F1 10 Tf 72 680 Td (Each) Tj 26 0 Td (word) Tj 27 0 Td (placed) Tj 33 0 Td (on) Tj 15 0 Td (its) Tj 16 0 Td (own) Tj "
                + "12 TL T* (Second line) Tj ET";
        String c2 = "BT /F1 10 Tf 72 700 Td (Page two) Tj ET";
        return pdf(false, "",
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R 6 0 R] /Count 2 /Resources << /Font << /F1 5 0 R >> >> >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>",
                stream("/Filter /FlateDecode", deflate(latin(c1))),
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding /FirstChar 32 /LastChar 126 "
                        + "/Widths [" + helveticaWidths() + "] >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 7 0 R >>",
                stream("", latin(c2)));
    }

    static String helveticaWidths() {
        // Helvetica's widths for 32..126 (thousandths of an em)
        int[] w = {278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278, 556, 556, 556, 556, 556, 556, 556,
                556, 556, 556, 278, 278, 584, 584, 584, 556, 1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722,
                778, 667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556, 333, 556, 556, 500, 556, 556, 278,
                556, 556, 222, 222, 500, 222, 833, 556, 556, 556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584};
        StringBuilder b = new StringBuilder();
        for (int v : w) b.append(v).append(' ');
        return b.toString().trim();
    }

    static byte[] pdfFiltersAndForm() throws Exception {
        String page = "BT /F1 12 Tf 72 700 Td (Hex and LZW) Tj ET /X1 Do";
        String form = "BT /F1 12 Tf 72 600 Td (Inside a form) Tj ET";
        byte[] hex = (hexOf(lzw(latin(page))) + ">").getBytes(StandardCharsets.ISO_8859_1);
        return pdf(true, "",
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> /XObject << /X1 6 0 R >> >> "
                        + "/Contents 4 0 R >>",
                stream("/Filter [/ASCIIHexDecode /LZWDecode]", hex),
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                stream("/Type /XObject /Subtype /Form /BBox [0 0 612 792] /Filter /ASCII85Decode", ascii85(latin(form))));
    }

    static byte[] pdfEncrypted() throws Exception {
        return pdf(false, "/Encrypt 6 0 R /ID [<00112233445566778899AABBCCDDEEFF> <00112233445566778899AABBCCDDEEFF>]",
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
                stream("", new byte[]{(byte) 0x8F, 0x12, 0x55, (byte) 0xA0}),
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                "<< /Filter /Standard /V 2 /R 3 /Length 128 /P -4 /O <00> /U <00> >>");
    }

    static byte[] pdfScan() throws Exception {
        return pdf(false, "",
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /XObject << /Im1 5 0 R >> >> /Contents 4 0 R >>",
                stream("", latin("q 612 0 0 792 0 0 cm /Im1 Do Q")),
                stream("/Type /XObject /Subtype /Image /Width 2 /Height 2 /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /DCTDecode",
                        new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9}));
    }

    static String hexOf(byte[] d) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < d.length; i++) {
            b.append(String.format(Locale.ROOT, "%02X", d[i] & 0xff));
            if (i % 32 == 31) b.append('\n');
        }
        return b.toString();
    }

    /** LZW as PDF writes it (early change, a clear code first, an end code last). */
    static byte[] lzw(byte[] d) {
        java.util.Map<String, Integer> table = new java.util.HashMap<String, Integer>();
        for (int i = 0; i < 256; i++) table.put(String.valueOf((char) i), i);
        int next = 258, width = 9;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long bits = 0;
        int have = 0;
        java.util.List<int[]> codes = new java.util.ArrayList<int[]>();
        codes.add(new int[]{256, 9});
        String w = "";
        for (byte x : d) {
            String c = String.valueOf((char) (x & 0xff));
            if (table.containsKey(w + c)) {
                w = w + c;
                continue;
            }
            codes.add(new int[]{table.get(w), width});
            table.put(w + c, next++);
            if (next + 1 > 511 && width == 9) width = 10;
            w = c;
        }
        if (!w.isEmpty()) codes.add(new int[]{table.get(w), width});
        codes.add(new int[]{257, width});
        for (int[] cw : codes) {
            bits = (bits << cw[1]) | cw[0];
            have += cw[1];
            while (have >= 8) {
                out.write((int) (bits >> (have - 8)) & 0xff);
                have -= 8;
            }
        }
        if (have > 0) out.write((int) (bits << (8 - have)) & 0xff);
        return out.toByteArray();
    }

    static byte[] ascii85(byte[] d) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < d.length; i += 4) {
            int n = Math.min(4, d.length - i);
            long v = 0;
            for (int k = 0; k < 4; k++) v = (v << 8) | (k < n ? d[i + k] & 0xff : 0);
            char[] c = new char[5];
            for (int k = 4; k >= 0; k--) {
                c[k] = (char) ('!' + v % 85);
                v /= 85;
            }
            b.append(c, 0, n + 1);
        }
        b.append("~>");
        return b.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    static Charset cp1251() {
        return Charset.forName("windows-1251");
    }
}
