package earthlife.mod.cinema;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A cinema recliner or 4D motion seat. Right-click to sit (you face the way the seat faces); the back reclines and
 * the footrest comes out while someone sits in it. Sneak to stand up. Motion seats drive the 4D effects.
 */
public final class SeatBlock extends Block {
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    public static final BooleanProperty RECLINED = BooleanProperty.create("reclined");
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 10, 16);
    public final boolean motion;

    public SeatBlock(BlockBehaviour.Properties p, boolean motion) {
        super(p);
        this.motion = motion;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(RECLINED, false));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING, RECLINED); }

    @Override public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override protected VoxelShape getShape(BlockState s, BlockGetter level, BlockPos pos, CollisionContext ctx) { return SHAPE; }
    @Override protected BlockState rotate(BlockState s, Rotation r) { return s.setValue(FACING, r.rotate(s.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState s, Mirror m) { return s.rotate(m.getRotation(s.getValue(FACING))); }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level instanceof ServerLevel sl) || !(player instanceof ServerPlayer sp) || player.isPassenger()) return InteractionResult.PASS;
        return sit(sl, pos, state, sp) ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    /** Seats a player (also used by the cinema ticket flow and tests). */
    public static boolean sit(ServerLevel level, BlockPos pos, BlockState state, ServerPlayer sp) {
        if (!(state.getBlock() instanceof SeatBlock)) return false;
        if (!level.getEntitiesOfClass(SeatEntity.class, new net.minecraft.world.phys.AABB(pos)).isEmpty()) {
            sp.sendOverlayMessage(net.minecraft.network.chat.Component.literal("Someone is sitting there."));
            return false;
        }
        SeatEntity seat = CinemaContent.SEAT.create(level, EntitySpawnReason.TRIGGERED);
        if (seat == null) return false;
        Direction f = state.getValue(FACING);
        seat.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        seat.setYRot(f.toYRot());
        seat.at(pos);
        level.addFreshEntity(seat);
        sp.startRiding(seat, true, true);
        sp.setYRot(f.toYRot());
        sp.setYHeadRot(f.toYRot());
        level.setBlockAndUpdate(pos, state.setValue(RECLINED, true));
        return true;
    }
}
