"""Builds a tiny stand-in for onnx-community/embeddinggemma-2-ONNX with the same file layout
and ONNX input/output names, so the Java pipeline can be checked against transformers.js.

usage: python3 make_dummy_model.py <gemma tokenizer.json> <out_dir>
"""
import json
import os
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

src_tok, out = sys.argv[1], sys.argv[2]
os.makedirs(os.path.join(out, "onnx"), exist_ok=True)
rng = np.random.default_rng(0)
H, E, PATCH = 16, 8, 16
D = PATCH * PATCH * 3

# --- tokenizer: Gemma tokenizer + Gemma-4-style multimodal tokens, <bos> ... <eos> template
tok = json.load(open(src_tok))
tok["post_processor"] = {
    "type": "TemplateProcessing",
    "single": [{"SpecialToken": {"id": "<bos>", "type_id": 0}}, {"Sequence": {"id": "A", "type_id": 0}},
               {"SpecialToken": {"id": "<eos>", "type_id": 0}}],
    "pair": [{"SpecialToken": {"id": "<bos>", "type_id": 0}}, {"Sequence": {"id": "A", "type_id": 0}},
             {"SpecialToken": {"id": "<eos>", "type_id": 0}}, {"Sequence": {"id": "B", "type_id": 1}},
             {"SpecialToken": {"id": "<eos>", "type_id": 1}}],
    "special_tokens": {"<bos>": {"id": "<bos>", "ids": [2], "tokens": ["<bos>"]},
                       "<eos>": {"id": "<eos>", "ids": [1], "tokens": ["<eos>"]}},
}
names = ["<|image|>", "<|image>", "<image|>", "<|audio|>", "<|audio>", "<audio|>", "<|video|>"]
ids = {}
nid = max(t["id"] for t in tok["added_tokens"]) + 1
for n in names:
    tok["added_tokens"].append({"id": nid, "content": n, "single_word": False, "lstrip": False,
                                "rstrip": False, "normalized": False, "special": True})
    ids[n] = nid
    nid += 1
V = nid
json.dump(tok, open(os.path.join(out, "tokenizer.json"), "w"), ensure_ascii=False)
json.dump({"tokenizer_class": "GemmaTokenizer", "image_token": "<|image|>", "boi_token": "<|image>",
           "eoi_token": "<image|>", "audio_token": "<|audio|>", "boa_token": "<|audio>", "eoa_token": "<audio|>",
           "video_token": "<|video|>", "padding_side": "right", "pad_token": "<pad>",
           # Real HF configs carry this out-of-long-range integer (regression: NumberFormatException).
           "model_max_length": 1000000000000000019884624838656},
          open(os.path.join(out, "tokenizer_config.json"), "w"))
json.dump({"model_type": "embedding_gemma2", "architectures": ["EmbeddingGemma2Model"],
           "image_token_id": ids["<|image|>"], "video_token_id": ids["<|video|>"], "audio_token_id": ids["<|audio|>"],
           "text_config": {"model_type": "gemma4_text", "hidden_size": H, "vocab_size": V, "rms_norm_eps": 1e-06,
                           "rope_theta": 1000000.0, "final_logit_softcapping": None},
           "vision_config": {"model_type": "gemma4_vision", "hidden_size": H}, "audio_config": None},
          open(os.path.join(out, "config.json"), "w"))
json.dump({"processor_class": "EmbeddingGemma2Processor",
           "image_processor": {"patch_size": PATCH, "max_soft_tokens": 280, "pooling_kernel_size": 3},
           "video_processor": {"patch_size": PATCH, "max_soft_tokens": 70, "pooling_kernel_size": 3, "max_frames": 32}},
          open(os.path.join(out, "processor_config.json"), "w"))
json.dump({"processor_class": "EmbeddingGemma2Processor"}, open(os.path.join(out, "preprocessor_config.json"), "w"))


def init(name, arr):
    return numpy_helper.from_array(np.asarray(arr), name)


# --- text model: order-sensitive pooling of token embeddings + per-modality features
nodes, inits = [], []
inits += [init("emb_table", rng.normal(0, 1, (V, E)).astype(np.float32)),
          init("w_img", rng.normal(0, 1, (H, E)).astype(np.float32)),
          init("w_vid", rng.normal(0, 1, (H, E)).astype(np.float32)),
          init("w_aud", rng.normal(0, 1, (H, E)).astype(np.float32)),
          init("zero", np.array(0, np.int64)), init("one", np.array(1, np.int64)),
          init("onef", np.array(1.0, np.float32)), init("ax1", np.array([1], np.int64)),
          init("ax0", np.array([0], np.int64)), init("shp_s1", np.array([1, -1, 1], np.int64)),
          init("shp_n1", np.array([-1, 1], np.int64)), init("idx1", np.array(1, np.int64))]
