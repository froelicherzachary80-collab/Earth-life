#!/usr/bin/env python3
"""
Download OpenStreetMap ways for a bounding box from the public Overpass API (run on your own PC).
Respect the Overpass usage policy: one request per region, cache the result, don't hammer the server.

  python3 tools/fetch_osm.py -o raw/western-ny.osm.json
  (default bbox covers the V0.2 Western NY region. Large areas: Overpass may time out; split the bbox into
   pieces and pass several files to osm_to_earthlife.py.)
"""
import argparse, json, urllib.request, urllib.parse, os, time

QUERY = """[out:json][timeout:180];
(
  way["highway"]({b});
  way["building"]({b});
  way["landuse"]({b});
  way["natural"]({b});
  way["water"]({b});
  way["waterway"="riverbank"]({b});
  way["aeroway"]({b});
  way["leisure"="park"]({b});
  way["railway"="rail"]({b});
  way["natural"="coastline"]({b});
  node["place"~"^(city|town|village|hamlet)$"]({b});
  node["man_made"~"^(lighthouse|water_tower)$"]({b});
  node["historic"="monument"]({b});
  way["tourism"="theme_park"]({b});
  way["man_made"~"^(lighthouse|water_tower)$"]({b});
);
out geom;"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bbox", default="42.85,-77.75,43.30,-76.80",
                    help="south,west,north,east (default: Western NY region, Rochester to Wolcott, Lake Ontario to Canandaigua)")
    ap.add_argument("--endpoint", default="https://overpass-api.de/api/interpreter")
    ap.add_argument("--split", type=int, default=1, help="split the bbox into N x N requests (merged into one file)")
    ap.add_argument("-o", "--output", required=True)
    a = ap.parse_args()
    s, w, n, e = map(float, a.bbox.split(","))
    endpoints = [a.endpoint, "https://overpass.kumi.systems/api/interpreter", "https://overpass.private.coffee/api/interpreter"]
    elements, seen = [], set()
    for i in range(a.split):
        for j in range(a.split):
            b = "%.5f,%.5f,%.5f,%.5f" % (s + (n - s) * i / a.split, w + (e - w) * j / a.split, s + (n - s) * (i + 1) / a.split, w + (e - w) * (j + 1) / a.split)
            data = urllib.parse.urlencode({"data": QUERY.format(b=b)}).encode()
            for attempt in range(6):
                url = endpoints[attempt % len(endpoints)]
                try:
                    req = urllib.request.Request(url, data=data, headers={"User-Agent": "EarthLife-pipeline/0.4 (Minecraft mod data build)"})
                    with urllib.request.urlopen(req, timeout=300) as r:
                        part = json.load(r)
                    break
                except Exception as ex:
                    print("piece", b, "attempt", attempt, url, ex, flush=True)
                    time.sleep(20 + attempt * 20)
            else:
                raise SystemExit("Overpass failed for " + b)
            for el in part.get("elements", []):
                k = (el.get("type"), el.get("id"))
                if k not in seen:
                    seen.add(k); elements.append(el)
            print("piece", b, len(part.get("elements", [])), "elements", flush=True)
            time.sleep(5)
    osm = {"elements": elements}
    os.makedirs(os.path.dirname(os.path.abspath(a.output)), exist_ok=True)
    with open(a.output, "w") as f:
        json.dump(osm, f)
    print("%d elements -> %s" % (len(osm.get("elements", [])), a.output))


if __name__ == "__main__":
    main()
