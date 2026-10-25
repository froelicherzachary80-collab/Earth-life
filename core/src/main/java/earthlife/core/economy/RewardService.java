package earthlife.core.economy;

import earthlife.core.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Free LifeCoins rewards: 150 LC per 60 minutes of qualifying active play, 700 LC daily login,
 * configurable weekly bonus.
 *
 * Anti-exploit design:
 *  - Hourly progress is measured in *server game ticks while the player is active* (moved, interacted
 *    or took damage within the last 5 minutes), never wall-clock time, so changing the PC clock or idling AFK earns nothing.
 *  - Daily/weekly use the server's wall clock but only ever move forward: the service remembers the
 *    highest timestamp ever seen and ignores time that goes backwards (clock rollback).
 *  - Every claim posts to the ledger with a deterministic reference (e.g. "hourly#7", "daily#2026-10-25"),
 *    so a double click or replayed packet hits DuplicateReference instead of paying twice.
 */
public final class RewardService {
    public static final long TICKS_PER_SECOND = 20;
    public static final long HOURLY_TICKS = 60 * 60 * TICKS_PER_SECOND;
    public static final long AFK_TIMEOUT_TICKS = 5 * 60 * TICKS_PER_SECOND;

    public record Config(long hourlyAmount, long dailyAmount, long weeklyAmount, int weeklyActiveHours, ZoneId zone) {
        public static Config defaults() { return new Config(150, 700, 1500, 5, ZoneId.of("America/New_York")); }
    }

    private static final class PlayerState {
        long activeTicks;          // total qualifying ticks ever
        long hourlyClaimed;        // number of hourly rewards claimed
        long lastActivityTick = Long.MIN_VALUE / 2;
        long lastSeenTick;
        long maxWallClockMs;
        String lastDailyDate = "";
        String lastWeeklyKey = "";
        long weekStartActiveTicks;
        String weekKeyForStart = "";
    }

    private final Ledger ledger;
    private final Config cfg;
    private final Path stateFile;
    private final Map<String, PlayerState> players = new HashMap<>();

    public RewardService(Ledger ledger, Config cfg, Path stateFile) throws IOException {
        this.ledger = ledger; this.cfg = cfg; this.stateFile = stateFile;
        load();
    }

    private PlayerState st(String p) { return players.computeIfAbsent(p, k -> new PlayerState()); }

    /** Call when the player does something that proves they're present (move, mine, interact, take damage). */
    public synchronized void markActivity(String player, long gameTick) { st(player).lastActivityTick = gameTick; }

    /** Call once per server tick (or batched) for each online player. Returns true if the tick counted. */
    public synchronized boolean tick(String player, long gameTick) {
        PlayerState s = st(player);
        if (gameTick <= s.lastSeenTick) return false;     // ticks must strictly advance
        long delta = Math.min(gameTick - s.lastSeenTick, 40); // cap catch-up after lag/sleep at 2 s
        s.lastSeenTick = gameTick;
        if (gameTick - s.lastActivityTick > AFK_TIMEOUT_TICKS) return false;
        s.activeTicks += delta;
        return true;
    }

    public synchronized long hourlyAvailable(String player) {
        PlayerState s = st(player);
        return s.activeTicks / HOURLY_TICKS - s.hourlyClaimed;
    }

    /** Ticks of active play left until the next hourly reward unlocks (0 if one is ready). */
    public synchronized long ticksUntilNextHourly(String player) {
        if (hourlyAvailable(player) > 0) return 0;
        return HOURLY_TICKS - (st(player).activeTicks % HOURLY_TICKS);
    }

    public synchronized Optional<Ledger.Entry> claimHourly(String player, long gameTick) throws IOException {
        if (hourlyAvailable(player) <= 0) return Optional.empty();
        PlayerState s = st(player);
        long n = s.hourlyClaimed + 1;
        try {
            Ledger.Entry e = ledger.credit(player, cfg.hourlyAmount(), "REWARD_HOURLY", "hourly#" + n, "Hourly active-play reward", gameTick);
            s.hourlyClaimed = n;
            save();
            return Optional.of(e);
        } catch (Ledger.DuplicateReference d) {
            s.hourlyClaimed = n; save();      // state was behind the ledger (crash between writes): resync, no double pay
            return Optional.empty();
        }
    }

