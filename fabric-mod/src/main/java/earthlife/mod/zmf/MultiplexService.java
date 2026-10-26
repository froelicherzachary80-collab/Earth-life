package earthlife.mod.zmf;

import earthlife.core.build.Blueprint;
import earthlife.core.cinema.FormatAllocator;
import earthlife.core.cinema.MovieScheduler;
import earthlife.core.cinema.MultiplexPlanner;
import earthlife.core.cinema.SiteGrid;
import earthlife.core.util.Json;
import earthlife.mod.EarthLifeMod;
import earthlife.mod.EarthLifeServices;
import earthlife.mod.McCompat;
import earthlife.mod.cinema.CinemaContent;
import earthlife.mod.cinema.CinemaScreenEntity;
import earthlife.mod.cinema.CinemaService;
import earthlife.mod.net.Net;
import earthlife.mod.net.Payloads;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.*;

/**
 * ZMF Cinema multiplexes in a world (one instance per running server).
 *
 * Natural generation: the world is divided into grid cells ({@link SiteGrid}); every cell has one candidate site,
 * fixed by the world seed. When a player gets near, the site is checked against the world generator's own terrain
 * noise (no chunks needed): mostly dry land, flat enough, no village or other surface structure nearby; if that fails a
 * few other spots in the cell are tried. A suitable site is built (in budgeted steps, without freezing the server) once
 * its chunks are loaded around a player. Because the plan comes from the seed, the same multiplex is rebuilt the same
 * way in every copy of the world, and everything is saved in &lt;world&gt;/zmfcinema/.
 *
 * Each built multiplex registers its auditoriums with {@link CinemaService}, which runs showings, tickets and screens
 * exactly as for the real-world theaters of Earth worlds. Scheduled screenings come from the film catalog players
 * shared from their own libraries ({@link MovieScheduler}).
 */
public final class MultiplexService {
    private static MultiplexService current;
    public static MultiplexService get() { return current; }

    final MinecraftServer server;
    final ServerLevel level;
    final Path dir;
    final SiteGrid grid;
    final boolean natural, earthWorld;
    final ZoneId zone;
    final Map<String, MultiplexRecord> records = new LinkedHashMap<>();
    final Map<String, MultiplexPlanner.Plan> plans = new HashMap<>();
    final Map<String, RoomRef> rooms = new HashMap<>();                  // aud key -> room
    final Map<String, List<MovieScheduler.Movie>> catalogBy = new LinkedHashMap<>();   // contributor -> titles
    final Deque<MultiplexRecord> queue = new ArrayDeque<>();
    MultiplexBuilder job;
    final Map<String, String> lastSig = new HashMap<>();
    final Map<String, CinemaScreenEntity> signs = new HashMap<>();
    final Map<UUID, String> insideRoom = new HashMap<>();
    final Map<UUID, Long> warned = new HashMap<>();
    final Map<UUID, Long> lastSchedule = new HashMap<>();
    int commandCounter;
    boolean dirty;
    public volatile String lastBuildReport = "";
    /** Tests and admins can hold natural generation (planned sites are still evaluated and can be built on demand). */
    public volatile boolean pauseNatural;

    public boolean naturalEnabled() { return natural; }

    /** Builds a planned site now (loading its chunks), e.g. for /zmfcinema teleport nearest and the tests. */
    public void forceBuild(MultiplexRecord r) {
        if (r == null || r.state == MultiplexRecord.State.BUILT || r.state == MultiplexRecord.State.REJECTED || queue.contains(r) || (job != null && job.rec == r)) return;
        int[] b = placed(r).worldBounds();
        for (int cx = Math.floorDiv(b[0] - 6, 16); cx <= Math.floorDiv(b[2] + 6, 16); cx++)
            for (int cz = Math.floorDiv(b[1] - 6, 16); cz <= Math.floorDiv(b[3] + 6, 16); cz++) level.getChunk(cx, cz);
        queue.addFirst(r);
    }

    public record RoomRef(MultiplexRecord rec, MultiplexPlanner.Room room, CinemaService.Aud aud) {}

    MultiplexService(MinecraftServer server, boolean earthWorld) {
        this.server = server;
        this.level = server.overworld();
        this.dir = server.getWorldPath(LevelResource.ROOT).resolve("zmfcinema");
        this.earthWorld = earthWorld;
        var cfg = ZmfConfig.get();
        this.natural = cfg.naturalGeneration && (!earthWorld || cfg.naturalInEarthWorlds);
        this.grid = new SiteGrid(level.getSeed(), natural ? SiteGrid.Rarity.parse(cfg.rarity) : SiteGrid.Rarity.OFF);
        this.zone = earthWorld && EarthLifeServices.running() ? EarthLifeServices.get().time.clock().zone() : ZoneId.systemDefault();
    }

    // ------------------------------------------------------------------ lifecycle

    public static void start(MinecraftServer server) {
        boolean earth = EarthLifeServices.running();
        if (CinemaService.get() == null) CinemaService.startVanilla();
        MultiplexService s = new MultiplexService(server, earth);
        current = s;
        s.load();
        int built = 0;
        for (var r : s.records.values()) if (r.state == MultiplexRecord.State.BUILT) { s.register(r); built++; }
        // interrupted builds continue
        for (var r : s.records.values()) if (r.state == MultiplexRecord.State.BUILDING) s.queue.add(r);
        EarthLifeMod.LOG.info("ZMF Cinema: {} multiplexes built in this world ({} known sites), natural generation {} ({}), {} catalog titles",
                built, s.records.size(), s.natural ? "on" : "off", ZmfConfig.get().rarity, s.catalog().size());
    }

