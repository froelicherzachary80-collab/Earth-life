package earthlife.mod.cinema;

import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/** Invisible seat a player rides while sitting in a recliner or motion seat; removed when they stand up. */
public final class SeatEntity extends Entity {
    private BlockPos seat;

    public SeatEntity(EntityType<? extends SeatEntity> t, Level level) { super(t, level); noPhysics = true; }

    void at(BlockPos p) { this.seat = p; }
    public BlockPos seatPos() { return seat != null ? seat : blockPosition(); }

    /** True if this is a 4D motion seat. */
    public boolean motion() { return level().getBlockState(seatPos()).getBlock() instanceof SeatBlock b && b.motion; }

    @Override protected void positionRider(Entity passenger, MoveFunction move) {
        // hips on the cushion (7/16 high), leaning back a little
        double yaw = Math.toRadians(getYRot());
        move.accept(passenger, getX() + Math.sin(yaw) * 0.12, getY() + 0.44 - 0.70, getZ() - Math.cos(yaw) * 0.12);
    }

    /** Sitting down: you face the way the seat faces (the screen). */
    @Override protected void addPassenger(Entity e) {
        super.addPassenger(e);
        e.setYRot(getYRot()); e.yRotO = getYRot(); e.setXRot(5);
        if (e instanceof LivingEntity le) { le.setYHeadRot(getYRot()); le.yBodyRot = getYRot(); }
    }

    /** You can look around, but your body stays in the seat (like a boat: within 100 degrees of the seat). */
    @Override public void onPassengerTurned(Entity e) {
        float f = net.minecraft.util.Mth.wrapDegrees(e.getYRot() - getYRot());
        float g = net.minecraft.util.Mth.clamp(f, -100f, 100f);
        e.yRotO += g - f;
        e.setYRot(e.getYRot() + g - f);
        e.setYHeadRot(e.getYRot());
    }

    @Override public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel sl)) return;
        BlockState st = sl.getBlockState(seatPos());
        if (getPassengers().isEmpty() || !(st.getBlock() instanceof SeatBlock)) {
            if (st.getBlock() instanceof SeatBlock && st.getValue(SeatBlock.RECLINED)) sl.setBlockAndUpdate(seatPos(), st.setValue(SeatBlock.RECLINED, false));
            discard();
        }
    }

    @Override public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        double yaw = Math.toRadians(getYRot());
        return new Vec3(getX() - Math.sin(yaw) * 1.0, getY() + 0.05, getZ() + Math.cos(yaw) * 1.0);
    }

    @Override protected void defineSynchedData(SynchedEntityData.Builder b) {}
    @Override public boolean hurtServer(ServerLevel level, DamageSource s, float a) { return false; }
    @Override protected void readAdditionalSaveData(ValueInput in) {}
    @Override protected void addAdditionalSaveData(ValueOutput out) {}
    @Override public boolean shouldBeSaved() { return false; }
}
