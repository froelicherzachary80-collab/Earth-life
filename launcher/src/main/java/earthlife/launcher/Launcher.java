package earthlife.launcher;

import earthlife.core.util.Json;
import earthlife.launcher.auth.MicrosoftAuth;
import earthlife.launcher.mc.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Orchestrates sign-in -> install -> launch. Shared by the CLI and the Swing UI. */
public final class Launcher {
    public static final String DEFAULT_GAME_VERSION = "26.3";

    public final Path root;
    public final Instance instance;
    private final Map<String, Object> config;
    private final Consumer<String> log;
    private MicrosoftAuth.Session session;

    public Launcher(Path root, Consumer<String> log) throws IOException {
        this.root = root; this.log = log;
        this.instance = new Instance(root.resolve("instances/earthlife"));
        Path cfg = root.resolve("launcher.json");
        this.config = Files.exists(cfg) ? Json.obj(Json.parse(Files.readString(cfg))) : new LinkedHashMap<>();
    }

    public static Path defaultRoot() {
        String appData = System.getenv("APPDATA");
        if (appData != null) return Path.of(appData, "EarthLife");
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        Path home = Path.of(System.getProperty("user.home"));
        return os.contains("mac") ? home.resolve("Library/Application Support/EarthLife") : home.resolve(".earthlife");
    }

    public String gameVersion() { return (String) config.getOrDefault("gameVersion", DEFAULT_GAME_VERSION); }
    public int memoryMb() { return ((Number) config.getOrDefault("memoryMb", 6144L)).intValue(); }

    private String clientId() {
        String env = System.getenv("EARTHLIFE_MSA_CLIENT_ID");
        return env != null ? env : (String) config.get("msaClientId");
    }

    public MicrosoftAuth.Session signIn(Consumer<String> prompt) throws IOException, InterruptedException {
        session = new MicrosoftAuth(clientId(), root.resolve("auth/refresh_token")).login(prompt);
        log.accept("Signed in as " + session.username() + " (Java Edition ownership verified).");
        return session;
    }

    public void signOut() throws IOException {
        new MicrosoftAuth(clientId(), root.resolve("auth/refresh_token")).logout();
        session = null;
    }

    public MicrosoftAuth.Session session() { return session; }

    /** Ownership must be verified before any game files are downloaded. */
    /**
     * Sign in & play through the official Minecraft Launcher (no Azure setup): installs the Fabric version + an
     * "EarthLife" installation into the official launcher, copies the EarthLife mod into the EarthLife game folder,
     * and opens the official launcher. Sign in there (once) and press Play with "EarthLife" selected.
     */
    public String playWithOfficialLauncher() throws IOException, InterruptedException {
        Installer inst = new Installer(root, log);
        String gv = gameVersion();
        String loader = (String) config.getOrDefault("fabricLoader", inst.latestFabricLoader(gv));
        log.accept("Preparing Minecraft " + gv + " + Fabric Loader " + loader + " for the official launcher…");
        String json = Http.getText(Installer.FABRIC_META + "/versions/loader/" + gv + "/" + loader + "/profile/json");
        instance.ensure();
        installBundledMod();
        var official = new OfficialLauncher(OfficialLauncher.defaultMinecraftDir());
        Path icon = Path.of(System.getProperty("user.dir")).resolve("earthlife.png");
        String id = official.installProfile(json, instance.dir, memoryMb(), icon);
        log.accept("Added installation \"EarthLife\" (" + id + ") to the Minecraft Launcher.");
        String how = official.open();
        log.accept("Opened the Minecraft Launcher (" + how + "). Sign in there if asked, pick \"EarthLife\" next to Play, and press Play.");
        return id;
    }

    public VersionProfile install() throws IOException, InterruptedException {
        if (session == null) throw new IllegalStateException("Sign in first: game files are only downloaded for verified owners.");
        Installer inst = new Installer(root, log);
        String gv = gameVersion();
        log.accept("Resolving Minecraft " + gv + "…");
        var vanilla = inst.vanillaJson(gv);
        String loader = (String) config.getOrDefault("fabricLoader", inst.latestFabricLoader(gv));
        log.accept("Fabric Loader " + loader);
        var profile = VersionProfile.layered(inst.fabricProfile(gv, loader), vanilla);
        var env = VersionProfile.Env.current(Set.of());
        inst.install(profile, env, 8);
        new JavaRuntimes(root).ensure(profile.javaComponent, env, log);
        instance.ensure();
        installBundledMod();
        return profile;
    }

    /** Copies the EarthLife Fabric mod jar shipped next to the launcher into the instance. */
    private void installBundledMod() throws IOException {
        Path here = Path.of(System.getProperty("user.dir"));
        List<Path> candidates = new ArrayList<>(List.of(here.resolve("earthlife-mod.jar"), root.resolve("earthlife-mod.jar")));
        try {   // next to the launcher jar itself (EarthLife.exe installs keep it in <install>\app\)
            Path self = Path.of(Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            candidates.add(0, self.resolveSibling("earthlife-mod.jar"));
        } catch (Exception ignored) {}
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) { instance.installMod(candidate); log.accept("Installed " + candidate.getFileName()); return; }
        }
        log.accept("WARNING: earthlife-mod.jar not found next to the launcher; build fabric-mod and copy it here.");
    }

    public Process launch(VersionProfile profile, String world) throws IOException {
        if (session == null) throw new IllegalStateException("Sign in first.");
        Installer inst = new Installer(root, log);
        var opts = new LaunchCommand.Options(null, instance.dir, memoryMb(), world, 1280, 720);
        var env = VersionProfile.Env.current(LaunchCommand.features(opts));
        Path java = new JavaRuntimes(root).javaExecutable(profile.javaComponent, env);
        opts = new LaunchCommand.Options(java, instance.dir, memoryMb(), world, 1280, 720);
        Path natives = inst.extractNatives(profile, env);
        List<String> cmd = LaunchCommand.build(profile, env, inst, natives, session, opts);
        log.accept("Launching: " + LaunchCommand.redact(cmd, session.accessToken()));
        return new ProcessBuilder(cmd).directory(instance.dir.toFile()).redirectErrorStream(true)
                .redirectOutput(instance.dir.resolve("latest-launch.log").toFile()).start();
    }
}
