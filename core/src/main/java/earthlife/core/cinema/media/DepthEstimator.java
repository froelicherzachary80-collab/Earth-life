package earthlife.core.cinema.media;

import java.util.Arrays;

/**
 * Estimates a coarse depth map (0 = far, 1 = near) of a single video frame for 2D-to-3D conversion.
 *
 * This is the built-in, model-free estimator: it combines monocular depth cues that hold for most film shots -
 * lower in the frame is nearer (ground plane), sharp/high-contrast regions are the focused subject, moving regions are
 * usually foreground, saturated warm regions come forward, and bright, flat, bluish regions at the top are sky (far).
 * The map is smoothed in space, normalised per shot and filtered over time (reset at scene cuts) to avoid the flicker
 * that frame-by-frame estimates cause. It is not a learned model and can't separate overlapping objects the way a
 * neural depth network can; {@link Model} lets a learned estimator replace it.
 */
public final class DepthEstimator {
    /** A pluggable depth model: fills depth[gw*gh] (0 far .. 1 near) for an RGBA frame. */
    public interface Model { void estimate(byte[] rgba, int w, int h, float[] depth, int gw, int gh); String name(); }

    public final int gw, gh;
    private final float[] depth, tmp, prevLuma, smooth;
    private boolean hasPrev;
    private float temporal = 0.25f;
    private Model model;
    public volatile String lastModel = "heuristic cues";

    public DepthEstimator(int gw, int gh) {
        this.gw = gw; this.gh = gh;
        depth = new float[gw * gh]; tmp = new float[gw * gh]; prevLuma = new float[gw * gh]; smooth = new float[gw * gh];
    }

    /** Fraction of the new estimate blended in per frame (lower = steadier, slower to follow). */
    public void temporal(float a) { temporal = Math.max(0.05f, Math.min(1, a)); }
    public void model(Model m) { model = m; }
    public void reset() { hasPrev = false; }
    public float[] map() { return smooth; }

    /** Updates and returns the smoothed depth grid for this frame. */
    public float[] update(byte[] rgba, int w, int h) {
        int n = gw * gh;
        boolean cut = false;
        if (model != null) {
            lumaGrid(rgba, w, h);
            model.estimate(rgba, w, h, depth, gw, gh);
            lastModel = model.name();
        } else {
            heuristic(rgba, w, h);
            lastModel = "heuristic cues";
        }
        // scene cut: a large change of the whole picture -> drop the temporal history
        if (hasPrev) {
            float d = 0; for (int i = 0; i < n; i++) d += Math.abs(tmp[i] - prevLuma[i]);
            cut = d / n > 0.16f;
        }
        System.arraycopy(tmp, 0, prevLuma, 0, n);
        normalise(depth);
        blur(depth, 2);
        if (!hasPrev || cut) System.arraycopy(depth, 0, smooth, 0, n);
        else for (int i = 0; i < n; i++) smooth[i] += (depth[i] - smooth[i]) * temporal;
        hasPrev = true;
        return smooth;
    }

