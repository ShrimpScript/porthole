"""A person for the illustrations, posed on a mannequin: Blender Studio's stylized primitive
body (Human Base Meshes, (c) Blender Foundation, CC-BY 4.0), whose parts are parented joint
to joint, so a pose is a direction for each limb.

    blender -b -P design/people/pose_figure.py -- BUNDLE.blend POSE OUT_PREFIX [PHONE.blend] [--style SCREEN.png]

POSE names an entry in POSES below ("rest" to see the joints). With PHONE.blend the phone is
placed where the eyes look and the arms reach it (a two-bone solve, elbows down and out).
With --style the figure is dressed in the palette, drawn in three line weights with the app
screen on the phone, rendered to OUT_PREFIX-shoulder.png and saved as OUT_PREFIX-figure.blend
for the boat scenes (ship/render_style.py). SCREEN.png is a phone screen with its system bars
(phone/statusbar.py).

Environment: HOLD=one (the phone in the far hand, turned a little to the viewer, for side
views), SIDE="ortho_scale,dy,dz" (a flat side view) or CAM="dx,dy,dz,lens,aim_dz" (a camera
behind the shoulder), RES="w,h", LINE_SCALE (line weight multiplier), TRANSPARENT=1.
"""
import math
import os
import sys

import bpy
from mathutils import Euler, Matrix, Vector

argv = sys.argv[sys.argv.index("--") + 1:]
BUNDLE, POSE, OUT = argv[0], argv[1], argv[2]
COL = "Body Male - Primitve (Stylized)"

bpy.ops.wm.read_factory_settings(use_empty=True)
with bpy.data.libraries.load(BUNDLE, link=False) as (src, dst):
    dst.collections = [COL]
col = bpy.data.collections[COL]
bpy.context.scene.collection.children.link(col)
parts = {o.name.replace("GEO-", "").replace("_male_primitive_stylized", ""): o for o in col.all_objects}
root = parts["pelvis"]
root.location = (0, 0, root.location.z)

# A pose is a direction for each limb, in the world (the figure faces -Y, up is +Z): the
# direction from a part's joint to the next joint down the chain. Each part is turned about
# its own joint to point there, from the torso outwards, so the children follow.
def aim(part, child, direction):
    bpy.context.view_layer.update()
    p = part.matrix_world.translation.copy()
    c = child.matrix_world.translation.copy()
    q = (c - p).normalized().rotation_difference(Vector(direction).normalized())
    t = Matrix.Translation(p)
    part.matrix_world = t @ q.to_matrix().to_4x4() @ t.inverted() @ part.matrix_world


POSES = {
    "rest": [],
    # Reclined in a deck chair, phone held at the chest in both hands, looking down at it.
    "deckchair": [
        ("pelvis", "neck", (0, 0.62, 0.78)),               # the back of the chair, 38 degrees
        ("neck", "head", (0, 0.2, 0.98)),                  # head lifted off the chair to look down
        ("leg_upper.L", "leg_lower.L", (0.08, -0.97, 0.2)),
        ("leg_upper.R", "leg_lower.R", (-0.08, -0.97, 0.2)),
        ("leg_lower.L", "foot.L", (0.03, -0.5, -0.86)),
        ("leg_lower.R", "foot.R", (-0.03, -0.42, -0.9)),
        ("arm_upper.L", "arm_lower.L", (0.14, -0.5, -0.85)),  # elbows by the ribs
        ("arm_upper.R", "arm_lower.R", (-0.14, -0.5, -0.85)),
        ("arm_lower.L", "hand.L", (-0.3, -0.88, 0.37)),      # forearms forward, holding the phone's sides
        ("arm_lower.R", "hand.R", (0.3, -0.88, 0.37)),
        ("head", "nose", (0, -0.98, -0.18)),                 # looking down at it: the nose sits above the head's pivot, so ~40 degrees down is this
    ],
}
for name, child, direction in POSES.get(POSE, []):
    aim(parts[name], parts[child], direction)
