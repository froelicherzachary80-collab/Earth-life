package earthlife.mod.client.map;

import earthlife.core.geo.GeoPoint;
import earthlife.core.geo.LocalProjection;
import earthlife.core.transport.RoadGraph;
import earthlife.core.util.Json;
import earthlife.core.world.ElevationSource;
import earthlife.core.world.Features;
import earthlife.core.world.ProceduralInfill;
import earthlife.core.world.Towns;
import earthlife.mod.EarthLifeMod;
import earthlife.mod.net.Payloads;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;

/**
 * The client's copy of the map: the same bundled OpenStreetMap features and terrain grids the server generates the
 * world from (they ship in the mod jar), positioned with the origin the server sends. Loaded off-thread; queries are
 * bucketed in 512-block cells so a map tile only looks at nearby features.
 */
public final class ClientMapData {
    public record NamedRoad(Features.Road road, String name) {}
    public record Line(List<Features.Pt> pts) {}

    public final Payloads.WorldInfo info;
    public final LocalProjection projection;
    public final Features features;
    public final List<NamedRoad> roads = new ArrayList<>();
    public final List<Line> coastline = new ArrayList<>();
    public final ElevationSource elevation;
    public final List<Towns.Town> towns;
    /** Hamlets and villages from OpenStreetMap place nodes (map labels and search). */
    public final List<Payloads.Poi> places = new ArrayList<>();
    private RoadGraph graph;
    static final int CELL = 512;
    private final Map<Long, List<Object>> cells = new HashMap<>();

    private static volatile CompletableFuture<ClientMapData> loading;
    private static volatile ClientMapData current;

    public static ClientMapData get() { return current; }
    public static boolean loading() { return loading != null && !loading.isDone(); }

    /** Called when the server says which world/region we're in. */
    public static void load(Payloads.WorldInfo info) {
        if (current != null && current.info.region().equals(info.region()) && current.info.originLat() == info.originLat()) { current = current.withInfo(info); return; }
        current = null;
        loading = CompletableFuture.supplyAsync(() -> {
            try { return new ClientMapData(info); }
            catch (Exception e) { EarthLifeMod.LOG.error("EarthLife map data failed to load", e); return null; }
        });
        loading.thenAccept(d -> { current = d; MapRaster.clear(); });
    }

    public static void unload() { current = null; MapRaster.clear(); }

    private ClientMapData withInfo(Payloads.WorldInfo i) { return new ClientMapData(i, this); }

    private ClientMapData(Payloads.WorldInfo i, ClientMapData base) {
        info = i; projection = base.projection; features = base.features; roads.addAll(base.roads); coastline.addAll(base.coastline);
        elevation = base.elevation; towns = base.towns; graph = base.graph; cells.putAll(base.cells); places.addAll(base.places);
    }

    private ClientMapData(Payloads.WorldInfo info) throws Exception {
        this.info = info;
        projection = new LocalProjection(new GeoPoint(info.originLat(), info.originLon()), info.seaLevelYAt0m(), 1.0);
        String json = info.bundledData() ? text("regions/western-ny/features.json.gz") : null;
        features = json != null ? Features.parse(json) : new Features("estimated");
        if (json != null) {
            Map<String, Object> root = Json.obj(Json.parse(json));
            Map<String, String> names = new HashMap<>();
            for (Object o : Json.arr(root.get("roads"))) { var m = Json.obj(o); if (m.get("name") != null) names.put(Json.str(m, "id"), Json.str(m, "name")); }
            for (Features.Road r : features.roads) roads.add(new NamedRoad(r, names.get(r.id())));
            for (Object o : Json.arr(root.get("coastline"))) {
                List<Features.Pt> pts = new ArrayList<>();
                for (Object e : Json.arr(Json.obj(o).get("line"))) { var a = Json.arr(e); pts.add(new Features.Pt(Json.num(a.get(0)), Json.num(a.get(1)))); }
                coastline.add(new Line(pts));
            }
        }
        String osmTowns = info.bundledData() ? text("regions/western-ny/osm-towns.json") : null;
        if (osmTowns != null) for (Object o : Json.arr(Json.obj(Json.parse(osmTowns)).get("towns"))) {
            var m = Json.obj(o);
            var b = projection.toBlock(Json.num(m.get("lat")), Json.num(m.get("lon")));
            places.add(new Payloads.Poi("place:" + Json.str(m, "kind"), Json.str(m, "name"), b.x(), b.z()));
        }
        String townsJson = info.bundledData() ? text("towns.json") : null;
        towns = townsJson != null ? Towns.load(townsJson) : List.of(new Towns.Town(info.startTown(), Towns.Kind.TOWN, projection.origin(), 0, false));
        List<ProceduralInfill.Centre> centres = new ArrayList<>();
        for (Towns.Town t : towns) {
            var b = projection.toBlock(t.centre().lat(), t.centre().lon());
            centres.add(new ProceduralInfill.Centre(t.name(), b.x(), b.z(), t.kind().radiusM(), t.kind() == Towns.Kind.CITY));
        }
        features.buildings.addAll(ProceduralInfill.infill(features, centres, 0xEA27L));   // same estimate as the server
        ElevationSource fallback = new ElevationSource.Procedural(info.bundledData() ? 110 : 95, 20, 0xEA27L);
        ElevationSource e = fallback;
        if (info.bundledData()) {
            e = grid("regions/western-ny/dem-region.eldg.gz", e);
            e = grid("regions/western-ny/dem.eldg.gz", e);
        }
        elevation = e;
        for (NamedRoad r : roads) index(r, r.road().line());
        for (Features.Building b : features.buildings) index(b, b.footprint());
        for (Features.Area a : features.areas) index(a, a.ring());
        for (Features.Water w : features.water) index(w, w.ring());
        for (Line l : coastline) index(l, l.pts());
        EarthLifeMod.LOG.info("EarthLife map: {} roads, {} buildings, {} areas, {} water, {} coastline ways loaded", roads.size(), features.buildings.size(),
                features.areas.size(), features.water.size(), coastline.size());
    }

