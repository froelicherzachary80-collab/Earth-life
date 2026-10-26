package earthlife.test;

import earthlife.core.events.HalloweenCalendar;
import earthlife.core.geo.GeoPoint;
import earthlife.core.geo.LocalProjection;
import earthlife.core.life.Objectives;
import earthlife.core.life.PlayerLife;
import earthlife.core.world.ElevationSource;
import earthlife.core.world.FeatureIndex;
import earthlife.core.world.Features;
import earthlife.core.world.TileData;
import earthlife.core.world.TileGenerator;
import earthlife.core.world.TileKey;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static earthlife.test.T.*;

/** V0.4: player life records, objectives, Halloween calendar, vertical datum, bundled starter dataset. */
public final class V04Test {
    static final Path DATA = Path.of("../fabric-mod/src/main/resources/data/earthlife/regions/western-ny");
    /** Same datum the mod uses: Y 63 = 74 m (Lake Ontario). */
    static final LocalProjection P = new LocalProjection(new GeoPoint(43.2237, -77.1861), 63 - 74, 1.0);

    public static void testPlayerLifeRoundTrip() {
        PlayerLife p = new PlayerLife("uuid-1");
        p.arrived = true; p.welcomed = true; p.scenario = "TRAVELER"; p.candy = 7; p.shareLocation = true;
        p.objectivesDone.add("open_map");
        p.waypoints.add(new PlayerLife.Waypoint("Lighthouse", "pin", 1234.5, 80, -987.25));
        p.home = new PlayerLife.Waypoint("Home", "home", 10, 70, 20);
        p.treated.add("2026:res-42"); p.contests.add("2026:costume");
        PlayerLife q = PlayerLife.parse("uuid-1", p.toJsonString());
        check(q.arrived && q.welcomed && q.shareLocation, "flags survive");
        check(q.scenario.equals("TRAVELER") && q.candy == 7, "scenario + candy");
        check(q.objectivesDone.contains("open_map"), "objectives");
        check(q.waypoint("lighthouse").isPresent() && q.waypoint("Lighthouse").get().x() == 1234.5, "waypoint lookup is case-insensitive and exact");
        check(q.home != null && q.home.z() == 20 && q.work == null, "home kept, no work");
        check(q.treated.contains("2026:res-42") && q.contests.contains("2026:costume"), "Halloween records");
    }

    public static void testOldOrPartialRecordsLoad() {
        PlayerLife q = PlayerLife.parse("u", "{\"arrived\":true}");
        check(q.arrived && q.scenario.equals("NEW_LIFE") && q.waypoints.isEmpty() && q.candy == 0, "missing fields default safely (migration)");
    }

    public static void testObjectivesPerScenario() {
        check(Objectives.forScenario("NORMAL_SURVIVAL").isEmpty(), "normal survival has no guided objectives");
        check(Objectives.forScenario("NEW_LIFE").size() >= 8, "new life is a full tutorial");
        check(Objectives.byId("NEW_LIFE", "get_job").isPresent() && Objectives.byId("TRAVELER", "get_job").isEmpty(), "ids are per scenario");
        PlayerLife p = new PlayerLife("u");
        p.objectivesDone.add("open_menu");
        var lines = Objectives.lines(p);
        check(lines.get(0).startsWith("✔") && lines.get(1).startsWith("•"), "done objectives are ticked");
        long total = Objectives.forScenario("NEW_LIFE").stream().mapToLong(Objectives.Objective::rewardLC).sum();
        check(total > 0 && total < 1000, "objective rewards are modest (" + total + " LC)");
    }

    public static void testHalloweenCalendar() {
        check(HalloweenCalendar.isSeason(LocalDate.of(2026, 10, 25)), "Oct 25 is Halloween week");
        check(HalloweenCalendar.isSeason(LocalDate.of(2026, 10, 31)) && HalloweenCalendar.isSeason(LocalDate.of(2026, 11, 1)), "Oct 31 / Nov 1");
        check(!HalloweenCalendar.isSeason(LocalDate.of(2026, 10, 23)) && !HalloweenCalendar.isSeason(LocalDate.of(2026, 11, 2)), "outside the week");
        check(HalloweenCalendar.countdown(LocalDate.of(2026, 10, 25)).contains("6 days"), "countdown " + HalloweenCalendar.countdown(LocalDate.of(2026, 10, 25)));
        check(HalloweenCalendar.countdown(LocalDate.of(2026, 10, 31)).contains("today"), "today");
        check(HalloweenCalendar.countdown(LocalDate.of(2026, 7, 4)).isEmpty(), "nothing in July");
        check(!HalloweenCalendar.isEveningHour(16) && HalloweenCalendar.isEveningHour(17) && HalloweenCalendar.isEveningHour(22) && !HalloweenCalendar.isEveningHour(23), "trick-or-treat hours");
        check(HalloweenCalendar.costumePrize(1) == 200 && HalloweenCalendar.costumePrize(7) == 10, "costume prizes");
        check(HalloweenCalendar.carvingPrize(3) == 45 && HalloweenCalendar.carvingPrize(50) == 150, "carving prize capped at 10 lanterns");
    }

