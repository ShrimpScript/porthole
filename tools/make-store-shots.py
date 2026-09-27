#!/usr/bin/env python3
"""Phone screenshots for a store listing, from the app's own screens.

The screens are the JVM renders (RenderScreensTest / RenderDocsTest draw the real
composables with neutral sample data), so the listing shows the app, not a mockup. Each
frame is 1080x1920 (9:16, within Play's limits): one line of copy in the app's typeface
above the phone, the ring as the only motif, no gradients, no stock hands.

    cd app-android && ./gradlew :app:testDebugUnitTest --tests '*Render*Test*'
    python3 tools/make-store-shots.py            ->  build/store/shots/NN-name.png
"""
import os
from PIL import Image, ImageDraw, ImageFont

GROUND, SURFACE, EDGE, ACCENT, TEXT, MUTED = "#07100F", "#0E1A19", "#22322F", "#3FD4C0", "#E8F0EE", "#8FA3A0"
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONTS = os.path.join(REPO, "app-android/app/src/main/res/font")
SCREENS = os.path.join(REPO, "app-android/app/build/reports/screens")
OUT = os.path.join(REPO, "build/store/shots")
W, H = 1080, 1920

# (render, headline, second line). Order is the order Play shows them: the reasons to
# install first, the trust page last but present.
SHOTS = [
    ("sessions", "Every session, live.", "Which ones need you, first."),
    ("session-question", "Answer Claude's questions with a tap.", "The choices, as they stand on the computer's screen."),
    ("changes-diff", "See what changed.", "The diff, file by file, on the phone."),
    ("terminal", "A real terminal.", "The same tmux screen as at the desk."),
    ("switches", "Model, effort, permissions.", "One tap each; the CLI confirms."),
    ("session-idle", "Reply in a tap.", "Or type, or dictate."),
    ("settings", "Three looks. Widget and tile.", "Porthole's own, Claude's, Gemini's."),
    ("consent", "It says what it can do before it does it.", "Pair by QR. Revoke from the computer."),
]


def frame(render, headline, line, index):
    im = Image.new("RGB", (W, H), GROUND)
    d = ImageDraw.Draw(im)
    big = ImageFont.truetype(os.path.join(FONTS, "schibsted_grotesk_semibold.ttf"), 64)
    small = ImageFont.truetype(os.path.join(FONTS, "schibsted_grotesk_regular.ttf"), 34)
    # the ring, small, top-left: the motif, not a logo lockup
    d.ellipse([72, 96, 72 + 44, 96 + 44], outline=ACCENT, width=6)
    # copy, wrapped to the width by hand: two lines at most for the headline
    y = 176
    for text, font, colour in ((headline, big, TEXT), (line, small, MUTED)):
        words, cur, lines = text.split(), "", []
        for w in words:
            t = (cur + " " + w).strip()
            if d.textlength(t, font=font) > W - 144 and cur:
                lines.append(cur)
                cur = w
            else:
                cur = t
        lines.append(cur)
        for l in lines:
            d.text((72, y), l, font=font, fill=colour)
            y += int(font.size * 1.22)
        y += 10
    # the phone: the render scaled to fit under the copy, in a rounded frame
    shot = Image.open(os.path.join(SCREENS, render + ".png")).convert("RGB")
    top = y + 36
    avail_h = H - top - 72
    scale = min((W - 200) / shot.width, avail_h / shot.height)
    sw, sh = int(shot.width * scale), int(shot.height * scale)
    shot = shot.resize((sw, sh), Image.LANCZOS)
    x = (W - sw) // 2
    pad = 14
    d.rounded_rectangle([x - pad, top - pad, x + sw + pad, top + sh + pad], radius=44, fill=SURFACE, outline=EDGE, width=3)
    mask = Image.new("L", (sw, sh), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, sw, sh], radius=32, fill=255)
    im.paste(shot, (x, top), mask)
    # crop to the frame bottom so the phone is not cut mid-screen by the 9:16 edge
    name = "%02d-%s.png" % (index, render)
    im.save(os.path.join(OUT, name), optimize=True)
    return name


def main():
    os.makedirs(OUT, exist_ok=True)
    for i, (render, headline, line) in enumerate(SHOTS, 1):
        if not os.path.exists(os.path.join(SCREENS, render + ".png")):
            raise SystemExit("missing render %s: run the Render tests first" % render)
        print(frame(render, headline, line, i))


if __name__ == "__main__":
    main()
