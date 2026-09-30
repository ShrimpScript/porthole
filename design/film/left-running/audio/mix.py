"""The sound of Left Running: the score's stems, every effect on its frame, the room and the park
around them, reverb, ducking, and a master at -14 LUFS (web) with a -1.5 dBTP ceiling.

    python3 mix.py STEMS_DIR OUT.wav        (STEMS_DIR from score.py; writes OUT.wav, 48 kHz 24-bit)

Times come from ../timeline.json, the same clock the picture is drawn from.
"""
import json
import os
import re
import subprocess
import sys

import numpy as np
import scipy.io.wavfile as wavfile
from scipy import signal

import sfx as S

SR = S.SR
HERE = os.path.dirname(os.path.abspath(__file__))
TL = json.load(open(os.path.join(HERE, '..', 'timeline.json')))
E, M = TL['events'], TL['music']
DUR = TL['duration']
N = int(DUR * SR)
rng = np.random.default_rng(11)
db = lambda x: 10 ** (x / 20)

BUSES = {k: np.zeros((N, 2)) for k in ('dry', 'room', 'out', 'memory', 'portal', 'bed')}


def place(bus, t, x, gain=0.0, pan=0.0):
    """Add mono x at time t (seconds) to a bus, equal-power panned (-1 left .. 1 right)."""
    if x.ndim == 1:
        a = (pan + 1) * np.pi / 4
        x = np.stack([x * np.cos(a), x * np.sin(a)], 1) * np.sqrt(2)
    k = int(round(t * SR))
    if k >= N or k + len(x) <= 0:
        return
    lo = max(0, -k); x = x[lo:]; k = max(0, k)
    n = min(len(x), N - k)
    BUSES[bus][k:k + n] += x[:n] * db(gain)


def every(t0, t1, step, fn, jitter=0.0):
    t = t0
    while t < t1:
        fn(t + rng.uniform(-jitter, jitter))
        t += step


def ir(rt60, pre=0.01, hf=0.5, seed=0):
    """A synthetic stereo room: decorrelated noise under an exponential decay, highs dying first."""
    r = np.random.default_rng(seed)
    n = int(rt60 * 1.3 * SR); t = np.arange(n) / SR
    out = np.zeros((n + int(pre * SR), 2))
    for c in range(2):
        w = r.standard_normal(n)
        lo = S.lp(w, 3000) * np.exp(-6.908 * t / rt60)
        hi = S.hp(w, 3000) * np.exp(-6.908 * t / (rt60 * hf))
        out[int(pre * SR):, c] = lo + hi * 0.7
    return out / np.sqrt((out ** 2).sum() / 2)


def verb(x, h, wet):
    y = np.stack([signal.fftconvolve(x[:, c], h[:, c])[:len(x)] for c in range(2)], 1)
    return y * db(wet)


# ============================================================================================
# the beds: where we are
# ============================================================================================
def seg_env(t0, t1, fin=0.3, fout=0.3):
    e = np.zeros(N)
    k0, k1 = int(t0 * SR), min(N, int(t1 * SR))
    e[k0:k1] = 1
    a, b = int(fin * SR), int(fout * SR)
    if a: e[k0:k0 + a] *= np.linspace(0, 1, a)
    if b: e[k1 - b:k1] *= np.linspace(1, 0, b)
    return e


def bed(x, envelope, gain, width=0.6):
    x = np.resize(x, N)
    y = np.roll(x, int(0.013 * SR))      # a little width
    st = np.stack([x + width * (y - x) * 0.5, y - width * (y - x) * 0.5], 1)
    BUSES['bed'] += st * envelope[:, None] * db(gain)


