package earthlife.launcher.mc;

import earthlife.core.util.Json;
import earthlife.launcher.Http;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Java runtime management: uses Mojang's official runtime manifest (the same Java builds the
 * vanilla launcher uses) so players don't need to install Java themselves.
 */
public final class JavaRuntimes {
    public static final String ALL_JSON = "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";
    private final Path root;

    public JavaRuntimes(Path root) { this.root = root.resolve("runtimes"); }

    public static String platformKey(VersionProfile.Env env) {
        boolean arm = env.isArm();
        return switch (env.osName()) {
            case "windows" -> arm ? "windows-arm64" : env.is64() ? "windows-x64" : "windows-x86";
            case "osx" -> arm ? "mac-os-arm64" : "mac-os";
            default -> env.is64() ? "linux" : "linux-i386";
        };
    }

    public Path javaExecutable(String component, VersionProfile.Env env) {
        Path home = root.resolve(component);
        if (env.osName().equals("osx")) home = home.resolve("jre.bundle/Contents/Home");
        return home.resolve("bin").resolve(env.osName().equals("windows") ? "javaw.exe" : "java");
    }

    /** Ensures the runtime is installed and verified; returns the java executable. */
    public Path ensure(String component, VersionProfile.Env env, java.util.function.Consumer<String> log) throws IOException, InterruptedException {
        var all = Json.obj(Json.parse(Http.getText(ALL_JSON)));
        var plat = Json.obj(all.get(platformKey(env)));
        if (plat == null || Json.arr(plat.get(component)).isEmpty()) throw new IOException("Mojang has no " + component + " runtime for " + platformKey(env));
        var entry = Json.obj(Json.arr(plat.get(component)).get(0));
        var mf = Json.obj(entry.get("manifest"));
        var files = Json.obj(Json.obj(Json.parse(Http.getText((String) mf.get("url")))).get("files"));
        Path home = root.resolve(component);
        int fetched = 0;
        for (var e : files.entrySet()) {
            var f = Json.obj(e.getValue());
            Path dest = home.resolve(e.getKey()).normalize();
            if (!dest.startsWith(home)) throw new IOException("Unsafe path in runtime manifest: " + e.getKey());
            switch ((String) f.get("type")) {
                case "directory" -> Files.createDirectories(dest);
                case "file" -> {
                    var raw = Json.obj(Json.obj(f.get("downloads")).get("raw"));
                    if (Http.download((String) raw.get("url"), dest, (String) raw.get("sha1"), ((Number) raw.get("size")).longValue())) fetched++;
                    if (Boolean.TRUE.equals(f.get("executable"))) dest.toFile().setExecutable(true);
                }
                case "link" -> {
                    Path target = Path.of((String) f.get("target"));
                    if (!Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) { Files.createDirectories(dest.getParent()); try { Files.createSymbolicLink(dest, target); } catch (UnsupportedOperationException ignored) {} }
                }
                default -> {}
            }
        }
        log.accept("Java runtime " + component + ": " + fetched + " files downloaded.");
        return javaExecutable(component, env);
    }
}
