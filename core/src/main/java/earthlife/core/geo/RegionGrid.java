package earthlife.core.geo;

/**
 * Splits the planet into play regions, each with its own {@link LocalProjection} origin, so that
 * per-region distortion stays small. Regions are latitude bands of fixed height, with the number of
 * longitude cells per band shrinking toward the poles (cells stay roughly square in meters).
 * The polar caps (|lat| >= 84) are single regions centred on the pole, avoiding singular longitudes.
 *
 * V0.1 only uses the region containing Williamson, NY; V0.4 uses this grid for global hand-off.
 */
public final class RegionGrid {
    public static final double BAND_DEG = 0.5;          // ~55 km tall bands
    public static final double POLAR_CAP = 84.0;

    public record RegionId(int band, int cell) {
        public String key() { return band + "_" + cell; }
    }

    public static int cellsInBand(int band) {
        double centreLat = -90 + (band + 0.5) * BAND_DEG;
        if (Math.abs(centreLat) >= POLAR_CAP) return 1;
        double cells = 360.0 / BAND_DEG * Math.cos(Math.toRadians(centreLat));
        return Math.max(1, (int) Math.round(cells));
    }

    public static RegionId regionOf(double lat, double lon) {
        if (lat >= POLAR_CAP) return new RegionId(Integer.MAX_VALUE, 0);
        if (lat <= -POLAR_CAP) return new RegionId(Integer.MIN_VALUE, 0);
        int band = (int) Math.floor((lat + 90) / BAND_DEG);
        int cells = cellsInBand(band);
        double l = GeoPoint.normalizeLon(lon) + 180.0;
        int cell = Math.min(cells - 1, (int) Math.floor(l / (360.0 / cells)));
        return new RegionId(band, cell);
    }

    public static GeoPoint originOf(RegionId id) {
        if (id.band() == Integer.MAX_VALUE) return new GeoPoint(90, 0);
        if (id.band() == Integer.MIN_VALUE) return new GeoPoint(-90, 0);
        int cells = cellsInBand(id.band());
        double lat = -90 + (id.band() + 0.5) * BAND_DEG;
        double lon = -180 + (id.cell() + 0.5) * (360.0 / cells);
        return new GeoPoint(lat, lon);
    }
}
