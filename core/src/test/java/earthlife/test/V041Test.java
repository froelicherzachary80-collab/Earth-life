package earthlife.test;

import earthlife.core.build.Blueprint;
import earthlife.core.build.CinemaLayout;

import earthlife.core.geo.GeoPoint;
import earthlife.core.geo.LocalProjection;
import earthlife.core.world.*;

import java.nio.file.Files;
import java.nio.file.Path;

import static earthlife.test.T.*;

/** V0.4.1: generated movie theaters. */
public final class V041Test {
    static int count(Blueprint bp, String prefix) {
        int[] b = bp.bounds(); int n = 0;
        for (int u = b[0]; u <= b[2]; u++) for (int v = b[1]; v <= b[3]; v++) for (int y = 0; y <= CinemaLayout.HEIGHT + 1; y++) {
            String id = bp.get(u, y, v);
            if (id != null && id.startsWith(prefix)) n++;
        }
        return n;
    }

    public static void testMultiplexLayout() {
        var l = CinemaLayout.build(8, 72, 64, true);
        check(l.auditoriums().size() >= 4, "a 72 x 64 m multiplex gets at least 4 auditoriums, got " + l.auditoriums().size());
        check(l.auditoriums().stream().filter(CinemaLayout.Auditorium::motion).count() == 1, "exactly one 4D auditorium");
        for (var a : l.auditoriums()) {
            check(!a.seats().isEmpty(), a.name() + " has seats");
            check(a.screenW() >= 5 && a.screenH() >= 2 && a.screenH() <= CinemaLayout.HEIGHT - 4, a.name() + " screen size " + a.screenW() + "x" + a.screenH());
            // every seat faces the screen and sits on a solid floor, with free legroom in front of it
            for (int[] s : a.seats()) {
                String id = l.bp().get(s[0], s[1], s[2]);
                check(id != null && id.endsWith("@u-"), "seat faces the screen: " + id);
                check(l.bp().get(s[0], s[1] - 1, s[2]) != null && !l.bp().get(s[0], s[1] - 1, s[2]).endsWith("air"), "seat on a floor");
                String leg = l.bp().get(s[0] - 1, s[1], s[2]);
                check(leg == null || leg.endsWith("carpet") || leg.endsWith("air"), "legroom in front of the seat: " + leg);
                check(s[0] > a.screenU() + 4, "first row at least 5 m from the screen");
            }
            // the door into the room is open
            check("minecraft:air".equals(l.bp().get((int) a.door()[0] + 1, 1, (int) a.door()[2])), a.name() + " door open");
        }
        check(count(l.bp(), "earthlife:motion_seat") > 0 && count(l.bp(), "earthlife:recliner") > 20, "recliners and motion seats placed");
        check(count(l.bp(), "earthlife:popcorn_machine") >= 2 && count(l.bp(), "earthlife:soda_fountain") >= 2, "concession stand");
        check(count(l.bp(), "earthlife:ticket_kiosk") == 2, "ticket kiosks");
        check("minecraft:air".equals(l.bp().get(0, 1, 0)), "entrance door in the glass facade");
    }

    public static void testSmallCinema() {
        var l = CinemaLayout.build(0, 26, 18, false);
        check(l.auditoriums().size() == 1, "a small single-screen theater, got " + l.auditoriums().size());
        check(l.auditoriums().get(0).seats().size() >= 10, "it has seats: " + l.auditoriums().get(0).seats().size());
        check(l.auditoriums().stream().noneMatch(CinemaLayout.Auditorium::motion), "no 4D room unless asked");
    }

    public static void testDeepBuildingTwoRows() {
        var l = CinemaLayout.build(12, 110, 70, true);
        check(l.auditoriums().size() >= 8, "deep building fits two rows of auditoriums, got " + l.auditoriums().size());
        double maxU = l.auditoriums().stream().mapToDouble(CinemaLayout.Auditorium::screenU).max().orElse(0);
        check(maxU > 55, "second row behind the first (screen u " + maxU + ")");
    }

    static final GeoPoint WILLIAMSON = new GeoPoint(43.2237, -77.1861);

    static double meridianM(double lat) { double a = 6378137, e2 = 0.00669437999014, s = Math.sin(Math.toRadians(lat)); return a * (1 - e2) / Math.pow(1 - e2 * s * s, 1.5); }
    static double primeM(double lat) { double a = 6378137, e2 = 0.00669437999014, s = Math.sin(Math.toRadians(lat)); return a / Math.sqrt(1 - e2 * s * s); }

