package earthlife.mod.world;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import earthlife.core.world.BuildingStyle;
import earthlife.core.world.TileData;
import earthlife.core.world.TileData.Surface;
import earthlife.core.world.TileKey;
import earthlife.mod.EarthLifeServices;
import earthlife.mod.McCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Overworld generator for Earth worlds. Each chunk reads its 16x16 columns from the core TileStreamer (tiles are
 * rasterised from OpenStreetMap + elevation on worker threads and cached on disk), then Minecraft's own chunk
 * pipeline saves the result in the region files. A chunk is generated exactly once: anything a player builds or
 * breaks afterwards lives in the saved chunk and is never overwritten by map data, even if the data changes.
 *
 * Vertical datum: Y 63 (vanilla sea level) = 74 m above sea level, the surface of Lake Ontario, so the lake sits at
 * vanilla sea level and Western NY terrain (75-300 m) fits comfortably; see {@link EarthLifeServices#SEA_LEVEL_Y_AT_0M}.
 * Vanilla structures and biome decoration are disabled (they would land on roads and houses); ores are placed here.
 */
public final class VoxelEarthChunkGenerator extends ChunkGenerator {
    public static final MapCodec<VoxelEarthChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
            EarthSettings.CODEC.optionalFieldOf("earth", EarthSettings.DEFAULT).forGetter(g -> g.settings)
    ).apply(i, VoxelEarthChunkGenerator::new));

    public static final int SEA = 63;
    private final EarthSettings settings;

    public VoxelEarthChunkGenerator(BiomeSource biomes, EarthSettings settings) {
        super(biomes);
        this.settings = settings;
    }

    public EarthSettings settings() { return settings; }

    public VoxelEarthChunkGenerator withSettings(EarthSettings s) { return new VoxelEarthChunkGenerator(biomeSource, s); }

    @Override protected MapCodec<? extends ChunkGenerator> codec() { return CODEC; }

    private static TileData tileFor(int blockX, int blockZ) {
        if (!EarthLifeServices.running()) return null;
        var s = EarthLifeServices.get();
        try { return s.tiles.request(TileKey.containing(s.region, blockX, blockZ, 0)).join(); }
        catch (Exception e) { earthlife.mod.EarthLifeMod.LOG.error("EarthLife tile failed at {},{}", blockX, blockZ, e); return null; }
    }

    @Override
    public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender, RandomState random, StructureManager structures,
                                                       BiomeManager biomes, WorldGenRegion region, Set<Holder<Biome>> biomeSet) {
        ChunkPos cp = chunk.getPos();
        int cx = cp.getMinBlockX(), cz = cp.getMinBlockZ();
        int minY = chunk.getMinY(), maxY = minY + chunk.getHeight() - 1;
        TileData t = tileFor(cx, cz);
        var pos = new BlockPos.MutableBlockPos();
        boolean lowQ = settings.qualityEnum() == EarthSettings.Quality.LOW;
        for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
            int x = cx + dx, z = cz + dz;
            if (t == null) { fallbackColumn(chunk, pos, x, z, minY); continue; }
            int sx = x - t.key.minX(), sz = z - t.key.minZ();
            int i = t.idx(sx, sz);
            int top = Math.max(minY + 2, Math.min(maxY - 40, t.terrainY[i]));
            Surface surf = t.surfaceAt(sx, sz);
            boolean road = t.has(sx, sz, TileData.FLAG_ROAD);
            if (!settings.roads() && road && surf != Surface.RUNWAY && surf != Surface.TAXIWAY) surf = Surface.GRASS;
            for (int y = minY; y <= top; y++) {
                BlockState st = y == minY ? Blocks.BEDROCK.defaultBlockState()
                        : y < top - 4 ? (y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState())
                        : y < top ? (surf == Surface.SAND ? Blocks.SAND.defaultBlockState() : Blocks.DIRT.defaultBlockState()) : surfaceBlock(surf);
                McCompat.set(chunk, pos.set(x, y, z), st);
            }
            // Water: mapped lakes/rivers are carved basins filled 2 deep; anything below the lake datum floods to sea level.
            if (t.has(sx, sz, TileData.FLAG_WATER))
                for (int y = top + 1; y <= top + 2; y++) McCompat.set(chunk, pos.set(x, y, z), Blocks.WATER.defaultBlockState());
            if (top < SEA - 1)
                for (int y = top + 1; y < SEA; y++) McCompat.set(chunk, pos.set(x, y, z), Blocks.WATER.defaultBlockState());
            if (surf == Surface.FARMLAND && !road && Math.floorMod(x, 4) != 0)
                McCompat.set(chunk, pos.set(x, top + 1, z), Blocks.WHEAT.defaultBlockState());
            if (settings.buildings() && t.structureTopY[i] > 0) building(chunk, pos, x, z, top, t, sx, sz, maxY, lowQ);
            if (!lowQ && t.has(sx, sz, TileData.FLAG_TREE)) tree(chunk, pos, x, z, top, cx, cz, surf == Surface.ORCHARD);
        }
        if (t != null && settings.buildings()) placeBlueprints(chunk, pos, cx, cz, minY, maxY);
        ores(chunk, pos, cp, minY);
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG));
        return CompletableFuture.completedFuture(chunk);
    }

    /** Used only if Earth services failed to start: gentle grass plain so the world still loads (logged as an error). */
    private static void fallbackColumn(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int z, int minY) {
        for (int y = minY; y <= 64; y++)
            McCompat.set(chunk, pos.set(x, y, z), y == minY ? Blocks.BEDROCK.defaultBlockState() : y < 61 ? Blocks.STONE.defaultBlockState()
                    : y < 64 ? Blocks.DIRT.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState());
    }

    /** Deterministic ore veins by depth (vanilla decoration is off in Earth worlds, so survival still has ores). */
    private static void ores(ChunkAccess chunk, BlockPos.MutableBlockPos pos, ChunkPos cp, int minY) {
        long seed = cp.x() * 341873128712L + cp.z() * 132897987541L;
        java.util.Random r = new java.util.Random(seed);
        Object[][] table = {   // block id, veins per chunk, min y, max y, vein size
                {"minecraft:coal_ore", 14, 0, 110, 8}, {"minecraft:iron_ore", 10, -24, 70, 6}, {"minecraft:copper_ore", 7, 0, 90, 7},
                {"minecraft:gold_ore", 3, -60, 30, 5}, {"minecraft:redstone_ore", 5, -60, 10, 5}, {"minecraft:lapis_ore", 2, -50, 30, 4},
                {"minecraft:diamond_ore", 1, -60, 14, 4}, {"minecraft:emerald_ore", 1, -16, 60, 1}};
        for (Object[] o : table) {
            int veins = (Integer) o[1], lo = Math.max(minY + 1, (Integer) o[2]), hi = (Integer) o[3], size = (Integer) o[4];
            if (hi <= lo) continue;
            BlockState ore = McCompat.block((String) o[0]);
            BlockState deep = McCompat.block(((String) o[0]).replace("minecraft:", "minecraft:deepslate_"));
            for (int v = 0; v < veins; v++) {
                int x = cp.getMinBlockX() + r.nextInt(16), z = cp.getMinBlockZ() + r.nextInt(16), y = lo + r.nextInt(hi - lo);
                for (int k = 0; k < size; k++) {
                    int px = Math.min(cp.getMaxBlockX(), Math.max(cp.getMinBlockX(), x + r.nextInt(3) - 1));
                    int pz = Math.min(cp.getMaxBlockZ(), Math.max(cp.getMinBlockZ(), z + r.nextInt(3) - 1));
                    int py = y + r.nextInt(3) - 1;
                    BlockState here = chunk.getBlockState(pos.set(px, py, pz));
                    if (here.is(Blocks.STONE)) McCompat.set(chunk, pos, ore);
                    else if (here.is(Blocks.DEEPSLATE)) McCompat.set(chunk, pos, deep);
                }
            }
        }
    }

    /** Airport terminals, towers, jet bridges and landmarks: generated blueprints written straight into the chunk. */
    private static void placeBlueprints(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int cx, int cz, int minY, int maxY) {
        for (var placed : EarthLifeServices.get().structures()) {
            int[] b = placed.worldBounds();
            if (b[2] < cx || b[0] > cx + 15 || b[3] < cz || b[1] > cz + 15) continue;
            placed.forEachIn(cx, cz, cx + 15, cz + 15, blk -> {
                if (blk.y() <= minY || blk.y() > maxY) return;
                McCompat.set(chunk, pos.set(blk.x(), blk.y(), blk.z()), McCompat.block(blk.id()));
            });
        }
    }

    static BlockState surfaceBlock(Surface s) {
        return switch (s) {
            case GRASS, ORCHARD, FOREST -> Blocks.GRASS_BLOCK.defaultBlockState();
            case FARMLAND -> Blocks.FARMLAND.defaultBlockState();
            case SAND -> Blocks.SAND.defaultBlockState();
            case WATER -> Blocks.GRAVEL.defaultBlockState();
            case ASPHALT -> McCompat.block("minecraft:gray_concrete");
            case ROAD_MARKING -> McCompat.block("minecraft:yellow_concrete");
            case GRAVEL_PATH -> Blocks.DIRT_PATH.defaultBlockState();
            case RUNWAY -> McCompat.block("minecraft:black_concrete");
            case TAXIWAY -> McCompat.block("minecraft:gray_concrete_powder");
            case CONCRETE -> McCompat.block("minecraft:light_gray_concrete");
            case DIRT -> Blocks.COARSE_DIRT.defaultBlockState();
            case WETLAND -> Blocks.MUD.defaultBlockState();
            case RAIL_BED -> Blocks.GRAVEL.defaultBlockState();
        };
    }

    static BlockState wallBlock(BuildingStyle.Wall w) {
        return McCompat.block(switch (w) {
            case WHITE_SIDING -> "minecraft:white_terracotta";
            case CREAM_SIDING -> "minecraft:birch_planks";
            case GREY_SIDING -> "minecraft:light_gray_terracotta";
            case BLUE_SIDING -> "minecraft:light_blue_terracotta";
            case YELLOW_SIDING -> "minecraft:yellow_terracotta";
            case GREEN_SIDING -> "minecraft:green_terracotta";
            case RED_BRICK -> "minecraft:bricks";
            case BROWN_BRICK -> "minecraft:mud_bricks";
            case STONE -> "minecraft:stone_bricks";
            case CONCRETE -> "minecraft:light_gray_concrete";
            case GLASS -> "minecraft:light_blue_stained_glass";
            case METAL -> "minecraft:iron_block";
            case RED_BARN -> "minecraft:red_terracotta";
            case WOOD -> "minecraft:spruce_planks";
            case PLASTER -> "minecraft:smooth_sandstone";
        });
    }

    static BlockState roofBlock(BuildingStyle.Roof r) {
        return McCompat.block(switch (r) {
            case ASPHALT_DARK -> "minecraft:deepslate_tiles";
            case ASPHALT_GREY -> "minecraft:stone_bricks";
            case ASPHALT_BROWN -> "minecraft:dark_oak_planks";
            case METAL_GREEN -> "minecraft:green_concrete";
            case METAL_RED -> "minecraft:red_nether_bricks";
            case METAL_GREY -> "minecraft:light_gray_concrete";
            case SLATE -> "minecraft:polished_deepslate";
            case COPPER -> "minecraft:oxidized_copper";
            case TAR_FLAT -> "minecraft:smooth_stone";
            case GLASS -> "minecraft:glass";
        });
    }

    /**
     * Building column: walls to the eave with window rows on every floor, hollow walkable floors every 3 blocks,
     * a doorway where the map says the front faces the street, and the roof shape from the map (or an estimate).
     */
    private static void building(ChunkAccess c, BlockPos.MutableBlockPos p, int x, int z, int base, TileData t, int sx, int sz, int maxY, boolean lowQ) {
        int i = t.idx(sx, sz);
        int top = Math.min(maxY - 3, t.structureTopY[i]);
        int eave = Math.min(top, t.eaveY[i] > 0 ? t.eaveY[i] : top - 1);
        boolean wall = t.has(sx, sz, TileData.FLAG_BUILDING_WALL), door = t.has2(sx, sz, TileData.F2_DOOR), chimney = t.has2(sx, sz, TileData.F2_CHIMNEY);
        var wallMat = BuildingStyle.Wall.values()[t.wallMat[i]];
        BlockState wb = wallBlock(wallMat), rb = roofBlock(BuildingStyle.Roof.values()[t.roofMat[i]]);
        boolean glassBuilding = wallMat == BuildingStyle.Wall.GLASS;
        BlockState window = glassBuilding ? McCompat.block("minecraft:light_blue_stained_glass") : Blocks.GLASS_PANE.defaultBlockState();
        BlockState floor = wallMat.name().endsWith("SIDING") || wallMat == BuildingStyle.Wall.WOOD ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.POLISHED_ANDESITE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int y = base; y <= eave; y++) {
            int level = y - base;
            BlockState st;
            if (wall) {
                if (door && (level == 1 || level == 2)) st = air;
                else if (glassBuilding) st = level % 3 == 0 ? McCompat.block("minecraft:gray_concrete") : window;
                else st = (!lowQ && level % 3 == 2 && Math.floorMod(x + z, 3) != 0 && wallMat != BuildingStyle.Wall.RED_BARN && wallMat != BuildingStyle.Wall.METAL) ? window : wb;
            } else st = level % 3 == 0 ? floor : air;
            McCompat.set(c, p.set(x, y, z), st);
        }
        if (door) McCompat.set(c, p.set(x, base, z), Blocks.POLISHED_ANDESITE.defaultBlockState());
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
            if (lx < cx || lx > cx + 15 || lz < cz || lz > cz + 15) continue;
            if ((dx == 0 && dz == 0 && dy < h + 1) || Math.abs(dx) + Math.abs(dz) > r + 1) continue;
            McCompat.set(c, p.set(lx, base + dy, lz), leaves);
        }
    }

    // Vanilla decoration (trees/flowers/lakes) and structures (villages) would collide with mapped roads and houses.
    @Override public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structures) {}
    @Override public void createStructures(RegistryAccess access, ChunkGeneratorStructureState state, StructureManager structures, ChunkAccess chunk,
                                           StructureTemplateManager templates, ResourceKey<Level> dimension) {}
    @Override public void spawnOriginalMobs(WorldGenRegion region) {}

    @Override public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        TileData t = tileFor(x, z);
        if (t == null) return 65;
        int i = t.idx(x - t.key.minX(), z - t.key.minZ());
        int ground = Math.max(t.terrainY[i] + 1, type == Heightmap.Types.OCEAN_FLOOR_WG || type == Heightmap.Types.OCEAN_FLOOR ? 0 : SEA);
        return Math.max(ground, settings.buildings() ? t.structureTopY[i] + 1 : 0);
    }

    @Override public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        int h = getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random);
        BlockState[] states = new BlockState[level.getHeight()];
        for (int i = 0; i < states.length; i++) {
            int y = level.getMinY() + i;
            states[i] = y < h ? Blocks.STONE.defaultBlockState() : y < SEA ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }
        return new NoiseColumn(level.getMinY(), states);
    }

    @Override public int getGenDepth() { return 1024; }
    @Override public int getSeaLevel() { return SEA; }
    @Override public int getMinY() { return -64; }

    @Override public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos, SamplerContext ctx) {
        if (!EarthLifeServices.running()) { info.add("EarthLife: services not running"); return; }
        var s = EarthLifeServices.get();
        var g = s.projection.fromBlock(pos.getX() + 0.5, pos.getZ() + 0.5);
        info.add(String.format("EarthLife: %.6f, %.6f  elev %.0f m  tiles %d", g.lat(), g.lon(), s.projection.yToElevation(pos.getY()), s.tiles.loadedCount()));
        TileData t = tileFor(pos.getX(), pos.getZ());
        if (t == null) return;
        boolean est = t.has(pos.getX() - t.key.minX(), pos.getZ() - t.key.minZ(), TileData.FLAG_ESTIMATED);
        info.add("EarthLife data: " + (est ? "ESTIMATED (procedural)" : "MAPPED (" + s.attribution + ")"));
    }
}
