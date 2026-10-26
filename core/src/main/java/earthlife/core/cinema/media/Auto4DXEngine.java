package earthlife.core.cinema.media;

import java.util.*;

/**
 * Recognises 4DX-worthy moments in a film from what is actually on screen and in the soundtrack (ZMF Cinema).
 *
 * Input: per-frame picture features ({@link FrameAnalyzer}) and the soundtrack levels at the same moment
 * ({@link AudioAnalyzer}). Output: timed {@link Event}s with a confidence, an intensity and a left/right direction,
 * which the game turns into seat motion, virtual wind/mist/rain/snow/embers/flashes and TactSuit patterns.
 *
 * Rules, not a trained classifier: each event needs specific evidence (e.g. an explosion = a sudden loud, bass-heavy
 * onset together with a bright or fiery picture), must pass a confidence threshold, and has a cooldown so it doesn't
 * repeat every frame. Ongoing conditions (rain, ocean, snow, fire, car chase, flight) must hold for a while before they
 * start and end when the evidence goes away. Nothing fires because of a film's genre; when no picture or sound is
 * available (e.g. protected streams) no events are produced. {@link #reset()} on seek, pause or a new film.
 */
public final class Auto4DXEngine {
    public enum Type {
        EXPLOSION("Explosion"), IMPACT("Impact"), THUNDER("Thunder"), HORROR_SCARE("Horror scare"), BLOOD("Blood (optional)"),
        CAR_CHASE("Car chase"), RAIN("Rainstorm"), OCEAN("Ocean"), SNOW("Snowstorm"), FIRE("Fire"), AIRCRAFT("Aircraft / takeoff"),
        SPACE_BATTLE("Space battle"), MAGIC("Magical energy");
        public final String label;
        Type(String label) { this.label = label; }
        public boolean sustained() { return switch (this) { case CAR_CHASE, RAIN, OCEAN, SNOW, FIRE, AIRCRAFT, SPACE_BATTLE, MAGIC -> true; default -> false; }; }
    }

    /** A detected moment: film time, how long the effect should last, strength and confidence 0..1, direction -1 left .. 1 right. */
    public record Event(Type type, long ptsMs, long durationMs, float intensity, float confidence, float dirX) {}

    public static final class Settings {
        public float threshold = 0.55f;      // minimum confidence
        public boolean blood = false;        // optional virtual blood effect (off by default)
        public float sensitivity = 1.0f;     // 0.5 = fewer events, 1.5 = more
    }

    private final Settings settings;
    private float avgLuma = 0.3f, avgLoud = 0.1f, avgBass = 0.1f, avgMotion = 0.1f, avgHigh = 0.05f, quietLoud = 0.1f;
    private final EnumMap<Type, Long> lastFired = new EnumMap<>(Type.class);
    private final EnumMap<Type, Long> since = new EnumMap<>(Type.class);       // sustained condition holding since (pts)
    private final EnumMap<Type, Long> activeUntil = new EnumMap<>(Type.class);
    private final Deque<long[]> flashes = new ArrayDeque<>();                  // recent bright flashes (pts) for thunder / space battles
    private long lastPts = Long.MIN_VALUE;
    private int frames;
    public final List<Event> history = new ArrayList<>();
    public volatile String lastSummary = "";

    public Auto4DXEngine(Settings s) { this.settings = s; }

    public void reset() {
        lastFired.clear(); since.clear(); activeUntil.clear(); flashes.clear(); frames = 0; lastPts = Long.MIN_VALUE;
        avgLuma = 0.3f; avgLoud = 0.1f; avgBass = 0.1f; avgMotion = 0.1f; avgHigh = 0.05f; quietLoud = 0.1f;
    }

    public boolean active(Type t, long pts) { Long u = activeUntil.get(t); return u != null && pts < u; }

    static final long[] COOLDOWN_MS = new long[Type.values().length];
    static {
        COOLDOWN_MS[Type.EXPLOSION.ordinal()] = 2500; COOLDOWN_MS[Type.IMPACT.ordinal()] = 600; COOLDOWN_MS[Type.THUNDER.ordinal()] = 5000;
        COOLDOWN_MS[Type.HORROR_SCARE.ordinal()] = 9000; COOLDOWN_MS[Type.BLOOD.ordinal()] = 6000;
        for (Type t : Type.values()) if (t.sustained()) COOLDOWN_MS[t.ordinal()] = 1500;
    }

