package earthlife.core.events;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

import static earthlife.core.events.LiveEvent.Activity.*;

/**
 * US federal holidays and widely observed days, computed exactly for any year (no network needed), in the region's
 * time zone. Region-wide: decorations, plus fireworks at typical times on the Fourth of July and New Year's Eve.
 * Fireworks times are typical local customs, not a specific town's announced show (use an iCal feed for those).
 */
public final class Holidays {
    private Holidays() {}

    public static List<LiveEvent> forYear(int y, ZoneId zone) {
        List<LiveEvent> out = new ArrayList<>();
        add(out, y, zone, "New Year's Day", LocalDate.of(y, 1, 1), 0, 24, Set.of(ANNOUNCE, DECORATIONS_WINTER));
        add(out, y, zone, "Martin Luther King Jr. Day", nth(y, 1, DayOfWeek.MONDAY, 3), 0, 24, Set.of(ANNOUNCE));
        add(out, y, zone, "Valentine's Day", LocalDate.of(y, 2, 14), 0, 24, Set.of(ANNOUNCE));
        add(out, y, zone, "Presidents' Day", nth(y, 2, DayOfWeek.MONDAY, 3), 0, 24, Set.of(ANNOUNCE));
        add(out, y, zone, "St. Patrick's Day", LocalDate.of(y, 3, 17), 0, 24, Set.of(ANNOUNCE));
        add(out, y, zone, "Easter Sunday", easter(y), 0, 24, Set.of(ANNOUNCE, DECORATIONS_SPRING));
        add(out, y, zone, "Mother's Day", nth(y, 5, DayOfWeek.SUNDAY, 2), 0, 24, Set.of(ANNOUNCE));
        add(out, y, zone, "Memorial Day", LocalDate.of(y, 5, 31).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), 0, 24, Set.of(ANNOUNCE, DECORATIONS_PATRIOTIC));
        add(out, y, zone, "Father's Day", nth(y, 6, DayOfWeek.SUNDAY, 3), 0, 24, Set.of(ANNOUNCE));
        add(out, y, zone, "Juneteenth", LocalDate.of(y, 6, 19), 0, 24, Set.of(ANNOUNCE));
        add(out, y, zone, "Independence Day", LocalDate.of(y, 7, 4), 0, 24, Set.of(ANNOUNCE, DECORATIONS_PATRIOTIC));
        add(out, y, zone, "Fourth of July fireworks", LocalDate.of(y, 7, 4), 21.5, 22.25, Set.of(FIREWORKS, CROWD));
        add(out, y, zone, "Labor Day", nth(y, 9, DayOfWeek.MONDAY, 1), 0, 24, Set.of(ANNOUNCE));
        add(out, y, zone, "Columbus Day / Indigenous Peoples' Day", nth(y, 10, DayOfWeek.MONDAY, 2), 0, 24, Set.of(ANNOUNCE));
        // Halloween season: decorations from Oct 15, trick-or-treating on the 31st evening
        out.add(ev(y, zone, "Halloween season", LocalDate.of(y, 10, 15).atStartOfDay(), LocalDate.of(y, 11, 1).atTime(12, 0), Set.of(DECORATIONS_HALLOWEEN)));
        add(out, y, zone, "Halloween trick-or-treat", LocalDate.of(y, 10, 31), 17.5, 20.5, Set.of(ANNOUNCE, TRICK_OR_TREAT, DECORATIONS_HALLOWEEN));
        add(out, y, zone, "Veterans Day", LocalDate.of(y, 11, 11), 0, 24, Set.of(ANNOUNCE, DECORATIONS_PATRIOTIC));
        add(out, y, zone, "Thanksgiving", nth(y, 11, DayOfWeek.THURSDAY, 4), 0, 24, Set.of(ANNOUNCE));
        // Winter lights from the day after Thanksgiving to Jan 6 (next year)
        out.add(ev(y, zone, "Holiday lights season", nth(y, 11, DayOfWeek.THURSDAY, 4).plusDays(1).atStartOfDay(), LocalDate.of(y + 1, 1, 6).atTime(23, 59), Set.of(DECORATIONS_WINTER)));
        add(out, y, zone, "Christmas Eve", LocalDate.of(y, 12, 24), 0, 24, Set.of(ANNOUNCE, DECORATIONS_WINTER));
        add(out, y, zone, "Christmas Day", LocalDate.of(y, 12, 25), 0, 24, Set.of(ANNOUNCE, DECORATIONS_WINTER));
        out.add(ev(y, zone, "New Year's Eve countdown", LocalDate.of(y, 12, 31).atTime(23, 45), LocalDate.of(y + 1, 1, 1).atTime(0, 20), Set.of(ANNOUNCE, FIREWORKS, CROWD)));
        return out;
    }

    private static void add(List<LiveEvent> out, int y, ZoneId z, String name, LocalDate d, double fromH, double toH, Set<LiveEvent.Activity> acts) {
        LocalDateTime s = d.atStartOfDay().plusMinutes(Math.round(fromH * 60)), e = d.atStartOfDay().plusMinutes(Math.round(toH * 60));
        out.add(ev(y, z, name, s, e, acts));
    }

    private static LiveEvent ev(int y, ZoneId z, String name, LocalDateTime s, LocalDateTime e, Set<LiveEvent.Activity> acts) {
        String id = "holiday:" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", "-") + ":" + y;
        return new LiveEvent(id, name, LiveEvent.Category.HOLIDAY, s.atZone(z).toInstant(), e.atZone(z).toInstant(), null,
                Double.NaN, Double.NaN, "holidays", null, acts, null);
    }

    static LocalDate nth(int y, int month, DayOfWeek dow, int n) {
        return LocalDate.of(y, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, dow));
    }

    /** Anonymous Gregorian algorithm (Meeus/Jones/Butcher). */
    static LocalDate easter(int y) {
        int a = y % 19, b = y / 100, c = y % 100, d = b / 4, e = b % 4, f = (b + 8) / 25, g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30, i = c / 4, k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7, m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31, day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(y, month, day);
    }
}
