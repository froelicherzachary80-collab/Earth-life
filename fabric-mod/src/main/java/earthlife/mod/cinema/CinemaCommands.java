package earthlife.mod.cinema;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import earthlife.mod.EarthLifeServices;
import earthlife.mod.McCompat;
import earthlife.mod.EarthLifeMod;
import earthlife.mod.net.Net;
import earthlife.mod.net.Payloads;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * /earthlife cinema ... - the text side of the cinema screens (the kiosk menu buttons run these):
 * <pre>
 *   cinema                    showtimes + concessions of the nearest theater
 *   cinema pick &lt;aud&gt;          open the film picker for a free auditorium
 *   cinema start &lt;aud&gt; &lt;format&gt; &lt;source&gt; &lt;title...&gt;   start a film (sent by the film picker; buys a ticket)
 *   cinema join &lt;aud&gt;          buy a ticket for the film that is playing
 *   cinema pause|resume|stop &lt;aud&gt;, cinema seek &lt;aud&gt; &lt;seconds&gt;   (whoever started it, or an operator)
 *   cinema buy &lt;item&gt;          concessions (popcorn, cola, gummy_bears, ...)
 *   cinema sit &lt;aud&gt;           sit in the nearest free seat of that auditorium
 *   cinema 4d on|off|auto     operators: open the 4D auditoriums outside Oct 29 - Nov 3 (testing)
 * </pre>
 * Prices are LifeCoins, EarthLife's in-game currency (not Minecoins, not real money).
 */
public final class CinemaCommands {
    private CinemaCommands() {}

    interface Body { String run(ServerPlayer p, CommandContext<CommandSourceStack> c) throws Exception; }

    static int run(CommandContext<CommandSourceStack> c, Body b) {
        if (CinemaService.get() == null) { c.getSource().sendFailure(Component.literal("There are no cinemas in this world yet.")); return 0; }
        ServerPlayer p = c.getSource().getPlayer();
        if (p == null) { c.getSource().sendFailure(Component.literal("Players only.")); return 0; }
        try {
            String r = b.run(p, c);
            if (r != null) p.sendSystemMessage(Component.literal(r));
            return 1;
        } catch (earthlife.core.economy.Ledger.InsufficientFunds e) {
            c.getSource().sendFailure(Component.literal("Not enough LifeCoins: " + e.getMessage()));
            return 0;
        } catch (Exception e) {
            c.getSource().sendFailure(Component.literal("Cinema: " + e.getMessage()));
            return 0;
        }
    }

    static String aud(CommandContext<CommandSourceStack> c) { return StringArgumentType.getString(c, "aud"); }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("earthlife").then(Commands.literal("cinema")
            .executes(c -> run(c, (p, x) -> { CinemaService.openMenu(p, "tickets", p.blockPosition()); return null; }))
            .then(Commands.literal("pick").then(Commands.argument("aud", StringArgumentType.string()).executes(c -> run(c, (p, x) -> {
                var a = CinemaService.get().auds.get(aud(x));
                if (a == null) return "Unknown auditorium.";
                if (CinemaService.seasonal(a) && EarthLifeServices.running() && !CinemaService.fourDOpen(EarthLifeServices.get().time.clock().zone()))
                    return "The 4D motion auditorium " + CinemaService.fourDOpening(EarthLifeServices.get().time.clock().zone()) + ".";
                if (!Net.hasClientMod(p)) return "Choosing a film needs EarthLife on your client (it streams and plays the film on your computer).";
                if (a.zmf() && !earthlife.mod.zmf.ZmfEconomy.mayStartPrivateShowing(p)) return "Private showings are switched off on this server - buy a ticket for a scheduled screening instead.";
                Net.send(p, new Payloads.OpenScreen("cinema:pick:" + a.key() + ":" + (a.motion() ? "4d" : "std") + (a.has("3D") ? "3d" : "") + ":" + a.cinema().name() + " - " + a.name()));
                return null;
            }))))
            .then(Commands.literal("start").then(Commands.argument("aud", StringArgumentType.string()).then(Commands.argument("format", StringArgumentType.word())
                .then(Commands.argument("source", StringArgumentType.string()).then(Commands.argument("title", StringArgumentType.greedyString())
                    .executes(c -> run(c, (p, x) -> CinemaService.get().start(p, aud(x), StringArgumentType.getString(x, "format").toUpperCase(java.util.Locale.ROOT),
                            StringArgumentType.getString(x, "source"), StringArgumentType.getString(x, "title")))))))))
            .then(Commands.literal("join").then(Commands.argument("aud", StringArgumentType.string()).executes(c -> run(c, (p, x) -> CinemaService.get().join(p, aud(x))))))
            .then(Commands.literal("pause").then(Commands.argument("aud", StringArgumentType.string()).executes(c -> run(c, (p, x) -> CinemaService.get().control(p, aud(x), "pause")))))
            .then(Commands.literal("resume").then(Commands.argument("aud", StringArgumentType.string()).executes(c -> run(c, (p, x) -> CinemaService.get().control(p, aud(x), "resume")))))
            .then(Commands.literal("stop").then(Commands.argument("aud", StringArgumentType.string()).executes(c -> run(c, (p, x) -> CinemaService.get().control(p, aud(x), "stop")))))
            .then(Commands.literal("seek").then(Commands.argument("aud", StringArgumentType.string()).then(Commands.argument("seconds", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                .executes(c -> run(c, (p, x) -> CinemaService.get().control(p, aud(x), "seek:" + com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(x, "seconds")))))))
            .then(Commands.literal("buy").then(Commands.argument("item", StringArgumentType.word()).executes(c -> run(c, (p, x) -> CinemaService.get().buy(p, StringArgumentType.getString(x, "item"))))))
            .then(Commands.literal("sit").then(Commands.argument("aud", StringArgumentType.string()).executes(c -> run(c, (p, x) -> {
                var a = CinemaService.get().auds.get(aud(x));
                if (a == null) return "Unknown auditorium.";
                ServerLevel level = (ServerLevel) p.level();
                var seats = new java.util.ArrayList<>(a.seats());
                seats.sort(java.util.Comparator.comparingDouble(s -> Math.abs(s.getX() + 0.5 - (a.sx() + a.nx() * 12)) + Math.abs(s.getZ() + 0.5 - (a.sz() + a.nz() * 12))));
                for (var s : seats) {
                    level.getChunk(s);
                    if (SeatBlock.sit(level, s, level.getBlockState(s), p)) return "You sit down in " + a.name() + ".";
                }
                return "No free seat.";
            }))))
            .then(Commands.literal("4d").requires(McCompat::isOp).then(Commands.argument("state", StringArgumentType.word()).executes(c -> run(c, (p, x) -> {
                String s = StringArgumentType.getString(x, "state");
                CinemaService.fourDOverride = s.equals("on") ? Boolean.TRUE : s.equals("off") ? Boolean.FALSE : null;
                return "4D auditoriums: " + (CinemaService.fourDOverride == null ? "automatic (open Oct 29 - Nov 3)" : CinemaService.fourDOverride ? "open" : "closed");
            }))))));
    }
}
