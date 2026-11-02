// STANDALONE
package zmf.cinema.block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import zmf.cinema.McCompat;
import zmf.cinema.MultiplexService;
import zmf.cinema.ZmfConfig;
import zmf.cinema.ZmfEconomy;
import zmf.cinema.core.Blueprint;
import zmf.cinema.net.Net;
import zmf.cinema.net.Payloads;

import java.util.*;

/**
 * Auditoriums and showings of the running world's multiplexes. The film itself is streamed and decoded on each
 * player's own computer; the server only keeps which film plays where and since when, so everyone in a room sees the
 * same moment. Screens are entities spawned near players and drawn by the client.
 */
public final class CinemaService {
    public record Cinema(String id, String name, String brand, double lat, double lon, Blueprint.Placed placed,
                         List<Aud> auds, double[] kiosk, double[] concessions, String address) {}

    /**
     * An auditorium in world coordinates. Screen faces (nx, nz). formats: "+"-joined capabilities ("2D", "3D", "4DX",
     * "IMAX", "ScreenX"); browser: web-cinema slot 'A'-'D' or '-'; box: interior {x0,y0,z0,x1,y1,z1}; geom: screen data.
     */
    public record Aud(String key, Cinema cinema, int index, String name, boolean motion, double sx, double sy, double sz,
                      double nx, double nz, double w, double h, List<BlockPos> seats, double[] door,
                      String formats, char browser, double[] box, String geom) {
        public boolean has(String fmt) { return ("+" + formats + "+").toLowerCase(Locale.ROOT).contains("+" + fmt.toLowerCase(Locale.ROOT) + "+"); }
        public boolean zmf() { return true; }
        public boolean inside(double x, double y, double z) {
            return box != null && x >= box[0] && x <= box[3] && y >= box[1] - 1 && y <= box[4] + 1 && z >= box[2] && z <= box[5];
        }
    }

    /** A film playing in an auditorium. positionMs at startedAtMs (server clock); pausedAtMs > 0 when paused. */
    public record Showing(String title, String source, String format, long startedAtMs, long pausedAtMs, String ownerName, UUID owner) {
        public long positionMs(long now) { return pausedAtMs > 0 ? pausedAtMs - startedAtMs : now - startedAtMs; }
    }

    private static CinemaService current;
    public final List<Cinema> cinemas = new java.util.concurrent.CopyOnWriteArrayList<>();
    public final Map<String, Aud> auds = new java.util.concurrent.ConcurrentHashMap<>();
    public final Map<String, Showing> showings = new HashMap<>();
    private final Map<String, CinemaScreenEntity> screens = new HashMap<>();

    public static CinemaService get() { return current; }
    public static CinemaService start() { CinemaService c = new CinemaService(); current = c; return c; }
    public static void reset() { if (current != null) current.clear(); current = null; }

    /** ZMF 4DX rooms are always open (kept for the shared command code). */
    static boolean seasonal(Aud a) { return false; }

    public void register(Cinema cin) {
        cinemas.removeIf(c -> c.id().equals(cin.id()));
        cinemas.add(cin);
        for (Aud a : cin.auds()) auds.put(a.key(), a);
    }