    /** Feed one frame (picture features + sound levels at the same film time). Returns the events that start now. */
    public List<Event> update(FrameAnalyzer.Features f, AudioAnalyzer.Levels a) {
        List<Event> out = new ArrayList<>();
        long t = f.ptsMs();
        if (lastPts != Long.MIN_VALUE && (t < lastPts - 500 || t > lastPts + 5000)) reset();      // seek / jump
        lastPts = t;
        frames++;
        if (f.sceneCut()) { since.clear(); }
        float sens = settings.sensitivity;
        float onset = Math.max(0, a.loud() - avgLoud), bassUp = Math.max(0, a.bass() - avgBass);
        float flash = Math.max(0, f.luma() - avgLuma);
        boolean warmUp = f.warm() > 0.12f;
        float dirX = Math.max(-1, Math.min(1, (f.lumaRight() - f.lumaLeft()) * 3));
        if (flash > 0.25f) flashes.addLast(new long[]{t});
        while (!flashes.isEmpty() && t - flashes.peekFirst()[0] > 6000) flashes.removeFirst();

        if (frames > 12) {          // let the averages settle on the first half second
            // explosion: loud bass-heavy onset with a bright or fiery picture
            float cEx = sens * (clamp((onset - 0.18f) * 2.2f) * 0.45f + clamp(bassUp * 2.5f) * 0.25f + clamp(Math.max(flash * 2.2f, warmUp ? f.warm() * 2.2f : 0)) * 0.30f);
            if (onset > 0.15f && (flash > 0.12f || f.warm() > 0.15f)) fire(out, Type.EXPLOSION, t, 1800, clamp(Math.max(a.loud(), a.bass()) * 1.8f), cEx, dirX);
            // thunder: a big white flash in a dark scene (lightning), sound may follow
            float cTh = sens * (clamp(flash * 2.0f) * 0.6f + clamp((0.35f - avgLuma) * 3) * 0.25f + clamp(f.white() * 2) * 0.15f);
            if (flash > 0.3f && avgLuma < 0.3f && f.warm() < 0.08f) fire(out, Type.THUNDER, t, 1500, clamp(flash * 1.6f), cTh, dirX);
            // horror scare: a sudden loud sting after a quiet, dark stretch
            float cSc = sens * (clamp((onset - 0.25f) * 2.5f) * 0.5f + clamp((0.12f - quietLoud) * 8) * 0.3f + clamp(f.dark() * 1.5f) * 0.2f);
            if (onset > 0.25f && quietLoud < 0.12f && avgLuma < 0.3f) fire(out, Type.HORROR_SCARE, t, 900, clamp(onset * 2), cSc, dirX);
            // impact (gunshot, collision, punch): short onset, no big flash needed
            float cIm = sens * (clamp((onset - 0.12f) * 3) * 0.7f + clamp(f.motion() * 1.5f) * 0.3f);
            if (onset > 0.12f) fire(out, Type.IMPACT, t, 350, clamp(onset * 2.5f), cIm, dirX);
            // blood: a mostly dark, deep red picture right after a scare or impact (optional effect)
            if (settings.blood && f.red() > 0.22f && (f.dark() > 0.2f || f.luma() < 0.25f) && (recent(Type.HORROR_SCARE, t, 3000) || recent(Type.IMPACT, t, 1500)))
                fire(out, Type.BLOOD, t, 900, clamp(f.red() * 2), sens * (0.5f + clamp(f.red() * 1.5f) * 0.5f), dirX);

            // ongoing conditions
            sustain(out, Type.RAIN, t, a.high() > Math.max(0.12f, avgHigh * 0.9f) && a.high() > a.bass() * 0.8f && f.luma() < 0.5f && f.saturation() < 0.35f,
                    sens * (clamp(a.high() * 3) * 0.6f + clamp((0.5f - f.luma()) * 2) * 0.2f + clamp((0.35f - f.saturation()) * 3) * 0.2f), 2000, clamp(a.high() * 3), dirX);
            sustain(out, Type.OCEAN, t, f.blue() > 0.35f && f.motion() > 0.06f,
                    sens * (clamp(f.blue() * 1.6f) * 0.7f + clamp(f.motion() * 4) * 0.3f), 2500, clamp(f.blue() * 1.5f), 0);
            sustain(out, Type.SNOW, t, f.white() > 0.35f && f.saturation() < 0.15f && f.motion() > 0.03f,
                    sens * (clamp(f.white() * 1.8f) * 0.7f + clamp((0.15f - f.saturation()) * 6) * 0.3f), 2500, clamp(f.white() * 1.5f), 0);
            sustain(out, Type.FIRE, t, f.warm() > 0.22f && f.luma() > 0.2f,
                    sens * (clamp(f.warm() * 2.2f) * 0.75f + clamp(Math.abs(f.lumaDelta()) * 20) * 0.25f), 1500, clamp(f.warm() * 2), dirX);
            sustain(out, Type.CAR_CHASE, t, avgMotion > 0.32f && a.mid() > 0.12f && Math.abs(f.panX()) > 0.15f,
                    sens * (clamp(avgMotion * 1.8f) * 0.5f + clamp(a.mid() * 3) * 0.3f + clamp(Math.abs(f.panX())) * 0.2f), 2000, clamp(avgMotion * 1.5f), f.panX());
            sustain(out, Type.AIRCRAFT, t, avgBass > 0.22f && a.loud() > 0.3f && f.skyBlue() > 0.3f,
                    sens * (clamp(avgBass * 2.5f) * 0.5f + clamp(f.skyBlue() * 1.5f) * 0.5f), 3000, clamp(avgBass * 2), 0);
            sustain(out, Type.SPACE_BATTLE, t, avgLuma < 0.2f && flashes.size() >= 3 && avgLoud > 0.2f,
                    sens * (clamp(flashes.size() / 6f) * 0.6f + clamp((0.2f - avgLuma) * 5) * 0.4f), 1500, clamp(flashes.size() / 5f), dirX);
            sustain(out, Type.MAGIC, t, f.magic() > 0.18f && f.luma() > 0.25f,
                    sens * (clamp(f.magic() * 2.5f) * 0.8f + clamp(a.high() * 2) * 0.2f), 1500, clamp(f.magic() * 2.5f), dirX);
        }
        // running averages (~1-3 s time constants at 24 fps)
        avgLuma += (f.luma() - avgLuma) * 0.06f;
        avgLoud += (a.loud() - avgLoud) * 0.05f;
        avgBass += (a.bass() - avgBass) * 0.03f;
        avgHigh += (a.high() - avgHigh) * 0.03f;
        avgMotion += (f.motion() - avgMotion) * 0.05f;
        quietLoud += (Math.min(a.loud(), quietLoud + 0.02f) - quietLoud) * 0.04f;        // follows quiet stretches, ignores spikes
        if (!out.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Event e : out) { if (!sb.isEmpty()) sb.append(", "); sb.append(e.type.label).append(String.format(Locale.ROOT, " %.0f%%", e.confidence * 100)); }
            lastSummary = sb.toString();
            history.addAll(out);
            if (history.size() > 200) history.subList(0, history.size() - 200).clear();
        }
        return out;
    }

    private boolean recent(Type type, long t, long within) { Long l = lastFired.get(type); return l != null && t - l < within; }

    private void fire(List<Event> out, Type type, long t, long dur, float intensity, float conf, float dirX) {
        if (conf < settings.threshold) return;
        Long l = lastFired.get(type);
        if (l != null && t - l < COOLDOWN_MS[type.ordinal()]) return;
        // a stronger event of the same moment wins: an explosion is not also reported as an impact
        if (type == Type.IMPACT && (recent(Type.EXPLOSION, t, 800) || recent(Type.HORROR_SCARE, t, 800) || recent(Type.THUNDER, t, 800))) return;
        if (type == Type.IMPACT) for (Event e : out) if (e.type == Type.EXPLOSION || e.type == Type.HORROR_SCARE || e.type == Type.THUNDER) return;
        lastFired.put(type, t);
        activeUntil.put(type, t + dur);
        out.add(new Event(type, t, dur, Math.max(0.15f, intensity), Math.min(1, conf), dirX));
    }

    private void sustain(List<Event> out, Type type, long t, boolean cond, float conf, long minHold, float intensity, float dirX) {
        if (!cond || conf < settings.threshold * 0.9f) { since.remove(type); return; }
        Long s0 = since.putIfAbsent(type, t);
        long held = t - (s0 == null ? t : s0);
        if (held < minHold) return;
        boolean wasActive = active(type, t);
        activeUntil.put(type, t + 1200);                       // stays on while the evidence holds
        if (!wasActive) fire(out, type, t, 1200, intensity, conf, dirX);
    }

    static float clamp(float v) { return Math.max(0, Math.min(1, v)); }
}
