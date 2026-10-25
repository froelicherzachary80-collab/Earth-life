#!/usr/bin/env python3
"""
Download OpenStreetMap ways for a bounding box from the public Overpass API (run on your own PC).
Respect the Overpass usage policy: one request per region, cache the result, don't hammer the server.

  python3 tools/fetch_osm.py -o raw/western-ny.osm.json
  (default bbox covers the V0.2 Western NY region. Large areas: Overpass may time out; split the bbox into
   pieces and pass several files to osm_to_earthlife.py.)
"""
import argparse, json, urllib.request, urllib.parse, os

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
    ap.add_argument("-o", "--output", required=True)
    a = ap.parse_args()
    data = urllib.parse.urlencode({"data": QUERY.format(b=a.bbox)}).encode()
    req = urllib.request.Request(a.endpoint, data=data, headers={"User-Agent": "EarthLife-pipeline/0.1"})
    with urllib.request.urlopen(req, timeout=300) as r:
        osm = json.load(r)
    os.makedirs(os.path.dirname(os.path.abspath(a.output)), exist_ok=True)
    with open(a.output, "w") as f:
        json.dump(osm, f)
    print("%d elements -> %s" % (len(osm.get("elements", [])), a.output))


if __name__ == "__main__":
    main()
