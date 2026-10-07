"""Makes 4-bit (MatMulNBits, accuracy_level unset) copies of the dummy EmbeddingGemma 2 graphs,
named like the real repo: onnx/model_q4.onnx, onnx/vision_encoder_q4.onnx (+ .onnx_data).
usage: quantize_dummy.py <dummy model dir>"""
import os
import sys

import onnx
from onnxruntime.quantization.matmul_nbits_quantizer import MatMulNBitsQuantizer

d = os.path.join(sys.argv[1], "onnx")
for name in ("model", "vision_encoder"):
    m = onnx.load(os.path.join(d, f"{name}.onnx"))
    q = MatMulNBitsQuantizer(m, block_size=32, is_symmetric=True)
    q.process()
    qm = q.model.model
    n = sum(1 for x in qm.graph.node if x.op_type == "MatMulNBits")
    onnx.save_model(qm, os.path.join(d, f"{name}_q4.onnx"), save_as_external_data=True,
                    all_tensors_to_one_file=True, location=f"{name}_q4.onnx_data", size_threshold=1024)
    print(name, "MatMulNBits:", n)
