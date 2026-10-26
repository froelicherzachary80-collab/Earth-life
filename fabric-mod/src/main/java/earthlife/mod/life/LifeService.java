package earthlife.mod.life;

import earthlife.core.geo.GeoPoint;
import earthlife.core.life.Objectives;
import earthlife.core.life.PlayerLife;
import earthlife.core.world.TileData;
import earthlife.core.world.TileKey;
import earthlife.core.world.Towns;
import earthlife.mod.EarthLifeMod;
import earthlife.mod.EarthLifeServices;
import earthlife.mod.McCompat;
import earthlife.mod.net.Payloads;
import earthlife.mod.world.EarthSettings;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;

import java.io.IOException;
import java.util.*;

/**
 * The "life" layer for Earth worlds: safe arrival in the start town, the chosen starting scenario, guided objectives,
 * waypoints/home/work, location sharing, and the once-a-second state packet behind the HUD, LifeMap and menu.
 */
public final class LifeService {
    public final LifeStore store;
    private final EarthLifeServices s;

    public LifeService(EarthLifeServices s) throws IOException {
        this.s = s;
        this.store = new LifeStore(s.worldDir);
    }

    public PlayerLife life(ServerPlayer p) { return store.get(p.getStringUUID()); }

    // ------------------------------------------------------------------ arrival

    /** First join in this world: move to a safe spot in the start town (never into water or onto a roof), give the scenario kit. */
    public void onJoin(ServerPlayer p) {
        PlayerLife life = life(p);
        if (!life.arrived) {
            life.scenario = s.settings.scenarioEnum().name();
            BlockPos spot = safeSpawn(p.level().getServer().overworld(), startPoint(life.scenario));
            p.teleportTo(p.level().getServer().overworld(), spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, Set.<Relative>of(), p.getYRot(), 0f, true);
            giveKit(p, life);
            life.arrived = true;
            life.markDirty();
            EarthLifeMod.LOG.info("EarthLife: {} arrived in {} at {} ({})", p.getName().getString(), s.settings.town(), spot.toShortString(), life.scenario);
        }
        sendWorldInfo(p);
    }

    /** Where the scenario starts: the town centre, or the nearest airport terminal for Traveler. */
    double[] startPoint(String scenario) {
        if ("TRAVELER".equals(scenario) && !s.airportLayouts.isEmpty()) {
            var nearest = s.airportLayouts.values().stream().min(Comparator.comparingDouble(l -> {
                double[] m = l.placed.marker("agent:checkin:1"); return Math.hypot(m[0] - s.spawnX, m[2] - s.spawnZ); })).get();
            double[] m = nearest.placed.marker("agent:checkin:1");
            return new double[]{m[0] - 6, m[2]};
        }
        return new double[]{s.spawnX, s.spawnZ};
    }

    /**
     * Nearest dry, open, solid-ground column to (x,z), searched in growing rings using the map tiles
     * (no water, no building footprint), then the real surface height from the generated chunk.
     */
    public BlockPos safeSpawn(ServerLevel level, double[] xz) {
        int cx = (int) Math.floor(xz[0]), cz = (int) Math.floor(xz[1]);
        for (int r = 0; r <= 96; r += 2) {
            for (int i = -r; i <= r; i += 2) for (int k = 0; k < 4; k++) {
                if (r != 0 && k > 0 && Math.abs(i) == r && (k == 1 || k == 3)) continue;
                int x = cx + (k == 0 ? i : k == 1 ? r : k == 2 ? -i : -r), z = cz + (k == 0 ? -r : k == 1 ? i : k == 2 ? r : -i);
                if (!openColumn(x, z)) continue;
                level.getChunk(x >> 4, z >> 4);          // generate/load it: getHeight() on an unloaded chunk returns the world bottom
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos below = new BlockPos(x, y - 1, z);
                var st = level.getBlockState(below);
                if (!st.getFluidState().isEmpty() || !st.isSolid()) continue;
                if (!level.getBlockState(below.above()).isAir() || !level.getBlockState(below.above(2)).isAir()) continue;
                return below.above();
            }
            if (r == 0) continue;
        }
        level.getChunk(cx >> 4, cz >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx, cz);
        return new BlockPos(cx, Math.max(y, level.getMinY() + 2), cz);
    }

