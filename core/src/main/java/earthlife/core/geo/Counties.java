package earthlife.core.geo;

/** County names for the Western NY starter region from latitude/longitude (approximate straight-line borders). */
public final class Counties {
    private Counties() {}

    public static String of(double lat, double lon) {
        if (lat < 42.6 || lat > 43.5 || lon < -78.3 || lon > -76.6) return "";
        if (lon < -77.375 && lat > 42.95) return "Monroe County";
        if (lon >= -77.375 && lon <= -76.70 && lat >= 42.97) return "Wayne County";
        if (lat < 42.97 && lon > -77.37 && lon < -76.95) return "Ontario County";
        return "";
    }
}
