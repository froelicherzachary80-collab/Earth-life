#!/usr/bin/env python3
"""
ZMF Cinema v0.1 assets (all original artwork, no brands). Run after cinema_assets.py:

  python3 tools/assets/zmf_assets.py fabric-mod/src/main/resources [--hd-size 128]

Writes:
  * detailed block models: cinema recliner and luxury recliner (stitched leather, padded cushions, sculpted back,
    armrests with cup holders, seat-number plate, metal frame, footrest that folds out when reclined), 4DX motion seat
    (seat shell, mechanical base, pivot assembly, motion platform, foot support, haptic indicator strip, airflow and
    mist nozzles), lounge sofa, candy display, snack shelf, projector, cinema speaker, 4DX effect module;
  * 16 px base textures, blockstates, item models, the Cinema Locator icon, English names and key names;
  * the optional built-in resource pack "ZMF Cinema HD" (resourcepacks/zmf_hd): the same textures at --hd-size pixels
    (default 128; the generator can make 1024-4096 px, but packs that large make the mod jar huge for little visible
    gain on Minecraft-sized blocks) with LabPBR normal (_n) and specular (_s: smoothness, metalness/F0, emission) maps,
    which shader packs that support LabPBR (e.g. through Iris) use for reflections and relief. Without shaders the HD
    pack just gives sharper textures.

Pure Python (zlib + struct).
"""
import json, math, os, random, struct, sys, zlib

ROOT = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("--") else "fabric-mod/src/main/resources"
HD = 128
if "--hd-size" in sys.argv:
    HD = int(sys.argv[sys.argv.index("--hd-size") + 1])
A = os.path.join(ROOT, "assets", "earthlife")
HDA = os.path.join(ROOT, "resourcepacks", "zmf_hd", "assets", "earthlife")
rnd = random.Random(2026)


def png(path, w, h, px):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        for x in range(w):
            raw.extend(px[y * w + x])
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    data = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)


def hexc(s, a=255):
    s = s.lstrip("#")
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), a)


def clamp(v):
    return max(0, min(255, int(v)))


def noise2(x, y, seed):
    """Smooth value noise in 0..1 (tileable on integer periods is not needed for these small textures)."""
    def h(i, j):
        n = (i * 374761393 + j * 668265263 + seed * 2147483647) & 0xffffffff
        n = (n ^ (n >> 13)) * 1274126177 & 0xffffffff
        return (n & 0xffff) / 65535.0
    xi, yi = int(math.floor(x)), int(math.floor(y))
    fx, fy = x - xi, y - yi
    fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
    a, b, c, d = h(xi, yi), h(xi + 1, yi), h(xi, yi + 1), h(xi + 1, yi + 1)
    return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy


# ---------------------------------------------------------------------------------------------------------------
# Material definitions: each returns (albedo rgba, height 0..1, smoothness 0..1, f0/metal 0..255, emission 0..1) per
# texel at a resolution-independent coordinate (u, v in 0..1), so the same material renders at 16 px and at HD size.

def mat_leather(base, stitch, seam_rows=(0.5,), seam_cols=(0.5,), tufted=False):
    b = hexc(base); s = hexc(stitch)
    def f(u, v, n):
        g = noise2(u * 18, v * 18, 7) * 0.6 + noise2(u * 55, v * 55, 9) * 0.4         # leather grain
        h = 0.55 + 0.25 * g
        col = [c * (0.86 + 0.22 * g) for c in b[:3]]
        # padded panels: darker, lower seams
        for r in seam_rows:
            d = abs(v - r)
            if d < 0.035:
                h -= 0.35 * (1 - d / 0.035); col = [c * 0.75 for c in col]
                if d < 0.012 and int(u * n * 1.5) % 2 == 0:
                    col = list(s[:3]); h += 0.1
        for c0 in seam_cols:
            d = abs(u - c0)
            if d < 0.03:
                h -= 0.3 * (1 - d / 0.03); col = [c * 0.78 for c in col]
        if tufted:
            for tu in (0.25, 0.75):
                for tv in (0.25, 0.75):
                    d = math.hypot(u - tu, v - tv)
                    if d < 0.06:
                        h -= 0.4 * (1 - d / 0.06); col = [c * 0.7 for c in col]
        edge = min(u, v, 1 - u, 1 - v)
        if edge < 0.05:
            col = [c * (0.7 + 0.3 * edge / 0.05) for c in col]; h -= 0.2 * (1 - edge / 0.05)
        return (clamp(col[0]), clamp(col[1]), clamp(col[2]), 255), max(0, min(1, h)), 0.55 + 0.2 * g, 30, 0
    return f