bpy.context.view_layer.update()
# The mannequin's head is about a fifth of its height; the drawings keep people's heads at a
# seventh or less, so it shrinks about its pivot (the neck), taking the face with it.
HEAD = 0.8
parts["head"].scale = [v * HEAD for v in parts["head"].scale]
bpy.context.view_layer.update()

PHONE = argv[3] if len(argv) > 3 else ""


def world(name):
    return parts[name].matrix_world.translation.copy()


def rail(name, pts, radius, flat=None):
    """A tube through points; with flat=(width), a flat strip instead (the canvas)."""
    cu = bpy.data.curves.new(name, "CURVE")
    cu.dimensions = "3D"
    cu.bevel_depth = radius
    cu.bevel_resolution = 6            # 16 sides: smooth enough that no facet draws as a line
    sp = cu.splines.new("NURBS")
    sp.points.add(len(pts) - 1)
    for k, p in enumerate(pts):
        sp.points[k].co = (*p, 1)
    sp.use_endpoint_u = True
    sp.order_u = 3
    ob = bpy.data.objects.new(name, cu)
    bpy.context.scene.collection.objects.link(ob)
    if flat:
        cu.bevel_depth = 0
        cu.extrude = flat / 2          # a strip across X: the curve lies in the YZ plane
    return ob


def ribbon(name, pts, half_width, samples=40):
    """A strip across X through points in the YZ plane, smoothed (Catmull-Rom): the canvas."""
    import bmesh
    P = [pts[0]] + pts + [pts[-1]]
    line = []
    for i in range(1, len(P) - 2):
        for k in range(samples):
            t = k / samples
            p0, p1, p2, p3 = P[i - 1], P[i], P[i + 1], P[i + 2]
            line.append(0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t + (-p0 + 3 * p1 - 3 * p2 + p3) * t ** 3))
    line.append(pts[-1])
    bm = bmesh.new()
    cols = 7                                   # one column per stripe of the canvas
    rows = [[bm.verts.new((v.x - half_width + 2 * half_width * i / cols, v.y, v.z)) for i in range(cols + 1)] for v in line]
    for ra, rb in zip(rows, rows[1:]):
        for i in range(cols):
            bm.faces.new((ra[i], ra[i + 1], rb[i + 1], rb[i]))
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    ob = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(ob)
    return ob


def bar(name, a, b, r=0.02):
    return rail(name, [tuple(a), tuple((a + b) / 2), tuple(b)], r)


