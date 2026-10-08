# One encoder block built the way the EmbeddingGemma 2 (Gemma 4 vision) export does it where fp16 breaks:
#  - clipped linears: Clip(x, -inf, +inf) around the projections (identity unless trained bounds);
#  - the padding mask carried inside attention: q gets an extra channel of ones per head, k an extra channel of
#    (1 - valid) * -3.4e38, so q·k already holds the mask (MultiHeadAttention with q/k heads one wider than v);
#  - the pooler's sqrt(hidden) scaling at the end.
# usage: make_masked_keys.py <out.onnx>
import sys
import numpy as np
import onnx
from onnx import helper, TensorProto, numpy_helper

S, H, HD = 24, 4, 8
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
x = n("SimplifiedLayerNormalization", ["x", c("ln_w", (rnd.randn(D) * 0.1 + 1).astype(np.float32))], "ln", axis=-1, epsilon=1e-6)
xc = n("Clip", [x, "neg_inf", "pos_inf"], "clip_in")
q = n("MatMul", [xc, c("wq", (rnd.randn(D, D) * 0.3).astype(np.float32))], "q")
k = n("MatMul", [xc, c("wk", (rnd.randn(D, D) * 0.3).astype(np.float32))], "k")
v = n("MatMul", [xc, c("wv", (rnd.randn(D, D) * 0.3).astype(np.float32))], "v")
heads = c("heads", np.array([0, 0, H, HD], np.int64))
q4 = n("Reshape", [q, heads], "q4")
k4 = n("Reshape", [k, heads], "k4")
qp = n("Pad", [q4, c("qpads", np.array([0, 0, 0, 0, 0, 0, 0, 1], np.int64)), c("one", np.array(1, np.float32))], "qp")
inv = n("Sub", [c("onef", np.array(1, np.float32)), "valid"], "inv")
mcol = n("Mul", [inv, "fmin"], "mcol")                       # 0 × -3.4e38 = 0 in fp32, NaN in fp16
m4 = n("Unsqueeze", [mcol, c("ax23", np.array([2, 3], np.int64))], "m4")
mh = n("Expand", [m4, c("mshape", np.array([1, 1, H, 1], np.int64))], "mh")
kp = n("Concat", [k4, mh], "kp", axis=-1)
flat = c("flat", np.array([0, 0, -1], np.int64))
qf = n("Reshape", [qp, flat], "qf")
kf = n("Reshape", [kp, flat], "kf")
att = n("MultiHeadAttention", [qf, kf, v], "att", domain="com.microsoft", num_heads=H, scale=1.0)
o = n("MatMul", [att, c("wo", (rnd.randn(D, D) * 0.3).astype(np.float32))], "o")
oc = n("Clip", [o, "neg_inf", "pos_inf"], "clip_out")
res = n("Add", ["x", oc], "res")
n("Mul", [res, c("root_hidden", np.array(np.sqrt(768), np.float32))], "y")
g = helper.make_graph(nodes, "masked_keys",
                      [helper.make_tensor_value_info("x", TensorProto.FLOAT, ["batch", "patches", D]),
                       helper.make_tensor_value_info("valid", TensorProto.FLOAT, ["batch", "patches"])],
                      [helper.make_tensor_value_info("y", TensorProto.FLOAT, ["batch", "patches", D])], inits)
m = helper.make_model(g, opset_imports=[helper.make_opsetid("", 20), helper.make_opsetid("com.microsoft", 1)], ir_version=10)
onnx.save(m, sys.argv[1])
