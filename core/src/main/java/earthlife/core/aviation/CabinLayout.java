package earthlife.core.aviation;

import earthlife.core.model.Part;

import java.util.*;

/**
 * Walkable aircraft interior. Local frame: x = right, y = up, z = forward (metres), origin on the cabin floor
 * at the aircraft's reference point. The same layout drives three things:
 *  1. collision for walking around the cabin ({@link #move}),
 *  2. the visible interior + hull model ({@link #parts}),
 *  3. seat and door positions used for boarding and stand alignment.
 *
 * Walking during flight works by keeping the player's position in this local frame and re-projecting it
 * into the world every tick as the aircraft moves, so the floor can never be "left behind" by the plane.
 */
public final class CabinLayout {
    public static final double WIDTH = 3.4, HEIGHT = 2.4, PITCH = 1.0, RADIUS = 0.3, WALK_SPEED = 4.3;
    private static final double GALLEY = 3.0, REAR = 2.0;

    public record Box(double x0, double z0, double x1, double z1, String what) {
        boolean hits(double x, double z, double r) { return x + r > x0 && x - r < x1 && z + r > z0 && z - r < z1; }
    }
    public record Seat(String id, int row, char letter, double x, double z) {}
    public record Move(double x, double z, boolean exited) {}

    public final AircraftType type;
    public final double length, front, rear;
    public final double doorZ, doorHalf = 0.55;
    private final List<Box> obstacles = new ArrayList<>();
    private final Map<String, Seat> seats = new LinkedHashMap<>();

    public CabinLayout(AircraftType type) {
        this.type = type;
        this.length = GALLEY + type.rows() * PITCH + REAR;
        this.front = length / 2;
        this.rear = -length / 2;
        this.doorZ = front - 1.5;
        String letters = type.seatLetters();
        int half = letters.length() / 2;
        double seatW = (WIDTH / 2 - 0.45) / half;   // aisle 0.9 m wide in the middle
        for (int r = 1; r <= type.rows(); r++) {
            double zc = front - GALLEY - (r - 0.5) * PITCH;
            for (int i = 0; i < letters.length(); i++) {
                boolean left = i < half;
                int k = left ? i : i - half;
                double x0 = left ? -WIDTH / 2 + k * seatW : 0.45 + k * seatW;
                Seat s = new Seat(r + "" + letters.charAt(i), r, letters.charAt(i), x0 + seatW / 2, zc);
                seats.put(s.id(), s);
                // Seat footprint: cushion + backrest occupy the rear 0.7 m of the row; leg room in front is walkable
                // only from the seat itself, so the whole row block is solid for passers-by in the aisle.
                obstacles.add(new Box(x0, zc - PITCH / 2, x0 + seatW, zc + PITCH / 2 - 0.05, "seat " + s.id()));
            }
        }
        obstacles.add(new Box(0.45, front - GALLEY + 0.4, WIDTH / 2, front - 0.4, "galley"));         // galley counter (right, front)
        obstacles.add(new Box(0.45, rear, WIDTH / 2, rear + 1.4, "lavatory"));                         // lavatory (right, rear)
    }

    public Collection<Seat> seats() { return seats.values(); }
    public Seat seat(String id) { return seats.get(id); }
    public List<Box> obstacles() { return Collections.unmodifiableList(obstacles); }

    /** Where a passenger stands to sit down in / get up from a seat: the aisle beside their row. */
    public double[] aisleBeside(Seat s) { return new double[]{0, s.z()}; }

    /** Inside the door frame, just within the cabin. */
    public double[] doorInside() { return new double[]{-WIDTH / 2 + RADIUS + 0.05, doorZ}; }
    /** Door threshold on the outside of the hull (where the jet bridge / stairs must meet). */
    public double[] doorOutside() { return new double[]{-WIDTH / 2 - 0.4, doorZ}; }

    public boolean walkable(double x, double z) {
        if (Math.abs(x) > WIDTH / 2 - RADIUS || z > front - RADIUS || z < rear + RADIUS) return false;
        for (Box b : obstacles) if (b.hits(x, z, RADIUS)) return false;
        return true;
    }

    /**
     * Moves a walking passenger. forward/strafe are -1..1 inputs relative to the player's view yaw
     * (relative to the aircraft's nose, degrees, 0 = facing forward, positive = turned right).
     * Collision is resolved per axis so you slide along walls instead of sticking. If the door is open and you walk
     * out through it, {@code exited} is true and the caller hands you to the jet bridge / ground.
     */
    public Move move(double x, double z, double forward, double strafe, double viewYawDeg, double dt, boolean doorOpen) {
        double len = Math.hypot(forward, strafe);
        if (len > 1) { forward /= len; strafe /= len; }
        double a = Math.toRadians(viewYawDeg);
        // forward is along view direction; strafe is to the view's right.
        double dx = (forward * Math.sin(a) + strafe * Math.cos(a)) * WALK_SPEED * dt;
        double dz = (forward * Math.cos(a) - strafe * Math.sin(a)) * WALK_SPEED * dt;
        double nx = x + dx;
        if (doorOpen && inDoorFrame(x, z) && nx < -WIDTH / 2 - 0.3) return new Move(nx, z, true);   // stepped out through the door
        if (!allowed(nx, z, doorOpen)) nx = x;
        double nz = z + dz;
        if (!allowed(nx, nz, doorOpen)) nz = z;
        return new Move(nx, nz, false);
    }

    private boolean allowed(double x, double z, boolean doorOpen) { return walkable(x, z) || (doorOpen && inDoorFrame(x, z)); }

