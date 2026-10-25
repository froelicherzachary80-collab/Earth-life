package earthlife.core.world;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Streams tiles around moving viewers. Near tiles at lod 0, middle ring lod 1, far ring lod 2
 * (used for distant ground seen from aircraft). Generation runs on a worker pool; results go to an
 * in-memory LRU and an on-disk checksummed cache so revisits and offline play skip regeneration.
 */
public final class TileStreamer implements AutoCloseable {
    public record Rings(int lod0, int lod1, int lod2) {
        public static final Rings WALKING = new Rings(2, 4, 6);
        public static final Rings FLYING = new Rings(1, 4, 12);
    }

    private final TileGenerator generator;
    private final Path diskCache;
    private final ModificationStore edits;
    private final ExecutorService pool;
    private final Map<TileKey, TileData> loaded;
    private final Map<TileKey, CompletableFuture<TileData>> inFlight = new ConcurrentHashMap<>();
    private final Consumer<TileData> onReady;
    private final java.util.concurrent.atomic.AtomicInteger generatedCount = new java.util.concurrent.atomic.AtomicInteger(), diskHits = new java.util.concurrent.atomic.AtomicInteger();

    public TileStreamer(TileGenerator generator, Path diskCache, ModificationStore edits, int threads, int maxLoaded, Consumer<TileData> onReady) {
        this.generator = generator;
        this.diskCache = diskCache;
        this.edits = edits;
        this.onReady = onReady == null ? t -> {} : onReady;
        this.pool = Executors.newFixedThreadPool(threads, r -> { Thread t = new Thread(r, "EarthLife-TileGen"); t.setDaemon(true); return t; });
        this.loaded = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<TileKey, TileData> e) { return size() > maxLoaded; }
        });
    }

    /** Desired tile set for a viewer, nearest first. */
    public static List<TileKey> desired(String region, int blockX, int blockZ, Rings rings) {
        TileKey c = TileKey.containing(region, blockX, blockZ, 0);
        List<TileKey> out = new ArrayList<>();
        int r = rings.lod2();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            int d = Math.max(Math.abs(dx), Math.abs(dz));
            int lod = d <= rings.lod0() ? 0 : d <= rings.lod1() ? 1 : 2;
            out.add(new TileKey(region, c.tx() + dx, c.tz() + dz, lod));
        }
        out.sort(Comparator.comparingInt(k -> k.distance(c)));
        return out;
    }

    /** Called every few ticks with each viewer's position. Schedules missing tiles and unloads far ones. */
    public void update(String region, List<int[]> viewerBlockXZ, Rings rings) {
        Set<TileKey> want = new LinkedHashSet<>();
        for (int[] v : viewerBlockXZ) want.addAll(desired(region, v[0], v[1], rings));
        for (TileKey k : want) request(k);
        synchronized (loaded) {
            loaded.keySet().removeIf(k -> k.region().equals(region) && !want.contains(k));
        }
        for (var k : new ArrayList<>(inFlight.keySet())) if (!want.contains(k)) { var f = inFlight.remove(k); if (f != null) f.cancel(false); }
    }

    public CompletableFuture<TileData> request(TileKey k) {
        TileData have = loaded.get(k);
        if (have != null) return CompletableFuture.completedFuture(have);
        CompletableFuture<TileData> existing = inFlight.get(k);
        if (existing != null) return existing;
        CompletableFuture<TileData> f = new CompletableFuture<>();
        existing = inFlight.putIfAbsent(k, f);
        if (existing != null) return existing;
        pool.execute(() -> {
            if (f.isCancelled()) { inFlight.remove(k, f); return; }
            try {
                TileData t = produce(k);
                loaded.put(k, t);
                inFlight.remove(k, f);
                onReady.accept(t);
                f.complete(t);
            } catch (Throwable e) {
                inFlight.remove(k, f);
                f.completeExceptionally(e);
            }
        });
        return f;
    }

    private TileData produce(TileKey k) {
        Path p = diskCache.resolve(k.fileName());
        if (Files.exists(p)) {
            try { TileData t = TileData.fromBytes(Files.readAllBytes(p)); diskHits.incrementAndGet(); return t; }
            catch (IOException e) { System.err.println("[EarthLife] regenerating corrupt cached tile " + k + ": " + e.getMessage()); }
        }
        TileData t = generator.generate(k);
        generatedCount.incrementAndGet();
        try { AtomicFiles.write(p, t.toBytes()); } catch (IOException e) { System.err.println("[EarthLife] tile cache write failed: " + e.getMessage()); }
        return t;
    }

    public TileData getLoaded(TileKey k) { return loaded.get(k); }
    public int loadedCount() { return loaded.size(); }
    public int generatedCount() { return generatedCount.get(); }
    public int diskHits() { return diskHits.get(); }
    public ModificationStore edits() { return edits; }

    public void awaitIdle(long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (!inFlight.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(5);
    }

    @Override public void close() throws IOException {
        pool.shutdownNow();
        if (edits != null) edits.flush();
    }
}
