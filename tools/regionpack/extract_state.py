#!/usr/bin/env python3
"""
Pass 1 of the Northeast region packs: one state's OpenStreetMap extract (Geofabrik .osm.pbf) -> features binned
into 1/8-degree cells (pickled per cell), plus the state's places, cinemas and major roads.

  pip install osmium shapely
  python3 tools/regionpack/extract_state.py new-york-latest.osm.pbf --state NY -o out/NY

Coordinates stay latitude/longitude. Lines and polygons that cross cells are clipped to each cell plus a 300 m
margin, so every cell can be rasterised on its own; buildings go to the cell holding their centre.
Map data (c) OpenStreetMap contributors, ODbL 1.0.
"""
import argparse, math, os, pickle, sys, json, time
from collections import defaultdict

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import cellfmt
from osm_to_earthlife import ROAD_CLASSES, LANDUSE, NATURAL, width_of, levels_of, style_of

import osmium
from shapely.geometry import LineString, Polygon, box
from shapely import make_valid

C = cellfmt.CELLS_PER_DEG
MARGIN_LAT = 0.0027          # ~300 m
MARGIN_LON = 0.0037
PLACE_KIND = {"city": "CITY", "town": "TOWN", "village": "VILLAGE", "hamlet": "HAMLET", "suburb": "SUBURB", "neighbourhood": "NEIGHBOURHOOD", "quarter": "SUBURB"}
MAJOR = {"motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary", "secondary_link", "tertiary"}


def cell_rect(lat8, lon8):
    return (lat8 / C - MARGIN_LAT, lon8 / C - MARGIN_LON, (lat8 + 1) / C + MARGIN_LAT, (lon8 + 1) / C + MARGIN_LON)


def cells_of_bbox(s, w, n, e):
    for a in range(math.floor((s - MARGIN_LAT) * C), math.floor((n + MARGIN_LAT) * C) + 1):
        for o in range(math.floor((w - MARGIN_LON) * C), math.floor((e + MARGIN_LON) * C) + 1):
            yield a, o


def bbox(coords):
    la = [c[0] for c in coords]; lo = [c[1] for c in coords]
    return min(la), min(lo), max(la), max(lo)


def simplify_line(coords, tol_deg):
    if len(coords) < 3 or tol_deg <= 0:
        return coords
    ls = LineString([(c[1], c[0]) for c in coords]).simplify(tol_deg, preserve_topology=False)
    return [(y, x) for x, y in ls.coords]


class State:
    def __init__(self):
        self.cells = defaultdict(lambda: {"roads": [], "buildings": [], "areas": [], "water": [], "pois": []})
        self.places, self.cinemas, self.major = [], [], []
        self.counts = defaultdict(int)

    # ---- lines ----
    def add_line(self, kind, rec, coords):
        s, w, n, e = bbox(coords)
        cells = list(cells_of_bbox(s, w, n, e))
        if len(cells) == 1:
            r = dict(rec); r["line"] = coords; self.cells[cells[0]][kind].append(r); return
        ls = LineString([(c[1], c[0]) for c in coords])
        for cid in cells:
            a, o, b, f = cell_rect(*cid)
            if a <= s and o <= w and b >= n and f >= e:
                r = dict(rec); r["line"] = coords; self.cells[cid][kind].append(r); continue
            clip = ls.intersection(box(o, a, f, b))
            for g in getattr(clip, "geoms", [clip]):
                if g.is_empty or g.geom_type != "LineString" or len(g.coords) < 2:
                    continue
                r = dict(rec); r["line"] = [(y, x) for x, y in g.coords]; self.cells[cid][kind].append(r)

    # ---- polygons (outer ring + holes) ----
    def add_poly(self, kind, rec, rings):
        outer = rings[0]
        s, w, n, e = bbox(outer)
        cells = list(cells_of_bbox(s, w, n, e))
        if len(cells) == 1 or (n - s) < 0.002 and (e - w) < 0.002:
            for cid in cells[:1] if len(cells) == 1 else [cellfmt.cell_of((s + n) / 2, (w + e) / 2)]:
                r = dict(rec); r["rings"] = rings; self.cells[cid][kind].append(r)
            return
        try:
            poly = make_valid(Polygon([(c[1], c[0]) for c in outer], [[(c[1], c[0]) for c in h] for h in rings[1:]]))
        except Exception:
            return
        tol = 0.000004  # ~0.4 m
        for cid in cells:
            a, o, b, f = cell_rect(*cid)
            clip = poly.intersection(box(o, a, f, b))
            for g in getattr(clip, "geoms", [clip]):
                if g.is_empty or g.geom_type != "Polygon" or g.area < 1e-10:
                    continue
                g = g.simplify(tol)
                if g.is_empty or g.geom_type != "Polygon":
                    continue
                rr = [[(y, x) for x, y in list(g.exterior.coords)[:-1]]] + [[(y, x) for x, y in list(h.coords)[:-1]] for h in g.interiors]
                if len(rr[0]) < 3:
                    continue
                r = dict(rec); r["rings"] = rr; self.cells[cid][kind].append(r)


