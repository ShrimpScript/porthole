"""Every sound effect in Left Running, synthesised: no samples. Each function returns a mono
float64 array at SR. The computer speaks in sine blips and a slide whistle; the phone buzzes
and chimes the brand's three notes; the dog jingles; the park has birds and a breeze.
"""
import numpy as np
from scipy import signal

SR = 48000
rng = np.random.default_rng(3)


def T(d):
    return np.arange(int(round(d * SR))) / SR


def env(n, a=0.005, tau=0.1, r=0.005):
    t = np.arange(n) / SR
    e = np.exp(-t / tau) * np.minimum(1, t / max(a, 1e-4))
    rel = int(r * SR)
    if rel and n > rel:
        e[-rel:] *= np.linspace(1, 0, rel)
    return e


def fade(x, a=0.003, r=0.01):
    x = x.copy(); na, nr = int(a * SR), int(r * SR)
    if na: x[:na] *= np.linspace(0, 1, na)
    if nr: x[-nr:] *= np.linspace(1, 0, nr)
    return x


def bp(x, lo, hi, order=2):
    sos = signal.butter(order, [lo, min(hi, SR / 2 - 100)], 'bandpass', fs=SR, output='sos')
    return signal.sosfilt(sos, x)


def lp(x, f, order=2):
    return signal.sosfilt(signal.butter(order, f, 'lowpass', fs=SR, output='sos'), x)


def hp(x, f, order=2):
    return signal.sosfilt(signal.butter(order, f, 'highpass', fs=SR, output='sos'), x)


def noise(d):
    return rng.standard_normal(int(round(d * SR)))


def pink(d):
    n = int(round(d * SR))
    X = np.fft.rfft(rng.standard_normal(n))
    f = np.fft.rfftfreq(n, 1 / SR); f[0] = f[1]
    x = np.fft.irfft(X / np.sqrt(f), n)
    return x / (np.abs(x).max() + 1e-9)


def osc(freq, d=None, shape='sine'):
    """freq: a number or an array (a glide); returns the waveform with integrated phase."""
    if np.isscalar(freq):
        freq = np.full(int(round(d * SR)), float(freq))
    ph = 2 * np.pi * np.cumsum(freq) / SR
    if shape == 'sine':
        return np.sin(ph)
    if shape == 'soft':          # a rounded, band-limited square: the computer's voice
        return np.sin(ph) + 0.25 * np.sin(3 * ph) + 0.08 * np.sin(5 * ph)
    return np.sin(ph)


def glide(f0, f1, d, curve='log'):
    u = np.linspace(0, 1, int(round(d * SR)))
    return f0 * (f1 / f0) ** u if curve == 'log' else f0 + (f1 - f0) * u


def mixa(*xs):
    """Sum sounds of different lengths, all starting together."""
    out = np.zeros(max(len(x) for x in xs))
    for x in xs:
        out[:len(x)] += x
    return out


def norm(x, peak=1.0):
    return x / (np.abs(x).max() + 1e-12) * peak


# ---- the computer ------------------------------------------------------------------------------
def blip(f0, f1=None, d=0.09, harm=0.2, tau=0.08):
    f = glide(f0, f1 or f0, d)
    ph = 2 * np.pi * np.cumsum(f) / SR
    x = np.sin(ph) + harm * np.sin(2 * ph)
    return fade(x * env(len(x), 0.004, tau), 0.002, 0.01)


def bibip(a=880, b=1320):
    return np.concatenate([blip(a, a * 1.02, 0.06, tau=0.05), np.zeros(int(0.03 * SR)), blip(b, b * 1.02, 0.07, tau=0.06)])


def tick_cursor():
    x = bp(noise(0.004), 2500, 5000) * 0.6
    x = np.concatenate([x, np.zeros(int(0.03 * SR))])
    x += 0.35 * np.sin(2 * np.pi * 2200 * T(len(x) / SR)) * env(len(x), 0.001, 0.012)
    return fade(x, 0.0005, 0.005)


def yawn(d=1.1):
    # up, and a long falling sigh, with a wobble: mmm-waaah-oo
    n = int(round(d * SR))
    u = np.linspace(0, 1, n)
    f = np.where(u < 0.4, 330 * (760 / 330) ** (u / 0.4), 760 * (430 / 760) ** ((u - 0.4) / 0.6))
    f = f * (1 + 0.02 * np.sin(2 * np.pi * 6 * u * d) * u)
    x = osc(f, shape='soft')
    x = lp(x, 2600)
    a = np.minimum(1, u / 0.12) * np.minimum(1, (1 - u) / 0.3)
    servo = bp(noise(d), 900, 2200) * 0.08 * np.sin(np.pi * u)
    return fade(x * a * 0.6 + servo, 0.01, 0.05)


