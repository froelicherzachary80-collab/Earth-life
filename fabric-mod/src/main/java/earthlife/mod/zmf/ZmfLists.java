package earthlife.mod.zmf;

import earthlife.core.cinema.MultiplexPlanner;
import earthlife.mod.McCompat;
import earthlife.mod.cinema.CinemaContent;
import earthlife.mod.cinema.CinemaService;
import earthlife.mod.net.Net;
import earthlife.mod.net.Payloads;
import earthlife.mod.net.Payloads.Row;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Server-built lists for the ZMF Cinema menu (B): showtimes, concessions and the admin panel. Buttons run /zmfcinema commands. */
public final class ZmfLists {
    private ZmfLists() {}

    public static boolean admin(ServerPlayer p) { return McCompat.isOp(p) || (p.level().getServer() != null && p.level().getServer().isSingleplayer()); }

    public static void send(ServerPlayer p, String tab) {
        List<Row> rows = new ArrayList<>();
        var s = MultiplexService.get();
        String title = s == null ? "ZMF Cinema" : switch (tab) {
            case "zmf:snacks" -> snacks(p, rows);
            case "zmf:admin" -> admin(p) ? adminRows(s, p, rows) : "Admin (operators only)";
            default -> showtimes(s, p, rows);
        };
        Net.send(p, new Payloads.UiList(tab, title, rows));
    }

