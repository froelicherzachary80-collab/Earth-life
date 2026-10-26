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

    public static KeyMapping MAP, MENU, JOBS, WALLET, NEARBY, ZMF, FX_STOP;
    static long welcomeUntilMs;

    public static void command(String c) {
        var mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.connection.sendCommand(c);
    }

    @Override public void onInitializeClient() {
        ClientConfig.load();
        EntityRendererRegistry.register(EarthLifeMod.AIRPLANE, NoopRenderer::new);
        EntityRendererRegistry.register(EarthLifeMod.VEHICLE, earthlife.mod.client.car.CarRenderer::new);   // cars: client model; others: block displays
        EntityRendererRegistry.register(EarthLifeMod.NPC, NpcRenderer::new);

        ClientPlayNetworking.registerGlobalReceiver(Payloads.CabinState.TYPE, (p, ctx) -> {
            for (int[] w : p.walkers()) earthlife.mod.aviation.AirplaneEntity.CLIENT_WALKERS.put(w[0], new double[]{w[2] / 1000.0, w[3] / 1000.0, w[1]});
        });
        ClientPlayNetworking.registerGlobalReceiver(Payloads.HudStatus.TYPE, (p, ctx) -> hud = p);
        ClientPlayNetworking.registerGlobalReceiver(Payloads.FlightTelemetry.TYPE, (p, ctx) -> { telemetry = p; telemetryAtMs = System.currentTimeMillis(); });
        ClientPlayNetworking.registerGlobalReceiver(Payloads.WalletStatus.TYPE, (p, ctx) -> wallet = p);
        ClientPlayNetworking.registerGlobalReceiver(Payloads.CinemaShow.TYPE, (p, ctx) -> earthlife.mod.client.cinema.CinemaClient.receive(p));
        earthlife.mod.client.cinema.CinemaConfig.load();
        // optional "ZMF Cinema HD" textures (with LabPBR maps for shader packs); off unless the player enables it
        net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("earthlife").ifPresent(c ->
                net.fabricmc.fabric.api.resource.ResourceManagerHelper.registerBuiltinResourcePack(EarthLifeMod.id("zmf_hd"), c,
                        net.minecraft.network.chat.Component.literal("ZMF Cinema HD (+ PBR maps)"), net.fabricmc.fabric.api.resource.ResourcePackActivationType.NORMAL));
        EntityRendererRegistry.register(earthlife.mod.cinema.CinemaContent.SEAT, NoopRenderer::new);
        EntityRendererRegistry.register(earthlife.mod.cinema.CinemaContent.SCREEN, earthlife.mod.client.cinema.CinemaScreenRenderer::new);
        ClientTickEvents.END_CLIENT_TICK.register(earthlife.mod.client.cinema.CinemaClient::tick);
        ClientTickEvents.END_CLIENT_TICK.register(earthlife.mod.client.cinema.FourD::tick);
        HudElementRegistry.addLast(EarthLifeMod.id("cinema_4d"), (g, delta) -> earthlife.mod.client.cinema.FourD.drawHud(g, Minecraft.getInstance()));
        ClientPlayNetworking.registerGlobalReceiver(Payloads.WorldInfo.TYPE, (p, ctx) -> {
            ClientState.world = p;
            ClientMapData.load(p);
            if (p.welcome()) welcomeUntilMs = System.currentTimeMillis() + 45_000;
        });
        ClientPlayNetworking.registerGlobalReceiver(Payloads.LifeState.TYPE, (p, ctx) -> { ClientState.life = p; ClientState.lifeAtMs = System.currentTimeMillis(); });
        ClientPlayNetworking.registerGlobalReceiver(Payloads.UiList.TYPE, (p, ctx) -> { ClientState.lists.put(p.tab(), p); EarthMenuScreen.listVersion++; earthlife.mod.client.zmf.ZmfScreen.listVersion++; });
        ClientPlayNetworking.registerGlobalReceiver(earthlife.mod.zmf.ZmfPayloads.Schedule.TYPE, (p, ctx) -> earthlife.mod.client.zmf.ZmfState.receive(p));
        ClientPlayNetworking.registerGlobalReceiver(earthlife.mod.zmf.ZmfPayloads.Nearest.TYPE, (p, ctx) -> { earthlife.mod.client.zmf.ZmfState.nearest = p; earthlife.mod.client.zmf.ZmfState.nearestAtMs = System.currentTimeMillis(); });
        ClientPlayConnectionEvents.JOIN.register((h, sender, mc) -> mc.execute(earthlife.mod.client.zmf.LibraryShare::onJoin));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> earthlife.mod.client.cinema.Haptics.tick());
        HudElementRegistry.addLast(EarthLifeMod.id("zmf_locator"), (g, delta) -> earthlife.mod.client.zmf.LocatorHud.draw(g, Minecraft.getInstance()));
        ClientPlayNetworking.registerGlobalReceiver(Payloads.MapPlayers.TYPE, (p, ctx) -> ClientState.sharedPlayers = p.players());
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Dialogue.TYPE, (p, ctx) -> ctx.client().gui.setScreen(new DialogueScreen(p)));
        ClientPlayNetworking.registerGlobalReceiver(Payloads.OpenScreen.TYPE, (p, ctx) -> open(ctx.client(), p.screen()));
        ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> { ClientState.reset(); ClientMapData.unload(); welcomeUntilMs = 0; earthlife.mod.client.cinema.CinemaClient.reset(); earthlife.mod.client.cinema.Haptics.close();
            earthlife.mod.client.zmf.ZmfState.reset(); earthlife.mod.client.zmf.Posters.reset(); });

        KeyMapping.Category cat = KeyMapping.Category.register(EarthLifeMod.id("earthlife"));
        MAP = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.earthlife.map", InputConstants.KEY_M, cat));
        MENU = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.earthlife.menu", InputConstants.KEY_K, cat));
        JOBS = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.earthlife.jobs", InputConstants.KEY_J, cat));
        WALLET = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.earthlife.wallet", InputConstants.KEY_L, cat));
        NEARBY = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.earthlife.nearby", InputConstants.KEY_N, cat));
        ZMF = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.earthlife.zmf", InputConstants.KEY_B, cat));
        FX_STOP = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.earthlife.fx_stop", InputConstants.KEY_END, cat));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (MAP.consumeClick()) {
                boolean flying = telemetry != null && System.currentTimeMillis() - telemetryAtMs < 5000 && mc.player != null
                        && mc.player.getVehicle() instanceof earthlife.mod.aviation.AirplaneEntity;
                open(mc, flying ? "seatback" : "map");
            }
            while (MENU.consumeClick()) openKey(mc, "menu:life");
            while (JOBS.consumeClick()) openKey(mc, "menu:jobs");
            while (WALLET.consumeClick()) openKey(mc, "menu:wallet");
            while (NEARBY.consumeClick()) openKey(mc, "menu:nearby");
            while (ZMF.consumeClick()) {
                // inside a web cinema: the browser controls; elsewhere the ZMF Cinema menu
                if (earthlife.mod.client.cinema.BrowserScreens.current() != null) mc.gui.setScreen(new earthlife.mod.client.cinema.BrowserControlScreen());
                else { command("zmfcinema ui showtimes"); mc.gui.setScreen(new earthlife.mod.client.zmf.ZmfScreen("showtimes")); }
            }
            while (FX_STOP.consumeClick()) {
                earthlife.mod.client.cinema.FourD.stopped = !earthlife.mod.client.cinema.FourD.stopped;
                if (earthlife.mod.client.cinema.FourD.stopped) earthlife.mod.client.cinema.Haptics.emergencyStop();
                if (mc.player != null) mc.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(earthlife.mod.client.cinema.FourD.stopped
                        ? "4DX STOPPED - seat motion, effects and TactSuit off (END to resume)" : "4DX resumed"));
            }
        });
        // A key can be shared with a vanilla binding (L = Advancements by default). Both get the press; if vanilla opened
        // its screen after ours, put ours back on the next tick (in Earth worlds; elsewhere vanilla keeps the key).
        ClientTickEvents.START_CLIENT_TICK.register(mc -> screenAtTickStart = mc.gui.screen());
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            // Vanilla binds Advancements to L too. Both mappings receive the press, but vanilla opens its screen first and
            // opening a screen clears every pending click. In Earth worlds the wallet wins; rebind either key to keep both.
            if (screenAtTickStart == null && mc.gui.screen() instanceof net.minecraft.client.gui.screens.advancements.AdvancementsScreen
                    && WALLET.same(mc.options.keyAdvancements) && ClientState.earth()) open(mc, "menu:wallet");
        });
        HudElementRegistry.addLast(EarthLifeMod.id("hud"), (g, delta) -> drawHud(g));
        HudElementRegistry.addLast(EarthLifeMod.id("gps"), (g, delta) -> {
            var mc = Minecraft.getInstance();
            if (!mc.gui.hud.isHidden() && mc.player != null && ClientState.earth()) earthlife.mod.client.car.Gps.drawHud(g, mc);
        });
        ClientTickEvents.END_CLIENT_TICK.register(earthlife.mod.client.car.Gps::tick);
    }

    static net.minecraft.client.gui.screens.Screen screenAtTickStart;

    static void openKey(Minecraft mc, String screen) { open(mc, screen); }

    /** Opens a client screen by name: map, map:route:x:z:label, map:clear, menu:<tab>, seatback. */
    public static void open(Minecraft mc, String screen) {
        if (welcomeUntilMs > 0) { welcomeUntilMs = 0; command("earthlife ui welcomed"); }
        if (screen.startsWith("zmf:")) { mc.gui.setScreen(new earthlife.mod.client.zmf.ZmfScreen(screen.substring(4))); return; }
        if (!ClientState.earth() && !screen.equals("seatback") && !screen.startsWith("cinema:")) {
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
        else if (screen.startsWith("cinema:pick:")) {
            String[] a = screen.split(":", 5);              // cinema:pick:<aud>:<4d|std>:<label>
            if (a.length >= 4) {
                var picker = new earthlife.mod.client.cinema.MoviePickerScreen(a[2], a[3].startsWith("4d"), a.length > 4 ? a[4] : "Cinema");
                picker.room3d = a[3].endsWith("3d");
                mc.gui.setScreen(picker);
            }
        }
    }

    static void drawHud(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.hud.isHidden() || mc.player == null || !ClientState.earth()) return;
        boolean welcomeOnly = !ClientConfig.showHud;
        var font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        var life = ClientState.life;
        int y = 6;
        if (life != null && !welcomeOnly) {
            String money = String.format("%,d LC", life.balance()) + (life.hourlyReady() ? "  ★ hourly reward [L]" : "") + (life.dailyReady() ? "  ★ daily" : "");
            g.text(font, money, sw - font.width(money) - 6, y, 0xFFFFD54F, true); y += 11;
            g.text(font, life.clock(), sw - font.width(life.clock()) - 6, y, 0xFFB3E5FC, true); y += 11;
            if (!life.job().isEmpty()) { g.text(font, life.job(), sw - font.width(life.job()) - 6, y, 0xFFC8E6C9, true); y += 11; }
            g.text(font, life.place(), sw - font.width(life.place()) - 6, y, 0xFFFFFFFF, true); y += 11;
            if (!life.eventLine().isEmpty()) { g.text(font, life.eventLine(), sw - font.width(life.eventLine()) - 6, y, 0xFFFFAB40, true); y += 11; }
            // next objectives
            int shown = 0;
            for (String o : life.objectives()) {
                if (!ClientConfig.showObjectives) break;
                if (o.startsWith("✔") || shown >= 3) continue;
                if (shown == 0) { y += 4; g.text(font, "Objectives", sw - font.width("Objectives") - 6, y, 0xFFFFFFFF, true); y += 11; }
                g.text(font, o, sw - font.width(o) - 6, y, 0xFFE0E0E0, true); y += 10; shown++;
            }
        }
        var route = ClientState.routeTarget;
        if (route != null && earthlife.mod.client.car.Gps.guidance == null) {     // in a car the GPS banner gives directions
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
            String mode = mc.player.isCreative() ? "Creative" : mc.player.isSpectator() ? "Spectator" : "Survival";
            String[] lines = {"§6§lWELCOME TO EARTHLIFE", "", "Location: " + w.startTown() + ", " + w.startState(), "Country: " + w.startCountry(),
                    "World: Earth (1:1 scale)", "Mode: " + mode + " · Scenario: " + w.scenario().replace('_', ' ').toLowerCase(java.util.Locale.ROOT), "",
                    "Press §eM§r to open your Earth map · §eK§r menu · §eJ§r jobs · §eL§r wallet · §eN§r nearby", "§aYour new life begins here.", "",
                    "§7Map data © OpenStreetMap contributors"};
            int bw = 0;
            for (String l : lines) bw = Math.max(bw, font.width(l));
            int bx = (sw - bw) / 2, by = Math.max(4, sh / 2 - 80);
            g.fill(bx - 8, by - 6, bx + bw + 8, by + lines.length * 10 + 2, 0xD0101418);
            for (int k = 0; k < lines.length; k++) g.centeredText(font, lines[k], sw / 2, by + k * 10, 0xFFFFFFFF);
        } else if (welcomeUntilMs != 0 && welcomeUntilMs <= System.currentTimeMillis()) {
            welcomeUntilMs = 0;
            command("earthlife ui welcomed");
        }
    }
}
