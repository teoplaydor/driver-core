# The end of EmbeddingGemma 2's vision graph, where shapes are known only when it runs: the real tokens picked
# from the pooled ones by a mask (NonZero → Gather: hidden_states[pooler_mask]), then the norm before the
# projection and the projection. QNN builds static shapes only. Before them a Reshape to a shape computed from a
# Shape, as the export does everywhere: its output looks unknown to shape inference on the raw graph, but it is not.
# usage: make_dynamic_tail.py <out.onnx>
import sys
import numpy as np
import onnx
from onnx import helper, TensorProto, numpy_helper

P, D = 12, 32
rnd = np.random.RandomState(11)
inits = [numpy_helper.from_array((rnd.randn(P, D) * 0.3).astype(np.float32), "w_in"),
         numpy_helper.from_array((rnd.randn(D, D) * 0.3).astype(np.float32), "w_proj"),
         numpy_helper.from_array(np.ones(D, np.float32), "ones"),
         numpy_helper.from_array(np.array(0, np.int64), "i0"),
         numpy_helper.from_array(np.array(1, np.int64), "i1"),
         numpy_helper.from_array(np.array(-1, np.int64), "m1")]
inits += [numpy_helper.from_array(np.array([0], np.int64), "s0"), numpy_helper.from_array(np.array([2], np.int64), "s2"),
          numpy_helper.from_array(np.array([-1], np.int64), "minus1v")]
nodes = [helper.make_node("MatMul", ["pixel_values", "w_in"], ["x0"], name="/tail/embed"),
         # a shape computed from a shape, as the export does it everywhere: unknown to shape inference on the raw
         # graph, fixed once constants fold (and in QNN's view)
         helper.make_node("Shape", ["x0"], ["xs"], name="/tail/shape"),
         helper.make_node("Slice", ["xs", "s0", "s2"], ["head"], name="/tail/head"),
         helper.make_node("Concat", ["head", "minus1v"], ["newshape"], name="/tail/newshape", axis=0),
         helper.make_node("Reshape", ["x0", "newshape"], ["x"], name="/tail/reshape"),
         helper.make_node("Gather", ["pixel_position_ids", "i0"], ["px"], name="/tail/px", axis=2),
         helper.make_node("Equal", ["px", "m1"], ["pad"], name="/tail/pad"),
         helper.make_node("Not", ["pad"], ["valid"], name="/tail/valid"),
         helper.make_node("NonZero", ["valid"], ["nz"], name="/tail/nz"),
         helper.make_node("Gather", ["nz", "i1"], ["rows"], name="/tail/rows", axis=0),
         helper.make_node("Gather", ["x", "rows"], ["sel"], name="/tail/select", axis=1),
         helper.make_node("SimplifiedLayerNormalization", ["sel", "ones"], ["normed"], name="/tail/_fused_rms_norm", axis=-1,
                          epsilon=1e-6),
         helper.make_node("MatMul", ["normed", "w_proj"], ["image_features"], name="/tail/proj")]
g = helper.make_graph(nodes, "tail",
                      [helper.make_tensor_value_info("pixel_values", TensorProto.FLOAT, ["batch", "patches", P]),
                       helper.make_tensor_value_info("pixel_position_ids", TensorProto.INT64, ["batch", "patches", 2])],
                      [helper.make_tensor_value_info("image_features", TensorProto.FLOAT, ["batch", "tokens", D])], inits)
m = helper.make_model(g, opset_imports=[helper.make_opsetid("", 20), helper.make_opsetid("com.microsoft", 1)], ir_version=10)
onnx.save(m, sys.argv[1])
