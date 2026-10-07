# Two encoder blocks with fused com.microsoft MultiHeadAttention (as in the EmbeddingGemma 2 vision export):
# Q/K/V projections in BSD layout, an additive attention_bias that hides padded patches with -3.4e38.
# usage: make_mha_graph.py <out.onnx>
import sys
import numpy as np
import onnx
from onnx import helper, TensorProto, numpy_helper

S, D, H = 24, 32, 4
rnd = np.random.RandomState(7)
nodes, inits = [], []
def w(name, shape, scale=0.3):
    inits.append(numpy_helper.from_array((rnd.randn(*shape) * scale).astype(np.float32), name))
    return name
x = "pixel_values"
for l in range(2):
    p = f"/encoder/layers.{l}/self_attn"
    for n in ("q", "k", "v"):
        nodes.append(helper.make_node("MatMul", [x, w(f"{p}.{n}_w", (D, D))], [f"{p}/{n}"], name=f"{p}/{n}_proj"))
    nodes.append(helper.make_node("MultiHeadAttention", [f"{p}/q", f"{p}/k", f"{p}/v", "", "", "attention_bias"],
                                  [f"{p}/attn"], name=f"{p}/MultiHeadAttention", domain="com.microsoft", num_heads=H))
    nodes.append(helper.make_node("MatMul", [f"{p}/attn", w(f"{p}.o_w", (D, D))], [f"{p}/o"], name=f"{p}/o_proj"))
    nodes.append(helper.make_node("Add", [x, f"{p}/o"], [f"{p}/res"], name=f"{p}/residual"))
    x = f"{p}/res"
nodes.append(helper.make_node("Identity", [x], ["image_features"], name="out"))
g = helper.make_graph(nodes, "mha", [helper.make_tensor_value_info("pixel_values", TensorProto.FLOAT, [1, S, D]),
                                      helper.make_tensor_value_info("attention_bias", TensorProto.FLOAT, [1, 1, S, S])],
                      [helper.make_tensor_value_info("image_features", TensorProto.FLOAT, [1, S, D])], inits)
m = helper.make_model(g, opset_imports=[helper.make_opsetid("", 20), helper.make_opsetid("com.microsoft", 1)], ir_version=10)
onnx.save(m, sys.argv[1])