    public static void stop() {
        if (current != null) { current.save(); current.signs.values().forEach(e -> e.discard()); current.signs.clear(); }
        current = null;
        CinemaService.reset();
    }

    // ------------------------------------------------------------------ persistence

    void load() {
        try {
            Path f = dir.resolve("multiplexes.json");
            if (Files.exists(f)) {
                var root = Json.obj(Json.parse(Files.readString(f)));
                for (Object o : Json.arr(root.get("multiplexes"))) { var r = MultiplexRecord.fromJson(Json.obj(o)); records.put(r.id, r); }
                commandCounter = root.get("counter") == null ? 0 : (int) Json.num(root.get("counter"));
            }
            Path c = dir.resolve("catalog.json");
            if (Files.exists(c)) {
                var root = Json.obj(Json.parse(Files.readString(c)));
                for (var e : Json.obj(root.get("contributors")).entrySet()) {
                    List<MovieScheduler.Movie> l = new ArrayList<>();
                    for (Object o : Json.arr(e.getValue())) l.add(MultiplexRecord.movie(Json.obj(o)));
                    catalogBy.put(e.getKey(), l);
                }
            }
        } catch (Exception e) { EarthLifeMod.LOG.error("ZMF Cinema: could not read zmfcinema/ data", e); }
    }

    public void save() {
        try {
            Files.createDirectories(dir);
            Map<String, Object> root = new LinkedHashMap<>();
            List<Object> l = new ArrayList<>();
            for (var r : records.values()) l.add(r.toJson());
            root.put("version", "0.1"); root.put("counter", (long) commandCounter); root.put("multiplexes", l);
            earthlife.core.world.AtomicFiles.write(dir.resolve("multiplexes.json"), Json.write(root).getBytes(StandardCharsets.UTF_8));
            Map<String, Object> cat = new LinkedHashMap<>();
            Map<String, Object> by = new LinkedHashMap<>();
            catalogBy.forEach((k, v) -> { List<Object> ms = new ArrayList<>(); for (var m : v) ms.add(MultiplexRecord.movie(m)); by.put(k, ms); });
            cat.put("note", "Film titles players shared from their own libraries for the schedules. Sources are references only - no passwords or access tokens.");
            cat.put("contributors", by);
            earthlife.core.world.AtomicFiles.write(dir.resolve("catalog.json"), Json.write(cat).getBytes(StandardCharsets.UTF_8));
            dirty = false;
        } catch (Exception e) { EarthLifeMod.LOG.error("ZMF Cinema: could not save zmfcinema/ data", e); }
    }

    // ------------------------------------------------------------------ plans and placement

    public MultiplexPlanner.Plan plan(MultiplexRecord r) {
        return plans.computeIfAbsent(r.id, k -> {
            MultiplexPlanner.Style st = null;
            try { if (!r.style.isEmpty()) st = MultiplexPlanner.Style.valueOf(r.style); } catch (IllegalArgumentException ignored) {}
            return MultiplexPlanner.plan(r.rooms, r.seed, st);
        });
    }

    public Blueprint.Placed placed(MultiplexRecord r) { return plan(r).bp().place(r.x + 0.5, r.z + 0.5, r.y, r.heading); }

    public Collection<MultiplexRecord> all() { return records.values(); }
    public MultiplexRecord record(String id) { return records.get(id); }
    public RoomRef room(String audKey) { return rooms.get(audKey); }
    public boolean busy() { return job != null || !queue.isEmpty(); }

    /** Terrain check of a candidate site from the world generator's noise (works before the chunks exist). Null = OK. */
    String unsuitable(int x, int z, MultiplexPlanner.Plan plan, int heading, int[] baseOut) {
        var gen = level.getChunkSource().getGenerator();
        var rs = level.getChunkSource().randomState();
        Blueprint.Placed pl = plan.bp().place(x + 0.5, z + 0.5, 0, heading);
        int sea = level.getSeaLevel();
        List<Integer> hs = new ArrayList<>();
        int water = 0, n = 0;
        for (int i = 0; i <= 5; i++) for (int j = 0; j <= 5; j++) {
            double u = -8 + (plan.lengthU() + 8) * i / 5.0, v = -plan.halfWidthV() + 2.0 * plan.halfWidthV() * j / 5.0;
            double[] w = pl.toWorld(u, v);
            int bx = (int) Math.floor(w[0]), bz = (int) Math.floor(w[1]);
            int floor = gen.getBaseHeight(bx, bz, Heightmap.Types.OCEAN_FLOOR_WG, level, rs);
            int surf = gen.getBaseHeight(bx, bz, Heightmap.Types.WORLD_SURFACE_WG, level, rs);
            if (surf > floor + 1 || floor < sea - 1) water++;
            hs.add(floor); n++;
        }
        Collections.sort(hs);
        int p10 = hs.get(n / 10), p90 = hs.get(n * 9 / 10), median = hs.get(n / 2);
        baseOut[0] = median;
        if (water > 3) return "water (" + water + " of " + n + " samples)";
        if (p90 - p10 > 12 || hs.get(n - 1) - hs.get(0) > 24) return "too steep (" + (p90 - p10) + " m spread)";
        String s = structureNear(pl, plan);
        if (s != null) return "next to " + s;
        return null;
    }

    static final Set<String> SURFACE_STRUCTURES = Set.of("villages", "pillager_outposts", "desert_pyramids", "jungle_temples", "igloos", "swamp_huts",
            "woodland_mansions", "ruined_portals", "trail_ruins", "trial_chambers");

