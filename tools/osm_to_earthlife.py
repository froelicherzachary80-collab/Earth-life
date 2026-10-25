#!/usr/bin/env python3
"""
Convert OpenStreetMap data (Overpass API JSON with `out geom;`) into an EarthLife region feature file.

  python3 tools/fetch_osm.py --bbox 43.17,-77.27,43.28,-77.07 -o raw/williamson.osm.json
  python3 tools/osm_to_earthlife.py raw/williamson.osm.json --origin 43.2237,-77.1861 -o data/regions/williamson/features.json

Output coordinates are blocks (1 block = 1 m) relative to the region origin. Every feature is marked
VERIFIED (geometry from OSM). Anything the generator later invents is marked ESTIMATED.
Data (c) OpenStreetMap contributors, ODbL 1.0. The attribution string travels with the file and is shown in-game.
"""
import argparse, json, sys, os
sys.path.insert(0, os.path.dirname(__file__))
from geo import Projection

ROAD_CLASSES = {
    "motorway": ("MOTORWAY", 14), "motorway_link": ("MOTORWAY", 7), "trunk": ("PRIMARY", 12), "trunk_link": ("PRIMARY", 7),
    "primary": ("PRIMARY", 10), "primary_link": ("PRIMARY", 7), "secondary": ("SECONDARY", 9), "secondary_link": ("SECONDARY", 7),
    "tertiary": ("SECONDARY", 8), "unclassified": ("RESIDENTIAL", 6), "residential": ("RESIDENTIAL", 6), "living_street": ("RESIDENTIAL", 5),
    "service": ("SERVICE", 4), "track": ("PATH", 3), "footway": ("PATH", 2), "path": ("PATH", 2), "cycleway": ("PATH", 2),
}
LANDUSE = {
    "farmland": "FARMLAND", "farmyard": "FARMLAND", "orchard": "ORCHARD", "vineyard": "ORCHARD", "forest": "FOREST",
    "meadow": "GRASS", "grass": "GRASS", "recreation_ground": "GRASS", "residential": "RESIDENTIAL", "industrial": "INDUSTRIAL",
    "commercial": "INDUSTRIAL", "retail": "INDUSTRIAL",
}
PLACE_KIND = {"city": "CITY", "town": "TOWN", "village": "VILLAGE", "hamlet": "HAMLET"}
NATURAL = {"wood": "FOREST", "scrub": "GRASS", "grassland": "GRASS", "beach": "BEACH", "sand": "BEACH", "wetland": "WETLAND"}


def width_of(tags, default):
    for k in ("width", "est_width"):
        try:
            return max(1.0, float(str(tags[k]).split()[0]))
        except (KeyError, ValueError):
            pass
    if "lanes" in tags:
        try:
            return max(default, int(tags["lanes"]) * 3.5)
        except ValueError:
            pass
    return float(default)


def levels_of(tags, kind):
    for k in ("building:levels",):
        try:
            return max(1, int(float(tags[k])))
        except (KeyError, ValueError):
            pass
    try:
        return max(1, round(float(str(tags["height"]).split()[0]) / 3))
    except (KeyError, ValueError):
        pass
    return {"house": 2, "detached": 2, "apartments": 3, "barn": 1, "shed": 1, "garage": 1, "commercial": 2, "school": 2}.get(kind, 1)


def style_of(tags):
    """Real appearance tags passed through to the game (only the ones present)."""
    st = {}
    for key, out in (("height", "height"), ("roof:height", "roofHeight"), ("roof:shape", "roofShape"), ("roof:colour", "roofColour"),
                     ("building:colour", "colour"), ("building:material", "material"), ("name", "name"), ("building", "use")):
        if key in tags:
            st[out] = tags[key]
    for k in ("height", "roofHeight"):
        if k in st:
            try:
                st[k] = float(str(st[k]).split()[0].replace("m", ""))
            except ValueError:
                del st[k]
    return st


