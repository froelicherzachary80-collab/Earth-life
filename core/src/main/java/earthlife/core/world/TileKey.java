package earthlife.core.world;

/** A 256x256-block square of a region (16x16 Minecraft chunks). lod 0 = full detail, 1 = 4 m samples, 2 = 16 m samples. */
public record TileKey(String region, int tx, int tz, int lod) {
    public static final int SIZE = 256;

    public static TileKey containing(String region, int blockX, int blockZ, int lod) {
        return new TileKey(region, Math.floorDiv(blockX, SIZE), Math.floorDiv(blockZ, SIZE), lod);
    }

    public int step() { return 1 << (2 * lod); }
    public int samples() { return SIZE / step(); }
    public int minX() { return tx * SIZE; }
    public int minZ() { return tz * SIZE; }

    public TileKey withLod(int l) { return new TileKey(region, tx, tz, l); }

    /** Chebyshev distance in tiles. */
    public int distance(TileKey o) { return Math.max(Math.abs(tx - o.tx), Math.abs(tz - o.tz)); }

    public String fileName() { return region + "/t_" + tx + "_" + tz + "_l" + lod + ".elt"; }
}
