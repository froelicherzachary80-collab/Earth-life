package earthlife.mod.client.map;

import com.mojang.blaze3d.platform.NativeImage;
import earthlife.core.world.Features;
import earthlife.mod.EarthLifeMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.*;
import java.util.concurrent.*;

/**
 * Draws LifeMap tiles (256x256 px) into textures: terrain shading from the elevation grid, Lake Ontario and other water,
 * land cover, building footprints and roads. Rasterising runs on a background thread; uploads happen on the render
 * thread a few per frame. Tiles are cached (LRU, 160 textures).
 */
public final class MapRaster {
    public static final int TILE = 256;
    /** Blocks per map pixel for each zoom level (index 0 = most zoomed in). */
    public static final double[] BPP = {0.25, 0.5, 1, 2, 4, 8, 16, 32, 64, 128, 256, 512};
    /** Names shown for each zoom level (building ... state). */
    public static final String[] ZOOM_NAMES = {"Building", "Building", "Street", "Street", "Neighbourhood", "Neighbourhood", "Town", "Town", "City", "Region", "Region", "State"};

    public static final class Layers {
        public boolean terrain = true, buildings = true, roads = true, water = true, landcover = true, railways = true, aeroways = true, satellite = false;
    }

    record Key(int zoom, int tx, int tz, int layers) {}