    // ------------------------------------------------------------------ screens near players

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
        long now = System.currentTimeMillis();
        showings.entrySet().removeIf(en -> en.getValue().pausedAtMs() == 0 && now - en.getValue().startedAtMs() > 4 * 3600_000L && broadcastClear(server, en.getKey()));
    }

    private boolean broadcastClear(MinecraftServer server, String key) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) Net.send(p, new Payloads.CinemaShow(key, "", "", "", 0, false, false, ""));
        return true;
    }

    public void clear() { screens.values().forEach(e -> e.discard()); screens.clear(); }

    // ------------------------------------------------------------------ showings

    public String priceLabel(Aud a, String format, ServerPlayer p) { return ZmfEconomy.label(ZmfEconomy.ticketPrice(a, format), p); }

    public String charge(ServerPlayer p, Aud a, String format, String ref, String what) throws Exception {
        return ZmfEconomy.pay(p, ZmfEconomy.ticketPrice(a, format), what);
    }

    /** The private showing of a room, or else its scheduled screening (if one is running now). */
    public Showing effective(String key) {
        Showing sh = showings.get(key);
        if (sh != null) return sh;
        var ms = MultiplexService.get();
        return ms == null ? null : ms.scheduledShowing(key, System.currentTimeMillis()).orElse(null);
    }

    /** Starts a film the player picked in their movie picker (a private showing) and gives them a ticket. */
    public String start(ServerPlayer p, String key, String format, String source, String title) throws Exception {
        Aud a = auds.get(key);
        if (a == null) return "That auditorium doesn't exist.";
        if (!ZmfEconomy.mayStartPrivateShowing(p)) return "Private showings are switched off on this server.";
        Showing cur = showings.get(key);
        if (cur != null && cur.positionMs(System.currentTimeMillis()) < 4 * 3600_000L && !Objects.equals(cur.owner(), p.getUUID()) && !McCompat.isOp(p))
            return "\"" + cur.title() + "\" is already playing in " + a.name + ". Join it, or pick another auditorium.";
        String paid = charge(p, a, format, "", "Movie ticket: " + title);
        showings.put(key, new Showing(title, source, format, System.currentTimeMillis(), 0, p.getName().getString(), p.getUUID()));
        giveTicket(p, a, title, format);
        broadcast(p.level().getServer(), key);
        return "🎬 \"" + title + "\" in " + a.name + " (" + paid + "). Find your seat - the film is starting!";
    }

    public String join(ServerPlayer p, String key) throws Exception {
        var ms = MultiplexService.get();
        return ms == null ? "No cinema here." : ms.buyTicket(p, key);
    }

    public String control(ServerPlayer p, String key, String what) {
        Showing sh = showings.get(key);
        if (sh == null) return effective(key) != null ? "This is a scheduled screening - it can't be paused (start your own showing instead)." : "Nothing is playing there.";
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

    /** On join: every running showing (clients only play the room they are in). */
    public void sendAll(ServerPlayer p) {
        for (Aud a : auds.values()) { var pl = payload(a.key); if (!pl.title().isEmpty()) Net.send(p, pl); }
    }

    void giveTicket(ServerPlayer p, Aud a, String title, String format) {
        ItemStack t = new ItemStack(CinemaContent.MOVIE_TICKET);
        t.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Ticket: " + title));
        t.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(List.of(
                Component.literal(a.cinema.name() + " · " + a.name), Component.literal(format + (a.motion ? " · 4DX" : "")))));
        ZmfEconomy.tagTicket(t, a.key, System.currentTimeMillis() + 4 * 3600_000L);
        p.addItem(t);
        if (!"2D".equals(format) && !hasGlasses(p)) p.addItem(new ItemStack(CinemaContent.GLASSES_3D));
    }

    static boolean hasGlasses(ServerPlayer p) {
        if (p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).is(CinemaContent.GLASSES_3D)) return true;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(CinemaContent.GLASSES_3D)) return true;
        return false;
    }

    // ------------------------------------------------------------------ concessions

    public String buy(ServerPlayer p, String item) throws Exception {
        var it = CinemaContent.byId(item);
        if (!CinemaContent.PRICES.containsKey(item) || it.isEmpty()) return "We don't sell that here.";
        String paid = ZmfEconomy.pay(p, ZmfConfig.get().snackEmeralds, "Concessions: " + item.replace('_', ' '));
        p.addItem(new ItemStack(it.get()));
        return "Enjoy your " + new ItemStack(it.get()).getHoverName().getString() + "! (" + paid + ")";
    }

    /** Kiosk / concession counter / popcorn machine clicked. */
    public static void openMenu(ServerPlayer p, String menu, BlockPos at) {
        var ms = MultiplexService.get();
        if (ms != null) ms.openHub(p, menu);
        else p.sendOverlayMessage(Component.literal("No cinema here."));
    }
}
