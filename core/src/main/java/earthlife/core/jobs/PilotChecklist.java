package earthlife.core.jobs;

import earthlife.core.aviation.FlightSim;

import java.util.*;

/**
 * Pilot job gameplay: the captain flies a scheduled flight from the cockpit by working the checklist at the right
 * moments (the autopilot handles stick-and-rudder in V0.2). Each item is only accepted in its window; doing it
 * too early or too late doesn't count. Completed items become "checklist" tasks for wages; finishing the flight
 * with every item done is the "flight_complete" task.
 */
public final class PilotChecklist {
    public enum Item {
        PREFLIGHT("Preflight check", "before pushback"),
        BEACON_ON("Beacon on", "before taxi"),
        TAKEOFF_CLEARANCE("Request takeoff clearance", "on the runway, before the takeoff roll ends"),
        LANDING_LIGHTS_OFF("Landing lights off", "climbing above 3,000 ft or in cruise"),
        DESCENT_BRIEFING("Descent briefing", "during cruise or early descent"),
        LANDING_LIGHTS_ON("Landing lights on", "descending below 3,000 ft"),
        PARKING_BRAKE("Parking brake set", "at the gate");

        public final String label, when;
        Item(String l, String w) { label = l; when = w; }
    }

    private final EnumSet<Item> done = EnumSet.noneOf(Item.class);

    public boolean accepted(Item i, FlightSim.Telemetry t) {
        double aglFt = t.altitudeAgl() * 3.28084;
        return switch (i) {
            case PREFLIGHT -> t.phase() == FlightSim.Phase.BOARDING;
            case BEACON_ON -> t.phase() == FlightSim.Phase.BOARDING || t.phase() == FlightSim.Phase.TAXI_OUT;
            case TAKEOFF_CLEARANCE -> t.phase() == FlightSim.Phase.TAXI_OUT || t.phase() == FlightSim.Phase.TAKEOFF_ROLL;
            case LANDING_LIGHTS_OFF -> (t.phase() == FlightSim.Phase.CLIMB && aglFt > 3000) || t.phase() == FlightSim.Phase.CRUISE;
            case DESCENT_BRIEFING -> t.phase() == FlightSim.Phase.CRUISE || (t.phase() == FlightSim.Phase.DESCENT && aglFt > 1500);
            case LANDING_LIGHTS_ON -> t.phase() == FlightSim.Phase.DESCENT && aglFt < 3000;
            case PARKING_BRAKE -> t.phase() == FlightSim.Phase.ARRIVED;
        };
    }

    /** @return true if this action completed a checklist item now. */
    public boolean act(Item i, FlightSim.Telemetry t) {
        if (done.contains(i) || !accepted(i, t)) return false;
        // Items must be done in order: everything before this one must already be complete.
        for (Item prev : Item.values()) { if (prev == i) break; if (!done.contains(prev) && required(prev, t)) return false; }
        done.add(i);
        return true;
    }

    /** Short flights may never climb above 3,000 ft AGL; those items are optional there. */
    private boolean required(Item i, FlightSim.Telemetry t) {
        return i != Item.LANDING_LIGHTS_OFF && i != Item.DESCENT_BRIEFING;
    }

    public Set<Item> done() { return Collections.unmodifiableSet(done); }
    public boolean complete() { return done.containsAll(EnumSet.of(Item.PREFLIGHT, Item.BEACON_ON, Item.TAKEOFF_CLEARANCE, Item.LANDING_LIGHTS_ON, Item.PARKING_BRAKE)); }
    public Optional<Item> next() { return Arrays.stream(Item.values()).filter(i -> !done.contains(i)).findFirst(); }
}
