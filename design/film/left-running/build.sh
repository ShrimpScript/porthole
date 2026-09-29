#!/usr/bin/env bash
# Builds Left Running from this folder alone: the score, the sound, every frame, the film.
#
#   design/film/left-running/build.sh WORK_DIR [JOBS]
#
# WORK_DIR gets audio/ (the stems and master.wav), frames/ (1800 PNGs) and left-running.mp4
# (H.264 + AAC, for feeds) and left-running.webm (VP9 + Opus, for the site). JOBS browsers draw
# frames at once (default 3); an interrupted run picks up where it stopped.
#
# Needs: node with this folder's packages (npm install: three, playwright-core) and a Chromium
# (CHROME=path, default Playwright's); python3 with numpy, scipy and mido; FluidSynth and the
# FluidR3 General MIDI SoundFont (SF2=path, default /usr/share/sounds/sf2/FluidR3_GM.sf2); ffmpeg.
# Headless throughout, no GPU: WebGL runs on SwiftShader.
set -euo pipefail
WORK=${1:?work dir}
JOBS=${2:-3}
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$WORK/audio" "$WORK/frames"
[ -d "$HERE/node_modules/three" ] || (cd "$HERE" && npm install --no-audit --no-fund)

# the sound: the score through FluidSynth, then every effect synthesised and the lot mastered
python3 "$HERE/audio/score.py" "$WORK/audio"
python3 "$HERE/audio/mix.py" "$WORK/audio" "$WORK/audio/master.wav"

# the picture: every frame drawn by the page's seek(t)
node "$HERE/render.cjs" "$HERE/film.html" "$WORK/frames" --jobs "$JOBS" --resume

# the film
in=(-y -loglevel error -framerate 30 -i "$WORK/frames/%05d.png" -i "$WORK/audio/master.wav" -map 0:v -map 1:a -shortest)
ffmpeg "${in[@]}" -c:v libx264 -preset slow -crf 16 -pix_fmt yuv420p -c:a aac -b:a 256k -movflags +faststart "$WORK/left-running.mp4"
ffmpeg "${in[@]}" -c:v libvpx-vp9 -b:v 0 -crf 30 -row-mt 1 -c:a libopus -b:a 160k "$WORK/left-running.webm"
echo "built $WORK/left-running.mp4 and $WORK/left-running.webm"
