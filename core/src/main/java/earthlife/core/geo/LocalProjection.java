package earthlife.core.geo;

/**
 * Maps WGS84 coordinates to Minecraft block coordinates with 1 block = 1 meter horizontally.
 *
 * Uses an exact ECEF -> local East-North-Up tangent plane anchored at a region origin. Minecraft
 * X = east, Minecraft Z = south (= -north). This is accurate to centimetres within tens of km of
 * the origin; error grows with distance because the tangent plane departs from the curved
 * surface (about 8 cm at 1 km, ~8 m at 10 km of "drop", but horizontal distortion stays small).
 *
 * A single flat Minecraft world cannot represent the whole sphere without distortion, so the
 * planet is split into regions, each with its own origin ({@link RegionGrid}). Crossing regions
 * is a dimension/world hand-off, not a continuous coordinate.
 */
public final class LocalProjection {
    private static final double A = 6_378_137.0;              // WGS84 semi-major axis
    private static final double F = 1 / 298.257223563;
    private static final double E2 = F * (2 - F);
    private static final double B = A * (1 - F);
    private static final double EP2 = (A * A - B * B) / (B * B);

    private final GeoPoint origin;
    private final double[] originEcef;
    private final double sinLat, cosLat, sinLon, cosLon;
    /** Minecraft Y used for 0 m elevation. */
    private final int seaLevelY;
    /** Vertical meters per block (1.0 = true scale). */
    private final double verticalScale;

    public LocalProjection(GeoPoint origin, int seaLevelY, double verticalScale) {
        this.origin = origin;
        this.seaLevelY = seaLevelY;
        this.verticalScale = verticalScale;
        double la = Math.toRadians(origin.lat()), lo = Math.toRadians(origin.lon());
        sinLat = Math.sin(la); cosLat = Math.cos(la); sinLon = Math.sin(lo); cosLon = Math.cos(lo);
        originEcef = toEcef(origin.lat(), origin.lon(), 0);
    }

    public GeoPoint origin() { return origin; }
    public int seaLevelY() { return seaLevelY; }

    static double[] toEcef(double latDeg, double lonDeg, double h) {
        double la = Math.toRadians(latDeg), lo = Math.toRadians(lonDeg);
        double n = A / Math.sqrt(1 - E2 * Math.sin(la) * Math.sin(la));
        return new double[]{(n + h) * Math.cos(la) * Math.cos(lo), (n + h) * Math.cos(la) * Math.sin(lo), (n * (1 - E2) + h) * Math.sin(la)};
    }

    /** Returns {east, north, up} in meters relative to origin. */
    public double[] toEnu(GeoPoint p) {
        double[] e = toEcef(p.lat(), p.lon(), p.height());
        double dx = e[0] - originEcef[0], dy = e[1] - originEcef[1], dz = e[2] - originEcef[2];
        double east = -sinLon * dx + cosLon * dy;
        double north = -sinLat * cosLon * dx - sinLat * sinLon * dy + cosLat * dz;
        double up = cosLat * cosLon * dx + cosLat * sinLon * dy + sinLat * dz;
        return new double[]{east, north, up};
    }

    public GeoPoint fromEnu(double east, double north, double up) {
        double dx = -sinLon * east - sinLat * cosLon * north + cosLat * cosLon * up;
        double dy = cosLon * east - sinLat * sinLon * north + cosLat * sinLon * up;
        double dz = cosLat * north + sinLat * up;
        double x = originEcef[0] + dx, y = originEcef[1] + dy, z = originEcef[2] + dz;
        // Bowring's method + 2 refinement iterations: sub-millimetre for terrestrial heights.
        double lon = Math.atan2(y, x);
        double p = Math.hypot(x, y);
        double lat = Math.atan2(z, p * (1 - E2));
        double h = 0;
        for (int i = 0; i < 5; i++) {
            double n = A / Math.sqrt(1 - E2 * Math.sin(lat) * Math.sin(lat));
            h = p / Math.cos(lat) - n;
            lat = Math.atan2(z, p * (1 - E2 * n / (n + h)));
        }
        return new GeoPoint(Math.toDegrees(lat), Math.toDegrees(lon), h);
    }

    /**
     * Horizontal block position for a geographic point. Elevation is handled separately
     * via {@link #elevationToY} because terrain height is "height above sea level", not ENU up.
     */
    public BlockPos2 toBlock(double lat, double lon) {
        double[] enu = toEnu(new GeoPoint(lat, lon, 0));
        return new BlockPos2(enu[0], -enu[1]);
    }

    public GeoPoint fromBlock(double x, double z) {
        // Recover the surface point: iterate so that height above ellipsoid is ~0.
        GeoPoint g = fromEnu(x, -z, 0);
        double[] enu = toEnu(new GeoPoint(g.lat(), g.lon(), 0));
        g = fromEnu(x, -z, enu[2]);
        return new GeoPoint(g.lat(), g.lon(), 0);
    }

    public int elevationToY(double meters) {
        return seaLevelY + (int) Math.round(meters / verticalScale);
    }

    public double yToElevation(double y) {
        return (y - seaLevelY) * verticalScale;
    }

    /** Fractional block coordinates (x east, z south). */
    public record BlockPos2(double x, double z) {
        public int bx() { return (int) Math.floor(x); }
        public int bz() { return (int) Math.floor(z); }
    }
}
