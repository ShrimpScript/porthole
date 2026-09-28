"""Porthole's boat: one master model for every still and every frame of motion, so the
ship never changes between pictures. A small motor vessel of about 12 m - a boat of your
own, not a liner - with a wheelhouse forward, an aft deck, a row of portholes, one exhaust
stack, and an engine room below, cut open on the centreline for the cutaway.

    blender -b -P design/ship/build_ship.py -- OUT_DIR

Writes OUT_DIR/ship.blend. Units are metres; +X is forward, +Z up, the centreline is Y=0.
Everything is built from the numbers below, so it is changed here and rebuilt.
"""
import math
import sys

import bmesh
import bpy

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
OUT = argv[0] if argv else "/tmp"

# The lines plan, in metres.
LOA = 12.0          # length overall
BEAM = 3.8          # at the widest deck
DRAFT = 1.0         # keel below the waterline
FREEBOARD = 1.15    # waterline to deck at the lowest point of the sheer
SHEER_BOW = 0.7     # the deck rises this much at the bow...
SHEER_STERN = 0.22  # ...and a little at the stern
STEM_RAKE = 0.9     # the stem leans forward this far between keel and deck
TRANSOM = 0.35      # the transom leans aft this far
STATIONS = 48
SECTION_PTS = 14

bpy.ops.wm.read_factory_settings(use_empty=True)


def smooth01(t):
    t = min(1.0, max(0.0, t))
    return t * t * (3 - 2 * t)


def pchip(points):
    """A smooth curve through (u, v) control points that never overshoots them (Fritsch-Carlson)."""
    xs, ys = [p[0] for p in points], [p[1] for p in points]
    n = len(xs)
    d = [(ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i]) for i in range(n - 1)]
    m = [d[0]] + [0.0 if d[i - 1] * d[i] <= 0 else 2 / (1 / d[i - 1] + 1 / d[i]) for i in range(1, n - 1)] + [d[-1]]

    def f(u):
        u = min(xs[-1], max(xs[0], u))
        i = max(k for k in range(n - 1) if xs[k] <= u) if u < xs[-1] else n - 2
        h = xs[i + 1] - xs[i]
        t = (u - xs[i]) / h
        return (ys[i] * (2 * t ** 3 - 3 * t ** 2 + 1) + h * m[i] * (t ** 3 - 2 * t ** 2 + t)
                + ys[i + 1] * (-2 * t ** 3 + 3 * t ** 2) + h * m[i + 1] * (t ** 3 - t ** 2))
    return f


# Deck half-breadth as a fraction of the beam, u = 0 at the transom, 1 at the stem: a full
# stern, widest just aft of amidships, a fine entry.
BREADTH = pchip([(0.0, 0.84), (0.12, 0.95), (0.38, 1.0), (0.6, 0.94), (0.78, 0.74), (0.9, 0.46), (0.97, 0.17), (1.0, 0.0)])
# Deck height above the waterline: a springy sheer, lowest just aft of amidships.
SHEER = pchip([(0.0, FREEBOARD + SHEER_STERN), (0.42, FREEBOARD), (0.75, FREEBOARD + SHEER_BOW * 0.35), (1.0, FREEBOARD + SHEER_BOW)])
# Keel below the waterline: level aft, sweeping up into the stem.
KEEL = pchip([(0.0, -DRAFT * 0.82), (0.25, -DRAFT), (0.62, -DRAFT), (0.86, -DRAFT * 0.72), (1.0, -DRAFT * 0.06)])


def half_breadth(u):
    return BEAM / 2 * max(0.0, BREADTH(u))


def sheer(u):
    return SHEER(u)


def keel(u):
    return KEEL(u)


def x_at(u, z):
    """Fore-and-aft position of a point: the stem rakes forward and the transom aft with height,
    each blended in over the last part of the hull so no section jumps."""
    x = -LOA / 2 + LOA * u
    top, bot = sheer(u), keel(u)
    h = (z - bot) / max(1e-6, top - bot)
    x += STEM_RAKE * h * smooth01((u - 0.82) / 0.18)
    x -= TRANSOM * h * (1 - smooth01(u / 0.08))
    return x


