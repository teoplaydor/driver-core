#!/usr/bin/env python3
"""What onnx-asr 0.12 makes of the stand-in GigaAM v3 e2e RNN-T (make_gigaam_dummy.py): its features for each sound
(GigaamPreprocessorNumpy and the ONNX preprocessor), the pieces its greedy decoding emits (fp32 and int8 graphs), and
the model's own window and mel filters as onnx-asr keeps them — for GigaAmTest to compare the phone's Java with.

usage: reference_gigaam.py DUMMY_DIR > reference.json   (features and filters as .f32 files next to the sounds)
"""
import json
import os
import sys
from importlib.resources import files

import numpy as np
import onnx_asr
import onnx_asr.preprocessors
from onnx_asr.preprocessors.numpy_preprocessor import GigaamPreprocessorNumpy
from onnx_asr.preprocessors.preprocessor import OnnxPreprocessor


def main():
    root = sys.argv[1]
    snd = os.path.join(root, "sounds")
    with np.load(files(onnx_asr.preprocessors).joinpath("data").joinpath("fbanks.npz")) as d:
        d["gigaam_v3_window"].astype("<f4").tofile(os.path.join(root, "window.f32"))
        d["gigaam_v3"].astype("<f4").tofile(os.path.join(root, "filters.f32"))  # [161][64]
    pre = GigaamPreprocessorNumpy("gigaam_v3")
    pre_onnx = OnnxPreprocessor("gigaam_v3", {})
    models = {q or "fp32": onnx_asr.load_model("gigaam-v3-e2e-rnnt", os.path.join(root, q or "fp32"), quantization=q)
              for q in (None, "int8")}
    vocab = {}
    with open(os.path.join(root, "fp32", "v3_e2e_rnnt_vocab.txt"), encoding="utf-8") as f:
        for line in f:
            tok, i = line.rstrip("\n").split(" ")
            vocab[tok.replace("▁", " ")] = int(i)
    out = {"sounds": {}}
    for name in sorted(os.listdir(snd)):
        if not name.endswith(".f32") or "." in name[:-4]:
            continue
        x = np.fromfile(os.path.join(snd, name), dtype="<f4")
        base = name[:-4]
        if len(x) < 320:  # less than one window: no frames (numpy's preprocessor cannot take it at all)
            out["sounds"][base] = {"samples": int(len(x)), "frames": 0, "fp32": {"ids": [], "text": ""}, "int8": {"ids": [], "text": ""}}
            continue
        feats, lens = pre(x[None, :], np.array([len(x)], np.int64))
        feats_onnx, lens_onnx = pre_onnx(x[None, :], np.array([len(x)], np.int64))
        feats[0].astype("<f4").tofile(os.path.join(snd, base + ".feats.f32"))  # [64][T]
        item = {"samples": int(len(x)), "frames": int(lens[0]), "frames_onnx": int(lens_onnx[0]),
                "onnx_vs_numpy": float(np.abs(feats_onnx - feats).max())}
        for q, m in models.items():
            r = m.with_timestamps().recognize(x)
            item[q] = {"ids": [vocab[t] for t in (r.tokens or [])], "text": r.text}
        out["sounds"][base] = item
    json.dump(out, sys.stdout, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
