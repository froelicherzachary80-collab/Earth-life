package earthlife.core.aviation;

import earthlife.core.geo.GeoPoint;

import java.util.*;

/**
 * Deterministic kinematic flight model for one aircraft. The aircraft moves continuously along a
 * geodetic path (gate -> runway -> great circle -> approach fix -> runway -> gate); its speed and
 * altitude are integrated from acceleration and vertical-rate limits each step. There is no
 * teleporting and no scripted animation: the in-flight map and the entity position both read
 * {@link #telemetry()}, which comes from this integrated state.
 *
 * Time compression multiplies simulated seconds per real second; integration sub-steps keep it stable.
 */
public final class FlightSim {
    public enum Phase { BOARDING, TAXI_OUT, TAKEOFF_ROLL, CLIMB, CRUISE, DESCENT, LANDING_ROLL, TAXI_IN, ARRIVED }

    public record Telemetry(Phase phase, GeoPoint position, double altitudeMsl, double altitudeAgl, double groundSpeed,
                            double verticalSpeed, double headingDeg, double pitchDeg, double distanceFlown,
                            double distanceRemaining, double etaSeconds, double elapsedSeconds,
                            double gearExtension, boolean doorsOpen, String departure, String destination) {}

    private final AircraftType type;
    private final Airport from, to;
    private final List<GeoPoint> path = new ArrayList<>();
    private final double[] cum;                 // cumulative distance at each path vertex
    private final double sTakeoffStart, sTakeoffEnd, sThreshold, sRolloutEnd, total;
    private final double cruiseAlt;             // MSL, possibly capped for short routes

    private Phase phase = Phase.BOARDING;
    private double s, v, alt, vs, elapsed, gear = 1.0;
    private final List<String> announcements = new ArrayList<>();
    private double timeCompression = 1.0;

    public FlightSim(AircraftType type, Airport from, Airport to) { this(type, from, to, List.of(), List.of()); }

    /**
     * @param taxiOut waypoints from the departure stand to the runway (excluding stand and threshold), e.g. along the
     *                generated apron/taxiway; aircraft follow them instead of cutting across grass.
     * @param taxiIn  waypoints from the arrival runway exit to the arrival stand.
     */
    public FlightSim(AircraftType type, Airport from, Airport to, List<GeoPoint> taxiOut, List<GeoPoint> taxiIn) {
        this.type = type; this.from = from; this.to = to;
        double course = from.ref().bearingTo(to.ref());
        Airport.Runway dep = from.bestRunwayFor(course);
        Airport.Runway arr = to.bestRunwayFor(course);
        double fixDist = Math.min(12_000, Math.max(6_000, from.ref().distanceTo(to.ref()) * 0.25));
        GeoPoint fix = arr.approachFix(fixDist);
        path.add(from.gate());
        path.addAll(taxiOut);
        path.add(dep.threshold());
        int takeoffIdx = path.size() - 1;
        path.add(dep.end());
        int takeoffEndIdx = path.size() - 1;
        // Intermediate great-circle points every ~50 km so long routes follow the sphere, not a straight chord.
        double cruiseLeg = dep.end().distanceTo(fix);
        int pieces = Math.max(1, (int) Math.ceil(cruiseLeg / 50_000));
        for (int i = 1; i < pieces; i++) path.add(dep.end().interpolate(fix, i / (double) pieces));
        path.add(fix);
        path.add(arr.threshold());
        int thrIdx = path.size() - 1;
        // Rollout to a point 70% down the runway, then taxi to gate.
        path.add(arr.threshold().interpolate(arr.end(), 0.7));
        int rollIdx = path.size() - 1;
        path.addAll(taxiIn);
        path.add(to.gate());

        cum = new double[path.size()];
        for (int i = 1; i < path.size(); i++) cum[i] = cum[i - 1] + path.get(i - 1).distanceTo(path.get(i));
        sTakeoffStart = cum[takeoffIdx]; sTakeoffEnd = cum[takeoffEndIdx];
        sThreshold = cum[thrIdx]; sRolloutEnd = cum[rollIdx]; total = cum[cum.length - 1];

        double tanG = Math.tan(Math.toRadians(type.glideSlopeDeg()));
        double airborne = sThreshold - sTakeoffStart;
        double avgClimbSpeed = (type.rotateSpeed() + type.cruiseSpeed()) / 2;
        double perMeter = avgClimbSpeed / type.climbRate() + 1 / tanG;   // ground distance used per metre of climb+descent
        double maxAgl = airborne * 0.85 / perMeter;
        double fieldMax = Math.max(from.elevationM(), to.elevationM());
        this.cruiseAlt = fieldMax + Math.max(300, Math.min(type.cruiseAltitude(), maxAgl));
        this.alt = from.elevationM();
        double takeoffDist = type.rotateSpeed() * type.rotateSpeed() / (2 * type.takeoffAccel());
        if (dep.lengthM() < takeoffDist * 1.15)
            throw new IllegalArgumentException(from.icao() + " runway " + dep.ident() + " (" + Math.round(dep.lengthM()) + " m) too short for " + type.name());
    }

