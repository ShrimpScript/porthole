"""
The phone on Porthole's site: a modern Android phone in the proportions of a 2025 flagship
(152.8 x 72.0 x 8.6 mm, flat frame, camera visor), with no maker's logo.

    blender -b -P design/phone/build_phone.py -- OUT_DIR [--render]

Writes OUT_DIR/phone.blend and OUT_DIR/phone.glb, and with --render a Cycles still,
OUT_DIR/phone-check.png, to judge the model by. Everything is built from numbers here, so
the model can be changed and rebuilt rather than hand-edited.

Built lying flat in millimetres (screen up, +Z), then stood up and scaled to metres, so
the glTF has the long side along +Y and the screen facing +Z - the way three.js looks at it.
"""
import math
import sys

import bmesh
import bpy
from mathutils import Matrix, Vector

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
OUT = argv[0] if argv else "/tmp"
RENDER = "--render" in argv

# Millimetres.
W, H, D = 72.0, 152.8, 8.6
CORNER = 10.5          # plan-view corner radius
EDGE = 0.9             # the soft edge where glass meets frame
SCREEN_W = 66.8        # 360:800, the app's screen
SCREEN_H = SCREEN_W * 800 / 360
SCREEN_R = 8.6

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
scene.unit_settings.system = "METRIC"


# ------------------------------------------------------------------ materials ---

def material(name, color, metallic=0.0, roughness=0.5, emission=None, coat=0.0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    bsdf = next(n for n in m.node_tree.nodes if n.type == "BSDF_PRINCIPLED")
    bsdf.inputs["Base Color"].default_value = (*color, 1.0)
    bsdf.inputs["Metallic"].default_value = metallic
    bsdf.inputs["Roughness"].default_value = roughness
    if coat and "Coat Weight" in bsdf.inputs:
        bsdf.inputs["Coat Weight"].default_value = coat
        bsdf.inputs["Coat Roughness"].default_value = 0.03
    if emission is not None:
        bsdf.inputs["Emission Color"].default_value = (*emission, 1.0)
        bsdf.inputs["Emission Strength"].default_value = 1.0
    # Nothing here is see-through: three.js renders transmission black (see the 3D notes).
    if "Transmission Weight" in bsdf.inputs:
        bsdf.inputs["Transmission Weight"].default_value = 0.0
    return m


def srgb(hexstr):
    """#rrggbb to linear RGB, as Blender's colour inputs expect."""
    out = []
    for i in (0, 2, 4):
        c = int(hexstr.lstrip("#")[i:i + 2], 16) / 255
        out.append(c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4)
    return tuple(out)


FRAME = material("Frame", srgb("#3a3d41"), metallic=1.0, roughness=0.32)
BACK = material("Back", srgb("#131417"), roughness=0.5)
FRONT = material("FrontGlass", srgb("#050606"), roughness=0.04, coat=1.0)
SCREEN = material("Screen", srgb("#000000"), roughness=0.08, emission=srgb("#000000"))
VISOR = material("Visor", srgb("#44474b"), metallic=1.0, roughness=0.22)
WINDOW = material("LensWindow", srgb("#060708"), roughness=0.05, coat=1.0)
RING = material("LensRing", srgb("#9aa0a6"), metallic=1.0, roughness=0.18)
LENS = material("LensGlass", srgb("#07090c"), roughness=0.02, coat=1.0)
IRIS = material("LensIris", srgb("#101820"), metallic=0.4, roughness=0.15)
FLASH = material("Flash", srgb("#d8d2c4"), roughness=0.35)
HOLE = material("Hole", srgb("#000000"), roughness=0.9)


# ------------------------------------------------------------------- shapes ---

def rounded_rect(w, h, r, seg=24):
    """The outline of a w x h rectangle with corners of radius r, anticlockwise."""
    pts = []
    corners = [(w / 2 - r, h / 2 - r, 0), (-w / 2 + r, h / 2 - r, 90),
               (-w / 2 + r, -h / 2 + r, 180), (w / 2 - r, -h / 2 + r, 270)]
    for cx, cy, start in corners:
        for i in range(seg + 1):
            a = math.radians(start + 90 * i / seg)
            pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def slab(name, w, h, r, z0, z1, edge=0.0, edge_seg=5, seg=24, mats=None, split=None):
    """A rounded-rectangle block from z0 to z1, its rims softened by edge."""
    bm = bmesh.new()
    bottom = [bm.verts.new((x, y, z0)) for x, y in rounded_rect(w, h, r, seg)]
    face = bm.faces.new(bottom)
    ext = bmesh.ops.extrude_face_region(bm, geom=[face])
    top = [v for v in ext["geom"] if isinstance(v, bmesh.types.BMVert)]
    for v in top:
        v.co.z = z1
    bm.normal_update()
    # Every face must point outwards; the base keeps the winding it was drawn with.
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces[:])
    bm.normal_update()
    if edge > 0:
        rim = [e for e in bm.edges if abs(e.verts[0].co.z - e.verts[1].co.z) < 1e-6
               and (abs(e.verts[0].co.z - z0) < 1e-6 or abs(e.verts[0].co.z - z1) < 1e-6)
               and len(e.link_faces) == 2
               and any(abs(f.normal.z) > 0.9 for f in e.link_faces)
               and any(abs(f.normal.z) < 0.1 for f in e.link_faces)]
        bmesh.ops.bevel(bm, geom=rim, offset=edge, segments=edge_seg, affect="EDGES", profile=0.5)
    mesh = bpy.data.meshes.new(name)
    bm.to_mesh(mesh)
    bm.free()
    obj = bpy.data.objects.new(name, mesh)
    scene.collection.objects.link(obj)
    for m in (mats or []):
        obj.data.materials.append(m)
    if split:
        for poly in obj.data.polygons:
            poly.material_index = split(poly)
    smooth(obj)
    return obj


