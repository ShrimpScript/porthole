"""Puts a phone's system bars on a screen rendered on the JVM (which has none): the status
bar across the top, around the camera hole, and the gesture handle along the bottom.

The app lays itself out between the bars on a real device, so the height they take comes out
of its stretchy part: the longest run of empty rows (a feed's blank space) closes up, and
nothing at the top or bottom edge is cut off.

    python3 design/phone/statusbar.py IN.png OUT.png [HH:MM] [FEED_TOP]

A full feed has no blank run. It is pinned to its newest message, so on a device the rows at
its top scroll out of view instead: FEED_TOP (a row in IN.png, just under the tabs) says where.
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

src, dst = sys.argv[1], sys.argv[2]
clock = sys.argv[3] if len(sys.argv) > 3 else "10:24"
feed_top = int(sys.argv[4]) if len(sys.argv) > 4 else None
im = Image.open(src).convert("RGB")
W, H = im.size
dp = W / 411                          # the renders are 411 dp wide, whatever their pixels
# The phone model's camera hole is 5.2 mm down a 66.8 mm screen: 32 dp. The bar's icons
# centre on it, and the app starts below its edge.
ICON_Y = round(32 * dp)
bar = round(52 * dp)
nav = round(20 * dp)
need = bar + nav

grey = im.convert("L")
runs, start = [], None
for y in range(H + 1):
    flat = y < H and (lambda e: e[1] - e[0] <= 3)(grey.crop((0, y, W, y + 1)).getextrema())
    if flat and start is None:
        start = y
    elif not flat and start is not None:
        runs.append((y - start, start))
        start = None
length, top = max(runs) if runs else (0, 0)
if feed_top is not None:
    cut = feed_top
elif length >= need + 8:
    cut = top + (length - need) // 2
else:
    sys.exit(f"no empty band tall enough to give the bars {need} px (longest is {length}); give FEED_TOP")
out = Image.new("RGB", (W, H), im.getpixel((4, 4)))
out.paste(im.crop((0, 0, W, cut)), (0, bar))
out.paste(im.crop((0, cut + need, W, H)), (0, bar + cut))
# under the gesture handle, the app's own bottom colour runs on, as it does edge to edge
out.paste(im.getpixel((W // 2, H - 1)), (0, H - nav, W, H))

d = ImageDraw.Draw(out)
fonts = Path(__file__).resolve().parents[2] / "site" / "assets" / "fonts"
face = ImageFont.truetype(str(fonts / "schibsted-grotesk-latin.woff2"), round(13.5 * dp))
try:
    face.set_variation_by_axes([560])
except Exception:
    pass
ink = (232, 240, 238)
mid = ICON_Y
d.text((round(20 * dp), mid), clock, font=face, fill=ink, anchor="lm")

# Right: signal, wifi, battery - drawn as the system draws them, flat and small.
x = W - round(20 * dp)
bw, bh = round(20 * dp), round(10.5 * dp)             # battery body
d.rounded_rectangle((x - bw, mid - bh / 2, x, mid + bh / 2), radius=round(2.6 * dp), outline=ink, width=max(2, round(1.2 * dp)))
d.rounded_rectangle((x - bw + round(2.4 * dp), mid - bh / 2 + round(2.4 * dp), x - round(6 * dp), mid + bh / 2 - round(2.4 * dp)), radius=round(1 * dp), fill=ink)
d.rectangle((x + round(1 * dp), mid - round(2 * dp), x + round(2.4 * dp), mid + round(2 * dp)), fill=ink)
x -= bw + round(9 * dp)
# wifi: a fan of three arcs over a dot
r = round(8.5 * dp)
cx, cy = x - r, mid + round(4.5 * dp)
for k in (1.0, 0.66, 0.34):
    rr = r * k
    d.pieslice((cx - rr, cy - rr, cx + rr, cy + rr), 225, 315, fill=ink if k == 0.34 else None, outline=ink, width=max(2, round(1.5 * dp)))
x -= 2 * r + round(8 * dp)
# signal: four rising bars
for i in range(4):
    h = round((3 + i * 2.6) * dp)
    bx = x - round((3 - i) * 3.6 * dp)
    d.rectangle((bx - round(2.2 * dp), mid + round(5.5 * dp) - h, bx, mid + round(5.5 * dp)), fill=ink)
# the gesture handle
hw, hh = round(54 * dp), round(2.2 * dp)
d.rounded_rectangle((W / 2 - hw, H - nav / 2 - hh, W / 2 + hw, H - nav / 2 + hh), radius=hh, fill=(200, 210, 207))
out.save(dst)
print("wrote", dst)
