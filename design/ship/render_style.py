"""The boat as an illustration: flat fills from the palette, lines in three weights
(silhouette 3, form 2, detail 1 per LINE_REF px of width), teal only on what is live.

    blender -b SHIP.blend -P design/ship/render_style.py -- VIEW OUT.png [WIDTH] [LIT] [FIGURE.blend]

VIEW: "side" (orthographic elevation), "cutaway" (the same, opened over the engine room),
"cutaway-aft" (a portrait crop of it: deck chair, speaking tube, engine), "deck" (close on
the aft deck, flat from the side), "stern", or "three-quarter" (a long lens from low on the
water). LIT lists porthole numbers to light, comma-separated ("all", or "none").
FIGURE is a posed person saved by people/pose_figure.py, set in the chair on the aft deck.

Environment: LINE_REF (the width the line weights are drawn for, default 1600: set it to the
width the picture will be shown at), ASPECT (height / width, default 0.5625), TRANSPARENT=1
(no sky, for compositing), MAP=1 (also writes OUT.png.json: where the engine, the speaking
tube, the person, the lit portholes, the waterline and the horizon land, in pixels, for
callouts and drawn water), DECK_SCALE and DECK_CENTRE="x,z" (framing for "deck"), HIDE="Name,Prefix" (parts to leave
out), FIGURE_TURN (degrees the chair is turned on the deck).
"""
import math
import os
import sys

import bpy
from mathutils import Vector

argv = sys.argv[sys.argv.index("--") + 1:]
VIEW, OUT = argv[0], argv[1]
WIDTH = int(argv[2]) if len(argv) > 2 else 1600
LIT = argv[3] if len(argv) > 3 else ""
FIGURE = argv[4] if len(argv) > 4 else ""        # the posed person, chair and phone (people/pose_figure.py)
scene = bpy.context.scene
U = WIDTH / float(os.environ.get("LINE_REF", "1600"))   # one line unit: a pixel at LINE_REF wide


