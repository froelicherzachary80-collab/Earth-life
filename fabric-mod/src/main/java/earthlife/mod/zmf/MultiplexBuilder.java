package earthlife.mod.zmf;

import earthlife.core.build.Blueprint;
import earthlife.core.cinema.MultiplexPlanner;
import earthlife.mod.McCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

/**
 * Builds a planned multiplex into the world a slice at a time (a block budget per tick), column by column:
 * clear the column above the floor, fill any gap under the foundation down to the ground, set the blueprint blocks.
 * Columns just outside the building are graded to the floor level so the building sits on a level pad instead of
 * being buried in a hillside or perched on a cliff. Chunks are visited in order, so each one is finished before the
 * next is touched.
 */
final class MultiplexBuilder {
    final MultiplexService svc;
    final MultiplexRecord rec;
    final MultiplexPlanner.Plan plan;
    final Blueprint.Placed placed;
    final ServerLevel level;
    final List<int[]> columns = new ArrayList<>();
    int next;
    long blocks, filled, cleared;
    final long started = System.currentTimeMillis();
    static final int MARGIN = 4;
    final BlockState air = Blocks.AIR.defaultBlockState();

    MultiplexBuilder(MultiplexService svc, MultiplexRecord rec, MultiplexPlanner.Plan plan, Blueprint.Placed placed) {
        this.svc = svc; this.rec = rec; this.plan = plan; this.placed = placed; this.level = svc.level;
        // floor height from the real terrain now that the chunks exist (median of the footprint)
        int[] b = placed.worldBounds();
        List<Integer> hs = new ArrayList<>();
        for (int i = 0; i <= 6; i++) for (int j = 0; j <= 6; j++) {
            double[] w = placed.toWorld(-8 + (plan.lengthU() + 8) * i / 6.0, -plan.halfWidthV() + 2.0 * plan.halfWidthV() * j / 6.0);
            int x = (int) Math.floor(w[0]), z = (int) Math.floor(w[1]);
            level.getChunk(BlockPos.containing(x, 64, z));
            hs.add(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1);
        }
        Collections.sort(hs);
        int base = hs.get(hs.size() / 2);
        base = Math.max(level.getSeaLevel(), Math.min(level.getMaxY() - plan.maxHeight() - 4, base));
        rec.y = base;
        // columns ordered chunk by chunk
        Map<Long, List<int[]>> byChunk = new TreeMap<>();
        for (int x = b[0] - MARGIN; x <= b[2] + MARGIN; x++) for (int z = b[1] - MARGIN; z <= b[3] + MARGIN; z++)
            byChunk.computeIfAbsent(((long) Math.floorDiv(x, 16) << 32) ^ (Math.floorDiv(z, 16) & 0xffffffffL), k -> new ArrayList<>()).add(new int[]{x, z});
        byChunk.values().forEach(columns::addAll);
    }

    /** Placed blueprint with the final floor height. */
    Blueprint.Placed placedAtBase() { return plan.bp().place(rec.x + 0.5, rec.z + 0.5, rec.y, rec.heading); }

    private Blueprint.Placed atBase;

    /** Does up to `budget` block changes; true when the whole building is done. */
    boolean step(int budget) {
        if (atBase == null) atBase = placedAtBase();
        int top = rec.y + plan.maxHeight() + 3;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (budget > 0 && next < columns.size()) {
            int[] c = columns.get(next++);
            int x = c[0], z = c[1];
            if (!level.hasChunk(Math.floorDiv(x, 16), Math.floorDiv(z, 16))) level.getChunk(BlockPos.containing(x, rec.y, z));
            TreeMap<Integer, String> col = new TreeMap<>();
            atBase.forEachIn(x, z, x, z, blk -> col.put(blk.y(), blk.id()));
            if (col.isEmpty()) { budget -= grade(pos, x, z); continue; }
            // clear above the floor where the blueprint has nothing (trees, hills)
            for (int y = rec.y + 1; y <= top; y++) {
                if (col.containsKey(y)) continue;
                pos.set(x, y, z);
                if (!level.getBlockState(pos).isAir()) { level.setBlock(pos, air, 2); cleared++; budget--; }
            }
            // foundation: fill air/water/plants under the floor down to solid ground
            int lowest = col.firstKey();
            for (int y = lowest - 1; y > lowest - 48 && y > level.getMinY(); y--) {
                pos.set(x, y, z);
                BlockState here = level.getBlockState(pos);
                if (!replaceable(here)) break;
                level.setBlock(pos, (y > lowest - 3 ? Blocks.DIRT : Blocks.STONE).defaultBlockState(), 2); filled++; budget--;
            }
            for (var e : col.entrySet()) {
                pos.set(x, e.getKey(), z);
                BlockState st = oriented(e.getValue());
                if (level.getBlockState(pos) != st) { level.setBlock(pos, st, 2); blocks++; }
                budget--;
            }
        }
        return next >= columns.size();
    }

    /** Levels a column next to the building to the floor height (cut hills, fill dips; grass on top). */
    int grade(BlockPos.MutableBlockPos pos, int x, int z) {
        int work = 0;
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        if (ground > rec.y) {
            for (int y = rec.y + 1; y <= Math.min(ground + 6, rec.y + plan.maxHeight() + 3); y++) {
                pos.set(x, y, z);
                if (!level.getBlockState(pos).isAir()) { level.setBlock(pos, air, 2); work++; }
            }
            pos.set(x, rec.y, z); level.setBlock(pos, Blocks.GRASS_BLOCK.defaultBlockState(), 2); work++;
        } else if (ground < rec.y) {
            for (int y = ground + 1; y <= rec.y; y++) {
                pos.set(x, y, z);
                if (replaceable(level.getBlockState(pos))) { level.setBlock(pos, (y == rec.y ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState(), 2); work++; }
            }
        }
        return Math.max(1, work);
    }

    static boolean replaceable(BlockState s) {
        return s.isAir() || s.liquid() || s.canBeReplaced() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.is(BlockTags.FLOWERS);
    }

    /** Blueprint ids may carry a facing in the blueprint's own frame: "earthlife:recliner@v-" faces local -v. */
    BlockState oriented(String id) {
        int at = id.indexOf('@');
        if (at < 0) return McCompat.block(id);
        BlockState st = McCompat.block(id.substring(0, at));
        String d = id.substring(at + 1);
        double dx = d.startsWith("u") ? atBase.ux : atBase.vx, dz = d.startsWith("u") ? atBase.uz : atBase.vz;
        if (d.endsWith("-")) { dx = -dx; dz = -dz; }
        Direction dir = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        var facing = HorizontalDirectionalBlock.FACING;
        return st.hasProperty(facing) ? st.setValue(facing, dir) : st;
    }

    String report() {
        return String.format(Locale.ROOT, "%d blocks set, %d cleared, %d foundation blocks, %d columns in %.1f s",
                blocks, cleared, filled, columns.size(), (System.currentTimeMillis() - started) / 1000.0);
    }
}
