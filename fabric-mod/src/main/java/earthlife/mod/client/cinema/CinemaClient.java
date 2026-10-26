package earthlife.mod.client.cinema;

import earthlife.mod.cinema.CinemaContent;
import earthlife.mod.cinema.CinemaScreenEntity;
import earthlife.mod.cinema.SeatEntity;
import earthlife.mod.net.Payloads;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;

import java.util.*;

/**
 * Client side of the cinemas: remembers what is playing in each auditorium (from the server) and decides which films
 * actually play on this computer. Only the auditorium you are in plays (ZMF rooms know their walls; for the real-world
 * theaters of Earth worlds it's the nearest screen in range), at most one stream at a time (two on ULTRA) - a 32-screen
 * multiplex never decodes 32 films. Restarts the film when you put on / take off your 3D glasses (the picture switches
 * between red/cyan anaglyph and 2D), fades the sound with distance and stops when you leave. Web sources ("web:") go to
 * the browser screens instead.
 */
public final class CinemaClient {
    private CinemaClient() {}

    record Show(Payloads.CinemaShow show, long receivedNanos) {
        long positionMs() { return show.paused() ? show.positionMs() : show.positionMs() + (System.nanoTime() - receivedNanos) / 1_000_000; }
    }

    static final Map<String, Show> shows = new HashMap<>();
    static final Map<String, VideoPlayer> players = new HashMap<>();
    static final double PLAY_RANGE = 48, STOP_RANGE = 64;
    /** Diagnostics: screens with a showing near you, and how many decode right now. */
    public static volatile int candidates, activeStreams;
    public static volatile String activeKey = "";

    public static void receive(Payloads.CinemaShow s) {
        if (s.title().isEmpty()) shows.remove(s.key()); else shows.put(s.key(), new Show(s, System.nanoTime()));
        VideoPlayer p = players.get(s.key());
        if (p != null && (s.title().isEmpty() || s.paused() || !p.source.equals(s.source()) || Math.abs(p.clockMs() - s.positionMs()) > 2500)) {
            p.close(); players.remove(s.key());          // restarted (at the new position) on the next tick
        }
    }

    public static Optional<Payloads.CinemaShow> show(String key) { return Optional.ofNullable(shows.get(key)).map(Show::show); }
    public static long positionMs(String key) { Show s = shows.get(key); return s == null ? 0 : s.positionMs(); }
    public static VideoPlayer player(String key) { return players.get(key); }
    public static Collection<VideoPlayer> players() { return players.values(); }
    public static int showCount() { return shows.size(); }

    public static boolean wearingGlasses() {
        var mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getItemBySlot(EquipmentSlot.HEAD).is(CinemaContent.GLASSES_3D);
    }

