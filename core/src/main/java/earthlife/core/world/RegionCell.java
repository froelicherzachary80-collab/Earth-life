package earthlife.core.world;

import earthlife.core.geo.LocalProjection;

import java.io.IOException;
import java.util.*;

/**
 * One 1/8 x 1/8 degree cell of a Northeast region pack ("ELC1", written by tools/regionpack/cellfmt.py): OpenStreetMap
 * roads, buildings, land cover, water and points of interest in latitude/longitude (1e-7 degrees), plus a terrain grid
 * with a water mask. {@link #project} turns it into block-coordinate {@link Features} for a world's projection.
 */
public final class RegionCell {
    public static final int CELLS_PER_DEG = 8;
    public static final int E7 = 10_000_000;
    public enum Tier { TERRAIN, RURAL, TOWN, CITY }

    static final Features.RoadClass[] ROAD = Features.RoadClass.values();
    static final String[] ROAD_NAMES = {"MOTORWAY", "PRIMARY", "SECONDARY", "RESIDENTIAL", "SERVICE", "PATH", "RUNWAY", "TAXIWAY", "RAIL"};
    static final String[] COVERS = {"FARMLAND", "ORCHARD", "FOREST", "GRASS", "RESIDENTIAL", "INDUSTRIAL", "BEACH", "WETLAND", "AIRPORT_APRON"};
    public static final String[] AMENITY = {null, "cinema", "theatre", "school", "hospital", "fire_station", "police", "library", "place_of_worship", "restaurant",
            "fast_food", "cafe", "bank", "fuel", "pharmacy", "supermarket", "mall", "townhall", "post_office", "parking", "hotel"};
    public static final String[] POI = {null, "cinema", "station", "bus_station", "airport", "attraction", "museum", "stadium", "park", "university", "hospital", "mall"};
    /** Water surface (metres) of the DEM mask values: 1 sea, 2 Lake Ontario, 3 Lake Erie. */
    public static final double[] MASK_SURFACE = {Double.NaN, 0.0, 74.0, 174.0};

    public record GeoRoad(String id, Features.RoadClass cls, double widthM, String name, int[] lat, int[] lon) {}
    public record GeoBuilding(String id, String kind, int levels, double heightM, double roofHeightM, String roofShape, String colour,
                              String roofColour, String material, String name, String amenity, int[] lat, int[] lon) {}
    public record GeoArea(String id, Features.Landcover cover, List<int[][]> rings) {}
    public record GeoWater(String id, List<int[][]> rings) {}
    public record Poi(String kind, String name, double lat, double lon) {}

    public final int lat8, lon8;
    public final Tier tier;
    public final List<GeoRoad> roads = new ArrayList<>();
    public final List<GeoBuilding> buildings = new ArrayList<>();
    public final List<GeoArea> areas = new ArrayList<>();
    public final List<GeoWater> water = new ArrayList<>();
    public final List<Poi> pois = new ArrayList<>();
    /** Samples per side of the terrain grid (0 = none), south->north rows of west->east decimetres. */
    public final int demN;
    public final short[] dem;
    public final byte[] mask;

    private RegionCell(int lat8, int lon8, Tier tier, int demN, short[] dem, byte[] mask) {
        this.lat8 = lat8; this.lon8 = lon8; this.tier = tier; this.demN = demN; this.dem = dem; this.mask = mask;
    }

    public double south() { return lat8 / (double) CELLS_PER_DEG; }
    public double west() { return lon8 / (double) CELLS_PER_DEG; }
    public static int cellLat(double lat) { return (int) Math.floor(lat * CELLS_PER_DEG); }
    public static int cellLon(double lon) { return (int) Math.floor(lon * CELLS_PER_DEG); }
    public static String name(int lat8, int lon8) { return "c_" + lat8 + "_" + lon8 + ".elc"; }
    public static String packName(int lat8, int lon8) { return "p_" + Math.floorDiv(lat8, 4) + "_" + Math.floorDiv(lon8, 4) + ".zip"; }

    // ------------------------------------------------------------------ parsing

