package earthlife.test;

import earthlife.core.aviation.*;
import earthlife.core.build.*;
import earthlife.core.geo.*;
import earthlife.core.time.GameClock;
import earthlife.core.world.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;

import static earthlife.test.T.*;

/** V0.2: time system, towns, infill, airport buildings, cabin walking. */
public final class V02WorldTest {
    static final ZoneId NY = ZoneId.of("America/New_York");
    static long ms(int h, int m) { return ZonedDateTime.of(2026, 10, 25, h, m, 0, 0, NY).toInstant().toEpochMilli(); }

    public static void testTicksOfDayMapping() {
        check(GameClock.ticksOfDay(LocalTime.of(6, 0)) == 0, "06:00 = 0");
        check(GameClock.ticksOfDay(LocalTime.NOON) == 6000, "noon = 6000");
        check(GameClock.ticksOfDay(LocalTime.MIDNIGHT) == 18000, "midnight = 18000");
        check(GameClock.timeOf(6000).equals(LocalTime.NOON), "inverse");
    }

    public static void testRealtimeFollowsWallClockAndNeverRunsBackwards() {
        GameClock c = new GameClock(GameClock.Mode.REALTIME, 1440, NY);
        long t = c.next(ms(12, 0), 0);
        check(t == 6000, "noon real -> 6000, got " + t);
        t = c.next(ms(13, 0), t);
        check(t == 7000, "13:00 -> 7000, got " + t);
        long back = c.next(ms(9, 0), t);
        check(back == t, "wall clock rollback ignored");
        t = c.next(ms(12, 0) + 86_400_000L, t);
        check(t == 24000 + 6000 && GameClock.day(t) == 1, "next real day -> next game day, got " + t);
        // Someone sets time 2h ahead: clock waits for real time to catch up instead of skipping a day.
        long ahead = t + 2000;
        check(c.next(ms(12, 30) + 86_400_000L, ahead) == ahead, "holds while world is ahead");
    }

    public static void testScaledSpeeds() {
        GameClock fast = new GameClock(GameClock.Mode.SCALED, 20, NY);
        long t = fast.next(0, 1000);
        t = fast.next(10 * 60_000, t);
        check(t == 1000 + 12000, "20 min/day: 10 real min = half a day, got " + t);
        GameClock real = new GameClock(GameClock.Mode.SCALED, 1440, NY);
        long u = real.next(0, 0);
        for (int i = 1; i <= 3600; i++) u = real.next(i * 1000L, u);
        check(u == 1000, "1440 min/day: 1 real hour = 1 game hour (1000 ticks), got " + u);
        near(real.realSecondsFor(24000), 86_400, 1e-6, "full day lasts 24 h");
        throwsEx(IllegalArgumentException.class, () -> new GameClock(GameClock.Mode.SCALED, 0.5, NY), "bounds");
        GameClock v = new GameClock(GameClock.Mode.VANILLA, 20, NY);
        check(v.next(5, 777) == 777 && !v.drivesTime(), "vanilla leaves time alone");
    }

    public static void testTownsCatalog() throws Exception {
        var towns = Towns.load(Files.readString(Path.of(System.getProperty("earthlife.data", "../data"), "towns.json")));
        check(towns.size() >= 12, "at least a dozen Western NY places, got " + towns.size());
        check(Towns.find(towns, "roch").map(t -> t.kind() == Towns.Kind.CITY).orElse(false), "Rochester is a city");
        check(Towns.nearest(towns, new GeoPoint(43.224, -77.186)).name().equals("Williamson"), "nearest to origin");
    }

