#!/usr/bin/env python3
"""
Generates EarthLife's cinema assets (all original artwork, no brands): block models + textures for the recliner,
the 4D motion seat, the popcorn machine, the soda fountain and the ticket kiosk; 16x16 item icons for concession
food and drinks, 3D glasses and movie tickets; the 3D-glasses camera overlay; blockstates, item definitions and
English names. Pure Python (zlib + struct), no image libraries needed.

  python3 tools/assets/cinema_assets.py fabric-mod/src/main/resources
"""
import json, math, os, random, struct, sys, zlib

ROOT = sys.argv[1] if len(sys.argv) > 1 else "fabric-mod/src/main/resources"
A = os.path.join(ROOT, "assets", "earthlife")
rnd = random.Random(4115)


def png(path, w, h, px):
    """px: list of (r,g,b,a) rows*cols."""
    raw = b"".join(b"\x00" + b"".join(struct.pack("BBBB", *px[y * w + x]) for x in range(w)) for y in range(h))
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    data = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)


def hexc(s, a=255):
    s = s.lstrip("#")
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), a)


def shade(c, f):
    return (max(0, min(255, int(c[0] * f))), max(0, min(255, int(c[1] * f))), max(0, min(255, int(c[2] * f))), c[3])


def jitter(c, amt=0.06):
    return shade(c, 1 + rnd.uniform(-amt, amt))


class Img:
    def __init__(self, w=16, h=16, fill=(0, 0, 0, 0)):
        self.w, self.h = w, h
        self.p = [fill] * (w * h)

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.p[y * self.w + x] = c

    def get(self, x, y):
        return self.p[y * self.w + x]

    def rect(self, x0, y0, x1, y1, c):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, c(x, y) if callable(c) else c)

    def outline(self, col):
        """Dark 1px outline around opaque pixels (classic item-icon look)."""
        src = list(self.p)
        for y in range(self.h):
            for x in range(self.w):
                if src[y * self.w + x][3] == 0:
                    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                        nx, ny = x + dx, y + dy
                        if 0 <= nx < self.w and 0 <= ny < self.h and src[ny * self.w + nx][3] > 0 and src[ny * self.w + nx] != col:
                            self.p[y * self.w + x] = col
                            break

    def save(self, rel):
        png(os.path.join(A, "textures", rel + ".png"), self.w, self.h, self.p)


OUT = hexc("1c1414")

# ------------------------------------------------------------------ block textures

def tex_leather(base):
    im = Img()
    for y in range(16):
        for x in range(16):
            c = jitter(base, 0.05)
            if y in (0, 15) or x in (0, 15):
                c = shade(base, 0.8)
            if (y == 7 or x == 7) and (x + y) % 2 == 0:
                c = shade(base, 1.25)          # stitching
            im.set(x, y, c)
    return im


def tex_noise(base, amt=0.07, border=None):
    im = Img()
    for y in range(16):
        for x in range(16):
            c = jitter(base, amt)
            if border and (y in (0, 15) or x in (0, 15)):
                c = border
            im.set(x, y, c)
    return im


tex_leather(hexc("8e1a1f")).save("block/recliner_leather")
tex_noise(hexc("24221f"), 0.05).save("block/recliner_frame")
im = tex_noise(hexc("2d3035"), 0.05)
for x in range(16):
    im.set(x, 13, hexc("31a8ff")); im.set(x, 14, hexc("1d6fb8"))
