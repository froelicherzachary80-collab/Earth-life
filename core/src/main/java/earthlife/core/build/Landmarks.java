package earthlife.core.build;

import earthlife.core.geo.GeoPoint;
import earthlife.core.geo.LocalProjection;
import earthlife.core.util.Json;
import earthlife.core.world.ElevationSource;
import earthlife.core.world.Features;

import java.util.*;

/**
 * Landmarks: notable real places built as detailed structures at their real coordinates.
 *
 * Positions/sizes come from data/landmarks.json. Entries there are either imported from OpenStreetMap
 * (tools/osm_to_earthlife.py --landmarks-out: lighthouses, stadiums, arenas, churches, water towers, monuments,
 * theme parks, tall buildings - verified=true) or hand-entered with approximate coordinates (verified=false) until
 * tools/match_landmarks.py snaps them to the OSM object of the same name.
 * The structure for each type is a faithful-in-spirit generated model (a ballpark has a diamond, stands, lights and a
 * scoreboard), not an architectural copy of the specific building.
 */
public final class Landmarks {
    public enum Type { LIGHTHOUSE, STADIUM, ARENA, AMPHITHEATER, TOWER, CHURCH, WATER_TOWER, MONUMENT, AMUSEMENT_PARK }

    public record Landmark(String id, String name, Type type, GeoPoint pos, Double heightM, Double widthM, Double lengthM,
                           Double headingDeg, boolean verified, List<String> aliases) {}

    /** A generated structure ready to place: blueprint + levelled sites + named markers (seats, stage, scoreboard...). */
    public record Placed(Landmark landmark, Blueprint.Placed placed, List<List<Features.Pt>> sites, int baseY) {
        public double[] marker(String n) { return placed.marker(n); }
        public List<double[]> markers(String prefix) {
            List<double[]> out = new ArrayList<>();
            for (String k : placed.bp.markers().keySet()) if (k.startsWith(prefix)) out.add(placed.marker(k));
            return out;
        }
    }

    public static List<Landmark> load(String json) {
        List<Landmark> out = new ArrayList<>();
        for (Object o : Json.arr(Json.obj(Json.parse(json)).get("landmarks"))) {
            var m = Json.obj(o);
            List<String> aliases = new ArrayList<>();
            for (Object a : Json.arr(m.get("aliases"))) aliases.add((String) a);
            out.add(new Landmark(Json.str(m, "id"), Json.str(m, "name"), Type.valueOf(Json.str(m, "type")),
                    new GeoPoint(Json.num(m.get("lat")), Json.num(m.get("lon"))), d(m.get("height")), d(m.get("width")), d(m.get("length")),
                    d(m.get("heading")), Boolean.TRUE.equals(m.get("verified")), aliases));
        }
        return out;
    }

    private static Double d(Object o) { return o == null ? null : ((Number) o).doubleValue(); }

    /** Finds a landmark by id, name or alias (used to match event venues, e.g. "Innovative Field"). */
    public static Optional<Landmark> find(List<Landmark> all, String q) {
        if (q == null) return Optional.empty();
        String n = norm(q);
        for (Landmark l : all) {
            if (norm(l.id()).equals(n) || norm(l.name()).equals(n)) return Optional.of(l);
            for (String a : l.aliases()) if (norm(a).equals(n)) return Optional.of(l);
        }
        for (Landmark l : all) if (n.length() >= 6 && (norm(l.name()).contains(n) || n.contains(norm(l.name())))) return Optional.of(l);
        return Optional.empty();
    }

    static String norm(String s) { return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }

    public static Placed build(Landmark l, LocalProjection proj, ElevationSource elev) {
        var b = proj.toBlock(l.pos().lat(), l.pos().lon());
        int baseY = proj.elevationToY(elev.elevationAt(b.x(), b.z()));
        Blueprint bp = new Blueprint();
        double heading = l.headingDeg() == null ? 0 : l.headingDeg();
        switch (l.type()) {
            case LIGHTHOUSE -> lighthouse(bp, h(l, 18));
            case STADIUM -> ballpark(bp);
            case ARENA -> arena(bp, (int) size(l.widthM(), 90), (int) size(l.lengthM(), 110), h(l, 26));
            case AMPHITHEATER -> amphitheater(bp);
            case TOWER -> tower(bp, (int) size(l.widthM(), 30), (int) size(l.lengthM(), 36), h(l, 80));
            case CHURCH -> church(bp, h(l, 32));
            case WATER_TOWER -> waterTower(bp, h(l, 35));
            case MONUMENT -> monument(bp, h(l, 15));
            case AMUSEMENT_PARK -> amusementPark(bp);
        }
        Blueprint.Placed placed = bp.place(b.x(), b.z(), baseY, heading);
        int[] bounds = bp.bounds();
        List<Features.Pt> ring = new ArrayList<>();
        for (double[] c : new double[][]{placed.toWorld(bounds[0] - 2, bounds[1] - 2), placed.toWorld(bounds[2] + 2, bounds[1] - 2),
                placed.toWorld(bounds[2] + 2, bounds[3] + 2), placed.toWorld(bounds[0] - 2, bounds[3] + 2)}) ring.add(new Features.Pt(c[0], c[1]));
        return new Placed(l, placed, List.of(ring), baseY);
    }

    private static int h(Landmark l, int def) { return l.heightM() == null ? def : (int) Math.max(4, Math.round(l.heightM())); }
    private static double size(Double v, double def) { return v == null ? def : Math.max(10, v); }

    // ---- helpers (local frame: u along heading, v to the left, y up; y=0 is the ground block) ----
    static void disc(Blueprint b, int cu, int cv, double r, int y, String block) {
        int R = (int) Math.ceil(r);
        for (int u = -R; u <= R; u++) for (int v = -R; v <= R; v++) if (u * u + v * v <= r * r) b.set(cu + u, y, cv + v, block);
    }

    static void ring(Blueprint b, int cu, int cv, double r, int y, String block) {
        int R = (int) Math.ceil(r) + 1;
        for (int u = -R; u <= R; u++) for (int v = -R; v <= R; v++) {
            double d = Math.sqrt(u * u + v * v);
            if (d <= r + 0.5 && d > r - 0.5) b.set(cu + u, y, cv + v, block);
        }
    }

    // ---- generators ----
    static void lighthouse(Blueprint b, int h) {
        b.box(-6, 0, -6, 12, 0, 6, "minecraft:stone_bricks");                           // base platform + keeper's house plot
        for (int y = 1; y <= h; y++) {
            String wall = (y > h - 4) ? "minecraft:red_concrete" : (y % 6 == 0 ? "minecraft:white_concrete" : "minecraft:white_terracotta");
            ring(b, 0, 0, 3, y, wall);
            disc(b, 0, 0, 2.2, y, "minecraft:air");
        }
        for (int y = 2; y <= h; y += 1) b.set(0, y, 0, "minecraft:scaffolding");       // climbable core
        b.box(-1, 1, 3, -1, 2, 3, "minecraft:air"); b.box(0, 1, 3, 0, 2, 3, "minecraft:air");    // door
        disc(b, 0, 0, 4, h + 1, "minecraft:stone_brick_slab");                          // gallery
        ring(b, 0, 0, 4, h + 2, "minecraft:iron_bars");
        for (int y = h + 2; y <= h + 4; y++) ring(b, 0, 0, 2, y, "minecraft:glass");   // lantern room
        b.set(0, h + 3, 0, "minecraft:sea_lantern");
        b.marker("light", 0, h + 3, 0);
        disc(b, 0, 0, 2.5, h + 5, "minecraft:black_concrete");
        b.set(0, h + 6, 0, "minecraft:lightning_rod");
        b.room(5, -4, 12, 4, 0, 5, "minecraft:white_terracotta", "minecraft:spruce_planks", "minecraft:dark_oak_planks");  // keeper's house
        b.box(5, 2, 0, 5, 3, 0, "minecraft:air");
        b.marker("gate", 4, 1, 0);
    }