def mat_fabric(base):
    b = hexc(base)
    def f(u, v, n):
        weave = (math.sin(u * n * math.pi * 1.0) * math.sin(v * n * math.pi * 1.0)) * 0.5 + 0.5
        g = noise2(u * 30, v * 30, 3)
        col = [c * (0.85 + 0.15 * weave + 0.1 * g) for c in b[:3]]
        return (clamp(col[0]), clamp(col[1]), clamp(col[2]), 255), 0.4 + 0.3 * weave, 0.2, 10, 0
    return f


def mat_metal(base, brushed=True, plates=False):
    b = hexc(base)
    def f(u, v, n):
        g = noise2(u * 3, v * 90, 5) if brushed else noise2(u * 20, v * 20, 5)
        col = [c * (0.85 + 0.25 * g) for c in b[:3]]
        h = 0.6
        if plates and (abs(u - 0.5) < 0.02 or abs(v - 0.5) < 0.02):
            col = [c * 0.6 for c in col]; h = 0.3
        if plates:
            for bu in (0.1, 0.9):
                for bv in (0.1, 0.9):
                    if math.hypot(u - bu, v - bv) < 0.04:
                        col = [c * 1.2 for c in col]; h = 0.85
        return (clamp(col[0]), clamp(col[1]), clamp(col[2]), 255), h, 0.8 + 0.15 * g, 230, 0
    return f


def mat_plastic(base, stripe=None, stripe_v=0.85, glow=False):
    b = hexc(base)
    def f(u, v, n):
        g = noise2(u * 12, v * 12, 11)
        col = [c * (0.92 + 0.1 * g) for c in b[:3]]
        e = 0
        if stripe and abs(v - stripe_v) < 0.05:
            col = list(hexc(stripe)[:3]); e = 0.8 if glow else 0
        return (clamp(col[0]), clamp(col[1]), clamp(col[2]), 255), 0.55, 0.6, 40, e
    return f


def mat_grille(base):
    b = hexc(base)
    def f(u, v, n):
        holes = (math.sin(u * 40) * math.sin(v * 40)) > 0.6
        col = [c * (0.5 if holes else 1.0) for c in b[:3]]
        return (clamp(col[0]), clamp(col[1]), clamp(col[2]), 255), 0.2 if holes else 0.6, 0.3, 20, 0
    return f


def mat_wood(base):
    b = hexc(base)
    def f(u, v, n):
        ring = math.sin((v + noise2(u * 4, v * 4, 2) * 0.3) * 40) * 0.5 + 0.5
        col = [c * (0.8 + 0.25 * ring) for c in b[:3]]
        return (clamp(col[0]), clamp(col[1]), clamp(col[2]), 255), 0.5 + 0.2 * ring, 0.45, 15, 0
    return f


def mat_glass(tint, a=110):
    t = hexc(tint, a)
    def f(u, v, n):
        streak = 1 if abs((u + v) % 0.5 - 0.1) < 0.02 else 0
        col = [min(255, c + 60 * streak) for c in t[:3]]
        edge = min(u, v, 1 - u, 1 - v) < 0.06
        if edge:
            return (190, 160, 60, 255), 0.7, 0.9, 230, 0
        return (col[0], col[1], col[2], a + 50 * streak), 0.5, 0.97, 40, 0
    return f


