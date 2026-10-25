package earthlife.launcher;

import earthlife.core.util.Json;
import earthlife.core.world.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Plays EarthLife through the official Minecraft Launcher - no Azure app or Microsoft developer setup needed.
 *
 * The official launcher handles sign-in, ownership and downloading the game. EarthLife only:
 *  1. writes the Fabric version JSON into the official ".minecraft/versions" folder (the same thing Fabric's own
 *     installer does; the official launcher downloads Minecraft + Fabric libraries itself),
 *  2. adds an "EarthLife" installation to launcher_profiles.json pointing at the EarthLife game folder (mods, saves),
 *     keeping every existing profile untouched,
 *  3. opens the official Minecraft Launcher, where you sign in (if needed) and press Play on "EarthLife".
 */
public final class OfficialLauncher {
    public static final String PROFILE_KEY = "earthlife";
    private final Path mcDir;

    public OfficialLauncher(Path mcDir) { this.mcDir = mcDir; }

    public static Path defaultMinecraftDir() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String appData = System.getenv("APPDATA");
        if (os.contains("win") && appData != null) return Path.of(appData, ".minecraft");
        Path home = Path.of(System.getProperty("user.home"));
        return os.contains("mac") ? home.resolve("Library/Application Support/minecraft") : home.resolve(".minecraft");
    }

    public Path dir() { return mcDir; }

    /** Installs the Fabric version JSON and the EarthLife profile. Returns the version id to launch. */
    public String installProfile(String fabricProfileJson, Path gameDir, int memoryMb, Path iconPng) throws IOException {
        Map<String, Object> profile = Json.obj(Json.parse(fabricProfileJson));
        String id = Json.str(profile, "id");
        if (id == null || profile.get("inheritsFrom") == null) throw new IOException("Not a Fabric loader profile");
        Path vdir = mcDir.resolve("versions").resolve(id);
        Files.createDirectories(vdir);
        AtomicFiles.write(vdir.resolve(id + ".json"), fabricProfileJson.getBytes(StandardCharsets.UTF_8));

        Path pf = mcDir.resolve("launcher_profiles.json");
        Map<String, Object> root = Files.exists(pf) ? new LinkedHashMap<>(Json.obj(Json.parse(Files.readString(pf)))) : new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> profiles = new LinkedHashMap<>((Map<String, Object>) root.getOrDefault("profiles", Map.of()));
        Map<String, Object> p = new LinkedHashMap<>();
        String now = Instant.now().toString();
        @SuppressWarnings("unchecked") Map<String, Object> old = (Map<String, Object>) profiles.get(PROFILE_KEY);
        p.put("created", old != null && old.get("created") != null ? old.get("created") : now);
        p.put("lastUsed", now);
        p.put("icon", iconPng != null && Files.exists(iconPng)
                ? "data:image/png;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(iconPng)) : "Grass");
        p.put("name", "EarthLife");
        p.put("type", "custom");
        p.put("lastVersionId", id);
        p.put("gameDir", gameDir.toAbsolutePath().toString());
        p.put("javaArgs", "-Xmx" + memoryMb + "M -XX:+UseG1GC");
        profiles.put(PROFILE_KEY, p);
        root.put("profiles", profiles);
        if (!root.containsKey("version")) root.put("version", 3L);
        if (Files.exists(pf)) Files.copy(pf, mcDir.resolve("launcher_profiles.json.earthlife-backup"), StandardCopyOption.REPLACE_EXISTING);
        AtomicFiles.write(pf, Json.write(root).getBytes(StandardCharsets.UTF_8));
        return id;
    }

    /** Opens the official Minecraft Launcher (Microsoft Store / Xbox app install, classic installer, macOS or Linux). */
    public String open() throws IOException {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        List<List<String>> attempts = new ArrayList<>();
        if (os.contains("win")) {
            for (String base : new String[]{System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles"), System.getenv("LOCALAPPDATA") + "\\Programs"}) {
                if (base == null) continue;
                Path exe = Path.of(base, "Minecraft Launcher", "MinecraftLauncher.exe");
                if (Files.exists(exe)) attempts.add(List.of(exe.toString()));
            }
            // Microsoft Store / Xbox app version (the current default install on Windows 10/11)
            attempts.add(List.of("explorer.exe", "shell:AppsFolder\\Microsoft.4297127D64EC6_8wekyb3d8bbwe!Minecraft"));
        } else if (os.contains("mac")) {
            attempts.add(List.of("open", "-a", "Minecraft"));
        } else {
            attempts.add(List.of("minecraft-launcher"));
        }
        IOException last = null;
        for (List<String> cmd : attempts) {
            try { new ProcessBuilder(cmd).start(); return String.join(" ", cmd); }
            catch (IOException e) { last = e; }
        }
        throw new IOException("Couldn't open the Minecraft Launcher. Install it from minecraft.net/download, then try again."
                + (last == null ? "" : " (" + last.getMessage() + ")"));
    }
}