    public static void testVerticalDatum() {
        check(P.elevationToY(74) == 63, "Lake Ontario surface (74 m) is vanilla sea level Y 63");
        near(P.yToElevation(63), 74, 1e-9, "inverse");
        check(P.elevationToY(125) == 114, "Williamson ~125 m -> Y 114");
        check(P.elevationToY(155 + 136) < 959, "Rochester's tallest tower on downtown ground fits in the 1024-tall world");
    }

    public static void testGridReadsFromGzipStream() throws IOException {
        var bo = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new GZIPOutputStream(bo))) {
            out.writeBytes("ELDG"); out.writeInt(1); out.writeDouble(0); out.writeDouble(0); out.writeDouble(10); out.writeInt(2); out.writeInt(2);
            out.writeFloat(100); out.writeFloat(110); out.writeFloat(120); out.writeFloat(130);
        }
        var g = ElevationSource.Grid.read(new GZIPInputStream(new ByteArrayInputStream(bo.toByteArray())), (x, z) -> -1);
        near(g.elevationAt(5, 5), 115, 1e-6, "bilinear centre");
        near(g.elevationAt(500, 500), -1, 0, "fallback outside the grid");
    }

    static String gz(Path p) throws IOException { try (var in = new GZIPInputStream(Files.newInputStream(p))) { return new String(in.readAllBytes()); } }

    public static void testBundledStarterDataset() throws IOException {
        if (!Files.exists(DATA.resolve("features.json.gz"))) throw new AssertionError("starter dataset missing at " + DATA.toAbsolutePath());
        Features f = Features.parse(gz(DATA.resolve("features.json.gz")));
        check(f.attribution.contains("OpenStreetMap"), "ODbL attribution kept: " + f.attribution);
        check(f.buildings.size() > 5000 && f.roads.size() > 2000, "real data volume: " + f.buildings.size() + " buildings, " + f.roads.size() + " roads");
        long nearRoads = f.roads.stream().filter(r -> r.line().stream().anyMatch(p -> Math.hypot(p.x(), p.z()) < 800)).count();
        long nearBuildings = f.buildings.stream().filter(b -> Math.hypot(b.footprint().get(0).x(), b.footprint().get(0).z()) < 800).count();
        check(nearRoads > 10 && nearBuildings > 50, "Williamson centre is mapped: " + nearRoads + " roads, " + nearBuildings + " buildings within 800 m");
        // a typical house footprint is house-sized (5..40 m across), i.e. coordinates really are metres
        var b = f.buildings.stream().filter(x -> "house".equals(x.kind()) && Math.hypot(x.footprint().get(0).x(), x.footprint().get(0).z()) < 3000).findFirst().orElseThrow();
        double[] bb = Features.bbox(b.footprint(), 0);
        double w = bb[2] - bb[0], d = bb[3] - bb[1];
        check(w > 4 && w < 40 && d > 4 && d < 40, "house " + b.id() + " is " + Math.round(w) + " x " + Math.round(d) + " m");
    }

    public static void testBundledTerrain() throws IOException {
        if (!Files.exists(DATA.resolve("dem.eldg.gz"))) throw new AssertionError("starter terrain missing");
        ElevationSource coarse = ElevationSource.Grid.read(new GZIPInputStream(Files.newInputStream(DATA.resolve("dem-region.eldg.gz"))), (x, z) -> Double.NaN);
        ElevationSource dem = ElevationSource.Grid.read(new GZIPInputStream(Files.newInputStream(DATA.resolve("dem.eldg.gz"))), coarse);
        double williamson = dem.elevationAt(0, 0);
        check(williamson > 90 && williamson < 170, "Williamson village elevation " + Math.round(williamson) + " m (real: ~125 m)");
        check(dem.isMeasured(0, 0), "measured, not estimated");
        // 6 km north of Williamson is Lake Ontario
        var lake = P.toBlock(43.30, -77.19);
        double e = dem.elevationAt(lake.x(), lake.z());
        check(e < 74, "Lake Ontario at 43.30,-77.19 is below the 74 m water surface (got " + Math.round(e * 10) / 10.0 + " m)");
        // Rochester (outside the 20 m starter grid) comes from the 60 m region grid
        var roc = P.toBlock(43.1566, -77.6088);
        double r = dem.elevationAt(roc.x(), roc.z());
        check(!Double.isNaN(r) && r > 100 && r < 200, "Rochester from the coarse region grid: " + Math.round(r) + " m");
    }

    public static void testStarterTileGeneratesRealRoads() throws IOException {
        Features f = Features.parse(gz(DATA.resolve("features.json.gz")));
        TileGenerator gen = new TileGenerator(new FeatureIndex(f), new ElevationSource.Procedural(125, 0, 1), P, 1);
        TileData t = gen.generate(new TileKey("western-ny", 0, 0, 0));
        int road = 0, building = 0;
        for (int z = 0; z < 256; z++) for (int x = 0; x < 256; x++) {
            if (t.has(x, z, TileData.FLAG_ROAD)) road++;
            if (t.structureTopY[t.idx(x, z)] > 0) building++;
        }
        check(road > 500 && building > 500, "tile 0,0 (Williamson) has " + road + " road columns and " + building + " building columns");
    }
}