def mat_lens():
    def f(u, v, n):
        d = math.hypot(u - 0.5, v - 0.5)
        if d < 0.3:
            k = 1 - d / 0.3
            return (clamp(120 + 130 * k), clamp(170 + 85 * k), 255, 255), 0.9, 0.98, 60, 0.9 * k
        if d < 0.38:
            return (30, 30, 34, 255), 0.4, 0.8, 230, 0
        g = noise2(u * 3, v * 80, 4)
        return (clamp(40 + 30 * g), clamp(42 + 30 * g), clamp(48 + 30 * g), 255), 0.6, 0.75, 230, 0
    return f


def mat_candy():
    cols = ["e53935", "fdd835", "43a047", "1e88e5", "8e24aa", "fb8c00", "ffffff"]
    def f(u, v, n):
        if v < 0.12 or v > 0.88 or u < 0.08 or u > 0.92:
            return (200, 200, 205, 255), 0.6, 0.85, 230, 0
        if abs(v - 0.5) < 0.03:
            return (210, 210, 215, 255), 0.4, 0.85, 230, 0
        k = int(noise2(u * 14, v * 14, 8) * len(cols) * 1.999) % len(cols)
        c = hexc(cols[k])
        return c, 0.7, 0.7, 30, 0.15
    return f


def mat_shelf():
    boxes = ["c62828", "f9a825", "2e7d32", "1565c0", "6a1b9a", "ef6c00"]
    def f(u, v, n):
        if v % 0.33 < 0.05:
            return (120, 85, 50, 255), 0.4, 0.4, 15, 0
        k = int(u * 6) % len(boxes)
        c = hexc(boxes[(k + int(v * 3)) % len(boxes)])
        light = 0.85 + 0.15 * (1 if (u * 6) % 1 < 0.15 else 0)
        return (clamp(c[0] * light), clamp(c[1] * light), clamp(c[2] * light), 255), 0.6, 0.5, 30, 0
    return f


def mat_plate():
    def f(u, v, n):
        col = (196, 160, 70, 255)
        digits = abs(u - 0.5) < 0.25 and abs(v - 0.5) < 0.22 and int(u * 10 + v * 3) % 2 == 0
        if digits:
            col = (40, 32, 20, 255)
        return col, 0.4 if digits else 0.7, 0.85, 230, 0
    return f


MATERIALS = {
    "zmf_leather_red": mat_leather("8e1a1f", "c9a227", seam_rows=(0.33, 0.66), seam_cols=(0.5,)),
    "zmf_leather_black": mat_leather("1f1d1f", "8a8a8a", seam_rows=(0.33, 0.66), seam_cols=(0.5,)),
    "zmf_leather_tufted": mat_leather("5a1220", "d4af37", seam_rows=(), seam_cols=(), tufted=True),
    "zmf_fabric_blue": mat_fabric("23395b"),
    "zmf_fabric_grey": mat_fabric("4a4d55"),
    "zmf_frame": mat_metal("2b2c30", brushed=True),
    "zmf_steel": mat_metal("a7adb4", brushed=True),
    "zmf_platform": mat_metal("3a3f46", brushed=False, plates=True),
    "zmf_shell": mat_plastic("23272d", stripe="31a8ff", stripe_v=0.85, glow=True),
    "zmf_nozzle": mat_plastic("15181c", stripe="9be7ff", stripe_v=0.5, glow=True),
    "zmf_grille": mat_grille("202226"),
    "zmf_speaker_wood": mat_wood("3e2a1c"),
    "zmf_glass": mat_glass("d8eef6"),
    "zmf_lens": mat_lens(),
    "zmf_candy": mat_candy(),
    "zmf_shelf": mat_shelf(),
    "zmf_plate": mat_plate(),
}