nodes += [helper.make_node("Gather", ["emb_table", "input_ids"], ["tok_emb"]),
          helper.make_node("Shape", ["input_ids"], ["ids_shape"]),
          helper.make_node("Gather", ["ids_shape", "idx1"], ["seq_len"]),
          helper.make_node("Range", ["zero", "seq_len", "one"], ["pos"]),
          helper.make_node("Cast", ["pos"], ["posf"], to=TensorProto.FLOAT),
          helper.make_node("Add", ["posf", "onef"], ["posf1"]),
          helper.make_node("Reshape", ["posf1", "shp_s1"], ["pos3"]),
          helper.make_node("Cast", ["attention_mask"], ["maskf"], to=TensorProto.FLOAT),
          helper.make_node("Unsqueeze", ["maskf", "ax1"], ["mask3"]),
          helper.make_node("Unsqueeze", ["mask3", "ax1"], ["mask3b"]),
          helper.make_node("Reshape", ["maskf", "shp_s1"], ["mask3c"]),
          helper.make_node("Mul", ["tok_emb", "pos3"], ["weighted"]),
          helper.make_node("Mul", ["weighted", "mask3c"], ["last_hidden_state"]),
          helper.make_node("ReduceSum", ["last_hidden_state", "ax1"], ["text_vec"], keepdims=0)]
for mod, w in (("image", "w_img"), ("video", "w_vid"), ("audio", "w_aud")):
    f = f"{mod}_features"
    nodes += [helper.make_node("Shape", [f], [f"{mod}_shape"]),
              helper.make_node("Gather", [f"{mod}_shape", "zero"], [f"{mod}_n"]),
              helper.make_node("Range", ["zero", f"{mod}_n", "one"], [f"{mod}_r"]),
              helper.make_node("Cast", [f"{mod}_r"], [f"{mod}_rf"], to=TensorProto.FLOAT),
              helper.make_node("Add", [f"{mod}_rf", "onef"], [f"{mod}_rf1"]),
              helper.make_node("Reshape", [f"{mod}_rf1", "shp_n1"], [f"{mod}_ramp"]),
              helper.make_node("Mul", [f, f"{mod}_ramp"], [f"{mod}_w"]),
              helper.make_node("ReduceSum", [f"{mod}_w", "ax0"], [f"{mod}_sum"], keepdims=1),
              helper.make_node("MatMul", [f"{mod}_sum", w], [f"{mod}_vec"])]
nodes += [helper.make_node("Add", ["text_vec", "image_vec"], ["s1"]),
          helper.make_node("Add", ["s1", "video_vec"], ["s2"]),
          helper.make_node("Add", ["s2", "audio_vec"], ["sentence_embedding"])]
graph = helper.make_graph(
    nodes, "dummy_text",
    [helper.make_tensor_value_info("input_ids", TensorProto.INT64, ["batch", "seq"]),
     helper.make_tensor_value_info("attention_mask", TensorProto.INT64, ["batch", "seq"]),
     helper.make_tensor_value_info("image_features", TensorProto.FLOAT, ["n_img", H]),
     helper.make_tensor_value_info("video_features", TensorProto.FLOAT, ["n_vid", H]),
     helper.make_tensor_value_info("audio_features", TensorProto.FLOAT, ["n_aud", H])],
    [helper.make_tensor_value_info("last_hidden_state", TensorProto.FLOAT, ["batch", "seq", E]),
     helper.make_tensor_value_info("sentence_embedding", TensorProto.FLOAT, ["batch", E])],
    inits)
m = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17)])
m.ir_version = 8
onnx.checker.check_model(m)
onnx.save(m, os.path.join(out, "onnx", "model.onnx"))

# --- vision encoder: keep valid patches (position >= 0), group by 9, project to H
inits = [init("w_v", rng.normal(0, 0.05, (9 * (D + 2), H)).astype(np.float32)),
         init("zero", np.array(0, np.int64)), init("ax0", np.array([0], np.int64)),
         init("shp_d", np.array([-1, D], np.int64)), init("shp_2", np.array([-1, 2], np.int64)),
         init("shp_flat", np.array([-1], np.int64)), init("shp_g", np.array([-1, 9 * (D + 2)], np.int64))]
nodes = [helper.make_node("Gather", ["pixel_position_ids", "zero"], ["pos0"], axis=2),
         helper.make_node("GreaterOrEqual", ["pos0", "zero"], ["valid"]),
         helper.make_node("Reshape", ["valid", "shp_flat"], ["valid1"]),
         helper.make_node("NonZero", ["valid1"], ["nz"]),
         helper.make_node("Squeeze", ["nz", "ax0"], ["idx"]),
         helper.make_node("Reshape", ["pixel_values", "shp_d"], ["pv2"]),
         helper.make_node("Gather", ["pv2", "idx"], ["g"], axis=0),
         helper.make_node("Cast", ["pixel_position_ids"], ["posf"], to=TensorProto.FLOAT),
         helper.make_node("Reshape", ["posf", "shp_2"], ["pos2"]),
         helper.make_node("Gather", ["pos2", "idx"], ["gp"], axis=0),
         helper.make_node("Concat", ["g", "gp"], ["cat"], axis=1),
         helper.make_node("Reshape", ["cat", "shp_g"], ["grp"]),
         helper.make_node("MatMul", ["grp", "w_v"], ["image_features"])]
graph = helper.make_graph(
    nodes, "dummy_vision",
    [helper.make_tensor_value_info("pixel_values", TensorProto.FLOAT, ["batch", "patches", D]),
     helper.make_tensor_value_info("pixel_position_ids", TensorProto.INT64, ["batch", "patches", 2])],
    [helper.make_tensor_value_info("image_features", TensorProto.FLOAT, ["tokens", H])],
    inits)
m = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17)])
m.ir_version = 8
onnx.checker.check_model(m)
onnx.save(m, os.path.join(out, "onnx", "vision_encoder.onnx"))
print("ok", out, "vocab", V, ids)
