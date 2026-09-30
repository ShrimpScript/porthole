"""The score for Left Running, written as notes in seconds and rendered through FluidSynth.

One motif carries the film: C E G A G (it rises and comes back, like the ring), with two
endings. The question ending, E D, stops on the second degree over the dominant and never
resolves; it is all the computer can sing while it waits. The answer ending, E D C, comes
only when the question is answered, and then a whole step higher, in D.

    python3 score.py OUT_DIR        writes OUT_DIR/stem-*.wav (48 kHz, float) and the .mid files

Needs mido, FluidSynth and the FluidR3 General MIDI SoundFont (MIT licence), which Debian and
Ubuntu ship as fluid-soundfont-gm. The times come from ../timeline.json.
"""
import json
import os
import random
import subprocess
import sys

import mido

HERE = os.path.dirname(os.path.abspath(__file__))
TL = json.load(open(os.path.join(HERE, '..', 'timeline.json')))
E = TL['events']
SF2 = os.environ.get('SF2', '/usr/share/sounds/sf2/FluidR3_GM.sf2')
R = random.Random(7)

# ---- instruments: (bank, program), and the stem each is rendered into -----------------------
INST = {
    'uke': (8, 24), 'glock': (0, 9), 'celesta': (0, 8), 'musicbox': (0, 10), 'pizz': (0, 45),
    'bass': (0, 32), 'strings': (0, 48), 'slowstr': (0, 49), 'whistle': (0, 78), 'flute': (0, 73),
    'ocarina': (0, 79), 'harp': (0, 46), 'choir': (0, 52), 'drums': (128, 40),
}
STEMS = {
    'uke': ['uke'], 'bells': ['glock', 'celesta', 'musicbox'], 'pizz': ['pizz', 'bass'],
    'strings': ['strings', 'slowstr', 'choir'], 'lead': ['whistle', 'flute', 'ocarina'], 'harp': ['harp'], 'drums': ['drums'],
}
notes = {k: [] for k in INST}          # instrument -> [(start s, dur s, midi note, velocity)]


def N(inst, t, dur, note, vel, jitter=0.006):
    """A note; plucks and bells get a little timing and velocity life."""
    t = t + (R.gauss(0, jitter) if jitter else 0)
    vel = max(1, min(127, int(round(vel + R.gauss(0, 4)))))
    notes[inst].append((max(0.0, t), dur, note, vel))


def beats(t0, bpm=120):
    spb = 60.0 / bpm
    return lambda b: t0 + b * spb


# re-entrant ukulele shapes, strings g c e a
UKE = {
    'C': (67, 60, 64, 72), 'G': (67, 62, 67, 71), 'Am': (69, 60, 64, 69), 'F': (69, 60, 65, 69),
    'G7': (67, 62, 65, 71), 'Gsus': (67, 62, 65, 72), 'Em': (67, 64, 67, 71), 'Dm': (69, 62, 65, 69),
    'D': (69, 62, 66, 69), 'A': (69, 61, 64, 69), 'Bm': (71, 62, 66, 71), 'Gm': (67, 62, 65, 70),
    'Bb': (70, 62, 65, 70), 'Csus': (67, 60, 65, 72),
}
ROOT = {'C': 48, 'G': 43, 'Am': 45, 'F': 41, 'G7': 43, 'Gsus': 43, 'Em': 40, 'Dm': 38, 'D': 50, 'A': 45, 'Bm': 47, 'Gm': 43, 'Bb': 46, 'Csus': 48}


def strum(t, chord, vel=62, down=True, dur=0.45):
    shape = UKE[chord]
    order = shape if down else tuple(reversed(shape))
    for i, n in enumerate(order):
        N('uke', t + i * 0.012, dur, n, vel - (0 if down else 10) - i * 2, jitter=0.004)


