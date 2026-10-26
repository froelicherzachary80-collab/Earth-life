package earthlife.mod.client;

import earthlife.core.util.Json;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Client-only preferences (config/earthlife-client.json): HUD, objectives tracker, map style. Never affects worlds. */
public final class ClientConfig {
    private ClientConfig() {}

    public static boolean showHud = true, showObjectives = true, satelliteMap = false, showCoordinates = true;

    static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("earthlife-client.json"); }

    public static void load() {
        try {
            if (!Files.exists(file())) return;
            Map<String, Object> m = Json.obj(Json.parse(Files.readString(file())));
            showHud = !Boolean.FALSE.equals(m.get("showHud"));
            showObjectives = !Boolean.FALSE.equals(m.get("showObjectives"));
            satelliteMap = Boolean.TRUE.equals(m.get("satelliteMap"));
            showCoordinates = !Boolean.FALSE.equals(m.get("showCoordinates"));
        } catch (Exception ignored) {}
    }

    public static void save() {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("showHud", showHud); m.put("showObjectives", showObjectives); m.put("satelliteMap", satelliteMap); m.put("showCoordinates", showCoordinates);
            Files.writeString(file(), Json.write(m));
        } catch (Exception ignored) {}
    }
}