class Handler(osmium.SimpleHandler):
    def __init__(self, st):
        super().__init__()
        self.st = st

    def node(self, n):
        t = n.tags
        if "place" in t and t["place"] in PLACE_KIND and "name" in t:
            pop = 0
            try:
                pop = int(str(t.get("population", "0")).replace(",", "").split(".")[0])
            except ValueError:
                pass
            self.st.places.append({"name": t["name"], "kind": PLACE_KIND[t["place"]], "lat": n.location.lat, "lon": n.location.lon, "population": pop})
        if t.get("amenity") == "cinema":
            self.st.cinemas.append(self.cinema(t, "n%d" % n.id, n.location.lat, n.location.lon, None))
        poi = self.poi_kind(t)
        if poi and "name" in t:
            self.st.cells[cellfmt.cell_of(n.location.lat, n.location.lon)]["pois"].append({"kind": poi, "name": t["name"], "lat": n.location.lat, "lon": n.location.lon})

    @staticmethod
    def poi_kind(t):
        if t.get("amenity") == "cinema": return "cinema"
        if t.get("railway") == "station" or t.get("public_transport") == "station": return "station"
        if t.get("amenity") == "bus_station": return "bus_station"
        if t.get("aeroway") == "aerodrome": return "airport"
        if t.get("tourism") == "museum": return "museum"
        if t.get("tourism") == "attraction": return "attraction"
        if t.get("leisure") == "stadium": return "stadium"
        if t.get("amenity") == "university": return "university"
        if t.get("amenity") == "hospital": return "hospital"
        if t.get("shop") == "mall": return "mall"
        return None

    @staticmethod
    def cinema(t, oid, lat, lon, ring):
        return {"id": oid, "name": t.get("name", "Cinema"), "brand": t.get("brand") or t.get("operator"), "lat": lat, "lon": lon,
                "screens": t.get("screen") or t.get("screens"), "ring": ring, "website": t.get("website"),
                "addr": " ".join(x for x in (t.get("addr:housenumber"), t.get("addr:street"), t.get("addr:city")) if x)}

    def way(self, w):
        t = w.tags
        hw, aw, rw = t.get("highway"), t.get("aeroway"), t.get("railway")
        if not (hw in ROAD_CLASSES or aw in ("runway", "taxiway") or (rw == "rail" and t.get("service") not in ("yard", "siding", "spur"))):
            return
        try:
            coords = [(nd.location.lat, nd.location.lon) for nd in w.nodes if nd.location.valid()]
        except osmium.InvalidLocationError:
            return
        if len(coords) < 2:
            return
        tags = {k: v for k, v in ((tg.k, tg.v) for tg in t)}
        oid = "w%d" % w.id
        if rw == "rail":
            rec = {"id": oid, "class": "RAIL", "width": 4}
        elif aw in ("runway", "taxiway"):
            rec = {"id": oid, "class": "RUNWAY" if aw == "runway" else "TAXIWAY", "width": width_of(tags, 30 if aw == "runway" else 15)}
        else:
            cls, wd = ROAD_CLASSES[hw]
            rec = {"id": oid, "class": cls, "width": width_of(tags, wd), "name": tags.get("name") or tags.get("ref")}
            if hw in MAJOR:
                self.st.major.append({"id": oid, "class": cls, "name": tags.get("name"), "ref": tags.get("ref"),
                                      "line": simplify_line(coords, 0.00004)})   # ~4 m
        self.st.add_line("roads", rec, coords)
        self.st.counts["roads"] += 1

    def area(self, a):
        t = a.tags
        tags = None
        oid = ("w%d" if a.from_way() else "r%d") % a.orig_id()
        is_building = "building" in t and t.get("building") != "no"
        nat, lu, wat = t.get("natural"), t.get("landuse"), t.get("waterway")
        is_water = nat == "water" or wat == "riverbank" or lu in ("reservoir", "basin") or ("water" in t and nat in (None, "water"))
        cover = LANDUSE.get(lu) or NATURAL.get(nat) or ("AIRPORT_APRON" if t.get("aeroway") == "apron" else None) or ("GRASS" if t.get("leisure") in ("park", "golf_course", "pitch") else None)
        cinema = t.get("amenity") == "cinema"
        if not (is_building or is_water or cover or cinema):
            return
        try:
            polys = []
            for outer in a.outer_rings():
                rr = [[(n.lat, n.lon) for n in outer][:-1]]
                for inner in a.inner_rings(outer):
                    rr.append([(n.lat, n.lon) for n in inner][:-1])
                if len(rr[0]) >= 3:
                    polys.append(rr)
        except osmium.InvalidLocationError:
            return
        if not polys:
            return
        tags = {tg.k: tg.v for tg in t}
        if cinema:
            ring = polys[0][0]
            la = sum(p[0] for p in ring) / len(ring); lo = sum(p[1] for p in ring) / len(ring)
            self.st.cinemas.append(self.cinema(tags, oid, la, lo, ring if is_building or len(ring) >= 4 else None))
        if is_building:
            kind = tags.get("building", "yes")
            if tags.get("aeroway") == "terminal": kind = "terminal"
            if tags.get("man_made") == "tower" or tags.get("aeroway") == "control_tower": kind = "control_tower"
            for rr in polys:
                ring = rr[0]
                la = sum(p[0] for p in ring) / len(ring); lo = sum(p[1] for p in ring) / len(ring)
                st = style_of(tags); st.pop("name", None); st.pop("use", None)
                self.st.cells[cellfmt.cell_of(la, lo)]["buildings"].append({
                    "id": oid, "kind": kind, "levels": levels_of(tags, kind), "style": st, "name": tags.get("name"),
                    "amenity": tags.get("amenity") or ("mall" if tags.get("shop") == "mall" else None), "ring": ring})
                self.st.counts["buildings"] += 1
            return
        if is_water:
            for rr in polys:
                self.st.add_poly("water", {"id": oid}, rr)
            self.st.counts["water"] += 1
            return
        if cover:
            for rr in polys:
                self.st.add_poly("areas", {"id": oid, "cover": cover}, rr)
            self.st.counts["areas"] += 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pbf")
    ap.add_argument("--state", required=True)
    ap.add_argument("-o", "--out", required=True)
    a = ap.parse_args()
    t0 = time.time()
    st = State()
    h = Handler(st)
    h.apply_file(a.pbf, locations=True, idx="flex_mem")
    print("parsed %s in %.0f s: %s, places=%d cinemas=%d major=%d cells=%d" % (a.pbf, time.time() - t0, dict(st.counts), len(st.places),
          len(st.cinemas), len(st.major), len(st.cells)), flush=True)
    os.makedirs(os.path.join(a.out, "cells"), exist_ok=True)
    for (lat8, lon8), c in st.cells.items():
        with open(os.path.join(a.out, "cells", "%d_%d.pkl" % (lat8, lon8)), "wb") as f:
            pickle.dump(c, f, protocol=pickle.HIGHEST_PROTOCOL)
    for p in st.places: p["state"] = a.state
    for c in st.cinemas: c["state"] = a.state
    with open(os.path.join(a.out, "globals.pkl"), "wb") as f:
        pickle.dump({"places": st.places, "cinemas": st.cinemas, "major": st.major, "counts": dict(st.counts)}, f, protocol=pickle.HIGHEST_PROTOCOL)
    print("wrote %d cells in %.0f s" % (len(st.cells), time.time() - t0))


if __name__ == "__main__":
    main()