def render(name, mat, size):
    alb, nrm, spe = [], [], []
    hs = []
    for y in range(size):
        for x in range(size):
            u, v = (x + 0.5) / size, (y + 0.5) / size
            c, h, smooth, f0, em = mat(u, v, size)
            alb.append(c); hs.append(h)
            # LabPBR specular: R = perceptual smoothness, G = F0 (>=230 = metal), B = porosity/SSS (0), A = emission (1-254, 255 = none)
            spe.append((clamp(smooth * 255), clamp(f0), 0, 255 if em <= 0 else clamp(1 + em * 253)))
    for y in range(size):
        for x in range(size):
            hl, hr = hs[y * size + (x - 1) % size], hs[y * size + (x + 1) % size]
            hu, hd = hs[((y - 1) % size) * size + x], hs[((y + 1) % size) * size + x]
            dx, dy = (hl - hr) * 2.5, (hu - hd) * 2.5
            l = math.sqrt(dx * dx + dy * dy + 1)
            # LabPBR normal: RG = normal XY, B = ambient occlusion, A = height
            nrm.append((clamp((dx / l * 0.5 + 0.5) * 255), clamp((dy / l * 0.5 + 0.5) * 255), clamp(200 + 55 * hs[y * size + x]), clamp(hs[y * size + x] * 255)))
    return alb, nrm, spe


for name, mat in MATERIALS.items():
    alb, _, _ = render(name, mat, 16)
    png(os.path.join(A, "textures", "block", name + ".png"), 16, 16, alb)
    if HD > 16:
        alb, nrm, spe = render(name, mat, HD)
        base = os.path.join(HDA, "textures", "block", name)
        png(base + ".png", HD, HD, alb)
        png(base + "_n.png", HD, HD, nrm)
        png(base + "_s.png", HD, HD, spe)

# a plain white texture for light spill / glows
png(os.path.join(A, "textures", "misc", "white.png"), 4, 4, [(255, 255, 255, 255)] * 16)

# Cinema Locator icon (a compass dial with a film-strip needle)
ic = [(0, 0, 0, 0)] * 256
def iset(x, y, c):
    if 0 <= x < 16 and 0 <= y < 16:
        ic[y * 16 + x] = c
for y in range(16):
    for x in range(16):
        d = math.hypot(x - 7.5, y - 7.5)
        if d < 7.2:
            iset(x, y, hexc("2a2d34") if d > 6 else hexc("e8e2d0"))
for i in range(-4, 5):
    iset(7 + (1 if i < 0 else 0), 7 + i, hexc("d32f2f") if i < 0 else hexc("37474f"))
    iset(8, 7 + i, hexc("b71c1c") if i < 0 else hexc("263238"))
for x, y in ((7, 1), (8, 1), (7, 14), (8, 14), (1, 7), (14, 8)):
    iset(x, y, hexc("ffd54f"))
png(os.path.join(A, "textures", "item", "cinema_locator.png"), 16, 16, ic)

# ---------------------------------------------------------------------------------------------------------------
# Models