def uke_bar(g, bar, chord, vel=62, pattern='full', swing=0.04):
    """One bar of island strum: D . D U . U D U, swung a little."""
    b = lambda x: g(bar * 4 + x) + (swing if (x % 1) > 0.4 else 0)
    if pattern == 'full':
        for x, d in [(0, 1), (1, 1), (1.5, 0), (2.5, 0), (3, 1), (3.5, 0)]:
            strum(b(x), chord, vel + (8 if x == 0 else 0), down=bool(d), dur=0.3 if x % 1 else 0.45)
    elif pattern == 'light':
        for x, d in [(0, 1), (1.5, 0), (2, 1), (3.5, 0)]:
            strum(b(x), chord, vel, down=bool(d), dur=0.4)
    elif pattern == 'one':
        strum(b(0), chord, vel, dur=1.8)


def pizz_bar(g, bar, chord, vel=70, walk=None):
    r = ROOT[chord]
    for x, n in [(0, r), (2, r + 7)] if walk is None else walk:
        N('pizz', g(bar * 4 + x), 0.35, n, vel)
        N('bass', g(bar * 4 + x), 0.45, n - 12 if n >= 48 else n, vel - 18)


def brush_bar(g, bar, vel=48, clap=False, tamb=False):
    for x in range(4):
        N('drums', g(bar * 4 + x), 0.1, 36 if x % 2 == 0 else 38, vel + (6 if x == 0 else 0))
        N('drums', g(bar * 4 + x + 0.5) + 0.04, 0.1, 42, vel - 18)
        N('drums', g(bar * 4 + x), 0.1, 42, vel - 12)
        if clap and x % 2 == 1:
            N('drums', g(bar * 4 + x), 0.1, 39, vel + 6)
        if tamb:
            N('drums', g(bar * 4 + x + 0.5) + 0.04, 0.1, 54, vel - 8)


def motif(inst, g, b0, root, vel, tail=None, octave=0, dur_last=1.0, double=None):
    """The motif from beat b0 of grid g: 1 3 5 6 5 in the key whose tonic is root, then a tail."""
    deg = {1: 0, 2: 2, 3: 4, 5: 7, 6: 9}
    seq = [(0, 1), (0.5, 3), (1, 5), (1.5, 6), (2, 5)]
    if tail == 'question':
        seq += [(3, 3), (3.5, 2)]
    elif tail == 'answer':
        seq += [(4, 3), (5, 2), (6, 1)]
    for i, (x, d) in enumerate(seq):
        last = i == len(seq) - 1
        dur = dur_last if last else (0.9 if x == 2 else 0.45)
        n = root + 12 * octave + deg[d]
        N(inst, g(b0 + x), dur, n, vel + (6 if x in (0, 2) else 0))
        if double:
            N(double[0], g(b0 + x), dur, n + double[1], double[2])


C5, D5 = 72, 74

# ---- 1. together (2.0 - 14.5): C major, the motif asked and answered ------------------------
g = beats(TL['music']['togetherDownbeat'])
chords = ['C', 'C', 'G', 'Am', 'G', 'F']
# bar 0 is the stretch: one big strum and a harp run up; the band comes in on bar 1
strum(g(0), 'C', 84, dur=1.6)
for i, n in enumerate([60, 64, 67, 72, 76, 79, 84]):
    N('harp', g(0) + i * 0.05, 0.8, n, 60 + i * 3, jitter=0)
N('pizz', g(0), 0.6, 48, 80)
for bar, ch in [(1, 'C'), (2, 'G'), (3, 'Am'), (4, 'G'), (5, 'F')]:
    if bar == 3:
        # half Am, half F
        for x, d, c in [(0, 1, 'Am'), (1, 1, 'Am'), (1.5, 0, 'Am'), (2, 1, 'F'), (2.5, 0, 'F'), (3, 1, 'F'), (3.5, 0, 'F')]:
            strum(g(bar * 4 + x) + (0.04 if x % 1 else 0), c, 64 + (8 if x == 0 else 0), down=bool(d))
        pizz_bar(g, bar, 'Am', walk=[(0, 45), (2, 41)])
    elif bar == 4:
        for x, d, c in [(0, 1, 'G'), (1, 1, 'G'), (1.5, 0, 'G'), (2, 1, 'C'), (2.5, 0, 'C'), (3, 1, 'C'), (3.5, 0, 'C')]:
            strum(g(bar * 4 + x) + (0.04 if x % 1 else 0), c, 64 + (8 if x == 0 else 0), down=bool(d))
        pizz_bar(g, bar, 'G', walk=[(0, 43), (2, 48)])
    elif bar == 5:
        uke_bar(g, bar, 'F', 54, 'light')
        pizz_bar(g, bar, 'F', 60)
    else:
        uke_bar(g, bar, ch, 62)
        pizz_bar(g, bar, ch)
    brush_bar(g, bar, 44 if bar < 5 else 36, clap=2 <= bar <= 4)
