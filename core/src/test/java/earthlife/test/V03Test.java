package earthlife.test;

import earthlife.core.build.Landmarks;
import earthlife.core.events.*;
import earthlife.core.geo.*;
import earthlife.core.world.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static earthlife.test.T.*;

/** V0.3: realistic buildings, landmarks, real-time events. */
public final class V03Test {
    static final ZoneId NY = ZoneId.of("America/New_York");
    static final LocalProjection P = new LocalProjection(new GeoPoint(43.2237, -77.1861), 63, 1.0);

    static TileGenerator gen(String buildingsJson) {
        String json = "{\"attribution\":\"t\",\"roads\":[{\"id\":\"street\",\"class\":\"RESIDENTIAL\",\"width\":6,\"line\":[[-50,-20],[150,-20]]}],"
                + "\"buildings\":[" + buildingsJson + "],\"areas\":[],\"water\":[]}";
        return new TileGenerator(new FeatureIndex(Features.parse(json)), new ElevationSource.Procedural(100, 0, 1), P, 1);
    }

    public static void testTaggedBuildingsUseRealHeightRoofAndColour() {
        // 16 x 10 house, long axis along x, south of the street (street at z=-20; house z=0..10)
        String house = "{\"id\":\"h\",\"kind\":\"house\",\"levels\":2,\"footprint\":[[10,0],[26,0],[26,10],[10,10]],"
                + "\"style\":{\"roofShape\":\"gabled\",\"roofColour\":\"darkred\",\"colour\":\"#6e8cb0\",\"material\":\"wood\"}}";
        String office = "{\"id\":\"o\",\"kind\":\"office\",\"levels\":5,\"footprint\":[[60,0],[80,0],[80,20],[60,20]],"
                + "\"style\":{\"height\":40,\"material\":\"glass\"}}";
        TileData t = gen(house + "," + office).generate(new TileKey("r", 0, 0, 0));
        int ground = t.terrainY[t.idx(18, 5)];
        check(t.eaveY[t.idx(18, 5)] == ground + 6, "2 levels -> 6-block walls");
        int ridge = t.structureTopY[t.idx(18, 5)], edge = t.structureTopY[t.idx(18, 0)];
        check(ridge > edge, "gabled: ridge (" + ridge + ") higher than the long edge (" + edge + ")");
        check(t.structureTopY[t.idx(10, 5)] == ridge || Math.abs(t.structureTopY[t.idx(11, 5)] - ridge) <= 1, "ridge runs along the long axis");
        check(BuildingStyle.Wall.values()[t.wallMat[t.idx(18, 5)]] == BuildingStyle.Wall.BLUE_SIDING, "tagged blue wood siding -> " + BuildingStyle.Wall.values()[t.wallMat[t.idx(18, 5)]]);
        check(BuildingStyle.Roof.values()[t.roofMat[t.idx(18, 5)]] == BuildingStyle.Roof.METAL_RED, "tagged dark red roof");
        // Front door faces the street (north side, z=0 edge)
        int doors = 0, doorsNorth = 0;
        for (int x = 10; x <= 26; x++) for (int z = 0; z <= 10; z++) if (t.has2(x, z, TileData.F2_DOOR)) { doors++; if (z <= 1) doorsNorth++; }
        check(doors >= 1 && doors == doorsNorth, "door(s) on the street side: " + doors + "/" + doorsNorth);
        // Office: real height 40 m, flat roof, glass
        int og = t.terrainY[t.idx(70, 10)];
        check(t.structureTopY[t.idx(70, 10)] == og + 40, "office height from tag: " + (t.structureTopY[t.idx(70, 10)] - og));
        check(BuildingStyle.Wall.values()[t.wallMat[t.idx(70, 10)]] == BuildingStyle.Wall.GLASS, "glass office");
    }

