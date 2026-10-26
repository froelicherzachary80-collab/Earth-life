#!/usr/bin/env python3
"""
Pass 2 of the Northeast region packs: merge the per-state cells from extract_state.py, decide each cell's detail
tier, add terrain (AWS Terrain Tiles, Terrarium encoding) and write the downloadable packs:

  out/packs/p_<lat2>_<lon2>.zip   16 cells each (c_<lat8>_<lon8>.elc, format in cellfmt.py)
  out/index.json                  every pack with its size, SHA-256 and the tier of each cell
  out/places.json.gz              cities, towns, villages, hamlets, suburbs (name, kind, population, state)
  out/cinemas.json                every OpenStreetMap cinema (also bundled in the mod jar)
  out/major.elc                   motorways..tertiary roads for the map overview and long-distance routes

Tiers: CITY (big cities; terrain every ~15 m), TOWN (~25 m), RURAL (~45 m), TERRAIN (no map features; ~90 m).

  pip install numpy pillow scipy
  python3 tools/regionpack/assemble.py --states out/NY out/PA out/NJ out/VT -o out/release
"""
import argparse, gzip, hashlib, io, json, math, os, pickle, sys, time, urllib.request, zipfile
from collections import defaultdict
from concurrent.futures import ProcessPoolExecutor, ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(__file__))
import cellfmt

import numpy as np

C = cellfmt.CELLS_PER_DEG
TIER_GRID = {0: (11, 151), 1: (12, 301), 2: (13, 601), 3: (14, 901)}       # tier -> (terrarium zoom, samples per side)
URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
STATE_BBOX = {"NY": (40.49, -79.77, 45.02, -71.85), "PA": (39.71, -80.52, 42.27, -74.69),
              "NJ": (38.92, -75.57, 41.36, -73.88), "VT": (42.72, -73.44, 45.02, -71.46)}
# Great Lakes: Terrain Tiles show the flat lake surface, not the bed. Inside these boxes, flat land at the surface level
# that forms a large connected body is lake; its bed deepens from the shore (as tools/terrain_tiles.py does).
LAKES = [(2, 74.0, 75.2, (43.17, -79.95, 44.30, -75.90)), (3, 174.0, 175.2, (41.35, -83.60, 42.95, -78.85))]
SEA_BOX = (38.5, -75.9, 41.45, -71.0)
TILE_CACHE = os.environ.get("TILE_CACHE", "/tmp/terrarium")


# ---------------- terrain ----------------
def merc(lat, lon, z):
    n = 2 ** z * 256
    x = (np.asarray(lon) + 180) / 360 * n
    la = np.radians(np.asarray(lat))
    y = (1 - np.log(np.tan(la) + 1 / np.cos(la)) / math.pi) / 2 * n
    return x - 0.5, y - 0.5


def fetch_tile(z, x, y):
    path = os.path.join(TILE_CACHE, str(z), "%d_%d.png" % (x, y))
    if os.path.exists(path):
        return path
    os.makedirs(os.path.dirname(path), exist_ok=True)
    for attempt in range(6):
        try:
            req = urllib.request.Request(URL.format(z=z, x=x, y=y), headers={"User-Agent": "EarthLife-regionpack/0.4.1"})
            with urllib.request.urlopen(req, timeout=60) as r:
                data = r.read()
            tmp = path + ".part%d" % os.getpid()
            with open(tmp, "wb") as f:
                f.write(data)
            os.replace(tmp, path)
            return path
        except Exception as e:
            if "404" in str(e):
                return None
            time.sleep(1 + attempt * 2)
    raise RuntimeError("terrain tile %d/%d/%d failed" % (z, x, y))