    private long observeClock(PlayerState s, long wallClockMs) {
        if (wallClockMs > s.maxWallClockMs) s.maxWallClockMs = wallClockMs;
        return s.maxWallClockMs;   // never let time run backwards
    }

    public synchronized boolean dailyAvailable(String player, long wallClockMs) {
        PlayerState s = st(player);
        String today = date(observeClock(s, wallClockMs));
        return today.compareTo(s.lastDailyDate) > 0;
    }

    public synchronized Optional<Ledger.Entry> claimDaily(String player, long wallClockMs, long gameTick) throws IOException {
        PlayerState s = st(player);
        long now = observeClock(s, wallClockMs);
        String today = date(now);
        if (today.compareTo(s.lastDailyDate) <= 0) return Optional.empty();
        try {
            Ledger.Entry e = ledger.credit(player, cfg.dailyAmount(), "REWARD_DAILY", "daily#" + today, "Daily login reward", gameTick);
            s.lastDailyDate = today; save();
            return Optional.of(e);
        } catch (Ledger.DuplicateReference d) { s.lastDailyDate = today; save(); return Optional.empty(); }
    }

    /** Weekly milestone: weeklyAmount after weeklyActiveHours of active play within the same ISO week. */
    public synchronized Optional<Ledger.Entry> claimWeekly(String player, long wallClockMs, long gameTick) throws IOException {
        PlayerState s = st(player);
        String wk = weekKey(observeClock(s, wallClockMs));
        if (!wk.equals(s.weekKeyForStart)) { s.weekKeyForStart = wk; s.weekStartActiveTicks = s.activeTicks; }
        if (wk.equals(s.lastWeeklyKey)) return Optional.empty();
        if (s.activeTicks - s.weekStartActiveTicks < cfg.weeklyActiveHours() * HOURLY_TICKS) { save(); return Optional.empty(); }
        try {
            Ledger.Entry e = ledger.credit(player, cfg.weeklyAmount(), "REWARD_WEEKLY", "weekly#" + wk, "Weekly milestone bonus", gameTick);
            s.lastWeeklyKey = wk; save();
            return Optional.of(e);
        } catch (Ledger.DuplicateReference d) { s.lastWeeklyKey = wk; save(); return Optional.empty(); }
    }

    private String date(long ms) { return Instant.ofEpochMilli(ms).atZone(cfg.zone()).toLocalDate().toString(); }

    private String weekKey(long ms) {
        LocalDate d = Instant.ofEpochMilli(ms).atZone(cfg.zone()).toLocalDate();
        return d.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR) + "-W" + String.format("%02d", d.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    // ---- persistence ----
    private void load() throws IOException {
        if (!Files.exists(stateFile)) return;
        Map<String, Object> root = Json.obj(Json.parse(Files.readString(stateFile)));
        for (var e : root.entrySet()) {
            Map<String, Object> m = Json.obj(e.getValue());
            PlayerState s = st(e.getKey());
            s.activeTicks = ((Number) m.get("activeTicks")).longValue();
            s.hourlyClaimed = ((Number) m.get("hourlyClaimed")).longValue();
            s.lastSeenTick = ((Number) m.get("lastSeenTick")).longValue();
            s.maxWallClockMs = ((Number) m.get("maxWallClockMs")).longValue();
            s.lastDailyDate = (String) m.get("lastDailyDate");
            s.lastWeeklyKey = (String) m.get("lastWeeklyKey");
            s.weekKeyForStart = (String) m.get("weekKeyForStart");
            s.weekStartActiveTicks = ((Number) m.get("weekStartActiveTicks")).longValue();
        }
    }

    public synchronized void save() throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        for (var e : players.entrySet()) {
            PlayerState s = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("activeTicks", s.activeTicks); m.put("hourlyClaimed", s.hourlyClaimed); m.put("lastSeenTick", s.lastSeenTick);
            m.put("maxWallClockMs", s.maxWallClockMs); m.put("lastDailyDate", s.lastDailyDate); m.put("lastWeeklyKey", s.lastWeeklyKey);
            m.put("weekKeyForStart", s.weekKeyForStart); m.put("weekStartActiveTicks", s.weekStartActiveTicks);
            root.put(e.getKey(), m);
        }
        earthlife.core.world.AtomicFiles.write(stateFile, Json.write(root).getBytes(StandardCharsets.UTF_8));
    }
}