if POSE == "deckchair":
    # The classic folding deck chair, fitted to the body: two long rails from behind the head
    # to the front feet, two rear legs, crossbars, and a canvas sling that sags beneath.
    spine = (world("neck") - world("pelvis")).normalized()
    seat = world("pelvis") + Vector((0, 0.03, -0.15))
    knee = (world("leg_lower.L") + world("leg_lower.R")) / 2
    floor = min(world("foot.L").z, world("foot.R").z) - 0.05
    top = seat + spine * 1.02 + Vector((0, 0.16, 0))
    front = Vector((0, knee.y + 0.18, floor))
    W = 0.31
    for side in (-W, W):
        o = Vector((side + seat.x, 0, 0))
        T, F = top + o, front + o
        bar(f"ChairRail{side}", T, F, 0.024)
        pivot = T + (F - T) * 0.5
        bar(f"ChairRearLeg{side}", pivot + Vector((side * 0.08, 0, 0)), Vector((T.x + side * 0.08, T.y + 0.12, floor)), 0.022)
    x0 = seat.x
    def across(p, r=0.02):
        return bar(f"ChairBar{p.y:.2f}", Vector((x0 - W, p.y, p.z)), Vector((x0 + W, p.y, p.z)), r)
    across(top)
    front_bar = front + (top - front) * 0.3
    across(front_bar)
    sling = [top + Vector((0, -0.02, -0.02)), seat + spine * 0.5 + Vector((0, 0.12, -0.03)), seat + Vector((0, 0.04, -0.03)),
             front_bar + Vector((0, 0, -0.01))]
    ribbon("ChairCanvas", sling, W - 0.03)

    if PHONE:
        # The real phone, between the hands, turned to the eyes.
        with bpy.data.libraries.load(PHONE, link=False) as (src, dst):
            dst.objects = [n for n in src.objects]
        roots = []
        for ob in dst.objects:
            if ob is None:
                continue
            bpy.context.scene.collection.objects.link(ob)
            if ob.parent is None:
                roots.append(ob)
        ph = roots[0]
        # The phone goes where the eyes are looking, a reading distance away; the hands then
        # reach it, rather than the phone being hung wherever the hands happened to end.
        eye = (world("eye.L") + world("eye.R")) / 2
        gaze = Vector((0, -0.78, -0.62)).normalized()
        ph.location = eye + gaze * 0.34
        look = (eye - ph.location).normalized()
        ph.rotation_mode = "QUATERNION"
        ph.rotation_quaternion = look.to_track_quat("Z", "Y")   # screen (+Z as built) to the eyes, long side up
        one_hand = os.environ.get("HOLD") == "one"
        if one_hand:
            # Drawn from the side, a phone held square to the eyes is a sliver behind the near
            # hand. As an illustrator would, turn it a little toward the viewer (+X), so the
            # screen reads, and hold it in the far hand alone.
            from mathutils import Quaternion
            ph.rotation_quaternion = Quaternion(look.to_track_quat("Z", "Y") @ Vector((0, 1, 0)), math.radians(-48)) @ ph.rotation_quaternion
        bpy.context.view_layer.update()
        m = ph.matrix_world.to_3x3().normalized()
        across, up = m.col[0], m.col[1]
        if across.x < 0:                     # "across" towards the figure's left (+X), whichever way the model faces
            across = -across
        look = m.col[2]

        def reach(side, sgn):
            """Two-bone reach: the elbow found from the arm's own lengths, dropped down and out."""
            S, E0, H0 = world(f"arm_upper.{side}"), world(f"arm_lower.{side}"), world(f"hand.{side}")
            la, lb = (E0 - S).length, (H0 - E0).length
            T = ph.location + across * sgn * 0.05 - up * 0.035 - look * 0.012   # the phone's edge, low
            d = min((T - S).length, (la + lb) * 0.995)
            n = (T - S).normalized()
            cos_a = (la * la + d * d - lb * lb) / (2 * la * d)
            pole = Vector((sgn * 0.45, 0.15, -0.88))
            perp = (pole - n * pole.dot(n)).normalized()
            E = S + la * (cos_a * n + math.sqrt(max(0, 1 - cos_a * cos_a)) * perp)
            aim(parts[f"arm_upper.{side}"], parts[f"arm_lower.{side}"], E - S)
            aim(parts[f"arm_lower.{side}"], parts[f"hand.{side}"], T - world(f"arm_lower.{side}"))
            # the mitten lies along the phone's edge, fingers up its side
            tip = next((parts[k] for k in parts if k.startswith(f"finger_") and k.endswith(f".{side}")), None)
            if tip:
                aim(parts[f"hand.{side}"], tip, up * 0.9 - look * 0.3)
            # the thumb over the phone's face, pointing across it, not up in the air
            th = parts.get(f"thumb.{side}")
            if th:
                bpy.context.view_layer.update()
                j = th.matrix_world.translation.copy()
                far = max((th.matrix_world @ Vector(c) for c in th.bound_box), key=lambda v: (v - j).length)
                q = (far - j).normalized().rotation_difference((ph.location + look * 0.01 - j).normalized())
                t = Matrix.Translation(j)
                th.matrix_world = t @ q.to_matrix().to_4x4() @ t.inverted() @ th.matrix_world

        if one_hand:
            reach("R", -1)
            # the near arm rests: upper arm down the side, forearm along the thigh to the knee
            aim(parts["arm_upper.L"], parts["arm_lower.L"], (0.16, -0.2, -0.97))
            knee = world("leg_lower.L") + Vector((0.02, 0, 0.06))
            aim(parts["arm_lower.L"], parts["hand.L"], knee - world("arm_lower.L"))
            # and its thumb lies along the thigh with the hand, not up in the air
            bpy.context.view_layer.update()
            th = parts["thumb.L"]
            j = th.matrix_world.translation.copy()
            far = max((th.matrix_world @ Vector(c) for c in th.bound_box), key=lambda v: (v - j).length)
            q = (far - j).normalized().rotation_difference((knee - world("arm_lower.L")).normalized() + Vector((0, 0, -0.3)))
            t = Matrix.Translation(j)
            th.matrix_world = t @ q.to_matrix().to_4x4() @ t.inverted() @ th.matrix_world
        else:
            reach("L", 1)
            reach("R", -1)
    bpy.context.view_layer.update()

