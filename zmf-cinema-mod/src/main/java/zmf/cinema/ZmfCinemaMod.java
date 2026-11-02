// STANDALONE
package zmf.cinema;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zmf.cinema.block.CinemaCommands;
import zmf.cinema.block.CinemaContent;
import zmf.cinema.block.CinemaService;
import zmf.cinema.net.Payloads;

/**
 * ZMF Cinema 0.1.0 - a standalone Fabric mod for Minecraft Java 26.3: 12-32 screen multiplex cinemas generate while
 * you explore, every player starts with a ZMF Cinema Pass that takes them to the nearest one, and the films come from
 * the players' own Jellyfin servers or video folders (automatic red/cyan 3D, automatic 4DX, IMAX, ScreenX, web cinemas
 * with MCEF Modern, bHaptics TactSuit).
 */
public final class ZmfCinemaMod implements ModInitializer {
    public static final String MOD_ID = "zmfcinema", VERSION = "0.1.0";
    public static final Logger LOG = LoggerFactory.getLogger("ZMF Cinema");

    public static Identifier id(String path) { return Identifier.fromNamespaceAndPath(MOD_ID, path); }

    @Override public void onInitialize() {
        CinemaContent.init();
        PayloadTypeRegistry.clientboundPlay().register(Payloads.CinemaShow.TYPE, Payloads.CinemaShow.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Payloads.UiList.TYPE, Payloads.UiList.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Payloads.OpenScreen.TYPE, Payloads.OpenScreen.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ZmfPayloads.Schedule.TYPE, ZmfPayloads.Schedule.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ZmfPayloads.Nearest.TYPE, ZmfPayloads.Nearest.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ZmfPayloads.Catalog.TYPE, ZmfPayloads.Catalog.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ZmfPayloads.Catalog.TYPE, (payload, ctx) -> ctx.server().execute(() -> {
            if (MultiplexService.get() != null) MultiplexService.get().receiveCatalog(ctx.player(), payload);
        }));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try { ZmfConfig.load(); MultiplexService.start(server); }
            catch (Exception e) { LOG.error("ZMF Cinema failed to start", e); }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> MultiplexService.stop());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            var s = MultiplexService.get();
            if (s == null) return;
            try {
                s.tick(server);
                if (CinemaService.get() != null) CinemaService.get().tick(server);
            } catch (Exception e) { LOG.error("ZMF Cinema tick", e); }
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> McCompat.onEntityLoad(entity));
        ServerPlayConnectionEvents.JOIN.register((h, sender, server) -> server.execute(() -> {
            var s = MultiplexService.get();
            if (s != null) s.giveStarterPass(h.getPlayer());
            if (CinemaService.get() != null) CinemaService.get().sendAll(h.getPlayer());
        }));
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> { ZmfCommands.register(dispatcher); CinemaCommands.register(dispatcher); });
        LOG.info("ZMF Cinema {} ready", VERSION);
    }
}
