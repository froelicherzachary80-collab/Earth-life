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
import earthlife.core.world.PackWorld;
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
    /** Map features. Starter/custom worlds: all of them. Northeast worlds: the cells around players (see {@link #tickPacks}). */
    public final Features features;
    /** V0.4.1 Northeast worlds: the region packs (null for starter/custom worlds). */
    public final boolean packMode;
    public final PackWorld packWorld;
    public static final String OSM_ATTRIBUTION = "Map data (c) OpenStreetMap contributors, ODbL 1.0";
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
    private final List<earthlife.core.build.Blueprint.Placed> structures = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Every generated structure to write into chunks: airport terminals and landmarks. */
    public List<earthlife.core.build.Blueprint.Placed> structures() { return structures; }
    public final List<Towns.Town> towns;
    public final List<Population> populations = new ArrayList<>();
    public RoadGraph roadGraph, railGraph;
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
    public earthlife.mod.life.ShopService shops;

    private EarthLifeServices(MinecraftServer server, earthlife.mod.world.EarthSettings settings) throws IOException {
        this.settings = settings;
        worldDir = server.getWorldPath(LevelResource.ROOT).resolve("earthlife");
        Files.createDirectories(worldDir);
        Path cfgDir = server.getServerDirectory().resolve("config/earthlife");
        Map<String, Object> cfg = readJson(cfgDir.resolve("earthlife.json"), Map.of());
        developerTools = Boolean.TRUE.equals(cfg.get("developerTools"));
        allowLocationSharing = !Boolean.FALSE.equals(cfg.get("allowLocationSharing"));
        packMode = settings.regionPacks();
        bundledData = !packMode && settings.inWesternNy();
        GeoPoint origin = packMode || bundledData ? WESTERN_NY_ORIGIN : new GeoPoint(settings.lat(), settings.lon());
        region = packMode ? "northeast" : bundledData ? "western-ny" : String.format(java.util.Locale.ROOT, "custom_%.3f_%.3f", settings.lat(), settings.lon()).replace('-', 'm');
        // Northeast worlds: one coordinate system for four states (Williamson = 0,0), 1:1 within ~0.1% everywhere
        projection = new LocalProjection(origin, SEA_LEVEL_Y_AT_0M, 1.0, packMode);
        var spawn = projection.toBlock(settings.lat(), settings.lon());
        spawnX = spawn.x(); spawnZ = spawn.z();

        // Map data: a features file in config/earthlife/regions/<region>/ overrides the starter data bundled in the jar.
        Path regionDir = cfgDir.resolve("regions").resolve(region);
        String featuresJson = null;
        if (packMode) {
            Path cache = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("earthlife").resolve("regions");
            String base = System.getProperty("earthlife.regionPackUrl", cfg.get("regionPackUrl") instanceof String u && !u.isBlank() ? u : RegionPacks.DEFAULT_BASE);
            boolean mapsOnline = !Boolean.getBoolean("earthlife.offlineMaps") && !Boolean.TRUE.equals(cfg.get("offlineMaps"));
            RegionPacks packs = RegionPacks.shared(cache, base, mapsOnline);
            if (!packs.loadIndex()) EarthLifeMod.LOG.warn("EarthLife: Northeast map index unavailable ({}); terrain will be estimated until it can be downloaded", packs.errors);
            packWorld = new PackWorld(packs, projection, OSM_ATTRIBUTION, ((Number) cfg.getOrDefault("regionCellBudget", 2_500_000L)).longValue());
            if (settings.options().mappedBuildings() && settings.options().roads() && settings.options().railways()) packWorld.filter = null;
            else packWorld.filter = f -> filtered(f, settings);
        } else packWorld = null;
        if (Files.exists(regionDir.resolve("features.json"))) featuresJson = Files.readString(regionDir.resolve("features.json"));
        else if (bundledData) featuresJson = bundled("regions/western-ny/features.json.gz");
        Features loaded = featuresJson != null ? Features.parse(featuresJson)
                : new Features(packMode ? OSM_ATTRIBUTION : "No map data for this area - estimated terrain and buildings");
        features = filtered(loaded, settings);
        attribution = features.attribution;
        towns = packMode ? northeastTowns(packWorld.packs, cfgDir)
                : bundledData ? Towns.load(resourceOrOverride(cfgDir, "towns.json")) : List.of(new Towns.Town(settings.town(), Towns.Kind.TOWN, origin, 0, false));

        List<ProceduralInfill.Centre> centres = new ArrayList<>();
        for (Towns.Town t : towns) {
            var b = projection.toBlock(t.centre().lat(), t.centre().lon());
            centres.add(new ProceduralInfill.Centre(t.name(), b.x(), b.z(), t.kind().radiusM(), t.kind() == Towns.Kind.CITY));
        }
        if (!packMode && settings.options().estimatedBuildings() && !Boolean.FALSE.equals(cfg.get("estimatedInfill"))) features.buildings.addAll(ProceduralInfill.infill(features, centres, 0xEA27L));

        shops = new earthlife.mod.life.ShopService(features);

        // Elevation: bundled/override DEM grid when available; otherwise gentle estimated hills. "Real terrain" off = flat land.
        ElevationSource fallback = new ElevationSource.Procedural(bundledData ? 110 : 95, 20, 0xEA27L);
        ElevationSource elevation = fallback;
        if (packMode) elevation = packWorld.elevation(fallback);
        else if (Files.exists(regionDir.resolve("dem.eldg"))) elevation = ElevationSource.Grid.load(regionDir.resolve("dem.eldg"), fallback);
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
        generator = new TileGenerator(packMode ? packWorld.index() : new FeatureIndex(features), elevation, projection, 0xEA27L);
        if (packMode && packWorld.packs.ready()) {
            // the cells around the start town before the first chunk: downloads its pack now (logged), not mid-game
            long t0 = System.currentTimeMillis();
            for (int[] c : packWorld.cellsFor(spawnX - 400, spawnZ - 400, spawnX + 400, spawnZ + 400, 0)) packWorld.cell(c[0], c[1]);
            EarthLifeMod.LOG.info("EarthLife: Northeast map around {} ready in {} ms ({})", settings.town(), System.currentTimeMillis() - t0, packWorld.packs.status);
        }

        // Airports: every airport with a runway of 900 m+ gets a generated terminal beside its real runway(s).
        Map<String, Airport> allAirports = settings.transport() ? Airport.loadAll(resourceOrOverride(cfgDir, "airports.json")) : Map.of();
        for (Airport a : allAirports.values()) {
            var ab = projection.toBlock(a.ref().lat(), a.ref().lon());
            if (Math.hypot(ab.x(), ab.z()) > (packMode ? 900_000 : 150_000)) continue;         // outside this world's region
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
        List<earthlife.core.build.Landmarks.Landmark> landmarkList = (bundledData || packMode) && settings.buildings()
                ? earthlife.core.build.Landmarks.load(resourceOrOverride(cfgDir, "landmarks.json")) : List.of();
        for (var lm : landmarkList) {
            var placed = earthlife.core.build.Landmarks.build(lm, projection, elevation);
            landmarks.put(lm.id(), placed);
            structures.add(placed.placed());
            for (var ring : placed.sites()) generator.addSite(new TileGenerator.Site(lm.id(), ring, placed.baseY(), TileData.Surface.CONCRETE));
        }

        // V0.4.1 movie theaters at real OpenStreetMap cinema locations (sites + blueprints, before any tile exists)
        if (packMode) earthlife.mod.cinema.CinemaService.startPacks(this, generator, structures, packWorld);
        else earthlife.mod.cinema.CinemaService.start(this, generator, structures);
        int cinemaCount = earthlife.mod.cinema.CinemaService.get().cinemas.size();

        String dataHash = hash(Json.write(Map.of("cin", String.valueOf(cinemaCount), "lm", landmarks.keySet().toString(), "f", features.roads.size() + "/" + features.buildings.size() + "/" + features.areas.size(),
                "a", airportLayouts.keySet().toString(), "dem", String.valueOf(elevation.getClass().getSimpleName()), "s", settings.toString(), "meta", packMode ? "packs " + packWorld.packs.indexBuilt : String.valueOf(bundled("regions/western-ny/meta.json")), "v", "0.4/gen" + TileGenerator.VERSION)));
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
        if (settings.simulation() && !packMode) buildPopulations();
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
        for (Towns.Town t : towns) buildPopulation(t);
    }

    private void buildPopulation(Towns.Town t) {
        {
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
            if (homes.isEmpty()) return;
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

    /** How far from the origin this world's map data reaches (features beyond are ignored). */
    public double worldRadiusM() { return packMode ? 900_000 : bundledData ? 70_000 : 30_000; }

    /** Cities, towns, villages, hamlets and suburbs of NY, PA, NJ and VT from the region packs' place list. */
    static List<Towns.Town> northeastTowns(RegionPacks packs, Path cfgDir) {
        try {
            byte[] gz = packs.global("places.json.gz", 7L * 24 * 3600_000);
            String json = new String(new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(gz)).readAllBytes(), StandardCharsets.UTF_8);
            List<Towns.Town> out = new ArrayList<>();
            for (Object o : Json.arr(Json.obj(Json.parse(json)).get("places"))) {
                var m = Json.obj(o);
                Towns.Kind k = switch (String.valueOf(m.get("kind"))) {
                    case "CITY" -> Towns.Kind.CITY; case "TOWN" -> Towns.Kind.TOWN; case "VILLAGE", "SUBURB" -> Towns.Kind.VILLAGE; default -> Towns.Kind.HAMLET;
                };
                out.add(new Towns.Town(Json.str(m, "name"), k, new GeoPoint(Json.num(m.get("lat")), Json.num(m.get("lon"))),
                        m.get("population") == null ? 0 : (int) Json.num(m.get("population")), true));
            }
            if (!out.isEmpty()) return out;
        } catch (Exception e) {
            EarthLifeMod.LOG.warn("EarthLife: Northeast place list unavailable ({}); using the bundled Western NY towns", e.toString());
        }
        try { return Towns.load(resourceOrOverride(cfgDir, "towns.json")); } catch (IOException e) { return List.of(); }
    }

    // ------------------------------------------------------------------ Northeast: the map window around players

    private final Set<Long> windowCells = new HashSet<>();
    private final Set<String> populatedTowns = new HashSet<>();
    private final java.util.concurrent.ExecutorService prefetcher = java.util.concurrent.Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "EarthLife-MapPrefetch"); t.setDaemon(true); return t; });

    /**
     * Every 5 s: prefetch map cells within 3 km of each player, and rebuild {@link #features} (shops, routing, residents,
     * jobs, events use it) from the cells within 2.5 km of players.
     */
    public void tickPacks(MinecraftServer server) {
        if (!packMode || server.getTickCount() % 100 != 0) return;
        List<double[]> pos = new ArrayList<>();
        for (var p : server.getPlayerList().getPlayers()) pos.add(new double[]{p.getX(), p.getZ()});
        for (double[] q : pos) packWorld.prefetch(q[0], q[1], 3000, prefetcher);
        Set<Long> want = new HashSet<>();
        List<PackWorld.Cell> cells = new ArrayList<>();
        for (PackWorld.Cell c : packWorld.loaded()) {
            for (double[] q : pos) {
                var near = packWorld.cellsFor(q[0] - 2500, q[1] - 2500, q[0] + 2500, q[1] + 2500, 0);
                if (near.stream().anyMatch(a -> a[0] == c.lat8() && a[1] == c.lon8())) { want.add(((long) c.lat8() << 32) ^ (c.lon8() & 0xffffffffL)); cells.add(c); break; }
            }
        }
        if (want.equals(windowCells)) return;
        windowCells.clear(); windowCells.addAll(want);
        features.roads.clear(); features.buildings.clear(); features.areas.clear(); features.water.clear(); features.names.clear();
        Set<String> seen = new HashSet<>();
        for (PackWorld.Cell c : cells) {
            Features f = c.features();
            for (var b : f.buildings) if (seen.add(b.id())) features.buildings.add(b);
            features.roads.addAll(f.roads); features.areas.addAll(f.areas); features.water.addAll(f.water); features.names.putAll(f.names);
        }
        shops = new earthlife.mod.life.ShopService(features);
        roadGraph = RoadGraph.roads(features);
        railGraph = RoadGraph.rails(features);
        // residents for the towns around players (a town is populated once, from its real houses and workplaces)
        if (settings.simulation()) for (double[] q : pos) {
            var here = projection.fromBlock(q[0], q[1]);
            towns.stream().filter(t -> t.centre().distanceTo(here) < Math.max(2000, t.kind().radiusM())).sorted(Comparator.comparingDouble(t -> t.centre().distanceTo(here))).limit(3)
                    .filter(t -> populatedTowns.add(t.name() + "@" + t.centre())).forEach(t -> buildPopulation(t));
        }
        EarthLifeMod.LOG.info("EarthLife: map window {} cells, {} buildings, {} roads, {} stores, {} populations", cells.size(), features.buildings.size(), features.roads.size(), shops.count(), populations.size());
    }

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
