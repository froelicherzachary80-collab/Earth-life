package earthlife.core.world;

import java.util.*;

/** Geographic index: buckets every feature into the 256-block tiles its bounding box touches. */
public class FeatureIndex {
    public static final class Bucket {
        public final List<Features.Road> roads = new ArrayList<>();
        public final List<Features.Building> buildings = new ArrayList<>();
        public final List<Features.Area> areas = new ArrayList<>();
        public final List<Features.Water> water = new ArrayList<>();
    }

    private static final Bucket EMPTY = new Bucket();
    private final Map<Long, Bucket> buckets = new HashMap<>();
    public final Features features;

    public FeatureIndex(Features f) {
        this.features = f;
        for (var r : f.roads) for (Bucket b : touched(Features.bbox(r.line(), r.widthM() / 2 + 1))) b.roads.add(r);
        for (var x : f.buildings) for (Bucket b : touched(Features.bbox(x.footprint(), 1))) b.buildings.add(x);
        for (var a : f.areas) for (Bucket b : touched(Features.bbox(a.ring(), 1))) b.areas.add(a);
        for (var w : f.water) for (Bucket b : touched(Features.bbox(w.ring(), 1))) b.water.add(w);
    }

    private static long k(int tx, int tz) { return ((long) tx << 32) ^ (tz & 0xffffffffL); }

    private List<Bucket> touched(double[] bb) {
        List<Bucket> out = new ArrayList<>();
        int x0 = Math.floorDiv((int) Math.floor(bb[0]), TileKey.SIZE), z0 = Math.floorDiv((int) Math.floor(bb[1]), TileKey.SIZE);
        int x1 = Math.floorDiv((int) Math.floor(bb[2]), TileKey.SIZE), z1 = Math.floorDiv((int) Math.floor(bb[3]), TileKey.SIZE);
        for (int tx = x0; tx <= x1; tx++) for (int tz = z0; tz <= z1; tz++) out.add(buckets.computeIfAbsent(k(tx, tz), q -> new Bucket()));
        return out;
    }

    public Bucket get(int tx, int tz) { return buckets.getOrDefault(k(tx, tz), EMPTY); }
}
