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
    /** Northeast worlds: the region packs (null in starter/custom worlds) and the state-wide major roads. */
    public final earthlife.core.world.PackWorld packs;
    public final List<NamedRoad> majors = new ArrayList<>();
    private final Map<Long, List<Object>> majorCells = new HashMap<>();
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
        packs = base.packs; majors.addAll(base.majors); majorCells.putAll(base.majorCells);
    }

    private ClientMapData(Payloads.WorldInfo info) throws Exception {
        this.info = info;
        boolean ne = "northeast".equals(info.region());
        projection = new LocalProjection(new GeoPoint(info.originLat(), info.originLon()), info.seaLevelYAt0m(), 1.0, ne);
        if (ne) {
            var rp = earthlife.core.world.RegionPacks.shared(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("earthlife").resolve("regions"),
                    System.getProperty("earthlife.regionPackUrl", earthlife.core.world.RegionPacks.DEFAULT_BASE), !Boolean.getBoolean("earthlife.offlineMaps"));
            rp.loadIndex();
            packs = new earthlife.core.world.PackWorld(rp, projection, "Map data (c) OpenStreetMap contributors, ODbL 1.0", 1_200_000);
            features = new Features("Map data (c) OpenStreetMap contributors, ODbL 1.0");
            elevation = packs.elevation(new ElevationSource.Procedural(110, 20, 0xEA27L));
            List<Towns.Town> ts = new ArrayList<>();
            try {
                byte[] gz = rp.global("places.json.gz", 7L * 24 * 3600_000);
                String pj = new String(new GZIPInputStream(new java.io.ByteArrayInputStream(gz)).readAllBytes(), StandardCharsets.UTF_8);
                for (Object o : Json.arr(Json.obj(Json.parse(pj)).get("places"))) {
                    var m = Json.obj(o);
                    var b = projection.toBlock(Json.num(m.get("lat")), Json.num(m.get("lon")));
                    String kind = Json.str(m, "kind");
                    places.add(new Payloads.Poi("place:" + kind, Json.str(m, "name") + ", " + Json.str(m, "state"), b.x(), b.z()));
                    Towns.Kind k = switch (kind) { case "CITY" -> Towns.Kind.CITY; case "TOWN" -> Towns.Kind.TOWN; case "VILLAGE", "SUBURB" -> Towns.Kind.VILLAGE; default -> Towns.Kind.HAMLET; };
                    ts.add(new Towns.Town(Json.str(m, "name"), k, new GeoPoint(Json.num(m.get("lat")), Json.num(m.get("lon"))), m.get("population") == null ? 0 : (int) Json.num(m.get("population")), true));
                }
            } catch (Exception e) { EarthLifeMod.LOG.warn("EarthLife map: place list unavailable: {}", e.toString()); }
            towns = ts.isEmpty() ? List.of(new Towns.Town(info.startTown(), Towns.Kind.TOWN, projection.origin(), 0, false)) : ts;
            try {
                var mj = earthlife.core.world.RegionCell.parse(rp.global("major.elc", 7L * 24 * 3600_000)).project(projection, "", true);
                for (Features.Road r : mj.roads) {
                    NamedRoad nr = new NamedRoad(r, mj.names.get(r.id()));
                    majors.add(nr);
                    if (r.line().isEmpty()) continue;
                    double[] bb = Features.bbox(r.line(), 0);
                    for (int cx = Math.floorDiv((int) bb[0], 8192); cx <= Math.floorDiv((int) bb[2], 8192); cx++)
                        for (int cz = Math.floorDiv((int) bb[1], 8192); cz <= Math.floorDiv((int) bb[3], 8192); cz++) majorCells.computeIfAbsent(key(cx, cz), k -> new ArrayList<>()).add(nr);
                }
            } catch (Exception e) { EarthLifeMod.LOG.warn("EarthLife map: major roads unavailable: {}", e.toString()); }
            EarthLifeMod.LOG.info("EarthLife map (Northeast 1:1): {} places, {} major roads; streets and buildings load per area", places.size(), majors.size());
            return;
        }
        packs = null;
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
        if (packs != null) return packQuery(x0, z0, x1, z1);
        Set<Object> out = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int cx = Math.floorDiv((int) x0, CELL); cx <= Math.floorDiv((int) x1, CELL); cx++)
            for (int cz = Math.floorDiv((int) z0, CELL); cz <= Math.floorDiv((int) z1, CELL); cz++) {
                var l = cells.get(key(cx, cz));
                if (l != null) out.addAll(l);
            }
        return out;
    }

    // ------------------------------------------------------------------ Northeast packs

    private final Map<Long, Map<Long, List<Object>>> cellIndex = Collections.synchronizedMap(new LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Map<Long, List<Object>>> e) { return size() > 24; }
    });

    /** Map objects of one cell bucketed by 512-block squares (built once per loaded cell). */
    private Map<Long, List<Object>> cellObjects(int lat8, int lon8) {
        long ck = key(lat8, lon8);
        var have = cellIndex.get(ck);
        if (have != null) return have;
        var c = packs.cell(lat8, lon8);
        Map<Long, List<Object>> idx = new HashMap<>();
        if (c != null) {
            Features f = c.features();
            java.util.function.BiConsumer<Object, List<Features.Pt>> add = (o, pts) -> {
                if (pts.isEmpty()) return;
                double[] b = Features.bbox(pts, 0);
                for (int cx = Math.floorDiv((int) b[0], CELL); cx <= Math.floorDiv((int) b[2], CELL); cx++)
                    for (int cz = Math.floorDiv((int) b[1], CELL); cz <= Math.floorDiv((int) b[3], CELL); cz++) idx.computeIfAbsent(key(cx, cz), k -> new ArrayList<>()).add(o);
            };
            for (Features.Road r : f.roads) add.accept(new NamedRoad(r, f.names.get(r.id())), r.line());
            for (Features.Building b : f.buildings) add.accept(b, b.footprint());
            for (Features.Area a : f.areas) add.accept(a, a.ring());
            for (Features.Water w : f.water) add.accept(w, w.ring());
        }
        cellIndex.put(ck, idx);
        return idx;
    }

    private Collection<Object> packQuery(double x0, double z0, double x1, double z1) {
        Set<Object> out = Collections.newSetFromMap(new IdentityHashMap<>());
        double span = Math.max(x1 - x0, z1 - z0);
        if (span <= 4096) {
            // streets and buildings: from the cells under the box (loads them; map tiles are drawn off-thread)
            for (int[] c : packs.cellsFor(x0, z0, x1, z1, 0)) {
                var idx = cellObjects(c[0], c[1]);
                for (int cx = Math.floorDiv((int) x0, CELL); cx <= Math.floorDiv((int) x1, CELL); cx++)
                    for (int cz = Math.floorDiv((int) z0, CELL); cz <= Math.floorDiv((int) z1, CELL); cz++) { var l = idx.get(key(cx, cz)); if (l != null) out.addAll(l); }
            }
        } else {
            // zoomed out: the state-wide major roads only
            for (int cx = Math.floorDiv((int) x0, 8192); cx <= Math.floorDiv((int) x1, 8192); cx++)
                for (int cz = Math.floorDiv((int) z0, 8192); cz <= Math.floorDiv((int) z1, 8192); cz++) { var l = majorCells.get(key(cx, cz)); if (l != null) out.addAll(l); }
        }
        return out;
    }

    /**
     * A road route. Starter worlds: the bundled road graph. Northeast worlds: the major roads of all four states plus
     * every street within 2.5 km of the start and the destination.
     */
    public List<Features.Pt> route(double fx, double fz, double tx, double tz) {
        if (packs == null) return graph().route(fx, fz, tx, tz);
        var g = new earthlife.core.transport.CompactGraph();
        double d = Math.hypot(tx - fx, tz - fz);
        for (NamedRoad r : majors) {
            var cls = r.road().cls();
            if (d > 60_000 && !(cls == Features.RoadClass.MOTORWAY || cls == Features.RoadClass.PRIMARY)) continue;
            g.addWay(r.road().line(), RoadGraph.speedFor(cls));
        }
        for (double[] p : new double[][]{{fx, fz}, {tx, tz}}) {
            var f = packs.featuresNear(p[0], p[1], Math.min(2500, Math.max(800, d / 2)));
            for (Features.Road r : f.roads) if (r.cls().drivable()) g.addWay(r.line(), RoadGraph.speedFor(r.cls()));
        }
        return g.route(fx, fz, tx, tz);
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
            boolean far = Math.hypot(b.x(), b.z()) > (packs != null ? 900_000 : 150_000);
            out.add(new Payloads.Poi(far ? "far" : "town", label + (far ? String.format(Locale.ROOT, " (%.4f, %.4f)", pl.lat(), pl.lon()) : ""), b.x(), b.z()));
        }
        Set<String> seenStreets = new HashSet<>();
        for (NamedRoad r : packs != null ? majors : roads) {
            if (r.name() == null || !r.name().toLowerCase(Locale.ROOT).contains(s) || !seenStreets.add(r.name())) continue;
            var mid = r.road().line().get(r.road().line().size() / 2);
            out.add(new Payloads.Poi("street", r.name(), mid.x(), mid.z()));
            if (out.size() > 40) break;
        }
        return out;
    }
}