    private boolean openColumn(int x, int z) {
        try {
            TileData t = s.tiles.request(TileKey.containing(s.region, x, z, 0)).join();
            int sx = x - t.key.minX(), sz = z - t.key.minZ();
            return !t.has(sx, sz, TileData.FLAG_WATER) && t.structureTopY[t.idx(sx, sz)] == 0 && !t.has(sx, sz, TileData.FLAG_TREE)
                    && t.terrainY[t.idx(sx, sz)] >= 63;
        } catch (Exception e) { return true; }
    }

    /** Sets the world spawn to the safe start point once, so deaths without a bed bring you back to town. */
    public void setWorldSpawn(MinecraftServer server) {
        ServerLevel ow = server.overworld();
        BlockPos spot = safeSpawn(ow, new double[]{s.spawnX, s.spawnZ});
        ow.setRespawnData(LevelData.RespawnData.of(ow.dimension(), spot, 0f, 0f));
        EarthLifeMod.LOG.info("EarthLife: world spawn set to {} in {}", spot.toShortString(), s.settings.town());
    }

    private void giveKit(ServerPlayer p, PlayerLife life) {
        long tick = p.level().getGameTime();
        long coins = switch (life.scenario) { case "TRAVELER" -> 1500; case "CAREER_STARTER" -> 100; case "NEW_LIFE" -> 250; default -> 0; };
        try { if (coins > 0) s.ledger.credit(p.getStringUUID(), coins, "STARTER", "starter#" + life.scenario, "Starting money (" + life.scenario + ")", tick); }
        catch (Exception ignored) {}   // already paid (e.g. record reset) - the ledger refuses duplicates
        if (!"NORMAL_SURVIVAL".equals(life.scenario)) {
            p.addItem(new ItemStack(Items.BREAD, 6));
            p.addItem(new ItemStack(Items.COMPASS));
        }
        if ("CAREER_STARTER".equals(life.scenario)) {
            try {
                s.jobs.hire(p.getStringUUID(), earthlife.core.jobs.JobType.STORE_CLERK, s.settings.town(), earthlife.core.time.GameClock.day(McCompat.dayTime(p.level())));
                complete(p, "get_job");
                p.sendSystemMessage(Component.literal("Career Starter: you've been hired as a Store clerk in " + s.settings.town() + ". Shifts 08:00-16:00 - clock in with [J]."));
            } catch (Exception e) { EarthLifeMod.LOG.warn("career starter hire", e); }
        }
        String intro = switch (life.scenario) {
            case "NEW_LIFE" -> "New Life: you just moved to " + s.settings.town() + " with 250 LifeCoins. Follow the objectives on the right - press [K] for the EarthLife menu.";
            case "TRAVELER" -> "Traveler: you start at the airport with 1,500 LifeCoins. Press [M] for the LifeMap and see /earthlife flights.";
            case "CAREER_STARTER" -> "Career Starter: a job is waiting for you. Press [J].";
            default -> "Normal Survival on the real Earth. Press [M] for your LifeMap, [K] for the EarthLife menu.";
        };
        p.sendSystemMessage(Component.literal("§6EarthLife§r · " + intro));
    }

    // ------------------------------------------------------------------ objectives

    public void complete(ServerPlayer p, String id) {
        PlayerLife life = life(p);
        var o = Objectives.byId(life.scenario, id);
        if (o.isEmpty() || !life.objectivesDone.add(id)) return;
        life.markDirty();
        try { s.ledger.credit(p.getStringUUID(), o.get().rewardLC(), "OBJECTIVE", "objective#" + id, o.get().text(), p.level().getGameTime()); }
        catch (Exception ignored) {}
        p.sendSystemMessage(Component.literal("§a✔ Objective complete:§r " + o.get().text() + " §6+" + o.get().rewardLC() + " LC"));
        if (life.objectivesDone.containsAll(Objectives.forScenario(life.scenario).stream().map(Objectives.Objective::id).toList()))
            p.sendSystemMessage(Component.literal("§6You've settled into your new life! Keep working, exploring and joining events."));
    }

    // ------------------------------------------------------------------ packets

