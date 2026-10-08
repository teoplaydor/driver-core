# One encoder block of EmbeddingGemma 2's vision encoder (Gemma 4 vision) as its export builds it, with the parts
# that broke on the NPU in fp16:
#  - patch positions (int64, -1 for padding) → padding = (pos == -1).all(-1) via Equal/Cast/ReduceMin, Not, And;
#    position embeddings by Gather on the clamped coordinates (the integer Gathers QNN cannot build), zeroed for
#    padding; RoPE angles from the positions;
#  - clipped linears: Clip(x, -inf, +inf) around the projections;
#  - the padding mask carried inside attention: q padded with ones (Pad, value 1), k with a mask channel block
#    (Concat of Where(valid, 0, -3.4e38)), so q·k already holds the mask (q/k heads 4 wider than v);
#  - the pooler's sqrt(hidden) scaling at the end.
# usage: make_gemma4_block.py <out.onnx>
import sys
import numpy as np
import onnx
from onnx import helper, TensorProto, numpy_helper

S, H, HD, P, MAXPOS = 24, 4, 8, 12, 16
D = H * HD
rnd = np.random.RandomState(7)
nodes, inits = [], []


def c(name, arr):
    inits.append(numpy_helper.from_array(np.asarray(arr), name))
    return name


def n(op, ins, out, domain="", **a):
    nodes.append(helper.make_node(op, ins, [out], name="/block/" + out, domain=domain, **a))
    return out


nodes.append(helper.make_node("Constant", [], ["neg_inf"], name="/block/neg_inf", value=numpy_helper.from_array(np.array(-np.inf, np.float32))))
nodes.append(helper.make_node("Constant", [], ["pos_inf"], name="/block/pos_inf", value=numpy_helper.from_array(np.array(np.inf, np.float32))))
nodes.append(helper.make_node("Constant", [], ["fmin"], name="/block/fmin", value_float=float(np.finfo(np.float32).min)))
pos = "pixel_position_ids"
# padding = (pos == -1).all(-1); valid = ~padding
eq = n("Equal", [pos, c("minus1", np.array(-1, np.int64))], "eq")
eqi = n("Cast", [eq], "eqi", to=TensorProto.INT32)
alli = n("ReduceMin", [eqi, c("axlast", np.array([-1], np.int64))], "alli", keepdims=0)
padding = n("Cast", [alli], "padding", to=TensorProto.BOOL)
valid = n("Not", [padding], "valid")
valid2 = n("And", [valid, valid], "valid2")
# position embeddings: Gather by the clamped x and y (the integer Gathers), zeroed for padding
clamped = n("Clip", [pos, c("zero_i", np.array(0, np.int64))], "clamped")
xi = n("Gather", [clamped, c("idx0", np.array(0, np.int64))], "node_select", axis=2)
yi = n("Gather", [clamped, c("idx1", np.array(1, np.int64))], "node_select_1", axis=2)
xe = n("Gather", [c("table_x", (rnd.randn(MAXPOS, D) * 0.2).astype(np.float32)), xi], "x_emb", axis=0)
ye = n("Gather", [c("table_y", (rnd.randn(MAXPOS, D) * 0.2).astype(np.float32)), yi], "y_emb", axis=0)
pe = n("Add", [xe, ye], "pos_emb")
pad3 = n("Unsqueeze", [padding, c("axm1", np.array([-1], np.int64))], "pad3")
pe0 = n("Where", [pad3, c("zf", np.array(0, np.float32)), pe], "pos_emb0")
# patches
px = n("Mul", [n("Sub", ["pixel_values", c("half", np.array(0.5, np.float32))], "px0"), c("two", np.array(2, np.float32))], "px")
h = n("Add", [n("MatMul", [px, c("w_in", (rnd.randn(P, D) * 0.3).astype(np.float32))], "emb"), pe0], "h")
# RoPE angles from positions (one frequency per channel pair, simplified)
posf = n("Cast", [clamped], "posf", to=TensorProto.FLOAT)
ang = n("Mul", [n("ReduceSum", [posf, "axlast"], "possum", keepdims=1), c("inv_freq", (1.0 / (10.0 ** (np.arange(HD) / HD))).astype(np.float32))], "ang")
cos = n("Unsqueeze", [n("Cos", [ang], "cos0"), c("ax2", np.array([2], np.int64))], "cos")
sin = n("Unsqueeze", [n("Sin", [ang], "sin0"), "ax2"], "sin")
# block
x = n("SimplifiedLayerNormalization", [h, c("ln_w", (rnd.randn(D) * 0.1 + 1).astype(np.float32))], "ln", axis=-1, epsilon=1e-6)
xc = n("Clip", [x, "neg_inf", "pos_inf"], "clip_in")
heads = c("heads", np.array([0, 0, H, HD], np.int64))
q4 = n("Reshape", [n("MatMul", [xc, c("wq", (rnd.randn(D, D) * 0.3).astype(np.float32))], "q"), heads], "q4")
k4 = n("Reshape", [n("MatMul", [xc, c("wk", (rnd.randn(D, D) * 0.3).astype(np.float32))], "k"), heads], "k4")
v = n("MatMul", [xc, c("wv", (rnd.randn(D, D) * 0.3).astype(np.float32))], "v")
qr = n("Add", [n("Mul", [q4, cos], "qc"), n("Mul", [q4, sin], "qs")], "qr")
kr = n("Add", [n("Mul", [k4, cos], "kc"), n("Mul", [k4, sin], "ks")], "kr")
qp = n("Pad", [qr, c("qpads", np.array([0, 0, 0, 0, 0, 0, 0, 4], np.int64)), c("one", np.array(1, np.float32))], "qp")
v4 = n("Unsqueeze", [valid2, c("ax23", np.array([2, 3], np.int64))], "valid4")
mcol = n("Where", [v4, "zf", "fmin"], "mcol")
mh = n("Expand", [mcol, c("mshape", np.array([1, 1, H, 4], np.int64))], "mh")
kp = n("Concat", [kr, mh], "kp", axis=-1)
flat = c("flat", np.array([0, 0, -1], np.int64))
att = n("MultiHeadAttention", [n("Reshape", [qp, flat], "qf"), n("Reshape", [kp, flat], "kf"), v], "att", domain="com.microsoft",
        num_heads=H, scale=1.0)
