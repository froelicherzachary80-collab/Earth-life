"""
EarthLife region-pack cell format "ELC1" (read by core/.../world/RegionCell.java). Pure Python, no dependencies.

The Northeast packs (New York, Pennsylvania, New Jersey, Vermont) cover the land in cells of 1/8 x 1/8 degree
(about 14 x 10 km). Coordinates are stored geographically (latitude/longitude in 1e-7 degree units, ~1 cm), so the
game can project them with whatever projection the world uses. 16 cells (1/2 x 1/2 degree) are zipped into a pack.

All integers are big-endian (Java DataInputStream). Varints are unsigned LEB128; signed values are zigzag-encoded.

  "ELC1" u8 version(1) i32 lat8 i32 lon8 u8 tier
  strings:   varint n, n x (varint len, utf8)
  roads:     varint n, n x (u8 idType, varint id, u8 class, u16 width_dm, varint name+1, pts)
  buildings: varint n, n x (u8 idType, varint id, varint kind+1, u8 levels, u16 height_dm, u16 roofHeight_dm,
                            varint roofShape+1, varint colour+1, varint roofColour+1, varint material+1,
                            varint name+1, u8 amenity, ring)
  areas:     varint n, n x (u8 idType, varint id, u8 cover, varint nRings, rings (outer first))
  water:     varint n, n x (u8 idType, varint id, varint nRings, rings (outer first))
  pois:      varint n, n x (u8 kind, varint name+1, svarint dlat, svarint dlon)
  dem:       u16 n (samples per side, corners inclusive, south->north rows, west->east columns),
             n*n svarint delta-coded elevation in decimetres (delta from previous sample in row; first of a row from
             first of the previous row), then mask runs: varint count, (varint runLength, u8 value)...
  pts/ring:  varint np, then np x (svarint dlat, svarint dlon) - first point relative to the cell's SW corner,
             later points relative to the previous point.
"""
import struct

CELLS_PER_DEG = 8
PACK_PER_DEG = 2
E7 = 10_000_000

ROAD_CLASSES = ["MOTORWAY", "PRIMARY", "SECONDARY", "RESIDENTIAL", "SERVICE", "PATH", "RUNWAY", "TAXIWAY", "RAIL"]
COVERS = ["FARMLAND", "ORCHARD", "FOREST", "GRASS", "RESIDENTIAL", "INDUSTRIAL", "BEACH", "WETLAND", "AIRPORT_APRON"]
TIERS = ["TERRAIN", "RURAL", "TOWN", "CITY"]
AMENITY = {"cinema": 1, "theatre": 2, "school": 3, "hospital": 4, "fire_station": 5, "police": 6, "library": 7,
           "place_of_worship": 8, "restaurant": 9, "fast_food": 10, "cafe": 11, "bank": 12, "fuel": 13, "pharmacy": 14,
           "supermarket": 15, "mall": 16, "townhall": 17, "post_office": 18, "parking": 19, "hotel": 20}
POI_KINDS = {"cinema": 1, "station": 2, "bus_station": 3, "airport": 4, "attraction": 5, "museum": 6, "stadium": 7,
             "park": 8, "university": 9, "hospital": 10, "mall": 11}
# DEM mask values: 0 land, 1 sea (surface 0 m), 2 Lake Ontario (74.0 m), 3 Lake Erie (174.0 m)
MASK_SURFACE = {1: 0.0, 2: 74.0, 3: 174.0}
ID_TYPE = {"n": 0, "w": 1, "r": 2, "x": 3}


def cell_of(lat, lon):
    import math
    return math.floor(lat * CELLS_PER_DEG), math.floor(lon * CELLS_PER_DEG)


def pack_of_cell(lat8, lon8):
    return lat8 // (CELLS_PER_DEG // PACK_PER_DEG), lon8 // (CELLS_PER_DEG // PACK_PER_DEG)


def pack_name(lat2, lon2):
    return "p_%d_%d.zip" % (lat2, lon2)


