package earthlife.test;

import earthlife.core.cinema.media.*;

import java.util.*;

import static earthlife.test.T.*;

/** ZMF Cinema v0.1 media pipeline: 2D-to-3D anaglyph, picture/sound analysis, 4DX event recognition, seat motion. */
public final class ZmfMediaTest {
    static final int W = 320, H = 180;

    /** A test frame: dark gradient sky at the top, a ground plane, and a bright textured box (the "subject") at the bottom centre. */
    static byte[] scene(int boxX) {
        byte[] f = new byte[W * H * 4];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int i = (y * W + x) * 4;
            int r, g, b;
            if (y < H * 0.4) { r = 120; g = 160; b = 230; }                       // flat blue sky
            else { int t = (x / 6 + y / 6) % 2; r = 70 + t * 10; g = 90 + t * 10; b = 60; }   // textured ground
            boolean box = x >= boxX && x < boxX + 40 && y > H * 0.55 && y < H * 0.95;
            if (box) { boolean stripe = (x / 4) % 2 == 0; r = stripe ? 250 : 40; g = stripe ? 240 : 40; b = stripe ? 230 : 40; }
            f[i] = (byte) r; f[i + 1] = (byte) g; f[i + 2] = (byte) b; f[i + 3] = (byte) 255;
        }
        return f;
    }

    /** Horizontal shift (pixels) that best aligns channel a onto channel b along the given rows, within +-maxShift. */
    static int shift(byte[] img, int chA, int chB, int y0, int y1, int x0, int x1, int maxShift) {
        double best = Double.MAX_VALUE; int bs = 0;
        for (int s = -maxShift; s <= maxShift; s++) {
            double d = 0;
            for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
                int a = img[(y * W + x) * 4 + chA] & 255, b = img[(y * W + Math.max(0, Math.min(W - 1, x - s))) * 4 + chB] & 255;
                d += Math.abs(a - b);
            }
            if (d < best) { best = d; bs = s; }
        }
        return bs;
    }

    public static void testAnaglyphPutsTheSubjectInFrontOfTheScreen() {
        var st = new StereoConverter.Settings();
        st.ghostReduction = false;       // plain channels make the geometry measurable: red = left eye, green/blue = right eye
        st.strength = 2.0f;
        var conv = new StereoConverter(st);
        byte[] src = scene(140), dst = new byte[src.length];
        for (int i = 0; i < 3; i++) conv.convert(src, dst, W, H);
        check(conv.lastMeanDisparityPx > 0.3f, "mean disparity " + conv.lastMeanDisparityPx + " px");
        // red channel = left eye's luminance, green = right eye. In the subject, the left eye's picture must be to the
        // right of the right eye's (crossed disparity = in front of the screen).
        int s = shift(dst, 0, 1, (int) (H * 0.62), (int) (H * 0.9), 120, 200, 8);
        check(s > 0, "subject: left-eye image shifted right of the right-eye image by " + s + " px (expected > 0)");
        // the depth map puts the bottom subject nearer than the sky
        float[] d = conv.depth().map();
        int gw = conv.depth().gw, gh = conv.depth().gh;
        float sky = d[1 * gw + gw / 2], subject = d[(int) (gh * 0.8) * gw + gw / 2];
        check(subject > sky + 0.2f, "subject nearer than sky: " + subject + " vs " + sky);
        // swap-eye correction reverses the geometry
        st.swapEyes = true; conv.settings(st);
        conv.convert(src, dst, W, H);
        check(shift(dst, 0, 1, (int) (H * 0.62), (int) (H * 0.9), 120, 200, 8) < 0, "swapped eyes reverse the disparity");
    }

    public static void testCompareModeKeepsTheOriginalOnTheLeft() {
        var st = new StereoConverter.Settings();
        st.compare = true;
        var conv = new StereoConverter(st);
        byte[] src = scene(140), dst = new byte[src.length];
        conv.convert(src, dst, W, H);
        boolean same = true, changed = false;
        for (int y = 0; y < H; y += 7) for (int x = 0; x < W; x += 5) {
            int i = (y * W + x) * 4;
            if (x < W / 2 && (dst[i] != src[i] || dst[i + 1] != src[i + 1])) same = false;
            if (x >= W / 2 && (dst[i] != src[i] || dst[i + 1] != src[i + 1] || dst[i + 2] != src[i + 2])) changed = true;
        }
        check(same, "left half is the original 2D picture");
        check(changed, "right half is converted");
    }

    public static void testDuboisAnaglyphIsRedCyan() {
        var conv = new StereoConverter(new StereoConverter.Settings());
        byte[] src = new byte[W * H * 4], dst = new byte[src.length];
        Arrays.fill(src, (byte) 200);
        conv.convert(src, dst, W, H);
        int r = dst[(90 * W + 160) * 4] & 255, g = dst[(90 * W + 160) * 4 + 1] & 255, b = dst[(90 * W + 160) * 4 + 2] & 255;
        check(r > 150 && g > 150 && b > 150, "a flat grey stays grey-ish (both eyes see it): " + r + "," + g + "," + b);
        check(conv.lastMicros > 0, "timed");
    }

    public static void testDepthIsStableOverTimeAndResetsOnCuts() {
        var de = new DepthEstimator(48, 27);
        byte[] a = scene(140);
        float[] d1 = de.update(a, W, H).clone();
        float[] d2 = de.update(a, W, H).clone();
        double diff = 0; for (int i = 0; i < d1.length; i++) diff += Math.abs(d1[i] - d2[i]);
        check(diff / d1.length < 0.02, "same frame -> same depth (" + diff / d1.length + ")");
        // a moved subject changes the map only gradually (temporal filter)
        de.temporal(0.25f);
        float[] d3 = de.update(scene(40), W, H).clone();
        double step = 0; for (int i = 0; i < d1.length; i++) step += Math.abs(d3[i] - d2[i]);
        check(step / d1.length < 0.15, "small change per frame: " + step / d1.length);
    }

    // ---------------------------------------------------------------- analysis + 4DX

    static byte[] fill(int r, int g, int b) {
        byte[] f = new byte[W * H * 4];
        for (int i = 0; i < f.length; i += 4) { f[i] = (byte) r; f[i + 1] = (byte) g; f[i + 2] = (byte) b; f[i + 3] = (byte) 255; }
        return f;
    }

    /** 1/24 s of stereo s16le: a sine of the given frequency plus noise. */
    static byte[] pcm(double freq, double amp, double noise, Random rnd) {
        int n = 48000 / 24;
        byte[] b = new byte[n * 4];
        for (int i = 0; i < n; i++) {
            double v = amp * Math.sin(2 * Math.PI * freq * i / 48000.0) + noise * (rnd.nextDouble() * 2 - 1);
            short s = (short) Math.max(-32768, Math.min(32767, v * 32767));
            b[i * 4] = (byte) s; b[i * 4 + 1] = (byte) (s >> 8); b[i * 4 + 2] = (byte) s; b[i * 4 + 3] = (byte) (s >> 8);
        }
        return b;
    }

    record Run(List<Auto4DXEngine.Event> events, SeatMotion seat, float maxPitch) {}

    /** Plays a synthetic "film": n frames from the supplier, analysing picture and sound like the game does. */
    static Run play(Auto4DXEngine eng, int frames, java.util.function.IntFunction<byte[]> picture, java.util.function.IntFunction<byte[]> sound) {
        var fa = new FrameAnalyzer();
        var aa = new AudioAnalyzer(48000);
        var seat = new SeatMotion();
        List<Auto4DXEngine.Event> all = new ArrayList<>();
        float maxPitch = 0;
        for (int i = 0; i < frames; i++) {
            byte[] snd = sound.apply(i);
            var lv = aa.feed(snd, 0, snd.length);
            var f = fa.analyze(picture.apply(i), W, H, i * 1000L / 24);
            for (var e : eng.update(f, lv)) { all.add(e); seat.onEvent(e); }
            seat.continuous(f.panX(), lv.bass());
            seat.update(1 / 24f);
            maxPitch = Math.max(maxPitch, seat.pitch);
        }
        return new Run(all, seat, maxPitch);
    }

    public static void testExplosionIsDetectedAndMovesTheSeat() {
        Random rnd = new Random(1);
        // 4 s of a quiet, dark scene; at 4 s an orange-white fireball with a loud low boom for half a second; then quiet
        var run = play(new Auto4DXEngine(new Auto4DXEngine.Settings()), 24 * 7,
                i -> i >= 96 && i < 108 ? fill(255, 150, 30) : fill(20, 22, 30),
                i -> i >= 96 && i < 108 ? pcm(55, 0.7, 0.35, rnd) : pcm(220, 0.01, 0.01, rnd));
        var ex = run.events.stream().filter(e -> e.type() == Auto4DXEngine.Type.EXPLOSION).toList();
        check(ex.size() == 1, "one explosion detected, got " + run.events);
        check(ex.get(0).ptsMs() >= 3900 && ex.get(0).ptsMs() <= 4300, "at the right moment: " + ex.get(0).ptsMs() + " ms");
        check(run.events.stream().noneMatch(e -> e.type() == Auto4DXEngine.Type.IMPACT && Math.abs(e.ptsMs() - ex.get(0).ptsMs()) < 800), "not also reported as a plain impact");
        check(run.maxPitch > 3f, "seat recoils (pitch up to " + run.maxPitch + " deg)");
        check(Math.abs(run.seat.pitch) < 0.5f, "seat settles afterwards (pitch " + run.seat.pitch + ")");
    }

    public static void testCalmSceneProducesNoEvents() {
        Random rnd = new Random(2);
        var run = play(new Auto4DXEngine(new Auto4DXEngine.Settings()), 24 * 10, i -> scene(140 + (i % 3)), i -> pcm(300, 0.05, 0.02, rnd));
        check(run.events.isEmpty(), "a calm dialogue-like scene triggers nothing, got " + run.events);
    }

    public static void testRainNeedsSustainedEvidence() {
        Random rnd = new Random(3);
        // dim, desaturated picture with hiss (high-frequency noise) for 6 s
        var run = play(new Auto4DXEngine(new Auto4DXEngine.Settings()), 24 * 6, i -> fill(70 + (i % 2) * 6, 75, 82), i -> pcm(8000, 0.02, 0.25, rnd));
        var rain = run.events.stream().filter(e -> e.type() == Auto4DXEngine.Type.RAIN).toList();
        check(!rain.isEmpty(), "rain detected, got " + run.events);
        check(rain.get(0).ptsMs() >= 2000, "only after the evidence held for 2 s: " + rain.get(0).ptsMs());
    }

    public static void testHorrorScareAfterQuiet() {
        Random rnd = new Random(4);
        var run = play(new Auto4DXEngine(new Auto4DXEngine.Settings()), 24 * 8,
                i -> fill(12, 12, 14), i -> i >= 120 && i < 126 ? pcm(900, 0.8, 0.3, rnd) : pcm(200, 0.004, 0.003, rnd));
        check(run.events.stream().anyMatch(e -> e.type() == Auto4DXEngine.Type.HORROR_SCARE), "jump scare detected, got " + run.events);
    }

    public static void testBloodIsOffUnlessEnabled() {
        Random rnd = new Random(5);
        java.util.function.IntFunction<byte[]> pic = i -> i >= 120 ? fill(110, 5, 8) : fill(12, 12, 14);
        java.util.function.IntFunction<byte[]> snd = i -> i >= 120 && i < 126 ? pcm(900, 0.8, 0.3, rnd) : pcm(200, 0.004, 0.003, rnd);
        var off = play(new Auto4DXEngine(new Auto4DXEngine.Settings()), 24 * 8, pic, snd);
        check(off.events.stream().noneMatch(e -> e.type() == Auto4DXEngine.Type.BLOOD), "no blood by default");
        var s = new Auto4DXEngine.Settings(); s.blood = true;
        var on = play(new Auto4DXEngine(s), 24 * 8, pic, snd);
        check(on.events.stream().anyMatch(e -> e.type() == Auto4DXEngine.Type.BLOOD), "blood only when enabled and the picture shows it: " + on.events);
    }

    public static void testSeekResetsTheEngine() {
        var eng = new Auto4DXEngine(new Auto4DXEngine.Settings());
        var fa = new FrameAnalyzer();
        for (int i = 0; i < 30; i++) eng.update(fa.analyze(fill(20, 20, 20), W, H, i * 42L), AudioAnalyzer.Levels.SILENT);
        eng.update(fa.analyze(fill(20, 20, 20), W, H, 600_000), AudioAnalyzer.Levels.SILENT);         // jumped 10 minutes ahead
        // right after a seek the averages are settling again, so even a loud onset is not reported yet
        var ev = eng.update(fa.analyze(fill(255, 150, 30), W, H, 600_042), new AudioAnalyzer.Levels(0.9f, 0.8f, 0.3f, 0.2f, 0.8f));
        check(ev.isEmpty(), "no events while settling after a seek: " + ev);
    }

    public static void testReducedMotionSetting() {
        var seat = new SeatMotion();
        seat.scale = 0;
        seat.onEvent(new Auto4DXEngine.Event(Auto4DXEngine.Type.EXPLOSION, 0, 1000, 1, 1, 0));
        for (int i = 0; i < 24; i++) seat.update(1 / 24f);
        check(!seat.moving(), "motion off = seat stays still");
        seat.scale = 1;
        seat.onEvent(new Auto4DXEngine.Event(Auto4DXEngine.Type.OCEAN, 0, 1000, 1, 1, 0));
        float maxRoll = 0;
        for (int i = 0; i < 48; i++) { seat.update(1 / 24f); maxRoll = Math.max(maxRoll, Math.abs(seat.roll)); }
        check(maxRoll > 2, "ocean rocks the seat sideways: " + maxRoll);
    }

    public static void testFrameAnalyzerColourClasses() {
        var fa = new FrameAnalyzer();
        var fire = fa.analyze(fill(250, 120, 20), W, H, 0);
        check(fire.warm() > 0.9f, "orange = warm " + fire.warm());
        var water = fa.analyze(fill(20, 90, 200), W, H, 42);
        check(water.blue() > 0.9f && water.motion() > 0.1f, "blue = water, and the change counts as motion");
        var snow = fa.analyze(fill(235, 238, 240), W, H, 84);
        check(snow.white() > 0.9f, "white");
        // pan: the textured scene shifted right by a few pixels
        fa.reset();
        fa.analyze(scene(100), W, H, 0);
        byte[] shifted = new byte[W * H * 4];
        byte[] base = scene(100);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) System.arraycopy(base, (y * W + Math.max(0, x - 20)) * 4, shifted, (y * W + x) * 4, 4);
        check(fa.analyze(shifted, W, H, 42).panX() > 0, "camera pan detected");
    }
}
