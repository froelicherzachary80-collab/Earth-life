package earthlife.mod.ui;

import earthlife.core.events.EventEngine;
import earthlife.core.jobs.JobType;
import earthlife.core.npc.Npc;
import earthlife.core.transport.VehicleType;
import earthlife.mod.EarthLifeMod;
import earthlife.mod.EarthLifeServices;
import earthlife.mod.McCompat;
import earthlife.mod.events.EventManager;
import earthlife.mod.events.Halloween;
import earthlife.mod.jobs.JobHooks;
import earthlife.mod.net.Net;
import earthlife.mod.net.Payloads;
import earthlife.mod.net.Payloads.Row;
import earthlife.mod.npc.NpcEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Server-built lists for the EarthLife menu tabs. Every button is an /earthlife command, so the server stays in charge. */
public final class UiLists {
    private UiLists() {}

    public static final List<String> TABS = List.of("life", "jobs", "wallet", "shop", "nearby", "events", "travel", "vehicles");

    public static void send(ServerPlayer p, String tab) {
        List<Row> rows = new ArrayList<>();
        String title = switch (tab) {
            case "jobs" -> jobs(p, rows);
            case "wallet" -> wallet(p, rows);
            case "nearby" -> nearby(p, rows);
            case "events" -> events(p, rows);
            case "travel" -> travel(p, rows);
            case "vehicles" -> vehicles(p, rows);
            case "shop" -> shop(p, rows);
            default -> life(p, rows);
        };
        Net.send(p, new Payloads.UiList(tab, title, rows));
    }

    static String life(ServerPlayer p, List<Row> rows) {
        var s = EarthLifeServices.get();
        var life = s.life.life(p);
        if (life.home != null) rows.add(new Row("Home", String.format("%.0f, %.0f", life.home.x(), life.home.z()), "Route", "earthlife map route home"));
        else rows.add(new Row("No home set", "Stand where you live and set it", "Set home here", "earthlife home set"));
        if (life.work != null) rows.add(new Row("Work", String.format("%.0f, %.0f", life.work.x(), life.work.z()), "Route", "earthlife map route work"));
        for (var w : life.waypoints) rows.add(new Row(w.name(), String.format("%s · %.0f, %.0f", w.kind(), w.x(), w.z()), "Remove", "earthlife waypoint remove " + w.name()));
        rows.add(new Row("Share my location on LifeMaps", life.shareLocation ? "On - others with EarthLife can see you" : "Off - nobody sees where you are",
                life.shareLocation ? "Turn off" : "Turn on", "earthlife map share " + (life.shareLocation ? "off" : "on")));
        return "Your life in " + s.settings.town();
    }

    static String jobs(ServerPlayer p, List<Row> rows) {
        var s = EarthLifeServices.get();
        String id = p.getStringUUID();
        var emp = s.jobs.employment(id);
        if (emp.isPresent()) {
            var e = emp.get();
            boolean on = s.jobs.shift(id).isPresent();
            String hours = e.job().hasShifts() ? String.format("%02d:00-%02d:00", e.job().shiftStart, e.job().shiftEnd) : "own hours";
            rows.add(new Row("You: " + e.job().title + " at " + e.employer(), hours + (on ? " · ON SHIFT" : " · off duty"), on ? "Clock out" : "Clock in",
                    on ? "earthlife job clockout" : "earthlife job clockin"));
            for (String t : JobHooks.taskList(p)) rows.add(new Row("Task", t, "", ""));
            rows.add(new Row("Workplace", "Mark it on your map", "Route", "earthlife map route work"));
            rows.add(new Row("Quit job", "You'll be paid for this shift so far", "Quit", "earthlife job quit"));
        }
        String town = s.towns.isEmpty() ? s.settings.town() : earthlife.core.world.Towns.nearest(s.towns, s.projection.fromBlock(p.getX(), p.getZ())).name();
        String airport = nearestAirport(p);
        Set<String> licences = s.jobs.licences(id);
        for (JobType j : JobType.values()) {
            String employer = j.workplace.equals("airport") ? airport : town;
            if (employer == null) continue;
            String hours = j.hasShifts() ? String.format("%02d:00-%02d:00", j.shiftStart, j.shiftEnd) : "own hours";
            String pay = j == JobType.FARMER ? "paid by selling crops" : j.perHour + " LC/h + " + j.perTask + " LC/task";
            boolean ok = j.requirement == null || licences.contains(j.requirement);
            rows.add(new Row(j.title + " · " + employer, hours + " · " + pay + (ok ? "" : " · needs " + j.requirement.replace("license:", "") + " licence"),
                    ok ? "Apply" : "Get licence", ok ? "earthlife job apply " + j.name().toLowerCase(Locale.ROOT) + " " + employer
                    : "earthlife license " + j.requirement.replace("license:", "")));
        }
        return "Jobs near " + town;
    }

