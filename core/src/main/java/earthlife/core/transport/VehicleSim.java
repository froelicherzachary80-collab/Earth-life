package earthlife.core.transport;

import earthlife.core.world.Features;

import java.util.*;

/**
 * Kinematic bicycle-model physics for road vehicles and boats, plus an autopilot that follows a path
 * (pure pursuit steering, speed planning for corners and stops). Rail vehicles use {@link TrainSim}.
 * Coordinates are blocks (x east, z south); heading is a compass bearing (0 = north).
 */
public final class VehicleSim {
    public final VehicleType type;
    public double x, z, headingDeg, speed;     // speed m/s along heading (negative = reversing)
    private double steerDeg;

    private List<Features.Pt> path = List.of();
    private int target = 0;
    private boolean arrived;

    public VehicleSim(VehicleType type, double x, double z, double headingDeg) {
        this.type = type; this.x = x; this.z = z; this.headingDeg = headingDeg;
    }

    /** Manual driving: throttle -1..1 (negative = brake/reverse), steer -1..1 (negative = left). */
    public void drive(double throttle, double steer, boolean handbrake, double dt) {
        throttle = clamp(throttle, -1, 1);
        steerDeg = clamp(steer, -1, 1) * type.maxSteerDeg * Math.max(0.35, 1 - Math.abs(speed) / (type.maxSpeed * 1.4));
        double a;
        if (handbrake) a = -Math.signum(speed) * type.brake * 1.2;
        else if (throttle > 0) a = speed < -0.1 ? type.brake : type.accel * throttle * (1 - Math.max(0, speed) / type.maxSpeed);
        else if (throttle < 0) a = speed > 0.1 ? type.brake * throttle : type.accel * 0.5 * throttle;
        else a = -Math.signum(speed) * Math.min(Math.abs(speed) / dt, 0.4);         // rolling resistance
        double ns = speed + a * dt;
        if ((handbrake || throttle == 0) && Math.signum(ns) != Math.signum(speed)) ns = 0;
        speed = clamp(ns, -type.maxSpeed * 0.25, type.maxSpeed);
        integrate(dt);
    }

    private void integrate(double dt) {
        double yawRate = type.wheelbase <= 0 ? Math.toRadians(steerDeg) * 0.5 * Math.signum(speed)       // boats: rudder
                : speed / type.wheelbase * Math.tan(Math.toRadians(steerDeg));
        headingDeg = (headingDeg + Math.toDegrees(yawRate * dt) + 360) % 360;
        double h = Math.toRadians(headingDeg);
        x += Math.sin(h) * speed * dt;
        z += -Math.cos(h) * speed * dt;
    }

    public void follow(List<Features.Pt> p) { path = p; target = 0; arrived = p.isEmpty(); }
    public boolean arrived() { return arrived; }

    /** One autopilot step: steer toward a look-ahead point, slow for sharp corners and for the end of the path. */
    public void autopilot(double cruise, double dt) {
        if (arrived || path.isEmpty()) { drive(0, 0, true, dt); return; }
        double look = Math.max(4, Math.abs(speed) * 1.2);
        while (target < path.size() - 1 && dist(path.get(target)) < look) target++;
        Features.Pt tp = path.get(target);
        double want = Math.toDegrees(Math.atan2(tp.x() - x, -(tp.z() - z)));
        double err = ((want - headingDeg + 540) % 360) - 180;
        double steer = clamp(err / type.maxSteerDeg, -1, 1);
        double remaining = dist(tp) + RoadGraph.length(path.subList(target, path.size()));
        // Speed planning: slow for the steering error now, and brake in time for the sharpest corner within reach.
        double cornerLimit = Math.max(4, cruise * (1 - Math.min(0.75, Math.abs(err) / 90)));
        double ahead = dist(tp);
        for (int k = target; k < path.size() - 1 && ahead < 80; k++) {
            Features.Pt a = k == 0 ? new Features.Pt(x, z) : path.get(k - 1), b = path.get(k), c = path.get(k + 1);
            double h1 = Math.atan2(b.x() - a.x(), -(b.z() - a.z())), h2 = Math.atan2(c.x() - b.x(), -(c.z() - b.z()));
            double turn = Math.abs(Math.toDegrees(((h2 - h1 + 3 * Math.PI) % (2 * Math.PI)) - Math.PI));
            double vCorner = Math.max(3.5, cruise * (1 - Math.min(0.85, turn / 90 * 0.85)));
            cornerLimit = Math.min(cornerLimit, Math.sqrt(vCorner * vCorner + 2 * type.brake * 0.5 * Math.max(0, ahead - 3)));
            ahead += Math.hypot(c.x() - b.x(), c.z() - b.z());
        }
        double stopLimit = Math.sqrt(2 * type.brake * 0.7 * Math.max(0, remaining - 1.5));
        double vTarget = Math.min(Math.min(cruise, type.maxSpeed), Math.min(cornerLimit, stopLimit));
        double throttle = speed < vTarget - 0.3 ? 1 : speed > vTarget + 0.3 ? -0.6 : 0.1;
        drive(throttle, steer, false, dt);
        if (target == path.size() - 1 && dist(tp) < 2.0) { arrived = true; speed = 0; }
    }

    private double dist(Features.Pt p) { return Math.hypot(p.x() - x, p.z() - z); }
    private static double clamp(double v, double a, double b) { return Math.max(a, Math.min(b, v)); }
}
