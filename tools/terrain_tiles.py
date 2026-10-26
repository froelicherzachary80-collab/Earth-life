#!/usr/bin/env python3
"""
Build an EarthLife DEM grid (ELDG, see ElevationSource.Grid) from the public AWS Terrain Tiles ("Terrarium" PNGs,
https://registry.opendata.aws/terrain-tiles/). Their sources include USGS 3DEP/NED in the US and NOAA Great Lakes
bathymetry; attribution: "Terrain Tiles: Mapzen / AWS Open Data; USGS 3DEP, NOAA, SRTM and others".

  pip install pillow
  python3 tools/terrain_tiles.py --bbox 43.13,-77.45,43.32,-76.95 --origin 43.2237,-77.1861 --zoom 13 --cell 20 -o dem.eldg

Samples bilinearly on a regular grid of `cell`-metre steps in EarthLife block coordinates (x east, z south).
Tiles are cached in --cache so re-runs don't re-download.
"""
import argparse, io, math, os, struct, sys, time, urllib.request

sys.path.insert(0, os.path.dirname(__file__))
from geo import Projection

URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"


def tile_xy(lat, lon, z):
    n = 2 ** z
    x = (lon + 180) / 360 * n
    y = (1 - math.log(math.tan(math.radians(lat)) + 1 / math.cos(math.radians(lat))) / math.pi) / 2 * n
    return x, y


class Tiles:
    def __init__(self, z, cache):
        from PIL import Image
        self.Image, self.z, self.cache, self.mem = Image, z, cache, {}
        os.makedirs(cache, exist_ok=True)

    def tile(self, tx, ty):
        key = (tx, ty)
        if key in self.mem:
            return self.mem[key]
        path = os.path.join(self.cache, "%d_%d_%d.png" % (self.z, tx, ty))
        if not os.path.exists(path):
            for attempt in range(4):
                try:
                    req = urllib.request.Request(URL.format(z=self.z, x=tx, y=ty), headers={"User-Agent": "EarthLife-pipeline/0.4"})
                    with urllib.request.urlopen(req, timeout=60) as r:
                        data = r.read()
                    with open(path, "wb") as f:
                        f.write(data)
                    break
                except Exception as e:
                    print("retry", tx, ty, e)
                    time.sleep(2 + attempt * 3)
            else:
                raise SystemExit("could not download tile %d/%d/%d" % (self.z, tx, ty))
        img = self.Image.open(path).convert("RGB")
        px = img.load()
        w, h = img.size
        vals = [[(px[i, j][0] * 256 + px[i, j][1] + px[i, j][2] / 256) - 32768 for i in range(w)] for j in range(h)]
        self.mem[key] = vals
        return vals

    def elevation(self, lat, lon):
        fx, fy = tile_xy(lat, lon, self.z)
        px, py = fx * 256 - 0.5, fy * 256 - 0.5
        x0, y0 = math.floor(px), math.floor(py)
        ax, ay = px - x0, py - y0

        def at(x, y):
            t = self.tile(x // 256, y // 256)
            return t[y % 256][x % 256]

        a, b, c, d = at(x0, y0), at(x0 + 1, y0), at(x0, y0 + 1), at(x0 + 1, y0 + 1)
        return (a * (1 - ax) + b * ax) * (1 - ay) + (c * (1 - ax) + d * ax) * ay


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bbox", required=True, help="south,west,north,east")
    ap.add_argument("--origin", required=True)
    ap.add_argument("--zoom", type=int, default=13)
    ap.add_argument("--cell", type=float, default=20)
    ap.add_argument("--cache", default="raw/terrain-cache")
    ap.add_argument("-o", "--output", required=True)
    a = ap.parse_args()
    s, w, n, e = map(float, a.bbox.split(","))
    lat0, lon0 = map(float, a.origin.split(","))
    proj = Projection(lat0, lon0)
    corners = [proj.to_block(la, lo) for la in (s, n) for lo in (w, e)]
    minx, maxx = min(c[0] for c in corners), max(c[0] for c in corners)
    minz, maxz = min(c[1] for c in corners), max(c[1] for c in corners)
    cols = int((maxx - minx) / a.cell) + 1
    rows = int((maxz - minz) / a.cell) + 1
    tiles = Tiles(a.zoom, a.cache)
    out = bytearray()
    lo_v, hi_v = 1e9, -1e9
    for j in range(rows):
        for i in range(cols):
            lat, lon = proj.from_block(minx + i * a.cell, minz + j * a.cell)
            v = tiles.elevation(lat, lon)
            lo_v, hi_v = min(lo_v, v), max(hi_v, v)
            out += struct.pack(">f", v)
        if j % 100 == 0:
            print("row %d/%d" % (j, rows), flush=True)
    os.makedirs(os.path.dirname(os.path.abspath(a.output)), exist_ok=True)
    with open(a.output, "wb") as f:
        f.write(b"ELDG" + struct.pack(">i", 1) + struct.pack(">ddd", minx, minz, a.cell) + struct.pack(">ii", cols, rows))
        f.write(out)
    print("%dx%d cells of %.0f m, elevation %.1f..%.1f m -> %s" % (cols, rows, a.cell, lo_v, hi_v, a.output))


if __name__ == "__main__":
    main()