    static String nearestAirport(ServerPlayer p) {
        var s = EarthLifeServices.get();
        return s.airportLayouts.entrySet().stream().min(Comparator.comparingDouble(e -> {
            double[] m = e.getValue().placed.marker("agent:checkin:1"); return Math.hypot(m[0] - p.getX(), m[2] - p.getZ()); })).map(Map.Entry::getKey).orElse(null);
    }

    static String wallet(ServerPlayer p, List<Row> rows) {
        var s = EarthLifeServices.get();
        String id = p.getStringUUID();
        if (s.rewards.hourlyAvailable(id) > 0) rows.add(new Row("Hourly reward ready", "+150 LC for active play", "Claim", "earthlife claim hourly"));
        if (s.rewards.dailyAvailable(id, System.currentTimeMillis())) rows.add(new Row("Daily reward ready", "+700 LC", "Claim", "earthlife claim daily"));
        rows.add(new Row("Weekly bonus", "Once per real week", "Claim", "earthlife claim weekly"));
        var hist = s.ledger.history(id);
        long earned = hist.stream().filter(e -> e.amount() > 0).mapToLong(e -> e.amount()).sum();
        long spent = -hist.stream().filter(e -> e.amount() < 0).mapToLong(e -> e.amount()).sum();
        long wages = hist.stream().filter(e -> "WAGE".equals(e.type())).mapToLong(e -> e.amount()).sum();
        long shifts = hist.stream().filter(e -> "WAGE".equals(e.type())).count();
        rows.add(new Row(String.format("Earned %,d LC · spent %,d LC", earned, spent), String.format("Job earnings %,d LC over %d paid shifts · %d transactions", wages, shifts, hist.size()), "", ""));
        for (int i = hist.size() - 1; i >= Math.max(0, hist.size() - 15); i--) {
            var e = hist.get(i);
            rows.add(new Row((e.amount() >= 0 ? "+" : "") + e.amount() + " LC · " + e.memo(), e.type().toLowerCase(Locale.ROOT) + " · balance " + e.balanceAfter() + " LC", "", ""));
        }
        return String.format("Wallet: %,d LifeCoins (fictional, no real-money value)", s.ledger.balance(id));
    }