def boing(f=300, d=0.4):
    t = T(d)
    fm = f * (1 + 0.5 * np.exp(-t / 0.08)) * (1 + 0.12 * np.sin(2 * np.pi * 24 * t) * np.exp(-t / 0.15))
    x = osc(fm) * env(len(t), 0.003, 0.12)
    return fade(x, 0.002, 0.03)


def boop(f0=600, f1=900):
    x = blip(f0, f1, 0.12, harm=0.3, tau=0.09)
    thump = np.sin(2 * np.pi * 70 * T(0.12)) * env(int(0.12 * SR), 0.002, 0.04)
    return mixa(x * 0.8, thump * 0.6)


def whirr(d=0.5, f0=170, f1=250):
    f = glide(f0, f1, d)
    x = osc(f) + 0.5 * osc(2 * f) + 0.3 * osc(3 * f)
    x = bp(x + 0.2 * noise(d), 200, 2500)
    u = np.linspace(0, 1, len(x))
    return fade(x * np.sin(np.pi * u) ** 0.6 * 0.3, 0.01, 0.05)


def slide_whistle(f0, f1, d, vib=(0.004, 0.02)):
    n = int(round(d * SR)); u = np.linspace(0, 1, n)
    f = f0 * (f1 / f0) ** (u ** 0.9)
    depth = vib[0] + (vib[1] - vib[0]) * u
    f = f * (1 + depth * np.sin(2 * np.pi * 5.5 * u * d))
    x = osc(f) + 0.08 * osc(3 * f)
    breath = np.zeros(n)
    nb = noise(d)
    # breath noise that follows the pitch
    for k in range(0, n, 2400):
        seg = nb[k:k + 2400]
        fc = f[min(k + 1200, n - 1)]
        breath[k:k + len(seg)] = bp(seg, fc * 0.8, fc * 1.25)
    a = np.minimum(1, u / 0.06) * np.minimum(1, (1 - u) / 0.12)
    return fade((x + 0.25 * breath) * a * 0.5, 0.005, 0.03)


def wah_wah(d=0.9):
    a = slide_whistle(620, 520, d * 0.45)
    b = slide_whistle(560, 330, d * 0.55, vib=(0.01, 0.035))
    return np.concatenate([a, b])


def pop_idea():
    # "!": a quick rising blip, and a small bell as the idea lands
    x = np.zeros(int(0.7 * SR))
    b = blip(700, 1500, 0.07, tau=0.05); x[:len(b)] += b
    g = glock(2093, 0.6) * 0.35; k = int(0.05 * SR); x[k:k + len(g)] += g[:len(x) - k]
    return x


def bye_blips():
    out = []
    for f in (1320, 1175, 990):
        out += [blip(f, f * 1.01, 0.06, tau=0.05), np.zeros(int(0.035 * SR))]
    return np.concatenate(out)


def hops(fs=(1175, 1480, 1760)):
    return [mixa(boing(f / 3, 0.3) * 0.5, blip(f, f * 1.12, 0.1, tau=0.08)) for f in fs]


# ---- bells and chimes ---------------------------------------------------------------------------
def bell(f, d=1.2, amp=1.0, partials=((1, 1, 0.9), (2.0, 0.45, 0.4), (3.0, 0.2, 0.22), (4.2, 0.1, 0.1), (5.4, 0.05, 0.06))):
    t = T(d)
    x = sum(a * np.sin(2 * np.pi * f * r * t) * np.exp(-t / tau) for r, a, tau in partials)
    click = hp(noise(0.002), 5000) * 0.1
    x[:len(click)] += click
    return fade(x * amp * np.minimum(1, t / 0.001), 0.0005, 0.05)


def glock(f, d=1.4):
    return bell(f, d, 1.0, ((1, 1, 1.2), (2.76, 0.4, 0.45), (5.4, 0.2, 0.18), (8.93, 0.08, 0.08)))


def chime(notes=(1047, 1319, 1568), gap=0.085, d=1.4):
    """The brand's chime: the motif's first three notes, quick, as bells (the notification)."""
    out = np.zeros(int((gap * len(notes) + d) * SR))
    for i, f in enumerate(notes):
        b = bell(f, d, 0.8 + 0.1 * i)
        k = int(i * gap * SR); out[k:k + len(b)] += b[:len(out) - k]
    return out


