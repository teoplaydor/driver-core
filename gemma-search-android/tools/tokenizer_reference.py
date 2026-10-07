"""Generates reference token ids with the Rust `tokenizers` library for TokenizerParityTest.

usage: python3 tokenizer_reference.py tokenizer.json out.jsonl
"""
import json
import random
import sys

from tokenizers import Tokenizer

tok = Tokenizer.from_file(sys.argv[1])
added = [t.content for t in tok.get_added_tokens_decoder().values()][:50]

cases = [
    "", " ", "  ", "Hello world", " leading space", "trailing space ", "Привет, мир!",
    "Где мой скриншот с паролем от Wi-Fi?", "task: search result | query: кот на диване",
    "title: none | text: Купить молоко, хлеб и яйца", "def f(x):\n    return x**2\n\n\n",
    "\t\ttabs\tand\nnewlines\r\n", "emoji 😀🐈‍⬛👍🏽 and flags 🇷🇺🇺🇸", "日本語のテキストと中文混合",
    "é combining", "𐎀𐎁 Ugaritic", "\u0000\u0001 control", "a" * 300, "абв" * 200,
    " ".join(["слово"] * 100), "<start_of_image>", "x<start_of_image>y", "<bos>hello<eos>",
    "<unused5><unused55>", "<start_of_turn>user\nhi<end_of_turn>", "<|image|> <|audio|>",
    "URL https://example.com/a?b=c&d=e#f", "числа 1234567890 3.14159 -42", "     many     spaces     ",
]
rng = random.Random(1234)
alphabet = (list("abcdefghijklmnopqrstuvwxyzабвгдеёжзийклмнопрстуфхцчшщъыьэюя ABCXYZ.,!?-_\n\t0123456789")
            + ["😀", "🐈", "é", "ß", "中", "日", "‍", "́", "𐎀", "<", ">", "|"]
            + added)
for _ in range(3000):
    n = rng.randint(1, 60)
    cases.append("".join(rng.choice(alphabet) for _ in range(n)))
for _ in range(200):
    cases.append("".join(chr(rng.randint(32, 0x2FFFF)) for _ in range(rng.randint(1, 30))))
cases = [c for c in cases if not any(0xD800 <= ord(ch) <= 0xDFFF for ch in c)]

with open(sys.argv[2], "w", encoding="utf-8") as f:
    for c in cases:
        f.write(json.dumps({"text": c, "ids": tok.encode(c).ids}, ensure_ascii=False) + "\n")
print("wrote", len(cases), "cases")
