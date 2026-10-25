package earthlife.launcher.mc;

import earthlife.core.util.Json;

import java.util.*;
import java.util.regex.Pattern;

/**
 * A Minecraft version JSON (from Mojang's piston-meta) optionally layered with a mod-loader profile
 * that "inheritsFrom" it (Fabric's profile JSON). Handles rule evaluation, library resolution and
 * argument templating the same way the official launcher does.
 */
public final class VersionProfile {
    public record Library(String name, String path, String url, String sha1, long size, boolean isNative) {
        /** group:artifact[:classifier] - used to de-duplicate (loader libraries override vanilla ones). */
        public String key() {
            String[] p = name.split(":");
            return p[0] + ":" + p[1] + (p.length > 3 ? ":" + p[3] : "");
        }
    }

    public record Env(String osName, String osArch, String osVersion, Set<String> features) {
        public static Env current(Set<String> features) {
            String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            String n = os.contains("win") ? "windows" : os.contains("mac") ? "osx" : "linux";
            String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
            return new Env(n, arch.equals("amd64") ? "x86_64" : arch, System.getProperty("os.version"), features);
        }
        boolean is64() { return osArch.contains("64"); }
        boolean isArm() { return osArch.contains("aarch64") || osArch.contains("arm"); }
    }

    public final String id, mainClass, assetIndexId, assetIndexUrl, assetIndexSha1, clientUrl, clientSha1, type, javaComponent;
    public final int javaMajor;
    public final long clientSize;
    private final List<Object> gameArgs = new ArrayList<>(), jvmArgs = new ArrayList<>();
    private final List<Map<String, Object>> rawLibraries = new ArrayList<>();
    private final String vanillaId;

    private VersionProfile(Map<String, Object> child, Map<String, Object> parent) {
        Map<String, Object> base = parent != null ? parent : child;
        this.id = Json.str(child, "id");
        this.vanillaId = Json.str(base, "id");
        this.mainClass = Json.str(child, "mainClass") != null ? Json.str(child, "mainClass") : Json.str(base, "mainClass");
        this.type = Json.str(base, "type");
        var ai = Json.obj(base.get("assetIndex"));
        this.assetIndexId = ai == null ? Json.str(base, "assets") : Json.str(ai, "id");
        this.assetIndexUrl = ai == null ? null : Json.str(ai, "url");
        this.assetIndexSha1 = ai == null ? null : Json.str(ai, "sha1");
        var client = base.get("downloads") == null ? null : Json.obj(Json.obj(base.get("downloads")).get("client"));
        this.clientUrl = client == null ? null : Json.str(client, "url");
        this.clientSha1 = client == null ? null : Json.str(client, "sha1");
        this.clientSize = client == null ? 0 : ((Number) client.get("size")).longValue();
        var jv = Json.obj(base.get("javaVersion"));
        this.javaComponent = jv == null ? "jre-legacy" : Json.str(jv, "component");
        this.javaMajor = jv == null ? 8 : ((Number) jv.get("majorVersion")).intValue();
        // Loader libraries first so they win de-duplication.
        for (Object o : Json.arr(child.get("libraries"))) rawLibraries.add(Json.obj(o));
        if (parent != null) for (Object o : Json.arr(parent.get("libraries"))) rawLibraries.add(Json.obj(o));
        for (var src : parent != null ? List.of(base, child) : List.of(base)) {
            var args = Json.obj(src.get("arguments"));
            if (args != null) { gameArgs.addAll(Json.arr(args.get("game"))); jvmArgs.addAll(Json.arr(args.get("jvm"))); }
            else if (src.get("minecraftArguments") != null) gameArgs.addAll(Arrays.asList(((String) src.get("minecraftArguments")).split(" ")));
        }
        if (jvmArgs.isEmpty()) { // pre-1.13 versions have no jvm args section
            jvmArgs.addAll(List.of("-Djava.library.path=${natives_directory}", "-cp", "${classpath}"));
        }
    }

    public static VersionProfile of(Map<String, Object> vanilla) { return new VersionProfile(vanilla, null); }

    public static VersionProfile layered(Map<String, Object> loader, Map<String, Object> vanilla) {
        String inherits = Json.str(loader, "inheritsFrom");
        if (inherits != null && !inherits.equals(Json.str(vanilla, "id")))
            throw new IllegalArgumentException("Loader profile inherits " + inherits + " but vanilla is " + Json.str(vanilla, "id"));
        return new VersionProfile(loader, vanilla);
    }

    public String vanillaId() { return vanillaId; }