def sparkle(n=5, base=(2093, 2349, 2637, 3136, 3520)):
    out = np.zeros(int(1.2 * SR))
    for i in range(n):
        b = glock(base[i % len(base)], 0.8) * (0.6 - 0.07 * i)
        k = int(i * 0.06 * SR); out[k:k + len(b)] += b[:len(out) - k]
    return out


# ---- the phone ------------------------------------------------------------------------------------
def vibrate(pulses=((0, 0.4), (0.6, 0.4)), pocket=True):
    d = max(a + b for a, b in pulses) + 0.1
    t = T(d); x = np.zeros(len(t))
    for a, b in pulses:
        k0, k1 = int(a * SR), int((a + b) * SR)
        tt = t[:k1 - k0]
        f = 130 + 40 * np.minimum(1, tt / 0.05)
        ph = 2 * np.pi * np.cumsum(f) / SR
        m = np.sin(ph) + 0.3 * np.sin(2 * ph) + 0.15 * np.sin(3 * ph)
        rattle = 1 + 0.35 * lp(noise(b), 1000) / 0.1
        e = np.minimum(1, tt / 0.01) * np.minimum(1, (b - tt) / 0.03)
        x[k0:k1] += np.tanh(1.5 * m * np.clip(rattle, 0.4, 1.8)) * e
    if pocket:
        x = lp(x, 1500)
    return x * 0.6


def ui_tap():
    x = hp(noise(0.002), 2000) * 0.5
    x = np.concatenate([x, np.zeros(int(0.03 * SR))])
    t = T(len(x) / SR)
    x += 0.3 * np.sin(2 * np.pi * 2000 * t) * np.exp(-t / 0.015)
    x += 0.5 * np.sin(2 * np.pi * np.cumsum(glide(300, 150, len(x) / SR)) / SR) * np.exp(-t / 0.02)
    return fade(x, 0.0003, 0.005)


def card_pop():
    return 0.5 * whoosh(0.18, 1500, 4000) + np.pad(blip(880, 1320, 0.06, tau=0.04), (int(0.12 * SR), 0))[:int(0.18 * SR)]


def rustle(d=0.45):
    x = bp(noise(d), 1500, 6000)
    am = np.abs(lp(noise(d), 18)) * 8
    u = np.linspace(0, 1, len(x))
    return fade(x * am * np.sin(np.pi * u) * 0.35, 0.01, 0.03)


def slide_wood(d=0.5):
    x = bp(noise(d), 700, 3000)
    rough = 1 + 0.6 * lp(noise(d), 60) / 0.05
    u = np.linspace(0, 1, len(x))
    return fade(x * np.clip(rough, 0.2, 2) * np.sin(np.pi * u) ** 0.5 * 0.25, 0.01, 0.03)


# ---- movement and the room ---------------------------------------------------------------------
def keyclick():
    x = bp(noise(0.006), 2000, 5500) * 0.6
    x = np.concatenate([x, np.zeros(int(0.03 * SR))])
    t = T(len(x) / SR)
    f = rng.uniform(160, 240)
    x += 0.5 * np.sin(2 * np.pi * f * t) * np.exp(-t / 0.018)
    return fade(x, 0.0003, 0.005)


def step_wood():
    d = 0.12; t = T(d)
    heel = np.sin(2 * np.pi * rng.uniform(80, 110) * t) * np.exp(-t / 0.03) * 0.8 + bp(noise(d), 800, 3000) * np.exp(-t / 0.012) * 0.25
    toe = np.roll(heel * 0.45, int(0.05 * SR))
    return fade(heel + toe, 0.001, 0.02)


def step_grass():
    d = 0.14; x = np.zeros(int(round(d * SR)))
    for _ in range(6):
        k = int(rng.uniform(0, 0.09) * SR)
        b = bp(noise(0.003), 2000, 6500) * rng.uniform(0.2, 0.6)
        x[k:k + len(b)] += b
    t = T(d)
    x += 0.2 * np.sin(2 * np.pi * 70 * t) * np.exp(-t / 0.03)
    return x


def paw_wood():
    d = 0.05; t = T(d)
    return bp(noise(d), 1800, 4500) * np.exp(-t / 0.004) * 0.5 + 0.25 * np.sin(2 * np.pi * 380 * t) * np.exp(-t / 0.01)


def jingle():
    d = 0.3; t = T(d); x = np.zeros(len(t))
    for _ in range(4):
        f = rng.uniform(3000, 8500); k = int(rng.uniform(0, 0.03) * SR)
        x[k:] += np.sin(2 * np.pi * f * t[:len(t) - k]) * np.exp(-t[:len(t) - k] / rng.uniform(0.04, 0.12)) * rng.uniform(0.3, 1)
    return fade(x * 0.25, 0.0005, 0.02)


