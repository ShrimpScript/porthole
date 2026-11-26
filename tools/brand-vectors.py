#!/usr/bin/env python3
"""Draws the porthole mark (DESIGN v3 §3) into the app's vector drawables, from one geometry.

    python3 tools/brand-vectors.py            # from the repo root

Writes, in app-android/app/src/main/res/drawable:
  ic_launcher_foreground.xml   the app icon: bolted rim, dark glass, white edge, reflection
  ic_launcher_monochrome.xml   the themed icon: the same shapes in one colour
  ic_launcher_bg.xml           the icon's ground: near-black, lighter at the top
  ic_splash_animated.xml       the splash: the white edge draws in, then the glass glints
  ic_porthole_rim.xml          the splash's rim and bolts alone, and
  ic_porthole_glass.xml        its glass alone - the opening (ApertureView) draws these two
                               where the splash left them, then opens the glass as a hole
  ic_splash_brand.xml          "Porthole" for the splash's branding slot, on a dark ground
  ic_splash_brand_on_light.xml the same on a light ground
The porthole is drawn on a 100-unit square (rim radius 47) and placed by centre and rim
radius. The wordmark is Hanken Grotesk Bold's own outlines, with the porthole as the "o".
Needs fontTools.
"""
import math
import pathlib

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "app-android/app/src/main/res"
OUT = RES / "drawable"
FONT = RES / "font/hanken_grotesk_bold.ttf"

# Where the splash draws its mark, in the 108-unit icon: the opening needs the same numbers.
SPLASH_RIM = 25.0
LAUNCHER_RIM = 29.0