def srgb(h):
    h = h.lstrip("#")
    c = [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    return [x / 12.92 if x <= 0.04045 else ((x + 0.055) / 1.055) ** 2.4 for x in c]


def set_flat(mat_name, hexc):
    m = bpy.data.materials.get(mat_name)
    if m:
        e = next(n for n in m.node_tree.nodes if n.type == "EMISSION")
        e.inputs["Color"].default_value = (*srgb(hexc), 1)


# Engraving at night: dark fills, light line. The painted house is a shade lighter than the
# hull so the two read apart; windows are the night itself.
set_flat("Hull", "#17352F")
set_flat("House", "#22463F")
set_flat("Deep", "#0B1D1A")       # below the waterline the hull is the sea's colour, drawn by its lines
set_flat("Paper", "#22463F")      # trim and rails are drawn by their lines, filled like the house
set_flat("Brass", "#17352F")
set_flat("Ground", "#07100F")

# Light chosen portholes: the accent, as a solid disc - no glow.
lit = bpy.data.materials.new("Lit")
lit.use_nodes = True
e = next(n for n in lit.node_tree.nodes if n.type == "BSDF_PRINCIPLED")
lit.node_tree.nodes.remove(e)
em = lit.node_tree.nodes.new("ShaderNodeEmission")
em.inputs["Color"].default_value = (*srgb("#3FD4C0"), 1)
out = next(n for n in lit.node_tree.nodes if n.type == "OUTPUT_MATERIAL")
lit.node_tree.links.new(em.outputs[0], out.inputs[0])
for ob in scene.objects:
    if ob.name.startswith("PortGlass") and (LIT == "all" or ob.name[-1:] in LIT.split(",")):
        ob.data.materials[0] = lit
# The speaking tube is the live line from the deck to the engine: the accent, in every view.
for name in ("SpeakingTube", "SpeakingTubeMouth", "SpeakingTubeLow"):
    ob = bpy.data.objects.get(name)
    if ob:
        ob.data.materials[0] = lit

# Inside is lighter than outside: in the cutaway the far walls of the hull and saloon are
# seen from within, and a lit room reads as a room. Backfaces take the lighter colour.
def inside_lighter(mat_name, inside_hex):
    m = bpy.data.materials.get(mat_name)
    if not m:
        return
    nt = m.node_tree
    em_out = next(n for n in nt.nodes if n.type == "EMISSION")
    inner = nt.nodes.new("ShaderNodeEmission")
    inner.inputs["Color"].default_value = (*srgb(inside_hex), 1)
    geo = nt.nodes.new("ShaderNodeNewGeometry")
    mix = nt.nodes.new("ShaderNodeMixShader")
    out_n = next(n for n in nt.nodes if n.type == "OUTPUT_MATERIAL")
    nt.links.new(geo.outputs["Backfacing"], mix.inputs[0])
    nt.links.new(em_out.outputs[0], mix.inputs[1])
    nt.links.new(inner.outputs[0], mix.inputs[2])
    nt.links.new(mix.outputs[0], out_n.inputs[0])


if VIEW.startswith("cutaway"):
    # one colour for every inside face: the cut reads as one room, not three near-alike tones
    inside_lighter("Hull", "#2B5A52")
    inside_lighter("Deep", "#2B5A52")
    inside_lighter("House", "#2B5A52")

# The three sessions on the engine: two running, one asking - the app's own colours for those.
amber = bpy.data.materials.new("Asking")
amber.use_nodes = True
ab = next(n for n in amber.node_tree.nodes if n.type == "BSDF_PRINCIPLED")
amber.node_tree.nodes.remove(ab)
ae = amber.node_tree.nodes.new("ShaderNodeEmission")
ae.inputs["Color"].default_value = (*srgb("#E7B84A"), 1)
amber.node_tree.links.new(ae.outputs[0], next(n for n in amber.node_tree.nodes if n.type == "OUTPUT_MATERIAL").inputs[0])
for k in range(3):
    ob = bpy.data.objects.get(f"Session{k}")
    if ob:
        ob.data.materials[0] = amber if k == 2 else lit

# Water: a plane at the waterline, the deep colour, so the hull sits in it.
bpy.ops.mesh.primitive_plane_add(size=200, location=(0, 0, 0.0))
water = bpy.context.object
wm = bpy.data.materials.new("Water")
wm.use_nodes = True
nt = wm.node_tree
for n in list(nt.nodes):
    nt.nodes.remove(n)
we = nt.nodes.new("ShaderNodeEmission")
we.inputs["Color"].default_value = (*srgb("#0B1D1A"), 1)
wo = nt.nodes.new("ShaderNodeOutputMaterial")
nt.links.new(we.outputs[0], wo.inputs[0])
water.data.materials.append(wm)

# On the water, the hull ends at the waterline: cut away what is below it, so its edge is the
# drawn waterline and nothing under the surface leaks through. The section keeps it all.
if VIEW not in ("side", "cutaway", "cutaway-aft"):
    import bmesh
    hull = bpy.data.objects["Hull"]
    bm = bmesh.new()
    bm.from_mesh(hull.data)
    bmesh.ops.bisect_plane(bm, geom=bm.verts[:] + bm.edges[:] + bm.faces[:], plane_co=(0, 0, 0.0), plane_no=(0, 0, 1), clear_inner=True)
    bm.to_mesh(hull.data)
    bm.free()
    water.hide_render = True        # the sea is the world colour below the horizon, drawn in 2D later
    er = bpy.data.collections.get("EngineRoom")
    if er:
        for ob in er.objects:
            if ob.name not in ("SpeakingTube", "SpeakingTubeMouth"):
                ob.hide_render = True

if FIGURE:
    # The person on the aft deck: the chair's feet on the planking, facing astern.
    with bpy.data.libraries.load(FIGURE, link=False) as (src, dst):
        dst.objects = [n for n in src.objects]
    rig = bpy.data.objects.new("Aboard", None)
    scene.collection.objects.link(rig)
    brought = [o for o in dst.objects if o is not None and o.type in ("MESH", "CURVE", "EMPTY")]
    # its own collection, so its lines can be drawn lighter than the boat's: at this scale
    # a person under the boat's weights is all outline
    figure = bpy.data.collections.new("Figure")
    scene.collection.children.link(figure)
    for o in brought:
        figure.objects.link(o)
    # the chair's feet are the floor; anything else in the file can hang lower
    lowest = min((o.matrix_world @ Vector(c)).z for o in brought if o.name.startswith("Chair") for c in o.bound_box)
    for o in brought:
        if o.parent is None:
            o.parent = rig
            o.location.z -= lowest
    import math as _m
    # facing astern; FIGURE_TURN degrees more turns the chair on the deck (negative: away from us)
    rig.rotation_euler = (0, 0, _m.radians(-90 + float(os.environ.get("FIGURE_TURN", "0"))))
    deck_x = -4.7
    import bmesh as _b
    rig.location = (deck_x, 0.25, 0)
    bpy.context.view_layer.update()
    # stand it on the planks: the deck's height there, from the planking boxes
    planks = [o for o in scene.objects if o.name.startswith("Plank")]
    top = max((o.matrix_world @ Vector(c)).z for o in planks for c in o.bound_box) if planks else 1.3
    # Measured, not assumed: however the file's own offsets compose, the chair's feet end on the planks.
    bpy.context.view_layer.update()
    feet = min((o.matrix_world @ Vector(c)).z for o in brought if o.name.startswith("Chair") for c in o.bound_box)
    rig.location.z += top - feet

# HIDE="Name,Prefix,...": parts to leave out of this picture (e.g. porthole rims at a distance)
for pre in [h for h in os.environ.get("HIDE", "").split(",") if h]:
    for ob in scene.objects:
        if ob.name.startswith(pre):
            ob.hide_render = True

cam = bpy.data.cameras.new("Cam")
co = bpy.data.objects.new("Cam", cam)
scene.collection.objects.link(co)
scene.camera = co
if VIEW.startswith("cutaway"):
    # The near side of the hull and saloon opened over the engine room: sliced at the room's
    # ends and the near faces between removed, with no cap, so the far wall's inside shows.
    import bmesh
    X0, X1 = -3.05, 0.65
    for name in ("Hull", "Saloon", "Strake-1"):
        ob = bpy.data.objects.get(name)
        if ob is None:
            continue
        if ob.type == "CURVE":
            bpy.context.view_layer.objects.active = ob
            for o in scene.objects:
                o.select_set(False)
            ob.select_set(True)
            bpy.ops.object.convert(target="MESH")
        for m in list(ob.modifiers):                      # bake the bevel before cutting
            bpy.context.view_layer.objects.active = ob
            bpy.ops.object.modifier_apply(modifier=m.name)
        bm = bmesh.new()
        bm.from_mesh(ob.data)
        for x in (X0, X1):
            bmesh.ops.bisect_plane(bm, geom=bm.verts[:] + bm.edges[:] + bm.faces[:], plane_co=(x, 0, 0), plane_no=(1, 0, 0))
        mw = ob.matrix_world
        doomed = [f for f in bm.faces
                  if X0 < (mw @ f.calc_center_median()).x < X1 and (mw @ f.calc_center_median()).y < -0.01]
        bmesh.ops.delete(bm, geom=doomed, context="FACES")
        # inside the cut the far wall is one surface: no colour change, so no waterline drawn
        # across the room (the sea stays outside the hull)
        for f in bm.faces:
            if X0 < (mw @ f.calc_center_median()).x < X1:
                f.material_index = 0
        bm.to_mesh(ob.data)
        bm.free()
    for ob in scene.objects:                      # what stood on the removed half goes too
        if ob.name == "SaloonPane1":              # the far window made the open room read as a wall
            ob.hide_render = True
        if ob.name.startswith(("PortRim-1", "PortGlass-1", "SaloonPane-1", "Fender", "FenderLine")):
            x = ob.location.x
            if -3.1 < x < 0.7:
                ob.hide_render = True
    # the near aft rail stands between the viewer and the person: an illustrator leaves it out
    for ob in scene.objects:
        if ob.name.startswith(("Rail-1", "Stanchion-1")):
            ob.hide_render = True
    cam.type = "ORTHO"
    if VIEW == "cutaway-aft":        # a portrait crop: the deck chair, the tube and the engine
        cam.ortho_scale = 10.6
        co.location, target = (-2.85, -40, 1.3), (-2.85, 0, 1.3)
    else:
        cam.ortho_scale = 14.5
        co.location, target = (0, -40, 1.2), (0, 0, 1.2)
    water.hide_render = True
elif VIEW == "deck":
    # Close on the aft deck, flat from the side like the section: the person in the chair, the
    # speaking tube's mouth beside them, the stern and the sea. The near rail is left out.
    for ob in scene.objects:
        if ob.name.startswith(("Rail-1", "Stanchion-1", "Ensign")):   # the flag and staff only crowd the detail here
            ob.hide_render = True
    cam.type = "ORTHO"
    cam.ortho_scale = float(os.environ.get("DECK_SCALE", "5.0"))
    cx, cz = (float(v) for v in os.environ.get("DECK_CENTRE", "-4.55,1.35").split(","))
    co.location, target = (cx, -40, cz), (cx, 0, cz)
elif VIEW == "side":
    cam.type = "ORTHO"
    cam.ortho_scale = 14.5
    co.location, target = (0, -40, 1.6), (0, 0, 1.6)
    water.hide_render = True        # a section: the waterline is drawn, not a plane
elif VIEW == "stern":
    cam.lens = 70
    co.location, target = (-17, -9, 3.4), (-3.5, 0, 1.6)
else:
    cam.lens = 85
    co.location, target = (22, -30, 3.0), (0.3, 0, 1.3)
co.rotation_euler = (Vector(target) - Vector(co.location)).to_track_quat("-Z", "Y").to_euler()

world = bpy.data.worlds.new("Night")
world.use_nodes = True
next(n for n in world.node_tree.nodes if n.type == "BACKGROUND").inputs[0].default_value = (*srgb("#07100F"), 1)
scene.world = world

scene.render.engine = "BLENDER_EEVEE"
scene.view_settings.view_transform = "Standard"
scene.render.resolution_x = WIDTH
scene.render.resolution_y = int(WIDTH * float(os.environ.get("ASPECT", "0.5625")))
scene.render.use_freestyle = True
scene.render.line_thickness_mode = "ABSOLUTE"

vl = scene.view_layers[0]
vl.use_freestyle = True
fs = vl.freestyle_settings
fs.crease_angle = math.radians(140)
for ls in list(fs.linesets):
    fs.linesets.remove(ls)


def lineset(name, weight, **flags):
    ls = fs.linesets.new(name)
    ls.select_by_visibility = True
    ls.select_by_edge_types = True
    for k in ("silhouette", "border", "crease", "contour", "external_contour", "material_boundary", "edge_mark"):
        setattr(ls, f"select_{k}", flags.get(k, False))
    st = bpy.data.linestyles.new(name)
    st.color = srgb("#E8F0EE")
    st.thickness = weight * U
    st.caps = "ROUND"
    ls.linestyle = st
    return ls


# Three weights, each 1.5x the next or more: the outline, the form, the detail.
boat = [lineset("Silhouette", 3.0, external_contour=True, border=True),
        lineset("Form", 2.0, silhouette=True, contour=True, crease=True),
        lineset("Detail", 1.0, material_boundary=True)]
fig = bpy.data.collections.get("Figure")
if fig:
    # The person one step down: outline at the form weight, the clothes' edges at the detail
    # weight, and no creases, which on a small body only scribble.
    for ls in boat:
        ls.select_by_collection = True
        ls.collection = fig
        ls.collection_negation = "EXCLUSIVE"
    for ls in (lineset("FigureOutline", 2.0, external_contour=True, silhouette=True, border=True),
               lineset("FigureDetail", 1.0, material_boundary=True)):
        ls.select_by_collection = True
        ls.collection = fig
        ls.collection_negation = "INCLUSIVE"
    # the canvas's stripes are marked faces: printed colour, so no boundary line between them
    ls.select_by_face_marks = True
    ls.face_mark_negation = "EXCLUSIVE"
    ls.face_mark_condition = "ONE"

if os.environ.get("TRANSPARENT"):
    # for compositing: the boat alone, the sky and sea drawn around it as vector layers
    scene.render.film_transparent = True
    scene.render.image_settings.color_mode = "RGBA"
if os.environ.get("MAP"):
    # where things land on the picture, for the callouts: world points to pixels
    from bpy_extras.object_utils import world_to_camera_view
    import json
    bpy.context.view_layer.update()
    marks = {}

    def centre(ob):
        pts = [ob.matrix_world @ Vector(c) for c in ob.bound_box]
        return sum(pts, Vector()) / len(pts)

    for name in ("Engine1", "SpeakingTubeMouth", "SpeakingTube", "SpeakingTubeLow", "Wheelhouse", "PortGlass-11", "Session2", "Stack", "Screen"):
        ob = bpy.data.objects.get(name)
        if ob:
            p = world_to_camera_view(scene, co, centre(ob))
            marks[name] = [p.x * scene.render.resolution_x, (1 - p.y) * scene.render.resolution_y]
    if FIGURE:
        p = world_to_camera_view(scene, co, rig.matrix_world.translation + Vector((0, 0, 0.9)))
        marks["Person"] = [p.x * scene.render.resolution_x, (1 - p.y) * scene.render.resolution_y]
    w = world_to_camera_view(scene, co, Vector((0, -2, 0)))
    marks["Waterline"] = [w.x * scene.render.resolution_x, (1 - w.y) * scene.render.resolution_y]

    def px(v):
        q = world_to_camera_view(scene, co, v)
        return [q.x * scene.render.resolution_x, (1 - q.y) * scene.render.resolution_y]

    # the horizon: sea level far off along the camera's heading
    ahead = (co.matrix_world.to_quaternion() @ Vector((0, 0, -1)))
    ahead.z = 0
    marks["Horizon"] = px(co.location + ahead.normalized() * 1e5 - Vector((0, 0, co.location.z)))
    # each lit porthole, and the point on the water under it, where its reflection starts
    for ob in scene.objects:
        if ob.name.startswith("PortGlass") and ob.data.materials[0].name == "Lit" and not ob.hide_render:
            c = centre(ob)
            marks["Lit " + ob.name] = {"at": px(c), "water": px(Vector((c.x, c.y, 0)))}
    open(OUT + ".json", "w").write(json.dumps(marks))
scene.render.filepath = OUT
bpy.ops.render.render(write_still=True)
print("rendered", OUT)
