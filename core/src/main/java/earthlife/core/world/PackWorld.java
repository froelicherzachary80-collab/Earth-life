package earthlife.core.world;

import earthlife.core.geo.GeoPoint;
import earthlife.core.geo.LocalProjection;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A world's view of the Northeast region packs: cells loaded on demand, projected into the world's block coordinates,
 * indexed per 256-block tile, with their terrain as an {@link ElevationSource}. Tile generation blocks until the cells
 * it needs are there (they are usually prefetched around players), so every chunk is built from the real data.
 * Cells are kept in a memory-bounded LRU; dropping one is safe because tiles are cached on disk and regenerating
 * gives the same result.
 */
public final class PackWorld {
    public static final double MARGIN_M = 300;

    public record Cell(int lat8, int lon8, RegionCell.Tier tier, RegionCell terrain, Features features, FeatureIndex index, int weight) {}

    public final RegionPacks packs;
    public final LocalProjection proj;
    public final String attribution;
    private final long budget;
    private long used;
    private final LinkedHashMap<Long, Cell> cells = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<Long, Object> loading = new ConcurrentHashMap<>();
    private final Set<Long> missing = ConcurrentHashMap.newKeySet();
    private final List<Consumer<Cell>> listeners = new CopyOnWriteArrayList<>();
    /** Max time tile generation waits for a pack download (ms). */
    public long waitMs = 180_000;
    /** Optional world options applied to each cell's features (e.g. roads or buildings turned off). */
    public volatile java.util.function.UnaryOperator<Features> filter;
    private final ThreadLocal<Map<Long, Cell>> announcing = ThreadLocal.withInitial(HashMap::new);

    public PackWorld(RegionPacks packs, LocalProjection proj, String attribution, long weightBudget) {
        this.packs = packs; this.proj = proj; this.attribution = attribution; this.budget = weightBudget;
    }

    static long key(int a, int o) { return RegionPacks.key(a, o); }

    /** Called (on the loading thread, before anyone can use the cell) each time a cell is loaded. */
    public void onLoad(Consumer<Cell> l) { listeners.add(l); }

    public boolean covered(double x, double z) {
        GeoPoint g = proj.fromBlock(x, z);
        return packs.covered(RegionCell.cellLat(g.lat()), RegionCell.cellLon(g.lon()));
    }

    /** A loaded cell, loading it (blocking) if needed; null if outside the map or unavailable. */
    public Cell cell(int lat8, int lon8) {
        long k = key(lat8, lon8);
        Cell own = announcing.get().get(k);                  // a load listener asking about the cell it is announcing
        if (own != null) return own;
        synchronized (cells) { Cell c = cells.get(k); if (c != null) return c; }
        if (missing.contains(k) || !packs.covered(lat8, lon8)) return null;
        Object lock = loading.computeIfAbsent(k, q -> new Object());
        synchronized (lock) {
            synchronized (cells) { Cell c = cells.get(k); if (c != null) return c; }
            try {
                byte[] b = packs.cellBytes(lat8, lon8, waitMs);
                if (b == null) { missing.add(k); return null; }
                RegionCell rc = RegionCell.parse(b);
                Features f = rc.project(proj, attribution);
                var flt = filter;
                if (flt != null) f = flt.apply(f);
                FeatureIndex idx = new FeatureIndex(f);
                int weight = 1 + f.buildings.size() + f.roads.size() + f.areas.size() / 2 + rc.demN * rc.demN / 400;
                rc.roads.clear(); rc.buildings.clear(); rc.areas.clear(); rc.water.clear();       // keep only terrain + POIs
                Cell c = new Cell(lat8, lon8, rc.tier, rc, f, idx, weight);
                // listeners (e.g. cinemas registering their sites) run before any other thread can see the cell
                announcing.get().put(k, c);
                try { for (var l : listeners) try { l.accept(c); } catch (RuntimeException ignored) {} }
                finally { announcing.get().remove(k); }
                synchronized (cells) {
                    cells.put(k, c);
                    used += weight;
                    var it = cells.entrySet().iterator();
                    while (used > budget && cells.size() > 4 && it.hasNext()) {
                        var e = it.next();
                        if (e.getKey() == k) continue;
                        used -= e.getValue().weight();
                        it.remove();
                    }
                }
                return c;
            } catch (java.util.concurrent.TimeoutException e) {
                packs.errors.add("timed out waiting for map data " + RegionCell.packName(lat8, lon8));
                return null;
            } catch (Exception e) {
                packs.errors.add(RegionCell.name(lat8, lon8) + ": " + e.getMessage());
                if (!(e.getCause() instanceof java.io.IOException io && String.valueOf(io.getMessage()).startsWith("offline"))) missing.add(k);
                return null;
            } finally {
                loading.remove(k);
            }
        }
    }

    /** Cell indices (lat8, lon8) overlapping a block rectangle plus a margin. */
    public List<int[]> cellsFor(double x0, double z0, double x1, double z1, double margin) {
        double[][] corners = {{x0 - margin, z0 - margin}, {x1 + margin, z0 - margin}, {x0 - margin, z1 + margin}, {x1 + margin, z1 + margin}, {(x0 + x1) / 2, (z0 + z1) / 2}};
        int la0 = Integer.MAX_VALUE, la1 = Integer.MIN_VALUE, lo0 = Integer.MAX_VALUE, lo1 = Integer.MIN_VALUE;
        for (double[] c : corners) {
            GeoPoint g = proj.fromBlock(c[0], c[1]);
            int a = RegionCell.cellLat(g.lat()), o = RegionCell.cellLon(g.lon());
            la0 = Math.min(la0, a); la1 = Math.max(la1, a); lo0 = Math.min(lo0, o); lo1 = Math.max(lo1, o);
        }
        List<int[]> out = new ArrayList<>();
        for (int a = la0; a <= la1; a++) for (int o = lo0; o <= lo1; o++) out.add(new int[]{a, o});
        return out;
    }