    public static void testInfillAddsEstimatedHousesOnlyInGaps() {
        String json = """
            {"attribution":"t","roads":[
              {"id":"main","class":"RESIDENTIAL","width":6,"line":[[0,0],[400,0]]},
              {"id":"cross","class":"RESIDENTIAL","width":6,"line":[[200,-150],[200,150]]}],
             "buildings":[{"id":"real","kind":"house","levels":2,"footprint":[[40,8],[52,8],[52,20],[40,20]]}],
             "areas":[],"water":[]}""";
        Features f = Features.parse(json);
        var centre = List.of(new ProceduralInfill.Centre("Testville", 200, 0, 600, false));
        var houses = ProceduralInfill.infill(f, centre, 7);
        check(houses.size() > 15, "many houses added, got " + houses.size());
        check(houses.stream().allMatch(b -> b.prov() == Features.Provenance.ESTIMATED), "all marked ESTIMATED");
        for (var h : houses) for (var p : h.footprint()) for (var r : f.roads)
            for (int k = 1; k < r.line().size(); k++)
                check(Features.distToSegment(p.x(), p.z(), r.line().get(k - 1), r.line().get(k)) > r.widthM() / 2, "house " + h.id() + " clear of road " + r.id());
        check(houses.stream().noneMatch(h -> Features.pointInRing(h.footprint(), 46, 14)), "no house on top of the mapped one");
        var again = ProceduralInfill.infill(Features.parse(json), centre, 7);
        check(again.size() == houses.size(), "deterministic");
        var none = ProceduralInfill.infill(Features.parse(json), List.of(new ProceduralInfill.Centre("Far", 5000, 5000, 300, false)), 7);
        check(none.isEmpty(), "nothing outside towns");
    }

    static LocalProjection proj() { return new LocalProjection(new GeoPoint(43.2237, -77.1861), 63, 1.0); }

    public static void testAirportBuildingsAreWalkableAndClearOfRunways() throws Exception {
        var airports = AviationTest.airports();
        var cabin = new CabinLayout(AircraftType.HOPPER);
        for (String icao : List.of("KSDC", "KROC")) {
            Airport a = airports.get(icao);
            AirportLayout l = new AirportLayout(a, proj(), cabin);
            Blueprint b = l.blueprint;
            check(l.stands.size() == (l.size == AirportLayout.Size.SMALL ? 1 : l.size == AirportLayout.Size.MEDIUM ? 4 : 8), icao + " gate count");
            // Terminal interior: floor everywhere, headroom above (furniture allowed only at known spots).
            int[] bnd = b.bounds();
            int floorMissing = 0, interiorCells = 0;
            for (int u = -10; u <= 10; u++) for (int v = 1; v <= 20; v++) {
                interiorCells++;
                String fl = b.get(u, 1, v);
                if (fl == null || fl.equals("minecraft:air")) floorMissing++;
            }
            check(floorMissing == 0, icao + " terminal floor complete (" + interiorCells + " cells checked)");
            for (String m : List.of("entrance:1", "checkin:1", "security:1", "gate:A1", "board:airside", "shop:1", "tower", "bridge_end:A1")) {
                check(b.markers().containsKey(m), icao + " has " + m);
            }
            double[] gate = b.markers().get("gate:A1");
            check("minecraft:air".equals(b.get((int) gate[0], 2, 0)) && "minecraft:air".equals(b.get((int) gate[0], 3, 0)), icao + " gate opening is passable");
            double[] ent = b.markers().get("entrance:1");
            int[] ws = l.placed.worldBounds();
            check(ws[2] - ws[0] > 40, "placed footprint has size");
            // Buildings clear of every runway by > 60 m
            for (var r : a.runways()) {
                var p0 = proj().toBlock(r.threshold().lat(), r.threshold().lon()); var p1 = proj().toBlock(r.end().lat(), r.end().lon());
                for (String m : List.of("gate:A1", "tower", "checkin:1", "parking")) {
                    double[] w = l.placed.marker(m);
                    double d = Features.distToSegment(w[0], w[2], new Features.Pt(p0.x(), p0.z()), new Features.Pt(p1.x(), p1.z()));
                    check(d > 60, icao + " " + m + " is " + Math.round(d) + " m from runway " + r.ident());
                }
            }
            // Taxiways end on the main runway centreline
            var mr = a.mainRunway();
            var q0 = proj().toBlock(mr.threshold().lat(), mr.threshold().lon()); var q1 = proj().toBlock(mr.end().lat(), mr.end().lon());
            double[] rw = l.placed.marker("runway:+");
            check(Features.distToSegment(rw[0], rw[2], new Features.Pt(q0.x(), q0.z()), new Features.Pt(q1.x(), q1.z())) < 2, icao + " taxiway meets runway centreline");
            // Parked aircraft door lines up with the jet bridge end.
            var st = l.stands.get(0);
            double h = Math.toRadians(st.headingDeg());
            double fx = Math.sin(h), fz = -Math.cos(h);                 // forward
            double rx = Math.cos(h), rz = Math.sin(h);                  // right (east,south) of heading
            double[] door = cabin.doorOutside();
            double dx = st.x() + rx * door[0] + fx * door[1], dz = st.z() + rz * door[0] + fz * door[1];
            double[] be = l.placed.marker("bridge_end:A1");
            check(Math.hypot(dx - be[0], dz - be[2]) < 0.6, icao + " door meets bridge end (off by " + Math.hypot(dx - be[0], dz - be[2]) + ")");
            check(Math.abs(st.floorY() - (l.baseY + 2)) <= 0, icao + " cabin floor at bridge floor level");
            // Flattened sites don't cover the runway's far parts and include the terminal.
            check(l.siteRings().stream().anyMatch(ring -> Features.pointInRing(ring, l.placed.marker("checkin:1")[0], l.placed.marker("checkin:1")[2])), icao + " terminal site levelled");
            System.out.printf("    %s: %s airport, %d blocks, %d gates%n", icao, l.size, b.blockCount(), l.stands.size());
        }
    }