def section(u):
    """One transverse section, deck edge to keel on the starboard side: a round bilge."""
    hb, top, bot = half_breadth(u), sheer(u), keel(u)
    pts = []
    for j in range(SECTION_PTS + 1):
        a = j / SECTION_PTS                                     # 0 at the deck edge, 1 at the keel
        # a superellipse: near-vertical topsides, a rounded bilge, a vee at the keel
        th = a * math.pi / 2
        y = hb * math.cos(th) ** 0.55
        z = top - (top - bot) * math.sin(th) ** 1.6
        pts.append((y, z))
    return pts


def hull():
    bm = bmesh.new()
    grid = []
    for i in range(STATIONS + 1):
        u = i / STATIONS
        ring = []
        sec = section(u)
        # starboard, keel, port: one continuous section so the hull is closed at the keel
        for y, z in sec:
            ring.append(bm.verts.new((x_at(u, z), -y, z)))
        for y, z in reversed(sec[:-1]):
            ring.append(bm.verts.new((x_at(u, z), y, z)))
        grid.append(ring)
    n = len(grid[0])
    for i in range(STATIONS):
        for j in range(n - 1):
            bm.faces.new((grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1]))
    bm.faces.new(grid[0])                                       # the transom
    deck = [grid[i][0] for i in range(STATIONS + 1)] + [grid[i][n - 1] for i in reversed(range(STATIONS + 1))]
    bm.faces.new(deck)                                          # the deck, flat across the sheer
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    me = bpy.data.meshes.new("Hull")
    bm.to_mesh(me)
    ob = bpy.data.objects.new("Hull", me)
    bpy.context.collection.objects.link(ob)
    for p in me.polygons:
        p.use_smooth = True
    return ob


# ------------------------------------------------------------------ palette ---
# Five colours and the accent, flat. The accent marks only what is live: a lit porthole,
# a running session, the phone's screen.
PALETTE = {
    "ground": "#07100F",   # the night, and anything in shadow
    "deep": "#0E2522",     # the water, the hull below the boot-top
    "hull": "#17352F",     # topsides
    "house": "#C9D6D2",    # the wheelhouse and saloon, painted
    "paper": "#E8F0EE",    # trim, rails, and the line
    "accent": "#3FD4C0",   # live
    "brass": "#8C7A55",    # porthole rims: the only warm note, dim
}


