package earthlife.mod.vehicle;

import earthlife.core.transport.*;
import earthlife.core.world.Features;
import earthlife.core.world.Towns;
import earthlife.mod.EarthLifeMod;
import earthlife.mod.EarthLifeServices;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;

import java.util.*;

/**
 * Public transport and the vehicle garage.
 *  - Bus line 1: Williamson <-> Sodus <-> Ontario <-> Webster <-> Rochester town centres along the real road graph,
 *    5 LC fare, spawned only while a player is within 2 km of the bus's position (so it doesn't drive unloaded chunks).
 *  - Trains: if the region data has a railway, a 3-car commuter train shuttles along the longest line.
 *  - Garage: players buy vehicles with LifeCoins; owned vehicles are summoned near the player.
 */
public final class TransitManager {
    static final List<String> BUS_STOPS = List.of("Williamson", "Sodus", "Ontario", "Webster", "Rochester");
    static final long BUS_FARE = 5;

    private VehicleEntity bus;
    private int busLeg = 0, busDir = 1;
    private TrainSim train;
    private final List<VehicleEntity> cars = new ArrayList<>();
    private final Map<String, List<String>> garage = new HashMap<>();    // player -> owned vehicle "TYPE:color"

    public void tick(MinecraftServer server) {
        var s = EarthLifeServices.get();
        ServerLevel level = server.overworld();
        if (server.getTickCount() % 100 == 0) ensureBus(server, level, s);
        if (train == null && server.getTickCount() == 200) startTrain(level, s);
        if (train != null) train.tick(0.05);
    }

    private double[] stop(EarthLifeServices s, String town) {
        var t = Towns.find(s.towns, town).orElseThrow();
        var b = s.projection.toBlock(t.centre().lat(), t.centre().lon());
        return new double[]{b.x(), b.z()};
    }

    private void ensureBus(MinecraftServer server, ServerLevel level, EarthLifeServices s) {
        if (bus != null && !bus.isRemoved()) {
            boolean anyoneNear = server.getPlayerList().getPlayers().stream().anyMatch(p -> p.distanceToSqr(bus) < 2000 * 2000);
            if (!anyoneNear && bus.getPassengers().isEmpty()) { bus.discard(); bus = null; }
            return;
        }
        double[] a = stop(s, BUS_STOPS.get(busLeg));
        boolean anyoneNear = server.getPlayerList().getPlayers().stream().anyMatch(p -> Math.hypot(p.getX() - a[0], p.getZ() - a[1]) < 2000);
        if (!anyoneNear || s.roadGraph.size() == 0) return;
        double[] b = stop(s, BUS_STOPS.get(busLeg + busDir));
        List<Features.Pt> route = s.roadGraph.route(a[0], a[1], b[0], b[1]);
        if (route.isEmpty()) return;
        var start = route.get(0);
        VehicleEntity v = EarthLifeMod.VEHICLE.create(level, EntitySpawnReason.EVENT);
        if (v == null) return;
        v.setPos(start.x(), level.getSeaLevel(), start.z());
        v.configure(VehicleType.BUS, "yellow", null, 0, BUS_FARE);
        v.setAutoRoute(route);
        earthlife.mod.McCompat.spawnTemporary(level, v);
        bus = v;
        for (ServerPlayer p : server.getPlayerList().getPlayers())
            if (p.distanceToSqr(v) < 200 * 200) p.sendSystemMessage(Component.literal("🚌 Bus 1 to " + BUS_STOPS.get(busLeg + busDir) + " is at the " + BUS_STOPS.get(busLeg) + " stop (" + BUS_FARE + " LC)."));
    }

    static void onArrived(VehicleEntity v) {
        var tm = EarthLifeMod.TRANSIT;
        if (v != tm.bus) return;
        tm.busLeg += tm.busDir;
        if (tm.busLeg == BUS_STOPS.size() - 1 || tm.busLeg == 0) tm.busDir = -tm.busDir;
        for (var p : v.getPassengers()) if (p instanceof ServerPlayer sp) {
            sp.sendSystemMessage(Component.literal("🚌 " + BUS_STOPS.get(tm.busLeg) + ". Sneak to get off."));
            earthlife.mod.jobs.JobHooks.onBusStop(sp, v, BUS_STOPS.get(tm.busLeg));
        }
        // Continue to the next stop after a short dwell (re-route from here).
        var s = EarthLifeServices.get();
        double[] next = tm.stop(s, BUS_STOPS.get(tm.busLeg + tm.busDir));
        v.setAutoRoute(s.roadGraph.route(v.getX(), v.getZ(), next[0], next[1]));
    }

