package earthlife.mod.aviation;

import earthlife.core.aviation.*;
import earthlife.core.jobs.PilotChecklist;
import earthlife.mod.EarthLifeServices;
import earthlife.mod.model.DisplayRig;
import earthlife.mod.net.Payloads;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Airliner with a walkable cabin (V0.2).
 *
 * How walking on a moving aircraft works: every passenger is a rider of this entity, but instead of a fixed seat
 * each has a position in the cabin's own frame ({@link CabinLayout}). Their WASD input moves that local position
 * with cabin collision (walls, seats, galley, lavatory), and every tick the local position is re-projected into the
 * world from the aircraft's current pose. The floor therefore can't move out from under anyone: you can walk the
 * aisle at 140 m/s and 1,500 m without falling, and when you sit, you snap into your assigned seat.
 *
 * Controls inside: WASD walk · Jump near your row = sit / stand · Walking out of the open door at the gate =
 * step onto the jet bridge. Sneaking (vanilla dismount) is ignored while the doors are closed.
 * The client receives every walker's local position (CabinState packet) so riders are placed identically there.
 * UNVERIFIED: ServerPlayer#getLastClientInput (1.21.2+) and entity save/hurt signatures.
 */
public final class AirplaneEntity extends Entity {
    enum Mode { WALKING, SEATED }
    static final class Walker { double x, z; Mode mode; String seat; boolean jumpHeld; }

    private FlightSim sim;
    private Booking.Flight flight;
    private CabinLayout cabin;
    private DisplayRig rig;
    private String captain;                                  // player flying as pilot (job), if any
    private final PilotChecklist checklist = new PilotChecklist();
    private final Map<UUID, Walker> walkers = new HashMap<>();
    private final Set<UUID> remount = new HashSet<>();
    private int ticksSinceArrival = -1;

    public AirplaneEntity(EntityType<? extends AirplaneEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    public void assign(Booking.Flight f, FlightSim s, CabinLayout c) { flight = f; sim = s; cabin = c; rig = new DisplayRig(c.parts()); }
    public Booking.Flight flight() { return flight; }
    public FlightSim sim() { return sim; }
    public PilotChecklist checklist() { return checklist; }
    public String captain() { return captain; }
    public boolean doorsOpen() { return sim != null && sim.telemetry().doorsOpen(); }

    // ---- boarding (called by FlightDispatcher when a ticketed player reaches the door at the bridge end) ----
    public boolean board(ServerPlayer p, boolean asCaptain) {
        if (!doorsOpen() || walkers.containsKey(p.getUUID())) return false;
        Walker w = new Walker();
        double[] in = cabin.doorInside();
        w.x = in[0]; w.z = in[1]; w.mode = Mode.WALKING;
        var pass = EarthLifeServices.get().booking.verify(flight.id(), p.getStringUUID());
        w.seat = asCaptain ? null : pass.map(Booking.BoardingPass::seat).orElse(null);
        if (asCaptain) captain = p.getStringUUID();
        walkers.put(p.getUUID(), w);
        earthlife.mod.McCompat.ride(p, this);
        p.sendSystemMessage(Component.literal(asCaptain ? "Captain on board. Work the checklist with /earthlife pilot <item>." :
                "Welcome aboard " + flight.number() + ". Your seat is " + w.seat + ": walk to your row and jump to sit. [M] seatback map."));
        return true;
    }

    @Override protected boolean canAddPassenger(Entity e) { return e instanceof ServerPlayer && getPassengers().size() < cabin.seats().size() + 2; }

    @Override protected void positionRider(Entity passenger, MoveFunction move) {
        double[] local = localOf(passenger);
        if (local == null) { super.positionRider(passenger, move); return; }
        Vec3 world = toWorld(local[0], local[2] > 0 ? 0.25 : 0.0, local[1]);    // seated: sink onto the cushion
        move.accept(passenger, world.x, world.y, world.z);
    }

    /** Riding passengers are normally offset by getMyRidingOffset; we place them exactly, feet on the floor. */
    @Override public Vec3 getPassengerRidingPosition(Entity passenger) {
        double[] local = localOf(passenger);
        return local == null ? super.getPassengerRidingPosition(passenger) : toWorld(local[0], 0, local[1]);
    }

    /** {x, z, seated?1:0} on either side: server from walkers, client from the last CabinState packet. */
    private double[] localOf(Entity passenger) {
        if (level().isClientSide()) return CLIENT_WALKERS.get(passenger.getId());
        Walker w = walkers.get(passenger.getUUID());
        return w == null ? null : new double[]{w.x, w.z, w.mode == Mode.SEATED ? 1 : 0};
    }

    @Override protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (level().isClientSide()) return;
        // Sneaking out is ignored while the doors are closed; at the gate it just leaves the aircraft.
        if (walkers.containsKey(passenger.getUUID())) {
            if (!doorsOpen() && passenger.isAlive() && !passenger.isRemoved()) remount.add(passenger.getUUID());
            else walkers.remove(passenger.getUUID());
        }
    }

