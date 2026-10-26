package earthlife.mod.client.cinema;

import earthlife.core.cinema.media.Auto4DXEngine;
import earthlife.core.cinema.media.Auto4DXEngine.Type;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;

import java.util.*;

/**
 * Virtual 4DX atmosphere around your seat: wind, mist, rain, snow, embers and heat glow, smoke and dust, splashes,
 * magic sparks, a shockwave ring for explosions, coloured light (red for horror, orange for fire, cold blue for space)
 * and frost at the screen edges for snowstorms. Particles only - nothing physical except a TactSuit's vibration.
 * Density follows the quality preset; blood (dark red mist) only with the gore setting on.
 */
public final class Atmosphere {
    private Atmosphere() {}

    static final RandomSource R = RandomSource.create();
    static final EnumMap<Type, long[]> running = new EnumMap<>(Type.class);   // type -> {until (clock ms), strength*1000}
    static float red, orange, blue, frost, fog;
    /** Emissions per effect type (diagnostics and tests). */
    public static final EnumMap<Type, Integer> emitted = new EnumMap<>(Type.class);

    public static void reset() { running.clear(); red = orange = blue = frost = fog = 0; }

    public static void start(Type t, long ms, float strength) {
        long now = System.currentTimeMillis();
        running.put(t, new long[]{now + ms, (long) (Math.min(1, strength) * 1000)});
    }

    static void count(Type t) { emitted.merge(t, 1, Integer::sum); }

