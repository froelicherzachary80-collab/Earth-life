package earthlife.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Every Minecraft call whose shape changed in 26.x goes through here (checked against the 26.3 API dump). */
public final class McCompat {
    private McCompat() {}

    public static void set(ChunkAccess chunk, BlockPos.MutableBlockPos pos, BlockState state) { chunk.setBlockState(pos, state, 0); }

    /** Overworld clock ticks (26.x replaced Level#getDayTime with world clocks). */
    public static long dayTime(Level level) { return level.getOverworldClockTime(); }

    public static void setDayTime(MinecraftServer server, long ticks) {
        var clock = server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD);
        server.overworld().clockManager().setTotalTicks(clock, ticks);
    }

    public static boolean isNight(Level level) { return level.isDarkOutside(); }

    public static void overlay(ServerPlayer p, String text) { p.sendOverlayMessage(Component.literal(text)); }

    public static boolean ride(Entity rider, Entity vehicle) { return rider.startRiding(vehicle, true, true); }

    /** Operator check (permission level 2, "gamemaster"); 26.x replaced int levels with PermissionSet. */
    public static boolean isOp(net.minecraft.commands.CommandSourceStack src) {
        return src.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
    }
    /** Time controls: operators, or anyone in their own singleplayer world. (The server can be null while the command tree is being sent.) */
    public static boolean canChangeTime(net.minecraft.commands.CommandSourceStack src) {
        if (isOp(src)) return true;
        var server = src.getServer();
        return server != null && server.isSingleplayer();
    }

    public static boolean isOp(ServerPlayer p) { return p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER); }

    public static Identifier id(String path) { return Identifier.fromNamespaceAndPath("earthlife", path); }

    /** Item by registry id (for dyed/colour-collection items such as "minecraft:red_bed"). */
    public static net.minecraft.world.item.Item item(String id) {
        Identifier rl = Identifier.tryParse(id);
        var it = rl == null ? null : BuiltInRegistries.ITEM.getValue(rl);
        return it == null ? net.minecraft.world.item.Items.STICK : it;
    }

    /** Entities EarthLife spawns for this session only (models, decorations, mini-games). */
    public static final String TEMP_TAG = "earthlife_temp";
    private static final java.util.Set<java.util.UUID> SESSION = ConcurrentHashMap.newKeySet();

    /** Adds an entity that must not outlive this server session (stale copies loaded from disk later are removed). */
    public static boolean spawnTemporary(net.minecraft.server.level.ServerLevel level, Entity e) {
        e.addTag(TEMP_TAG);
        SESSION.add(e.getUUID());
        return level.addFreshEntity(e);
    }

    /** ServerEntityEvents.LOAD: discard EarthLife session entities saved by an earlier session. */
    public static void onEntityLoad(Entity e) {
        if (e.entityTags().contains(TEMP_TAG) && !SESSION.contains(e.getUUID())) e.discard();
    }

    private static final Map<String, BlockState> BLOCKS = new ConcurrentHashMap<>();

    /** Block by registry id ("minecraft:light_gray_concrete"); unknown ids fall back to stone and are logged once. */
    public static BlockState block(String id) {
        return BLOCKS.computeIfAbsent(id, k -> {
            Identifier rl = Identifier.tryParse(k);
            Block b = rl == null ? null : BuiltInRegistries.BLOCK.getValue(rl);
            if (b == null || (b == Blocks.AIR && !k.endsWith("air"))) {
                EarthLifeMod.LOG.warn("EarthLife: unknown block id {}, using stone", k);
                return Blocks.STONE.defaultBlockState();
            }
            return b.defaultBlockState();
        });
    }
}
