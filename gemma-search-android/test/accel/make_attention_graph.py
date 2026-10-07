# Two transformer blocks with attention spelled out the way torch exports it (Reshape/Transpose,
# MatMul(Q, K^T) → Mul(scale) → Add(mask) → Softmax → MatMul(V)), names like the real vision encoder.
# usage: make_attention_graph.py <out.onnx>
import sys
import numpy as np
import onnx
from onnx import helper, TensorProto, numpy_helper

S, D, H = 16, 8, 2
nodes, inits = [], []
def w(name, shape):
    inits.append(numpy_helper.from_array(np.random.RandomState(len(inits)).randn(*shape).astype(np.float32), name))
    return name
inits.append(numpy_helper.from_array(np.array([1, S, H, D // H], np.int64), "heads_shape"))
inits.append(numpy_helper.from_array(np.array([1, S, D], np.int64), "flat_shape"))
inits.append(numpy_helper.from_array(np.array(1 / np.sqrt(D // H), np.float32), "scale"))
x = "pixel_values"
for l in range(2):
    p = f"/vision_tower/encoder/layers.{l}/self_attn"
    for n in ("q", "k", "v"):
        nodes.append(helper.make_node("MatMul", [x, w(f"{p}.{n}_w", (D, D))], [f"{p}/{n}_proj/MatMul_output_0"], name=f"{p}/{n}_proj/MatMul"))
        nodes.append(helper.make_node("Reshape", [f"{p}/{n}_proj/MatMul_output_0", "heads_shape"], [f"{p}/{n}/Reshape_output_0"], name=f"{p}/{n}/Reshape"))
        perm = [0, 2, 3, 1] if n == "k" else [0, 2, 1, 3]
        nodes.append(helper.make_node("Transpose", [f"{p}/{n}/Reshape_output_0"], [f"{p}/{n}/Transpose_output_0"], name=f"{p}/{n}/Transpose", perm=perm))
    nodes.append(helper.make_node("MatMul", [f"{p}/q/Transpose_output_0", f"{p}/k/Transpose_output_0"], [f"{p}/MatMul_output_0"], name=f"{p}/MatMul"))
    nodes.append(helper.make_node("Mul", [f"{p}/MatMul_output_0", "scale"], [f"{p}/Mul_output_0"], name=f"{p}/Mul"))
    nodes.append(helper.make_node("Add", [f"{p}/Mul_output_0", "attention_mask"], [f"{p}/Add_output_0"], name=f"{p}/Add"))
    nodes.append(helper.make_node("Softmax", [f"{p}/Add_output_0"], [f"{p}/Softmax_output_0"], name=f"{p}/Softmax", axis=-1))
    nodes.append(helper.make_node("MatMul", [f"{p}/Softmax_output_0", f"{p}/v/Transpose_output_0"], [f"{p}/MatMul_1_output_0"], name=f"{p}/MatMul_1"))
    nodes.append(helper.make_node("Transpose", [f"{p}/MatMul_1_output_0"], [f"{p}/Transpose_output_0"], name=f"{p}/Transpose", perm=[0, 2, 1, 3]))
    nodes.append(helper.make_node("Reshape", [f"{p}/Transpose_output_0", "flat_shape"], [f"{p}/Reshape_output_0"], name=f"{p}/Reshape"))
    nodes.append(helper.make_node("MatMul", [f"{p}/Reshape_output_0", w(f"{p}.o_w", (D, D))], [f"{p}/o_proj/MatMul_output_0"], name=f"{p}/o_proj/MatMul"))
    x = f"{p}/o_proj/MatMul_output_0"
nodes.append(helper.make_node("Identity", [x], ["image_features"], name="out"))
g = helper.make_graph(nodes, "attn", [helper.make_tensor_value_info("pixel_values", TensorProto.FLOAT, [1, S, D]),
                                       helper.make_tensor_value_info("attention_mask", TensorProto.FLOAT, [1, 1, S, S])],
                      [helper.make_tensor_value_info("image_features", TensorProto.FLOAT, [1, S, D])], inits)
m = helper.make_model(g, opset_imports=[helper.make_opsetid("", 17)])
onnx.checker.check_model(m)
onnx.save(m, sys.argv[1])
