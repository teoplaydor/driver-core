"""Builds a tiny stand-in for an onnx-community SigLIP 2 export (FixRes, e.g. siglip2-base-patch16-224):
same file layout (onnx/text_model*.onnx, onnx/vision_model*.onnx), the same ONNX input/output names
and configs, so the Java SigLip class can be checked against transformers.js.

usage: python3 make_dummy_siglip.py <gemma tokenizer.json> <out_dir>
"""
import json
import os
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

src_tok, out = sys.argv[1], sys.argv[2]
os.makedirs(os.path.join(out, "onnx"), exist_ok=True)
rng = np.random.default_rng(7)
SIZE, PATCH, H, OUT, L = 224, 16, 32, 768, 64

# --- tokenizer: Gemma vocabulary, SigLIP 2 style special tokens: no <bos>, <eos> appended, padded to 64
tok = json.load(open(src_tok))
tok["post_processor"] = {
    "type": "TemplateProcessing",
    "single": [{"Sequence": {"id": "A", "type_id": 0}}, {"SpecialToken": {"id": "<eos>", "type_id": 0}}],
    "pair": [{"Sequence": {"id": "A", "type_id": 0}}, {"SpecialToken": {"id": "<eos>", "type_id": 0}},
             {"Sequence": {"id": "B", "type_id": 1}}, {"SpecialToken": {"id": "<eos>", "type_id": 1}}],
    "special_tokens": {"<eos>": {"id": "<eos>", "ids": [1], "tokens": ["<eos>"]}},
}
V = max(t["id"] for t in tok["added_tokens"]) + 1
V = max(V, len(tok["model"]["vocab"]))
json.dump(tok, open(os.path.join(out, "tokenizer.json"), "w"), ensure_ascii=False)
json.dump({"tokenizer_class": "GemmaTokenizer", "model_max_length": L, "pad_token": "<pad>", "eos_token": "<eos>",
           "padding_side": "right", "add_bos_token": False, "add_eos_token": True},
          open(os.path.join(out, "tokenizer_config.json"), "w"))
json.dump({"model_type": "siglip", "architectures": ["SiglipModel"],
           "text_config": {"model_type": "siglip_text_model", "hidden_size": H, "vocab_size": V,
                           "max_position_embeddings": L},
           "vision_config": {"model_type": "siglip_vision_model", "hidden_size": H, "image_size": SIZE,
                             "patch_size": PATCH}},
          open(os.path.join(out, "config.json"), "w"))
json.dump({"image_processor_type": "SiglipImageProcessor", "processor_class": "SiglipProcessor",
           "do_resize": True, "size": {"height": SIZE, "width": SIZE}, "resample": 2,
           "do_rescale": True, "rescale_factor": 1 / 255, "do_normalize": True,
           "image_mean": [0.5, 0.5, 0.5], "image_std": [0.5, 0.5, 0.5]},
          open(os.path.join(out, "preprocessor_config.json"), "w"))


def init(name, arr):
    return numpy_helper.from_array(np.asarray(arr), name)


def save(graph, name):
    m = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17)])
    m.ir_version = 8
    onnx.checker.check_model(m)
    onnx.save(m, os.path.join(out, "onnx", name))


# --- vision tower: patch conv → token MLP (tanh) → mean over tokens → head; outputs like SiglipVisionModel
P = (SIZE // PATCH) ** 2
nodes = [
    helper.make_node("Conv", ["pixel_values", "patch_w", "patch_b"], ["patches"], strides=[PATCH, PATCH],
                     kernel_shape=[PATCH, PATCH]),
    helper.make_node("Reshape", ["patches", "flat_shape"], ["flat"]),
    helper.make_node("Transpose", ["flat"], ["tokens"], perm=[0, 2, 1]),
    helper.make_node("MatMul", ["tokens", "w1"], ["h1"]),
    helper.make_node("Tanh", ["h1"], ["a1"]),
    helper.make_node("MatMul", ["a1", "w2"], ["last_hidden_state"]),
    helper.make_node("ReduceMean", ["last_hidden_state"], ["pooled"], axes=[1], keepdims=0),
    helper.make_node("MatMul", ["pooled", "head"], ["pooler_output"]),
]
inits = [init("patch_w", rng.normal(0, 0.05, (H, 3, PATCH, PATCH)).astype(np.float32)),
         init("patch_b", rng.normal(0, 0.05, (H,)).astype(np.float32)),
         init("flat_shape", np.array([0, H, -1], dtype=np.int64)),
         init("w1", rng.normal(0, 0.3, (H, H)).astype(np.float32)),
         init("w2", rng.normal(0, 0.3, (H, H)).astype(np.float32)),
         init("head", rng.normal(0, 0.3, (H, OUT)).astype(np.float32))]
g = helper.make_graph(nodes, "vision", [helper.make_tensor_value_info("pixel_values", TensorProto.FLOAT,
                                                                     ["batch_size", 3, SIZE, SIZE])],
                      [helper.make_tensor_value_info("last_hidden_state", TensorProto.FLOAT, ["batch_size", P, H]),
                       helper.make_tensor_value_info("pooler_output", TensorProto.FLOAT, ["batch_size", OUT])], inits)
save(g, "vision_model.onnx")

# --- text tower: token + position embeddings → tanh → the last position (SigLIP pools the last token) → head
nodes = [
    helper.make_node("Gather", ["tok_emb", "input_ids"], ["te"]),
    helper.make_node("Add", ["te", "pos_emb"], ["x"]),
    helper.make_node("Tanh", ["x"], ["last_hidden_state"]),
    helper.make_node("Gather", ["last_hidden_state", "last"], ["lastTok"], axis=1),
    helper.make_node("MatMul", ["lastTok", "thead"], ["pooler_output"]),
]
inits = [init("tok_emb", rng.normal(0, 0.5, (V, H)).astype(np.float32)),
         init("pos_emb", rng.normal(0, 0.5, (L, H)).astype(np.float32)),
         init("last", np.array(L - 1, dtype=np.int64)),
         init("thead", rng.normal(0, 0.3, (H, OUT)).astype(np.float32))]
# A mixing layer so the last position depends on every token (the real tower's attention does that).
mix = rng.normal(0, 0.2, (L, L)).astype(np.float32) + np.eye(L, dtype=np.float32)
nodes.insert(2, helper.make_node("MatMul", ["mix", "x"], ["xm"]))
nodes[3] = helper.make_node("Tanh", ["xm"], ["last_hidden_state"])
inits.append(init("mix", mix))
g = helper.make_graph(nodes, "text", [helper.make_tensor_value_info("input_ids", TensorProto.INT64,
                                                                   ["batch_size", "sequence_length"])],
                      [helper.make_tensor_value_info("last_hidden_state", TensorProto.FLOAT, ["batch_size", L, H]),
                       helper.make_tensor_value_info("pooler_output", TensorProto.FLOAT, ["batch_size", OUT])], inits)
save(g, "text_model.onnx")
print("ok", out, "vocab", V)
