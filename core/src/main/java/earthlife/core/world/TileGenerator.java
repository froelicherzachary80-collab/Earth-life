package earthlife.core.world;

import earthlife.core.geo.LocalProjection;
import earthlife.core.world.Features.*;
import earthlife.core.world.TileData.Surface;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Rasterizes projected geographic features + elevation into a {@link TileData}. Pure function of
 * (key, features, elevation, seed): the same input always yields identical tiles, which is what lets
 * us discard unmodified tiles and regenerate them later while persisting only player edits.
 */
public final class TileGenerator {
    /** Bump when the rasterisation changes, so cached tiles from older versions are regenerated. */
    public static final int VERSION = 4;

    static int roadRank(RoadClass c) {
        return switch (c) { case PATH -> 0; case RAIL -> 1; case SERVICE -> 2; case RESIDENTIAL -> 3; case SECONDARY -> 4; case PRIMARY -> 5; case MOTORWAY -> 6; case TAXIWAY -> 7; case RUNWAY -> 8; };
    }

    private final FeatureIndex index;
    private final ElevationSource elevation;
    private final LocalProjection proj;
    private final long seed;

    public TileGenerator(FeatureIndex index, ElevationSource elevation, LocalProjection proj, long seed) {
        this.index = index; this.elevation = elevation; this.proj = proj; this.seed = seed;
    }

    /** A flattened construction site (airport, station) whose blocks come from a blueprint. */
    public record Site(String id, java.util.List<Pt> ring, int baseY, Surface surface) {}

    private final java.util.List<Site> sites = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Register sites before tiles are generated; columns inside are levelled to baseY and kept free of trees/houses. */
    public void addSite(Site s) { sites.add(s); }
    public java.util.List<Site> sites() { return java.util.List.copyOf(sites); }

    /** Per-building decisions that must agree across tile borders: base height, style, roof frame, door and chimney spots. */
    record Plan(int baseY, BuildingStyle.Resolved style, BuildingStyle.Obb obb, int roofH, byte kind, double[] door, double[] chimney,
                java.util.Map<Long, Integer> furniture) {}

    /** Interior furniture codes stored in TileData.flags2 bits 4-7 (see TileData.furniture). */
    public static final int F_CRAFTING = 1, F_FURNACE = 2, F_CHEST = 3, F_BED_FOOT = 4, F_BED_HEAD = 5, F_TABLE = 6, F_BOOKSHELF = 7, F_SHELF = 8, F_COUNTER = 9;

    /**
     * Gameplay floor plans (never a claim about a real building's interior): houses get a kitchen corner (crafting
     * table + furnace), storage chest, bed, bookshelf and a lit table; stores get shelf rows (barrels) and a counter.
     */
    static java.util.Map<Long, Integer> furniture(Building bd, BuildingStyle.Obb o) {
        java.util.Map<Long, Integer> m = new java.util.HashMap<>();
        if (o == null || o.halfU() < 3 || o.halfV() < 2.5) return m;
        String k = bd.kind() == null ? "" : bd.kind();
        double area = o.halfU() * o.halfV() * 4;
        if (k.equals("yes") && area >= 50 && area <= 260) k = "house";     // most small untagged buildings in Western NY are houses
        java.util.function.BiConsumer<double[], Integer> put = (uv, code) -> {
            double x = o.cx() + uv[0] * o.ux() - uv[1] * o.uz(), z = o.cz() + uv[0] * o.uz() + uv[1] * o.ux();
            if (Features.pointInRing(bd.footprint(), x, z)) m.putIfAbsent(((long) (int) Math.floor(x) << 32) ^ ((int) Math.floor(z) & 0xffffffffL), code);
        };
        double hu = o.halfU() - 1.5, hv = o.halfV() - 1.5;
        switch (k) {
            case "house", "residential", "detached", "semidetached_house", "bungalow", "terrace", "apartments", "farm" -> {
                put.accept(new double[]{-hu, -hv}, F_CRAFTING);
                put.accept(new double[]{-hu + 1, -hv}, F_FURNACE);
                put.accept(new double[]{-hu, hv}, F_CHEST);
                put.accept(new double[]{hu - 1, hv}, F_BED_FOOT);
                put.accept(new double[]{hu, hv}, F_BED_HEAD);
                put.accept(new double[]{hu, -hv}, F_BOOKSHELF);
                put.accept(new double[]{0, 0}, F_TABLE);
            }
            case "retail", "commercial", "supermarket", "kiosk", "shop" -> {
                for (double u = -hu + 1; u <= hu - 3; u += 2) { put.accept(new double[]{u, -hv}, F_SHELF); put.accept(new double[]{u, 0}, F_SHELF); }
                for (double v = hv; v >= hv - 2; v -= 1) put.accept(new double[]{hu, v}, F_COUNTER);
            }
            default -> {}
        }
        return m;
    }
    private final java.util.Map<String, Plan> plans = new java.util.concurrent.ConcurrentHashMap<>();

