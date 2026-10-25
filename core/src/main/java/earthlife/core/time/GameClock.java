package earthlife.core.time;

import java.time.*;

/**
 * EarthLife time system. Decides the Minecraft day-time (ticks; 24000 per day, tick 0 = 06:00) every tick:
 *
 *  - VANILLA:  Minecraft's normal cycle (1 day = 20 real minutes). The clock doesn't interfere.
 *  - REALTIME: the in-game clock shows the real local time in the configured zone; 1 game day = 1 real day.
 *  - SCALED:   1 game day lasts {@code minutesPerDay} real minutes (1 .. 1440). 1440 = real-length days
 *              without being pinned to the wall clock; smaller = faster.
 *
 * Guarantees: the day counter never runs backwards (switching modes or clock jumps only move forward to the
 * next matching time), and wall-clock rollbacks are ignored, so schedules (flights, shifts) keyed on the
 * day number can't be replayed.
 */
public final class GameClock {
    public enum Mode { VANILLA, REALTIME, SCALED }

    public static final long TICKS_PER_DAY = 24_000;
    /**
     * If something pushed the world clock ahead of real time (a /time command, sleeping), REALTIME holds the clock
     * still until real time catches up when the gap is under this many ticks (6 h); bigger gaps continue on the next day.
     */
    private static final long HOLD_WINDOW = 6_000;

    private Mode mode;
    private double minutesPerDay;
    private ZoneId zone;
    private long lastWallMs = -1;
    private double fractional;
    private long lastRealDay = Long.MIN_VALUE;

    public GameClock(Mode mode, double minutesPerDay, ZoneId zone) { configure(mode, minutesPerDay, zone); }

    public void configure(Mode mode, double minutesPerDay, ZoneId zone) {
        if (minutesPerDay < 1 || minutesPerDay > 1440) throw new IllegalArgumentException("minutesPerDay must be 1..1440");
        this.mode = mode; this.minutesPerDay = minutesPerDay; this.zone = zone;
        this.fractional = 0;
    }

    public Mode mode() { return mode; }
    /** Persist this with the world so days passed while the server was off are counted after a restart. */
    public long lastRealDay() { return lastRealDay; }
    public void restoreLastRealDay(long d) { lastRealDay = d; }
    public double minutesPerDay() { return mode == Mode.REALTIME ? 1440 : mode == Mode.VANILLA ? 20 : minutesPerDay; }
    public ZoneId zone() { return zone; }

    /** True when Minecraft's own daylight cycle must be switched off because this clock drives time. */
    public boolean drivesTime() { return mode != Mode.VANILLA; }

    /** Converts a wall-clock local time to Minecraft time-of-day ticks (06:00 -> 0, 12:00 -> 6000, 00:00 -> 18000). */
    public static long ticksOfDay(LocalTime t) {
        long secs = t.toSecondOfDay();
        return Math.floorMod(Math.round(secs * TICKS_PER_DAY / 86_400.0) - 6_000, TICKS_PER_DAY);
    }

    public static LocalTime timeOf(long dayTime) {
        long tod = Math.floorMod(dayTime + 6_000, TICKS_PER_DAY);
        return LocalTime.ofSecondOfDay(Math.min(86_399, Math.round(tod * 86_400.0 / TICKS_PER_DAY)));
    }

    /**
     * @param wallMs     current wall-clock time (server)
     * @param currentDay current world day-time ticks
     * @return the day-time the world should have now (== currentDay for VANILLA, which lets Minecraft run its own cycle)
     */
    public long next(long wallMs, long currentDay) {
        if (lastWallMs >= 0 && wallMs < lastWallMs) wallMs = lastWallMs;        // ignore wall-clock rollback
        long elapsed = lastWallMs < 0 ? 0 : wallMs - lastWallMs;
        lastWallMs = wallMs;
        switch (mode) {
            case VANILLA -> { return currentDay; }
            case REALTIME -> {
                ZonedDateTime now = Instant.ofEpochMilli(wallMs).atZone(zone);
                long tod = ticksOfDay(now.toLocalTime());
                // Real "game days" start at 06:00 like Minecraft's. If real days passed (e.g. server was off overnight),
                // the game day number advances by at least as many.
                long realDay = now.minusHours(6).toLocalDate().toEpochDay();
                long minDay = day(currentDay) + (lastRealDay == Long.MIN_VALUE ? 0 : Math.max(0, realDay - lastRealDay));
                lastRealDay = realDay;
                long candidate = minDay * TICKS_PER_DAY + tod;
                if (candidate >= currentDay) return candidate;           // normal case: real time moved forward
                if (currentDay - candidate <= HOLD_WINDOW) return currentDay; // world ahead of real clock: wait
                return candidate + TICKS_PER_DAY;                        // otherwise sync on the next day
            }
            case SCALED -> {
                fractional += elapsed * TICKS_PER_DAY / (minutesPerDay * 60_000.0);
                long whole = (long) fractional;
                fractional -= whole;
                return currentDay + whole;
            }
        }
        return currentDay;
    }

    /** Real seconds for a given number of game ticks under this clock (e.g. to show "shift ends in 3h real"). */
    public double realSecondsFor(long ticks) { return ticks * minutesPerDay() * 60.0 / TICKS_PER_DAY; }

    public static long day(long dayTime) { return Math.floorDiv(dayTime, TICKS_PER_DAY); }
    public static int hour(long dayTime) { return timeOf(dayTime).getHour(); }
}
