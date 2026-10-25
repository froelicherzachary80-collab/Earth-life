package earthlife.core.aviation;

import earthlife.core.geo.GeoPoint;
import earthlife.core.util.Json;

import java.util.*;

/**
 * Airport + runway reference data. Loaded from data/airports.json, which tools/import_ourairports.py
 * builds from OurAirports (public domain) airports.csv and runways.csv. The `verified` flag is false
 * for hand-entered fallback data that hasn't been replaced by the importer yet.
 */
public record Airport(String icao, String iata, String name, String country, GeoPoint ref, double elevationM,
                      List<Runway> runways, GeoPoint gate, boolean verified) {

    /** One runway direction: aircraft use it from {@code threshold} toward {@code end}. */
    public record Runway(String ident, GeoPoint threshold, GeoPoint end, double widthM) {
        public double lengthM() { return threshold.distanceTo(end); }
        public double headingDeg() { return threshold.bearingTo(end); }
        /** Point on the extended centreline {@code meters} before the threshold (approach fix). */
        public GeoPoint approachFix(double meters) { return end.interpolate(threshold, 1 + meters / lengthM()); }
    }

    public Airport withGate(GeoPoint g) { return new Airport(icao, iata, name, country, ref, elevationM, runways, g, verified); }

    /** The longest runway (used to orient generated terminals). */
    public Runway mainRunway() { return runways.stream().max(Comparator.comparingDouble(Runway::lengthM)).orElseThrow(); }

    public Runway runway(String ident) {
        return runways.stream().filter(r -> r.ident().equalsIgnoreCase(ident)).findFirst()
                .orElseThrow(() -> new NoSuchElementException(icao + " has no runway " + ident));
    }

    /** Pick the runway direction best aligned with the given course (proxy for wind until weather drives it). */
    public Runway bestRunwayFor(double courseDeg) {
        return runways.stream().min(Comparator.comparingDouble(r -> angleDiff(r.headingDeg(), courseDeg))).orElseThrow();
    }

    static double angleDiff(double a, double b) {
        double d = Math.abs(a - b) % 360;
        return d > 180 ? 360 - d : d;
    }

    public static Map<String, Airport> loadAll(String json) {
        Map<String, Airport> out = new LinkedHashMap<>();
        for (Object o : Json.arr(Json.obj(Json.parse(json)).get("airports"))) {
            var m = Json.obj(o);
            List<Runway> rws = new ArrayList<>();
            for (Object r : Json.arr(m.get("runways"))) {
                var rm = Json.obj(r);
                rws.add(new Runway(Json.str(rm, "ident"), pt(rm.get("threshold")), pt(rm.get("end")), Json.num(rm.get("width"))));
            }
            Airport a = new Airport(Json.str(m, "icao"), Json.str(m, "iata"), Json.str(m, "name"), Json.str(m, "country"),
                    pt(m.get("ref")), Json.num(m.get("elevation")), rws, pt(m.get("gate")), Boolean.TRUE.equals(m.get("verified")));
            out.put(a.icao(), a);
        }
        return out;
    }

    private static GeoPoint pt(Object o) {
        var a = Json.arr(o);
        return new GeoPoint(Json.num(a.get(0)), Json.num(a.get(1)));
    }
}
