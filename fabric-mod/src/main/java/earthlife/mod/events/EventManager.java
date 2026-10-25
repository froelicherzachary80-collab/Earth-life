package earthlife.mod.events;

import earthlife.core.events.EventEngine;
import earthlife.core.events.LiveEvent;
import earthlife.core.events.LiveEvent.Activity;
import earthlife.core.world.Features;
import earthlife.core.world.Towns;
import earthlife.mod.EarthLifeMod;
import earthlife.mod.EarthLifeServices;
import earthlife.mod.npc.NpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Makes real events happen in the world at their real time:
 *  - games: a crowd of residents in the stands and a live scoreboard (MLB live linescore) at the real ballpark;
 *  - concerts: a crowd and music at the venue's stage/floor (vanilla music discs - no real recordings);
 *  - holidays: Halloween pumpkins on doorsteps + trick-or-treaters, winter lights, flags, fireworks at the
 *    customary times over town centres and the lakeshore;
 *  - announcements when something starts near you; /earthlife events lists today and what's coming.
 * Times are always real (wall-clock) times, whatever the in-game clock mode.
 * UNVERIFIED: TextDisplay#setText (access-widened), Fireworks data components (1.20.5+ API).
 */
public final class EventManager {
    private final ExecutorService net = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "EarthLife-Events"); t.setDaemon(true); return t; });
    private final Map<String, List<NpcEntity>> crowds = new HashMap<>();
    private final Map<String, Display.TextDisplay> boards = new HashMap<>();
    private final Map<String, List<Display.BlockDisplay>> decorations = new HashMap<>();
    private final Set<String> announced = new HashSet<>();
    private volatile List<EventEngine.Placement> active = List.of();
    private Future<?> pending;

    public void tick(MinecraftServer server) {
        var s = EarthLifeServices.get();
        int t = server.getTickCount();
        if (t % 200 == 0 && (pending == null || pending.isDone())) pending = net.submit(() -> {
            try { s.events.refresh(Instant.now()); active = s.events.activeAt(Instant.now()); }
            catch (Exception e) { EarthLifeMod.LOG.warn("events refresh", e); }
        });
        if (t % 100 != 0) return;
        ServerLevel level = server.overworld();
        Set<String> live = new HashSet<>();
        for (EventEngine.Placement p : active) {
            LiveEvent e = p.event();
            live.add(e.id());
            if (announced.add(e.id()) && e.activities().contains(Activity.ANNOUNCE))
                server.getPlayerList().broadcastSystemMessage(Component.literal("§6★ " + headline(p)), false);
            if (p.landmarkId() != null) venue(server, level, p);
            else if (e.regionWide()) holiday(server, level, e);
            else townEvent(server, level, p);
        }
        // Clean up after events end
        for (var it = crowds.entrySet().iterator(); it.hasNext(); ) { var en = it.next(); if (!live.contains(en.getKey())) { en.getValue().forEach(NpcEntity::discard); it.remove(); } }
        for (var it = boards.entrySet().iterator(); it.hasNext(); ) { var en = it.next(); if (!live.contains(en.getKey())) { en.getValue().discard(); it.remove(); } }
        for (var it = decorations.entrySet().iterator(); it.hasNext(); ) { var en = it.next(); if (!live.contains(en.getKey())) { en.getValue().forEach(Display.BlockDisplay::discard); it.remove(); } }
    }

    static String headline(EventEngine.Placement p) {
        LiveEvent e = p.event();
        String where = p.landmarkId() != null ? EarthLifeServices.get().landmarks.get(p.landmarkId()).landmark().name() : p.town() != null ? p.town() : "everywhere";
        return switch (e.category()) {
            case HOLIDAY -> "Today: " + e.title();
            case SPORTS -> "Live now at " + where + ": " + e.title();
            case CONCERT -> "On stage now at " + where + ": " + e.title();
            default -> "Happening now in " + where + ": " + e.title();
        };
    }

    private static boolean playerNear(MinecraftServer server, double x, double z, double r) {
        return server.getPlayerList().getPlayers().stream().anyMatch(p -> Math.hypot(p.getX() - x, p.getZ() - z) < r);
    }

    private void venue(MinecraftServer server, ServerLevel level, EventEngine.Placement p) {
        var lm = EarthLifeServices.get().landmarks.get(p.landmarkId());
        double[] gate = Optional.ofNullable(lm.marker("gate")).orElse(lm.marker("stage"));
        if (gate == null || !playerNear(server, gate[0], gate[2], 300) || !level.hasChunkAt(BlockPos.containing(gate[0], gate[1], gate[2]))) return;
        LiveEvent e = p.event();
        if (e.activities().contains(Activity.CROWD) && !crowds.containsKey(e.id())) {
            List<NpcEntity> crowd = new ArrayList<>();
            var seats = lm.markers("seats:");
            Collections.shuffle(seats, new Random(e.id().hashCode()));
            var residents = EarthLifeServices.get().populations.stream().flatMap(x -> x.all().stream()).toList();
            for (int i = 0; i < Math.min(60, seats.size()) && !residents.isEmpty(); i++) {
                double[] seat = seats.get(i);
                NpcEntity n = EarthLifeMod.NPC.create(level, EntitySpawnReason.EVENT);
                if (n == null) break;
                n.bind(residents.get(Math.floorMod(e.id().hashCode() + i * 7919, residents.size())), true);
                n.setPos(seat[0] + 0.5, seat[1], seat[2] + 0.5);
                n.setNoAi(true);                                   // spectators stay in their seats
                double[] focus = Optional.ofNullable(lm.marker("field:home")).or(() -> Optional.ofNullable(lm.marker("floor"))).or(() -> Optional.ofNullable(lm.marker("stage"))).orElse(gate);
                n.setYRot((float) Math.toDegrees(Math.atan2(-(focus[0] - seat[0]), focus[2] - seat[2])));
                level.addFreshEntity(n);
                crowd.add(n);
            }
            crowds.put(e.id(), crowd);
        }
        if (e.activities().contains(Activity.SCOREBOARD)) {
            double[] sb = Optional.ofNullable(lm.marker("scoreboard")).orElse(lm.marker("marquee"));
            if (sb != null) {
                Display.TextDisplay board = boards.computeIfAbsent(e.id(), k -> {
                    Display.TextDisplay d = EntityType.TEXT_DISPLAY.create(level, EntitySpawnReason.TRIGGERED);
                    d.setPos(sb[0], sb[1], sb[2]);
                    d.setYRot((float) (lm.placed().headingDeg + 90));
                    level.addFreshEntity(d);
                    return d;
                });
                var sc = e.score();
                String text = sc == null ? e.title() + "\n" + (e.start().isAfter(Instant.now()) ? "Starts soon" : "In progress")
                        : String.format("%s  %d\n%s  %d\n%s", short_(sc.away()), sc.awayRuns(), short_(sc.home()), sc.homeRuns(), sc.detail());
                board.setText(Component.literal(text));
            }
        }
        if (e.activities().contains(Activity.STAGE_MUSIC) && server.getTickCount() % 3600 == 0) {
            double[] st = Optional.ofNullable(lm.marker("stage")).or(() -> Optional.ofNullable(lm.marker("floor"))).orElse(gate);
            level.playSound(null, st[0], st[1], st[2], SoundEvents.MUSIC_DISC_PIGSTEP.value(), SoundSource.RECORDS, 4f, 1f);
        }
        if (e.activities().contains(Activity.FIREWORKS)) fireworks(level, gate[0], gate[1] + 2, gate[2], 3);
        // Ticket holders at the venue during the event get a one-time fan bonus.
        var s = EarthLifeServices.get();
        for (ServerPlayer pl : server.getPlayerList().getPlayers()) {
            if (Math.hypot(pl.getX() - gate[0], pl.getZ() - gate[2]) > 120 || !s.ledger.hasReference(pl.getStringUUID(), "eventticket#" + e.id())) continue;
            try { s.ledger.credit(pl.getStringUUID(), 20, "EVENT_BONUS", "attend#" + e.id(), "Attended " + e.title(), level.getGameTime());
                  pl.sendSystemMessage(Component.literal("🎟 Thanks for coming to " + e.title() + "! +20 LC")); }
            catch (Exception ignored) { /* already rewarded */ }
        }
    }

    /** Ticket prices in LifeCoins (holidays and community events are free). */
    public static long price(LiveEvent e) {
        return switch (e.category()) { case SPORTS -> 40; case CONCERT -> 60; default -> 0; };
    }

    static String short_(String team) { return team.length() > 18 ? team.substring(team.lastIndexOf(' ') + 1) : team; }

    private void townEvent(MinecraftServer server, ServerLevel level, EventEngine.Placement p) {
        var b = EarthLifeServices.get().projection.toBlock(p.lat(), p.lon());
        if (!playerNear(server, b.x(), b.z(), 300)) return;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) b.x(), (int) b.z());
        if (p.event().activities().contains(Activity.FIREWORKS)) fireworks(level, b.x(), y + 1, b.z(), 4);
    }

    private void holiday(MinecraftServer server, ServerLevel level, LiveEvent e) {
        var s = EarthLifeServices.get();
        if (e.activities().contains(Activity.FIREWORKS)) {
            for (Towns.Town t : s.towns) {
                var b = s.projection.toBlock(t.centre().lat(), t.centre().lon());
                if (!playerNear(server, b.x(), b.z(), 400) || !level.hasChunkAt(BlockPos.containing(b.x(), 0, b.z()))) continue;
                fireworks(level, b.x(), level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) b.x(), (int) b.z()) + 1, b.z(), 5);
            }
        }
        boolean halloween = e.activities().contains(Activity.DECORATIONS_HALLOWEEN), winter = e.activities().contains(Activity.DECORATIONS_WINTER),
                patriotic = e.activities().contains(Activity.DECORATIONS_PATRIOTIC);
        if (!(halloween || winter || patriotic)) return;
        List<Display.BlockDisplay> deco = decorations.computeIfAbsent(e.id(), k -> new ArrayList<>());
        if (!deco.isEmpty()) return;                                  // placed once per event (refreshed if players move far: V0.4)
        for (ServerPlayer pl : server.getPlayerList().getPlayers()) {
            for (Features.Building bd : s.features.buildings) {
                if (deco.size() > 300) break;
                var c = bd.footprint().get(0);
                if (Math.hypot(c.x() - pl.getX(), c.z() - pl.getZ()) > 64 || !"house".equals(bd.kind())) continue;
                double[] door = doorstep(bd);
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(door[0]), (int) Math.floor(door[1]));
                var state = halloween ? Blocks.JACK_O_LANTERN.defaultBlockState() : winter ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.RED_WOOL.defaultBlockState();
                Display.BlockDisplay d = EntityType.BLOCK_DISPLAY.create(level, EntitySpawnReason.TRIGGERED);
                if (d == null) continue;
                d.setBlockState(state);
                d.setTransformation(new com.mojang.math.Transformation(new org.joml.Vector3f(0, 0, 0), new org.joml.Quaternionf(),
                        new org.joml.Vector3f(halloween ? 0.6f : 0.3f), new org.joml.Quaternionf()));
                d.setPos(door[0], y, door[1]);
                level.addFreshEntity(d);
                deco.add(d);
            }
        }
    }

    /** A point just outside the building on its street side (same door logic the tile generator uses, approximated). */
    static double[] doorstep(Features.Building b) {
        var o = earthlife.core.world.BuildingStyle.obb(b.footprint());
        return new double[]{o.cx() - o.uz() * (o.halfV() + 1.2), o.cz() + o.ux() * (o.halfV() + 1.2)};
    }

    static void fireworks(ServerLevel level, double x, double y, double z, int count) {
        Random r = new Random();
        for (int i = 0; i < count; i++) {
            ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
            var colors = it.unimi.dsi.fastutil.ints.IntList.of(new int[]{0xE53935, 0x1E88E5, 0xFFFFFF, 0xFDD835, 0x43A047}[r.nextInt(5)]);
            rocket.set(DataComponents.FIREWORKS, new Fireworks(1 + r.nextInt(2), List.of(new FireworkExplosion(
                    FireworkExplosion.Shape.values()[r.nextInt(FireworkExplosion.Shape.values().length)], colors, it.unimi.dsi.fastutil.ints.IntList.of(), r.nextBoolean(), r.nextBoolean()))));
            level.addFreshEntity(new FireworkRocketEntity(level, x + r.nextGaussian() * 8, y, z + r.nextGaussian() * 8, rocket));
        }
    }

    public List<EventEngine.Placement> today() {
        Instant now = Instant.now();
        return EarthLifeServices.get().events.between(now.minus(Duration.ofHours(1)), now.plus(Duration.ofDays(7)));
    }

    public void shutdown() {
        net.shutdownNow();
        crowds.values().forEach(l -> l.forEach(NpcEntity::discard));
        boards.values().forEach(Display.TextDisplay::discard);
        decorations.values().forEach(l -> l.forEach(Display.BlockDisplay::discard));
    }
}