def thud_soft(f=60, d=0.25, nf=300):
    t = T(d)
    return np.sin(2 * np.pi * f * t) * np.exp(-t / 0.06) * 0.8 + lp(noise(d), nf) * np.exp(-t / 0.05) * 1.5


def door_close():
    d = 0.6; t = T(d)
    thump = np.sin(2 * np.pi * 70 * t) * np.exp(-t / 0.08) + bp(noise(d), 200, 800) * np.exp(-t / 0.05) * 0.6
    latch = np.zeros(len(t)); k = int(0.045 * SR)
    lc = bp(noise(0.01), 2000, 6000) * np.exp(-T(0.01) / 0.003) * 0.6
    latch[k:k + len(lc)] += lc
    return fade(thump + latch, 0.001, 0.05)


def door_open():
    d = 0.9; t = T(d)
    latch = bp(noise(0.01), 1800, 5000) * np.exp(-T(0.01) / 0.003) * 0.5
    air = bp(pink(d), 150, 900) * np.sin(np.pi * np.linspace(0, 1, len(t))) * 0.35
    air[:len(latch)] += latch
    return fade(air, 0.001, 0.05)


def chair_roll(d=0.45):
    x = lp(noise(d), 220) * 2.5 + bp(noise(d), 1200, 3000) * 0.08
    u = np.linspace(0, 1, len(x))
    return fade(x * np.sin(np.pi * u), 0.01, 0.05) * 0.4


def boof():
    """A small dog's woof: a falling pulse train through two vowel formants, and breath."""
    d = 0.22; n = int(round(d * SR)); t = T(d)
    f0 = glide(300, 190, d)
    ph = np.cumsum(f0) / SR
    pulses = (np.diff(np.floor(ph), prepend=0) > 0).astype(float)
    src = signal.lfilter([1], [1, -0.95], pulses) + 0.15 * noise(d)
    x = bp(src, 450, 800, 2) * 1.0 + bp(src, 950, 1500, 2) * 0.6 + bp(noise(d), 1500, 4000) * 0.08
    e = np.minimum(1, t / 0.012) * np.exp(-t / 0.07)
    return norm(fade(x * e, 0.001, 0.03), 0.8)


def squeak():
    d = 0.2; u = np.linspace(0, 1, int(round(d * SR)))
    f = 1500 + 1100 * np.sin(np.pi * u) ** 0.7 - 300 * u
    f = f * (1 + 0.03 * np.sin(2 * np.pi * 42 * u * d))
    x = osc(f) + 0.3 * osc(2 * f)
    a = np.sin(np.pi * u) ** 0.4
    first = fade(x * a * 0.5, 0.004, 0.02)
    second = fade(osc(np.full(int(0.08 * SR), 1900.0) * (1 + 0.1 * np.linspace(0, 1, int(0.08 * SR)))) * 0.35, 0.004, 0.02)
    return np.concatenate([first, np.zeros(int(0.04 * SR)), second])


def sigh():
    d = 0.6; t = T(d); u = t / d
    x = bp(noise(d), 400, 1600) * np.sin(np.pi * u) ** 1.5 * 0.4
    return fade(x, 0.02, 0.1)


def pat():
    d = 0.08; t = T(d)
    return np.sin(2 * np.pi * 160 * t) * np.exp(-t / 0.018) * 0.7 + bp(noise(d), 700, 2200) * np.exp(-t / 0.01) * 0.4


def bubble(f0=280, f1=720, d=0.07):
    x = blip(f0, f1, d, harm=0.05, tau=0.05)
    return x


def clock_tick(tock=False):
    d = 0.05; x = np.zeros(int(round(d * SR)))
    exc = noise(0.004); x[:len(exc)] = exc
    f = 2400 if tock else 3200
    b, a = signal.iirpeak(f, 20, SR)
    y = signal.lfilter(b, a, x) * 3 + signal.lfilter(*signal.iirpeak(800, 8, SR), x) * 0.8
    return fade(y * env(len(y), 0.0005, 0.02), 0.0003, 0.005)


def wind(d, lo=380, hi=900):
    x = pink(d)
    out = np.zeros(len(x)); seg = 4800
    u = np.linspace(0, 1, len(x))
    for k in range(0, len(x), seg):
        c = lo + (hi - lo) * (0.5 + 0.5 * np.sin(2 * np.pi * 0.6 * u[k] * d + 1))
        out[k:k + seg] = bp(x[k:k + seg], c * 0.85, c * 1.18)
    return fade(out * np.sin(np.pi * u) ** 0.8 * 1.5, 0.05, 0.1)


