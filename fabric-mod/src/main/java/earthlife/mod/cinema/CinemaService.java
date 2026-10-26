package earthlife.mod.cinema;

import earthlife.core.build.Blueprint;
import earthlife.core.build.CinemaLayout;
import earthlife.core.geo.GeoPoint;
import earthlife.core.util.Json;
import earthlife.core.world.BuildingStyle;
import earthlife.core.world.Features;
import earthlife.core.world.TileData;
import earthlife.core.world.TileGenerator;
import earthlife.mod.EarthLifeMod;
import earthlife.mod.EarthLifeServices;
import earthlife.mod.McCompat;
import earthlife.mod.net.Net;
import earthlife.mod.net.Payloads;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Movie theaters at the real locations of OpenStreetMap cinemas (bundled list for New York, Pennsylvania, New Jersey
 * and Vermont). Each one gets a generated theater (lobby, ticket kiosks, concessions, auditoriums with recliners and
 * one 4D motion auditorium) inside its real building, a big screen in every auditorium, and showings that players start
 * from a kiosk. The movie itself is streamed and decoded on each player's own computer (Jellyfin, public-domain films
 * from the Internet Archive, or a link/file the player provides); the server only keeps who started what and when, so
 * everyone in the room sees the same moment of the film.
 */
public final class CinemaService {
    public record Cinema(String id, String name, String brand, double lat, double lon, Blueprint.Placed placed,
                         List<Aud> auds, double[] kiosk, double[] concessions, String address) {}

    /**
     * An auditorium in world coordinates. Screen faces direction (nx, nz). formats: "+"-joined capabilities
     * ("2D", "3D", "4DX", "IMAX", "ScreenX"); browser: ZMF web-cinema slot 'A'-'D' or '-'; box: interior bounds
     * {x0,y0,z0,x1,y1,z1} (null for OpenStreetMap theaters, which use distance instead); geom: extra rendering data for
     * the screen (ScreenX walls, IMAX curve).
     */
    public record Aud(String key, Cinema cinema, int index, String name, boolean motion, double sx, double sy, double sz,
                      double nx, double nz, double w, double h, List<BlockPos> seats, double[] door,
                      String formats, char browser, double[] box, String geom) {
        public Aud(String key, Cinema cinema, int index, String name, boolean motion, double sx, double sy, double sz,
                   double nx, double nz, double w, double h, List<BlockPos> seats, double[] door) {
            this(key, cinema, index, name, motion, sx, sy, sz, nx, nz, w, h, seats, door, motion ? "4DX+3D" : "2D+3D", '-', null, "");
        }
        public boolean has(String fmt) { return ("+" + formats + "+").toLowerCase(Locale.ROOT).contains("+" + fmt.toLowerCase(Locale.ROOT) + "+"); }
        public boolean zmf() { return "ZMF".equals(cinema.brand()); }
        public boolean inside(double x, double y, double z) {
            return box != null && x >= box[0] && x <= box[3] && y >= box[1] - 1 && y <= box[4] + 1 && z >= box[2] && z <= box[5];
        }
    }

    /** A film playing in an auditorium. positionMs at startedAtMs (server clock); pausedAtMs > 0 when paused. */
    public record Showing(String title, String source, String format, long startedAtMs, long pausedAtMs, String ownerName, UUID owner) {
        public long positionMs(long now) { return pausedAtMs > 0 ? pausedAtMs - startedAtMs : now - startedAtMs; }
    }

    public static final int TICKET_LC = 12, TICKET_3D_LC = 15, TICKET_4D_LC = 20;

    private static CinemaService current;
    public final List<Cinema> cinemas = new java.util.concurrent.CopyOnWriteArrayList<>();
    public final Map<String, Aud> auds = new java.util.concurrent.ConcurrentHashMap<>();
    /** Every cinema in the bundled list (lat/lon), placed or not yet (Northeast worlds place them as their map cell loads). */
    public final List<Map<String, Object>> listed = new ArrayList<>();
    public final Map<String, Showing> showings = new HashMap<>();
    private final Map<String, CinemaScreenEntity> screens = new HashMap<>();
    /** Admin override for the 4D season: null = automatic (Oct 29 - Nov 3), true/false = forced. */
    public static Boolean fourDOverride;

    public static CinemaService get() { return current; }