    /** A village or other surface structure whose chunks overlap the site (checked from structure placement, before generation). */
    String structureNear(Blueprint.Placed pl, MultiplexPlanner.Plan plan) {
        try {
            var state = level.getChunkSource().getGeneratorState();
            int[] b = pl.worldBounds();
            int cx = Math.floorDiv((b[0] + b[2]) / 2, 16), cz = Math.floorDiv((b[1] + b[3]) / 2, 16);
            int range = Math.max(b[2] - b[0], b[3] - b[1]) / 32 + 2;
            for (var set : state.possibleStructureSets()) {
                String name = set.getRegisteredName();
                String path = name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
                if (!SURFACE_STRUCTURES.contains(path)) continue;
                if (state.hasStructureChunkInRange(set, cx, cz, range)) return path.replace('_', ' ');
            }
        } catch (Exception e) { EarthLifeMod.LOG.debug("ZMF: structure check skipped: {}", e.toString()); }
        return null;
    }

    /** A natural site must not overlap another multiplex (command-generated ones included). */
    String tooClose(int x, int z, MultiplexPlanner.Plan plan) {
        for (var o : records.values()) {
            if (o.state == MultiplexRecord.State.REJECTED) continue;
            double need = plan.lengthU() + plan(o).lengthU() + 40;
            if (Math.hypot(o.x - x, o.z - z) < need) return "too close to " + o.name;
        }
        return null;
    }