    private static final class R {
        final byte[] b; int i;
        R(byte[] b) { this.b = b; }
        int u8() { return b[i++] & 255; }
        int u16() { int v = ((b[i] & 255) << 8) | (b[i + 1] & 255); i += 2; return v; }
        int i32() { int v = ((b[i] & 255) << 24) | ((b[i + 1] & 255) << 16) | ((b[i + 2] & 255) << 8) | (b[i + 3] & 255); i += 4; return v; }
        long varint() {
            long v = 0; int shift = 0;
            while (true) { int x = b[i++] & 255; v |= (long) (x & 0x7F) << shift; if ((x & 0x80) == 0) return v; shift += 7; }
        }
        long svarint() { long v = varint(); return (v >>> 1) ^ -(v & 1); }
        String utf() { int n = (int) varint(); String s = new String(b, i, n, java.nio.charset.StandardCharsets.UTF_8); i += n; return s; }
    }

    public static RegionCell parse(byte[] data) throws IOException {
        if (data.length < 14 || data[0] != 'E' || data[1] != 'L' || data[2] != 'C' || data[3] != '1') throw new IOException("not an EarthLife region cell");
        try {
            R r = new R(data);
            r.i = 4;
            int version = r.u8();
            if (version != 1) throw new IOException("unsupported cell version " + version);
            int lat8 = r.i32(), lon8 = r.i32();
            Tier tier = Tier.values()[Math.min(3, r.u8())];
            int ns = (int) r.varint();
            String[] strings = new String[ns];
            for (int k = 0; k < ns; k++) strings[k] = r.utf().intern();
            long lat0 = (long) lat8 * E7 / CELLS_PER_DEG, lon0 = (long) lon8 * E7 / CELLS_PER_DEG;
            List<Object[]> roads = new ArrayList<>(), buildings = new ArrayList<>(), areas = new ArrayList<>(), water = new ArrayList<>();
            List<Poi> pois = new ArrayList<>();
            int n = (int) r.varint();
            for (int k = 0; k < n; k++) {
                String id = oid(r); int cls = r.u8(); double w = r.u16() / 10.0; String name = str(strings, r.varint());
                int[][] p = pts(r, lat0, lon0);
                roads.add(new Object[]{new GeoRoad(id, Features.RoadClass.valueOf(ROAD_NAMES[Math.min(cls, ROAD_NAMES.length - 1)]), w, name, p[0], p[1])});
            }
            n = (int) r.varint();
            for (int k = 0; k < n; k++) {
                String id = oid(r); String kind = str(strings, r.varint()); int levels = r.u8(); double h = r.u16() / 10.0, rh = r.u16() / 10.0;
                String rs = str(strings, r.varint()), col = str(strings, r.varint()), rcol = str(strings, r.varint()), mat = str(strings, r.varint());
                String name = str(strings, r.varint()); int am = r.u8();
                int[][] p = pts(r, lat0, lon0);
                buildings.add(new Object[]{new GeoBuilding(id, kind, levels, h, rh, rs, col, rcol, mat, name, am < AMENITY.length ? AMENITY[am] : null, p[0], p[1])});
            }
            n = (int) r.varint();
            for (int k = 0; k < n; k++) {
                String id = oid(r); int cov = r.u8(); int nr = (int) r.varint();
                List<int[][]> rings = new ArrayList<>(nr);
                for (int j = 0; j < nr; j++) rings.add(pts(r, lat0, lon0));
                areas.add(new Object[]{new GeoArea(id, Features.Landcover.valueOf(COVERS[Math.min(cov, COVERS.length - 1)]), rings)});
            }
            n = (int) r.varint();
            for (int k = 0; k < n; k++) {
                String id = oid(r); int nr = (int) r.varint();
                List<int[][]> rings = new ArrayList<>(nr);
                for (int j = 0; j < nr; j++) rings.add(pts(r, lat0, lon0));
                water.add(new Object[]{new GeoWater(id, rings)});
            }
            n = (int) r.varint();
            for (int k = 0; k < n; k++) {
                int kind = r.u8(); String name = str(strings, r.varint());
                double la = (lat0 + r.svarint()) / (double) E7, lo = (lon0 + r.svarint()) / (double) E7;
                pois.add(new Poi(kind < POI.length ? POI[kind] : null, name, la, lo));
            }
            int dn = r.u16();
            short[] dem = null; byte[] mask = null;
            if (dn > 0) {
                dem = new short[dn * dn];
                for (int j = 0; j < dn; j++) {
                    long prev = j > 0 ? dem[(j - 1) * dn] : 0;
                    for (int k = 0; k < dn; k++) { prev += r.svarint(); dem[j * dn + k] = (short) prev; }
                }
                mask = new byte[dn * dn];
                int runs = (int) r.varint(), at = 0;
                for (int k = 0; k < runs; k++) { int len = (int) r.varint(); byte v = (byte) r.u8(); Arrays.fill(mask, at, Math.min(mask.length, at + len), v); at += len; }
            }
            RegionCell c = new RegionCell(lat8, lon8, tier, dn, dem, mask);
            for (Object[] o : roads) c.roads.add((GeoRoad) o[0]);
            for (Object[] o : buildings) c.buildings.add((GeoBuilding) o[0]);
            for (Object[] o : areas) c.areas.add((GeoArea) o[0]);
            for (Object[] o : water) c.water.add((GeoWater) o[0]);
            c.pois.addAll(pois);
            return c;
        } catch (ArrayIndexOutOfBoundsException | IllegalArgumentException e) {
            throw new IOException("corrupt region cell: " + e, e);
        }
    }