    public void sendWorldInfo(ServerPlayer p) {
        List<Payloads.Poi> pois = new ArrayList<>();
        for (Towns.Town t : s.towns) { var b = s.projection.toBlock(t.centre().lat(), t.centre().lon()); pois.add(new Payloads.Poi("town:" + t.kind().name().toLowerCase(Locale.ROOT), t.name(), b.x(), b.z())); }
        for (var e : s.airportLayouts.entrySet()) { double[] m = e.getValue().placed.marker("agent:checkin:1"); pois.add(new Payloads.Poi("airport", e.getKey() + " " + s.airports.get(e.getKey()).name(), m[0], m[2])); }
        for (var l : s.landmarks.values()) { var b = s.projection.toBlock(l.landmark().pos().lat(), l.landmark().pos().lon()); pois.add(new Payloads.Poi("landmark", l.landmark().name(), b.x(), b.z())); }
        EarthSettings st = s.settings;
        String summary = String.format("%s, %s · %s quality · buildings %s · roads %s · real terrain %s · airports %s · residents %s · %s · day length %s",
                st.town(), st.state(), EarthSettings.pretty(st.qualityEnum()), onOff(st.buildings()), onOff(st.roads()), onOff(st.realTerrain()),
                onOff(st.transport()), onOff(st.simulation()), EarthSettings.pretty(st.scenarioEnum()), EarthSettings.pretty(st.dayLengthEnum()));
        earthlife.mod.net.Net.send(p, new Payloads.WorldInfo(true, s.region, s.projection.origin().lat(), s.projection.origin().lon(),
                EarthLifeServices.SEA_LEVEL_Y_AT_0M, st.town(), st.state(), st.country(), life(p).scenario, s.bundledData, s.attribution, s.allowLocationSharing, !life(p).welcomed, summary, pois));
    }

    static String onOff(boolean b) { return b ? "on" : "off"; }

    /** Once a second for every player. */
    public void sendState(ServerPlayer p, String clock, String job, boolean onShift, String eventLine, boolean halloween) {
        PlayerLife life = life(p);
        String id = p.getStringUUID();
        GeoPoint g = s.projection.fromBlock(p.getX(), p.getZ());
        List<Payloads.Poi> wps = new ArrayList<>();
        if (life.home != null) wps.add(new Payloads.Poi("home", life.home.name(), life.home.x(), life.home.z()));
        if (life.work != null) wps.add(new Payloads.Poi("work", life.work.name(), life.work.x(), life.work.z()));
        for (var w : life.waypoints) wps.add(new Payloads.Poi("waypoint", w.name(), w.x(), w.z()));
        List<String> obj = Objectives.lines(life);
        int done = (int) obj.stream().filter(x -> x.startsWith("✔")).count();
        earthlife.mod.net.Net.send(p, new Payloads.LifeState(s.ledger.balance(id), s.rewards.ticksUntilNextHourly(id), s.rewards.hourlyAvailable(id) > 0,
                s.rewards.dailyAvailable(id, System.currentTimeMillis()), clock, job, onShift, placeName(g), g.lat(), g.lon(),
                s.projection.yToElevation(p.getY()), obj, done, wps, eventLine, life.candy, halloween, life.shareLocation));
    }

    /** "Williamson" inside a town, "2.3 km NE of Sodus" outside. */
    public String placeName(GeoPoint g) {
        if (s.towns.isEmpty()) return s.settings.town();
        Towns.Town t = Towns.nearest(s.towns, g);
        double d = t.centre().distanceTo(g);
        if (d < t.kind().radiusM()) return t.name();
        String dir = new String[]{"N", "NE", "E", "SE", "S", "SW", "W", "NW"}[(int) Math.round(t.centre().bearingTo(g) / 45) % 8];
        return String.format("%.1f km %s of %s", d / 1000, dir, t.name());
    }

    /** Players who opted in to sharing, sent to everyone (only if the server allows sharing at all). */
    public void sendSharedPlayers(MinecraftServer server) {
        if (!s.allowLocationSharing) return;
        List<Payloads.Poi> shared = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers())
            if (life(p).shareLocation && p.level() == server.overworld()) shared.add(new Payloads.Poi("player", p.getName().getString(), p.getX(), p.getZ()));
        var pkt = new Payloads.MapPlayers(shared);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) earthlife.mod.net.Net.send(p, pkt);
    }
}