    /** Cinemas for an ordinary (non-Earth) world: starts empty, ZMF multiplexes register their auditoriums as they are built. */
    public static CinemaService startVanilla() { CinemaService c = new CinemaService(); current = c; return c; }

    /** Called when a server stops, so the next world in this session never sees this one's theaters. */
    public static void reset() { if (current != null) current.clear(); current = null; }

    /** Adds a theater built after the world started (ZMF multiplexes). */
    public void register(Cinema cin) {
        cinemas.removeIf(c -> c.id().equals(cin.id()));
        cinemas.add(cin);
        for (Aud a : cin.auds()) auds.put(a.key(), a);
    }

    static boolean earth() { return EarthLifeServices.running(); }

    /** The 4D season only applies to the 4D rooms of real-world (OpenStreetMap) theaters; ZMF 4DX rooms are always open. */
    static boolean seasonal(Aud a) { return a.motion && !a.zmf(); }

    /** Builds the theaters before any tile is generated (their sites level the ground and replace the building). */
    public static CinemaService start(EarthLifeServices s, TileGenerator gen, List<Blueprint.Placed> structures) {
        CinemaService c = new CinemaService();
        current = c;
        if (!s.settings.buildings()) return c;
        List<Map<String, Object>> list = load();
        c.listed.addAll(list);
        int idx = 0;
        for (var m : list) {
            try {
                Cinema cin = c.place(s, gen, m, idx++, s.features);
                if (cin == null) continue;
                c.cinemas.add(cin);
                structures.add(cin.placed);
                for (Aud a : cin.auds) c.auds.put(a.key, a);
            } catch (RuntimeException e) {
                EarthLifeMod.LOG.warn("cinema {} not placed: {}", m.get("name"), e.toString());
            }
        }
        EarthLifeMod.LOG.info("EarthLife cinemas: {} theaters with {} auditoriums in this world (of {} listed)", c.cinemas.size(), c.auds.size(), list.size());
        return c;
    }

