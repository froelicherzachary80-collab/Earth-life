package earthlife.mod.client.cinema;

import earthlife.core.cinema.media.Auto4DXEngine;
import earthlife.core.cinema.media.SeatMotion;
import earthlife.mod.cinema.SeatEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.RandomSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 4D / 4DX motion seats. While you sit in a motion seat and a film plays, the film itself is analysed (picture and sound,
 * {@link Auto4DXEngine}): explosions, impacts, thunder, jump scares, car chases, rain, ocean, snow, fire, flights, space
 * battles and magic become seat motion (a damped-spring rig: pitch, roll, heave, vibration - your view moves with the
 * seat), virtual atmosphere in the room ({@link Atmosphere}: wind, mist, rain, snow, embers, smoke, flashes, coloured
 * light) and, with a bHaptics TactSuit, vest patterns. All effects are virtual except the vest's vibration.
 * A local film can also carry an authored effect track ("film.4d.json" next to "film.mp4": [{"t":12.5,"fx":"impact",
 * "power":0.8,"dur":0.3}, ...] with fx = rumble, impact, sway, wind, mist, flash). Strength, reduced motion and the
 * emergency stop are in the settings.
 */
public final class FourD {
    private FourD() {}

    static boolean active;
    public static volatile boolean stopped;          // emergency stop (key END): no motion, effects or vibration until released
    static final SeatMotion seat = new SeatMotion();
    static float flash, jitterYaw, jitterPitch;
    static int flashColor = 0xF4F8FF;
    static List<double[]> track;
    static String trackFor;
    static final RandomSource R = RandomSource.create();
    static final String[] FX = {"rumble", "impact", "sway", "wind", "mist", "flash"};
    static Object lastSource;
    public static volatile String last = "";
    /** Recent events (for the HUD, diagnostics and tests): newest last. */
    public static final Deque<Auto4DXEngine.Event> recent = new ArrayDeque<>();
    public static volatile int eventsTotal;
    public static volatile float maxPitchSeen, maxRollSeen;

    /** Camera offsets (degrees), read by the camera mixin every frame. */
    public static float yawOffset() { return active ? jitterYaw : 0; }
    public static float pitchOffset() { return active ? -seat.pitch * 0.6f + jitterPitch : 0; }
    public static float rollOffset() { return active ? seat.roll * 0.7f : 0; }
    public static boolean active() { return active; }
    public static SeatMotion seat() { return seat; }