def srgb(h):
    h = h.lstrip("#")
    c = [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    return [x / 12.92 if x <= 0.04045 else ((x + 0.055) / 1.055) ** 2.4 for x in c] + [1.0]


def flat(name, key):
    """A material that is exactly its colour: the illustration's flat fills."""
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    nt = m.node_tree
    for n in list(nt.nodes):
        nt.nodes.remove(n)
    e = nt.nodes.new("ShaderNodeEmission")
    e.inputs["Color"].default_value = srgb(PALETTE[key])
    o = nt.nodes.new("ShaderNodeOutputMaterial")
    nt.links.new(e.outputs[0], o.inputs[0])
    m.diffuse_color = srgb(PALETTE[key])
    return m


M = {k: flat(k.capitalize(), k) for k in PALETTE}


def box(name, x0, x1, y0, y1, z0, z1, mat, bevel=0.0, parent=None):
    bpy.ops.mesh.primitive_cube_add(location=((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2))
    ob = bpy.context.object
    ob.name = name
    ob.scale = ((x1 - x0) / 2, (y1 - y0) / 2, (z1 - z0) / 2)
    bpy.ops.object.transform_apply(scale=True)
    ob.data.materials.append(M[mat])
    if bevel:
        b = ob.modifiers.new("Bevel", "BEVEL")
        b.width, b.segments, b.limit_method = bevel, 3, "ANGLE"
    if parent:
        ob.parent = parent
    return ob


def raked_house(name, x0, x1, half_w, z0, z1, front_rake, back_rake, mat, bevel=0.04):
    """A deckhouse with raked ends: the front leans aft, the way a wheelhouse does."""
    bm = bmesh.new()
    pts = [(x0 + back_rake, z1), (x1 - front_rake, z1), (x1, z0), (x0, z0)]
    for sgn in (-1, 1):
        for (x, z) in pts:
            bm.verts.new((x, sgn * half_w, z))
    bm.verts.ensure_lookup_table()
    v = bm.verts
    faces = [(0, 1, 2, 3), (7, 6, 5, 4), (0, 4, 5, 1), (1, 5, 6, 2), (2, 6, 7, 3), (3, 7, 4, 0)]
    for f in faces:
        bm.faces.new([v[i] for i in f])
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    ob = bpy.data.objects.new(name, me)
    bpy.context.collection.objects.link(ob)
    ob.data.materials.append(M[mat])
    if bevel:
        b = ob.modifiers.new("Bevel", "BEVEL")
        b.width, b.segments, b.limit_method = bevel, 3, "ANGLE"
    return ob


def cyl(name, x, y, z, r, depth, mat, axis="Y", verts=48):
    rot = {"X": (0, math.pi / 2, 0), "Y": (math.pi / 2, 0, 0), "Z": (0, 0, 0)}[axis]
    bpy.ops.mesh.primitive_cylinder_add(vertices=verts, radius=r, depth=depth, location=(x, y, z), rotation=rot)
    ob = bpy.context.object
    ob.name = name
    ob.data.materials.append(M[mat])
    return ob


def deck_z(x):
    """The sheer height at a fore-and-aft position (inverse of the station spacing)."""
    return sheer(min(1, max(0, (x + LOA / 2) / LOA)))


h = hull()
h.data.materials.append(M["hull"])

# The boot-top: the hull is cut through at a line just above the water, so the colour change
# is one clean line, then everything below it is the deep colour.
BOOT_TOP = 0.0
bm = bmesh.new()
bm.from_mesh(h.data)
bmesh.ops.bisect_plane(bm, geom=bm.verts[:] + bm.edges[:] + bm.faces[:], plane_co=(0, 0, BOOT_TOP), plane_no=(0, 0, 1))
bm.to_mesh(h.data)
bm.free()
h.data.materials.append(M["deep"])
for poly in h.data.polygons:
    cz = sum(h.data.vertices[i].co.z for i in poly.vertices) / len(poly.vertices)
    poly.material_index = 1 if cz < BOOT_TOP else 0

# A rubbing strake along the topsides, a hand below the deck edge: the classic line of a boat.
for sgn in (-1, 1):
    pts = []
    for i in range(1, STATIONS):
        u = i / STATIONS
        z = sheer(u) - 0.16
        y = half_breadth(u) * 1.01 + 0.04
        pts.append((x_at(u, z), sgn * y, z))
    cu = bpy.data.curves.new(f"Strake{sgn}", "CURVE")
    cu.dimensions = "3D"
    cu.bevel_depth = 0.045
    cu.bevel_resolution = 3
    sp = cu.splines.new("POLY")
    sp.points.add(len(pts) - 1)
    for k, p in enumerate(pts):
        sp.points[k].co = (*p, 1)
    ob = bpy.data.objects.new(f"Strake{sgn}", cu)
    bpy.context.collection.objects.link(ob)
    ob.data.materials.append(M["paper"])

# Deckhouses: the wheelhouse forward, raked; the saloon behind it, lower; the aft deck open.
WH0, WH1 = 0.6, 3.3
raked_house("Wheelhouse", WH0, WH1, 1.2, deck_z(2.0) - 0.05, deck_z(2.0) + 2.0, 0.45, 0.0, "house")
box("WheelhouseRoof", WH0 - 0.15, WH1 - 0.3, -1.35, 1.35, deck_z(2.0) + 2.0, deck_z(2.0) + 2.12, "paper", 0.03)
SAL0, SAL1 = -2.9, WH0
box("Saloon", SAL0, SAL1, -1.3, 1.3, deck_z(-1.0) - 0.05, deck_z(-1.0) + 1.25, "house", 0.05)
box("SaloonRoof", SAL0 - 0.1, SAL1, -1.42, 1.42, deck_z(-1.0) + 1.25, deck_z(-1.0) + 1.35, "paper", 0.03)

# Wheelhouse windows: three forward panes on the raked front, two each side.
wz = deck_z(2.0) + 1.25
for k, y in enumerate((-0.72, 0.0, 0.72)):
    box(f"FrontPane{k}", WH1 - 0.23, WH1 - 0.19, y - 0.3, y + 0.3, wz - 0.3, wz + 0.3, "ground")
for sgn in (-1, 1):
    for k, x in enumerate((1.35, 2.45)):
        box(f"SidePane{sgn}{k}", x - 0.42, x + 0.42, sgn * 1.2 - 0.02, sgn * 1.2 + 0.02, wz - 0.32, wz + 0.3, "ground")
    # the saloon's windows: long and low
    box(f"SaloonPane{sgn}", -2.4, -0.85, sgn * 1.3 - 0.02, sgn * 1.3 + 0.02, deck_z(-1) + 0.5, deck_z(-1) + 0.95, "ground")

# Portholes along the hull: brass rims, dark glass - lit ones become the accent in a scene.
PORTHOLES = [-4.6, -3.7, 1.6, 2.5, 3.4]
for sgn in (-1, 1):
    for k, x in enumerate(PORTHOLES):
        u = (x + LOA / 2) / LOA
        z = sheer(u) - 0.55
        y = half_breadth(u) * 0.985
        # shallow, so from an angle the rim's side is not a second heavy line beside its face
        rim = cyl(f"PortRim{sgn}{k}", x, sgn * y, z, 0.17, 0.025, "brass")
        glass = cyl(f"PortGlass{sgn}{k}", x, sgn * (y + 0.006), z, 0.12, 0.02, "ground")

# The exhaust stack, raked aft, behind the wheelhouse; the mast on the wheelhouse roof.
# upright: a raked funnel met the flat roof in a kinked joint that read as two parts
st = cyl("Stack", -0.1, 0, deck_z(-1) + 1.35 + 0.5, 0.26, 1.1, "paper", axis="Z")
st.scale = (1.3, 0.85, 1)
top = cyl("StackTop", -0.1, 0, deck_z(-1) + 1.35 + 1.0, 0.265, 0.16, "ground", axis="Z")
top.scale = (1.3, 0.85, 1)
cyl("Mast", 1.7, 0, deck_z(2.0) + 2.12 + 0.9, 0.04, 1.8, "paper", axis="Z", verts=16)
cyl("MastLight", 1.7, 0, deck_z(2.0) + 2.12 + 1.83, 0.07, 0.1, "paper", axis="Z", verts=16)

# The aft deck's rail: stanchions and a top rail.
for sgn in (-1, 1):
    for x in [-5.6 + i * 0.7 for i in range(5)]:
        u = (x + LOA / 2) / LOA
        cyl(f"Stanchion{sgn}{x:.1f}", x, sgn * half_breadth(u) * 0.95, deck_z(x) + 0.4, 0.022, 0.8, "paper", axis="Z", verts=12)
    pts = []
    for x in [-5.75 + i * 0.1 for i in range(30)]:
        u = (x + LOA / 2) / LOA
        pts.append((x, sgn * half_breadth(u) * 0.95, deck_z(x) + 0.8))
    cu = bpy.data.curves.new(f"Rail{sgn}", "CURVE")
    cu.dimensions = "3D"
    cu.bevel_depth = 0.03
    sp = cu.splines.new("POLY")
    sp.points.add(len(pts) - 1)
    for k, p in enumerate(pts):
        sp.points[k].co = (*p, 1)
    ob = bpy.data.objects.new(f"Rail{sgn}", cu)
    bpy.context.collection.objects.link(ob)
    ob.data.materials.append(M["paper"])



def rounded(pts, r, n=8):
    """Points along a path whose corners are bent round, as a pipe would be, not mitred."""
    def lerp(a, b, t):
        return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))

    def dist(a, b):
        return math.dist(a, b)

    out = [pts[0]]
    for a, b, c in zip(pts, pts[1:], pts[2:]):
        rr = min(r, dist(a, b) / 2, dist(b, c) / 2)
        p1, p2 = lerp(b, a, rr / dist(a, b)), lerp(b, c, rr / dist(b, c))
        for k in range(n + 1):
            t = k / n
            out.append(lerp(lerp(p1, b, t), lerp(b, p2, t), t))
    out.append(pts[-1])
    return out


