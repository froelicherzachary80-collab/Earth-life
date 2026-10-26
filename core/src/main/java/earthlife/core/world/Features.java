package earthlife.core.world;

import earthlife.core.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Geographic features for one region, already projected to block coordinates (x east, z south)
 * by the data pipeline (tools/osm_to_earthlife.py). Every feature carries a provenance flag so the
 * game can distinguish verified geometry from procedural estimates.
 */
public final class Features {
    public enum Provenance { VERIFIED, ESTIMATED }
    public enum RoadClass { MOTORWAY, PRIMARY, SECONDARY, RESIDENTIAL, SERVICE, PATH, RUNWAY, TAXIWAY, RAIL;
        public boolean drivable() { return ordinal() <= SERVICE.ordinal(); }
    }
    public enum Landcover { FARMLAND, ORCHARD, FOREST, GRASS, RESIDENTIAL, INDUSTRIAL, BEACH, WETLAND, AIRPORT_APRON }

    public record Pt(double x, double z) {}
    public record Road(String id, RoadClass cls, double widthM, List<Pt> line, Provenance prov) {}
    /** A building footprint. {@code tags} carries real height/roof/colour/material info from the map when available. */
    public record Building(String id, String kind, int levels, List<Pt> footprint, Provenance prov, BuildingStyle.Tags tags) {
        public Building(String id, String kind, int levels, List<Pt> footprint, Provenance prov) { this(id, kind, levels, footprint, prov, BuildingStyle.Tags.NONE); }
        public int heightBlocks() { return Math.max(3, levels * 3 + 1); }
    }
    public record Area(String id, Landcover cover, List<Pt> ring, Provenance prov) {}
    /** A water body; {@code holes} are islands (inner rings of a multipolygon). */
    public record Water(String id, List<Pt> ring, Provenance prov, List<List<Pt>> holes) {
        public Water(String id, List<Pt> ring, Provenance prov) { this(id, ring, prov, List.of()); }
        public boolean contains(double x, double z) {
            if (!pointInRing(ring, x, z)) return false;
            for (List<Pt> h : holes) if (pointInRing(h, x, z)) return false;
            return true;
        }
    }

    public final String attribution;
    public final List<Road> roads = new ArrayList<>();
    public final List<Building> buildings = new ArrayList<>();
    public final List<Area> areas = new ArrayList<>();
    public final List<Water> water = new ArrayList<>();
    /** Street names by road id (region packs; the LifeMap and in-car GPS show them). */
    public final Map<String, String> names = new HashMap<>();

    public Features(String attribution) { this.attribution = attribution; }

    public static Features load(Path p) throws IOException {
        return parse(Files.readString(p));
    }

    public static Features parse(String json) {
        Map<String, Object> root = Json.obj(Json.parse(json));
        Features f = new Features(Json.str(root, "attribution"));
        for (Object o : Json.arr(root.get("roads"))) {
            var m = Json.obj(o);
            f.roads.add(new Road(Json.str(m, "id"), RoadClass.valueOf(Json.str(m, "class")),
                    Json.num(m.get("width")), pts(m.get("line")), prov(m)));
        }
        for (Object o : Json.arr(root.get("buildings"))) {
            var m = Json.obj(o);
            f.buildings.add(new Building(Json.str(m, "id"), Json.str(m, "kind"), (int) Json.num(m.get("levels")),
                    pts(m.get("footprint")), prov(m), BuildingStyle.Tags.parse(Json.obj(m.get("style")))));
        }
        for (Object o : Json.arr(root.get("areas"))) {
            var m = Json.obj(o);
            f.areas.add(new Area(Json.str(m, "id"), Landcover.valueOf(Json.str(m, "cover")), pts(m.get("ring")), prov(m)));
        }
        for (Object o : Json.arr(root.get("water"))) {
            var m = Json.obj(o);
            f.water.add(new Water(Json.str(m, "id"), pts(m.get("ring")), prov(m)));
        }
        return f;
    }

    private static Provenance prov(Map<String, Object> m) {
        String p = Json.str(m, "prov");
        return p == null ? Provenance.VERIFIED : Provenance.valueOf(p);
    }

    private static List<Pt> pts(Object o) {
        List<Pt> out = new ArrayList<>();
        for (Object e : Json.arr(o)) {
            var a = Json.arr(e);
            out.add(new Pt(Json.num(a.get(0)), Json.num(a.get(1))));
        }
        return out;
    }

    // ---- geometry helpers used by the rasterizer ----

    public static boolean pointInRing(List<Pt> ring, double x, double z) {
        boolean in = false;
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            Pt a = ring.get(i), b = ring.get(j);
            if ((a.z() > z) != (b.z() > z) && x < (b.x() - a.x()) * (z - a.z()) / (b.z() - a.z()) + a.x()) in = !in;
        }
        return in;
    }

    public static double distToSegment(double px, double pz, Pt a, Pt b) {
        double dx = b.x() - a.x(), dz = b.z() - a.z();
        double len2 = dx * dx + dz * dz;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - a.x()) * dx + (pz - a.z()) * dz) / len2));
        return Math.hypot(px - (a.x() + t * dx), pz - (a.z() + t * dz));
    }

    /** {minX, minZ, maxX, maxZ}. */
    public static double[] bbox(List<Pt> pts, double pad) {
        double a = Double.MAX_VALUE, b = Double.MAX_VALUE, c = -Double.MAX_VALUE, d = -Double.MAX_VALUE;
        for (Pt p : pts) { a = Math.min(a, p.x()); b = Math.min(b, p.z()); c = Math.max(c, p.x()); d = Math.max(d, p.z()); }
        return new double[]{a - pad, b - pad, c + pad, d + pad};
    }
}
