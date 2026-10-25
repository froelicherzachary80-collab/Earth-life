#!/usr/bin/env python3
"""
Snap hand-entered landmarks (data/landmarks.json, verified=false) to the real OpenStreetMap object of the same name.

  python3 tools/match_landmarks.py data/landmarks.json raw/western-ny*.osm.json --origin 43.2237,-77.1861 -o data/landmarks.json

For each unverified landmark, looks for an OSM node/way whose name (or alias) matches within 5 km of the approximate
position. On a match it takes the real centre, footprint length/width and orientation, and sets verified=true.
Unmatched entries are left unchanged and reported, so nothing silently pretends to be exact.
"""
import argparse, json, math, os, re, sys
sys.path.insert(0, os.path.dirname(__file__))
from geo import Projection
from osm_to_earthlife import obb


def norm(s):
    return re.sub(r"[^a-z0-9]", "", (s or "").lower())


def dist_m(a, b):
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dl = math.radians(b[1] - a[1])
    x = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * 6371008.8 * math.asin(math.sqrt(x))


def centre(el):
    if el["type"] == "node":
        return el["lat"], el["lon"]
    g = el.get("geometry") or []
    return (sum(p["lat"] for p in g) / len(g), sum(p["lon"] for p in g) / len(g)) if g else None


def match(landmarks, elements, proj):
    named = [e for e in elements if e.get("tags", {}).get("name") and (e["type"] == "node" or e.get("geometry"))]
    report = []
    for lm in landmarks:
        if lm.get("verified"):
            continue
        names = {norm(lm["name"])} | {norm(a) for a in lm.get("aliases", [])}
        best = None
        for e in named:
            n = norm(e["tags"]["name"])
            if n not in names and not any(len(x) >= 8 and (x in n or n in x) for x in names):
                continue
            c = centre(e)
            if not c:
                continue
            d = dist_m((lm["lat"], lm["lon"]), c)
            if d <= 5000 and (best is None or d < best[0] or (e["type"] == "way" and best[1]["type"] == "node")):
                best = (d, e, c)
        if not best:
            report.append("NOT FOUND: " + lm["name"])
            continue
        d, e, c = best
        lm["lat"], lm["lon"] = round(c[0], 7), round(c[1], 7)
        if e["type"] == "way":
            box = obb([proj.to_block(p["lat"], p["lon"]) for p in e["geometry"][:-1]])
            if box:
                lm["length"], lm["width"], lm["heading"] = round(box[2], 1), round(box[3], 1), round(box[4], 1)
        h = e["tags"].get("height")
        if h:
            try:
                lm["height"] = float(str(h).split()[0])
            except ValueError:
                pass
        lm["verified"] = True
        lm["osm"] = "%s/%d" % (e["type"], e["id"])
        report.append("matched %s -> %s/%d (moved %.0f m)" % (lm["name"], e["type"], e["id"], d))
    return report


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("landmarks")
    ap.add_argument("osm", nargs="+")
    ap.add_argument("--origin", required=True)
    ap.add_argument("-o", "--output", required=True)
    a = ap.parse_args()
    data = json.load(open(a.landmarks))
    elements = []
    for f in a.osm:
        elements += json.load(open(f)).get("elements", [])
    lat, lon = map(float, a.origin.split(","))
    for line in match(data["landmarks"], elements, Projection(lat, lon)):
        print(line)
    json.dump(data, open(a.output, "w"), indent=1)


if __name__ == "__main__":
    main()
