package earthlife.core.cinema.media;

/**
 * Loudness of a film's soundtrack in four bands, from 16-bit little-endian stereo PCM as it is played: overall RMS, bass
 * (below ~150 Hz: explosions, engines, thunder), mid (~300 Hz-2 kHz: voices, engines), high (above ~4 kHz: rain, hiss,
 * shattering glass), plus the onset (how much louder than the recent average). All values 0..1.
 */
public final class AudioAnalyzer {
    public record Levels(float loud, float bass, float mid, float high, float onset) {
        public static final Levels SILENT = new Levels(0, 0, 0, 0, 0);
    }

    private final float aBass, aMidLo, aMidHi, aHigh;
    private float lpBass, lpMidLo, lpMidHi, lpHigh, prevSample;
    private float avgLoud;
    private volatile Levels last = Levels.SILENT;

    public AudioAnalyzer(int sampleRate) {
        aBass = alpha(150, sampleRate); aMidLo = alpha(300, sampleRate); aMidHi = alpha(2000, sampleRate); aHigh = alpha(4000, sampleRate);
    }

    static float alpha(double fc, int rate) { double rc = 1.0 / (2 * Math.PI * fc), dt = 1.0 / rate; return (float) (dt / (rc + dt)); }

    public Levels last() { return last; }
    public void reset() { lpBass = lpMidLo = lpMidHi = lpHigh = prevSample = 0; avgLoud = 0; last = Levels.SILENT; }

    /** Analyses one chunk of interleaved stereo s16le samples (len bytes from off). */
    public Levels feed(byte[] buf, int off, int len) {
        int frames = len / 4;
        if (frames == 0) return last;
        double sq = 0, b = 0, m = 0, hi = 0;
        for (int i = 0; i < frames; i++) {
            int p = off + i * 4;
            int lch = (short) ((buf[p] & 255) | (buf[p + 1] << 8)), rch = (short) ((buf[p + 2] & 255) | (buf[p + 3] << 8));
            float x = (lch + rch) / 65536f;
            sq += x * x;
            lpBass += aBass * (x - lpBass);
            lpMidLo += aMidLo * (x - lpMidLo);
            lpMidHi += aMidHi * (x - lpMidHi);
            lpHigh += aHigh * (x - lpHigh);
            float mid = lpMidHi - lpMidLo, high = x - lpHigh;
            b += lpBass * lpBass; m += mid * mid; hi += high * high;
        }
        float loud = (float) Math.min(1, Math.sqrt(sq / frames) * 3.2);
        float bass = (float) Math.min(1, Math.sqrt(b / frames) * 4.0);
        float mid = (float) Math.min(1, Math.sqrt(m / frames) * 5.0);
        float high = (float) Math.min(1, Math.sqrt(hi / frames) * 8.0);
        float onset = Math.max(0, loud - avgLoud);
        avgLoud += (loud - avgLoud) * 0.04f;
        return last = new Levels(loud, bass, mid, high, onset);
    }
}