    public static void testUntaggedDefaultsAreRegional() {
        String barn = "{\"id\":\"b1\",\"kind\":\"barn\",\"levels\":1,\"footprint\":[[0,0],[20,0],[20,12],[0,12]]}";
        var b = Features.parse("{\"attribution\":\"t\",\"roads\":[],\"buildings\":[" + barn + "],\"areas\":[],\"water\":[]}").buildings.get(0);
        var s = BuildingStyle.resolve(b);
        check(s.shape() == BuildingStyle.Shape.GAMBREL && (s.wall() == BuildingStyle.Wall.RED_BARN || s.wall() == BuildingStyle.Wall.WOOD), "barns: gambrel, red/wood");
        check(!s.fromTags(), "flagged as default style");
        var shop = new Features.Building("s", "retail", 2, List.of(new Features.Pt(0, 0), new Features.Pt(10, 0), new Features.Pt(10, 10), new Features.Pt(0, 10)), Features.Provenance.VERIFIED);
        check(BuildingStyle.resolve(shop).shape() == BuildingStyle.Shape.FLAT, "main-street shop has a flat roof");
        var o = BuildingStyle.obb(List.of(new Features.Pt(0, 0), new Features.Pt(30, 10), new Features.Pt(27, 19), new Features.Pt(-3, 9)));
        near(o.halfU() * 2, Math.hypot(30, 10), 0.5, "rotated OBB long side"); near(o.halfV() * 2, Math.hypot(3, 9), 0.5, "rotated OBB short side");
        check(BuildingStyle.rgb("#fff")[0] == 255 && BuildingStyle.rgb("Dark Green") != null, "colour parsing");
    }

    public static void testLandmarksBuildForEveryType() throws Exception {
        var all = Landmarks.load(Files.readString(Path.of(System.getProperty("earthlife.data", "../data"), "landmarks.json")));
        check(all.size() >= 8, "landmark catalog");
        check(Landmarks.find(all, "Frontier Field").map(l -> l.id().equals("innovative_field")).orElse(false), "alias lookup (old stadium name)");
        check(Landmarks.find(all, "Blue Cross Arena at the War Memorial").isPresent(), "full venue name lookup");
        var elev = new ElevationSource.Procedural(150, 0, 1);
        for (Landmarks.Type type : Landmarks.Type.values()) {
            var l = new Landmarks.Landmark("t-" + type, "Test " + type, type, new GeoPoint(43.2, -77.2), null, null, null, 30.0, false, List.of());
            var p = Landmarks.build(l, P, elev);
            check(p.placed().bp.blockCount() > 50, type + " has a structure (" + p.placed().bp.blockCount() + " blocks)");
            check(p.marker("gate") != null || type == Landmarks.Type.WATER_TOWER, type + " has an entrance marker");
        }
        var stadium = Landmarks.build(Landmarks.find(all, "Innovative Field").get(), P, elev);
        check(stadium.markers("seats:").size() > 100, "ballpark has seats for a crowd: " + stadium.markers("seats:").size());
        check(stadium.marker("scoreboard") != null && stadium.marker("field:home") != null, "scoreboard + home plate");
        var arena = Landmarks.build(Landmarks.find(all, "Blue Cross Arena").get(), P, elev);
        check(arena.marker("floor") != null && arena.markers("seats:").size() > 50, "arena floor + seats");
        var tower = Landmarks.build(Landmarks.find(all, "Xerox Tower").get(), P, elev);
        check(tower.marker("roof")[1] - tower.baseY() >= 135, "tower built to its real height");
    }

    public static void testHolidaysAreExact() {
        var h = Holidays.forYear(2026, NY);
        Map<String, LocalDate> d = new HashMap<>();
        for (var e : h) d.put(e.title(), e.start().atZone(NY).toLocalDate());
        check(d.get("Thanksgiving").equals(LocalDate.of(2026, 11, 26)), "Thanksgiving 2026 = Nov 26");
        check(d.get("Easter Sunday").equals(LocalDate.of(2026, 4, 5)), "Easter 2026 = Apr 5");
        check(d.get("Memorial Day").equals(LocalDate.of(2026, 5, 25)), "Memorial Day 2026 = May 25");
        check(d.get("Labor Day").equals(LocalDate.of(2026, 9, 7)), "Labor Day 2026 = Sep 7");
        check(Holidays.forYear(2027, NY).stream().anyMatch(e -> e.title().equals("Easter Sunday") && e.start().atZone(NY).toLocalDate().equals(LocalDate.of(2027, 3, 28))), "Easter 2027 = Mar 28");
        var fw = h.stream().filter(e -> e.title().equals("Fourth of July fireworks")).findFirst().orElseThrow();
        check(fw.start().atZone(NY).toLocalTime().equals(LocalTime.of(21, 30)) && fw.activities().contains(LiveEvent.Activity.FIREWORKS), "fireworks 9:30 pm local");
        var halloween = h.stream().filter(e -> e.title().equals("Halloween season")).findFirst().orElseThrow();
        check(halloween.activeAt(ZonedDateTime.of(2026, 10, 25, 16, 0, 0, 0, NY).toInstant()), "today (Oct 25 2026) is Halloween decoration season");
    }