    Plan plan(Building bd) {
        double[] bb = Features.bbox(bd.footprint(), 0);
        double cx = (bb[0] + bb[2]) / 2, cz = (bb[1] + bb[3]) / 2;
        int baseY = proj.elevationToY(elevation.elevationAt(cx, cz));
        var style = BuildingStyle.resolve(bd);
        var obb = BuildingStyle.obb(bd.footprint());
        int roofH = style.roofHeight() > 0 ? style.roofHeight() : BuildingStyle.defaultRoofHeight(style.shape(), obb);
        byte kind = (byte) switch (bd.kind() == null ? "" : bd.kind()) {
            case "house", "residential", "detached" -> 0;
            case "commercial", "retail", "school" -> 1;
            case "terminal" -> 2;
            case "tower", "control_tower" -> 3;
            case "barn", "farm_auxiliary", "shed" -> 4;
            default -> 5;
        };
        return new Plan(baseY, style, obb, roofH, kind, doorPoint(bd, obb), style.chimney() ? new double[]{
                obb.cx() + obb.ux() * obb.halfU() * 0.55 - obb.uz() * obb.halfV() * 0.3,
                obb.cz() + obb.uz() * obb.halfU() * 0.55 + obb.ux() * obb.halfV() * 0.3} : null, furniture(bd, obb));
    }

    /** Front door: the point on the outline closest to the nearest road (within 80 m), else the middle of a long side. */
    double[] doorPoint(Building bd, BuildingStyle.Obb obb) {
        double[] best = null; double bestD = 80;
        int tx0 = Math.floorDiv((int) (obb.cx() - 100), TileKey.SIZE), tx1 = Math.floorDiv((int) (obb.cx() + 100), TileKey.SIZE);
        int tz0 = Math.floorDiv((int) (obb.cz() - 100), TileKey.SIZE), tz1 = Math.floorDiv((int) (obb.cz() + 100), TileKey.SIZE);
        for (int tx = tx0; tx <= tx1; tx++) for (int tz = tz0; tz <= tz1; tz++) for (Road r : index.get(tx, tz).roads) {
            if (!r.cls().drivable() && r.cls() != RoadClass.PATH) continue;
            for (int k = 1; k < r.line().size(); k++) {
                Pt a = r.line().get(k - 1), c = r.line().get(k);
                double d = Features.distToSegment(obb.cx(), obb.cz(), a, c);
                if (d < bestD) { bestD = d; best = closestOnSegment(obb.cx(), obb.cz(), a, c); }
            }
        }
        if (best == null) best = new double[]{obb.cx() - obb.uz() * (obb.halfV() + 10), obb.cz() + obb.ux() * (obb.halfV() + 10)};
        // closest point on the footprint outline to that road point
        double[] door = null; double dd = Double.MAX_VALUE;
        var fp = bd.footprint();
        for (int k = 0; k < fp.size(); k++) {
            Pt a = fp.get(k), c = fp.get((k + 1) % fp.size());
            double[] q = closestOnSegment(best[0], best[1], a, c);
            double d = Math.hypot(q[0] - best[0], q[1] - best[1]);
            if (d < dd) { dd = d; door = q; }
        }
        return door;
    }

