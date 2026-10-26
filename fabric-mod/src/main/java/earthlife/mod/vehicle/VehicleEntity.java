package earthlife.mod.vehicle;

import earthlife.core.transport.*;
import earthlife.core.world.Features;
import earthlife.mod.model.DisplayRig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
 * Physics come from core {@link VehicleSim}/{@link TrainSim}.
 *
 * V0.4.1: cars, taxis and pickups are drawn by the client with a real entity model ({@code client.car.CarRenderer}):
 * body, glass, lights, interior with a dashboard GPS, wheels that spin and steer, and four hinged doors. You get in
 * through the door nearest to you (the owner always takes the driver's seat if it's free); the door swings open, you
 * sit down and it closes behind you. Sneak to get out - the door opens again and you step out beside it.
 * Other vehicles still use the block-display {@link DisplayRig} (visible without the client mod).
 *
 * Driving: W/S throttle and brake/reverse, A/D steer, Space handbrake. Ground vehicles follow the terrain height and
 * stop if the next position is inside a solid block (no driving through houses). Buses/trains/ferries run on
 * autopilot and passengers pay a fare when they board.
 */
public final class VehicleEntity extends Entity {
    private static final EntityDataAccessor<Integer> DATA_TYPE = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> DATA_COLOR = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.STRING);
    /** Bits 0-3: door open (FL, FR, RL, RR). Bit 4: braking. Bit 5: reversing. Bit 6: engine running. */
    private static final EntityDataAccessor<Byte> DATA_FLAGS = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Float> DATA_SPEED = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_STEER = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.FLOAT);
    public static final int BRAKE = 16, REVERSE = 32, ENGINE = 64;

    private VehicleType type = VehicleType.CAR;
    private VehicleSim sim;
    private TrainSim train;
    private double trainOffset;                 // carriage distance behind the head of the train
    private DisplayRig rig;
    private String owner;                       // player UUID, or null for public/company vehicles
    private String color = "red";
    private long fare;
    private List<Features.Pt> autoRoute;
    private final Map<UUID, Integer> seatOf = new HashMap<>();
    private final long[] doorCloseAt = new long[4];

    // client-side animation state
    private final float[] doorAnim = new float[4], doorAnimO = new float[4];
    private float wheelSpin, wheelSpinO;

    public VehicleEntity(EntityType<? extends VehicleEntity> t, Level level) { super(t, level); noPhysics = true; }

    public VehicleEntity configure(VehicleType type, String color, String owner, double headingDeg, long fare) {
        this.type = type; this.color = color; this.owner = owner; this.fare = fare;
        this.sim = new VehicleSim(type, getX(), getZ(), headingDeg);
        if (!clientModel(type)) this.rig = new DisplayRig(type.model(color));
        entityData.set(DATA_TYPE, type.ordinal());
        entityData.set(DATA_COLOR, color);
        setYRot((float) (headingDeg + 180));
        refreshDimensions();
        return this;
    }

    /** Vehicles drawn with the client-side model instead of block displays. */
    public static boolean clientModel(VehicleType t) { return t == VehicleType.CAR || t == VehicleType.TAXI || t == VehicleType.PICKUP; }

    public VehicleEntity onTrain(TrainSim t, double offset) { this.train = t; this.trainOffset = offset; return this; }
    public void setAutoRoute(List<Features.Pt> route) { this.autoRoute = route; sim.follow(route); }
    public VehicleType vehicleType() { return level().isClientSide() ? VehicleType.values()[Math.floorMod(entityData.get(DATA_TYPE), VehicleType.values().length)] : type; }
    public String color() { return entityData.get(DATA_COLOR); }
    public String owner() { return owner; }
    public VehicleSim sim() { return sim; }
    public int flags() { return entityData.get(DATA_FLAGS); }
    public float speed() { return entityData.get(DATA_SPEED); }
    public float steer() { return entityData.get(DATA_STEER); }
    public float doorAnim(int i, float partial) { return doorAnimO[i] + (doorAnim[i] - doorAnimO[i]) * partial; }
    public float wheelSpin(float partial) { return wheelSpinO + (wheelSpin - wheelSpinO) * partial; }
    /** Seat index (0 driver, 1 front passenger, 2 rear left, 3 rear right) of a rider, or -1. */
    public int seatOf(Entity e) { return seatOf.getOrDefault(e.getUUID(), -1); }

    @Override protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(DATA_TYPE, VehicleType.CAR.ordinal()).define(DATA_COLOR, "red").define(DATA_FLAGS, (byte) 0).define(DATA_SPEED, 0f).define(DATA_STEER, 0f);
    }

    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_TYPE.equals(key)) refreshDimensions();
    }

    @Override public EntityDimensions getDimensions(Pose pose) {
        VehicleType t = entityData == null ? VehicleType.CAR : vehicleType();
        if (!clientModel(t)) return super.getDimensions(pose);
        return EntityDimensions.scalable((float) Math.max(2.0, (t.length + t.width) / 2 * 0.8), (float) t.height);
    }

    @Override public boolean isPickable() { return !isRemoved(); }
    @Override public boolean canBeCollidedWith(Entity other) { return false; }

    private void setFlag(int bit, boolean on) {
        int f = entityData.get(DATA_FLAGS);
        int n = on ? f | bit : f & ~bit;
        if (n != f) entityData.set(DATA_FLAGS, (byte) n);
    }

    private void openDoor(int seat, int ticks) {
        if (!clientModel(type) || seat < 0 || seat > 3) return;
        if ((entityData.get(DATA_FLAGS) & (1 << seat)) == 0)
            level().playSound(null, getX(), getY() + 1, getZ(), SoundEvents.IRON_DOOR_OPEN, SoundSource.NEUTRAL, 0.5f, 1.4f);
        setFlag(1 << seat, true);
        doorCloseAt[seat] = level().getGameTime() + ticks;
    }

    @Override public InteractionResult interact(Player player, InteractionHand hand, Vec3 at) {
        if (!(player instanceof ServerPlayer sp) || sim == null) return level().isClientSide() ? InteractionResult.SUCCESS : InteractionResult.PASS;
        if (getPassengers().contains(sp)) return InteractionResult.PASS;
        int seat = chooseSeat(sp);
        if (seat < 0) { sp.sendOverlayMessage(Component.literal("Full.")); return InteractionResult.FAIL; }
        if (fare > 0 && !Objects.equals(owner, sp.getStringUUID())) {
            var s = earthlife.mod.EarthLifeServices.get();
            try { s.ledger.debit(sp.getStringUUID(), fare, "FARE", "fare#" + getUUID() + "#" + sp.getStringUUID() + "#" + level().getGameTime() / 1200, type.label + " fare", level().getGameTime()); }
            catch (earthlife.core.economy.Ledger.InsufficientFunds e) { sp.sendSystemMessage(Component.literal("Fare is " + fare + " LC. " + e.getMessage())); return InteractionResult.FAIL; }
            catch (Exception e) { /* duplicate fare within the same minute: already paid */ }
        }
        seatOf.put(sp.getUUID(), seat);
        openDoor(seat, 22);
        earthlife.mod.McCompat.ride(sp, this);
        if (seat == 0 && clientModel(type)) sp.sendOverlayMessage(Component.literal("W/S drive · A/D steer · Space brake · Sneak to get out · GPS: set a route on the map [M]"));
        return InteractionResult.SUCCESS;
    }

    /** Nearest free seat to where the player stands (owner: driver's seat when free). */
    int chooseSeat(ServerPlayer p) {
        int seats = Math.min(type.seats, clientModel(type) ? 4 : type.seats);
        Set<Integer> taken = new HashSet<>(seatOf.values());
        if (taken.size() >= seats) return -1;
        if (!clientModel(type)) { for (int i = 0; i < seats; i++) if (!taken.contains(i)) return i; return -1; }
        if (Objects.equals(owner, p.getStringUUID()) && !taken.contains(0)) return 0;
        double yaw = Math.toRadians(getYRot());
        double dx = p.getX() - getX(), dz = p.getZ() - getZ();
        double fwd = -dx * Math.sin(yaw) + dz * Math.cos(yaw);          // metres ahead of the centre
        double right = -dx * Math.cos(yaw) - dz * Math.sin(yaw);        // metres to the right
        int side = right > 0 ? 1 : 0;
        int row = fwd > CarGeometry.bPillarM(type) ? 0 : 1;
        int want = row * 2 + side;
        if (want == 0 && owner != null && !Objects.equals(owner, p.getStringUUID())) want = 1;   // only the owner drives
        int best = -1; double bd = Double.MAX_VALUE;
        for (int i = 0; i < seats; i++) {
            if (taken.contains(i) || (i == 0 && owner != null && !Objects.equals(owner, p.getStringUUID()))) continue;
            double d = (i % 2 != want % 2 ? 2 : 0) + Math.abs(i / 2 - want / 2);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    @Override protected boolean canAddPassenger(Entity e) { return getPassengers().size() < type.seats; }

    @Override protected void removePassenger(Entity p) {
        super.removePassenger(p);
        Integer seat = seatOf.remove(p.getUUID());
        if (seat != null && !level().isClientSide()) openDoor(seat, 20);
    }

    /** Step out beside your door (or beside the vehicle). */
    @Override public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        int seat = seatOf.getOrDefault(passenger.getUUID(), -1);
        if (!clientModel(type) || seat < 0) return super.getDismountLocationForPassenger(passenger);
        double yaw = Math.toRadians(getYRot());
        double side = (seat % 2 == 0 ? -1 : 1) * (type.width / 2 + 0.7);      // left = negative "right"
        double fwd = seat < 2 ? CarGeometry.frontSeatM(type) : CarGeometry.rearSeatM(type);
        double x = getX() - fwd * Math.sin(yaw) - side * Math.cos(yaw), z = getZ() + fwd * Math.cos(yaw) - side * Math.sin(yaw);
        double y = level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
        return new Vec3(x, Math.max(y, getY()), z);
    }

    /** Seat 0 is the driver when the player owns the vehicle (or drives it for work). */
    private ServerPlayer driver() {
        if (autoRoute != null || train != null) return null;
        for (Entity e : getPassengers()) if (e instanceof ServerPlayer sp && seatOf.getOrDefault(sp.getUUID(), -1) == 0)
            return owner == null || owner.equals(sp.getStringUUID()) ? sp : null;
        return null;
    }

    @Override protected void positionRider(Entity passenger, MoveFunction move) {
        int i = Math.max(0, seatOf.getOrDefault(passenger.getUUID(), 0));
        VehicleType t = vehicleType();
        double[] s;
        double seatY;
        if (clientModel(t)) {
            s = new double[]{(i % 2 == 0 ? -1 : 1) * CarGeometry.seatXM(t), i < 2 ? CarGeometry.frontSeatM(t) : CarGeometry.rearSeatM(t)};
            seatY = CarGeometry.hipM(t) - 0.70;             // the player's hips sit on the cushion
        } else {
            var seats = t.seatPositions();
            s = seats.get(Math.min(i, seats.size() - 1));
            seatY = (t == VehicleType.TRAIN ? 1.05 : t == VehicleType.FERRY || t == VehicleType.BOAT ? 1.35 : t == VehicleType.BICYCLE ? 0.85 : 0.7) - 0.6;
        }
        double yaw = Math.toRadians(getYRot());
        move.accept(passenger, getX() - s[1] * Math.sin(yaw) - s[0] * Math.cos(yaw), getY() + seatY, getZ() + s[1] * Math.cos(yaw) - s[0] * Math.sin(yaw));
    }

    @Override public void tick() {
        super.tick();
        if (level().isClientSide()) { clientTick(); return; }
        if (!(level() instanceof ServerLevel server) || sim == null) return;
        // a rider whose seat we don't know (e.g. after a reload) takes the first free seat
        for (Entity e : getPassengers()) if (!seatOf.containsKey(e.getUUID())) {
            Set<Integer> taken = new HashSet<>(seatOf.values());
            for (int i = 0; i < type.seats; i++) if (!taken.contains(i)) { seatOf.put(e.getUUID(), i); break; }
        }
        long now = level().getGameTime();
        for (int d = 0; d < 4; d++) if ((entityData.get(DATA_FLAGS) & (1 << d)) != 0 && now >= doorCloseAt[d]) {
            setFlag(1 << d, false);
            level().playSound(null, getX(), getY() + 1, getZ(), SoundEvents.IRON_DOOR_CLOSE, SoundSource.NEUTRAL, 0.6f, 1.5f);
        }
        double px = sim.x, pz = sim.z, ph = sim.headingDeg, pv = sim.speed;
        double steer = 0;
        boolean braking = false, reversing = false, engine = false;
        if (train != null) {
            double[] pose = train.poseAt(trainOffset);
            sim.x = pose[0]; sim.z = pose[1]; sim.headingDeg = pose[2]; sim.speed = train.speed();
        } else if (autoRoute != null) {
            sim.autopilot(type == VehicleType.BUS ? 13 : 11, 0.05);
            engine = true;
            if (sim.arrived()) TransitManager.onArrived(this);
        } else {
            ServerPlayer d = driver();
            if (d != null) {
                var in = d.getLastClientInput();
                double throttle = (in.forward() ? 1 : 0) - (in.backward() ? 1 : 0);
                steer = (in.right() ? 1 : 0) - (in.left() ? 1 : 0);
                sim.drive(throttle, steer, in.jump(), 0.05);
                braking = in.jump() || (throttle < 0 && sim.speed > 0.3) || (throttle > 0 && sim.speed < -0.3);
                reversing = sim.speed < -0.2;
                engine = true;
                if (tickCount % 10 == 0 && !clientModel(type)) d.sendOverlayMessage(Component.literal(String.format("%s · %d mph", type.label, Math.round(Math.abs(sim.speed) * 2.237))));
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
        entityData.set(DATA_SPEED, (float) sim.speed);
        entityData.set(DATA_STEER, (float) steer);
        setFlag(BRAKE, braking); setFlag(REVERSE, reversing); setFlag(ENGINE, engine);
        if (rig != null) {
            rig.setTagScale("headlight", level().isDarkOutside() ? 1f : 0.6f);
            rig.pose(server, getX(), getY(), getZ(), getYRot(), 0);
        }
    }

    /** Client: doors swing towards their synced open/closed state; wheels roll with the synced speed. */
    private void clientTick() {
        int f = entityData.get(DATA_FLAGS);
        for (int d = 0; d < 4; d++) {
            doorAnimO[d] = doorAnim[d];
            float target = (f & (1 << d)) != 0 ? 1f : 0f;
            float step = target > doorAnim[d] ? 0.16f : 0.2f;
            doorAnim[d] = target > doorAnim[d] ? Math.min(target, doorAnim[d] + step) : Math.max(target, doorAnim[d] - step);
        }
        wheelSpinO = wheelSpin;
        wheelSpin += (float) (entityData.get(DATA_SPEED) * 0.05 / CarGeometry.wheelRadiusM(vehicleType()));
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
    @Override protected void readAdditionalSaveData(ValueInput in) {}
    @Override protected void addAdditionalSaveData(ValueOutput out) {}
    @Override public boolean shouldBeSaved() { return false; }        // owned vehicles are re-spawned from the garage list
}
