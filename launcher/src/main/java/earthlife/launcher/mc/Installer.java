package earthlife.launcher.mc;

import earthlife.core.util.Json;
import earthlife.launcher.Http;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/**
 * Installs a Minecraft version + Fabric loader into the launcher's own directory by downloading
 * official files from Mojang and Fabric servers on the user's machine. Nothing proprietary is
 * shipped with EarthLife. Every file is SHA-1 verified, so running install again doubles as
 * the "repair game files" tool.
 */
public final class Installer {
    public static final String MOJANG_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    public static final String FABRIC_META = "https://meta.fabricmc.net/v2";
    public static final String ASSET_BASE = "https://resources.download.minecraft.net/";

    private final Path root;
    private final Consumer<String> log;
    private String manifestUrl = MOJANG_MANIFEST, fabricMeta = FABRIC_META, assetBase = ASSET_BASE;

    public Installer(Path root, Consumer<String> log) { this.root = root; this.log = log; }

    /** For tests: point at local file:// mirrors. */
    public Installer withEndpoints(String manifest, String fabric, String assets) { manifestUrl = manifest; fabricMeta = fabric; assetBase = assets; return this; }

    public Path librariesDir() { return root.resolve("libraries"); }
    public Path assetsDir() { return root.resolve("assets"); }
    public Path versionsDir() { return root.resolve("versions"); }

    public Map<String, Object> vanillaJson(String gameVersion) throws IOException, InterruptedException {
        Path local = versionsDir().resolve(gameVersion).resolve(gameVersion + ".json");
        var manifest = Json.obj(Json.parse(Http.getText(manifestUrl)));
        for (Object o : Json.arr(manifest.get("versions"))) {
            var v = Json.obj(o);
            if (gameVersion.equals(v.get("id"))) {
                Http.download((String) v.get("url"), local, (String) v.get("sha1"), 0);
                return Json.obj(Json.parse(Files.readString(local)));
            }
        }
        throw new IOException("Minecraft version " + gameVersion + " not found in Mojang's manifest. "
                + "Check the version name (e.g. snapshots/pre-releases have their own ids).");
    }

    public String latestFabricLoader(String gameVersion) throws IOException, InterruptedException {
        var list = Json.arr(Json.parse(Http.getText(fabricMeta + "/versions/loader/" + gameVersion)));
        if (list.isEmpty()) throw new IOException("Fabric has no loader for Minecraft " + gameVersion + " yet.");
        for (Object o : list) {
            var loader = Json.obj(Json.obj(o).get("loader"));
            if (Boolean.TRUE.equals(loader.get("stable"))) return (String) loader.get("version");
        }
        return (String) Json.obj(Json.obj(list.get(0)).get("loader")).get("version");
    }

    public Map<String, Object> fabricProfile(String gameVersion, String loaderVersion) throws IOException, InterruptedException {
        String id = "fabric-loader-" + loaderVersion + "-" + gameVersion;
        Path local = versionsDir().resolve(id).resolve(id + ".json");
        String json = Http.getText(fabricMeta + "/versions/loader/" + gameVersion + "/" + loaderVersion + "/profile/json");
        Files.createDirectories(local.getParent());
        Files.writeString(local, json);
        return Json.obj(Json.parse(json));
    }

    /** Downloads/verifies client jar, libraries, natives and assets. Returns number of files fetched. */
    public int install(VersionProfile p, VersionProfile.Env env, int threads) throws IOException, InterruptedException {
        List<Callable<Boolean>> jobs = new ArrayList<>();
        Path clientJar = clientJar(p);
        if (p.clientUrl != null) jobs.add(() -> Http.download(p.clientUrl, clientJar, p.clientSha1, p.clientSize));
        for (var lib : p.libraries(env)) jobs.add(() -> Http.download(lib.url(), librariesDir().resolve(lib.path()), lib.sha1(), lib.size()));
        if (p.assetIndexUrl != null) {
            Path idx = assetsDir().resolve("indexes").resolve(p.assetIndexId + ".json");
            Http.download(p.assetIndexUrl, idx, p.assetIndexSha1, 0);
            var objects = Json.obj(Json.obj(Json.parse(Files.readString(idx))).get("objects"));
            for (Object o : objects.values()) {
                var obj = Json.obj(o);
                String h = (String) obj.get("hash");
                long size = ((Number) obj.get("size")).longValue();
                jobs.add(() -> Http.download(assetBase + h.substring(0, 2) + "/" + h, assetsDir().resolve("objects").resolve(h.substring(0, 2)).resolve(h), h, size));
            }
        }
        log.accept("Verifying " + jobs.size() + " files…");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        int fetched = 0, done = 0;
        List<String> failures = new ArrayList<>();
        try {
            for (Future<Boolean> f : pool.invokeAll(jobs)) {
                try { if (f.get()) fetched++; } catch (ExecutionException e) { failures.add(e.getCause().getMessage()); }
                if (++done % 500 == 0) log.accept("  " + done + "/" + jobs.size());
            }
        } finally { pool.shutdown(); }
        if (!failures.isEmpty()) throw new IOException(failures.size() + " downloads failed, first: " + failures.get(0));
        log.accept("Downloaded " + fetched + " files, " + (jobs.size() - fetched) + " already up to date.");
        return fetched;
    }

    public Path clientJar(VersionProfile p) { return versionsDir().resolve(p.vanillaId()).resolve(p.vanillaId() + ".jar"); }

    /** Extracts native libraries (.dll/.so/.dylib) from native jars into a per-version folder. */
    public Path extractNatives(VersionProfile p, VersionProfile.Env env) throws IOException {
        Path dir = root.resolve("natives").resolve(p.id);
        Files.createDirectories(dir);
        for (var lib : p.libraries(env)) {
            if (!lib.isNative()) continue;
            try (ZipFile z = new ZipFile(librariesDir().resolve(lib.path()).toFile())) {
                var en = z.entries();
                while (en.hasMoreElements()) {
                    var e = en.nextElement();
                    String n = e.getName();
                    if (e.isDirectory() || n.startsWith("META-INF") || !(n.endsWith(".dll") || n.endsWith(".so") || n.endsWith(".dylib") || n.endsWith(".jnilib"))) continue;
                    Path out = dir.resolve(Path.of(n).getFileName().toString());
                    try (var in = z.getInputStream(e)) { Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING); }
                }
            }
        }
        return dir;
    }
}
