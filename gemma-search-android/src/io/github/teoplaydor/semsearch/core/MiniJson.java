package io.github.teoplaydor.semsearch.core;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal streaming JSON reader. Works on both desktop JVM and Android, so the
 * tokenizer can be tested off-device. Large arrays/objects (vocab, merges) are
 * consumed element by element instead of being materialised as a tree.
 */
public final class MiniJson {
    public enum Token { BEGIN_OBJECT, END_OBJECT, BEGIN_ARRAY, END_ARRAY, STRING, NUMBER, BOOLEAN, NULL, END }

    private final Reader in;
    private final char[] buf = new char[1 << 16];
    private int pos, limit;
    private final StringBuilder sb = new StringBuilder();

    public MiniJson(Reader in) {
        this.in = in;
    }

    private int read() throws IOException {
        if (pos == limit) {
            limit = in.read(buf, 0, buf.length);
            pos = 0;
            if (limit <= 0) {
                limit = 0;
                return -1;
            }
        }
        return buf[pos++];
    }

    private int peekRaw() throws IOException {
        if (pos == limit) {
            limit = in.read(buf, 0, buf.length);
            pos = 0;
            if (limit <= 0) {
                limit = 0;
                return -1;
            }
        }
        return buf[pos];
    }

    /** Skips whitespace, commas and colons (the reader is lenient about separators). */
    private int peekSignificant() throws IOException {
        while (true) {
            int c = peekRaw();
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == ',' || c == ':') {
                pos++;
                continue;
            }
            return c;
        }
    }

    public Token peek() throws IOException {
        int c = peekSignificant();
        switch (c) {
            case -1: return Token.END;
            case '{': return Token.BEGIN_OBJECT;
            case '}': return Token.END_OBJECT;
            case '[': return Token.BEGIN_ARRAY;
            case ']': return Token.END_ARRAY;
            case '"': return Token.STRING;
            case 't': case 'f': return Token.BOOLEAN;
            case 'n': return Token.NULL;
            default: return Token.NUMBER;
        }
    }

    private void expect(char ch) throws IOException {
        int c = peekSignificant();
        if (c != ch) throw new IOException("JSON: expected '" + ch + "' but got '" + (char) c + "'");
        pos++;
    }

    public void beginObject() throws IOException { expect('{'); }
    public void endObject() throws IOException { expect('}'); }
    public void beginArray() throws IOException { expect('['); }
    public void endArray() throws IOException { expect(']'); }

    public boolean hasNext() throws IOException {
        int c = peekSignificant();
        return c != '}' && c != ']' && c != -1;
    }

    public String nextName() throws IOException {
        return nextString();
    }

    public String nextString() throws IOException {
        expect('"');
        sb.setLength(0);
        while (true) {
            int c = read();
            if (c == -1) throw new IOException("JSON: unterminated string");
            if (c == '"') break;
            if (c == '\\') {
                int e = read();
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u': {
                        int v = 0;
                        for (int i = 0; i < 4; i++) {
                            int h = read();
                            v = (v << 4) | Character.digit(h, 16);
                        }
                        sb.append((char) v);
                        break;
                    }
                    default: throw new IOException("JSON: bad escape \\" + (char) e);
                }
            } else {
                sb.append((char) c);
            }
        }
        return sb.toString();
    }

    private String nextLiteral() throws IOException {
        peekSignificant();
        sb.setLength(0);
        while (true) {
            int c = peekRaw();
            if (c == -1 || c == ',' || c == '}' || c == ']' || c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == ':') break;
            sb.append((char) c);
            pos++;
        }
        return sb.toString();
    }

    public double nextDouble() throws IOException { return Double.parseDouble(nextLiteral()); }

    public long nextLong() throws IOException {
        String s = nextLiteral();
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return (long) Double.parseDouble(s);
        }
    }

    public int nextInt() throws IOException { return (int) nextLong(); }

    public boolean nextBoolean() throws IOException {
        String s = nextLiteral();
        if ("true".equals(s)) return true;
        if ("false".equals(s)) return false;
        throw new IOException("JSON: expected boolean, got " + s);
    }

    public void nextNull() throws IOException {
        String s = nextLiteral();
        if (!"null".equals(s)) throw new IOException("JSON: expected null, got " + s);
    }

    public void skipValue() throws IOException {
        readValue();
    }

    /** Reads any value as Map / List / String / Double / Long / Boolean / null. */
    public Object readValue() throws IOException {
        switch (peek()) {
            case BEGIN_OBJECT: {
                beginObject();
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                while (hasNext()) {
                    String k = nextName();
                    m.put(k, readValue());
                }
                endObject();
                return m;
            }
            case BEGIN_ARRAY: {
                beginArray();
                List<Object> l = new ArrayList<Object>();
                while (hasNext()) l.add(readValue());
                endArray();
                return l;
            }
            case STRING: return nextString();
            case BOOLEAN: return nextBoolean();
            case NULL: nextNull(); return null;
            case NUMBER: {
                String s = nextLiteral();
                if (s.indexOf('.') < 0 && s.indexOf('e') < 0 && s.indexOf('E') < 0) {
                    try {
                        return Long.parseLong(s);
                    } catch (NumberFormatException ignored) {
                        // Out of long range, e.g. tokenizer_config.json "model_max_length": 1000000000000000019884624838656
                    }
                }
                return Double.parseDouble(s); // also accepts NaN / Infinity written by Python's json
            }
            default: throw new IOException("JSON: unexpected token " + peek());
        }
    }

    // ---- small helpers for working with readValue() trees ----

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    public static String str(Map<String, Object> m, String k, String def) {
        Object v = m == null ? null : m.get(k);
        return v instanceof String ? (String) v : def;
    }

    public static long num(Map<String, Object> m, String k, long def) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Number ? ((Number) v).longValue() : def;
    }

    public static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    public static Object parse(String s) throws IOException {
        return new MiniJson(new java.io.StringReader(s)).readValue();
    }

    /** Serialises a readValue() tree back to JSON (used for the tokenizer cache). */
    public static String write(Object o) {
        StringBuilder out = new StringBuilder();
        write(o, out);
        return out.toString();
    }

    private static void write(Object o, StringBuilder out) {
        if (o == null) {
            out.append("null");
        } else if (o instanceof String) {
            quote((String) o, out);
        } else if (o instanceof Number || o instanceof Boolean) {
            out.append(o.toString());
        } else if (o instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : obj(o).entrySet()) {
                if (!first) out.append(',');
                first = false;
                quote(e.getKey(), out);
                out.append(':');
                write(e.getValue(), out);
            }
            out.append('}');
        } else if (o instanceof List) {
            out.append('[');
            boolean first = true;
            for (Object x : arr(o)) {
                if (!first) out.append(',');
                first = false;
                write(x, out);
            }
            out.append(']');
        } else {
            quote(o.toString(), out);
        }
    }

    private static void quote(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }
}