    public static void testFlightUsesTaxiwaysAndParksAtStand() throws Exception {
        var airports = AviationTest.airports();
        var cabin = new CabinLayout(AircraftType.HOPPER);
        var p = proj();
        AirportLayout ls = new AirportLayout(airports.get("KSDC"), p, cabin), lr = new AirportLayout(airports.get("KROC"), p, cabin);
        Airport from = airports.get("KSDC").withGate(ls.stands.get(0).geo()), to = airports.get("KROC").withGate(lr.stands.get(1).geo());
        double course = from.ref().bearingTo(to.ref());
        var sim = new FlightSim(AircraftType.HOPPER, from, to,
                ls.taxiOut(ls.stands.get(0), from.bestRunwayFor(course), p), lr.taxiIn(lr.stands.get(1), to.bestRunwayFor(course), p));
        sim.beginDeparture();
        int n = 0;
        while (sim.phase() != FlightSim.Phase.ARRIVED && n++ < 80_000) sim.tick(0.05);
        check(sim.phase() == FlightSim.Phase.ARRIVED, "arrived");
        var end = sim.telemetry().position();
        var stb = p.toBlock(end.lat(), end.lon());
        check(Math.hypot(stb.x() - lr.stands.get(1).x(), stb.z() - lr.stands.get(1).z()) < 1, "parked on stand A2 at ROC");
    }

    /** Every route in the V0.2 timetable flies stand-to-stand along generated taxiways. */
    public static void testAllTimetableRoutesFly() throws Exception {
        var airports = AviationTest.airports();
        var cabin = new CabinLayout(AircraftType.HOPPER);
        var p = proj();
        Map<String, AirportLayout> layouts = new HashMap<>();
        for (var a : airports.values()) {
            AirportLayout l = new AirportLayout(a, p, cabin);
            check(!l.fallbackPlacement, a.icao() + " found a spot clear of its runways");
            layouts.put(a.icao(), l);
        }
        String[][] routes = {{"KSDC", "KROC"}, {"KROC", "KSDC"}, {"KROC", "KIUA"}, {"KIUA", "KROC"}, {"KROC", "KGVQ"}, {"KGVQ", "KROC"}};
        for (String[] r : routes) {
            AirportLayout lf = layouts.get(r[0]), lt = layouts.get(r[1]);
            var dep = lf.stands.get(0); var arr = lt.stands.get(Math.min(1, lt.stands.size() - 1));
            Airport from = airports.get(r[0]).withGate(dep.geo()), to = airports.get(r[1]).withGate(arr.geo());
            double course = from.ref().bearingTo(to.ref());
            var sim = new FlightSim(AircraftType.HOPPER, from, to, lf.taxiOut(dep, from.bestRunwayFor(course), p), lt.taxiIn(arr, to.bestRunwayFor(course), p));
            sim.setTimeCompression(4);
            sim.beginDeparture();
            int n = 0;
            while (sim.phase() != FlightSim.Phase.ARRIVED && n++ < 100_000) sim.tick(0.05);
            check(sim.phase() == FlightSim.Phase.ARRIVED, r[0] + "->" + r[1] + " arrived");
            System.out.printf("    %s->%s: %.0f km, %.1f min at 1x%n", r[0], r[1], sim.totalDistance() / 1000, sim.telemetry().elapsedSeconds() / 60);
        }
    }

