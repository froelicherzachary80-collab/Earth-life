package earthlife.mod.world;

import com.mojang.serialization.MapCodec;
import earthlife.core.world.*;
import earthlife.core.world.BuildingStyle;
import earthlife.core.world.TileData.Surface;
import earthlife.mod.EarthLifeServices;
import earthlife.mod.McCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.StructureManager;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Overworld generator for the VoxelEarth Engine. Each 16x16 chunk reads its columns from the core
 * {@link TileStreamer} (tile generation runs on worker threads and is cached on disk), so Minecraft's own
 * chunk pipeline and region files handle loading, unloading and saving player edits.
 *
 * The Nether and End keep their vanilla generators (see the earthlife world preset), so vanilla progression is intact.
 * UNVERIFIED against the 26.3 ChunkGenerator API: abstract method set matches 1.21.x.
 */
public final class VoxelEarthChunkGenerator extends ChunkGenerator {
    public static final MapCodec<VoxelEarthChunkGenerator> CODEC =
            BiomeSource.CODEC.fieldOf("biome_source").xmap(VoxelEarthChunkGenerator::new, g -> g.biomeSource);

    // V0.3: the EarthLife overworld is 1024 blocks tall (min -64, top 959; see dimension_type/earthlife_overworld.json)
    // so real skyscrapers fit at their true heights on top of real terrain elevation.
    private static final int MIN_Y = -64, HEIGHT = 1024, SEA = 63;

    public VoxelEarthChunkGenerator(BiomeSource biomes) { super(biomes); }

    @Override protected MapCodec<? extends ChunkGenerator> codec() { return CODEC; }

