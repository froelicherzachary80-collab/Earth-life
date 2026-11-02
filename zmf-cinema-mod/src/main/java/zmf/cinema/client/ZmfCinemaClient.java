// STANDALONE
package zmf.cinema.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.network.chat.Component;
import zmf.cinema.ZmfCinemaMod;
import zmf.cinema.ZmfPayloads;
import zmf.cinema.block.CinemaContent;
import zmf.cinema.client.media.*;
import zmf.cinema.net.Payloads;

/** ZMF Cinema client: key B (menu / web browser), END (4DX + TactSuit emergency stop), screens, HUD, packets. */
public final class ZmfCinemaClient implements ClientModInitializer {
    public static KeyMapping ZMF, FX_STOP;

    public static void command(String c) {
        var mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.connection.sendCommand(c);
    }

    @Override public void onInitializeClient() {
        CinemaConfig.load();
        EntityRendererRegistry.register(CinemaContent.SEAT, NoopRenderer::new);
        EntityRendererRegistry.register(CinemaContent.SCREEN, CinemaScreenRenderer::new);
        ClientPlayNetworking.registerGlobalReceiver(Payloads.CinemaShow.TYPE, (p, ctx) -> CinemaClient.receive(p));
        ClientPlayNetworking.registerGlobalReceiver(Payloads.UiList.TYPE, (p, ctx) -> { ClientState.lists.put(p.tab(), p); ZmfScreen.listVersion++; });
        ClientPlayNetworking.registerGlobalReceiver(Payloads.OpenScreen.TYPE, (p, ctx) -> open(ctx.client(), p.screen()));
        ClientPlayNetworking.registerGlobalReceiver(ZmfPayloads.Schedule.TYPE, (p, ctx) -> ZmfState.receive(p));
        ClientPlayNetworking.registerGlobalReceiver(ZmfPayloads.Nearest.TYPE, (p, ctx) -> { ZmfState.nearest = p; ZmfState.nearestAtMs = System.currentTimeMillis(); });
        ClientPlayConnectionEvents.JOIN.register((h, sender, mc) -> mc.execute(LibraryShare::onJoin));
        ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> { CinemaClient.reset(); Haptics.close(); ZmfState.reset(); Posters.reset(); ClientState.reset(); });
        ClientTickEvents.END_CLIENT_TICK.register(CinemaClient::tick);
        ClientTickEvents.END_CLIENT_TICK.register(FourD::tick);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> Haptics.tick());
        HudElementRegistry.addLast(ZmfCinemaMod.id("cinema_4d"), (g, delta) -> FourD.drawHud(g, Minecraft.getInstance()));
        HudElementRegistry.addLast(ZmfCinemaMod.id("locator"), (g, delta) -> LocatorHud.draw(g, Minecraft.getInstance()));
        // optional "ZMF Cinema HD" textures (with LabPBR maps for shader packs)
        net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(ZmfCinemaMod.MOD_ID).ifPresent(c ->
                net.fabricmc.fabric.api.resource.ResourceManagerHelper.registerBuiltinResourcePack(ZmfCinemaMod.id("zmf_hd"), c,
                        Component.literal("ZMF Cinema HD (+ PBR maps)"), net.fabricmc.fabric.api.resource.ResourcePackActivationType.NORMAL));

        KeyMapping.Category cat = KeyMapping.Category.register(ZmfCinemaMod.id("zmfcinema"));
        ZMF = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.zmfcinema.menu", InputConstants.KEY_B, cat));
        FX_STOP = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.zmfcinema.fx_stop", InputConstants.KEY_END, cat));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (ZMF.consumeClick()) {
                if (mc.player == null) continue;
                if (BrowserScreens.current() != null) mc.gui.setScreen(new BrowserControlScreen());
                else { command("zmfcinema ui showtimes"); mc.gui.setScreen(new ZmfScreen("showtimes")); }
            }
            while (FX_STOP.consumeClick()) {
                FourD.stopped = !FourD.stopped;
                if (FourD.stopped) Haptics.emergencyStop();
                if (mc.player != null) mc.player.sendOverlayMessage(Component.literal(FourD.stopped
                        ? "4DX STOPPED - seat motion, effects and TactSuit off (END to resume)" : "4DX resumed"));
            }
        });
    }

    /** Opens a client screen by name: zmf:<tab>, cinema:pick:<aud>:<4d|std>[3d]:<label>. */
    public static void open(Minecraft mc, String screen) {
        if (screen.startsWith("zmf:")) mc.gui.setScreen(new ZmfScreen(screen.substring(4)));
        else if (screen.startsWith("cinema:pick:")) {
            String[] a = screen.split(":", 5);
            if (a.length >= 4) {
                var picker = new MoviePickerScreen(a[2], a[3].startsWith("4d"), a.length > 4 ? a[4] : "Cinema");
                picker.room3d = a[3].endsWith("3d");
                mc.gui.setScreen(picker);
            }
        }
    }
}
