"""What OpenCV's FaceDetectorYN and FaceRecognizerSF (objdetect, 4.x) make of the test images with the stand-in models:
the face rows, the aligned crops (PNG) and the unit-length SFace vectors; also alignments for hand-placed landmarks and
the image fitted into 320×320 by cv2.resize (the app fits it into a graph of one size with its own resize).

usage: reference_faces.py <models dir> <out dir> <image>... > reference.json
"""
import json
import os
import sys

import cv2
import numpy as np

models, out, paths = sys.argv[1], sys.argv[2], sys.argv[3:]
os.makedirs(out, exist_ok=True)
rec = cv2.FaceRecognizerSF.create(os.path.join(models, "sface.onnx"), "")
res = []
for k, p in enumerate(paths):
    img = cv2.imread(p)  # BGR
    h, w = img.shape[:2]
    det = cv2.FaceDetectorYN.create(os.path.join(models, "yunet.onnx"), "", (w, h), 0.9, 0.3, 5000)
    det.setInputSize((w, h))
    _, faces = det.detect(img)
    faces = [] if faces is None else faces
    entry = {"image": p, "faces": [], "aligned": [], "fixed": []}
    for i, f in enumerate(faces):
        crop = rec.alignCrop(img, f)
        feat = rec.feature(crop).flatten()
        feat = feat / np.linalg.norm(feat)
        cp = os.path.join(out, f"crop{k}_{i}.png")
        cv2.imwrite(cp, crop)
        entry["faces"].append({"row": [float(x) for x in f], "crop": cp, "emb": [float(x) for x in feat]})
    # landmarks put by hand: a face upright, tilted, small, mirrored, half outside the image
    rng = np.random.default_rng(100 + k)
    for j in range(6):
        cx, cy = rng.uniform(0.2, 0.8) * w, rng.uniform(0.2, 0.8) * h
        s = rng.uniform(0.2, 1.6) if j != 2 else 0.15
        a = rng.uniform(-0.6, 0.6)
        base = np.array([[-18, -20], [18, -20], [0, 0], [-15, 21], [15, 21]], np.float64)
        if j == 3:
            base[:, 0] *= -1  # mirrored: the right eye on the left
        if j == 4:
            cx, cy = w - 5, h - 5
        R = np.array([[np.cos(a), -np.sin(a)], [np.sin(a), np.cos(a)]])
        lm = (base @ R.T) * s + [cx, cy] + rng.normal(0, 1.0, (5, 2))
        row = np.zeros(15, np.float32)
        row[4:14] = lm.flatten()
        crop = rec.alignCrop(img, row)
        cp = os.path.join(out, f"hand{k}_{j}.png")
        cv2.imwrite(cp, crop)
        feat = rec.feature(crop).flatten()
        entry["aligned"].append({"landmarks": [float(x) for x in row[4:14]], "crop": cp,
                                 "emb": [float(x) for x in feat / np.linalg.norm(feat)]})
    # a graph of one size (320×320): the image fitted into it, as the app does it
    scale = min(320 / w, 320 / h)
    iw, ih = max(1, min(320, round(w * scale))), max(1, min(320, round(h * scale)))
    small = cv2.resize(img, (iw, ih), interpolation=cv2.INTER_LINEAR)
    entry["small"] = os.path.join(out, f"small{k}.png")
    cv2.imwrite(entry["small"], small)
    det2 = cv2.FaceDetectorYN.create(os.path.join(models, "yunet.onnx"), "", (iw, ih), 0.9, 0.3, 5000)
    det2.setInputSize((iw, ih))
    _, f2 = det2.detect(small)
    for f in ([] if f2 is None else f2):
        r = [float(x) for x in f]
        entry["fixed"].append([v / scale for v in r[:14]] + [r[14]])
    res.append(entry)
print(json.dumps(res))