    static final String MLB = """
        {"dates":[{"date":"2026-07-04","games":[
          {"gamePk":777001,"gameDate":"2026-07-04T23:05:00Z","status":{"abstractGameState":"Live","detailedState":"In Progress"},
           "teams":{"away":{"team":{"id":422,"name":"Buffalo Bisons"},"score":2},"home":{"team":{"id":534,"name":"Rochester Red Wings"},"score":3}},
           "linescore":{"currentInning":6,"currentInningOrdinal":"6th","inningState":"Top","inningHalf":"Top"},
           "venue":{"id":2541,"name":"Innovative Field","location":{"city":"Rochester","defaultCoordinates":{"latitude":43.15837,"longitude":-77.61988}}}},
          {"gamePk":777002,"gameDate":"2026-07-05T17:05:00Z","status":{"abstractGameState":"Preview","detailedState":"Postponed"},
           "teams":{"away":{"team":{"id":422,"name":"Buffalo Bisons"}},"home":{"team":{"id":534,"name":"Rochester Red Wings"}}},
           "venue":{"id":2541,"name":"Innovative Field"}}]}]}""";
    static final String TEAMS = """
        {"teams":[{"id":422,"name":"Buffalo Bisons"},{"id":534,"name":"Rochester Red Wings"},{"id":552,"name":"Syracuse Mets"}]}""";
    static final String TM = """
        {"_embedded":{"events":[
          {"name":"Example Band Live","id":"G5vYZ9x","url":"https://www.ticketmaster.com/event/G5vYZ9x",
           "dates":{"start":{"localDate":"2026-07-04","localTime":"19:30:00","dateTime":"2026-07-04T23:30:00Z"},"status":{"code":"onsale"}},
           "classifications":[{"segment":{"name":"Music"},"genre":{"name":"Rock"}}],
           "_embedded":{"venues":[{"name":"Blue Cross Arena","city":{"name":"Rochester"},"location":{"longitude":"-77.6137","latitude":"43.1530"}}]}},
          {"name":"Red Wings vs Bisons","id":"Dup1","dates":{"start":{"dateTime":"2026-07-04T23:05:00Z"},"status":{"code":"onsale"}},
           "classifications":[{"segment":{"name":"Sports"}}],
           "_embedded":{"venues":[{"name":"Innovative Field","location":{"longitude":"-77.6199","latitude":"43.1584"}}]}},
          {"name":"Far Away Show","id":"far1","dates":{"start":{"dateTime":"2026-07-04T23:00:00Z"},"status":{"code":"onsale"}},
           "classifications":[{"segment":{"name":"Music"}}],
           "_embedded":{"venues":[{"name":"Somewhere Else","location":{"longitude":"-73.99","latitude":"40.75"}}]}},
          {"name":"TBA Date","id":"tba","dates":{"start":{"localDate":"2026-08-01"},"status":{"code":"onsale"}}}]}}""";
    static final String ICS = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:fw-2026@williamson\r\n"
            + "DTSTART;TZID=America/New_York:20260704T213000\r\nDTEND;TZID=America/New_York:20260704T221500\r\n"
            + "SUMMARY:Village of Williamson Fireworks\\, at the \r\n park\r\nGEO:43.2240;-77.1870\r\nLOCATION:Williamson\\, NY\r\nEND:VEVENT\r\n"
            + "BEGIN:VEVENT\r\nUID:market\r\nDTSTART;VALUE=DATE:20260711\r\nSUMMARY:Farmers Market\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";