def obb(points):
    """Minimum-area rectangle over edge directions: (cx, cz, length, width, heading_deg of the long axis)."""
    import math
    best = None
    n = len(points)
    for i in range(n):
        (ax, az), (bx, bz) = points[i], points[(i + 1) % n]
        L = math.hypot(bx - ax, bz - az)
        if L < 1e-6:
            continue
        ux, uz = (bx - ax) / L, (bz - az) / L
        us = [p[0] * ux + p[1] * uz for p in points]
        vs = [-p[0] * uz + p[1] * ux for p in points]
        area = (max(us) - min(us)) * (max(vs) - min(vs))
        if best is None or area < best[0]:
            cu, cv = (max(us) + min(us)) / 2, (max(vs) + min(vs)) / 2
            lu, lv = max(us) - min(us), max(vs) - min(vs)
            if lv > lu:
                ux, uz, lu, lv = -uz, ux, lv, lu
            best = (area, cu * (bx - ax) / L - cv * (bz - az) / L, cu * (bz - az) / L + cv * (bx - ax) / L, lu, lv,
                    (math.degrees(math.atan2(ux, -uz)) + 360) % 360)
    return best[1:] if best else None


def landmark_of(el, tags):
    """OSM object -> EarthLife landmark type, or None."""
    name = tags.get("name")
    mm, lei, tour, hist = tags.get("man_made"), tags.get("leisure"), tags.get("tourism"), tags.get("historic")
    if mm == "lighthouse":
        return "LIGHTHOUSE"
    if mm == "water_tower":
        return "WATER_TOWER"
    if tour == "theme_park" and name:
        return "AMUSEMENT_PARK"
    if hist == "monument" and name:
        return "MONUMENT"
    if lei == "stadium" and name:
        return "ARENA" if "arena" in name.lower() or tags.get("building") == "yes" and "baseball" not in tags.get("sport", "") else "STADIUM"
    if tags.get("theatre:type") == "amphi" and name:
        return "AMPHITHEATER"
    if tags.get("building") in ("church", "cathedral") and name:
        return "CHURCH"
    try:
        h = float(str(tags.get("height", "0")).split()[0])
    except ValueError:
        h = 0
    try:
        lv = int(float(tags.get("building:levels", "0")))
    except ValueError:
        lv = 0
    if "building" in tags and (h >= 60 or lv >= 15):
        return "TOWER"
    return None