    /**
     * Northeast worlds: each cinema is built when the map cell it stands in is loaded (before any of that cell's tiles
     * are generated), so all ~900 theaters in the four states exist without loading the whole map up front.
     */
    public static CinemaService startPacks(EarthLifeServices s, TileGenerator gen, List<Blueprint.Placed> structures, earthlife.core.world.PackWorld pw) {
        CinemaService c = new CinemaService();
        current = c;
        if (!s.settings.buildings()) return c;
        List<Map<String, Object>> list = load();
        c.listed.addAll(list);
        Map<Long, List<Integer>> byCell = new HashMap<>();
        for (int i = 0; i < list.size(); i++) {
            var m = list.get(i);
            long k = ((long) earthlife.core.world.RegionCell.cellLat(Json.num(m.get("lat"))) << 32) ^ (earthlife.core.world.RegionCell.cellLon(Json.num(m.get("lon"))) & 0xffffffffL);
            byCell.computeIfAbsent(k, q -> new ArrayList<>()).add(i);
        }
        pw.onLoad(cell -> {
            var ids = byCell.get(((long) cell.lat8() << 32) ^ (cell.lon8() & 0xffffffffL));
            if (ids == null) return;
            for (int i : ids) {
                try {
                    Cinema cin = c.place(s, gen, list.get(i), i, cell.features());
                    if (cin == null) continue;
                    c.cinemas.add(cin);
                    structures.add(cin.placed);
                    for (Aud a : cin.auds) c.auds.put(a.key, a);
                    EarthLifeMod.LOG.info("cinema placed: {} ({} auditoriums)", cin.name(), cin.auds.size());
                } catch (RuntimeException e) {
                    EarthLifeMod.LOG.warn("cinema {} not placed: {}", list.get(i).get("name"), e.toString());
                }
            }
        });
        EarthLifeMod.LOG.info("EarthLife cinemas: {} listed in NY/PA/NJ/VT; each is built when its map area loads", list.size());
        return c;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> load() {
        try (InputStream in = CinemaService.class.getResourceAsStream("/data/earthlife/northeast/cinemas.json")) {
            if (in == null) return List.of();
            var root = Json.obj(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : Json.arr(root.get("cinemas"))) out.add(Json.obj(o));
            return out;
        } catch (Exception e) {
            EarthLifeMod.LOG.warn("cinema list unreadable: {}", e.toString());
            return List.of();
        }
    }

    private Cinema place(EarthLifeServices s, TileGenerator gen, Map<String, Object> m, int idx, Features src) {
        double lat = Json.num(m.get("lat")), lon = Json.num(m.get("lon"));
        var b = s.projection.toBlock(lat, lon);
        if (Math.hypot(b.x(), b.z()) > s.worldRadiusM()) return null;               // outside this world's map
        String name = Json.str(m, "name") == null ? "Cinema" : Json.str(m, "name");
        // footprint: the cinema's own building outline, else the building it stands in, else a plausible box
        List<Features.Pt> ring = new ArrayList<>();
        if (m.get("ring") != null) for (Object p : Json.arr(m.get("ring"))) {
            var a = Json.arr(p);
            var q = s.projection.toBlock(Json.num(a.get(0)), Json.num(a.get(1)));
            ring.add(new Features.Pt(q.x(), q.z()));
        }
        if (ring.size() < 3) for (Features.Building bd : src.buildings) {
            if (Features.pointInRing(bd.footprint(), b.x(), b.z())) { ring = bd.footprint(); break; }
        }
        BuildingStyle.Obb o = ring.size() >= 3 ? BuildingStyle.obb(ring) : null;
        boolean inMall = o != null && o.halfU() * o.halfV() * 4 > 16000;
        if (o == null || inMall) {
            // a cinema mapped as a point (or inside a big mall): a 60 x 44 m theater centred on it, facing the nearest road
            o = new BuildingStyle.Obb(b.x(), b.z(), 1, 0, 30, 22);
        }
        // the entrance faces the nearest drivable road
        double[][] sides = {{o.ux(), o.uz(), o.halfU(), o.halfV()}, {-o.ux(), -o.uz(), o.halfU(), o.halfV()},
                {-o.uz(), o.ux(), o.halfV(), o.halfU()}, {o.uz(), -o.ux(), o.halfV(), o.halfU()}};
        double best = Double.MAX_VALUE; double[] front = sides[0];
        for (double[] sd : sides) {
            double mx = o.cx() + sd[0] * sd[2], mz = o.cz() + sd[1] * sd[2];
            double d = nearestRoad(src, mx, mz);
            if (d < best) { best = d; front = sd; }
        }
        double nx = front[0], nz = front[1];                    // outward normal of the entrance side
        double depth = front[2] * 2, width = front[3] * 2;
        if (depth < 18 || width < 12) { depth = Math.max(depth, 26); width = Math.max(width, 18); }
        int screens = 0;
        try { screens = Integer.parseInt(String.valueOf(m.getOrDefault("screens", "0")).replaceAll("[^0-9].*", "")); } catch (NumberFormatException ignored) {}
        var layout = CinemaLayout.build(screens, depth, width, true);
        // blueprint origin: middle of the entrance side, 0.5 m inside; +u points into the building
        double ox = o.cx() + nx * (front[2] - 0.5), oz = o.cz() + nz * (front[2] - 0.5);
        double heading = Math.toDegrees(Math.atan2(-nx, nz));        // compass heading of -n (into the building)
        int baseY = s.projection.elevationToY(s.elevationSource.elevationAt(o.cx(), o.cz()));
        Blueprint.Placed placed = layout.bp().place(ox, oz, baseY, heading);
        // level the ground under it and keep the OSM building / trees out of the generated theater
        List<double[]> c = placed.corners(1);
        List<Features.Pt> site = new ArrayList<>();
        for (double[] p : c) site.add(new Features.Pt(p[0], p[1]));
        gen.addSite(new TileGenerator.Site("cinema-" + idx, site, baseY, TileData.Surface.CONCRETE));

        String id = String.valueOf(m.getOrDefault("id", "c" + idx));
        double[] k = placed.toWorld(layout.kiosk()[0], layout.kiosk()[2]);
        double[] cc = placed.toWorld(layout.concessions()[0], layout.concessions()[2]);
        List<Aud> auds = new ArrayList<>();
        Cinema cin = new Cinema(id, name, Json.str(m, "brand"), lat, lon, placed, auds, new double[]{k[0], baseY + 1, k[1]}, new double[]{cc[0], baseY + 1, cc[1]}, Json.str(m, "addr"));
        for (var a : layout.auditoriums()) {
            double[] w = placed.toWorld(a.screenU(), a.screenV());
            List<BlockPos> seats = new ArrayList<>();
            for (int[] st : a.seats()) {
                double[] sw = placed.toWorld(st[0], st[2]);
                seats.add(BlockPos.containing(sw[0], baseY + st[1], sw[1]));
            }
            double[] dw = placed.toWorld(a.door()[0], a.door()[2]);
            auds.add(new Aud(idx + "-" + a.index(), cin, a.index(), a.name(), a.motion(), w[0], baseY + a.screenY(), w[1], placed.ux, placed.uz,
                    a.screenW(), a.screenH(), seats, new double[]{dw[0], baseY + 1, dw[1]}));
        }
        return cin;
    }

    static double nearestRoad(Features src, double x, double z) {
        double best = 400;
        for (var r : src.roads) {
            if (!r.cls().drivable()) continue;
            var l = r.line();
            double[] bb = Features.bbox(l, 0);
            if (x < bb[0] - best || x > bb[2] + best || z < bb[1] - best || z > bb[3] + best) continue;
            for (int i = 1; i < l.size(); i++) best = Math.min(best, Features.distToSegment(x, z, l.get(i - 1), l.get(i)));
        }
        return best;
    }

    // ------------------------------------------------------------------ 4D season

    /** The 4D motion auditoriums open October 29 - November 3 (real date), unless an operator forces them on/off. */
    public static boolean fourDOpen(ZoneId zone) {
        if (fourDOverride != null) return fourDOverride;
        LocalDate d = LocalDate.now(zone);
        return (d.getMonthValue() == 10 && d.getDayOfMonth() >= 29) || (d.getMonthValue() == 11 && d.getDayOfMonth() <= 3);
    }

    public static String fourDOpening(ZoneId zone) {
        LocalDate d = LocalDate.now(zone);
        int year = d.getMonthValue() == 11 && d.getDayOfMonth() > 3 || d.getMonthValue() == 12 ? d.getYear() + 1 : d.getYear();
        return "opens Oct 29, " + year;
    }

    // ------------------------------------------------------------------ ticking: screens near players

    public void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0 || auds.isEmpty()) return;
        ServerLevel level = server.overworld();
        var players = server.getPlayerList().getPlayers();
        for (Aud a : auds.values()) {
            double near = Double.MAX_VALUE;
            for (ServerPlayer p : players) if (p.level() == level) near = Math.min(near, Math.hypot(p.getX() - a.sx, p.getZ() - a.sz));
            CinemaScreenEntity e = screens.get(a.key);
            if (near < 64 && (e == null || e.isRemoved())) {
                level.getChunk(BlockPos.containing(a.sx, a.sy, a.sz));
                CinemaScreenEntity sc = CinemaContent.SCREEN.create(level, EntitySpawnReason.TRIGGERED);
                if (sc == null) continue;
                sc.setPos(a.sx, a.sy - a.h / 2, a.sz);
                sc.setYRot((float) Math.toDegrees(Math.atan2(-a.nx, a.nz)));
                sc.configure(a.key, (float) a.w, (float) a.h, a.cinema.name() + " · " + a.name);
                sc.data(a.geom == null ? "" : a.geom);
                McCompat.spawnTemporary(level, sc);
                screens.put(a.key, sc);
            } else if (near > 112 && e != null) { e.discard(); screens.remove(a.key); }
        }
        // finished showings (after 4 hours) are cleared
        long now = System.currentTimeMillis();
        showings.entrySet().removeIf(en -> en.getValue().pausedAtMs() == 0 && now - en.getValue().startedAtMs() > 4 * 3600_000L && broadcastClear(server, en.getKey()));
    }

    private boolean broadcastClear(MinecraftServer server, String key) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) Net.send(p, new Payloads.CinemaShow(key, "", "", "", 0, false, false, ""));
        return true;
    }

    public void clear() { screens.values().forEach(e -> e.discard()); screens.clear(); }

    // ------------------------------------------------------------------ showings

    public Optional<Aud> nearestAud(double x, double z, double max) {
        return auds.values().stream().filter(a -> Math.hypot(a.sx - x, a.sz - z) < max).min(Comparator.comparingDouble(a -> Math.hypot(a.sx - x, a.sz - z)));
    }

    public Optional<Cinema> nearestCinema(double x, double z) {
        return cinemas.stream().min(Comparator.comparingDouble(c -> Math.hypot(c.kiosk[0] - x, c.kiosk[2] - z)));
    }

    int price(Aud a, String format) { return a.motion ? TICKET_4D_LC : "2D".equals(format) ? TICKET_LC : TICKET_3D_LC; }

    /** Price label for menus: LifeCoins in Earth worlds, emeralds (or free) elsewhere. */
    public String priceLabel(Aud a, String format, ServerPlayer p) {
        if (earth()) return price(a, format) + " LC";
        return earthlife.mod.zmf.ZmfEconomy.label(earthlife.mod.zmf.ZmfEconomy.ticketPrice(a, format), p);
    }

    /** Takes payment for a ticket and returns what was paid ("12 LC", "1 emerald", "free"). */
    public String charge(ServerPlayer p, Aud a, String format, String ref, String what) throws Exception {
        if (earth()) {
            int price = price(a, format);
            EarthLifeServices.get().ledger.debit(p.getStringUUID(), price, "CINEMA", ref, what, p.level().getGameTime());
            return price + " LC";
        }
        return earthlife.mod.zmf.ZmfEconomy.pay(p, earthlife.mod.zmf.ZmfEconomy.ticketPrice(a, format), what);
    }

    /** The manual showing of a room, or else its scheduled ZMF screening (if one is running now). */
    public Showing effective(String key) {
        Showing sh = showings.get(key);
        if (sh != null) return sh;
        var ms = earthlife.mod.zmf.MultiplexService.get();
        return ms == null ? null : ms.scheduledShowing(key, System.currentTimeMillis()).orElse(null);
    }

    /** Starts a film (the player picked it in their movie picker) and sells them a ticket. */
    public String start(ServerPlayer p, String key, String format, String source, String title) throws Exception {
        Aud a = auds.get(key);
        if (a == null) return "That auditorium doesn't exist.";
        if (seasonal(a) && earth() && !fourDOpen(EarthLifeServices.get().time.clock().zone())) return "The 4D motion auditorium " + fourDOpening(EarthLifeServices.get().time.clock().zone()) + ".";
        Showing cur = showings.get(key);
        if (cur != null && cur.positionMs(System.currentTimeMillis()) < 4 * 3600_000L && !Objects.equals(cur.owner(), p.getUUID()) && !McCompat.isOp(p))
            return "\"" + cur.title() + "\" is already playing in " + a.name + ". Join it, or pick another auditorium.";
        String paid = charge(p, a, format, "ticket#" + key + "#" + p.getStringUUID() + "#" + System.currentTimeMillis() / 1000, "Movie ticket: " + title);
        showings.put(key, new Showing(title, source, format, System.currentTimeMillis(), 0, p.getName().getString(), p.getUUID()));
        giveTicket(p, a, title, format);
        broadcast(p.level().getServer(), key);
        waypoint(p, a);
        return "Ticket bought (" + paid + "): \"" + title + "\" in " + a.name + " at " + a.cinema.name() + ". Find your seat - the film is starting!";
    }

    /** Buys a ticket for the film already playing. */
    public String join(ServerPlayer p, String key) throws Exception {
        Aud a = auds.get(key);
        Showing sh = effective(key);
        if (a == null || sh == null) return "Nothing is playing there right now.";
        String paid = charge(p, a, sh.format(), "ticket#" + key + "#" + p.getStringUUID() + "#" + sh.startedAtMs(), "Movie ticket: " + sh.title());
        giveTicket(p, a, sh.title(), sh.format());
        Net.send(p, payload(key));
        waypoint(p, a);
        return "Ticket bought (" + paid + ") for \"" + sh.title() + "\" in " + a.name + ".";
    }

    public String control(ServerPlayer p, String key, String what) {
        Showing sh = showings.get(key);
        if (sh == null) return effective(key) != null ? "This is a scheduled screening - it can't be paused (start your own showing from the kiosk)." : "Nothing is playing there.";
        if (!Objects.equals(sh.owner(), p.getUUID()) && !McCompat.isOp(p)) return "Only " + sh.ownerName() + " (who started it) or an operator can do that.";
        long now = System.currentTimeMillis();
        switch (what) {
            case "pause" -> { if (sh.pausedAtMs() == 0) showings.put(key, new Showing(sh.title(), sh.source(), sh.format(), sh.startedAtMs(), now, sh.ownerName(), sh.owner())); }
            case "resume" -> { if (sh.pausedAtMs() > 0) showings.put(key, new Showing(sh.title(), sh.source(), sh.format(), now - (sh.pausedAtMs() - sh.startedAtMs()), 0, sh.ownerName(), sh.owner())); }
            case "stop" -> showings.remove(key);
            default -> {
                if (what.startsWith("seek:")) {
                    long pos = Math.max(0, Long.parseLong(what.substring(5)) * 1000);
                    showings.put(key, new Showing(sh.title(), sh.source(), sh.format(), now - pos, sh.pausedAtMs() > 0 ? now : 0, sh.ownerName(), sh.owner()));
                } else return "Unknown control " + what;
            }
        }
        broadcast(p.level().getServer(), key);
        return what.startsWith("seek") ? "Seeked." : "Film " + what + (what.endsWith("e") ? "d" : "ped") + ".";
    }

    Payloads.CinemaShow payload(String key) {
        Aud a = auds.get(key);
        Showing sh = effective(key);
        if (a == null || sh == null) return new Payloads.CinemaShow(key, "", "", "", 0, false, a != null && a.motion, "");
        return new Payloads.CinemaShow(key, sh.title(), sh.source(), sh.format(), sh.positionMs(System.currentTimeMillis()), sh.pausedAtMs() > 0, a.motion, sh.ownerName());
    }

    public void broadcast(MinecraftServer server, String key) {
        var pl = payload(key);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) Net.send(p, pl);
    }

    /** On join: every running showing (clients only play the ones whose screen is near them). */
    public void sendAll(ServerPlayer p) {
        Set<String> keys = new HashSet<>(showings.keySet());
        for (Aud a : auds.values()) if (a.zmf()) keys.add(a.key);
        for (String k : keys) { var pl = payload(k); if (!pl.title().isEmpty() || showings.containsKey(k)) Net.send(p, pl); }
    }

    void giveTicket(ServerPlayer p, Aud a, String title, String format) {
        ItemStack t = new ItemStack(CinemaContent.MOVIE_TICKET);
        t.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Ticket: " + title));
        t.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(List.of(
                Component.literal(a.cinema.name() + " · " + a.name), Component.literal(format + (a.motion ? " · 4D motion" : "")))));
        earthlife.mod.zmf.ZmfEconomy.tagTicket(t, a.key, System.currentTimeMillis() + 4 * 3600_000L);
        p.addItem(t);
        if (!"2D".equals(format) && !hasGlasses(p)) p.addItem(new ItemStack(CinemaContent.GLASSES_3D));
    }

    static boolean hasGlasses(ServerPlayer p) {
        if (p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).is(CinemaContent.GLASSES_3D)) return true;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(CinemaContent.GLASSES_3D)) return true;
        return false;
    }

    void waypoint(ServerPlayer p, Aud a) {
        if (!earth()) return;
        var s = EarthLifeServices.get();
        var life = s.life.life(p);
        String n = a.cinema.name() + " " + a.name;
        life.waypoints.removeIf(w -> w.name().startsWith(a.cinema.name()));
        life.waypoints.add(new earthlife.core.life.PlayerLife.Waypoint(n, "cinema", a.door[0], a.door[1], a.door[2]));
        life.markDirty();
    }

    // ------------------------------------------------------------------ concessions

    public String buy(ServerPlayer p, String item) throws Exception {
        Integer price = CinemaContent.PRICES.get(item);
        var it = CinemaContent.byId(item);
        if (price == null || it.isEmpty()) return "We don't sell that here.";
        if (!earth()) {
            String paid = earthlife.mod.zmf.ZmfEconomy.pay(p, earthlife.mod.zmf.ZmfConfig.get().snackEmeralds, "Concessions: " + item.replace('_', ' '));
            p.addItem(new ItemStack(it.get()));
            return "Enjoy your " + new ItemStack(it.get()).getHoverName().getString() + "! (" + paid + ")";
        }
        var s = EarthLifeServices.get();
        s.ledger.debit(p.getStringUUID(), price, "CINEMA", "snack#" + item + "#" + p.getStringUUID() + "#" + System.nanoTime(), "Concessions: " + item.replace('_', ' '), p.level().getGameTime());
        p.addItem(new ItemStack(it.get()));
        s.life.complete(p, "purchase");
        return "Enjoy your " + new ItemStack(it.get()).getHoverName().getString() + "! (" + price + " LC)";
    }

    /** Kiosk / concession counter / popcorn machine clicked. */
    public static void openMenu(ServerPlayer p, String menu, BlockPos at) {
        if (!EarthLifeServices.running() || (earthlife.mod.zmf.MultiplexService.get() != null && earthlife.mod.zmf.MultiplexService.get().nearestBuilt(p.getX(), p.getZ(), 200) != null)) {
            if (earthlife.mod.zmf.MultiplexService.get() != null) earthlife.mod.zmf.MultiplexService.get().openHub(p, menu);
            else p.sendOverlayMessage(Component.literal("No cinema here."));
            return;
        }
        earthlife.mod.ui.UiLists.send(p, "cinema");
        Net.send(p, new Payloads.OpenScreen("menu:cinema"));
    }

    /** Rows for the EarthLife menu "Cinema" tab: showtimes of the nearest theater, then the concession menu. */
    public String rows(ServerPlayer p, List<Payloads.Row> rows) {
        var c = nearestCinema(p.getX(), p.getZ());
        // the nearest listed cinema may not be built yet (Northeast worlds build them as you get close)
        var here = EarthLifeServices.get().projection.fromBlock(p.getX(), p.getZ());
        var listedNearest = listed.stream().min(Comparator.comparingDouble(m -> new GeoPoint(Json.num(m.get("lat")), Json.num(m.get("lon"))).distanceTo(here)));
        if (listedNearest.isPresent()) {
            var m = listedNearest.get();
            var b = EarthLifeServices.get().projection.toBlock(Json.num(m.get("lat")), Json.num(m.get("lon")));
            double dl = Math.hypot(b.x() - p.getX(), b.z() - p.getZ());
            if (c.isEmpty() || dl + 500 < Math.hypot(c.get().kiosk[0] - p.getX(), c.get().kiosk[2] - p.getZ())) {
                rows.add(new Payloads.Row(String.valueOf(m.get("name")), String.format("Nearest movie theater, %.1f km away - drive there to see showtimes", dl / 1000),
                        "Route", String.format(Locale.ROOT, "earthlife map route xz %.1f %.1f", b.x(), b.z())));
                if (c.isEmpty()) return "Cinema";
            }
        }
        if (c.isEmpty()) { rows.add(new Payloads.Row("No movie theaters in this world", "Cinemas come from OpenStreetMap for NY, PA, NJ and VT", "", "")); return "Cinema"; }
        Cinema cin = c.get();
        var s = EarthLifeServices.get();
        double d = Math.hypot(cin.kiosk[0] - p.getX(), cin.kiosk[2] - p.getZ());
        if (d > 80) {
            rows.add(new Payloads.Row(cin.name(), String.format("Nearest movie theater, %.1f km away%s", d / 1000, cin.address() == null || cin.address().isBlank() ? "" : " · " + cin.address()),
                    "Route", String.format(Locale.ROOT, "earthlife map route xz %.1f %.1f", cin.kiosk[0], cin.kiosk[2])));
        }
        long now = System.currentTimeMillis();
        boolean open4d = fourDOpen(s.time.clock().zone());
        for (Aud a : cin.auds) {
            Showing sh = showings.get(a.key);
            String tag = a.motion ? "4D motion" + (open4d ? "" : " · " + fourDOpening(s.time.clock().zone())) : "Recliners";
            if (sh != null) {
                long min = sh.positionMs(now) / 60000;
                rows.add(new Payloads.Row(a.name + ": " + sh.title(), tag + " · " + sh.format() + " · " + (sh.pausedAtMs() > 0 ? "paused" : min + " min in") + " · started by " + sh.ownerName(),
                        "Ticket " + price(a, sh.format()) + " LC", "earthlife cinema join " + a.key));
            } else if (a.motion && !open4d) {
                rows.add(new Payloads.Row(a.name, tag, "", ""));
            } else {
                rows.add(new Payloads.Row(a.name + ": free", tag + " · pick any film to start a showing", "Choose film", "earthlife cinema pick " + a.key));
            }
        }
        for (var e : CinemaContent.PRICES.entrySet()) {
            var it = CinemaContent.byId(e.getKey());
            if (it.isEmpty()) continue;
            rows.add(new Payloads.Row(new ItemStack(it.get()).getHoverName().getString(), "Concessions", e.getValue() + " LC", "earthlife cinema buy " + e.getKey()));
        }
        return cin.name();
    }

    /** Lat/lon of a block position (for the map). */
    public static GeoPoint geo(double x, double z) { return EarthLifeServices.get().projection.fromBlock(x, z); }

    public static Direction facing(double dx, double dz) {
        return Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
    }
}
