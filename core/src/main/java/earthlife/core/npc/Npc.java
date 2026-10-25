package earthlife.core.npc;

import earthlife.core.jobs.JobType;
import earthlife.core.time.GameClock;

import java.util.*;

/**
 * A persistent resident. Identity is deterministic from (region seed, index) so the same people live in the same
 * houses every time the world loads; only relationships and memory are saved.
 */
public record Npc(String id, String firstName, String lastName, long skinSeed, String town,
                  Place home, Place work, JobType job, Place leisure, int wakeHour, int sleepHour, Personality personality) {

    public record Place(String id, String label, double x, double z) {}
    public enum Personality { FRIENDLY, SHY, CHATTY, GRUMPY, CHEERFUL }
    public enum Activity { SLEEPING, MORNING_AT_HOME, COMMUTING_TO_WORK, WORKING, LUNCH, COMMUTING_HOME, LEISURE, EVENING_AT_HOME }

    public String name() { return firstName + " " + lastName; }

    /** What this person is doing at a given world time (weekends off for most jobs). */
    public Activity activityAt(long dayTime) {
        int h = GameClock.hour(dayTime);
        long day = GameClock.day(dayTime);
        boolean weekend = Math.floorMod(day, 7) >= 5 && job != JobType.JANITOR && job != JobType.CHECKIN_AGENT && job != JobType.GATE_AGENT && job != JobType.PILOT;
        int ws = job.hasShifts() ? job.shiftStart : 8, we = job.hasShifts() ? job.shiftEnd : 16;
        boolean asleep = sleepHour > wakeHour ? (h >= sleepHour || h < wakeHour) : (h >= sleepHour && h < wakeHour);
        if (asleep) return Activity.SLEEPING;
        if (!weekend && work != null) {
            if (h == ws - 1) return Activity.COMMUTING_TO_WORK;
            if (h >= ws && h < we) return (h == ws + (we - ws) / 2) ? Activity.LUNCH : Activity.WORKING;
            if (h == we) return Activity.COMMUTING_HOME;
        }
        if (h >= 17 && h < 21) return Activity.LEISURE;
        return h < 12 ? Activity.MORNING_AT_HOME : Activity.EVENING_AT_HOME;
    }

    /** Where they should be for an activity. */
    public Place placeFor(Activity a) {
        return switch (a) {
            case WORKING, LUNCH, COMMUTING_TO_WORK -> work != null ? work : home;
            case LEISURE -> leisure != null ? leisure : home;
            default -> home;
        };
    }
}
