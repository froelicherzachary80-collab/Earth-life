package earthlife.test;

import earthlife.core.geo.*;

import static earthlife.test.T.*;

public final class GeoTest {
    static final GeoPoint WILLIAMSON = new GeoPoint(43.2237, -77.1861);

    public static void testOriginMapsToZero() {
        var p = new LocalProjection(WILLIAMSON, 63, 1.0);
        var b = p.toBlock(WILLIAMSON.lat(), WILLIAMSON.lon());
        near(b.x(), 0, 1e-6, "x"); near(b.z(), 0, 1e-6, "z");
    }

    public static void testOneBlockIsOneMetre() {
        var p = new LocalProjection(WILLIAMSON, 63, 1.0);
        // 0.01 deg north ~ 1111 m; Minecraft z decreases northward.
        GeoPoint north = new GeoPoint(WILLIAMSON.lat() + 0.01, WILLIAMSON.lon());
        var b = p.toBlock(north.lat(), north.lon());
        near(-b.z(), WILLIAMSON.distanceTo(north), 5, "north distance in blocks equals metres");
        GeoPoint east = new GeoPoint(WILLIAMSON.lat(), WILLIAMSON.lon() + 0.01);
        var e = p.toBlock(east.lat(), east.lon());
        near(e.x(), WILLIAMSON.distanceTo(east), 5, "east distance in blocks equals metres");
        check(e.x() > 0, "east is +x");
    }

    public static void testRoundTripWithin2cmAt30km() {
        var p = new LocalProjection(WILLIAMSON, 63, 1.0);
        for (double[] d : new double[][]{{0.27, 0.0}, {-0.2, 0.35}, {0.05, -0.4}, {0.001, 0.001}}) {
            double lat = WILLIAMSON.lat() + d[0], lon = WILLIAMSON.lon() + d[1];
            var b = p.toBlock(lat, lon);
            var g = p.fromBlock(b.x(), b.z());
            var back = p.toBlock(g.lat(), g.lon());
            near(back.x(), b.x(), 0.02, "x round trip"); near(back.z(), b.z(), 0.02, "z round trip");
            near(g.lat(), lat, 2e-7, "lat"); near(g.lon(), lon, 2e-7, "lon");
        }
    }

    public static void testLongitudeWrapAndAntimeridianInterpolation() {
        near(GeoPoint.normalizeLon(190), -170, 1e-9, "wrap 190");
        near(GeoPoint.normalizeLon(-540), -180, 1e-9, "wrap -540");
        GeoPoint a = new GeoPoint(0, 179), b = new GeoPoint(0, -179);
        GeoPoint mid = a.interpolate(b, 0.5);
        check(Math.abs(Math.abs(mid.lon()) - 180) < 1e-6, "midpoint across antimeridian is at 180, was " + mid.lon());
        near(a.distanceTo(b), 222_390, 200, "2 deg at equator");
    }

    public static void testPolarCapsAreSingleRegions() {
        check(RegionGrid.regionOf(89.9, 10).equals(RegionGrid.regionOf(85, -150)), "north cap single region");
        var r = RegionGrid.regionOf(WILLIAMSON.lat(), WILLIAMSON.lon());
        GeoPoint o = RegionGrid.originOf(r);
        check(o.distanceTo(WILLIAMSON) < 40_000, "Williamson lies within ~40 km of its region origin: " + o.distanceTo(WILLIAMSON));
    }

    public static void testElevationMapping() {
        var p = new LocalProjection(WILLIAMSON, 63, 1.0);
        check(p.elevationToY(129) == 192, "129 m -> y 192");
        near(p.yToElevation(192), 129, 1e-9, "inverse");
    }
}
