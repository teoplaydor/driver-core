# A small vision graph with the encoder's own inputs (pixel_values, pixel_position_ids) for the NPU process round
# trip on Robolectric: an RMS norm, a projection, positions folded in.
# usage: make_npu_graph.py <out.onnx>
import sys
import numpy as np
import onnx
from onnx import helper, TensorProto, numpy_helper

D = 32
rnd = np.random.RandomState(4)
inits = [numpy_helper.from_array((rnd.randn(D) * 0.1 + 1).astype(np.float32), "ln_w"),
         numpy_helper.from_array((rnd.randn(D, D) * 0.3).astype(np.float32), "w"),
         numpy_helper.from_array(np.array([-1], np.int64), "last"),
         numpy_helper.from_array(np.array(0.01, np.float32), "small")]
nodes = [helper.make_node("SimplifiedLayerNormalization", ["pixel_values", "ln_w"], ["ln"], name="/enc/ln", axis=-1, epsilon=1e-6),
         helper.make_node("MatMul", ["ln", "w"], ["proj"], name="/enc/proj"),
         helper.make_node("Cast", ["pixel_position_ids"], ["posf"], name="/enc/posf", to=TensorProto.FLOAT),
         helper.make_node("ReduceSum", ["posf", "last"], ["pos1"], name="/enc/pos1", keepdims=1),
         helper.make_node("Mul", ["pos1", "small"], ["pos2"], name="/enc/pos2"),
         helper.make_node("Add", ["proj", "pos2"], ["image_features"], name="/enc/out")]
g = helper.make_graph(nodes, "npu", [helper.make_tensor_value_info("pixel_values", TensorProto.FLOAT, ["batch", "patches", D]),
                                     helper.make_tensor_value_info("pixel_position_ids", TensorProto.INT64, ["batch", "patches", 2])],
                      [helper.make_tensor_value_info("image_features", TensorProto.FLOAT, ["batch", "patches", D])], inits)
onnx.save(helper.make_model(g, opset_imports=[helper.make_opsetid("", 20)], ir_version=10), sys.argv[1])
