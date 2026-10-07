package io.github.teoplaydor.semsearch.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;

/**
 * Rewrites an ONNX graph so every 4-bit {@code MatMulNBits} node gets {@code accuracy_level = 4}.
 *
 * onnx-community q4 exports leave the attribute unset, so ONNX Runtime de-quantizes the weights
 * and multiplies in fp32. With level 4 it quantizes activations to int8 per block and uses the
 * int8 dot-product kernels (KleidiAI / i8mm on ARM64), which is several times faster on phones.
 * Weights stay in the external .onnx_data file; only the small graph file is rewritten, so the
 * patched copy must live next to the original.
 *
 * Minimal protobuf handling: ModelProto.graph (7) → GraphProto.node (1) → NodeProto.op_type (4) /
 * attribute (5) → AttributeProto name (1), i (3), type (20). Everything else is copied verbatim.
 */
public final class OnnxPatcher {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int ATTR_TYPE_INT = 2;

    private OnnxPatcher() {}

    /** @return number of MatMulNBits nodes set to {@code level} (0 = nothing to patch). */
    public static int setMatMulNBitsAccuracy(File in, File out, int level) throws IOException {
        byte[] model = readAll(in);
        int[] count = {0};
        byte[] patched = rewriteModel(model, level, count);
        File tmp = new File(out.getPath() + ".tmp");
        OutputStream os = new FileOutputStream(tmp);
        try {
            os.write(patched);
        } finally {
            os.close();
        }
        if (out.exists() && !out.delete()) throw new IOException("cannot replace " + out);
        if (!tmp.renameTo(out)) throw new IOException("cannot write " + out);
        return count[0];
    }

    // ---------------------------------------------------------------- message rewriting

    private static byte[] rewriteModel(byte[] b, int level, int[] count) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length + 1024);
        Reader r = new Reader(b, 0, b.length);
        while (r.more()) {
            int start = r.pos;
            long key = r.varint();
            int field = (int) (key >>> 3), wire = (int) (key & 7);
            if (field == 7 && wire == 2) {
                int len = (int) r.varint();
                byte[] g = rewriteGraph(b, r.pos, r.pos + len, level, count);
                r.pos += len;
                writeLenField(out, 7, g);
            } else {
                r.skip(wire);
                out.write(b, start, r.pos - start);
            }
        }
        return out.toByteArray();
    }

    private static byte[] rewriteGraph(byte[] b, int from, int to, int level, int[] count) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(to - from + 256);
        Reader r = new Reader(b, from, to);
        while (r.more()) {
            int start = r.pos;
            long key = r.varint();
            int field = (int) (key >>> 3), wire = (int) (key & 7);
            if (field == 1 && wire == 2) {
                int len = (int) r.varint();
                byte[] node = rewriteNode(b, r.pos, r.pos + len, level, count);
                r.pos += len;
                writeLenField(out, 1, node);
            } else {
                r.skip(wire);
                out.write(b, start, r.pos - start);
            }
        }
        return out.toByteArray();
    }

    private static byte[] rewriteNode(byte[] b, int from, int to, int level, int[] count) throws IOException {
        // First pass: is this a MatMulNBits node?
        Reader r = new Reader(b, from, to);
        boolean target = false;
        while (r.more()) {
            long key = r.varint();
            int field = (int) (key >>> 3), wire = (int) (key & 7);
            if (field == 4 && wire == 2) {
                int len = (int) r.varint();
                target = "MatMulNBits".equals(new String(b, r.pos, len, UTF8));
                r.pos += len;
            } else {
                r.skip(wire);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(to - from + 32);
        if (!target) {
            out.write(b, from, to - from);
            return out.toByteArray();
        }
        // Second pass: copy everything except an existing accuracy_level attribute, then append ours.
        r = new Reader(b, from, to);
        while (r.more()) {
            int start = r.pos;
            long key = r.varint();
            int field = (int) (key >>> 3), wire = (int) (key & 7);
            if (field == 5 && wire == 2) {
                int len = (int) r.varint();
                boolean isAccuracy = "accuracy_level".equals(attributeName(b, r.pos, r.pos + len));
                r.pos += len;
                if (isAccuracy) continue;
                out.write(b, start, r.pos - start);
            } else {
                r.skip(wire);
                out.write(b, start, r.pos - start);
            }
        }
        ByteArrayOutputStream attr = new ByteArrayOutputStream();
        writeLenField(attr, 1, "accuracy_level".getBytes(UTF8));
        writeVarint(attr, (3L << 3)); // i (varint)
        writeVarint(attr, level);
        writeVarint(attr, (20L << 3)); // type
        writeVarint(attr, ATTR_TYPE_INT);
        writeLenField(out, 5, attr.toByteArray());
        count[0]++;
        return out.toByteArray();
    }

    private static String attributeName(byte[] b, int from, int to) throws IOException {
        Reader r = new Reader(b, from, to);
        while (r.more()) {
            long key = r.varint();
            int field = (int) (key >>> 3), wire = (int) (key & 7);
            if (field == 1 && wire == 2) {
                int len = (int) r.varint();
                return new String(b, r.pos, len, UTF8);
            }
            r.skip(wire);
        }
        return null;
    }

    // ---------------------------------------------------------------- wire format

    private static final class Reader {
        final byte[] b;
        int pos;
        final int end;

        Reader(byte[] b, int pos, int end) {
            this.b = b;
            this.pos = pos;
            this.end = end;
        }

        boolean more() {
            return pos < end;
        }

        long varint() throws IOException {
            long v = 0;
            int shift = 0;
            while (true) {
                if (pos >= end) throw new IOException("truncated varint");
                int x = b[pos++] & 0xff;
                v |= (long) (x & 0x7f) << shift;
                if ((x & 0x80) == 0) return v;
                shift += 7;
                if (shift > 63) throw new IOException("bad varint");
            }
        }

        void skip(int wire) throws IOException {
            switch (wire) {
                case 0: varint(); break;
                case 1: pos += 8; break;
                case 2: {
                    long len = varint();
                    pos += (int) len;
                    break;
                }
                case 5: pos += 4; break;
                default: throw new IOException("unsupported wire type " + wire);
            }
            if (pos > end) throw new IOException("truncated field");
        }
    }

    private static void writeVarint(ByteArrayOutputStream out, long v) {
        while ((v & ~0x7fL) != 0) {
            out.write((int) ((v & 0x7f) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    private static void writeLenField(ByteArrayOutputStream out, int field, byte[] data) {
        writeVarint(out, ((long) field << 3) | 2);
        writeVarint(out, data.length);
        out.write(data, 0, data.length);
    }

    private static byte[] readAll(File f) throws IOException {
        long n = f.length();
        if (n > Integer.MAX_VALUE - 16) throw new IOException("graph file too large: " + n);
        byte[] b = new byte[(int) n];
        InputStream in = new FileInputStream(f);
        try {
            int off = 0;
            while (off < b.length) {
                int r = in.read(b, off, b.length - off);
                if (r < 0) throw new IOException("short read");
                off += r;
            }
        } finally {
            in.close();
        }
        return b;
    }
}