def tube(name, pts, radius, mat):
    """A pipe, rail or cable through points."""
    cu = bpy.data.curves.new(name, "CURVE")
    cu.dimensions = "3D"
    cu.bevel_depth = radius
    cu.bevel_resolution = 3
    cu.use_fill_caps = True
    sp = cu.splines.new("POLY")
    sp.points.add(len(pts) - 1)
    for k, p in enumerate(pts):
        sp.points[k].co = (*p, 1)
    ob = bpy.data.objects.new(name, cu)
    bpy.context.collection.objects.link(ob)
    ob.data.materials.append(M[mat])
    return ob


# ------------------------------------------------------------ deck details ---
# The bow rail (pulpit), round the foredeck.
bow = []
for k in range(15):
    u = 0.76 + 0.235 * k / 14
    bow.append((x_at(u, sheer(u)), -half_breadth(u) * 0.88, sheer(u) + 0.62))
for k in reversed(range(15)):
    u = 0.76 + 0.235 * k / 14
    bow.append((x_at(u, sheer(u)), half_breadth(u) * 0.88, sheer(u) + 0.62))
tube("BowRail", bow, 0.028, "paper")
for u in (0.8, 0.88, 0.95):
    for sgn in (-1, 1):
        x, y, z = x_at(u, sheer(u)), sgn * half_breadth(u) * 0.88, sheer(u)
        tube(f"BowStanchion{u}{sgn}", [(x, y, z), (x, y, z + 0.62)], 0.02, "paper")