    /** Minor/major-league style ballpark: home plate at (0,0), centre field along +u. */
    static void ballpark(Blueprint b) {
        int R = 105;                                                                     // outfield fence distance
        for (int u = -8; u <= R + 6; u++) for (int v = -R; v <= R; v++) {
            double d = Math.hypot(u, v);
            if (u >= -6 && Math.abs(v) <= u + 6 && d <= R) b.set(u, 0, v, (d < 40 && Math.abs(Math.abs(u - 19) + Math.abs(v)) > 13) && d > 3 ? "minecraft:grass_block" : "minecraft:grass_block");
        }
        // infield dirt: square rotated 45° (bases 27 m apart) with grass in the middle
        for (int u = -2; u <= 40; u++) for (int v = -22; v <= 22; v++) {
            double du = u - 19, dist = Math.abs(du) + Math.abs(v);
            if (dist <= 21 && dist >= 16) b.set(u, 0, v, "minecraft:coarse_dirt");
            if (Math.hypot(u, v) <= 4) b.set(u, 0, v, "minecraft:coarse_dirt");
        }
        b.set(0, 0, 0, "minecraft:white_concrete"); b.marker("field:home", 0, 1, 0);
        for (int[] base : new int[][]{{19, -19}, {38, 0}, {19, 19}}) b.set(base[0], 0, base[1], "minecraft:white_concrete");
        disc(b, 18, 0, 2.5, 0, "minecraft:coarse_dirt"); b.set(18, 1, 0, "minecraft:smooth_stone_slab");   // mound
        // outfield wall + foul poles
        for (int a = -45; a <= 45; a++) {
            double r = Math.toRadians(a);
            int u = (int) Math.round(R * Math.cos(r)), v = (int) Math.round(R * Math.sin(r));
            for (int y = 1; y <= 3; y++) b.set(u, y, v, "minecraft:green_concrete");
        }
        // grandstand: stepped seats in an arc behind home plate (radius 22..48), concourse on top
        int row = 0;
        for (int rr = 22; rr <= 48; rr += 2, row++) {
            for (int a = -150; a <= 150; a += 1) {
                if (Math.abs(a) < 45 && rr < 26) continue;
                double r = Math.toRadians(180 + a * 0.6);
                int u = (int) Math.round(rr * Math.cos(r)), v = (int) Math.round(rr * Math.sin(r));
                int y = 1 + row;
                b.box(u, 1, v, u, y - 1, v, "minecraft:gray_concrete");
                b.set(u, y, v, a % 9 == 0 ? "minecraft:gray_concrete" : "minecraft:smooth_quartz_slab");
                if (a % 9 == 4 && row % 2 == 0) b.marker("seats:" + rr + ":" + a, u, y + 0.5, v);
            }
        }
        // light towers and scoreboard beyond centre field
        for (int[] t : new int[][]{{-20, -40}, {-20, 40}, {60, -75}, {60, 75}}) {
            b.box(t[0], 1, t[1], t[0], 30, t[1], "minecraft:iron_block");
            b.box(t[0] - 1, 31, t[1] - 2, t[0] + 1, 33, t[1] + 2, "minecraft:sea_lantern");
        }
        b.box(R + 8, 1, -2, R + 8, 9, 2, "minecraft:iron_block");
        b.box(R + 8, 10, -10, R + 8, 18, 10, "minecraft:black_concrete");
        b.marker("scoreboard", R + 7, 14, 0);
        b.marker("gate", -52, 1, 0);
        b.box(-54, 0, -6, -50, 0, 6, "minecraft:polished_andesite");
    }

