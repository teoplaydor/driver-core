#!/usr/bin/env python3
"""A stand-in for GigaAM v3 e2e RNN-T as onnx-asr loads it (istupakov/gigaam-v3-onnx): the same files, inputs, outputs
and shapes — a small encoder (64 log-mel bands in, a strided convolution: 4× fewer frames), the decoder (embedding +
LSTM, state 320), the joint network — random weights, a SentencePiece-like vocabulary with punctuation and «<blk>»
last. Also its int8 version (dynamic quantization, «.int8.onnx» as onnx-asr names them), and sounds to recognize.

usage: make_gigaam_dummy.py OUT_DIR    (OUT_DIR/fp32, OUT_DIR/int8, OUT_DIR/sounds/*.f32)
"""
import json
import os
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

D, H, J = 48, 320, 64
PIECES = ["<unk>", "▁Привет", ",", "▁мир", ".", "▁как", "▁дела", "?", "▁купить", "▁хлеб", "▁и", "▁молоко",
          "▁завтра", "▁в", "▁10", ":", "00", "▁—", "▁позвонить", "▁маме", "!", "▁(", "срочно", ")", "▁«", "дом",
          "»", "▁Напомни", "▁мне", "▁про", "▁встречу", "а", "о", "е", "▁не", "▁забыть", "…", "▁ну", "-", "ка"]
V = len(PIECES) + 1  # + <blk>


def tensor(name, a):
    return numpy_helper.from_array(np.asarray(a), name)


def encoder(rng):
    w = (rng.standard_normal((D, 64, 4)) * 0.08).astype(np.float32)
    b = (rng.standard_normal(D) * 0.1).astype(np.float32)
    nodes = [
        # the features are about −20…+5: centred first, so the stand-in's frames differ from one another
        helper.make_node("Add", ["audio_signal", "shift"], ["centred"]),
        helper.make_node("Conv", ["centred", "w", "b"], ["conv"], strides=[4], kernel_shape=[4]),
        helper.make_node("Tanh", ["conv"], ["encoded"]),
        helper.make_node("Sub", ["length", "four"], ["l1"]),
        helper.make_node("Div", ["l1", "four"], ["l2"]),
        helper.make_node("Add", ["l2", "one"], ["l3"]),
        helper.make_node("Cast", ["l3"], ["encoded_len"], to=TensorProto.INT32),
    ]
    g = helper.make_graph(
        nodes, "encoder",
        [helper.make_tensor_value_info("audio_signal", TensorProto.FLOAT, [None, 64, None]),
         helper.make_tensor_value_info("length", TensorProto.INT64, [None])],
        [helper.make_tensor_value_info("encoded", TensorProto.FLOAT, [None, D, None]),
         helper.make_tensor_value_info("encoded_len", TensorProto.INT32, [None])],
        [tensor("w", w), tensor("b", b), tensor("shift", np.float32(8.0)), tensor("four", np.int64(4)),
         tensor("one", np.int64(1))])
    return helper.make_model(g, opset_imports=[helper.make_opsetid("", 17)], ir_version=9)


def decoder(rng):
    emb = (rng.standard_normal((V, H)) * 0.5).astype(np.float32)
    emb[V - 1] = 0  # blank: GigaAM's padding_idx
    w = (rng.standard_normal((1, 4 * H, H)) * 0.05).astype(np.float32)
    r = (rng.standard_normal((1, 4 * H, H)) * 0.05).astype(np.float32)
    b = (rng.standard_normal((1, 8 * H)) * 0.05).astype(np.float32)
    nodes = [
        helper.make_node("Gather", ["emb", "x"], ["e"]),          # [1, 1, H] (batch, U, H)
        helper.make_node("Transpose", ["e"], ["seq"], perm=[1, 0, 2]),  # [U, batch, H]
        helper.make_node("LSTM", ["seq", "W", "R", "B", "", "h.1", "c.1"], ["y", "h", "c"], hidden_size=H),
        helper.make_node("Squeeze", ["y", "ax1"], ["y1"]),         # [U, batch, H]
        helper.make_node("Transpose", ["y1"], ["dec"], perm=[1, 0, 2]),  # [batch, U, H]
    ]
    g = helper.make_graph(
        nodes, "decoder",
        [helper.make_tensor_value_info("x", TensorProto.INT64, [None, None]),
         helper.make_tensor_value_info("h.1", TensorProto.FLOAT, [1, None, H]),
         helper.make_tensor_value_info("c.1", TensorProto.FLOAT, [1, None, H])],
        [helper.make_tensor_value_info("dec", TensorProto.FLOAT, [None, None, H]),
         helper.make_tensor_value_info("h", TensorProto.FLOAT, [1, None, H]),
         helper.make_tensor_value_info("c", TensorProto.FLOAT, [1, None, H])],
        [tensor("emb", emb), tensor("W", w), tensor("R", r), tensor("B", b), tensor("ax1", np.array([1], np.int64))])
    return helper.make_model(g, opset_imports=[helper.make_opsetid("", 17)], ir_version=9)