# the motif, glockenspiel doubled by celesta an octave down: asked in bars 1-2, answered in 3-4
motif('glock', g, 4, C5 + 12, 70, tail='question', double=('celesta', -12, 58))
motif('glock', g, 12, C5 + 12, 72, tail='answer', dur_last=1.2, double=('celesta', -12, 60))
# the typing: woodblocks in eighths while they work
tb = g(0)
x = E['typing'][0]
while x < E['typing'][1]:
    N('drums', x, 0.05, 76 if R.random() > 0.5 else 77, 26 + R.randint(0, 10), jitter=0.01)
    x += 0.25
# the dog trots in: the bar thins to the ukulele and pizzicato; on the look back it stops
strum(g(24), 'Gsus', 56, dur=0.5)
N('pizz', g(24), 0.4, 43, 64)
# the hesitation: one low pizzicato, falling
N('pizz', E['humanToComputer'] + 0.3, 0.6, 36, 70, jitter=0)

# ---- 2. the thought (14.6 - 17.5): the question, slow, on a music box, in the minor ---------
t0 = E['thoughtOpen'][0] + 0.2
for dt, n, d in [(0.0, 72, 0.4), (0.35, 75, 0.4), (0.7, 79, 0.4), (1.05, 80, 0.5), (1.45, 79, 0.9), (2.2, 75, 0.4), (2.55, 74, 1.2)]:
    N('musicbox', t0 + dt, d, n, 64, jitter=0.012)
    N('celesta', t0 + dt, d, n - 12, 40, jitter=0.012)
for n in (48, 55, 60, 63):
    N('slowstr', t0 - 0.1, 2.9, n, 34, jitter=0)

# ---- 3. the idea, the nudge, "go on", out of the door (17.5 - 21.9) -------------------------
g = beats(TL['music']['resume'])
N('glock', g(0), 0.6, 84, 70, jitter=0); N('glock', g(0) + 0.06, 0.6, 91, 60, jitter=0)
for i, n in enumerate([55, 57, 59, 60]):
    N('pizz', E['nudge'][0] + i * 0.2, 0.2, n, 58 + i * 5, jitter=0.004)
# bars from 19.5: out they go, whistling the motif; it stops on the question as the door shuts
uke_bar(g, 1, 'C', 60)
pizz_bar(g, 1, 'C', 66)
brush_bar(g, 1, 38, clap=True)
motif('whistle', g, 4, C5, 74, tail='question', dur_last=0.9)
N('whistle', g(4), 0.4, 64, 30)
strum(g(8), 'G', 60, dur=1.4)
N('pizz', g(8), 0.5, 43, 64)

# ---- 4. alone (21.9 - 27.6): its own hum; then a question, and nobody to answer it ---------
h0 = E['hum'][0]
for dt, n, d in [(0, 72, 0.22), (0.25, 76, 0.22), (0.5, 79, 0.22), (0.75, 81, 0.22), (1.0, 79, 0.45)]:
    N('ocarina', h0 + dt, d, n, 58, jitter=0.01)
N('celesta', E['ask'], 0.6, 76, 70, jitter=0)
N('celesta', E['ask'] + 0.3, 2.4, 74, 64, jitter=0)
for n in (43, 50, 60, 65):
    N('slowstr', E['ask'], 2.0, n, 40, jitter=0)
# it remembers: a harp swept upward, out through the window, into the park
t = E['send'] + 0.1
for i, n in enumerate([60, 62, 64, 67, 69, 72, 74, 76, 79, 81, 84, 86, 88, 91]):
    N('harp', t + i * 0.085, 0.9, n, 52 + i * 2, jitter=0)