    static void arena(Blueprint b, int W, int L, int h) {
        int u0 = -L / 2, u1 = L / 2, v0 = -W / 2, v1 = W / 2;
        b.box(u0, 0, v0, u1, 0, v1, "minecraft:polished_andesite");
        for (int y = 1; y < h; y++) {
            for (int u = u0; u <= u1; u++) { b.set(u, y, v0, y % 5 == 0 ? "minecraft:light_blue_stained_glass" : "minecraft:light_gray_concrete"); b.set(u, y, v1, y % 5 == 0 ? "minecraft:light_blue_stained_glass" : "minecraft:light_gray_concrete"); }
            for (int v = v0; v <= v1; v++) { b.set(u0, y, v, "minecraft:light_gray_concrete"); b.set(u1, y, v, "minecraft:light_gray_concrete"); }
            b.box(u0 + 1, y, v0 + 1, u1 - 1, y, v1 - 1, "minecraft:air");
        }
        b.box(u0, h, v0, u1, h, v1, "minecraft:smooth_stone");
        for (int u = u0 + 4; u < u1; u += 8) for (int v = v0 + 4; v < v1; v += 8) b.set(u, h, v, "minecraft:sea_lantern");
        // event floor (ice/court) and stepped bowl
        int fu = Math.min(30, L / 2 - 16), fv = Math.min(15, W / 2 - 12);
        b.box(-fu, 0, -fv, fu, 0, fv, "minecraft:packed_ice");
        b.marker("floor", 0, 1, 0);
        for (int step = 0; step < 10; step++) {
            int y = 1 + step;
            for (int u = -fu - 2 - step * 1; u <= fu + 2 + step; u++) for (int v = -fv - 2 - step; v <= fv + 2 + step; v++) {
                boolean edge = Math.abs(u) == fu + 2 + step || Math.abs(v) == fv + 2 + step;
                if (!edge || Math.abs(u) >= L / 2 - 1 || Math.abs(v) >= W / 2 - 1) continue;
                b.box(u, 1, v, u, y - 1, v, "minecraft:gray_concrete");
                b.set(u, y, v, "minecraft:red_wool");
                if ((u + v) % 7 == 0) b.marker("seats:" + u + ":" + v, u, y + 0.5, v);
            }
        }
        b.box(-3, h - 8, -3, 3, h - 4, 3, "minecraft:black_concrete");                 // centre-hung scoreboard
        b.marker("scoreboard", 0, h - 6, -3.6);
        b.box(u1, 1, -4, u1, 4, 4, "minecraft:air");
        b.box(u1, 1, -4, u1, 1, 4, "minecraft:polished_andesite");
        b.box(u1 + 1, 5, -8, u1 + 1, 7, 8, "minecraft:black_concrete");               // marquee over the entrance
        b.marker("marquee", u1 + 1.6, 6, 0);
        b.marker("gate", u1 + 3, 1, 0);
    }

    static void amphitheater(Blueprint b) {
        b.box(-6, 0, -14, 8, 2, 14, "minecraft:dark_oak_planks");                       // stage deck
        b.marker("stage", 1, 3, 0);
        for (int[] p : new int[][]{{-6, -14}, {-6, 14}, {8, -14}, {8, 14}}) b.box(p[0], 3, p[1], p[0], 14, p[1], "minecraft:iron_block");
        b.box(-8, 15, -16, 10, 15, 16, "minecraft:white_concrete");                    // canopy
        for (int r = 16, row = 0; r <= 70; r += 2, row++) {
            for (int a = -55; a <= 55; a++) {
                double rad = Math.toRadians(a);
                int u = 8 + (int) Math.round(r * Math.cos(rad)), v = (int) Math.round(r * Math.sin(rad));
                int y = 1 + row / 2;
                b.box(u, 0, v, u, y - 1, v, r <= 40 ? "minecraft:gray_concrete" : "minecraft:dirt");
                b.set(u, y, v, r <= 40 ? (a % 6 == 0 ? "minecraft:gray_concrete" : "minecraft:smooth_quartz_slab") : "minecraft:grass_block");
                if (a % 6 == 3 && row % 2 == 0) b.marker("seats:" + r + ":" + a, u, y + 0.5, v);
            }
        }
        for (int a = -55; a <= 55; a += 10) {                                            // pavilion roof posts over the reserved seats
            double rad = Math.toRadians(a);
            int u = 8 + (int) Math.round(42 * Math.cos(rad)), v = (int) Math.round(42 * Math.sin(rad));
            b.box(u, 1, v, u, 22, v, "minecraft:iron_block");
        }
        b.marker("gate", 85, 1, 0);
    }