def disc(name, radius, z, x=0.0, y=0.0, mat=None, seg=48, down=False):
    """A flat disc at height z, facing +Z, or -Z (out of the back) when down."""
    bm = bmesh.new()
    ring = [bm.verts.new((x + radius * math.cos(2 * math.pi * i / seg),
                          y + radius * math.sin(2 * math.pi * i / seg), z)) for i in range(seg)]
    f = bm.faces.new(ring)
    bm.normal_update()
    if (f.normal.z > 0) == down:
        bmesh.ops.reverse_faces(bm, faces=[f])
    bm.normal_update()
    mesh = bpy.data.meshes.new(name)
    bm.to_mesh(mesh)
    bm.free()
    obj = bpy.data.objects.new(name, mesh)
    scene.collection.objects.link(obj)
    if mat:
        obj.data.materials.append(mat)
    smooth(obj)
    return obj


def smooth(obj):
    # Flat caps, smooth sides: split normals by angle.
    with bpy.context.temp_override(active_object=obj, selected_editable_objects=[obj], object=obj):
        bpy.ops.object.shade_smooth_by_angle(angle=math.radians(35))


# --------------------------------------------------------------------- build ---

# Body: satin frame round the sides, glass front and back.
def body_split(poly):
    if poly.normal.z > 0.97:
        return 1  # front glass
    if poly.normal.z < -0.97:
        return 2  # back glass
    return 0      # frame


body = slab("Body", W, H, CORNER, -D / 2, D / 2, edge=EDGE, mats=[FRAME, FRONT, BACK], split=body_split)

# The display, a hair above the front glass; UVs map it 0..1 for the app's screens.
screen = slab("Screen", SCREEN_W, SCREEN_H, SCREEN_R, D / 2 + 0.01, D / 2 + 0.02, mats=[SCREEN])
uv = screen.data.uv_layers.new(name="UVMap")
for loop in screen.data.loops:
    co = screen.data.vertices[loop.vertex_index].co
    uv.data[loop.index].uv = ((co.x + SCREEN_W / 2) / SCREEN_W, (co.y + SCREEN_H / 2) / SCREEN_H)

# Front camera, punched through the display near the top.
disc("FrontCamera", 1.55, D / 2 + 0.03, y=SCREEN_H / 2 - 5.2, mat=HOLE, seg=40)

# The camera visor on the back: a raised pill, a black glass window, three lenses, a flash.
VIS_W, VIS_H, VIS_Y, VIS_D = 60.0, 19.5, H / 2 - 21.0, 2.1
visor = slab("Visor", VIS_W, VIS_H, VIS_H / 2 - 0.01, -D / 2 - VIS_D, -D / 2 + 0.2, edge=0.8, mats=[VISOR])
visor.location.y = VIS_Y
win = slab("LensWindow", 37.5, 14.2, 7.09, -D / 2 - VIS_D - 0.06, -D / 2 - VIS_D + 0.2, edge=0.35, mats=[WINDOW])
win.location = (-8.0, VIS_Y, 0)
# Each lens: a metal ring, the glass, the iris - stacked outwards from the window.
FACE = -D / 2 - VIS_D - 0.06          # the window's outer surface
for i, lx in enumerate((-20.4, -8.0, 4.4)):
    disc(f"LensRing{i}", 5.0, FACE - 0.02, x=lx, y=VIS_Y, mat=RING, seg=64, down=True)
    disc(f"Lens{i}", 4.35, FACE - 0.04, x=lx, y=VIS_Y, mat=LENS, seg=64, down=True)
    disc(f"LensIris{i}", 1.9, FACE - 0.06, x=lx, y=VIS_Y, mat=IRIS, seg=40, down=True)
