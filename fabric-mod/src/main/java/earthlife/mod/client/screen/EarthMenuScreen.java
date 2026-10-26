package earthlife.mod.client.screen;

import earthlife.mod.client.ClientConfig;
import earthlife.mod.client.ClientState;
import earthlife.mod.client.EarthLifeClient;
import earthlife.mod.net.Payloads;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.*;

/**
 * The EarthLife menu (K): tabs for your life (objectives, home, waypoints, privacy), jobs (J), wallet (L), nearby (N),
 * events (with Halloween), travel (flights) and vehicles. Lists come from the server; every button runs an
 * /earthlife command so the server validates everything (money, distance, permissions).
 */
public final class EarthMenuScreen extends Screen {
    public static final String[] TABS = {"life", "jobs", "wallet", "shop", "cinema", "nearby", "events", "travel", "vehicles", "settings", "help"};
    static final String[] LABELS = {"Profile", "Jobs", "Wallet", "Shop", "Cinema", "Nearby", "Events", "Travel", "Vehicles", "Settings", "Help"};
    public static volatile int listVersion;

    private String tab;
    private int scroll, seenVersion = -1;

    public EarthMenuScreen(String tab) {
        super(Component.literal("EarthLife"));
        this.tab = Arrays.asList(TABS).contains(tab) ? tab : "life";
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        int tabW = Math.min(64, (width - 20) / TABS.length), x = (width - tabW * TABS.length) / 2;
        for (int i = 0; i < TABS.length; i++) {
            final String t = TABS[i];
            Button b = Button.builder(Component.literal((t.equals(tab) ? "▸" : "") + LABELS[i]), btn -> switchTab(t)).bounds(x + i * tabW, 30, tabW - 2, 18).build();
            b.active = !t.equals(tab);
            addRenderableWidget(b);
        }
        if (seenVersion == -1) request();
        seenVersion = listVersion;
        if (tab.equals("settings")) { settingsButtons(); return; }
        Payloads.UiList list = ClientState.lists.get(tab);
        int top = contentTop(), rowH = 22, y = top - scroll;
        if (list != null && !tab.equals("help")) for (Payloads.Row r : list.rows()) {
            if (!r.button().isEmpty() && !r.command().isEmpty() && y >= top - 2 && y + 18 <= height - 30) {
                addRenderableWidget(Button.builder(Component.literal(r.button()), b -> {
                    EarthLifeClient.command(r.command());
                    if (r.command().startsWith("earthlife map route")) onClose();
                    else request();
                }).bounds(width / 2 + panelW() / 2 - 92, y, 86, 18).build());
            }
            y += rowH;
        }
        if (tab.equals("cinema")) addRenderableWidget(Button.builder(Component.literal("Films & 4D settings"), b -> minecraft.gui.setScreen(new earthlife.mod.client.cinema.CinemaSettingsScreen(this)))
                .bounds(width / 2 - 260, height - 26, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("LifeMap [M]"), b -> minecraft.gui.setScreen(new LifeMapScreen())).bounds(width / 2 - 155, height - 26, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> request()).bounds(width / 2 - 50, height - 26, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(width / 2 + 55, height - 26, 100, 20).build());
    }

    int panelW() { return Math.min(width - 20, 460); }
    int contentTop() { return tab.equals("life") ? 62 + objectivesHeight() : 62; }

    int objectivesHeight() {
        var l = ClientState.life;
        return l == null || l.objectives().isEmpty() ? 0 : 16 + l.objectives().size() * 11 + 6;
    }

    private void settingsButtons() {
        int x = width / 2 - 155, y = 62;
        Object[][] toggles = {{"HUD (coins, clock, job)", ClientConfig.showHud}, {"Objectives tracker", ClientConfig.showObjectives},
                {"Coordinates on the LifeMap", ClientConfig.showCoordinates}, {"Satellite-style map colours", ClientConfig.satelliteMap}};
        for (int i = 0; i < toggles.length; i++) {
            final int k = i;
            boolean on = (Boolean) toggles[i][1];
            addRenderableWidget(Button.builder(Component.literal(toggles[i][0] + ": " + (on ? "ON" : "OFF")), b -> {
                switch (k) { case 0 -> ClientConfig.showHud = !on; case 1 -> ClientConfig.showObjectives = !on; case 2 -> ClientConfig.showCoordinates = !on; default -> ClientConfig.satelliteMap = !on; }
                ClientConfig.save(); rebuildWidgets(); }).bounds(x + (i % 2) * 160, y + (i / 2) * 22, 150, 20).build());
        }
        var life = ClientState.life;
        boolean sharing = life != null && life.sharingLocation();
        addRenderableWidget(Button.builder(Component.literal("Share my location: " + (sharing ? "ON" : "OFF")), b -> {
            EarthLifeClient.command("earthlife map share " + (sharing ? "off" : "on")); }).bounds(x, y + 48, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Controls (key binds)"), b -> minecraft.gui.setScreen(
                new net.minecraft.client.gui.screens.options.controls.KeyBindsScreen(this, minecraft.options))).bounds(x + 160, y + 48, 150, 20).build());
    }