home_day = seg_env(0.0, 27.4, 1.0, 0.8) + seg_env(38.45, 42.5, 0.05, 0.05)
park = seg_env(26.9, 38.5, 0.8, 0.05) + seg_env(42.5, 46.5, 0.05, 0.2)
dusk = seg_env(46.5, 53.6, 0.1, 1.2)
night_out = seg_env(51.6, 57.5, 1.4, 2.5)
bed(S.room_tone(8.0), home_day + dusk, -44)
bed(S.breeze(16.0), park, -30, 0.9)
bed(S.crickets(10.0, 4700), dusk * 0.4 + night_out, -30, 0.8)
bed(np.roll(S.crickets(10.0, 4200), 12000), night_out, -33, 0.8)
# the clock: it is always ticking at home; you notice it when nobody is there
for t in np.arange(0.5, 53.5, 1.0):
    inside = home_day[int(t * SR)] + dusk[int(t * SR)]
    if inside < 0.05 or 14.6 < t < 17.4:
        continue
    loud = -32 if 23.4 < t < 26.6 else -40
    place('room', t, S.clock_tick(tock=int(t) % 2 == 1), loud * 1.0 + 20 * np.log10(max(inside, 1e-3)), 0.35)
# birds: muffled through the window at home, bright and near in the park
for t in np.arange(0.3, 46.5, 0.05):
    if park[int(t * SR)] > 0.5 and rng.random() < 0.028:
        place('out', t, S.chirp_group(), -22 - rng.uniform(0, 8), rng.uniform(-0.9, 0.9))
    elif home_day[int(t * SR)] > 0.5 and rng.random() < 0.01:
        place('room', t, S.lp(S.chirp_group(), 2500), -40, -0.7)

# ============================================================================================
# the effects, on their frames
# ============================================================================================
# the hook: a cursor, a second one, a face that wakes and stretches
for b in (0.95, 1.45):
    place('dry', b, S.tick_cursor(), -18)
place('room', E['secondEye'], S.blip(1100, 1320, 0.08), -12)
place('room', E['eyeDarts'][0], S.blip(880, 820, 0.05, tau=0.03), -21, -0.2)
place('room', E['eyeDarts'][1], S.blip(990, 1060, 0.05, tau=0.03), -21, 0.2)
place('room', E['stretch'][0], S.yawn(1.15), -11)
place('room', E['stretch'][1] - 0.05, S.boing(260, 0.4), -17)
# together: typing, the tests passing, the fist bump
t = E['typing'][0] - 0.5
while t < E['typing'][1] + 0.2:
    place('room', t, S.keyclick(), -27 + rng.uniform(-3, 2), rng.uniform(-0.15, 0.15))
    t += rng.uniform(0.07, 0.17)
place('room', E['tick'], S.bibip(), -12)
place('room', E['tick'], S.boing(300, 0.35), -19)
place('room', E['fistRaise'] + 0.15, S.whirr(0.5, 160, 230), -26)
place('room', E['fistBump'], S.boop(620, 950), -9)
place('room', E['fistBump'], S.thud_soft(90, 0.15, 900), -22)
# one day: the door, the dog, a woof
place('room', E['doorOpen1'], S.door_open(), -18, 0.55)
every(11.35, 13.0, 0.19, lambda t: place('room', t, S.paw_wood(), -27, 0.5 - 0.4 * (t - 11.35) / 1.65), 0.015)
every(11.3, 13.2, 0.38, lambda t: place('room', t, S.jingle(), -25, 0.45 - 0.35 * (t - 11.3) / 1.9), 0.02)
place('room', E['leashDrop'], S.thud_soft(110, 0.18, 900), -25, 0.15)
place('room', E['boof'], S.boof(), -11, 0.15)
place('room', 13.1, S.chair_roll(0.35), -25)
place('room', 13.95, S.chair_roll(0.35), -27)
# the thought: bubbles up, the memory inside them muffled
place('room', 14.55, S.bubble(300, 700, 0.06), -22, 0.15)
place('room', 14.66, S.bubble(320, 760, 0.06), -20, 0.25)
place('room', 14.8, S.bubble(220, 620, 0.1), -16, 0.3)
every(14.95, 17.25, 0.125, lambda t: place('memory', t, S.clock_tick(tock=int(t * 8) % 2 == 0), -24, 0.3))
place('memory', 15.05, S.wind(1.6), -22, 0.2)
place('memory', E['tumbleweed'][0], S.tumble(E['tumbleweed'][1] - E['tumbleweed'][0]), -26, 0.4)
place('memory', E['flashWah'][0], S.wah_wah(0.9), -11, 0.2)
place('room', E['thoughtClose'][0], S.bubble(720, 280, 0.08), -22, 0.25)
# the idea, the nudge, the phone wakes with the chime, "go on"
place('room', M['resume'], S.pop_idea(), -13)
place('room', E['nudge'][0], S.whirr(0.55, 170, 250), -22)
place('room', 18.05, S.slide_wood(0.5), -15, 0.1)
place('room', E['phoneWake'], S.chime(), -17, 0.15)
place('room', E['nods'][0], S.blip(990, 1320, 0.08), -14)
place('room', E['nods'][1], S.blip(1175, 1568, 0.08), -14)
# out of the door
place('room', E['standUp'], S.chair_roll(0.5), -20)
place('room', 19.5, S.jingle(), -24, 0.1)
for i, st in enumerate(E['steps']):
    place('room', st, S.step_wood(), -20, 0.1 + 0.12 * i)
