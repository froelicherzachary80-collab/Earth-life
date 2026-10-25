package earthlife.mod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import earthlife.core.events.EventEngine;
import earthlife.mod.events.EventManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * V0.3 commands:
 *   /earthlife events            today + next 7 days, numbered (real local times)
 *   /earthlife events buy <n>    buy a ticket (sports 40 LC, concerts 60 LC) - be there during the event for a fan bonus
 *   /earthlife events status     which feeds work / last errors
 *   /earthlife landmarks         landmarks by distance, with verified/approximate flag
 */
public final class V03Commands {
    private V03Commands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("earthlife")
            .then(Commands.literal("events")
                .executes(c -> V02Commands.run(c, (p, x) -> {
                    var list = EarthLifeMod.EVENTS.today();
                    if (list.isEmpty()) return "No events in the region for the next 7 days.";
                    ZoneId z = EarthLifeServices.get().time.clock().zone();
                    DateTimeFormatter f = DateTimeFormatter.ofPattern("EEE MMM d h:mm a");
                    StringBuilder sb = new StringBuilder("Events (real local time):");
                    Instant now = Instant.now();
                    for (int i = 0; i < Math.min(25, list.size()); i++) {
                        EventEngine.Placement pl = list.get(i);
                        var e = pl.event();
                        String where = pl.landmarkId() != null ? EarthLifeServices.get().landmarks.get(pl.landmarkId()).landmark().name()
                                : pl.town() != null ? pl.town() : "region-wide";
                        long price = EventManager.price(e);
                        sb.append(String.format("%n %2d. %s%s · %s · %s%s", i + 1, e.activeAt(now) ? "§a[LIVE] §r" : "", e.title(), where,
                                e.start().atZone(z).format(f), price > 0 ? " · " + price + " LC" : ""));
                        if (e.score() != null && e.activeAt(now)) sb.append(String.format(" · %d-%d %s", e.score().awayRuns(), e.score().homeRuns(), e.score().detail()));
                    }
                    return sb.append("\nSources: holidays, MLB Stats API, Ticketmaster, iCal (see /earthlife events status).").toString(); }))
                .then(Commands.literal("buy").then(Commands.argument("n", IntegerArgumentType.integer(1))
                    .executes(c -> V02Commands.run(c, (p, x) -> {
                        var list = EarthLifeMod.EVENTS.today();
                        int n = IntegerArgumentType.getInteger(x, "n");
                        if (n > list.size()) return "No event #" + n + ".";
                        var e = list.get(n - 1).event();
                        long price = EventManager.price(e);
                        if (price == 0) return e.title() + " is free - just show up!";
                        var s = EarthLifeServices.get();
                        if (s.ledger.hasReference(p.getStringUUID(), "eventticket#" + e.id())) return "You already have a ticket for " + e.title() + ".";
                        s.ledger.debit(p.getStringUUID(), price, "EVENT_TICKET", "eventticket#" + e.id(), e.title(), p.level().getGameTime());
                        return "🎟 Ticket bought for " + e.title() + " (" + price + " LC). Be at the venue while it's on for a +20 LC fan bonus."; }))))
                .then(Commands.literal("status").executes(c -> V02Commands.run(c, (p, x) -> {
                    var errs = EarthLifeServices.get().events.errors();
                    return "Event feeds: " + EarthLifeServices.get().events.all().size() + " events loaded. "
                            + (errs.isEmpty() ? "No errors." : "Errors: " + String.join("; ", errs)); }))))
            .then(Commands.literal("landmarks").executes(c -> V02Commands.run(c, (p, x) -> {
                var s = EarthLifeServices.get();
                var here = s.projection.fromBlock(p.getX(), p.getZ());
                StringBuilder sb = new StringBuilder("Landmarks:");
                s.landmarks.values().stream().sorted(Comparator.comparingDouble(l -> l.landmark().pos().distanceTo(here))).forEach(l ->
                        sb.append(String.format("%n  %s (%s) - %.1f km %s%s", l.landmark().name(), l.landmark().type().name().toLowerCase(Locale.ROOT).replace('_', ' '),
                                l.landmark().pos().distanceTo(here) / 1000, V02Commands.compass(here.bearingTo(l.landmark().pos())),
                                l.landmark().verified() ? "" : " (approximate position)")));
                return sb.toString(); }))));
    }
}
