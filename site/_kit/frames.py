#!/usr/bin/env python3
"""
Still frames for pages read without JavaScript: every scene at its poster step and every
phone screen, rendered by the kit itself in headless Chrome, written as WebP to
site/assets/frames/<scene>.webp and site/assets/frames/screen-<id>.webp.

    python3 site/_kit/frames.py            # everything the kit and kit-more.js register
    python3 site/_kit/frames.py question   # just these ids (scene or screen)

Needs google-chrome-stable and Pillow. Starts its own local server on 127.0.0.1 and
stops it (by its own PID) when done. Run it again whenever a scene or screen changes.
"""
import json, os, re, socket, subprocess, sys, tempfile, time
from PIL import Image

SITE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(SITE, 'assets', 'frames')
CHROME = 'google-chrome-stable'


def free_port():
    s = socket.socket(); s.bind(('127.0.0.1', 0)); p = s.getsockname()[1]; s.close(); return p


def chrome(url, profile, *extra):
    base = [CHROME, '--headless=new', '--disable-gpu', '--hide-scrollbars', '--no-first-run',
            f'--user-data-dir={profile}', '--virtual-time-budget=3000']
    return subprocess.run(base + list(extra) + [url], capture_output=True, text=True, timeout=90)


def main():
    os.makedirs(OUT, exist_ok=True)
    port = free_port()
    server = subprocess.Popen([sys.executable, '-m', 'http.server', str(port), '--bind', '127.0.0.1', '-d', SITE],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        time.sleep(0.8)
        root = f'http://127.0.0.1:{port}/_kit/'
        with tempfile.TemporaryDirectory() as tmp:
            profile = os.path.join(tmp, 'profile')
            dom = chrome(root + '?ids', profile, '--dump-dom').stdout
            ids = json.loads(re.search(r'<pre id="ids">(\{.*?)</pre>', dom, re.S).group(1))
            want = set(sys.argv[1:])
            jobs = [('scene', i) for i in ids['scenes']] + [('screen', i) for i in ids['screens']]
            if want:
                jobs = [j for j in jobs if j[1] in want]
            for kind, i in jobs:
                png = os.path.join(tmp, f'{kind}-{i}.png')
                scale = '1.5' if kind == 'scene' else '2'
                size = '1160,900' if kind == 'scene' else '440,900'
                chrome(f'{root}?only={kind}:{i}&still&theme=dark', profile, f'--window-size={size}',
                       f'--force-device-scale-factor={scale}', '--default-background-color=00000000', f'--screenshot={png}')
                im = Image.open(png).convert('RGBA')
                k = float(scale)
                # A phone is cut to the device itself (380 x 820 at 1x, the live aspect ratio); a scene to what it paints.
                im = im.crop(im.getchannel('A').getbbox() if kind == 'scene' else (int(24 * k), int(24 * k), int(404 * k), int(844 * k)))
                name = i if kind == 'scene' else f'screen-{i}'
                dest = os.path.join(OUT, name + '.webp')
                im.save(dest, 'WEBP', quality=82, method=6)
                print(f'{dest}  {im.size[0]}x{im.size[1]}  {os.path.getsize(dest) // 1024} KB'
                      f'  (at 1x: {round(im.size[0] / float(scale))}x{round(im.size[1] / float(scale))})')
    finally:
        server.terminate()
        server.wait()


if __name__ == '__main__':
    main()