    private static TileData tileFor(int blockX, int blockZ) {
        var s = EarthLifeServices.get();
        return s.tiles.request(TileKey.containing(s.region, blockX, blockZ, 0)).join();
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState random, StructureManager structures, ChunkAccess chunk) {
        int cx = chunk.getPos().getMinBlockX(), cz = chunk.getPos().getMinBlockZ();
        TileData t = tileFor(cx, cz);
        var pos = new BlockPos.MutableBlockPos();
        for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
            int x = cx + dx, z = cz + dz;
            int sx = x - t.key.minX(), sz = z - t.key.minZ();
            int i = t.idx(sx, sz);
            int top = Math.max(MIN_Y + 2, Math.min(MIN_Y + HEIGHT - 40, t.terrainY[i]));
            Surface surf = t.surfaceAt(sx, sz);
            for (int y = MIN_Y; y <= top; y++) {
                BlockState st = y == MIN_Y ? Blocks.BEDROCK.defaultBlockState()
                        : y < top - 4 ? (y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState())
                        : y < top ? Blocks.DIRT.defaultBlockState() : surfaceBlock(surf);
                McCompat.set(chunk, pos.set(x, y, z), st);
            }
            if (t.has(sx, sz, TileData.FLAG_WATER)) {
                for (int y = top + 1; y <= top + 2; y++) McCompat.set(chunk, pos.set(x, y, z), Blocks.WATER.defaultBlockState());
            }
            if (surf == Surface.FARMLAND && !t.has(sx, sz, TileData.FLAG_ROAD) && Math.floorMod(x, 4) != 0)
                McCompat.set(chunk, pos.set(x, top + 1, z), Blocks.WHEAT.defaultBlockState());
            int roof = t.structureTopY[i];
            if (roof > 0) building(chunk, pos, x, z, top, t, sx, sz);
            if (t.has(sx, sz, TileData.FLAG_TREE)) tree(chunk, pos, x, z, top, cx, cz, surf == Surface.ORCHARD);
        }
        placeBlueprints(chunk, pos, cx, cz);
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG));
        return CompletableFuture.completedFuture(chunk);
    }

    /** Airport terminals, towers, aprons, jet bridges: generated blueprints written straight into the chunk. */
    private static void placeBlueprints(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int cx, int cz) {
        for (var placed : EarthLifeServices.get().structures()) {                 // airports + landmarks
            int[] b = placed.worldBounds();
            if (b[2] < cx || b[0] > cx + 15 || b[3] < cz || b[1] > cz + 15) continue;
            placed.forEachIn(cx, cz, cx + 15, cz + 15, blk -> {
                if (blk.y() < MIN_Y || blk.y() >= MIN_Y + HEIGHT) return;
                McCompat.set(chunk, pos.set(blk.x(), blk.y(), blk.z()), BLOCK_CACHE.computeIfAbsent(blk.id(), VoxelEarthChunkGenerator::lookup));
            });
        }
    }

    private static final Map<String, BlockState> BLOCK_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static BlockState lookup(String id) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.ResourceLocation.parse(id)).defaultBlockState();
    }

    static BlockState surfaceBlock(Surface s) {
        return switch (s) {
            case GRASS, ORCHARD, FOREST -> Blocks.GRASS_BLOCK.defaultBlockState();
            case FARMLAND -> Blocks.FARMLAND.defaultBlockState();
            case SAND -> Blocks.SAND.defaultBlockState();
            case WATER -> Blocks.GRAVEL.defaultBlockState();
            case ASPHALT -> Blocks.GRAY_CONCRETE.defaultBlockState();
            case ROAD_MARKING -> Blocks.YELLOW_CONCRETE.defaultBlockState();
            case GRAVEL_PATH -> Blocks.DIRT_PATH.defaultBlockState();
            case RUNWAY -> Blocks.BLACK_CONCRETE.defaultBlockState();
            case TAXIWAY -> Blocks.GRAY_CONCRETE_POWDER.defaultBlockState();
            case CONCRETE -> Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
            case DIRT -> Blocks.COARSE_DIRT.defaultBlockState();
            case WETLAND -> Blocks.MUD.defaultBlockState();
            case RAIL_BED -> Blocks.GRAVEL.defaultBlockState();
        };
    }

    static BlockState wallBlock(BuildingStyle.Wall w) {
        return switch (w) {
            case WHITE_SIDING -> Blocks.WHITE_TERRACOTTA.defaultBlockState();
            case CREAM_SIDING -> Blocks.BIRCH_PLANKS.defaultBlockState();
            case GREY_SIDING -> Blocks.LIGHT_GRAY_TERRACOTTA.defaultBlockState();
            case BLUE_SIDING -> Blocks.LIGHT_BLUE_TERRACOTTA.defaultBlockState();
            case YELLOW_SIDING -> Blocks.YELLOW_TERRACOTTA.defaultBlockState();
            case GREEN_SIDING -> Blocks.GREEN_TERRACOTTA.defaultBlockState();
            case RED_BRICK -> Blocks.BRICKS.defaultBlockState();
            case BROWN_BRICK -> Blocks.MUD_BRICKS.defaultBlockState();
            case STONE -> Blocks.STONE_BRICKS.defaultBlockState();
            case CONCRETE -> Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
            case GLASS -> Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState();
            case METAL -> Blocks.IRON_BLOCK.defaultBlockState();
            case RED_BARN -> Blocks.RED_TERRACOTTA.defaultBlockState();
            case WOOD -> Blocks.SPRUCE_PLANKS.defaultBlockState();
            case PLASTER -> Blocks.SMOOTH_SANDSTONE.defaultBlockState();
        };
    }

    static BlockState roofBlock(BuildingStyle.Roof r) {
        return switch (r) {
            case ASPHALT_DARK -> Blocks.DEEPSLATE_TILES.defaultBlockState();
            case ASPHALT_GREY -> Blocks.STONE_BRICKS.defaultBlockState();
            case ASPHALT_BROWN -> Blocks.DARK_OAK_PLANKS.defaultBlockState();
            case METAL_GREEN -> Blocks.GREEN_CONCRETE.defaultBlockState();
            case METAL_RED -> Blocks.RED_NETHER_BRICKS.defaultBlockState();
            case METAL_GREY -> Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
            case SLATE -> Blocks.POLISHED_DEEPSLATE.defaultBlockState();
            case COPPER -> Blocks.OXIDIZED_COPPER.defaultBlockState();
            case TAR_FLAT -> Blocks.SMOOTH_STONE.defaultBlockState();
            case GLASS -> Blocks.GLASS.defaultBlockState();
        };
    }

    /**
     * Realistic building column: walls up to the eave with window rows on every floor (curtain-wall glass for glass
     * buildings), hollow walkable floors every 3 blocks, a doorway where the map says the front faces the street,
     * and a solid roof following the real/estimated roof shape (gabled, hipped, gambrel, dome...) with a chimney on many houses.
     */
    private static void building(ChunkAccess c, BlockPos.MutableBlockPos p, int x, int z, int base, TileData t, int sx, int sz) {
        int i = t.idx(sx, sz);
        int eave = t.eaveY[i] > 0 ? t.eaveY[i] : t.structureTopY[i] - 1, top = t.structureTopY[i];
        boolean wall = t.has(sx, sz, TileData.FLAG_BUILDING_WALL), door = t.has2(sx, sz, TileData.F2_DOOR), chimney = t.has2(sx, sz, TileData.F2_CHIMNEY);
        var wallMat = BuildingStyle.Wall.values()[t.wallMat[i]];
        BlockState wb = wallBlock(wallMat), rb = roofBlock(BuildingStyle.Roof.values()[t.roofMat[i]]);
        boolean glassBuilding = wallMat == BuildingStyle.Wall.GLASS;
        BlockState window = glassBuilding ? Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState() : Blocks.GLASS_PANE.defaultBlockState();
        BlockState floor = wallMat.name().endsWith("SIDING") || wallMat == BuildingStyle.Wall.WOOD ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.POLISHED_ANDESITE.defaultBlockState();
        for (int y = base; y <= eave; y++) {
            int level = y - base;
            BlockState st;
            if (wall) {
                if (door && (level == 1 || level == 2)) st = Blocks.AIR.defaultBlockState();
                else if (glassBuilding) st = level % 3 == 0 ? Blocks.GRAY_CONCRETE.defaultBlockState() : window;
                else st = (level % 3 == 2 && Math.floorMod(x + z, 3) != 0 && wallMat != BuildingStyle.Wall.RED_BARN && wallMat != BuildingStyle.Wall.METAL) ? window : wb;
            } else st = level % 3 == 0 ? floor : Blocks.AIR.defaultBlockState();
            McCompat.set(c, p.set(x, y, z), st);
        }
        if (door) McCompat.set(c, p.set(x, base, z), Blocks.POLISHED_ANDESITE.defaultBlockState());          // threshold
        for (int y = eave + 1; y <= top; y++) McCompat.set(c, p.set(x, y, z), y == top ? rb : (wall ? wb : rb));
        if (chimney) for (int y = top + 1; y <= top + 2; y++) McCompat.set(c, p.set(x, y, z), Blocks.BRICKS.defaultBlockState());
    }

    private static void tree(ChunkAccess c, BlockPos.MutableBlockPos p, int x, int z, int base, int cx, int cz, boolean orchard) {
        int h = orchard ? 3 : 5 + Math.floorMod(x * 31 + z * 17, 3);
        BlockState log = orchard ? Blocks.OAK_LOG.defaultBlockState() : (Math.floorMod(x + z, 3) == 0 ? Blocks.BIRCH_LOG.defaultBlockState() : Blocks.OAK_LOG.defaultBlockState());
        BlockState leaves = orchard ? Blocks.FLOWERING_AZALEA_LEAVES.defaultBlockState() : Blocks.OAK_LEAVES.defaultBlockState();
        for (int y = 1; y <= h; y++) McCompat.set(c, p.set(x, base + y, z), log);
        int r = orchard ? 1 : 2;
        for (int dy = h - 1; dy <= h + 1; dy++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            int lx = x + dx, lz = z + dz;
            if (lx < cx || lx > cx + 15 || lz < cz || lz > cz + 15) continue;   // stay inside this chunk
            if ((dx == 0 && dz == 0 && dy < h + 1) || Math.abs(dx) + Math.abs(dz) > r + 1) continue;
            McCompat.set(c, p.set(lx, base + dy, lz), leaves);
        }
    }

    @Override public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        TileData t = tileFor(x, z);
        int i = t.idx(x - t.key.minX(), z - t.key.minZ());
        return Math.max(t.terrainY[i], t.structureTopY[i]) + 1;
    }

    @Override public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        int h = getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, random);
        BlockState[] states = new BlockState[HEIGHT];
        for (int i = 0; i < HEIGHT; i++) states[i] = MIN_Y + i < h ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        return new NoiseColumn(MIN_Y, states);
    }

    @Override public void buildSurface(WorldGenRegion region, StructureManager structures, RandomState random, ChunkAccess chunk) {}
    @Override public void applyCarvers(WorldGenRegion region, long seed, RandomState random, BiomeManager biomes, StructureManager structures, ChunkAccess chunk) {}
    @Override public void spawnOriginalMobs(WorldGenRegion region) {}
    @Override public int getGenDepth() { return HEIGHT; }
    @Override public int getSeaLevel() { return SEA; }
    @Override public int getMinY() { return MIN_Y; }
    @Override public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
        var s = EarthLifeServices.get();
        var g = s.projection.fromBlock(pos.getX() + 0.5, pos.getZ() + 0.5);
        info.add(String.format("EarthLife: %.6f, %.6f  elev %.0f m  tiles %d", g.lat(), g.lon(), s.projection.yToElevation(pos.getY()), s.tiles.loadedCount()));
        TileData t = tileFor(pos.getX(), pos.getZ());
        boolean est = t.has(pos.getX() - t.key.minX(), pos.getZ() - t.key.minZ(), TileData.FLAG_ESTIMATED);
        info.add("EarthLife data: " + (est ? "ESTIMATED (procedural)" : "VERIFIED (" + s.attribution + ")"));
    }
}
