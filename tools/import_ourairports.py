#!/usr/bin/env python3
"""
Build data/airports.json from OurAirports (public domain): https://ourairports.com/data/
  curl -LO https://davidmegginson.github.io/ourairports-data/airports.csv
  curl -LO https://davidmegginson.github.io/ourairports-data/runways.csv
  python3 tools/import_ourairports.py airports.csv runways.csv --icao KSDC,KROC -o data/airports.json

Runway threshold coordinates come straight from runways.csv (le_/he_ latitude/longitude); entries
missing them are skipped rather than guessed. Imported airports get verified=true.
"""
import argparse, csv, json, math

R = 6371008.8


def dest(lat, lon, brg, d):
    p, l, b, a = math.radians(lat), math.radians(lon), math.radians(brg), d / R
    p2 = math.asin(math.sin(p) * math.cos(a) + math.cos(p) * math.sin(a) * math.cos(b))
    l2 = l + math.atan2(math.sin(b) * math.sin(a) * math.cos(p), math.cos(a) - math.sin(p) * math.sin(p2))
    return [math.degrees(p2), math.degrees(l2)]


def fnum(s):
    try:
        return float(s)
    except (TypeError, ValueError):
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("airports_csv")
    ap.add_argument("runways_csv")
    ap.add_argument("--icao", default="", help="comma separated idents, e.g. KSDC,KROC")
    ap.add_argument("--bbox", help="south,west,north,east: import every public airport with paved runways inside")
    ap.add_argument("--types", default="small_airport,medium_airport,large_airport")
    ap.add_argument("-o", "--output", required=True)
    a = ap.parse_args()
    wanted = set(x for x in a.icao.upper().split(",") if x)
    bbox = tuple(map(float, a.bbox.split(","))) if a.bbox else None
    types = set(a.types.split(","))
    airports = {}
    with open(a.airports_csv, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            ident = (row.get("gps_code") or row["ident"]).upper()
            inside = bbox and bbox[0] <= float(row["latitude_deg"]) <= bbox[2] and bbox[1] <= float(row["longitude_deg"]) <= bbox[3] \
                and row["type"] in types and row.get("scheduled_service") in ("yes", "no")
            if ident in wanted or row["ident"].upper() in wanted or inside:
                airports[row["ident"]] = row
    out = []
    with open(a.runways_csv, newline="", encoding="utf-8") as f:
        runways = [r for r in csv.DictReader(f) if r["airport_ident"] in airports and r.get("closed") != "1"
                   and (r.get("surface") or "").upper()[:3] in ("ASP", "CON", "PEM", "BIT", "")]
    for ident, row in airports.items():
        lat, lon = float(row["latitude_deg"]), float(row["longitude_deg"])
        elev = (fnum(row.get("elevation_ft")) or 0) * 0.3048
        rws = []
        for r in runways:
            if r["airport_ident"] != ident:
                continue
            le = [fnum(r["le_latitude_deg"]), fnum(r["le_longitude_deg"])]
            he = [fnum(r["he_latitude_deg"]), fnum(r["he_longitude_deg"])]
            if None in le or None in he:
                print("skipping %s runway %s: no threshold coordinates" % (ident, r["le_ident"]))
                continue
            width = (fnum(r.get("width_ft")) or 75) * 0.3048
            rws.append({"ident": r["le_ident"], "threshold": le, "end": he, "width": round(width, 1)})
            rws.append({"ident": r["he_ident"], "threshold": he, "end": le, "width": round(width, 1)})
        if not rws:
            print("WARNING: %s has no usable runways, skipped" % ident)
            continue
        out.append({"icao": (row.get("gps_code") or ident).upper(), "iata": row.get("iata_code") or None, "name": row["name"],
                    "country": row["iso_country"], "ref": [lat, lon], "elevation": round(elev, 1), "runways": rws,
                    # Gate: placeholder stand 150 m north of the reference point until terminal geometry is mapped.
                    "gate": dest(lat, lon, 0, 150), "verified": True})
    with open(a.output, "w") as f:
        json.dump({"_source": "OurAirports (public domain)", "airports": out}, f, indent=1)
    print("%d airports -> %s" % (len(out), a.output))


if __name__ == "__main__":
    main()
