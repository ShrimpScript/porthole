"""Judge the model's shape: side profile, plan and a three-quarter view, in Workbench
with outlines - fast, and honest about the form.
    blender -b SHIP.blend -P design/ship/check.py -- OUT_PREFIX
"""
import math
import sys

import bpy
from mathutils import Vector

OUT = sys.argv[sys.argv.index("--") + 1]
scene = bpy.context.scene
scene.render.engine = "BLENDER_WORKBENCH"
sh = scene.display.shading
sh.light = "STUDIO"
sh.color_type = "SINGLE"
sh.single_color = (0.75, 0.8, 0.8)
sh.show_object_outline = True
sh.show_cavity = True
scene.render.resolution_x, scene.render.resolution_y = 1400, 700
scene.render.film_transparent = False

cam = bpy.data.cameras.new("C")
co = bpy.data.objects.new("C", cam)
scene.collection.objects.link(co)
scene.camera = co


def shot(name, loc, ortho=None, lens=50, target=(0, 0, 0.6)):
    co.location = loc
    co.rotation_euler = (Vector(target) - Vector(loc)).to_track_quat("-Z", "Y").to_euler()
    if ortho:
        cam.type = "ORTHO"
        cam.ortho_scale = ortho
    else:
        cam.type = "PERSP"
        cam.lens = lens
    scene.render.filepath = f"{OUT}-{name}.png"
    bpy.ops.render.render(write_still=True)


shot("side", (0, -30, 0.6), ortho=14)
shot("plan", (0, 0, 30), ortho=14, target=(0, 0.0001, 0))
shot("three-quarter", (14, -14, 5), lens=45)
print("checked", OUT)
