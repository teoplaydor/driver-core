package io.github.teoplaydor.semsearch.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * What Android recorded about a native crash, read from its tombstone (ApplicationExitInfo.getTraceInputStream:
 * the protobuf of AOSP's tombstone.proto since Android 12, plain text before): the signal, the abort message
 * (an uncaught C++ exception or an assertion says itself there), the top of the crashing thread's stack and the
 * last lines of the process's log — where in ONNX Runtime or QNN the NPU process died, and what it said last.
 */
public final class Tombstone {
    public String signal = "", abortMessage = "", cause = "";
    public final List<String> frames = new ArrayList<String>();
    public final List<String> log = new ArrayList<String>();

    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0 && b.size() < (8 << 20)) b.write(buf, 0, n);
        return b.toByteArray();
    }

    /** The tombstone in protobuf or as text; null when neither gives anything. */
    public static Tombstone parse(byte[] b) {
        Tombstone t;
        try {
            t = proto(b);
        } catch (RuntimeException e) {
            t = null;
        }
        if (t == null || (t.signal.isEmpty() && t.frames.isEmpty() && t.abortMessage.isEmpty())) t = text(new String(b, UTF8));
        return t == null || (t.signal.isEmpty() && t.frames.isEmpty() && t.abortMessage.isEmpty()) ? null : t;
    }

    /** For the report: the top {@code maxFrames} frames of the stack and the last {@code maxLog} log lines. */
    public String summary(int maxFrames, int maxLog) {
        StringBuilder sb = new StringBuilder();
        if (!signal.isEmpty()) sb.append("сигнал ").append(signal);
        if (!abortMessage.isEmpty()) sb.append(sb.length() > 0 ? "; " : "").append("сообщение: ").append(clip(abortMessage, 300));
        if (!cause.isEmpty()) sb.append(sb.length() > 0 ? "; " : "").append(clip(cause, 200));
        if (!frames.isEmpty()) {
            sb.append(sb.length() > 0 ? "\n" : "").append("стек:");
            for (int i = 0; i < frames.size() && i < maxFrames; i++) sb.append("\n  #").append(i).append(' ').append(frames.get(i));
        }
        if (!log.isEmpty()) {
            sb.append("\nжурнал перед падением:");
            for (int i = Math.max(0, log.size() - maxLog); i < log.size(); i++) sb.append("\n  ").append(clip(log.get(i), 220));
        }
        return sb.toString();
    }

    private static String clip(String s, int n) {
        s = s.trim();
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    // ---------------------------------------------------------------- protobuf (tombstone.proto)

    private static final class Reader {
        final byte[] b;
        int pos;
        final int end;

        Reader(byte[] b, int from, int to) {
            this.b = b;
            pos = from;
            end = to;
        }

        boolean more() {
            return pos < end;
        }

        long varint() {
            long v = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= end) throw new IllegalStateException("truncated");
                int x = b[pos++] & 0xff;
                v |= (long) (x & 0x7f) << shift;
                if ((x & 0x80) == 0) return v;
            }
            throw new IllegalStateException("bad varint");
        }

        /** The field's bytes (length-delimited) as {from, to}; skips other wire types and returns null. */
        int[] next(int wire) {
            switch (wire) {
                case 0:
                    varint();
                    return null;
                case 1:
                    pos += 8;
                    return null;
                case 5:
                    pos += 4;
                    return null;
                case 2: {
                    int len = (int) varint();
                    if (len < 0 || pos + len > end) throw new IllegalStateException("bad length");
                    int[] r = {pos, pos + len};
                    pos += len;
                    return r;
                }
                default:
                    throw new IllegalStateException("wire type " + wire);
            }
        }
    }

    private static String str(byte[] b, int[] r) {
        return new String(b, r[0], r[1] - r[0], UTF8);
    }

    static Tombstone proto(byte[] b) {
        Tombstone t = new Tombstone();
        Reader r = new Reader(b, 0, b.length);
        long tid = -1;
        List<int[]> threads = new ArrayList<int[]>();
        List<int[]> buffers = new ArrayList<int[]>();
        while (r.more()) {
            long key = r.varint();
            int field = (int) (key >>> 3), wire = (int) (key & 7);
            if (field == 6 && wire == 0) {
                tid = r.varint();
                continue;
            }
            int[] v = r.next(wire);
            if (v == null) continue;
            switch (field) {
                case 10:
                    t.signal = signal(b, v);
                    break;
                case 14:
                    t.abortMessage = str(b, v);
                    break;
                case 15:
                    if (t.cause.isEmpty()) {
                        Reader c = new Reader(b, v[0], v[1]);
                        while (c.more()) {
                            long k = c.varint();
                            int[] cv = c.next((int) (k & 7));
                            if (cv != null && (k >>> 3) == 1) t.cause = str(b, cv);
                        }
                    }
                    break;
                case 16:
                    threads.add(v);
                    break;
                case 18:
                    buffers.add(v);
                    break;
                default:
                    break;
            }
        }
        // threads: map<uint32, Thread> — entries {1: key, 2: Thread}; the crashing one has the tombstone's tid
        int[] crashing = null;
        for (int[] e : threads) {
            Reader m = new Reader(b, e[0], e[1]);
            long id = -1;
            int[] thread = null;
            while (m.more()) {
                long k = m.varint();
                int f = (int) (k >>> 3), w = (int) (k & 7);
                if (f == 1 && w == 0) {
                    id = m.varint();
                    continue;
                }
                int[] mv = m.next(w);
                if (f == 2 && mv != null) thread = mv;
            }
            if (thread != null && (crashing == null || id == tid)) crashing = thread;
        }
        if (crashing != null) {
            Reader th = new Reader(b, crashing[0], crashing[1]);
            while (th.more()) {
                long k = th.varint();
                int[] fv = th.next((int) (k & 7));
                if (fv != null && (k >>> 3) == 4) t.frames.add(frame(b, fv));
            }
        }
        for (int[] buf : buffers) {
            Reader lb = new Reader(b, buf[0], buf[1]);
            while (lb.more()) {
                long k = lb.varint();
                int[] lv = lb.next((int) (k & 7));
                if (lv != null && (k >>> 3) == 2) t.log.add(logLine(b, lv));
            }
        }
        if (t.log.size() > 60) t.log.subList(0, t.log.size() - 60).clear();
        return t;
    }

    private static String signal(byte[] b, int[] v) {
        Reader s = new Reader(b, v[0], v[1]);
        String name = "", code = "";
        long number = 0, fault = 0;
        boolean hasFault = false;
        while (s.more()) {
            long k = s.varint();
            int f = (int) (k >>> 3), w = (int) (k & 7);
            if (w == 0) {
                long x = s.varint();
                if (f == 1) number = x;
                else if (f == 8) hasFault = x != 0;
                else if (f == 9) fault = x;
                continue;
            }
            int[] sv = s.next(w);
            if (sv == null) continue;
            if (f == 2) name = str(b, sv);
            else if (f == 4) code = str(b, sv);
        }
        return (name.isEmpty() ? String.valueOf(number) : name) + (code.isEmpty() ? "" : " (" + code + ")")
                + (hasFault ? String.format(java.util.Locale.ROOT, ", адрес 0x%x", fault) : "");
    }

    /** "library.so function+offset" of a BacktraceFrame. */
    private static String frame(byte[] b, int[] v) {
        Reader f = new Reader(b, v[0], v[1]);
        String fn = "", file = "";
        long off = 0, rel = 0;
        while (f.more()) {
            long k = f.varint();
            int n = (int) (k >>> 3), w = (int) (k & 7);
            if (w == 0) {
                long x = f.varint();
                if (n == 1) rel = x;
                else if (n == 5) off = x;
                continue;
            }
            int[] fv = f.next(w);
            if (fv == null) continue;
            if (n == 4) fn = str(b, fv);
            else if (n == 6) file = str(b, fv);
        }
        String lib = file.substring(file.lastIndexOf('/') + 1);
        if (fn.length() > 90) fn = fn.substring(0, 90) + "…";
        return (lib.isEmpty() ? "?" : lib) + (fn.isEmpty() ? String.format(java.util.Locale.ROOT, " pc 0x%x", rel) : " " + fn + "+" + off);
    }

    /** "P/tag: message" of a LogMessage. */
    private static String logLine(byte[] b, int[] v) {
        Reader l = new Reader(b, v[0], v[1]);
        String tag = "", msg = "";
        long prio = 0;
        while (l.more()) {
            long k = l.varint();
            int n = (int) (k >>> 3), w = (int) (k & 7);
            if (w == 0) {
                long x = l.varint();
                if (n == 4) prio = x;
                continue;
            }
            int[] lv = l.next(w);
            if (lv == null) continue;
            if (n == 5) tag = str(b, lv);
            else if (n == 6) msg = str(b, lv);
        }
        String p = prio >= 2 && prio <= 7 ? String.valueOf("VDIWEF".charAt((int) prio - 2)) : "?";
        return p + "/" + tag + ": " + msg.trim();
    }

    // ---------------------------------------------------------------- text (before Android 12)

    static Tombstone text(String s) {
        Tombstone t = new Tombstone();
        boolean inBacktrace = false;
        for (String line : s.split("\n")) {
            String l = line.trim();
            if (l.startsWith("signal ")) {
                t.signal = l.substring(7).trim();
            } else if (l.startsWith("Abort message:")) {
                t.abortMessage = l.substring(14).trim().replaceAll("^'|'$", "");
            } else if (l.startsWith("Cause:")) {
                t.cause = l.substring(6).trim();
            } else if (l.equals("backtrace:")) {
                inBacktrace = t.frames.isEmpty();
            } else if (inBacktrace && l.startsWith("#")) {
                // "#00 pc 000000000004f1c8  /system/lib64/libc.so (abort+168) (BuildId: …)"
                String[] p = l.split("\\s+", 4);
                if (p.length >= 4) {
                    String rest = p[3].replaceAll(" \\(BuildId: [^)]*\\)", "");
                    int sp = rest.indexOf(' ');
                    String file = sp > 0 ? rest.substring(0, sp) : rest, fn = sp > 0 ? rest.substring(sp + 1) : "";
                    t.frames.add(file.substring(file.lastIndexOf('/') + 1) + (fn.isEmpty() ? " pc 0x" + p[2] : " " + fn.replaceAll("^\\(|\\)$", "")));
                }
            } else if (inBacktrace && !l.isEmpty()) {
                inBacktrace = false;
            }
        }
        return t;
    }
}
