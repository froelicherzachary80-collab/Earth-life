package earthlife.mod.client;

import earthlife.mod.EarthLifeMod;
import earthlife.mod.net.Payloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.NoopRenderer;

/**
 * Client: seatback map [M], wallet/booking [L], and a small HUD showing LifeCoins and the hourly countdown.
 * The airliner's hull is drawn by vanilla block-display entities, so the airplane entity itself renders nothing.
 * UNVERIFIED: KeyMapping category and HUD callback APIs changed in late 1.21.x; adjust at first compile.
 */
public final class EarthLifeClient implements net.fabricmc.api.ClientModInitializer {
    public static volatile Payloads.FlightTelemetry telemetry;
    public static volatile Payloads.WalletStatus wallet;
    public static volatile long telemetryAtMs;
    public static volatile Payloads.HudStatus hud;

    @Override public void onInitializeClient() {
        EntityRendererRegistry.register(EarthLifeMod.AIRPLANE, NoopRenderer::new);
        EntityRendererRegistry.register(EarthLifeMod.VEHICLE, NoopRenderer::new);        // models are block displays
        EntityRendererRegistry.register(EarthLifeMod.NPC, NpcRenderer::new);
        ClientPlayNetworking.registerGlobalReceiver(Payloads.CabinState.TYPE, (p, ctx) -> {
            for (int[] w : p.walkers()) earthlife.mod.aviation.AirplaneEntity.CLIENT_WALKERS.put(w[0], new double[]{w[2] / 1000.0, w[3] / 1000.0, w[1]});
        });
        ClientPlayNetworking.registerGlobalReceiver(Payloads.HudStatus.TYPE, (p, ctx) -> hud = p);
        ClientPlayNetworking.registerGlobalReceiver(Payloads.FlightTelemetry.TYPE, (p, ctx) -> { telemetry = p; telemetryAtMs = System.currentTimeMillis(); });
        ClientPlayNetworking.registerGlobalReceiver(Payloads.WalletStatus.TYPE, (p, ctx) -> wallet = p);

        KeyMapping.Category cat = KeyMapping.Category.register(EarthLifeMod.id("earthlife"));
        KeyMapping seatback = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.earthlife.seatback", 77, cat));
        KeyMapping walletKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.earthlife.wallet", 76, cat));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (seatback.consumeClick()) mc.gui.setScreen(new SeatbackScreen());
            while (walletKey.consumeClick()) mc.gui.setScreen(new WalletScreen());
        });

        HudElementRegistry.addLast(EarthLifeMod.id("hud"), (g, delta) -> {
            var w = wallet;
            Minecraft mc = Minecraft.getInstance();
            if (w == null || mc.gui.hud.isHidden()) return;
            long s = w.ticksUntilHourly() / 20;
            String line = String.format("%,d LC", w.balance()) + (w.hourlyReady() > 0 ? "  ★ hourly ready [L]" : String.format("  next +150 in %d:%02d", s / 60, s % 60))
                    + (w.dailyReady() ? "  ★ daily ready" : "");
            int x = mc.getWindow().getGuiScaledWidth() - mc.font.width(line) - 6;
            g.text(mc.font, line, x, 6, 0xFFFFD54F, true);
            var h = hud;
            if (h != null) {
                g.text(mc.font, h.clock(), mc.getWindow().getGuiScaledWidth() - mc.font.width(h.clock()) - 6, 18, 0xFFB3E5FC, true);
                if (!h.job().isEmpty()) g.text(mc.font, h.job(), mc.getWindow().getGuiScaledWidth() - mc.font.width(h.job()) - 6, 30, 0xFFC8E6C9, true);
            }
        });
    }
}