every(19.85, 21.4, 0.17, lambda t: place('room', t, S.paw_wood(), -28, 0.2 + 0.4 * (t - 19.85) / 1.55), 0.01)
every(19.85, 21.4, 0.34, lambda t: place('room', t, S.jingle(), -27, 0.3 + 0.3 * (t - 19.85) / 1.55), 0.02)
place('room', E['wave'], S.bye_blips(), -14)
place('room', E['doorClose'], S.door_close(), -11, 0.55)
# alone: a question, the clock, the droop, the idea, and the question sent
place('room', E['ask'], S.bell(1319, 1.6, 0.8), -15)
place('room', E['amber'], S.whum(0.7), -27)
place('room', E['lookDoor'], S.whirr(0.3, 150, 190), -29)
place('room', E['droop'][0], S.slide_whistle(700, 390, E['droop'][1] - E['droop'][0] + 0.1, (0.006, 0.03)), -14)
place('room', E['perk'], S.pop_idea(), -10)
place('room', E['send'], S.chime(), -15)
place('room', E['send'], S.whum(0.6), -18)
place('dry', E['send'] + 0.05, S.whoosh(0.9, 400, 3200), -17, 0.3)
place('dry', E['wipe'][0] - 0.1, S.whoosh(1.2, 300, 5000), -18)
# the park: the throw, the run, the ball
place('out', E['throw1'] - 0.1, S.whoosh(0.35, 600, 2500), -19, -0.1)
place('out', E['throw1'] + 0.05, S.whoosh(0.9, 2200, 900), -27, -0.4)
place('out', 29.35, S.thud_soft(90, 0.15, 600), -26, -0.6)
place('out', 29.6, S.thud_soft(110, 0.1, 600), -32, -0.6)
every(E['dogRun'][0], E['dogRun'][1], 0.14, lambda t: place('out', t, S.step_grass(), -27, -0.5), 0.01)
every(E['dogRun'][0], E['dogRun'][1], 0.28, lambda t: place('out', t, S.jingle(), -28, -0.4), 0.02)
place('out', 29.5, S.squeak(), -24, -0.5)
# the buzz: the hero of this act. The chime muffled in a pocket, then the phone out
place('dry', E['buzz'][0], S.vibrate(((0, 0.35), (0.55, 0.35))), -9)
place('dry', E['buzz'][0] + 0.05, S.lp(S.chime(), 1800), -19)
place('out', E['phoneUp'][0], S.rustle(0.5), -20)
place('dry', E['notifTap'], S.ui_tap(), -12)
place('dry', E['card'][0] - 0.15, S.card_pop(), -19)
place('out', E['squeak'], S.squeak(), -15, 0.1)
place('dry', E['tapDark'], S.ui_tap(), -9)
place('dry', E['tapDark'] + 0.02, S.blip(1320, 1760, 0.06, tau=0.04), -21)
# the porthole
place('portal', E['portal'][0], S.whoosh(0.85, 300, 4000), -12)
place('portal', E['portal'][0], S.shimmer(1.9), -17)
place('portal', E['portal'][0], S.whum(0.8), -18)
place('portal', 36.72, S.bubble(400, 820, 0.05), -16)
place('portal', E['answerArrives'], S.chime((1175, 1480, 1760)), -16)
rs = S.reverse_swell(1.45)
place('dry', 38.46 - len(rs) / SR, rs, -12)
# the answer lands: dark mode, joy
place('room', E['flip'], S.whoomp(), -7)
place('room', E['flip'] + 0.05, S.sparkle(), -19)
for t, h in zip(E['joyHops'], S.hops()):
    place('room', t, h, -13)