    private static final ExecutorService pool = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "EarthLife-MapRaster"); t.setDaemon(true); return t; });
    private static final Map<Key, Identifier> textures = new LinkedHashMap<>(256, 0.75f, true);
    private static final Map<Key, Future<int[]>> pending = new HashMap<>();
    private static int generation;

    public static synchronized void clear() {
        generation++;
        pending.clear();
        var tm = Minecraft.getInstance().getTextureManager();
        for (Identifier id : textures.values()) tm.release(id);
        textures.clear();
    }

    static int mask(Layers l) {
        return (l.terrain ? 1 : 0) | (l.buildings ? 2 : 0) | (l.roads ? 4 : 0) | (l.water ? 8 : 0) | (l.landcover ? 16 : 0)
                | (l.railways ? 32 : 0) | (l.aeroways ? 64 : 0) | (l.satellite ? 128 : 0);
    }

    /** Texture for a tile, or null while it's still being drawn (requested on first call). */
    public static synchronized Identifier tile(ClientMapData data, int zoom, int tx, int tz, Layers layers, int[] uploadBudget) {
        Key k = new Key(zoom, tx, tz, mask(layers));
        Identifier id = textures.get(k);
        if (id != null) return id;
        Future<int[]> f = pending.get(k);
        if (f == null) {
            final int gen = generation;
            pending.put(k, pool.submit(() -> gen == generation ? draw(data, zoom, tx, tz, layers) : null));
            return null;
        }
        if (!f.isDone() || uploadBudget[0] <= 0) return null;
        pending.remove(k);
        try {
            int[] px = f.get();
            if (px == null) return null;
            uploadBudget[0]--;
            NativeImage img = new NativeImage(TILE, TILE, false);
            for (int y = 0; y < TILE; y++) for (int x = 0; x < TILE; x++) img.setPixel(x, y, px[y * TILE + x]);
            id = EarthLifeMod.id("lifemap/" + zoom + "_" + tx + "_" + tz + "_" + k.layers() + "_" + generation);
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "EarthLife map tile", img));
            textures.put(k, id);
            if (textures.size() > 160) {
                var it = textures.entrySet().iterator();
                var old = it.next();
                Minecraft.getInstance().getTextureManager().release(old.getValue());
                it.remove();
            }
            return id;
        } catch (Exception e) {
            EarthLifeMod.LOG.warn("map tile", e);
            return null;
        }
    }

    // ------------------------------------------------------------------ rasterizer (background thread)

    static int[] draw(ClientMapData d, int zoom, int tx, int tz, Layers layers) {
        double bpp = BPP[zoom];
        double x0 = tx * TILE * bpp, z0 = tz * TILE * bpp;
        int[] px = new int[TILE * TILE];
        // Terrain: elevation-tinted land, water below the Lake Ontario surface (74.2 m).
        for (int y = 0; y < TILE; y++) for (int x = 0; x < TILE; x++) {
            double wx = x0 + (x + 0.5) * bpp, wz = z0 + (y + 0.5) * bpp;
            double e = d.elevation.elevationAt(wx, wz);
            int c;
            if (layers.water && e < 74.2) c = layers.satellite ? blend(0xFF1E3F5C, 0xFF0E2238, Math.min(1, (74.2 - e) / 40)) : depthBlue(74.2 - e);
            else if (!layers.terrain) c = 0xFFE8E4D8;
            else {
                double shade = d.elevation.elevationAt(wx - bpp * 2, wz - bpp * 2) - e;   // light from the north-west
                c = layers.satellite ? satLand(e, shade / Math.max(1, bpp), x, y) : landColor(e, shade / Math.max(1, bpp));
            }
            px[y * TILE + x] = c;
        }
        double x1 = x0 + TILE * bpp, z1 = z0 + TILE * bpp;
        Collection<Object> feats = d.query(x0, z0, x1, z1);
        if (layers.landcover) for (Object o : feats) if (o instanceof Features.Area a) fill(px, a.ring(), x0, z0, bpp, layers.satellite ? satCover(a.cover()) : coverColor(a.cover()));
        if (layers.water) {
            for (Object o : feats) if (o instanceof Features.Water w) fill(px, w.ring(), x0, z0, bpp, layers.satellite ? 0xFF22445E : 0xFF6FA8DC);
            for (Object o : feats) if (o instanceof ClientMapData.Line l) line(px, l.pts(), x0, z0, bpp, 1.5, 0xFF3D6E9E);
        }
        if (layers.buildings && bpp <= 8) for (Object o : feats) if (o instanceof Features.Building b) {
            boolean est = b.prov() == Features.Provenance.ESTIMATED;
            int c = "terminal".equals(b.kind()) || "commercial".equals(b.kind()) || "retail".equals(b.kind()) ? 0xFFB98F6E
                    : "barn".equals(b.kind()) || "farm_auxiliary".equals(b.kind()) ? 0xFFB5544B : 0xFF9C8F84;
            if (layers.satellite) c = (Math.abs(b.id().hashCode()) % 3 == 0) ? 0xFF7A6F66 : (Math.abs(b.id().hashCode()) % 3 == 1 ? 0xFF5B5550 : 0xFF8E8A86);
            fill(px, b.footprint(), x0, z0, bpp, est ? blend(c, 0xFFD0CCC4, 0.45) : c);
            if (bpp <= 1) outline(px, b.footprint(), x0, z0, bpp, 0xFF5E544C);
        }
        if (layers.roads) {
            for (int pass = 0; pass < 2; pass++) for (Object o : feats) if (o instanceof ClientMapData.NamedRoad nr) {
                Features.Road r = nr.road();
                var cls = r.cls();
                if (cls == Features.RoadClass.RAIL && !layers.railways) continue;
                if ((cls == Features.RoadClass.RUNWAY || cls == Features.RoadClass.TAXIWAY) && !layers.aeroways) continue;
                if (bpp > 16 && !(cls == Features.RoadClass.MOTORWAY || cls == Features.RoadClass.PRIMARY || cls == Features.RoadClass.RAIL)) continue;
                if (bpp > 4 && (cls == Features.RoadClass.SERVICE || cls == Features.RoadClass.PATH)) continue;
                double wPx = Math.max(cls == Features.RoadClass.PATH ? 0.8 : 1.2, r.widthM() / bpp);
                if (pass == 0) line(px, r.line(), x0, z0, bpp, wPx + 1.2, casing(cls));
                else line(px, r.line(), x0, z0, bpp, wPx, roadColor(cls));
            }
        }
        return px;
    }

    /** "Satellite-inspired" cartography: an imagery-like palette drawn from the same map data (not real satellite imagery). */
    static int satLand(double elev, double shade, int x, int y) {
        double t = Math.max(0, Math.min(1, (elev - 74) / 250));
        int base = blend(0xFF5D7A3C, 0xFF77744A, t);
        int noise = ((x * 7349 + y * 1871) & 15) - 8;
        base = blend(base, noise > 0 ? 0xFF6E8A44 : 0xFF4F6A33, Math.abs(noise) / 32.0);
        double s = Math.max(-0.3, Math.min(0.3, shade * 0.1));
        return s >= 0 ? blend(base, 0xFFFFFFFF, s) : blend(base, 0xFF000000, -s);
    }

    static int satCover(Features.Landcover c) {
        return switch (c) {
            case FARMLAND -> 0xFF8E8B55; case ORCHARD -> 0xFF5E7E3A; case FOREST -> 0xFF35512A; case GRASS -> 0xFF6A8A45;
            case RESIDENTIAL -> 0xFF7D7F6A; case INDUSTRIAL -> 0xFF8A8580; case BEACH -> 0xFFCDBF92; case WETLAND -> 0xFF4E6447; case AIRPORT_APRON -> 0xFF9A9896;
        };
    }

    static int depthBlue(double depth) {
        double t = Math.min(1, depth / 60);
        return blend(0xFF8EC1EA, 0xFF2F6CA6, t);
    }

    static int landColor(double elev, double shade) {
        double t = Math.max(0, Math.min(1, (elev - 74) / 300));
        int base = blend(0xFFCFE3B4, 0xFFB7A57E, t);
        double s = Math.max(-0.25, Math.min(0.25, shade * 0.08));
        return s >= 0 ? blend(base, 0xFFFFFFFF, s) : blend(base, 0xFF000000, -s);
    }

    static int coverColor(Features.Landcover c) {
        return switch (c) {
            case FARMLAND -> 0xFFE6DFA8; case ORCHARD -> 0xFFC8DFA0; case FOREST -> 0xFF9CC48A; case GRASS -> 0xFFC5E3A6;
            case RESIDENTIAL -> 0xFFE4DEDA; case INDUSTRIAL -> 0xFFDAD0DE; case BEACH -> 0xFFF3E6B5; case WETLAND -> 0xFFA9CBB5; case AIRPORT_APRON -> 0xFFD7D3D8;
        };
    }

    static int roadColor(Features.RoadClass c) {
        return switch (c) {
            case MOTORWAY -> 0xFFF2A65A; case PRIMARY -> 0xFFF7D774; case SECONDARY -> 0xFFFBF0B0; case RESIDENTIAL, SERVICE -> 0xFFFFFFFF;
            case PATH -> 0xFFB48A62; case RUNWAY -> 0xFF5A5A5A; case TAXIWAY -> 0xFF8C8C8C; case RAIL -> 0xFF6B6B6B;
        };
    }

    static int casing(Features.RoadClass c) {
        return switch (c) { case MOTORWAY -> 0xFFB06A28; case PRIMARY, SECONDARY -> 0xFFB79E50; case PATH -> 0x00000000; default -> 0xFF9A9A9A; };
    }

    static int blend(int a, int b, double t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    /** Even-odd scanline polygon fill. */
    static void fill(int[] px, List<Features.Pt> ring, double x0, double z0, double bpp, int color) {
        int n = ring.size();
        if (n < 3 || (color >>> 24) == 0) return;
        double[] xs = new double[n], ys = new double[n];
        double miny = Double.MAX_VALUE, maxy = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) { xs[i] = (ring.get(i).x() - x0) / bpp; ys[i] = (ring.get(i).z() - z0) / bpp; miny = Math.min(miny, ys[i]); maxy = Math.max(maxy, ys[i]); }
        int ya = Math.max(0, (int) Math.floor(miny)), yb = Math.min(TILE - 1, (int) Math.ceil(maxy));
        double[] cross = new double[n];
        for (int y = ya; y <= yb; y++) {
            double sy = y + 0.5;
            int k = 0;
            for (int i = 0, j = n - 1; i < n; j = i++)
                if ((ys[i] > sy) != (ys[j] > sy)) cross[k++] = xs[i] + (sy - ys[i]) * (xs[j] - xs[i]) / (ys[j] - ys[i]);
            Arrays.sort(cross, 0, k);
            for (int c = 0; c + 1 < k; c += 2) {
                int xa = Math.max(0, (int) Math.ceil(cross[c] - 0.5)), xb = Math.min(TILE - 1, (int) Math.floor(cross[c + 1] - 0.5));
                for (int x = xa; x <= xb; x++) px[y * TILE + x] = color;
            }
            if (yb - ya < 1 && k >= 2) { int x = (int) Math.round(cross[0]); if (x >= 0 && x < TILE) px[y * TILE + x] = color; }
        }
        if (yb < ya || (maxy - miny) < 1) {   // tiny footprint at this zoom: one pixel
            int x = (int) Math.round(xs[0]), y = (int) Math.round(ys[0]);
            if (x >= 0 && x < TILE && y >= 0 && y < TILE) px[y * TILE + x] = color;
        }
    }

    static void outline(int[] px, List<Features.Pt> ring, double x0, double z0, double bpp, int color) {
        List<Features.Pt> closed = new ArrayList<>(ring);
        if (!ring.isEmpty()) closed.add(ring.get(0));
        line(px, closed, x0, z0, bpp, 1, color);
    }

    /** Thick polyline: stamps discs along each segment. */
    static void line(int[] px, List<Features.Pt> pts, double x0, double z0, double bpp, double width, int color) {
        if ((color >>> 24) == 0) return;
        double r = width / 2;
        for (int i = 1; i < pts.size(); i++) {
            double ax = (pts.get(i - 1).x() - x0) / bpp, ay = (pts.get(i - 1).z() - z0) / bpp, bx = (pts.get(i).x() - x0) / bpp, by = (pts.get(i).z() - z0) / bpp;
            if (Math.max(ax, bx) < -r - 1 || Math.min(ax, bx) > TILE + r || Math.max(ay, by) < -r - 1 || Math.min(ay, by) > TILE + r) continue;
            double len = Math.hypot(bx - ax, by - ay);
            int steps = Math.max(1, (int) Math.ceil(len / Math.max(0.5, r * 0.7)));
            for (int s = 0; s <= steps; s++) {
                double cx = ax + (bx - ax) * s / steps, cy = ay + (by - ay) * s / steps;
                int xa = (int) Math.floor(cx - r), xb = (int) Math.ceil(cx + r), ya = (int) Math.floor(cy - r), yb = (int) Math.ceil(cy + r);
                for (int y = Math.max(0, ya); y <= Math.min(TILE - 1, yb); y++)
                    for (int x = Math.max(0, xa); x <= Math.min(TILE - 1, xb); x++) {
                        double dx = x + 0.5 - cx, dy = y + 0.5 - cy;
                        if (dx * dx + dy * dy <= r * r + 0.25) px[y * TILE + x] = color;
                    }
            }
        }
    }
}
