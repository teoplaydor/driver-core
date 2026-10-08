# The masked-keys block (make_masked_keys.py) after the rewrite for the NPU, computed in fp16 as the NPU does,
# against the original in fp32: the mask carried in the keys (-3.4e38 → a sentinel of -1e4, so 0 × it is 0 and
# q·k cannot overflow) and the clipped linears (±inf bounds → ±65504) must give finite, matching rows.
# usage: check_masked_keys.py <original> <rewritten>
import sys
import numpy as np
import onnx
import onnxruntime as ort
from onnx import numpy_helper
from onnx.reference import ReferenceEvaluator
from onnxconverter_common import float16

orig, qnn = sys.argv[1], sys.argv[2]
m = onnx.load(qnn)
bad = 0
consts = {}
for nd in m.graph.node:
    if nd.op_type == "Constant":
        for a in nd.attribute:
            if a.name == "value_float":
                consts[nd.output[0]] = [a.f]
            elif a.name == "value" and a.t.data_type == 1:
                consts[nd.output[0]] = list(numpy_helper.to_array(a.t).ravel())
want = {"neg_inf": -65504.0, "pos_inf": 65504.0, "fmin": -1e4}
for name, v in want.items():
    ok = name in consts and abs(consts[name][0] - v) < 1e-3 * abs(v)
    bad += not ok
    print(("ok   " if ok else "FAIL ") + f"constant {name}: {consts.get(name)} (want {v})")
for nd in m.graph.node:
    if nd.op_type == "Constant" and nd.attribute and nd.attribute[0].name == "value_float":
        f = nd.attribute[0].f
        del nd.attribute[:]
        nd.attribute.append(onnx.helper.make_attribute("value", numpy_helper.from_array(np.array(f, np.float32))))
m16 = float16.convert_float_to_float16(m, keep_io_types=False, op_block_list=[], max_finite_val=65504.0)
for nd in m16.graph.node:
    if nd.op_type == "Cast":
        for a in nd.attribute:
            if a.name == "to" and a.i == onnx.TensorProto.FLOAT:
                a.i = onnx.TensorProto.FLOAT16
ev = ReferenceEvaluator(m16)
ref = ort.InferenceSession(orig, providers=["CPUExecutionProvider"])
rnd = np.random.RandomState(2)
for name, scale in (("normal", 1.0), ("×100", 100.0)):
    x = (rnd.randn(1, 24, 32) * scale).astype(np.float32)
    x[0, 20:] = 0
    valid = np.ones((1, 24), np.float32)
    valid[0, 20:] = 0
    w = ref.run(None, {"x": x, "valid": valid})[0][0]
    with np.errstate(all="ignore"):
        g = ev.run(None, {"x": x.astype(np.float16), "valid": valid.astype(np.float16)})[0][0].astype(np.float32)
    finite = bool(np.isfinite(g).all())
    worst = min(float(np.dot(g[i], w[i]) / (np.linalg.norm(g[i]) * np.linalg.norm(w[i]) + 1e-30)) for i in range(20))
    ok = finite and worst > 0.995
    bad += not ok
    print(("ok   " if ok else "FAIL ") + f"fp16 masked keys {name}: finite {finite}, worst row cos {worst:.5f}")
if bad:
    print(bad, "FAILED")
    sys.exit(1)
print("masked keys in fp16: all ok")
