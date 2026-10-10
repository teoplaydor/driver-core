package io.github.teoplaydor.semsearch.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;

/**
 * The text layer of a PDF, page by page, as one reads it: the objects found by a scan of the file (a broken or missing
 * cross-reference table does not matter; a later definition of an object wins, compressed object streams too), the
 * page tree from the catalog, each page's content (and its forms) through the text operators. Characters through the
 * font's ToUnicode map, else its encoding (WinAnsi, a /Differences list with glyph names, Cyrillic ones too); spaces
 * and line breaks where the glyphs' positions (with the fonts' widths) leave a gap or start a new line. An encrypted
 * file or a scan has no text here: "" (a picture of its page stands for it).
 */
public final class PdfText {
    private final byte[] pdf;
    /** Object number → where its latest definition is: {offset in the file} or {object stream number, index}. */
    private final Map<Integer, long[]> where = new HashMap<Integer, long[]>();
    private final Map<Integer, Object> cache = new HashMap<Integer, Object>();
    private final Map<Integer, List<Object>> objStreams = new HashMap<Integer, List<Object>>();
    private final IdentityHashMap<Map<String, Object>, Font> fonts = new IdentityHashMap<Map<String, Object>, Font>();
    private final StringBuilder out = new StringBuilder();
    private int maxChars;

    private PdfText(byte[] pdf) {
        this.pdf = pdf;
    }

    /** The text of the PDF's pages, at most {@code maxChars}; "" when it has none (or is encrypted). */
    public static String extract(byte[] pdf, int maxChars) {
        PdfText p = new PdfText(pdf);
        p.maxChars = maxChars;
        try {
            p.run();
        } catch (RuntimeException | StackOverflowError e) {
            // a damaged file: what was read so far
        }
        return p.out.toString();
    }