def mosaic(z, s, w, n, e):
    """Elevation mosaic (metres, float32) covering the box, plus the global pixel of its top-left."""
    from PIL import Image
    x0, y1 = merc(s, w, z); x1, y0 = merc(n, e, z)
    tx0, tx1 = int(x0 // 256) - (1 if x0 % 256 < 1 else 0), int(x1 // 256) + (1 if x1 % 256 > 254 else 0)
    ty0, ty1 = int(y0 // 256) - (1 if y0 % 256 < 1 else 0), int(y1 // 256) + (1 if y1 % 256 > 254 else 0)
    arr = np.zeros(((ty1 - ty0 + 1) * 256, (tx1 - tx0 + 1) * 256), dtype=np.float32)
    jobs = [(tx, ty) for ty in range(ty0, ty1 + 1) for tx in range(tx0, tx1 + 1)]
    with ThreadPoolExecutor(8) as ex:
        paths = list(ex.map(lambda t: fetch_tile(z, t[0] % (2 ** z), t[1]), jobs))
    for (tx, ty), p in zip(jobs, paths):
        if p is None:
            continue
        im = np.asarray(Image.open(p).convert("RGB"), dtype=np.float32)
        arr[(ty - ty0) * 256:(ty - ty0 + 1) * 256, (tx - tx0) * 256:(tx - tx0 + 1) * 256] = im[:, :, 0] * 256 + im[:, :, 1] + im[:, :, 2] / 256 - 32768
    return arr, tx0 * 256, ty0 * 256


def sample(arr, gx0, gy0, z, lats, lons):
    px, py = merc(lats, lons, z)
    px -= gx0; py -= gy0
    x0 = np.clip(np.floor(px).astype(np.int64), 0, arr.shape[1] - 2); y0 = np.clip(np.floor(py).astype(np.int64), 0, arr.shape[0] - 2)
    ax = np.clip(px - x0, 0, 1); ay = np.clip(py - y0, 0, 1)
    a, b, c, d = arr[y0, x0], arr[y0, x0 + 1], arr[y0 + 1, x0], arr[y0 + 1, x0 + 1]
    return (a * (1 - ax) + b * ax) * (1 - ay) + (c * (1 - ax) + d * ax) * ay


def terrain(lat8, lon8, tier):
    """(n, decimetre values south->north rows, mask) for one cell."""
    from scipy import ndimage
    z, n = TIER_GRID[tier]
    step = 1.0 / C / (n - 1)
    m = int(math.ceil(0.012 / step))                       # ~1.3 km margin so lake depth is continuous across cells
    s, w = lat8 / C - m * step, lon8 / C - m * step
    N = n + 2 * m
    lats = s + np.arange(N) * step
    lons = w + np.arange(N) * step
    LA, LO = np.meshgrid(lats, lons, indexing="ij")
    arr, gx0, gy0 = mosaic(z, lats[0], lons[0], lats[-1], lons[-1])
    elev = sample(arr, gx0, gy0, z, LA, LO).astype(np.float64)
    mask = np.zeros(elev.shape, dtype=np.uint8)
    for code, surface, level, (ls, lw, ln, le) in LAKES:
        inside = (LA >= ls) & (LA <= ln) & (LO >= lw) & (LO <= le)
        if not inside.any():
            continue
        cand = inside & (elev <= level)
        lab, k = ndimage.label(cand)
        if k == 0:
            continue
        sizes = ndimage.sum(cand, lab, range(1, k + 1))
        area_px = (1.0 / 1.0e6) * (step * 111_000) * (step * 111_000 * math.cos(math.radians(lat8 / C)))  # km2 per sample
        edge = set(np.unique(np.concatenate([lab[0], lab[-1], lab[:, 0], lab[:, -1]])).tolist())
        keep = [i + 1 for i, sz in enumerate(sizes) if sz * area_px > 20 or (sz * area_px > 1 and (i + 1) in edge)]
        lake = np.isin(lab, keep)
        if not lake.any():
            continue
        dist = ndimage.distance_transform_edt(lake) * step * 111_000          # metres from shore (approx.)
        elev = np.where(lake, surface - np.minimum(40.0, 1.0 + dist * 0.06), elev)
        mask[lake] = code
    ss, sw, sn, se = SEA_BOX
    sea = (LA >= ss) & (LA <= sn) & (LO >= sw) & (LO <= se) & (elev < -0.5) & (mask == 0)
    if sea.any():
        lab, k = ndimage.label(sea)
        sizes = ndimage.sum(sea, lab, range(1, k + 1))
        area_px = (step * 111) ** 2 * math.cos(math.radians(lat8 / C))
        edge = set(np.unique(np.concatenate([lab[0], lab[-1], lab[:, 0], lab[:, -1]])).tolist())
        keep = [i + 1 for i, sz in enumerate(sizes) if sz * area_px > 5 or (sz * area_px > 0.05 and (i + 1) in edge)]
        mask[np.isin(lab, keep)] = 1
    core = (slice(m, m + n), slice(m, m + n))
    dm = np.clip(np.round(elev[core] * 10), -32000, 32000).astype(np.int64)
    return n, dm.ravel().tolist(), bytes(mask[core].ravel().tolist())


# ---------------- merging ----------------
def load_cell(state_dirs, lat8, lon8):
    merged = {"roads": [], "buildings": [], "areas": [], "water": [], "pois": []}
    seen = set()
    for d in state_dirs:
        p = os.path.join(d, "cells", "%d_%d.pkl" % (lat8, lon8))
        if not os.path.exists(p):
            continue
        with open(p, "rb") as f:
            c = pickle.load(f)
        for kind, items in c.items():
            for it in items:
                if kind == "buildings":
                    key = (kind, it["id"])
                elif kind == "roads":
                    key = (kind, it["id"], len(it["line"]), it["line"][0])
                elif kind == "pois":
                    key = (kind, it.get("name"), round(it["lat"], 5), round(it["lon"], 5))
                else:
                    key = (kind, it["id"], it["rings"][0][0])
                if key in seen:
                    continue
                seen.add(key)
                merged[kind].append(it)
    return merged


def build_pack(args):
    pack, cells, state_dirs, out_dir, tiers = args
    t0 = time.time()
    path = os.path.join(out_dir, "packs", cellfmt.pack_name(*pack))
    buf = io.BytesIO()
    counts = defaultdict(int)
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for (lat8, lon8) in cells:
            data = load_cell(state_dirs, lat8, lon8)
            c = cellfmt.Cell(lat8, lon8)
            c.tier = tiers[(lat8, lon8)]
            for k, v in data.items():
                setattr(c, k, v); counts[k] += len(v)
            c.dem = terrain(lat8, lon8, c.tier)
            zf.writestr(cellfmt.cell_name(lat8, lon8), c.write())
    b = buf.getvalue()
    with open(path, "wb") as f:
        f.write(b)
    print("%s: %d cells, %s, %.1f MB, %.0f s" % (os.path.basename(path), len(cells), dict(counts), len(b) / 1e6, time.time() - t0), flush=True)
    return cellfmt.pack_name(*pack), {"size": len(b), "sha256": hashlib.sha256(b).hexdigest(),
                                       "cells": {"%d_%d" % c: tiers[c] for c in cells}}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--states", nargs="+", required=True, help="extract_state.py output dirs (named by state code)")
    ap.add_argument("-o", "--out", required=True)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--limit-packs", help="only build these packs (comma separated lat2_lon2), for tests")
    a = ap.parse_args()
    os.makedirs(os.path.join(a.out, "packs"), exist_ok=True)

    places, cinemas, major, counts = [], [], [], {}
    for d in a.states:
        with open(os.path.join(d, "globals.pkl"), "rb") as f:
            g = pickle.load(f)
        places += g["places"]; cinemas += g["cinemas"]; major += g["major"]; counts[os.path.basename(d.rstrip("/"))] = g["counts"]

    # cells: everything with map data, plus terrain-only cells inside the states' boxes
    feature_cells = defaultdict(int)
    for d in a.states:
        for fn in os.listdir(os.path.join(d, "cells")):
            la, lo = map(int, fn[:-4].split("_"))
            with open(os.path.join(d, "cells", fn), "rb") as f:
                c = pickle.load(f)
            feature_cells[(la, lo)] += len(c["buildings"]) * 1 + 1
    states = [os.path.basename(d.rstrip("/")) for d in a.states]
    all_cells = set(feature_cells)
    for st in states:
        s, w, n, e = STATE_BBOX.get(st, (0, 0, 0, 0))
        for la in range(math.floor(s * C), math.floor(n * C) + 1):
            for lo in range(math.floor(w * C), math.floor(e * C) + 1):
                all_cells.add((la, lo))

    tiers = {}
    for c in all_cells:
        nb = feature_cells.get(c, 0)
        tiers[c] = 0 if nb == 0 else 3 if nb >= 20000 else 2 if nb >= 1500 else 1
    for p in places:
        cc = cellfmt.cell_of(p["lat"], p["lon"])
        if p["kind"] == "CITY" and p["population"] >= 100_000:
            for dl in (-1, 0, 1):
                for do in (-1, 0, 1):
                    if (cc[0] + dl, cc[1] + do) in tiers and tiers[(cc[0] + dl, cc[1] + do)] >= 1:
                        tiers[(cc[0] + dl, cc[1] + do)] = 3
        elif p["kind"] == "CITY" and p["population"] >= 40_000 and cc in tiers:
            tiers[cc] = 3
        elif p["kind"] in ("CITY", "TOWN") and cc in tiers and tiers[cc] in (0, 1) and feature_cells.get(cc, 0) > 0:
            tiers[cc] = 2

    packs = defaultdict(list)
    for c in sorted(all_cells):
        packs[cellfmt.pack_of_cell(*c)].append(c)
    if a.limit_packs:
        want = {tuple(map(int, x.split("_"))) for x in a.limit_packs.split(",")}
        packs = {k: v for k, v in packs.items() if k in want}
    print("%d cells in %d packs; tiers: %s" % (sum(len(v) for v in packs.values()), len(packs),
          {cellfmt.TIERS[t]: sum(1 for c in all_cells if tiers[c] == t) for t in range(4)}), flush=True)

    index = {"version": 1, "built": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "cellsPerDeg": C, "packsPerDeg": cellfmt.PACK_PER_DEG,
             "states": states, "counts": counts,
             "attribution": ["Map data (c) OpenStreetMap contributors, ODbL 1.0 - https://www.openstreetmap.org/copyright",
                             "Terrain: AWS Terrain Tiles (Mapzen); sources USGS 3DEP, NOAA, SRTM and others"],
             "packs": {}}
    jobs = [(k, v, a.states, a.out, tiers) for k, v in sorted(packs.items(), key=lambda kv: -sum(tiers[c] for c in kv[1]))]
    with ProcessPoolExecutor(a.workers) as ex:
        for name, info in ex.map(build_pack, jobs):
            index["packs"][name] = info

    # cinemas: drop node duplicates of mapped cinema buildings, give nodes the footprint of the building they sit in
    cin, ringed = [], [c for c in cinemas if c.get("ring")]
    for c in cinemas:
        if not c.get("ring") and any(r["name"] == c["name"] and abs(r["lat"] - c["lat"]) < 0.002 and abs(r["lon"] - c["lon"]) < 0.002 for r in ringed):
            continue
        cin.append(c)
    with open(os.path.join(a.out, "cinemas.json"), "w") as f:
        json.dump({"_source": "OpenStreetMap amenity=cinema (ODbL)", "cinemas": cin}, f, separators=(",", ":"))
    with gzip.open(os.path.join(a.out, "places.json.gz"), "wt") as f:
        json.dump({"_source": "OpenStreetMap place nodes (ODbL)", "places": places}, f, separators=(",", ":"))
    mj = cellfmt.Cell(0, 0)
    seen = set()
    for r in major:
        if r["id"] in seen:
            continue
        seen.add(r["id"])
        mj.roads.append({"id": r["id"], "class": r["class"], "width": 0, "name": r.get("ref") or r.get("name"), "line": r["line"]})
    with open(os.path.join(a.out, "major.elc"), "wb") as f:
        f.write(mj.write())
    for g in ("cinemas.json", "places.json.gz", "major.elc"):
        b = open(os.path.join(a.out, g), "rb").read()
        index.setdefault("globals", {})[g] = {"size": len(b), "sha256": hashlib.sha256(b).hexdigest()}
    with open(os.path.join(a.out, "index.json"), "w") as f:
        json.dump(index, f, indent=1)
    total = sum(p["size"] for p in index["packs"].values())
    print("done: %d packs, %.0f MB, %d cinemas, %d places, %d major roads" % (len(index["packs"]), total / 1e6, len(cin), len(places), len(mj.roads)))


if __name__ == "__main__":
    main()
