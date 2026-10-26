package earthlife.mod;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import earthlife.core.economy.Ledger;
import earthlife.mod.aviation.*;
import earthlife.mod.net.Payloads;
import earthlife.mod.world.VoxelEarthChunkGenerator;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * EarthLife server-side entry point: registers the VoxelEarth generator and airliner entity, enforces
 * EarthLife Survival, runs rewards, flights and tile streaming each tick, and exposes the /earthlife command
 * tree that the client screens (wallet, booking) drive.
 */
public final class EarthLifeMod implements ModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("EarthLife");
    public static final ResourceKey<EntityType<?>> AIRPLANE_KEY = ResourceKey.create(Registries.ENTITY_TYPE, id("airplane"));
    public static final EntityType<AirplaneEntity> AIRPLANE = Registry.register(BuiltInRegistries.ENTITY_TYPE, AIRPLANE_KEY,
            EntityType.Builder.<AirplaneEntity>of(AirplaneEntity::new, MobCategory.MISC).sized(4f, 4f).clientTrackingRange(32).updateInterval(1).build(AIRPLANE_KEY));

    public static final ResourceKey<EntityType<?>> NPC_KEY = ResourceKey.create(Registries.ENTITY_TYPE, id("resident"));
    public static final EntityType<earthlife.mod.npc.NpcEntity> NPC = Registry.register(BuiltInRegistries.ENTITY_TYPE, NPC_KEY,
            EntityType.Builder.<earthlife.mod.npc.NpcEntity>of(earthlife.mod.npc.NpcEntity::new, MobCategory.CREATURE).sized(0.6f, 1.8f).clientTrackingRange(10).build(NPC_KEY));
    public static final ResourceKey<EntityType<?>> VEHICLE_KEY = ResourceKey.create(Registries.ENTITY_TYPE, id("vehicle"));
    public static final EntityType<earthlife.mod.vehicle.VehicleEntity> VEHICLE = Registry.register(BuiltInRegistries.ENTITY_TYPE, VEHICLE_KEY,
            EntityType.Builder.<earthlife.mod.vehicle.VehicleEntity>of(earthlife.mod.vehicle.VehicleEntity::new, MobCategory.MISC).sized(2f, 1.6f).clientTrackingRange(16).updateInterval(1).build(VEHICLE_KEY));

    // Per-world managers: recreated each time an Earth world starts, so nothing leaks between worlds in one session.
    public static FlightDispatcher FLIGHTS = new FlightDispatcher();
    public static earthlife.mod.npc.NpcManager NPCS = new earthlife.mod.npc.NpcManager();
    public static earthlife.mod.vehicle.TransitManager TRANSIT = new earthlife.mod.vehicle.TransitManager();
    public static earthlife.mod.events.EventManager EVENTS = new earthlife.mod.events.EventManager();
    public static earthlife.mod.events.HauntedHouse HAUNTED = new earthlife.mod.events.HauntedHouse();
    private static final Map<UUID, Vec3> lastPos = new HashMap<>();

    public static Identifier id(String path) { return Identifier.fromNamespaceAndPath("earthlife", path); }

    /** Earth world settings if the running server's overworld is an Earth world, else empty (Default worlds are untouched). */
    public static Optional<earthlife.mod.world.EarthSettings> earthSettings(MinecraftServer server) {
        try {
            var stems = server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM);
            var ow = stems.getValue(net.minecraft.world.level.dimension.LevelStem.OVERWORLD);
            if (ow != null && ow.generator() instanceof VoxelEarthChunkGenerator g) return Optional.of(g.settings());
        } catch (Exception e) { LOG.warn("EarthLife: could not read world dimensions", e); }
        return Optional.empty();
    }

    @Override
    public void onInitialize() {
        Registry.register(BuiltInRegistries.CHUNK_GENERATOR, id("voxel_earth"), VoxelEarthChunkGenerator.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Payloads.FlightTelemetry.TYPE, Payloads.FlightTelemetry.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Payloads.WalletStatus.TYPE, Payloads.WalletStatus.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Payloads.CabinState.TYPE, Payloads.CabinState.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Payloads.HudStatus.TYPE, Payloads.HudStatus.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Payloads.WorldInfo.TYPE, Payloads.WorldInfo.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Payloads.LifeState.TYPE, Payloads.LifeState.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Payloads.UiList.TYPE, Payloads.UiList.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Payloads.Dialogue.TYPE, Payloads.Dialogue.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Payloads.MapPlayers.TYPE, Payloads.MapPlayers.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Payloads.OpenScreen.TYPE, Payloads.OpenScreen.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Payloads.CinemaShow.TYPE, Payloads.CinemaShow.CODEC);
        earthlife.mod.cinema.CinemaContent.init();
        net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry.register(NPC, earthlife.mod.npc.NpcEntity.attributes());

        ServerLifecycleEvents.SERVER_STARTING.register(server -> earthSettings(server).ifPresent(st -> {
            FLIGHTS = new FlightDispatcher();
            NPCS = new earthlife.mod.npc.NpcManager();
            TRANSIT = new earthlife.mod.vehicle.TransitManager();
            EVENTS = new earthlife.mod.events.EventManager();
            HAUNTED = new earthlife.mod.events.HauntedHouse();
            lastPos.clear();
            EarthLifeServices.start(server, st);
            LOG.info("EarthLife: Earth world detected ({}, {}), services started", st.town(), st.state());
        }));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!EarthLifeServices.running()) return;
            var s = EarthLifeServices.get();
            long day = earthlife.mod.McCompat.dayTime(server.overworld()) / 24000;
            FLIGHTS.scheduleDay(s, day); FLIGHTS.scheduleDay(s, day + 1);
            LOG.info("EarthLife ready: {} airports, {} bookings restored from ledger", s.airports.size(), s.booking.restoreFromLedger());
            TRANSIT.restoreGarage();
            try { s.time.set(server, s.time.clock().mode(), s.time.clock().minutesPerDay()); } catch (Exception e) { LOG.error("time", e); }
            firstStart(server, s);
            int residents = s.populations.stream().mapToInt(p -> p.all().size()).sum();
            LOG.info("EarthLife: {} towns, {} residents, {} buildings ({} estimated), {} airports with terminals", s.towns.size(), residents,
                    s.features.buildings.size(), s.features.buildings.stream().filter(b -> b.prov() == earthlife.core.world.Features.Provenance.ESTIMATED).count(), s.airportLayouts.size());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { if (!EarthLifeServices.running()) return; NPCS.clear(); EVENTS.shutdown(); HAUNTED.clear();
            if (earthlife.mod.cinema.CinemaService.get() != null) earthlife.mod.cinema.CinemaService.get().clear();
            EarthLifeServices.stop(); });
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((h, server) -> {
            if (EarthLifeServices.running()) { earthlife.mod.npc.DialogueService.forget(h.getPlayer()); EarthLifeServices.get().life.store.saveDirty(); }
        });
        ServerTickEvents.END_SERVER_TICK.register(EarthLifeMod::tick);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> McCompat.onEntityLoad(entity));
        ServerPlayConnectionEvents.JOIN.register((h, sender, server) -> {
            ServerPlayer p = h.getPlayer();
            if (!EarthLifeServices.running()) return;
            server.execute(() -> {
                EarthLifeServices.get().life.onJoin(p);
                if (earthlife.mod.cinema.CinemaService.get() != null) earthlife.mod.cinema.CinemaService.get().sendAll(p);
            });
        });
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
            if (!EarthLifeServices.running()) return;
            EarthLifeServices.get().rewards.markActivity(player.getStringUUID(), world.getGameTime());
            if (player instanceof ServerPlayer sp) earthlife.mod.jobs.JobHooks.onBlockBroken(sp, pos);
        });
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (player instanceof ServerPlayer sp && EarthLifeServices.running()) {
                earthlife.mod.jobs.JobHooks.onContainerUsed(sp, hit.getBlockPos());
                if (sp.getItemInHand(hand).getItem() instanceof net.minecraft.world.item.BlockItem)
                    earthlife.mod.jobs.JobHooks.onBlockPlaced(sp, hit.getBlockPos().relative(hit.getDirection()));
            }
            return net.minecraft.world.InteractionResult.PASS;
        });
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, world, hand, entity, hit) ->
                player instanceof ServerPlayer sp && EarthLifeServices.running() && HAUNTED.onInteract(sp, entity)
                        ? net.minecraft.world.InteractionResult.SUCCESS : net.minecraft.world.InteractionResult.PASS);
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> V02Commands.register(dispatcher));
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> V03Commands.register(dispatcher));
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> V04Commands.register(dispatcher));
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> earthlife.mod.cinema.CinemaCommands.register(dispatcher));
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(Commands.literal("earthlife")
                .then(Commands.literal("wallet").executes(EarthLifeMod::wallet))
                .then(Commands.literal("claim").then(Commands.argument("kind", StringArgumentType.word()).executes(EarthLifeMod::claim)))
                .then(Commands.literal("flights").executes(EarthLifeMod::flights))
                .then(Commands.literal("seats").then(Commands.argument("flight", StringArgumentType.string()).executes(EarthLifeMod::seats)))
                .then(Commands.literal("book").then(Commands.argument("flight", StringArgumentType.string())
                        .then(Commands.argument("seat", StringArgumentType.word()).executes(EarthLifeMod::book))))
                .then(Commands.literal("whereami").executes(EarthLifeMod::whereami))));
    }

    private static void tick(MinecraftServer server) {
        if (!EarthLifeServices.running()) return;
        var s = EarthLifeServices.get();
        long now = server.overworld().getGameTime();
        List<int[]> viewers = new ArrayList<>();
        boolean anyFlying = false;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            String id = p.getStringUUID();
            Vec3 pos = p.position(), prev = lastPos.put(p.getUUID(), pos);
            // Moving, fighting or flying as a passenger counts as active play; standing still for 5+ minutes does not.
            if (prev == null || pos.distanceToSqr(prev) > 0.01 || p.hurtTime > 0) s.rewards.markActivity(id, now);
            s.rewards.tick(id, now);
            viewers.add(new int[]{p.getBlockX(), p.getBlockZ()});
            anyFlying |= p.getVehicle() instanceof AirplaneEntity;
            if (now % 20 == 0) earthlife.mod.net.Net.send(p, new Payloads.WalletStatus(s.ledger.balance(id), s.rewards.ticksUntilNextHourly(id),
                        s.rewards.hourlyAvailable(id), s.rewards.dailyAvailable(id, System.currentTimeMillis())));
        }
        if (now % 10 == 0) s.tiles.update(s.region, viewers, anyFlying ? earthlife.core.world.TileStreamer.Rings.FLYING : earthlife.core.world.TileStreamer.Rings.WALKING);
        FLIGHTS.tick(server);
        s.time.tick(server);
        NPCS.tick(server);
        TRANSIT.tick(server);
        EVENTS.tick(server);
        HAUNTED.tick(server);
        if (earthlife.mod.cinema.CinemaService.get() != null) earthlife.mod.cinema.CinemaService.get().tick(server);
        earthlife.mod.jobs.JobHooks.tick(server);
        if (now % 20 == 0) {
            java.time.ZoneId zone = s.time.clock().zone();
            boolean halloween = earthlife.mod.events.Halloween.isSeason(zone);
            String eventLine = halloween ? earthlife.mod.events.Halloween.countdown(zone) : EVENTS.headline();
            String clock = s.time.describe(earthlife.mod.McCompat.dayTime(server.overworld()));
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                String pid = p.getStringUUID();
                boolean onShift = s.jobs.shift(pid).isPresent();
                String job = s.jobs.employment(pid).map(e -> e.job().title + " at " + e.employer() + (onShift ? " · on shift, " + s.jobs.shift(pid).get().tasks().size() + " tasks" : " · off duty")).orElse("");
                earthlife.mod.net.Net.send(p, new Payloads.HudStatus(clock, job));
                s.life.sendState(p, clock, job, onShift, eventLine, halloween);
            }
            if (now % 40 == 0) s.life.sendSharedPlayers(server);
            s.life.tickObjectives(server);
        }
        if (now % 600 == 0) s.life.store.saveDirty();
        if (now % 6000 == 0) { try { s.rewards.save(); s.tiles.edits().flush(); } catch (Exception e) { LOG.error("EarthLife autosave failed", e); } }
    }

    /** Once per world: start time from the Customize screen, and the world spawn moved to the safe start point. */
    static void firstStart(MinecraftServer server, EarthLifeServices s) {
        java.nio.file.Path marker = s.worldDir.resolve("initialized.json");
        if (java.nio.file.Files.exists(marker)) return;
        try {
            s.life.setWorldSpawn(server);
            if (s.time.clock().mode() == earthlife.core.time.GameClock.Mode.VANILLA || s.time.clock().mode() == earthlife.core.time.GameClock.Mode.SCALED) {
                java.time.LocalTime local = java.time.LocalTime.now(s.time.clock().zone());
                int hour = switch (s.settings.startTimeEnum()) {
                    case MORNING -> 7; case NOON -> 12; case EVENING -> 18; case NIGHT -> 22; case REAL_TIME -> local.getHour();
                };
                int minute = s.settings.startTimeEnum() == earthlife.mod.world.EarthSettings.StartTime.REAL_TIME ? local.getMinute() : 0;
                // Minecraft day ticks: 0 = 06:00, 1000 ticks per hour.
                long ticks = Math.floorMod((hour - 6) * 1000L + minute * 1000L / 60, 24000L);
                earthlife.mod.McCompat.setDayTime(server, ticks);
            }
            earthlife.core.world.AtomicFiles.write(marker, ("{\"version\":\"0.4.0\",\"created\":\"" + java.time.Instant.now() + "\"}").getBytes());
        } catch (Exception e) { LOG.error("EarthLife first start", e); }
    }

    // ---- commands ----
    private static ServerPlayer player(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return c.getSource().getPlayerOrException();
    }

    private static int wallet(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!EarthLifeServices.running()) { c.getSource().sendFailure(Component.literal("EarthLife commands work in Earth worlds.")); return 0; }
        var s = EarthLifeServices.get();
        ServerPlayer p = player(c);
        long left = s.rewards.ticksUntilNextHourly(p.getStringUUID()) / 20;
        p.sendSystemMessage(Component.literal(String.format("LifeCoins: %,d LC · next hourly reward in %d:%02d of active play%s",
                s.ledger.balance(p.getStringUUID()), left / 60, left % 60, s.rewards.dailyAvailable(p.getStringUUID(), System.currentTimeMillis()) ? " · daily reward ready!" : "")));
        return 1;
    }

    private static int claim(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!EarthLifeServices.running()) { c.getSource().sendFailure(Component.literal("EarthLife commands work in Earth worlds.")); return 0; }
        var s = EarthLifeServices.get();
        ServerPlayer p = player(c);
        String kind = StringArgumentType.getString(c, "kind");
        long tick = p.level().getGameTime();
        try {
            Optional<Ledger.Entry> e = switch (kind) {
                case "hourly" -> s.rewards.claimHourly(p.getStringUUID(), tick);
                case "daily" -> s.rewards.claimDaily(p.getStringUUID(), System.currentTimeMillis(), tick);
                case "weekly" -> s.rewards.claimWeekly(p.getStringUUID(), System.currentTimeMillis(), tick);
                default -> Optional.empty();
            };
            p.sendSystemMessage(Component.literal(e.map(x -> "+" + x.amount() + " LC (" + kind + "). Balance " + x.balanceAfter() + " LC")
                    .orElse("No " + kind + " reward available yet.")));
        } catch (Exception ex) { p.sendSystemMessage(Component.literal("Reward error: " + ex.getMessage())); }
        return 1;
    }

    private static int flights(CommandContext<CommandSourceStack> c) {
        if (!EarthLifeServices.running()) { c.getSource().sendFailure(Component.literal("EarthLife commands work in Earth worlds.")); return 0; }
        var s = EarthLifeServices.get();
        if (c.getSource().getPlayer() != null) s.life.complete(c.getSource().getPlayer(), "view_flights");
        long day = earthlife.mod.McCompat.dayTime(c.getSource().getServer().overworld()) / 24000;
        for (var f : s.booking.search(null, null)) {
            if (f.departureGameDay() < day) continue;
            long dep = f.departureTimeTicks();
            String time = String.format("%02d:%02d", (dep / 1000 + 6) % 24, dep % 1000 * 60 / 1000);
            c.getSource().sendSystemMessage(Component.literal(String.format("%s  %s → %s  day %d %s  %d LC  %d seats  (%.0f km)",
                    f.id(), f.from().icao(), f.to().icao(), f.departureGameDay(), time, s.booking.priceFor(f), s.booking.availableSeats(f).size(), f.distanceKm())));
        }
        return 1;
    }

    private static int seats(CommandContext<CommandSourceStack> c) {
        if (!EarthLifeServices.running()) { c.getSource().sendFailure(Component.literal("EarthLife commands work in Earth worlds.")); return 0; }
        var s = EarthLifeServices.get();
        var f = s.booking.flight(StringArgumentType.getString(c, "flight"));
        c.getSource().sendSystemMessage(Component.literal(f == null ? "No such flight." : "Free seats: " + String.join(" ", s.booking.availableSeats(f))));
        return 1;
    }

    private static int book(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!EarthLifeServices.running()) { c.getSource().sendFailure(Component.literal("EarthLife commands work in Earth worlds.")); return 0; }
        var s = EarthLifeServices.get();
        ServerPlayer p = player(c);
        try {
            String fid = StringArgumentType.getString(c, "flight"), seat = StringArgumentType.getString(c, "seat").toUpperCase(Locale.ROOT);
            if (seat.equals("AUTO")) {
                var f = s.booking.flight(fid);
                var free = f == null ? List.<String>of() : s.booking.availableSeats(f);
                if (free.isEmpty()) { p.sendSystemMessage(Component.literal("No free seats on " + fid + ".")); return 0; }
                seat = free.get(0);
            }
            var bp = s.booking.purchase(p.getStringUUID(), fid, seat, p.level().getGameTime());
            s.life.complete(p, "book_flight");
            p.sendSystemMessage(Component.literal("✓ Booked " + bp.flightNumber() + " " + bp.from() + "→" + bp.to() + ", seat " + bp.seat()
                    + ", gate " + bp.gate() + ". Boarding pass " + bp.code() + ". Paid " + bp.priceLC() + " LC."));
        } catch (Ledger.InsufficientFunds e) {
            p.sendSystemMessage(Component.literal("Not enough LifeCoins: " + e.getMessage() + ". Play, work or claim rewards to earn more."));
        } catch (Exception e) {
            p.sendSystemMessage(Component.literal("Booking failed: " + e.getMessage()));
        }
        return 1;
    }

    private static int whereami(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!EarthLifeServices.running()) { c.getSource().sendFailure(Component.literal("EarthLife commands work in Earth worlds.")); return 0; }
        var s = EarthLifeServices.get();
        ServerPlayer p = player(c);
        var g = s.projection.fromBlock(p.getX(), p.getZ());
        p.sendSystemMessage(Component.literal(String.format("%.5f, %.5f · elevation %.0f m · %s", g.lat(), g.lon(), s.projection.yToElevation(p.getY()), s.attribution)));
        return 1;
    }
}