place('room', E['done'], S.bibip(1175, 1760), -12)
place('room', 41.15, S.whirr(1.0, 200, 320), -26)
# the park again: pocket, the big throw, the leap, the catch
place('out', E['pocket'], S.rustle(0.4), -18, 0.2)
place('out', E['throw2'] - 0.1, S.whoosh(0.45, 500, 3000), -14, -0.2)
place('out', E['throw2'] + 0.1, S.whoosh(1.1, 2600, 1000), -25, -0.5)
every(43.25, 44.1, 0.13, lambda t: place('out', t, S.step_grass(), -26, -0.3), 0.01)
place('out', 44.05, S.whoosh(0.45, 800, 2400), -21)
place('out', E['catch'], S.chomp(), -11)
place('out', E['catch'] + 0.05, S.squeak(), -15)
place('out', 44.95, S.thud_soft(70, 0.2, 500), -18)
every(45.0, 45.5, 0.12, lambda t: place('out', t, S.step_grass(), -28), 0.01)
# home at dusk
place('room', E['doorOpen'], S.door_open(), -16, 0.6)
every(46.95, 48.3, 0.15, lambda t: place('room', t, S.paw_wood(), -26, 0.55 - 0.9 * (t - 46.95) / 1.35), 0.01)
every(46.95, 48.5, 0.3, lambda t: place('room', t, S.jingle(), -26, 0.5 - 0.9 * (t - 46.95) / 1.55), 0.02)
place('room', 48.95, S.thud_soft(55, 0.3, 300), -20, -0.5)
place('room', 49.35, S.sigh(), -18, -0.5)
for i, st in enumerate([47.5, 47.9, 48.3, 48.7]):
    place('room', st, S.step_wood(), -22, 0.5 - 0.1 * i)
for p in (E['pat'][0], E['pat'][0] + 0.42):
    place('room', p, S.pat(), -14)
    place('room', p + 0.04, S.boop(700, 1000), -18)
