package earthlife.mod.zmf;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import earthlife.core.cinema.MultiplexPlanner;
import earthlife.mod.McCompat;
import earthlife.mod.cinema.CinemaContent;
import earthlife.mod.cinema.CinemaService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

/**
 * /zmfcinema - ZMF Cinema commands:
 * <pre>
 *   zmfcinema                          showtimes of the nearest multiplex (also key B)
 *   zmfcinema locate nearest           direction and distance to the nearest multiplex
 *   zmfcinema generate small|large|mega|&lt;12-32&gt; [style]   build one in front of you   (operators / singleplayer)
 *   zmfcinema teleport nearest         go to the nearest multiplex's doors                (operators / singleplayer)
 *   zmfcinema tp &lt;id&gt;                   go to a multiplex                                  (operators / singleplayer)
 *   zmfcinema admin                    admin panel                                        (operators / singleplayer)
 *   zmfcinema ticket &lt;room&gt;            buy a ticket for the current or next screening
 *   zmfcinema snack &lt;item&gt;             concessions
 *   zmfcinema room &lt;room&gt; browser &lt;url&gt; | jellyfin   web-cinema rooms: show a web page / go back to the schedule
 *   zmfcinema reschedule &lt;id&gt;           new film program for a multiplex                  (operators / singleplayer)
 *   zmfcinema locator                  a Cinema Locator                                   (operators / singleplayer)
 *   zmfcinema info                     status and diagnostics
 * </pre>
 */
public final class ZmfCommands {
    private ZmfCommands() {}

    interface Body { String run(ServerPlayer p, MultiplexService s, CommandContext<CommandSourceStack> c) throws Exception; }

