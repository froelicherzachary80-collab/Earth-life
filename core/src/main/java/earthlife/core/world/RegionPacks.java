package earthlife.core.world;

import earthlife.core.util.Json;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The downloadable 1:1 Northeast map (New York, Pennsylvania, New Jersey, Vermont), built by the "Region packs"
 * GitHub workflow and published as release assets: index.json, 1/2-degree packs of 16 cells, places, major roads and
 * cinemas. Packs are fetched the first time the game needs that area, checked against the index's SHA-256 and kept in
 * a cache folder shared by all worlds (and by the client and the integrated server).
 */
public final class RegionPacks implements AutoCloseable {
    public static final String DEFAULT_BASE = "https://github.com/froelicherzachary80-collab/Earth-life/releases/download/regions-v1/";

    public record PackInfo(String name, long size, String sha256, Map<String, Integer> cells) {}

    private static final Map<Path, RegionPacks> SHARED = new HashMap<>();

    public final Path dir;
    public final String base;
    private final boolean online;
    private final HttpClient http;
    private final ExecutorService io;
    private volatile Map<String, PackInfo> packs = Map.of();
    private final Map<Long, String> cellToPack = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Path>> downloads = new ConcurrentHashMap<>();
    private final Map<String, ZipFile> zips = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, ZipFile> e) {
            if (size() <= 6) return false;
            try { e.getValue().close(); } catch (IOException ignored) {}
            return true;
        }
    });
    private final Set<String> verified = ConcurrentHashMap.newKeySet();
    public volatile String status = "map data: not loaded";
    public volatile String indexBuilt = "";
    public volatile long downloadedBytes;
    public final List<String> errors = new CopyOnWriteArrayList<>();

    private RegionPacks(Path dir, String base, boolean online) {
        this.dir = dir; this.base = base.endsWith("/") ? base : base + "/"; this.online = online;
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(20)).build();
        this.io = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "EarthLife-RegionPacks"); t.setDaemon(true); return t; });
    }

    /** One manager per cache folder in this JVM (singleplayer client + integrated server share downloads). */
    public static synchronized RegionPacks shared(Path dir, String base, boolean online) {
        return SHARED.computeIfAbsent(dir.toAbsolutePath().normalize(), d -> new RegionPacks(d, base, online));
    }

    static long key(int lat8, int lon8) { return ((long) lat8 << 32) ^ (lon8 & 0xffffffffL); }

    // ------------------------------------------------------------------ index

    /** Loads index.json: a fresh copy from the release when online (then cached), else the cached copy. */
    public synchronized boolean loadIndex() {
        if (!packs.isEmpty()) return true;
        Path cached = dir.resolve("index.json");
        String json = null;
        if (online) {
            try {
                byte[] b = fetch(base + "index.json", 30);
                json = new String(b, java.nio.charset.StandardCharsets.UTF_8);
                Files.createDirectories(dir);
                AtomicFiles.write(cached, b);
            } catch (Exception e) {
                errors.add("index: " + e.getMessage());
            }
        }
        if (json == null && Files.exists(cached)) try { json = Files.readString(cached); } catch (IOException ignored) {}
        if (json == null) { status = "map data: no index (offline and nothing downloaded yet)"; return false; }
        parseIndex(json);
        status = "map data: " + packs.size() + " packs available";
        return true;
    }

    /** For tests and offline use: an index already on disk. */
    public synchronized void parseIndex(String json) {
        var root = Json.obj(Json.parse(json));
        indexBuilt = String.valueOf(root.getOrDefault("built", ""));
        Map<String, PackInfo> m = new LinkedHashMap<>();
        for (var e : Json.obj(root.get("packs")).entrySet()) {
            var p = Json.obj(e.getValue());
            Map<String, Integer> cells = new HashMap<>();
            for (var c : Json.obj(p.get("cells")).entrySet()) {
                cells.put(c.getKey(), (int) Json.num(c.getValue()));
                String[] ll = c.getKey().split("_");
                cellToPack.put(key(Integer.parseInt(ll[0]), Integer.parseInt(ll[1])), e.getKey());
            }
            m.put(e.getKey(), new PackInfo(e.getKey(), (long) Json.num(p.get("size")), Json.str(p, "sha256"), cells));
        }
        packs = m;
    }

    public boolean ready() { return !packs.isEmpty(); }
    public int packCount() { return packs.size(); }
    public boolean covered(int lat8, int lon8) { return cellToPack.containsKey(key(lat8, lon8)); }
    public Optional<RegionCell.Tier> tier(int lat8, int lon8) {
        String p = cellToPack.get(key(lat8, lon8));
        if (p == null) return Optional.empty();
        Integer t = packs.get(p).cells().get(lat8 + "_" + lon8);
        return Optional.ofNullable(t).map(i -> RegionCell.Tier.values()[Math.min(3, i)]);
    }
    public Optional<PackInfo> packOf(int lat8, int lon8) { return Optional.ofNullable(cellToPack.get(key(lat8, lon8))).map(packs::get); }
    public boolean downloaded(String pack) { return Files.exists(dir.resolve("packs").resolve(pack)); }

    // ------------------------------------------------------------------ packs

    /** The local file of a pack, downloading (and verifying) it first if needed. */
    public CompletableFuture<Path> pack(String name) {
        PackInfo info = packs.get(name);
        if (info == null) return CompletableFuture.failedFuture(new FileNotFoundException("no pack " + name));
        Path file = dir.resolve("packs").resolve(name);
        if (Files.exists(file) && (verified.contains(name) || sizeOk(file, info))) return CompletableFuture.completedFuture(file);
        return downloads.computeIfAbsent(name, n -> CompletableFuture.supplyAsync(() -> {
            try {
                if (!online) throw new IOException("offline - map data for this area isn't downloaded yet");
                status = String.format("downloading map data %s (%.1f MB)", name, info.size() / 1e6);
                Files.createDirectories(file.getParent());
                Path part = file.resolveSibling(name + ".part");
                HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(base + name)).timeout(Duration.ofMinutes(20))
                        .header("User-Agent", "EarthLife/0.4.1 (Minecraft mod)").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode() + " for " + name);
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(part)) {
                    byte[] buf = new byte[1 << 16]; int k;
                    while ((k = in.read(buf)) > 0) { out.write(buf, 0, k); md.update(buf, 0, k); downloadedBytes += k; }
                }
                String sha = HexFormat.of().formatHex(md.digest());
                if (info.sha256() != null && !info.sha256().equalsIgnoreCase(sha)) { Files.deleteIfExists(part); throw new IOException("checksum mismatch for " + name); }
                Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                verified.add(name);
                status = "map data: " + name + " ready";
                return file;
            } catch (Exception e) {
                errors.add(name + ": " + e.getMessage());
                status = "map data: " + e.getMessage();
                throw new CompletionException(e);
            } finally {
                downloads.remove(name);
            }
        }, io));
    }

    private boolean sizeOk(Path f, PackInfo info) {
        try { if (Files.size(f) == info.size()) { verified.add(info.name()); return true; } } catch (IOException ignored) {}
        return false;
    }

    /** Raw bytes of one cell (null if the area isn't covered by the Northeast map). */
    public byte[] cellBytes(int lat8, int lon8, long timeoutMs) throws Exception {
        String p = cellToPack.get(key(lat8, lon8));
        if (p == null) return null;
        Path file = pack(p).get(timeoutMs, TimeUnit.MILLISECONDS);
        ZipFile z;
        synchronized (zips) {
            z = zips.get(p);
            if (z == null) { z = new ZipFile(file.toFile()); zips.put(p, z); }
        }
        synchronized (z) {
            ZipEntry e = z.getEntry(RegionCell.name(lat8, lon8));
            if (e == null) return null;
            try (InputStream in = z.getInputStream(e)) { return in.readAllBytes(); }
        }
    }

    // ------------------------------------------------------------------ globals (places, major roads, cinemas)

    public byte[] global(String name, long maxAgeMs) throws Exception {
        Path f = dir.resolve(name);
        if (Files.exists(f) && (!online || System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis() < maxAgeMs)) return Files.readAllBytes(f);
        try {
            byte[] b = fetch(base + name, 120);
            Files.createDirectories(dir);
            AtomicFiles.write(f, b);
            return b;
        } catch (Exception e) {
            if (Files.exists(f)) return Files.readAllBytes(f);
            throw e;
        }
    }

    private byte[] fetch(String url, int timeoutS) throws Exception {
        var r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutS)).header("User-Agent", "EarthLife/0.4.1 (Minecraft mod)").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode() + " for " + url);
        return r.body();
    }

    @Override public void close() {
        synchronized (zips) { for (ZipFile z : zips.values()) try { z.close(); } catch (IOException ignored) {} zips.clear(); }
    }
}