    // ---- rules ----
    @SuppressWarnings("unchecked")
    public static boolean allowed(Object rulesObj, Env env) {
        if (rulesObj == null) return true;
        boolean allow = false;
        for (Object r : (List<Object>) rulesObj) {
            Map<String, Object> rule = Json.obj(r);
            if (matches(rule, env)) allow = "allow".equals(rule.get("action"));
        }
        return allow;
    }

    private static boolean matches(Map<String, Object> rule, Env env) {
        var os = Json.obj(rule.get("os"));
        if (os != null) {
            if (os.get("name") != null && !os.get("name").equals(env.osName())) return false;
            if (os.get("arch") != null) {
                String a = (String) os.get("arch");
                if (a.equals("x86") ? env.is64() : a.equals("arm64") ? !env.isArm() : !a.equals(env.osArch())) return false;
            }
            if (os.get("version") != null && !Pattern.compile((String) os.get("version")).matcher(env.osVersion()).find()) return false;
        }
        var feats = Json.obj(rule.get("features"));
        if (feats != null) for (var e : feats.entrySet())
            if (Boolean.TRUE.equals(e.getValue()) != env.features().contains(e.getKey())) return false;
        return true;
    }

    // ---- libraries ----
    public List<Library> libraries(Env env) {
        Map<String, Library> out = new LinkedHashMap<>();
        for (var lib : rawLibraries) {
            if (!allowed(lib.get("rules"), env)) continue;
            String name = (String) lib.get("name");
            var dl = Json.obj(lib.get("downloads"));
            if (dl != null && dl.get("artifact") != null) {
                var a = Json.obj(dl.get("artifact"));
                add(out, new Library(name, Json.str(a, "path"), Json.str(a, "url"), Json.str(a, "sha1"), num(a.get("size")), name.contains(":natives-")));
            } else if (dl == null) {
                // Maven-style (Fabric): name + repository url, sha1 optional.
                String repo = lib.get("url") != null ? (String) lib.get("url") : "https://libraries.minecraft.net/";
                String path = mavenPath(name);
                add(out, new Library(name, path, repo + (repo.endsWith("/") ? "" : "/") + path, Json.str(lib, "sha1"), num(lib.get("size")), false));
            }
            // Legacy natives with classifiers (pre-1.19).
            var natives = Json.obj(lib.get("natives"));
            if (natives != null && dl != null && natives.get(env.osName()) != null) {
                String cls = ((String) natives.get(env.osName())).replace("${arch}", env.is64() ? "64" : "32");
                var c = Json.obj(Json.obj(dl.get("classifiers")).get(cls));
                if (c != null) add(out, new Library(name + ":" + cls, Json.str(c, "path"), Json.str(c, "url"), Json.str(c, "sha1"), num(c.get("size")), true));
            }
        }
        return new ArrayList<>(out.values());
    }

    private static void add(Map<String, Library> m, Library l) { m.putIfAbsent(l.key(), l); }
    private static long num(Object o) { return o == null ? 0 : ((Number) o).longValue(); }

    public static String mavenPath(String coord) {
        String[] p = coord.split(":");
        String ext = "jar";
        String last = p[p.length - 1];
        if (last.contains("@")) { ext = last.substring(last.indexOf('@') + 1); p[p.length - 1] = last.substring(0, last.indexOf('@')); }
        String file = p[1] + "-" + p[2] + (p.length > 3 ? "-" + p[3] : "") + "." + ext;
        return p[0].replace('.', '/') + "/" + p[1] + "/" + p[2] + "/" + file;
    }

    // ---- arguments ----
    public List<String> jvmArguments(Env env, Map<String, String> vars) { return expand(jvmArgs, env, vars); }
    public List<String> gameArguments(Env env, Map<String, String> vars) { return expand(gameArgs, env, vars); }

    private static List<String> expand(List<Object> args, Env env, Map<String, String> vars) {
        List<String> out = new ArrayList<>();
        for (Object a : args) {
            if (a instanceof String s) out.add(subst(s, vars));
            else {
                var m = Json.obj(a);
                if (!allowed(m.get("rules"), env)) continue;
                Object v = m.get("value");
                if (v instanceof String s) out.add(subst(s, vars));
                else for (Object x : Json.arr(v)) out.add(subst((String) x, vars));
            }
        }
        return out;
    }

    static String subst(String s, Map<String, String> vars) {
        var m = Pattern.compile("\\$\\{([a-zA-Z_]+)}").matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) m.appendReplacement(b, java.util.regex.Matcher.quoteReplacement(vars.getOrDefault(m.group(1), m.group())));
        m.appendTail(b);
        return b.toString();
    }
}
