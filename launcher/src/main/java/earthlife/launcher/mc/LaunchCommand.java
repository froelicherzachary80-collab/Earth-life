package earthlife.launcher.mc;

import earthlife.launcher.auth.MicrosoftAuth;

import java.io.File;
import java.nio.file.Path;
import java.util.*;

/** Builds the java command line for a verified, installed profile. */
public final class LaunchCommand {
    public record Options(Path javaExe, Path gameDir, int maxMemoryMb, String quickPlayWorld, int width, int height) {}

    public static List<String> build(VersionProfile p, VersionProfile.Env env, Installer inst, Path nativesDir,
                                     MicrosoftAuth.Session s, Options o) {
        List<String> cp = new ArrayList<>();
        for (var lib : p.libraries(env)) if (!lib.isNative() || lib.name().contains(":natives-")) cp.add(inst.librariesDir().resolve(lib.path()).toString());
        cp.add(inst.clientJar(p).toString());

        Map<String, String> v = new HashMap<>();
        v.put("auth_player_name", s.username());
        v.put("auth_uuid", s.uuid());
        v.put("auth_access_token", s.accessToken());
        v.put("auth_xuid", s.xuid() == null ? "0" : s.xuid());
        v.put("clientid", "0");
        v.put("user_type", "msa");
        v.put("user_properties", "{}");
        v.put("version_name", p.id);
        v.put("version_type", "EarthLife");
        v.put("game_directory", o.gameDir().toString());
        v.put("assets_root", inst.assetsDir().toString());
        v.put("game_assets", inst.assetsDir().toString());
        v.put("assets_index_name", p.assetIndexId);
        v.put("natives_directory", nativesDir.toString());
        v.put("library_directory", inst.librariesDir().toString());
        v.put("classpath_separator", File.pathSeparator);
        v.put("classpath", String.join(File.pathSeparator, cp));
        v.put("launcher_name", "EarthLife");
        v.put("launcher_version", "0.1.0");
        v.put("resolution_width", String.valueOf(o.width()));
        v.put("resolution_height", String.valueOf(o.height()));
        if (o.quickPlayWorld() != null) v.put("quickPlaySingleplayer", o.quickPlayWorld());
        v.put("quickPlayPath", o.gameDir().resolve("quickPlay.json").toString());

        List<String> cmd = new ArrayList<>();
        cmd.add(o.javaExe().toString());
        cmd.add("-Xmx" + o.maxMemoryMb() + "M");
        cmd.add("-Dearthlife.launcher=0.1.0");
        cmd.addAll(p.jvmArguments(env, v));
        cmd.add(p.mainClass);
        cmd.addAll(p.gameArguments(env, v));
        return cmd;
    }

    public static Set<String> features(Options o) {
        Set<String> f = new HashSet<>();
        if (o.width() > 0) f.add("has_custom_resolution");
        if (o.quickPlayWorld() != null) f.add("is_quick_play_singleplayer");
        return f;
    }

    /** Redacts the access token for logs. */
    public static String redact(List<String> cmd, String token) {
        return String.join(" ", cmd).replace(token, "<access-token>");
    }
}
