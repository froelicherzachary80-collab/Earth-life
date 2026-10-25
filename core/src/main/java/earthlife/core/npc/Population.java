package earthlife.core.npc;

import earthlife.core.jobs.JobType;
import earthlife.core.world.Features;

import java.util.*;

/**
 * Generates and tracks the residents of a region. Homes are real (or estimated) houses; workplaces are commercial
 * buildings, farms and airports; leisure spots are parks and shops. Only residents near a player are spawned as
 * entities ({@link #nearby}); everyone else is simulated by schedule alone, so a region can hold thousands of people.
 */
public final class Population {
    static final String[] FIRST = {"Ava", "Liam", "Mia", "Noah", "Emma", "Owen", "Ella", "Lucas", "Grace", "Henry", "Chloe", "Jack", "Lily", "Leo",
            "Nora", "Caleb", "Ruby", "Eli", "Hazel", "Isaac", "Maya", "Wyatt", "Zoe", "Dylan", "Aria", "Ezra", "Clara", "Miles", "Ivy", "Silas",
            "June", "Theo", "Rosa", "Felix", "Jade", "Hugo", "Iris", "Omar", "Priya", "Mateo", "Sofia", "Ken", "Amara", "Diego", "Lena"};
    static final String[] LAST = {"Miller", "Smith", "Brown", "Johnson", "Davis", "Garcia", "Wilson", "Martin", "Clark", "Lewis", "Walker", "Hall",
            "Young", "King", "Wright", "Hill", "Green", "Adams", "Baker", "Nelson", "Carter", "Mitchell", "Rivera", "Patel", "Nguyen", "Kim",
            "Reyes", "Brooks", "Foster", "Hayes", "Shaw", "Quinn", "Ortiz", "Byrne", "Fischer", "Rossi", "Kowalski", "Olsen"};

    public record Workplace(Npc.Place place, JobType job) {}

    private final List<Npc> people = new ArrayList<>();

    /**
     * @param houses     candidate homes (mapped or estimated house footprints)
     * @param workplaces jobs on offer (each slot employs one resident)
     * @param leisure    parks, shops, the lake shore...
     */
    public Population(long seed, String town, List<Features.Building> houses, List<Workplace> workplaces, List<Npc.Place> leisure, int residentsPerHouse) {
        Random r = new Random(seed ^ town.hashCode());
        List<Workplace> openJobs = new ArrayList<>(workplaces);
        Collections.shuffle(openJobs, r);
        int i = 0;
        for (Features.Building h : houses) {
            if (!(h.kind() == null || h.kind().equals("house") || h.kind().equals("detached") || h.kind().equals("residential") || h.kind().equals("yes"))) continue;
            double[] c = centroid(h.footprint());
            Npc.Place home = new Npc.Place(h.id(), "home", c[0], c[1]);
            String last = LAST[r.nextInt(LAST.length)];
            for (int k = 0; k < residentsPerHouse; k++) {
                Workplace w = openJobs.isEmpty() ? null : openJobs.remove(openJobs.size() - 1);
                JobType job = w == null ? JobType.FARMER : w.job();
                int li = r.nextInt(Math.max(1, leisure.size()));      // always draw, so the same people result regardless of leisure data
                Npc.Place leisureSpot = leisure.isEmpty() ? null : leisure.get(li);
                int wake = 5 + r.nextInt(3), sleep = 21 + r.nextInt(3);
                people.add(new Npc(town.toLowerCase(Locale.ROOT).replace(' ', '_') + "-" + (i++), FIRST[r.nextInt(FIRST.length)], last,
                        r.nextLong(), town, home, w == null ? null : w.place(), job, leisureSpot, wake, sleep,
                        Npc.Personality.values()[r.nextInt(Npc.Personality.values().length)]));
            }
        }
    }

    public List<Npc> all() { return Collections.unmodifiableList(people); }

    public Optional<Npc> byId(String id) { return people.stream().filter(n -> n.id().equals(id)).findFirst(); }

    /** Residents whose scheduled location at {@code dayTime} is within {@code radius} of the point (who to spawn). */
    public List<Npc> nearby(double x, double z, double radius, long dayTime, int max) {
        List<Npc> out = new ArrayList<>();
        for (Npc n : people) {
            var a = n.activityAt(dayTime);
            if (a == Npc.Activity.SLEEPING) continue;                       // asleep indoors: not spawned
            Npc.Place p = n.placeFor(a);
            if (Math.hypot(p.x() - x, p.z() - z) <= radius) out.add(n);
        }
        out.sort(Comparator.comparingDouble(n -> { var p = n.placeFor(n.activityAt(dayTime)); return Math.hypot(p.x() - x, p.z() - z); }));
        return out.size() > max ? out.subList(0, max) : out;
    }

    static double[] centroid(List<Features.Pt> ring) {
        double x = 0, z = 0;
        for (var p : ring) { x += p.x(); z += p.z(); }
        return new double[]{x / ring.size(), z / ring.size()};
    }
}
