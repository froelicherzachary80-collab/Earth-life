package earthlife.core.world;

import earthlife.core.world.Features.*;

import java.util.*;

/**
 * Fills gaps in map data with plausible, clearly-labelled ESTIMATED buildings. OSM coverage of rural
 * New York has many roads whose houses were never traced; V0.1 left them empty.
 *
 * Rule: along residential/secondary roads inside a town's built-up radius, place a house lot every
 * ~24 m on each side, set back from the road, oriented to face it - but only where there is no mapped
 * building, road, water or other lot nearby. Near town centres, primary roads get small commercial
 * blocks instead. Deterministic for a given seed, so regenerating gives the same houses.
 */
public final class ProceduralInfill {
    public record Centre(String name, double x, double z, double radius, boolean city) {}

    private ProceduralInfill() {}

    public static List<Building> infill(Features f, List<Centre> centres, long seed) {
        List<Building> placed = new ArrayList<>();
        // Spatial hash of everything that blocks a lot (5 m cells).
        Set<Long> blocked = new HashSet<>();
        for (Building b : f.buildings) markPolygon(blocked, b.footprint(), 4);
        for (Water w : f.water) markPolygon(blocked, w.ring(), 2);
        Set<Long> roadCells = new HashSet<>();
        for (Road r : f.roads) markLine(roadCells, r.line(), r.widthM() / 2 + 2);

        int n = 0;
        for (Road r : f.roads) {
            boolean residential = r.cls() == RoadClass.RESIDENTIAL || r.cls() == RoadClass.SECONDARY;
            boolean primary = r.cls() == RoadClass.PRIMARY;
            if (!residential && !primary) continue;
            List<Pt> line = r.line();
            double carry = 12;
            for (int k = 1; k < line.size(); k++) {
                Pt a = line.get(k - 1), b = line.get(k);
                double len = Math.hypot(b.x() - a.x(), b.z() - a.z());
                if (len < 1) continue;
                double ux = (b.x() - a.x()) / len, uz = (b.z() - a.z()) / len;   // along road
                double nx = -uz, nz = ux;                                        // left normal
                double s = carry;
                for (; s < len; s += 24) {
                    double px = a.x() + ux * s, pz = a.z() + uz * s;
                    Centre c = within(centres, px, pz);
                    if (c == null) continue;
                    boolean commercial = primary && Math.hypot(px - c.x(), pz - c.z()) < c.radius() * (c.city() ? 0.5 : 0.25);
                    if (primary && !commercial) continue;
                    for (int side = -1; side <= 1; side += 2) {
                        double w = commercial ? 18 : 9, d = commercial ? 14 : 11;
                        double setback = r.widthM() / 2 + (commercial ? 3 : 9) + d / 2;
                        double cx = px + nx * side * setback, cz = pz + nz * side * setback;
                        long h = mix(seed, (int) Math.round(cx), (int) Math.round(cz));
                        if (!commercial && (h & 7) == 0) continue;                      // ~1 in 8 lots left empty
                        List<Pt> fp = rect(cx, cz, ux, uz, nx, nz, w + (h >>> 3 & 3), d);
                        if (collides(fp, blocked) || collides(fp, roadCells)) continue;
                        String kind = commercial ? "commercial" : ((h >>> 6 & 15) == 0 ? "barn" : "house");
                        int levels = commercial ? 2 : 1 + (int) (h >>> 10 & 1);
                        Building bd = new Building("est-" + (n++), kind, levels, fp, Provenance.ESTIMATED);
                        placed.add(bd);
                        markPolygon(blocked, fp, 3);
                    }
                }
                carry = s - len;   // keep 24 m spacing across polyline vertices
            }
        }
        return placed;
    }

    private static Centre within(List<Centre> cs, double x, double z) {
        for (Centre c : cs) if (Math.hypot(x - c.x(), z - c.z()) <= c.radius()) return c;
        return null;
    }

    private static List<Pt> rect(double cx, double cz, double ux, double uz, double nx, double nz, double w, double d) {
        double hw = w / 2, hd = d / 2;
        return List.of(new Pt(cx - ux * hw - nx * hd, cz - uz * hw - nz * hd), new Pt(cx + ux * hw - nx * hd, cz + uz * hw - nz * hd),
                new Pt(cx + ux * hw + nx * hd, cz + uz * hw + nz * hd), new Pt(cx - ux * hw + nx * hd, cz - uz * hw + nz * hd));
    }

    private static long cell(double x, double z) { return ((long) Math.floorDiv((int) Math.floor(x), 5) << 32) ^ (Math.floorDiv((int) Math.floor(z), 5) & 0xffffffffL); }

    private static void markPolygon(Set<Long> set, List<Pt> ring, double pad) {
        double[] bb = Features.bbox(ring, pad);
        for (double x = bb[0]; x <= bb[2] + 5; x += 5) for (double z = bb[1]; z <= bb[3] + 5; z += 5) set.add(cell(x, z));
    }

    private static void markLine(Set<Long> set, List<Pt> line, double half) {
        for (int k = 1; k < line.size(); k++) {
            Pt a = line.get(k - 1), b = line.get(k);
            double len = Math.hypot(b.x() - a.x(), b.z() - a.z());
            int steps = (int) Math.ceil(len / 2.5) + 1;
            for (int i = 0; i <= steps; i++) {
                double x = a.x() + (b.x() - a.x()) * i / steps, z = a.z() + (b.z() - a.z()) * i / steps;
                for (double dx = -half; dx <= half; dx += 2.5) for (double dz = -half; dz <= half; dz += 2.5)
                    if (dx * dx + dz * dz <= half * half) set.add(cell(x + dx, z + dz));
            }
        }
    }

    private static boolean collides(List<Pt> fp, Set<Long> set) {
        double[] bb = Features.bbox(fp, 0);
        for (double x = bb[0]; x <= bb[2]; x += 2.5) for (double z = bb[1]; z <= bb[3]; z += 2.5)
            if (Features.pointInRing(fp, x, z) && set.contains(cell(x, z))) return true;
        return false;
    }

    private static long mix(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 31; h *= 0xff51afd7ed558ccdL; h ^= h >>> 29;
        return h & Long.MAX_VALUE;
    }
}
