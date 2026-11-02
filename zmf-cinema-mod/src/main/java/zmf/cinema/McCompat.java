// STANDALONE
package zmf.cinema;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Minecraft 26.3 calls whose shape changed in 26.x (checked against the 26.3 API dump). */
public final class McCompat {
    private McCompat() {}

    /** Operator check (permission level 2, "gamemaster"); 26.x replaced int levels with PermissionSet. */
    public static boolean isOp(net.minecraft.commands.CommandSourceStack src) {
        return src.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
    }
    public static boolean isOp(ServerPlayer p) { return p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER); }

    /** Operators, or anyone in their own singleplayer world. */
    public static boolean canChangeTime(net.minecraft.commands.CommandSourceStack src) {
        if (isOp(src)) return true;
        var server = src.getServer();
        return server != null && server.isSingleplayer();
    }

    /** Overworld clock ticks (26.x replaced Level#getDayTime with world clocks). */
    public static long dayTime(net.minecraft.world.level.Level level) { return level.getOverworldClockTime(); }

    public static void setDayTime(net.minecraft.server.MinecraftServer server, long ticks) {
        var clock = server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_CLOCK).getOrThrow(net.minecraft.world.clock.WorldClocks.OVERWORLD);
        server.overworld().clockManager().setTotalTicks(clock, ticks);
    }

    public static final String TEMP_TAG = "zmfcinema_temp";
    private static final Set<UUID> SESSION = ConcurrentHashMap.newKeySet();

    /** Adds an entity that must not outlive this server session (screens, signs). */
    public static boolean spawnTemporary(net.minecraft.server.level.ServerLevel level, Entity e) {
        e.addTag(TEMP_TAG);
        SESSION.add(e.getUUID());
        return level.addFreshEntity(e);
    }

    /** Stale session entities saved by an earlier session are removed when loaded. */
    public static void onEntityLoad(Entity e) {
        if (e.entityTags().contains(TEMP_TAG) && !SESSION.contains(e.getUUID())) e.discard();
    }

    private static final Map<String, BlockState> BLOCKS = new ConcurrentHashMap<>();

    /** Block by registry id; unknown ids fall back to stone (logged once). */
    public static BlockState block(String id) {
        return BLOCKS.computeIfAbsent(id, k -> {
            Identifier rl = Identifier.tryParse(k);
            Block b = rl == null ? null : BuiltInRegistries.BLOCK.getValue(rl);
            if (b == null || (b == Blocks.AIR && !k.endsWith("air"))) {
                ZmfCinemaMod.LOG.warn("ZMF Cinema: unknown block id {}, using stone", k);
                return Blocks.STONE.defaultBlockState();
            }
            return b.defaultBlockState();
        });
    }
}