    private void heuristic(byte[] rgba, int w, int h) {
        int n = gw * gh;
        float[] lum = tmp;               // tmp keeps this frame's luma (for cut detection and motion)
        float[] contrast = new float[n], sat = new float[n], sky = new float[n], warm = new float[n];
        for (int gy = 0; gy < gh; gy++) for (int gx = 0; gx < gw; gx++) {
            int x0 = gx * w / gw, x1 = Math.max(x0 + 1, (gx + 1) * w / gw), y0 = gy * h / gh, y1 = Math.max(y0 + 1, (gy + 1) * h / gh);
            int sx = Math.max(1, (x1 - x0) / 4), sy = Math.max(1, (y1 - y0) / 4);
            float sl = 0, ss = 0, sb = 0, sw = 0, grad = 0; int cnt = 0;
            float prevRowL = -1;
            for (int y = y0; y < y1; y += sy) {
                float prevL = -1;
                for (int x = x0; x < x1; x += sx) {
                    int i = (y * w + x) * 4;
                    float r = (rgba[i] & 255) / 255f, g = (rgba[i + 1] & 255) / 255f, b = (rgba[i + 2] & 255) / 255f;
                    float L = 0.299f * r + 0.587f * g + 0.114f * b;
                    float mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
                    sl += L; ss += mx <= 0.001f ? 0 : (mx - mn) / mx; sb += b - (r + g) / 2; sw += r - b;
                    if (prevL >= 0) grad += Math.abs(L - prevL);
                    if (prevRowL >= 0 && x == x0) grad += Math.abs(L - prevRowL);
                    prevL = L; if (x == x0) prevRowL = L;
                    cnt++;
                }
            }
            int c = gy * gw + gx;
            lum[c] = sl / cnt; sat[c] = ss / cnt; contrast[c] = grad / cnt; warm[c] = Math.max(0, sw / cnt);
            float top = 1f - gy / (gh * 0.45f);
            sky[c] = top > 0 && lum[c] > 0.5f && contrast[c] < 0.03f ? top * Math.min(1, 0.5f + Math.max(0, sb / cnt) * 4) : 0;
        }
        float cMax = percentile(contrast, 0.95f) + 1e-4f, sMax = percentile(sat, 0.95f) + 1e-4f;
        for (int gy = 0; gy < gh; gy++) for (int gx = 0; gx < gw; gx++) {
            int c = gy * gw + gx;
            float vertical = (gy + 0.5f) / gh;                                          // lower = nearer
            float centre = 1f - Math.min(1, (float) Math.hypot((gx + 0.5f) / gw - 0.5f, ((gy + 0.5f) / gh - 0.55f) * 0.8f) * 1.6f);
            float motion = hasPrev ? Math.min(1, Math.abs(lum[c] - prevLuma[c]) * 8) : 0;
            depth[c] = 0.42f * (float) Math.pow(vertical, 1.15) + 0.22f * Math.min(1, contrast[c] / cMax) + 0.12f * motion
                    + 0.10f * centre + 0.08f * Math.min(1, sat[c] / sMax) + 0.06f * Math.min(1, warm[c] * 3) - 0.45f * sky[c];
        }
    }

    private void lumaGrid(byte[] rgba, int w, int h) {
        for (int gy = 0; gy < gh; gy++) for (int gx = 0; gx < gw; gx++) {
            int i = ((Math.min(h - 1, (int) ((gy + 0.5f) * h / gh))) * w + Math.min(w - 1, (int) ((gx + 0.5f) * w / gw))) * 4;
            tmp[gy * gw + gx] = (0.299f * (rgba[i] & 255) + 0.587f * (rgba[i + 1] & 255) + 0.114f * (rgba[i + 2] & 255)) / 255f;
        }
    }

    static float percentile(float[] a, float q) {
        float[] c = a.clone(); Arrays.sort(c);
        return c[Math.min(c.length - 1, (int) (q * (c.length - 1)))];
    }

    /** Stretches the map to 0..1 between its 5th and 95th percentile. */
    static void normalise(float[] d) {
        float lo = percentile(d, 0.05f), hi = percentile(d, 0.95f);
        float span = Math.max(1e-4f, hi - lo);
        for (int i = 0; i < d.length; i++) d[i] = Math.max(0, Math.min(1, (d[i] - lo) / span));
    }

    void blur(float[] d, int passes) {
        for (int p = 0; p < passes; p++) {
            for (int y = 0; y < gh; y++) for (int x = 0; x < gw; x++) {
                float s = 0; int c = 0;
                for (int dx = -1; dx <= 1; dx++) { int xx = x + dx; if (xx < 0 || xx >= gw) continue; s += d[y * gw + xx]; c++; }
                tmpBlur(y * gw + x, s / c);
            }
            swapBlur(d);
            for (int y = 0; y < gh; y++) for (int x = 0; x < gw; x++) {
                float s = 0; int c = 0;
                for (int dy = -1; dy <= 1; dy++) { int yy = y + dy; if (yy < 0 || yy >= gh) continue; s += d[yy * gw + x]; c++; }
                tmpBlur(y * gw + x, s / c);
            }
            swapBlur(d);
        }
    }

    private float[] blurBuf;
    private void tmpBlur(int i, float v) { if (blurBuf == null) blurBuf = new float[gw * gh]; blurBuf[i] = v; }
    private void swapBlur(float[] d) { System.arraycopy(blurBuf, 0, d, 0, d.length); }
}
