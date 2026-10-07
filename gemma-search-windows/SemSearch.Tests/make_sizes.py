"""Images with known pixel sizes for the header-size test; writes <dir>/expected.tsv (name, width, height).

usage: python3 make_sizes.py <dir>
"""
import os
import sys

from PIL import Image

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
rows = []


def save(name, im, w=None, h=None, **kw):
    im.save(os.path.join(out, name), **kw)
    rows.append((name, im.width if w is None else w, im.height if h is None else h))


rgb = Image.new("RGB", (640, 427), (10, 120, 200))
save("baseline.jpg", rgb, quality=90)
save("progressive.jpg", Image.new("RGB", (1024, 768), (200, 30, 30)), quality=85, progressive=True)
save("cmyk.jpg", Image.new("CMYK", (300, 500), (0, 50, 100, 0)))
# EXIF with a big embedded blob and XMP: the frame header sits ~120 KB into the file
exif = Image.Exif()
exif[0x010E] = "x" * 60000  # ImageDescription
save("big-exif.jpg", Image.new("RGB", (4000, 3000), (5, 5, 5)), exif=exif.tobytes(), xmp=b"<x>" + b"y" * 60000 + b"</x>")
save("icon.png", Image.new("RGBA", (64, 64), (0, 0, 0, 0)))
save("wide.png", Image.new("L", (1920, 200), 128))
save("anim.gif", Image.new("P", (256, 128)))
save("pic.bmp", Image.new("RGB", (333, 222), (1, 2, 3)))
save("ii.tif", Image.new("RGB", (700, 300)), compression="raw")
# Pillow writes little-endian TIFF; build a big-endian one by hand (8-byte header + one IFD with width/height)
import struct
ifd = struct.pack(">H", 2) + struct.pack(">HHII", 256, 4, 1, 4321) + struct.pack(">HHI2sxx", 257, 3, 1, struct.pack(">H", 1234)) + b"\0\0\0\0"
open(os.path.join(out, "mm.tif"), "wb").write(b"MM\0*" + struct.pack(">I", 8) + ifd)
rows.append(("mm.tif", 4321, 1234))
# top-down BMP (negative height)
bmp = bytearray(open(os.path.join(out, "pic.bmp"), "rb").read())
struct.pack_into("<i", bmp, 22, -222)
open(os.path.join(out, "topdown.bmp"), "wb").write(bytes(bmp))
rows.append(("topdown.bmp", 333, 222))
open(os.path.join(out, "notimage.jpg"), "wb").write(b"hello world, definitely not an image")
rows.append(("notimage.jpg", 0, 0))
data = open(os.path.join(out, "big-exif.jpg"), "rb").read()
open(os.path.join(out, "truncated.jpg"), "wb").write(data[:5000])
rows.append(("truncated.jpg", 0, 0))
with open(os.path.join(out, "expected.tsv"), "w") as f:
    for r in rows:
        f.write("%s\t%d\t%d\n" % r)
print(len(rows), "images")
