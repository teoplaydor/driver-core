# Half-precision copy of the dummy vision encoder (inputs and outputs fp16 too, as in fp16 exports).
# usage: make_fp16_vision.py <vision_encoder.onnx> <vision_encoder_fp16.onnx>
import sys, onnx
from onnxconverter_common import float16
src, dst = sys.argv[1], sys.argv[2]
m = onnx.load(src)
# Casts (int64 positions -> float) stay fp32 with an fp16 cast after them, as in real fp16 exports
m16 = float16.convert_float_to_float16(m, keep_io_types=False,
                                       op_block_list=float16.DEFAULT_OP_BLOCK_LIST + ['Cast'])
onnx.save(m16, dst)
for i in m16.graph.input: print("in ", i.name, i.type.tensor_type.elem_type)
for o in m16.graph.output: print("out", o.name, o.type.tensor_type.elem_type)