# ---- 5. the park (28.0 - 36.0): light and outdoors; then the buzz, and it holds its breath --
g = beats(TL['music']['parkDownbeat'])
uke_bar(g, 0, 'C', 56, 'light')
pizz_bar(g, 0, 'C', 62)
for b, n, d in [(0, 79, 0.25), (0.5, 81, 0.25), (1, 84, 0.5), (2, 88, 0.25), (2.5, 86, 0.25), (3, 84, 0.5)]:
    N('flute', g(b), d, n, 70)
brush_bar(g, 0, 34)
strum(g(4), 'F', 52, dur=0.45)
N('pizz', g(4), 0.4, 41, 58)
N('flute', g(4), 0.4, 81, 64)
# the drop: strings on a suspended G, and a soft pulse like a held breath
for n in (43, 50, 60, 62):
    N('slowstr', E['buzz'][0] + 0.2, 5.3, n, 36, jitter=0)
t = E['buzz'][0] + 1.0
while t < E['portal'][0] - 0.1:
    N('pizz', t, 0.2, 43, 34, jitter=0.003)
    t += 0.5
N('celesta', E['card'][0] + 0.15, 0.5, 76, 52, jitter=0)
N('celesta', E['card'][0] + 0.4, 1.5, 74, 48, jitter=0)
N('glock', E['dogLook'][0] + 0.25, 0.3, 84, 42, jitter=0)
N('glock', E['dogLook'][0] + 0.5, 0.5, 88, 44, jitter=0)

# ---- 6. the porthole (36.0 - 38.0): Bb add9, then C sus4, rising; then nothing -------------
p0 = E['portal'][0]
for n in (46, 53, 62, 72):
    N('strings', p0, 1.05, n, 58, jitter=0)
for n in (58, 62, 65):
    N('choir', p0, 1.05, n, 44, jitter=0)
for n in (48, 55, 65, 67):
    N('strings', p0 + 1.0, 1.0, n, 72, jitter=0)
for n in (60, 65, 67):
    N('choir', p0 + 1.0, 1.0, n, 54, jitter=0)
arp1 = [70, 74, 77, 84, 86, 89]
arp2 = [72, 77, 79, 84, 89, 91]
for i in range(8):
    N('celesta', p0 + i * 0.125, 0.3, arp1[i % 6], 50 + i * 2, jitter=0.003)
    N('celesta', p0 + 1.0 + i * 0.125, 0.3, arp2[i % 6], 58 + i * 2, jitter=0.003)
N('glock', E['answerArrives'], 1.0, 86, 64, jitter=0)

# ---- 7. the answer (38.5 - 46.5): up a whole step, everyone in ------------------------------
g = beats(TL['music']['payoffDownbeat'])
for bar, ch in enumerate(['D', 'A', 'Bm', 'G']):
    uke_bar(g, bar, ch, 70)
    pizz_bar(g, bar, ch, 78)
    brush_bar(g, bar, 50, clap=True, tamb=True)
    # strings come in arco from the second bar
    if bar >= 1:
        for n in [ROOT[ch] + 12, *[m for m in UKE[ch][1:3]]]:
            N('strings', g(bar * 4), 1.95, n, 56, jitter=0)
# the motif, and at last the answer: glockenspiel and celesta, then whistle and flute
motif('glock', g, 0, D5 + 12, 80, tail='answer', dur_last=1.6, double=('celesta', -12, 66))
motif('whistle', g, 8, D5, 78, tail='answer', dur_last=1.8, double=('flute', 12, 52))
# a cymbal on the catch
N('drums', E['catch'], 1.5, 49, 64, jitter=0)
N('drums', E['flip'], 1.5, 49, 58, jitter=0)

# ---- 8. home at dusk (46.5 - 53.4): IV, iv, I, slow; the answer once more, softly ------------
b0 = TL['music']['button']
for t, ch, dur in [(b0, 'G', 2.0), (b0 + 2.0, 'Gm', 2.0), (b0 + 4.0, 'D', 6.5)]:
    for n in [ROOT[ch], ROOT[ch] + 7, *UKE[ch][1:4]]:
        N('slowstr', t, dur, n if n > 40 else n + 12, 46, jitter=0)
    strum(t, ch, 46, dur=1.6)
for dt, n, d in [(0, 78, 0.9), (1.0, 76, 0.9), (2.0, 74, 1.8), (2.0, 70, 1.8)]:
    N('celesta', b0 + dt, d, n, 56, jitter=0.01)