    static int run(CommandContext<CommandSourceStack> c, Body b) {
        var s = MultiplexService.get();
        if (s == null) { c.getSource().sendFailure(Component.literal("ZMF Cinema isn't running in this world.")); return 0; }
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null) { c.getSource().sendFailure(Component.literal("Players only.")); return 0; }
        try {
            String r = b.run(p, s, c);
            if (r != null) p.sendSystemMessage(Component.literal(r));
            return 1;
        } catch (Exception e) {
            c.getSource().sendFailure(Component.literal("ZMF Cinema: " + e.getMessage()));
            return 0;
        }
    }

    static boolean admin(CommandSourceStack src) { return McCompat.canChangeTime(src); }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("zmfcinema")
            .executes(c -> run(c, (p, s, x) -> { s.openHub(p, "tickets"); return null; }))
            .then(Commands.literal("locate").then(Commands.literal("nearest").executes(c -> run(c, (p, s, x) -> s.locateText(p)))))
            .then(Commands.literal("generate").requires(ZmfCommands::admin)
                .then(Commands.argument("size", StringArgumentType.word())
                    .executes(c -> run(c, (p, s, x) -> generate(p, s, StringArgumentType.getString(x, "size"), null)))
                    .then(Commands.argument("style", StringArgumentType.word())
                        .executes(c -> run(c, (p, s, x) -> generate(p, s, StringArgumentType.getString(x, "size"), StringArgumentType.getString(x, "style")))))))
            .then(Commands.literal("teleport").requires(ZmfCommands::admin).then(Commands.literal("nearest").executes(c -> run(c, (p, s, x) -> {
                var r = s.nearest(p.getX(), p.getZ(), true);
                if (r == null) return s.locateText(p);
                s.forceBuild(r);
                return tp(p, s, r);
            }))))
            .then(Commands.literal("tp").requires(ZmfCommands::admin).then(Commands.argument("id", StringArgumentType.word()).executes(c -> run(c, (p, s, x) -> {
                var r = s.record(StringArgumentType.getString(x, "id"));
                return r == null ? "No multiplex with that id." : tp(p, s, r);
            }))))
            .then(Commands.literal("admin").requires(ZmfCommands::admin).executes(c -> run(c, (p, s, x) -> {
                ZmfLists.send(p, "zmf:admin");
                earthlife.mod.net.Net.send(p, new earthlife.mod.net.Payloads.OpenScreen("zmf:admin"));
                return null;
            })))
            .then(Commands.literal("ui").then(Commands.argument("tab", StringArgumentType.word()).executes(c -> run(c, (p, s, x) -> {
                ZmfLists.send(p, "zmf:" + StringArgumentType.getString(x, "tab")); return null;
            }))))
            .then(Commands.literal("ticket").then(Commands.argument("room", StringArgumentType.string()).executes(c -> run(c, (p, s, x) -> s.buyTicket(p, StringArgumentType.getString(x, "room"))))))
            .then(Commands.literal("snack").then(Commands.argument("item", StringArgumentType.word()).executes(c -> run(c, (p, s, x) -> CinemaService.get().buy(p, StringArgumentType.getString(x, "item"))))))
            .then(Commands.literal("room").then(Commands.argument("room", StringArgumentType.string())
                .then(Commands.literal("jellyfin").executes(c -> run(c, (p, s, x) -> room(p, s, StringArgumentType.getString(x, "room"), null))))
                .then(Commands.literal("browser").then(Commands.argument("url", StringArgumentType.greedyString())
                    .executes(c -> run(c, (p, s, x) -> room(p, s, StringArgumentType.getString(x, "room"), StringArgumentType.getString(x, "url"))))))))
            .then(Commands.literal("reschedule").requires(ZmfCommands::admin).then(Commands.argument("id", StringArgumentType.word()).executes(c -> run(c, (p, s, x) -> {
                var r = s.record(StringArgumentType.getString(x, "id"));
                return r == null ? "No multiplex with that id." : s.reschedule(r, null);
            }))))
            .then(Commands.literal("locator").requires(ZmfCommands::admin).executes(c -> run(c, (p, s, x) -> {
                p.addItem(new ItemStack(CinemaContent.CINEMA_LOCATOR));
                return "Hold the Cinema Locator to see the way to the nearest multiplex (sneak + use: a map).";
            })))
            .then(Commands.literal("info").executes(c -> run(c, (p, s, x) -> info(p, s)))));
    }

    static String generate(ServerPlayer p, MultiplexService s, String size, String style) {
        int n;
        try { n = MultiplexPlanner.roomsFor(size); } catch (NumberFormatException e) { return "Size: small (12), large (20), mega (32) or a number 12-32."; }
        if (n < 12 || n > 32) return "A multiplex has 12-32 screening rooms.";
        MultiplexPlanner.Style st = style == null ? null : ZmfLists.style(style);
        if (style != null && st == null) return "Styles: brick, glass_front, concrete_modern, futuristic, hollywood, mall, urban.";
        var r = s.generateAt(p, n, st, null);
        var plan = s.plan(r);
        return "🏗 Building " + r.name + " (" + n + " screens, " + plan.style().label + ", " + plan.lengthU() + " x " + (2 * plan.halfWidthV() + 1)
                + " blocks) in front of you - it takes a few seconds. Id " + r.id + ".";
    }

    static String tp(ServerPlayer p, MultiplexService s, MultiplexRecord r) {
        double[] w = s.placed(r).toWorld(-5, 0);
        p.teleportTo(w[0], r.y + 1, w[1]);
        return "Welcome to " + r.name + "!" + (r.state == MultiplexRecord.State.BUILT ? "" : " (It's being built as you arrive.)");
    }

    static String room(ServerPlayer p, MultiplexService s, String key, String url) {
        var ref = s.room(key);
        if (ref == null) return "Unknown room.";
        if (ref.room().formats().browser() == '-') return ref.room().name() + " is not a web cinema (rooms A-D are).";
        boolean inside = ref.aud().inside(p.getX(), p.getY(), p.getZ());
        if (!inside && !ZmfLists.admin(p)) return "Walk into the room to change what it shows.";
        if (url != null) {
            url = url.trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
            if (url.length() > 2000) return "That link is too long.";
        }
        return s.setBrowser(ref, url);
    }

    static String info(ServerPlayer p, MultiplexService s) {
        long built = s.all().stream().filter(r -> r.state == MultiplexRecord.State.BUILT).count();
        long planned = s.all().stream().filter(r -> r.state == MultiplexRecord.State.PLANNED).count();
        long rejected = s.all().stream().filter(r -> r.state == MultiplexRecord.State.REJECTED).count();
        return String.format(Locale.ROOT, "ZMF Cinema v0.1 · %d built, %d planned, %d sites unsuitable · natural generation %s · catalog %d titles · %s",
                built, planned, rejected, s.natural ? ZmfConfig.get().rarity : "off", s.catalog().size(), s.busy() ? "building..." : "idle");
    }
}
