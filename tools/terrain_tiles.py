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


def lake_fill(buf, cols, rows, minx, minz, cell, proj, a):
    """Terrain tiles carry the Great Lakes' flat water surface, not the lake bed. Flood-fill the lake from a seed
    and give it a bed that deepens away from the shore (1 m at the shore, ~0.6 m per 10 m, max 40 m), so the lake
    becomes real water in Minecraft (below Y 63) and blue on the LifeMap."""
    from collections import deque
    vals = list(struct.unpack(">%df" % (cols * rows), bytes(buf)))
    lat, lon = map(float, a.lake_seed.split(","))
    sx, sz = proj.to_block(lat, lon)
    i0, j0 = int((sx - minx) / cell), int((sz - minz) / cell)
    i0, j0 = max(0, min(cols - 1, i0)), max(0, min(rows - 1, j0))
    if vals[j0 * cols + i0] > a.lake_level:
        print("lake seed is not on low water (%.1f m); no lake fill" % vals[j0 * cols + i0]); return buf
    lake = bytearray(cols * rows)
    q = deque([(i0, j0)]); lake[j0 * cols + i0] = 1
    while q:
        i, j = q.popleft()
        for di, dj in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            ni, nj = i + di, j + dj
            if 0 <= ni < cols and 0 <= nj < rows and not lake[nj * cols + ni] and vals[nj * cols + ni] <= a.lake_level:
                lake[nj * cols + ni] = 1; q.append((ni, nj))
    # distance (in cells) from the shore, multi-source BFS from lake cells next to land
    dist = [-1] * (cols * rows)
    q = deque()
    for j in range(rows):
        for i in range(cols):
            k = j * cols + i
            if lake[k] and any(0 <= i + di < cols and 0 <= j + dj < rows and not lake[(j + dj) * cols + i + di] for di, dj in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                dist[k] = 0; q.append((i, j))
    while q:
        i, j = q.popleft()
        for di, dj in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            ni, nj = i + di, j + dj
            if 0 <= ni < cols and 0 <= nj < rows:
                k = nj * cols + ni
                if lake[k] and dist[k] < 0:
                    dist[k] = dist[j * cols + i] + 1; q.append((ni, nj))
    n = 0
    for k in range(cols * rows):
        if lake[k]:
            d = max(0, dist[k]) * cell
            vals[k] = a.lake_surface - min(40.0, 1.0 + d * 0.06)
            n += 1
    print("lake fill: %d cells (%.1f km2) set below the %.1f m surface" % (n, n * cell * cell / 1e6, a.lake_surface))
    return bytearray(struct.pack(">%df" % (cols * rows), *vals))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bbox", required=True, help="south,west,north,east")
    ap.add_argument("--origin", required=True)
    ap.add_argument("--zoom", type=int, default=13)
    ap.add_argument("--cell", type=float, default=20)
    ap.add_argument("--cache", default="raw/terrain-cache")
    ap.add_argument("--lake-seed", help="lat,lon inside a large lake (e.g. Lake Ontario) whose DEM shows only its flat surface")
    ap.add_argument("--lake-level", type=float, default=75.2, help="cells connected to the seed at or below this elevation are lake")
    ap.add_argument("--lake-surface", type=float, default=74.2)
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
    if a.lake_seed:
        out = lake_fill(out, cols, rows, minx, minz, a.cell, proj, a)
    os.makedirs(os.path.dirname(os.path.abspath(a.output)), exist_ok=True)
    with open(a.output, "wb") as f:
        f.write(b"ELDG" + struct.pack(">i", 1) + struct.pack(">ddd", minx, minz, a.cell) + struct.pack(">ii", cols, rows))
        f.write(out)
    print("%dx%d cells of %.0f m, elevation %.1f..%.1f m -> %s" % (cols, rows, a.cell, lo_v, hi_v, a.output))


if __name__ == "__main__":
    main()
