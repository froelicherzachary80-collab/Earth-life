package earthlife.mod;

import earthlife.core.aviation.*;
import earthlife.core.build.AirportLayout;
import earthlife.core.economy.*;
import earthlife.core.geo.*;
import earthlife.core.jobs.JobService;
import earthlife.core.npc.*;
import earthlife.core.transport.RoadGraph;
import earthlife.core.util.Json;
import earthlife.core.world.*;
import earthlife.mod.time.TimeSync;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Per-server EarthLife state. Everything that matters for saves lives inside the world folder
 * (saves/<world>/earthlife/...) so backups and the launcher's save manager capture it.
 *
 * V0.2 startup order matters: towns -> estimated infill -> airport layouts (+ flattened sites) are all decided
 * before the first tile is generated, and the tile cache folder is keyed by a hash of that input, so changing
 * map data can never mix stale cached tiles with new ones.
 */
public final class EarthLifeServices implements AutoCloseable {
    private static EarthLifeServices current;

    public final LocalProjection projection;
    public final String region;
    public final Features features;
    public final TileGenerator generator;
    public final TileStreamer tiles;
    public final Ledger ledger;
    public final RewardService rewards;
    public final Booking booking;
    public final JobService jobs;
    public final Social social;
    public final TimeSync time;
    public final Map<String, Airport> airports = new LinkedHashMap<>();
    public final Map<String, AirportLayout> airportLayouts = new LinkedHashMap<>();
    public final Map<String, earthlife.core.build.Landmarks.Placed> landmarks = new LinkedHashMap<>();
    public final earthlife.core.events.EventEngine events;
    private final List<earthlife.core.build.Blueprint.Placed> structures = new ArrayList<>();

    /** Every generated structure to write into chunks: airport terminals and landmarks. */
    public List<earthlife.core.build.Blueprint.Placed> structures() { return structures; }
    public final List<Towns.Town> towns;
    public final List<Population> populations = new ArrayList<>();
    public final RoadGraph roadGraph, railGraph;
    public final CabinLayout cabin = new CabinLayout(AircraftType.HOPPER);
    public final Path worldDir;
    public final boolean developerTools;
    public final String attribution;

    /** Y of 0 m above sea level. Y 63 (vanilla sea level) = 74 m, the surface of Lake Ontario. */
    public static final int SEA_LEVEL_Y_AT_0M = 63 - 74;
    /** Region origin of the bundled Western NY dataset (features.json coordinates are relative to it). */
    public static final GeoPoint WESTERN_NY_ORIGIN = new GeoPoint(43.2237, -77.1861);

    public final earthlife.mod.world.EarthSettings settings;
    /** Where new players arrive: the chosen start town, snapped later to a safe surface block. */
    public final double spawnX, spawnZ;
    public final boolean bundledData;
    /** Server option (config/earthlife/earthlife.json "allowLocationSharing", default true): players may opt in to appear on others' LifeMaps. */
    public final boolean allowLocationSharing;
    public final earthlife.mod.life.LifeService life;
    public final earthlife.mod.life.ShopService shops;