    public void setTimeCompression(double c) { this.timeCompression = Math.max(0.1, Math.min(8, c)); }
    public double timeCompression() { return timeCompression; }
    public Phase phase() { return phase; }
    public double cruiseAltitude() { return cruiseAlt; }
    public double totalDistance() { return total; }
    public List<GeoPoint> route() { return Collections.unmodifiableList(path); }

    /** Drains announcements raised since the last call (shown as chat/sound cues in-game). */
    public List<String> pollAnnouncements() { var a = new ArrayList<>(announcements); announcements.clear(); return a; }

    public void beginDeparture() {
        if (phase != Phase.BOARDING) return;
        phase = Phase.TAXI_OUT;
        announcements.add("Doors closed. Welcome aboard, flight from " + from.name() + " to " + to.name() + ".");
    }

    private double groundElevationAt(double dist) {
        // Linear blend between field elevations; terrain-following AGL comes from the world in the Fabric layer.
        double f = Math.max(0, Math.min(1, (dist - sTakeoffEnd) / Math.max(1, sThreshold - sTakeoffEnd)));
        return from.elevationM() + (to.elevationM() - from.elevationM()) * f;
    }

    /** Advance by {@code realSeconds} of wall time (e.g. 0.05 per Minecraft tick). */
    public void tick(double realSeconds) {
        double simSeconds = realSeconds * timeCompression;
        while (simSeconds > 1e-9) {
            double dt = Math.min(0.05, simSeconds);
            step(dt);
            simSeconds -= dt;
        }
    }

    private void step(double dt) {
        if (phase == Phase.BOARDING || phase == Phase.ARRIVED) return;
        elapsed += dt;
        double tanG = Math.tan(Math.toRadians(type.glideSlopeDeg()));
        double ground = groundElevationAt(s);
        Phase before = phase;
        switch (phase) {
            case TAXI_OUT -> {
                v = approach(v, s < sTakeoffStart - 60 ? type.taxiSpeed() : 3, 1.0, dt);
                if (s >= sTakeoffStart - 0.5) { s = sTakeoffStart; v = 0; phase = Phase.TAKEOFF_ROLL; announcements.add("Flight attendants, prepare for takeoff."); }
                vs = 0; alt = from.elevationM();
            }
            case TAKEOFF_ROLL -> {
                v += type.takeoffAccel() * dt;
                alt = from.elevationM(); vs = 0;
                if (v >= type.rotateSpeed()) phase = Phase.CLIMB;
            }
            case CLIMB -> {
                v = approach(v, type.cruiseSpeed(), type.airAccel(), dt);
                vs = Math.min(type.climbRate(), (cruiseAlt - alt) / Math.max(dt, 1));
                if (alt >= cruiseAlt - 1) { alt = cruiseAlt; vs = 0; phase = Phase.CRUISE; announcements.add("We've reached our cruising altitude. Seatback map is live."); }
                if (shouldDescend(tanG)) phase = Phase.DESCENT;
            }
            case CRUISE -> {
                v = approach(v, type.cruiseSpeed(), type.airAccel(), dt);
                vs = 0;
                if (shouldDescend(tanG)) { phase = Phase.DESCENT; announcements.add("We've begun our descent into " + to.name() + "."); }
            }
            case DESCENT -> {
                double toThr = Math.max(0, sThreshold - s);
                double glideAlt = to.elevationM() + toThr * tanG;
                // Slow down progressively: approach speed within 15 km, blending up to cruise further out.
                double targetV = type.approachSpeed() + (type.cruiseSpeed() - type.approachSpeed()) * Math.min(1, toThr / 40_000);
                v = approach(v, targetV, type.airAccel(), dt);
                double wantVs = (Math.min(alt, glideAlt) - alt) / Math.max(dt, 0.05);
                vs = Math.max(-type.maxDescentRate(), Math.min(0, wantVs));
                if (alt > glideAlt + 1 && wantVs >= 0) vs = -type.maxDescentRate();
                if (toThr <= 0.5 || alt <= to.elevationM() + 0.2) {
                    alt = to.elevationM(); vs = 0; phase = Phase.LANDING_ROLL;
                    announcements.add("Welcome to " + to.name() + ".");
                }
            }
            case LANDING_ROLL -> {
                v = approach(v, type.taxiSpeed(), type.brakeDecel(), dt);
                alt = to.elevationM(); vs = 0;
                if (s >= sRolloutEnd || v <= type.taxiSpeed() + 0.01) phase = Phase.TAXI_IN;
            }
            case TAXI_IN -> {
                v = approach(v, total - s < 60 ? 2 : type.taxiSpeed(), 1.5, dt);
                alt = to.elevationM(); vs = 0;
                if (s >= total - 0.5) { s = total; v = 0; phase = Phase.ARRIVED; announcements.add("We've arrived at the gate. Doors are opening."); }
            }
            default -> {}
        }
        if (phase == Phase.ARRIVED) return;
        s = Math.min(total, s + v * dt);
        alt += vs * dt;
        // Landing gear: retract 150 m after liftoff, extend below 600 m AGL on descent.
        double agl = alt - ground;
        boolean wantDown = switch (phase) {
            case CLIMB -> agl < 150;
            case CRUISE -> false;
            case DESCENT -> agl < 600;
            default -> true;
        };
        gear = approach(gear, wantDown ? 1 : 0, 1 / type.gearCycleSeconds(), dt);
        if (before != phase && phase == Phase.CLIMB) announcements.add("Rotate. We're airborne.");
    }

