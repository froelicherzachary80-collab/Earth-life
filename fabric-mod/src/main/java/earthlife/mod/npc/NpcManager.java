package earthlife.mod.npc;

import earthlife.core.npc.Npc;
import earthlife.mod.EarthLifeMod;
import earthlife.mod.EarthLifeServices;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

/**
 * Materialises residents near players. Every 2 s: residents whose scheduled place is within 96 blocks of a player
 * get an entity (spawned at that place, on the ground); entities whose resident is no longer near any player, or
 * whose schedule moved them far away, are removed. Caps keep it light: 40 residents per player area.
 */
public final class NpcManager {
    static final double RADIUS = 96;
    static final int CAP = 40;
    private final Map<String, NpcEntity> live = new HashMap<>();

    public void tick(MinecraftServer server) {
        if (server.getTickCount() % 40 != 0) return;
        var s = EarthLifeServices.get();
        ServerLevel level = server.overworld();
        long time = earthlife.mod.McCompat.dayTime(level);
        Set<String> wanted = new HashSet<>();
        Map<String, Npc> byId = new HashMap<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level() != level) continue;
            for (var pop : s.populations)
                for (Npc n : pop.nearby(p.getX(), p.getZ(), RADIUS, time, s.settings.npcDensityEnum().cap())) { wanted.add(n.id()); byId.put(n.id(), n); }
        }
        boolean halloween = earthlife.mod.events.Halloween.isSeason(s.time.clock().zone());
        for (var e : live.entrySet()) earthlife.mod.events.Halloween.dress(e.getValue(), e.getKey(), halloween);
        for (Iterator<Map.Entry<String, NpcEntity>> it = live.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (!wanted.contains(e.getKey()) || e.getValue().isRemoved()) { e.getValue().discard(); it.remove(); }
        }
        for (String id : wanted) {
            if (live.containsKey(id)) continue;
            Npc n = byId.get(id);
            var place = n.placeFor(n.activityAt(time));
            int x = (int) Math.floor(place.x()) + (Math.abs(id.hashCode()) % 7) - 3, z = (int) Math.floor(place.z()) + (Math.abs(id.hashCode() >> 3) % 7) - 3;
            if (!level.hasChunkAt(new BlockPos(x, 0, z))) continue;          // don't force-load chunks for NPCs
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            NpcEntity e = EarthLifeMod.NPC.create(level, EntitySpawnReason.EVENT);
            if (e == null) continue;
            e.bind(n, false);
            e.setPos(x + 0.5, y, z + 0.5);
            level.addFreshEntity(e);
            live.put(id, e);
        }
    }

    public int liveCount() { return live.size(); }

    public void clear() { live.values().forEach(NpcEntity::discard); live.clear(); }
}