def cell_name(lat8, lon8):
    return "c_%d_%d.elc" % (lat8, lon8)


class Writer:
    def __init__(self):
        self.b = bytearray()

    def u8(self, v): self.b += struct.pack(">B", max(0, min(255, int(v))))
    def u16(self, v): self.b += struct.pack(">H", max(0, min(65535, int(round(v)))))
    def i32(self, v): self.b += struct.pack(">i", int(v))

    def varint(self, v):
        v = int(v)
        assert v >= 0, v
        while True:
            x = v & 0x7F
            v >>= 7
            if v:
                self.b.append(x | 0x80)
            else:
                self.b.append(x)
                return

    def svarint(self, v):
        v = int(v)
        self.varint((v << 1) ^ (v >> 63) if v < 0 else v << 1)

    def utf(self, s):
        e = s.encode("utf-8")
        self.varint(len(e))
        self.b += e


class Reader:
    def __init__(self, b):
        self.b, self.i = b, 0

    def u8(self):
        v = self.b[self.i]; self.i += 1; return v

    def u16(self):
        v = struct.unpack_from(">H", self.b, self.i)[0]; self.i += 2; return v

    def i32(self):
        v = struct.unpack_from(">i", self.b, self.i)[0]; self.i += 4; return v

    def varint(self):
        shift = v = 0
        while True:
            x = self.b[self.i]; self.i += 1
            v |= (x & 0x7F) << shift
            if not x & 0x80:
                return v
            shift += 7

    def svarint(self):
        v = self.varint()
        return (v >> 1) ^ -(v & 1)

    def utf(self):
        n = self.varint()
        s = self.b[self.i:self.i + n].decode("utf-8"); self.i += n; return s


class Cell:
    """Features of one cell, coordinates in degrees. Built by extract_state.py, written by write()."""

    def __init__(self, lat8, lon8):
        self.lat8, self.lon8 = lat8, lon8
        self.tier = 0
        self.roads, self.buildings, self.areas, self.water, self.pois = [], [], [], [], []
        self.dem = None          # (n, list of decimetre ints n*n, mask bytes n*n)

    def write(self):
        strings, sidx = [], {}

        def s(v):
            if v is None or v == "":
                return 0
            v = str(v)[:120]
            if v not in sidx:
                sidx[v] = len(strings); strings.append(v)
            return sidx[v] + 1

        body = Writer()
        lat0, lon0 = self.lat8 * E7 // CELLS_PER_DEG, self.lon8 * E7 // CELLS_PER_DEG

        def pts(w, coords):
            w.varint(len(coords))
            pl, po = lat0, lon0
            for lat, lon in coords:
                a, o = int(round(lat * E7)), int(round(lon * E7))
                w.svarint(a - pl); w.svarint(o - po)
                pl, po = a, o

        def oid(w, i):
            w.u8(ID_TYPE.get(i[0], 3)); w.varint(int(i[1:]) if i[1:].isdigit() else 0)

        body.varint(len(self.roads))
        for r in self.roads:
            oid(body, r["id"]); body.u8(ROAD_CLASSES.index(r["class"])); body.u16(r["width"] * 10); body.varint(s(r.get("name"))); pts(body, r["line"])
        body.varint(len(self.buildings))
        for b in self.buildings:
            st = b.get("style", {})
            oid(body, b["id"]); body.varint(s(b.get("kind"))); body.u8(b.get("levels", 1))
            body.u16(st.get("height", 0) * 10); body.u16(st.get("roofHeight", 0) * 10)
            for k in ("roofShape", "colour", "roofColour", "material"):
                body.varint(s(st.get(k)))
            body.varint(s(b.get("name"))); body.u8(AMENITY.get(b.get("amenity"), 0)); pts(body, b["ring"])
        body.varint(len(self.areas))
        for a in self.areas:
            oid(body, a["id"]); body.u8(COVERS.index(a["cover"])); body.varint(len(a["rings"]))
            for ring in a["rings"]:
                pts(body, ring)
        body.varint(len(self.water))
        for w in self.water:
            oid(body, w["id"]); body.varint(len(w["rings"]))
            for ring in w["rings"]:
                pts(body, ring)
        body.varint(len(self.pois))
        for p in self.pois:
            body.u8(POI_KINDS.get(p["kind"], 0)); body.varint(s(p.get("name")))
            body.svarint(int(round(p["lat"] * E7)) - lat0); body.svarint(int(round(p["lon"] * E7)) - lon0)
        if self.dem is None:
            body.u16(0)
        else:
            n, vals, mask = self.dem
            body.u16(n)
            for j in range(n):
                row = j * n
                prev = vals[row - n] if j else 0
                for i in range(n):
                    v = vals[row + i]
                    body.svarint(v - prev)
                    prev = v
            runs, k = [], 0
            while k < len(mask):
                m = mask[k]; e = k
                while e < len(mask) and mask[e] == m:
                    e += 1
                runs.append((e - k, m)); k = e
            body.varint(len(runs))
            for ln, m in runs:
                body.varint(ln); body.u8(m)

        head = Writer()
        head.b += b"ELC1"; head.u8(1); head.i32(self.lat8); head.i32(self.lon8); head.u8(self.tier)
        head.varint(len(strings))
        for t in strings:
            head.utf(t)
        return bytes(head.b + body.b)