    /** The number of pages (the page tree's leaves), or 0 when the file cannot be read. */
    public static int pages(byte[] pdf) {
        PdfText p = new PdfText(pdf);
        try {
            p.scan();
            Map<String, Object> cat = p.catalog();
            if (cat == null) return 0;
            List<Map<String, Object>> pages = new ArrayList<Map<String, Object>>();
            p.collectPages(p.dict(cat.get("Pages")), null, pages, 0);
            return pages.size();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private void run() {
        scan();
        Map<String, Object> trailer = trailer();
        if (trailer != null && trailer.get("Encrypt") != null) return;
        Map<String, Object> cat = catalog();
        if (cat == null) return;
        List<Map<String, Object>> pages = new ArrayList<Map<String, Object>>();
        collectPages(dict(cat.get("Pages")), null, pages, 0);
        for (Map<String, Object> page : pages) {
            if (out.length() >= maxChars) break;
            Map<String, Object> res = dict(page.get("Resources"));
            Object contents = resolve(page.get("Contents"));
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            if (contents instanceof Stream) {
                byte[] d = decoded((Stream) contents);
                all.write(d, 0, d.length);
            } else if (contents instanceof List) {
                for (Object o : (List<?>) contents) {
                    Object s = resolve(o);
                    if (s instanceof Stream) {
                        byte[] d = decoded((Stream) s);
                        all.write(d, 0, d.length);
                        all.write('\n');
                    }
                }
            }
            new Content(res, 0).run(all.toByteArray());
            newLine();
            out.append('\n');
        }
    }

    // ------------------------------------------------------------------ objects

    static final class Name {
        final String s;

        Name(String s) {
            this.s = s;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Name && ((Name) o).s.equals(s);
        }

        @Override
        public int hashCode() {
            return s.hashCode();
        }

        @Override
        public String toString() {
            return "/" + s;
        }
    }

    static final class Ref {
        final int num;

        Ref(int num) {
            this.num = num;
        }
    }

    static final class Str {
        final byte[] b;

        Str(byte[] b) {
            this.b = b;
        }
    }

    static final class Op {
        final String s;

        Op(String s) {
            this.s = s;
        }
    }

    /** A stream: its dictionary and where its data lies (a slice of the file, or of an object stream's data). */
    static final class Stream {
        final Map<String, Object> dict;
        final byte[] src;
        final int from, length;

        Stream(Map<String, Object> dict, byte[] src, int from, int length) {
            this.dict = dict;
            this.src = src;
            this.from = from;
            this.length = length;
        }

        byte[] raw() {
            byte[] r = new byte[length];
            System.arraycopy(src, from, r, 0, length);
            return r;
        }
    }

    private static final Object NULL = new Object();

    /** Every "N G obj" in the file; object streams' contents after them. */
    private void scan() {
        byte[] b = pdf;
        for (int i = 0; i + 3 <= b.length; i++) {
            if (b[i] != 'o' || b[i + 1] != 'b' || b[i + 2] != 'j') continue;
            if (i + 3 < b.length && !delim(b[i + 3]) && !white(b[i + 3])) continue;
            // back over "N G "
            int k = i - 1;
            if (k < 0 || !white(b[k])) continue;
            while (k >= 0 && white(b[k])) k--;
            int ge = k;
            while (k >= 0 && digit(b[k])) k--;
            if (k == ge) continue;
            if (k < 0 || !white(b[k])) continue;
            while (k >= 0 && white(b[k])) k--;
            int ne = k;
            while (k >= 0 && digit(b[k])) k--;
            if (k == ne || (k >= 0 && !white(b[k]) && !delim(b[k]))) continue;
            int num = parseIntAt(b, k + 1, ne + 1);
            where.put(num, new long[]{i + 3});
        }
        // object streams: the objects inside them, where no later plain definition replaces them
        List<Integer> nums = new ArrayList<Integer>(where.keySet());
        for (int num : nums) {
            Object o = object(num);
            if (!(o instanceof Stream)) continue;
            Stream s = (Stream) o;
            if (!new Name("ObjStm").equals(s.dict.get("Type"))) continue;
            long at = where.get(num)[0];
            try {
                byte[] d = decoded(s);
                int n = (int) number(s.dict.get("N")), first = (int) number(s.dict.get("First"));
                Lexer lx = new Lexer(d, 0, d.length);
                for (int k = 0; k < n; k++) {
                    Object on = lx.next(), off = lx.next();
                    if (!(on instanceof Double) || !(off instanceof Double)) break;
                    int inner = ((Double) on).intValue();
                    long[] prev = where.get(inner);
                    // a plain definition later in the file is newer than this stream
                    if (prev != null && prev.length == 1 && prev[0] > at) continue;
                    where.put(inner, new long[]{num, k, first + ((Double) off).intValue(), at});
                    cache.remove(inner);
                }
            } catch (RuntimeException e) {
                // a damaged object stream: its objects stay unknown
            }
        }
    }

    private Object object(int num) {
        if (cache.containsKey(num)) return cache.get(num);
        long[] w = where.get(num);
        Object o = null;
        cache.put(num, null); // a reference loop reads as null
        if (w != null) {
            try {
                if (w.length == 1) {
                    Lexer lx = new Lexer(pdf, (int) w[0], pdf.length);
                    o = lx.object(true);
                } else {
                    Object st = object((int) w[0]);
                    if (st instanceof Stream) {
                        byte[] d = decoded((Stream) st);
                        Lexer lx = new Lexer(d, (int) w[2], d.length);
                        o = lx.object(false);
                    }
                }
            } catch (RuntimeException e) {
                o = null;
            }
        }
        cache.put(num, o);
        return o;
    }

    private Object resolve(Object o) {
        int guard = 0;
        while (o instanceof Ref && guard++ < 32) o = object(((Ref) o).num);
        return o == NULL ? null : o;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dict(Object o) {
        o = resolve(o);
        if (o instanceof Stream) return ((Stream) o).dict;
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private double number(Object o) {
        o = resolve(o);
        return o instanceof Double ? (Double) o : 0;
    }

    /** The last trailer's dictionary (a classic one, or a cross-reference stream's). */
    private Map<String, Object> trailer() {
        Map<String, Object> best = null;
        int at = lastIndexOf(pdf, "trailer".getBytes(), pdf.length);
        while (at >= 0 && best == null) {
            try {
                Lexer lx = new Lexer(pdf, at + 7, pdf.length);
                Object o = lx.next();
                if (o instanceof Map) {
                    @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
                    if (m.get("Root") != null) best = m;
                }
            } catch (RuntimeException ignored) {
                // a damaged trailer: the one before
            }
            at = lastIndexOf(pdf, "trailer".getBytes(), at);
        }
        if (best != null) return best;
        long last = -1;
        for (Map.Entry<Integer, long[]> e : where.entrySet()) {
            if (e.getValue().length != 1) continue;
            Map<String, Object> d = dict(object(e.getKey()));
            if (d != null && new Name("XRef").equals(d.get("Type")) && d.get("Root") != null && e.getValue()[0] > last) {
                last = e.getValue()[0];
                best = d;
            }
        }
        return best;
    }

    private Map<String, Object> catalog() {
        Map<String, Object> t = trailer();
        Map<String, Object> cat = t == null ? null : dict(t.get("Root"));
        if (cat != null && cat.get("Pages") != null) return cat;
        long last = -1;
        for (Map.Entry<Integer, long[]> e : where.entrySet()) {
            Map<String, Object> d = dict(object(e.getKey()));
            long at = e.getValue().length == 1 ? e.getValue()[0] : e.getValue()[3];
            if (d != null && new Name("Catalog").equals(d.get("Type")) && d.get("Pages") != null && at > last) {
                last = at;
                cat = d;
            }
        }
        return cat;
    }

    /** The pages in order, each with its resources (inherited from the tree when it has none of its own). */
    private void collectPages(Map<String, Object> node, Map<String, Object> inherited, List<Map<String, Object>> pages, int depth) {
        if (node == null || depth > 64 || pages.size() > 5000) return;
        Object res = node.get("Resources") != null ? node.get("Resources") : inherited;
        Object kids = resolve(node.get("Kids"));
        if (kids instanceof List) {
            @SuppressWarnings("unchecked") Map<String, Object> r = res instanceof Map ? (Map<String, Object>) res : dict(res);
            for (Object k : (List<?>) kids) collectPages(dict(k), r, pages, depth + 1);
            return;
        }
        Map<String, Object> page = new HashMap<String, Object>(node);
        if (page.get("Resources") == null && inherited != null) page.put("Resources", inherited);
        pages.add(page);
    }

    // ------------------------------------------------------------------ stream filters

    private byte[] decoded(Stream s) {
        Object f = resolve(s.dict.get("Filter"));
        Object p = resolve(s.dict.get("DecodeParms"));
        if (p == null) p = resolve(s.dict.get("DP"));
        List<Object> filters = new ArrayList<Object>(), parms = new ArrayList<Object>();
        if (f instanceof Name) {
            filters.add(f);
            parms.add(p);
        } else if (f instanceof List) {
            filters.addAll((List<?>) f);
            for (int i = 0; i < filters.size(); i++) parms.add(p instanceof List && i < ((List<?>) p).size() ? resolve(((List<?>) p).get(i)) : null);
        }
        if (filters.isEmpty()) return s.raw();
        byte[] d = null;
        for (int i = 0; i < filters.size(); i++) {
            Object fi = resolve(filters.get(i));
            String name = fi instanceof Name ? ((Name) fi).s : "";
            Map<String, Object> pm = dict(parms.get(i));
            if (d == null && !(name.equals("FlateDecode") || name.equals("Fl"))) d = s.raw();
            switch (name) {
                case "FlateDecode":
                case "Fl":
                    d = predict(d == null ? inflate(s.src, s.from, s.length) : inflate(d, 0, d.length), pm);
                    break;
                case "LZWDecode":
                case "LZW":
                    d = predict(lzw(d, pm == null || number(pm.get("EarlyChange")) != 0 || pm.get("EarlyChange") == null), pm);
                    break;
                case "ASCIIHexDecode":
                case "AHx":
                    d = asciiHex(d);
                    break;
                case "ASCII85Decode":
                case "A85":
                    d = ascii85(d);
                    break;
                case "RunLengthDecode":
                case "RL":
                    d = runLength(d);
                    break;
                default:
                    return new byte[0]; // pictures (DCT, JBIG2, CCITT…) are no text
            }
        }
        return d;
    }

    static byte[] inflate(byte[] d, int from, int length) {
        Inflater inf = new Inflater();
        inf.setInput(d, from, length);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, Math.min(length * 3, 64 << 20)));
        byte[] buf = new byte[1 << 15];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break;
                }
                out.write(buf, 0, n);
                if (out.size() > 256 << 20) break;
            }
        } catch (java.util.zip.DataFormatException e) {
            // a damaged stream: what inflated so far
        } finally {
            inf.end();
        }
        return out.toByteArray();
    }

    private byte[] predict(byte[] d, Map<String, Object> pm) {
        if (pm == null) return d;
        int pred = (int) number(pm.get("Predictor"));
        if (pred < 10) return d; // none (TIFF 2 is for pictures)
        int colors = pm.get("Colors") != null ? (int) number(pm.get("Colors")) : 1;
        int bpc = pm.get("BitsPerComponent") != null ? (int) number(pm.get("BitsPerComponent")) : 8;
        int cols = pm.get("Columns") != null ? (int) number(pm.get("Columns")) : 1;
        int bpp = Math.max(1, colors * bpc / 8), row = (colors * bpc * cols + 7) / 8;
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.length);
        byte[] prev = new byte[row], cur = new byte[row];
        for (int at = 0; at + 1 + row <= d.length; at += row + 1) {
            int type = d[at] & 0xff;
            for (int i = 0; i < row; i++) {
                int x = d[at + 1 + i] & 0xff, a = i >= bpp ? cur[i - bpp] & 0xff : 0, b = prev[i] & 0xff,
                        c = i >= bpp ? prev[i - bpp] & 0xff : 0;
                int v;
                switch (type) {
                    case 1:
                        v = x + a;
                        break;
                    case 2:
                        v = x + b;
                        break;
                    case 3:
                        v = x + ((a + b) >> 1);
                        break;
                    case 4: {
                        int pp = a + b - c, pa = Math.abs(pp - a), pb = Math.abs(pp - b), pc = Math.abs(pp - c);
                        v = x + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c);
                        break;
                    }
                    default:
                        v = x;
                }
                cur[i] = (byte) v;
            }
            out.write(cur, 0, row);
            byte[] t = prev;
            prev = cur;
            cur = t;
        }
        return out.toByteArray();
    }

    static byte[] lzw(byte[] d, boolean early) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.length * 3);
        byte[][] table = new byte[4096][];
        for (int i = 0; i < 256; i++) table[i] = new byte[]{(byte) i};
        int next = 258, width = 9;
        long bits = 0;
        int have = 0, pos = 0;
        byte[] prev = null;
        while (true) {
            while (have < width && pos < d.length) {
                bits = (bits << 8) | (d[pos++] & 0xff);
                have += 8;
            }
            if (have < width) break;
            int code = (int) ((bits >> (have - width)) & ((1 << width) - 1));
            have -= width;
            if (code == 256) {
                next = 258;
                width = 9;
                prev = null;
                continue;
            }
            if (code == 257) break;
            byte[] entry;
            if (code < next && table[code] != null) entry = table[code];
            else if (code == next && prev != null) {
                entry = new byte[prev.length + 1];
                System.arraycopy(prev, 0, entry, 0, prev.length);
                entry[prev.length] = prev[0];
            } else break;
            out.write(entry, 0, entry.length);
            if (prev != null && next < 4096) {
                byte[] add = new byte[prev.length + 1];
                System.arraycopy(prev, 0, add, 0, prev.length);
                add[prev.length] = entry[0];
                table[next++] = add;
            }
            prev = entry;
            int limit = early ? next + 1 : next;
            if (limit >= 512 && width == 9) width = 10;
            if (limit >= 1024 && width == 10) width = 11;
            if (limit >= 2048 && width == 11) width = 12;
        }
        return out.toByteArray();
    }

    static byte[] asciiHex(byte[] d) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.length / 2);
        int hi = -1;
        for (byte x : d) {
            if (x == '>') break;
            int v = Character.digit(x, 16);
            if (v < 0) continue;
            if (hi < 0) hi = v;
            else {
                out.write(hi * 16 + v);
                hi = -1;
            }
        }
        if (hi >= 0) out.write(hi * 16);
        return out.toByteArray();
    }

    static byte[] ascii85(byte[] d) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.length);
        long v = 0;
        int n = 0;
        for (int i = 0; i < d.length; i++) {
            int c = d[i] & 0xff;
            if (c == '~') break;
            if (c == 'z' && n == 0) {
                out.write(0);
                out.write(0);
                out.write(0);
                out.write(0);
                continue;
            }
            if (c < '!' || c > 'u') continue;
            v = v * 85 + (c - '!');
            if (++n == 5) {
                for (int k = 3; k >= 0; k--) out.write((int) (v >> (8 * k)) & 0xff);
                v = 0;
                n = 0;
            }
        }
        if (n > 1) {
            for (int k = n; k < 5; k++) v = v * 85 + 84;
            for (int k = 3; k >= 5 - n; k--) out.write((int) (v >> (8 * k)) & 0xff);
        }
        return out.toByteArray();
    }

    static byte[] runLength(byte[] d) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.length * 2);
        int i = 0;
        while (i < d.length) {
            int len = d[i++] & 0xff;
            if (len == 128) break;
            if (len < 128) {
                for (int k = 0; k <= len && i < d.length; k++) out.write(d[i++]);
            } else if (i < d.length) {
                for (int k = 0; k < 257 - len; k++) out.write(d[i]);
                i++;
            }
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ lexer

    private final class Lexer {
        final byte[] b;
        int i;
        final int end;

        Lexer(byte[] b, int from, int end) {
            this.b = b;
            this.i = from;
            this.end = end;
        }

        /** An object at the top level of the file ({@code stream} data read with it) or inside an object stream. */
        Object object(boolean plain) {
            Object o = next();
            if (plain && o instanceof Map) {
                int save = i;
                skipSpace();
                if (startsWith("stream")) {
                    i += 6;
                    if (i < end && b[i] == '\r') i++;
                    if (i < end && b[i] == '\n') i++;
                    @SuppressWarnings("unchecked") Map<String, Object> d = (Map<String, Object>) o;
                    int len = streamLength(d);
                    int from = i;
                    int stop = len >= 0 && from + len <= end && endsHere(from + len) ? from + len : -1;
                    if (stop < 0) {
                        int e = indexOf(b, "endstream".getBytes(), from, end);
                        stop = e < 0 ? end : e;
                        while (stop > from && (b[stop - 1] == '\n' || b[stop - 1] == '\r')) stop--;
                    }
                    return new Stream(d, b, from, stop - from);
                }
                i = save;
            }
            return o == null ? NULL : o;
        }

        private boolean endsHere(int at) {
            int k = at;
            while (k < end && white(b[k])) k++;
            return startsWithAt(k, "endstream");
        }

        private int streamLength(Map<String, Object> d) {
            Object l = d.get("Length");
            if (l instanceof Double) return ((Double) l).intValue();
            if (l instanceof Ref) {
                Object v = PdfText.this.object(((Ref) l).num);
                if (v instanceof Double) return ((Double) v).intValue();
            }
            return -1;
        }

        void skipSpace() {
            while (i < end) {
                byte c = b[i];
                if (white(c)) i++;
                else if (c == '%') {
                    while (i < end && b[i] != '\n' && b[i] != '\r') i++;
                } else break;
            }
        }

        boolean startsWith(String s) {
            return startsWithAt(i, s);
        }

        boolean startsWithAt(int at, String s) {
            if (at + s.length() > end) return false;
            for (int k = 0; k < s.length(); k++) if (b[at + k] != s.charAt(k)) return false;
            return true;
        }

        /** The next token: a number (Double), name, string, array, dictionary, reference, operator; null at the end. */
        Object next() {
            skipSpace();
            if (i >= end) return null;
            byte c = b[i];
            if (c == '/') {
                i++;
                StringBuilder s = new StringBuilder();
                while (i < end && !white(b[i]) && !delim(b[i])) {
                    if (b[i] == '#' && i + 2 < end) {
                        int v = Character.digit(b[i + 1], 16) * 16 + Character.digit(b[i + 2], 16);
                        if (v >= 0) {
                            s.append((char) v);
                            i += 3;
                            continue;
                        }
                    }
                    s.append((char) (b[i++] & 0xff));
                }
                return new Name(s.toString());
            }
            if (c == '(') return new Str(literal());
            if (c == '<') {
                if (i + 1 < end && b[i + 1] == '<') {
                    i += 2;
                    Map<String, Object> m = new HashMap<String, Object>();
                    while (true) {
                        skipSpace();
                        if (i >= end) break;
                        if (b[i] == '>' && i + 1 < end && b[i + 1] == '>') {
                            i += 2;
                            break;
                        }
                        Object k = next();
                        if (!(k instanceof Name)) {
                            if (k == null) break;
                            continue;
                        }
                        Object v = value();
                        m.put(((Name) k).s, v);
                    }
                    return m;
                }
                i++;
                ByteArrayOutputStream h = new ByteArrayOutputStream();
                int hi = -1;
                while (i < end && b[i] != '>') {
                    int v = Character.digit(b[i++], 16);
                    if (v < 0) continue;
                    if (hi < 0) hi = v;
                    else {
                        h.write(hi * 16 + v);
                        hi = -1;
                    }
                }
                if (hi >= 0) h.write(hi * 16);
                i++;
                return new Str(h.toByteArray());
            }
            if (c == '[') {
                i++;
                List<Object> a = new ArrayList<Object>();
                while (true) {
                    skipSpace();
                    if (i >= end) break;
                    if (b[i] == ']') {
                        i++;
                        break;
                    }
                    Object v = value();
                    if (v == null) break;
                    a.add(v);
                }
                return a;
            }
            if (c == ']' || c == '>' || c == ')' || c == '{' || c == '}') {
                i++;
                return new Op(String.valueOf((char) c));
            }
            if (c == '+' || c == '-' || c == '.' || digit(c)) {
                int s = i;
                i++;
                while (i < end && (digit(b[i]) || b[i] == '.' || b[i] == '-')) i++;
                try {
                    return Double.parseDouble(new String(b, s, i - s, Charset.forName("ISO-8859-1")).replaceAll("(?<=.)-", ""));
                } catch (NumberFormatException e) {
                    return 0.0;
                }
            }
            int s = i;
            while (i < end && !white(b[i]) && !delim(b[i])) i++;
            if (i == s) {
                i++;
                return new Op(String.valueOf((char) c));
            }
            String w = new String(b, s, i - s, Charset.forName("ISO-8859-1"));
            switch (w) {
                case "true":
                    return Boolean.TRUE;
                case "false":
                    return Boolean.FALSE;
                case "null":
                    return NULL;
                default:
                    return new Op(w);
            }
        }

        /** A value inside an array or dictionary: "N G R" read as one reference. */
        Object value() {
            Object v = next();
            if (v instanceof Double) {
                int save = i;
                Object g = next();
                if (g instanceof Double) {
                    Object r = next();
                    if (r instanceof Op && ((Op) r).s.equals("R")) return new Ref(((Double) v).intValue());
                }
                i = save;
            }
            return v;
        }

        byte[] literal() {
            i++; // (
            ByteArrayOutputStream s = new ByteArrayOutputStream();
            int depth = 1;
            while (i < end) {
                byte c = b[i++];
                if (c == '(') {
                    depth++;
                    s.write(c);
                } else if (c == ')') {
                    if (--depth == 0) break;
                    s.write(c);
                } else if (c == '\\' && i < end) {
                    byte e = b[i++];
                    switch (e) {
                        case 'n':
                            s.write('\n');
                            break;
                        case 'r':
                            s.write('\r');
                            break;
                        case 't':
                            s.write('\t');
                            break;
                        case 'b':
                            s.write('\b');
                            break;
                        case 'f':
                            s.write('\f');
                            break;
                        case '\r':
                            if (i < end && b[i] == '\n') i++;
                            break;
                        case '\n':
                            break;
                        default:
                            if (e >= '0' && e <= '7') {
                                int v = e - '0';
                                for (int k = 0; k < 2 && i < end && b[i] >= '0' && b[i] <= '7'; k++) v = v * 8 + (b[i++] - '0');
                                s.write(v & 0xff);
                            } else {
                                s.write(e);
                            }
                    }
                } else {
                    s.write(c);
                }
            }
            return s.toByteArray();
        }
    }

    static boolean white(byte c) {
        return c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\f' || c == 0;
    }

    static boolean delim(byte c) {
        return c == '(' || c == ')' || c == '<' || c == '>' || c == '[' || c == ']' || c == '{' || c == '}' || c == '/' || c == '%';
    }

    static boolean digit(byte c) {
        return c >= '0' && c <= '9';
    }

    static int parseIntAt(byte[] b, int from, int to) {
        int v = 0;
        for (int k = from; k < to; k++) v = v * 10 + (b[k] - '0');
        return v;
    }

    static int indexOf(byte[] b, byte[] what, int from, int to) {
        outer:
        for (int i = from; i + what.length <= to; i++) {
            for (int k = 0; k < what.length; k++) if (b[i + k] != what[k]) continue outer;
            return i;
        }
        return -1;
    }

    static int lastIndexOf(byte[] b, byte[] what, int before) {
        outer:
        for (int i = Math.min(before - 1, b.length - what.length); i >= 0; i--) {
            for (int k = 0; k < what.length; k++) if (b[i + k] != what[k]) continue outer;
            return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ fonts

    /** How a font's codes become text and how wide each glyph is (thousandths of the font size). */
    final class Font {
        /** Code bytes → text (ToUnicode), by code length. */
        final Map<Long, String> map = new HashMap<Long, String>();
        /** Code lengths in bytes for codes starting with each first byte (from the codespace ranges). */
        final int[] lengthByFirst = new int[256];
        boolean composite;
        final String[] simple = new String[256];
        final Map<Long, Double> widths = new HashMap<Long, Double>();
        double defaultWidth = 500;

        Font(Map<String, Object> d) {
            java.util.Arrays.fill(lengthByFirst, 0);
            Object sub = resolve(d.get("Subtype"));
            composite = new Name("Type0").equals(sub);
            Object tu = resolve(d.get("ToUnicode"));
            if (tu instanceof Stream) cmap(decoded((Stream) tu));
            if (composite) {
                Object desc = resolve(d.get("DescendantFonts"));
                Map<String, Object> cid = desc instanceof List && !((List<?>) desc).isEmpty() ? dict(((List<?>) desc).get(0)) : null;
                if (cid != null) {
                    defaultWidth = cid.get("DW") != null ? number(cid.get("DW")) : 1000;
                    Object w = resolve(cid.get("W"));
                    if (w instanceof List) cidWidths((List<?>) w);
                }
            } else {
                encoding(resolve(d.get("Encoding")), d);
                Object fc = resolve(d.get("FirstChar"));
                Object w = resolve(d.get("Widths"));
                if (fc instanceof Double && w instanceof List) {
                    int first = ((Double) fc).intValue();
                    List<?> l = (List<?>) w;
                    for (int k = 0; k < l.size(); k++) widths.put((long) (first + k), number(l.get(k)));
                }
                Map<String, Object> fd = dict(d.get("FontDescriptor"));
                if (fd != null && fd.get("MissingWidth") != null) defaultWidth = number(fd.get("MissingWidth"));
            }
        }

        private void cidWidths(List<?> w) {
            int k = 0;
            while (k < w.size()) {
                Object a = resolve(w.get(k));
                if (!(a instanceof Double) || k + 1 >= w.size()) break;
                Object b = resolve(w.get(k + 1));
                long first = ((Double) a).longValue();
                if (b instanceof List) {
                    List<?> l = (List<?>) b;
                    for (int j = 0; j < l.size(); j++) widths.put(first + j, number(l.get(j)));
                    k += 2;
                } else if (k + 2 < w.size()) {
                    long last = (long) number(b);
                    double v = number(w.get(k + 2));
                    for (long c = first; c <= last && c - first < 65536; c++) widths.put(c, v);
                    k += 3;
                } else break;
            }
        }

        private void encoding(Object enc, Map<String, Object> font) {
            String base = "WinAnsiEncoding";
            List<?> diffs = null;
            if (enc instanceof Name) base = ((Name) enc).s;
            else if (enc instanceof Map) {
                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) enc;
                Object b = resolve(m.get("BaseEncoding"));
                if (b instanceof Name) base = ((Name) b).s;
                Object df = resolve(m.get("Differences"));
                if (df instanceof List) diffs = (List<?>) df;
            }
            Charset cs = Charset.forName("MacRomanEncoding".equals(base) ? "x-MacRoman" : "windows-1252");
            for (int c = 32; c < 256; c++) {
                try {
                    simple[c] = new String(new byte[]{(byte) c}, cs);
                } catch (Exception e) {
                    simple[c] = null;
                }
            }
            if (diffs != null) {
                int code = 0;
                for (Object o : diffs) {
                    o = resolve(o);
                    if (o instanceof Double) code = ((Double) o).intValue();
                    else if (o instanceof Name) {
                        if (code >= 0 && code < 256) simple[code] = Glyphs.unicode(((Name) o).s);
                        code++;
                    }
                }
            }
        }

        /** A ToUnicode CMap: its code space and the codes' text. */
        private void cmap(byte[] d) {
            Lexer lx = new Lexer(d, 0, d.length);
            List<Object> ops = new ArrayList<Object>();
            Object t;
            while ((t = lx.next()) != null) {
                if (t instanceof Op) {
                    String op = ((Op) t).s;
                    if (op.equals("endcodespacerange")) {
                        for (int k = 0; k + 1 < ops.size(); k += 2) {
                            if (ops.get(k) instanceof Str && ops.get(k + 1) instanceof Str) {
                                byte[] lo = ((Str) ops.get(k)).b, hi = ((Str) ops.get(k + 1)).b;
                                if (lo.length == 0) continue;
                                for (int f = lo[0] & 0xff; f <= (hi[0] & 0xff); f++) lengthByFirst[f] = lo.length;
                            }
                        }
                    } else if (op.equals("endbfchar")) {
                        for (int k = 0; k + 1 < ops.size(); k += 2) {
                            if (ops.get(k) instanceof Str && ops.get(k + 1) instanceof Str) {
                                byte[] src = ((Str) ops.get(k)).b;
                                map.put(key(src, 0, src.length), utf16(((Str) ops.get(k + 1)).b));
                                noteLength(src);
                            }
                        }
                    } else if (op.equals("endbfrange")) {
                        for (int k = 0; k + 2 < ops.size(); k += 3) {
                            if (!(ops.get(k) instanceof Str) || !(ops.get(k + 1) instanceof Str)) continue;
                            byte[] lo = ((Str) ops.get(k)).b, hi = ((Str) ops.get(k + 1)).b;
                            long a = key(lo, 0, lo.length), z = key(hi, 0, hi.length);
                            noteLength(lo);
                            Object dst = ops.get(k + 2);
                            for (long c = a; c <= z && c - a < 65536; c++) {
                                if (dst instanceof Str) {
                                    byte[] base = ((Str) dst).b.clone();
                                    if (base.length > 0) {
                                        long last = (base[base.length - 1] & 0xff) + (c - a);
                                        base[base.length - 1] = (byte) last;
                                        if (base.length > 1 && last > 255) base[base.length - 2] += (byte) (last >> 8);
                                    }
                                    map.put(c | ((long) lo.length << 40), utf16(base));
                                } else if (dst instanceof List) {
                                    List<?> l = (List<?>) dst;
                                    int idx = (int) (c - a);
                                    if (idx < l.size() && l.get(idx) instanceof Str) map.put(c | ((long) lo.length << 40), utf16(((Str) l.get(idx)).b));
                                }
                            }
                        }
                    }
                    ops.clear();
                } else {
                    ops.add(t);
                }
            }
        }

        private void noteLength(byte[] src) {
            if (src.length > 0 && lengthByFirst[src[0] & 0xff] == 0) lengthByFirst[src[0] & 0xff] = src.length;
        }

        long key(byte[] b, int from, int len) {
            long v = 0;
            for (int k = 0; k < len; k++) v = (v << 8) | (b[from + k] & 0xff);
            return v | ((long) len << 40);
        }

        /** A string's codes: their text and their widths (thousandths of an em). */
        void decode(byte[] s, StringBuilder text, double[] advance, int[] spaces) {
            int k = 0;
            while (k < s.length) {
                int len = lengthByFirst[s[k] & 0xff];
                if (len == 0) len = composite ? 2 : 1;
                if (k + len > s.length) len = s.length - k;
                long code = 0;
                for (int j = 0; j < len; j++) code = (code << 8) | (s[k + j] & 0xff);
                String u = map.get(code | ((long) len << 40));
                if (u == null && !composite && len == 1) u = simple[(int) code];
                if (u != null) {
                    text.append(u);
                    if (len == 1 && code == 32) spaces[0]++;
                }
                Double w = widths.get(code);
                advance[0] += w != null ? w : defaultWidth;
                k += len;
            }
        }
    }

    static String utf16(byte[] b) {
        if (b.length == 1) return String.valueOf((char) (b[0] & 0xff));
        return new String(b, Charset.forName("UTF-16BE"));
    }

    // ------------------------------------------------------------------ content

    /** Where the last glyphs ended (user space before the CTM), to tell a new word or line by the next. */
    private double lastX = Double.NaN, lastY, lastSize = 10;

    private void newLine() {
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
        lastX = Double.NaN;
    }

    /** One content stream (a page's or a form's) through the text operators. */
    private final class Content {
        final Map<String, Object> res;
        final int depth;
        Font font;
        double size = 10, charSpace, wordSpace, scale = 1, leading;
        double[] tm = {1, 0, 0, 1, 0, 0}, tlm = {1, 0, 0, 1, 0, 0};

        Content(Map<String, Object> res, int depth) {
            this.res = res;
            this.depth = depth;
        }

        void run(byte[] d) {
            Lexer lx = new Lexer(d, 0, d.length);
            List<Object> args = new ArrayList<Object>();
            Object t;
            while ((t = lx.next()) != null && out.length() < maxChars) {
                if (!(t instanceof Op)) {
                    args.add(t);
                    continue;
                }
                String op = ((Op) t).s;
                if (op.equals("BI")) { // an inline picture: up to its end
                    int e = indexOf(d, "EI".getBytes(), lx.i, d.length);
                    lx.i = e < 0 ? d.length : e + 2;
                    args.clear();
                    continue;
                }
                operator(op, args);
                args.clear();
            }
        }

        private double num(List<Object> a, int i) {
            return i < a.size() && a.get(i) instanceof Double ? (Double) a.get(i) : 0;
        }

        private void operator(String op, List<Object> a) {
            switch (op) {
                case "BT":
                    tm = new double[]{1, 0, 0, 1, 0, 0};
                    tlm = tm.clone();
                    break;
                case "Tf": {
                    Object n = a.size() > 0 ? a.get(0) : null;
                    size = num(a, 1);
                    Map<String, Object> fd = res == null ? null : dict(res.get("Font"));
                    Map<String, Object> f = fd != null && n instanceof Name ? dict(fd.get(((Name) n).s)) : null;
                    font = f == null ? null : fontOf(f);
                    break;
                }
                case "Tc":
                    charSpace = num(a, 0);
                    break;
                case "Tw":
                    wordSpace = num(a, 0);
                    break;
                case "Tz":
                    scale = num(a, 0) / 100.0;
                    break;
                case "TL":
                    leading = num(a, 0);
                    break;
                case "Td":
                    move(num(a, 0), num(a, 1));
                    break;
                case "TD":
                    leading = -num(a, 1);
                    move(num(a, 0), num(a, 1));
                    break;
                case "Tm":
                    tm = new double[]{num(a, 0), num(a, 1), num(a, 2), num(a, 3), num(a, 4), num(a, 5)};
                    tlm = tm.clone();
                    break;
                case "T*":
                    move(0, -leading);
                    break;
                case "Tj":
                    if (!a.isEmpty() && a.get(a.size() - 1) instanceof Str) show(((Str) a.get(a.size() - 1)).b);
                    break;
                case "'":
                    move(0, -leading);
                    if (!a.isEmpty() && a.get(a.size() - 1) instanceof Str) show(((Str) a.get(a.size() - 1)).b);
                    break;
                case "\"":
                    wordSpace = num(a, 0);
                    charSpace = num(a, 1);
                    move(0, -leading);
                    if (!a.isEmpty() && a.get(a.size() - 1) instanceof Str) show(((Str) a.get(a.size() - 1)).b);
                    break;
                case "TJ":
                    if (!a.isEmpty() && a.get(0) instanceof List) {
                        for (Object o : (List<?>) a.get(0)) {
                            if (o instanceof Str) show(((Str) o).b);
                            else if (o instanceof Double) {
                                double adj = (Double) o;
                                double tx = -adj / 1000.0 * size * scale;
                                tm[4] += tx * tm[0];
                                tm[5] += tx * tm[1];
                                // a gap a space wide inside an array is a word break
                                if (adj < -180 && out.length() > 0 && out.charAt(out.length() - 1) != ' ' && out.charAt(out.length() - 1) != '\n') {
                                    out.append(' ');
                                }
                                if (!Double.isNaN(lastX)) lastX = tm[4];
                            }
                        }
                    }
                    break;
                case "Do":
                    if (depth < 6 && !a.isEmpty() && a.get(0) instanceof Name && res != null) {
                        Map<String, Object> xo = dict(res.get("XObject"));
                        Object x = xo == null ? null : resolve(xo.get(((Name) a.get(0)).s));
                        if (x instanceof Stream && new Name("Form").equals(resolve(((Stream) x).dict.get("Subtype")))) {
                            Stream s = (Stream) x;
                            Map<String, Object> r = dict(s.dict.get("Resources"));
                            new Content(r != null ? r : res, depth + 1).run(decoded(s));
                        }
                    }
                    break;
                default:
                    break;
            }
        }

        private void move(double tx, double ty) {
            tlm = new double[]{tlm[0], tlm[1], tlm[2], tlm[3], tx * tlm[0] + ty * tlm[2] + tlm[4], tx * tlm[1] + ty * tlm[3] + tlm[5]};
            tm = tlm.clone();
        }

        private void show(byte[] s) {
            if (font == null) return;
            double h = Math.hypot(tm[2], tm[3]) * Math.abs(size), w = Math.hypot(tm[0], tm[1]) * Math.abs(size);
            double x = tm[4], y = tm[5];
            if (!Double.isNaN(lastX)) {
                double lineH = Math.max(1e-3, Math.max(h, lastSize));
                if (Math.abs(y - lastY) > 0.5 * lineH) {
                    newLine();
                } else if (x - lastX > 0.18 * Math.max(w, 1e-3) || x < lastX - 2 * Math.max(w, 1e-3)) {
                    char c = out.length() > 0 ? out.charAt(out.length() - 1) : '\n';
                    if (c != ' ' && c != '\n') out.append(' ');
                }
            }
            StringBuilder text = new StringBuilder();
            double[] adv = {0};
            int[] spaces = {0};
            font.decode(s, text, adv, spaces);
            out.append(text);
            int codes = s.length / (font.composite ? 2 : 1);
            double tx = (adv[0] / 1000.0 * size + charSpace * codes + wordSpace * spaces[0]) * scale;
            tm[4] += tx * tm[0];
            tm[5] += tx * tm[1];
            lastX = tm[4];
            lastY = tm[5];
            lastSize = h;
        }
    }

    private Font fontOf(Map<String, Object> d) {
        Font f = fonts.get(d);
        if (f == null) {
            f = new Font(d);
            fonts.put(d, f);
        }
        return f;
    }

    /** Glyph names to Unicode: uniXXXX / uXXXX, the Adobe names of Latin and Cyrillic letters and of punctuation. */
    static final class Glyphs {
        private static final Map<String, String> NAMES = new HashMap<String, String>();

        static {
            String upper = "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ", lower = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя";
            for (int k = 0; k < upper.length(); k++) {
                NAMES.put("afii" + (10017 + k), String.valueOf(upper.charAt(k)));
                NAMES.put("afii" + (10065 + k), String.valueOf(lower.charAt(k)));
            }
            String[] extra = {"afii10050", "Ґ", "afii10098", "ґ", "afii10051", "Ђ", "afii10052", "Ѓ", "afii10053", "Є",
                    "afii10054", "Ѕ", "afii10055", "І", "afii10056", "Ї", "afii10057", "Ј", "afii10058", "Љ", "afii10059", "Њ",
                    "afii10060", "Ћ", "afii10061", "Ќ", "afii10062", "Ў", "afii10145", "Џ", "afii10099", "ђ", "afii10100", "ѓ",
                    "afii10101", "є", "afii10102", "ѕ", "afii10103", "і", "afii10104", "ї", "afii10105", "ј", "afii10106", "љ",
                    "afii10107", "њ", "afii10108", "ћ", "afii10109", "ќ", "afii10110", "ў", "afii10193", "џ", "afii61352", "№",
                    "space", " ", "exclam", "!", "quotedbl", "\"", "numbersign", "#", "dollar", "$", "percent", "%",
                    "ampersand", "&", "quotesingle", "'", "parenleft", "(", "parenright", ")", "asterisk", "*", "plus", "+",
                    "comma", ",", "hyphen", "-", "period", ".", "slash", "/", "zero", "0", "one", "1", "two", "2", "three", "3",
                    "four", "4", "five", "5", "six", "6", "seven", "7", "eight", "8", "nine", "9", "colon", ":", "semicolon",
                    ";", "less", "<", "equal", "=", "greater", ">", "question", "?", "at", "@", "bracketleft", "[",
                    "backslash", "\\", "bracketright", "]", "asciicircum", "^", "underscore", "_", "grave", "`", "braceleft",
                    "{", "bar", "|", "braceright", "}", "asciitilde", "~", "quoteleft", "‘", "quoteright", "’",
                    "quotedblleft", "“", "quotedblright", "”", "quotedblbase", "„", "quotesinglbase", "‚", "endash", "–",
                    "emdash", "—", "bullet", "•", "ellipsis", "…", "guillemotleft", "«", "guillemotright", "»", "degree", "°",
                    "copyright", "©", "registered", "®", "trademark", "™", "section", "§", "paragraph", "¶", "periodcentered",
                    "·", "minus", "−", "multiply", "×", "divide", "÷", "plusminus", "±", "euro", "€", "sterling", "£", "yen",
                    "¥", "cent", "¢", "fi", "fi", "fl", "fl", "ff", "ff", "ffi", "ffi", "ffl", "ffl", "dotlessi", "ı",
                    "nbspace", " ", "uni00A0", " ", "dagger", "†", "daggerdbl", "‡", "perthousand", "‰"};
            for (int k = 0; k + 1 < extra.length; k += 2) NAMES.put(extra[k], extra[k + 1]);
        }

        static String unicode(String name) {
            String n = name;
            int dot = n.indexOf('.');
            if (dot > 0) n = n.substring(0, dot); // "a.sc", "one.oldstyle"
            String v = NAMES.get(n);
            if (v != null) return v;
            if (n.length() == 1 && Character.isLetter(n.charAt(0))) return n;
            try {
                if (n.startsWith("uni") && n.length() >= 7) {
                    StringBuilder b = new StringBuilder();
                    for (int k = 3; k + 4 <= n.length(); k += 4) b.append((char) Integer.parseInt(n.substring(k, k + 4), 16));
                    return b.toString();
                }
                if (n.startsWith("u") && n.length() >= 5 && n.length() <= 7) return new String(Character.toChars(Integer.parseInt(n.substring(1), 16)));
            } catch (IllegalArgumentException e) {
                return null;
            }
            // Latin letters with marks ("eacute", "Agrave"): the letter itself is enough for meaning
            if (n.length() > 1 && Character.isLetter(n.charAt(0)) && (n.endsWith("acute") || n.endsWith("grave") || n.endsWith("dieresis")
                    || n.endsWith("circumflex") || n.endsWith("tilde") || n.endsWith("cedilla") || n.endsWith("ring") || n.endsWith("caron"))) {
                return n.substring(0, 1);
            }
            return null;
        }
    }
}