    static String nearby(ServerPlayer p, List<Row> rows) {
        var s = EarthLifeServices.get();
        long time = McCompat.dayTime(p.level());
        var npcs = p.level().getEntitiesOfClass(NpcEntity.class, new AABB(p.getX() - 64, p.getY() - 32, p.getZ() - 64, p.getX() + 64, p.getY() + 32, p.getZ() + 64));
        npcs.sort(Comparator.comparingDouble(e -> e.distanceTo(p)));
        for (NpcEntity e : npcs.subList(0, Math.min(12, npcs.size()))) {
            Npc n = e.residentId() == null ? null : s.npc(e.residentId()).orElse(null);
            if (n == null) continue;
            String rel = s.social.level(p.getStringUUID(), n.id()).name().toLowerCase(Locale.ROOT).replace('_', ' ');
            rows.add(new Row(n.name(), String.format("%.0f m · %s · %s · %s", e.distanceTo(p), n.job().title, n.activityAt(time).name().toLowerCase(Locale.ROOT).replace('_', ' '), rel),
                    "Mark", String.format(Locale.ROOT, "earthlife waypoint set %s %.1f %.1f", n.firstName(), e.getX(), e.getZ())));
        }
        if (npcs.isEmpty()) rows.add(new Row("No residents within 64 blocks", s.settings.simulation() ? "Residents live in town - head toward a town centre" : "NPC simulation is off for this world", "", ""));
        var here = s.projection.fromBlock(p.getX(), p.getZ());
        s.towns.stream().sorted(Comparator.comparingDouble(t -> t.centre().distanceTo(here))).limit(4).forEach(t ->
                rows.add(new Row(t.name(), String.format("%s · %.1f km", t.kind().name().toLowerCase(Locale.ROOT), t.centre().distanceTo(here) / 1000), "Route",
                        "earthlife map route town " + t.name())));
        s.landmarks.values().stream().sorted(Comparator.comparingDouble(l -> l.landmark().pos().distanceTo(here))).limit(4).forEach(l ->
                rows.add(new Row(l.landmark().name(), String.format("landmark · %.1f km%s", l.landmark().pos().distanceTo(here) / 1000, l.landmark().verified() ? "" : " · approximate"), "Route",
                        "earthlife map route landmark " + l.landmark().id())));
        String ap = nearestAirport(p);
        if (ap != null) rows.add(new Row(ap + " " + s.airports.get(ap).name(), "airport", "Route", "earthlife map route airport " + ap));
        return "Nearby · " + s.life.placeName(here);
    }

    static String events(ServerPlayer p, List<Row> rows) {
        var s = EarthLifeServices.get();
        ZoneId z = s.time.clock().zone();
        var life = s.life.life(p);
        if (Halloween.isSeason(z)) {
            int year = Halloween.today(z).getYear();
            rows.add(new Row(Halloween.countdown(z), "Candy collected: " + life.candy + " · trick-or-treat residents in the evening (talk → Trick or treat!)", "", ""));
            rows.add(new Row("Costume contest (EarthLife activity)", life.contests.contains(year + ":costume") ? "Entered ✔" : "Evening, at a town centre · your costume scores " + Halloween.costumeScore(p),
                    life.contests.contains(year + ":costume") ? "" : "Enter", "earthlife event costume"));
            var hh = EarthLifeMod.HAUNTED.location();
            if (hh.isPresent()) rows.add(new Row("Haunted house in " + EarthLifeMod.HAUNTED.town() + " (EarthLife activity)",
                    "Find 3 hidden jack o'lanterns tonight · found " + EarthLifeMod.HAUNTED.progress(p), "Route",
                    String.format(Locale.ROOT, "earthlife map route xz %.1f %.1f", hh.get()[0], hh.get()[1])));
            else rows.add(new Row("Haunted house (EarthLife activity)", "Opens in the evening (17:00-23:00) near a town centre", "", ""));
            rows.add(new Row("Pumpkin-carving contest (EarthLife activity)", life.contests.contains(year + ":carving") ? "Entered ✔" : "Place carved pumpkins within 12 blocks of your home",
                    life.contests.contains(year + ":carving") ? "" : "Judge my home", "earthlife event carving"));
        } else {
            String c = Halloween.countdown(z);
            if (!c.isEmpty()) rows.add(new Row(c, "", "", ""));
        }
        var list = EarthLifeMod.EVENTS.today();
        DateTimeFormatter f = DateTimeFormatter.ofPattern("EEE MMM d h:mm a");
        Instant now = Instant.now();
        for (int i = 0; i < Math.min(20, list.size()); i++) {
            EventEngine.Placement pl = list.get(i);
            var e = pl.event();
            String where = pl.landmarkId() != null && s.landmarks.containsKey(pl.landmarkId()) ? s.landmarks.get(pl.landmarkId()).landmark().name()
                    : pl.town() != null ? pl.town() : "region-wide";
            long price = EventManager.price(e);
            boolean has = s.ledger.hasReference(p.getStringUUID(), "eventticket#" + e.id());
            rows.add(new Row((e.activeAt(now) ? "[LIVE] " : "") + e.title(), where + " · " + e.start().atZone(z).format(f) + (price > 0 ? " · " + price + " LC" : " · free") + " · " + sourceLabel(e.source()),
                    price > 0 && !has ? "Buy ticket" : (Double.isNaN(pl.lat()) ? "" : "Route"),
                    price > 0 && !has ? "earthlife events buy " + (i + 1) : (Double.isNaN(pl.lat()) ? "" : String.format(Locale.ROOT, "earthlife map route geo %.5f %.5f", pl.lat(), pl.lon()))));
        }
        if (list.isEmpty()) rows.add(new Row("No other events found for the next 7 days", "Holidays work offline; sports/concert feeds need internet", "", ""));
        return "Events";
    }

