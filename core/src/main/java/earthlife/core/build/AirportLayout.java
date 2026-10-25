package earthlife.core.build;

import earthlife.core.aviation.*;
import earthlife.core.geo.GeoPoint;
import earthlife.core.geo.LocalProjection;
import earthlife.core.world.Features;

import java.util.*;

/**
 * Generates a walkable airport at a real airport's location: terminal with entrance, check-in hall,
 * security checkpoint, departure boards, shops, restrooms, gate lounge and gates (with jet bridges at
 * medium/large airports), apron, taxiways to the runway, control tower, curb road and parking.
 *
 * Runway positions are real (OurAirports/OSM); the building layout is generated (real terminal floor plans
 * aren't openly available), aligned with the longest runway and placed beside it without overlapping any runway.
 * Aircraft stands are computed so a parked aircraft's door lines up exactly with its jet bridge.
 */
public final class AirportLayout {
    public enum Size { SMALL, MEDIUM, LARGE }

    public record Stand(String gate, double x, double z, int floorY, double headingDeg, GeoPoint geo, List<GeoPoint> taxiOut, List<GeoPoint> taxiInFromRunway) {}

    public final Airport airport;
    public final Size size;
    public final Blueprint blueprint;
    public final Blueprint.Placed placed;
    public final List<Stand> stands = new ArrayList<>();
    public final int baseY;
    /** True when no spot clear of every runway was found and the default spot was used (logged by the game). */
    public final boolean fallbackPlacement;

    private static final String FLOOR = "minecraft:polished_andesite", WALL = "minecraft:white_concrete", ROOF = "minecraft:smooth_stone",
            GLASS = "minecraft:light_blue_stained_glass", APRON = "minecraft:light_gray_concrete", ASPHALT = "minecraft:gray_concrete",
            LINE = "minecraft:yellow_concrete", AIR = "minecraft:air";

    public static Size sizeOf(Airport a) {
        double len = a.runways().stream().mapToDouble(Airport.Runway::lengthM).max().orElse(0);
        return len < 1800 ? Size.SMALL : len < 2600 ? Size.MEDIUM : Size.LARGE;
    }

    public AirportLayout(Airport a, LocalProjection proj, CabinLayout cabin) {
        this.airport = a;
        this.size = sizeOf(a);
        this.baseY = proj.elevationToY(a.elevationM());
        int W = switch (size) { case SMALL -> 48; case MEDIUM -> 96; case LARGE -> 176; };
        int D = switch (size) { case SMALL -> 24; case MEDIUM -> 34; case LARGE -> 44; };
        int gates = switch (size) { case SMALL -> 1; case MEDIUM -> 4; case LARGE -> 8; };
        int bridge = size == Size.SMALL ? 0 : 14;
        int apron = bridge + 60;                                           // apron depth from terminal wall toward runway

        Airport.Runway main = a.mainRunway();
        var t = proj.toBlock(main.threshold().lat(), main.threshold().lon());
        var e = proj.toBlock(main.end().lat(), main.end().lon());
        double midX = (t.x() + e.x()) / 2, midZ = (t.z() + e.z()) / 2;
        // Heading measured in block space (not the geodesic bearing) so the layout is exactly parallel to the runway as
        // drawn; far from the region origin, grid north and true north differ by a fraction of a degree.
        double heading = (Math.toDegrees(Math.atan2(e.x() - t.x(), -(e.z() - t.z()))) + 360) % 360;
        double runwayLen = Math.hypot(e.x() - t.x(), e.z() - t.z());

        // Try both sides of the runway at growing offsets until the site clears every runway's safety strip.
        Blueprint.Placed chosen = null;
        Blueprint bp = null;
        int offset = 0;
        outer:
        for (int off = apron + 90; off <= apron + 600; off += 40) {
            for (int side = 1; side >= -1; side -= 2) {
                double h = Math.toRadians(heading);
                double vx = -Math.cos(h), vz = -Math.sin(h);                     // left of heading in (east, south)
                double ox = midX + side * vx * off, oz = midZ + side * vz * off;
                Blueprint candidate = build(W, D, gates, bridge, apron, off, runwayLen, cabin);
                Blueprint.Placed pl = candidate.place(ox, oz, baseY, side > 0 ? heading : heading + 180);
                if (clearOfRunways(pl, proj, W, D, apron, off)) { chosen = pl; bp = candidate; offset = off; break outer; }
            }
        }
        if (chosen == null) {                                              // fallback: first candidate, logged by caller
            bp = build(W, D, gates, bridge, apron, apron + 90, runwayLen, cabin);
            double h = Math.toRadians(heading);
            chosen = bp.place(midX - Math.cos(h) * (apron + 90), midZ - Math.sin(h) * (apron + 90), baseY, heading);
        }
        this.fallbackPlacement = offset == 0;
        this.blueprint = bp;
        this.placed = chosen;

        for (int g = 0; g < gates; g++) {
            String id = "A" + (g + 1);
            double[] st = placed.marker("stand:" + id);
            double[] lane = placed.marker("lane:" + id);
            double[] twNear = placed.marker("taxiway:-");
            double[] twFar = placed.marker("taxiway:+");
            double[] rwNear = placed.marker("runway:-");
            double[] rwFar = placed.marker("runway:+");
            GeoPoint geo = proj.fromBlock(st[0], st[2]);
            stands.add(new Stand(id, st[0], st[2], (int) Math.round(st[1]), placed.headingDeg, geo,
                    List.of(geoOf(proj, lane), geoOf(proj, twNear), geoOf(proj, rwNear)),
                    List.of(geoOf(proj, rwFar), geoOf(proj, twFar), geoOf(proj, lane))));
        }
    }

