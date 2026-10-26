package earthlife.mod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import earthlife.core.geo.GeoPoint;
import earthlife.core.world.TileData;
import earthlife.core.world.TileKey;
import earthlife.core.world.Towns;
import earthlife.mod.events.Halloween;
import earthlife.mod.life.PlayerLife;
import earthlife.mod.net.Net;
import earthlife.mod.net.Payloads;
import earthlife.mod.npc.DialogueService;
import earthlife.mod.ui.UiLists;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;

import java.nio.file.Files;
import java.util.*;

/**
 * V0.4 commands. Everything a player needs is also on the keybind screens (M map, K menu, J jobs, L wallet, N nearby);
 * these are the text equivalents plus operator tools. Admin commands require operator permission.
 */
public final class V04Commands {
    private V04Commands() {}

    static final String VERSION = "0.4.0";

    static int run(CommandContext<CommandSourceStack> c, V02Commands.Body b) { return V02Commands.run(c, b); }

    static LiteralArgumentBuilder<CommandSourceStack> lit(String s) { return Commands.literal(s); }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(lit("earthlife")
            .executes(c -> { c.getSource().sendSystemMessage(Component.literal(help())); return 1; })
            .then(lit("help").executes(c -> { c.getSource().sendSystemMessage(Component.literal(help())); return 1; }))
            .then(lit("version").executes(c -> { c.getSource().sendSystemMessage(Component.literal("EarthLife " + VERSION + " for Minecraft 26.3 (Fabric)")); return 1; }))
            .then(lit("map")
                .executes(c -> run(c, (p, x) -> { open(p, "map"); return Net.hasClientMod(p) ? null : "The LifeMap needs EarthLife installed on your client."; }))
                .then(lit("share").then(Commands.argument("state", StringArgumentType.word()).executes(c -> run(c, (p, x) -> {
                    var s = EarthLifeServices.get();
                    PlayerLife life = s.life.life(p);
                    life.shareLocation = StringArgumentType.getString(x, "state").equalsIgnoreCase("on");
                    life.markDirty();
                    if (life.shareLocation && !s.allowLocationSharing) return "This server has location sharing turned off; nobody will see you.";
                    return life.shareLocation ? "Your position is now shown on other EarthLife players' maps. /earthlife map share off to stop."
                            : "Location sharing off: other players can't see where you are.";
                }))))
                .then(lit("route")
                    .then(lit("home").executes(c -> run(c, (p, x) -> route(p, wp(p, "home")))))
                    .then(lit("work").executes(c -> run(c, (p, x) -> route(p, wp(p, "work")))))
                    .then(lit("clear").executes(c -> run(c, (p, x) -> { open(p, "map:clear"); return "Route cleared."; })))
                    .then(lit("waypoint").then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> run(c, (p, x) ->
                            route(p, EarthLifeServices.get().life.life(p).waypoint(StringArgumentType.getString(x, "name")).map(w -> new double[]{w.x(), w.z()}).orElse(null), StringArgumentType.getString(x, "name"))))))
                    .then(lit("town").then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> run(c, (p, x) -> {
                        var s = EarthLifeServices.get();
                        var t = Towns.find(s.towns, StringArgumentType.getString(x, "name")).orElse(null);
                        if (t == null) return "No town called " + StringArgumentType.getString(x, "name") + ". /earthlife towns";
                        var b = s.projection.toBlock(t.centre().lat(), t.centre().lon());
                        return route(p, new double[]{b.x(), b.z()}, t.name()); }))))
                    .then(lit("landmark").then(Commands.argument("id", StringArgumentType.greedyString()).executes(c -> run(c, (p, x) -> {
                        var s = EarthLifeServices.get();
                        var l = s.landmarks.get(StringArgumentType.getString(x, "id"));
                        if (l == null) return "Unknown landmark.";
                        var b = s.projection.toBlock(l.landmark().pos().lat(), l.landmark().pos().lon());
                        return route(p, new double[]{b.x(), b.z()}, l.landmark().name()); }))))
                    .then(lit("airport").then(Commands.argument("icao", StringArgumentType.word()).executes(c -> run(c, (p, x) -> {
                        var l = EarthLifeServices.get().airportLayouts.get(StringArgumentType.getString(x, "icao").toUpperCase(Locale.ROOT));
                        if (l == null) return "Unknown airport.";
                        double[] m = l.placed.marker("agent:checkin:1");
                        return route(p, new double[]{m[0], m[2]}, StringArgumentType.getString(x, "icao").toUpperCase(Locale.ROOT)); }))))
                    .then(lit("geo").then(Commands.argument("lat", DoubleArgumentType.doubleArg(-90, 90)).then(Commands.argument("lon", DoubleArgumentType.doubleArg(-180, 180))
                        .executes(c -> run(c, (p, x) -> {
                            var b = EarthLifeServices.get().projection.toBlock(DoubleArgumentType.getDouble(x, "lat"), DoubleArgumentType.getDouble(x, "lon"));
                            return route(p, new double[]{b.x(), b.z()}, String.format("%.4f, %.4f", DoubleArgumentType.getDouble(x, "lat"), DoubleArgumentType.getDouble(x, "lon"))); })))))
                    .then(lit("xz").then(Commands.argument("x", DoubleArgumentType.doubleArg()).then(Commands.argument("z", DoubleArgumentType.doubleArg())
                        .executes(c -> run(c, (p, x) -> route(p, new double[]{DoubleArgumentType.getDouble(x, "x"), DoubleArgumentType.getDouble(x, "z")}, "map point")))))))
            )
            .then(lit("location").executes(c -> run(c, (p, x) -> location(p))))
            .then(lit("coordinates").executes(c -> run(c, (p, x) -> location(p))))
            .then(lit("town").then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> run(c, (p, x) -> {
                var s = EarthLifeServices.get();
                var t = Towns.find(s.towns, StringArgumentType.getString(x, "name")).orElse(null);
                if (t == null) return "No town called " + StringArgumentType.getString(x, "name") + ". /earthlife towns";
                var here = s.projection.fromBlock(p.getX(), p.getZ());
                var b = s.projection.toBlock(t.centre().lat(), t.centre().lon());
                return String.format("%s (%s%s) · %.5f, %.5f · block %.0f, %.0f · %.1f km %s of you. Route: /earthlife map route town %s", t.name(),
                        t.kind().name().toLowerCase(Locale.ROOT), t.population() > 0 ? ", pop. " + t.population() : "", t.centre().lat(), t.centre().lon(), b.x(), b.z(),
                        t.centre().distanceTo(here) / 1000, V02Commands.compass(here.bearingTo(t.centre())), t.name()); }))))
            .then(lit("jobs").executes(c -> run(c, (p, x) -> { UiLists.send(p, "jobs"); open(p, "menu:jobs"); return Net.hasClientMod(p) ? null : "Jobs: /earthlife job list"; })))
            .then(lit("vehicles").executes(c -> run(c, (p, x) -> { UiLists.send(p, "vehicles"); open(p, "menu:vehicles");
                return "Your vehicles: " + EarthLifeMod.TRANSIT.owned(p) + " · shop: /earthlife vehicle shop"; })))
            .then(lit("time").executes(c -> run(c, (p, x) -> EarthLifeServices.get().time.describe(McCompat.dayTime(p.level())))))
            .then(lit("data").then(lit("status").executes(c -> run(c, (p, x) -> dataStatus(p)))))
            .then(lit("world").then(lit("info").executes(c -> run(c, (p, x) -> worldInfo()))))
            .then(lit("waypoint")
                .then(lit("add").then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> run(c, (p, x) ->
                        addWaypoint(p, StringArgumentType.getString(x, "name"), p.getX(), p.getY(), p.getZ())))))
                .then(lit("set").then(Commands.argument("name", StringArgumentType.word()).then(Commands.argument("x", DoubleArgumentType.doubleArg()).then(Commands.argument("z", DoubleArgumentType.doubleArg())
                        .executes(c -> run(c, (p, x) -> addWaypoint(p, StringArgumentType.getString(x, "name"), DoubleArgumentType.getDouble(x, "x"), p.getY(), DoubleArgumentType.getDouble(x, "z"))))))))
                .then(lit("remove").then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> run(c, (p, x) -> {
                    PlayerLife life = EarthLifeServices.get().life.life(p);
                    String n = StringArgumentType.getString(x, "name");
                    boolean removed = life.waypoints.removeIf(w -> w.name().equalsIgnoreCase(n));
                    life.markDirty();
                    return removed ? "Removed waypoint " + n + "." : "No waypoint called " + n + "."; }))))
                .then(lit("list").executes(c -> run(c, (p, x) -> {
                    PlayerLife life = EarthLifeServices.get().life.life(p);
                    StringBuilder sb = new StringBuilder("Waypoints:");
                    if (life.home != null) sb.append(String.format("%n  home %.0f, %.0f", life.home.x(), life.home.z()));
                    if (life.work != null) sb.append(String.format("%n  work %.0f, %.0f", life.work.x(), life.work.z()));
                    for (var w : life.waypoints) sb.append(String.format("%n  %s %.0f, %.0f (%.0f m)", w.name(), w.x(), w.z(), Math.hypot(w.x() - p.getX(), w.z() - p.getZ())));
                    return sb.toString(); }))))
            .then(lit("home").then(lit("set").executes(c -> run(c, (p, x) -> {
                PlayerLife life = EarthLifeServices.get().life.life(p);
                life.home = new PlayerLife.Waypoint("Home", "home", p.getX(), p.getY(), p.getZ());
                life.markDirty();
                EarthLifeServices.get().life.complete(p, "set_home");
                return String.format("Home set at %.0f, %.0f, %.0f.", p.getX(), p.getY(), p.getZ()); }))))
            .then(lit("work").then(lit("set").executes(c -> run(c, (p, x) -> {
                PlayerLife life = EarthLifeServices.get().life.life(p);
                life.work = new PlayerLife.Waypoint("Work", "work", p.getX(), p.getY(), p.getZ());
                life.markDirty();
                return String.format("Workplace marker set at %.0f, %.0f.", p.getX(), p.getZ()); }))))
            .then(lit("talk").then(Commands.argument("entity", IntegerArgumentType.integer()).then(Commands.argument("choice", IntegerArgumentType.integer(0, 16))
                .executes(c -> run(c, (p, x) -> DialogueService.choose(p, IntegerArgumentType.getInteger(x, "entity"), IntegerArgumentType.getInteger(x, "choice")))))))
            .then(lit("ui")
                .then(lit("opened").then(Commands.argument("screen", StringArgumentType.word()).executes(c -> run(c, (p, x) -> {
                    var s = EarthLifeServices.get();
                    switch (StringArgumentType.getString(x, "screen")) {
                        case "menu" -> s.life.complete(p, "open_menu");
                        case "map" -> s.life.complete(p, "open_map");
                        case "nearby" -> s.life.complete(p, "open_nearby");
                        case "wallet" -> s.life.complete(p, "open_wallet");
                        default -> {}
                    }
                    return null; }))))
                .then(lit("welcomed").executes(c -> run(c, (p, x) -> { var l = EarthLifeServices.get().life.life(p); l.welcomed = true; l.markDirty(); return null; })))
                .then(Commands.argument("tab", StringArgumentType.word()).executes(c -> run(c, (p, x) -> { UiLists.send(p, StringArgumentType.getString(x, "tab")); return null; }))))
            .then(lit("event")
                .then(lit("costume").executes(c -> run(c, (p, x) -> Halloween.costumeContest(p))))
                .then(lit("carving").executes(c -> run(c, (p, x) -> Halloween.carvingContest(p)))))
            .then(lit("objectives").executes(c -> run(c, (p, x) -> {
                var life = EarthLifeServices.get().life.life(p);
                var lines = earthlife.mod.life.Objectives.lines(life);
                return lines.isEmpty() ? "No guided objectives in Normal Survival." : "Objectives (" + life.scenario + "):\n" + String.join("\n", lines); })))
            .then(lit("admin").requires(McCompat::isOp)
                .then(lit("reload").executes(c -> run(c, (p, x) -> {
                    EarthLifeMod.EVENTS.shutdownAndRestart();
                    for (ServerPlayer q : p.level().getServer().getPlayerList().getPlayers()) EarthLifeServices.get().life.sendWorldInfo(q);
                    return "EarthLife: event feeds refreshing; world info resent to all players. (Map data changes apply to newly generated chunks only.)"; })))
                .then(lit("region").then(lit("status").executes(c -> run(c, (p, x) -> dataStatus(p) + "\n" + worldInfo()))))
                .then(lit("debug").executes(c -> run(c, (p, x) -> {
                    var s = EarthLifeServices.get();
                    TileData t = s.tiles.request(TileKey.containing(s.region, p.getBlockX(), p.getBlockZ(), 0)).join();
                    int sx = p.getBlockX() - t.key.minX(), sz = p.getBlockZ() - t.key.minZ(), i = t.idx(sx, sz);
                    return String.format("tile %s · terrainY %d · surface %s · structureTop %d · flags %s · npcs live %d · players %d",
                            t.key.fileName(), t.terrainY[i], t.surfaceAt(sx, sz), t.structureTopY[i], Integer.toBinaryString(t.flags[i] & 0xFF),
                            EarthLifeMod.NPCS.liveCount(), p.level().getServer().getPlayerList().getPlayerCount()); })))
                .then(lit("halloween").then(Commands.argument("state", StringArgumentType.word()).executes(c -> run(c, (p, x) -> {
                    Halloween.forced = StringArgumentType.getString(x, "state").equalsIgnoreCase("on");
                    return "Halloween forced " + (Halloween.forced ? "ON (any date, any hour)" : "off (real calendar)") + "."; }))))
                .then(lit("teleport")
                    .then(lit("town").then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> run(c, (p, x) -> {
                        var s = EarthLifeServices.get();
                        var t = Towns.find(s.towns, StringArgumentType.getString(x, "name")).orElse(null);
                        if (t == null) return "No such town.";
                        var b = s.projection.toBlock(t.centre().lat(), t.centre().lon());
                        return teleport(p, b.x(), b.z(), t.name()); }))))
                    .then(Commands.argument("lat", DoubleArgumentType.doubleArg(-90, 90)).then(Commands.argument("lon", DoubleArgumentType.doubleArg(-180, 180)).executes(c -> run(c, (p, x) -> {
                        var b = EarthLifeServices.get().projection.toBlock(DoubleArgumentType.getDouble(x, "lat"), DoubleArgumentType.getDouble(x, "lon"));
                        return teleport(p, b.x(), b.z(), "the requested coordinates"); })))))));
    }

    static String help() {
        return String.join("\n",
                "§6EarthLife " + VERSION + "§r - keys: §eM§r LifeMap · §eK§r menu · §eJ§r jobs · §eL§r wallet · §eN§r nearby (rebind in Controls > EarthLife)",
                "/earthlife map [route home|work|town <name>|airport <ICAO>|geo <lat> <lon>|clear] · map share on|off",
                "/earthlife location · coordinates · towns · town <name> · landmarks",
                "/earthlife wallet · claim hourly|daily|weekly · jobs · job list|apply|clockin|clockout|tasks",
                "/earthlife vehicles · vehicle shop|buy|summon · flights · book <flight> auto",
                "/earthlife events · event costume|carving · time · objectives",
                "/earthlife waypoint add|set|remove|list · home set · work set",
                "/earthlife data status · world info · version" + " · operators: /earthlife admin reload|region status|debug|teleport|halloween");
    }

    static void open(ServerPlayer p, String screen) { Net.send(p, new Payloads.OpenScreen(screen)); }

    static double[] wp(ServerPlayer p, String which) {
        PlayerLife life = EarthLifeServices.get().life.life(p);
        PlayerLife.Waypoint w = which.equals("home") ? life.home : life.work;
        if (w == null && which.equals("work")) {
            var e = EarthLifeServices.get().jobs.employment(p.getStringUUID());
            if (e.isPresent()) return earthlife.mod.jobs.JobHooks.workplace(e.get()).map(m -> new double[]{m[0], m[2]}).orElse(null);
        }
        return w == null ? null : new double[]{w.x(), w.z()};
    }

    static String route(ServerPlayer p, double[] xz) { return route(p, xz, "destination"); }

    /** Shows a road route on the LifeMap. Navigation only - it never moves the player. */
    static String route(ServerPlayer p, double[] xz, String label) {
        if (xz == null) return "Nothing to route to (set it first).";
        open(p, String.format(Locale.ROOT, "map:route:%.1f:%.1f:%s", xz[0], xz[1], label.replace(':', ' ')));
        double d = Math.hypot(xz[0] - p.getX(), xz[1] - p.getZ());
        return String.format("Route to %s: %.1f km %s. Follow the line on your LifeMap [M].", label, d / 1000,
                V02Commands.compass((Math.toDegrees(Math.atan2(xz[0] - p.getX(), -(xz[1] - p.getZ()))) + 360) % 360));
    }

    static String location(ServerPlayer p) {
        var s = EarthLifeServices.get();
        GeoPoint g = s.projection.fromBlock(p.getX(), p.getZ());
        TileData t = s.tiles.request(TileKey.containing(s.region, p.getBlockX(), p.getBlockZ(), 0)).join();
        boolean est = t.has(p.getBlockX() - t.key.minX(), p.getBlockZ() - t.key.minZ(), TileData.FLAG_ESTIMATED);
        return String.format("You are in %s · XYZ %.1f / %.1f / %.1f · %.6f, %.6f · elevation %.0f m above sea level · map data here: %s",
                s.life.placeName(g), p.getX(), p.getY(), p.getZ(), g.lat(), g.lon(), s.projection.yToElevation(p.getY()),
                est ? "estimated" : "mapped (" + s.attribution + ")");
    }

    static String addWaypoint(ServerPlayer p, String name, double x, double y, double z) {
        PlayerLife life = EarthLifeServices.get().life.life(p);
        if (life.waypoints.size() >= 64) return "You have 64 waypoints; remove some first.";
        life.waypoints.removeIf(w -> w.name().equalsIgnoreCase(name));
        life.waypoints.add(new PlayerLife.Waypoint(name, "pin", x, y, z));
        life.markDirty();
        EarthLifeServices.get().life.complete(p, "waypoint");
        return String.format("Waypoint \"%s\" at %.0f, %.0f. Route: /earthlife map route waypoint %s", name, x, z, name);
    }

    static String dataStatus(ServerPlayer p) throws Exception {
        var s = EarthLifeServices.get();
        java.nio.file.Path cache = s.worldDir.resolve("tilecache");
        long[] size = {0, 0};
        if (Files.exists(cache)) try (var w = Files.walk(cache)) { w.filter(Files::isRegularFile).forEach(f -> { size[0]++; try { size[1] += Files.size(f); } catch (Exception ignored) {} }); }
        return String.format("Region %s (%s) · %d roads, %d buildings (incl. estimated infill), %d land areas, %d water areas · terrain: %s · "
                        + "tiles in memory %d, on disk %d (%.1f MB) · %s",
                s.region, s.bundledData ? "bundled Western NY starter data" : "estimated terrain (no map data bundled for this area)",
                s.features.roads.size(), s.features.buildings.size(), s.features.areas.size(), s.features.water.size(),
                s.elevationSource instanceof earthlife.core.world.ElevationSource.Grid ? "measured elevation grid" : "estimated hills",
                s.tiles.loadedCount(), size[0], size[1] / 1e6, s.attribution);
    }

    static String worldInfo() {
        var st = EarthLifeServices.get().settings;
        return String.format("Earth world · start %s, %s, %s (%.4f, %.4f) · quality %s · buildings %s · roads %s · real terrain %s · transport %s · simulation %s · start time %s · scenario %s · day length %s",
                st.town(), st.state(), st.country(), st.lat(), st.lon(), st.quality(), st.buildings(), st.roads(), st.realTerrain(), st.transport(), st.simulation(),
                st.startTime(), st.scenario(), st.dayLength());
    }

    static String teleport(ServerPlayer p, double x, double z, String label) {
        var s = EarthLifeServices.get();
        var spot = s.life.safeSpawn(p.level().getServer().overworld(), new double[]{x, z});
        p.teleportTo(p.level().getServer().overworld(), spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, Set.<Relative>of(), p.getYRot(), p.getXRot(), true);
        return "Teleported to " + label + " (operator command).";
    }
}