    private EarthLifeServices(MinecraftServer server, earthlife.mod.world.EarthSettings settings) throws IOException {
        this.settings = settings;
        worldDir = server.getWorldPath(LevelResource.ROOT).resolve("earthlife");
        Files.createDirectories(worldDir);
        Path cfgDir = server.getServerDirectory().resolve("config/earthlife");
        Map<String, Object> cfg = readJson(cfgDir.resolve("earthlife.json"), Map.of());
        developerTools = Boolean.TRUE.equals(cfg.get("developerTools"));
        allowLocationSharing = !Boolean.FALSE.equals(cfg.get("allowLocationSharing"));
        bundledData = settings.inWesternNy();
        GeoPoint origin = bundledData ? WESTERN_NY_ORIGIN : new GeoPoint(settings.lat(), settings.lon());
        region = bundledData ? "western-ny" : String.format(java.util.Locale.ROOT, "custom_%.3f_%.3f", settings.lat(), settings.lon()).replace('-', 'm');
        projection = new LocalProjection(origin, SEA_LEVEL_Y_AT_0M, 1.0);
        var spawn = projection.toBlock(settings.lat(), settings.lon());
        spawnX = spawn.x(); spawnZ = spawn.z();

        // Map data: a features file in config/earthlife/regions/<region>/ overrides the starter data bundled in the jar.
        Path regionDir = cfgDir.resolve("regions").resolve(region);
        String featuresJson = null;
        if (Files.exists(regionDir.resolve("features.json"))) featuresJson = Files.readString(regionDir.resolve("features.json"));
        else if (bundledData) featuresJson = bundled("regions/western-ny/features.json.gz");
        Features loaded = featuresJson != null ? Features.parse(featuresJson) : new Features("No map data for this area - estimated terrain and buildings");
        features = filtered(loaded, settings);
        attribution = features.attribution;
        towns = bundledData ? Towns.load(resourceOrOverride(cfgDir, "towns.json")) : List.of(new Towns.Town(settings.town(), Towns.Kind.TOWN, origin, 0, false));

        List<ProceduralInfill.Centre> centres = new ArrayList<>();
        for (Towns.Town t : towns) {
            var b = projection.toBlock(t.centre().lat(), t.centre().lon());
            centres.add(new ProceduralInfill.Centre(t.name(), b.x(), b.z(), t.kind().radiusM(), t.kind() == Towns.Kind.CITY));
        }
        if (settings.options().estimatedBuildings() && !Boolean.FALSE.equals(cfg.get("estimatedInfill"))) features.buildings.addAll(ProceduralInfill.infill(features, centres, 0xEA27L));

        shops = new earthlife.mod.life.ShopService(features);

        // Elevation: bundled/override DEM grid when available; otherwise gentle estimated hills. "Real terrain" off = flat land.
        ElevationSource fallback = new ElevationSource.Procedural(bundledData ? 110 : 95, 20, 0xEA27L);
        ElevationSource elevation = fallback;
        if (Files.exists(regionDir.resolve("dem.eldg"))) elevation = ElevationSource.Grid.load(regionDir.resolve("dem.eldg"), fallback);
        else if (bundledData) {
            // Coarse 60 m terrain for the whole region first, then the detailed 20 m starter grid on top of it.
            ElevationSource coarse = loadBundledGrid("regions/western-ny/dem-region.eldg.gz", "dem-region-cache.eldg", fallback);
            elevation = loadBundledGrid("regions/western-ny/dem.eldg.gz", "dem-cache.eldg", coarse);
        }
        if (!settings.realTerrain()) {
            double flat = elevation.elevationAt(spawnX, spawnZ);
            elevation = (x, z) -> flat;
        }
        this.elevationSource = elevation;
        generator = new TileGenerator(new FeatureIndex(features), elevation, projection, 0xEA27L);

        // Airports: every airport with a runway of 900 m+ gets a generated terminal beside its real runway(s).
        Map<String, Airport> allAirports = settings.transport() ? Airport.loadAll(resourceOrOverride(cfgDir, "airports.json")) : Map.of();
        for (Airport a : allAirports.values()) {
            var ab = projection.toBlock(a.ref().lat(), a.ref().lon());
            if (Math.hypot(ab.x(), ab.z()) > 150_000) continue;         // outside this world's region
            if (a.mainRunway().lengthM() < 900) { EarthLifeMod.LOG.info("{}: runway under 900 m, no terminal generated", a.icao()); continue; }
            AirportLayout l = new AirportLayout(a, projection, cabin);
            airportLayouts.put(a.icao(), l);
            airports.put(a.icao(), a.withGate(l.stands.get(0).geo()));
            for (var ring : l.siteRings()) generator.addSite(new TileGenerator.Site(a.icao(), ring, l.baseY, TileData.Surface.CONCRETE));
            for (Airport.Runway r : a.runways()) {
                var t0 = projection.toBlock(r.threshold().lat(), r.threshold().lon()); var t1 = projection.toBlock(r.end().lat(), r.end().lon());
                double len = Math.hypot(t1.x() - t0.x(), t1.z() - t0.z()), nx = -(t1.z() - t0.z()) / len, nz = (t1.x() - t0.x()) / len, w = r.widthM() / 2 + 8;
                generator.addSite(new TileGenerator.Site(a.icao() + "-" + r.ident(), List.of(
                        new Features.Pt(t0.x() + nx * w, t0.z() + nz * w), new Features.Pt(t1.x() + nx * w, t1.z() + nz * w),
                        new Features.Pt(t1.x() - nx * w, t1.z() - nz * w), new Features.Pt(t0.x() - nx * w, t0.z() - nz * w)), l.baseY, TileData.Surface.GRASS));
            }
        }

        for (var a : airportLayouts.values()) structures.add(a.placed);
        List<earthlife.core.build.Landmarks.Landmark> landmarkList = bundledData && settings.buildings()
                ? earthlife.core.build.Landmarks.load(resourceOrOverride(cfgDir, "landmarks.json")) : List.of();
        for (var lm : landmarkList) {
            var placed = earthlife.core.build.Landmarks.build(lm, projection, elevation);
            landmarks.put(lm.id(), placed);
            structures.add(placed.placed());
            for (var ring : placed.sites()) generator.addSite(new TileGenerator.Site(lm.id(), ring, placed.baseY(), TileData.Surface.CONCRETE));
        }

        String dataHash = hash(Json.write(Map.of("lm", landmarks.keySet().toString(), "f", features.roads.size() + "/" + features.buildings.size() + "/" + features.areas.size(),
                "a", airportLayouts.keySet().toString(), "dem", String.valueOf(elevation.getClass().getSimpleName()), "s", settings.toString(), "meta", String.valueOf(bundled("regions/western-ny/meta.json")), "v", "0.4/gen" + TileGenerator.VERSION)));
        tiles = new TileStreamer(generator, worldDir.resolve("tilecache").resolve(dataHash), new ModificationStore(worldDir.resolve("edits")),
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2), 4096, null);

