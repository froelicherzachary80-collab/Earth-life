package earthlife.mod.client;

import com.mojang.blaze3d.platform.InputConstants;
import earthlife.mod.EarthLifeMod;
import earthlife.mod.client.map.ClientMapData;
import earthlife.mod.client.screen.DialogueScreen;
import earthlife.mod.client.screen.EarthMenuScreen;
import earthlife.mod.client.screen.LifeMapScreen;
import earthlife.mod.net.Payloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.entity.NoopRenderer;

import java.util.List;

/**
 * Client side: keybinds (M LifeMap, K menu, J jobs, L wallet, N nearby - all in the "EarthLife" controls category),
 * packet receivers, the HUD (LifeCoins, clock, job, objectives, route hint) and the first-join welcome card.
 * In non-Earth worlds the keys show a short note and the HUD stays hidden.
 */
public final class EarthLifeClient implements ClientModInitializer {
    public static volatile Payloads.FlightTelemetry telemetry;
    public static volatile Payloads.WalletStatus wallet;
    public static volatile long telemetryAtMs;
    public static volatile Payloads.HudStatus hud;

    public static KeyMapping MAP, MENU, JOBS, WALLET, NEARBY;
    static long welcomeUntilMs;

    public static void command(String c) {
        var mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.connection.sendCommand(c);
    }

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
        ClientPlayNetworking.registerGlobalReceiver(Payloads.WorldInfo.TYPE, (p, ctx) -> {
            ClientState.world = p;
            ClientMapData.load(p);
            if (p.welcome()) welcomeUntilMs = System.currentTimeMillis() + 45_000;
        });
        ClientPlayNetworking.registerGlobalReceiver(Payloads.LifeState.TYPE, (p, ctx) -> { ClientState.life = p; ClientState.lifeAtMs = System.currentTimeMillis(); });
        ClientPlayNetworking.registerGlobalReceiver(Payloads.UiList.TYPE, (p, ctx) -> { ClientState.lists.put(p.tab(), p); EarthMenuScreen.listVersion++; });
        ClientPlayNetworking.registerGlobalReceiver(Payloads.MapPlayers.TYPE, (p, ctx) -> ClientState.sharedPlayers = p.players());
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Dialogue.TYPE, (p, ctx) -> ctx.client().gui.setScreen(new DialogueScreen(p)));
        ClientPlayNetworking.registerGlobalReceiver(Payloads.OpenScreen.TYPE, (p, ctx) -> open(ctx.client(), p.screen()));
        ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> { ClientState.reset(); ClientMapData.unload(); welcomeUntilMs = 0; });

        KeyMapping.Category cat = KeyMapping.Category.register(EarthLifeMod.id("earthlife"));
        MAP = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.earthlife.map", InputConstants.KEY_M, cat));
        MENU = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.earthlife.menu", InputConstants.KEY_K, cat));
        JOBS = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.earthlife.jobs", InputConstants.KEY_J, cat));
        WALLET = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.earthlife.wallet", InputConstants.KEY_L, cat));
        NEARBY = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.earthlife.nearby", InputConstants.KEY_N, cat));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (MAP.consumeClick()) {
                boolean flying = telemetry != null && System.currentTimeMillis() - telemetryAtMs < 5000 && mc.player != null
                        && mc.player.getVehicle() instanceof earthlife.mod.aviation.AirplaneEntity;
                open(mc, flying ? "seatback" : "map");
            }
            while (MENU.consumeClick()) open(mc, "menu:life");
            while (JOBS.consumeClick()) open(mc, "menu:jobs");
            while (WALLET.consumeClick()) open(mc, "menu:wallet");
            while (NEARBY.consumeClick()) open(mc, "menu:nearby");
        });
        HudElementRegistry.addLast(EarthLifeMod.id("hud"), (g, delta) -> drawHud(g));
    }

    /** Opens a client screen by name: map, map:route:x:z:label, map:clear, menu:<tab>, seatback. */
    public static void open(Minecraft mc, String screen) {
        if (welcomeUntilMs > 0) { welcomeUntilMs = 0; command("earthlife ui welcomed"); }
        if (!ClientState.earth() && !screen.equals("seatback")) {
            if (mc.player != null) mc.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal("EarthLife screens work in Earth worlds (World Type: Earth - 1:1 Real World)."));
            return;
        }
        if (screen.equals("map")) mc.gui.setScreen(new LifeMapScreen());
        else if (screen.equals("map:clear")) { ClientState.route = List.of(); ClientState.routeTarget = null; }
        else if (screen.startsWith("map:route:")) {
            String[] a = screen.split(":", 5);
            try { mc.gui.setScreen(LifeMapScreen.withRoute(Double.parseDouble(a[2]), Double.parseDouble(a[3]), a.length > 4 ? a[4] : "destination")); }
            catch (NumberFormatException ignored) {}
        } else if (screen.startsWith("menu:")) mc.gui.setScreen(new EarthMenuScreen(screen.substring(5)));
        else if (screen.equals("seatback")) mc.gui.setScreen(new SeatbackScreen());
    }

    static void drawHud(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.hud.isHidden() || mc.player == null || !ClientState.earth()) return;
        var font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        var life = ClientState.life;
        int y = 6;
        if (life != null) {
            String money = String.format("%,d LC", life.balance()) + (life.hourlyReady() ? "  ★ hourly reward [L]" : "") + (life.dailyReady() ? "  ★ daily" : "");
            g.text(font, money, sw - font.width(money) - 6, y, 0xFFFFD54F, true); y += 11;
            g.text(font, life.clock(), sw - font.width(life.clock()) - 6, y, 0xFFB3E5FC, true); y += 11;
            if (!life.job().isEmpty()) { g.text(font, life.job(), sw - font.width(life.job()) - 6, y, 0xFFC8E6C9, true); y += 11; }
            g.text(font, life.place(), sw - font.width(life.place()) - 6, y, 0xFFFFFFFF, true); y += 11;
            if (!life.eventLine().isEmpty()) { g.text(font, life.eventLine(), sw - font.width(life.eventLine()) - 6, y, 0xFFFFAB40, true); y += 11; }
            // next objectives
            int shown = 0;
            for (String o : life.objectives()) {
                if (o.startsWith("✔") || shown >= 3) continue;
                if (shown == 0) { y += 4; g.text(font, "Objectives", sw - font.width("Objectives") - 6, y, 0xFFFFFFFF, true); y += 11; }
                g.text(font, o, sw - font.width(o) - 6, y, 0xFFE0E0E0, true); y += 10; shown++;
            }
        }
        var route = ClientState.routeTarget;
        if (route != null) {
            double dx = route[0] - mc.player.getX(), dz = route[1] - mc.player.getZ(), d = Math.hypot(dx, dz);
            if (d < 12) { ClientState.routeTarget = null; ClientState.route = List.of(); mc.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal("You've arrived: " + ClientState.routeLabel)); }
            else {
                double bearing = Math.toDegrees(Math.atan2(-dx, dz));          // Minecraft yaw convention
                double rel = ((bearing - mc.player.getYRot()) % 360 + 540) % 360 - 180;
                String arrow = Math.abs(rel) < 22 ? "↑" : Math.abs(rel) > 158 ? "↓" : rel > 0 ? (rel < 68 ? "↗" : rel < 112 ? "→" : "↘") : (rel > -68 ? "↖" : rel > -112 ? "←" : "↙");
                String txt = arrow + " " + ClientState.routeLabel + " · " + (d >= 1000 ? String.format("%.1f km", d / 1000) : String.format("%.0f m", d));
                g.text(font, txt, (sw - font.width(txt)) / 2, 6, 0xFF64B5F6, true);
            }
        }
        if (welcomeUntilMs > System.currentTimeMillis() && ClientState.world != null) {
            var w = ClientState.world;
            int bw = Math.min(320, sw - 20), bx = (sw - bw) / 2, by = sh / 2 - 70;
            g.fill(bx - 6, by - 6, bx + bw + 6, by + 96, 0xD0101418);
            g.centeredText(font, "§6Welcome to " + w.startTown() + "!", sw / 2, by, 0xFFFFFFFF);
            g.textWithWordWrap(font, net.minecraft.network.chat.Component.literal("You're standing in a 1:1 copy of the real place, built from real map data. "
                    + "Scenario: " + w.scenario().replace('_', ' ').toLowerCase() + "."), bx, by + 14, bw, 0xFFE0E0E0);
            g.centeredText(font, "§eK§r menu · §eM§r map · §eJ§r jobs · §eL§r wallet · §eN§r nearby", sw / 2, by + 50, 0xFFFFFFFF);
            g.centeredText(font, "Press any EarthLife key to begin", sw / 2, by + 64, 0xFFBDBDBD);
            g.centeredText(font, "Map data © OpenStreetMap contributors", sw / 2, by + 80, 0xFF8D8D8D);
        } else if (welcomeUntilMs != 0 && welcomeUntilMs <= System.currentTimeMillis()) {
            welcomeUntilMs = 0;
            command("earthlife ui welcomed");
        }
    }
}