# the heart
for i, n in enumerate([86, 90, 93]):
    N('glock', E['heart'] + i * 0.07, 0.6, n, 54 - i * 4, jitter=0)
# out through the window: a slow harp climb
t = E['pullOut'][0] + 0.9
for i, n in enumerate([62, 66, 69, 74, 78, 81]):
    N('harp', t + i * 0.3, 1.4, n, 44 + i * 2, jitter=0.01)

# ---- 9. the end: the ring draws on a climbing pentatonic; the button is a D ------------------
r0, r1 = E['ringDraw']
ring_notes = [86, 88, 90, 93, 95, 98]
for i, n in enumerate(ring_notes):
    N('glock', r0 + (r1 - r0 - 0.12) * i / (len(ring_notes) - 1), 0.5, n, 50 + i * 4, jitter=0)
    N('celesta', r0 + (r1 - r0 - 0.12) * i / (len(ring_notes) - 1), 0.5, n - 12, 40 + i * 3, jitter=0)
rc = TL['music']['ringClose']
strum(rc, 'D', 80, dur=0.35)
for n in (38, 50):
    N('pizz', rc, 0.4, n, 84, jitter=0)
    N('bass', rc, 0.8, n - 12 if n > 40 else n, 70, jitter=0)
for n in (62, 66, 69, 74):
    N('strings', rc, 0.3, n, 70, jitter=0)
    N('slowstr', rc + 0.05, 3.8, n, 40, jitter=0)
N('glock', rc, 2.5, 98, 76, jitter=0)
N('glock', rc, 2.5, 93, 60, jitter=0)
N('celesta', rc, 2.5, 86, 60, jitter=0)
N('drums', rc, 1.0, 81, 50, jitter=0)  # a triangle


# ---- render ---------------------------------------------------------------------------------------
def write_mid(path, insts):
    tpb = 960
    mid = mido.MidiFile(ticks_per_beat=tpb)
    track = mido.MidiTrack(); mid.tracks.append(track)
    track.append(mido.MetaMessage('set_tempo', tempo=500000, time=0))   # 120 bpm: a tick is 1/1920 s
    evs = []
    for ch_i, inst in enumerate(insts):
        bank, prog = INST[inst]
        ch = 9 if inst == 'drums' else ch_i
        evs.append((0, 0, mido.Message('control_change', channel=ch, control=0, value=bank % 128)))
        if inst == 'drums':
            evs.append((0, 0, mido.Message('program_change', channel=ch, program=prog)))
        else:
            evs.append((0, 0, mido.Message('program_change', channel=ch, program=prog)))
        evs.append((0, 0, mido.Message('control_change', channel=ch, control=91, value=0)))
        evs.append((0, 0, mido.Message('control_change', channel=ch, control=93, value=0)))
        for (t, d, n, v) in notes[inst]:
            on = int(round(t * 1920)); off = int(round((t + d) * 1920))
            evs.append((on, 1, mido.Message('note_on', channel=ch, note=n, velocity=v)))
            evs.append((off, 0, mido.Message('note_off', channel=ch, note=n, velocity=0)))
    end = int(round((TL['duration'] + 2) * 1920))
    evs.append((end, 0, mido.Message('control_change', channel=0, control=121, value=0)))
    evs.sort(key=lambda e: (e[0], e[1]))
    now = 0
    for (tick, _, msg) in evs:
        track.append(msg.copy(time=tick - now)); now = tick
    mid.save(path)


def main(out):
    os.makedirs(out, exist_ok=True)
    for stem, insts in STEMS.items():
        mid = os.path.join(out, f'stem-{stem}.mid'); wav = os.path.join(out, f'stem-{stem}.wav')
        write_mid(mid, insts)
        subprocess.run(['fluidsynth', '-ni', '-q', '-r', '48000', '-g', '0.6', '-R', '0', '-C', '0', '-O', 'float', '-T', 'wav',
                        '-F', wav, SF2, mid], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        print('rendered', wav, sum(len(notes[i]) for i in insts), 'notes')


if __name__ == '__main__':
    main(sys.argv[1])