def wjson(rel, obj):
    p = os.path.join(A, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w") as f:
        json.dump(obj, f, indent=1)


def el(frm, to, tex, rot=None, faces=None, shade=True, uv=None):
    e = {"from": frm, "to": to, "faces": {}}
    for d in ("north", "south", "east", "west", "up", "down"):
        e["faces"][d] = {"texture": "#" + (faces or {}).get(d, tex)}
        if uv:
            e["faces"][d]["uv"] = uv
    if rot:
        e["rotation"] = rot
    if not shade:
        e["shade"] = False
    return e


DISPLAY = {"gui": {"rotation": [30, 225, 0], "translation": [0, -1.5, 0], "scale": [0.5, 0.5, 0.5]},
           "ground": {"translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]}, "fixed": {"scale": [0.5, 0.5, 0.5]},
           "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
           "firstperson_righthand": {"rotation": [0, 45, 0], "scale": [0.4, 0.4, 0.4]}}


def model(textures, elements, particle):
    t = dict(textures); t["particle"] = particle
    return {"parent": "minecraft:block/block", "textures": t, "elements": elements, "display": DISPLAY}


def recliner(reclined, leather, luxury=False):
    """Cinema recliner, facing north (the screen). Back at the south side."""
    L, F, S, P = "leather", "frame", "steel", "plate"
    els = [
        el([2.5, 0, 3], [13.5, 2, 14], F),                                  # metal base frame
        el([3, 2, 2], [13, 4.5, 14], F),                                    # seat pan
        el([2.6, 4.5, 1.6], [13.4, 7, 13.5], L),                            # seat cushion
        el([3.2, 7, 2.2], [12.8, 7.6, 12.8], L),                            # cushion crown (padding)
        el([0, 0, 1], [2.4, 9, 15], L), el([13.6, 0, 1], [16, 9, 15], L),  # armrests
        el([0.2, 9, 1.4], [2.2, 9.6, 14.6], F), el([13.8, 9, 1.4], [15.8, 9.6, 14.6], F),   # armrest tops
        el([14.1, 9.2, 1.8], [15.5, 9.7, 4.2], S), el([0.5, 9.2, 1.8], [1.9, 9.7, 4.2], S),  # cup holders (rings)
        el([14.4, 7.2, 2.1], [15.2, 9.2, 3.9], F), el([0.8, 7.2, 2.1], [1.6, 9.2, 3.9], F),   # cup holder wells
        el([6, 2.6, 0.9], [10, 3.8, 1.1], P),                               # seat-number plate
    ]
    back = [el([2.6, 7, 12.4], [13.4, 20, 15], L), el([3.2, 9, 11.8], [12.8, 18, 12.4], L)]       # back + lumbar padding
    head = [el([3.5, 19, 11.2], [12.5, 23 if luxury else 22, 14], L)]
    if luxury:
        head.append(el([2, 21, 12], [14, 23.5, 14.5], L))                   # wing headrest
    pivot = {"origin": [8, 7, 13.5], "axis": "x", "angle": -22.5}
    if reclined:
        for e in back + head:
            e["rotation"] = pivot
        els.append(el([2.6, 3.5, -6], [13.4, 6.5, 1.5], L))                 # footrest out
        els.append(el([4, 1.5, -4], [5, 4, -3], S)); els.append(el([11, 1.5, -4], [12, 4, -3], S))   # footrest arms
    else:
        els.append(el([2.6, 1, 0.6], [13.4, 6.5, 1.8], L))                  # footrest folded
    els += back + head
    tex = {"leather": "earthlife:block/" + leather, "frame": "earthlife:block/zmf_frame", "steel": "earthlife:block/zmf_steel", "plate": "earthlife:block/zmf_plate"}
    return model(tex, els, "earthlife:block/" + leather)


def motion_seat(reclined):
    """4DX motion seat: shell seat on a pivot assembly over a motion platform, with effect nozzles."""
    els = [
        el([0, 0, 0], [16, 1, 16], "platform"),                             # motion platform
        el([5, 1, 5], [11, 3, 11], "steel"),                                # actuator base
        el([6.5, 3, 6.5], [9.5, 5, 9.5], "steel"),                          # pivot column
        el([4, 4.5, 6], [12, 5.5, 10], "frame"),                            # pivot cross-beam
        el([3, 0.5, -3], [13, 1.5, 1], "platform"),                         # foot support
        el([2.5, 5, 2], [13.5, 7.5, 14], "shell"),                          # seat shell (bucket)
        el([3, 7.5, 2.5], [13, 9, 13], "fabric"),                           # cushion
        el([0.5, 5, 2], [2.5, 11, 14], "shell"), el([13.5, 5, 2], [15.5, 11, 14], "shell"),   # armrests
        el([0.6, 11, 2.5], [2.4, 11.5, 13.5], "nozzle"), el([13.6, 11, 2.5], [15.4, 11.5, 13.5], "nozzle"),  # haptic indicator strips
        el([0.8, 10.2, 2.2], [2.2, 11, 3.2], "nozzle"), el([13.8, 10.2, 2.2], [15.2, 11, 3.2], "nozzle"),    # mist nozzles (front of armrests)
    ]
    back = [el([2.5, 8, 12.5], [13.5, 21, 15.5], "shell"), el([3.2, 9.5, 11.9], [12.8, 19, 12.5], "fabric"),
            el([4, 20.5, 13], [6, 21.5, 14], "nozzle"), el([10, 20.5, 13], [12, 21.5, 14], "nozzle")]          # airflow outlets at the neck
    if reclined:
        for e in back:
            e["rotation"] = {"origin": [8, 8, 14], "axis": "x", "angle": -22.5}
    els += back
    tex = {"shell": "earthlife:block/zmf_shell", "fabric": "earthlife:block/zmf_fabric_grey", "steel": "earthlife:block/zmf_steel",
           "frame": "earthlife:block/zmf_frame", "platform": "earthlife:block/zmf_platform", "nozzle": "earthlife:block/zmf_nozzle"}
    return model(tex, els, "earthlife:block/zmf_shell")


wjson("models/block/recliner.json", recliner(False, "zmf_leather_red"))
wjson("models/block/recliner_reclined.json", recliner(True, "zmf_leather_red"))
wjson("models/block/luxury_recliner.json", recliner(False, "zmf_leather_tufted", luxury=True))
wjson("models/block/luxury_recliner_reclined.json", recliner(True, "zmf_leather_tufted", luxury=True))
wjson("models/block/motion_seat.json", motion_seat(False))
wjson("models/block/motion_seat_reclined.json", motion_seat(True))

sofa = [el([0, 0, 2], [16, 3, 15], "frame"), el([0, 3, 1], [16, 7, 13], "fabric"), el([0, 7, 11], [16, 16, 15], "fabric"),
        el([0, 3, 1], [1.5, 10, 15], "fabric"), el([14.5, 3, 1], [16, 10, 15], "fabric"), el([1, 7, 1.5], [15, 8, 11], "fabric")]
for name in ("lounge_sofa", "lounge_sofa_reclined"):
    wjson("models/block/%s.json" % name, model({"fabric": "earthlife:block/zmf_fabric_blue", "frame": "earthlife:block/zmf_frame"}, sofa, "earthlife:block/zmf_fabric_blue"))

wjson("models/block/candy_display.json", model({"candy": "earthlife:block/zmf_candy", "steel": "earthlife:block/zmf_steel", "glass": "earthlife:block/zmf_glass"},
      [el([1, 0, 3], [15, 2, 13], "steel"), el([1.5, 2, 3.5], [14.5, 15, 12.5], "candy"), el([1, 2, 3], [15, 15.5, 3.4], "glass"),
       el([1, 15, 3], [15, 16, 13], "steel")], "earthlife:block/zmf_candy"))
wjson("models/block/snack_shelf.json", model({"shelf": "earthlife:block/zmf_shelf", "wood": "earthlife:block/zmf_speaker_wood"},
      [el([0, 0, 9], [16, 16, 16], "wood"), el([0.5, 0.5, 6], [15.5, 15.5, 9], "shelf", faces={"north": "shelf"}),
       el([0, 5, 5], [16, 5.6, 9], "wood"), el([0, 10.5, 5], [16, 11.1, 9], "wood")], "earthlife:block/zmf_shelf"))
wjson("models/block/projector.json", model({"body": "earthlife:block/zmf_frame", "lens": "earthlife:block/zmf_lens", "steel": "earthlife:block/zmf_steel", "grille": "earthlife:block/zmf_grille"},
      [el([2, 0, 3], [14, 1, 15], "steel"), el([3, 1, 4], [13, 10, 15], "body", faces={"east": "grille", "west": "grille"}),
       el([5.5, 3, 1], [10.5, 8, 4], "lens", faces={"north": "lens"}), el([4, 10, 6], [12, 12, 13], "body")], "earthlife:block/zmf_frame"))
wjson("models/block/cinema_speaker.json", model({"wood": "earthlife:block/zmf_speaker_wood", "grille": "earthlife:block/zmf_grille"},
      [el([3, 0, 5], [13, 16, 13], "wood", faces={"north": "grille"}), el([2.5, 0, 4.5], [13.5, 0.5, 13.5], "wood")], "earthlife:block/zmf_grille"))
wjson("models/block/effect_module.json", model({"shell": "earthlife:block/zmf_shell", "nozzle": "earthlife:block/zmf_nozzle", "grille": "earthlife:block/zmf_grille"},
      [el([1, 1, 6], [15, 15, 15], "shell"), el([3, 9, 4], [13, 13, 6], "grille", faces={"north": "grille"}),
       el([4, 3, 4.5], [7, 6, 6], "nozzle"), el([9, 3, 4.5], [12, 6, 6], "nozzle")], "earthlife:block/zmf_shell"))


def facing_states(model_name, extra=None):
    v = {}
    for facing, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        if extra:
            for val, suffix in extra:
                v["facing=%s,%s" % (facing, val)] = {"model": "earthlife:block/" + model_name + suffix, **({"y": y} if y else {})}
        else:
            v["facing=" + facing] = {"model": "earthlife:block/" + model_name, **({"y": y} if y else {})}
    return {"variants": v}


SEATS = ["luxury_recliner", "lounge_sofa"]
OTHER = ["candy_display", "snack_shelf", "projector", "cinema_speaker", "effect_module"]
for b in SEATS:
    wjson("blockstates/%s.json" % b, facing_states(b, [("reclined=false", ""), ("reclined=true", "_reclined")]))
for b in OTHER:
    wjson("blockstates/%s.json" % b, facing_states(b))
for b in SEATS + OTHER:
    wjson("items/%s.json" % b, {"model": {"type": "minecraft:model", "model": "earthlife:block/" + b}})
wjson("items/cinema_locator.json", {"model": {"type": "minecraft:model", "model": "earthlife:item/cinema_locator"}})
wjson("models/item/cinema_locator.json", {"parent": "minecraft:item/generated", "textures": {"layer0": "earthlife:item/cinema_locator"}})

NAMES = {"block.earthlife.luxury_recliner": "Luxury Cinema Recliner", "block.earthlife.lounge_sofa": "Lobby Lounge Sofa",
         "block.earthlife.candy_display": "Candy Display", "block.earthlife.snack_shelf": "Snack Shelf", "block.earthlife.projector": "Cinema Projector",
         "block.earthlife.cinema_speaker": "Cinema Speaker", "block.earthlife.effect_module": "4DX Effect Module",
         "item.earthlife.cinema_locator": "Cinema Locator", "block.earthlife.motion_seat": "4DX Motion Seat",
         "key.earthlife.zmf": "ZMF Cinema menu / web browser", "key.earthlife.fx_stop": "4DX + TactSuit emergency stop",
         "itemGroup.earthlife.cinema": "EarthLife + ZMF Cinema"}
lang_path = os.path.join(A, "lang", "en_us.json")
lang = json.load(open(lang_path)) if os.path.exists(lang_path) else {}
lang.update(NAMES)
json.dump(lang, open(lang_path, "w"), indent=2, ensure_ascii=False)

# HD pack metadata
if HD > 16:
    meta = {"pack": {"description": "ZMF Cinema HD - %dpx cinema textures with LabPBR normal/specular maps" % HD, "min_format": 1, "max_format": 999,
                     "pack_format": 100}}
    os.makedirs(os.path.join(ROOT, "resourcepacks", "zmf_hd"), exist_ok=True)
    json.dump(meta, open(os.path.join(ROOT, "resourcepacks", "zmf_hd", "pack.mcmeta"), "w"), indent=1)
print("ZMF assets written to", A, "(HD pack %dpx)" % HD if HD > 16 else "")
