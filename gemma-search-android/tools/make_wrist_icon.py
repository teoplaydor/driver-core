"""The watch apps' icon: a dial between two turning arrows (the Wear OS app has it as a vector).
usage: python3 tools/make_wrist_icon.py OUT.png SIZE"""
import math
import sys

from PIL import Image, ImageDraw

out, size = sys.argv[1], int(sys.argv[2])
k = 4  # drawn large, then scaled down: smooth edges
S = size * k
im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(im)
d.ellipse((0, 0, S - 1, S - 1), fill=(0x18, 0x20, 0x2A, 255))
u = S / 108.0  # the vector's 108-unit grid


def p(x, y):
    return (x * u, y * u)


light, accent = (0xE6, 0xED, 0xF3, 255), (0x8D, 0xA6, 0xC0, 255)
d.ellipse((*p(40, 40), *p(68, 68)), outline=light, width=int(4 * u))
d.line((*p(54, 47), *p(54, 54), *p(59, 57)), fill=light, width=int(3 * u), joint="curve")
# the arrows: arcs around the dial with heads
for start, end, head in ((-23, 23, ((73, 60), (78, 66), (84, 62))), (157, 203, ((35, 48), (30, 42), (24, 46)))):
    d.arc((*p(28, 28), *p(80, 80)), start, end, fill=accent, width=int(4 * u))
    d.line([p(*q) for q in head], fill=accent, width=int(4 * u), joint="curve")
im.resize((size, size), Image.LANCZOS).save(out)
