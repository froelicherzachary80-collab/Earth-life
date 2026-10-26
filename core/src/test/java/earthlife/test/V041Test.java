package earthlife.test;

import earthlife.core.build.Blueprint;
import earthlife.core.build.CinemaLayout;

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
}
