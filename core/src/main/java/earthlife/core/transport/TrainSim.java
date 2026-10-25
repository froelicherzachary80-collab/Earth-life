package earthlife.core.transport;

import earthlife.core.world.Features;

import java.util.*;

/**
 * A train running a timetable along a rail line, stopping at stations (dwell time), and turning back at the ends.
 * Position is exact distance along the track, so carriages can be placed on the curve behind the locomotive.
 */
public final class TrainSim {
    public record Station(String name, double at) {}
    public enum State { DWELL, RUNNING }

    private final List<Features.Pt> track;
    private final double[] cum;
    private final List<Station> stations;
    private final VehicleType type;
    private final double dwellSeconds;
    private double s, v, dwell;
    private int dir = 1, nextStop;
    private State state = State.DWELL;

    public TrainSim(VehicleType type, List<Features.Pt> track, List<Station> stations, double dwellSeconds) {
        this.type = type; this.track = track; this.dwellSeconds = dwellSeconds;
        cum = new double[track.size()];
        for (int i = 1; i < track.size(); i++) cum[i] = cum[i - 1] + Math.hypot(track.get(i).x() - track.get(i - 1).x(), track.get(i).z() - track.get(i - 1).z());
        this.stations = new ArrayList<>(stations);
        this.stations.sort(Comparator.comparingDouble(Station::at));
        s = this.stations.get(0).at();
        nextStop = 1;
        dwell = dwellSeconds;
    }

    public double length() { return cum[cum.length - 1]; }
    public State state() { return state; }
    public double speed() { return v; }
    public double distance() { return s; }
    public Station currentOrNext() { return stations.get(nextStop); }

    public void tick(double dt) {
        if (state == State.DWELL) {
            dwell -= dt;
            if (dwell <= 0) state = State.RUNNING;
            return;
        }
        double stopAt = stations.get(nextStop).at();
        double remaining = Math.abs(stopAt - s);
        double vBrake = Math.sqrt(2 * type.brake * Math.max(0, remaining));
        double target = Math.min(type.maxSpeed, vBrake);
        v = v < target ? Math.min(target, v + type.accel * dt) : Math.max(target, v - type.brake * dt);
        s += dir * v * dt;
        if (remaining <= 0.3 || (dir > 0 ? s >= stopAt : s <= stopAt)) {
            s = stopAt; v = 0; state = State.DWELL; dwell = dwellSeconds;
            if (nextStop == stations.size() - 1 && dir > 0) dir = -1;
            else if (nextStop == 0 && dir < 0) dir = 1;
            nextStop += dir;
        }
    }

    /** Position and heading at a distance behind the head of the train (for each carriage). */
    public double[] poseAt(double behind) {
        double d = Math.max(0, Math.min(length(), s - dir * behind));
        int i = Arrays.binarySearch(cum, d);
        if (i < 0) i = -i - 2;
        i = Math.max(0, Math.min(track.size() - 2, i));
        var a = track.get(i); var b = track.get(i + 1);
        double seg = cum[i + 1] - cum[i], f = seg <= 0 ? 0 : (d - cum[i]) / seg;
        double hx = (b.x() - a.x()) * dir, hz = (b.z() - a.z()) * dir;
        return new double[]{a.x() + (b.x() - a.x()) * f, a.z() + (b.z() - a.z()) * f, (Math.toDegrees(Math.atan2(hx, -hz)) + 360) % 360};
    }
}
