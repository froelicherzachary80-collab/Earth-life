package earthlife.mod.client;

import com.mojang.blaze3d.platform.InputConstants;
import earthlife.mod.EarthLifeMod;
import earthlife.mod.net.Payloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.NoopRenderer;
import org.lwjgl.glfw.GLFW;

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

        KeyMapping seatback = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.earthlife.seatback", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, "key.categories.earthlife"));
        KeyMapping walletKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.earthlife.wallet", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_L, "key.categories.earthlife"));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (seatback.consumeClick()) mc.setScreen(new SeatbackScreen());
            while (walletKey.consumeClick()) mc.setScreen(new WalletScreen());
        });

        HudRenderCallback.EVENT.register((g, delta) -> {
            var w = wallet;
            Minecraft mc = Minecraft.getInstance();
            if (w == null || mc.options.hideGui) return;
            long s = w.ticksUntilHourly() / 20;
            String line = String.format("%,d LC", w.balance()) + (w.hourlyReady() > 0 ? "  ★ hourly ready [L]" : String.format("  next +150 in %d:%02d", s / 60, s % 60))
                    + (w.dailyReady() ? "  ★ daily ready" : "");
            int x = mc.getWindow().getGuiScaledWidth() - mc.font.width(line) - 6;
            g.drawString(mc.font, line, x, 6, 0xFFFFD54F, true);
            var h = hud;
            if (h != null) {
                g.drawString(mc.font, h.clock(), mc.getWindow().getGuiScaledWidth() - mc.font.width(h.clock()) - 6, 18, 0xFFB3E5FC, true);
                if (!h.job().isEmpty()) g.drawString(mc.font, h.job(), mc.getWindow().getGuiScaledWidth() - mc.font.width(h.job()) - 6, 30, 0xFFC8E6C9, true);
            }
        });
    }
}
