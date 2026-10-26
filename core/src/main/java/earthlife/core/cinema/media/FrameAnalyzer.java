package earthlife.core.cinema.media;

/**
 * Cheap per-frame picture analysis on a coarse grid (default 32 x 18 cells) of an RGBA frame: brightness, colour
 * classes, motion against the previous frame, horizontal camera pan and how much of the picture flickers. Used by the
 * 4DX event recogniser and the depth estimator. Costs well under a millisecond per frame (it samples, it doesn't scan
 * every pixel).
 */
public final class FrameAnalyzer {
    /** What one frame looks like. Fractions are of the grid cells (0..1). */
    public record Features(long ptsMs, float luma, float lumaDelta, float motion, float panX, float warm, float red, float blue,
                           float white, float dark, float magic, float skyBlue, float saturation, float lumaLeft, float lumaRight,
                           boolean sceneCut) {
        public static Features empty(long t) { return new Features(t, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false); }
    }

    public final int gw, gh;
    final float[] l, s, hue, prevL;
    private boolean hasPrev;
    private float prevLuma;

    public FrameAnalyzer() { this(32, 18); }
    public FrameAnalyzer(int gw, int gh) {
        this.gw = gw; this.gh = gh;
        l = new float[gw * gh]; s = new float[gw * gh]; hue = new float[gw * gh]; prevL = new float[gw * gh];
    }

    public void reset() { hasPrev = false; }

    /** Grid luma of the last analysed frame (row major, 0..1). */
    public float[] luma() { return l; }
    public float[] sat() { return s; }
    public float[] hue() { return hue; }

    public Features analyze(byte[] rgba, int w, int h, long ptsMs) {
        int n = gw * gh;
        float sumL = 0, sumS = 0, warm = 0, red = 0, blue = 0, white = 0, dark = 0, magic = 0, sky = 0, left = 0, right = 0;
        int skyCells = 0;
        for (int gy = 0; gy < gh; gy++) for (int gx = 0; gx < gw; gx++) {
            // average 4 samples per cell (corners of the inner quarter)
            float r = 0, g = 0, b = 0;
            for (int k = 0; k < 4; k++) {
                int x = (int) ((gx + 0.25f + 0.5f * (k & 1)) * w / gw), y = (int) ((gy + 0.25f + 0.5f * (k >> 1)) * h / gh);
                int i = (Math.min(h - 1, y) * w + Math.min(w - 1, x)) * 4;
                r += rgba[i] & 255; g += rgba[i + 1] & 255; b += rgba[i + 2] & 255;
            }
            r /= 1020f; g /= 1020f; b /= 1020f;
            float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
            float lum = 0.299f * r + 0.587f * g + 0.114f * b, sat = max <= 0.001f ? 0 : (max - min) / max;
            float hh = 0;
            if (max - min > 1e-4f) {
                if (max == r) hh = ((g - b) / (max - min)) % 6; else if (max == g) hh = (b - r) / (max - min) + 2; else hh = (r - g) / (max - min) + 4;
                hh *= 60; if (hh < 0) hh += 360;
            }
            int c = gy * gw + gx;
            l[c] = lum; s[c] = sat; hue[c] = hh;
            sumL += lum; sumS += sat;
            if (sat > 0.45f && lum > 0.25f && (hh < 45 || hh > 345)) warm++;               // fire, explosions, sunsets
            if (sat > 0.55f && lum > 0.08f && lum < 0.45f && (hh < 12 || hh > 345)) red++;    // deep red (blood, alarms)
            if (sat > 0.3f && hh > 170 && hh < 250) blue++;                                   // water, ice, night skies
            if (sat < 0.18f && lum > 0.72f) white++;                                          // snow, flashes, clouds
            if (lum < 0.12f) dark++;
            if (sat > 0.5f && lum > 0.35f && ((hh > 260 && hh < 330) || (hh > 90 && hh < 160))) magic++;   // purple / vivid green energy
            if (gy < gh * 0.4f) { skyCells++; if (lum > 0.45f && hh > 180 && hh < 240 && sat > 0.15f) sky++; }
            if (gx < gw / 3) left += lum; else if (gx >= gw - gw / 3) right += lum;
        }
        float luma = sumL / n;
        float motion = 0;
        float panX = 0;
        if (hasPrev) {
            float d = 0;
            for (int i = 0; i < n; i++) d += Math.abs(l[i] - prevL[i]);
            motion = Math.min(1f, d / n * 6f);
            panX = pan();
        }
        boolean cut = hasPrev && motion > 0.75f && Math.abs(luma - prevLuma) > 0.08f;
        float delta = hasPrev ? luma - prevLuma : 0;
        System.arraycopy(l, 0, prevL, 0, n);
        prevLuma = luma; hasPrev = true;
        int third = Math.max(1, (gw / 3) * gh);
        return new Features(ptsMs, luma, delta, motion, panX, warm / n, red / n, blue / n, white / n, dark / n, magic / n,
                skyCells == 0 ? 0 : sky / skyCells, sumS / n, left / third, right / third, cut);
    }

    /** Horizontal shift (cells, -1..1 of a quarter width) that best aligns this frame with the previous one. */
    private float pan() {
        int maxShift = Math.max(1, gw / 8);
        float best = Float.MAX_VALUE; int bestS = 0;
        for (int sh = -maxShift; sh <= maxShift; sh++) {
            float d = 0; int cnt = 0;
            for (int y = 0; y < gh; y++) for (int x = Math.max(0, sh); x < Math.min(gw, gw + sh); x++) {
                d += Math.abs(l[y * gw + x] - prevL[y * gw + x - sh]); cnt++;
            }
            d /= Math.max(1, cnt);
            if (d < best - 1e-5f) { best = d; bestS = sh; }
        }
        return bestS / (float) maxShift;
    }
}