    private boolean inDoorFrame(double x, double z) {
        return x < -WIDTH / 2 + RADIUS + 0.01 && x > -WIDTH / 2 - 0.6 && Math.abs(z - doorZ) < doorHalf - 0.05;
    }

    // ---- model: interior + hull, built from thin panels so walls are visible from both sides ----
    public List<Part> parts() {
        List<Part> p = new ArrayList<>();
        double t = 0.08, hw = WIDTH / 2;
        String hull = "minecraft:white_concrete", trim = "minecraft:light_gray_concrete", glass = "minecraft:light_blue_stained_glass";
        p.add(new Part(0, -t / 2, 0, WIDTH + 2 * t, t, length, "minecraft:gray_wool", "floor"));
        p.add(new Part(0, HEIGHT + t / 2, 0, WIDTH + 2 * t, t, length, hull, "ceiling"));
        p.add(new Part(0, HEIGHT / 2, front + t / 2, WIDTH, HEIGHT, t, trim, "cockpit_wall"));
        p.add(new Part(0, HEIGHT / 2, rear - t / 2, WIDTH, HEIGHT, t, hull, "rear_wall"));
        for (int side = -1; side <= 1; side += 2) {
            double x = side * (hw + t / 2);
            strip(p, x, 0.5, 1.0, rear, front, side < 0, hull);           // lower wall
            strip(p, x, 1.95, 0.9, rear, front, side < 0, hull);          // upper wall
            // window band (y 1.0-1.5): glass at each row, wall pillars in between
            double z = front;
            for (int r = 1; r <= type.rows(); r++) {
                double wz = front - GALLEY - (r - 0.5) * PITCH, g0 = wz - 0.22, g1 = wz + 0.22;
                strip(p, x, 1.25, 0.5, g1, z, side < 0, hull);
                p.add(new Part(x, 1.25, wz, t, 0.5, 0.44, glass, "window"));
                z = g0;
            }
            strip(p, x, 1.25, 0.5, rear, z, side < 0, hull);
        }
        for (Seat s : seats.values()) {
            p.add(new Part(s.x(), 0.25, s.z() - 0.1, 0.62, 0.5, 0.55, "minecraft:blue_wool", "seat"));
            p.add(new Part(s.x(), 0.75, s.z() - 0.42, 0.62, 0.9, 0.12, "minecraft:blue_wool", "seat"));
        }
        for (Box b : obstacles) if (!b.what().startsWith("seat"))
            p.add(new Part((b.x0() + b.x1()) / 2, b.what().equals("galley") ? 0.5 : 1.1, (b.z0() + b.z1()) / 2,
                    b.x1() - b.x0(), b.what().equals("galley") ? 1.0 : 2.2, b.z1() - b.z0(), "minecraft:smooth_quartz", b.what()));
        // exterior: nose, tail cone, wing, engines, fin, tailplane, gear
        p.add(new Part(0, HEIGHT / 2, front + 1.2, WIDTH, HEIGHT, 2.2, hull, "nose"));
        p.add(new Part(0, HEIGHT * 0.65, front + 1.9, WIDTH * 0.8, 0.6, 0.9, glass, "cockpit_glass"));
        p.add(new Part(0, HEIGHT / 2, rear - 1.5, WIDTH * 0.8, HEIGHT * 0.8, 2.8, hull, "tailcone"));
        p.add(new Part(0, HEIGHT + 0.35, 1.0, 26, 0.3, 3.0, trim, "wing"));
        p.add(new Part(4.5, HEIGHT - 0.3, 2.0, 1.2, 1.2, 3.4, "minecraft:gray_concrete", "engine"));
        p.add(new Part(-4.5, HEIGHT - 0.3, 2.0, 1.2, 1.2, 3.4, "minecraft:gray_concrete", "engine"));
        p.add(new Part(0, HEIGHT + 2.2, rear - 2.0, 0.3, 3.6, 2.2, "minecraft:blue_concrete", "fin"));
        p.add(new Part(0, HEIGHT + 4.0, rear - 2.3, 8.0, 0.25, 1.6, trim, "tailplane"));
        p.add(new Part(0, -0.75, front - 0.5, 0.4, 1.4, 0.4, "minecraft:black_concrete", "gear"));
        p.add(new Part(2.6, -0.75, 0.5, 0.5, 1.4, 0.9, "minecraft:black_concrete", "gear"));
        p.add(new Part(-2.6, -0.75, 0.5, 0.5, 1.4, 0.9, "minecraft:black_concrete", "gear"));
        return p;
    }

    /** Wall strip from z0 to z1; on the door side the part inside the door opening becomes a separate "door" panel. */
    private void strip(List<Part> p, double x, double y, double sy, double z0, double z1, boolean doorSide, String block) {
        if (z1 - z0 < 1e-6) return;
        double d0 = doorZ - doorHalf, d1 = doorZ + doorHalf;
        boolean inDoorHeight = y - sy / 2 < 2.0;
        if (!doorSide || !inDoorHeight || z1 <= d0 || z0 >= d1) { p.add(new Part(x, y, (z0 + z1) / 2, 0.08, sy, z1 - z0, block, "wall")); return; }
        if (z0 < d0) p.add(new Part(x, y, (z0 + d0) / 2, 0.08, sy, d0 - z0, block, "wall"));
        double a = Math.max(z0, d0), b = Math.min(z1, d1);
        p.add(new Part(x, y, (a + b) / 2, 0.08, sy, b - a, "minecraft:light_gray_concrete", "door"));
        if (z1 > d1) p.add(new Part(x, y, (d1 + z1) / 2, 0.08, sy, z1 - d1, block, "wall"));
    }

    /** Height of the cabin floor above the ground when parked (gear extended). */
    public static double floorAboveGround() { return 1.45; }
}
