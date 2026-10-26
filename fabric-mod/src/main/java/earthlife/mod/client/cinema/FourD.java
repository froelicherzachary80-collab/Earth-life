package earthlife.mod.client.cinema;

import earthlife.mod.cinema.SeatEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * The 4D motion auditorium (open Oct 29 - Nov 3). While you sit in a motion seat and a film plays, effects follow the
 * film: the seat rumbles with the bass, kicks on loud hits and sways with fast motion on screen (your view moves a
 * little), wind and mist blow past, lightning-like flashes light the room - and a bHaptics TactSuit, if you wear one,
 * buzzes in time. Effects are worked out live from the film's sound and picture; a local film can also carry an
 * authored effect track ("film.mp4" + "film.4d.json": [{"t":12.5,"fx":"impact","power":0.8,"dur":0.3}, ...] with fx =
 * rumble, impact, sway, wind, mist, flash). Strength is set in the cinema menu (0 = off).
 */
public final class FourD {
    private FourD() {}

    static boolean active;
    static float yaw, pitch, targetYaw, targetPitch, flash;
    static float bassAvg, loudAvg, lumaAvg;
    static int ticks, windTicks, mistTicks, swayTicks;
    static float swayPhase, swayPower;
    static List<double[]> track;            // t, fxIndex, power, dur
    static String trackFor;
    static final RandomSource R = RandomSource.create();
    static final String[] FX = {"rumble", "impact", "sway", "wind", "mist", "flash"};
    public static volatile String last = "";

    /** Camera offsets (degrees), read by the camera mixin every frame. */
    public static float yawOffset() { return active ? yaw : 0; }
    public static float pitchOffset() { return active ? pitch : 0; }

    public static void tick(Minecraft mc) {
        ticks++;
        float k = (float) Math.max(0, Math.min(1, CinemaConfig.effects));
        active = false;
        if (mc.player == null || !(mc.player.getVehicle() instanceof SeatEntity seat) || !seat.motion() || k <= 0) { relax(); return; }
        var screen = CinemaClient.seatedScreen();
        if (screen == null) { relax(); return; }
        var show = CinemaClient.show(screen.key());
        VideoPlayer p = CinemaClient.player(screen.key());
        if (show.isEmpty() || !show.get().motion() || p == null || p.texture() == null) { relax(); return; }
        active = true;

        float bass = p.bass, loud = p.loud, luma = p.luma, motion = p.motion;
        bassAvg += (bass - bassAvg) * 0.05f; loudAvg += (loud - loudAvg) * 0.05f; lumaAvg += (luma - lumaAvg) * 0.08f;
        float rumble = Math.max(0, bass - 0.15f) * 1.4f;
        boolean impact = loud - loudAvg > 0.28f && loud > 0.45f;
        boolean flashHit = luma - lumaAvg > 0.28f;
        if (motion > 0.35f) { swayTicks = 20; swayPower = Math.min(1, motion); }
        // authored track (local files only)
        long pos = p.clockMs();
        for (double[] e : track(show.get().source())) {
            if (pos / 1000.0 < e[0] || pos / 1000.0 > e[0] + Math.max(0.1, e[3])) continue;
            switch ((int) e[1]) {
                case 0 -> rumble = Math.max(rumble, (float) e[2]);
                case 1 -> impact = true;
                case 2 -> { swayTicks = Math.max(swayTicks, 10); swayPower = (float) e[2]; }
                case 3 -> windTicks = Math.max(windTicks, 10);
                case 4 -> mistTicks = Math.max(mistTicks, 6);
                case 5 -> flashHit = true;
                default -> {}
            }
        }
        // seat motion -> view offsets (smoothed); rumble is a fast jitter
        if (swayTicks > 0) { swayTicks--; swayPhase += 0.25f; }
        targetYaw = (float) (Math.sin(swayPhase) * 2.5 * swayPower * (swayTicks > 0 ? 1 : 0));
        targetPitch = (float) (Math.cos(swayPhase * 0.7) * 1.5 * swayPower * (swayTicks > 0 ? 1 : 0)) + (impact ? -2.2f : 0);
        float jitter = rumble * 0.6f;
        yaw += (targetYaw - yaw) * 0.25f + (R.nextFloat() - 0.5f) * jitter * k;
        pitch += (targetPitch - pitch) * 0.3f + (R.nextFloat() - 0.5f) * jitter * k;
        yaw *= k > 0 ? 1 : 0; pitch *= k > 0 ? 1 : 0;
        if (flashHit) flash = Math.min(0.55f, 0.25f + (luma - lumaAvg)) * k;
        flash *= 0.75f;
        // wind with fast motion; mist on big impacts during dark, busy scenes
        if (motion > 0.5f && windTicks == 0 && R.nextFloat() < 0.3f) windTicks = 30;
        if (impact && luma < 0.35f && R.nextFloat() < 0.35f) mistTicks = 8;
        var lvl = mc.level;
        double x = mc.player.getX(), y = mc.player.getEyeY(), z = mc.player.getZ();
        double yr = Math.toRadians(mc.player.getYRot()), fx = -Math.sin(yr), fz = Math.cos(yr);
        if (windTicks > 0) {
            windTicks--;
            for (int i = 0; i < 3; i++) lvl.addParticle(ParticleTypes.CLOUD, x + fx * 1.2 + R.nextGaussian() * 0.6, y + R.nextGaussian() * 0.3, z + fz * 1.2 + R.nextGaussian() * 0.6,
                    -fx * 0.35, 0.01, -fz * 0.35);
            if (windTicks % 15 == 0) mc.player.playSound(SoundEvents.ELYTRA_FLYING, 0.12f * k, 1.6f);
        }
        if (mistTicks > 0) {
            mistTicks--;
            for (int i = 0; i < 6; i++) lvl.addParticle(ParticleTypes.SPLASH, x + fx * 0.6 + R.nextGaussian() * 0.25, y + 0.1, z + fz * 0.6 + R.nextGaussian() * 0.25, -fx * 0.1, 0, -fz * 0.1);
            if (mistTicks == 7) mc.player.playSound(SoundEvents.WATER_AMBIENT, 0.25f * k, 1.8f);
        }
        if (impact) mc.player.playSound(SoundEvents.PISTON_EXTEND, 0.15f * k, 0.6f);
        last = String.format(Locale.ROOT, "rumble %.0f%%%s%s%s", rumble * 100, impact ? " · hit" : "", windTicks > 0 ? " · wind" : "", flash > 0.05 ? " · flash" : "");

        // TactSuit: rumble on the lower torso, hits everywhere, sway runs side to side
        if (CinemaConfig.haptics) {
            int[] front = new int[20], back = new int[20];
            int rv = Math.round(Math.min(1, rumble) * 70 * k);
            for (int i = 12; i < 20; i++) { front[i] = rv; back[i] = rv; }
            if (impact) { Arrays.fill(front, Math.round(90 * k)); Arrays.fill(back, Math.round(60 * k)); }
            if (swayTicks > 0) {
                int col = (int) ((Math.sin(swayPhase) * 0.5 + 0.5) * 3.99);
                for (int r = 0; r < 5; r++) { front[r * 4 + col] = Math.max(front[r * 4 + col], Math.round(55 * swayPower * k)); back[r * 4 + (3 - col)] = Math.max(back[r * 4 + (3 - col)], Math.round(55 * swayPower * k)); }
            }
            if (windTicks > 0) for (int i = 0; i < 8; i++) front[i] = Math.max(front[i], Math.round(25 * k));
            if (rv > 0 || impact || swayTicks > 0 || windTicks > 0) Haptics.frame(front, back, impact ? 140 : 110);
        }
    }