    /** A one-off effect when an event starts. */
    static void event(Minecraft mc, Auto4DXEngine.Event e, float k) {
        var lvl = mc.level; var pl = mc.player;
        if (lvl == null || pl == null) return;
        double x = pl.getX(), y = pl.getEyeY(), z = pl.getZ();
        double yr = Math.toRadians(pl.getYRot()), fx = -Math.sin(yr), fz = Math.cos(yr);
        float dens = CinemaConfig.presetParticles() * k;
        count(e.type());
        switch (e.type()) {
            case EXPLOSION -> {
                int n = (int) (28 * dens) + 6;
                for (int i = 0; i < n; i++) {                                          // shockwave ring rushing past
                    double a = i * Math.PI * 2 / n;
                    lvl.addParticle(ParticleTypes.POOF, x + fx * 2 + Math.cos(a) * 0.6, y - 0.2 + Math.sin(a) * 0.4, z + fz * 2 + Math.sin(a) * 0.6, -fx * 0.6, 0.02, -fz * 0.6);
                }
                for (int i = 0; i < (int) (10 * dens); i++) lvl.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, x + fx * 3 + R.nextGaussian(), y + 1.5, z + fz * 3 + R.nextGaussian(), 0, 0.03, 0);
                orange = Math.max(orange, 0.25f * e.intensity());
                start(Type.EXPLOSION, 900, e.intensity());
            }
            case IMPACT -> { for (int i = 0; i < (int) (6 * dens) + 1; i++) lvl.addParticle(ParticleTypes.CRIT, x + fx * 1.5 + R.nextGaussian() * 0.3, y, z + fz * 1.5 + R.nextGaussian() * 0.3, 0, 0.05, 0); }
            case THUNDER -> { mc.player.playSound(SoundEvents.LIGHTNING_BOLT_THUNDER, 0.08f * k, 1.3f); blue = Math.max(blue, 0.15f); }
            case HORROR_SCARE -> { red = Math.max(red, 0.35f * e.intensity()); fog = Math.max(fog, 0.3f); start(Type.HORROR_SCARE, 2500, e.intensity()); }
            case BLOOD -> {
                if (CinemaConfig.gore <= 0) return;
                var dust = new DustParticleOptions(0x8A0303, CinemaConfig.gore >= 2 ? 1.6f : 0.9f);
                for (int i = 0; i < (int) ((CinemaConfig.gore >= 2 ? 24 : 8) * dens) + 2; i++)
                    lvl.addParticle(dust, x + fx * 1.2 + R.nextGaussian() * 0.3, y + R.nextGaussian() * 0.2, z + fz * 1.2 + R.nextGaussian() * 0.3, -fx * 0.2, 0, -fz * 0.2);
            }
            default -> start(e.type(), Math.max(1200, e.durationMs()), e.intensity());
        }
    }

    /** Sustained effects, every client tick (20/s) while the film keeps showing the evidence. */
    static void tick(Minecraft mc, Auto4DXEngine fxEngine, long pos, float k) {
        var lvl = mc.level; var pl = mc.player;
        if (lvl == null || pl == null) return;
        long now = System.currentTimeMillis();
        running.entrySet().removeIf(en -> en.getValue()[0] < now && !fxEngine.active(en.getKey(), pos));
        double x = pl.getX(), y = pl.getEyeY(), z = pl.getZ();
        double yr = Math.toRadians(pl.getYRot()), fx = -Math.sin(yr), fz = Math.cos(yr);
        float dens = CinemaConfig.presetParticles() * k;
        for (var en : running.entrySet()) {
            float s = en.getValue()[1] / 1000f;
            int n = Math.max(1, (int) (4 * dens * s + R.nextFloat()));
            switch (en.getKey()) {
                case RAIN -> {
                    for (int i = 0; i < n * 2; i++) lvl.addParticle(ParticleTypes.FALLING_WATER, x + R.nextGaussian() * 1.5, y + 1.6, z + R.nextGaussian() * 1.5, 0, -0.2, 0);
                    if (R.nextInt(3) == 0) mist(lvl, x, y, z, fx, fz);
                    wind(lvl, x, y, z, fx, fz, 1);
                }
                case OCEAN -> { for (int i = 0; i < n; i++) lvl.addParticle(ParticleTypes.SPLASH, x + fx * 0.8 + R.nextGaussian() * 0.4, y - 0.2, z + fz * 0.8 + R.nextGaussian() * 0.4, -fx * 0.1, 0.05, -fz * 0.1); mist(lvl, x, y, z, fx, fz); }
                case SNOW -> { for (int i = 0; i < n * 2; i++) lvl.addParticle(ParticleTypes.SNOWFLAKE, x + R.nextGaussian() * 1.6, y + 1.2, z + R.nextGaussian() * 1.6, -fx * 0.05, -0.04, -fz * 0.05); frost = Math.min(0.35f, frost + 0.01f); }
                case FIRE -> { for (int i = 0; i < n; i++) lvl.addParticle(R.nextBoolean() ? ParticleTypes.SMALL_FLAME : ParticleTypes.LAVA, x + fx * 2 + R.nextGaussian(), y - 0.8, z + fz * 2 + R.nextGaussian(), 0, 0.04, 0); orange = Math.min(0.28f, orange + 0.02f); }
                case CAR_CHASE -> { wind(lvl, x, y, z, fx, fz, n); for (int i = 0; i < n; i++) lvl.addParticle(ParticleTypes.WHITE_ASH, x + fx * 2 + R.nextGaussian(), y - 0.6, z + fz * 2 + R.nextGaussian(), -fx * 0.3, 0, -fz * 0.3); }
                case AIRCRAFT -> wind(lvl, x, y, z, fx, fz, n + 1);
                case SPACE_BATTLE -> { blue = Math.min(0.2f, blue + 0.02f); if (R.nextInt(6) == 0) for (int i = 0; i < n; i++) lvl.addParticle(ParticleTypes.ELECTRIC_SPARK, x + fx * 2 + R.nextGaussian(), y + R.nextGaussian() * 0.5, z + fz * 2 + R.nextGaussian(), 0, 0, 0); }
                case MAGIC -> { for (int i = 0; i < n; i++) lvl.addParticle(R.nextBoolean() ? ParticleTypes.ENCHANT : ParticleTypes.END_ROD, x + fx * 1.5 + R.nextGaussian(), y + R.nextGaussian() * 0.6, z + fz * 1.5 + R.nextGaussian(), 0, 0.02, 0); }
                case HORROR_SCARE -> { if (R.nextInt(2) == 0) lvl.addParticle(ParticleTypes.SMOKE, x + R.nextGaussian() * 1.2, y - 1.2, z + R.nextGaussian() * 1.2, 0, 0.01, 0); }
                case EXPLOSION -> { for (int i = 0; i < n; i++) lvl.addParticle(ParticleTypes.ASH, x + fx * 2 + R.nextGaussian(), y + 0.5, z + fz * 2 + R.nextGaussian(), 0, -0.02, 0); }
                default -> {}
            }
            count(en.getKey());
        }
        red *= 0.97f; orange *= 0.96f; blue *= 0.97f; frost *= running.containsKey(Type.SNOW) ? 1 : 0.97f; fog *= 0.97f;
    }

    static void mist(net.minecraft.client.multiplayer.ClientLevel lvl, double x, double y, double z, double fx, double fz) {
        for (int i = 0; i < 3; i++) lvl.addParticle(ParticleTypes.CLOUD, x + fx * 0.7 + R.nextGaussian() * 0.15, y - 0.1, z + fz * 0.7 + R.nextGaussian() * 0.15, -fx * 0.04, 0, -fz * 0.04);
    }

    static void wind(net.minecraft.client.multiplayer.ClientLevel lvl, double x, double y, double z, double fx, double fz, int n) {
        for (int i = 0; i < n; i++) lvl.addParticle(ParticleTypes.CLOUD, x + fx * 1.4 + R.nextGaussian() * 0.6, y + R.nextGaussian() * 0.3, z + fz * 1.4 + R.nextGaussian() * 0.6, -fx * 0.35, 0.01, -fz * 0.35);
    }

    static void drawHud(GuiGraphicsExtractor g, Minecraft mc, int w, int h) {
        if (red > 0.01f) g.fill(0, 0, w, h, ((int) (red * 255) << 24) | 0x8A0000);
        if (orange > 0.01f) g.fill(0, 0, w, h, ((int) (orange * 255) << 24) | 0xFF7A1A);
        if (blue > 0.01f) g.fill(0, 0, w, h, ((int) (blue * 255) << 24) | 0x5AA0FF);
        if (fog > 0.01f) g.fill(0, h * 2 / 3, w, h, ((int) (fog * 255) << 24) | 0x101418);
        if (frost > 0.01f) {
            int a = (int) (frost * 255) << 24 | 0xE8F4FF;
            int edge = Math.max(8, w / 14);
            g.fill(0, 0, w, edge / 2, a); g.fill(0, h - edge / 2, w, h, a); g.fill(0, 0, edge, h, a); g.fill(w - edge, 0, w, h, a);
        }
    }
}
