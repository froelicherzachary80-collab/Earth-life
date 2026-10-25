package earthlife.core.events;

import java.time.Instant;
import java.util.Set;

/**
 * A real-world happening mirrored in game at its real place and real time.
 * {@code lat/lon} are NaN for region-wide events (holidays). {@code venue} is the venue name reported by the source;
 * the engine resolves it to a landmark/town in the world.
 */
public record LiveEvent(String id, String title, Category category, Instant start, Instant end, String venue,
                        double lat, double lon, String source, String url, Set<Activity> activities, Score score) {

    public enum Category { HOLIDAY, SPORTS, CONCERT, COMMUNITY, OTHER }

    /** What the game does for the event. */
    public enum Activity { CROWD, SCOREBOARD, STAGE_MUSIC, FIREWORKS, DECORATIONS_HALLOWEEN, DECORATIONS_WINTER, DECORATIONS_PATRIOTIC,
        DECORATIONS_SPRING, TRICK_OR_TREAT, ANNOUNCE }

    /** Live score for games (null when not a game or not started). */
    public record Score(String home, String away, int homeRuns, int awayRuns, String status, String detail) {}

    public boolean regionWide() { return Double.isNaN(lat); }
    public boolean activeAt(Instant t) { return !t.isBefore(start) && t.isBefore(end); }

    public LiveEvent withScore(Score s) { return new LiveEvent(id, title, category, start, end, venue, lat, lon, source, url, activities, s); }
}
