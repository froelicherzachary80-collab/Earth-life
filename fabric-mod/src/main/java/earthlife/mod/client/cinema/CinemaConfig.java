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
    // ---- ZMF Cinema v0.1
    public static String preset = "BALANCED";        // LITE | BALANCED | HIGH | ULTRA (picture, depth map, particles, streams)
    public static double stereoStrength = 1.0, stereoZero = 0.40, stereoPopOut = 0.5, stereoScreenDepth = 1.0;
    public static boolean stereoComfort = false, stereoGhost = true, stereoSwap = false, stereoCompare = false, auto3d = true;
    public static double fxSensitivity = 1.0;        // 4DX event recognition (0.5 = fewer events, 1.5 = more)
    public static boolean reducedMotion = false;     // seat motion at 40% (accessibility)
    public static int gore = 0;                      // 0 = no blood effects, 1 = subtle, 2 = full
    public static double hapticIntensity = 0.8;      // 0..1 scale of every TactSuit pattern
    public static int hapticMax = 80;                // safety limit: no motor ever above this (0..100)
    public static boolean shareLibrary = true;       // share film titles (not files, not tokens) for the multiplex schedules
    public static final List<String> libraryFolders = new ArrayList<>();   // local folders with your own video files
    public static String browserHome = "https://www.youtube.com";
    public static int browserHeight = 720;
    public static boolean jellyfinTranscode = false;  // false = direct play (original file), true = server converts to H.264/AAC
    public static int jellyfinMaxHeight = 1080;       // transcoding size limit (up to 2160 if server and PC can handle it)
    public static String audioLang = "";              // preferred audio language (ISO 639-2, e.g. "eng"), "" = the film's default
    public static String subtitleLang = "";           // subtitles burned in by the server (needs transcoding), "" = off
    public static boolean reportProgress = true;      // tell Jellyfin how far you watched (resume points, "played")
    public static final Map<String, List<String>> jellyfinLibraries = new HashMap<>();   // serverId -> library ids to use (empty = all)

    /** Quality preset values: decode height, depth-map width, particle density, streams at once. */
    public static int presetHeight() { return switch (preset) { case "LITE" -> 360; case "HIGH" -> 720; case "ULTRA" -> 1080; default -> 480; }; }
    public static int presetDepthGrid() { return switch (preset) { case "LITE" -> 32; case "HIGH" -> 96; case "ULTRA" -> 128; default -> 64; }; }
    public static float presetParticles() { return switch (preset) { case "LITE" -> 0.3f; case "HIGH" -> 1.0f; case "ULTRA" -> 1.5f; default -> 0.6f; }; }
    public static int presetStreams() { return "ULTRA".equals(preset) ? 2 : 1; }

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
            if (Json.str(m, "preset") != null) preset = Json.str(m, "preset");
            stereoStrength = d(m, "stereoStrength", stereoStrength); stereoZero = d(m, "stereoZero", stereoZero);
            stereoPopOut = d(m, "stereoPopOut", stereoPopOut); stereoScreenDepth = d(m, "stereoScreenDepth", stereoScreenDepth);
            stereoComfort = b(m, "stereoComfort", stereoComfort); stereoGhost = b(m, "stereoGhost", stereoGhost); stereoSwap = b(m, "stereoSwap", stereoSwap);
            stereoCompare = b(m, "stereoCompare", stereoCompare); auto3d = b(m, "auto3d", auto3d);
            fxSensitivity = d(m, "fxSensitivity", fxSensitivity); reducedMotion = b(m, "reducedMotion", reducedMotion); gore = (int) d(m, "gore", gore);
            hapticIntensity = d(m, "hapticIntensity", hapticIntensity); hapticMax = (int) d(m, "hapticMax", hapticMax);
            shareLibrary = b(m, "shareLibrary", shareLibrary);
            libraryFolders.clear(); for (Object o : Json.arr(m.get("libraryFolders"))) libraryFolders.add(String.valueOf(o));
            if (Json.str(m, "browserHome") != null) browserHome = Json.str(m, "browserHome");
            browserHeight = (int) d(m, "browserHeight", browserHeight);
            jellyfinTranscode = b(m, "jellyfinTranscode", jellyfinTranscode); jellyfinMaxHeight = (int) d(m, "jellyfinMaxHeight", jellyfinMaxHeight);
            if (Json.str(m, "audioLang") != null) audioLang = Json.str(m, "audioLang");
            if (Json.str(m, "subtitleLang") != null) subtitleLang = Json.str(m, "subtitleLang");
            reportProgress = b(m, "reportProgress", reportProgress);
            jellyfinLibraries.clear();
            if (m.get("jellyfinLibraries") != null) for (var e : Json.obj(m.get("jellyfinLibraries")).entrySet()) {
                List<String> l = new ArrayList<>(); for (Object o : Json.arr(e.getValue())) l.add(String.valueOf(o)); jellyfinLibraries.put(e.getKey(), l);
            }
        } catch (Exception ignored) {}
    }

    static double d(Map<String, Object> m, String k, double def) { Object v = m.get(k); return v instanceof Number n ? n.doubleValue() : def; }
    static boolean b(Map<String, Object> m, String k, boolean def) { Object v = m.get(k); return v instanceof Boolean x ? x : def; }

    /** Current 3D settings for the converter. */
    public static earthlife.core.cinema.media.StereoConverter.Settings stereo() {
        var st = new earthlife.core.cinema.media.StereoConverter.Settings();
        st.strength = (float) stereoStrength; st.zeroPlane = (float) stereoZero; st.popOut = (float) stereoPopOut; st.screenDepth = (float) stereoScreenDepth;
        st.comfort = stereoComfort; st.ghostReduction = stereoGhost; st.swapEyes = stereoSwap; st.compare = stereoCompare; st.depthGrid = presetDepthGrid();
        return st;
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
            m.put("preset", preset); m.put("stereoStrength", stereoStrength); m.put("stereoZero", stereoZero); m.put("stereoPopOut", stereoPopOut);
            m.put("stereoScreenDepth", stereoScreenDepth); m.put("stereoComfort", stereoComfort); m.put("stereoGhost", stereoGhost); m.put("stereoSwap", stereoSwap);
            m.put("stereoCompare", stereoCompare); m.put("auto3d", auto3d); m.put("fxSensitivity", fxSensitivity); m.put("reducedMotion", reducedMotion);
            m.put("gore", (long) gore); m.put("hapticIntensity", hapticIntensity); m.put("hapticMax", (long) hapticMax); m.put("shareLibrary", shareLibrary);
            m.put("libraryFolders", new ArrayList<Object>(libraryFolders)); m.put("browserHome", browserHome); m.put("browserHeight", (long) browserHeight);
            m.put("jellyfinTranscode", jellyfinTranscode); m.put("jellyfinMaxHeight", (long) jellyfinMaxHeight); m.put("audioLang", audioLang); m.put("subtitleLang", subtitleLang);
            m.put("reportProgress", reportProgress);
            Map<String, Object> libs = new LinkedHashMap<>(); jellyfinLibraries.forEach((k, v) -> libs.put(k, new ArrayList<Object>(v))); m.put("jellyfinLibraries", libs);
            Files.createDirectories(file().getParent());
            Files.writeString(file(), Json.write(m));
        } catch (Exception ignored) {}
    }
}
