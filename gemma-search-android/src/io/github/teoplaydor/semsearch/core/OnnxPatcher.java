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

    /**
     * Names of the graph inputs and, per dimension, its symbolic name (dim_param) or fixed size. Streams
     * the file (ModelProto.graph (7) → GraphProto.input (11) → ValueInfoProto name (1) / type (2) →
     * TypeProto.tensor_type (1) → shape (2) → dim (1): dim_value (1) | dim_param (2)) and skips everything
     * else, so a graph with embedded weights is never loaded into memory.
     */
    public static java.util.Map<String, java.util.List<String>> inputDims(File f) throws IOException {
        java.util.Map<String, java.util.List<String>> out = new java.util.LinkedHashMap<String, java.util.List<String>>();
        java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(new FileInputStream(f), 1 << 16));
        try {
            long[] pos = {0};
            long end = f.length();
            while (pos[0] < end) {
                long key = varint(in, pos);
                int field = (int) (key >>> 3), wire = (int) (key & 7);
                if (field == 7 && wire == 2) {
                    long graphEnd = pos[0] + varint(in, pos);
                    while (pos[0] < graphEnd) {
                        long k = varint(in, pos);
                        int gf = (int) (k >>> 3), gw = (int) (k & 7);
                        if (gf == 11 && gw == 2) {
                            int len = (int) varint(in, pos);
                            byte[] vi = new byte[len];
                            in.readFully(vi);
                            pos[0] += len;
                            parseValueInfo(vi, out);
                        } else {
                            skipStream(in, gw, pos);
                        }
                    }
                    break;
                }
                skipStream(in, wire, pos);
            }
        } finally {
            in.close();
        }
        return out;
    }

    private static void parseValueInfo(byte[] b, java.util.Map<String, java.util.List<String>> out) throws IOException {
        Reader r = new Reader(b, 0, b.length);
        String name = null;
        java.util.List<String> dims = new java.util.ArrayList<String>();
        while (r.more()) {
            long key = r.varint();
            int field = (int) (key >>> 3), wire = (int) (key & 7);
            if (field == 1 && wire == 2) {
                int len = (int) r.varint();
                name = new String(b, r.pos, len, UTF8);
                r.pos += len;
            } else if (field == 2 && wire == 2) {
                int len = (int) r.varint();
                int typeEnd = r.pos + len;
                // TypeProto.tensor_type (1) → shape (2) → dim (1)
                byte[] tensor = sub(b, r, typeEnd, 1);
                r.pos = typeEnd;
                if (tensor == null) continue;
                Reader tr = new Reader(tensor, 0, tensor.length);
                byte[] shape = sub(tensor, tr, tensor.length, 2);
                if (shape == null) continue;
                Reader sr = new Reader(shape, 0, shape.length);
                while (sr.more()) {
                    long k = sr.varint();
                    int sf = (int) (k >>> 3), sw = (int) (k & 7);
                    if (sf == 1 && sw == 2) {
                        int dl = (int) sr.varint();
                        Reader dr = new Reader(shape, sr.pos, sr.pos + dl);
                        String d = "?";
                        while (dr.more()) {
                            long dk = dr.varint();
                            int df = (int) (dk >>> 3), dw = (int) (dk & 7);
                            if (df == 1 && dw == 0) {
                                d = String.valueOf(dr.varint());
                            } else if (df == 2 && dw == 2) {
                                int pl = (int) dr.varint();
                                d = new String(shape, dr.pos, pl, UTF8);
                                dr.pos += pl;
                            } else {
                                dr.skip(dw);
                            }
                        }
                        dims.add(d);
                        sr.pos += dl;
                    } else {
                        sr.skip(sw);
                    }
                }
            } else {
                r.skip(wire);
            }
        }
        if (name != null) out.put(name, dims);
    }

    /** The first length-delimited field {@code field} in [r.pos, end), or null. */
    private static byte[] sub(byte[] b, Reader r, int end, int field) throws IOException {
        Reader x = new Reader(b, r.pos, end);
        while (x.more()) {
            long key = x.varint();
            int f = (int) (key >>> 3), w = (int) (key & 7);
            if (f == field && w == 2) {
                int len = (int) x.varint();
                byte[] out = new byte[len];
                System.arraycopy(b, x.pos, out, 0, len);
                return out;
            }
            x.skip(w);
        }
        return null;
    }

    /** A graph node, as much of NodeProto as a structural summary needs. */
    public static final class Node {
        public String opType = "", domain = "", name = "";
        public final java.util.List<String> inputs = new java.util.ArrayList<String>(), outputs = new java.util.ArrayList<String>();
    }

    /**
     * The graph's nodes in file (topological) order and the opsets (domain → version), streamed like
     * {@link #inputDims}: ModelProto.opset_import (8) and graph (7) → node (1); initializers are skipped.
     */
    public static java.util.List<Node> nodes(File f, java.util.Map<String, Long> opsets) throws IOException {
        java.util.List<Node> out = new java.util.ArrayList<Node>();
        java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(new FileInputStream(f), 1 << 16));
        try {
            long[] pos = {0};
            long end = f.length();
            while (pos[0] < end) {
                long key = varint(in, pos);
                int field = (int) (key >>> 3), wire = (int) (key & 7);
                if (field == 8 && wire == 2) {
                    byte[] b = readBytes(in, pos);
                    Reader r = new Reader(b, 0, b.length);
                    String domain = "";
                    long version = 0;
                    while (r.more()) {
                        long k = r.varint();
                        int f2 = (int) (k >>> 3), w = (int) (k & 7);
                        if (f2 == 1 && w == 2) domain = r.string();
                        else if (f2 == 2 && w == 0) version = r.varint();
                        else r.skip(w);
                    }
                    if (opsets != null) opsets.put(domain.isEmpty() ? "ai.onnx" : domain, version);
                } else if (field == 7 && wire == 2) {
                    long graphEnd = pos[0] + varint(in, pos);
                    while (pos[0] < graphEnd) {
                        long k = varint(in, pos);
                        int gf = (int) (k >>> 3), gw = (int) (k & 7);
                        if (gf == 1 && gw == 2) out.add(parseNode(readBytes(in, pos)));
                        else skipStream(in, gw, pos);
                    }
                } else {
                    skipStream(in, wire, pos);
                }
            }
        } finally {
            in.close();
        }
        return out;
    }

    private static byte[] readBytes(java.io.DataInputStream in, long[] pos) throws IOException {
        int len = (int) varint(in, pos);
        byte[] b = new byte[len];
        in.readFully(b);
        pos[0] += len;
        return b;
    }

    private static Node parseNode(byte[] b) throws IOException {
        Node n = new Node();
        Reader r = new Reader(b, 0, b.length);
        while (r.more()) {
            long k = r.varint();
            int f = (int) (k >>> 3), w = (int) (k & 7);
            if (w != 2) {
                r.skip(w);
                continue;
            }
            switch (f) {
                case 1: n.inputs.add(r.string()); break;
                case 2: n.outputs.add(r.string()); break;
                case 3: n.name = r.string(); break;
                case 4: n.opType = r.string(); break;
                case 7: n.domain = r.string(); break;
                default: r.skip(w);
            }
        }
        return n;
    }

    private static final String[] FUSED_ATTENTION = {"MultiHeadAttention", "Attention", "GroupQueryAttention",
            "PackedMultiHeadAttention", "SparseAttention"};

    /**
     * How a transformer graph computes attention, for the speed report: size, opsets, the most frequent ops,
     * whether attention is a fused op or spelled out (MatMul → Softmax → MatMul), and the ops of the first
     * attention block in order (to plan a rewrite to a fused, flash-attention op).
     */
    public static String graphSummary(File f) throws IOException {
        java.util.Map<String, Long> opsets = new java.util.TreeMap<String, Long>();
        java.util.List<Node> nodes = nodes(f, opsets);
        final java.util.Map<String, Integer> count = new java.util.HashMap<String, Integer>();
        for (Node n : nodes) {
            String op = n.domain.isEmpty() || "ai.onnx".equals(n.domain) ? n.opType : n.domain + ":" + n.opType;
            count.put(op, count.containsKey(op) ? count.get(op) + 1 : 1);
        }
        java.util.List<String> ops = new java.util.ArrayList<String>(count.keySet());
        java.util.Collections.sort(ops, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return count.get(b) - count.get(a);
            }
        });
        StringBuilder sb = new StringBuilder();
        sb.append(f.getName()).append(": ").append(nodes.size()).append(" узлов, opset");
        for (java.util.Map.Entry<String, Long> e : opsets.entrySet()) sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
        sb.append("\nЧаще всего:");
        for (int i = 0; i < ops.size() && i < 14; i++) sb.append(i == 0 ? " " : ", ").append(ops.get(i)).append(" ×").append(count.get(ops.get(i)));
        int fused = 0;
        for (String a : FUSED_ATTENTION) {
            for (String op : ops) if (op.equals(a) || op.endsWith(":" + a)) fused += count.get(op);
        }
        int softmax = count.containsKey("Softmax") ? count.get("Softmax") : 0;
        sb.append("\nВнимание: ").append(fused > 0 ? "слитое (" + fused + " узлов)" : softmax > 0
                ? "по частям — MatMul → Softmax ×" + softmax + " → MatMul, слитых узлов нет" : "не найдено");
        // the first attention block: from ~24 nodes before the first Softmax to a few after it
        int first = -1;
        for (int i = 0; i < nodes.size() && first < 0; i++) if ("Softmax".equals(nodes.get(i).opType)) first = i;
        if (first >= 0 && fused == 0) {
            sb.append("\nПервый блок внимания:");
            java.util.Map<String, String> producer = new java.util.HashMap<String, String>();
            for (int i = Math.max(0, first - 24); i <= Math.min(nodes.size() - 1, first + 6); i++) {
                Node n = nodes.get(i);
                StringBuilder in = new StringBuilder();
                for (String x : n.inputs) {
                    if (x.isEmpty()) continue;
                    String p = producer.get(x);
                    in.append(in.length() > 0 ? "," : "").append(p != null ? p : shortName(x));
                }
                String id = "#" + (i - first);
                sb.append("\n ").append(id).append(' ').append(n.opType).append('(').append(in).append(')');
                for (String o : n.outputs) producer.put(o, id);
            }
        }
        return sb.toString();
    }

    /** "/vision_tower/encoder/layers.0/self_attn/q_proj/MatMul_output_0" → "q_proj/MatMul_output_0". */
    static String shortName(String s) {
        String[] parts = s.split("/");
        String t = parts.length >= 2 ? parts[parts.length - 2] + "/" + parts[parts.length - 1] : s;
        return t.length() > 40 ? t.substring(t.length() - 40) : t;
    }

    private static long varint(java.io.DataInputStream in, long[] pos) throws IOException {
        long v = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            int b = in.readUnsignedByte();
            pos[0]++;
            v |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return v;
        }
        throw new IOException("bad varint");
    }

    private static void skipStream(java.io.DataInputStream in, int wire, long[] pos) throws IOException {
        long n;
        switch (wire) {
            case 0: varint(in, pos); return;
            case 1: n = 8; break;
            case 2: n = varint(in, pos); break;
            case 5: n = 4; break;
            default: throw new IOException("unsupported wire type " + wire);
        }
        long left = n;
        while (left > 0) {
            long k = in.skip(left);
            if (k <= 0) {
                in.readByte();
                k = 1;
            }
            left -= k;
        }
        pos[0] += n;
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

        String string() throws IOException {
            int len = (int) varint();
            String v = new String(b, pos, len, UTF8);
            pos += len;
            return v;
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