def convert(osm, proj):
    out = {"attribution": "Map data (c) OpenStreetMap contributors, ODbL 1.0", "origin": [proj.lat0, proj.lon0],
           "roads": [], "buildings": [], "areas": [], "water": []}

    def pts(geom, closed=False):
        p = [[round(c, 2) for c in proj.to_block(g["lat"], g["lon"])] for g in geom]
        if closed and len(p) > 1 and p[0] == p[-1]:
            p = p[:-1]
        return p

    for el in osm.get("elements", []):
        ltype = landmark_of(el, el.get("tags", {}))
        if ltype:
            t = el["tags"]
            lm = {"id": "osm-%s-%d" % (el["type"], el["id"]), "name": t.get("name", ltype.title()), "type": ltype, "verified": True, "aliases": []}
            if el.get("type") == "node":
                lm["lat"], lm["lon"] = el["lat"], el["lon"]
            elif "geometry" in el:
                g = el["geometry"]
                lm["lat"] = sum(p["lat"] for p in g) / len(g)
                lm["lon"] = sum(p["lon"] for p in g) / len(g)
                box = obb([proj.to_block(p["lat"], p["lon"]) for p in g[:-1] if g])
                if box:
                    lm["length"], lm["width"], lm["heading"] = round(box[2], 1), round(box[3], 1), round(box[4], 1)
            st = style_of(t)
            if "height" in st:
                lm["height"] = st["height"]
            out.setdefault("landmarks", []).append(lm)
        if el.get("type") == "node" and el.get("tags", {}).get("place") in PLACE_KIND:
            t = el["tags"]
            pop = 0
            try:
                pop = int(str(t.get("population", "0")).replace(",", ""))
            except ValueError:
                pass
            out.setdefault("towns", []).append({"name": t.get("name", "?"), "kind": PLACE_KIND[t["place"]], "lat": el["lat"], "lon": el["lon"],
                                                "population": pop, "verified": True})
            continue
        if el.get("type") != "way" or "geometry" not in el:
            continue
        tags, geom, wid = el.get("tags", {}), el["geometry"], "w%d" % el["id"]
        if len(geom) < 2:
            continue
        hw, aw = tags.get("highway"), tags.get("aeroway")
        if tags.get("railway") == "rail" and tags.get("service") not in ("yard", "siding", "spur"):
            out["roads"].append({"id": wid, "class": "RAIL", "width": 4, "line": pts(geom), "prov": "VERIFIED"})
        elif aw in ("runway", "taxiway"):
            cls = "RUNWAY" if aw == "runway" else "TAXIWAY"
            out["roads"].append({"id": wid, "class": cls, "width": width_of(tags, 30 if aw == "runway" else 15), "line": pts(geom), "prov": "VERIFIED"})
        elif hw in ROAD_CLASSES:
            cls, w = ROAD_CLASSES[hw]
            out["roads"].append({"id": wid, "class": cls, "width": width_of(tags, w), "line": pts(geom), "prov": "VERIFIED"})
        elif "building" in tags and len(geom) >= 4:
            kind = tags.get("building", "yes")
            if aw == "terminal":
                kind = "terminal"
            if tags.get("man_made") == "tower" or aw == "control_tower" or tags.get("tower:type") == "air_traffic_control":
                kind = "control_tower"
            out["buildings"].append({"id": wid, "kind": kind, "levels": levels_of(tags, kind), "footprint": pts(geom, True),
                                     "name": tags.get("name"), "prov": "VERIFIED", "style": style_of(tags)})
        elif tags.get("natural") == "water" or tags.get("waterway") == "riverbank" or tags.get("water"):
            if len(geom) >= 4:
                out["water"].append({"id": wid, "ring": pts(geom, True), "prov": "VERIFIED"})
        elif tags.get("landuse") in LANDUSE or tags.get("natural") in NATURAL or aw == "apron" or tags.get("leisure") == "park":
            cover = LANDUSE.get(tags.get("landuse")) or NATURAL.get(tags.get("natural")) or ("AIRPORT_APRON" if aw == "apron" else "GRASS")
            if len(geom) >= 4:
                out["areas"].append({"id": wid, "cover": cover, "ring": pts(geom, True), "prov": "VERIFIED"})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("input", nargs="+", help="one or more Overpass JSON files (pieces of a large region)")
    ap.add_argument("--towns-out", help="also write towns.json from OSM place nodes")
    ap.add_argument("--landmarks-out", help="also write landmarks.json (lighthouses, stadiums, arenas, churches, towers...)")
    ap.add_argument("--origin", required=True, help="lat,lon of region origin (block 0,0)")
    ap.add_argument("-o", "--output", required=True)
    a = ap.parse_args()
    lat, lon = map(float, a.origin.split(","))
    elements, seen = [], set()
    for path in a.input:
        with open(path) as f:
            for el in json.load(f).get("elements", []):
                key = (el.get("type"), el.get("id"))
                if key not in seen:
                    seen.add(key)
                    elements.append(el)
    res = convert({"elements": elements}, Projection(lat, lon))
    towns = res.pop("towns", [])
    landmarks = res.pop("landmarks", [])
    if a.landmarks_out:
        with open(a.landmarks_out, "w") as f:
            json.dump({"_source": "OpenStreetMap (ODbL)", "landmarks": landmarks}, f, indent=1)
        print("%d landmarks -> %s" % (len(landmarks), a.landmarks_out))
    if a.towns_out:
        with open(a.towns_out, "w") as f:
            json.dump({"_source": "OpenStreetMap place nodes (ODbL)", "towns": towns}, f, indent=1)
        print("%d towns -> %s" % (len(towns), a.towns_out))
    os.makedirs(os.path.dirname(os.path.abspath(a.output)), exist_ok=True)
    with open(a.output, "w") as f:
        json.dump(res, f, separators=(",", ":"))
    print("roads=%d buildings=%d areas=%d water=%d -> %s" % (len(res["roads"]), len(res["buildings"]), len(res["areas"]), len(res["water"]), a.output))


if __name__ == "__main__":
    main()
