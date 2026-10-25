package earthlife.core.build;

import java.util.*;
import java.util.function.Consumer;

/**
 * A block structure on a local integer grid (u = along, y = up, v = across), plus named markers
 * (doors, counters, aircraft stands...). Placed into the world at any angle by inverse sampling: every
 * world column maps back to exactly one local column, so rotated walls never get holes.
 * Block ids are vanilla ids without orientation-dependent states, so rotation never needs state fixes.
 */
public final class Blueprint {
    /** column key -> (y -> block id) */
    private final Map<Long, TreeMap<Integer, String>> cols = new HashMap<>();
    private final Map<String, double[]> markers = new LinkedHashMap<>();
    private int minU = Integer.MAX_VALUE, maxU = Integer.MIN_VALUE, minV = Integer.MAX_VALUE, maxV = Integer.MIN_VALUE;

    static long key(int u, int v) { return ((long) u << 32) ^ (v & 0xffffffffL); }

    public void set(int u, int y, int v, String block) {
        cols.computeIfAbsent(key(u, v), k -> new TreeMap<>()).put(y, block);
        minU = Math.min(minU, u); maxU = Math.max(maxU, u); minV = Math.min(minV, v); maxV = Math.max(maxV, v);
    }

    public String get(int u, int y, int v) {
        var c = cols.get(key(u, v));
        return c == null ? null : c.get(y);
    }

    /** Fill an axis-aligned local box (inclusive). */
    public void box(int u0, int y0, int v0, int u1, int y1, int v1, String block) {
        for (int u = Math.min(u0, u1); u <= Math.max(u0, u1); u++)
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                for (int v = Math.min(v0, v1); v <= Math.max(v0, v1); v++) set(u, y, v, block);
    }

    /** Hollow room: floor, walls and roof; interior cleared to air. */
    public void room(int u0, int v0, int u1, int v1, int floorY, int height, String wall, String floor, String roof) {
        box(u0, floorY, v0, u1, floorY, v1, floor);
        box(u0, floorY + height, v0, u1, floorY + height, v1, roof);
        for (int y = floorY + 1; y < floorY + height; y++) {
            for (int u = u0; u <= u1; u++) { set(u, y, v0, wall); set(u, y, v1, wall); }
            for (int v = v0; v <= v1; v++) { set(u0, y, v, wall); set(u1, y, v, wall); }
            box(u0 + 1, y, v0 + 1, u1 - 1, y, v1 - 1, "minecraft:air");
        }
    }

    public void marker(String name, double u, double y, double v) { markers.put(name, new double[]{u, y, v}); }
    public Map<String, double[]> markers() { return Collections.unmodifiableMap(markers); }
    public int blockCount() { return cols.values().stream().mapToInt(Map::size).sum(); }
    public int[] bounds() { return new int[]{minU, minV, maxU, maxV}; }

    /** Blueprint fixed in the world: origin (x,z) at local (0,0), local +u along heading, +v to its left. */
    public Placed place(double originX, double originZ, int baseY, double headingDeg) {
        return new Placed(this, originX, originZ, baseY, headingDeg);
    }

    public static final class Placed {
        public final Blueprint bp;
        public final double ox, oz, ux, uz, vx, vz;
        public final int baseY;
        public final double headingDeg;

        Placed(Blueprint bp, double ox, double oz, int baseY, double headingDeg) {
            this.bp = bp; this.ox = ox; this.oz = oz; this.baseY = baseY; this.headingDeg = headingDeg;
            double h = Math.toRadians(headingDeg);
            ux = Math.sin(h); uz = -Math.cos(h);          // compass heading -> (east, south)
            vx = uz; vz = -ux;                             // left of heading
        }

        public double[] toWorld(double u, double v) { return new double[]{ox + u * ux + v * vx, oz + u * uz + v * vz}; }

        public double[] toLocal(double x, double z) {
            double dx = x - ox, dz = z - oz;
            return new double[]{dx * ux + dz * uz, dx * vx + dz * vz};
        }

        public double[] marker(String name) {
            double[] m = bp.markers.get(name);
            if (m == null) return null;
            double[] w = toWorld(m[0], m[2]);
            return new double[]{w[0], baseY + m[1], w[1]};
        }

        /** World-space corners of the blueprint's footprint (for flattening and overlap checks). */
        public List<double[]> corners(int pad) {
            int[] b = bp.bounds();
            return List.of(toWorld(b[0] - pad, b[1] - pad), toWorld(b[2] + pad, b[1] - pad), toWorld(b[2] + pad, b[3] + pad), toWorld(b[0] - pad, b[3] + pad));
        }

        public record Block(int x, int y, int z, String id) {}

        /** Every block whose world column lies in the given block rectangle (inclusive), e.g. one 16x16 chunk. */
        public void forEachIn(int x0, int z0, int x1, int z1, Consumer<Block> out) {
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
                double[] l = toLocal(x + 0.5, z + 0.5);
                var col = bp.cols.get(key((int) Math.round(l[0]), (int) Math.round(l[1])));
                if (col == null) continue;
                for (var e : col.entrySet()) out.accept(new Block(x, baseY + e.getKey(), z, e.getValue()));
            }
        }

        public int[] worldBounds() {
            double a = Double.MAX_VALUE, b = Double.MAX_VALUE, c = -Double.MAX_VALUE, d = -Double.MAX_VALUE;
            for (double[] p : corners(1)) { a = Math.min(a, p[0]); b = Math.min(b, p[1]); c = Math.max(c, p[0]); d = Math.max(d, p[1]); }
            return new int[]{(int) Math.floor(a), (int) Math.floor(b), (int) Math.ceil(c), (int) Math.ceil(d)};
        }
    }
}