# A lifebuoy on the wheelhouse side: a ring, the one shape Porthole already owns.
bpy.ops.mesh.primitive_torus_add(major_radius=0.3, minor_radius=0.075, major_segments=48, minor_segments=12,
                                 location=(1.9, -1.24, deck_z(2.0) + 0.55), rotation=(math.pi / 2, 0, 0))
buoy = bpy.context.object
buoy.name = "Lifebuoy"
buoy.data.materials.append(M["paper"])

# The searchlight on the wheelhouse roof, and a door in the wheelhouse side.
cyl("Searchlight", 2.6, 0, deck_z(2.0) + 2.12 + 0.16, 0.13, 0.22, "paper", axis="X", verts=24)
box("WheelhouseDoor", 0.75, 1.3, -1.225, -1.2, deck_z(2.0), deck_z(2.0) + 1.75, "house")

# An ensign at the stern on a short staff.
sx = -LOA / 2 - 0.1
tube("EnsignStaff", [(sx, 0, deck_z(-5.9)), (sx - 0.15, 0, deck_z(-5.9) + 1.3)], 0.025, "paper")
bpy.ops.mesh.primitive_plane_add(size=1, location=(sx - 0.35, 0, deck_z(-5.9) + 1.1))   # hoisted to the staff
flag = bpy.context.object
flag.name = "Ensign"
flag.scale = (0.45, 0.28, 1)        # the plane lies in XY; turned upright, its Y is the flag's height
flag.rotation_euler = (math.pi / 2, 0, 0)
flag.data.materials.append(M["house"])

# Aft deck planking: planks with hairline gaps, drawn by their edges.
for k in range(9):
    y = -1.45 + k * 0.36
    box(f"Plank{k}", -5.9, -3.0, y, y + 0.34, deck_z(-4.5) + 0.0, deck_z(-4.5) + 0.02, "house")

# -------------------------------------------------------------- engine room ---
# Below the saloon: the computer is the ship's engine room. Three engines, one per running
# session, each with a gauge and a status light; their uptakes join one exhaust to the stack;
# a ladder up through the hatch (the way in when all else fails); and a speaking tube from
# the engines straight up to the aft deck (the phone's line to them, with nothing of ours in
# between).
ER0, ER1 = -3.05, 0.65            # the cutaway's ends: the floors run to the cut
SOLE = -0.55
FLOOR = deck_z(-1.0) - 0.05       # the saloon's floor, level with its walls' foot
_before = set(bpy.data.objects)
box("EngineSole", ER0, ER1, -1.25, 1.25, SOLE - 0.05, SOLE, "deep")
box("SaloonSole", ER0, -0.45, -1.25, 1.25, FLOOR - 0.05, FLOOR, "deep")
box("SaloonSoleFwd", 0.12, ER1, -1.25, 1.25, FLOOR - 0.05, FLOOR, "deep")
ENGINES = [-2.2 + k * 0.6 for k in range(3)]
for k, x0 in enumerate(ENGINES):
    box(f"Engine{k}", x0, x0 + 0.5, -0.45, 0.45, SOLE, SOLE + 0.78, "house", 0.04)
    cyl(f"Gauge{k}", x0 + 0.25, -0.465, SOLE + 0.55, 0.1, 0.02, "ground", verts=32)
    a = math.radians(40 + 50 * k)                                  # each needle somewhere else
    tube(f"Needle{k}", [(x0 + 0.25, -0.48, SOLE + 0.55), (x0 + 0.25 + 0.075 * math.cos(a), -0.48, SOLE + 0.55 + 0.075 * math.sin(a))], 0.008, "paper")
    box(f"Session{k}", x0 + 0.12, x0 + 0.38, -0.47, -0.455, SOLE + 0.14, SOLE + 0.3, "ground")
    tube(f"Uptake{k}", [(x0 + 0.25, 0.12, SOLE + 0.78), (x0 + 0.25, 0.12, SOLE + 1.02)], 0.05, "paper")
