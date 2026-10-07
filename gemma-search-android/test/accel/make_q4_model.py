"""Builds a small transformer-like MLP stack, quantizes its MatMuls to 4-bit MatMulNBits (block 32,
accuracy_level unset — like the onnx-community q4 exports) and saves it with external data.
usage: make_q4_model.py <out_dir>"""
import os
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper
from onnxruntime.quantization.matmul_nbits_quantizer import MatMulNBitsQuantizer

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
rng = np.random.default_rng(0)
D, F, L = 768, 3072, 4
nodes, inits = [], []
x = "x"
for i in range(L):
    inits += [numpy_helper.from_array((rng.standard_normal((D, F)) * 0.03).astype(np.float32), f"w1_{i}"),
              numpy_helper.from_array((rng.standard_normal((F, D)) * 0.03).astype(np.float32), f"w2_{i}")]
    nodes += [helper.make_node("MatMul", [x, f"w1_{i}"], [f"h{i}"]),
              helper.make_node("Relu", [f"h{i}"], [f"r{i}"]),
              helper.make_node("MatMul", [f"r{i}", f"w2_{i}"], [f"o{i}"]),
              helper.make_node("Add", [x, f"o{i}"], [f"x{i + 1}"])]
    x = f"x{i + 1}"
nodes.append(helper.make_node("Identity", [x], ["y"]))
g = helper.make_graph(nodes, "mlp", [helper.make_tensor_value_info("x", TensorProto.FLOAT, [1, "m", D])],
                      [helper.make_tensor_value_info("y", TensorProto.FLOAT, [1, "m", D])], inits)
m = helper.make_model(g, opset_imports=[helper.make_opsetid("", 17)])
m.ir_version = 9
q = MatMulNBitsQuantizer(m, block_size=32, is_symmetric=True)
q.process()
qm = q.model.model
ops = [n.op_type for n in qm.graph.node]
print("ops:", sorted(set(ops)), "MatMulNBits:", ops.count("MatMulNBits"))
acc = [a for n in qm.graph.node if n.op_type == "MatMulNBits" for a in n.attribute if a.name == "accuracy_level"]
print("accuracy_level attrs:", [a.i for a in acc])
onnx.save_model(qm, os.path.join(out, "model_q4.onnx"), save_as_external_data=True, all_tensors_to_one_file=True,
                location="model_q4.onnx_data", size_threshold=1024)
print("saved", os.path.getsize(os.path.join(out, "model_q4.onnx")), os.path.getsize(os.path.join(out, "model_q4.onnx_data")))
