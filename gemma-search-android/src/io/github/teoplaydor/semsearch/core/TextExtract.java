package io.github.teoplaydor.semsearch.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The text of a document for its meaning (its start: {@link #MAX_CHARS}): plain text and code (UTF-8/16 or the
 * Windows Cyrillic code page), Word (.docx), Excel (.xlsx: the cells' strings and the sheets' names), PowerPoint
 * (.pptx: slide by slide), OpenDocument (.odt/.ods/.odp), EPUB, FictionBook (.fb2), RTF, HTML, PDF ({@link PdfText}).
 */
public final class TextExtract {
    public static final int MAX_CHARS = 6000;
    public static final int NONE = 0, TEXT = 1, DOCX = 2, XLSX = 3, PPTX = 4, ODF = 5, RTF = 6, HTML = 7, EPUB = 8, FB2 = 9,
            PDF = 10;
    /**
     * The most read of a file: a PDF up to 32 MB (a bigger one is mostly pictures; its first pages' text is in the part
     * read, and the phone's memory is spared), any other part at most this much.
     */
    public static final int MAX_PDF_BYTES = 32 << 20, MAX_ENTRY_BYTES = 8 << 20;

    private static final String[] TEXT_EXT = {"txt", "md", "markdown", "rst", "csv", "tsv", "log", "ini", "cfg", "conf",
            "json", "xml", "yaml", "yml", "toml", "css", "js", "ts", "tsx", "jsx", "py", "java", "kt", "cs", "cpp", "cc",
            "c", "h", "hpp", "go", "rs", "rb", "php", "swift", "sql", "sh", "ps1", "bat", "cmd", "lua", "dart", "vue",
            "tex", "srt", "vtt", "nfo", "org", "adoc", "gradle", "properties"};

    private TextExtract() {
    }

    /** What kind of document {@code name} (and its MIME type, when the name says nothing) is: {@link #NONE} if none. */
    public static int type(String name, String mime) {
        String ext = ext(name);
        switch (ext) {
            case "docx":
            case "docm":
            case "dotx":
                return DOCX;
            case "xlsx":
            case "xlsm":
                return XLSX;
            case "pptx":
            case "ppsx":
                return PPTX;
            case "odt":
            case "ods":
            case "odp":
            case "ott":
                return ODF;
            case "rtf":
                return RTF;
            case "html":
            case "htm":
            case "xhtml":
            case "mht":
                return HTML;
            case "epub":
                return EPUB;
            case "fb2":
                return FB2;
            case "pdf":
                return PDF;
            default:
                break;
        }
        for (String t : TEXT_EXT) if (t.equals(ext)) return TEXT;
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.equals("application/pdf")) return PDF;
        if (m.equals("text/html")) return HTML;
        if (m.equals("text/rtf") || m.equals("application/rtf")) return RTF;
        if (m.startsWith("text/")) return TEXT;
        if (m.contains("wordprocessingml")) return DOCX;
        if (m.contains("spreadsheetml")) return XLSX;
        if (m.contains("presentationml")) return PPTX;
        if (m.contains("opendocument")) return ODF;
        if (m.equals("application/epub+zip")) return EPUB;
        return NONE;
    }

    static String ext(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** The document's text, at most {@code maxChars}, tidied (no runs of blank lines); "" when it has none. */
    public static String extract(InputStream in, int type, int maxChars) throws IOException {
        String s;
        switch (type) {
            case TEXT:
                s = decode(read(in, maxChars * 4L + 4));
                break;
            case DOCX:
            case XLSX:
            case PPTX:
            case ODF:
            case EPUB:
                s = fromZip(in, type, maxChars);
                break;
            case RTF:
                s = rtf(read(in, MAX_ENTRY_BYTES));
                break;
            case HTML:
                s = html(decode(read(in, MAX_ENTRY_BYTES)));
                break;
            case FB2:
                s = fb2(decodeXml(read(in, MAX_ENTRY_BYTES)));
                break;
            case PDF:
                s = PdfText.extract(read(in, MAX_PDF_BYTES), maxChars);
                break;
            default:
                return "";
        }
        return tidy(s == null ? "" : s, maxChars);
    }

    static byte[] read(InputStream in, long max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 15];
        int n;
        while (out.size() < max && (n = in.read(buf, 0, (int) Math.min(buf.length, max - out.size()))) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** Spaces and blank lines evened out, cut at {@code max} characters (not inside a surrogate pair). */
    static String tidy(String s, int max) {
        StringBuilder b = new StringBuilder(Math.min(s.length(), max + 16));
        int blank = 0;
        boolean space = false;
        for (int i = 0; i < s.length() && b.length() < max; i++) {
            char c = s.charAt(i);
            if (c == '\r') continue;
            if (c == '\n') {
                // the line's trailing spaces go
                while (b.length() > 0 && b.charAt(b.length() - 1) == ' ') b.setLength(b.length() - 1);
                if (b.length() > 0 && blank < 2) b.append('\n');
                blank++;
                space = false;
                continue;
            }
            if (c == '\t' || c == ' ' || c == ' ' || c == '\f' || c == '\u000B') {
                space = b.length() > 0 && b.charAt(b.length() - 1) != '\n';
                continue;
            }
            if (Character.isISOControl(c) || c == '﻿') continue;
            if (space) b.append(' ');
            space = false;
            blank = 0;
            b.append(c);
        }
        if (b.length() > max) b.setLength(max);
        if (b.length() > 0 && Character.isHighSurrogate(b.charAt(b.length() - 1))) b.setLength(b.length() - 1);
        return b.toString().trim();
    }

    // ------------------------------------------------------------------ plain text

    /** Text bytes: a BOM's encoding, else UTF-8 when valid, else Windows-1251; null for a binary file. */
    public static String decode(byte[] b) {
        if (b.length >= 2 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xFE) return new String(b, 2, b.length - 2, Charset.forName("UTF-16LE"));
        if (b.length >= 2 && (b[0] & 0xff) == 0xFE && (b[1] & 0xff) == 0xFF) return new String(b, 2, b.length - 2, Charset.forName("UTF-16BE"));
        int start = b.length >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF ? 3 : 0;
        for (int i = start; i < Math.min(b.length, 4096); i++) if (b[i] == 0) return null; // binary
        // a cut at the end may split a character: decoding stops short of it
        int end = b.length;
        int k = end - 1, back = 0;
        while (k >= start && back < 3 && (b[k] & 0xC0) == 0x80) {
            k--;
            back++;
        }
        if (k >= start && (b[k] & 0x80) != 0) {
            int need = (b[k] & 0xE0) == 0xC0 ? 1 : (b[k] & 0xF0) == 0xE0 ? 2 : (b[k] & 0xF8) == 0xF0 ? 3 : 0;
            if (need > back) end = k;
        }
        try {
            CharBuffer cb = Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b, start, end - start));
            return cb.toString();
        } catch (CharacterCodingException e) {
            return new String(b, start, b.length - start, Charset.forName("windows-1251"));
        }
    }

    /** XML bytes in the encoding its declaration names (UTF-8 by default). */
    static String decodeXml(byte[] b) {
        String head = new String(b, 0, Math.min(b.length, 200), Charset.forName("ISO-8859-1"));
        int e = head.indexOf("encoding=");
        if (e > 0 && head.indexOf("<?xml") >= 0) {
            int q = e + 9;
            if (q < head.length()) {
                char quote = head.charAt(q);
                int end = head.indexOf(quote, q + 1);
                if (end > q) {
                    String enc = head.substring(q + 1, end);
                    try {
                        if (!enc.equalsIgnoreCase("utf-8")) return new String(b, Charset.forName(enc));
                    } catch (Exception ignored) {
                        // an encoding Java does not know: as UTF-8
                    }
                }
            }
        }
        String s = decode(b);
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------ zipped XML (Office, OpenDocument, EPUB)

    private static String fromZip(InputStream in, int type, int maxChars) throws IOException {
        ZipInputStream zip = new ZipInputStream(in);
        Map<String, String> parts = new HashMap<String, String>();
        List<String> order = new ArrayList<String>();
        long total = 0;
        ZipEntry e;
        while ((e = zip.getNextEntry()) != null) {
            String n = e.getName();
            if (!wanted(type, n)) continue;
            byte[] data = read(zip, MAX_ENTRY_BYTES);
            total += data.length;
            parts.put(n, decodeXml(data));
            order.add(n);
            if (total > 4L * MAX_ENTRY_BYTES) break;
        }
        StringBuilder out = new StringBuilder();
        switch (type) {
            case DOCX:
                for (String n : new String[]{"word/document.xml", "word/footnotes.xml", "word/endnotes.xml"}) {
                    if (parts.containsKey(n)) out.append(xmlText(parts.get(n), DOCX_BREAKS)).append('\n');
                }
                break;
            case PPTX:
                for (String n : numbered(order, "ppt/slides/slide")) out.append(xmlText(parts.get(n), PPTX_BREAKS)).append("\n\n");
                break;
            case XLSX:
                if (parts.containsKey("xl/workbook.xml")) out.append(attrs(parts.get("xl/workbook.xml"), "sheet", "name")).append('\n');
                if (parts.containsKey("xl/sharedStrings.xml")) out.append(xmlText(parts.get("xl/sharedStrings.xml"), XLSX_BREAKS));
                for (String n : numbered(order, "xl/worksheets/sheet")) out.append(inlineStrings(parts.get(n)));
                break;
            case ODF:
                if (parts.containsKey("content.xml")) out.append(xmlText(parts.get("content.xml"), ODF_BREAKS));
                break;
            case EPUB:
                for (String n : order) {
                    if (out.length() > maxChars * 2) break;
                    out.append(html(parts.get(n))).append("\n\n");
                }
                break;
            default:
                break;
        }
        return out.toString();
    }

    private static boolean wanted(int type, String n) {
        switch (type) {
            case DOCX:
                return n.equals("word/document.xml") || n.equals("word/footnotes.xml") || n.equals("word/endnotes.xml");
            case PPTX:
                return n.startsWith("ppt/slides/slide") && n.endsWith(".xml");
            case XLSX:
                return n.equals("xl/sharedStrings.xml") || n.equals("xl/workbook.xml")
                        || (n.startsWith("xl/worksheets/sheet") && n.endsWith(".xml"));
            case ODF:
                return n.equals("content.xml");
            case EPUB: {
                String l = n.toLowerCase(Locale.ROOT);
                return (l.endsWith(".xhtml") || l.endsWith(".html") || l.endsWith(".htm")) && !l.contains("toc") && !l.contains("nav");
            }
            default:
                return false;
        }
    }

    /** Entries {@code prefix}N.xml in N's order (slide2 before slide10). */
    private static List<String> numbered(List<String> names, final String prefix) {
        List<String> out = new ArrayList<String>();
        for (String n : names) if (n.startsWith(prefix) && n.endsWith(".xml") && n.indexOf('/', prefix.length()) < 0) out.add(n);
        Collections.sort(out, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return Long.compare(num(a, prefix), num(b, prefix));
            }
        });
        return out;
    }

    private static long num(String n, String prefix) {
        String d = n.substring(prefix.length(), n.length() - 4);
        try {
            return Long.parseLong(d);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    // the tags that end a line or a cell, and those that are a tab or a break of their own
    private static final String[] DOCX_BREAKS = {"/w:p", "w:br", "w:cr", "/w:tr"}, DOCX_TABS = {"w:tab", "/w:tc"};
    private static final String[] PPTX_BREAKS = {"/a:p", "a:br"}, XLSX_BREAKS = {"/si"};
    private static final String[] ODF_BREAKS = {"/text:p", "/text:h", "text:line-break", "/table:table-row"};

    /** The text of an XML document: tags dropped, the {@code breaks} as new lines, entities decoded. */
    static String xmlText(String xml, String[] breaks) {
        StringBuilder out = new StringBuilder(Math.min(xml.length(), 1 << 20));
        int i = 0, n = xml.length();
        while (i < n) {
            char c = xml.charAt(i);
            if (c != '<') {
                int lt = xml.indexOf('<', i);
                if (lt < 0) lt = n;
                out.append(entities(xml.substring(i, lt)));
                i = lt;
                continue;
            }
            int gt = xml.indexOf('>', i);
            if (gt < 0) break;
            String tag = tagName(xml, i + 1, gt);
            for (String b : breaks) if (b.equals(tag)) out.append('\n');
            for (String t : DOCX_TABS) if (t.equals(tag)) out.append('\t');
            if (tag.equals("text:tab") || tag.equals("/table:table-cell") || tag.equals("text:s")) out.append(' ');
            // Word's deleted text (tracked changes) is not the document's
            if (tag.equals("w:delText")) {
                int end = xml.indexOf("</w:delText>", gt);
                i = end < 0 ? n : end + 12;
                continue;
            }
            i = gt + 1;
        }
        return out.toString();
    }

    /** "w:p" from "<w:p w:rsidR=...>", "/w:p" from "</w:p>"; a self-closing tag without its slash. */
    private static String tagName(String s, int from, int to) {
        int e = from;
        if (e < to && s.charAt(e) == '/') e++; // a closing tag keeps its slash
        while (e < to && !Character.isWhitespace(s.charAt(e)) && s.charAt(e) != '/') e++;
        return s.substring(from, e);
    }

    /** Every {@code attr} of the {@code tag} elements, one a line (Excel's sheet names). */
    private static String attrs(String xml, String tag, String attr) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while ((i = xml.indexOf("<" + tag, i)) >= 0) {
            int gt = xml.indexOf('>', i);
            if (gt < 0) break;
            String t = xml.substring(i, gt);
            int a = t.indexOf(" " + attr + "=\"");
            if (a >= 0) {
                int s = a + attr.length() + 3, e = t.indexOf('"', s);
                if (e > s) out.append(entities(t.substring(s, e))).append('\n');
            }
            i = gt;
        }
        return out.toString();
    }

    /** Excel's inline strings and formula strings in a sheet (not in the shared strings). */
    private static String inlineStrings(String xml) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while ((i = xml.indexOf("<is>", i)) >= 0) {
            int e = xml.indexOf("</is>", i);
            if (e < 0) break;
            out.append(xmlText(xml.substring(i, e), new String[0])).append('\n');
            i = e;
        }
        return out.toString();
    }

    private static final Map<String, Character> NAMED = new HashMap<String, Character>();

    static {
        String[] pairs = {"amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos", "'", "nbsp", " ", "laquo", "«",
                "raquo", "»", "mdash", "—", "ndash", "–", "hellip", "…", "copy", "©", "reg", "®", "trade", "™", "deg", "°",
                "bull", "•", "middot", "·", "lsquo", "‘", "rsquo", "’", "ldquo", "“", "rdquo", "”", "bdquo", "„", "euro", "€",
                "times", "×", "minus", "−", "shy", "­", "sect", "§", "para", "¶", "plusmn", "±", "frac12", "½", "num", "#"};
        for (int i = 0; i < pairs.length; i += 2) NAMED.put(pairs[i], pairs[i + 1].charAt(0));
    }

    /** XML/HTML character references decoded. */
    static String entities(String s) {
        int amp = s.indexOf('&');
        if (amp < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        int i = 0;
        while (amp >= 0) {
            b.append(s, i, amp);
            int semi = s.indexOf(';', amp);
            if (semi < 0 || semi - amp > 10) {
                b.append('&');
                i = amp + 1;
            } else {
                String name = s.substring(amp + 1, semi);
                String rep = null;
                try {
                    if (name.startsWith("#x") || name.startsWith("#X")) rep = new String(Character.toChars(Integer.parseInt(name.substring(2), 16)));
                    else if (name.startsWith("#")) rep = new String(Character.toChars(Integer.parseInt(name.substring(1))));
                } catch (IllegalArgumentException e) {
                    rep = null;
                }
                if (rep == null && NAMED.containsKey(name)) rep = String.valueOf(NAMED.get(name));
                if (rep == null) {
                    b.append('&');
                    i = amp + 1;
                } else {
                    b.append(rep);
                    i = semi + 1;
                }
            }
            amp = s.indexOf('&', i);
        }
        b.append(s, i, s.length());
        return b.toString();
    }

    // ------------------------------------------------------------------ HTML, FictionBook

    private static final String[] BLOCKS = {"p", "/p", "br", "div", "/div", "li", "tr", "/tr", "h1", "h2", "h3", "h4", "h5",
            "h6", "/h1", "/h2", "/h3", "/h4", "/h5", "/h6", "/li", "title", "/title", "td", "th", "/table", "hr", "section",
            "/section", "blockquote", "/blockquote", "pre", "/pre", "article", "header", "footer"};

    /** HTML's text: scripts, styles and comments dropped, blocks on lines of their own. */
    static String html(String h) {
        if (h == null) return "";
        StringBuilder out = new StringBuilder();
        int i = 0, n = h.length();
        while (i < n) {
            char c = h.charAt(i);
            if (c != '<') {
                int lt = h.indexOf('<', i);
                if (lt < 0) lt = n;
                // inside HTML, runs of white space are one space
                out.append(entities(h.substring(i, lt).replaceAll("[ \\t\\r\\n]+", " ")));
                i = lt;
                continue;
            }
            if (h.startsWith("<!--", i)) {
                int e = h.indexOf("-->", i + 4);
                i = e < 0 ? n : e + 3;
                continue;
            }
            int gt = h.indexOf('>', i);
            if (gt < 0) break;
            String tag = tagName(h, i + 1, gt).toLowerCase(Locale.ROOT);
            if (tag.equals("script") || tag.equals("style")) {
                int e = h.toLowerCase(Locale.ROOT).indexOf("</" + tag, gt);
                i = e < 0 ? n : e;
                continue;
            }
            for (String b : BLOCKS) {
                if (b.equals(tag)) {
                    out.append('\n');
                    break;
                }
            }
            if (tag.equals("td") || tag.equals("th")) out.append(' ');
            i = gt + 1;
        }
        return out.toString();
    }

    /** A FictionBook's text: its title and author, then the body (the binary pictures dropped). */
    static String fb2(String xml) {
        StringBuilder out = new StringBuilder();
        int t = xml.indexOf("<title-info>"), te = xml.indexOf("</title-info>");
        if (t >= 0 && te > t) {
            String info = xml.substring(t, te);
            int a = info.indexOf("<book-title>"), ae = info.indexOf("</book-title>");
            if (a >= 0 && ae > a) out.append(entities(info.substring(a + 12, ae))).append('\n');
            int au = info.indexOf("<author>"), aue = info.indexOf("</author>");
            if (au >= 0 && aue > au) {
                String names = xmlText(info.substring(au, aue), new String[]{"/first-name", "/middle-name", "/last-name"});
                out.append(names.trim().replaceAll("\\s+", " ")).append("\n\n");
            }
        }
        int b = xml.indexOf("<body");
        int be = xml.lastIndexOf("</body>");
        if (b >= 0) out.append(xmlText(xml.substring(b, be > b ? be : xml.length()), new String[]{"/p", "/title", "empty-line", "/v"}));
        return out.toString();
    }

    // ------------------------------------------------------------------ RTF

    /** RTF's text: control words for breaks, \'hh in the document's code page, \\uN with its fallback skipped. */
    static String rtf(byte[] b) {
        String s = new String(b, Charset.forName("ISO-8859-1"));
        StringBuilder out = new StringBuilder();
        Charset cp = Charset.forName("windows-1252");
        java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<int[]>(); // {skip group, uc}
        boolean skip = false;
        int uc = 1, skipChars = 0;
        ByteArrayOutputStream hex = new ByteArrayOutputStream();
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= n || s.charAt(i + 1) != '\'') flush(hex, out, cp, skip);
            if (c == '{') {
                stack.push(new int[]{skip ? 1 : 0, uc});
                i++;
                if (i + 1 < n && s.charAt(i) == '\\' && s.charAt(i + 1) == '*') skip = true;
                continue;
            }
            if (c == '}') {
                if (!stack.isEmpty()) {
                    int[] st = stack.pop();
                    skip = st[0] != 0;
                    uc = st[1];
                }
                i++;
                continue;
            }
            if (c == '\\') {
                i++;
                if (i >= n) break;
                char d = s.charAt(i);
                if (d == '\'') {
                    if (i + 2 < n) {
                        try {
                            int v = Integer.parseInt(s.substring(i + 1, i + 3), 16);
                            if (skipChars > 0) skipChars--;
                            else hex.write(v);
                        } catch (NumberFormatException ignored) {
                            // not a hex escape
                        }
                    }
                    i += 3;
                    continue;
                }
                if (!Character.isLetter(d)) {
                    if (!skip && (d == '\\' || d == '{' || d == '}')) out.append(d);
                    if (!skip && d == '~') out.append(' ');
                    i++;
                    continue;
                }
                int ws = i;
                while (i < n && Character.isLetter(s.charAt(i))) i++;
                String word = s.substring(ws, i);
                int ps = i;
                if (i < n && (s.charAt(i) == '-' || Character.isDigit(s.charAt(i)))) {
                    i++;
                    while (i < n && Character.isDigit(s.charAt(i))) i++;
                }
                int param = ps < i ? parseInt(s.substring(ps, i)) : Integer.MIN_VALUE;
                if (i < n && s.charAt(i) == ' ') i++;
                switch (word) {
                    case "par":
                    case "line":
                    case "row":
                    case "page":
                    case "sect":
                        if (!skip) out.append('\n');
                        break;
                    case "tab":
                    case "cell":
                        if (!skip) out.append('\t');
                        break;
                    case "u":
                        if (!skip && param != Integer.MIN_VALUE) out.append((char) (param < 0 ? param + 65536 : param));
                        skipChars = uc;
                        break;
                    case "uc":
                        uc = Math.max(0, param);
                        break;
                    case "ansicpg":
                        try {
                            cp = Charset.forName("windows-" + param);
                        } catch (Exception ignored) {
                            // a code page Java does not have
                        }
                        break;
                    case "fonttbl":
                    case "colortbl":
                    case "stylesheet":
                    case "info":
                    case "pict":
                    case "object":
                    case "header":
                    case "footer":
                    case "listtable":
                    case "listoverridetable":
                    case "rsidtbl":
                    case "xmlnstbl":
                    case "themedata":
                    case "datastore":
                        skip = true;
                        break;
                    default:
                        break;
                }
                continue;
            }
            if (c == '\r' || c == '\n') {
                i++;
                continue;
            }
            if (skipChars > 0) skipChars--;
            else if (!skip) out.append(c);
            i++;
        }
        flush(hex, out, cp, skip);
        return out.toString();
    }

    private static void flush(ByteArrayOutputStream hex, StringBuilder out, Charset cp, boolean skip) {
        if (hex.size() == 0) return;
        if (!skip) out.append(new String(hex.toByteArray(), cp));
        hex.reset();
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }
}
