package earthlife.mod.client.cinema;

import earthlife.core.util.Json;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Client cinema settings (config/earthlife-cinema.json, on this computer only): Jellyfin servers you signed in to
 * (address + access token, never your password and never sent to the Minecraft server), the FFmpeg location, picture
 * quality, cinema volume, 4D effect strength and the bHaptics connection.
 */
public final class CinemaConfig {
    private CinemaConfig() {}

    public record JellyfinServer(String name, String url, String serverId, String userId, String token) {}

    public static final List<JellyfinServer> jellyfin = new ArrayList<>();
    public static String ffmpegPath = "";
    public static String ffmpegDownloadUrl = "";
    public static int quality = 480;                 // vertical resolution of the decoded picture
    public static double volume = 1.0;
    public static double effects = 1.0;              // 4D effect strength 0..1 (camera motion, wind, mist, flashes)
    public static boolean haptics = true;            // bHaptics TactSuit via the bHaptics Player on this PC
    public static String hapticsHost = "127.0.0.1";
    public static int hapticsPort = 15881;
    public static String deviceId = UUID.randomUUID().toString();

    static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("earthlife-cinema.json"); }

    public static synchronized void load() {
        try {
            if (!Files.exists(file())) { save(); return; }
            Map<String, Object> m = Json.obj(Json.parse(Files.readString(file())));
            jellyfin.clear();
            for (Object o : Json.arr(m.getOrDefault("jellyfin", List.of()))) {
                var j = Json.obj(o);
                jellyfin.add(new JellyfinServer(Json.str(j, "name"), Json.str(j, "url"), Json.str(j, "serverId"), Json.str(j, "userId"), Json.str(j, "token")));
            }
            ffmpegPath = Objects.requireNonNullElse(Json.str(m, "ffmpegPath"), "");
            ffmpegDownloadUrl = Objects.requireNonNullElse(Json.str(m, "ffmpegDownloadUrl"), "");
            if (m.get("quality") != null) quality = (int) Json.num(m.get("quality"));
            if (m.get("volume") != null) volume = Json.num(m.get("volume"));
            if (m.get("effects") != null) effects = Json.num(m.get("effects"));
            haptics = !Boolean.FALSE.equals(m.get("haptics"));
            if (Json.str(m, "hapticsHost") != null) hapticsHost = Json.str(m, "hapticsHost");
            if (m.get("hapticsPort") != null) hapticsPort = (int) Json.num(m.get("hapticsPort"));
            if (Json.str(m, "deviceId") != null) deviceId = Json.str(m, "deviceId");
        } catch (Exception ignored) {}
    }

    public static synchronized void save() {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            List<Object> js = new ArrayList<>();
            for (var j : jellyfin) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("name", j.name()); o.put("url", j.url()); o.put("serverId", j.serverId()); o.put("userId", j.userId()); o.put("token", j.token());
                js.add(o);
            }
            m.put("jellyfin", js); m.put("ffmpegPath", ffmpegPath); m.put("ffmpegDownloadUrl", ffmpegDownloadUrl); m.put("quality", (long) quality);
            m.put("volume", volume); m.put("effects", effects); m.put("haptics", haptics); m.put("hapticsHost", hapticsHost); m.put("hapticsPort", (long) hapticsPort);
            m.put("deviceId", deviceId);
            Files.createDirectories(file().getParent());
            Files.writeString(file(), Json.write(m));
        } catch (Exception ignored) {}
    }
}
