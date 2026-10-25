package earthlife.test;

import earthlife.core.aviation.*;
import earthlife.core.economy.Ledger;
import earthlife.core.jobs.*;
import earthlife.core.npc.*;
import earthlife.core.time.GameClock;
import earthlife.core.transport.*;
import earthlife.core.world.Features;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.LocalTime;
import java.util.*;

import static earthlife.test.T.*;

/** V0.2: jobs, NPCs, skins, vehicles, trains. */
public final class V02LifeTest {
    static long at(long day, int h) { return day * 24000 + GameClock.ticksOfDay(LocalTime.of(h, 0)); }

    public static void testJobShiftTasksAndWages() throws Exception {
        Path d = tmpDir();
        Ledger l = Ledger.open(d.resolve("l.jsonl"));
        JobService j = new JobService(l, d.resolve("jobs.json"));
        j.hire("zach", JobType.JANITOR, "KROC", 0);
        throwsEx(JobService.JobException.class, () -> j.clockIn("zach", at(0, 10), true), "outside shift window");
        throwsEx(JobService.JobException.class, () -> j.clockIn("zach", at(0, 18), false), "must be at workplace");
        j.clockIn("zach", at(0, 18), true);
        check(j.recordTask("zach", "clean", "spill-1"), "task counts");
        check(!j.recordTask("zach", "clean", "spill-1"), "same spill can't count twice");
        check(!j.recordTask("zach", "deliver", "pkg-1"), "other job's task ignored");
        check(j.recordTask("zach", "clean", "spill-2") && j.recordTask("zach", "clean", "spill-3"), "more tasks");
        var slip = j.clockOut("zach", at(0, 21), 100);
        check(slip.hoursTicks() == 3000 && slip.tasks() == 3, "3 hours, 3 tasks");
        check(slip.total() == 3 * 35 + 3 * 8 && l.balance("zach") == slip.total(), "paid " + slip.total());
        check(j.clockOut("zach", at(0, 22), 101).total() == 0, "no double pay");
        // AFK shift: hours withheld when no tasks were done
        j.clockIn("zach", at(1, 18), true);
        var afk = j.clockOut("zach", at(1, 23), 200);
        check(afk.hourlyWithheld() && afk.total() == 0, "no wages without work");
        // Auto clock-out at shift end caps paid hours
        j.clockIn("zach", at(2, 18), true);
        j.recordTask("zach", "clean", "a"); j.recordTask("zach", "clean", "b"); j.recordTask("zach", "clean", "c");
        var auto = j.autoClockOut(at(3, 2), 300);
        check(auto.get("zach").hoursTicks() == 5000, "capped at shift end (5 h), got " + auto.get("zach").hoursTicks());
        // Licence requirement and persistence
        throwsEx(JobService.JobException.class, () -> j.hire("zach", JobType.PILOT, "KROC", 3), "one job at a time");
        j.quit("zach", at(3, 8), 400);
        throwsEx(JobService.JobException.class, () -> j.hire("zach", JobType.PILOT, "KROC", 3), "pilot needs licence");
        j.grantLicence("zach", "license:pilot");
        j.hire("zach", JobType.PILOT, "KROC", 3);
        JobService again = new JobService(Ledger.open(d.resolve("l.jsonl")), d.resolve("jobs.json"));
        check(again.employment("zach").map(e -> e.job() == JobType.PILOT).orElse(false), "employment persisted");
    }

    public static void testCropSalesAndTasks() throws Exception {
        Path d = tmpDir();
        Ledger l = Ledger.open(d.resolve("l.jsonl"));
        JobService j = new JobService(l, d.resolve("jobs.json"));
        check(j.sell("zach", "minecraft:wheat", 32, "s1", 1) == 128, "32 wheat = 128 LC");
        check(j.sell("zach", "minecraft:wheat", 32, "s1", 2) == 0, "same sale id not paid twice");
        check(j.sell("zach", "minecraft:diamond", 1, "s2", 3) == 0, "market doesn't buy diamonds");
        var t1 = JobService.tasksFor(JobType.JANITOR, "KROC", 5, List.of("janitor:A1", "janitor:A2"), 6);
        var t2 = JobService.tasksFor(JobType.JANITOR, "KROC", 5, List.of("janitor:A1", "janitor:A2"), 6);
        check(t1.equals(t2) && t1.size() == 6 && t1.stream().allMatch(t -> t.kind().equals("clean")), "deterministic daily tasks");
    }

