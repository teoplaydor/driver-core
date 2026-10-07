"""Reference stems (Snowball, snowballstemmer package) for StemmerParityTest: Russian words from
the Gemma vocabulary plus a few inflected forms. usage: stemmer_reference.py tokenizer.json out.tsv"""
import json
import re
import sys

import snowballstemmer

tok = json.load(open(sys.argv[1], encoding="utf-8"))
words = {w for w in (t.replace("▁", "").lower() for t in tok["model"]["vocab"]) if re.fullmatch(r"[а-яё]{2,}", w)}
words |= set("кошки диване закаты собаками красивые машины морем горах фотографии ёлка бегающий праздновали "
             "величайший длиннейшее странность ценностью ограниченная ресторане".split())
stem = snowballstemmer.stemmer("russian")
with open(sys.argv[2], "w", encoding="utf-8") as f:
    for w in sorted(words):
        f.write(f"{w}\t{stem.stemWord(w)}\n")
print("words", len(words))
