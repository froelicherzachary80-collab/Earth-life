package earthlife.test;

import earthlife.core.cinema.FormatAllocator;
import earthlife.core.cinema.FormatAllocator.Format;
import earthlife.core.cinema.MovieScheduler;
import earthlife.core.cinema.MultiplexPlanner;
import earthlife.core.cinema.SiteGrid;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import static earthlife.test.T.*;

/** ZMF Cinema v0.1: format allocation, multiplex blueprints, film assignment and showtimes, site grid. */
public final class ZmfCinemaTest {

    public static void testAllocatorValidForEverySizeAndManySeeds() {
        for (int n = 12; n <= 32; n++) for (long seed = 0; seed < 60; seed++) {
            var p = FormatAllocator.allocate(n, seed * 7919 + n);
            var err = FormatAllocator.validate(p);
            check(err.isEmpty(), n + " rooms, seed " + seed + ": " + err);
            check(p.list().size() == n, "exact room count");
        }
    }

    public static void testTwelveScreensNeedOneCombinedRoom() {
        for (long seed = 0; seed < 50; seed++) {
            var p = FormatAllocator.allocate(12, seed);
            check(p.combos() >= 1, "12 rooms cannot hold 13 minimum capabilities without a combined room");
            check(p.count(Format.SCREENX) == 1, "one ScreenX");
            long combined = p.list().stream().filter(r -> r.formats().size() == 2).count();
            check(combined == p.combos(), "combo count matches rooms with two formats");
        }
    }

    public static void testThirtyTwoScreensUseEveryMaximum() {
        var p = FormatAllocator.allocate(32, 42);
        check(p.count(Format.F2D) == 5 && p.count(Format.F3D) == 10 && p.count(Format.F4DX) == 10 && p.count(Format.IMAX) == 6 && p.count(Format.SCREENX) == 1,
                "32 rooms = 5 2D, 10 3D, 10 4DX, 6 IMAX, 1 ScreenX: " + FormatAllocator.summary(p));
        check(p.combos() == 0, "no combined rooms at 32");
    }

    public static void testAllocationsVaryBetweenBuildings() {
        Set<String> seen = new HashSet<>();
        for (long seed = 0; seed < 40; seed++) {
            var p = FormatAllocator.allocate(20, seed);
            StringBuilder sb = new StringBuilder();
            for (var r : p.list()) sb.append(r.code()).append(r.browser()).append(',');
            seen.add(sb.toString());
        }
        check(seen.size() > 30, "20-screen arrangements differ between seeds: " + seen.size() + " distinct of 40");
    }

    public static void testFourBrowserRoomsInsideTheCount() {
        var p = FormatAllocator.allocate(16, 5);
        check(p.list().stream().filter(r -> r.browser() != '-').count() == 4, "four browser rooms");
        check(p.browser('A').get().has(Format.F2D) && p.browser('B').get().has(Format.F3D) && p.browser('C').get().has(Format.F4DX) && p.browser('D').get().has(Format.IMAX),
                "A=2D B=3D C=4DX D=IMAX");
        check(p.list().size() == 16, "browser rooms are part of the 16, not extra");
    }

    public static void testValidatorCatchesBadPlans() {
        var p = FormatAllocator.allocate(12, 1);
        List<FormatAllocator.Room> l = new ArrayList<>(p.list());
        l.set(0, new FormatAllocator.Room(l.get(0).index(), EnumSet.of(Format.SCREENX), '-'));      // second ScreenX
        var bad = new FormatAllocator.Plan(12, l, p.counts(), p.combos());
        check(!FormatAllocator.validate(bad).isEmpty(), "two ScreenX rooms rejected");
        l = new ArrayList<>(p.list());
        l.set(1, new FormatAllocator.Room(l.get(0).index(), l.get(1).formats(), l.get(1).browser()));  // duplicate id
        check(!FormatAllocator.validate(new FormatAllocator.Plan(12, l, p.counts(), p.combos())).isEmpty(), "duplicate room id rejected");
    }

    static int countBlocks(MultiplexPlanner.Plan p, String prefix) {
        int[] b = p.bp().bounds(); int n = 0;
        for (int u = b[0]; u <= b[2]; u++) for (int v = b[1]; v <= b[3]; v++) for (int y = 0; y <= p.maxHeight(); y++) {
            String id = p.bp().get(u, y, v);
            if (id != null && id.startsWith(prefix)) n++;
        }
        return n;
    }

