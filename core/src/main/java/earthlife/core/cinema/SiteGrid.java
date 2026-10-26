package earthlife.core.cinema;

import java.util.*;

/**
 * Where multiplexes may stand in an ordinary world: one candidate site per square grid cell, at a position that depends
 * only on the world seed and the cell (so every server, client and reload agrees, and a site can be found before its
 * chunks exist). Whether the site is actually usable (land, flat enough, no village) is decided later by the game.
 *
 * Rarity presets set the cell size; with one site per cell the typical distance between neighbouring multiplexes is
 * about the cell size: VERY_COMMON ~700 blocks, COMMON ~1,100 (the default), UNCOMMON ~1,800, RARE ~3,000.
 */
public final class SiteGrid {
    public enum Rarity {
        VERY_COMMON(700), COMMON(1100), UNCOMMON(1800), RARE(3000), OFF(0);
        public final int cell;
        Rarity(int cell) { this.cell = cell; }
        public static Rarity parse(String s) {
            for (Rarity r : values()) if (r.name().equalsIgnoreCase(s.replace('-', '_').replace(' ', '_'))) return r;
            return COMMON;
        }
    }

    /** A candidate: cell coordinates, block position (x, z), planned room count, layout seed, facing (0/90/180/270). */
    public record Site(int cx, int cz, int x, int z, int rooms, long seed, int heading) {
        public String id() { return "m" + cx + "_" + cz; }
        public double distanceTo(double px, double pz) { return Math.hypot(x - px, z - pz); }
    }

    public final long worldSeed;
    public final int cell;

    public SiteGrid(long worldSeed, Rarity rarity) { this(worldSeed, rarity.cell); }
    public SiteGrid(long worldSeed, int cell) { this.worldSeed = worldSeed; this.cell = Math.max(0, cell); }

    static long mix(long a, long b, long c) {
        long h = a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL ^ c * 0x165667B19E3779F9L;
        h ^= h >>> 31; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 29;
        return h;
    }

    /** The cell's site, or null if the grid is off. A margin keeps neighbouring buildings apart. */
    public Site site(int cx, int cz) {
        if (cell <= 0) return null;
        Random r = new Random(mix(worldSeed, cx, cz));
        int margin = Math.min(220, cell / 4);
        int x = cx * cell + margin + r.nextInt(Math.max(1, cell - 2 * margin));
        int z = cz * cell + margin + r.nextInt(Math.max(1, cell - 2 * margin));
        int rooms = MultiplexPlanner.randomRooms(r);
        return new Site(cx, cz, x, z, rooms, r.nextLong(), r.nextInt(4) * 90);
    }

    /** An alternative position inside the same cell (tries 1..n) when the first one is not buildable. */
    public Site alternative(Site s, int attempt) {
        Random r = new Random(mix(worldSeed ^ attempt * 0x51L, s.cx(), s.cz()));
        int margin = Math.min(220, cell / 4);
        int x = s.cx() * cell + margin + r.nextInt(Math.max(1, cell - 2 * margin));
        int z = s.cz() * cell + margin + r.nextInt(Math.max(1, cell - 2 * margin));
        return new Site(s.cx(), s.cz(), x, z, s.rooms(), s.seed(), s.heading());
    }

    public int cellOf(double coord) { return Math.floorDiv((int) Math.floor(coord), cell); }

    /** Candidate sites in the square of cells within `rings` cells of the given position, nearest first. */
    public List<Site> near(double x, double z, int rings) {
        List<Site> out = new ArrayList<>();
        if (cell <= 0) return out;
        int cx = cellOf(x), cz = cellOf(z);
        for (int i = cx - rings; i <= cx + rings; i++) for (int j = cz - rings; j <= cz + rings; j++) out.add(site(i, j));
        out.sort(Comparator.comparingDouble(s -> s.distanceTo(x, z)));
        return out;
    }
}
