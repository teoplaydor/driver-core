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

    // ---------------------------------------------------------------- fp16 attention

    private static final int ATTR_TYPE_FLOAT = 1;
    private static final int FLOAT = 1, FLOAT16 = 10;
    /** Additive masks use huge negatives (−3.4e38, −inf); fp16 tops out at 65504, so they are floored first. */
    static final float MASK_FLOOR = -60000f;
    static final String FLOOR_NAME = "fp16attn_mask_floor";

    /**
     * Copies a graph so that every {@code com.microsoft:MultiHeadAttention} computes in fp16 while the rest
     * (4-bit MatMulNBits projections, norms) stays as it is: its float inputs — query, key, value, bias,
     * attention_bias, past — go through Cast(fp16) (the additive mask through Max(mask, −60000) first), its
     * outputs through Cast(fp32) back to their original names. Mobile GPUs run fp16 arithmetic at up to twice
     * the fp32 rate, and with many patches attention is most of the work.
     *
     * @return number of attention nodes rewritten (0 = none, nothing written)
     */
    public static int fp16Attention(File in, File out) throws IOException {
        byte[] model = readAll(in);
        int[] count = {0};
        ByteArrayOutputStream res = new ByteArrayOutputStream(model.length + 4096);
        Reader r = new Reader(model, 0, model.length);
        while (r.more()) {
            int start = r.pos;
            long key = r.varint();
            int field = (int) (key >>> 3), wire = (int) (key & 7);
            if (field == 7 && wire == 2) {
                int len = (int) r.varint();
                byte[] g = fp16AttentionGraph(model, r.pos, r.pos + len, count);
                r.pos += len;
                writeLenField(res, 7, g);
            } else {
                r.skip(wire);
                res.write(model, start, r.pos - start);
            }
        }
        if (count[0] == 0) return 0;
        File tmp = new File(out.getPath() + ".tmp");
        OutputStream os = new FileOutputStream(tmp);
        try {
            os.write(res.toByteArray());
        } finally {
            os.close();
        }
        if (out.exists() && !out.delete()) throw new IOException("cannot replace " + out);
        if (!tmp.renameTo(out)) throw new IOException("cannot write " + out);
        return count[0];
    }

    private static byte[] fp16AttentionGraph(byte[] b, int from, int to, int[] count) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(to - from + 4096);
        Reader r = new Reader(b, from, to);
        boolean floorWritten = false;
        while (r.more()) {
            int start = r.pos;
            long key = r.varint();
            int field = (int) (key >>> 3), wire = (int) (key & 7);
            if (field != 1 || wire != 2) {
                r.skip(wire);
                out.write(b, start, r.pos - start);
                continue;
            }
            int len = (int) r.varint();
            int nodeFrom = r.pos, nodeTo = r.pos + len;
            r.pos = nodeTo;
            byte[] raw = new byte[len];
            System.arraycopy(b, nodeFrom, raw, 0, len);
            Node n = parseNode(raw);
            if (!"MultiHeadAttention".equals(n.opType) || !"com.microsoft".equals(n.domain)) {
                writeLenField(out, 1, raw);
                continue;
            }
            int id = count[0]++;
            String tag = "_fp16attn" + id;
            // inputs: 0 query, 1 key, 2 value, 3 bias, 4 key_padding_mask (int), 5 attention_bias, 6/7 past
            java.util.List<String> ins = new java.util.ArrayList<String>(n.inputs);
            for (int i = 0; i < ins.size(); i++) {
                String x = ins.get(i);
                if (x.isEmpty() || i == 4 || i > 7) continue;
                String src = x;
                if (i == 5) {
                    if (!floorWritten) {
                        writeLenField(out, 1, constantFloat(FLOOR_NAME, MASK_FLOOR));
                        floorWritten = true;
                    }
                    src = x + tag + "_floored";
                    writeLenField(out, 1, node("Max", "", new String[]{x, FLOOR_NAME}, new String[]{src}, src, null, 0));
                }
                String half = x + tag + "_in" + i;
                writeLenField(out, 1, node("Cast", "", new String[]{src}, new String[]{half}, half, "to", FLOAT16));
                ins.set(i, half);
            }
            java.util.List<String> outs = new java.util.ArrayList<String>(n.outputs);
            java.util.List<String[]> back = new java.util.ArrayList<String[]>();
            for (int i = 0; i < outs.size(); i++) {
                String y = outs.get(i);
                if (y.isEmpty()) continue;
                String half = y + tag + "_out" + i;
                back.add(new String[]{half, y});
                outs.set(i, half);
            }
            // the attention node itself: new input/output names, every other field (attributes, name) as it was
            ByteArrayOutputStream nb = new ByteArrayOutputStream(len + 256);
            for (String x : ins) writeLenField(nb, 1, x.getBytes(UTF8));
            for (String y : outs) writeLenField(nb, 2, y.getBytes(UTF8));
            Reader nr = new Reader(raw, 0, raw.length);
            while (nr.more()) {
                int s0 = nr.pos;
                long k = nr.varint();
                int f = (int) (k >>> 3), w = (int) (k & 7);
                nr.skip(w);
                if ((f == 1 || f == 2) && w == 2) continue;
                nb.write(raw, s0, nr.pos - s0);
            }
            writeLenField(out, 1, nb.toByteArray());
            for (String[] c : back) {
                writeLenField(out, 1, node("Cast", "", new String[]{c[0]}, new String[]{c[1]}, c[1] + "_fp32", "to", FLOAT));
            }
        }
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- Qualcomm QNN (Snapdragon NPU)

    /**
     * Copies a graph into ops the QNN execution provider can put on the Hexagon NPU. QNN (ONNX Runtime 1.29)
     * has no {@code com.microsoft:MultiHeadAttention} and no {@code SimplifiedLayerNormalization}, so:
     * <ul>
     * <li>attention becomes Reshape/Transpose → MatMul(Q, Kᵀ) → ×scale (+ mask, floored to −60000 for fp16)
     *     → Softmax → MatMul(V) → Transpose/Reshape (scale = 1/√head when the node has none);</li>
     * <li>RMS normalisation becomes X/4 → mean of squares → +ε/16 → √ → divide → ×weight — the same values,
     *     but the squares of X/4 stay inside fp16 (the NPU computes in fp16) for |X| up to ~1000.</li>
     * </ul>
     * Everything else is copied as is. Initializers stay in the external data file next to the original.
     * Every node's name gets a token unique in the graph ({@code _N12N}): ONNX Runtime assigns nodes to an
     * execution provider by substrings of their names (session.name_based_layer_assignment), and a token cannot
     * be part of another one, so a node can be kept off the NPU exactly.
     *
     * @return {attention nodes, normalisation nodes} rewritten
     */
    public static int[] forQnn(File in, File out) throws IOException {
        java.util.Map<String, Long> opsets = new java.util.HashMap<String, Long>();
        nodes(in, opsets);
        Long ai = opsets.get("ai.onnx");
        QnnRewrite q = new QnnRewrite(ai == null ? 17 : ai);
        byte[] model = readAll(in);
        ByteArrayOutputStream res = new ByteArrayOutputStream(model.length + 65536);
        Reader r = new Reader(model, 0, model.length);
        while (r.more()) {
            int start = r.pos;
            long key = r.varint();
            int field = (int) (key >>> 3), wire = (int) (key & 7);
            if (field == 7 && wire == 2) {
                int len = (int) r.varint();
                byte[] g = q.graph(model, r.pos, r.pos + len);
                r.pos += len;
                writeLenField(res, 7, g);
            } else {
                r.skip(wire);
                res.write(model, start, r.pos - start);
            }
        }
        File tmp = new File(out.getPath() + ".tmp");
        OutputStream os = new FileOutputStream(tmp);
        try {
            os.write(res.toByteArray());
        } finally {
            os.close();
        }
        if (out.exists() && !out.delete()) throw new IOException("cannot replace " + out);
        if (!tmp.renameTo(out)) throw new IOException("cannot write " + out);
        return new int[]{q.attention, q.norms};
    }

    private static final class QnnRewrite {
        final long opset;
        int attention, norms;
        final java.util.Set<String> constants = new java.util.HashSet<String>();
        ByteArrayOutputStream out;
        int named;

        String unique(String name) {
            return (name.isEmpty() ? "node" : name) + "_N" + named++ + "N";
        }

        /** The node with its name replaced by a unique one. */
        byte[] renamed(byte[] raw, String name) throws IOException {
            ByteArrayOutputStream nb = new ByteArrayOutputStream(raw.length + 16);
            Reader r = new Reader(raw, 0, raw.length);
            while (r.more()) {
                int s0 = r.pos;
                long k = r.varint();
                int f = (int) (k >>> 3), w = (int) (k & 7);
                r.skip(w);
                if (f == 3 && w == 2) continue;
                nb.write(raw, s0, r.pos - s0);
            }
            writeLenField(nb, 3, unique(name).getBytes(UTF8));
            return nb.toByteArray();
        }

        QnnRewrite(long opset) {
            this.opset = opset;
        }

        byte[] graph(byte[] b, int from, int to) throws IOException {
            out = new ByteArrayOutputStream(to - from + 65536);
            Reader r = new Reader(b, from, to);
            while (r.more()) {
                int start = r.pos;
                long key = r.varint();
                int field = (int) (key >>> 3), wire = (int) (key & 7);
                if (field != 1 || wire != 2) {
                    r.skip(wire);
                    out.write(b, start, r.pos - start);
                    continue;
                }
                int len = (int) r.varint();
                byte[] raw = new byte[len];
                System.arraycopy(b, r.pos, raw, 0, len);
                r.pos += len;
                Node n = parseNode(raw);
                if ("MultiHeadAttention".equals(n.opType) && "com.microsoft".equals(n.domain)) {
                    attention(n, attributes(raw));
                } else if ("SimplifiedLayerNormalization".equals(n.opType)) {
                    rmsNorm(n, attributes(raw));
                } else {
                    writeLenField(out, 1, renamed(raw, n.name));
                }
            }
            return out.toByteArray();
        }

        private void emit(String op, String[] ins, String[] outs, String name, byte[]... attrs) {
            ByteArrayOutputStream nb = new ByteArrayOutputStream();
            for (String x : ins) writeLenField(nb, 1, x.getBytes(UTF8));
            for (String y : outs) writeLenField(nb, 2, y.getBytes(UTF8));
            writeLenField(nb, 3, unique(name).getBytes(UTF8));
            writeLenField(nb, 4, op.getBytes(UTF8));
            for (byte[] a : attrs) writeLenField(nb, 5, a);
            writeLenField(out, 1, nb.toByteArray());
        }

        private String constInts(String name, long... v) {
            if (constants.add(name)) emit("Constant", new String[0], new String[]{name}, name, attrInts("value_ints", v));
            return name;
        }

        private String constInt(String name, long v) {
            if (constants.add(name)) emit("Constant", new String[0], new String[]{name}, name, attrInt("value_int", v));
            return name;
        }

        private String constFloat(String name, float v) {
            if (constants.add(name)) emit("Constant", new String[0], new String[]{name}, name, attrFloat("value_float", v));
            return name;
        }

        private void attention(Node n, java.util.Map<String, Object> attrs) throws IOException {
            java.util.List<String> in = n.inputs;
            for (int i = 3; i < in.size(); i++) {
                if (i != 5 && !in.get(i).isEmpty()) {
                    throw new IOException("MultiHeadAttention с входом " + i + " (bias, key_padding_mask или past) не поддержан");
                }
            }
            if (attrs.containsKey("unidirectional") && ((Long) attrs.get("unidirectional")) != 0) {
                throw new IOException("однонаправленное внимание не поддержано");
            }
            long heads = (Long) attrs.get("num_heads");
            String t = "qnn" + attention++ + "_" + (n.name.isEmpty() ? "mha" : n.name.replaceAll("[^A-Za-z0-9_./]", "_"));
            String split = constInts("qnn_shape_heads_" + heads, 0, 0, heads, -1);
            String q = t + "/q", k = t + "/k", v = t + "/v";
            emit("Reshape", new String[]{in.get(0), split}, new String[]{q + "4"}, q + "4");
            emit("Transpose", new String[]{q + "4"}, new String[]{q}, q, attrInts("perm", 0, 2, 1, 3));
            emit("Reshape", new String[]{in.get(1), split}, new String[]{k + "4"}, k + "4");
            emit("Transpose", new String[]{k + "4"}, new String[]{k}, k, attrInts("perm", 0, 2, 3, 1));
            emit("Reshape", new String[]{in.get(2), split}, new String[]{v + "4"}, v + "4");
            emit("Transpose", new String[]{v + "4"}, new String[]{v}, v, attrInts("perm", 0, 2, 1, 3));
            emit("MatMul", new String[]{q, k}, new String[]{t + "/qk"}, t + "/qk");
            String scale;
            Float given = (Float) attrs.get("scale");
            if (given != null && given != 0f) {
                scale = constFloat(t + "/scale", given);
            } else {
                // 1/sqrt(head size) from the shape; with fixed input shapes ONNX Runtime folds this to a constant
                emit("Shape", new String[]{q}, new String[]{t + "/qshape"}, t + "/qshape");
                emit("Gather", new String[]{t + "/qshape", constInt("qnn_index_3", 3)}, new String[]{t + "/head"}, t + "/head",
                        attrInt("axis", 0));
                emit("Cast", new String[]{t + "/head"}, new String[]{t + "/headf"}, t + "/headf", attrInt("to", FLOAT));
                emit("Sqrt", new String[]{t + "/headf"}, new String[]{t + "/sqrt"}, t + "/sqrt");
                emit("Reciprocal", new String[]{t + "/sqrt"}, new String[]{t + "/scale"}, t + "/scale");
                scale = t + "/scale";
            }
            emit("Mul", new String[]{t + "/qk", scale}, new String[]{t + "/scores"}, t + "/scores");
            String scores = t + "/scores";
            if (in.size() > 5 && !in.get(5).isEmpty()) {
                emit("Max", new String[]{in.get(5), constFloat(FLOOR_NAME, MASK_FLOOR)}, new String[]{t + "/mask"}, t + "/mask");
                emit("Add", new String[]{scores, t + "/mask"}, new String[]{t + "/masked"}, t + "/masked");
                scores = t + "/masked";
            }
            emit("Softmax", new String[]{scores}, new String[]{t + "/probs"}, t + "/probs", attrInt("axis", -1));
            emit("MatMul", new String[]{t + "/probs", v}, new String[]{t + "/ctx"}, t + "/ctx");
            emit("Transpose", new String[]{t + "/ctx"}, new String[]{t + "/ctxt"}, t + "/ctxt", attrInts("perm", 0, 2, 1, 3));
            emit("Reshape", new String[]{t + "/ctxt", constInts("qnn_shape_merge", 0, 0, -1)}, new String[]{n.outputs.get(0)},
                    t + "/out");
        }

        private void rmsNorm(Node n, java.util.Map<String, Object> attrs) throws IOException {
            if (n.outputs.size() > 1) {
                for (int i = 1; i < n.outputs.size(); i++) {
                    if (!n.outputs.get(i).isEmpty()) throw new IOException("SimplifiedLayerNormalization с выходом inv_std_var");
                }
            }
            long axis = attrs.containsKey("axis") ? (Long) attrs.get("axis") : -1;
            float eps = attrs.containsKey("epsilon") ? (Float) attrs.get("epsilon") : 1e-5f;
            String t = "qnn" + norms++ + "_" + (n.name.isEmpty() ? "rms" : n.name.replaceAll("[^A-Za-z0-9_./]", "_"));
            String x = n.inputs.get(0), w = n.inputs.size() > 1 ? n.inputs.get(1) : "";
            emit("Mul", new String[]{x, constFloat("qnn_quarter", 0.25f)}, new String[]{t + "/x4"}, t + "/x4");
            emit("Mul", new String[]{t + "/x4", t + "/x4"}, new String[]{t + "/sq"}, t + "/sq");
            if (opset >= 18) {
                emit("ReduceMean", new String[]{t + "/sq", constInts("qnn_axes_" + (axis < 0 ? "m" + -axis : String.valueOf(axis)), axis)},
                        new String[]{t + "/ms"}, t + "/ms", attrInt("keepdims", 1));
            } else {
                emit("ReduceMean", new String[]{t + "/sq"}, new String[]{t + "/ms"}, t + "/ms", attrInts("axes", axis),
                        attrInt("keepdims", 1));
            }
            emit("Add", new String[]{t + "/ms", constFloat("qnn_eps_" + Float.floatToIntBits(eps), eps / 16f)},
                    new String[]{t + "/mse"}, t + "/mse");
            emit("Sqrt", new String[]{t + "/mse"}, new String[]{t + "/rms"}, t + "/rms");
            String normed = w.isEmpty() ? n.outputs.get(0) : t + "/normed";
            emit("Div", new String[]{t + "/x4", t + "/rms"}, new String[]{normed}, normed);
            if (!w.isEmpty()) emit("Mul", new String[]{normed, w}, new String[]{n.outputs.get(0)}, t + "/out");
        }
    }

    /**
     * A tiny float graph — MatMul → Add → Softmax on [1, 64, 64], the ops of attention — to tell whether the NPU
     * compiles anything at all, when a whole model does not compile.
     */
    public static byte[] canaryModel() {
        int n = 64;
        float[] w = new float[n * n], b = new float[n];
        for (int i = 0; i < w.length; i++) w[i] = (float) Math.sin(i * 0.37) * 0.1f;
        for (int i = 0; i < n; i++) b[i] = i / (float) n;
        ByteArrayOutputStream g = new ByteArrayOutputStream();
        writeLenField(g, 1, node("MatMul", "", new String[]{"x", "w"}, new String[]{"xw"}, "canary_matmul", null, 0));
        writeLenField(g, 1, node("Add", "", new String[]{"xw", "b"}, new String[]{"xb"}, "canary_add", null, 0));
        writeLenField(g, 1, node("Softmax", "", new String[]{"xb"}, new String[]{"y"}, "canary_softmax", "axis", -1));
        writeLenField(g, 2, "canary".getBytes(UTF8));
        writeLenField(g, 5, floatTensor("w", new long[]{n, n}, w));
        writeLenField(g, 5, floatTensor("b", new long[]{n}, b));
        writeLenField(g, 11, floatValueInfo("x", new long[]{1, n, n}));
        writeLenField(g, 12, floatValueInfo("y", new long[]{1, n, n}));
        ByteArrayOutputStream opset = new ByteArrayOutputStream();
        writeLenField(opset, 1, new byte[0]);
        writeVarint(opset, 2L << 3);
        writeVarint(opset, 17);
        ByteArrayOutputStream m = new ByteArrayOutputStream();
        writeVarint(m, 1L << 3);
        writeVarint(m, 8); // IR version
        writeLenField(m, 8, opset.toByteArray());
        writeLenField(m, 7, g.toByteArray());
        return m.toByteArray();
    }

    private static byte[] floatTensor(String name, long[] dims, float[] v) {
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        for (long d : dims) {
            writeVarint(t, 1L << 3);
            writeVarint(t, d);
        }
        writeVarint(t, 2L << 3);
        writeVarint(t, FLOAT);
        writeLenField(t, 8, name.getBytes(UTF8));
        java.nio.ByteBuffer raw = java.nio.ByteBuffer.allocate(4 * v.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (float f : v) raw.putFloat(f);
        writeLenField(t, 9, raw.array());
        return t.toByteArray();
    }

    private static byte[] floatValueInfo(String name, long[] dims) {
        ByteArrayOutputStream shape = new ByteArrayOutputStream();
        for (long d : dims) {
            ByteArrayOutputStream dim = new ByteArrayOutputStream();
            writeVarint(dim, 1L << 3);
            writeVarint(dim, d);
            writeLenField(shape, 1, dim.toByteArray());
        }
        ByteArrayOutputStream tensor = new ByteArrayOutputStream();
        writeVarint(tensor, 1L << 3);
        writeVarint(tensor, FLOAT);
        writeLenField(tensor, 2, shape.toByteArray());
        ByteArrayOutputStream type = new ByteArrayOutputStream();
        writeLenField(type, 1, tensor.toByteArray());
        ByteArrayOutputStream vi = new ByteArrayOutputStream();
        writeLenField(vi, 1, name.getBytes(UTF8));
        writeLenField(vi, 2, type.toByteArray());
        return vi.toByteArray();
    }

    // ---------------------------------------------------------------- graph edits for the NPU search (QnnBuild)

    public static final int TYPE_FLOAT = 1, TYPE_INT32 = 6, TYPE_INT64 = 7, TYPE_BOOL = 9, TYPE_FLOAT16 = 10, TYPE_DOUBLE = 11;

    /** A tensor's element type (TensorProto.DataType) and shape (-1: unknown size). */
    public static final class TensorType {
        public final int elem;
        public final long[] dims;

        public TensorType(int elem, long[] dims) {
            this.elem = elem;
            this.dims = dims;
        }

        @Override
        public String toString() {
            String[] names = {"?", "float", "uint8", "int8", "uint16", "int16", "int32", "int64", "string", "bool", "float16", "double"};
            StringBuilder sb = new StringBuilder(elem >= 0 && elem < names.length ? names[elem] : "type" + elem).append('[');
            for (int i = 0; dims != null && i < dims.length; i++) sb.append(i > 0 ? "," : "").append(dims[i] < 0 ? "?" : String.valueOf(dims[i]));
            return sb.append(']').toString();
        }
    }

    private interface GraphEdit {
        byte[] apply(byte[] model, int from, int to) throws IOException;
    }

    /** The model with its graph edited, written next to the original (its external data stays reachable). */
    private static void editGraph(File in, File out, GraphEdit e) throws IOException {
        byte[] model = readAll(in);
        ByteArrayOutputStream res = new ByteArrayOutputStream(model.length + 65536);
        Reader r = new Reader(model, 0, model.length);
        while (r.more()) {
            int start = r.pos;
            long key = r.varint();
            int field = (int) (key >>> 3), wire = (int) (key & 7);
            if (field == 7 && wire == 2) {
                int len = (int) r.varint();
                writeLenField(res, 7, e.apply(model, r.pos, r.pos + len));
                r.pos += len;
            } else {
                r.skip(wire);
                res.write(model, start, r.pos - start);
            }
        }
        File tmp = new File(out.getPath() + ".tmp");
        OutputStream os = new FileOutputStream(tmp);
        try {
            os.write(res.toByteArray());
        } finally {
            os.close();
        }
        if (out.exists() && !out.delete()) throw new IOException("cannot replace " + out);
        if (!tmp.renameTo(out)) throw new IOException("cannot write " + out);
    }

    /**
     * The graph with only its first {@code keep} nodes (all when negative) and these outputs. An output the
     * graph declared keeps its declaration; the others get none, and ONNX Runtime infers their types and shapes
     * — which is also how all of a graph's tensor types can be learned (QnnBuild.tensorTypes).
     */
    public static void subgraph(File in, File out, final int keep, final java.util.List<String> outputs) throws IOException {
        editGraph(in, out, new GraphEdit() {
            @Override
            public byte[] apply(byte[] b, int from, int to) throws IOException {
                ByteArrayOutputStream g = new ByteArrayOutputStream(to - from + 65536);
                java.util.Map<String, byte[]> declared = new java.util.HashMap<String, byte[]>();
                Reader r = new Reader(b, from, to);
                int node = 0;
                while (r.more()) {
                    int start = r.pos;
                    long key = r.varint();
                    int field = (int) (key >>> 3), wire = (int) (key & 7);
                    if (field == 12 && wire == 2) {
                        int len = (int) r.varint();
                        byte[] vi = new byte[len];
                        System.arraycopy(b, r.pos, vi, 0, len);
                        r.pos += len;
                        Reader vr = new Reader(vi, 0, vi.length);
                        while (vr.more()) {
                            long k = vr.varint();
                            if ((k >>> 3) == 1 && (k & 7) == 2) {
                                declared.put(vr.string(), vi);
                                break;
                            }
                            vr.skip((int) (k & 7));
                        }
                        continue;
                    }
                    r.skip(wire);
                    if (field == 1 && wire == 2 && keep >= 0 && node++ >= keep) continue;
                    g.write(b, start, r.pos - start);
                }
                for (String o : outputs) {
                    byte[] vi = declared.get(o);
                    if (vi == null) {
                        ByteArrayOutputStream v = new ByteArrayOutputStream();
                        writeLenField(v, 1, o.getBytes(UTF8));
                        vi = v.toByteArray();
                    }
                    writeLenField(g, 12, vi);
                }
                return g.toByteArray();
            }
        });
    }

    /** Element types and shapes of the graph's initializers, by name. */
    public static java.util.Map<String, TensorType> initializerTypes(File f) throws IOException {
        java.util.Map<String, TensorType> out = new java.util.HashMap<String, TensorType>();
        byte[] model = readAll(f);
        Reader r = new Reader(model, 0, model.length);
        while (r.more()) {
            long key = r.varint();
            int field = (int) (key >>> 3), wire = (int) (key & 7);
            if (field != 7 || wire != 2) {
                r.skip(wire);
                continue;
            }
            int len = (int) r.varint();
            Reader g = new Reader(model, r.pos, r.pos + len);
            r.pos += len;
            while (g.more()) {
                long k = g.varint();
                int gf = (int) (k >>> 3), gw = (int) (k & 7);
                if (gf != 5 || gw != 2) {
                    g.skip(gw);
                    continue;
                }
                int tl = (int) g.varint();
                Reader t = new Reader(model, g.pos, g.pos + tl);
                g.pos += tl;
                int elem = 0;
                String name = null;
                java.util.List<Long> dims = new java.util.ArrayList<Long>();
                while (t.more()) {
                    long tk = t.varint();
                    int tf = (int) (tk >>> 3), tw = (int) (tk & 7);
                    if (tf == 1 && tw == 0) {
                        dims.add(t.varint());
                    } else if (tf == 1 && tw == 2) {
                        int pl = (int) t.varint(), end = t.pos + pl;
                        while (t.pos < end) dims.add(t.varint());
                    } else if (tf == 2 && tw == 0) {
                        elem = (int) t.varint();
                    } else if (tf == 8 && tw == 2) {
                        name = t.string();
                    } else {
                        t.skip(tw);
                    }
                }
                long[] d = new long[dims.size()];
                for (int i = 0; i < d.length; i++) d[i] = dims.get(i);
                if (name != null) out.put(name, new TensorType(elem, d));
            }
        }
        return out;
    }

    /** Inputs that carry the data (others are indices, shapes, axes, pads: they keep their types). */
    private static int[] dataInputs(String op, int n) {
        if (op.startsWith("Reduce") || op.equals("Gather") || op.equals("GatherElements") || op.equals("GatherND")
                || op.equals("Reshape") || op.equals("Slice") || op.equals("Expand") || op.equals("Tile")
                || op.equals("Squeeze") || op.equals("Unsqueeze") || op.equals("Transpose") || op.equals("Split")
                || op.equals("Softmax") || op.equals("LogSoftmax") || op.equals("TopK") || op.equals("CumSum")
                || op.equals("Resize") || op.equals("Cast") || op.equals("Shape") || op.equals("Size")) {
            return new int[]{0};
        }
        if (op.equals("Pad")) return new int[]{0, 2};
        if (op.equals("Where")) return new int[]{1, 2};
        if (op.equals("ScatterElements") || op.equals("ScatterND")) return new int[]{0, 2};
        int[] all = new int[n];
        for (int i = 0; i < n; i++) all[i] = i;
        return all;
    }

    /** Ops ONNX Runtime's CPU has no double kernel for (checked against the op zoo in tests): they cannot move. */
    private static final java.util.Set<String> NO_DOUBLE = new java.util.HashSet<String>(java.util.Arrays.asList("Erf"));

    /** Ops whose output type does not follow their inputs'. */
    private static final java.util.Set<String> OWN_OUTPUT_TYPE = new java.util.HashSet<String>(java.util.Arrays.asList(
            "Cast", "Shape", "Size", "Equal", "Less", "LessOrEqual", "Greater", "GreaterOrEqual", "Not", "And", "Or",
            "Xor", "IsNaN", "IsInf", "ArgMax", "ArgMin", "NonZero", "ConstantOfShape"));

    /**
     * The graph with these nodes computed in double precision: QNN has no double, so they stay on the CPU, whose
     * kernels take it. Their data inputs (float or integer) are cast to double, their outputs back to their own
     * types. Unlike assigning nodes by name, ONNX Runtime partitions this graph as usual (its name-based
     * assignment hides the initializers from QNN, which then fails on Slice). Returns the nodes it could not
     * move (no input of a type to cast, or no double kernel on the CPU).
     */
    public static java.util.List<String> keepOnCpu(File in, File out, final java.util.Set<String> names,
                                                   final java.util.Map<String, TensorType> types) throws IOException {
        final java.util.List<String> unmoved = new java.util.ArrayList<String>();
        editGraph(in, out, new GraphEdit() {
            @Override
            public byte[] apply(byte[] b, int from, int to) throws IOException {
                ByteArrayOutputStream g = new ByteArrayOutputStream(to - from + 65536);
                java.util.Map<String, String> asDouble = new java.util.HashMap<String, String>();
                Reader r = new Reader(b, from, to);
                while (r.more()) {
                    int start = r.pos;
                    long key = r.varint();
                    int field = (int) (key >>> 3), wire = (int) (key & 7);
                    if (field != 1 || wire != 2) {
                        r.skip(wire);
                        g.write(b, start, r.pos - start);
                        continue;
                    }
                    int len = (int) r.varint();
                    byte[] raw = new byte[len];
                    System.arraycopy(b, r.pos, raw, 0, len);
                    r.pos += len;
                    Node n = parseNode(raw);
                    if (!names.contains(n.name)) {
                        writeLenField(g, 1, raw);
                        continue;
                    }
                    if (NO_DOUBLE.contains(n.opType)) {
                        unmoved.add(n.name);
                        writeLenField(g, 1, raw);
                        continue;
                    }
                    java.util.List<String> ins = new java.util.ArrayList<String>(n.inputs);
                    int cast = 0, firstType = 0;
                    for (int i : dataInputs(n.opType, ins.size())) {
                        if (i >= ins.size() || ins.get(i).isEmpty()) continue;
                        TensorType t = types.get(ins.get(i));
                        if (t == null || !(t.elem == TYPE_FLOAT || t.elem == TYPE_FLOAT16 || t.elem == TYPE_INT64 || t.elem == TYPE_INT32)) continue;
                        if (firstType == 0) firstType = t.elem;
                        // a node moved before may already give it in double
                        String d = asDouble.get(ins.get(i));
                        if (d == null) {
                            d = ins.get(i) + "_to_double";
                            asDouble.put(ins.get(i), d);
                            writeLenField(g, 1, node("Cast", "", new String[]{ins.get(i)}, new String[]{d}, d, "to", TYPE_DOUBLE));
                        }
                        ins.set(i, d);
                        cast++;
                    }
                    if (cast == 0) {
                        unmoved.add(n.name);
                        writeLenField(g, 1, raw);
                        continue;
                    }
                    java.util.List<String> outs = new java.util.ArrayList<String>(n.outputs);
                    java.util.List<String[]> back = new java.util.ArrayList<String[]>();
                    for (int j = 0; j < outs.size(); j++) {
                        String o = outs.get(j);
                        if (o.isEmpty() || OWN_OUTPUT_TYPE.contains(n.opType) || (n.opType.equals("TopK") && j == 1)) continue;
                        TensorType t = types.get(o);
                        int elem = t != null ? t.elem : firstType;
                        outs.set(j, o + "_as_double");
                        asDouble.put(o, o + "_as_double");
                        back.add(new String[]{o + "_as_double", o, String.valueOf(elem)});
                    }
                    ByteArrayOutputStream nb = new ByteArrayOutputStream(raw.length + 64);
                    for (String x : ins) writeLenField(nb, 1, x.getBytes(UTF8));
                    for (String y : outs) writeLenField(nb, 2, y.getBytes(UTF8));
                    Reader nr = new Reader(raw, 0, raw.length);
                    while (nr.more()) {
                        int s0 = nr.pos;
                        long k = nr.varint();
                        int f = (int) (k >>> 3), w = (int) (k & 7);
                        nr.skip(w);
                        if ((f == 1 || f == 2) && w == 2) continue;
                        nb.write(raw, s0, nr.pos - s0);
                    }
                    writeLenField(g, 1, nb.toByteArray());
                    for (String[] c : back) {
                        writeLenField(g, 1, node("Cast", "", new String[]{c[0]}, new String[]{c[1]}, c[1] + "_from_double", "to",
                                Integer.parseInt(c[2])));
                    }
                }
                return g.toByteArray();
            }
        });
        return unmoved;
    }

    /** name → Long (INT), Float (FLOAT), long[] (INTS) for the attributes a rewrite needs. */
    private static java.util.Map<String, Object> attributes(byte[] node) throws IOException {
        java.util.Map<String, Object> m = new java.util.HashMap<String, Object>();
        Reader r = new Reader(node, 0, node.length);
        while (r.more()) {
            long k = r.varint();
            int f = (int) (k >>> 3), w = (int) (k & 7);
            if (f != 5 || w != 2) {
                r.skip(w);
                continue;
            }
            int len = (int) r.varint();
            Reader a = new Reader(node, r.pos, r.pos + len);
            r.pos += len;
            String name = null;
            Object value = null;
            java.util.List<Long> ints = new java.util.ArrayList<Long>();
            while (a.more()) {
                long ak = a.varint();
                int af = (int) (ak >>> 3), aw = (int) (ak & 7);
                if (af == 1 && aw == 2) {
                    name = a.string();
                } else if (af == 2 && aw == 5) {
                    int bits = (node[a.pos] & 0xff) | (node[a.pos + 1] & 0xff) << 8 | (node[a.pos + 2] & 0xff) << 16
                            | (node[a.pos + 3] & 0xff) << 24;
                    a.pos += 4;
                    value = Float.intBitsToFloat(bits);
                } else if (af == 3 && aw == 0) {
                    value = a.varint();
                } else if (af == 8 && aw == 0) {
                    ints.add(a.varint());
                } else if (af == 8 && aw == 2) {
                    int pl = (int) a.varint(), end = a.pos + pl;
                    while (a.pos < end) ints.add(a.varint());
                } else {
                    a.skip(aw);
                }
            }
            if (value == null && !ints.isEmpty()) {
                long[] v = new long[ints.size()];
                for (int i = 0; i < v.length; i++) v[i] = ints.get(i);
                value = v;
            }
            if (name != null && value != null) m.put(name, value);
        }
        return m;
    }

    private static byte[] attrInt(String name, long v) {
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        writeLenField(a, 1, name.getBytes(UTF8));
        writeVarint(a, 3L << 3);
        writeVarint(a, v);
        writeVarint(a, 20L << 3);
        writeVarint(a, ATTR_TYPE_INT);
        return a.toByteArray();
    }

    private static final int ATTR_TYPE_INTS = 7;

    private static byte[] attrInts(String name, long... v) {
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        writeLenField(a, 1, name.getBytes(UTF8));
        for (long x : v) {
            writeVarint(a, 8L << 3);
            writeVarint(a, x);
        }
        writeVarint(a, 20L << 3);
        writeVarint(a, ATTR_TYPE_INTS);
        return a.toByteArray();
    }

    private static byte[] attrFloat(String name, float v) {
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        writeLenField(a, 1, name.getBytes(UTF8));
        writeVarint(a, (2L << 3) | 5);
        int bits = Float.floatToIntBits(v);
        for (int i = 0; i < 4; i++) a.write((bits >>> (8 * i)) & 0xff);
        writeVarint(a, 20L << 3);
        writeVarint(a, ATTR_TYPE_FLOAT);
        return a.toByteArray();
    }

    /** NodeProto with an optional INT attribute. */
    private static byte[] node(String op, String domain, String[] ins, String[] outs, String name, String attr, long value) {
        ByteArrayOutputStream nb = new ByteArrayOutputStream();
        for (String x : ins) writeLenField(nb, 1, x.getBytes(UTF8));
        for (String y : outs) writeLenField(nb, 2, y.getBytes(UTF8));
        writeLenField(nb, 3, name.getBytes(UTF8));
        writeLenField(nb, 4, op.getBytes(UTF8));
        if (!domain.isEmpty()) writeLenField(nb, 7, domain.getBytes(UTF8));
        if (attr != null) {
            ByteArrayOutputStream a = new ByteArrayOutputStream();
            writeLenField(a, 1, attr.getBytes(UTF8));
            writeVarint(a, 3L << 3); // i
            writeVarint(a, value);
            writeVarint(a, 20L << 3); // type
            writeVarint(a, ATTR_TYPE_INT);
            writeLenField(nb, 5, a.toByteArray());
        }
        return nb.toByteArray();
    }

    /** Constant node with a scalar float ({@code value_float}, opset 12+). */
    private static byte[] constantFloat(String output, float v) {
        ByteArrayOutputStream nb = new ByteArrayOutputStream();
        writeLenField(nb, 2, output.getBytes(UTF8));
        writeLenField(nb, 3, output.getBytes(UTF8));
        writeLenField(nb, 4, "Constant".getBytes(UTF8));
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        writeLenField(a, 1, "value_float".getBytes(UTF8));
        writeVarint(a, (2L << 3) | 5); // f (fixed32)
        int bits = Float.floatToIntBits(v);
        for (int i = 0; i < 4; i++) a.write((bits >>> (8 * i)) & 0xff);
        writeVarint(a, 20L << 3);
        writeVarint(a, ATTR_TYPE_FLOAT);
        writeLenField(nb, 5, a.toByteArray());
        return nb.toByteArray();
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
        /** INT, FLOAT and INTS attributes (see attributes()). */
        public java.util.Map<String, Object> attrs = new java.util.HashMap<String, Object>();
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
        n.attrs = attributes(b);
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