def f(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


def circle(cx, cy, r, clockwise_from_top=False):
    if clockwise_from_top:
        return f"M {f(cx)},{f(cy - r)} a {f(r)},{f(r)} 0 1,1 0,{f(2 * r)} a {f(r)},{f(r)} 0 1,1 0,{f(-2 * r)}"
    return f"M {f(cx - r)},{f(cy)} a {f(r)},{f(r)} 0 1,0 {f(2 * r)},0 a {f(r)},{f(r)} 0 1,0 {f(-2 * r)},0"


def rect(x0, y0, x1, y1):
    return f"M {f(x0)},{f(y0)} H {f(x1)} V {f(y1)} H {f(x0)} Z"


def linear(x0, y0, x1, y1, stops):
    items = "".join(f'<item android:offset="{o}" android:color="{c}"/>' for o, c in stops)
    return (f'<gradient android:type="linear" android:startX="{f(x0)}" android:startY="{f(y0)}" '
            f'android:endX="{f(x1)}" android:endY="{f(y1)}">{items}</gradient>')


def radial(cx, cy, r, stops):
    items = "".join(f'<item android:offset="{o}" android:color="{c}"/>' for o, c in stops)
    return (f'<gradient android:type="radial" android:centerX="{f(cx)}" android:centerY="{f(cy)}" '
            f'android:gradientRadius="{f(r)}">{items}</gradient>')


def filled(path, gradient, name=None, alpha=None):
    n = f' android:name="{name}"' if name else ""
    a = f' android:fillAlpha="{alpha}"' if alpha is not None else ""
    return (f'<path{n} android:pathData="{path}"{a}><aapt:attr name="android:fillColor">{gradient}</aapt:attr></path>')


class Porthole:
    """The mark on a 100-unit square, placed at (cx, cy) with rim radius R."""

    def __init__(self, cx, cy, R):
        self.cx, self.cy, self.k = cx, cy, R / 47.0

    def p(self, x, y):
        return self.cx + (x - 50) * self.k, self.cy + (y - 50) * self.k

    def u(self, v):
        return v * self.k

    def rim(self, light=False, edge_name=None, edge_trim=None):
        k, cx, cy = self.k, self.cx, self.cy
        x0, y0 = self.p(3, 3)
        x1, y1 = self.p(97, 97)
        stops = ([("0", "#FFFFFFFF"), ("0.5", "#FFECECEF"), ("1", "#FFC9C9CF")] if light else
                 [("0", "#FF6A6A72"), ("0.35", "#FF2C2C31"), ("0.75", "#FF18181B"), ("1", "#FF2A2A2F")])
        out = [filled(circle(cx, cy, self.u(47)), linear(x0, y0, x1, y1, stops))]
        if not light:
            trim = f' android:trimPathEnd="{edge_trim}"' if edge_trim is not None else ""
            n = f' android:name="{edge_name}"' if edge_name else ""
            out.append(
                f'<path{n} android:pathData="{circle(cx, cy, self.u(46.4), clockwise_from_top=True)}" '
                f'android:strokeWidth="{f(self.u(1.4))}" android:strokeLineCap="round"{trim}>'
                f'<aapt:attr name="android:strokeColor">'
                + linear(x0, y0, x1, y1, [("0", "#D9FFFFFF"), ("0.4", "#14FFFFFF"), ("0.75", "#00FFFFFF"), ("1", "#4DFFFFFF")])
                + "</aapt:attr></path>")
        for i in range(8):
            t = math.radians(i * 45 + 22.5)
            bx, by = cx + self.u(40) * math.cos(t), cy + self.u(40) * math.sin(t)
            if light:
                out.append(f'<path android:pathData="{circle(bx, by, self.u(2.6))}" android:fillColor="#D90B0B0C"/>')
            else:
                out.append(f'<path android:pathData="{circle(bx, by + self.u(0.5), self.u(2.5))}" android:fillColor="#B3050506"/>')
                out.append(filled(circle(bx, by, self.u(2.3)),
                                  radial(bx - self.u(0.8), by - self.u(1.0), self.u(3.4),
                                         [("0", "#FFD9D9DE"), ("0.5", "#FF77777F"), ("1", "#FF2C2C31")])))
        out.append(f'<path android:pathData="{circle(cx, cy, self.u(33.6))}" android:fillColor="#FF050506"/>')
        out.append(f'<path android:pathData="{circle(cx, cy, self.u(33.6))}" android:strokeColor="#1AFFFFFF" android:strokeWidth="{f(self.u(0.8))}"/>')
        return "\n".join(out)

    def glass(self, glint_name=None):
        k, cx, cy = self.k, self.cx, self.cy
        gx, gy = self.p(50, 18)
        sx, sy = self.p(50, 8)
        a0, a1 = self.p(16, 22), self.p(84, 31)
        b0, b1 = self.p(16, 35), self.p(84, 38.4)
        n = f' android:name="{glint_name}"' if glint_name else ""
        return "\n".join([
            filled(circle(cx, cy, self.u(32)), radial(gx, gy, self.u(78), [("0", "#FF3A4049"), ("0.5", "#FF1D2127"), ("1", "#FF0D0F12")])),
            filled(circle(cx, cy, self.u(32)), radial(sx, sy, self.u(75), [("0", "#8C000000"), ("0.55", "#00000000"), ("1", "#00000000")])),
            f'<group android:rotation="-42" android:pivotX="{f(cx)}" android:pivotY="{f(cy)}">'
            f'<clip-path android:pathData="{circle(cx, cy, self.u(32))}"/>'
            f'<group{n}>'
            f'<path android:pathData="{rect(a0[0], a0[1], a1[0], a1[1])}" android:fillColor="#8CFFFFFF"/>'
            f'<path android:pathData="{rect(b0[0], b0[1], b1[0], b1[1])}" android:fillColor="#61FFFFFF"/>'
            f'</group></group>',
            f'<path android:pathData="{circle(cx, cy, self.u(32))}" android:strokeColor="#80000000" android:strokeWidth="{f(self.u(1.4))}"/>',
        ])


def vector(body, w=108, h=108, comment=""):
    c = f"<!-- {comment} -->\n" if comment else ""
    return (f'<?xml version="1.0" encoding="utf-8"?>\n{c}'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android" '
            f'xmlns:aapt="http://schemas.android.com/aapt" android:width="{w}dp" android:height="{h}dp" '
            f'android:viewportWidth="{w}" android:viewportHeight="{h}">\n{body}\n</vector>\n')


def write(name, text):
    (OUT / name).write_text(text)
    print("wrote", name)


GENERATED = "Generated by tools/brand-vectors.py from the porthole's one geometry; edit that, not this."

# ------------------------------------------------------------------ the launcher icon
mark = Porthole(54, 54, LAUNCHER_RIM)
write("ic_launcher_foreground.xml", vector(mark.rim() + "\n" + mark.glass(), comment=GENERATED))
write("ic_launcher_bg.xml", vector(
    filled("M 0,0 H 108 V 108 H 0 Z", radial(54, 18, 96, [("0", "#FF222226"), ("0.7", "#FF0B0B0C"), ("1", "#FF0B0B0C")])),
    comment=GENERATED))

# The themed icon: one colour, the shapes cut from it. Ring with the bolts as holes; the glass
# as a disc with its reflection cut out.
mono = Porthole(54, 54, LAUNCHER_RIM)
ring = circle(54, 54, mono.u(47)) + " " + circle(54, 54, mono.u(33.6))
for i in range(8):
    t = math.radians(i * 45 + 22.5)
    ring += " " + circle(54 + mono.u(40) * math.cos(t), 54 + mono.u(40) * math.sin(t), mono.u(2.8))
a0, a1 = mono.p(16, 24), mono.p(84, 32)
disc = circle(54, 54, mono.u(27)) + " " + rect(a0[0], a0[1], a1[0], a1[1])
write("ic_launcher_monochrome.xml", vector(
    f'<path android:fillType="evenOdd" android:fillColor="#FF000000" android:pathData="{ring}"/>\n'
    f'<group android:rotation="-42" android:pivotX="54" android:pivotY="54">'
    f'<clip-path android:pathData="{circle(54, 54, mono.u(27))}"/>'
    f'<path android:fillType="evenOdd" android:fillColor="#FF000000" android:pathData="{disc}"/></group>',
    comment=GENERATED))

# ----------------------------------------------------------------------- the splash
splash = Porthole(54, 54, SPLASH_RIM)
write("ic_porthole_rim.xml", vector(splash.rim(), comment=GENERATED + " The opening draws this where the splash left it."))
write("ic_porthole_glass.xml", vector(splash.glass(), comment=GENERATED + " The opening draws this, then opens it as a hole."))
anim = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- {GENERATED}
     The launch: the porthole is there from the first frame; its white edge draws in from
     twelve o'clock, clockwise, over 600ms on the settle curve, then the reflection slides
     into place across the glass in 240ms. 840ms, under the platform's 1000ms cap. The
     opening that follows (ApertureView) starts from exactly this. -->
<animated-vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt">
    <aapt:attr name="android:drawable">
        <vector android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
{splash.rim(edge_name="edge", edge_trim="0")}
{splash.glass(glint_name="glint")}
        </vector>
    </aapt:attr>
    <target android:name="edge">
        <aapt:attr name="android:animation">
            <objectAnimator android:propertyName="trimPathEnd" android:valueFrom="0" android:valueTo="1"
                android:duration="600" android:interpolator="@interpolator/settle" />
        </aapt:attr>
    </target>
    <target android:name="glint">
        <aapt:attr name="android:animation">
            <objectAnimator android:propertyName="translateY" android:valueFrom="{f(splash.u(30))}" android:valueTo="0"
                android:startOffset="600" android:duration="240" android:interpolator="@interpolator/settle" />
        </aapt:attr>
    </target>
</animated-vector>
'''
write("ic_splash_animated.xml", anim)

# --------------------------------------------------------------------- the wordmark
font = TTFont(str(FONT))
gs, cmap, upm = font.getGlyphSet(), font.getBestCmap(), font["head"].unitsPerEm
SIZE = 36.0
scale = SIZE / upm
TRACK = -0.035 * SIZE


def adv(ch):
    return gs[cmap[ord(ch)]].width * scale


o_w = adv("o")
width = adv("P") + o_w + sum(adv(c) for c in "rthole") + TRACK * 7
ox, base = (200 - width) / 2, 53.0


def glyph(ch, x):
    pen = SVGPathPen(gs, ntos=lambda v: f"{v:.2f}")
    gs[cmap[ord(ch)]].draw(TransformPen(pen, (scale, 0, 0, -scale, ox + x, base)))
    return pen.getCommands()


x = 0.0
parts = [glyph("P", x)]
x += adv("P") + TRACK
# The porthole as the "o": its rim as tall as the face's o (0.513em with overshoot), on the baseline.
o_d = 0.513 * SIZE
o_cx, o_cy = ox + x + o_w / 2, base + 0.01 * SIZE - o_d / 2
x += o_w + TRACK
for ch in "rthole":
    parts.append(glyph(ch, x))
    x += adv(ch) + TRACK
text = " ".join(parts)
letter = Porthole(o_cx, o_cy, o_d / 2)


def brand(color, light_rim):
    return vector(
        f'<path android:fillColor="{color}" android:pathData="{text}"/>\n'
        + letter.rim(light=light_rim) + "\n" + letter.glass(),
        w=200, h=80,
        comment=GENERATED + ' The wordmark for the splash\'s branding slot (200x80dp): "Porthole" in Hanken Grotesk Bold, the porthole as the o.')


write("ic_splash_brand.xml", brand("#FFF2F2F4", light_rim=True))
write("ic_splash_brand_on_light.xml", brand("#FF141413", light_rim=False))
print("splash rim radius", SPLASH_RIM, "glass radius", f(32 * SPLASH_RIM / 47), "(108-unit icon)")
