package earthlife.mod.jobs;

import earthlife.core.jobs.*;
import earthlife.core.npc.Npc;
import earthlife.core.time.GameClock;
import earthlife.core.world.Features;
import earthlife.mod.EarthLifeMod;
import earthlife.mod.EarthLifeServices;
import earthlife.mod.npc.NpcEntity;
import earthlife.mod.vehicle.VehicleEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

/**
 * Turns world actions into job tasks:
 *  janitor - break the mess (brown carpet) spawned at today's spots ·  check-in agent - right-click a waiting traveler
 *  from behind the counter ·  gate agent - right-click travelers at the gate desk ·  store clerk - restock barrels in the
 *  store (once per barrel per game hour) ·  construction - place blocks at the site ·  delivery driver - drive the
 *  company van to each address ·  bus driver - ride Bus 1 as operator, each stop served counts ·  pilot - checklist +
 *  completed flights ·  farmer - sell crops at any store with /earthlife sell.
 * Plus licences: driving test (4 checkpoints in 5 real minutes, in your own car) and flight school.
 */
public final class JobHooks {
    private static final Map<String, List<BlockPos>> messes = new HashMap<>();
    private static final Map<String, List<JobService.Task>> deliveries = new HashMap<>();
    private static final Map<String, List<NpcEntity>> travelers = new HashMap<>();
    private static final Map<String, DrivingTest> tests = new HashMap<>();

    record DrivingTest(List<Features.Pt> checkpoints, int[] next, long deadlineMs) {}

    /** Where a player must be to clock in for their employer. */
    public static Optional<double[]> workplace(JobService.Employment e) {
        var s = EarthLifeServices.get();
        var layout = s.airportLayouts.get(e.employer());
        if (layout != null) return Optional.of(layout.placed.marker(e.job() == JobType.GATE_AGENT ? "agent:gate:A1" : "agent:checkin:1"));
        for (Features.Building b : s.features.buildings) if (b.id().equals(e.employer())) { var p = b.footprint().get(0); return Optional.of(new double[]{p.x(), 0, p.z()}); }
        return earthlife.core.world.Towns.find(s.towns, e.employer()).map(t -> { var b = s.projection.toBlock(t.centre().lat(), t.centre().lon()); return new double[]{b.x(), 0, b.z()}; });
    }

    public static String clockIn(ServerPlayer p) throws Exception {
        var s = EarthLifeServices.get();
        var e = s.jobs.employment(p.getStringUUID()).orElseThrow(() -> new JobService.JobException("No job yet: /earthlife job apply <job> <employer>"));
        double[] w = workplace(e).orElseThrow(() -> new JobService.JobException("Unknown workplace " + e.employer()));
        boolean at = e.job() == JobType.JANITOR || Math.hypot(p.getX() - w[0], p.getZ() - w[2]) < 80;
        s.jobs.clockIn(p.getStringUUID(), earthlife.mod.McCompat.dayTime(p.level()), at);
        setUpTasks(p, e);
        s.life.complete(p, "clock_in");
        return "Clocked in as " + e.job().title + ". Shift ends " + String.format("%02d:00", e.job().shiftEnd) + ".";
    }

