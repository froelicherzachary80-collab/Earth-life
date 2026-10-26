package earthlife.core.cinema;

import java.time.*;
import java.util.*;

/**
 * Assigns films to the auditoriums of a multiplex and works out their showtimes (ZMF Cinema).
 *
 * Films only ever come from a catalog of titles the players actually have: their own Jellyfin libraries or other
 * sources they configured. Nothing is invented: when there are fewer films than rooms, the remaining rooms show
 * "Coming Soon". Within one multiplex no film plays in two rooms; the program rotates every day (deterministically, so
 * every player and every reload agree on it).
 *
 * Showtimes run on the real clock of the server's time zone: the first show of a room starts between 10:00 and 11:00,
 * then one show follows another (trailers + film + cleaning) until about 23:30.
 */
public final class MovieScheduler {
    private MovieScheduler() {}

    /** A playable title. source is a shareable reference ("jellyfin:&lt;server&gt;:&lt;item&gt;", "file:...", ...), never a token. */
    public record Movie(String source, String title, int year, String genre, long runtimeMs, String format3d) {
        public String key() { return (title + "|" + year).toLowerCase(Locale.ROOT); }
        public long runtimeOrDefault() { return runtimeMs > 10 * 60_000 ? runtimeMs : 120 * 60_000L; }
    }

    public static final int DEFAULT_MIN_YEAR = 2000;
    public static final long TRAILERS_MS = 10 * 60_000L, CLEANING_MS = 15 * 60_000L;

    /** Films whose year is in [minYear, maxYear], one per title+year (the first one listed wins). */
    public static List<Movie> filter(List<Movie> catalog, int minYear, int maxYear) {
        Map<String, Movie> seen = new LinkedHashMap<>();
        for (Movie m : catalog) {
            if (m == null || m.source() == null || m.source().isBlank() || m.title() == null || m.title().isBlank()) continue;
            if (m.year() < minYear || m.year() > maxYear) continue;
            seen.putIfAbsent(m.key(), m);
        }
        return new ArrayList<>(seen.values());
    }

    /**
     * Distinct films for rooms 0..rooms-1 on a given day (null = Coming Soon). The order depends on the multiplex seed and
     * the day, so programs differ between multiplexes and rotate daily. A different multiplex may show the same film.
     */
    public static List<Movie> assign(List<Movie> films, int rooms, long multiplexSeed, long day) {
        List<Movie> pool = new ArrayList<>(films);
        pool.sort(Comparator.comparing(Movie::key));                       // independent of catalog order
        Collections.shuffle(pool, new Random(multiplexSeed * 31 + day * 0x9E3779B9L));
        List<Movie> out = new ArrayList<>(rooms);
        for (int i = 0; i < rooms; i++) out.add(i < pool.size() ? pool.get(i) : null);
        return out;
    }

    /** One screening: trailers start at startMs, the film itself at filmStartMs, it ends at endMs (epoch ms). */
    public record Screening(long startMs, long filmStartMs, long endMs) {
        public boolean contains(long t) { return t >= startMs && t < endMs; }
    }

    /** All screenings of a room on a local date. */
    public static List<Screening> screenings(Movie m, LocalDate date, ZoneId zone, long multiplexSeed, int room) {
        List<Screening> out = new ArrayList<>();
        if (m == null) return out;
        Random r = new Random(multiplexSeed ^ (date.toEpochDay() * 1_000_003L) ^ (room * 7919L));
        long first = date.atTime(10, 0).atZone(zone).toInstant().toEpochMilli() + (r.nextInt(12) * 5) * 60_000L;
        long last = date.atTime(23, 30).atZone(zone).toInstant().toEpochMilli();
        long len = TRAILERS_MS + m.runtimeOrDefault();
        for (long t = first; t <= last; t += len + CLEANING_MS) out.add(new Screening(t, t + TRAILERS_MS, t + len));
        return out;
    }

    /** The screening running at time t (trailers or film), if any. */
    public static Optional<Screening> current(Movie m, long t, ZoneId zone, long seed, int room) {
        LocalDate d = Instant.ofEpochMilli(t).atZone(zone).toLocalDate();
        for (LocalDate day : new LocalDate[]{d.minusDays(1), d})
            for (Screening s : screenings(m, day, zone, seed, room)) if (s.contains(t)) return Optional.of(s);
        return Optional.empty();
    }

    /** The next screening starting after time t (looks up to two days ahead). */
    public static Optional<Screening> next(Movie m, long t, ZoneId zone, long seed, int room) {
        LocalDate d = Instant.ofEpochMilli(t).atZone(zone).toLocalDate();
        for (int i = 0; i < 3; i++)
            for (Screening s : screenings(m, d.plusDays(i), zone, seed, room)) if (s.startMs() > t) return Optional.of(s);
        return Optional.empty();
    }

    /** Local day number used for the daily program rotation. */
    public static long day(long t, ZoneId zone) { return Instant.ofEpochMilli(t).atZone(zone).toLocalDate().toEpochDay(); }
}
