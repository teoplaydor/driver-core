"""
Made-up pictures with text (a ticket, a receipt, a screenshot line, a slightly turned card, white on dark) and what
RapidOCR 3.10 reads on them with its bundled PP-OCRv6 small models (no direction classifier, one piece at a time — as
the app runs them). The Java port (core/Ocr) is checked against this: the same texts, boxes within a few pixels.
usage: reference_ocr.py <out dir>
"""
import json
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

from rapidocr import RapidOCR

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
MONO = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"


def page(w, h, lines, bg=(255, 255, 255), fg=(20, 20, 20), angle=0.0):
    img = Image.new("RGB", (w, h), bg)
    d = ImageDraw.Draw(img)
    for (x, y, text, size, font) in lines:
        d.text((x, y), text, fill=fg, font=ImageFont.truetype(font, size))
    if angle:
        img = img.rotate(angle, resample=Image.BICUBIC, expand=False, fillcolor=bg)
    return img


cases = {
    "ticket": page(900, 520, [
        (40, 30, "ELECTRONIC TICKET", 34, BOLD),
        (40, 100, "Train 024A MOSCOW - KAZAN", 30, FONT),
        (40, 160, "Departure 21.10.2026 14:35", 30, FONT),
        (40, 220, "Arrival 22.10.2026 06:10", 30, FONT),
        (40, 280, "Car 05 Seat 017", 30, FONT),
        (520, 420, "Total 4590.00", 28, MONO),
    ]),
    "receipt": page(640, 760, [
        (60, 40, "TECHNO STORE", 36, BOLD),
        (60, 120, "Receipt 12.03.2026 18:42", 26, FONT),
        (60, 180, "Vacuum cleaner V12", 26, FONT),
        (60, 240, "Warranty 24 months", 26, FONT),
        (60, 320, "TOTAL 45990.00", 30, MONO),
        (60, 400, "Thank you!", 26, FONT),
    ]),
    "screenshot": page(1080, 400, [
        (48, 60, "Meeting at 19:00 near the cafe", 40, FONT),
        (48, 160, "Code 47K1290, entrance 3", 40, FONT),
        (48, 260, "wifi password: Lisa2024", 40, MONO),
    ], bg=(245, 245, 245)),
    "turned": page(800, 500, [
        (80, 120, "Boarding pass SU 1234", 34, FONT),
        (80, 200, "Gate B12 Boarding 07:15", 34, FONT),
    ], angle=4.0),
    "dark": page(760, 300, [
        (50, 60, "Night mode", 40, BOLD),
        (50, 160, "Battery 87%", 36, FONT),
    ], bg=(18, 22, 28), fg=(230, 230, 230)),
}

engine = RapidOCR(params={"Global.use_cls": False, "Rec.rec_batch_num": 1})
# the recognizer's alphabet as RapidOCR reads it (the model's metadata): a file for the Java side (JNI strings would
# mangle its emoji)
import onnxruntime as ort
import rapidocr
rec_path = os.path.join(os.path.dirname(rapidocr.__file__), "models", "PP-OCRv6_rec_small.onnx")
chars = ort.InferenceSession(rec_path, providers=["CPUExecutionProvider"]).get_modelmeta().custom_metadata_map["character"]
open(os.path.join(out, "dict.txt"), "w", encoding="utf-8").write("\n".join(chars.splitlines()) + "\n")
ref = {}
for name, img in cases.items():
    path = os.path.join(out, name + ".png")
    img.save(path)
    r = engine(np.array(img)[:, :, ::-1])  # BGR, as OpenCV reads it
    items = []
    if r.boxes is not None:
        for box, txt, score in zip(r.boxes, r.txts, r.scores):
            items.append({"text": txt, "score": float(score), "box": [float(v) for v in np.array(box).reshape(-1)]})
    ref[name] = items
    print(name, [i["text"] for i in items])
json.dump(ref, open(os.path.join(out, "reference.json"), "w"), ensure_ascii=False, indent=1)
