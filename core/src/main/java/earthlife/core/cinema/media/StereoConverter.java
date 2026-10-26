package earthlife.core.cinema.media;

/**
 * Converts ordinary 2D video frames into red/cyan anaglyph 3D for paper/plastic red-cyan glasses (ZMF Cinema).
 *
 * Pipeline per frame: depth map ({@link DepthEstimator}) -> per-pixel disparity relative to the zero-parallax plane ->
 * left and right views by depth-image-based rendering (each eye samples the original frame shifted by half the
 * disparity; sampling backwards from the output means there are no holes to fill - occluded edges repeat the nearest
 * background pixels instead) -> red/cyan encoding. The default encoding is Dubois' least-squares projection, which
 * keeps more colour and leaks less of each eye's picture into the other (less ghosting) than plain channel splitting.
 *
 * Anaglyph glasses add depth to a flat screen; they can't produce holographic pictures, and colour is always partly
 * lost. Disparities are kept small (about 1% of the picture width) for viewing comfort.
 */
public final class StereoConverter {
    public static final class Settings {
        public float strength = 1.0f;     // overall depth 0..2
        public float zeroPlane = 0.40f;   // depth value that sits exactly on the screen (0 far .. 1 near)
        public float popOut = 0.5f;       // how far in front of the screen things may come, 0..1 of the maximum
        public float screenDepth = 1.0f;  // how far behind the screen the background goes, 0..1.5
        public boolean comfort = false;   // gentler depth, steadier map
        public boolean ghostReduction = true;   // Dubois matrices instead of plain red/cyan channels
        public boolean swapEyes = false;  // for glasses worn the other way (cyan left)
        public boolean compare = false;   // left half: original 2D, right half: 3D
        public int depthGrid = 64;        // depth map width in cells (quality vs CPU)

        public Settings copy() {
            Settings s = new Settings();
            s.strength = strength; s.zeroPlane = zeroPlane; s.popOut = popOut; s.screenDepth = screenDepth; s.comfort = comfort;
            s.ghostReduction = ghostReduction; s.swapEyes = swapEyes; s.compare = compare; s.depthGrid = depthGrid;
            return s;
        }
    }

    private Settings settings;
    private DepthEstimator depth;
    private int dw, dh;
    private float[] colW; private int[] colI;
    public volatile float lastMeanDisparityPx;     // diagnostics: average |disparity| of the last frame
    public volatile long lastMicros;

    public StereoConverter(Settings s) { this.settings = s.copy(); }

    public void settings(Settings s) { this.settings = s.copy(); }
    public Settings settings() { return settings; }
    public DepthEstimator depth() { return depth; }
    public void reset() { if (depth != null) depth.reset(); }

    /** Max disparity in pixels for a picture this wide. */
    public float maxDisparity(int w) { return settings.strength * (settings.comfort ? 0.006f : 0.010f) * w; }

    /** Converts src (RGBA, w x h) into dst (same size). src and dst must be different arrays. */
    public void convert(byte[] src, byte[] dst, int w, int h) {
        long t0 = System.nanoTime();
        Settings st = settings;
        int gw = Math.max(16, Math.min(160, st.depthGrid)), gh = Math.max(9, Math.round(gw * h / (float) w));
        if (depth == null || depth.gw != gw || depth.gh != gh) { depth = new DepthEstimator(gw, gh); dw = gw; dh = gh; colW = null; }
        depth.temporal(st.comfort ? 0.12f : 0.25f);
        float[] d = depth.update(src, w, h);
        if (colW == null || colW.length != w) {
            colW = new float[w]; colI = new int[w];
            for (int x = 0; x < w; x++) {
                float gx = (x + 0.5f) * dw / w - 0.5f;
                int i0 = (int) Math.floor(gx); float f = gx - i0;
                colI[x] = Math.max(0, Math.min(dw - 2, i0)); colW[x] = i0 < 0 ? 0 : i0 >= dw - 1 ? 1 : f;
            }
        }
        float maxD = maxDisparity(w);
        float front = st.popOut, back = st.screenDepth;
        float zero = st.zeroPlane;
        boolean swap = st.swapEyes, dubois = st.ghostReduction;
        int compareX = st.compare ? w / 2 : -1;
        double sumAbs = 0;
        float[] row = new float[w];
        for (int y = 0; y < h; y++) {
            float gy = (y + 0.5f) * dh / h - 0.5f;
            int r0 = Math.max(0, Math.min(dh - 2, (int) Math.floor(gy)));
            float fy = Math.max(0, Math.min(1, gy - r0));
            int base0 = r0 * dw, base1 = (r0 + 1) * dw;
            for (int x = 0; x < w; x++) {
                int ci = colI[x]; float fx = colW[x];
                float v0 = d[base0 + ci] + (d[base0 + ci + 1] - d[base0 + ci]) * fx;
                float v1 = d[base1 + ci] + (d[base1 + ci + 1] - d[base1 + ci]) * fx;
                float z = v0 + (v1 - v0) * fy - zero;                   // >0 in front of the screen
                float disp = z > 0 ? Math.min(z / Math.max(0.05f, 1 - zero), 1f) * front : Math.max(z / Math.max(0.05f, zero), -1f) * back;
                row[x] = disp * maxD;
            }
            int line = y * w * 4;
            for (int x = 0; x < w; x++) {
                int o = line + x * 4;
                if (x < compareX) { dst[o] = src[o]; dst[o + 1] = src[o + 1]; dst[o + 2] = src[o + 2]; dst[o + 3] = (byte) 255; continue; }
                float half = row[x] * 0.5f;
                sumAbs += Math.abs(row[x]);
                // left eye sees near things shifted right (crossed disparity): L(x) = S(x - d/2), R(x) = S(x + d/2)
                float xl = x - half, xr = x + half;
                if (swap) { float t = xl; xl = xr; xr = t; }
                int il = line + clampX(Math.round(xl), w) * 4, ir = line + clampX(Math.round(xr), w) * 4;
                int lr = src[il] & 255, lg = src[il + 1] & 255, lb = src[il + 2] & 255;
                int rr = src[ir] & 255, rg = src[ir + 1] & 255, rb = src[ir + 2] & 255;
                int R, G, B;
                if (dubois) {
                    R = (int) (0.4561f * lr + 0.500484f * lg + 0.176381f * lb - 0.0434706f * rr - 0.0879388f * rg - 0.00155529f * rb);
                    G = (int) (-0.0400822f * lr - 0.0378246f * lg - 0.0157589f * lb + 0.378476f * rr + 0.73364f * rg - 0.0184503f * rb);
                    B = (int) (-0.0152161f * lr - 0.0205971f * lg - 0.00546856f * lb - 0.0721527f * rr - 0.112961f * rg + 1.2264f * rb);
                } else {
                    R = (int) (0.299f * lr + 0.587f * lg + 0.114f * lb);    // half-colour: left eye's luminance in red
                    G = rg; B = rb;
                }
                dst[o] = (byte) Math.max(0, Math.min(255, R));
                dst[o + 1] = (byte) Math.max(0, Math.min(255, G));
                dst[o + 2] = (byte) Math.max(0, Math.min(255, B));
                dst[o + 3] = (byte) 255;
            }
        }
        lastMeanDisparityPx = (float) (sumAbs / Math.max(1, (long) (w - Math.max(0, compareX)) * h));
        lastMicros = (System.nanoTime() - t0) / 1000;
    }

    static int clampX(int x, int w) { return x < 0 ? 0 : x >= w ? w - 1 : x; }
}