    private static GeoPoint geoOf(LocalProjection p, double[] w) { return p.fromBlock(w[0], w[2]); }

    /**
     * Departure taxi route for a given runway direction: stand -> apron lane -> the taxiway nearest that
     * runway's threshold -> onto the centreline. Arrival: centreline at the far taxiway -> lane -> stand.
     */
    public List<GeoPoint> taxiOut(Stand s, Airport.Runway dep, LocalProjection proj) {
        boolean nearIsThreshold = nearestIsMinus(dep.threshold(), proj);
        return List.of(s.taxiOut().get(0), geoOf(proj, placed.marker(nearIsThreshold ? "taxiway:-" : "taxiway:+")),
                geoOf(proj, placed.marker(nearIsThreshold ? "runway:-" : "runway:+")));
    }

    public List<GeoPoint> taxiIn(Stand s, Airport.Runway arr, LocalProjection proj) {
        boolean thresholdIsMinus = nearestIsMinus(arr.threshold(), proj);
        String far = thresholdIsMinus ? "+" : "-";
        return List.of(geoOf(proj, placed.marker("runway:" + far)), geoOf(proj, placed.marker("taxiway:" + far)), s.taxiOut().get(0));
    }

    private boolean nearestIsMinus(GeoPoint thr, LocalProjection proj) {
        var b = proj.toBlock(thr.lat(), thr.lon());
        double[] m = placed.marker("runway:-"), p = placed.marker("runway:+");
        return Math.hypot(b.x() - m[0], b.z() - m[2]) < Math.hypot(b.x() - p[0], b.z() - p[2]);
    }

    /** Only the buildings, apron and landside count; the taxiways are meant to reach the runway. */
    private boolean clearOfRunways(Blueprint.Placed pl, LocalProjection proj, int W, int D, int apron, int off) {
        double[][] box = {pl.toWorld(-W / 2.0 - 30, -apron), pl.toWorld(W / 2.0 + 30, -apron), pl.toWorld(W / 2.0 + 30, D + 60), pl.toWorld(-W / 2.0 - 30, D + 60)};
        List<Features.Pt> ring = new ArrayList<>();
        for (double[] c : box) ring.add(new Features.Pt(c[0], c[1]));
        for (Airport.Runway r : airport.runways()) {
            var a = proj.toBlock(r.threshold().lat(), r.threshold().lon());
            var b = proj.toBlock(r.end().lat(), r.end().lon());
            double strip = r.widthM() / 2 + 60;
            for (int i = 0; i <= 40; i++) {
                double x = a.x() + (b.x() - a.x()) * i / 40, z = a.z() + (b.z() - a.z()) * i / 40;
                if (Features.pointInRing(ring, x, z)) return false;
                for (int k = 0; k < 4; k++) {
                    var p = ring.get(k); var q = ring.get((k + 1) % 4);
                    if (Features.distToSegment(x, z, p, q) < strip) return false;
                }
            }
        }
        return true;
    }