    /** The auditorium (screen entity) the local player is seated in front of, if any. */
    public static CinemaScreenEntity seatedScreen() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || !(mc.player.getVehicle() instanceof SeatEntity)) return null;
        CinemaScreenEntity in = roomScreen();
        return in != null ? in : nearestScreen(18);
    }

    /** The ZMF auditorium whose walls the player is inside, if any. */
    public static CinemaScreenEntity roomScreen() {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return null;
        for (Entity e : mc.level.entitiesForRendering()) if (e instanceof CinemaScreenEntity s && isScreen(s) && inside(s, mc.player.getX(), mc.player.getY(), mc.player.getZ())) return s;
        return null;
    }

    static boolean isScreen(CinemaScreenEntity s) { String k = s.dataValue("kind"); return k.isEmpty() || k.equals("screen"); }

    /** Interior box of a ZMF room (null for other theaters). */
    public static double[] box(CinemaScreenEntity s) {
        String b = s.dataValue("box");
        if (b.isEmpty()) return null;
        String[] a = b.split(",");
        if (a.length != 6) return null;
        double[] d = new double[6];
        for (int i = 0; i < 6; i++) d[i] = Double.parseDouble(a[i]);
        return d;
    }

    public static boolean inside(CinemaScreenEntity s, double x, double y, double z) {
        double[] b = box(s);
        return b != null && x >= b[0] && x <= b[3] && y >= b[1] - 1 && y <= b[4] + 1 && z >= b[2] && z <= b[5];
    }

    static CinemaScreenEntity nearestScreen(double max) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return null;
        CinemaScreenEntity best = null; double bd = max;
        for (Entity e : mc.level.entitiesForRendering()) if (e instanceof CinemaScreenEntity s && isScreen(s)) {
            double d = s.distanceTo(mc.player);
            if (d < bd) { bd = d; best = s; }
        }
        return best;
    }

    public static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) { stopAll(); return; }
        boolean glasses = wearingGlasses();
        CinemaScreenEntity seated = mc.player.getVehicle() instanceof SeatEntity ? seatedScreen() : null;
        record Cand(CinemaScreenEntity s, Show sh, double d, boolean in) {}
        List<Cand> cands = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof CinemaScreenEntity s) || !isScreen(s)) continue;
            Show sh = shows.get(s.key());
            if (sh == null || sh.show.paused()) continue;
            double d = s.distanceTo(mc.player);
            boolean zmf = box(s) != null;
            boolean in = zmf ? inside(s, mc.player.getX(), mc.player.getY(), mc.player.getZ()) : d < PLAY_RANGE;
            if (sh.show.source().startsWith("web:")) { if (in || s == seated) BrowserScreens.want(s, sh.show); continue; }
            if (!zmf && d > STOP_RANGE) continue;
            if (in || s == seated || (!zmf && players.containsKey(s.key()))) cands.add(new Cand(s, sh, d, in || s == seated));
        }
        candidates = cands.size();
        // seated screen first, then the nearest; only as many as the quality preset allows
        cands.sort(Comparator.comparing((Cand c) -> c.s != seated).thenComparingDouble(c -> c.d));
        int max = CinemaConfig.presetStreams();
        Set<String> wanted = new HashSet<>();
        for (Cand c : cands) {
            if (wanted.size() >= max) break;
            CinemaScreenEntity s = c.s; Show sh = c.sh;
            String roomFormats = s.dataValue("fmt");
            boolean room3d = roomFormats.isEmpty() || roomFormats.contains("3D");
            boolean threeD = !"2D".equals(sh.show.format()) && room3d && (!"AUTO3D".equals(sh.show.format()) || CinemaConfig.auto3d);
            VideoPlayer p = players.get(s.key());
            if (p != null && threeD && p.anaglyph != glasses) { p.close(); players.remove(s.key()); p = null; }
            if (p == null && (c.in || c.d < PLAY_RANGE)) {
                p = new VideoPlayer(s.key(), sh.show.source(), sh.show.format(), threeD && glasses, roomFormats);
                p.start(sh.positionMs(), sh.show.owner());
                players.put(s.key(), p);
            }
            if (p == null) continue;
            wanted.add(s.key());
            // inside the auditorium: full volume; Earth theaters fade out over the last 20 m before the stop range
            float g = box(s) != null ? (c.in ? 1f : 0f) : (float) Math.max(0, Math.min(1, (STOP_RANGE - c.d) / 20.0));
            p.gain(g * mc.options.getFinalSoundSourceVolume(SoundSource.RECORDS));
        }
        players.entrySet().removeIf(en -> { if (!wanted.contains(en.getKey()) || en.getValue().finished()) { en.getValue().close(); return true; } return false; });
        activeStreams = players.size();
        activeKey = players.isEmpty() ? "" : players.keySet().iterator().next();
        BrowserScreens.tick(mc);
    }

    /** New 3D settings take effect immediately on the running conversion. */
    public static void stereoChanged() { var st = CinemaConfig.stereo(); players.values().forEach(p -> p.stereoSettings(st)); }

    public static void stopAll() { players.values().forEach(VideoPlayer::close); players.clear(); activeStreams = 0; BrowserScreens.closeAll(); }

    public static void reset() { stopAll(); shows.clear(); }
}