    static void tower(Blueprint b, int W, int L, int h) {
        int u0 = -L / 2, u1 = L / 2, v0 = -W / 2, v1 = W / 2;
        b.box(u0 - 3, 0, v0 - 3, u1 + 3, 0, v1 + 3, "minecraft:polished_andesite");
        for (int y = 1; y <= h; y++) {
            boolean floor = y % 4 == 0;
            String skin = floor ? "minecraft:gray_concrete" : "minecraft:light_blue_stained_glass";
            for (int u = u0; u <= u1; u++) { b.set(u, y, v0, skin); b.set(u, y, v1, skin); }
            for (int v = v0; v <= v1; v++) { b.set(u0, y, v, skin); b.set(u1, y, v, skin); }
            if (floor) b.box(u0 + 1, y, v0 + 1, u1 - 1, y, v1 - 1, "minecraft:polished_andesite");
            else b.box(u0 + 1, y, v0 + 1, u1 - 1, y, v1 - 1, "minecraft:air");
            b.box(-2, y, -2, 2, y, 2, (y % 4 == 0) ? "minecraft:smooth_stone" : "minecraft:smooth_stone");   // core
            b.set(0, y, 3, "minecraft:scaffolding");                                                         // stair core
        }
        b.box(u1, 1, -2, u1, 3, 2, "minecraft:air");                                    // lobby doors
        b.box(u0 + 2, h + 1, v0 + 2, u1 - 2, h + 4, v1 - 2, "minecraft:gray_concrete"); // mechanical penthouse
        b.box(0, h + 5, 0, 0, h + 12, 0, "minecraft:iron_bars");                       // antenna
        b.marker("gate", u1 + 2, 1, 0);
        b.marker("roof", 0, h + 1, 0);
    }

    static void church(Blueprint b, int h) {
        b.room(-14, -7, 14, 7, 0, 8, "minecraft:stone_bricks", "minecraft:spruce_planks", "minecraft:stone_bricks");
        for (int i = 0; i <= 7; i++) b.box(-14, 8 + i, -7 + i, 14, 8 + i, 7 - i, "minecraft:deepslate_tiles");   // stepped gable roof
        for (int u = -12; u <= 12; u += 4) { b.box(u, 3, -7, u, 6, -7, "minecraft:light_blue_stained_glass"); b.box(u, 3, 7, u, 6, 7, "minecraft:light_blue_stained_glass"); }
        b.box(14, 1, -3, 20, h - 8, 3, "minecraft:stone_bricks");                       // steeple tower
        b.box(15, 1, -2, 19, h - 9, 2, "minecraft:air");
        for (int i = 0; i < 8; i++) b.box(14 + i / 2, h - 8 + i, -3 + i / 2, 20 - i / 2, h - 8 + i, 3 - i / 2, "minecraft:deepslate_tiles");
        b.set(17, h, 0, "minecraft:gold_block");
        b.box(20, 1, -1, 20, 3, 1, "minecraft:air");                                   // front door
        for (int u = -10; u <= 10; u += 3) b.box(u, 1, -4, u, 1, 4, "minecraft:spruce_slab");   // pews
        b.marker("gate", 22, 1, 0); b.marker("altar", -12, 1, 0);
    }

