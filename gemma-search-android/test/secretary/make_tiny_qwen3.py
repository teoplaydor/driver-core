"""A tiny Qwen3 in GGUF for the secretary's engine tests: random weights, the real Qwen3 vocabulary (tokenizer.json
from @lenml/tokenizer-qwen3), the same architecture llama.cpp loads for Ternary-Bonsai-4B (qwen3: q/k norms, GQA,
SwiGLU, tied embeddings). Written with gguf-py (from the llama.cpp sources) — no torch needed.

usage: PYTHONPATH=<llama.cpp>/gguf-py python3 make_tiny_qwen3.py TOKENIZER_DIR OUT.gguf
"""
import json
import sys

import numpy as np

import gguf

tok_dir, out = sys.argv[1], sys.argv[2]
tj = json.load(open(f"{tok_dir}/tokenizer.json", encoding="utf-8"))
tc = json.load(open(f"{tok_dir}/tokenizer_config.json", encoding="utf-8"))

vocab = dict(tj["model"]["vocab"])
added = {t["id"]: t for t in tj["added_tokens"]}
n_vocab = max(max(vocab.values()), max(added)) + 1
rev = {i: t for t, i in vocab.items()}
tokens, types = [], []
for i in range(n_vocab):
    if i in added:
        tokens.append(added[i]["content"])
        types.append(gguf.TokenType.CONTROL if added[i]["special"] else gguf.TokenType.USER_DEFINED)
    elif i in rev:
        tokens.append(rev[i])
        types.append(gguf.TokenType.NORMAL)
    else:
        tokens.append(f"[PAD{i}]")
        types.append(gguf.TokenType.UNUSED)
merges = [m if isinstance(m, str) else " ".join(m) for m in tj["model"]["merges"]]

HID, LAYERS, HEADS, KV, HD, FF = 256, 2, 4, 2, 64, 512
rng = np.random.default_rng(7)

w = gguf.GGUFWriter(out, "qwen3")
w.add_name("tiny-qwen3-test")
w.add_context_length(4096)
w.add_embedding_length(HID)
w.add_block_count(LAYERS)
w.add_feed_forward_length(FF)
w.add_head_count(HEADS)
w.add_head_count_kv(KV)
w.add_key_length(HD)
w.add_value_length(HD)
w.add_rope_freq_base(1000000.0)
w.add_layer_norm_rms_eps(1e-6)
w.add_file_type(gguf.LlamaFileType.MOSTLY_F16)

w.add_tokenizer_model("gpt2")
w.add_tokenizer_pre("qwen2")
w.add_token_list(tokens)
w.add_token_types(types)
w.add_token_merges(merges)
w.add_eos_token_id(vocab.get("<|im_end|>", [i for i, t in added.items() if t["content"] == "<|im_end|>"][0]))
w.add_pad_token_id([i for i, t in added.items() if t["content"] == "<|endoftext|>"][0])
w.add_bos_token_id([i for i, t in added.items() if t["content"] == "<|endoftext|>"][0])
w.add_add_bos_token(False)
w.add_chat_template(tc["chat_template"])


def t(name, *shape, scale=0.15, ones=False):
    a = np.ones(shape, dtype=np.float32) if ones else (rng.standard_normal(shape) * scale).astype(np.float32)
    w.add_tensor(name, a.astype(np.float16) if len(shape) > 1 else a)


t("token_embd.weight", n_vocab, HID, scale=0.05)
t("output_norm.weight", HID, ones=True)
for b in range(LAYERS):
    p = f"blk.{b}."
    t(p + "attn_norm.weight", HID, ones=True)
    t(p + "attn_q.weight", HEADS * HD, HID)
    t(p + "attn_k.weight", KV * HD, HID)
    t(p + "attn_v.weight", KV * HD, HID)
    t(p + "attn_output.weight", HID, HEADS * HD)
    t(p + "attn_q_norm.weight", HD, ones=True)
    t(p + "attn_k_norm.weight", HD, ones=True)
    t(p + "ffn_norm.weight", HID, ones=True)
    t(p + "ffn_gate.weight", FF, HID)
    t(p + "ffn_up.weight", FF, HID)
    t(p + "ffn_down.weight", HID, FF)

w.write_header_to_file()
w.write_kv_data_to_file()
w.write_tensors_to_file()
w.close()
print(out, n_vocab, "tokens")