    public static void testFeedParsers() {
        var games = Sources.parseMlbSchedule(MLB);
        check(games.size() == 1, "postponed game dropped");
        var g = games.get(0);
        check(g.title().equals("Buffalo Bisons at Rochester Red Wings") && g.venue().equals("Innovative Field"), "game title + venue");
        check(g.score().homeRuns() == 3 && g.score().awayRuns() == 2 && g.score().detail().equals("Top 6th"), "live score: " + g.score());
        near(g.lat(), 43.15837, 1e-6, "venue coordinates");
        check(Sources.mlbTeamId(TEAMS, "Rochester Red Wings").orElse(-1) == 534, "team lookup");
        var tm = Sources.parseTicketmaster(TM);
        check(tm.size() == 3 && tm.get(0).category() == LiveEvent.Category.CONCERT && tm.get(0).lat() == 43.1530, "Ticketmaster parse (TBA skipped)");
        var ics = Sources.parseIcs(ICS, "williamson", NY);
        check(ics.size() == 2, "two calendar events");
        check(ics.get(0).title().equals("Village of Williamson Fireworks, at the park"), "line unfolding + unescape: " + ics.get(0).title());
        check(ics.get(0).start().equals(ZonedDateTime.of(2026, 7, 4, 21, 30, 0, 0, NY).toInstant()), "TZID time");
        check(ics.get(0).activities().contains(LiveEvent.Activity.FIREWORKS), "fireworks keyword");
        check(ics.get(1).start().equals(LocalDate.of(2026, 7, 11).atStartOfDay(NY).toInstant()), "all-day event");
    }

    public static void testEnginePlacesDedupsCachesAndSurvivesOffline() throws Exception {
        var landmarks = Landmarks.load(Files.readString(Path.of(System.getProperty("earthlife.data", "../data"), "landmarks.json")));
        var towns = Towns.load(Files.readString(Path.of(System.getProperty("earthlife.data", "../data"), "towns.json")));
        Path cache = tmpDir().resolve("events.json");
        AtomicInteger calls = new AtomicInteger();
        EventEngine.Fetcher net = url -> {
            calls.incrementAndGet();
            if (url.contains("/teams")) return TEAMS;
            if (url.contains("statsapi")) return MLB;
            if (url.contains("ticketmaster")) return TM;
            if (url.contains("williamson")) return ICS;
            throw new java.io.IOException("unexpected " + url);
        };
        var cfg = new EventEngine.Config(true, true, List.of("Rochester Red Wings"), List.of(11), "KEY", 50, List.of("https://example.org/williamson.ics"), NY);
        GeoPoint centre = new GeoPoint(43.13, -77.35);
        var eng = new EventEngine(cfg, centre, 90_000, landmarks, towns, cache, net);
        Instant gameTime = Instant.parse("2026-07-04T23:40:00Z");
        eng.refresh(gameTime);
        check(eng.errors().isEmpty(), "no errors: " + eng.errors());
        var now = eng.activeAt(gameTime);
        var game = now.stream().filter(p -> p.event().category() == LiveEvent.Category.SPORTS).toList();
        check(game.size() == 1 && "innovative_field".equals(game.get(0).landmarkId()), "game at Innovative Field, Ticketmaster duplicate merged: " + game.size());
        check(now.stream().anyMatch(p -> p.event().title().equals("Example Band Live") && "blue_cross_arena".equals(p.landmarkId())), "concert at the arena");
        check(now.stream().noneMatch(p -> p.event().title().equals("Far Away Show")), "out-of-region event dropped");
        check(now.stream().anyMatch(p -> p.event().title().equals("Independence Day") && p.event().regionWide()), "holiday is region-wide");
        var fireworks = eng.between(Instant.parse("2026-07-05T01:30:00Z"), Instant.parse("2026-07-05T01:31:00Z"));
        check(fireworks.stream().anyMatch(p -> p.event().title().contains("Williamson Fireworks") && "Williamson".equals(p.town())), "town fireworks anchored to Williamson");
        // Live game: next refresh 1 minute later re-fetches MLB; non-due sources don't
        int before = calls.get();
        eng.refresh(gameTime.plusSeconds(61));
        check(calls.get() == before + 1, "only the live MLB feed refreshed (calls +" + (calls.get() - before) + ")");
        // Offline restart: everything comes back from the cache without network
        var offline = new EventEngine(cfg, centre, 90_000, landmarks, towns, cache, url -> { throw new java.io.IOException("offline"); });
        check(offline.activeAt(gameTime).stream().anyMatch(p -> p.event().category() == LiveEvent.Category.CONCERT), "cached concert available offline");
        offline.refresh(gameTime.plusSeconds(3600));
        check(!offline.errors().isEmpty() && offline.activeAt(gameTime).stream().anyMatch(p -> p.event().category() == LiveEvent.Category.SPORTS), "network errors keep cached data");
    }
}