    /** The record for a grid cell's site (evaluated on first use), or null if the cell has no buildable site. */
    MultiplexRecord siteRecord(SiteGrid.Site site) {
        MultiplexRecord r = records.get(site.id());
        if (r != null) return r.state == MultiplexRecord.State.REJECTED ? null : r;
        var plan = MultiplexPlanner.plan(site.rooms(), site.seed());
        String why = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            SiteGrid.Site s = attempt == 0 ? site : grid.alternative(site, attempt);
            int[] base = new int[1];
            why = tooClose(s.x(), s.z(), plan);
            if (why == null) why = unsuitable(s.x(), s.z(), plan, s.heading(), base);
            if (why == null) {
                r = new MultiplexRecord();
                r.id = site.id(); r.cellX = site.cx(); r.cellZ = site.cz(); r.x = s.x(); r.z = s.z(); r.y = base[0];
                r.heading = s.heading(); r.rooms = site.rooms(); r.seed = site.seed(); r.style = plan.style().name(); r.name = plan.name();
                r.natural = true; r.state = MultiplexRecord.State.PLANNED;
                records.put(r.id, r); plans.put(r.id, plan); dirty = true;
                return r;
            }
        }
        r = new MultiplexRecord();
        r.id = site.id(); r.cellX = site.cx(); r.cellZ = site.cz(); r.x = site.x(); r.z = site.z(); r.rooms = site.rooms(); r.seed = site.seed();
        r.state = MultiplexRecord.State.REJECTED; r.note = why; r.natural = true;
        records.put(r.id, r); dirty = true;
        return null;
    }

    /** Nearest multiplex: built ones, and planned sites in the grid cells around the position. */
    public MultiplexRecord nearest(double x, double z, boolean includePlanned) {
        MultiplexRecord best = null; double bd = Double.MAX_VALUE;
        for (var r : records.values()) {
            if (r.state == MultiplexRecord.State.REJECTED || (!includePlanned && r.state != MultiplexRecord.State.BUILT)) continue;
            double d = Math.hypot(r.x - x, r.z - z);
            if (d < bd) { bd = d; best = r; }
        }
        if (includePlanned && natural) {
            for (var site : grid.near(x, z, 2)) {
                if (site.distanceTo(x, z) > bd + 400) break;
                var r = siteRecord(site);
                if (r == null) continue;
                double d = Math.hypot(r.x - x, r.z - z);
                if (d < bd) { bd = d; best = r; }
            }
        }
        return best;
    }

    public MultiplexRecord nearestBuilt(double x, double z, double max) {
        var r = nearest(x, z, false);
        return r != null && Math.hypot(r.x - x, r.z - z) <= max + plan(r).lengthU() ? r : null;
    }

    // ------------------------------------------------------------------ building

    /** A multiplex at a player's position, facing away from them (the /zmfcinema generate command). */
    public MultiplexRecord generateAt(ServerPlayer p, int roomCount, MultiplexPlanner.Style style, Long seed) {
        float yaw = p.getYRot();
        int heading = Math.floorMod(Math.round(yaw / 90f) * 90 + 180, 360);       // MC yaw 0 = south; compass heading 0 = north
        double rad = Math.toRadians(yaw);
        int ox = (int) Math.floor(p.getX() - Math.sin(rad) * 12), oz = (int) Math.floor(p.getZ() + Math.cos(rad) * 12);
        MultiplexRecord r = new MultiplexRecord();
        r.id = "c" + (++commandCounter);
        r.x = ox; r.z = oz;
        r.y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ox, oz) - 1;
        r.heading = heading; r.rooms = roomCount;
        r.seed = seed != null ? seed : new Random().nextLong();
        var plan = MultiplexPlanner.plan(roomCount, r.seed, style);
        r.style = plan.style().name(); r.name = plan.name(); r.natural = false; r.state = MultiplexRecord.State.BUILDING;
        records.put(r.id, r); plans.put(r.id, plan); dirty = true;
        queue.addFirst(r);
        return r;
    }

    void tickBuild() {
        if (job == null && !queue.isEmpty()) {
            var r = queue.pollFirst();
            r.state = MultiplexRecord.State.BUILDING; dirty = true;
            job = new MultiplexBuilder(this, r, plan(r), placed(r));
            EarthLifeMod.LOG.info("ZMF Cinema: building {} ({} rooms, {}) at {} {} {}", r.name, r.rooms, r.style, r.x, r.y, r.z);
        }
        if (job == null) return;
        try {
            if (job.step(ZmfConfig.get().buildBlocksPerTick)) {
                var r = job.rec;
                r.state = MultiplexRecord.State.BUILT; r.builtAtMs = System.currentTimeMillis(); dirty = true;
                lastBuildReport = job.report();
                EarthLifeMod.LOG.info("ZMF Cinema: {} built - {}", r.name, lastBuildReport);
                job = null;
                register(r);
                fillProgram(r, MovieScheduler.day(System.currentTimeMillis(), zone));
                save();
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    if (Math.hypot(p.getX() - r.x, p.getZ() - r.z) < 400) {
                        p.sendSystemMessage(Component.literal("§6🎬 " + r.name + "§r - a " + r.rooms + "-screen multiplex - opened nearby."));
                        CinemaService.get().sendAll(p);
                    }
                }
            }
        } catch (Exception e) {
            EarthLifeMod.LOG.error("ZMF Cinema: building " + job.rec.name + " failed", e);
            job.rec.state = MultiplexRecord.State.REJECTED; job.rec.note = "build failed: " + e;
            job = null; dirty = true;
        }
    }

    /** Builds the CinemaService view of a built multiplex: one auditorium per room, in world coordinates. */
    void register(MultiplexRecord r) {
        var plan = plan(r);
        var pl = placed(r);
        List<CinemaService.Aud> auds = new ArrayList<>();
        double[] kiosk = world(pl, r, plan.marker("kiosk")), conc = world(pl, r, plan.marker("concessions"));
        CinemaService.Cinema cin = new CinemaService.Cinema("zmf:" + r.id, r.name, "ZMF", 0, 0, pl, auds, kiosk, conc,
                plan.style().label + " · " + r.rooms + " screens");
        for (var room : plan.rooms()) {
            double[] s = pl.toWorld(room.screenU(), room.screenV());
            double nx = pl.vx * room.normalV(), nz = pl.vz * room.normalV();
            List<BlockPos> seats = new ArrayList<>();
            for (int[] st : room.seats()) { double[] w = pl.toWorld(st[0], st[2]); seats.add(BlockPos.containing(w[0], r.y + st[1], w[1])); }
            int[] bx = room.box();
            double[] a = pl.toWorld(bx[0] - 0.5, bx[2] - 0.5), b = pl.toWorld(bx[3] + 0.5, bx[5] + 0.5);
            double[] box = {Math.min(a[0], b[0]), r.y + bx[1], Math.min(a[1], b[1]), Math.max(a[0], b[0]), r.y + bx[4] + 1, Math.max(a[1], b[1])};
            double[] door = pl.toWorld(room.door()[0], room.door()[2]);
            String key = r.id + "-" + room.index();
            double screenBottom = r.y + room.screenY() - room.screenH() / 2;
            String geom = "kind=screen;fmt=" + room.formats().code() + ";browser=" + room.formats().browser()
                    + ";side=" + fmt((room.u1() - room.u0()) / 2.0 - 0.55) + ";depth=" + fmt(Math.abs(room.v1() - room.v0()) - 1.2)
                    + ";floor=" + fmt(r.y + room.floorY() + 1 - screenBottom) + ";ceil=" + fmt(r.y + room.floorY() + room.height() + 1 - screenBottom)
                    + ";curved=" + (room.curved() ? 1 : 0) + ";mid=" + r.id + ";room=" + (room.index() + 1)
                    + ";box=" + fmt(box[0]) + "," + fmt(box[1]) + "," + fmt(box[2]) + "," + fmt(box[3]) + "," + fmt(box[4]) + "," + fmt(box[5]);
            var aud = new CinemaService.Aud(key, cin, room.index(), room.name(), room.formats().has(FormatAllocator.Format.F4DX),
                    s[0], r.y + room.screenY(), s[1], nx, nz, room.screenW(), room.screenH(), seats, new double[]{door[0], r.y + room.door()[1], door[1]},
                    room.formats().code(), room.formats().browser(), box, geom);
            auds.add(aud);
            rooms.put(key, new RoomRef(r, room, aud));
        }
        CinemaService.get().register(cin);
    }

    static double[] world(Blueprint.Placed pl, MultiplexRecord r, MultiplexPlanner.Marker m) {
        if (m == null) return new double[]{r.x, r.y + 1, r.z};
        double[] w = pl.toWorld(m.u(), m.v());
        return new double[]{w[0], r.y + m.y(), w[1]};
    }

    static String fmt(double d) { return String.format(Locale.ROOT, "%.2f", d); }

    // ------------------------------------------------------------------ ticking

    public void tick(MinecraftServer server) {
        long t = server.getTickCount();
        tickBuild();
        var players = server.getPlayerList().getPlayers();
        if (t % 40 == 0 && natural && !pauseNatural) {
            for (ServerPlayer p : players) {
                if (p.level() != level) continue;
                for (var site : grid.near(p.getX(), p.getZ(), 1)) {
                    if (site.distanceTo(p.getX(), p.getZ()) > ZmfConfig.get().buildDistance + 260) continue;
                    var r = siteRecord(site);
                    if (r == null || r.state != MultiplexRecord.State.PLANNED || queue.contains(r) || (job != null && job.rec == r)) continue;
                    var plan = plan(r);
                    double cx = r.x, cz = r.z;
                    double[] mid = placed(r).toWorld(plan.lengthU() / 2.0, 0);
                    double d = Math.hypot(mid[0] - p.getX(), mid[1] - p.getZ());
                    if (d < ZmfConfig.get().buildDistance && chunksReady(r)) { queue.addLast(r); EarthLifeMod.LOG.info("ZMF Cinema: {} queued ({} m from {})", r.name, Math.round(d), p.getName().getString()); }
                }
            }
        }
        if (t % 20 == 0) { schedules(players, t); signage(players); }
        if (t % 10 == 0) { doors(players); locators(players); }
        if (dirty && t % 600 == 0) save();
    }

    /** Every chunk under the building exists (a player has been near), so building never generates terrain on the server thread. */
    boolean chunksReady(MultiplexRecord r) {
        int[] b = placed(r).worldBounds();
        for (int cx = Math.floorDiv(b[0] - 6, 16); cx <= Math.floorDiv(b[2] + 6, 16); cx++)
            for (int cz = Math.floorDiv(b[1] - 6, 16); cz <= Math.floorDiv(b[3] + 6, 16); cz++) if (!level.hasChunk(cx, cz)) return false;
        return true;
    }

    // ------------------------------------------------------------------ programs and screenings

    /** Every film title shared for this world, filtered by year (no duplicates). */
    public List<MovieScheduler.Movie> catalog() {
        List<MovieScheduler.Movie> all = new ArrayList<>();
        catalogBy.values().forEach(all::addAll);
        int maxYear = java.time.Year.now(zone).getValue() + 1;
        return MovieScheduler.filter(all, ZmfConfig.get().minYear, maxYear);
    }

    /** Today's program of a multiplex; kept once assigned (persistent), Coming Soon rooms filled as films become available. */
    List<MovieScheduler.Movie> fillProgram(MultiplexRecord r, long day) {
        long useDay = ZmfConfig.get().dailyRotation ? day : 0;
        var films = catalog();
        if (r.programDay != useDay || r.program.size() != r.rooms) {
            r.program = new ArrayList<>(MovieScheduler.assign(films, r.rooms, r.seed ^ r.programSalt, useDay));
            r.programDay = useDay; dirty = true;
        } else if (r.program.contains(null) && !films.isEmpty()) {
            Set<String> used = new HashSet<>();
            for (var m : r.program) if (m != null) used.add(m.key());
            var order = MovieScheduler.assign(films, films.size(), r.seed ^ r.programSalt, useDay);
            Iterator<MovieScheduler.Movie> it = order.iterator();
            for (int i = 0; i < r.program.size(); i++) if (r.program.get(i) == null) {
                while (it.hasNext()) { var m = it.next(); if (used.add(m.key())) { r.program.set(i, m); dirty = true; break; } }
            }
        }
        return r.program;
    }

    public MovieScheduler.Movie movieFor(MultiplexRecord r, int room, long now) {
        var man = r.manual.get(room);
        if (man != null) return man;
        var prog = fillProgram(r, MovieScheduler.day(now, zone));
        return room < prog.size() ? prog.get(room) : null;
    }

    /** Presentation format of a film in a room: a 3D file's own layout in 3D rooms, automatic 2D-to-3D otherwise. */
    public static String formatFor(CinemaService.Aud a, MovieScheduler.Movie m) {
        boolean room3d = a.has("3D");
        String f3 = m == null || m.format3d() == null ? "2D" : m.format3d();
        if (room3d) return "2D".equals(f3) ? "AUTO3D" : f3;
        return "2D";
    }

    /** The screening running now in a ZMF room (film started, not ended), as a showing everyone watches in sync. */
    public Optional<CinemaService.Showing> scheduledShowing(String key, long now) {
        RoomRef ref = rooms.get(key);
        if (ref == null) return Optional.empty();
        var r = ref.rec; int idx = ref.room.index();
        String url = r.browserUrl.get(idx);
        if (url != null) return Optional.of(new CinemaService.Showing("Web: " + host(url), "web:" + url, ref.aud.has("3D") ? "AUTO3D" : "2D",
                r.browserSince.getOrDefault(idx, now), 0, "Web cinema", null));
        var m = movieFor(r, idx, now);
        if (m == null) return Optional.empty();
        var cur = MovieScheduler.current(m, now, zone, r.seed, idx);
        if (cur.isEmpty() || now < cur.get().filmStartMs()) return Optional.empty();
        return Optional.of(new CinemaService.Showing(m.title(), m.source(), formatFor(ref.aud, m), cur.get().filmStartMs(), 0, "ZMF schedule", null));
    }

    static String host(String url) {
        try { var h = java.net.URI.create(url).getHost(); return h == null ? url : h.replaceFirst("^www\\.", ""); } catch (Exception e) { return url; }
    }

    ZmfPayloads.RoomInfo info(RoomRef ref, long now) {
        var r = ref.rec; int idx = ref.room.index();
        String url = r.browserUrl.get(idx);
        String fmts = ref.room.formats().label();
        String b = ref.room.formats().browser() == '-' ? "" : String.valueOf(ref.room.formats().browser());
        if (url != null) return new ZmfPayloads.RoomInfo(ref.aud.key(), idx + 1, ref.room.name(), fmts, b, "Web: " + host(url), 0, "", "web:" + url, "web", 0, 0, 0);
        var manualShow = CinemaService.get().showings.get(ref.aud.key());
        if (manualShow != null) return new ZmfPayloads.RoomInfo(ref.aud.key(), idx + 1, ref.room.name(), fmts, b, manualShow.title(), 0, "", manualShow.source(),
                "playing", manualShow.startedAtMs(), 0, 0);
        var m = movieFor(r, idx, now);
        if (m == null) return new ZmfPayloads.RoomInfo(ref.aud.key(), idx + 1, ref.room.name(), fmts, b, "Coming Soon", 0, "", "", "soon", 0, 0, 0);
        var cur = MovieScheduler.current(m, now, zone, r.seed, idx);
        var next = MovieScheduler.next(m, now, zone, r.seed, idx);
        String status = cur.isPresent() ? (now < cur.get().filmStartMs() ? "trailers" : "playing") : "next";
        return new ZmfPayloads.RoomInfo(ref.aud.key(), idx + 1, ref.room.name(), fmts, b, m.title(), m.year(), m.genre(), m.source(), status,
                cur.map(MovieScheduler.Screening::filmStartMs).orElse(0L), cur.map(MovieScheduler.Screening::endMs).orElse(0L),
                next.map(MovieScheduler.Screening::startMs).orElse(0L));
    }

    public ZmfPayloads.Schedule schedule(MultiplexRecord r, long now) {
        List<ZmfPayloads.RoomInfo> l = new ArrayList<>();
        for (var ref : rooms.values()) if (ref.rec == r) l.add(info(ref, now));
        l.sort(Comparator.comparingInt(ZmfPayloads.RoomInfo::number));
        return new ZmfPayloads.Schedule(r.id, r.name, plan(r).style().label, r.rooms, now, l);
    }

    void schedules(List<ServerPlayer> players, long tick) {
        long now = System.currentTimeMillis();
        var cs = CinemaService.get();
        if (cs == null) return;
        for (var r : records.values()) {
            if (r.state != MultiplexRecord.State.BUILT) continue;
            boolean near = false;
            for (ServerPlayer p : players) if (p.level() == level && Math.hypot(p.getX() - r.x, p.getZ() - r.z) < 260) { near = true; break; }
            if (!near) continue;
            // broadcast a room's showing when its screening starts, ends or changes
            for (var ref : rooms.values()) {
                if (ref.rec != r) continue;
                var sh = cs.effective(ref.aud.key());
                String sig = sh == null ? "" : sh.title() + "|" + sh.source() + "|" + sh.startedAtMs() + "|" + sh.pausedAtMs() + "|" + sh.format();
                if (!sig.equals(lastSig.get(ref.aud.key()))) { lastSig.put(ref.aud.key(), sig); cs.broadcast(server, ref.aud.key()); }
            }
            var sched = schedule(r, now);
            for (ServerPlayer p : players) {
                if (p.level() != level || Math.hypot(p.getX() - r.x, p.getZ() - r.z) > 260) continue;
                Long last = lastSchedule.get(p.getUUID());
                if (last != null && tick - last < 100 && tick % 100 != 0) continue;
                Net.send(p, sched);
                lastSchedule.put(p.getUUID(), tick);
            }
        }
    }

    /** Schedules must be resent after a change (catalog, manual film, browser source). */
    void scheduleChanged() { lastSchedule.clear(); lastSig.clear(); }

    // ------------------------------------------------------------------ signage (showtime boards, door signs, marquee)

    void signage(List<ServerPlayer> players) {
        Set<String> wanted = new HashSet<>();
        for (var r : records.values()) {
            if (r.state != MultiplexRecord.State.BUILT) continue;
            var plan = plan(r); var pl = placed(r);
            boolean near = false;
            for (ServerPlayer p : players) if (p.level() == level && Math.hypot(p.getX() - r.x, p.getZ() - r.z) < 140 + plan.lengthU()) { near = true; break; }
            if (!near) continue;
            for (var m : plan.markers()) {
                String kind = m.kind().contains(":") ? m.kind().substring(0, m.kind().indexOf(':')) : m.kind();
                if (!Set.of("board", "facade", "wayfinding", "restroom").contains(kind)) continue;
                String key = kind + ":" + r.id + ":" + m.kind();
                double[] w = pl.toWorld(m.u(), m.v());
                double nx = pl.ux * m.normalU() + pl.vx * m.normalV(), nz = pl.uz * m.normalU() + pl.vz * m.normalV();
                String text = kind.equals("restroom") ? m.kind().substring(m.kind().indexOf(':') + 1) : kind.equals("wayfinding") ? "floor " + (Integer.parseInt(m.kind().substring(m.kind().indexOf(':') + 1)) + 1) : r.name;
                spawnSign(key, w[0], r.y + m.y() - m.h() / 2, w[1], nx, nz, (float) m.w(), (float) m.h(), "kind=" + kind + ";mid=" + r.id + ";text=" + text, r.name);
                wanted.add(key);
            }
            for (var ref : rooms.values()) {
                if (ref.rec != r) continue;
                var room = ref.room;
                double[] w = pl.toWorld(room.sign()[0], room.sign()[2]);
                double nx = -pl.vx * room.normalV(), nz = -pl.vz * room.normalV();            // faces the hallway
                String key = "sign:" + ref.aud.key();
                spawnSign(key, w[0], r.y + room.sign()[1] - 0.6, w[1], nx, nz, 3.0f, 1.2f, "kind=sign;mid=" + r.id + ";aud=" + ref.aud.key() + ";room=" + (room.index() + 1), room.name());
                wanted.add(key);
            }
        }
        signs.entrySet().removeIf(e -> { if (!wanted.contains(e.getKey())) { e.getValue().discard(); return true; } return false; });
    }

    void spawnSign(String key, double x, double y, double z, double nx, double nz, float w, float h, String data, String label) {
        var e = signs.get(key);
        if (e != null && !e.isRemoved()) return;
        level.getChunk(BlockPos.containing(x, y, z));
        CinemaScreenEntity sc = CinemaContent.SCREEN.create(level, EntitySpawnReason.TRIGGERED);
        if (sc == null) return;
        sc.setPos(x, y, z);
        sc.setYRot((float) Math.toDegrees(Math.atan2(-nx, nz)));
        sc.configure(key, w, h, label);
        sc.data(data);
        McCompat.spawnTemporary(level, sc);
        signs.put(key, sc);
    }

    // ------------------------------------------------------------------ the door scans tickets

    RoomRef roomAt(double x, double y, double z) {
        for (var ref : rooms.values()) if (ref.aud.inside(x, y, z)) return ref;
        return null;
    }

    void doors(List<ServerPlayer> players) {
        long now = System.currentTimeMillis();
        var cfg = ZmfConfig.get();
        for (ServerPlayer p : players) {
            if (p.level() != level) continue;
            var ref = roomAt(p.getX(), p.getY(), p.getZ());
            String prev = insideRoom.get(p.getUUID());
            String key = ref == null ? null : ref.aud.key();
            if (Objects.equals(prev, key)) continue;
            if (key == null) { insideRoom.remove(p.getUUID()); continue; }
            var show = CinemaService.get().effective(key);
            boolean needed = cfg.ticketsRequired && show != null && !ZmfEconomy.free(p) && !ZmfEconomy.hasTicket(p, key, now)
                    && (CinemaService.get().showings.get(key) == null || !Objects.equals(CinemaService.get().showings.get(key).owner(), p.getUUID()));
            if (needed) {
                double[] d = ref.aud.door();
                double ox = d[0] - ref.aud.nx() * 1.2, oz = d[2] - ref.aud.nz() * 1.2;
                p.teleportTo(ox, d[1], oz);
                Long w = warned.get(p.getUUID());
                if (w == null || now - w > 4000) {
                    warned.put(p.getUUID(), now);
                    p.sendOverlayMessage(Component.literal("🎟 " + ref.room.name() + " is showing \"" + show.title() + "\" - buy a ticket at a kiosk in the lobby."));
                }
                continue;
            }
            insideRoom.put(p.getUUID(), key);
            p.sendOverlayMessage(Component.literal(show == null ? "🎬 " + ref.room.name() : (ZmfEconomy.free(p) || !cfg.ticketsRequired ? "🎬 " : "🎟 Ticket scanned ✓  ") + ref.room.name() + " · " + show.title()));
        }
    }

    /** Buys a ticket for the screening running now or the next one in a ZMF room. */
    public String buyTicket(ServerPlayer p, String key) throws Exception {
        RoomRef ref = rooms.get(key);
        if (ref == null) return "That room doesn't exist.";
        long now = System.currentTimeMillis();
        var cs = CinemaService.get();
        var show = cs.effective(key);
        String title; long until; String format;
        if (show != null) { title = show.title(); until = now + 4 * 3600_000L; format = show.format(); }
        else {
            var m = movieFor(ref.rec, ref.room.index(), now);
            if (m == null) return ref.room.name() + " has nothing scheduled yet (Coming Soon).";
            var cur = MovieScheduler.current(m, now, zone, ref.rec.seed, ref.room.index());
            var next = cur.isPresent() ? cur : MovieScheduler.next(m, now, zone, ref.rec.seed, ref.room.index());
            if (next.isEmpty()) return "No more screenings of \"" + m.title() + "\" today.";
            title = m.title(); until = next.get().endMs(); format = formatFor(ref.aud, m);
        }
        String paid = cs.charge(p, ref.aud, format, "ticket#" + key + "#" + p.getStringUUID() + "#" + now, "Movie ticket: " + title);
        ItemStack t = new ItemStack(CinemaContent.MOVIE_TICKET);
        t.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Ticket: " + title));
        t.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(List.of(
                Component.literal(ref.rec.name + " · " + ref.room.name()), Component.literal(ref.room.formats().label()))));
        ZmfEconomy.tagTicket(t, key, until);
        p.addItem(t);
        if (ref.aud.has("3D") && !hasGlasses(p)) p.addItem(new ItemStack(CinemaContent.GLASSES_3D));
        return "🎟 Ticket for \"" + title + "\" in " + ref.room.name() + " (" + paid + ").";
    }

    static boolean hasGlasses(ServerPlayer p) {
        if (p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).is(CinemaContent.GLASSES_3D)) return true;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(CinemaContent.GLASSES_3D)) return true;
        return false;
    }

    // ------------------------------------------------------------------ locator and map

    void locators(List<ServerPlayer> players) {
        for (ServerPlayer p : players) {
            if (!(p.getMainHandItem().is(CinemaContent.CINEMA_LOCATOR) || p.getOffhandItem().is(CinemaContent.CINEMA_LOCATOR)) || p.level() != level) continue;
            var r = nearest(p.getX(), p.getZ(), true);
            if (r == null) Net.send(p, new ZmfPayloads.Nearest("", 0, 0, false, 0, false));
            else {
                double[] mid = placed(r).toWorld(-2, 0);
                Net.send(p, new ZmfPayloads.Nearest(r.name.isEmpty() ? "Multiplex" : r.name, mid[0], mid[1], r.state == MultiplexRecord.State.BUILT, r.rooms, true));
            }
        }
    }

    public String locateText(ServerPlayer p) {
        var r = nearest(p.getX(), p.getZ(), true);
        if (r == null) return natural ? "No multiplex found nearby - try further away." : "No multiplexes in this world yet (natural generation is off here). Operators: /zmfcinema generate small|large|mega";
        double[] mid = placed(r).toWorld(-2, 0);
        double dx = mid[0] - p.getX(), dz = mid[1] - p.getZ(), d = Math.hypot(dx, dz);
        String dir = compass(dx, dz);
        return String.format(Locale.ROOT, "🎬 %s · %d screens · %s %s, %.0f blocks (x %.0f, z %.0f)%s", r.name, r.rooms, dir, arrow(dx, dz), d, mid[0], mid[1],
                r.state == MultiplexRecord.State.BUILT ? "" : " · opens when you get close");
    }

    static String compass(double dx, double dz) {
        String[] n = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
        double a = Math.toDegrees(Math.atan2(-dx, dz));          // MC yaw convention: 0 = south, 90 = west
        return n[(int) Math.floorMod(Math.round(a / 45.0), 8)];
    }

    static String arrow(double dx, double dz) {
        String[] a = {"↓", "↙", "←", "↖", "↑", "↗", "→", "↘"};
        return a[(int) Math.floorMod(Math.round(Math.toDegrees(Math.atan2(-dx, dz)) / 45.0), 8)];
    }

    public void giveMap(ServerPlayer p) {
        var r = nearest(p.getX(), p.getZ(), true);
        if (r == null) { p.sendSystemMessage(Component.literal(locateText(p))); return; }
        double[] mid = placed(r).toWorld(plan(r).lengthU() / 2.0, 0);
        ItemStack map = net.minecraft.world.item.MapItem.create(level, (int) mid[0], (int) mid[1], (byte) 2, true, true);
        net.minecraft.world.item.MapItem.renderBiomePreviewMap(level, map);
        net.minecraft.world.level.saveddata.maps.MapItemSavedData.addTargetDecoration(map, BlockPos.containing(mid[0], r.y, mid[1]), "+",
                net.minecraft.world.level.saveddata.maps.MapDecorationTypes.TARGET_X);
        map.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Cinema map: " + r.name));
        p.addItem(map);
        p.sendSystemMessage(Component.literal("🗺 A map to " + r.name + " is in your inventory."));
    }

    // ------------------------------------------------------------------ catalog (titles shared from players' libraries)

    public boolean mayShare(ServerPlayer p) {
        return ZmfConfig.get().anyoneCanShareLibrary || McCompat.isOp(p) || server.isSingleplayer();
    }

    public void receiveCatalog(ServerPlayer p, ZmfPayloads.Catalog c) {
        if (!mayShare(p)) return;
        String who = p.getStringUUID();
        List<MovieScheduler.Movie> l = c.first() ? new ArrayList<>() : new ArrayList<>(catalogBy.getOrDefault(who, List.of()));
        for (var e : c.entries()) {
            if (e.source() == null || e.source().contains("api_key") || e.source().contains("token")) continue;       // never accept credentials
            l.add(new MovieScheduler.Movie(e.source(), e.title(), e.year(), e.genre(), e.runtimeMs(), e.format3d()));
        }
        catalogBy.put(who, l);
        if (c.last()) {
            long day = MovieScheduler.day(System.currentTimeMillis(), zone);
            for (var r : records.values()) if (r.state == MultiplexRecord.State.BUILT) fillProgram(r, day);
            dirty = true; save(); scheduleChanged();
            EarthLifeMod.LOG.info("ZMF Cinema: {} shared {} film titles ({} usable in the schedules)", p.getName().getString(), l.size(), catalog().size());
        }
    }

    // ------------------------------------------------------------------ admin actions

    public String reschedule(MultiplexRecord r, Integer room) {
        // a new program salt reshuffles the films; the layout seed stays (it describes the building that stands)
        if (room == null) { r.manual.clear(); r.programDay = Long.MIN_VALUE; r.programSalt = r.programSalt * 6364136223846793005L + 1442695040888963407L; }
        else r.manual.remove(room);
        fillProgram(r, MovieScheduler.day(System.currentTimeMillis(), zone));
        dirty = true; save(); scheduleChanged();
        return room == null ? "New program for " + r.name + "." : "Room " + (room + 1) + " follows the schedule again.";
    }

    public String setBrowser(RoomRef ref, String url) {
        int idx = ref.room.index();
        if (url == null) { ref.rec.browserUrl.remove(idx); ref.rec.browserSince.remove(idx); }
        else { ref.rec.browserUrl.put(idx, url); ref.rec.browserSince.put(idx, System.currentTimeMillis()); }
        dirty = true; scheduleChanged();
        return url == null ? ref.room.name() + " is back on the film schedule." : ref.room.name() + " now shows " + url;
    }

    /** Opens the ZMF Cinema menu (showtimes of the nearest multiplex) on the player's client. */
    public void openHub(ServerPlayer p, String menu) {
        String tab = "concessions".equals(menu) ? "zmf:snacks" : "zmf:showtimes";
        ZmfLists.send(p, tab);
        Net.send(p, new Payloads.OpenScreen("zmf:" + tab.substring(4)));
    }
}