    static void relax() {
        yaw *= 0.7f; pitch *= 0.7f; flash *= 0.6f;
        if (Math.abs(yaw) < 0.01 && Math.abs(pitch) < 0.01) { yaw = 0; pitch = 0; }
    }

    /** Authored effect track next to a local film file (film.4d.json), if any. */
    static List<double[]> track(String source) {
        if (Objects.equals(source, trackFor)) return track == null ? List.of() : track;
        trackFor = source; track = null;
        if (source == null || !source.startsWith("file:")) return List.of();
        try {
            String f = source.substring(5);
            Path t = Path.of(f.replaceAll("\\.[A-Za-z0-9]{2,4}$", "") + ".4d.json");
            if (!Files.isRegularFile(t)) return List.of();
            List<double[]> out = new ArrayList<>();
            for (Object o : earthlife.core.util.Json.arr(earthlife.core.util.Json.parse(Files.readString(t)))) {
                var m = earthlife.core.util.Json.obj(o);
                int fx = Arrays.asList(FX).indexOf(String.valueOf(m.get("fx")));
                if (fx < 0) continue;
                out.add(new double[]{earthlife.core.util.Json.num(m.get("t")), fx, m.get("power") == null ? 1 : earthlife.core.util.Json.num(m.get("power")),
                        m.get("dur") == null ? 0.3 : earthlife.core.util.Json.num(m.get("dur"))});
            }
            track = out;
            return out;
        } catch (Exception e) { return List.of(); }
    }

    /** HUD: the white flash and a small 4D status line. */
    public static void drawHud(GuiGraphicsExtractor g, Minecraft mc) {
        if (!active) return;
        int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        if (flash > 0.02f) g.fill(0, 0, w, h, ((int) (flash * 255) << 24) | 0xF4F8FF);
        String s = "4D · " + last + " · " + Haptics.status;
        g.text(mc.font, s, 6, h - 12, 0x90FFFFFF, true);
    }
}