    public static void testPlannerBuildsEveryRoom() {
        for (int n : new int[]{12, 15, 20, 26, 32}) for (long seed = 1; seed <= 6; seed++) {
            var p = MultiplexPlanner.plan(n, seed * 1000 + n);
            check(p.rooms().size() == n, n + " rooms planned, got " + p.rooms().size());
            Set<Integer> idx = new HashSet<>();
            for (var r : p.rooms()) {
                check(idx.add(r.index()), "unique room index " + r.index());
                check(r.seats().size() >= 12, r.name() + " has " + r.seats().size() + " seats");
                for (int[] s : r.seats()) {
                    String id = p.bp().get(s[0], s[1], s[2]);
                    check(id != null && id.startsWith("earthlife:") && id.contains("@v"), "seat block at " + Arrays.toString(s) + ": " + id);
                    String below = p.bp().get(s[0], s[1] - 1, s[2]);
                    check(below != null && !below.endsWith("air"), "seat stands on something: " + below);
                }
                // the door into the room is open from the hallway
                int dv = r.side() > 0 ? r.v0() : r.v1();
                int du = (int) Math.round(r.door()[0]);
                check("minecraft:air".equals(p.bp().get(du, r.floorY() + 1, dv)) && "minecraft:air".equals(p.bp().get(du, r.floorY() + 2, dv)),
                        r.name() + " door open at " + du + "," + dv);
                // the screen is inside the room, on the hallway wall side
                check(r.screenU() > r.u0() && r.screenU() < r.u1(), "screen inside the room along u");
                check(r.screenW() >= 6 && r.screenH() >= 2.5 && r.screenY() + r.screenH() / 2 <= r.floorY() + r.height() + 0.5, r.name() + " screen " + r.screenW() + "x" + r.screenH());
                // ScreenX side walls are projection surfaces
                if (r.formats().has(Format.SCREENX)) check(p.bp().get(r.u0(), r.floorY() + 4, (r.v0() + r.v1()) / 2).equals("minecraft:light_gray_concrete"), "ScreenX side wall");
            }
            // rooms never overlap (interiors are disjoint)
            for (var a : p.rooms()) for (var b : p.rooms()) if (a != b && a.floor() == b.floor()) {
                int[] x = a.box(), y = b.box();
                boolean overlap = x[0] <= y[3] && y[0] <= x[3] && x[2] <= y[5] && y[2] <= x[5];
                check(!overlap, a.name() + " overlaps " + b.name());
            }
            check(countBlocks(p, "earthlife:ticket_kiosk") >= 4, "kiosks in the lobby");
            check(countBlocks(p, "earthlife:popcorn_machine") >= 2, "popcorn machines");
            if (p.floors() == 2) check(countBlocks(p, "minecraft:polished_andesite_stairs") >= MultiplexPlanner.FLOOR_HEIGHT, "a staircase to the upper floor");
            check(p.marker("board") != null && p.marker("kiosk") != null && p.marker("facade") != null, "lobby markers");
        }
    }

    public static void testUpperFloorRoomsAreSupported() {
        for (long seed = 1; seed <= 10; seed++) {
            var p = MultiplexPlanner.plan(32, seed);
            for (var r : p.rooms()) if (r.floor() == 1) {
                // every column under the room's floor has something at ground level (a ground-floor room or a storage space)
                for (int u = r.u0(); u <= r.u1(); u += 3) for (int v = r.v0(); v <= r.v1(); v += 3)
                    check(p.bp().get(u, 0, v) != null, "upper room " + r.name() + " supported at " + u + "," + v);
            }
        }
    }

    public static void testBuildingsDiffer() {
        Set<String> looks = new HashSet<>();
        for (long seed = 0; seed < 20; seed++) {
            var p = MultiplexPlanner.plan(12, seed);
            looks.add(p.style() + "/" + p.lengthU() + "/" + p.halfWidthV() + "/" + p.rooms().get(0).u1() + "/" + p.name());
        }
        check(looks.size() >= 18, "12-screen multiplexes look different: " + looks.size() + " of 20");
        var a = MultiplexPlanner.plan(20, 77);
        var b = MultiplexPlanner.plan(20, 77);
        check(a.bp().blockCount() == b.bp().blockCount() && a.style() == b.style() && a.name().equals(b.name()), "same seed = same building");
    }

    static List<MovieScheduler.Movie> catalog(int n) {
        List<MovieScheduler.Movie> l = new ArrayList<>();
        String[] genres = {"Comedy", "Science Fiction", "Action", "Horror", "Fantasy", "Drama"};
        for (int i = 0; i < n; i++) l.add(new MovieScheduler.Movie("jellyfin:srv:" + i, "Film " + i, 1995 + i % 32, genres[i % genres.length], (90 + i % 60) * 60_000L, "2D"));
        return l;
    }