    private void startTrain(ServerLevel level, EarthLifeServices s) {
        var rail = s.features.roads.stream().filter(r -> r.cls() == Features.RoadClass.RAIL)
                .max(Comparator.comparingDouble(r -> RoadGraph.length(r.line()))).orElse(null);
        if (rail == null || RoadGraph.length(rail.line()) < 800) return;
        double len = RoadGraph.length(rail.line());
        train = new TrainSim(VehicleType.TRAIN, rail.line(), List.of(new TrainSim.Station("West", 0), new TrainSim.Station("Central", len / 2), new TrainSim.Station("East", len)), 30);
        for (int car = 0; car < 3; car++) {
            VehicleEntity v = EarthLifeMod.VEHICLE.create(level, EntitySpawnReason.EVENT);
            if (v == null) return;
            var p0 = rail.line().get(0);
            v.setPos(p0.x(), level.getSeaLevel(), p0.z());
            v.configure(VehicleType.TRAIN, car == 0 ? "red" : "white", null, 0, car == 0 ? 0 : 6).onTrain(train, car * (VehicleType.TRAIN.length + 1));
            earthlife.mod.McCompat.spawnTemporary(level, v);
        }
    }

    // ---- garage ----
    /** Owned vehicles are rebuilt from the ledger (each purchase is a VEHICLE entry), so they persist with the world. */
    public void restoreGarage() {
        garage.clear();
        for (var e : EarthLifeServices.get().ledger.all()) {
            if (!"VEHICLE".equals(e.type())) continue;
            String[] r = e.reference().split("#");       // vehicle#player#TYPE#color#n
            if (r.length >= 4) garage.computeIfAbsent(r[1], k -> new ArrayList<>()).add(r[2] + ":" + r[3]);
        }
    }

    public List<String> owned(ServerPlayer p) { return garage.getOrDefault(p.getStringUUID(), List.of()); }

    public String buy(ServerPlayer p, VehicleType t, String color) throws Exception {
        if (t.priceLC <= 0) return t.label + " isn't for sale.";
        var s = EarthLifeServices.get();
        s.ledger.debit(p.getStringUUID(), t.priceLC, "VEHICLE", "vehicle#" + p.getStringUUID() + "#" + t + "#" + color + "#" + garage.getOrDefault(p.getStringUUID(), List.of()).size(),
                "Bought " + t.label, p.level().getGameTime());
        garage.computeIfAbsent(p.getStringUUID(), k -> new ArrayList<>()).add(t + ":" + color);
        return "Bought a " + color + " " + t.label + " for " + t.priceLC + " LC. Use /earthlife vehicle summon " + t.name().toLowerCase();
    }

    public String summon(ServerPlayer p, VehicleType t) {
        var owned = garage.getOrDefault(p.getStringUUID(), List.of()).stream().filter(x -> x.startsWith(t + ":")).findFirst();
        boolean workVan = t == VehicleType.DELIVERY_VAN && EarthLifeServices.get().jobs.employment(p.getStringUUID()).map(e -> e.job() == earthlife.core.jobs.JobType.DELIVERY_DRIVER).orElse(false);
        if (owned.isEmpty() && !workVan) return "You don't own a " + t.label + ". Buy one: /earthlife vehicle buy " + t.name().toLowerCase() + " <color>";
        cars.removeIf(c -> { if (c.isRemoved()) return true; if (p.getStringUUID().equals(c.owner()) && c.vehicleType() == t) { c.discard(); return true; } return false; });
        ServerLevel level = (ServerLevel) p.level();
        VehicleEntity v = EarthLifeMod.VEHICLE.create(level, EntitySpawnReason.EVENT);
        if (v == null) return "Could not spawn.";
        double yaw = Math.toRadians(p.getYRot());
        v.setPos(p.getX() - Math.sin(yaw) * 4, p.getY(), p.getZ() + Math.cos(yaw) * 4);
        v.configure(t, owned.map(o -> o.split(":")[1]).orElse("white"), p.getStringUUID(), (p.getYRot() + 180 + 360) % 360, 0);
        earthlife.mod.McCompat.spawnTemporary(level, v);
        cars.add(v);
        return "Your " + t.label + " is here. Right-click to drive.";
    }
}