    /** Starts loading every cell within radius metres of a point (no waiting). */
    public void prefetch(double x, double z, double radius, java.util.concurrent.Executor ex) {
        for (int[] c : cellsFor(x - radius, z - radius, x + radius, z + radius, 0)) {
            long k = key(c[0], c[1]);
            boolean have;
            synchronized (cells) { have = cells.containsKey(k); }
            if (!have && packs.covered(c[0], c[1]) && !missing.contains(k) && !loading.containsKey(k)) ex.execute(() -> cell(c[0], c[1]));
        }
    }

    /** Loaded cells (no loading). */
    public List<Cell> loaded() { synchronized (cells) { return new ArrayList<>(cells.values()); } }

    // ------------------------------------------------------------------ feature index used by the tile generator

    private final Map<Long, FeatureIndex.Bucket> merged = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, FeatureIndex.Bucket> e) { return size() > 512; }
    });

    public FeatureIndex index() {
        return new FeatureIndex(new Features(attribution)) {
            @Override public Bucket get(int tx, int tz) {
                long k = key(tx, tz);
                Bucket b = merged.get(k);
                if (b != null) return b;
                double x0 = (double) tx * TileKey.SIZE, z0 = (double) tz * TileKey.SIZE;
                Bucket out = new Bucket();
                for (int[] c : cellsFor(x0, z0, x0 + TileKey.SIZE, z0 + TileKey.SIZE, MARGIN_M)) {
                    Cell cell = cell(c[0], c[1]);
                    if (cell == null) continue;
                    Bucket cb = cell.index().get(tx, tz);
                    out.roads.addAll(cb.roads); out.buildings.addAll(cb.buildings); out.areas.addAll(cb.areas); out.water.addAll(cb.water);
                }
                merged.put(k, out);
                return out;
            }
        };
    }

    /** All features of the loaded cells around a point (loading them), e.g. for shops, residents and cinemas. */
    public Features featuresNear(double x, double z, double radius) {
        Features f = new Features(attribution);
        Set<String> seen = new HashSet<>();
        for (int[] c : cellsFor(x - radius, z - radius, x + radius, z + radius, 0)) {
            Cell cell = cell(c[0], c[1]);
            if (cell == null) continue;
            for (var b : cell.features().buildings) if (seen.add(b.id())) f.buildings.add(b);
            f.roads.addAll(cell.features().roads); f.areas.addAll(cell.features().areas); f.water.addAll(cell.features().water);
            f.names.putAll(cell.features().names);
        }
        return f;
    }

    // ------------------------------------------------------------------ terrain

    /**
     * Terrain from the packs' grids (and the sea / Great Lakes mask), falling back to {@code fallback} outside the map.
     * Block -> lat/lon uses a per-tile interpolated inverse projection (sub-millimetre over 256 blocks).
     */
    public ElevationSource elevation(ElevationSource fallback) {
        return new ElevationSource() {
            final Map<Long, double[]> inv = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<Long, double[]> e) { return size() > 4096; }
            });

            double[] latLon(double x, double z) {
                int tx = Math.floorDiv((int) Math.floor(x), TileKey.SIZE), tz = Math.floorDiv((int) Math.floor(z), TileKey.SIZE);
                double[] n = inv.computeIfAbsent(key(tx, tz), q -> {
                    double[] a = new double[18];
                    for (int j = 0; j < 3; j++) for (int i = 0; i < 3; i++) {
                        GeoPoint g = proj.fromBlock((double) tx * TileKey.SIZE + i * TileKey.SIZE / 2.0, (double) tz * TileKey.SIZE + j * TileKey.SIZE / 2.0);
                        a[(j * 3 + i) * 2] = g.lat(); a[(j * 3 + i) * 2 + 1] = g.lon();
                    }
                    return a;
                });
                double u = (x - (double) tx * TileKey.SIZE) / TileKey.SIZE, v = (z - (double) tz * TileKey.SIZE) / TileKey.SIZE;
                double[] bu = {2 * (u - 0.5) * (u - 1), -4 * u * (u - 1), 2 * u * (u - 0.5)}, bv = {2 * (v - 0.5) * (v - 1), -4 * v * (v - 1), 2 * v * (v - 0.5)};
                double la = 0, lo = 0;
                for (int j = 0; j < 3; j++) for (int i = 0; i < 3; i++) { double k = bv[j] * bu[i]; la += k * n[(j * 3 + i) * 2]; lo += k * n[(j * 3 + i) * 2 + 1]; }
                return new double[]{la, lo};
            }

            Cell at(double[] ll) { return cell(RegionCell.cellLat(ll[0]), RegionCell.cellLon(ll[1])); }

            @Override public double elevationAt(double x, double z) {
                double[] ll = latLon(x, z);
                Cell c = at(ll);
                double e = c == null ? Double.NaN : c.terrain().elevation(ll[0], ll[1]);
                return Double.isNaN(e) ? fallback.elevationAt(x, z) : e;
            }

            @Override public boolean isMeasured(double x, double z) {
                double[] ll = latLon(x, z);
                Cell c = at(ll);
                return c != null && c.terrain().demN > 1;
            }

            @Override public double waterLevel(double x, double z) {
                double[] ll = latLon(x, z);
                Cell c = at(ll);
                return c == null ? Double.NaN : c.terrain().waterSurface(ll[0], ll[1]);
            }
        };
    }
}
