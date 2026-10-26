package earthlife.mod.zmf;

import earthlife.mod.EarthLifeMod;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * ZMF Cinema v0.1 (part of EarthLife): procedurally generated 12-32 screen multiplexes in ordinary Minecraft worlds,
 * scheduled films from players' own libraries, web-cinema rooms, automatic 3D and 4DX. Server side setup.
 */
public final class ZmfCinema {
    private ZmfCinema() {}
    public static final String VERSION = "0.1";

    public static void init() {
        PayloadTypeRegistry.clientboundPlay().register(ZmfPayloads.Schedule.TYPE, ZmfPayloads.Schedule.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ZmfPayloads.Nearest.TYPE, ZmfPayloads.Nearest.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ZmfPayloads.Catalog.TYPE, ZmfPayloads.Catalog.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ZmfPayloads.Catalog.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
            if (MultiplexService.get() != null) MultiplexService.get().receiveCatalog(ctx.player(), payload);
        }));
        // after EarthLife's own SERVER_STARTED (Earth worlds create their CinemaService while starting)
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try { ZmfConfig.load(); MultiplexService.start(server); }
            catch (Exception e) { EarthLifeMod.LOG.error("ZMF Cinema failed to start", e); }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> MultiplexService.stop());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            var s = MultiplexService.get();
            if (s == null) return;
            try {
                s.tick(server);
                // Earth worlds tick their CinemaService themselves; ordinary worlds do it here
                if (!earthlife.mod.EarthLifeServices.running() && earthlife.mod.cinema.CinemaService.get() != null) earthlife.mod.cinema.CinemaService.get().tick(server);
            } catch (Exception e) { EarthLifeMod.LOG.error("ZMF Cinema tick", e); }
        });
        ServerPlayConnectionEvents.JOIN.register((h, sender, server) -> server.execute(() -> {
            if (!earthlife.mod.EarthLifeServices.running() && earthlife.mod.cinema.CinemaService.get() != null) earthlife.mod.cinema.CinemaService.get().sendAll(h.getPlayer());
        }));
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> ZmfCommands.register(dispatcher));
        EarthLifeMod.LOG.info("ZMF Cinema {} ready", VERSION);
    }
}