    public static void testAzimuthalScaleAcrossTheNortheast() {
        LocalProjection az = new LocalProjection(WILLIAMSON, -11, 1.0, true), plane = new LocalProjection(WILLIAMSON, -11, 1.0, false);
        // 1 km north-south and east-west steps at New York City, Philadelphia, Pittsburgh, Burlington VT, Cape May NJ
        double[][] places = {{40.7128, -74.0060}, {39.9526, -75.1652}, {40.4406, -79.9959}, {44.4759, -73.2121}, {38.9351, -74.9060}};
        double worstAz = 0, worstPlane = 0;
        for (double[] q : places) {
            double dlat = 1000 / meridianM(q[0]) * 180 / Math.PI, dlon = 1000 / (primeM(q[0]) * Math.cos(Math.toRadians(q[0]))) * 180 / Math.PI;
            for (LocalProjection p : new LocalProjection[]{az, plane}) {
                var a = p.toBlock(q[0], q[1]);
                var n = p.toBlock(q[0] + dlat, q[1]);
                var e = p.toBlock(q[0], q[1] + dlon);
                double ns = Math.hypot(n.x() - a.x(), n.z() - a.z()), ew = Math.hypot(e.x() - a.x(), e.z() - a.z());
                double err = Math.max(Math.abs(ns - 1000), Math.abs(ew - 1000)) / 1000;
                if (p == az) worstAz = Math.max(worstAz, err); else worstPlane = Math.max(worstPlane, err);
            }
            var b = az.toBlock(q[0], q[1]);
            var g = az.fromBlock(b.x(), b.z());
            check(Math.abs(g.lat() - q[0]) < 1e-7 && Math.abs(g.lon() - q[1]) < 1e-7, "block -> lat/lon round trip at " + q[0] + "," + q[1]);
        }
        check(worstAz < 0.0015, String.format("1:1 within 0.15%% everywhere in NY/PA/NJ/VT (worst %.3f%%; flat tangent plane %.3f%%)", worstAz * 100, worstPlane * 100));
        check(worstPlane > worstAz, "the azimuthal correction improves on the flat tangent plane");
        var w = az.toBlock(WILLIAMSON.lat(), WILLIAMSON.lon());
        check(Math.abs(w.x()) < 1e-6 && Math.abs(w.z()) < 1e-6, "Williamson stays at 0,0");
    }

    public static void testRegionPackCell() throws Exception {
        Path dir = Path.of("src/test/resources/regionpack");
        RegionPacks packs = RegionPacks.shared(dir, "http://invalid.example/", false);
        packs.parseIndex(Files.readString(dir.resolve("index.json")));
        check(packs.covered(345, -618) && !packs.covered(346, -618), "index lists the Williamson cell only");
        check(packs.tier(345, -618).orElse(null) == RegionCell.Tier.TOWN, "cell tier");
        LocalProjection proj = new LocalProjection(WILLIAMSON, -11, 1.0, true);
        PackWorld world = new PackWorld(packs, proj, "test", 1_000_000);
        int[] loads = {0};
        world.onLoad(c -> loads[0]++);
        var cell = world.cell(345, -618);
        check(cell != null && loads[0] == 1, "cell loads from the pack (written by the Python pipeline)");
        check(cell.features().roads.size() == 1 && cell.features().buildings.size() == 2 && cell.features().water.size() == 1 && cell.features().areas.size() == 1, "features");
        check("Ridge Road".equals(cell.features().names.get("w1001")), "street name kept");
        var road = cell.features().roads.get(0);
        var p0 = road.line().get(0);
        var exp = proj.toBlock(43.2230, -77.1900);
        check(Math.hypot(p0.x() - exp.x(), p0.z() - exp.z()) < 0.02, "road vertex projected to 2 cm (fast cell projection) " + p0 + " vs " + exp.x() + "," + exp.z());
        var water = cell.features().water.get(0);
        var hole = proj.toBlock(43.2315, -77.1975);
        var lake = proj.toBlock(43.2305, -77.1990);
        check(!water.contains(hole.x(), hole.z()) && water.contains(lake.x(), lake.z()), "island (inner ring) is dry, the rest is water");
        // terrain + Lake Ontario mask
        ElevationSource el = world.elevation((x, z) -> -999);
        var south = proj.toBlock(43.1255, -77.18);
        var north = proj.toBlock(43.2499, -77.18);
        check(Math.abs(el.elevationAt(south.x(), south.z()) - 100) < 6, "terrain in the south ~100 m: " + el.elevationAt(south.x(), south.z()));
        check(Double.isNaN(el.waterLevel(south.x(), south.z())) && el.waterLevel(north.x(), north.z()) == 74.0, "north edge is Lake Ontario (74 m surface)");
        // the tile generator builds from the pack: the house, and the lake water up to Y 63
        var gen = new TileGenerator(world.index(), el, proj, 1);
        var house = cell.features().buildings.stream().filter(b -> b.id().equals("w2002")).findFirst().get();
        var hp = house.footprint().get(0);
        var key = TileKey.containing("northeast", (int) Math.floor(hp.x() + 1), (int) Math.floor(hp.z() + 1), 0);
        var t = gen.generate(key);
        boolean found = false;
        for (int i = 0; i < t.n * t.n; i++) if ((t.flags[i] & TileData.FLAG_BUILDING) != 0) found = true;
        check(found, "house generated from the pack");
        var lk = TileKey.containing("northeast", (int) north.x(), (int) north.z() - 2, 0);
        var lt = gen.generate(lk);
        int sx = (int) Math.floor(north.x()) - lk.minX(), sz = (int) Math.floor(north.z() - 2) - lk.minZ();
        check(lt.has(sx, sz, TileData.FLAG_WATER) && lt.waterY[lt.idx(sx, sz)] == 63, "Great Lake column: water surface at Y 63, got " + lt.waterY[lt.idx(sx, sz)]);
        check(world.featuresNear(hp.x(), hp.z(), 200).buildings.size() == 2, "features near a point");
    }
}
