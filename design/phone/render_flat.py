"""The phone drawn the way the boat is: flat fills from the palette, lines in three weights,
square on - and the app's real screen, pixel for pixel, as the one picture in it.

    blender -b PHONE.blend -P design/phone/render_flat.py -- SCREEN.png OUT.png [HEIGHT]

PHONE.blend is from build_phone.py; SCREEN.png a screen with its system bars (statusbar.py).
The camera is orthographic and fits the phone to the frame's height, on a transparent ground.
LINE_REF (environment, default 1080) is the height the picture will be shown at, so the
weights come out at 3, 2 and 1 there.
"""
import math
import os
import sys

import bpy
from mathutils import Vector

argv = sys.argv[sys.argv.index("--") + 1:]
SCREEN, OUT = argv[0], argv[1]
HEIGHT = int(argv[2]) if len(argv) > 2 else 2700
scene = bpy.context.scene


def srgb(h):
    h = h.lstrip("#")
    return [(c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4) for c in (int(h[i:i + 2], 16) / 255 for i in (0, 2, 4))]


def flat(m, hexc=None, image=None):
    """Make a material its colour (or its image) whatever the light."""
    m.use_nodes = True
    nt = m.node_tree
    for n in list(nt.nodes):
        nt.nodes.remove(n)
    e = nt.nodes.new("ShaderNodeEmission")
    o = nt.nodes.new("ShaderNodeOutputMaterial")
    nt.links.new(e.outputs[0], o.inputs[0])
    if image:
        t = nt.nodes.new("ShaderNodeTexImage")
        t.image = bpy.data.images.load(image)
        t.interpolation = "Closest"          # the app's pixels, not a blur of them
        nt.links.new(t.outputs[0], e.inputs[0])
    else:
        e.inputs["Color"].default_value = (*srgb(hexc), 1)


# The palette: the frame is the house colour, the glass round the screen is the night, the
# camera hole and the grilles are the deepest dark. Only the screen carries anything else.
COLOURS = {"Frame": "#22463F", "FrontGlass": "#07100F", "Back": "#17352F", "Hole": "#050C0B", "Visor": "#17352F"}
for m in bpy.data.materials:
    if m.name == "Screen":
        flat(m, image=SCREEN)
    else:
        flat(m, COLOURS.get(m.name, "#17352F"))

# Seen square on, the speaker grille, the USB port and the mic in the bottom edge are only
# stray dashes along the foot: leave them out.
for o in scene.objects:
    if o.name.startswith(("Speaker", "USB", "Mic")):
        o.hide_render = True

root = next(o for o in scene.objects if o.parent is None and o.type == "EMPTY")
root.location = (0, 0, 0)
root.rotation_euler = (math.radians(90), 0, 0)      # upright, screen towards -Y
bpy.context.view_layer.update()
pts = [o.matrix_world @ Vector(c) for o in scene.objects if o.type == "MESH" and not o.hide_render for c in o.bound_box]
lo = Vector((min(p.x for p in pts), min(p.y for p in pts), min(p.z for p in pts)))
hi = Vector((max(p.x for p in pts), max(p.y for p in pts), max(p.z for p in pts)))
centre = (lo + hi) / 2
height = hi.z - lo.z

cam = bpy.data.cameras.new("Flat")
cam.type = "ORTHO"
cam.ortho_scale = height * 1.02
co = bpy.data.objects.new("Flat", cam)
scene.collection.objects.link(co)
co.location = centre + Vector((0, -1, 0))
co.rotation_euler = (Vector((0, 1, 0))).to_track_quat("-Z", "Z").to_euler()
scene.camera = co
width = (hi.x - lo.x) * 1.02

scene.render.engine = "BLENDER_EEVEE"
scene.view_settings.view_transform = "Standard"
scene.render.resolution_y = HEIGHT
scene.render.resolution_x = int(round(HEIGHT * width / (height * 1.02) / 2)) * 2
scene.render.film_transparent = True
scene.render.image_settings.color_mode = "RGBA"
scene.render.use_freestyle = True
scene.render.line_thickness_mode = "ABSOLUTE"
U = HEIGHT / float(os.environ.get("LINE_REF", "1080"))
vl = scene.view_layers[0]
vl.use_freestyle = True
fs = vl.freestyle_settings
fs.crease_angle = math.radians(140)
for ls in list(fs.linesets):
    fs.linesets.remove(ls)
for name, w, flags in (("Silhouette", 3.0, ("external_contour", "border")), ("Form", 2.0, ("silhouette", "contour", "crease")),
                       ("Detail", 1.0, ("material_boundary",))):
    ls = fs.linesets.new(name)
    ls.select_by_visibility = ls.select_by_edge_types = True
    for k in ("silhouette", "border", "crease", "contour", "external_contour", "material_boundary", "edge_mark"):
        setattr(ls, f"select_{k}", k in flags)
    st = bpy.data.linestyles.new(name)
    st.color = srgb("#E8F0EE")
    st.thickness = w * U
    st.caps = "ROUND"
    ls.linestyle = st
scene.render.filepath = OUT
bpy.ops.render.render(write_still=True)
print("rendered", OUT, scene.render.resolution_x, "x", scene.render.resolution_y)
