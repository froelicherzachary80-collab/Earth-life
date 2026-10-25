package earthlife.core.aviation;

/** Performance + cabin layout. Speeds m/s, altitudes m, accelerations m/s². */
public record AircraftType(String id, String name,
                           double taxiSpeed, double rotateSpeed, double approachSpeed, double cruiseSpeed,
                           double takeoffAccel, double airAccel, double brakeDecel,
                           double climbRate, double maxDescentRate, double cruiseAltitude, double glideSlopeDeg,
                           int rows, String seatLetters, double gearCycleSeconds) {

    /** The V0.1 "EarthLife Hopper": a blocky 48-seat regional turboprop (Dash 8-class performance) that fits small fields. */
    public static final AircraftType HOPPER = new AircraftType("hopper", "EarthLife Hopper",
            9, 52, 58, 140,
            2.4, 0.8, 2.2,
            8, 12, 7_000, 3.0,
            12, "ABCD", 8);
}