def tumble(d):
    x = bp(noise(d), 1500, 5000)
    am = np.clip(lp(noise(d), 12) * 12, 0, 1)
    return x * am * 0.2


# ---- whooshes and the porthole ------------------------------------------------------------------
def whoosh(d, f0=300, f1=4000, q=3.0):
    n = int(round(d * SR)); x = pink(d); out = np.zeros(n)
    u = np.linspace(0, 1, n); seg = 1200
    for k in range(0, n, seg):
        fc = f0 * (f1 / f0) ** u[k]
        out[k:k + seg] = bp(x[k:k + seg], fc / (1 + 1 / q), min(fc * (1 + 1 / q), 20000))
    e = np.sin(np.pi * u) ** 1.5
    return fade(out * e * 2.0, 0.01, 0.02)


def reverse_swell(d=1.4):
    x = hp(pink(d) + 0.3 * noise(d), 3500)
    u = np.linspace(0, 1, len(x))
    return x * (u ** 3) * 0.5


def shimmer(d=1.6, freqs=(932, 1175, 1397, 2093, 2349, 2794)):
    out = np.zeros(int(round(d * SR))); t = T(d)
    for i in range(22):
        f = freqs[rng.integers(len(freqs))] * rng.choice([1, 2])
        k = int(rng.uniform(0, d - 0.3) * SR)
        b = bell(f, 0.8, 0.12)
        out[k:k + len(b)] += b[:len(out) - k]
    return out * (1 + 0.3 * np.sin(2 * np.pi * 10 * t))


def whum(d=0.6):
    t = T(d); u = t / d
    return (np.sin(2 * np.pi * 85 * t) * 0.7 + lp(noise(d), 400) * 0.6) * np.sin(np.pi * u) ** 2


def whoomp():
    d = 0.7; t = T(d)
    low = np.sin(2 * np.pi * np.cumsum(glide(70, 38, d)) / SR) * np.exp(-t / 0.18)
    burst = lp(noise(d), 500) * np.exp(-t / 0.08) * 1.5
    fwip = np.zeros(len(t)); fw = blip(420, 1700, 0.25, harm=0.1, tau=0.2) * 0.5
    k = int(0.05 * SR); fwip[k:k + len(fw)] += fw
    return fade(low + burst + fwip, 0.002, 0.05)


def chomp():
    d = 0.15; t = T(d)
    return bp(noise(d), 1000, 4000) * np.exp(-t / 0.006) * 0.8 + np.sin(2 * np.pi * 120 * t) * np.exp(-t / 0.03) * 0.6


def pen(d):
    x = bp(noise(d), 3000, 6000)
    u = np.linspace(0, 1, len(x))
    return x * np.sin(np.pi * u) ** 0.7 * 0.12


# ---- beds ------------------------------------------------------------------------------------------
def room_tone(d):
    return lp(pink(d), 600) * 0.5 + hp(pink(d), 3000) * 0.02


def breeze(d):
    x = lp(pink(d), 900)
    u = np.linspace(0, 1, len(x))
    m = 0.6 + 0.4 * np.sin(2 * np.pi * 0.11 * u * d + 0.5) * np.sin(2 * np.pi * 0.07 * u * d + 2)
    leaves = bp(noise(d), 2000, 6000) * np.clip(lp(noise(d), 3) * 20, 0, 1) * 0.15
    return x * m + leaves


def chirp_group():
    out = []
    n = rng.integers(3, 7)
    up = rng.random() > 0.5
    for _ in range(n):
        d = rng.uniform(0.04, 0.08)
        f = glide(rng.uniform(2800, 3400), rng.uniform(5200, 6200), d) if up else glide(rng.uniform(4800, 5400), rng.uniform(2400, 2900), d)
        f = f * (1 + 0.06 * np.sin(2 * np.pi * rng.uniform(30, 60) * T(d)))
        c = osc(f) * np.sin(np.pi * np.linspace(0, 1, len(f))) ** 0.6
        out += [c, np.zeros(int(rng.uniform(0.06, 0.12) * SR))]
    return lp(np.concatenate(out), 7000) * 0.3


def crickets(d, f=4600):
    x = np.zeros(int(round(d * SR))); t = 0.2
    while t < d - 0.3:
        for p in range(3):
            k = int((t + p * 0.033) * SR)
            b = np.sin(2 * np.pi * f * T(0.016)) * np.sin(np.pi * np.linspace(0, 1, int(0.016 * SR)))
            x[k:k + len(b)] += b
        t += rng.uniform(0.45, 0.75)
    return x * 0.15
