package earthlife.mod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import earthlife.core.jobs.*;
import earthlife.core.time.GameClock;
import earthlife.core.transport.VehicleType;
import earthlife.core.world.Towns;
import earthlife.mod.aviation.AirplaneEntity;
import earthlife.mod.jobs.JobHooks;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/**
 * V0.2 commands (the wallet and booking screens drive these too):
 *   /earthlife time realtime | speed <minutesPerDay> | vanilla | show
 *   /earthlife job list | apply <job> <employer> | quit | clockin | clockout | status | tasks
 *   /earthlife license driver | pilot
 *   /earthlife pilot board | <checklist item>
 *   /earthlife vehicle shop | buy <type> <color> | summon <type> | list
 *   /earthlife towns · /earthlife sell
 */
public final class V02Commands {
    private V02Commands() {}

    interface Body { String run(ServerPlayer p, CommandContext<CommandSourceStack> c) throws Exception; }

    static int run(CommandContext<CommandSourceStack> c, Body b) {
        if (!EarthLifeServices.running()) {
            c.getSource().sendFailure(Component.literal("EarthLife commands work in Earth worlds (Create New World > World Type: Earth - 1:1 Real World)."));
            return 0;
        }
        try {
            ServerPlayer p = c.getSource().getPlayerOrException();
            String msg = b.run(p, c);
            if (msg != null) p.sendSystemMessage(Component.literal(msg));
            return 1;
        } catch (JobService.JobException e) {
            c.getSource().sendFailure(Component.literal(e.getMessage())); return 0;
        } catch (Exception e) {
            c.getSource().sendFailure(Component.literal("EarthLife: " + e.getMessage())); return 0;
        }
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("earthlife")
            .then(Commands.literal("time")
                .then(Commands.literal("show").executes(c -> run(c, (p, x) -> EarthLifeServices.get().time.describe(earthlife.mod.McCompat.dayTime(p.level())))))
                .then(Commands.literal("realtime").requires(earthlife.mod.McCompat::canChangeTime)
                    .executes(c -> run(c, (p, x) -> { EarthLifeServices.get().time.set(p.level().getServer(), GameClock.Mode.REALTIME, 1440); return "Clock now follows real time: 1 day = 1 day. Sleeping can't skip nights in this mode."; })))
                .then(Commands.literal("vanilla").requires(earthlife.mod.McCompat::canChangeTime)
                    .executes(c -> run(c, (p, x) -> { EarthLifeServices.get().time.set(p.level().getServer(), GameClock.Mode.VANILLA, 20); return "Vanilla day/night cycle (20 minutes)."; })))
                .then(Commands.literal("speed").requires(earthlife.mod.McCompat::canChangeTime)
                    .then(Commands.argument("minutesPerDay", DoubleArgumentType.doubleArg(1, 1440))
                        .executes(c -> run(c, (p, x) -> { double m = DoubleArgumentType.getDouble(x, "minutesPerDay");
                            EarthLifeServices.get().time.set(p.level().getServer(), GameClock.Mode.SCALED, m);
                            return String.format("1 game day now lasts %.0f real minutes%s.", m, m >= 1440 ? " (real-length days)" : ""); })))))
            .then(Commands.literal("job")
                .then(Commands.literal("list").executes(c -> run(c, (p, x) -> {
                    StringBuilder sb = new StringBuilder("Jobs:");
                    for (JobType j : JobType.values()) sb.append(String.format("%n  %s (%s) %s · %d LC/h + %d LC/task%s", j.name().toLowerCase(), j.title,
                            j.hasShifts() ? String.format("%02d:00-%02d:00", j.shiftStart, j.shiftEnd) : "own hours", j.perHour, j.perTask, j.requirement == null ? "" : " · needs " + j.requirement));
                    return sb.append("\nEmployers: an airport code (KROC), a town name, or the store you're standing in.").toString(); })))
                .then(Commands.literal("apply").then(Commands.argument("job", StringArgumentType.word()).then(Commands.argument("employer", StringArgumentType.greedyString())
                    .executes(c -> run(c, (p, x) -> {
                        JobType j = JobType.valueOf(StringArgumentType.getString(x, "job").toUpperCase(Locale.ROOT));
                        String employer = StringArgumentType.getString(x, "employer").trim();
                        var s = EarthLifeServices.get();
                        if (j.workplace.equals("airport") && !s.airportLayouts.containsKey(employer.toUpperCase(Locale.ROOT))) return "Airport jobs need an airport code: " + s.airportLayouts.keySet();
                        if (j.workplace.equals("airport")) employer = employer.toUpperCase(Locale.ROOT);
                        var e = s.jobs.hire(p.getStringUUID(), j, employer, GameClock.day(earthlife.mod.McCompat.dayTime(p.level())));
                        s.life.complete(p, "get_job");
                        return "Hired! You're now a " + e.job().title + " at " + e.employer() + ". Clock in with /earthlife job clockin during your shift."; })))))
                .then(Commands.literal("quit").executes(c -> run(c, (p, x) -> EarthLifeServices.get().jobs.quit(p.getStringUUID(), earthlife.mod.McCompat.dayTime(p.level()), p.level().getGameTime())
                        .map(ps -> JobHooks.payslip(ps) + " You quit.").orElse("You quit your job."))))
                .then(Commands.literal("clockin").executes(c -> run(c, (p, x) -> JobHooks.clockIn(p))))
                .then(Commands.literal("clockout").executes(c -> run(c, (p, x) -> JobHooks.payslip(EarthLifeServices.get().jobs.clockOut(p.getStringUUID(), earthlife.mod.McCompat.dayTime(p.level()), p.level().getGameTime())))))
                .then(Commands.literal("status").executes(c -> run(c, (p, x) -> {
                    var s = EarthLifeServices.get();
                    return s.jobs.employment(p.getStringUUID()).map(e -> e.job().title + " at " + e.employer() + s.jobs.shift(p.getStringUUID())
                            .map(sh -> " · on shift, " + sh.tasks().size() + " tasks done").orElse(" · off duty") + " · licences " + s.jobs.licences(p.getStringUUID())).orElse("Unemployed. /earthlife job list"); })))
                .then(Commands.literal("tasks").executes(c -> run(c, (p, x) -> { var t = JobHooks.taskList(p); return t.isEmpty() ? "No open tasks." : String.join("\n", t); }))))
            .then(Commands.literal("license")
                .then(Commands.literal("driver").executes(c -> run(c, (p, x) -> JobHooks.startDrivingTest(p))))
                .then(Commands.literal("pilot").executes(c -> run(c, (p, x) -> JobHooks.flightSchool(p)))))
            .then(Commands.literal("pilot")
                .then(Commands.literal("board").executes(c -> run(c, (p, x) -> {
                    var s = EarthLifeServices.get();
                    var e = s.jobs.employment(p.getStringUUID()).filter(j -> j.job() == JobType.PILOT).orElseThrow(() -> new JobService.JobException("Only pilots can take the captain's seat."));
                    if (s.jobs.shift(p.getStringUUID()).isEmpty()) throw new JobService.JobException("Clock in first.");
                    var plane = EarthLifeMod.FLIGHTS.boardingAt(e.employer()).orElseThrow(() -> new JobService.JobException("No aircraft boarding at " + e.employer() + " right now. Check /earthlife flights."));
                    return plane.board(p, true) ? null : "Couldn't board."; })))
                .then(Commands.argument("item", StringArgumentType.word()).executes(c -> run(c, (p, x) -> {
                    if (!(p.getVehicle() instanceof AirplaneEntity plane) || !p.getStringUUID().equals(plane.captain())) return "You're not the captain of an aircraft.";
                    var item = PilotChecklist.Item.valueOf(StringArgumentType.getString(x, "item").toUpperCase(Locale.ROOT));
                    if (plane.checklist().act(item, plane.sim().telemetry())) {
                        EarthLifeServices.get().jobs.recordTask(p.getStringUUID(), "checklist", plane.flight().id() + ":" + item);
                        return "✓ " + item.label + ". Next: " + plane.checklist().next().map(i -> i.label + " (" + i.when + ")").orElse("done");
                    }
                    return "Not now: " + item.label + " is done " + item.when + "."; }))))
            .then(Commands.literal("vehicle")
                .then(Commands.literal("shop").executes(c -> run(c, (p, x) -> {
                    StringBuilder sb = new StringBuilder("Vehicle dealer:");
                    for (VehicleType v : VehicleType.values()) if (v.priceLC > 0) sb.append(String.format("%n  %s - %d LC (%d seats, top speed %d mph)", v.name().toLowerCase(), v.priceLC, v.seats, Math.round(v.maxSpeed * 2.237)));
                    return sb.toString(); })))
                .then(Commands.literal("buy").then(Commands.argument("type", StringArgumentType.word()).then(Commands.argument("color", StringArgumentType.word())
                    .executes(c -> run(c, (p, x) -> EarthLifeMod.TRANSIT.buy(p, VehicleType.valueOf(StringArgumentType.getString(x, "type").toUpperCase(Locale.ROOT)),
                            StringArgumentType.getString(x, "color").toLowerCase(Locale.ROOT)))))))
                .then(Commands.literal("summon").then(Commands.argument("type", StringArgumentType.word())
                    .executes(c -> run(c, (p, x) -> EarthLifeMod.TRANSIT.summon(p, VehicleType.valueOf(StringArgumentType.getString(x, "type").toUpperCase(Locale.ROOT)))))))
                .then(Commands.literal("list").executes(c -> run(c, (p, x) -> "Your vehicles: " + EarthLifeMod.TRANSIT.owned(p)))))
            .then(Commands.literal("towns").executes(c -> run(c, (p, x) -> {
                var s = EarthLifeServices.get();
                var here = s.projection.fromBlock(p.getX(), p.getZ());
                StringBuilder sb = new StringBuilder("Towns in this region:");
                s.towns.stream().sorted(Comparator.comparingDouble(t -> t.centre().distanceTo(here))).forEach(t ->
                        sb.append(String.format("%n  %s (%s) - %.1f km %s", t.name(), t.kind().name().toLowerCase(), t.centre().distanceTo(here) / 1000, compass(here.bearingTo(t.centre())))));
                return sb.append("\nBus 1 links Williamson, Sodus, Ontario, Webster and Rochester (5 LC).").toString(); })))
            .then(Commands.literal("sell").executes(c -> run(c, (p, x) -> {
                var stack = p.getMainHandItem();
                if (stack.isEmpty()) return "Hold the crops you want to sell.";
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                long paid = EarthLifeServices.get().jobs.sell(p.getStringUUID(), id, stack.getCount(), p.getStringUUID() + "@" + p.level().getGameTime(), p.level().getGameTime());
                if (paid == 0) return "The market doesn't buy that. It buys: " + EarthLifeServices.get().jobs.cropPrices().keySet();
                stack.setCount(0);
                return "Sold for " + paid + " LC."; }))));
    }

    static String compass(double b) { return new String[]{"N", "NE", "E", "SE", "S", "SW", "W", "NW"}[(int) Math.round(b / 45) % 8]; }
}
