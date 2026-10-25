package earthlife.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Every Minecraft call whose signature has changed between recent versions is routed through here,
 * so adapting to the exact 26.3 API (or a later version) is a one-file job.
 * UNVERIFIED: written against the 1.21.x-era Mojang-named API; check each method at first compile.
 */
public final class McCompat {
    private McCompat() {}

    /** ChunkAccess.setBlockState: (pos, state, int flags) since 1.21.5; earlier (pos, state, boolean moved). */
    public static void set(ChunkAccess chunk, BlockPos.MutableBlockPos pos, BlockState state) {
        chunk.setBlockState(pos, state, 0);
    }
}
