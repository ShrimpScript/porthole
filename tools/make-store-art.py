#!/usr/bin/env python3
"""Generates the Play Store art from the app's own tokens and fonts.

Generated rather than drawn by hand so the store art and the app cannot drift apart: the
ring geometry is the one in res/drawable/ic_launcher_foreground.xml, the colours are
the app's design tokens, and the type is the font the app actually ships.

    python3 tools/make-store-art.py     ->  build/store/
"""
import os
from PIL import Image, ImageDraw, ImageFont

GROUND, ACCENT, TEXT, MUTED = "#07100F", "#3FD4C0", "#E8F0EE", "#8FA3A0"
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONTS = os.path.join(REPO, "app-android/app/src/main/res/font")
OUT = os.path.join(REPO, "build/store")
SS = 4  # supersample factor, for clean curves and type

# The launcher masks the icon's 108 canvas down to a ~72 circle, so 72 is the frame people
# actually see. Scaling by 108 would be geometrically faithful and visibly too small.
RING_R, RING_STROKE, VISIBLE = 22.0, 5.5, 72.0


def ring(draw, cx, cy, outer_d, scale=1):
    r = RING_R / VISIBLE * outer_d
    w = RING_STROKE / VISIBLE * outer_d
    draw.ellipse(
        [(cx - r) * scale, (cy - r) * scale, (cx + r) * scale, (cy + r) * scale],
        outline=ACCENT, width=max(1, int(w * scale)),
    )


def icon_512():
    size = 512
    im = Image.new("RGB", (size * SS, size * SS), GROUND)
    ring(ImageDraw.Draw(im), size / 2, size / 2, size, SS)
    im.resize((size, size), Image.LANCZOS).save(os.path.join(OUT, "play-icon-512.png"))
    return "play-icon-512.png", (size, size)


def feature_graphic():
    w, h = 1024, 500
    im = Image.new("RGB", (w * SS, h * SS), GROUND)
    d = ImageDraw.Draw(im)
    ring(d, 250, h / 2, 300, SS)

    name = ImageFont.truetype(os.path.join(FONTS, "schibsted_grotesk_semibold.ttf"), 76 * SS)
    line = ImageFont.truetype(os.path.join(FONTS, "schibsted_grotesk_regular.ttf"), 33 * SS)
    # The block is centred optically on the ring, not on the canvas: its top sits above
    # the ring's centre by roughly half its own height.
    d.text((470 * SS, 168 * SS), "Porthole", font=name, fill=TEXT)
    d.text((472 * SS, 258 * SS), "Claude Code on your computer,", font=line, fill=MUTED)
    d.text((472 * SS, 302 * SS), "from your phone.", font=line, fill=MUTED)

    im.resize((w, h), Image.LANCZOS).save(os.path.join(OUT, "play-feature-1024x500.png"))
    return "play-feature-1024x500.png", (w, h)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for name, size in (icon_512(), feature_graphic()):
        print(f"  build/store/{name}  {size[0]}x{size[1]}")
