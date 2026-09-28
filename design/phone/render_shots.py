"""Studio shots of the phone for ads and the store: the model from build_phone.py with one of
the app's real screens on it, lit like a product photograph, on a transparent ground.

    blender -b PHONE.blend -P design/phone/render_shots.py -- SCREEN.png VIEW OUT.png [SIZE]

VIEW is "front" (square on, for reading the screen), "hero" (three-quarter, turned left),
"hero-r" (turned right), "tilt" (leaning back, as if held) or "window" (a few degrees off
square, for a close crop where the screen must still read). The screen is emitted, not lit,
so it reads as the app does on a real phone, and never takes a colour cast from the lights.
"""
import math
import sys

import bpy
from mathutils import Vector

argv = sys.argv[sys.argv.index("--") + 1:]
SCREEN, VIEW, OUT = argv[0], argv[1], argv[2]
SIZE = int(argv[3]) if len(argv) > 3 else 2400
scene = bpy.context.scene

# The real screen, as emission: the app's own pixels.
img = bpy.data.images.load(SCREEN)
scr = next(m for m in bpy.data.materials if m.name == "Screen")
nt = scr.node_tree
for n in list(nt.nodes):
    nt.nodes.remove(n)
tex = nt.nodes.new("ShaderNodeTexImage")
tex.image = img
tex.interpolation = "Cubic"
emit = nt.nodes.new("ShaderNodeEmission")
emit.inputs["Strength"].default_value = 1.0
glossy = nt.nodes.new("ShaderNodeBsdfGlossy")        # the glass over it still catches a light
glossy.inputs["Roughness"].default_value = 0.12
fres = nt.nodes.new("ShaderNodeFresnel")
fres.inputs["IOR"].default_value = 1.5
mix = nt.nodes.new("ShaderNodeMixShader")
out = nt.nodes.new("ShaderNodeOutputMaterial")
nt.links.new(tex.outputs["Color"], emit.inputs["Color"])
# A hint of glass, not a mirror: at a raking angle a full Fresnel turns the key light into
# a white sheet over the app.
hint = nt.nodes.new("ShaderNodeMath")
hint.operation = "MULTIPLY"
hint.inputs[1].default_value = 0.0  # pure image: at any raking angle a light's mirror image swamps a dark app
nt.links.new(fres.outputs[0], hint.inputs[0])
nt.links.new(hint.outputs[0], mix.inputs[0])
nt.links.new(emit.outputs[0], mix.inputs[1])
nt.links.new(glossy.outputs[0], mix.inputs[2])
nt.links.new(mix.outputs[0], out.inputs[0])

# The phone: find its root and stand it on the origin.
root = next(o for o in scene.objects if o.parent is None and o.type == "EMPTY")
root.location = (0, 0, 0)
root.rotation_euler = (math.radians(90), 0, 0)   # glTF-up as built: long side +Y, screen +Z

VIEWS = {
    #          phone rotation (deg)       camera position        lens
    "front":  ((90, 0, 0),               (0, -0.62, 0.0),       85),
    "hero":   ((90, 0, -24),             (0.06, -0.60, 0.05),   85),
    "hero-r": ((90, 0, 24),              (-0.06, -0.60, 0.05),  85),
    "tilt":   ((62, 0, -14),             (0.02, -0.58, 0.16),   85),
    "window": ((84, 0, -9),              (0.03, -0.60, 0.04),   85),   # seen through a porthole: turned just enough to be an object
}
rot, cam_loc, lens = VIEWS[VIEW]
# The screen faces +Z before the root's own turn; turn the whole phone to face the camera.
root.rotation_euler = tuple(math.radians(a) for a in rot)
bpy.context.view_layer.update()

cam = bpy.data.cameras.new("ShotCam")
cam.lens = lens
co = bpy.data.objects.new("ShotCam", cam)
scene.collection.objects.link(co)
co.location = cam_loc
direction = Vector((0, 0, 0)) - co.location
co.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()
scene.camera = co

for o in [o for o in scene.objects if o.type == "LIGHT"]:
    bpy.data.objects.remove(o, do_unlink=True)


def area(name, loc, size, power, color=(1, 1, 1), shape="RECTANGLE", size_y=None):
    l = bpy.data.lights.new(name, "AREA")
    l.shape = shape
    l.size = size
    if size_y:
        l.size_y = size_y
    l.energy = power
    l.color = color
    o = bpy.data.objects.new(name, l)
    scene.collection.objects.link(o)
    o.location = loc
    o.rotation_euler = (Vector((0, 0, 0)) - Vector(loc)).to_track_quat("-Z", "Y").to_euler()
    return o


# Lights: a big soft key high left; two strip rims behind, so both long edges of the frame
# read against a dark ground; nothing from below, which only puts a hot line on the base.
area("Key", (-0.5, -0.45, 0.5), 0.9, 70)
area("RimR", (0.45, 0.2, 0.15), 0.04, 45, (0.78, 1.0, 0.96), size_y=0.8)
area("RimL", (-0.45, 0.25, 0.1), 0.04, 30, (1.0, 0.97, 0.92), size_y=0.8)

world = bpy.data.worlds.get("World") or bpy.data.worlds.new("World")
world.use_nodes = True
bg = next(n for n in world.node_tree.nodes if n.type == "BACKGROUND")
bg.inputs["Color"].default_value = (0.004, 0.012, 0.011, 1)
bg.inputs["Strength"].default_value = 1.0
scene.world = world

scene.render.engine = "CYCLES"
scene.cycles.samples = 160
scene.cycles.use_denoising = True
# Tiles keep a large still inside a few GB of RAM (a 3600 px frame in one piece peaks near 5 GB,
# enough for a busy machine's OOM killer to take it).
scene.cycles.use_auto_tile = True
scene.cycles.tile_size = 1024
try:
    prefs = bpy.context.preferences.addons["cycles"].preferences
    prefs.compute_device_type = "CUDA"
    prefs.get_devices()
    for d in prefs.devices:
        d.use = True
    scene.cycles.device = "GPU"
except Exception:
    pass
scene.render.film_transparent = True
scene.render.resolution_x = int(SIZE * 0.62)
scene.render.resolution_y = SIZE
scene.render.image_settings.file_format = "PNG"
scene.render.image_settings.color_mode = "RGBA"
scene.view_settings.view_transform = "Standard"   # the screen's pixels stay the app's colours
scene.render.filepath = OUT
bpy.ops.render.render(write_still=True)
print("rendered", OUT)