    /**
     * Areas to level for the terrain generator: the terminal/apron/parking block, the tower, and one strip per taxiway.
     * Kept tight so real hangars and trees elsewhere on the field survive.
     */
    public List<List<Features.Pt>> siteRings() {
        List<List<Features.Pt>> out = new ArrayList<>();
        int[] bnd = blueprint.bounds();
        double[] tw = blueprint.markers().get("runway:+"), tower = blueprint.markers().get("tower");
        double terminalU = Math.max(Math.abs(bnd[0]), Math.abs(bnd[2]));
        double halfW = Math.min(terminalU, Math.abs(tower[0]) + 8);
        double apronV = blueprint.markers().get("taxiway:+")[2] - 15;
        out.add(rect(-halfW, apronV - 1, halfW, bnd[3] + 2));
        for (double sgn : new double[]{-1, 1}) {
            out.add(rect(sgn * tw[0] - 9, tw[2] - 2, sgn * tw[0] + 9, apronV + 24));                     // taxiway to runway
            out.add(rect(Math.min(sgn * halfW, sgn * tw[0]), apronV + 6, Math.max(sgn * halfW, sgn * tw[0]), apronV + 24)); // apron stub
        }
        return out;
    }

    private List<Features.Pt> rect(double u0, double v0, double u1, double v1) {
        List<Features.Pt> r = new ArrayList<>();
        for (double[] c : new double[][]{placed.toWorld(u0, v0), placed.toWorld(u1, v0), placed.toWorld(u1, v1), placed.toWorld(u0, v1)})
            r.add(new Features.Pt(c[0], c[1]));
        return r;
    }