    static void waterTower(Blueprint b, int h) {
        for (int[] l : new int[][]{{-5, -5}, {-5, 5}, {5, -5}, {5, 5}}) b.box(l[0], 1, l[1], l[0], h - 8, l[1], "minecraft:iron_block");
        b.box(0, 1, 0, 0, h - 8, 0, "minecraft:iron_bars");
        for (int y = h - 8; y <= h; y++) {
            double r = 8 - Math.abs(y - (h - 4)) * 0.6;
            disc(b, 0, 0, r, y, "minecraft:light_gray_concrete");
            if (y > h - 8 && y < h) disc(b, 0, 0, r - 1, y, "minecraft:water");
        }
        b.set(0, h + 1, 0, "minecraft:lightning_rod");
    }

    static void monument(Blueprint b, int h) {
        b.box(-4, 0, -4, 4, 1, 4, "minecraft:polished_andesite");
        for (int y = 2; y <= h; y++) { int r = y < h * 0.8 ? 1 : 0; b.box(-r, y, -r, r, y, r, "minecraft:quartz_block"); }
        b.marker("gate", 6, 1, 0);
    }

    static void amusementPark(Blueprint b) {
        b.box(-60, 0, -60, 60, 0, 60, "minecraft:grass_block");
        for (int u = -60; u <= 60; u += 1) for (int v = -2; v <= 2; v++) b.set(u, 0, v, "minecraft:polished_andesite");     // midway
        // Ferris wheel (vertical, in the u-y plane) at u=-30
        int cy = 18, R = 16;
        for (int a = 0; a < 360; a += 2) {
            double r = Math.toRadians(a);
            b.set(-30 + (int) Math.round(R * Math.cos(r)), cy + (int) Math.round(R * Math.sin(r)), 8, "minecraft:white_concrete");
            if (a % 30 == 0) {
                for (int k = 0; k < R; k++) b.set(-30 + (int) Math.round(k * Math.cos(r)), cy + (int) Math.round(k * Math.sin(r)), 8, "minecraft:iron_bars");
                b.marker("gondola:" + a, -30 + R * Math.cos(r), cy + R * Math.sin(r) - 1.5, 8);
            }
        }
        b.box(-34, 1, 8, -34, cy, 8, "minecraft:iron_block"); b.box(-26, 1, 8, -26, cy, 8, "minecraft:iron_block");
        // Wooden roller coaster: closed loop with two lift hills, track on supports
        for (int i = 0; i < 720; i++) {
            double t = i / 720.0 * Math.PI * 2;
            int u = 25 + (int) Math.round(28 * Math.cos(t)), v = 30 + (int) Math.round(18 * Math.sin(2 * t) + 0.0);
            int y = 4 + (int) Math.round(10 * Math.max(0, Math.sin(t * 2)) + 6 * Math.max(0, Math.cos(t * 3)));
            b.set(u, y, v, "minecraft:spruce_planks");
            if (i % 12 == 0) b.box(u, 1, v, u, y - 1, v, "minecraft:oak_fence");
            if (i % 60 == 0) b.marker("coaster:" + i, u, y + 1, v);
        }
        // Carousel
        disc(b, 10, -25, 8, 1, "minecraft:smooth_quartz");
        for (int a = 0; a < 360; a += 45) { double r = Math.toRadians(a); b.box(10 + (int) Math.round(6 * Math.cos(r)), 2, -25 + (int) Math.round(6 * Math.sin(r)), 10 + (int) Math.round(6 * Math.cos(r)), 5, -25 + (int) Math.round(6 * Math.sin(r)), "minecraft:gold_block"); }
        for (int y = 6; y <= 9; y++) disc(b, 10, -25, 9 - (y - 6) * 2.5, y, "minecraft:red_wool");
        b.marker("gate", 62, 1, 0);
        b.marker("stage", 40, 1, -30);
    }
}