def joint(rng):
    we = (rng.standard_normal((D, J)) * 0.5).astype(np.float32)
    wd = (rng.standard_normal((H, J)) * 0.2).astype(np.float32)
    wo = (rng.standard_normal((J, V)) * 0.15).astype(np.float32)
    bo = np.zeros(V, np.float32)
    bo[V - 1] = 8.0  # blank often, not always: a few pieces a second
    nodes = [
        helper.make_node("Transpose", ["enc"], ["et"], perm=[0, 2, 1]),   # [B, T, D]
        helper.make_node("Unsqueeze", ["et", "ax2"], ["eu"]),             # [B, T, 1, D]
        helper.make_node("MatMul", ["eu", "we"], ["ep"]),
        helper.make_node("Transpose", ["dec"], ["dt"], perm=[0, 2, 1]),   # [B, U, H]
        helper.make_node("Unsqueeze", ["dt", "ax1"], ["du"]),             # [B, 1, U, H]
        helper.make_node("MatMul", ["du", "wd"], ["dp"]),
        helper.make_node("Add", ["ep", "dp"], ["s"]),
        helper.make_node("Relu", ["s"], ["a"]),
        helper.make_node("MatMul", ["a", "wo"], ["o"]),
        helper.make_node("Add", ["o", "bo"], ["joint"]),
    ]
    g = helper.make_graph(
        nodes, "joint",
        [helper.make_tensor_value_info("enc", TensorProto.FLOAT, [None, D, None]),
         helper.make_tensor_value_info("dec", TensorProto.FLOAT, [None, H, None])],
        [helper.make_tensor_value_info("joint", TensorProto.FLOAT, [None, None, None, V])],
        [tensor("we", we), tensor("wd", wd), tensor("wo", wo), tensor("bo", bo),
         tensor("ax1", np.array([1], np.int64)), tensor("ax2", np.array([2], np.int64))])
    return helper.make_model(g, opset_imports=[helper.make_opsetid("", 17)], ir_version=9)


def sounds(out):
    """Speech-like sounds: voiced stretches (a pitch gliding, its harmonics shaped by moving formants), pauses, noise."""
    rng = np.random.default_rng(7)
    sr = 16000

    def speech(seconds, words, level):
        n = int(seconds * sr)
        x = rng.standard_normal(n).astype(np.float64) * 0.002
        t0 = int(0.2 * sr)
        for _ in range(words):
            ln = int(rng.uniform(0.25, 0.7) * sr)
            if t0 + ln >= n:
                break
            tt = np.arange(ln) / sr
            f0 = rng.uniform(100, 220) * (1 + 0.15 * np.sin(2 * np.pi * rng.uniform(1, 4) * tt))
            ph = 2 * np.pi * np.cumsum(f0) / sr
            v = np.zeros(ln)
            for k in range(1, 25):
                fk = k * f0
                amp = np.exp(-((fk - rng.uniform(400, 900)) / 300) ** 2) + 0.6 * np.exp(-((fk - rng.uniform(1100, 2400)) / 400) ** 2)
                v += amp * np.sin(k * ph)
            env = np.sin(np.pi * np.arange(ln) / ln) ** 0.5
            x[t0:t0 + ln] += level * v * env / max(1e-9, np.abs(v).max())
            t0 += ln + int(rng.uniform(0.05, 0.6) * sr)
        return x.astype(np.float32)

    os.makedirs(out, exist_ok=True)
    for name, x in [("short", speech(2.5, 5, 0.3)), ("quiet", speech(4.0, 8, 0.02)), ("long", speech(12.0, 22, 0.5)),
                    ("tiny", (rng.standard_normal(300) * 0.01).astype(np.float32))]:
        x.astype("<f4").tofile(os.path.join(out, name + ".f32"))


def main():
    out = sys.argv[1]
    rng = np.random.default_rng(2025)
    fp32 = os.path.join(out, "fp32")
    int8 = os.path.join(out, "int8")
    for d in (fp32, int8):
        os.makedirs(d, exist_ok=True)
    models = {"encoder": encoder(rng), "decoder": decoder(rng), "joint": joint(rng)}
    for k, m in models.items():
        onnx.checker.check_model(m)
        onnx.save(m, os.path.join(fp32, f"v3_e2e_rnnt_{k}.onnx"))
    from onnxruntime.quantization import QuantType, quantize_dynamic
    for k in models:
        quantize_dynamic(os.path.join(fp32, f"v3_e2e_rnnt_{k}.onnx"), os.path.join(int8, f"v3_e2e_rnnt_{k}.int8.onnx"),
                         weight_type=QuantType.QInt8)
    for d in (fp32, int8):
        with open(os.path.join(d, "v3_e2e_rnnt_vocab.txt"), "w", encoding="utf-8") as f:
            for i, p in enumerate(PIECES + ["<blk>"]):
                f.write(f"{p} {i}\n")
        with open(os.path.join(d, "config.json"), "w") as f:
            json.dump({"model_type": "gigaam-v3-e2e-rnnt", "features_size": 64, "subsampling_factor": 4,
                       "max_tokens_per_step": 3, "version": "v3"}, f)
    sounds(os.path.join(out, "sounds"))


if __name__ == "__main__":
    main()
