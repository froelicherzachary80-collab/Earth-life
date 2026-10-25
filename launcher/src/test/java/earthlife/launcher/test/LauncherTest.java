package earthlife.launcher.test;

import earthlife.core.util.Json;
import earthlife.launcher.Http;
import earthlife.launcher.Instance;
import earthlife.launcher.auth.MicrosoftAuth;
import earthlife.launcher.mc.*;

import java.nio.file.*;
import java.util.*;

/**
 * Offline launcher tests. Uses a synthetic version JSON shaped like Mojang's real schema and a
 * Fabric-style profile, served from file:// URLs, so install/verify/launch-command logic is exercised
 * without network access or any Minecraft files.
 */
public final class LauncherTest {
    static int pass, fail;

    static void check(boolean c, String m) { if (c) { pass++; System.out.println("  PASS " + m); } else { fail++; System.out.println("  FAIL " + m); } }

    public static void main(String[] args) throws Exception {
        Path res = Path.of(args[0]);
        Path tmp = Files.createTempDirectory("el-launcher");

        // --- fake "official" server: files + their sha1s
        Path srv = tmp.resolve("server");
        Files.createDirectories(srv);
        Path clientJar = Files.writeString(srv.resolve("client.jar"), "not really minecraft");
        Path lwjgl = Files.writeString(srv.resolve("lwjgl.jar"), "lwjgl");
        Path winOnly = Files.writeString(srv.resolve("win.jar"), "windows only lib");
        Path loader = Files.createDirectories(srv.resolve("maven/net/fabricmc/fabric-loader/0.99.0")).resolve("fabric-loader-0.99.0.jar");
        Files.writeString(loader, "loader");
        Path asset = Files.createDirectories(srv.resolve("assets")).resolve("x");
        Files.writeString(asset, "sound bytes");
        String assetHash = Http.sha1(asset);
        Files.createDirectories(srv.resolve("assets/" + assetHash.substring(0, 2)));
        Files.copy(asset, srv.resolve("assets/" + assetHash.substring(0, 2) + "/" + assetHash));
        Path assetIndex = Files.writeString(srv.resolve("index.json"), "{\"objects\":{\"minecraft/sounds/x.ogg\":{\"hash\":\"" + assetHash + "\",\"size\":11}}}");

        String vanilla = Files.readString(res.resolve("vanilla-26.3-test.json"))
                .replace("@CLIENT_URL@", clientJar.toUri().toString()).replace("@CLIENT_SHA1@", Http.sha1(clientJar))
                .replace("@LWJGL_URL@", lwjgl.toUri().toString()).replace("@LWJGL_SHA1@", Http.sha1(lwjgl))
                .replace("@WIN_URL@", winOnly.toUri().toString()).replace("@WIN_SHA1@", Http.sha1(winOnly))
                .replace("@INDEX_URL@", assetIndex.toUri().toString()).replace("@INDEX_SHA1@", Http.sha1(assetIndex));
        Path vanillaFile = Files.writeString(srv.resolve("26.3-test.json"), vanilla);
        Files.writeString(srv.resolve("manifest.json"), "{\"versions\":[{\"id\":\"26.3-test\",\"url\":\"" + vanillaFile.toUri() + "\",\"sha1\":\"" + Http.sha1(vanillaFile) + "\"}]}");
        Path fabricDir = Files.createDirectories(srv.resolve("fabric/versions/loader/26.3-test/0.99.0/profile"));
        Files.writeString(fabricDir.resolve("json"), Files.readString(res.resolve("fabric-profile-test.json")).replace("@MAVEN@", srv.resolve("maven").toUri().toString()));

        // --- rules
        var linux = new VersionProfile.Env("linux", "x86_64", "6.0", Set.of());
        var windows = new VersionProfile.Env("windows", "x86_64", "10.0", Set.of());
        Object winRule = Json.parse("[{\"action\":\"allow\",\"os\":{\"name\":\"windows\"}}]");
        Object notMac = Json.parse("[{\"action\":\"allow\"},{\"action\":\"disallow\",\"os\":{\"name\":\"osx\"}}]");
        check(VersionProfile.allowed(winRule, windows) && !VersionProfile.allowed(winRule, linux), "os allow rule");
        check(VersionProfile.allowed(notMac, linux) && !VersionProfile.allowed(notMac, new VersionProfile.Env("osx", "aarch64", "14", Set.of())), "disallow rule");
        check(VersionProfile.mavenPath("net.fabricmc:fabric-loader:0.99.0").equals("net/fabricmc/fabric-loader/0.99.0/fabric-loader-0.99.0.jar"), "maven path");

        // --- install from file:// "servers"
        Path root = tmp.resolve("root");
        Installer inst = new Installer(root, s -> {}).withEndpoints(srv.resolve("manifest.json").toUri().toString(),
                srv.resolve("fabric").toUri().toString().replaceAll("/$", ""), srv.resolve("assets").toUri() + "");
        var v = inst.vanillaJson("26.3-test");
        var fabric = Json.obj(Json.parse(Files.readString(fabricDir.resolve("json"))));
        var profile = VersionProfile.layered(fabric, v);
        check(profile.mainClass.equals("net.fabricmc.loader.impl.launch.knot.KnotClient"), "fabric main class wins");
        check(profile.libraries(linux).stream().noneMatch(l -> l.name().contains("winonly")), "windows lib skipped on linux");
        check(profile.libraries(windows).stream().anyMatch(l -> l.name().contains("winonly")), "windows lib kept on windows");
        int fetched = inst.install(profile, linux, 2);
        check(fetched == 4, "client + 2 libs + 1 asset downloaded (got " + fetched + ")");
        check(inst.install(profile, linux, 2) == 0, "second run verifies hashes, downloads nothing");
        Files.writeString(inst.clientJar(profile), "tampered");
        check(inst.install(profile, linux, 2) == 1, "repair re-downloads tampered client jar");
        check(Files.readString(inst.clientJar(profile)).equals("not really minecraft"), "client restored");

        // --- launch command
        var opts = new LaunchCommand.Options(Path.of("/jre/bin/java"), root.resolve("game"), 4096, "EarthLife Williamson", 1280, 720);
        var env = new VersionProfile.Env("linux", "x86_64", "6.0", LaunchCommand.features(opts));
        var session = new MicrosoftAuth.Session("Zach", "uuid-123", "SECRET_TOKEN", 0, "xuid", null);
        List<String> cmd = LaunchCommand.build(profile, env, inst, root.resolve("natives"), session, opts);
        String joined = String.join(" ", cmd);
        check(cmd.get(0).equals(Path.of("/jre/bin/java").toString()) && cmd.contains("-Xmx4096M"), "java + memory");
        check(cmd.contains("net.fabricmc.loader.impl.launch.knot.KnotClient"), "main class present");
        int mc = cmd.indexOf("net.fabricmc.loader.impl.launch.knot.KnotClient");
        check(cmd.indexOf("-cp") < mc && cmd.get(cmd.indexOf("-cp") + 1).contains("fabric-loader-0.99.0.jar"), "classpath has loader");
        check(joined.contains("--username Zach") && joined.contains("--accessToken SECRET_TOKEN") && joined.contains("--userType msa"), "auth args");
        check(joined.contains("--quickPlaySingleplayer EarthLife Williamson"), "quick play feature arg");
        check(joined.contains("--width 1280"), "custom resolution feature arg");
        check(!joined.contains("${"), "no unresolved placeholders");
        check(cmd.contains("-Dfabric.test.jvm=1"), "loader jvm args merged");
        check(!LaunchCommand.redact(cmd, "SECRET_TOKEN").contains("SECRET_TOKEN"), "token redacted in logs");

        // --- entitlements / errors
        check(MicrosoftAuth.ownsJava(Json.obj(Json.parse("{\"items\":[{\"name\":\"product_minecraft\"},{\"name\":\"game_minecraft\"}]}"))), "owner detected");
        check(!MicrosoftAuth.ownsJava(Json.obj(Json.parse("{\"items\":[]}"))), "non-owner rejected");
        check(MicrosoftAuth.xstsMessage("{\"XErr\":2148916233}").contains("no Xbox profile"), "XSTS error explained");

        // --- instance tools
        Instance in = new Instance(tmp.resolve("inst"));
        in.ensure();
        Files.writeString(in.mods().resolve("earthlife-mod.jar"), "x");
        in.setModEnabled("earthlife-mod.jar", false);
        check(in.listMods().get(0).equals(new Instance.Mod("earthlife-mod.jar", false)), "mod disabled");
        in.setModEnabled("earthlife-mod.jar", true);
        check(in.listMods().get(0).enabled(), "mod re-enabled");
        Files.createDirectories(in.saves().resolve("W1/earthlife"));
        Files.writeString(in.saves().resolve("W1/level.dat"), "lvl");
        Files.writeString(in.saves().resolve("W1/earthlife/ledger.jsonl"), "{}");
        check(in.listSaves().equals(List.of("W1")), "save listed");
        check(Files.size(in.backup("W1")) > 0, "backup zip");
        in.requestWorld("EarthLife Williamson", 43.2237, -77.1861, "Williamson, NY", 1);
        check(Files.readString(in.dir.resolve("config/earthlife/pending_world.json")).contains("earthlife_survival"), "world request written");

        in.setTime("SCALED", 120, "America/New_York");
        String cfgText = Files.readString(in.dir.resolve("config/earthlife/earthlife.json"));
        check(cfgText.contains("\"mode\":\"SCALED\"") && cfgText.contains("\"minutesPerDay\":120"), "time setting written");
        in.setTime("REALTIME", 5, "America/New_York");
        check(Files.readString(in.dir.resolve("config/earthlife/earthlife.json")).contains("\"minutesPerDay\":1440"), "realtime forces 1440 min/day");
        boolean rejected = false;
        try { in.setTime("SCALED", 0.2, "UTC"); } catch (IllegalArgumentException e) { rejected = true; }
        check(rejected, "invalid day length rejected");

        // --- live events settings
        in.setEventSetting("ticketmasterKey", "AbCdEf0123456789XyZ");
        in.setEventSetting("ics", "https://example.org/town.ics");
        in.setEventSetting("ics", "https://example.org/town.ics");
        in.setEventSetting("mlb", "false");
        String evText = Files.readString(in.dir.resolve("config/earthlife/earthlife.json"));
        check(evText.contains("\"ticketmasterKey\":\"AbCdEf0123456789XyZ\"") && evText.contains("\"mlb\":false"), "events settings written");
        check(evText.indexOf("town.ics") == evText.lastIndexOf("town.ics"), "calendar URL not duplicated");
        check(evText.contains("\"time\""), "time settings kept alongside events");
        boolean badKey = false;
        try { in.setEventSetting("ticketmasterKey", "nope"); } catch (IllegalArgumentException e) { badKey = true; }
        check(badKey, "bad API key rejected");

        // --- token storage
        Path tok = tmp.resolve("auth/refresh_token");
        var store = new earthlife.launcher.auth.TokenStore(tok, false);
        store.save("M.R3_BAY.secret-refresh");
        check("M.R3_BAY.secret-refresh".equals(store.load()), "token round trip");
        check(Files.readString(tok).startsWith("plain\n"), "non-Windows stores plain+permissions");
        Files.writeString(tok, "legacy-token\n");
        check("legacy-token".equals(store.load()), "V0.1 token file still readable");
        Files.writeString(tok, "dpapi\nAAAA\n");
        boolean refused = false;
        try { store.load(); } catch (java.io.IOException e) { refused = true; }
        check(refused, "DPAPI token from Windows isn't misread elsewhere");

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
