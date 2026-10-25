#!/usr/bin/env python3
"""
Convert a USGS 3DEP elevation GeoTIFF (e.g. 1/3 arc-second tiles from https://apps.nationalmap.gov/downloader/)
into an EarthLife DEM grid (format documented in ElevationSource.Grid).

  pip install rasterio numpy
  python3 tools/dem_to_grid.py USGS_13_n44w078.tif --origin 43.2237,-77.1861 --extent 12000 --cell 2 -o data/regions/williamson/dem.eldg

Samples the DEM on a regular block grid (cell = metres per sample) around the origin. USGS 3DEP is public domain.
"""
import argparse, struct, sys, os
sys.path.insert(0, os.path.dirname(__file__))
from geo import Projection


def main():
    import numpy as np
    import rasterio
    ap = argparse.ArgumentParser()
    ap.add_argument("tif")
    ap.add_argument("--origin", required=True)
    ap.add_argument("--extent", type=float, default=12000, help="half-width in metres")
    ap.add_argument("--cell", type=float, default=2.0)
    ap.add_argument("-o", "--output", required=True)
    a = ap.parse_args()
    lat0, lon0 = map(float, a.origin.split(","))
    proj = Projection(lat0, lon0)
    n = int(2 * a.extent / a.cell) + 1
    ox = oz = -a.extent
    coords = [proj.from_block(ox + i * a.cell, oz + j * a.cell) for j in range(n) for i in range(n)]
    with rasterio.open(a.tif) as src:
        vals = np.array([v[0] for v in src.sample([(lon, lat) for lat, lon in coords])], dtype=np.float32)
        nodata = src.nodata
    if nodata is not None:
        vals[vals == nodata] = np.nan
    vals = np.nan_to_num(vals, nan=float(np.nanmin(vals)))
    os.makedirs(os.path.dirname(os.path.abspath(a.output)), exist_ok=True)
    with open(a.output, "wb") as f:
        f.write(b"ELDG" + struct.pack(">i", 1) + struct.pack(">ddd", ox, oz, a.cell) + struct.pack(">ii", n, n))
        f.write(vals.astype(">f4").tobytes())
    print("%dx%d grid, %.1f..%.1f m -> %s" % (n, n, vals.min(), vals.max(), a.output))


if __name__ == "__main__":
    main()
