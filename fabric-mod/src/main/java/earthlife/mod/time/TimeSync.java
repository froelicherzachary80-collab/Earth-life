package earthlife.mod.time;

import earthlife.core.time.GameClock;
import earthlife.core.util.Json;
import earthlife.core.world.AtomicFiles;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.ZoneId;
import java.util.*;

/**
 * Drives Minecraft's clock from {@link GameClock}. Settings live in the world (earthlife/clock.json) and can be
 * changed in-game with /earthlife time ... or from the launcher (config/earthlife/earthlife.json "time" block,
 * applied when a world is first opened).
 *
 * REALTIME: 1 Minecraft day = 1 real day, showing the real local time. Sleeping can't skip the night in this mode
 * (it would desync), so the sleep percentage gamerule is raised to 101; it is restored when leaving REALTIME.
 * UNVERIFIED: GameRules API names (26.x may rename/restructure gamerules).
 */
public final class TimeSync {
    private final GameClock clock;
    private final Path file;
    private int savedSleepPercent = 100;
    private Object stamp;

    public TimeSync(Path worldDir, Map<String, Object> launcherDefaults) throws IOException {
        this.file = worldDir.resolve("clock.json");
        Map<String, Object> saved = Files.exists(file) ? Json.obj(Json.parse(Files.readString(file))) : Map.of();
        // A newer setting chosen in the launcher (different "stamp") wins over what the world last used.
        boolean launcherNewer = launcherDefaults.get("stamp") != null && !Objects.equals(launcherDefaults.get("stamp"), saved.get("stamp"));
        Map<String, Object> cfg = new HashMap<>(saved.isEmpty() || launcherNewer ? launcherDefaults : saved);
        if (launcherNewer) stamp = launcherDefaults.get("stamp"); else stamp = saved.get("stamp");
        if (saved.get("lastRealDay") != null) cfg.put("lastRealDay", saved.get("lastRealDay"));
        if (saved.get("savedSleepPercent") != null) cfg.put("savedSleepPercent", saved.get("savedSleepPercent"));
        GameClock.Mode mode = GameClock.Mode.valueOf(((String) cfg.getOrDefault("mode", "VANILLA")).toUpperCase(Locale.ROOT));
        double minutes = ((Number) cfg.getOrDefault("minutesPerDay", 20L)).doubleValue();
        ZoneId zone = ZoneId.of((String) cfg.getOrDefault("zone", "America/New_York"));
        clock = new GameClock(mode, Math.max(1, Math.min(1440, minutes)), zone);
        if (cfg.get("lastRealDay") instanceof Number n) clock.restoreLastRealDay(n.longValue());
        if (cfg.get("savedSleepPercent") instanceof Number n) savedSleepPercent = n.intValue();
    }

    public GameClock clock() { return clock; }

    public void set(MinecraftServer server, GameClock.Mode mode, double minutesPerDay) throws IOException {
        GameClock.Mode before = clock.mode();
        clock.configure(mode, minutesPerDay, clock.zone());
        ServerLevel ow = server.overworld();
        var rules = ow.getGameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(mode == GameClock.Mode.VANILLA, server);
        if (mode == GameClock.Mode.REALTIME && before != GameClock.Mode.REALTIME) {
            savedSleepPercent = rules.getInt(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE);
            rules.getRule(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE).set(101, server);
        } else if (mode != GameClock.Mode.REALTIME && before == GameClock.Mode.REALTIME) {
            rules.getRule(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE).set(savedSleepPercent, server);
        }
        save();
    }

    /** Every server tick. */
    public void tick(MinecraftServer server) {
        if (!clock.drivesTime()) return;
        ServerLevel ow = server.overworld();
        long now = ow.getDayTime();
        long next = clock.next(System.currentTimeMillis(), now);
        if (next != now) for (ServerLevel l : server.getAllLevels()) l.setDayTime(next);
    }

    public String describe(long dayTime) {
        String t = GameClock.timeOf(dayTime).toString();
        return switch (clock.mode()) {
            case VANILLA -> "Vanilla time (1 day = 20 min) - " + t;
            case REALTIME -> "Real time (" + clock.zone() + ") - " + t;
            case SCALED -> String.format("1 day = %.0f real minutes - %s", clock.minutesPerDay(), t);
        };
    }

    public void save() throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", clock.mode().name()); m.put("minutesPerDay", clock.minutesPerDay()); m.put("zone", clock.zone().getId());
        m.put("lastRealDay", clock.lastRealDay()); m.put("savedSleepPercent", savedSleepPercent);
        if (stamp != null) m.put("stamp", stamp);
        AtomicFiles.write(file, Json.write(m).getBytes(StandardCharsets.UTF_8));
    }
}