    public static void tick(Minecraft mc) {
        float k = (float) Math.max(0, Math.min(1, CinemaConfig.effects));
        active = false;
        if (stopped || mc.player == null || !(mc.player.getVehicle() instanceof SeatEntity se) || !se.motion() || k <= 0) { relax(); return; }
        var screen = CinemaClient.seatedScreen();
        if (screen == null) { relax(); return; }
        var show = CinemaClient.show(screen.key());
        VideoPlayer p = CinemaClient.player(screen.key());
        var web = show.isPresent() && show.get().source().startsWith("web:") ? BrowserScreens.session(screen.key()) : null;
        if (show.isEmpty() || !show.get().motion() || ((p == null || p.texture() == null) && (web == null || !web.hasFrame))) { relax(); return; }
        active = true;
        Object src = p != null ? p : web;
        if (src != lastSource) { lastSource = src; seat.reset(); Atmosphere.reset(); BrowserScreens.webEvents.clear(); }
        seat.scale = k * (CinemaConfig.reducedMotion ? 0.4f : 1f);
        long pos = p != null ? p.clockMs() : System.currentTimeMillis();
        // events recognised in the film (picture + sound), or in a web page's picture
        if (p != null) { p.analyse4dx = true; for (var e : p.pollEvents()) onEvent(mc, e, k); }
        else { Auto4DXEngine.Event e; while ((e = BrowserScreens.webEvents.poll()) != null) onEvent(mc, e, k); }
        // authored track (local files only)
        for (double[] e : track(show.get().source())) {
            if (pos / 1000.0 < e[0] || pos / 1000.0 > e[0] + Math.max(0.1, e[3])) continue;
            float pw = (float) e[2];
            switch ((int) e[1]) {
                case 0 -> seat.cue(SeatMotion.Cue.RUMBLE, pw, 0.2f);
                case 1 -> { seat.cue(SeatMotion.Cue.JOLT, pw, 0.3f); Haptics.pattern(Haptics.Pattern.IMPACT, pw, 0); }
                case 2 -> seat.cue(SeatMotion.Cue.ROCK, pw, 0.4f);
                case 3 -> Atmosphere.start(Auto4DXEngine.Type.AIRCRAFT, 600, pw);
                case 4 -> Atmosphere.start(Auto4DXEngine.Type.OCEAN, 500, pw);
                case 5 -> flash(0.45f * pw, 0xF4F8FF);
                default -> {}
            }
        }
        var f = p != null ? p.features : web.features;
        var lv = p != null ? p.shownLevels : earthlife.core.cinema.media.AudioAnalyzer.Levels.SILENT;
        seat.continuous(f == null ? 0 : f.panX(), lv.bass());
        seat.update(0.05f);
        maxPitchSeen = Math.max(maxPitchSeen, Math.abs(seat.pitch)); maxRollSeen = Math.max(maxRollSeen, Math.abs(seat.roll));
        float vib = seat.vibration;
        jitterYaw = (R.nextFloat() - 0.5f) * vib * 0.9f;
        jitterPitch = (R.nextFloat() - 0.5f) * vib * 0.9f;
        flash *= 0.75f;
        Atmosphere.tick(mc, p != null ? p.fx : web.fx, pos, k);
        if (CinemaConfig.haptics && vib > 0.08f) Haptics.rumble(vib);
        last = String.format(Locale.ROOT, "seat %+.1f° %+.1f°%s%s", seat.pitch, seat.roll, vib > 0.05 ? " · rumble " + Math.round(vib * 100) + "%" : "",
                recent.isEmpty() ? "" : " · " + recent.peekLast().type().label);
    }

    static void onEvent(Minecraft mc, Auto4DXEngine.Event e, float k) {
        recent.addLast(e); eventsTotal++;
        while (recent.size() > 12) recent.removeFirst();
        seat.onEvent(e);
        Atmosphere.event(mc, e, k);
        switch (e.type()) {
            case EXPLOSION -> flash(0.55f * e.intensity() * k, 0xFFD9A0);
            case THUNDER -> flash(0.6f * e.intensity() * k, 0xE8F0FF);
            case SPACE_BATTLE -> flash(0.35f * k, 0xBFE0FF);
            case MAGIC -> flash(0.25f * k, 0xE0B0FF);
            default -> {}
        }
        if (CinemaConfig.haptics) Haptics.event(e);
    }

    static void flash(float a, int color) { if (a > flash) { flash = Math.min(0.7f, a); flashColor = color; } }

    static void relax() {
        seat.scale = 0;
        seat.update(0.05f);
        jitterYaw *= 0.5f; jitterPitch *= 0.5f; flash *= 0.6f;
        if (lastSource != null && !active) { lastSource = null; Atmosphere.reset(); }
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

    /** HUD: flashes, coloured light (fire, horror), frost, and a small 4DX status line. */
    public static void drawHud(GuiGraphicsExtractor g, Minecraft mc) {
        int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        if (stopped) { g.text(mc.font, "4DX stopped [END to resume]", 6, h - 12, 0xC0FF8A80, true); return; }
        if (!active) return;
        Atmosphere.drawHud(g, mc, w, h);
        if (flash > 0.02f) g.fill(0, 0, w, h, ((int) (flash * 255) << 24) | flashColor);
        String s = "4DX · " + last + " · " + Haptics.status;
        g.text(mc.font, s, 6, h - 12, 0x90FFFFFF, true);
    }
}
