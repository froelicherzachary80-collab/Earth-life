package earthlife.core.npc;

import earthlife.core.time.GameClock;
import earthlife.core.util.Json;
import earthlife.core.world.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Relationships and conversation. Affinity runs -100..100 per (player, NPC) pair; greetings, small talk and gifts
 * move it. Dialogue is assembled from the NPC's real state (job, what they're doing, time of day, how well they
 * know you) - deterministic and offline, with a hook to swap in a local AI model later.
 */
public final class Social {
    public enum Level { STRANGER, ACQUAINTANCE, FRIEND, CLOSE_FRIEND, RIVAL }
    public enum Action { GREET, CHAT, GIFT, HELP, INSULT }

    private final Map<String, Integer> affinity = new HashMap<>();
    private final Map<String, Long> lastChatDay = new HashMap<>();
    private final Path file;

    public Social(Path file) throws IOException {
        this.file = file;
        if (file != null && Files.exists(file)) {
            var m = Json.obj(Json.parse(Files.readString(file)));
            for (var e : Json.obj(m.get("affinity")).entrySet()) affinity.put(e.getKey(), ((Number) e.getValue()).intValue());
            for (var e : Json.obj(m.get("lastChat")).entrySet()) lastChatDay.put(e.getKey(), ((Number) e.getValue()).longValue());
        }
    }

    private static String key(String player, String npc) { return player + "|" + npc; }
    public int affinity(String player, String npc) { return affinity.getOrDefault(key(player, npc), 0); }

    public Level level(String player, String npc) {
        int a = affinity(player, npc);
        return a <= -30 ? Level.RIVAL : a < 10 ? Level.STRANGER : a < 40 ? Level.ACQUAINTANCE : a < 75 ? Level.FRIEND : Level.CLOSE_FRIEND;
    }

    /** Applies a social action. Repeated chats on the same game day give no extra affinity (no spamming). */
    public int interact(String player, Npc npc, Action action, long dayTime) throws IOException {
        String k = key(player, npc.id());
        long day = GameClock.day(dayTime);
        int delta = switch (action) {
            case GREET -> 1;
            case CHAT -> Objects.equals(lastChatDay.get(k), day) ? 0 : (npc.personality() == Npc.Personality.CHATTY ? 4 : npc.personality() == Npc.Personality.SHY ? 2 : 3);
            case GIFT -> 8;
            case HELP -> 10;
            case INSULT -> npc.personality() == Npc.Personality.GRUMPY ? -5 : -12;
        };
        if (action == Action.CHAT) lastChatDay.put(k, day);
        int v = Math.max(-100, Math.min(100, affinity(player, npc.id()) + delta));
        affinity.put(k, v);
        save();
        return v;
    }

    public String line(Npc npc, String playerId, String playerName, long dayTime) {
        int h = GameClock.hour(dayTime);
        String greet = h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening";
        Level lv = level(playerId, npc.id());
        Npc.Activity a = npc.activityAt(dayTime);
        String doing = switch (a) {
            case WORKING -> "I'm on shift as a " + npc.job().title.toLowerCase(Locale.ROOT) + (npc.work() != null ? " here" : "") + ".";
            case LUNCH -> "Just grabbing lunch before I head back to work.";
            case COMMUTING_TO_WORK -> "Can't stop long, I'm heading to work.";
            case COMMUTING_HOME -> "Long day. Heading home.";
            case LEISURE -> "Nice evening to be out in " + npc.town() + ".";
            default -> "Just taking it easy around the house.";
        };
        String opener = switch (lv) {
            case STRANGER -> greet + ". I'm " + npc.firstName() + ".";
            case ACQUAINTANCE -> greet + ", " + playerName + "!";
            case FRIEND -> "Hey " + playerName + "! Good to see you.";
            case CLOSE_FRIEND -> playerName + "! Was hoping I'd run into you.";
            case RIVAL -> "Oh. It's you.";
        };
        if (npc.personality() == Npc.Personality.GRUMPY && lv.ordinal() < Level.FRIEND.ordinal()) opener = "Hmph. " + opener;
        if (npc.personality() == Npc.Personality.SHY && lv == Level.STRANGER) opener = "Oh, um... hi. I'm " + npc.firstName() + ".";
        return opener + " " + doing;
    }

    private void save() throws IOException {
        if (file == null) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("affinity", new TreeMap<>(affinity)); m.put("lastChat", new TreeMap<>(lastChatDay));
        AtomicFiles.write(file, Json.write(m).getBytes(StandardCharsets.UTF_8));
    }
}
