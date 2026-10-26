package earthlife.core.world;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Terrain height in meters above sea level at block coordinates. */
public interface ElevationSource {
    double elevationAt(double x, double z);

    /** True when the value comes from measured data (e.g. USGS 3DEP) rather than a procedural fill. */
    default boolean isMeasured(double x, double z) { return false; }

    /**
     * Regular grid of elevations (produced by tools/dem_to_grid.py from USGS 3DEP GeoTIFFs), bilinearly
     * interpolated. Falls back to {@code fallback} outside the grid.
     * File format (big-endian): magic "ELDG", int version=1, double originX, double originZ,
     * double cellSize, int cols, int rows, then rows*cols floats (row-major, z increasing).
     */
    final class Grid implements ElevationSource {
        final double ox, oz, cell;
        final int cols, rows;
        final float[] h;
        final ElevationSource fallback;

        public Grid(double ox, double oz, double cell, int cols, int rows, float[] h, ElevationSource fallback) {
            this.ox = ox; this.oz = oz; this.cell = cell; this.cols = cols; this.rows = rows; this.h = h; this.fallback = fallback;
        }

        public static Grid load(Path p, ElevationSource fallback) throws IOException {
            try (var raw = Files.newInputStream(p)) { return read(raw, fallback); }
        }

        /** Reads an ELDG grid from a stream (e.g. a gzip stream of a grid bundled in the mod jar). */
        public static Grid read(java.io.InputStream raw, ElevationSource fallback) throws IOException {
            try (var in = new DataInputStream(new java.io.BufferedInputStream(raw))) {
                byte[] magic = in.readNBytes(4);
                if (!new String(magic).equals("ELDG")) throw new IOException("not an EarthLife DEM grid");
                if (in.readInt() != 1) throw new IOException("unsupported DEM grid version");
                double ox = in.readDouble(), oz = in.readDouble(), cell = in.readDouble();
                int cols = in.readInt(), rows = in.readInt();
                float[] h = new float[cols * rows];
                for (int i = 0; i < h.length; i++) h[i] = in.readFloat();
                return new Grid(ox, oz, cell, cols, rows, h, fallback);
            }
        }

        @Override public boolean isMeasured(double x, double z) {
            double gx = (x - ox) / cell, gz = (z - oz) / cell;
            return gx >= 0 && gz >= 0 && gx <= cols - 1 && gz <= rows - 1;
        }

        @Override public double elevationAt(double x, double z) {
            double gx = (x - ox) / cell, gz = (z - oz) / cell;
            if (!isMeasured(x, z)) return fallback.elevationAt(x, z);
            int x0 = Math.min((int) gx, cols - 2), z0 = Math.min((int) gz, rows - 2);
            double fx = gx - x0, fz = gz - z0;
            double a = h[z0 * cols + x0], b = h[z0 * cols + x0 + 1], c = h[(z0 + 1) * cols + x0], d = h[(z0 + 1) * cols + x0 + 1];
            return (a * (1 - fx) + b * fx) * (1 - fz) + (c * (1 - fx) + d * fx) * fz;
        }
    }

    /** Deterministic gentle rolling terrain used where no DEM is available. Clearly an estimate. */
    final class Procedural implements ElevationSource {
        final double base, amp;
        final long seed;
        public Procedural(double base, double amp, long seed) { this.base = base; this.amp = amp; this.seed = seed; }

        @Override public double elevationAt(double x, double z) {
            return base + amp * (0.6 * noise(x / 900, z / 900) + 0.3 * noise(x / 260, z / 260) + 0.1 * noise(x / 70, z / 70));
        }

        private double noise(double x, double z) {
            int xi = (int) Math.floor(x), zi = (int) Math.floor(z);
            double fx = x - xi, fz = z - zi;
            double u = fx * fx * (3 - 2 * fx), v = fz * fz * (3 - 2 * fz);
            double a = hash(xi, zi), b = hash(xi + 1, zi), c = hash(xi, zi + 1), d = hash(xi + 1, zi + 1);
            return (a * (1 - u) + b * u) * (1 - v) + (c * (1 - u) + d * u) * v;
        }

        private double hash(int x, int z) {
            long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
            h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33;
            return ((h >>> 11) / (double) (1L << 53)) * 2 - 1;
        }
    }
}