    void switchTab(String t) {
        tab = t; scroll = 0; seenVersion = -1;
        rebuildWidgets();
    }

    void request() {
        if (!tab.equals("help")) EarthLifeClient.command("earthlife ui " + tab);
        switch (tab) {
            case "wallet" -> EarthLifeClient.command("earthlife ui opened wallet");
            case "nearby" -> EarthLifeClient.command("earthlife ui opened nearby");
            default -> EarthLifeClient.command("earthlife ui opened menu");
        }
    }

    @Override public void tick() {
        if (seenVersion != listVersion) { seenVersion = listVersion; rebuildWidgets(); }
    }

    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        Payloads.UiList list = ClientState.lists.get(tab);
        int max = list == null ? 0 : Math.max(0, list.rows().size() * 22 - (height - 30 - contentTop()));
        scroll = Math.max(0, Math.min(max, scroll - (int) (sy * 22)));
        rebuildWidgets();
        return true;
    }

    @Override public boolean keyPressed(KeyEvent e) {
        if (EarthLifeClient.MENU != null && EarthLifeClient.MENU.matches(e)) { onClose(); return true; }
        return super.keyPressed(e);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float d) {
        int pw = panelW(), x0 = (width - pw) / 2;
        g.fill(x0 - 6, 4, x0 + pw + 6, height - 30, 0xC0101418);
        var life = ClientState.life;
        String head = life == null ? "EarthLife" : String.format("%s · %,d LC · %s", life.place(), life.balance(), life.clock());
        g.centeredText(font, head, width / 2, 10, 0xFFFFD54F);
        if (life != null && !life.job().isEmpty()) g.centeredText(font, life.job(), width / 2, 20, 0xFFC8E6C9);
        super.extractRenderState(g, mx, my, d);
        int y = 56;
        if (tab.equals("help")) { help(g, x0, y); return; }
        if (tab.equals("settings")) {
            var w = ClientState.world;
            g.textWithWordWrap(font, Component.literal("This world (set when it was created): " + (w == null ? "?" : w.settingsSummary())), x0, 140, pw, 0xFFB0BEC5);
            g.text(font, "Client options are saved in config/earthlife-client.json and apply to every world.", x0, height - 42, 0xFF9E9E9E, false);
            return;
        }
        if (tab.equals("life") && life != null && !life.objectives().isEmpty()) {
            g.text(font, String.format("Objectives (%d/%d)", life.objectivesDone(), life.objectives().size()), x0, y, 0xFFFFFFFF, true);
            y += 14;
            for (String o : life.objectives()) { g.text(font, o, x0 + 6, y, o.startsWith("✔") ? 0xFF81C784 : 0xFFE0E0E0, false); y += 11; }
        }
        Payloads.UiList list = ClientState.lists.get(tab);
        int top = contentTop();
        if (list == null) { g.centeredText(font, "Loading…", width / 2, top + 10, 0xFFBDBDBD); return; }
        g.text(font, list.title(), x0, top - 10, 0xFF90CAF9, true);
        int ry = top - scroll;
        g.enableScissor(x0 - 4, top - 2, x0 + pw + 4, height - 30);
        for (Payloads.Row r : list.rows()) {
            if (ry > top - 24 && ry < height - 30) {
                g.fill(x0, ry - 1, x0 + pw, ry + 20, 0x40FFFFFF);
                g.text(font, LifeMapScreen.trim(r.title(), 60), x0 + 4, ry + 1, 0xFFFFFFFF, false);
                g.text(font, LifeMapScreen.trim(r.detail(), 70), x0 + 4, ry + 11, 0xFFB0BEC5, false);
            }
            ry += 22;
        }
        g.disableScissor();
        if (tab.equals("wallet")) g.centeredText(font, "LifeCoins are a fictional in-game currency. They can't be bought, sold or cashed out.", width / 2, height - 40, 0xFF9E9E9E);
    }

    private void help(GuiGraphicsExtractor g, int x, int y) {
        String[] lines = {
                "§eM§r LifeMap · §eK§r this menu · §eJ§r jobs · §eL§r wallet · §eN§r nearby residents and places",
                "Keys can be changed in Options > Controls > Key Binds > EarthLife.",
                "Talk to residents: right-click them and pick what to say.",
                "Work: apply in Jobs, go to your workplace during your shift, clock in, do the tasks.",
                "Money: LifeCoins from work, rewards, contests. Spend on vehicles, flights, tickets.",
                "Travel: buy a car in Vehicles, or book a flight in Travel and walk to the gate.",
                "Halloween week: trick-or-treat residents in the evening, enter the costume and carving contests.",
                "Map: scroll to zoom, drag to pan, click to select, double-click to route. Routes never teleport you.",
                "Privacy: your location is hidden from other players unless you turn sharing on (Life tab).",
                "Commands: /earthlife help · Map data © OpenStreetMap contributors (ODbL)."};
        for (String l : lines) { g.text(font, l, x, y, 0xFFFFFFFF, false); y += 13; }
    }
}
