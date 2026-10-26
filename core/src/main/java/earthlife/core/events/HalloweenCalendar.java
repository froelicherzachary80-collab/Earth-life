package earthlife.core.events;

import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.ChronoUnit;

/** Halloween week rules (real calendar): Oct 24 - Nov 1. Pure functions so they can be unit-tested. */
public final class HalloweenCalendar {
    private HalloweenCalendar() {}

    public static boolean isSeason(LocalDate d) {
        return (d.getMonth() == Month.OCTOBER && d.getDayOfMonth() >= 24) || (d.getMonth() == Month.NOVEMBER && d.getDayOfMonth() == 1);
    }

    /** Trick-or-treat hours on the game clock: 17:00-22:59. */
    public static boolean isEveningHour(int hour) { return hour >= 17 && hour <= 22; }

    public static String countdown(LocalDate d) {
        long days = ChronoUnit.DAYS.between(d, LocalDate.of(d.getYear(), 10, 31));
        if (days == 0) return "🎃 Halloween today! Trick-or-treat 17:00-23:00";
        if (days > 0 && days <= 7) return "🎃 Halloween in " + days + " day" + (days == 1 ? "" : "s") + " - trick-or-treat and contests are on";
        if (days == -1) return "🎃 Halloween week wraps up today";
        return "";
    }

    /** Prize for a costume-contest placing (1-based). */
    public static long costumePrize(int place) { return switch (place) { case 1 -> 200; case 2 -> 100; case 3 -> 50; default -> 10; }; }

    /** Carving contest: 15 LC per lantern, counted up to 10. */
    public static long carvingPrize(int lanterns) { return Math.min(10, Math.max(0, lanterns)) * 15L; }
}
