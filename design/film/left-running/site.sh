#!/usr/bin/env bash
# The hand-drawn film as the site plays it, from a drawn build's WORK_DIR (LOOK=drawn build.sh):
#
#   design/film/left-running/site.sh WORK_DIR
#
# Writes site/assets/film/left-running.webm (VP9 + Opus, what most browsers play),
# left-running.mp4 (H.264 + AAC, for the rest) and left-running.webp (the poster: the dog
# arriving with its lead). Both films are 1080p at 15 fps, one frame per drawing, and aimed at
# about 2 Mbit/s so the page stays light: two passes each, which soften the paper's grain a
# little but keep the line. Needs ffmpeg and python3 with Pillow.
set -euo pipefail
WORK=${1:?work dir}
HERE=$(cd "$(dirname "$0")" && pwd)
OUT="$HERE/../../../site/assets/film"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$OUT"
in=(-framerate 15 -i "$WORK/frames-drawn/%05d.png")
au=(-i "$WORK/audio/master.wav" -map 0:v -map 1:a -shortest)

ffmpeg -y -loglevel error "${in[@]}" -c:v libvpx-vp9 -b:v 1700k -pass 1 -passlogfile "$TMP/vp" -row-mt 1 -cpu-used 4 \
  -tile-columns 2 -pix_fmt yuv420p -an -f webm /dev/null
ffmpeg -y -loglevel error "${in[@]}" "${au[@]}" -c:v libvpx-vp9 -b:v 1700k -pass 2 -passlogfile "$TMP/vp" -row-mt 1 -cpu-used 1 \
  -tile-columns 2 -auto-alt-ref 1 -lag-in-frames 25 -pix_fmt yuv420p -c:a libopus -b:a 128k "$OUT/left-running.webm"

ffmpeg -y -loglevel error "${in[@]}" -c:v libx264 -preset slower -b:v 2000k -pass 1 -passlogfile "$TMP/x" \
  -x264-params aq-mode=3 -pix_fmt yuv420p -an -f mp4 /dev/null
ffmpeg -y -loglevel error "${in[@]}" "${au[@]}" -c:v libx264 -preset slower -b:v 2000k -pass 2 -passlogfile "$TMP/x" \
  -x264-params aq-mode=3 -pix_fmt yuv420p -profile:v high -level 4.1 -c:a aac -b:a 160k -movflags +faststart "$OUT/left-running.mp4"

python3 -c "import sys; from PIL import Image; Image.open(sys.argv[1]).convert('RGB').save(sys.argv[2], 'WEBP', quality=80, method=6)" \
  "$WORK/frames-drawn/00187.png" "$OUT/left-running.webp"
ls -l "$OUT"
