# A chain of the ops met in EmbeddingGemma 2's vision encoder (float and integer paths), to check that every
# one of them still computes the same when kept on the CPU in double precision (OnnxPatcher.keepOnCpu).
# usage: make_op_zoo.py <out.onnx>
import sys
import numpy as np
import onnx
from onnx import helper, TensorProto, numpy_helper

D = 32
rnd = np.random.RandomState(5)
nodes, inits = [], []


def c(name, arr):
    inits.append(numpy_helper.from_array(np.asarray(arr), name))
    return name


def n(op, ins, out, **attrs):
    nodes.append(helper.make_node(op, ins, [out] if isinstance(out, str) else out, name="zoo/" + (out if isinstance(out, str) else out[0]), **attrs))
    return out


table = c("table", (rnd.randn(64, D) * 0.5).astype(np.float32))
g1 = n("Gather", [table, "ids"], "g1", axis=0)
a = n("Add", ["x", g1], "a")
p = n("Pad", [a, c("pads", np.array([0, 0, 0, 0, 0, 2], np.int64)), c("pad_value", np.array(0.25, np.float32))], "p")
sl = n("Slice", [p, c("starts", np.array([0], np.int64)), c("ends", np.array([D], np.int64)), c("axes", np.array([2], np.int64))], "sl")
cc = n("Concat", [sl, "x"], "cc", axis=-1)
n("Split", [cc, c("split", np.array([D, D], np.int64))], ["s1", "s2"], axis=-1)
m = n("Mul", ["s1", "s2"], "m")
cl = n("Clip", [m, c("lo", np.array(-3, np.float32)), c("hi", np.array(3, np.float32))], "cl")
ge = n("Gelu", [cl], "ge", approximate="tanh")
er = n("Erf", [ge], "er")
th = n("Tanh", [er], "th")
co = n("Cos", [th], "co")
si = n("Sin", [co], "si")
ne = n("Neg", [si], "ne")
pw = n("Pow", [ne, c("two", np.array(2, np.float32))], "pw")
sq = n("Sqrt", [n("Add", [pw, c("one", np.array(1, np.float32))], "pw1")], "sq")
dv = n("Div", [sq, c("half", np.array(2, np.float32))], "dv")
un = n("Unsqueeze", [dv, c("ax1", np.array([1], np.int64))], "un")
sz = n("Squeeze", [un, "ax1"], "sz")
last = c("axlast", np.array([-1], np.int64))
rs = n("ReduceSum", [sz, last], "rs", keepdims=1)
rx = n("ReduceMax", [sz, last], "rx", keepdims=1)
rm = n("ReduceMean", [sz, last], "rm", keepdims=1)
ex = n("Expand", [rs, c("shape", np.array([1, 1, D], np.int64))], "ex")
gt = n("Greater", [ex, c("thr", np.array(0.5, np.float32))], "gt")
wh = n("Where", [gt, ex, rx], "wh")
mx = n("Max", [wh, rm], "mx")
mm = n("MatMul", [mx, c("w", (rnd.randn(D, D) * 0.2).astype(np.float32))], "mm")
t1 = n("Transpose", [mm], "t1", perm=[0, 2, 1])
t2 = n("Transpose", [t1], "t2", perm=[0, 2, 1])
rh = n("Reshape", [t2, c("rshape", np.array([0, 0, -1], np.int64))], "rh")
sm = n("Softmax", [rh], "sm", axis=-1)
# integer path: positions → their maximum → scale
imax = n("ReduceMax", ["ids", c("ax1i", np.array([1], np.int64))], "imax", keepdims=1)
iadd = n("Add", [imax, c("ione", np.array(1, np.int64))], "iadd")
ifl = n("Cast", [iadd], "ifl", to=TensorProto.FLOAT)
iun = n("Unsqueeze", [ifl, c("ax2", np.array([2], np.int64))], "iun")
n("Mul", [sm, iun], "y")

g = helper.make_graph(nodes, "zoo",
                      [helper.make_tensor_value_info("x", TensorProto.FLOAT, ["batch", "patches", D]),
                       helper.make_tensor_value_info("ids", TensorProto.INT64, ["batch", "patches"])],
                      [helper.make_tensor_value_info("y", TensorProto.FLOAT, ["batch", "patches", D])], inits)
m = helper.make_model(g, opset_imports=[helper.make_opsetid("", 20)], ir_version=10)
onnx.checker.check_model(m)
onnx.save(m, sys.argv[1])