STYLE = "--style" in argv
SCREEN = next((a for a in argv if a.endswith(".png") and "sb-" in a), "")

scene = bpy.context.scene
if STYLE:
    # The illustration: flat fills from the palette, light line in three weights, and the
    # phone's screen the only picture in it - the real app.
    import math as _m
    import os

    def srgb(h):
        h = h.lstrip("#")
        c = [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
        return [x / 12.92 if x <= 0.04045 else ((x + 0.055) / 1.055) ** 2.4 for x in c]

    def flat(name, hexc):
        m = bpy.data.materials.new(name)
        m.use_nodes = True
        nt = m.node_tree
        for n in list(nt.nodes):
            nt.nodes.remove(n)
        e = nt.nodes.new("ShaderNodeEmission")
        e.inputs["Color"].default_value = (*srgb(hexc), 1)
        o = nt.nodes.new("ShaderNodeOutputMaterial")
        nt.links.new(e.outputs[0], o.inputs[0])
        return m

    chair, canvas_a, canvas_b, phone_body = flat("Chair", "#17352F"), flat("CanvasA", "#2B5A52"), flat("CanvasB", "#17352F"), flat("PhoneBody", "#0B1F1C")
    # Dressed by region, a duotone in the palette's teals: shirt, trousers, shoes, skin, hair.
    DRESS = {"Shirt": "#3A7268", "Trousers": "#17352F", "Shoes": "#0B1F1C", "Skin": "#8FB1A9", "Hair": "#0B1F1C"}
    dress = {k: flat(k, v) for k, v in DRESS.items()}

    def region(name):
        n = name.replace("GEO-", "")
        if any(k in n for k in ("pelvis", "leg_upper", "leg_lower")):
            return "Trousers"
        if any(k in n for k in ("foot", "toe")):
            return "Shoes"
        if any(k in n for k in ("chest", "belly", "shoulder", "arm_upper", "arm_lower", "neck")):   # a high collar
            return "Shirt"
        return "Skin"

    # Hair: a close cap over the top and back of the head - from behind, most of what reads.
    head = parts["head"]
    bpy.ops.mesh.primitive_uv_sphere_add(segments=32, ring_count=16, radius=1.0)
    hair = bpy.context.object
    hair.name = "Hair"
    hw = head.matrix_world
    hair.location = hw @ Vector((0, 0.045, 0.18))        # the head's pivot is at the neck
    # tipped back 20 degrees, so it comes down to the nape behind and stays off the brow
    from mathutils import Quaternion
    hair.rotation_mode = "QUATERNION"
    hair.rotation_quaternion = hw.to_quaternion() @ Quaternion((1, 0, 0), math.radians(-20))
    hair.scale = (0.12 * HEAD, 0.15 * HEAD, 0.14 * HEAD)
    # Hands as mitten and thumb, feet as shoes: the separate fingers and toes go, so nothing
    # can be miscounted and the shapes read at any size.
    # No face: the eyes, lids, nose and ears go, so a head is a head-shaped shape, as in the
    # references (Mahé, Blair), and no expression can be wrong.
    for n in list(parts):
        if n.startswith(("finger_", "toe_", "eye", "nose", "ear")):
            bpy.data.objects.remove(parts.pop(n), do_unlink=True)
    for ob in scene.objects:
        if ob.type not in ("MESH", "CURVE"):
            continue
        ob.data.materials.clear()
        if ob.name.startswith("GEO-"):
            ob.data.materials.append(dress[region(ob.name)])
        elif ob.name == "Hair":
            ob.data.materials.append(dress["Hair"])
        elif ob.name == "ChairCanvas":
            ob.data.materials.append(canvas_a)
            ob.data.materials.append(canvas_b)
            xs = [poly.center.x for poly in ob.data.polygons]
            lo, hi = min(xs), max(xs)
            for poly in ob.data.polygons:        # stripes down the sling, as a deck chair has
                poly.material_index = round((poly.center.x - lo) / ((hi - lo) / 6)) % 2
            # printed stripes, not seams: Freestyle face marks keep lines off the joins between them
            fm = ob.data.attributes.get("freestyle_face") or ob.data.attributes.new("freestyle_face", "BOOLEAN", "FACE")
            fm.data.foreach_set("value", [True] * len(ob.data.polygons))
        elif ob.name.startswith("Chair"):
            ob.data.materials.append(chair)
        elif ob.name == "Screen" and SCREEN:
            m = bpy.data.materials.new("AppScreen")
            m.use_nodes = True
            nt = m.node_tree
            for n in list(nt.nodes):
                nt.nodes.remove(n)
            tx = nt.nodes.new("ShaderNodeTexImage")
            tx.image = bpy.data.images.load(SCREEN)
            em = nt.nodes.new("ShaderNodeEmission")
            o = nt.nodes.new("ShaderNodeOutputMaterial")
            nt.links.new(tx.outputs[0], em.inputs[0])
            nt.links.new(em.outputs[0], o.inputs[0])
            ob.data.materials.append(m)
        else:
            ob.data.materials.append(phone_body)
    target = world("hand.L") * 0.5 + world("hand.R") * 0.5 + Vector((0, 0, 0.08))   # before the body is merged
    screen = next((o for o in scene.objects if o.name == "Screen"), None)
    if screen:
        target = screen.matrix_world.translation.copy()
    body = [o for o in scene.objects if o.name.startswith("GEO-")]
    for o in scene.objects:
        o.select_set(False)
    for o in body:
        o.select_set(True)
    bpy.context.view_layer.objects.active = body[0]
    bpy.ops.object.join()
    night = bpy.data.worlds.new("Night")
    night.use_nodes = True
    next(n for n in night.node_tree.nodes if n.type == "BACKGROUND").inputs[0].default_value = (*srgb("#07100F"), 1)
    scene.world = night
    scene.render.engine = "BLENDER_EEVEE"
    scene.view_settings.view_transform = "Standard"
    scene.render.resolution_x, scene.render.resolution_y = (int(v) for v in os.environ.get("RES", "1600,1600").split(","))
    scene.render.use_freestyle = True
    scene.render.line_thickness_mode = "ABSOLUTE"
    fs = scene.view_layers[0].freestyle_settings
    scene.view_layers[0].use_freestyle = True
    fs.crease_angle = _m.radians(140)
    for ls in list(fs.linesets):
        fs.linesets.remove(ls)
    for name, w, flags in (("Silhouette", 3.0, ("external_contour", "border")), ("Form", 2.0, ("silhouette", "contour", "crease")), ("Detail", 1.0, ("material_boundary",))):
        ls = fs.linesets.new(name)
        ls.select_by_visibility = ls.select_by_edge_types = True
        for k in ("silhouette", "border", "crease", "contour", "external_contour", "material_boundary", "edge_mark"):
            setattr(ls, f"select_{k}", k in flags)
        st = bpy.data.linestyles.new(name)
        st.color = srgb("#E8F0EE")
        st.thickness = w * float(os.environ.get("LINE_SCALE", "1"))
        st.caps = "ROUND"
        ls.linestyle = st
    cam = bpy.data.cameras.new("Shoulder")
    # Behind the right shoulder and above it, far enough back that the head is a shape in the
    # corner rather than a wall; CAM="dx,dy,dz,lens,aim_dz" moves it for a new frame.
    dx, dy, dz, lens, aim_dz = (float(v) for v in os.environ.get("CAM", "-0.55,0.95,0.75,55,-0.05").split(","))
    cam.lens = lens
    co = bpy.data.objects.new("Shoulder", cam)
    scene.collection.objects.link(co)
    scene.camera = co
    co.location = target + Vector((dx, dy, dz))
    co.rotation_euler = (target + Vector((0, 0, aim_dz)) - co.location).to_track_quat("-Z", "Y").to_euler()
    if os.environ.get("SIDE"):
        # Flat side view, as the ship's section is drawn: SIDE="ortho_scale,dy,dz" frames it.
        scale, sy, sz = (float(v) for v in os.environ["SIDE"].split(","))
        cam.type = "ORTHO"
        cam.ortho_scale = scale
        centre = target + Vector((0, sy, sz))
        co.location = centre + Vector((5, 0, 0))
        co.rotation_euler = (centre - co.location).to_track_quat("-Z", "Y").to_euler()
    if os.environ.get("TRANSPARENT"):
        scene.render.film_transparent = True
        scene.render.image_settings.color_mode = "RGBA"
    # Kept for the scenes: the figure, its chair and phone, posed and dressed.
    bpy.ops.wm.save_as_mainfile(filepath=f"{OUT}-figure.blend")
    scene.render.filepath = f"{OUT}-shoulder.png"
    bpy.ops.render.render(write_still=True)
    print("styled", scene.render.filepath)
    raise SystemExit
scene.render.engine = "BLENDER_WORKBENCH"
scene.display.shading.light = "STUDIO"
scene.display.shading.color_type = "SINGLE"
scene.display.shading.show_object_outline = True
scene.render.resolution_x, scene.render.resolution_y = 700, 900
cam = bpy.data.cameras.new("C")
cam.type = "ORTHO"
cam.ortho_scale = 2.2
co = bpy.data.objects.new("C", cam)
scene.collection.objects.link(co)
scene.camera = co
centre = Vector((0, 0, 0.9))
for view, loc in {"front": (0, -6, 0.9), "side": (6, 0, 0.9), "rear34": (-4, 4, 1.8)}.items():
    co.location = loc
    co.rotation_euler = (centre - Vector(loc)).to_track_quat("-Z", "Y").to_euler()
    scene.render.filepath = f"{OUT}-{view}.png"
    bpy.ops.render.render(write_still=True)
phones = [o for o in bpy.data.objects if o.parent is None and o.type == "EMPTY"]
print("PHONE", [(o.name, tuple(round(v, 3) for v in o.matrix_world.translation), tuple(round(v, 3) for v in o.scale)) for o in phones])
kids = [o for o in bpy.data.objects if o.parent and o.parent.name == "Phone"]
print("KIDS", len(kids), [(o.name, o.type, o.hide_render, o.name in bpy.context.scene.objects) for o in kids][:4])
print("HANDS", tuple(round(v, 3) for v in world("hand.L")), tuple(round(v, 3) for v in world("hand.R")))
print("posed", POSE, [n for n in sorted(parts) if not any(k in n for k in ("finger", "toe", "eye", "nose", "ear"))])