    public static void testCabinWalkingAndCollision() {
        CabinLayout c = new CabinLayout(AircraftType.HOPPER);
        check(c.seats().size() == 48, "48 seats");
        double x = c.doorInside()[0], z = c.doorInside()[1];
        check(c.walkable(x, z), "door spot walkable");
        // Walk to the aisle (turn right = +90°), then down the aisle to the back (yaw 180 = facing the tail).
        for (int i = 0; i < 40; i++) { var m = c.move(x, z, 1, 0, 90, 0.05, false); x = m.x(); z = m.z(); }
        check(x <= 0.45 - CabinLayout.RADIUS + 1e-9 && x > -0.3, "stopped before the galley counter, x=" + x);
        x = 0;
        for (int i = 0; i < 200; i++) { var m = c.move(x, z, 1, 0, 180, 0.05, false); x = m.x(); z = m.z(); }
        check(z < c.rear + 1.0 && z >= c.rear + CabinLayout.RADIUS - 1e-9, "reached the rear wall, z=" + z);
        // Can't walk sideways into the seats from the aisle
        double sx = x;
        for (int i = 0; i < 20; i++) { var m = c.move(x, -2, 0, 1, 0, 0.05, false); x = m.x(); }
        check(Math.abs(x) < 0.3, "seats block sideways movement, x=" + x);
        // Doors closed: can't leave. Doors open: walking out of the door frame exits.
        double dx = c.doorInside()[0], dz = c.doorInside()[1];
        for (int i = 0; i < 20; i++) { var m = c.move(dx, dz, 1, 0, -90, 0.05, false); check(!m.exited(), "no exit while closed"); dx = m.x(); }
        check(dx >= -CabinLayout.WIDTH / 2, "stayed inside");
        boolean exited = false;
        for (int i = 0; i < 20 && !exited; i++) { var m = c.move(dx, dz, 1, 0, -90, 0.05, true); exited = m.exited(); dx = m.x(); }
        check(exited, "walked out of the open door");
    }

    public static void testCabinModelHasFloorWindowsAndDoor() {
        CabinLayout c = new CabinLayout(AircraftType.HOPPER);
        var parts = c.parts();
        var floor = parts.stream().filter(p -> p.tag().equals("floor")).findFirst().orElseThrow();
        for (double x = -1.6; x <= 1.6; x += 0.2) for (double z = c.rear; z <= c.front; z += 0.25)
            check(floor.minX() <= x && floor.maxX() >= x && floor.minZ() <= z + 1e-9 && floor.maxZ() >= z - 1e-9, "floor under " + x + "," + z);
        check(parts.stream().filter(p -> p.tag().equals("window")).count() == 2L * AircraftType.HOPPER.rows(), "a window per row per side");
        check(parts.stream().anyMatch(p -> p.tag().equals("door") && p.minZ() <= c.doorZ && p.maxZ() >= c.doorZ), "door panel covers the door");
        // With door panels removed, there is an opening at the door
        boolean blocked = parts.stream().filter(p -> !p.tag().equals("door")).anyMatch(p -> p.contains(-CabinLayout.WIDTH / 2 - 0.04, 1.0, c.doorZ));
        check(!blocked, "nothing but the door panel fills the doorway");
        System.out.printf("    cabin: %.1f m long, %d model parts%n", c.length, parts.size());
    }
}