def read(data):
    """Parses a cell (used by the pipeline self-test; the game reads cells in Java)."""
    r = Reader(data)
    assert data[:4] == b"ELC1"; r.i = 4
    r.u8(); lat8, lon8 = r.i32(), r.i32()
    c = Cell(lat8, lon8); c.tier = r.u8()
    strings = [r.utf() for _ in range(r.varint())]
    st = lambda k: strings[k - 1] if k else None
    lat0, lon0 = lat8 * E7 // CELLS_PER_DEG, lon8 * E7 // CELLS_PER_DEG
    inv = {v: k for k, v in ID_TYPE.items()}

    def pts():
        out, pl, po = [], lat0, lon0
        for _ in range(r.varint()):
            pl += r.svarint(); po += r.svarint(); out.append((pl / E7, po / E7))
        return out

    def oid():
        t = r.u8(); return inv.get(t, "x") + str(r.varint())

    for _ in range(r.varint()):
        i = oid(); cls = ROAD_CLASSES[r.u8()]; w = r.u16() / 10; name = st(r.varint())
        c.roads.append({"id": i, "class": cls, "width": w, "name": name, "line": pts()})
    for _ in range(r.varint()):
        i = oid(); kind = st(r.varint()); lv = r.u8(); h = r.u16() / 10; rh = r.u16() / 10
        style = {k: st(r.varint()) for k in ("roofShape", "colour", "roofColour", "material")}
        style.update(height=h, roofHeight=rh)
        name = st(r.varint()); am = r.u8()
        c.buildings.append({"id": i, "kind": kind, "levels": lv, "style": style, "name": name, "amenity": am, "ring": pts()})
    for _ in range(r.varint()):
        i = oid(); cov = COVERS[r.u8()]
        c.areas.append({"id": i, "cover": cov, "rings": [pts() for _ in range(r.varint())]})
    for _ in range(r.varint()):
        i = oid()
        c.water.append({"id": i, "rings": [pts() for _ in range(r.varint())]})
    for _ in range(r.varint()):
        k = r.u8(); name = st(r.varint())
        c.pois.append({"kind": k, "name": name, "lat": (lat0 + r.svarint()) / E7, "lon": (lon0 + r.svarint()) / E7})
    n = r.u16()
    if n:
        vals = [0] * (n * n)
        for j in range(n):
            prev = vals[(j - 1) * n] if j else 0
            for i in range(n):
                prev += r.svarint(); vals[j * n + i] = prev
        mask = bytearray()
        for _ in range(r.varint()):
            ln = r.varint(); m = r.u8(); mask += bytes([m]) * ln
        c.dem = (n, vals, bytes(mask))
    return c