EX_X = -0.68
tube("Exhaust", rounded([(ENGINES[0] + 0.12, 0.12, SOLE + 1.02), (EX_X, 0.12, SOLE + 1.02), (EX_X, 0.12, FLOOR + 1.02),
                         (-0.1, 0.12, FLOOR + 1.12), (-0.1, 0.12, FLOOR + 1.45)], 0.18), 0.07, "paper")
# the ladder, facing the viewer: two rails either side, rungs across, leaning back to the hatch
LAD = (-0.36, 0.0)
for x in LAD:
    tube(f"LadderSide{x}", [(x, 0.5, SOLE), (x, 0.08, FLOOR)], 0.022, "paper")
for r in range(6):
    t = (r + 0.6) / 6.6
    y, z = 0.5 - 0.42 * t, SOLE + (FLOOR - SOLE) * t
    tube(f"Rung{r}", [(LAD[0], y, z), (LAD[1], y, z)], 0.016, "paper")
# the speaking tube: a bell mouth facing the engines, up the aft bulkhead, aft under the deck,
# and up through it beside the chair, where it turns over into a second mouth facing aft
ST_Y, ST_X = -0.45, -3.3
ST_TOP = deck_z(ST_X) + 0.85
st_pts = rounded([(-2.52, ST_Y, SOLE + 0.6), (-2.72, ST_Y, SOLE + 0.6), (-2.72, ST_Y, FLOOR - 0.3),
                  (ST_X, ST_Y, FLOOR - 0.3), (ST_X, ST_Y, ST_TOP), (ST_X - 0.2, ST_Y, ST_TOP)], 0.16)
tube("SpeakingTube", st_pts, 0.045, "paper")
bpy.ops.mesh.primitive_cone_add(vertices=32, radius1=0.13, radius2=0.045, depth=0.2,
                                location=(ST_X - 0.3, ST_Y, ST_TOP), rotation=(0, math.pi / 2, 0))
mouth = bpy.context.object
mouth.name = "SpeakingTubeMouth"
mouth.data.materials.append(M["paper"])
bpy.ops.mesh.primitive_cone_add(vertices=32, radius1=0.11, radius2=0.045, depth=0.16,
                                location=(-2.44, ST_Y, SOLE + 0.6), rotation=(0, -math.pi / 2, 0))
low = bpy.context.object
low.name = "SpeakingTubeLow"
low.data.materials.append(M["paper"])

# Everything from the engine room on lives in its own collection: shown in the cutaway,
# hidden outside, where it would only show through the hull under the water.
inside = bpy.data.collections.new("EngineRoom")
bpy.context.scene.collection.children.link(inside)
for ob in set(bpy.data.objects) - _before:
    for c in list(ob.users_collection):
        c.objects.unlink(ob)
    inside.objects.link(ob)
# the speaking tube's top half and its bell mouth are on deck, seen from outside too
for name in ("SpeakingTube", "SpeakingTubeMouth"):
    bpy.context.scene.collection.objects.link(bpy.data.objects[name])

bpy.ops.wm.save_as_mainfile(filepath=f"{OUT}/ship.blend")
print("saved", f"{OUT}/ship.blend", "objects", len(bpy.data.objects))