im.save("block/motion_seat_shell")
tex_noise(hexc("8c9196"), 0.06, hexc("5f6469")).save("block/motion_seat_metal")
im = Img()
for y in range(16):
    for x in range(16):
        im.set(x, y, hexc("b81c22") if (x // 4) % 2 == 0 else hexc("f1ece0"))
im.save("block/popcorn_stripes")
tex_noise(hexc("c3161d"), 0.05, hexc("8a0f14")).save("block/popcorn_red")
im = Img(fill=hexc("d8eef6", 70))
for i in range(16):
    im.set(i, i, hexc("ffffff", 110)); im.set((i + 5) % 16, i, hexc("ffffff", 90))
for x in range(16):
    im.set(x, 0, hexc("c9a227")); im.set(x, 15, hexc("c9a227"))
for y in range(16):
    im.set(0, y, hexc("c9a227")); im.set(15, y, hexc("c9a227"))
im.save("block/popcorn_glass")
im = Img()
for y in range(16):
    for x in range(16):
        im.set(x, y, rnd.choice([hexc("fff6d8"), hexc("ffeeb0"), hexc("f7dc8a"), hexc("fffbe8"), hexc("e8c56a")]))
im.save("block/popcorn_kernels")
tex_noise(hexc("c4c9ce"), 0.04, hexc("8d9297")).save("block/steel")
im = tex_noise(hexc("c4c9ce"), 0.03)
for i, col in enumerate(["7a1c1c", "2e7d32", "ef6c00", "1565c0"]):
    im.rect(1 + i * 4, 3, 3 + i * 4, 7, hexc(col))
    im.rect(2 + i * 4, 9, 2 + i * 4, 11, hexc("3b3f44"))
im.rect(0, 13, 15, 15, hexc("2a2d31"))
im.save("block/soda_fountain_front")
tex_noise(hexc("1f2226"), 0.04).save("block/kiosk_body")
im = Img(fill=hexc("0b1a2e"))
im.rect(1, 1, 14, 4, hexc("1e88e5"))
for r in range(3):
    im.rect(2, 6 + r * 3, 13, 7 + r * 3, hexc("e3f2fd") if r == 0 else hexc("90caf9"))
im.save("block/kiosk_screen")
tex_noise(hexc("101010"), 0.03).save("block/screen_frame")

# ------------------------------------------------------------------ item icons

def icon_bucket(name, large):
    im = Img()
    top, bot = (3, 14) if large else (4, 14)
    w_top, w_bot = (6, 4) if large else (5, 3)
    for y in range(top + 3, bot + 1):
        t = (y - top - 3) / max(1, (bot - top - 3))
        half = round(w_top + (w_bot - w_top) * t)
        for x in range(8 - half, 8 + half):
            stripe = ((x - (8 - half)) * 4 // (2 * half)) % 2 == 0
            im.set(x, y, shade(hexc("c8161d") if stripe else hexc("f4efe4"), 1.0 - 0.15 * t))
    for y in range(top - (2 if large else 1), top + 3):
        for x in range(8 - w_top - 1, 8 + w_top + 1):
            dx = abs(x + 0.5 - 8) / (w_top + 1)
            if (y - top) * -1 < 3 - 3 * dx * dx + rnd.random():
                im.set(x, y, rnd.choice([hexc("fff8de"), hexc("fbe9a8"), hexc("f3d77d"), hexc("ffffff")]))
    im.outline(OUT)
    im.save("item/" + name)


def icon_cup(name, color, slushie=False):
    im = Img()
    for y in range(5, 15):
        t = (y - 5) / 9
        half = round(4 - 1.2 * t)
        for x in range(8 - half, 8 + half):
            c = hexc(color)
            if slushie and y < 9:
                c = hexc("7fd4ff")
            if 7 <= y <= 9 and not slushie:
                c = hexc("f5f5f5")                   # plain band on the cup (no brand)
            im.set(x, y, shade(c, 1.0 - 0.18 * t + (0.12 if x == 8 - half else 0)))
    im.rect(3, 4, 12, 4, hexc("e8e8e8"))          # lid
    im.rect(4, 3, 11, 3, hexc("ffffff"))
    for y in range(0, 4):
        im.set(10 - y // 2, y, hexc("e53935") if y % 2 == 0 else hexc("ffffff"))   # straw
    im.outline(OUT)
    im.save("item/" + name)


def icon_bottle():
    im = Img()
    im.rect(7, 1, 8, 2, hexc("1565c0"))
    im.rect(6, 3, 9, 4, hexc("bbdefb", 220))
    im.rect(5, 5, 10, 14, lambda x, y: hexc("90caf9", 200) if x not in (5, 10) else hexc("64b5f6", 220))
    im.rect(5, 8, 10, 10, hexc("e3f2fd"))
    im.outline(OUT)
    im.save("item/bottled_water")


def icon_choc():
    im = Img()
    im.rect(3, 2, 12, 13, lambda x, y: hexc("5d3a1a") if (x - 3) % 3 and (y - 2) % 3 else hexc("3e2510"))
    im.rect(3, 9, 12, 13, hexc("6a1b9a"))
    im.rect(3, 10, 12, 10, hexc("ffd54f"))
    im.outline(OUT)
    im.save("item/chocolate_bar")


def icon_gummies():
    im = Img()
    im.rect(3, 2, 12, 14, hexc("f5f5f5", 140))       # clear bag
    im.rect(3, 2, 12, 3, hexc("e53935"))
    cols = ["e53935", "43a047", "fdd835", "fb8c00", "8e24aa"]
    for i, (x, y) in enumerate([(4, 5), (8, 5), (6, 8), (10, 9), (4, 11), (8, 12)]):
        c = hexc(cols[i % len(cols)])
        im.set(x, y, c); im.set(x + 1, y, c); im.set(x, y + 1, c); im.set(x + 1, y + 1, c); im.set(x, y - 1, c); im.set(x + 1, y - 1, c)
    im.outline(OUT)
    im.save("item/gummy_bears")


def icon_worms():
    im = Img()
    cols = [("ff4081", "ffeb3b"), ("00e5ff", "ff9100"), ("76ff03", "e040fb")]
    for k, (a, b) in enumerate(cols):
        for x in range(2, 14):
            y = 4 + k * 4 + round(math.sin(x * 0.9 + k) * 1.2)
            im.set(x, y, hexc(a if x < 8 else b)); im.set(x, y + 1, shade(hexc(a if x < 8 else b), 0.85))
    im.outline(OUT)
    im.save("item/sour_worms")


def icon_licorice():
    im = Img()
    for k in range(3):
        for i in range(12):
            x, y = 3 + k * 3 + i // 4, 2 + i
            im.set(x, y, hexc("c62828") if (i + k) % 2 else hexc("8e0000"))
            im.set(x + 1, y, hexc("d32f2f"))
    im.outline(OUT)
    im.save("item/licorice")


def icon_candy_mix():
    im = Img()
    im.rect(4, 3, 11, 14, hexc("ffffff"))
    for x in range(4, 12):
        if x % 2 == 0:
            for y in range(3, 15):
                im.set(x, y, hexc("1e88e5"))
    for x, y, c in [(5, 1, "e53935"), (7, 2, "fdd835"), (9, 1, "43a047"), (10, 2, "8e24aa"), (6, 2, "fb8c00")]:
        im.set(x, y, hexc(c)); im.set(x, y + 1, hexc(c))
    im.outline(OUT)
    im.save("item/candy_mix")


def icon_nachos():
    im = Img()
    im.rect(2, 10, 13, 13, hexc("424242"))
    for x0 in (3, 6, 9):
        for i in range(5):
            for x in range(x0 + i // 2, x0 + 4 - i // 2):
                im.set(x, 9 - i, hexc("f9c74f"))
    im.rect(4, 8, 11, 9, hexc("ffb703"))
    im.outline(OUT)
    im.save("item/nachos")


def icon_hotdog():
    im = Img()
    for x in range(2, 14):
        im.set(x, 8, hexc("d7a35f")); im.set(x, 9, hexc("c98c40")); im.set(x, 10, hexc("b47530"))
        im.set(x, 6, hexc("d7a35f")); im.set(x, 7, hexc("a6332b"))
    for x in range(1, 15):
        im.set(x, 7, hexc("b23a2f"))
    for x in range(3, 13):
        if x % 2:
            im.set(x, 6, hexc("ffd600"))
    im.outline(OUT)
    im.save("item/hot_dog")


def icon_pretzel():
    im = Img()
    pts = []
    for t in range(0, 360, 6):
        a = math.radians(t)
        pts.append((8 + 5 * math.sin(a), 7 + 4 * math.sin(2 * a) * 0.9 + 1.5 * math.cos(a)))
    for x, y in pts:
        im.set(round(x), round(y), hexc("8d5524")); im.set(round(x) + 1, round(y), hexc("a0652e"))
    for x, y in pts[::5]:
        im.set(round(x), round(y) - 1, hexc("ffffff"))
    im.outline(OUT)
    im.save("item/soft_pretzel")


def icon_glasses():
    im = Img()
    im.rect(1, 6, 14, 6, hexc("111111"))
    im.rect(1, 7, 6, 10, hexc("111111")); im.rect(9, 7, 14, 10, hexc("111111"))
    im.rect(2, 7, 5, 9, hexc("e53935", 230)); im.rect(10, 7, 13, 9, hexc("00bcd4", 230))
    im.rect(7, 7, 8, 7, hexc("111111"))
    im.set(0, 6, hexc("111111")); im.set(15, 6, hexc("111111"))
    im.save("item/glasses_3d")


def icon_ticket():
    im = Img()
    im.rect(1, 4, 14, 11, hexc("ffe082"))
    im.rect(1, 4, 3, 11, hexc("e53935"))
    for y in range(4, 12, 2):
        im.set(4, y, hexc("bf9b30"))
    im.rect(6, 6, 12, 6, hexc("6d4c41")); im.rect(6, 8, 10, 8, hexc("6d4c41")); im.rect(6, 10, 11, 10, hexc("6d4c41"))
    im.outline(OUT)
    im.save("item/movie_ticket")


icon_bucket("popcorn", False)
icon_bucket("large_popcorn", True)
icon_cup("cola", "4e2a1e")
icon_cup("lemon_lime_soda", "9ccc65")
icon_cup("orange_soda", "fb8c00")
icon_cup("blue_slushie", "1e88e5", slushie=True)
icon_bottle()
icon_choc(); icon_gummies(); icon_worms(); icon_licorice(); icon_candy_mix()
icon_nachos(); icon_hotdog(); icon_pretzel(); icon_glasses(); icon_ticket()

# 3D glasses camera overlay: thin frame rims with a faint red (left) / cyan (right) tint, so the anaglyph picture
# still works with real red/cyan glasses in front of the monitor.
W, H = 256, 128
ov = Img(W, H)
for y in range(H):
    for x in range(W):
        lx = (x - 64) / 58.0; rx = (x - 192) / 58.0; yy = (y - 64) / 50.0
        dl, dr = lx * lx + yy * yy, rx * rx + yy * yy
        if dl <= 1:
            ov.set(x, y, (255, 40, 40, 14) if dl < 0.86 else (12, 12, 12, 235))
        elif dr <= 1:
            ov.set(x, y, (0, 220, 230, 14) if dr < 0.86 else (12, 12, 12, 235))
        elif 116 <= x <= 140 and 52 <= y <= 60:
            ov.set(x, y, (12, 12, 12, 235))
        else:
            ov.set(x, y, (8, 8, 8, 200))
ov.save("misc/glasses_3d_overlay")

# ------------------------------------------------------------------ models, blockstates, items


def wjson(rel, obj):
    p = os.path.join(A, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w") as f:
        json.dump(obj, f, indent=1)


def el(frm, to, tex, rot=None, faces=None, shade=True):
    e = {"from": frm, "to": to, "faces": {d: {"texture": "#" + (faces or {}).get(d, tex)} for d in ("north", "south", "east", "west", "up", "down")}}
    if rot:
        e["rotation"] = rot
    if not shade:
        e["shade"] = False
    return e


def seat_model(reclined, motion=False):
    L, F = ("shell", "metal") if motion else ("leather", "frame")
    els = [
        el([2, 0, 2], [14, 3, 15], F),                                    # base
        el([2, 3, 1], [14, 7, 13], L),                                    # cushion
        el([0, 0, 0], [2, 10, 15], L if not motion else F),              # armrests
        el([14, 0, 0], [16, 10, 15], L if not motion else F),
        el([14.2, 10, 1], [15.8, 10.4, 3.5], F),                         # cup holder rim
    ]
    back = el([2, 6, 12], [14, 21, 15], L)
    head = el([3, 17, 11], [13, 21, 12], L)
    if reclined:
        for e in (back, head):
            e["rotation"] = {"origin": [8, 6, 13], "axis": "x", "angle": -22.5}
        els.append(el([2, 3, -5], [14, 6, 1], L))                         # footrest out
    else:
        els.append(el([2, 1, 0], [14, 6, 1.2], L))                        # footrest folded
    els += [back, head]
    if motion:
        els += [el([5, 0, 5], [11, 3, 11], "metal"), el([4, 19, 14.5], [6, 20, 15.5], "metal"), el([10, 19, 14.5], [12, 20, 15.5], "metal"),
                el([0.3, 10, 0.5], [1.7, 10.5, 14.5], "shell"), el([14.3, 10, 0.5], [15.7, 10.5, 14.5], "shell")]
    tex = {"shell": "earthlife:block/motion_seat_shell", "metal": "earthlife:block/motion_seat_metal", "particle": "earthlife:block/motion_seat_shell"} if motion else \
          {"leather": "earthlife:block/recliner_leather", "frame": "earthlife:block/recliner_frame", "particle": "earthlife:block/recliner_leather"}
    return {"parent": "minecraft:block/block", "textures": tex, "elements": els,
            "display": {"gui": {"rotation": [30, 225, 0], "translation": [0, -1.5, 0], "scale": [0.5, 0.5, 0.5]},
                        "ground": {"translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
                        "fixed": {"scale": [0.5, 0.5, 0.5]},
                        "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
                        "firstperson_righthand": {"rotation": [0, 45, 0], "scale": [0.4, 0.4, 0.4]}}}


wjson("models/block/recliner.json", seat_model(False))
wjson("models/block/recliner_reclined.json", seat_model(True))
wjson("models/block/motion_seat.json", seat_model(False, True))
wjson("models/block/motion_seat_reclined.json", seat_model(True, True))

wjson("models/block/popcorn_machine.json", {"parent": "minecraft:block/block",
      "textures": {"red": "earthlife:block/popcorn_red", "stripes": "earthlife:block/popcorn_stripes", "glass": "earthlife:block/popcorn_glass",
                   "pop": "earthlife:block/popcorn_kernels", "steel": "earthlife:block/steel", "particle": "earthlife:block/popcorn_red"},
      "elements": [el([1, 0, 1], [15, 6, 15], "red", faces={"north": "stripes", "south": "stripes"}),
                   el([2, 6, 2], [14, 9, 14], "pop"),
                   el([1, 6, 1], [2, 15, 2], "steel"), el([14, 6, 1], [15, 15, 2], "steel"), el([1, 6, 14], [2, 15, 15], "steel"), el([14, 6, 14], [15, 15, 15], "steel"),
                   el([1.1, 6, 1.1], [14.9, 15, 14.9], "glass"),
                   el([6, 11, 6], [10, 13, 10], "steel"),
                   el([0, 15, 0], [16, 16, 16], "red"), el([4, 16, 4], [12, 18, 12], "red")]})
wjson("models/block/soda_fountain.json", {"parent": "minecraft:block/block",
      "textures": {"steel": "earthlife:block/steel", "front": "earthlife:block/soda_fountain_front", "particle": "earthlife:block/steel"},
      "elements": [el([1, 0, 5], [15, 14, 15], "steel", faces={"north": "front"}),
                   el([2, 2, 2], [14, 3, 5], "steel"),
                   el([2.5, 7, 3.5], [3.5, 9, 5], "steel"), el([6.5, 7, 3.5], [7.5, 9, 5], "steel"), el([10.5, 7, 3.5], [11.5, 9, 5], "steel"), el([13, 7, 3.5], [14, 9, 5], "steel")]})
wjson("models/block/ticket_kiosk.json", {"parent": "minecraft:block/block",
      "textures": {"body": "earthlife:block/kiosk_body", "screen": "earthlife:block/kiosk_screen", "particle": "earthlife:block/kiosk_body"},
      "elements": [el([4, 0, 5], [12, 2, 11], "body"), el([6, 2, 7], [10, 14, 10], "body"),
                   el([3, 14, 5], [13, 24, 9], "body", faces={"north": "screen"}, rot={"origin": [8, 14, 7], "axis": "x", "angle": 22.5})]})


def facing_states(model, extra=None):
    v = {}
    for facing, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        if extra:
            for val, suffix in extra:
                v["facing=%s,%s" % (facing, val)] = {"model": "earthlife:block/" + model + suffix, **({"y": y} if y else {})}
        else:
            v["facing=" + facing] = {"model": "earthlife:block/" + model, **({"y": y} if y else {})}
    return {"variants": v}


wjson("blockstates/recliner.json", facing_states("recliner", [("reclined=false", ""), ("reclined=true", "_reclined")]))
wjson("blockstates/motion_seat.json", facing_states("motion_seat", [("reclined=false", ""), ("reclined=true", "_reclined")]))
for b in ("popcorn_machine", "soda_fountain", "ticket_kiosk"):
    wjson("blockstates/%s.json" % b, facing_states(b))

BLOCK_ITEMS = ["recliner", "motion_seat", "popcorn_machine", "soda_fountain", "ticket_kiosk"]
ITEMS = ["popcorn", "large_popcorn", "cola", "lemon_lime_soda", "orange_soda", "blue_slushie", "bottled_water", "chocolate_bar", "gummy_bears",
         "sour_worms", "licorice", "candy_mix", "nachos", "hot_dog", "soft_pretzel", "glasses_3d", "movie_ticket"]
for b in BLOCK_ITEMS:
    wjson("items/%s.json" % b, {"model": {"type": "minecraft:model", "model": "earthlife:block/" + b}})
for i in ITEMS:
    wjson("items/%s.json" % i, {"model": {"type": "minecraft:model", "model": "earthlife:item/" + i}})
    m = {"parent": "minecraft:item/generated", "textures": {"layer0": "earthlife:item/" + i}}
    if i == "glasses_3d":
        m["display"] = {"head": {"rotation": [0, 180, 0], "translation": [0, -2.25, -7.6], "scale": [0.86, 0.86, 0.86]}}
    wjson("models/item/%s.json" % i, m)

NAMES = {"recliner": "Cinema Recliner", "motion_seat": "4D Motion Seat", "popcorn_machine": "Popcorn Machine", "soda_fountain": "Soda Fountain",
         "ticket_kiosk": "Ticket Kiosk", "popcorn": "Popcorn", "large_popcorn": "Large Popcorn", "cola": "Cola", "lemon_lime_soda": "Lemon-Lime Soda",
         "orange_soda": "Orange Soda", "blue_slushie": "Blue Raspberry Slushie", "bottled_water": "Bottled Water", "chocolate_bar": "Chocolate Bar",
         "gummy_bears": "Gummy Bears", "sour_worms": "Sour Gummy Worms", "licorice": "Licorice Twists", "candy_mix": "Candy Mix", "nachos": "Nachos with Cheese",
         "hot_dog": "Hot Dog", "soft_pretzel": "Soft Pretzel", "glasses_3d": "3D Glasses (red/cyan)", "movie_ticket": "Movie Ticket"}
lang_path = os.path.join(A, "lang", "en_us.json")
lang = json.load(open(lang_path)) if os.path.exists(lang_path) else {}
for k, v in NAMES.items():
    lang[("block." if k in BLOCK_ITEMS else "item.") + "earthlife." + k] = v
lang["itemGroup.earthlife.cinema"] = "EarthLife Cinema"
json.dump(lang, open(lang_path, "w"), indent=2, ensure_ascii=False)
print("cinema assets written to", A)