    private static void setUpTasks(ServerPlayer p, JobService.Employment e) {
        var s = EarthLifeServices.get();
        ServerLevel level = (ServerLevel) p.level();
        long day = GameClock.day(earthlife.mod.McCompat.dayTime(level));
        String id = p.getStringUUID();
        switch (e.job()) {
            case JANITOR -> {
                List<BlockPos> spots = new ArrayList<>();
                var r = new Random(Objects.hash(id, day));
                for (int i = 0; i < 6; i++) {
                    int x = (int) p.getX() + r.nextInt(31) - 15, z = (int) p.getZ() + r.nextInt(31) - 15;
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos pos = new BlockPos(x, y, z);
                    if (level.getBlockState(pos).isAir()) { level.setBlockAndUpdate(pos, earthlife.mod.McCompat.block("minecraft:brown_carpet")); spots.add(pos); }
                }
                messes.put(id, spots);
                p.sendSystemMessage(Component.literal(spots.size() + " messes to clean nearby (brown stains). Break them."));
            }
            case STORE_CLERK -> {
                // Stock room: barrels to restock appear beside the clerk (each counts once per game hour).
                List<BlockPos> spots = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    BlockPos pos = p.blockPosition().offset(i % 2 == 0 ? 2 : -2, 0, i < 2 ? 2 : -2);
                    if (level.getBlockState(pos).isAir() && !level.getBlockState(pos.below()).isAir()) { level.setBlockAndUpdate(pos, Blocks.BARREL.defaultBlockState()); spots.add(pos); }
                    else if (level.getBlockState(pos).is(Blocks.BARREL)) spots.add(pos);
                }
                p.sendSystemMessage(Component.literal("Restock the shelves: open each barrel (" + spots.size() + " here) - once per hour each."));
            }
            case DELIVERY_DRIVER -> {
                List<String> addresses = s.features.buildings.stream().filter(b -> "house".equals(b.kind()))
                        .filter(b -> Math.hypot(b.footprint().get(0).x() - p.getX(), b.footprint().get(0).z() - p.getZ()) < 1500).map(Features.Building::id).toList();
                var tasks = JobService.tasksFor(JobType.DELIVERY_DRIVER, e.employer(), day, addresses, Math.min(5, addresses.size()));
                deliveries.put(id, new ArrayList<>(tasks));
                p.sendSystemMessage(Component.literal(tasks.size() + " packages today. Summon your van: /earthlife vehicle summon delivery_van. /earthlife job tasks shows addresses."));
            }
            case CHECKIN_AGENT, GATE_AGENT -> {
                var layout = s.airportLayouts.get(e.employer());
                if (layout == null) return;
                List<NpcEntity> list = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    String marker = e.job() == JobType.CHECKIN_AGENT ? "checkin:" + (1 + i % 2) : "gate:A1";
                    double[] m = layout.placed.marker(marker);
                    if (m == null) continue;
                    var pop = s.populations.get(i % s.populations.size()).all();
                    if (pop.isEmpty()) continue;
                    Npc n = pop.get(new Random(day * 31 + i).nextInt(pop.size()));
                    NpcEntity t = EarthLifeMod.NPC.create(level, EntitySpawnReason.EVENT);
                    if (t == null) continue;
                    t.bind(n, true);
                    t.setPos(m[0] + 0.5 + (i / 2) * 0.0, m[1], m[2] + 0.5 + (i / 2) * 1.2);
                    level.addFreshEntity(t);
                    list.add(t);
                }
                travelers.put(id, list);
                p.sendSystemMessage(Component.literal(list.size() + " travelers waiting. Right-click each one to " + (e.job() == JobType.CHECKIN_AGENT ? "check them in." : "scan their boarding pass.")));
            }
            default -> {}
        }
    }

    public static boolean onNpcInteract(ServerPlayer p, NpcEntity npc, Npc resident) {
        if (!npc.traveler()) return false;
        var s = EarthLifeServices.get();
        var e = s.jobs.employment(p.getStringUUID()).orElse(null);
        if (e == null || s.jobs.shift(p.getStringUUID()).isEmpty()) { p.sendSystemMessage(Component.literal(resident.firstName() + ": I'm waiting for the agent.")); return true; }
        String kind = e.job() == JobType.CHECKIN_AGENT ? "checkin" : e.job() == JobType.GATE_AGENT ? "board" : null;
        if (kind == null) return false;
        try {
            if (s.jobs.recordTask(p.getStringUUID(), kind, npc.residentId() + "@" + GameClock.day(earthlife.mod.McCompat.dayTime(p.level())))) {
                p.sendSystemMessage(Component.literal(kind.equals("checkin") ? "✓ Checked in " + resident.name() + ". Bag tagged." : "✓ Boarding pass scanned: " + resident.name() + "."));
                s.life.complete(p, "work_task");
                npc.discard();
            }
        } catch (Exception ex) { EarthLifeMod.LOG.warn("task", ex); }
        return true;
    }

    public static void onBlockBroken(ServerPlayer p, BlockPos pos) {
        var list = messes.get(p.getStringUUID());
        if (list != null && list.remove(pos)) task(p, "clean", pos.toShortString(), "Cleaned. " + list.size() + " left.");
    }

    public static void onBlockPlaced(ServerPlayer p, BlockPos pos) {
        var s = EarthLifeServices.get();
        var e = s.jobs.employment(p.getStringUUID()).orElse(null);
        if (e != null && e.job() == JobType.CONSTRUCTION_WORKER) task(p, "build", pos.toShortString(), null);
    }

    public static void onContainerUsed(ServerPlayer p, BlockPos pos) {
        var s = EarthLifeServices.get();
        var e = s.jobs.employment(p.getStringUUID()).orElse(null);
        if (e != null && e.job() == JobType.STORE_CLERK && p.level().getBlockState(pos).is(Blocks.BARREL))
            task(p, "restock", pos.toShortString() + "@" + GameClock.hour(earthlife.mod.McCompat.dayTime(p.level())), "Shelf restocked.");
    }

    public static void onBusStop(ServerPlayer p, VehicleEntity bus, String stop) {
        var e = EarthLifeServices.get().jobs.employment(p.getStringUUID()).orElse(null);
        if (e != null && e.job() == JobType.BUS_DRIVER) task(p, "bus_stop", stop + "@" + earthlife.mod.McCompat.dayTime(p.level()) / 1000, "Stop served: " + stop);
    }

    private static void task(ServerPlayer p, String kind, String ref, String msg) {
        try {
            if (EarthLifeServices.get().jobs.recordTask(p.getStringUUID(), kind, ref)) {
                if (msg != null) p.sendOverlayMessage(Component.literal("✓ " + msg));
                EarthLifeServices.get().life.complete(p, "work_task");
            }
        }
        catch (Exception ex) { EarthLifeMod.LOG.warn("task", ex); }
    }

    /** Every second: deliveries, driving tests, end-of-shift payouts. */
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) return;
        var s = EarthLifeServices.get();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            var dl = deliveries.get(p.getStringUUID());
            if (dl != null && p.getVehicle() instanceof VehicleEntity) {
                for (Iterator<JobService.Task> it = dl.iterator(); it.hasNext(); ) {
                    var t = it.next();
                    var b = s.features.buildings.stream().filter(x -> x.id().equals(t.target())).findFirst().orElse(null);
                    if (b != null && Math.hypot(b.footprint().get(0).x() - p.getX(), b.footprint().get(0).z() - p.getZ()) < 12) {
                        task(p, "deliver", t.id(), "Package delivered."); it.remove();
                    }
                }
            }
            DrivingTest dt = tests.get(p.getStringUUID());
            if (dt != null) {
                if (System.currentTimeMillis() > dt.deadlineMs()) { tests.remove(p.getStringUUID()); p.sendSystemMessage(Component.literal("Driving test failed: out of time.")); continue; }
                var cp = dt.checkpoints().get(dt.next()[0]);
                if (p.getVehicle() instanceof VehicleEntity && Math.hypot(cp.x() - p.getX(), cp.z() - p.getZ()) < 10) {
                    if (++dt.next()[0] == dt.checkpoints().size()) {
                        tests.remove(p.getStringUUID());
                        try { s.jobs.grantLicence(p.getStringUUID(), "license:driver"); } catch (Exception ignored) {}
                        p.sendSystemMessage(Component.literal("Driving test passed! You now hold a driver licence."));
                    } else {
                        var n = dt.checkpoints().get(dt.next()[0]);
                        p.sendSystemMessage(Component.literal("Checkpoint " + dt.next()[0] + "/4. Next: " + Math.round(n.x()) + ", " + Math.round(n.z())));
                    }
                }
            }
        }
        try {
            for (var e : s.jobs.autoClockOut(earthlife.mod.McCompat.dayTime(server.overworld()), server.overworld().getGameTime()).entrySet()) {
                var p = server.getPlayerList().getPlayer(UUID.fromString(e.getKey()));
                if (p != null) { p.sendSystemMessage(Component.literal(payslip(e.getValue()))); if (e.getValue().total() > 0) s.life.complete(p, "paid"); }
            }
        } catch (Exception ex) { EarthLifeMod.LOG.warn("autoClockOut", ex); }
    }

    public static String payslip(JobService.Payslip ps) {
        return String.format("Shift over: %.1f h, %d tasks → %d LC%s", ps.hoursTicks() / 1000.0, ps.tasks(), ps.total(),
                ps.hourlyWithheld() ? " (hourly pay withheld: not enough tasks done)" : "");
    }

    public static String startDrivingTest(ServerPlayer p) {
        var s = EarthLifeServices.get();
        if (s.roadGraph.size() < 4) return "No road data here for a test route.";
        var r = new Random();
        List<Features.Pt> cps = new ArrayList<>();
        int start = s.roadGraph.nearest(p.getX(), p.getZ());
        var from = s.roadGraph.node(start);
        for (int i = 0; i < 4; i++) {
            var path = s.roadGraph.route(from.x(), from.z(), from.x() + r.nextInt(600) - 300, from.z() + r.nextInt(600) - 300);
            if (path.size() < 2) return "Couldn't plan a route; try nearer a town.";
            var end = path.get(path.size() - 1);
            cps.add(end);
            from = new earthlife.core.transport.RoadGraph.Node(-1, end.x(), end.z());
        }
        tests.put(p.getStringUUID(), new DrivingTest(cps, new int[]{0}, System.currentTimeMillis() + 5 * 60_000));
        return "Driving test started: drive your car through 4 checkpoints within 5 minutes. First: " + Math.round(cps.get(0).x()) + ", " + Math.round(cps.get(0).z());
    }

    public static String flightSchool(ServerPlayer p) throws Exception {
        var s = EarthLifeServices.get();
        long flown = s.ledger.history(p.getStringUUID()).stream().filter(e -> "TICKET".equals(e.type())).count();
        if (flown < 3) return "Flight school needs 3 flights' experience as a passenger (you have " + flown + ").";
        s.ledger.debit(p.getStringUUID(), 1500, "TUITION", "flightschool#" + p.getStringUUID(), "Flight school", p.level().getGameTime());
        s.jobs.grantLicence(p.getStringUUID(), "license:pilot");
        return "Flight school complete (1,500 LC). You can now apply as a Pilot at an airport.";
    }

    public static List<String> taskList(ServerPlayer p) {
        List<String> out = new ArrayList<>();
        var m = messes.get(p.getStringUUID());
        if (m != null) m.forEach(pos -> out.add("Clean mess at " + pos.toShortString()));
        var d = deliveries.get(p.getStringUUID());
        var s = EarthLifeServices.get();
        if (d != null) for (var t : d) s.features.buildings.stream().filter(b -> b.id().equals(t.target())).findFirst()
                .ifPresent(b -> out.add("Deliver to house at " + Math.round(b.footprint().get(0).x()) + ", " + Math.round(b.footprint().get(0).z())));
        var tr = travelers.get(p.getStringUUID());
        if (tr != null) out.add(tr.stream().filter(x -> !x.isRemoved()).count() + " travelers waiting");
        return out;
    }
}
