#!/usr/bin/env bash
# Builds the ad set from nothing but this repo, Blender and the mannequin bundle:
#
#   design/ads/build.sh WORK_DIR HUMAN_BASE_MESHES.blend
#
# WORK_DIR gets the models, the renders and the finished ads (NAME.png at the channel's size,
# NAME@2x.png the master), and the engine-room film (engine-room.mp4 and .webm). HUMAN_BASE_MESHES.blend is Blender Studio's Human Base Meshes
# bundle (CC-BY 4.0, (c) Blender Foundation), from studio.blender.org; it is not in the repo.
#
# The app screens come from the JVM screen tests; run them first, in UTC (the status-bar clocks
# below are set to match the times the feed shows):
#   (cd app-android && TZ=UTC ./gradlew :app:testDebugUnitTest --tests '*RenderScreensTest*')
# The callouts' positions in the pages come from each render's map (renders/*.png.json); if a
# model or a camera changes, check them against it. Headless throughout. Needs blender,
# python3 with Pillow and numpy, ffmpeg, node with playwright-core
# (PW=path/to/playwright-core if it is not in ~/node_modules) and Chrome.
set -euo pipefail
WORK=${1:?work dir}
BUNDLE=${2:?Human Base Meshes .blend}
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
SCREENS="$REPO/app-android/app/build/reports/screens"
[ -f "$SCREENS/approval-whole-command.png" ] || { echo "no app screens in $SCREENS: run the screen tests first" >&2; exit 1; }
mkdir -p "$WORK/renders" "$WORK/fonts"
cd "$WORK"
B="blender -b"
quiet() { "$@" > "$WORK/last.log" 2>&1 || { tail -20 "$WORK/last.log" >&2; exit 1; }; }

# Phone screens with their system bars.
python3 "$REPO/design/phone/statusbar.py" "$SCREENS/approval-whole-command.png" sb-awc.png 14:14
python3 "$REPO/design/phone/statusbar.py" "$SCREENS/table-question.png" sb-question.png 10:00
# the film's: the approval card's countdown as it ticks, and the screen once Allow is pressed
for n in 46 45; do python3 "$REPO/design/phone/statusbar.py" "$SCREENS/approval-whole-command-$n.png" "sb-awc-$n.png" 14:14; done
python3 "$REPO/design/phone/statusbar.py" "$SCREENS/approval-allowed.png" sb-allowed.png 14:14

# The phone, the boat, the person.
quiet $B -P "$REPO/design/phone/build_phone.py" -- "$WORK"
quiet $B -P "$REPO/design/ship/build_ship.py" -- "$WORK"
HOLD=one RES=2160,2160 LINE_SCALE=2 SIDE=2.3,0.05,-0.3 TRANSPARENT=1 \
  quiet $B -P "$REPO/design/people/pose_figure.py" -- "$BUNDLE" deckchair "$WORK/person" "$WORK/phone.blend" --style "$WORK/sb-question.png"

# Renders, each drawn for the size it is shown at. The person's chair is turned 30 degrees
# away from us on the deck in every view, so the canvas and the phone both show.
export FIGURE_TURN=-30
LINE_REF=1300 quiet $B phone.blend -P "$REPO/design/phone/render_flat.py" -- "$WORK/sb-awc.png" "$WORK/renders/phone-flat.png" 2600
for n in 46 45 allowed; do
  src="$WORK/sb-awc-$n.png"; [ "$n" = allowed ] && src="$WORK/sb-allowed.png"
  LINE_REF=1300 quiet $B phone.blend -P "$REPO/design/phone/render_flat.py" -- "$src" "$WORK/renders/phone-flat-$n.png" 2600
done
LINE_REF=1080 ASPECT=1.25 TRANSPARENT=1 MAP=1 \
  quiet $B ship.blend -P "$REPO/design/ship/render_style.py" -- cutaway-aft "$WORK/renders/aft.png" 2160 none "$WORK/person-figure.blend"
# at this distance the person, the tube's mouth and the porthole rims only knot into lines
HIDE=PortRim,SpeakingTube LINE_REF=864 TRANSPARENT=1 MAP=1 \
  quiet $B ship.blend -P "$REPO/design/ship/render_style.py" -- three-quarter "$WORK/renders/night.png" 2400 1,3
python3 "$REPO/design/ship/reflection.py" renders/night.png renders/night-reflection.png
DECK_SCALE=4.0 DECK_CENTRE=-5.35,1.85 LINE_REF=1080 ASPECT=1.25 TRANSPARENT=1 MAP=1 \
  quiet $B ship.blend -P "$REPO/design/ship/render_style.py" -- deck "$WORK/renders/deck.png" 2160 none "$WORK/person-figure.blend"

# The film's still: the cutaway without its needles and lights, which the film draws, and its
# map as a script (a page opened from disk may not fetch).
HIDE=Needle,Session LINE_REF=1080 ASPECT=1.25 TRANSPARENT=1 MAP=1 \
  quiet $B ship.blend -P "$REPO/design/ship/render_style.py" -- cutaway-aft "$WORK/renders/film-base.png" 2160 none "$WORK/person-figure.blend"
python3 -c "import json; open('renders/film-base.map.js', 'w').write('window.MAP = ' + json.dumps(json.load(open('renders/film-base.png.json'))) + ';')"

# The question card alone, for the deck ad's magnified detail.
python3 -c "from PIL import Image; im = Image.open('sb-question.png'); im.crop((0, 1360, im.width, im.height)).save('renders/question-card.png')"

# The layouts, captured at twice the size and brought down to the channel's.
cp "$REPO"/site/assets/fonts/*.woff2 fonts/
cp "$HERE"/*.html "$HERE"/ads.css "$HERE"/port.js .
for ad in engine-room:1080:1350 whole-command:1080:1350 deck:1080:1350 night:1200:675; do
  IFS=: read -r name w h <<< "$ad"
  SCALE=2 node "$HERE/capture.cjs" "$name.html" "$name@2x.png" "$w" "$h" > /dev/null
  python3 -c "from PIL import Image; Image.open('$name@2x.png').resize(($w, $h), Image.LANCZOS).save('$name.png')"
  echo "built $WORK/$name.png"
done

# The film: every frame drawn by the page's seek(t), then H.264 for feeds and VP9 for the site.
cp "$REPO/design/film/engine-room.html" film-engine-room.html
node "$REPO/design/film/capture.cjs" film-engine-room.html "$WORK/engine-room" 30 1080 1350
echo "built $WORK/engine-room.mp4"