    public static void testAssignmentDistinctAndHonest() {
        var films = MovieScheduler.filter(catalog(60), 2000, 2026);
        check(films.stream().allMatch(m -> m.year() >= 2000 && m.year() <= 2026), "year filter");
        var a = MovieScheduler.assign(films, 32, 99, 20000);
        Set<String> keys = new HashSet<>();
        for (var m : a) if (m != null) check(keys.add(m.key()), "no film in two rooms: " + m.title());
        // fewer films than rooms: the rest are Coming Soon, nothing invented
        var few = MovieScheduler.assign(films.subList(0, 5), 12, 99, 20000);
        check(few.stream().filter(Objects::nonNull).count() == 5 && few.stream().filter(Objects::isNull).count() == 7, "5 films for 12 rooms -> 7 Coming Soon");
        // different multiplexes and different days give different programs
        check(!MovieScheduler.assign(films, 12, 1, 20000).equals(MovieScheduler.assign(films, 12, 2, 20000)), "programs differ between multiplexes");
        check(!MovieScheduler.assign(films, 12, 1, 20000).equals(MovieScheduler.assign(films, 12, 1, 20001)), "daily rotation");
        check(MovieScheduler.assign(films, 12, 1, 20000).equals(MovieScheduler.assign(films, 12, 1, 20000)), "deterministic");
        // duplicates (same title+year on two servers) collapse to one
        List<MovieScheduler.Movie> dup = new ArrayList<>(catalog(3));
        dup.add(new MovieScheduler.Movie("jellyfin:other:0", "Film 0", 1995, "Comedy", 0, "2D"));
        check(MovieScheduler.filter(dup, 1900, 2100).size() == 3, "duplicate titles removed");
    }

    public static void testShowtimes() {
        ZoneId z = ZoneId.of("America/New_York");
        var m = new MovieScheduler.Movie("file:x.mp4", "X", 2010, "Drama", 100 * 60_000L, "2D");
        var day = LocalDate.of(2026, 10, 26);
        var s = MovieScheduler.screenings(m, day, z, 5, 3);
        check(s.size() >= 5, "a 100-minute film plays at least 5 times a day, got " + s.size());
        for (int i = 1; i < s.size(); i++) check(s.get(i).startMs() >= s.get(i - 1).endMs(), "screenings don't overlap");
        long noon = day.atTime(12, 0).atZone(z).toInstant().toEpochMilli();
        var next = MovieScheduler.next(m, noon, z, 5, 3);
        check(next.isPresent() && next.get().startMs() > noon, "next screening after noon");
        check(MovieScheduler.current(m, s.get(1).filmStartMs() + 60_000, z, 5, 3).map(x -> x.startMs() == s.get(1).startMs()).orElse(false), "current screening found");
        check(MovieScheduler.screenings(null, day, z, 5, 3).isEmpty(), "Coming Soon has no screenings");
    }

    public static void testSiteGridDeterministicAndSpaced() {
        var g = new SiteGrid(123456789L, SiteGrid.Rarity.COMMON);
        check(g.site(3, -2).equals(new SiteGrid(123456789L, SiteGrid.Rarity.COMMON).site(3, -2)), "same seed = same site");
        check(!g.site(3, -2).equals(new SiteGrid(987L, SiteGrid.Rarity.COMMON).site(3, -2)), "other seed = other site");
        // average nearest-neighbour distance in the COMMON preset is within the requested 800-1,500 blocks
        double sum = 0; int n = 0;
        for (int i = -6; i <= 6; i++) for (int j = -6; j <= 6; j++) {
            var s = g.site(i, j); double best = Double.MAX_VALUE;
            for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) if (a != 0 || b != 0) best = Math.min(best, s.distanceTo(g.site(i + a, j + b).x(), g.site(i + a, j + b).z()));
            sum += best; n++;
        }
        double avg = sum / n;
        check(avg >= 800 && avg <= 1500, "COMMON average nearest-neighbour distance " + Math.round(avg) + " blocks");
        var near = g.near(0, 0, 2);
        check(near.size() == 25 && near.get(0).distanceTo(0, 0) <= near.get(1).distanceTo(0, 0), "nearest first");
        for (var s : near) check(s.rooms() >= 12 && s.rooms() <= 32, "planned size 12-32");
    }
}
