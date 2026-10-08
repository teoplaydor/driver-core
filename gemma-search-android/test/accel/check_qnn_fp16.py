# The graph rewritten for the Snapdragon NPU, computed in fp16 as the NPU does (the model converted to float16,
# run by ONNX's reference evaluator in numpy fp16), against the original graph in fp32 (ONNX Runtime):
#  - padding: zero rows (their RMS norm was 0/0 = NaN once ε underflowed) under a −3.4e38 key mask (−∞ in fp16);
#  - large activations (×3000: their squares overflow fp16 in a plain RMS norm);
# every output must be finite and every row must match. Also: all float constants are 0 or normal fp16 numbers.
# usage: check_qnn_fp16.py <original vit.onnx> <rewritten vit.qnn.onnx>
import sys
import numpy as np
import onnx
import onnxruntime as ort
from onnx import numpy_helper
from onnx.reference import ReferenceEvaluator
from onnxconverter_common import float16

orig, qnn = sys.argv[1], sys.argv[2]
m = onnx.load(qnn)
# the RMS norms' own outputs too (same names in both graphs): the residual stream around them would hide a
# norm that broke
om = onnx.load(orig)
norms = [n.output[0] for n in om.graph.node if n.op_type == "SimplifiedLayerNormalization"]
for g in (om.graph, m.graph):
    for name in norms:
        g.output.append(onnx.helper.make_tensor_value_info(name, onnx.TensorProto.FLOAT, None))
orig = om.SerializeToString()
bad = 0
for n in m.graph.node:
    if n.op_type == "Constant":
        for a in n.attribute:
            vals = [a.f] if a.name == "value_float" else list(numpy_helper.to_array(a.t).ravel()) if a.name == "value" and a.t.data_type == 1 else []
            for v in vals:
                if v != 0 and not (6.1035e-5 <= abs(v) <= 65504):
                    print("FAIL constant outside fp16's normal range:", n.output[0], v)
                    bad += 1
# the converter turns tensor constants to float16 but not value_float ones: make them tensors first
for n in m.graph.node:
    if n.op_type == "Constant" and n.attribute and n.attribute[0].name == "value_float":
        v = n.attribute[0].f
        del n.attribute[:]
        n.attribute.append(onnx.helper.make_attribute("value", numpy_helper.from_array(np.array(v, np.float32))))
# everything in fp16, as on the NPU: no op kept in float32, inputs and outputs fp16, casts to float too
m16 = float16.convert_float_to_float16(m, keep_io_types=False, op_block_list=[], max_finite_val=65504.0)
for n in m16.graph.node:
    if n.op_type == "Cast":
        for a in n.attribute:
            if a.name == "to" and a.i == onnx.TensorProto.FLOAT:
                a.i = onnx.TensorProto.FLOAT16
ev = ReferenceEvaluator(m16)
ref = ort.InferenceSession(orig, providers=["CPUExecutionProvider"])
S, D = 24, 32
rnd = np.random.RandomState(3)
cases = {}
x = rnd.randn(1, S, D).astype(np.float32)
cases["normal"] = (x, np.zeros((1, 1, S, S), np.float32), np.ones(S, bool))
xp = x.copy()
xp[0, 18:] = 0  # padding patches
mask = np.zeros((1, 1, S, S), np.float32)
mask[..., 18:] = np.float32(-3.4e38)
cases["padding"] = (xp, mask, np.ones(S, bool))
cases["large ×3000"] = (x * 3000, np.zeros((1, 1, S, S), np.float32), np.ones(S, bool))
for name, (xi, mi, rows) in cases.items():
    wants = ref.run(None, {"pixel_values": xi, "attention_bias": mi})
    with np.errstate(all="ignore"):
        gots = ev.run(None, {"pixel_values": xi.astype(np.float16), "attention_bias": mi.astype(np.float16)})
    finite, worst = True, 1.0
    for want, got in zip(wants, gots):
        want, got = want[0], got[0].astype(np.float32)
        finite &= bool(np.isfinite(got).all())
        for i in range(S):
            if not rows[i]:
                continue
            nw, ng = np.linalg.norm(want[i]), np.linalg.norm(got[i])
            # a zero row must stay zero; any other must point the same way
            c = 1.0 if nw < 1e-6 and ng < 1e-3 else float(np.dot(got[i], want[i]) / (nw * ng + 1e-30))
            worst = min(worst, c)
    ok = finite and worst > 0.995
    if not ok:
        bad += 1
    print(("ok   " if ok else "FAIL ") + f"fp16 {name}: output and {len(norms)} RMS norms finite {finite}, worst row cos {worst:.5f}")
if bad:
    print(bad, "FAILED")
    sys.exit(1)
print("QNN graph in fp16: all ok")