# The flash and a microphone on the visor's metal, right of the window.
TOP = -D / 2 - VIS_D - 0.02
disc("Flash", 2.0, TOP, x=19.5, y=VIS_Y + 2.6, mat=FLASH, seg=40, down=True)
disc("Mic", 0.45, TOP, x=19.5, y=VIS_Y - 3.6, mat=HOLE, seg=16, down=True)

# Buttons on the right edge: power above the volume rocker.
for name, y, length in (("Power", 25.0, 11.0), ("Volume", 3.0, 22.0)):
    b = slab(name, 1.4, length, 0.69, -0.55, 0.55, mats=[FRAME], seg=10)
    b.rotation_euler.y = math.pi / 2
    b.location = (W / 2 + 0.25, y, 0)

# USB-C and speaker holes in the bottom edge.
usb = slab("USB", 8.6, 2.6, 1.29, -0.01, 0.4, mats=[HOLE], seg=10)
usb.rotation_euler.x = math.pi / 2
usb.location = (0, -H / 2 - 0.02, 0)
for i in range(6):
    s = disc(f"Speaker{i}", 0.5, 0, mat=HOLE, seg=12)
    s.rotation_euler.x = math.pi / 2
    s.location = (-12.5 - i * 1.6 if i < 3 else 9.3 + (i - 3) * 1.6, -H / 2 - 0.03, 0)

# Stand it up (long side +Z in Blender, which glTF makes +Y), screen facing -Y (glTF +Z),
# and go from millimetres to metres. One parent keeps the parts together in three.js.
root = bpy.data.objects.new("Phone", None)
scene.collection.objects.link(root)
for obj in list(scene.objects):
    if obj is root:
        continue
    obj.parent = root
root.rotation_euler.x = math.radians(90)
root.scale = (0.001, 0.001, 0.001)

bpy.ops.wm.save_as_mainfile(filepath=f"{OUT}/phone.blend")
bpy.ops.export_scene.gltf(filepath=f"{OUT}/phone.glb", export_format="GLB", export_apply=True,
                          export_yup=True)
print("wrote", f"{OUT}/phone.glb")

if RENDER:
    scene.render.engine = "CYCLES"
    scene.cycles.samples = 96
    scene.cycles.use_denoising = True
    try:
        prefs = bpy.context.preferences.addons["cycles"].preferences
        prefs.compute_device_type = "OPTIX"
        prefs.get_devices()
        for d in prefs.devices:
            d.use = True
        scene.cycles.device = "GPU"
    except Exception:
        pass
    scene.render.resolution_x, scene.render.resolution_y = 1400, 1000
    world = bpy.data.worlds.new("World")
    world.use_nodes = True
    world.node_tree.nodes["Background"].inputs[0].default_value = (0.012, 0.02, 0.02, 1)
    scene.world = world
    # Three-quarter looks at the back (the visor catching the key light) and the front,
    # with a rim light in Porthole's teal.
    cam = bpy.data.objects.new("Cam", bpy.data.cameras.new("Cam"))
    scene.collection.objects.link(cam)
    cam.data.lens = 70
    cam.location = (0.30, 0.45, 0.13)
    cam.rotation_euler = (Vector((0, 0, 0.0)) - cam.location).to_track_quat("-Z", "Y").to_euler()
    scene.camera = cam
    for name, loc, energy, color, size in (("Key", (0.25, 0.3, 0.45), 45, (1, 0.97, 0.92), 0.3),
                                            ("Rim", (-0.35, -0.2, 0.25), 90, (0.25, 0.83, 0.75), 0.15),
                                            ("Fill", (0.45, 0.2, -0.15), 10, (0.8, 0.85, 1), 0.5)):
        l = bpy.data.objects.new(name, bpy.data.lights.new(name, "AREA"))
        l.data.energy, l.data.color, l.data.size = energy, color, size
        l.location = loc
        l.rotation_euler = (Vector((0, 0, 0)) - l.location).to_track_quat("-Z", "Y").to_euler()
        scene.collection.objects.link(l)
    for view, zrot in (("back", 4), ("front", 184)):
        root.rotation_euler = (math.radians(90), 0, math.radians(zrot))
        scene.render.filepath = f"{OUT}/phone-{view}.png"
        bpy.ops.render.render(write_still=True)
        print("rendered", scene.render.filepath)