        ledger = Ledger.open(worldDir.resolve("ledger.jsonl"));
        rewards = new RewardService(ledger, RewardService.Config.defaults(), worldDir.resolve("rewards.json"));
        booking = new Booking(ledger, Booking.PricingConfig.defaults());
        jobs = new JobService(ledger, worldDir.resolve("jobs.json"));
        social = new Social(worldDir.resolve("social.json"));
        time = new TimeSync(worldDir, timeDefaults(settings));

        roadGraph = RoadGraph.roads(features);
        railGraph = RoadGraph.rails(features);
        if (settings.simulation()) buildPopulations();
        life = new earthlife.mod.life.LifeService(this);

        // Real-time events (holidays computed locally; MLB / Ticketmaster / iCal fetched on this PC, cached for offline play).
        @SuppressWarnings("unchecked") Map<String, Object> ev = (Map<String, Object>) cfg.getOrDefault("events", Map.of());
        var d = earthlife.core.events.EventEngine.Config.defaults();
        @SuppressWarnings("unchecked") List<String> teams = (List<String>) ev.getOrDefault("mlbTeams", d.mlbTeams());
        @SuppressWarnings("unchecked") List<String> ics = (List<String>) ev.getOrDefault("ics", List.of());
        List<Integer> sports = new ArrayList<>();
        for (Object o : (List<?>) ev.getOrDefault("mlbSportIds", List.of(11L))) sports.add(((Number) o).intValue());
        boolean eventsOn = settings.options().events();
        var evCfg = new earthlife.core.events.EventEngine.Config(eventsOn && !Boolean.FALSE.equals(ev.get("holidays")), eventsOn && !Boolean.FALSE.equals(ev.get("mlb")),
                teams, sports, eventsOn ? (String) ev.get("ticketmasterKey") : null, ((Number) ev.getOrDefault("radiusMiles", 50L)).intValue(), eventsOn ? ics : List.of(), time.clock().zone());
        var http = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL).connectTimeout(java.time.Duration.ofSeconds(15)).build();
        boolean offline = Boolean.getBoolean("earthlife.offline") || Boolean.TRUE.equals(cfg.get("offline"));
        events = new earthlife.core.events.EventEngine(evCfg, projection.origin(), 90_000, landmarkList, towns, worldDir.resolve("events-cache.json"), url -> {
            if (offline) throw new IOException("offline mode (earthlife.offline) - using cached/holiday events only");
            var resp = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).header("User-Agent", "EarthLife/0.3 (Minecraft mod)")
                    .timeout(java.time.Duration.ofSeconds(30)).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) throw new IOException("HTTP " + resp.statusCode());
            return resp.body();
        });
    }

    /** Residents per town: homes from (mapped or estimated) houses inside the town radius, jobs at local workplaces. */
    private void buildPopulations() {
        for (Towns.Town t : towns) {
            var c = projection.toBlock(t.centre().lat(), t.centre().lon());
            double r = t.kind().radiusM();
            List<Features.Building> homes = new ArrayList<>();
            List<Population.Workplace> work = new ArrayList<>();
            int shop = 0;
            for (Features.Building b : features.buildings) {
                var p = b.footprint().get(0);
                if (Math.hypot(p.x() - c.x(), p.z() - c.z()) > r) continue;
                if ("commercial".equals(b.kind()) || "retail".equals(b.kind())) {
                    var place = new Npc.Place(b.id(), "store", p.x(), p.z());
                    work.add(new Population.Workplace(place, earthlife.core.jobs.JobType.STORE_CLERK));
                    if (shop++ % 3 == 0) work.add(new Population.Workplace(place, earthlife.core.jobs.JobType.JANITOR));
                } else homes.add(b);
            }
            for (var e : airportLayouts.entrySet()) {
                double[] ci = e.getValue().placed.marker("agent:checkin:1");
                if (Math.hypot(ci[0] - c.x(), ci[2] - c.z()) < r + 8000) {
                    work.add(new Population.Workplace(new Npc.Place(e.getKey() + ":checkin", "airport", ci[0], ci[2]), earthlife.core.jobs.JobType.CHECKIN_AGENT));
                    double[] g = e.getValue().placed.marker("agent:gate:A1");
                    work.add(new Population.Workplace(new Npc.Place(e.getKey() + ":gate", "airport", g[0], g[2]), earthlife.core.jobs.JobType.GATE_AGENT));
                }
            }
            List<Npc.Place> leisure = List.of(new Npc.Place(t.name() + ":centre", t.name() + " centre", c.x(), c.z()));
            // Cap homes so huge cities don't spawn 100k residents in V0.2: the nearest 400 households are simulated.
            homes.sort(Comparator.comparingDouble(b -> Math.hypot(b.footprint().get(0).x() - c.x(), b.footprint().get(0).z() - c.z())));
            populations.add(new Population(0xEA27L, t.name(), homes.subList(0, Math.min(400, homes.size())), work, leisure, 2));
        }
    }

    public Optional<Npc> npc(String id) {
        for (Population p : populations) { var n = p.byId(id); if (n.isPresent()) return n; }
        return Optional.empty();
    }

    public final ElevationSource elevationSource;

    /** Day-length choice from the Customize screen; applied the first time the world opens (then /earthlife time). */
    private static Map<String, Object> timeDefaults(earthlife.mod.world.EarthSettings s) {
        Map<String, Object> m = new HashMap<>();
        switch (s.dayLengthEnum()) {
            case REAL_TIME -> { m.put("mode", "REALTIME"); m.put("minutesPerDay", 1440L); }
            case TWO_HOURS -> { m.put("mode", "SCALED"); m.put("minutesPerDay", 120L); }
            case ONE_HOUR -> { m.put("mode", "SCALED"); m.put("minutesPerDay", 60L); }
            default -> { m.put("mode", "VANILLA"); m.put("minutesPerDay", 20L); }
        }
        m.put("zone", s.inWesternNy() ? "America/New_York" : java.time.ZoneId.systemDefault().getId());
        return m;
    }

    /** Applies the world's building/road toggles to raw map data. */
    private static Features filtered(Features in, earthlife.mod.world.EarthSettings s) {
        var o = s.options();
        if (o.mappedBuildings() && o.roads() && o.railways()) return in;
        Features out = new Features(in.attribution);
        out.areas.addAll(in.areas); out.water.addAll(in.water);
        if (o.mappedBuildings()) out.buildings.addAll(in.buildings);
        for (var r : in.roads) {
            boolean air = r.cls() == Features.RoadClass.RUNWAY || r.cls() == Features.RoadClass.TAXIWAY;
            boolean rail = r.cls() == Features.RoadClass.RAIL;
            if (air || (rail ? o.railways() : o.roads())) out.roads.add(r);
        }
        return out;
    }

    private ElevationSource loadBundledGrid(String resource, String cacheName, ElevationSource fallback) throws IOException {
        byte[] dem = bundledBytes(resource);
        if (dem == null) return fallback;
        Path tmp = worldDir.resolve(cacheName);
        if (!Files.exists(tmp) || Files.size(tmp) != dem.length) AtomicFiles.write(tmp, dem);
        return ElevationSource.Grid.load(tmp, fallback);
    }

    static String bundled(String path) throws IOException {
        byte[] b = bundledBytes(path);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    /** Reads /data/earthlife/<path> from the jar, un-gzipping *.gz. Null when not bundled. */
    static byte[] bundledBytes(String path) throws IOException {
        try (InputStream in = EarthLifeServices.class.getResourceAsStream("/data/earthlife/" + path)) {
            if (in == null) return null;
            InputStream src = path.endsWith(".gz") ? new java.util.zip.GZIPInputStream(in) : in;
            return src.readAllBytes();
        }
    }

    private static String resourceOrOverride(Path cfgDir, String name) throws IOException {
        Path override = cfgDir.resolve(name);
        if (Files.exists(override)) return Files.readString(override);
        try (InputStream in = EarthLifeServices.class.getResourceAsStream("/data/earthlife/" + name)) {
            if (in == null) throw new IOException(name + " missing from mod jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, Object> readJson(Path p, Map<String, Object> def) throws IOException {
        return Files.exists(p) ? Json.obj(Json.parse(Files.readString(p))) : def;
    }

    private static String hash(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 12); }
        catch (Exception e) { return "nohash"; }
    }

    public static synchronized void start(MinecraftServer server, earthlife.mod.world.EarthSettings settings) {
        try { current = new EarthLifeServices(server, settings); }
        catch (IOException e) { throw new IllegalStateException("EarthLife failed to start: " + e.getMessage(), e); }
    }

    public static synchronized void stop() {
        if (current != null) { try { current.close(); } catch (IOException e) { EarthLifeMod.LOG.error("EarthLife save failed", e); } current = null; }
    }

    public static EarthLifeServices get() {
        EarthLifeServices s = current;
        if (s == null) throw new IllegalStateException("EarthLife services not running");
        return s;
    }

    public static boolean running() { return current != null; }

    @Override public void close() throws IOException {
        rewards.save();
        time.save();
        life.store.saveDirty();
        tiles.close();
    }
}
