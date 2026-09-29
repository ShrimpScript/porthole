# Left Running

A 60-second story film for Porthole, at 1920 x 1080 and 30 fps, with its own score and
sound. A little desk computer always waited, stuck, whenever its person went out; one day its
person goes out anyway, and its question finds them at the park.

Everything is made from code in this folder, headless: the models and the animation in
three.js, the music through FluidSynth, every sound effect synthesised. Why it is the way it
is (the research, the story beat by beat, the look, the sound) is in
[TREATMENT.md](TREATMENT.md).

```sh
design/film/left-running/build.sh WORK_DIR        # WORK_DIR/left-running.mp4 and .webm
```

[build.sh](build.sh) lists what it needs. A full render takes about two hours on four CPU cores (no GPU needed).

## How it fits together

- [timeline.json](timeline.json): the one clock. Every beat's time in seconds, read by the
  picture and by the sound, so a sound lands on its frame. Move a beat here and both follow.
- [film.html](film.html): the page that is filmed. It exposes `window.ready` (a promise of the
  duration) and `window.seek(t)`, which draws the frame at time t. The words and the end card
  are HTML over the canvas, in the site's fonts.
- [render.cjs](render.cjs): draws frames with headless Chromium, several browsers at once.
  `--at 1.5,40` draws stills to review; `--query scale=0.5 --dpr 0.5` draws a quick
  half-size preview.
- `src/`: the picture.
  - [film.js](src/film.js): the director. For any t it decides which set is on screen, where
    the camera is, what everyone does, and what is laid over the picture.
  - [stage.js](src/stage.js): the renderer, a second view drawn to a texture (for the thought
    bubble, the wipe and the porthole), bloom, tone mapping, and a grade.
  - [computer.js](src/computer.js) and [face.js](src/face.js): the computer, and the face and
    work drawn on its screen.
  - [human.js](src/human.js), [dog.js](src/dog.js): the person and the dog, each a small rig
    of joints.
  - [home.js](src/home.js), [park.js](src/park.js): the two sets, and their light through the day.
  - [phone.js](src/phone.js): the site's phone model (`site/assets/intro/phone.glb`), with the
    app's screens drawn live in its style, and its real notification.
  - [porthole.js](src/porthole.js): the round window whose glass shows the other place.
  - [kit.js](src/kit.js): easing, keyframes, noise and the rounded shapes everything is built from.
- `audio/`: the sound.
  - [score.py](audio/score.py): the score as notes in seconds, rendered stem by stem.
  - [sfx.py](audio/sfx.py): every effect, synthesised.
  - [mix.py](audio/mix.py): places effects and beds on the timeline, adds reverb and ducking,
    and masters to -14 LUFS with a -1.5 dBTP ceiling.
- [lookdev.html](lookdev.html), [settest.html](settest.html): the cast and the room on their
  own, for look development.

## Credits

- **Phone:** Porthole's own model, from the site.
- **Music:** composed here and played by the FluidR3 General MIDI SoundFont (MIT licence), by
  Frank Wen.
- **Fonts:** Schibsted Grotesk and Iosevka Term (SIL Open Font License), from `site/assets/fonts`.
- **Everything else:** made in this folder. No samples, no stock.
