"""The boat's reflection on a calm night sea, as an engraver cuts it: the silhouette mirrored at
the waterline and broken into level strokes that thin out and part further the deeper they go.

    python3 design/ship/reflection.py BOAT.png OUT.png [HEX]

BOAT.png is an exterior render on a transparent ground (render_style.py with TRANSPARENT=1),
whose hull ends at the waterline. OUT.png is the same size, to sit under it.
"""
import sys

import numpy as np
from PIL import Image

src, dst = sys.argv[1], sys.argv[2]
ink = sys.argv[3] if len(sys.argv) > 3 else "#050C0B"
a = np.asarray(Image.open(src).getchannel("A")) > 8
H, W = a.shape

# The waterline: the hull's lowest edge, which is a straight line in any view (the sea is a
# plane). Fit it through the columns whose lowest point is hull, not a rail or the ensign.
cols = np.where(a.any(axis=0))[0]
low = np.array([np.max(np.where(a[:, x])[0]) for x in cols])
hull = low > np.percentile(low, 40)
b, c = np.polyfit(cols[hull], low[hull], 1)
water = b * np.arange(W) + c

# Mirror every opaque pixel about the waterline under it.
ys, xs = np.nonzero(a)
depth = water[xs] - ys                      # height above the water
keep = depth > 0
ry = np.round(water[xs[keep]] + depth[keep]).astype(int)
rx = xs[keep]
d = depth[keep]
inside = ry < H
out = np.zeros((H, W), bool)
out[ry[inside], rx[inside]] = True

# Strokes: level bands whose spacing grows with depth.
tall = np.max(water - np.array([np.min(np.where(a[:, x])[0]) if a[:, x].any() else water[x] for x in range(W)]))
yy, xx = np.nonzero(out)
dd = yy - water[xx]
# Only the hull's height is mirrored (rails and deckhouse reflections only scribble).
REACH = 0.3 * tall
# A flat dark mirror of the hull, solid near the waterline; in its lower part it breaks into a
# few long strokes, fewer and shorter the deeper they go - placed by eye, not a ruled grid.
solid = dd < REACH * 0.45
rows = [0.52, 0.64, 0.8]                   # the strokes below, as fractions of the reach
band = solid & (dd >= 0)
rng = np.random.default_rng(5)
for k, fr in enumerate(rows):
    # where this stroke is broken: a run, a gap, a run... along x, the deepest broken most
    open_x = np.ones(W, bool)
    x = int(rng.integers(0, 120))
    while x < W:
        x += int(rng.integers(90, 320) * (1 - 0.25 * k))
        gap = int(rng.integers(20, 70) * (1 + 0.6 * k))
        open_x[x:x + gap] = False
        x += gap
    band |= (np.abs(dd - REACH * fr) < 2.2 - 0.4 * k) & open_x[xx]
res = np.zeros((H, W), bool)
res[yy[band], xx[band]] = True

rgb = tuple(int(ink.lstrip("#")[i:i + 2], 16) for i in (0, 2, 4))
img = np.zeros((H, W, 4), np.uint8)
img[res] = (*rgb, 255)
Image.fromarray(img, "RGBA").save(dst)
print("wrote", dst, "waterline y =", round(c), "+", round(b, 4), "x")
