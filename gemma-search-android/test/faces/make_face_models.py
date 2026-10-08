"""Stand-ins for YuNet (face_detection_yunet_2023mar) and SFace (face_recognition_sface_2021dec) with their inputs and
outputs — names, layouts, shapes — so that OpenCV's own FaceDetectorYN / FaceRecognizerSF run them as the real ones,
and the app's FaceModel can be compared with OpenCV: preprocessing (BGR for the detector, RGB for SFace), per-stride
decoding, NMS, alignment. The values are made up: blue patches are "faces".

usage: make_face_models.py <out dir> <test images dir>
writes yunet.onnx (any input size), yunet_fixed.onnx (320×320), sface.onnx and a few test images (PNG)
"""
import os
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper


def yunet(path, fixed=None):
    h, w = (fixed, fixed) if fixed else ("h", "w")
    inp = helper.make_tensor_value_info("input", TensorProto.FLOAT, [1, 3, h, w])
    nodes, inits, outs = [], [], {}
    # channels: B, G, R (OpenCV's blob); 16 = cls, obj, bbox(4), kps(10)
    W = np.zeros((16, 3, 1, 1), np.float32)
    B = np.zeros(16, np.float32)
    W[0, :, 0, 0] = [0.012, -0.006, -0.006]; B[0] = 0.5        # cls: blue (not saturated: scores differ)
    W[1, :, 0, 0] = [0.01, 0.0, 0.0]; B[1] = 0.5               # obj
    W[2, :, 0, 0] = [0.0, 0.002, 0.0]; B[2] = -0.25            # dx
    W[3, :, 0, 0] = [0.0, 0.0, 0.002]; B[3] = -0.25            # dy
    W[4, :, 0, 0] = [0.004, 0.0, 0.0]; B[4] = 0.6              # log w
    W[5, :, 0, 0] = [0.0, 0.003, 0.0]; B[5] = 0.8              # log h
    rng = np.random.default_rng(7)
    W[6:, :, 0, 0] = rng.uniform(-0.004, 0.004, (10, 3)).astype(np.float32)
    B[6:] = np.array([-0.4, -0.3, 0.4, -0.3, 0.0, 0.1, -0.3, 0.4, 0.3, 0.4], np.float32)
    inits += [numpy_helper.from_array(W, "W"), numpy_helper.from_array(B, "B"),
              numpy_helper.from_array(np.array([1, -1, 16], np.int64), "shape16")]
    for name, (a, b) in {"cls": (0, 1), "obj": (1, 2), "bbox": (2, 6), "kps": (6, 16)}.items():
        inits += [numpy_helper.from_array(np.array([a], np.int64), f"{name}_a"),
                  numpy_helper.from_array(np.array([b], np.int64), f"{name}_b")]
    inits.append(numpy_helper.from_array(np.array([2], np.int64), "axis2"))
    graph_outs = []
    for s in (8, 16, 32):
        nodes += [
            helper.make_node("AveragePool", ["input"], [f"pool{s}"], kernel_shape=[s, s], strides=[s, s]),
            helper.make_node("Conv", [f"pool{s}", "W", "B"], [f"conv{s}"], kernel_shape=[1, 1]),
            helper.make_node("Transpose", [f"conv{s}"], [f"t{s}"], perm=[0, 2, 3, 1]),
            helper.make_node("Reshape", [f"t{s}", "shape16"], [f"r{s}"]),
        ]
        for name in ("cls", "obj", "bbox", "kps"):
            out = f"{name}_{s}"
            raw = out + "_raw" if name in ("cls", "obj") else out
            nodes.append(helper.make_node("Slice", [f"r{s}", f"{name}_a", f"{name}_b", "axis2"], [raw]))
            if name in ("cls", "obj"):
                nodes.append(helper.make_node("Sigmoid", [raw], [out]))
            outs[out] = {"cls": 1, "obj": 1, "bbox": 4, "kps": 10}[name]
    for kind in ("cls", "obj", "bbox", "kps"):
        for s in (8, 16, 32):
            graph_outs.append(helper.make_tensor_value_info(f"{kind}_{s}", TensorProto.FLOAT, [1, None, outs[f"{kind}_{s}"]]))
    g = helper.make_graph(nodes, "yunet_stand_in", [inp], graph_outs, inits)
    m = helper.make_model(g, opset_imports=[helper.make_opsetid("", 13)])
    m.ir_version = 8
    onnx.checker.check_model(m)
    onnx.save(m, path)


def sface(path):
    inp = helper.make_tensor_value_info("data", TensorProto.FLOAT, [1, 3, 112, 112])
    out = helper.make_tensor_value_info("fc1", TensorProto.FLOAT, [1, 128])
    rng = np.random.default_rng(11)
    W = rng.normal(0, 1, (588, 128)).astype(np.float32) / 255.0
    # channels unalike, so that RGB and BGR give other vectors
    W[:196] *= 3.0
    B = rng.normal(0, 0.1, 128).astype(np.float32)
    nodes = [
        helper.make_node("AveragePool", ["data"], ["p"], kernel_shape=[8, 8], strides=[8, 8]),
        helper.make_node("Flatten", ["p"], ["f"], axis=1),
        helper.make_node("Gemm", ["f", "W", "B"], ["fc1"]),
    ]
    g = helper.make_graph(nodes, "sface_stand_in", [inp], [out],
                          [numpy_helper.from_array(W, "W"), numpy_helper.from_array(B, "B")])
    m = helper.make_model(g, opset_imports=[helper.make_opsetid("", 13)])
    m.ir_version = 8
    onnx.checker.check_model(m)
    onnx.save(m, path)


def images(d):
    """Scenes with blue patches ("faces") of several sizes over a busy background, RGB."""
    from PIL import Image
    rng = np.random.default_rng(3)
    out = []
    for k, (w, h) in enumerate([(640, 480), (500, 375), (333, 517), (800, 600)]):
        yy, xx = np.mgrid[0:h, 0:w]
        img = np.stack([(xx * 255 // w), (yy * 255 // h), ((xx + yy) * 127 // (w + h)) + 60], -1).astype(np.int32)
        img += rng.integers(-20, 20, img.shape)
        for _ in range(3 + k):
            s = int(rng.integers(20, 90))
            x, y = int(rng.integers(0, w - s)), int(rng.integers(0, h - s))
            img[y:y + s, x:x + s] = [int(rng.integers(0, 40)), int(rng.integers(20, 120)), 230 + int(rng.integers(0, 25))]
        img += rng.integers(-6, 6, img.shape)  # no two cells alike: no ties in the scores
        p = os.path.join(d, f"scene{k}.png")
        Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).save(p)
        out.append(p)
    return out


if __name__ == "__main__":
    o, d = sys.argv[1], sys.argv[2]
    os.makedirs(o, exist_ok=True)
    os.makedirs(d, exist_ok=True)
    yunet(os.path.join(o, "yunet.onnx"))
    yunet(os.path.join(o, "yunet_fixed.onnx"), fixed=320)
    sface(os.path.join(o, "sface.onnx"))
    print("\n".join(images(d)))