    /** Real-world feeds vs calendar-computed holidays vs EarthLife's own (fictional) activities. */
    static String sourceLabel(String src) {
        if (src == null) return "EarthLife";
        String s = src.toLowerCase(Locale.ROOT);
        if (s.contains("holiday")) return "calendar holiday";
        if (s.contains("mlb")) return "real game (MLB Stats API)";
        if (s.contains("ticketmaster")) return "real event (Ticketmaster)";
        if (s.contains("ics") || s.contains("ical")) return "real event (calendar feed)";
        return src;
    }

    static String travel(ServerPlayer p, List<Row> rows) {
        var s = EarthLifeServices.get();
        long day = McCompat.dayTime(p.level()) / 24000;
        int n = 0;
        for (var f : s.booking.search(null, null)) {
            if (f.departureGameDay() < day || n++ >= 14) continue;
            long dep = f.departureTimeTicks();
            String time = String.format("%02d:%02d", (dep / 1000 + 6) % 24, dep % 1000 * 60 / 1000);
            boolean booked = s.booking.verify(f.id(), p.getStringUUID()).isPresent();
            rows.add(new Row(f.number() + "  " + f.from().icao() + " → " + f.to().icao(), String.format("day %d %s · %d LC · %d seats · %.0f km", f.departureGameDay(), time,
                    s.booking.priceFor(f), s.booking.availableSeats(f).size(), f.distanceKm()), booked ? "Booked ✔" : "Book", booked ? "" : "earthlife book " + f.id() + " auto"));
        }
        if (n == 0) rows.add(new Row("No flights scheduled", s.settings.transport() ? "Flights start at airports with terminals" : "Transport is off for this world", "", ""));
        return "Flights";
    }

    static String shop(ServerPlayer p, List<Row> rows) {
        var s = EarthLifeServices.get();
        var here = s.shops.at(p.getX(), p.getZ());
        if (here.isEmpty()) {
            var n = s.shops.nearest(p.getX(), p.getZ());
            if (n.isEmpty()) { rows.add(new Row("No stores in this area", "", "", "")); return "Shop"; }
            var st = n.get();
            rows.add(new Row("Walk into a store to shop", String.format("Nearest: %s (%s), %.0f m away", st.name(), st.type(), Math.hypot(st.x() - p.getX(), st.z() - p.getZ())),
                    "Route", String.format(Locale.ROOT, "earthlife map route xz %.1f %.1f", st.x(), st.z())));
            return "Shop";
        }
        var st = here.get();
        for (var w : earthlife.mod.life.ShopService.wares(st))
            rows.add(new Row(w.label(), w.price() + " LC", "Buy", "earthlife buy " + w.id()));
        return st.name() + " (" + st.type() + ") · balance " + s.ledger.balance(p.getStringUUID()) + " LC";
    }

    static String vehicles(ServerPlayer p, List<Row> rows) {
        for (String v : EarthLifeMod.TRANSIT.owned(p)) {
            String type = v.contains(":") ? v.substring(0, v.indexOf(':')) : v;
            rows.add(new Row("Your " + v.replace(':', ' '), "Owned", "Summon", "earthlife vehicle summon " + type.toLowerCase(Locale.ROOT)));
        }
        for (VehicleType t : VehicleType.values()) if (t.priceLC > 0)
            rows.add(new Row(t.label, String.format("%,d LC · %d seats · %d mph", t.priceLC, t.seats, Math.round(t.maxSpeed * 2.237)), "Buy (red)",
                    "earthlife vehicle buy " + t.name().toLowerCase(Locale.ROOT) + " red"));
        return "Vehicles";
    }
}