    static double[] closestOnSegment(double px, double pz, Pt a, Pt b) {
        double dx = b.x() - a.x(), dz = b.z() - a.z(), len2 = dx * dx + dz * dz;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - a.x()) * dx + (pz - a.z()) * dz) / len2));
        return new double[]{a.x() + t * dx, a.z() + t * dz};
    }

    public TileData generate(TileKey key) {
        TileData t = new TileData(key);
        FeatureIndex.Bucket b = index.get(key.tx(), key.tz());
        int step = key.step(), n = t.n;

        for (int sz = 0; sz < n; sz++) for (int sx = 0; sx < n; sx++) {
            double x = key.minX() + sx * step + step / 2.0, z = key.minZ() + sz * step + step / 2.0;
            int i = t.idx(sx, sz);
            t.terrainY[i] = (short) proj.elevationToY(elevation.elevationAt(x, z));
            Surface s = Surface.GRASS;
            byte f = elevation.isMeasured(x, z) ? 0 : TileData.FLAG_ESTIMATED;
            boolean coverKnown = false;
            for (Area a : b.areas) {
                if (Features.pointInRing(a.ring(), x, z)) {
                    s = switch (a.cover()) {
                        case FARMLAND -> Surface.FARMLAND;
                        case ORCHARD -> Surface.ORCHARD;
                        case FOREST -> Surface.FOREST;
                        case BEACH -> Surface.SAND;
                        case WETLAND -> Surface.WETLAND;
                        case INDUSTRIAL, AIRPORT_APRON -> Surface.CONCRETE;
                        case GRASS, RESIDENTIAL -> Surface.GRASS;
                    };
                    coverKnown = true;
                    if (a.prov() == Provenance.ESTIMATED) f |= TileData.FLAG_ESTIMATED;
                }
            }
            if (!coverKnown) f |= TileData.FLAG_ESTIMATED;
            for (Water w : b.water) {
                if (w.contains(x, z)) {
                    s = Surface.WATER;
                    f |= TileData.FLAG_WATER;
                    f &= ~TileData.FLAG_ESTIMATED;
                    t.terrainY[i] = (short) (t.terrainY[i] - 3); // basin; Fabric layer fills water to terrainY+2
                    break;
                }
            }
            // sea and Great Lakes from the terrain data's water mask: the elevation is the bed, water up to the surface
            double wl = elevation.waterLevel(x, z);
            if (!Double.isNaN(wl)) {
                int wy = proj.elevationToY(wl);
                if (wy > t.terrainY[i]) {
                    s = Surface.WATER;
                    f = (byte) ((f | TileData.FLAG_WATER) & ~TileData.FLAG_ESTIMATED & ~TileData.FLAG_TREE);
                    t.waterY[i] = (short) wy;
                }
            }
            if ((s == Surface.FOREST || s == Surface.ORCHARD) && step == 1) {
                int spacing = s == Surface.ORCHARD ? 6 : 5;
                int gx = Math.floorMod((int) x, spacing), gz = Math.floorMod((int) z, spacing);
                long h = mix(seed, Math.floorDiv((int) x, spacing), Math.floorDiv((int) z, spacing));
                int jx = (int) Math.floorMod(h, (long) spacing), jz = (int) Math.floorMod(h >>> 8, (long) spacing);
                if (s == Surface.ORCHARD) { jx = 2; jz = 2; } // orchards are rows
                if (gx == jx && gz == jz && (s == Surface.ORCHARD || (h & 0x30000) != 0)) f |= TileData.FLAG_TREE;
            }
            t.surface[i] = (byte) s.ordinal();
            t.flags[i] = f;
        }

        // Roads after landcover, minor first so major roads win where they cross (footpaths/crosswalks don't cut a
        // highway into dirt). Roads/runways are flattened to their own centre-line elevation for smooth surfaces.
        List<Road> ordered = new ArrayList<>(b.roads);
        ordered.sort(Comparator.comparingInt(r -> roadRank(r.cls())));
        for (Road r : ordered) {
            double half = Math.max(0.5, r.widthM() / 2);
            double[] bb = Features.bbox(r.line(), half + step);
            forEachSample(t, bb, (sx, sz, x, z) -> {
                double best = Double.MAX_VALUE;
                for (int k = 1; k < r.line().size(); k++) best = Math.min(best, Features.distToSegment(x, z, r.line().get(k - 1), r.line().get(k)));
                if (best > half + (step > 1 ? step / 2.0 : 0)) return;
                int i = t.idx(sx, sz);
                Surface s = switch (r.cls()) {
                    case RUNWAY -> (best < 0.6 && step == 1 && Math.floorMod((int) (x + z), 30) < 15) ? Surface.ROAD_MARKING : Surface.RUNWAY;
                    case TAXIWAY -> Surface.TAXIWAY;
                    case PATH -> Surface.GRAVEL_PATH;
                    case RAIL -> Surface.RAIL_BED;
                    default -> (best < 0.5 && step == 1 && r.cls() != RoadClass.SERVICE && Math.floorMod((int) (x + z), 8) < 4) ? Surface.ROAD_MARKING : Surface.ASPHALT;
                };
                t.surface[i] = (byte) s.ordinal();
                // crossing water: the road runs on a causeway/bridge deck just above the water surface
                if ((t.flags[i] & TileData.FLAG_WATER) != 0) {
                    int surfaceY = t.waterY[i] != 0 ? t.waterY[i] : t.terrainY[i] + 2;
                    t.terrainY[i] = (short) Math.max(t.terrainY[i], surfaceY + 1);
                    t.waterY[i] = 0;
                }
                byte kindFlag = r.cls() == RoadClass.RAIL ? TileData.FLAG_RAIL : TileData.FLAG_ROAD;
                t.flags[i] = (byte) ((t.flags[i] | kindFlag) & ~TileData.FLAG_TREE & ~TileData.FLAG_WATER);
                if (r.prov() == Provenance.VERIFIED) t.flags[i] &= ~TileData.FLAG_ESTIMATED;
            });
        }

        // Detail tiers (region packs): towns get 1.5 m sidewalks along their streets, cities 2.5 m with street trees.
        if (b.tier >= RegionCell.Tier.TOWN.ordinal() && step == 1) {
            boolean city = b.tier >= RegionCell.Tier.CITY.ordinal();
            for (Road r : ordered) {
                if (!(r.cls() == RoadClass.PRIMARY || r.cls() == RoadClass.SECONDARY || r.cls() == RoadClass.RESIDENTIAL)) continue;
                double half = Math.max(0.5, r.widthM() / 2), sw = city ? 2.5 : 1.5;
                double[] bb = Features.bbox(r.line(), half + sw + 1);
                forEachSample(t, bb, (sx, sz, x, z) -> {
                    int i = t.idx(sx, sz);
                    if ((t.flags[i] & (TileData.FLAG_ROAD | TileData.FLAG_WATER | TileData.FLAG_RAIL | TileData.FLAG_SITE)) != 0) return;
                    double best = Double.MAX_VALUE;
                    for (int k = 1; k < r.line().size(); k++) best = Math.min(best, Features.distToSegment(x, z, r.line().get(k - 1), r.line().get(k)));
                    if (best <= half || best > half + sw) return;
                    t.surface[i] = (byte) Surface.SIDEWALK.ordinal();
                    byte f = (byte) (t.flags[i] & ~TileData.FLAG_TREE);
                    // street trees on the outer edge of city sidewalks, every ~14 m
                    if (city && best > half + sw - 1 && (mix(seed, Math.floorDiv((int) x, 14), Math.floorDiv((int) z, 14)) & 7) == 0
                            && Math.floorMod((int) x, 14) == 7 && Math.floorMod((int) z, 14) == 7) f |= TileData.FLAG_TREE;
                    t.flags[i] = f;
                });
            }
        }

        for (Building bd : b.buildings) {
            if (plans.size() > 400_000) plans.clear();               // region packs: millions of buildings; plans are deterministic
            Plan plan = plans.computeIfAbsent(bd.id(), id -> plan(bd));
            double[] bb = Features.bbox(bd.footprint(), 0);
            forEachSample(t, bb, (sx, sz, x, z) -> {
                if (!Features.pointInRing(bd.footprint(), x, z)) return;
                int i = t.idx(sx, sz);
                t.terrainY[i] = (short) plan.baseY;
                int eave = plan.baseY + plan.style.eave();
                int roof = (int) Math.round(BuildingStyle.roofAt(plan.style.shape(), plan.obb, plan.roofH, x, z));
                t.eaveY[i] = (short) eave;
                t.structureTopY[i] = (short) (eave + Math.max(plan.style.shape() == BuildingStyle.Shape.FLAT ? 1 : 0, roof));
                t.structureKind[i] = plan.kind;
                t.wallMat[i] = (byte) plan.style.wall().ordinal();
                t.roofMat[i] = (byte) plan.style.roof().ordinal();
                boolean wall = !Features.pointInRing(bd.footprint(), x - step, z) || !Features.pointInRing(bd.footprint(), x + step, z)
                        || !Features.pointInRing(bd.footprint(), x, z - step) || !Features.pointInRing(bd.footprint(), x, z + step);
                byte f = (byte) ((t.flags[i] | TileData.FLAG_BUILDING) & ~TileData.FLAG_TREE & ~TileData.FLAG_ROAD);
                if (wall) f |= TileData.FLAG_BUILDING_WALL;
                if (bd.prov() == Provenance.ESTIMATED) f |= TileData.FLAG_ESTIMATED; else f &= ~TileData.FLAG_ESTIMATED;
                t.flags[i] = f;
                byte f2 = 0;
                if (wall && plan.door != null && Math.hypot(x - plan.door[0], z - plan.door[1]) < 1.0) f2 |= TileData.F2_DOOR;
                if (plan.chimney != null && Math.hypot(x - plan.chimney[0], z - plan.chimney[1]) < 0.71) f2 |= TileData.F2_CHIMNEY;
                if (!wall && step == 1) {
                    Integer fc = plan.furniture.get(((long) (int) Math.floor(x) << 32) ^ ((int) Math.floor(z) & 0xffffffffL));
                    if (fc != null) f2 |= (byte) (fc << 4);
                }
                t.flags2[i] = f2;
            });
        }
        for (Site site : sites) {
            double[] bb = Features.bbox(site.ring(), 0);
            if (bb[2] < key.minX() || bb[0] > key.minX() + TileKey.SIZE || bb[3] < key.minZ() || bb[1] > key.minZ() + TileKey.SIZE) continue;
            forEachSample(t, bb, (sx, sz, x, z) -> {
                if (!Features.pointInRing(site.ring(), x, z)) return;
                int i = t.idx(sx, sz);
                t.terrainY[i] = (short) site.baseY();
                t.structureTopY[i] = 0;
                t.eaveY[i] = 0;
                t.flags2[i] = 0;
                boolean keepRunway = (t.flags[i] & TileData.FLAG_ROAD) != 0 && (t.surfaceAt(sx, sz) == Surface.RUNWAY || t.surfaceAt(sx, sz) == Surface.TAXIWAY || t.surfaceAt(sx, sz) == Surface.ROAD_MARKING);
                if (!keepRunway) t.surface[i] = (byte) site.surface().ordinal();
                t.flags[i] = (byte) ((t.flags[i] | TileData.FLAG_SITE) & ~TileData.FLAG_TREE & ~TileData.FLAG_BUILDING & ~TileData.FLAG_BUILDING_WALL & ~TileData.FLAG_ESTIMATED & ~TileData.FLAG_WATER);
            });
        }
        return t;
    }

    private interface SampleFn { void at(int sx, int sz, double x, double z); }

    private static void forEachSample(TileData t, double[] bb, SampleFn fn) {
        TileKey k = t.key;
        int step = k.step();
        int sx0 = Math.max(0, (int) Math.floor((bb[0] - k.minX()) / step)), sz0 = Math.max(0, (int) Math.floor((bb[1] - k.minZ()) / step));
        int sx1 = Math.min(t.n - 1, (int) Math.floor((bb[2] - k.minX()) / step)), sz1 = Math.min(t.n - 1, (int) Math.floor((bb[3] - k.minZ()) / step));
        for (int sz = sz0; sz <= sz1; sz++) for (int sx = sx0; sx <= sx1; sx++)
            fn.at(sx, sz, k.minX() + sx * step + step / 2.0, k.minZ() + sz * step + step / 2.0);
    }

    private static long mix(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xD6E8FEB86659FD93L);
        h ^= h >>> 32; h *= 0xff51afd7ed558ccdL; h ^= h >>> 29;
        return h & Long.MAX_VALUE;
    }
}