    // ------------------------------------------------------------------------------------------------
    // Local frame: u along the runway, v away from the runway (terminal airside wall at v=0, landside at v=D).
    // y=0 is the ground block level (baseY); the terminal floor is raised one block (walk surface y=2).
    private static Blueprint build(int W, int D, int gates, int bridge, int apron, int off, double runwayLen, CabinLayout cabin) {
        Blueprint b = new Blueprint();
        int u0 = -W / 2, u1 = W / 2, h = 7;

        // Apron and taxiways (y=0 surface).
        b.box(u0 - 30, 0, -apron, u1 + 30, 0, -1, APRON);
        int tw = (int) Math.min(runwayLen * 0.42, W / 2.0 + 300);
        for (int sgn = -1; sgn <= 1; sgn += 2) {
            int uc = sgn * tw;
            b.box(Math.min(uc, sgn * (u1 + 30)), 0, -apron + 8, Math.max(uc, sgn * (u1 + 30)), 0, -apron + 22, ASPHALT);   // stub along apron edge
            b.box(uc - 7, 0, -off, uc + 7, 0, -apron + 8, ASPHALT);                                                     // taxiway to runway
            for (int v = -off + 30; v < -apron + 8; v++) b.set(uc, 0, v, LINE);                                         // centreline
            b.marker("taxiway:" + (sgn < 0 ? "-" : "+"), uc, 0, -apron + 15);
            b.marker("runway:" + (sgn < 0 ? "-" : "+"), uc, 0, -off);
        }

        // Terminal shell: raised floor (y=1), walls y=2..h-1, roof y=h. Glass curtain wall on the airside.
        b.box(u0, 1, 0, u1, 1, D, FLOOR);
        for (int y = 2; y < h; y++) {
            for (int u = u0; u <= u1; u++) { b.set(u, y, 0, (y >= 3 && y <= 5) ? GLASS : WALL); b.set(u, y, D, (y >= 3 && y <= 4 && u % 4 != 0) ? GLASS : WALL); }
            for (int v = 0; v <= D; v++) { b.set(u0, y, v, WALL); b.set(u1, y, v, WALL); }
            b.box(u0 + 1, y, 1, u1 - 1, y, D - 1, AIR);
        }
        b.box(u0, h, 0, u1, h, D, ROOF);
        for (int u = u0 + 3; u < u1; u += 6) for (int v = 3; v < D; v += 6) b.set(u, h, v, "minecraft:sea_lantern");   // ceiling lights

        // Landside: entrance (3 openings), step, curb road and parking.
        for (int du = -6; du <= 6; du += 6) { b.box(du - 1, 2, D, du + 1, 4, D, AIR); b.box(du - 1, 1, D + 1, du + 1, 1, D + 1, "minecraft:smooth_stone_slab"); b.marker("entrance:" + (du / 6 + 1), du, 2, D + 1.5); }
        b.box(u0 - 30, 0, D + 2, u1 + 30, 0, D + 11, ASPHALT);
        for (int u = u0 - 30; u <= u1 + 30; u += 4) b.set(u, 0, D + 6, "minecraft:white_concrete");
        int parkDepth = W >= 96 ? 50 : 26;
        b.box(u0, 0, D + 12, u1, 0, D + 12 + parkDepth, ASPHALT);
        for (int u = u0; u <= u1; u += 3) for (int v = D + 13; v < D + 12 + parkDepth; v++) if ((v - D - 13) % 12 < 5) b.set(u, 0, v, "minecraft:white_concrete");
        b.marker("parking", 0, 1, D + 12 + parkDepth / 2.0);
        b.marker("bus_stop", u1 - 6, 1, D + 4);

        // Check-in hall: counters row parallel to the landside wall.
        int vCounter = D - 9;
        int counters = Math.max(2, W / 12);
        for (int i = 0; i < counters; i++) {
            int uc = u0 + 6 + i * ((W - 12) / Math.max(1, counters - 1));
            b.box(uc - 1, 2, vCounter, uc + 1, 2, vCounter, "minecraft:polished_diorite");
            b.set(uc, 2, vCounter - 2, "minecraft:dark_oak_planks");                // bag belt behind
            b.marker("checkin:" + (i + 1), uc, 2, vCounter + 1.5);                   // where the passenger stands
            b.marker("agent:checkin:" + (i + 1), uc, 2, vCounter - 1);               // where the agent stands
        }
        b.box(u0 + 2, 3, D - 1, u0 + 8, 4, D - 1, "minecraft:black_concrete");       // arrivals/departures board (landside)
        b.marker("board:landside", u0 + 5, 3.5, D - 1.6);

        // Security: barrier wall across the hall with scanner lanes.
        int vSec = D / 2;
        for (int u = u0 + 1; u < u1; u++) for (int y = 2; y <= 3; y++) b.set(u, y, vSec, "minecraft:iron_bars");
        int lanes = Math.max(2, W / 30);
        for (int i = 0; i < lanes; i++) {
            int uc = -((lanes - 1) * 6) / 2 + i * 6;
            b.box(uc, 2, vSec, uc, 3, vSec, AIR);
            b.set(uc - 1, 4, vSec, "minecraft:iron_block"); b.set(uc, 4, vSec, "minecraft:iron_block"); b.set(uc + 1, 4, vSec, "minecraft:iron_block");
            b.set(uc - 1, 2, vSec, "minecraft:iron_block"); b.set(uc + 1, 2, vSec, "minecraft:iron_block");
            b.marker("security:" + (i + 1), uc, 2, vSec + 1.5);
            b.marker("agent:security:" + (i + 1), uc + 2, 2, vSec - 1.5);
        }

        // Airside: shops at the ends, restrooms, lounge seating, departure board, gates.
        b.room(u0 + 1, 1, u0 + 9, vSec - 2, 1, 4, "minecraft:spruce_planks", FLOOR, "minecraft:spruce_planks");
        b.box(u0 + 9, 2, 3, u0 + 9, 3, 4, AIR); b.box(u0 + 3, 2, 2, u0 + 6, 2, 2, "minecraft:barrel");
        b.marker("shop:1", u0 + 5, 2, 4); b.marker("agent:shop:1", u0 + 5, 2, 2.6);
        b.room(u1 - 9, 1, u1 - 1, vSec - 2, 1, 4, "minecraft:quartz_bricks", FLOOR, "minecraft:quartz_bricks");
        b.box(u1 - 9, 2, 3, u1 - 9, 3, 4, AIR);
        b.marker("restroom", u1 - 5, 2, 4);
        b.box(-4, 3, vSec - 1, 4, 4, vSec - 1, "minecraft:black_concrete");
        b.marker("board:airside", 0, 3.5, vSec - 1.6);
        for (int u = u0 + 12; u < u1 - 12; u += 4) for (int v = 4; v < vSec - 3; v += 3) b.box(u, 2, v, u + 2, 2, v, "minecraft:smooth_quartz_slab");

        int spacing = W / gates;
        double doorX = cabin.doorOutside()[0], doorZ = cabin.doorOutside()[1];
        for (int g = 0; g < gates; g++) {
            String id = "A" + (g + 1);
            int ug = u0 + spacing / 2 + g * spacing;
            b.box(ug - 1, 2, 0, ug + 1, 4, 0, AIR);                                  // gate opening in the glass wall
            b.box(ug - 2, 2, 2, ug + 2, 2, 2, "minecraft:polished_diorite");          // gate desk
            b.marker("gate:" + id, ug, 2, 1.5);
            b.marker("agent:gate:" + id, ug, 2, 3);
            b.marker("janitor:" + id, ug + 4, 2, 5);
            // Jet bridge: enclosed corridor at terminal floor level out over the apron (or a ramp down for small airports).
            double endV;
            if (bridge > 0) {
                for (int v = -1; v >= -bridge; v--) {
                    b.box(ug - 1, 1, v, ug + 1, 1, v, "minecraft:gray_concrete");
                    b.set(ug - 2, 2, v, "minecraft:gray_concrete"); b.set(ug - 2, 3, v, (v % 3 == 0) ? GLASS : "minecraft:gray_concrete");
                    b.set(ug + 2, 2, v, "minecraft:gray_concrete"); b.set(ug + 2, 3, v, (v % 3 == 0) ? GLASS : "minecraft:gray_concrete");
                    b.box(ug - 2, 4, v, ug + 2, 4, v, "minecraft:gray_concrete");
                    b.box(ug - 1, 2, v, ug + 1, 3, v, AIR);
                }
                b.box(ug - 1, 0, -bridge / 2, ug - 1, 0, -bridge / 2, "minecraft:gray_concrete");     // support column foot
                endV = -bridge - 0.5;
            } else {
                // Small field: a raised boarding walkway at terminal floor level out to the aircraft door.
                b.box(ug - 1, 1, -1, ug + 1, 1, -6, "minecraft:polished_andesite");
                for (int v = -1; v >= -6; v--) { b.set(ug - 2, 2, v, "minecraft:oak_fence"); b.set(ug + 2, 2, v, "minecraft:oak_fence"); }
                endV = -6.5;
            }
            b.marker("bridge_end:" + id, ug, 2, endV);
            // Stand: aircraft parked heading +u with its left (door) side toward the terminal; the door's outside
            // threshold must sit at the bridge end. Aircraft local (x right, z forward) -> blueprint (u = z, v = -x).
            double standU = ug - doorZ, standV = endV + doorX;   // doorX is negative (left side): v = endV - |doorX|
            b.marker("stand:" + id, standU, 2, standV);       // cabin floor level == bridge/walkway floor surface
            b.marker("lane:" + id, standU, 0, -apron + 15);
            for (int u = (int) standU - 12; u <= (int) standU + 12; u++) b.set(u, 0, (int) Math.round(standV), LINE);  // lead-in line
        }

        // Control tower at the end of the terminal, airside.
        int tu = u1 + 12, towerH = W >= 96 ? 34 : 18;
        b.box(tu - 3, 1, 2, tu + 3, towerH, 8, "minecraft:light_gray_concrete");
        b.box(tu - 2, 2, 3, tu + 2, towerH - 1, 7, AIR);
        for (int y = 2; y < towerH; y++) b.set(tu, y, 7, "minecraft:scaffolding");          // climbable core
        b.box(tu - 1, 2, 8, tu + 1, 3, 8, AIR);                                                                          // door
        b.box(tu - 5, towerH, 0, tu + 5, towerH, 10, "minecraft:light_gray_concrete");                                   // cab floor
        for (int y = towerH + 1; y <= towerH + 3; y++) {
            for (int u = tu - 5; u <= tu + 5; u++) { b.set(u, y, 0, GLASS); b.set(u, y, 10, GLASS); }
            for (int v = 0; v <= 10; v++) { b.set(tu - 5, y, v, GLASS); b.set(tu + 5, y, v, GLASS); }
        }
        b.box(tu - 5, towerH + 4, 0, tu + 5, towerH + 4, 10, "minecraft:smooth_stone");
        b.box(tu - 3, towerH, 3, tu + 3, towerH, 7, "minecraft:light_gray_concrete");
        b.set(tu, towerH, 7, "minecraft:scaffolding");
        b.marker("tower", tu, towerH + 1, 5);
        return b;
    }
}
