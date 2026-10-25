#!/usr/bin/env python3
"""Offline tests for the data pipeline (no network): projection parity with Java + OSM conversion."""
import json, math, os, subprocess, sys, tempfile
sys.path.insert(0, os.path.dirname(__file__))
from geo import Projection
from osm_to_earthlife import convert

fails = 0


def check(c, m):
    global fails
    print(("  PASS " if c else "  FAIL ") + m)
    fails += 0 if c else 1


p = Projection(43.2237, -77.1861)
x, z = p.to_block(43.2237, -77.1861)
check(abs(x) < 1e-6 and abs(z) < 1e-6, "origin -> 0,0")
x, z = p.to_block(43.2337, -77.1861)
lat2, lon2 = p.from_block(1234.5, -987.6)
bx, bz = p.to_block(lat2, lon2)
check(abs(bx - 1234.5) < 1e-3 and abs(bz + 987.6) < 1e-3, "from_block inverts to_block")
check(-1115 < z < -1108 and abs(x) < 0.5, "0.01 deg north ~ -1111 z (got %.2f)" % z)

osm = {"elements": [
    {"type": "way", "id": 1, "tags": {"highway": "residential", "name": "Main St"},
     "geometry": [{"lat": 43.2237, "lon": -77.1861}, {"lat": 43.2237, "lon": -77.1851}]},
    {"type": "way", "id": 2, "tags": {"building": "house", "building:levels": "2"},
     "geometry": [{"lat": 43.2240, "lon": -77.1860}, {"lat": 43.2240, "lon": -77.1859}, {"lat": 43.2241, "lon": -77.1859},
                  {"lat": 43.2241, "lon": -77.1860}, {"lat": 43.2240, "lon": -77.1860}]},
    {"type": "way", "id": 3, "tags": {"aeroway": "runway", "width": "23"},
     "geometry": [{"lat": 43.234, "lon": -77.128}, {"lat": 43.235, "lon": -77.114}]},
    {"type": "way", "id": 4, "tags": {"landuse": "orchard"},
     "geometry": [{"lat": 43.22, "lon": -77.19}, {"lat": 43.22, "lon": -77.18}, {"lat": 43.21, "lon": -77.18}, {"lat": 43.22, "lon": -77.19}]},
    {"type": "node", "id": 9, "lat": 0, "lon": 0},
    {"type": "node", "id": 10, "lat": 43.2376, "lon": -77.0617, "tags": {"place": "village", "name": "Sodus", "population": "1,800"}},
    {"type": "way", "id": 11, "tags": {"railway": "rail"}, "geometry": [{"lat": 43.10, "lon": -77.30}, {"lat": 43.10, "lon": -77.20}]},
    {"type": "node", "id": 12, "lat": 43.2716, "lon": -76.9760, "tags": {"man_made": "lighthouse", "name": "Sodus Bay Lighthouse"}},
    {"type": "way", "id": 13, "tags": {"leisure": "stadium", "name": "Innovative Field", "sport": "baseball"},
     "geometry": [{"lat": 43.1578, "lon": -77.6210}, {"lat": 43.1578, "lon": -77.6185}, {"lat": 43.1590, "lon": -77.6185}, {"lat": 43.1590, "lon": -77.6210}, {"lat": 43.1578, "lon": -77.6210}]},
    {"type": "way", "id": 14, "tags": {"building": "house", "roof:shape": "gabled", "roof:colour": "#800000", "building:colour": "white", "height": "9 m"},
     "geometry": [{"lat": 43.2250, "lon": -77.1860}, {"lat": 43.2250, "lon": -77.1858}, {"lat": 43.2251, "lon": -77.1858}, {"lat": 43.2251, "lon": -77.1860}, {"lat": 43.2250, "lon": -77.1860}]},
]}
res = convert(osm, p)
check(len(res["roads"]) == 3 and res["roads"][0]["class"] == "RESIDENTIAL", "road + runway + rail converted")
check(any(r["class"] == "RAIL" for r in res["roads"]), "railway mapped to RAIL")
check(res["towns"][0]["name"] == "Sodus" and res["towns"][0]["kind"] == "VILLAGE" and res["towns"][0]["population"] == 1800, "place node -> town")
check([r for r in res["roads"] if r["class"] == "RUNWAY"][0]["width"] == 23, "runway width from tag")
b = res["buildings"][0]
check(b["levels"] == 2 and len(b["footprint"]) == 4, "building footprint closed ring de-duplicated")
check(res["areas"][0]["cover"] == "ORCHARD", "orchard landuse")
check(all(r["prov"] == "VERIFIED" for r in res["roads"]), "provenance")
check("OpenStreetMap" in res["attribution"], "ODbL attribution carried")

# Java parity: compile-free check by running the core's projection via jshell-less tiny Java program if available.
java_cls = os.path.join(os.path.dirname(__file__), "..", "build", "core")
if os.path.isdir(java_cls):
    src = 'import earthlife.core.geo.*;public class P{public static void main(String[] a){var p=new LocalProjection(new GeoPoint(43.2237,-77.1861),63,1);var b=p.toBlock(43.30,-77.05);System.out.println(b.x()+" "+b.z());}}'
    d = tempfile.mkdtemp()
    open(os.path.join(d, "P.java"), "w").write(src)
    subprocess.run(["javac", "-cp", java_cls, "-d", d, os.path.join(d, "P.java")], check=True, stderr=subprocess.DEVNULL)
    out = subprocess.run(["java", "-cp", java_cls + os.pathsep + d, "P"], capture_output=True, text=True).stdout.split()
    jx, jz = float(out[0]), float(out[1])
    px, pz = p.to_block(43.30, -77.05)
    check(abs(jx - px) < 1e-3 and abs(jz - pz) < 1e-3, "Python and Java projections agree to 1 mm (%.4f,%.4f)" % (jx - px, jz - pz))

lms = {l["name"]: l for l in res["landmarks"]}
check(lms["Sodus Bay Lighthouse"]["type"] == "LIGHTHOUSE" and lms["Sodus Bay Lighthouse"]["verified"], "lighthouse node -> landmark")
st = lms["Innovative Field"]
check(st["type"] == "STADIUM" and 150 < st["length"] < 260, "stadium way -> landmark with real size (%.0f m)" % st["length"])
tagged = [b for b in res["buildings"] if b["id"] == "w14"][0]["style"]
check(tagged["roofShape"] == "gabled" and tagged["height"] == 9.0 and tagged["colour"] == "white", "building style tags passed through")

from match_landmarks import match
lm_list = [{"name": "Frontier Field", "aliases": ["Innovative Field"], "lat": 43.1583, "lon": -77.6198, "type": "STADIUM", "verified": False},
           {"name": "Nonexistent Place", "aliases": [], "lat": 43.0, "lon": -77.0, "type": "MONUMENT", "verified": False}]
rep = match(lm_list, osm["elements"], p)
check(lm_list[0]["verified"] and lm_list[0]["osm"] == "way/13", "alias matched to the OSM stadium")
check(not lm_list[1]["verified"] and any("NOT FOUND" in r for r in rep), "unmatched landmark left unverified")

print("pipeline: %s" % ("OK" if fails == 0 else "%d FAILED" % fails))
sys.exit(1 if fails else 0)
