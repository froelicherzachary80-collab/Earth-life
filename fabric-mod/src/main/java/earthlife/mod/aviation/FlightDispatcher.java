package earthlife.mod.aviation;

import earthlife.core.aviation.*;
import earthlife.core.build.AirportLayout;
import earthlife.core.time.GameClock;
import earthlife.mod.EarthLifeMod;
import earthlife.mod.EarthLifeServices;
import earthlife.mod.net.Payloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;

import java.util.*;

/**
 * Timetable for every airport pair in the region (V0.2: hub-and-spoke through KROC plus the original
 * Williamson-Sodus hop). Aircraft appear at their generated stand, boarding opens at the jet bridge, and they
 * taxi along the generated apron/taxiways to the real runway.
 */
public final class FlightDispatcher {
    static final int BOARDING_LEAD_TICKS = 2000;                 // 2 in-game hours (100 s real at vanilla speed)
    record Route(String number, String from, String to, int departTicks) {}

    private final List<Route> timetable = new ArrayList<>();
    private final Map<String, AirplaneEntity> active = new HashMap<>();

    public FlightDispatcher() {
        timetable.add(new Route("EL101", "KSDC", "KROC", 3000));
        timetable.add(new Route("EL102", "KROC", "KSDC", 13000));
        timetable.add(new Route("EL201", "KROC", "KIUA", 5000));
        timetable.add(new Route("EL202", "KIUA", "KROC", 9000));
        timetable.add(new Route("EL301", "KROC", "KGVQ", 7000));
        timetable.add(new Route("EL302", "KGVQ", "KROC", 11000));
    }

    public void scheduleDay(EarthLifeServices s, long day) {
        for (Route r : timetable) {
            Airport from = s.airports.get(r.from()), to = s.airports.get(r.to());
            if (from == null || to == null) continue;
            String id = r.number() + "-" + day;
            if (s.booking.flight(id) == null) s.booking.schedule(new Booking.Flight(id, r.number(), from, to, AircraftType.HOPPER, day, r.departTicks()));
        }
    }

    public void tick(MinecraftServer server) {
        var s = EarthLifeServices.get();
        ServerLevel overworld = server.overworld();
        long time = earthlife.mod.McCompat.dayTime(overworld);
        long day = GameClock.day(time);
        int tod = (int) Math.floorMod(time, GameClock.TICKS_PER_DAY);
        scheduleDay(s, day); scheduleDay(s, day + 1);
        for (Route r : timetable) {
            String id = r.number() + "-" + day;
            Booking.Flight f = s.booking.flight(id);
            if (f == null) continue;
            AirplaneEntity plane = active.get(id);
            if (plane == null && tod >= r.departTicks() - BOARDING_LEAD_TICKS && tod < r.departTicks()) {
                plane = spawn(overworld, f);
                if (plane != null) {
                    active.put(id, plane);
                    for (ServerPlayer p : server.getPlayerList().getPlayers())
                        if (s.booking.verify(id, p.getStringUUID()).isPresent())
                            p.sendSystemMessage(Component.literal("Now boarding " + f.number() + " to " + f.to().name() + " at gate A1. Walk down the jet bridge to board."));
                }
            }
            if (plane == null) continue;
            if (plane.isRemoved()) { active.remove(id); continue; }
            if (plane.sim().phase() == FlightSim.Phase.BOARDING) {
                autoBoard(server, s, plane, f);
                if (tod >= r.departTicks()) plane.sim().beginDeparture();
            }
        }
    }

    /** Ticketed players who walk to the end of the jet bridge are boarded through the door. */
    private void autoBoard(MinecraftServer server, EarthLifeServices s, AirplaneEntity plane, Booking.Flight f) {
        double[] end = s.airportLayouts.get(f.from().icao()).placed.marker("bridge_end:A1");
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getVehicle() != null || p.distanceToSqr(end[0], end[1], end[2]) > 2.25) continue;
            if (s.booking.verify(f.id(), p.getStringUUID()).isPresent()) plane.board(p, false);
            else p.sendOverlayMessage(Component.literal("Boarding pass required for " + f.number() + "."));
        }
    }

    private AirplaneEntity spawn(ServerLevel level, Booking.Flight f) {
        var s = EarthLifeServices.get();
        AirplaneEntity e = EarthLifeMod.AIRPLANE.create(level, EntitySpawnReason.EVENT);
        if (e == null) return null;
        AirportLayout lf = s.airportLayouts.get(f.from().icao()), lt = s.airportLayouts.get(f.to().icao());
        AirportLayout.Stand dep = lf.stands.get(0), arr = lt.stands.get(Math.min(1, lt.stands.size() - 1));
        Airport from = f.from().withGate(dep.geo()), to = f.to().withGate(arr.geo());
        double course = from.ref().bearingTo(to.ref());
        FlightSim sim = new FlightSim(f.type(), from, to,
                lf.taxiOut(dep, from.bestRunwayFor(course), s.projection), lt.taxiIn(arr, to.bestRunwayFor(course), s.projection));
        e.assign(f, sim, s.cabin);
        level.getChunk((int) Math.floor(dep.x()) >> 4, (int) Math.floor(dep.z()) >> 4);
        e.setPos(dep.x(), dep.floorY(), dep.z());
        e.setYRot((float) (dep.headingDeg() + 180));
        level.addFreshEntity(e);
        return e;
    }

    public Optional<AirplaneEntity> boardingAt(String icao) {
        return active.values().stream().filter(a -> !a.isRemoved() && a.flight().from().icao().equals(icao) && a.sim().phase() == FlightSim.Phase.BOARDING).findFirst();
    }

    static void onDisembarkAtDestination(ServerPlayer p, AirplaneEntity plane) {
        if (!p.getStringUUID().equals(plane.captain())) return;
        try {
            var jobs = EarthLifeServices.get().jobs;
            if (plane.checklist().complete()) {
                jobs.recordTask(p.getStringUUID(), "flight_complete", plane.flight().id());
                p.sendSystemMessage(Component.literal("Flight " + plane.flight().number() + " completed with a perfect checklist."));
            } else p.sendSystemMessage(Component.literal("Flight complete, but checklist items were missed: no completion bonus."));
        } catch (Exception ex) { EarthLifeMod.LOG.warn("pilot task", ex); }
    }

    /** Sends the live sim state to every passenger's seatback screen. */
    static void broadcastTelemetry(AirplaneEntity plane) {
        var t = plane.sim().telemetry();
        var route = plane.sim().route();
        double[] lats = new double[route.size()], lons = new double[route.size()];
        for (int i = 0; i < route.size(); i++) { lats[i] = route.get(i).lat(); lons[i] = route.get(i).lon(); }
        var payload = new Payloads.FlightTelemetry(plane.flight().number(), t.departure(), t.destination(), t.phase().name(),
                t.position().lat(), t.position().lon(), t.altitudeMsl(), t.groundSpeed(), t.headingDeg(), t.distanceRemaining(),
                t.etaSeconds() / plane.sim().timeCompression(), lats, lons);
        for (Entity p : plane.getPassengers()) if (p instanceof ServerPlayer sp) ServerPlayNetworking.send(sp, payload);
    }
}
