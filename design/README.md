# Design

Models, illustrations and ads for Porthole, built headless from scripts. Nothing here ships in the app.

- `phone/`: the phone model (`build_phone.py`, also used for the site's 3D opening), system bars for the app's screen renders (`statusbar.py`), product shots (`render_shots.py`) and a flat line drawing of the phone (`render_flat.py`).
- `ship/`: the boat, built from its lines (`build_ship.py`), and drawn as flat fills with lines in three weights (`render_style.py`), with water reflections (`reflection.py`).
- `people/`: a person posed in a deck chair, holding the phone (`pose_figure.py`).
- `ads/`: the ad layouts (HTML), their shared styles, and `build.sh`, which rebuilds the whole set.
- `film/`: the engine room as a 20-second film (`engine-room.html`, captured by `capture.cjs`), and `left-running/`, a 60-second story film in three.js with its own score and sound, built by its own `build.sh` (see its README and TREATMENT).

## Rebuilding the ads

```sh
(cd app-android && TZ=UTC ./gradlew :app:testDebugUnitTest --tests '*RenderScreensTest*')
design/ads/build.sh WORK_DIR path/to/human_base_meshes_bundle.blend
```

This needs Blender, Python 3 with Pillow and numpy, and Node with playwright-core and Chrome.

## Credits

- **The person:** posed on the stylized primitive body from Blender Studio's Human Base Meshes, © Blender Foundation, CC-BY 4.0 (studio.blender.org). The bundle is not in this repo. Pictures that include the figure need that credit wherever they are published.
- **Fonts:** Schibsted Grotesk and Iosevka Term, both under the SIL Open Font License (see `site/assets/fonts`).
