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
 * Client side of the cinemas: remembers what is playing in each auditorium (from the server), starts a
 * {@link VideoPlayer} for screens you are close to, restarts it when you put on / take off your 3D glasses (the picture
 * switches between red/cyan anaglyph and 2D), fades the sound with distance and stops when you leave.
 */
public final class CinemaClient {
    private CinemaClient() {}

    record Show(Payloads.CinemaShow show, long receivedNanos) {
        long positionMs() { return show.paused() ? show.positionMs() : show.positionMs() + (System.nanoTime() - receivedNanos) / 1_000_000; }
    }

    static final Map<String, Show> shows = new HashMap<>();
    static final Map<String, VideoPlayer> players = new HashMap<>();
    static final double PLAY_RANGE = 48, STOP_RANGE = 64;

    public static void receive(Payloads.CinemaShow s) {
        if (s.title().isEmpty()) shows.remove(s.key()); else shows.put(s.key(), new Show(s, System.nanoTime()));
        VideoPlayer p = players.get(s.key());
        if (p != null && (s.title().isEmpty() || s.paused() || !p.source.equals(s.source()) || Math.abs(p.clockMs() - s.positionMs()) > 2500)) {
            p.close(); players.remove(s.key());          // restarted (at the new position) on the next tick
        }
    }

    public static Optional<Payloads.CinemaShow> show(String key) { return Optional.ofNullable(shows.get(key)).map(Show::show); }
    public static VideoPlayer player(String key) { return players.get(key); }

    public static boolean wearingGlasses() {
        var mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getItemBySlot(EquipmentSlot.HEAD).is(CinemaContent.GLASSES_3D);
    }

    /** The auditorium (screen entity) the local player is seated in front of, if any. */
    public static CinemaScreenEntity seatedScreen() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || !(mc.player.getVehicle() instanceof SeatEntity)) return null;
        return nearestScreen(18);
    }

    static CinemaScreenEntity nearestScreen(double max) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return null;
        CinemaScreenEntity best = null; double bd = max;
        for (Entity e : mc.level.entitiesForRendering()) if (e instanceof CinemaScreenEntity s) {
            double d = s.distanceTo(mc.player);
            if (d < bd) { bd = d; best = s; }
        }
        return best;
    }

    public static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) { stopAll(); return; }
        boolean glasses = wearingGlasses();
        Set<String> wanted = new HashSet<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof CinemaScreenEntity s)) continue;
            double d = s.distanceTo(mc.player);
            Show sh = shows.get(s.key());
            VideoPlayer p = players.get(s.key());
            if (sh == null || sh.show.paused() || d > STOP_RANGE) continue;
            wanted.add(s.key());
            boolean threeD = !"2D".equals(sh.show.format());
            if (p != null && threeD && p.anaglyph != glasses) { p.close(); players.remove(s.key()); p = null; }
            if (p == null && d < PLAY_RANGE) {
                p = new VideoPlayer(s.key(), sh.show.source(), sh.show.format(), threeD && glasses);
                p.start(sh.positionMs(), sh.show.owner());
                players.put(s.key(), p);
            }
            if (p != null) {
                // inside the auditorium: full volume; fading out over the last 20 m before the stop range
                float g = (float) Math.max(0, Math.min(1, (STOP_RANGE - d) / 20.0));
                p.gain(g * mc.options.getFinalSoundSourceVolume(SoundSource.RECORDS));
            }
        }
        players.entrySet().removeIf(en -> { if (!wanted.contains(en.getKey()) || en.getValue().finished()) { en.getValue().close(); return true; } return false; });
    }

    public static void stopAll() { players.values().forEach(VideoPlayer::close); players.clear(); }

    public static void reset() { stopAll(); shows.clear(); }
}