    /** Cabin-local (x right, y up, z forward) -> world, using the aircraft's yaw and pitch. */
    Vec3 toWorld(double lx, double ly, double lz) {
        double yaw = Math.toRadians(getYRot()), pitch = Math.toRadians(getXRot());
        double fy = ly * Math.cos(pitch) - lz * Math.sin(pitch);
        double fz = lz * Math.cos(pitch) + ly * Math.sin(pitch);
        // Minecraft: forward for yaw θ is (-sin θ, 0, cos θ); right is (-cos θ, 0, -sin θ).
        return new Vec3(getX() - fz * Math.sin(yaw) - lx * Math.cos(yaw), getY() + fy, getZ() + fz * Math.cos(yaw) - lx * Math.sin(yaw));
    }

    // ---- tick ----
    @Override public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel server)) return;
        if (sim == null) { discard(); return; }                    // stale entity from a previous session
        for (Iterator<UUID> it = remount.iterator(); it.hasNext(); ) {
            if (server.getEntity(it.next()) instanceof ServerPlayer p && p.isAlive()) earthlife.mod.McCompat.ride(p, this);
            it.remove();
        }
        sim.tick(0.05);
        var t = sim.telemetry();
        var s = EarthLifeServices.get();
        var b = s.projection.toBlock(t.position().lat(), t.position().lon());
        // Cabin floor sits 2 blocks above the runway/apron surface block (matches the jet-bridge floor).
        setPos(b.x(), s.projection.elevationToY(t.altitudeMsl()) + 2.0, b.z());
        setYRot((float) (t.headingDeg() + 180.0));
        setXRot((float) -t.pitchDeg());
        rig.setTagScale("gear", (float) t.gearExtension());
        rig.setTagScale("door", t.doorsOpen() ? 0f : 1f);
        rig.pose(server, getX(), getY(), getZ(), getYRot(), getXRot());

        stepWalkers(server, t.doorsOpen());
        for (String msg : sim.pollAnnouncements())
            for (Entity p : getPassengers()) if (p instanceof ServerPlayer sp) sp.sendSystemMessage(Component.literal("✈ " + msg));
        if (tickCount % 10 == 0) FlightDispatcher.broadcastTelemetry(this);
        if (t.phase() == FlightSim.Phase.ARRIVED) {
            if (ticksSinceArrival < 0) ticksSinceArrival = 0;
            if (++ticksSinceArrival > 20 * 300 && getPassengers().isEmpty()) discard();
        }
    }

    private void stepWalkers(ServerLevel server, boolean doorOpen) {
        List<int[]> sync = new ArrayList<>();
        for (Entity e : List.copyOf(getPassengers())) {
            if (!(e instanceof ServerPlayer p)) continue;
            Walker w = walkers.get(p.getUUID());
            if (w == null) continue;
            var in = p.getLastClientInput();
            double fwd = (in.forward() ? 1 : 0) - (in.backward() ? 1 : 0), str = (in.right() ? 1 : 0) - (in.left() ? 1 : 0);
            boolean jumpPressed = in.jump() && !w.jumpHeld;
            w.jumpHeld = in.jump();
            if (w.mode == Mode.SEATED) {
                if (jumpPressed) { double[] a = cabin.aisleBeside(cabin.seat(w.seat)); w.x = a[0]; w.z = a[1]; w.mode = Mode.WALKING; }
            } else {
                double viewYaw = p.getYRot() - getYRot();             // relative to the nose, positive = right
                var m = cabin.move(w.x, w.z, fwd, str, viewYaw, 0.05, doorOpen);
                w.x = m.x(); w.z = m.z();
                if (m.exited()) { disembark(p, w); continue; }
                if (jumpPressed && w.seat != null) {
                    var seat = cabin.seat(w.seat);
                    if (seat != null && Math.abs(w.z - seat.z()) < 0.8 && Math.abs(w.x) < 0.6) { w.x = seat.x(); w.z = seat.z(); w.mode = Mode.SEATED; }
                    else p.sendOverlayMessage(Component.literal("Your seat " + w.seat + " is in row " + seat.row() + "."));
                }
            }
            sync.add(new int[]{p.getId(), w.mode == Mode.SEATED ? 1 : 0, (int) Math.round(w.x * 1000), (int) Math.round(w.z * 1000)});
        }
        var payload = new Payloads.CabinState(getId(), sync);
        for (ServerPlayer watcher : PlayerLookup.tracking(this)) earthlife.mod.net.Net.send(watcher, payload);
    }

    private void disembark(ServerPlayer p, Walker w) {
        walkers.remove(p.getUUID());
        p.stopRiding();
        double[] out = cabin.doorOutside();
        Vec3 pos = toWorld(out[0] - 0.6, 0.1, out[1]);
        p.teleportTo(pos.x, pos.y, pos.z);
        if (sim.phase() == FlightSim.Phase.ARRIVED) {
            p.sendSystemMessage(Component.literal("Welcome to " + flight.to().name() + "."));
            FlightDispatcher.onDisembarkAtDestination(p, this);
        }
    }

    @Override public void remove(RemovalReason reason) {
        if (rig != null) rig.remove();
        super.remove(reason);
    }

    @Override public boolean hurtServer(ServerLevel level, DamageSource source, float amount) { return false; }
    @Override protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder b) {}
    @Override protected void readAdditionalSaveData(ValueInput in) {}
    @Override protected void addAdditionalSaveData(ValueOutput out) {}

    // ---- client-side placement of riders (from CabinState packets) ----
    public static final Map<Integer, double[]> CLIENT_WALKERS = new java.util.concurrent.ConcurrentHashMap<>();
}
