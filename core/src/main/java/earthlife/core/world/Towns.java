package earthlife.core.world;

import earthlife.core.geo.GeoPoint;
import earthlife.core.util.Json;

import java.util.*;

/**
 * Towns and cities in a region (data/towns.json, produced by tools/fetch_osm.py --places from OSM place nodes,
 * with a hand-entered fallback list marked verified=false). Used for fast travel destinations, NPC homes,
 * bus routes, signage and to decide where to infill estimated buildings.
 */
public final class Towns {
    public enum Kind { HAMLET, VILLAGE, TOWN, CITY;
        /** Rough built-up radius in metres used for infill and NPC placement. */
        public double radiusM() { return switch (this) { case HAMLET -> 400; case VILLAGE -> 900; case TOWN -> 1800; case CITY -> 6000; }; }
    }

    public record Town(String name, Kind kind, GeoPoint centre, int population, boolean verified) {}

    public static List<Town> load(String json) {
        List<Town> out = new ArrayList<>();
        for (Object o : Json.arr(Json.obj(Json.parse(json)).get("towns"))) {
            var m = Json.obj(o);
            out.add(new Town(Json.str(m, "name"), Kind.valueOf(Json.str(m, "kind")),
                    new GeoPoint(Json.num(m.get("lat")), Json.num(m.get("lon"))),
                    m.get("population") == null ? 0 : (int) Json.num(m.get("population")), Boolean.TRUE.equals(m.get("verified"))));
        }
        return out;
    }

    public static Optional<Town> find(List<Town> towns, String query) {
        String q = query.toLowerCase(Locale.ROOT).replace('_', ' ');
        return towns.stream().filter(t -> t.name().toLowerCase(Locale.ROOT).equals(q)).findFirst()
                .or(() -> towns.stream().filter(t -> t.name().toLowerCase(Locale.ROOT).startsWith(q)).findFirst());
    }

    public static Town nearest(List<Town> towns, GeoPoint p) {
        return towns.stream().min(Comparator.comparingDouble(t -> t.centre().distanceTo(p))).orElseThrow();
    }
}
