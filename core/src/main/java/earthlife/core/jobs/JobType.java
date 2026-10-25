package earthlife.core.jobs;

import java.util.Set;

/**
 * Careers available in V0.2. Each job is real gameplay: shifts happen at fixed in-game hours at a workplace,
 * pay is per game-hour worked plus per completed task, and tasks are things the player physically does in the world.
 * Hours are Minecraft clock hours, so with the real-time clock a 9-5 shift lasts a real 8 hours.
 */
public enum JobType {
    STORE_CLERK("Store clerk", "store", 8, 16, 30, 6, Set.of("restock", "checkout"), null),
    JANITOR("Janitor", "any", 18, 23, 35, 8, Set.of("clean"), null),
    FARMER("Farmer", "farm", -1, -1, 0, 0, Set.of(), null),                    // self-employed: paid by selling crops
    CONSTRUCTION_WORKER("Construction worker", "site", 7, 15, 40, 1, Set.of("build"), null),
    DELIVERY_DRIVER("Delivery driver", "depot", 9, 17, 30, 25, Set.of("deliver"), "license:driver"),
    BUS_DRIVER("Bus driver", "depot", 6, 14, 45, 4, Set.of("bus_stop"), "license:driver"),
    CHECKIN_AGENT("Airport check-in agent", "airport", 5, 13, 40, 10, Set.of("checkin"), null),
    GATE_AGENT("Airport gate agent", "airport", 7, 15, 40, 6, Set.of("board", "gate_announce"), null),
    PILOT("Pilot", "airport", 6, 18, 90, 40, Set.of("checklist", "flight_complete"), "license:pilot");

    public final String title, workplace, requirement;
    public final int shiftStart, shiftEnd;   // game hours (0-23); -1 = no shift
    public final long perHour, perTask;
    public final Set<String> taskKinds;

    JobType(String title, String workplace, int start, int end, long perHour, long perTask, Set<String> kinds, String req) {
        this.title = title; this.workplace = workplace; this.shiftStart = start; this.shiftEnd = end;
        this.perHour = perHour; this.perTask = perTask; this.taskKinds = kinds; this.requirement = req;
    }

    public boolean hasShifts() { return shiftStart >= 0; }
    public int shiftHours() { return hasShifts() ? shiftEnd - shiftStart : 0; }
}