    static String hhmm(long ms, MultiplexService s) { return DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(ms).atZone(s.zone)); }
    static String mins(long ms) { long m = Math.max(0, ms / 60000); return m >= 60 ? (m / 60) + " h " + (m % 60) + " min" : m + " min"; }

    static String showtimes(MultiplexService s, ServerPlayer p, List<Row> rows) {
        var r = s.nearestBuilt(p.getX(), p.getZ(), 200);
        if (r == null) {
            rows.add(new Row("No multiplex here", s.locateText(p), "", ""));
            rows.add(new Row("Cinema Locator", "Hold it to see the way to the nearest multiplex", admin(p) ? "Get one" : "", admin(p) ? "zmfcinema locator" : ""));
            return "ZMF Cinema";
        }
        long now = System.currentTimeMillis();
        var sched = s.schedule(r, now);
        var cs = CinemaService.get();
        boolean priv = ZmfEconomy.mayStartPrivateShowing(p);
        for (var ri : sched.rooms()) {
            var ref = s.room(ri.key());
            String head = ri.number() + " · " + ri.formats() + (ri.browser().isEmpty() ? "" : " · Web " + ri.browser()) + ": " + ri.title() + (ri.year() > 0 ? " (" + ri.year() + ")" : "");
            String detail; String button = "", cmd = "";
            String price = cs.priceLabel(ref.aud(), "2D", p);
            switch (ri.status()) {
                case "playing" -> { detail = "Now playing · " + mins(now - ri.filmStartMs()) + " in" + (ri.genre().isEmpty() ? "" : " · " + ri.genre()); button = "Ticket (" + price + ")"; cmd = "zmfcinema ticket " + ri.key(); }
                case "trailers" -> { detail = "Starts " + hhmm(ri.filmStartMs(), s) + " (in " + mins(ri.filmStartMs() - now) + ") · pre-show running"; button = "Ticket (" + price + ")"; cmd = "zmfcinema ticket " + ri.key(); }
                case "next" -> { detail = ri.nextStartMs() > 0 ? "Next show " + hhmm(ri.nextStartMs(), s) + " (in " + mins(ri.nextStartMs() - now) + ")" + (ri.genre().isEmpty() ? "" : " · " + ri.genre()) : "No more shows today";
                    if (ri.nextStartMs() > 0) { button = "Ticket (" + price + ")"; cmd = "zmfcinema ticket " + ri.key(); } }
                case "web" -> { detail = "Web cinema · walk in and press B to control the browser"; button = "Ticket (" + price + ")"; cmd = "zmfcinema ticket " + ri.key(); }
                default -> detail = "Coming Soon - share your film library (B > Settings > Library) to fill the screens";
            }
            rows.add(new Row(head, detail, button, cmd));
        }
        if (priv) for (var ri : sched.rooms()) rows.add(new Row("Private showing · room " + ri.number(), "Pick any film from your own library and start it now", "Choose film", "earthlife cinema pick " + ri.key()));
        return r.name + " · " + r.rooms + " screens · " + s.plan(r).style().label;
    }

    static String snacks(ServerPlayer p, List<Row> rows) {
        for (var e : CinemaContent.PRICES.entrySet()) {
            var it = CinemaContent.byId(e.getKey());
            if (it.isEmpty()) continue;
            rows.add(new Row(new ItemStack(it.get()).getHoverName().getString(), "Concessions", ZmfEconomy.label(ZmfConfig.get().snackEmeralds, p), "zmfcinema snack " + e.getKey()));
        }
        return "Concessions";
    }

    static String adminRows(MultiplexService s, ServerPlayer p, List<Row> rows) {
        rows.add(new Row("Generate a 12-screen multiplex", "In front of you (needs some free space)", "Small", "zmfcinema generate small"));
        rows.add(new Row("Generate a 20-screen multiplex", "", "Large", "zmfcinema generate large"));
        rows.add(new Row("Generate a 32-screen mega multiplex", "Two floors", "Mega", "zmfcinema generate mega"));
        rows.add(new Row("Locate the nearest multiplex", s.locateText(p), "Teleport", "zmfcinema teleport nearest"));
        int cat = s.catalog().size();
        rows.add(new Row("Film catalog: " + cat + " titles (" + ZmfConfig.get().minYear + "+)", cat == 0 ? "Share your Jellyfin library: B > Settings > Library > Share" : "From " + s.catalogBy.size() + " player(s)' libraries", "", ""));
        var cfg = ZmfConfig.get();
        rows.add(new Row("Natural generation: " + (s.natural ? cfg.rarity : "off"), "Tickets: " + (cfg.ticketsRequired ? "scanned at the door" : "not required") + " · 2D " + cfg.ticketEmeralds + ", premium " + cfg.premiumEmeralds + " emeralds · config/zmf-cinema.json", "", ""));
        if (!s.lastBuildReport.isEmpty()) rows.add(new Row("Last build", s.lastBuildReport, "", ""));
        var r = s.nearestBuilt(p.getX(), p.getZ(), 300);
        if (r != null) {
            rows.add(new Row(r.name + " (" + r.id + ")", r.rooms + " rooms · " + s.plan(r).style().label + " · " + r.x + " " + r.y + " " + r.z, "New program", "zmfcinema reschedule " + r.id));
            long now = System.currentTimeMillis();
            for (var ri : s.schedule(r, now).rooms()) {
                var ref = s.room(ri.key());
                var sh = CinemaService.get().effective(ri.key());
                String src = sh == null ? "-" : sh.source().startsWith("web:") ? "browser" : sh.source().startsWith("jellyfin:") ? "Jellyfin" : sh.source().startsWith("file:") ? "file" : "link";
                String detail = ri.formats() + " · " + (ri.year() > 0 ? ri.year() + " · " : "") + "source " + src + " · " + ri.status()
                        + (sh == null ? "" : " · " + mins(now - sh.startedAtMs()) + " in · " + sh.format());
                boolean browserRoom = ref.room().formats().browser() != '-';
                String button = browserRoom ? (ri.status().equals("web") ? "Back to film" : "Web mode") : "Change film";
                String cmd = browserRoom ? (ri.status().equals("web") ? "zmfcinema room " + ri.key() + " jellyfin" : "zmfcinema room " + ri.key() + " browser https://www.youtube.com")
                        : "earthlife cinema pick " + ri.key();
                rows.add(new Row(ri.number() + " · " + ri.title(), detail, button, cmd));
            }
        }
        for (var rec : s.all()) {
            if (rec == r || rec.state != MultiplexRecord.State.BUILT) continue;
            rows.add(new Row(rec.name + " (" + rec.id + ")", rec.rooms + " screens · " + Math.round(Math.hypot(rec.x - p.getX(), rec.z - p.getZ())) + " blocks away", "Teleport", "zmfcinema tp " + rec.id));
        }
        return "ZMF Cinema admin";
    }

    static MultiplexPlanner.Style style(String s) {
        for (var st : MultiplexPlanner.Style.values()) if (st.name().equalsIgnoreCase(s) || st.label.equalsIgnoreCase(s.replace('_', ' '))) return st;
        return null;
    }
}
