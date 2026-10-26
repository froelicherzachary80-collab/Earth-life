package earthlife.core.cinema.media;

import java.util.*;

/**
 * Motion of a virtual 4DX seat: pitch (forward/back), roll (sideways) and heave (up/down) driven by 4DX events and by
 * continuous cues (camera pan, bass rumble). Every axis is a damped spring towards a target that the cues move, so the
 * seat eases into and out of motion instead of jumping; vibration is a separate fast jitter. Units: degrees for pitch
 * and roll (positive pitch = leaning back), metres for heave.
 *
 * Motion types: forward/backward pitch, sideways roll, impact jolts, engine vibration, acceleration and braking,
 * turbulence, falling, floating, ocean rocking and explosion recoil.
 */
public final class SeatMotion {
    public enum Cue { RECOIL, JOLT, ACCELERATE, BRAKE, TURBULENCE, FALL, FLOAT, ROCK, LIFT_OFF, RUMBLE }

    /** 0 = no motion (accessibility), 0.4 = reduced, 1 = full. */
    public float scale = 1.0f;
    public float pitch, roll, heave, vibration;
    private float vp, vr, vh;
    private float tp, tr, th;
    private float rockPhase, floatPhase, turbulence;
    private final Map<Cue, float[]> timers = new EnumMap<>(Cue.class);     // remaining seconds, strength
    private final Random rnd = new Random(7);
    private float continuousRoll, continuousVib;

    public void reset() { pitch = roll = heave = vibration = vp = vr = vh = tp = tr = th = 0; timers.clear(); continuousRoll = continuousVib = 0; }

    public void cue(Cue c, float strength, float seconds) {
        float[] cur = timers.get(c);
        if (cur == null || cur[1] < strength || cur[0] < seconds) timers.put(c, new float[]{seconds, Math.max(0, Math.min(1, strength))});
    }

    /** Continuous inputs each frame: camera pan (-1..1) leans the seat into turns, bass adds engine vibration. */
    public void continuous(float panX, float bass) {
        continuousRoll = Math.max(-1, Math.min(1, panX)) * 4f;
        continuousVib = Math.max(0, bass - 0.2f) * 0.8f;
    }

    /** Maps a recognised 4DX event to seat cues. */
    public void onEvent(Auto4DXEngine.Event e) {
        float s = e.intensity();
        switch (e.type()) {
            case EXPLOSION -> { cue(Cue.RECOIL, s, 0.6f); cue(Cue.RUMBLE, s, 1.2f); }
            case IMPACT, HORROR_SCARE -> cue(Cue.JOLT, s, 0.35f);
            case THUNDER -> cue(Cue.RUMBLE, s * 0.8f, 1.5f);
            case CAR_CHASE -> { cue(Cue.ACCELERATE, s * 0.6f, 1.2f); cue(Cue.RUMBLE, s * 0.5f, 1.2f); }
            case AIRCRAFT -> { cue(Cue.LIFT_OFF, s, 1.5f); cue(Cue.RUMBLE, s * 0.6f, 1.5f); }
            case OCEAN -> cue(Cue.ROCK, s, 1.4f);
            case SPACE_BATTLE -> { cue(Cue.TURBULENCE, s * 0.7f, 1.2f); cue(Cue.JOLT, s * 0.5f, 0.3f); }
            case MAGIC -> cue(Cue.FLOAT, s * 0.6f, 1.4f);
            case RAIN, SNOW, FIRE, BLOOD -> {}
        }
    }

    public void update(float dt) {
        dt = Math.max(0.001f, Math.min(0.1f, dt));
        float p = 0, r = continuousRoll, h = 0, vib = continuousVib;
        for (var it = timers.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next(); float[] v = e.getValue();
            Cue cue = e.getKey();
            float s = v[1];
            switch (cue) {
                case RECOIL -> p += 7f * s;                       // thrown back
                case JOLT -> { p += 3.5f * s; h += 0.06f * s; vib += 0.6f * s; }
                case ACCELERATE -> p += 4f * s;
                case BRAKE -> p -= 5f * s;
                case LIFT_OFF -> p += 8f * s;                     // nose up
                case TURBULENCE -> { turbulence = s; }
                case FALL -> h -= 0.18f * s;
                case FLOAT -> { floatPhase += dt * 1.1f; h += (float) Math.sin(floatPhase) * 0.08f * s; }
                case ROCK -> { rockPhase += dt * 1.0f; r += (float) Math.sin(rockPhase) * 6f * s; p += (float) Math.cos(rockPhase * 0.7f) * 2f * s; }
                case RUMBLE -> vib += s;
            }
            v[0] -= dt;
            if (v[0] <= 0) { it.remove(); if (cue == Cue.TURBULENCE) turbulence = 0; }
        }
        if (turbulence > 0) { p += (rnd.nextFloat() - 0.5f) * 5f * turbulence; r += (rnd.nextFloat() - 0.5f) * 5f * turbulence; }
        tp = p * scale; tr = r * scale; th = h * scale;
        // damped springs (about 4 Hz, slightly under-damped so motion settles naturally)
        float k = 160f, c = 18f;
        vp += (k * (tp - pitch) - c * vp) * dt; pitch += vp * dt;
        vr += (k * (tr - roll) - c * vr) * dt; roll += vr * dt;
        vh += (k * (th - heave) - c * vh) * dt; heave += vh * dt;
        float tv = Math.min(1, vib) * scale;
        vibration += (tv - vibration) * Math.min(1, dt * 12);
        pitch = clampF(pitch, -12, 12); roll = clampF(roll, -12, 12); heave = clampF(heave, -0.3f, 0.3f);
    }

    /** True while the seat visibly moves. */
    public boolean moving() { return Math.abs(pitch) > 0.15f || Math.abs(roll) > 0.15f || Math.abs(heave) > 0.01f || vibration > 0.05f; }

    static float clampF(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
