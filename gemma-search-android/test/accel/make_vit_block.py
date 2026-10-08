# Two encoder blocks with the fused ops of the EmbeddingGemma 2 vision export that QNN cannot run:
# SimplifiedLayerNormalization (RMS norm) and com.microsoft MultiHeadAttention (layer 0 without a scale
# attribute, layer 1 with one), an additive attention_bias, Gelu MLP, residuals.
# usage: make_vit_block.py <out.onnx>
import sys
import numpy as np
import onnx
from onnx import helper, TensorProto, numpy_helper

S, D, H, F = 24, 32, 4, 64
rnd = np.random.RandomState(11)
nodes, inits = [], []
def w(name, shape, scale=0.3, offset=0.0):
    inits.append(numpy_helper.from_array((rnd.randn(*shape) * scale + offset).astype(np.float32), name))
    return name
x = "pixel_values"
for l in range(2):
    p = f"/encoder/layers.{l}"
    nodes.append(helper.make_node("SimplifiedLayerNormalization", [x, w(f"{p}.ln1", (D,), 0.1, 1.0)], [f"{p}/ln1"],
                                  name=f"{p}/input_layernorm", axis=-1, epsilon=1e-6, stash_type=1))
    for n in ("q", "k", "v"):
        nodes.append(helper.make_node("MatMul", [f"{p}/ln1", w(f"{p}.{n}_w", (D, D))], [f"{p}/{n}"], name=f"{p}/{n}_proj"))
    attrs = {"num_heads": H}
    if l == 1:
        attrs["scale"] = 0.3
    nodes.append(helper.make_node("MultiHeadAttention", [f"{p}/q", f"{p}/k", f"{p}/v", "", "", "attention_bias"],
                                  [f"{p}/attn"], name=f"{p}/MultiHeadAttention", domain="com.microsoft", **attrs))
    nodes.append(helper.make_node("MatMul", [f"{p}/attn", w(f"{p}.o_w", (D, D))], [f"{p}/o"], name=f"{p}/o_proj"))
    nodes.append(helper.make_node("Add", [x, f"{p}/o"], [f"{p}/res1"], name=f"{p}/residual1"))
    nodes.append(helper.make_node("SimplifiedLayerNormalization", [f"{p}/res1", w(f"{p}.ln2", (D,), 0.1, 1.0)], [f"{p}/ln2"],
                                  name=f"{p}/post_layernorm", axis=-1, epsilon=1e-6, stash_type=1))
    nodes.append(helper.make_node("MatMul", [f"{p}/ln2", w(f"{p}.up", (D, F))], [f"{p}/up"], name=f"{p}/up_proj"))
    nodes.append(helper.make_node("Gelu", [f"{p}/up"], [f"{p}/act"], name=f"{p}/act", approximate="tanh"))
    nodes.append(helper.make_node("MatMul", [f"{p}/act", w(f"{p}.down", (F, D))], [f"{p}/down"], name=f"{p}/down_proj"))
    nodes.append(helper.make_node("Add", [f"{p}/res1", f"{p}/down"], [f"{p}/out"], name=f"{p}/residual2"))
    x = f"{p}/out"
nodes.append(helper.make_node("Identity", [x], ["image_features"], name="out"))
g = helper.make_graph(nodes, "vit", [helper.make_tensor_value_info("pixel_values", TensorProto.FLOAT, ["batch", "patches", D]),
                                      helper.make_tensor_value_info("attention_bias", TensorProto.FLOAT, ["batch", 1, "patches", "patches"])],
                      [helper.make_tensor_value_info("image_features", TensorProto.FLOAT, ["batch", "patches", D])], inits)
m = helper.make_model(g, opset_imports=[helper.make_opsetid("", 20), helper.make_opsetid("com.microsoft", 1)], ir_version=10)
onnx.save(m, sys.argv[1])
