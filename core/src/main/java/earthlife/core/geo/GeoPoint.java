package earthlife.core.geo;

/** WGS84 geodetic coordinate. Latitude/longitude in degrees, height in meters above the ellipsoid/MSL. */
public record GeoPoint(double lat, double lon, double height) {
    public static final double EARTH_MEAN_RADIUS_M = 6_371_008.8;

    public GeoPoint {
        if (Double.isNaN(lat) || lat < -90 || lat > 90) throw new IllegalArgumentException("lat out of range: " + lat);
        lon = normalizeLon(lon);
    }

    public GeoPoint(double lat, double lon) { this(lat, lon, 0); }

    /** Wraps longitude into [-180, 180). Handles the antimeridian. */
    public static double normalizeLon(double lon) {
        double l = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        return l;
    }

    /** Great-circle distance (haversine) in meters. */
    public double distanceTo(GeoPoint o) {
        double p1 = Math.toRadians(lat), p2 = Math.toRadians(o.lat);
        double dp = p2 - p1, dl = Math.toRadians(o.lon - lon);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_MEAN_RADIUS_M * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Initial great-circle bearing in degrees [0,360). */
    public double bearingTo(GeoPoint o) {
        double p1 = Math.toRadians(lat), p2 = Math.toRadians(o.lat), dl = Math.toRadians(o.lon - lon);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /** Point at fraction f (0..1) along the great circle to o (spherical slerp). Works across the antimeridian and near poles. */
    public GeoPoint interpolate(GeoPoint o, double f) {
        double p1 = Math.toRadians(lat), l1 = Math.toRadians(lon), p2 = Math.toRadians(o.lat), l2 = Math.toRadians(o.lon);
        double d = distanceTo(o) / EARTH_MEAN_RADIUS_M;
        if (d < 1e-12) return new GeoPoint(lat, lon, height + (o.height - height) * f);
        double a = Math.sin((1 - f) * d) / Math.sin(d), b = Math.sin(f * d) / Math.sin(d);
        double x = a * Math.cos(p1) * Math.cos(l1) + b * Math.cos(p2) * Math.cos(l2);
        double y = a * Math.cos(p1) * Math.sin(l1) + b * Math.cos(p2) * Math.sin(l2);
        double z = a * Math.sin(p1) + b * Math.sin(p2);
        return new GeoPoint(Math.toDegrees(Math.atan2(z, Math.hypot(x, y))), Math.toDegrees(Math.atan2(y, x)),
                height + (o.height - height) * f);
    }
}