    static String text(String path) throws Exception {
        try (InputStream in = ClientMapData.class.getResourceAsStream("/data/earthlife/" + path)) {
            if (in == null) return null;
            return new String((path.endsWith(".gz") ? new GZIPInputStream(in) : in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static ElevationSource grid(String path, ElevationSource fallback) throws Exception {
        try (InputStream in = ClientMapData.class.getResourceAsStream("/data/earthlife/" + path)) {
            if (in == null) return fallback;
            return ElevationSource.Grid.read(new GZIPInputStream(in), fallback);
        }
    }

    private void index(Object o, List<Features.Pt> pts) {
        if (pts.isEmpty()) return;
        double[] b = Features.bbox(pts, 0);
        for (int cx = Math.floorDiv((int) b[0], CELL); cx <= Math.floorDiv((int) b[2], CELL); cx++)
            for (int cz = Math.floorDiv((int) b[1], CELL); cz <= Math.floorDiv((int) b[3], CELL); cz++)
                cells.computeIfAbsent(key(cx, cz), k -> new ArrayList<>()).add(o);
    }

    static long key(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }

    /** All features whose bounding box touches [x0,x1]x[z0,z1] (no duplicates). */
    public Collection<Object> query(double x0, double z0, double x1, double z1) {
        Set<Object> out = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int cx = Math.floorDiv((int) x0, CELL); cx <= Math.floorDiv((int) x1, CELL); cx++)
            for (int cz = Math.floorDiv((int) z0, CELL); cz <= Math.floorDiv((int) z1, CELL); cz++) {
                var l = cells.get(key(cx, cz));
                if (l != null) out.addAll(l);
            }
        return out;
    }

    public synchronized RoadGraph graph() {
        if (graph == null) {
            Features drivable = new Features("");
            for (NamedRoad r : roads) if (r.road().cls().drivable() || r.road().cls() == Features.RoadClass.PATH) drivable.roads.add(r.road());
            graph = RoadGraph.roads(drivable);
        }
        return graph;
    }

    /** Search towns, POIs from the server, waypoints are added by the screen; plus named streets. */
    public List<Payloads.Poi> search(String q, List<Payloads.Poi> extra) {
        String s = q.toLowerCase(Locale.ROOT).trim();
        List<Payloads.Poi> out = new ArrayList<>();
        if (s.length() < 2) return out;
        for (Payloads.Poi p : extra) if (p.name().toLowerCase(Locale.ROOT).contains(s)) out.add(p);
        for (Payloads.Poi p : places) if (p.name().toLowerCase(Locale.ROOT).contains(s) && out.stream().noneMatch(o -> o.name().equals(p.name()))) out.add(p);
        // Worldwide gazetteer: places outside this world's map data are listed with their real position and coverage.
        for (var pl : earthlife.mod.world.EarthSettings.PLACES) {
            String label = pl.town() + ", " + pl.state();
            if (!label.toLowerCase(Locale.ROOT).contains(s) || out.stream().anyMatch(o -> o.name().startsWith(pl.town()))) continue;
            var b = projection.toBlock(pl.lat(), pl.lon());
            boolean far = Math.hypot(b.x(), b.z()) > 150_000;
            out.add(new Payloads.Poi(far ? "far" : "town", label + (far ? String.format(Locale.ROOT, " (%.4f, %.4f)", pl.lat(), pl.lon()) : ""), b.x(), b.z()));
        }
        Set<String> seenStreets = new HashSet<>();
        for (NamedRoad r : roads) {
            if (r.name() == null || !r.name().toLowerCase(Locale.ROOT).contains(s) || !seenStreets.add(r.name())) continue;
            var mid = r.road().line().get(r.road().line().size() / 2);
            out.add(new Payloads.Poi("street", r.name(), mid.x(), mid.z()));
            if (out.size() > 40) break;
        }
        return out;
    }
}