o = n("Clip", [n("MatMul", [att, c("wo", (rnd.randn(D, D) * 0.3).astype(np.float32))], "o"), "neg_inf", "pos_inf"], "clip_out")
res0 = n("Add", [h, o], "res0")
# pooling-like indices: integer division of the clamped positions (truncating), combined, used as a Gather index
kidx = n("Div", [clamped, c("k2", np.array(2, np.int64))], "kidx")
kx = n("Gather", [kidx, "idx0"], "kx", axis=2)
ky = n("Gather", [kidx, "idx1"], "ky", axis=2)
lin = n("Add", [kx, n("Mul", [ky, c("three", np.array(3, np.int64))], "ky3")], "lin")
pool = n("Gather", [c("table_pool", (rnd.randn(MAXPOS, D) * 0.2).astype(np.float32)), lin], "pool_emb", axis=0)
res = n("Add", [res0, pool], "res")
# the count of valid patches through NonZero (no double kernel on the CPU: it must stay as it is), used as n/n = 1
nz = n("NonZero", [valid], "nz")
cnt = n("Cast", [n("Slice", [n("Shape", [nz], "nz_shape"), c("one_i", np.array([1], np.int64)), c("two_i", np.array([2], np.int64))], "nz_n")],
        "nz_f", to=TensorProto.FLOAT)
unit = n("Div", [cnt, cnt], "unit")
n("Mul", [n("Mul", [res, c("root_hidden", np.array(np.sqrt(768), np.float32))], "pooled"), unit], "y")
g = helper.make_graph(nodes, "gemma4_block",
                      [helper.make_tensor_value_info("pixel_values", TensorProto.FLOAT, ["batch", "patches", P]),
                       helper.make_tensor_value_info(pos, TensorProto.INT64, ["batch", "patches", 2])],
                      [helper.make_tensor_value_info("y", TensorProto.FLOAT, ["batch", "patches", D])], inits)
m = helper.make_model(g, opset_imports=[helper.make_opsetid("", 20), helper.make_opsetid("com.microsoft", 1)], ir_version=10)
onnx.save(m, sys.argv[1])