    private static final char[] ID_TYPE = {'n', 'w', 'r', 'x'};
    private static String oid(R r) { int t = r.u8(); return ID_TYPE[Math.min(3, t)] + Long.toString(r.varint()); }
    private static String str(String[] s, long k) { return k == 0 || k > s.length ? null : s[(int) k - 1]; }

    private static int[][] pts(R r, long lat0, long lon0) {
        int n = (int) r.varint();
        int[] la = new int[n], lo = new int[n];
        long pl = lat0, po = lon0;
        for (int k = 0; k < n; k++) { pl += r.svarint(); po += r.svarint(); la[k] = (int) pl; lo[k] = (int) po; }
        return new int[][]{la, lo};
    }

    // ------------------------------------------------------------------ terrain

    /** Elevation (m) at a latitude/longitude inside this cell, bilinear; NaN without terrain. */
    public double elevation(double lat, double lon) {
        if (demN < 2) return Double.NaN;
        double gy = (lat - south()) * CELLS_PER_DEG * (demN - 1), gx = (lon - west()) * CELLS_PER_DEG * (demN - 1);
        int x0 = Math.max(0, Math.min(demN - 2, (int) Math.floor(gx))), y0 = Math.max(0, Math.min(demN - 2, (int) Math.floor(gy)));
        double fx = Math.max(0, Math.min(1, gx - x0)), fy = Math.max(0, Math.min(1, gy - y0));
        double a = dem[y0 * demN + x0], b = dem[y0 * demN + x0 + 1], c = dem[(y0 + 1) * demN + x0], d = dem[(y0 + 1) * demN + x0 + 1];
        return ((a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy) / 10.0;
    }

    /** Water surface (m) at a point if the terrain mask says sea or one of the Great Lakes, else NaN. */
    public double waterSurface(double lat, double lon) {
        if (demN < 2) return Double.NaN;
        int gx = (int) Math.round((lon - west()) * CELLS_PER_DEG * (demN - 1)), gy = (int) Math.round((lat - south()) * CELLS_PER_DEG * (demN - 1));
        gx = Math.max(0, Math.min(demN - 1, gx)); gy = Math.max(0, Math.min(demN - 1, gy));
        int m = mask[gy * demN + gx];
        return m > 0 && m < MASK_SURFACE.length ? MASK_SURFACE[m] : Double.NaN;
    }

    // ------------------------------------------------------------------ projection to block coordinates

    /** Block-coordinate features for a world (ids keep their OpenStreetMap ids, so pieces of one way stay one way). */
    public Features project(LocalProjection proj, String attribution) {
        Features f = new Features(attribution);
        Projector p = new Projector(proj, south(), west(), 1.0 / CELLS_PER_DEG);
        for (GeoRoad r : roads) {
            f.roads.add(new Features.Road(r.id, r.cls, r.widthM, line(p, r.lat, r.lon), Features.Provenance.VERIFIED));
            if (r.name != null) f.names.put(r.id, r.name);
        }
        for (GeoBuilding b : buildings) {
            var pts = line(p, b.lat, b.lon);
            if (pts.size() < 3) continue;
            Map<String, Object> st = new HashMap<>();
            if (b.heightM > 0) st.put("height", b.heightM);
            if (b.roofHeightM > 0) st.put("roofHeight", b.roofHeightM);
            if (b.roofShape != null) st.put("roofShape", b.roofShape);
            if (b.colour != null) st.put("colour", b.colour);
            if (b.roofColour != null) st.put("roofColour", b.roofColour);
            if (b.material != null) st.put("material", b.material);
            if (b.name != null) st.put("name", b.name);
            String kind = "cinema".equals(b.amenity) ? "cinema" : b.kind;
            f.buildings.add(new Features.Building(b.id, kind, Math.max(1, b.levels), pts, Features.Provenance.VERIFIED, BuildingStyle.Tags.parse(st)));
        }
        for (GeoArea a : areas) {
            if (a.rings.isEmpty()) continue;
            f.areas.add(new Features.Area(a.id, a.cover, line(p, a.rings.get(0)[0], a.rings.get(0)[1]), Features.Provenance.VERIFIED));
        }
        for (GeoWater w : water) {
            if (w.rings.isEmpty()) continue;
            List<List<Features.Pt>> holes = new ArrayList<>();
            for (int k = 1; k < w.rings.size(); k++) holes.add(line(p, w.rings.get(k)[0], w.rings.get(k)[1]));
            f.water.add(new Features.Water(w.id, line(p, w.rings.get(0)[0], w.rings.get(0)[1]), Features.Provenance.VERIFIED, holes));
        }
        return f;
    }

    static List<Features.Pt> line(Projector p, int[] lat, int[] lon) {
        List<Features.Pt> out = new ArrayList<>(lat.length);
        for (int k = 0; k < lat.length; k++) out.add(p.at(lat[k] / (double) E7, lon[k] / (double) E7));
        return out;
    }

    /**
     * Fast projection inside one cell: the exact projection at a 3 x 3 grid of nodes, interpolated biquadratically.
     * Over a 14 x 10 km cell the difference from the exact projection is below a centimetre.
     */
    static final class Projector {
        final double s, w, h;
        final double[] xs = new double[9], zs = new double[9];
        Projector(LocalProjection p, double south, double west, double size) {
            s = south; w = west; h = size;
            for (int j = 0; j < 3; j++) for (int i = 0; i < 3; i++) {
                var b = p.toBlock(south + j * size / 2, west + i * size / 2);
                xs[j * 3 + i] = b.x(); zs[j * 3 + i] = b.z();
            }
        }
        private static void basis(double t, double[] out) {            // Lagrange basis on nodes 0, 0.5, 1
            out[0] = 2 * (t - 0.5) * (t - 1); out[1] = -4 * t * (t - 1); out[2] = 2 * t * (t - 0.5);
        }
        Features.Pt at(double lat, double lon) {
            double[] by = new double[3], bx = new double[3];
            basis((lat - s) / h, by); basis((lon - w) / h, bx);
            double x = 0, z = 0;
            for (int j = 0; j < 3; j++) for (int i = 0; i < 3; i++) { double k = by[j] * bx[i]; x += k * xs[j * 3 + i]; z += k * zs[j * 3 + i]; }
            return new Features.Pt(x, z);
        }
    }

    public int featureCount() { return roads.size() + buildings.size() + areas.size() + water.size(); }
}