    public static void testPilotChecklistWindows() throws Exception {
        var a = AviationTest.airports();
        FlightSim sim = new FlightSim(AircraftType.HOPPER, a.get("KSDC"), a.get("KROC"));
        PilotChecklist c = new PilotChecklist();
        check(!c.act(PilotChecklist.Item.TAKEOFF_CLEARANCE, sim.telemetry()), "can't request takeoff at the gate");
        check(c.act(PilotChecklist.Item.PREFLIGHT, sim.telemetry()) && c.act(PilotChecklist.Item.BEACON_ON, sim.telemetry()), "preflight + beacon");
        sim.beginDeparture();
        boolean clearance = false, lightsOn = false, brake = false;
        for (int i = 0; i < 40_000 && sim.phase() != FlightSim.Phase.ARRIVED; i++) {
            sim.tick(0.05);
            var t = sim.telemetry();
            if (!clearance && t.phase() == FlightSim.Phase.TAXI_OUT) clearance = c.act(PilotChecklist.Item.TAKEOFF_CLEARANCE, t);
            if (t.phase() == FlightSim.Phase.CRUISE) c.act(PilotChecklist.Item.DESCENT_BRIEFING, t);
            if (!lightsOn && t.phase() == FlightSim.Phase.DESCENT && t.altitudeAgl() < 800) lightsOn = c.act(PilotChecklist.Item.LANDING_LIGHTS_ON, t);
        }
        brake = c.act(PilotChecklist.Item.PARKING_BRAKE, sim.telemetry());
        check(clearance && lightsOn && brake && c.complete(), "full checklist flown: " + c.done());
    }

