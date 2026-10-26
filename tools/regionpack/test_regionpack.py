#!/usr/bin/env python3
"""Offline self-test of the region-pack cell format (writer/reader round trip)."""
import os, random, sys
sys.path.insert(0, os.path.dirname(__file__))
import cellfmt

c = cellfmt.Cell(345, -620)
c.tier = 2
c.roads.append({"id": "w123", "class": "PRIMARY", "width": 10.5, "name": "Main St", "line": [(43.13, -77.45), (43.131, -77.449)]})
c.buildings.append({"id": "r55", "kind": "house", "levels": 2, "style": {"height": 7.5, "roofShape": "gabled"}, "name": None, "amenity": "cinema",
                    "ring": [(43.13, -77.45), (43.1301, -77.45), (43.1301, -77.4499)]})
c.areas.append({"id": "w9", "cover": "FOREST", "rings": [[(43.13, -77.45), (43.14, -77.45), (43.14, -77.44)], [(43.135, -77.448), (43.136, -77.448), (43.136, -77.447)]]})
c.water.append({"id": "w10", "rings": [[(43.13, -77.45), (43.14, -77.45), (43.14, -77.44)]]})
c.pois.append({"kind": "cinema", "name": "Regal", "lat": 43.133, "lon": -77.441})
n = 5
vals = [random.randint(-500, 3000) for _ in range(n * n)]
mask = bytes([0] * 10 + [2] * 15)
c.dem = (n, vals, mask)
b = c.write()
r = cellfmt.read(b)
assert r.dem[1] == vals and r.dem[2] == mask, "dem"
assert abs(r.roads[0]["line"][1][1] + 77.449) < 1e-7 and r.roads[0]["name"] == "Main St" and r.roads[0]["width"] == 10.5
assert r.buildings[0]["amenity"] == 1 and r.buildings[0]["style"]["height"] == 7.5 and r.buildings[0]["id"] == "r55"
assert len(r.areas[0]["rings"]) == 2 and r.pois[0]["name"] == "Regal"
assert cellfmt.cell_of(43.2237, -77.1861) == (345, -618) and cellfmt.pack_of_cell(345, -618) == (86, -155)
print("region pack format OK (%d bytes)" % len(b))
