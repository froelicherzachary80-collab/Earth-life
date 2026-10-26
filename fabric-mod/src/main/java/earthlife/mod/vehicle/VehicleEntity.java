package earthlife.mod.vehicle;

import earthlife.core.transport.*;
import earthlife.core.world.Features;
import earthlife.mod.model.DisplayRig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Every land/water vehicle: bicycle, car, pickup, taxi, bus, delivery van, boat, ferry - and train carriages.
 * Physics come from core {@link VehicleSim}/{@link TrainSim}; the model is a block-display {@link DisplayRig}.
 *
 * Driving: right-click to enter (first seat = driver if you own it or it's your work vehicle). W/S throttle and
 * brake/reverse, A/D steer, Space handbrake, Sneak to leave. Ground vehicles follow the terrain height and stop
 * if the next position is inside a solid block (no driving through houses). Buses/trains/ferries run on autopilot
 * and passengers pay a fare when they board.
 * UNVERIFIED: getLastClientInput, save/hurt signatures (see McCompat notes).
 */
public final class VehicleEntity extends Entity {
    private VehicleType type = VehicleType.CAR;
    private VehicleSim sim;
    private TrainSim train;
    private double trainOffset;                 // carriage distance behind the head of the train
    private DisplayRig rig;
    private String owner;                       // player UUID, or null for public/company vehicles
    private String color = "red";
    private long fare;
    private List<Features.Pt> autoRoute;
    private final List<UUID> seatOrder = new ArrayList<>();

    public VehicleEntity(EntityType<? extends VehicleEntity> t, Level level) { super(t, level); noPhysics = true; }

    public VehicleEntity configure(VehicleType type, String color, String owner, double headingDeg, long fare) {
        this.type = type; this.color = color; this.owner = owner; this.fare = fare;
        this.sim = new VehicleSim(type, getX(), getZ(), headingDeg);
        this.rig = new DisplayRig(type.model(color));
        return this;
    }

    public VehicleEntity onTrain(TrainSim t, double offset) { this.train = t; this.trainOffset = offset; return this; }
    public void setAutoRoute(List<Features.Pt> route) { this.autoRoute = route; sim.follow(route); }
    public VehicleType vehicleType() { return type; }
    public String owner() { return owner; }
    public VehicleSim sim() { return sim; }

    @Override public InteractionResult interact(Player player, InteractionHand hand, net.minecraft.world.phys.Vec3 at) {
        if (!(player instanceof ServerPlayer sp) || sim == null) return InteractionResult.PASS;
        if (getPassengers().size() >= type.seats) { sp.sendOverlayMessage(Component.literal("Full.")); return InteractionResult.FAIL; }
        if (fare > 0 && !Objects.equals(owner, sp.getStringUUID())) {
            var s = earthlife.mod.EarthLifeServices.get();
            try { s.ledger.debit(sp.getStringUUID(), fare, "FARE", "fare#" + getUUID() + "#" + sp.getStringUUID() + "#" + level().getGameTime() / 1200, type.label + " fare", level().getGameTime()); }
            catch (earthlife.core.economy.Ledger.InsufficientFunds e) { sp.sendSystemMessage(Component.literal("Fare is " + fare + " LC. " + e.getMessage())); return InteractionResult.FAIL; }
            catch (Exception e) { /* duplicate fare within the same minute: already paid */ }
        }
        seatOrder.remove(sp.getUUID());
        seatOrder.add(sp.getUUID());
        earthlife.mod.McCompat.ride(sp, this);
        return InteractionResult.SUCCESS;
    }

    @Override protected boolean canAddPassenger(Entity e) { return getPassengers().size() < type.seats; }

    @Override protected void removePassenger(Entity p) { super.removePassenger(p); seatOrder.remove(p.getUUID()); }

    /** Seat 0 is the driver when the player owns the vehicle (or drives it for work). */
    private ServerPlayer driver() {
        if (autoRoute != null || train != null || seatOrder.isEmpty()) return null;
        Entity e = getPassengers().stream().filter(x -> x.getUUID().equals(seatOrder.get(0))).findFirst().orElse(null);
        if (!(e instanceof ServerPlayer sp)) return null;
        boolean allowed = owner == null || owner.equals(sp.getStringUUID());
        return allowed ? sp : null;
    }

    @Override protected void positionRider(Entity passenger, MoveFunction move) {
        int i = Math.max(0, seatOrder.indexOf(passenger.getUUID()));
        var seats = type.seatPositions();
        double[] s = seats.get(Math.min(i, seats.size() - 1));
        double yaw = Math.toRadians(getYRot());
        double seatY = type == VehicleType.TRAIN ? 1.05 : type == VehicleType.FERRY || type == VehicleType.BOAT ? 1.35 : type == VehicleType.BICYCLE ? 0.85 : 0.7;
        move.accept(passenger, getX() - s[1] * Math.sin(yaw) - s[0] * Math.cos(yaw), getY() + seatY - 0.6, getZ() + s[1] * Math.cos(yaw) - s[0] * Math.sin(yaw));
    }

    @Override public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel server) || sim == null) return;
        double px = sim.x, pz = sim.z, ph = sim.headingDeg, pv = sim.speed;
        if (train != null) {
            double[] pose = train.poseAt(trainOffset);
            sim.x = pose[0]; sim.z = pose[1]; sim.headingDeg = pose[2]; sim.speed = train.speed();
        } else if (autoRoute != null) {
            sim.autopilot(type == VehicleType.BUS ? 13 : 11, 0.05);
            if (sim.arrived()) TransitManager.onArrived(this);
        } else {
            ServerPlayer d = driver();
            if (d != null) {
                var in = d.getLastClientInput();
                double throttle = (in.forward() ? 1 : 0) - (in.backward() ? 1 : 0), steer = (in.right() ? 1 : 0) - (in.left() ? 1 : 0);
                sim.drive(throttle, steer, in.jump(), 0.05);
                if (tickCount % 10 == 0) d.sendOverlayMessage(Component.literal(String.format("%s · %d mph", type.label, Math.round(Math.abs(sim.speed) * 2.237))));
            } else sim.drive(0, 0, true, 0.05);
        }
        double y = groundY(server, sim.x, sim.z);
        if (train == null && blocked(server, sim.x, y, sim.z)) {            // crashed into a wall: stop where we were
            sim.x = px; sim.z = pz; sim.headingDeg = ph; sim.speed = 0;
            if (Math.abs(pv) > 8) for (Entity e : getPassengers()) e.hurt(damageSources().generic(), (float) (Math.abs(pv) - 8));
            y = groundY(server, px, pz);
        }
        setPos(sim.x, y, sim.z);
        setYRot((float) (sim.headingDeg + 180));
        rig.setTagScale("headlight", level().isDarkOutside() ? 1f : 0.6f);
        rig.pose(server, getX(), getY(), getZ(), getYRot(), 0);
    }

    private double groundY(ServerLevel l, double x, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (type.terrain.equals("water")) return l.getSeaLevel() - 0.3;    // V0.2: lakes at sea level only (Lake Ontario, Sodus Bay)
        return l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
    }

    private boolean blocked(ServerLevel l, double x, double y, double z) {
        double h = Math.toRadians(sim.headingDeg);
        double fx = x + Math.sin(h) * type.length / 2, fz = z - Math.cos(h) * type.length / 2;
        BlockPos p = BlockPos.containing(fx, y + 0.6, fz);
        if (type.terrain.equals("water")) return !l.getFluidState(BlockPos.containing(fx, l.getSeaLevel() - 1, fz)).isSource();
        if (Math.abs(groundY(l, fx, fz) - y) > 1.2) return true;             // cliff or wall
        return l.getBlockState(p).isCollisionShapeFullBlock(l, p);
    }

    @Override public void remove(RemovalReason r) { if (rig != null) rig.remove(); super.remove(r); }
    @Override public boolean hurtServer(ServerLevel level, DamageSource s, float a) { return false; }
    @Override protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder b) {}
    @Override protected void readAdditionalSaveData(ValueInput in) {}
    @Override protected void addAdditionalSaveData(ValueOutput out) {}
    @Override public boolean shouldBeSaved() { return false; }        // owned vehicles are re-spawned from the garage list
}