    static List<Features.Building> houses(int n) {
        List<Features.Building> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double x = i * 30;
            out.add(new Features.Building("h" + i, "house", 2, List.of(new Features.Pt(x, 0), new Features.Pt(x + 10, 0), new Features.Pt(x + 10, 10), new Features.Pt(x, 10)), Features.Provenance.VERIFIED));
        }
        return out;
    }

    public static void testPopulationSchedulesAndSpawning() {
        var work = List.of(new Population.Workplace(new Npc.Place("store", "Store", 500, 0), JobType.STORE_CLERK),
                new Population.Workplace(new Npc.Place("KSDC", "Airport", 3000, 0), JobType.CHECKIN_AGENT));
        var pop = new Population(42, "Williamson", houses(20), work, List.of(new Npc.Place("park", "Park", 250, 80)), 2);
        check(pop.all().size() == 40, "2 residents per house");
        check(new Population(42, "Williamson", houses(20), work, List.of(), 2).all().get(7).name().equals(pop.all().get(7).name()), "same people every load");
        Npc clerk = pop.all().stream().filter(n -> n.job() == JobType.STORE_CLERK).findFirst().orElseThrow();
        check(clerk.activityAt(at(0, 3)) == Npc.Activity.SLEEPING, "asleep at 3am");
        check(clerk.activityAt(at(0, 10)) == Npc.Activity.WORKING && clerk.placeFor(Npc.Activity.WORKING).id().equals("store"), "at the store at 10am");
        check(clerk.activityAt(at(5, 10)) != Npc.Activity.WORKING, "weekend off");
        check(clerk.activityAt(at(0, 18)) == Npc.Activity.LEISURE, "evening out");
        var nearHomes = pop.nearby(100, 5, 120, at(0, 19), 10);
        check(nearHomes.size() <= 10 && nearHomes.stream().noneMatch(n -> n.activityAt(at(0, 19)) == Npc.Activity.SLEEPING), "spawn cap and no sleepers");
        check(pop.nearby(100, 5, 120, at(0, 2), 50).isEmpty(), "streets empty at 2am");
    }

    public static void testRelationshipsAndDialogue() throws Exception {
        var pop = new Population(1, "Sodus", houses(3), List.of(), List.of(), 1);
        Npc n = pop.all().get(0);
        Social s = new Social(tmpDir().resolve("social.json"));
        check(s.level("p1", n.id()) == Social.Level.STRANGER, "start as strangers");
        s.interact("p1", n, Social.Action.CHAT, at(0, 10));
        int once = s.affinity("p1", n.id());
        s.interact("p1", n, Social.Action.CHAT, at(0, 11));
        check(s.affinity("p1", n.id()) == once, "chat spam gives nothing");
        for (int d = 1; d < 6; d++) s.interact("p1", n, Social.Action.HELP, at(d, 10));
        check(s.level("p1", n.id()).ordinal() >= Social.Level.FRIEND.ordinal(), "helping makes friends: " + s.level("p1", n.id()));
        String line = s.line(n, "p1", "Zach", at(0, 19));
        check(line.contains("Zach") && line.length() > 20, "personal greeting: " + line);
    }

    public static void testSkinsArePlayerFormatAndUnique() throws Exception {
        BufferedImage a = SkinGenerator.paint(1, SkinGenerator.Uniform.NONE), b = SkinGenerator.paint(2, SkinGenerator.Uniform.PILOT);
        check(a.getWidth() == 64 && a.getHeight() == 64, "64x64");
        check(a.getRGB(9, 12) == 0xFFFFFFFF, "eye white on the face");
        check(((a.getRGB(20, 25) >>> 24) & 255) == 255 && ((a.getRGB(40, 24) >>> 24) & 255) == 255, "body and arm painted");
        check(b.getRGB(23, 24) == 0xFF0D1B3E, "pilot tie");
        int diff = 0;
        for (int x = 0; x < 64; x++) for (int y = 0; y < 64; y++) if (a.getRGB(x, y) != b.getRGB(x, y)) diff++;
        check(diff > 200, "different people look different");
        byte[] png = SkinGenerator.png(1, SkinGenerator.Uniform.NONE);
        check(png[1] == 'P' && png[2] == 'N' && png[3] == 'G', "valid PNG");
        check(Arrays.equals(png, SkinGenerator.png(1, SkinGenerator.Uniform.NONE)), "deterministic");
    }

    static Features grid() {
        // 3x3 street grid, 200 m blocks, plus a one-way spur
        StringBuilder sb = new StringBuilder("{\"attribution\":\"t\",\"roads\":[");
        int id = 0;
        for (int i = 0; i <= 2; i++) {
            sb.append(String.format("{\"id\":\"h%d\",\"class\":\"RESIDENTIAL\",\"width\":6,\"line\":[[0,%d],[200,%d],[400,%d]]},", id++, i * 200, i * 200, i * 200));
            sb.append(String.format("{\"id\":\"v%d\",\"class\":\"%s\",\"width\":6,\"line\":[[%d,0],[%d,200],[%d,400]]},", id++, i == 1 ? "PRIMARY" : "RESIDENTIAL", i * 200, i * 200, i * 200));
        }
        sb.append("{\"id\":\"rail\",\"class\":\"RAIL\",\"width\":4,\"line\":[[-100,-50],[500,-50],[900,-200]]}],\"buildings\":[],\"areas\":[],\"water\":[]}");
        return Features.parse(sb.toString());
    }

    public static void testRoutingAndAutopilotDrive() {
        Features f = grid();
        RoadGraph g = RoadGraph.roads(f);
        check(g.size() == 9, "9 junctions, got " + g.size());
        var route = g.route(0, 0, 400, 400);
        check(!route.isEmpty() && Math.abs(RoadGraph.length(route) - 800) < 1, "Manhattan route 800 m");
        check(route.stream().anyMatch(p -> p.x() == 200 && p.z() > 0 && p.z() < 400), "prefers the faster primary road through the middle");
        VehicleSim car = new VehicleSim(VehicleType.CAR, 0, 0, 90);
        car.follow(route);
        double maxOff = 0;
        int ticks = 0;
        while (!car.arrived() && ticks++ < 20 * 300) {
            car.autopilot(15, 0.05);
            double off = Double.MAX_VALUE;
            for (int k = 1; k < route.size(); k++) off = Math.min(off, Features.distToSegment(car.x, car.z, route.get(k - 1), route.get(k)));
            maxOff = Math.max(maxOff, off);
        }
        check(car.arrived(), "car reached destination in " + ticks / 20 + " s");
        check(maxOff < 6, "stayed within the road (max " + String.format("%.1f", maxOff) + " m off centreline)");
        check(Math.hypot(car.x - 400, car.z - 400) < 2.5 && car.speed == 0, "stopped at the destination");
    }

    public static void testManualDrivingPhysics() {
        VehicleSim car = new VehicleSim(VehicleType.CAR, 0, 0, 0);
        double t = 0;
        while (car.speed < 26.8 && t < 30) { car.drive(1, 0, false, 0.05); t += 0.05; }
        check(t > 7 && t < 20, "0-60 mph in " + String.format("%.1f", t) + " s");
        double startZ = car.z;
        while (car.speed > 0) car.drive(-1, 0, false, 0.05);
        double brakeDist = Math.abs(car.z - startZ);
        check(brakeDist > 35 && brakeDist < 70, "60-0 braking distance " + Math.round(brakeDist) + " m");
        check(car.headingDeg == 0, "straight line");
        VehicleSim bus = new VehicleSim(VehicleType.BUS, 0, 0, 0);
        check(bus.type.minTurnRadius() > car.type.minTurnRadius(), "bus turns wider than a car");
    }

    public static void testTrainTimetable() {
        Features f = grid();
        var rail = f.roads.stream().filter(r -> r.cls() == Features.RoadClass.RAIL).findFirst().orElseThrow();
        double len = RoadGraph.length(rail.line());
        TrainSim tr = new TrainSim(VehicleType.TRAIN, rail.line(), List.of(new TrainSim.Station("West", 0), new TrainSim.Station("Mid", 600), new TrainSim.Station("East", len)), 20);
        List<String> stops = new ArrayList<>();
        TrainSim.State prev = tr.state();
        for (int i = 0; i < 20 * 600; i++) {
            tr.tick(0.05);
            check(tr.distance() >= -1e-9 && tr.distance() <= len + 1e-9, "on the track");
            if (prev == TrainSim.State.RUNNING && tr.state() == TrainSim.State.DWELL) stops.add(String.format("%.0f", tr.distance()));
            prev = tr.state();
        }
        check(stops.size() >= 3 && stops.get(0).equals("600") && stops.get(1).equals(String.format("%.0f", len)) && stops.get(2).equals("600"), "stops Mid, East, back to Mid: " + stops);
        double[] pose = tr.poseAt(10);
        check(!Double.isNaN(pose[0]) && pose[2] >= 0 && pose[2] < 360, "carriage pose");
    }

    public static void testVehicleModelsAndSeats() {
        for (VehicleType v : VehicleType.values()) {
            var parts = v.model("red");
            check(!parts.isEmpty(), v + " has a model");
            check(v.seatPositions().size() == v.seats, v + " seat count");
            for (double[] s : v.seatPositions()) check(Math.abs(s[0]) <= v.width / 2 && Math.abs(s[1]) <= v.length / 2 + 0.6, v + " seat inside body");
            if (v.terrain.equals("ground") && v != VehicleType.BICYCLE)
                check(parts.stream().filter(p -> p.tag().equals("wheel")).count() == 4, v + " has 4 wheels");
        }
    }
}
