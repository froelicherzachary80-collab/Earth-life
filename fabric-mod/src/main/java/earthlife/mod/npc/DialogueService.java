package earthlife.mod.npc;

import earthlife.core.npc.Npc;
import earthlife.core.npc.Social;
import earthlife.mod.EarthLifeServices;
import earthlife.mod.McCompat;
import earthlife.mod.events.Halloween;
import earthlife.core.life.PlayerLife;
import earthlife.mod.net.Payloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/**
 * Talking to residents: right-click opens a dialogue with choices (the client shows them as buttons). Choices map to
 * server actions; the client only sends "/earthlife talk <entity> <choice>", so it can't invent outcomes.
 */
public final class DialogueService {
    private DialogueService() {}

    record Pending(int entityId, String residentId, List<String> actions) {}
    private static final Map<UUID, Pending> pending = new HashMap<>();

    static final Map<String, String> LABELS = Map.of(
            "chat", "How's your day going?",
            "work", "What do you do for work?",
            "jobs", "Where can I find a job around here?",
            "directions", "How do I get to the town centre?",
            "trick", "Trick or treat!",
            "gift", "Give a gift (held item)",
            "bye", "Goodbye");

    /** Opens (or continues) a conversation. */
    public static void open(ServerPlayer p, NpcEntity e, Npc npc, String line) {
        var s = EarthLifeServices.get();
        List<String> actions = new ArrayList<>(List.of("chat", "work", "jobs", "directions"));
        if (Halloween.isSeason(s.time.clock().zone())) actions.add(0, "trick");
        if (!p.getMainHandItem().isEmpty()) actions.add("gift");
        actions.add("bye");
        pending.put(p.getUUID(), new Pending(e.getId(), npc.id(), actions));
        if (!earthlife.mod.net.Net.hasClientMod(p)) { chatFallback(p, npc, line); return; }
        String rel = s.social.level(p.getStringUUID(), npc.id()).name().replace('_', ' ').toLowerCase(Locale.ROOT);
        earthlife.mod.net.Net.send(p, new Payloads.Dialogue(e.getId(), npc.name(), rel + " · " + npc.personality().name().toLowerCase(Locale.ROOT)
                + " · " + npc.activityAt(McCompat.dayTime(p.level())).name().replace('_', ' ').toLowerCase(Locale.ROOT), line,
                actions.stream().map(LABELS::get).toList()));
    }

    /** Handles "/earthlife talk <entity> <choice>". Returns a chat line when no dialogue follows (or null). */
    public static String choose(ServerPlayer p, int entityId, int choice) throws Exception {
        Pending pd = pending.get(p.getUUID());
        if (pd == null || pd.entityId != entityId || choice < 0 || choice >= pd.actions.size()) return "That conversation has ended.";
        var s = EarthLifeServices.get();
        if (!(p.level().getEntity(entityId) instanceof NpcEntity e) || e.distanceTo(p) > 8) { pending.remove(p.getUUID()); return "They're too far away now."; }
        Npc npc = s.npc(pd.residentId).orElse(null);
        if (npc == null) return "They're gone.";
        long time = McCompat.dayTime(p.level());
        String action = pd.actions.get(choice);
        String reply = switch (action) {
            case "chat" -> {
                s.social.interact(p.getStringUUID(), npc, Social.Action.CHAT, time);
                yield s.social.line(npc, p.getStringUUID(), p.getName().getString(), time);
            }
            case "work" -> npc.work() == null ? "I'm between jobs right now." :
                    "I work as a " + npc.job().title.toLowerCase(Locale.ROOT) + " at " + npc.work().label() + ". "
                            + (npc.job().hasShifts() ? String.format("My shift is %02d:00 to %02d:00.", npc.job().shiftStart, npc.job().shiftEnd) : "I set my own hours.");
            case "jobs" -> {
                earthlife.mod.ui.UiLists.send(p, "jobs");
                earthlife.mod.net.Net.send(p, new Payloads.OpenScreen("menu:jobs"));
                pending.remove(p.getUUID());
                yield null;
            }
            case "directions" -> {
                var t = s.towns.isEmpty() ? null : earthlife.core.world.Towns.nearest(s.towns, s.projection.fromBlock(p.getX(), p.getZ()));
                if (t == null) yield "Sorry, I'm new here myself.";
                var b = s.projection.toBlock(t.centre().lat(), t.centre().lon());
                PlayerLife life = s.life.life(p);
                life.waypoints.removeIf(w -> w.name().equals(t.name() + " centre"));
                life.waypoints.add(new PlayerLife.Waypoint(t.name() + " centre", "town", b.x(), p.getY(), b.z()));
                life.markDirty();
                double d = Math.hypot(b.x() - p.getX(), b.z() - p.getZ());
                yield String.format("%s centre is about %.0f m %s of here. I've marked it on your map [M].", t.name(), d,
                        compass(Math.toDegrees(Math.atan2(b.x() - p.getX(), -(b.z() - p.getZ())))));
            }
            case "trick" -> Halloween.trickOrTreat(p, npc);
            case "gift" -> {
                ItemStack held = p.getMainHandItem();
                if (held.isEmpty()) yield "Oh, you're not holding anything!";
                String what = held.getHoverName().getString();
                held.shrink(1);
                int aff = s.social.interact(p.getStringUUID(), npc, Social.Action.GIFT, time);
                yield "For me? A " + what + "! Thank you so much. (" + s.social.level(p.getStringUUID(), npc.id()).name().toLowerCase(Locale.ROOT).replace('_', ' ') + ", " + aff + ")";
            }
            default -> { pending.remove(p.getUUID()); yield null; }
        };
        if (reply != null) open(p, e, npc, reply);
        return null;
    }

    static String compass(double bearing) {
        return new String[]{"north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"}[(int) Math.round(((bearing % 360) + 360) % 360 / 45) % 8];
    }

    public static void forget(ServerPlayer p) { pending.remove(p.getUUID()); }

    /** Plain chat fallback for clients without EarthLife installed (vanilla clients on a server). */
    public static void chatFallback(ServerPlayer p, Npc npc, String line) {
        p.sendSystemMessage(Component.literal("§b" + npc.name() + "§r: " + line));
    }
}