place('room', E['heart'], S.sparkle(4), -20)
place('dry', E['pullOut'][0] + 0.2, S.whoosh(2.6, 180, 1100, 2.0), -28)
# the end: the pen of the ring, and the bell as it closes
r0, r1 = E['ringDraw']
pn = S.pen(r1 - r0)
for i in range(8):
    seg = pn[i * len(pn) // 8:(i + 1) * len(pn) // 8]
    place('dry', r0 + i * (r1 - r0) / 8, seg, -20, np.cos(np.pi / 2 - i / 8 * 2 * np.pi) * 0.6)
place('dry', M['ringClose'], S.bell(2349, 3.0, 0.8), -15)

# ducking: the music gives way to the sounds that tell the story
DUCK = [(E['stretch'][0], 1.0, 3), (E['fistBump'], 0.4, 3), (E['boof'], 0.4, 4), (E['flashWah'][0], 0.9, 3),
        (E['phoneWake'], 0.8, 4), (E['buzz'][0], 1.4, 6), (E['notifTap'], 0.4, 3), (E['tapDark'], 0.4, 3), (E['squeak'], 0.5, 3)]


def duck_env():
    g = np.zeros(N)
    for t, d, depth in DUCK:
        k0 = int((t - 0.05) * SR); k1 = int((t + d) * SR)
        a = np.zeros(N); a[max(0, k0):k1] = depth
        g = np.maximum(g, a)
    # smooth: 20 ms in, 300 ms out
    sm = signal.lfilter([1 - np.exp(-1 / (0.3 * SR))], [1, -np.exp(-1 / (0.3 * SR))], g)
    return db(-np.maximum(g * 0.0 + sm, 0))


# ============================================================================================
def load(path):
    sr, x = wavfile.read(path)
    x = x.astype(np.float64)
    if x.ndim == 1:
        x = np.stack([x, x], 1)
    out = np.zeros((N, 2)); n = min(N, len(x)); out[:n] = x[:n]
    return out


def main(stems, outwav):
    GAIN = {'uke': 1, 'bells': 0, 'pizz': 0, 'strings': -1.5, 'lead': -6, 'harp': -3, 'drums': -2}
    # the score leads; the effects sit in it, the few that tell the story a little above it
    music = sum(load(os.path.join(stems, f'stem-{k}.wav')) * db(g + 10) for k, g in GAIN.items())
    music = np.stack([S.hp(music[:, c], 35) for c in range(2)], 1)
    music *= duck_env()[:, None]
    hall, room, memo, big = ir(1.7, 0.025, 0.5, 1), ir(0.45, 0.008, 0.45, 2), ir(0.9, 0.02, 0.3, 3), ir(3.6, 0.03, 0.6, 4)
    mix = music + verb(music, hall, -13)
    # the room: a little reverb; the memory: small, dull and far; the porthole: vast
    mix += BUSES['room'] + verb(BUSES['room'], room, -15)
    memo_dry = np.stack([S.lp(S.hp(BUSES['memory'][:, c], 250), 4500) for c in range(2)], 1)
    mix += memo_dry * 0.8 + verb(memo_dry, memo, -9)
    mix += BUSES['portal'] + verb(BUSES['portal'], big, -8)
    # outdoors: a couple of soft, late reflections, no tail
    o = BUSES['out']; refl = np.zeros_like(o)
    for dly, g in ((0.043, -17), (0.091, -21), (0.137, -25)):
        k = int(dly * SR); refl[k:] += np.stack([S.lp(o[:-k, 1], 3500), S.lp(o[:-k, 0], 3500)], 1) * db(g)
    mix += o + refl
    mix += BUSES['dry'] + BUSES['bed']
    # glue, and the tail kept clean: the last half second is room tone only
    mix = np.tanh(mix * 1.4) / 1.4
    fade = np.ones(N); k = int((DUR - 0.9) * SR); fade[k:] = np.linspace(1, 0, N - k) ** 2
    mix *= fade[:, None]
    raw = outwav.replace('.wav', '-raw.wav')
    wavfile.write(raw, SR, (mix / max(1.0, np.abs(mix).max())).astype(np.float32))
    master(raw, outwav)


def loudness(path, af=None):
    cmd = ['ffmpeg', '-hide_banner', '-i', path, '-af', (af + ',' if af else '') + 'ebur128=peak=true', '-f', 'null', '-']
    err = subprocess.run(cmd, capture_output=True, text=True).stderr
    summ = err[err.rindex('Summary:'):]
    return float(re.search(r'I:\s*(-?[\d.]+) LUFS', summ).group(1)), float(re.search(r'Peak:\s*(-?[\d.]+) dBFS', summ).group(1)), ' '.join(summ.split())


def master(raw, out, target=-14.0, tp=-1.5):
    """Linear gain to the target loudness into a true-peak limiter, corrected once for what the
    limiter takes off. (loudnorm would switch itself to dynamic mode here and ride the gain.)"""
    chain = lambda g: f'volume={g:.3f}dB,aresample=192000,alimiter=limit={db(tp - 0.2):.4f}:attack=1:release=80:level=false:latency=true,aresample=48000'
    i0, _, _ = loudness(raw)
    g = target - i0
    for _ in range(2):
        i1, _, _ = loudness(raw, chain(g))
        g += target - i1
    subprocess.run(['ffmpeg', '-hide_banner', '-y', '-i', raw, '-af', chain(g), '-ar', '48000', '-c:a', 'pcm_s24le', out], capture_output=True, check=True)
    print('mastered', out, loudness(out)[2])


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