    private boolean shouldDescend(double tanG) {
        double toThr = sThreshold - s;
        double glideAlt = to.elevationM() + toThr * tanG;
        return toThr > 0 && alt >= glideAlt - 1;
    }

    private static double approach(double cur, double target, double rate, double dt) {
        double d = target - cur, m = rate * dt;
        return Math.abs(d) <= m ? target : cur + Math.signum(d) * m;
    }

    public GeoPoint positionAt(double dist) {
        int i = Arrays.binarySearch(cum, dist);
        if (i >= 0) return path.get(i);
        i = -i - 2;
        if (i >= path.size() - 1) return path.get(path.size() - 1);
        double seg = cum[i + 1] - cum[i];
        return path.get(i).interpolate(path.get(i + 1), seg <= 0 ? 0 : (dist - cum[i]) / seg);
    }

    private double headingAt(double dist) {
        int i = Arrays.binarySearch(cum, dist);
        if (i < 0) i = -i - 2;
        i = Math.max(0, Math.min(path.size() - 2, i));
        GeoPoint here = positionAt(dist);
        GeoPoint next = path.get(i + 1);
        if (here.distanceTo(next) < 1) return path.get(i).bearingTo(next);
        return here.bearingTo(next);
    }

    public Telemetry telemetry() {
        double remaining = total - s;
        double ground = groundElevationAt(s);
        double eta;
        if (phase == Phase.ARRIVED) eta = 0;
        else {
            double airborneRemaining = Math.max(0, sThreshold - Math.max(s, sTakeoffStart));
            double avgAir = phase.ordinal() >= Phase.DESCENT.ordinal() ? (v + type.approachSpeed()) / 2 : type.cruiseSpeed() * 0.85;
            double taxiRemaining = remaining - airborneRemaining;
            eta = airborneRemaining / Math.max(avgAir, 1) + taxiRemaining / type.taxiSpeed();
        }
        double pitch = v > 1 ? Math.toDegrees(Math.atan2(vs, v)) : 0;
        if (phase == Phase.DESCENT && alt - ground < 20) pitch = 3; // flare
        return new Telemetry(phase, positionAt(s), alt, alt - ground, v, vs, headingAt(s), pitch, s, remaining, eta, elapsed,
                gear, phase == Phase.BOARDING || phase == Phase.ARRIVED, from.icao(), to.icao());
    }
}
