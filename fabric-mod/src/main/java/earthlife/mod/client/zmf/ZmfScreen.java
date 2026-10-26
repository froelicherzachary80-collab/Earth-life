package earthlife.mod.client.zmf;

import earthlife.mod.client.ClientState;
import earthlife.mod.client.EarthLifeClient;
import earthlife.mod.client.cinema.*;
import earthlife.mod.net.Payloads;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;

/**
 * ZMF Cinema menu (key B, or right-click a kiosk / concession counter in a multiplex):
 * Showtimes · Snacks · Library (Jellyfin servers, film folders, sharing titles for the schedules) · 3D · 4DX & TactSuit ·
 * Quality · Diagnostics · Admin (operators / singleplayer host). Lists come from the server; every button runs a
 * command, so the server decides prices, permissions and tickets.
 */
public final class ZmfScreen extends Screen {
    static final String[] TABS = {"showtimes", "snacks", "library", "3d", "4dx", "quality", "diagnostics", "admin"};
    static final String[] LABELS = {"Showtimes", "Snacks", "Library", "3D", "4DX & Suit", "Quality", "Diagnostics", "Admin"};
    public static volatile int listVersion;
    String tab;
    int scroll, seenVersion = -1;
    String message = "";
    EditBox folder;

    public ZmfScreen(String tab) { super(Component.literal("ZMF Cinema")); this.tab = Arrays.asList(TABS).contains(tab) ? tab : "showtimes"; }

    @Override public boolean isPauseScreen() { return false; }

    static boolean serverList(String t) { return t.equals("showtimes") || t.equals("snacks") || t.equals("admin"); }

    void request() { if (serverList(tab)) EarthLifeClient.command("zmfcinema ui " + tab); }

    @Override protected void init() {
        int tabW = Math.min(72, (width - 20) / TABS.length), x = (width - tabW * TABS.length) / 2;
        for (int i = 0; i < TABS.length; i++) {
            final String t = TABS[i];
            Button b = Button.builder(Component.literal(LABELS[i]), btn -> { tab = t; scroll = 0; seenVersion = -1; message = ""; rebuildWidgets(); })
                    .bounds(x + i * tabW, 26, tabW - 2, 18).build();
            b.active = !t.equals(tab);
            addRenderableWidget(b);
        }
        if (seenVersion == -1) request();
        seenVersion = listVersion;
        int pw = panelW(), x0 = (width - pw) / 2, y = 56;
        switch (tab) {
            case "library" -> library(x0, y, pw);
            case "3d" -> stereo(x0, y, pw);
            case "4dx" -> fourDx(x0, y, pw);
            case "quality" -> quality(x0, y, pw);
            case "diagnostics" -> {}
            default -> listButtons();
        }
        if (BrowserScreens.current() != null)
            addRenderableWidget(Button.builder(Component.literal("Web browser"), b -> minecraft.gui.setScreen(new BrowserControlScreen())).bounds(width / 2 - 260, height - 26, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> request()).bounds(width / 2 - 50, height - 26, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(width / 2 + 55, height - 26, 100, 20).build());
    }

    int panelW() { return Math.min(width - 20, 480); }

    void listButtons() {
        Payloads.UiList list = ClientState.lists.get("zmf:" + tab);
        if (list == null) return;
        int top = 62, rowH = 22, y = top - scroll;
        for (Payloads.Row r : list.rows()) {
            if (!r.button().isEmpty() && !r.command().isEmpty() && y >= top - 2 && y + 18 <= height - 30) {
                addRenderableWidget(Button.builder(Component.literal(r.button()), b -> {
                    EarthLifeClient.command(r.command());
                    if (r.command().startsWith("earthlife cinema pick") || r.command().startsWith("zmfcinema teleport") || r.command().startsWith("zmfcinema tp")) onClose();
                    else request();
                }).bounds(width / 2 + panelW() / 2 - 106, y, 100, 18).build());
            }
            y += rowH;
        }
    }

    // ---------------------------------------------------------------- library

    void library(int x0, int y, int pw) {
        addRenderableWidget(Button.builder(Component.literal("Jellyfin servers: " + CinemaConfig.jellyfin.size() + " (add / sign in / test / libraries)"),
                b -> minecraft.gui.setScreen(new JellyfinScreen(this))).bounds(x0, y, pw, 20).build());
        y += 24;
        folder = new EditBox(font, x0, y, pw - 110, 20, Component.literal("folder"));
        folder.setMaxLength(1000);
        folder.setHint(Component.literal("A folder with your own video files (drag it here or type the path)"));
        addRenderableWidget(folder);
        addRenderableWidget(Button.builder(Component.literal("Add folder"), b -> {
            String f = folder.getValue().trim().replace("\"", "");
            if (f.isEmpty() || !java.nio.file.Files.isDirectory(java.nio.file.Path.of(f))) { message = "Not a folder: " + f; return; }
            CinemaConfig.libraryFolders.add(f); CinemaConfig.save(); rebuildWidgets();
        }).bounds(x0 + pw - 104, y, 104, 20).build());
        y += 24;
        for (String f : List.copyOf(CinemaConfig.libraryFolders)) {
            final String ff = f;
            addRenderableWidget(Button.builder(Component.literal("Remove " + (f.length() > 50 ? "…" + f.substring(f.length() - 49) : f)), b -> {
                CinemaConfig.libraryFolders.remove(ff); CinemaConfig.save(); rebuildWidgets();
            }).bounds(x0, y, pw, 18).build());
            y += 20;
        }
        addRenderableWidget(Button.builder(Component.literal("Share titles for the schedules: " + (CinemaConfig.shareLibrary ? "ON" : "OFF")), b -> {
            CinemaConfig.shareLibrary = !CinemaConfig.shareLibrary; CinemaConfig.save(); rebuildWidgets();
        }).bounds(x0, y, pw / 2 - 2, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Share now"), b -> {
            message = "Reading your libraries...";
            LibraryShare.shareNow(s -> minecraft.execute(() -> message = s));
        }).bounds(x0 + pw / 2 + 2, y, pw / 2 - 2, 20).build());
    }

    void drawLibrary(GuiGraphicsExtractor g, int x0, int pw) {
        int y = height - 74;
        g.textWithWordWrap(font, Component.literal("Only titles, years, genres and a reference (\"jellyfin:server:item\" or \"file:...\") are shared with the world so "
                + "multiplexes can schedule them - never files, passwords or access tokens. Everyone signed in to the same Jellyfin server sees the film; "
                + "a file plays only on the computer it is on. Films from " + "2000 onwards are scheduled."), x0, y, pw, 0xFF9E9E9E);
        g.text(font, LibraryShare.status, x0, y - 12, 0xFFB0BEC5, false);
    }

    // ---------------------------------------------------------------- 3D

    void stereo(int x0, int y, int pw) {
        int half = pw / 2 - 2;
        slider(x0, y, half, "Depth strength", () -> CinemaConfig.stereoStrength, v -> CinemaConfig.stereoStrength = v, 0, 2, 0.1);
        slider(x0 + half + 4, y, half, "Screen plane", () -> CinemaConfig.stereoZero, v -> CinemaConfig.stereoZero = v, 0.1, 0.9, 0.05);
        y += 24;
        slider(x0, y, half, "Pop-out depth", () -> CinemaConfig.stereoPopOut, v -> CinemaConfig.stereoPopOut = v, 0, 1, 0.1);
        slider(x0 + half + 4, y, half, "Screen depth", () -> CinemaConfig.stereoScreenDepth, v -> CinemaConfig.stereoScreenDepth = v, 0, 1.5, 0.1);
        y += 24;
        toggle(x0, y, half, "Ghosting reduction", () -> CinemaConfig.stereoGhost, v -> CinemaConfig.stereoGhost = v);
        toggle(x0 + half + 4, y, half, "Comfort mode", () -> CinemaConfig.stereoComfort, v -> CinemaConfig.stereoComfort = v);
        y += 24;
        toggle(x0, y, half, "Swap eyes (cyan left)", () -> CinemaConfig.stereoSwap, v -> CinemaConfig.stereoSwap = v);
        toggle(x0 + half + 4, y, half, "Compare with 2D", () -> CinemaConfig.stereoCompare, v -> CinemaConfig.stereoCompare = v);
        y += 24;
        toggle(x0, y, half, "Automatic 2D-to-3D", () -> CinemaConfig.auto3d, v -> CinemaConfig.auto3d = v);
        addRenderableWidget(Button.builder(Component.literal("Performance mode (lighter depth map)"), b -> {
            CinemaConfig.preset = "LITE"; CinemaConfig.stereoComfort = true; CinemaConfig.save(); CinemaClient.stereoChanged(); rebuildWidgets();
        }).bounds(x0 + half + 4, y, half, 20).build());
    }

    // ---------------------------------------------------------------- 4DX + TactSuit

    void fourDx(int x0, int y, int pw) {
        int half = pw / 2 - 2;
        slider(x0, y, half, "Effect strength", () -> CinemaConfig.effects, v -> CinemaConfig.effects = v, 0, 1, 0.1);
        slider(x0 + half + 4, y, half, "Detection sensitivity", () -> CinemaConfig.fxSensitivity, v -> CinemaConfig.fxSensitivity = v, 0.5, 1.5, 0.1);
        y += 24;
        toggle(x0, y, half, "Reduced motion", () -> CinemaConfig.reducedMotion, v -> CinemaConfig.reducedMotion = v);
        addRenderableWidget(Button.builder(Component.literal("Blood effects: " + new String[]{"off", "subtle", "full"}[Math.max(0, Math.min(2, CinemaConfig.gore))]), b -> {
            CinemaConfig.gore = (CinemaConfig.gore + 1) % 3; CinemaConfig.save(); rebuildWidgets();
        }).bounds(x0 + half + 4, y, half, 20).build());
        y += 30;
        toggle(x0, y, half, "TactSuit (bHaptics)", () -> CinemaConfig.haptics, v -> { CinemaConfig.haptics = v; if (!v) Haptics.close(); });
        slider(x0 + half + 4, y, half, "Vest intensity", () -> CinemaConfig.hapticIntensity, v -> CinemaConfig.hapticIntensity = v, 0, 1, 0.1);
        y += 24;
        slider(x0, y, half, "Safety limit", () -> CinemaConfig.hapticMax / 100.0, v -> CinemaConfig.hapticMax = (int) Math.round(v * 100), 0.2, 1, 0.05);
        addRenderableWidget(Button.builder(Component.literal(FourD.stopped ? "Emergency stop: ON (resume)" : "Emergency stop [END]"), b -> {
            FourD.stopped = !FourD.stopped; if (FourD.stopped) Haptics.emergencyStop(); rebuildWidgets();
        }).bounds(x0 + half + 4, y, half, 20).build());
        y += 24;
        String[] tests = {"Test: front", "Test: back", "Test: explosion", "Test: heartbeat"};
        Haptics.Pattern[] pats = {Haptics.Pattern.TEST_FRONT, Haptics.Pattern.TEST_BACK, Haptics.Pattern.EXPLOSION, Haptics.Pattern.HEARTBEAT};
        int qw = pw / 4 - 3;
        for (int i = 0; i < 4; i++) {
            final int k = i;
            addRenderableWidget(Button.builder(Component.literal(tests[i]), b -> { Haptics.pattern(pats[k], 0.7f, 0); message = Haptics.status; })
                    .bounds(x0 + i * (qw + 4), y, qw, 20).build());
        }
    }

    // ---------------------------------------------------------------- quality

    void quality(int x0, int y, int pw) {
        String[] presets = {"LITE", "BALANCED", "HIGH", "ULTRA"};
        int qw = pw / 4 - 3;
        for (int i = 0; i < 4; i++) {
            final String p = presets[i];
            Button b = Button.builder(Component.literal((p.equals(CinemaConfig.preset) ? "▸" : "") + p), btn -> {
                CinemaConfig.preset = p; CinemaConfig.quality = CinemaConfig.presetHeight(); CinemaConfig.save(); CinemaClient.stereoChanged(); rebuildWidgets();
            }).bounds(x0 + i * (qw + 4), y, qw, 20).build();
            b.active = !p.equals(CinemaConfig.preset);
            addRenderableWidget(b);
        }
        y += 24;
        addRenderableWidget(Button.builder(Component.literal("Film picture: " + CinemaConfig.quality + "p"), b -> {
            int[] q = {360, 480, 720, 1080, 2160};
            int i = 0; while (i < q.length && q[i] != CinemaConfig.quality) i++;
            CinemaConfig.quality = q[(i + 1) % q.length]; CinemaConfig.save(); rebuildWidgets();
        }).bounds(x0, y, pw / 2 - 2, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Film volume: " + Math.round(CinemaConfig.volume * 100) + "%"), b -> {
            CinemaConfig.volume = CinemaConfig.volume >= 1 ? 0.25 : CinemaConfig.volume + 0.25; CinemaConfig.save(); rebuildWidgets();
        }).bounds(x0 + pw / 2 + 2, y, pw / 2 - 2, 20).build());
        y += 24;
        addRenderableWidget(Button.builder(Component.literal("FFmpeg: " + (Ffmpeg.path() != null ? "found - rescan" : Ffmpeg.downloadUrl() != null ? "get it (needed for films)" : "not found - rescan")), b -> {
            if (Ffmpeg.path() != null || Ffmpeg.downloadUrl() == null) { Ffmpeg.rescan(); rebuildWidgets(); return; }
            message = "Downloading FFmpeg...";
            Ffmpeg.downloadAsync(s -> message = s).whenComplete((p, e) -> minecraft.execute(() -> { message = e != null ? "FFmpeg: " + e.getMessage() : "FFmpeg ready."; rebuildWidgets(); }));
        }).bounds(x0, y, pw, 20).build());
    }

    // ---------------------------------------------------------------- helpers

    interface Get { double get(); }
    interface Set { void set(double v); }
    interface GetB { boolean get(); }
    interface SetB { void set(boolean v); }

    void slider(int x, int y, int w, String label, Get get, Set set, double min, double max, double step) {
        addRenderableWidget(Button.builder(Component.literal(String.format(Locale.ROOT, "%s: %.2f  ( - )", label, get.get())), b -> {
            set.set(Math.max(min, get.get() - step)); CinemaConfig.save(); CinemaClient.stereoChanged(); rebuildWidgets();
        }).bounds(x, y, w - 44, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            set.set(Math.min(max, get.get() + step)); CinemaConfig.save(); CinemaClient.stereoChanged(); rebuildWidgets();
        }).bounds(x + w - 40, y, 40, 20).build());
    }

    void toggle(int x, int y, int w, String label, GetB get, SetB set) {
        addRenderableWidget(Button.builder(Component.literal(label + ": " + (get.get() ? "ON" : "OFF")), b -> {
            set.set(!get.get()); CinemaConfig.save(); CinemaClient.stereoChanged(); rebuildWidgets();
        }).bounds(x, y, w, 20).build());
    }

    @Override public void tick() { if (seenVersion != listVersion) { seenVersion = listVersion; rebuildWidgets(); } }

    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        Payloads.UiList list = ClientState.lists.get("zmf:" + tab);
        int max = list == null ? 0 : Math.max(0, list.rows().size() * 22 - (height - 92));
        scroll = Math.max(0, Math.min(max, scroll - (int) (sy * 22)));
        rebuildWidgets();
        return true;
    }

    @Override public boolean keyPressed(net.minecraft.client.input.KeyEvent e) {
        if (EarthLifeClient.ZMF != null && EarthLifeClient.ZMF.matches(e) && (folder == null || !folder.isFocused())) { onClose(); return true; }
        return super.keyPressed(e);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float d) {
        int pw = panelW(), x0 = (width - pw) / 2;
        g.fill(x0 - 6, 4, x0 + pw + 6, height - 30, 0xD0101418);
        g.centeredText(font, "§6§lZMF CINEMA§r  ·  IMAX · 4DX · 3D · ScreenX · Web", width / 2, 10, 0xFFFFFFFF);
        super.extractRenderState(g, mx, my, d);
        switch (tab) {
            case "library" -> drawLibrary(g, x0, pw);
            case "3d" -> g.textWithWordWrap(font, Component.literal("Red/cyan glasses: red over the left eye. 3D rooms convert ordinary films to 3D automatically "
                    + "(depth estimated from each frame) while you wear the in-game 3D glasses; real 3D files (side-by-side, top/bottom) keep their own depth. "
                    + "Anaglyph adds depth to a flat screen - colours are partly lost and it can't look holographic."), x0, height - 76, pw, 0xFF9E9E9E);
            case "4dx" -> g.textWithWordWrap(font, Component.literal("4DX rooms analyse the film's picture and sound: explosions, impacts, thunder, jump scares, chases, "
                    + "rain, ocean, snow, fire, flights, space battles and magic move your seat and fill the room with virtual wind, mist, rain, snow, embers and light. "
                    + "Only the TactSuit is physical (bHaptics Player must run on this PC): it vibrates - it can't make wind, water, heat or motion. " + Haptics.status), x0, height - 76, pw, 0xFF9E9E9E);
            case "quality" -> g.textWithWordWrap(font, Component.literal("LITE: 360p, light depth map, few particles · BALANCED: 480p · HIGH: 720p, detailed depth · "
                    + "ULTRA: 1080p, finest depth map, 2 films at once. 4K files play when your PC and server can decode them; the picture on the screen is scaled "
                    + "to the chosen size. Only the room you are in plays a film."), x0, height - 76, pw, 0xFF9E9E9E);
            case "diagnostics" -> diagnostics(g, x0, 60);
            default -> list(g, x0, pw);
        }
        if (!message.isEmpty()) g.centeredText(font, message, width / 2, height - 42, 0xFFFFE082);
    }

    void list(GuiGraphicsExtractor g, int x0, int pw) {
        Payloads.UiList list = ClientState.lists.get("zmf:" + tab);
        int top = 62;
        if (list == null) { g.centeredText(font, "Loading…", width / 2, top + 10, 0xFFBDBDBD); return; }
        g.text(font, list.title(), x0, top - 11, 0xFF90CAF9, true);
        int ry = top - scroll;
        g.enableScissor(x0 - 4, top - 2, x0 + pw + 4, height - 30);
        for (Payloads.Row r : list.rows()) {
            if (ry > top - 24 && ry < height - 30) {
                g.fill(x0, ry - 1, x0 + pw, ry + 20, 0x40FFFFFF);
                g.text(font, cut(r.title(), pw - 112), x0 + 4, ry + 1, 0xFFFFFFFF, false);
                g.text(font, cut(r.detail(), pw - 112), x0 + 4, ry + 11, 0xFFB0BEC5, false);
            }
            ry += 22;
        }
        g.disableScissor();
    }

    String cut(String s, int px) {
        if (font.width(s) <= px) return s;
        while (s.length() > 1 && font.width(s + "…") > px) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    void diagnostics(GuiGraphicsExtractor g, int x0, int y) {
        List<String> l = new ArrayList<>();
        l.add("Screens with a showing near you: " + CinemaClient.candidates + " · films decoding now: " + CinemaClient.activeStreams + " (limit " + CinemaConfig.presetStreams() + ")");
        for (VideoPlayer p : CinemaClient.players()) {
            l.add("▶ " + p.source.replaceAll("api_key=[^&]+", "api_key=…") + " · " + p.width() + "x" + p.height() + " · " + p.status + (p.error != null ? " · " + p.error : ""));
            l.add("   format " + p.format + (p.auto3d ? String.format(Locale.ROOT, " · 2D-to-3D %.1f ms/frame, mean disparity %.1f px, depth: %s", p.convertMs, p.disparityPx, p.depthModel) : "")
                    + (p.analyse4dx ? " · 4DX: " + (p.fx.lastSummary.isEmpty() ? "watching" : p.fx.lastSummary) : ""));
        }
        l.add("Web cinema: " + BrowserScreens.engineStatus());
        var ws = BrowserScreens.current();
        if (ws != null) l.add("   browser: " + BrowserScreens.url(ws) + " · frames " + ws.frames);
        l.add("4DX: " + (FourD.active() ? "active · " + FourD.last : "not seated in a running 4DX room") + " · events this session " + FourD.eventsTotal);
        l.add(Haptics.status + (Haptics.devices.isEmpty() ? "" : " · devices " + Haptics.devices) + " · frames sent " + Haptics.framesSent);
        l.add(Ffmpeg.status.isEmpty() ? "FFmpeg: " + (Ffmpeg.path() == null ? "not found" : Ffmpeg.path()) : Ffmpeg.status);
        l.add("Jellyfin servers: " + CinemaConfig.jellyfin.size() + " · library folders " + CinemaConfig.libraryFolders.size() + " · " + LibraryShare.status);
        l.add("Quality " + CinemaConfig.preset + " · picture " + CinemaConfig.quality + "p · depth map " + CinemaConfig.presetDepthGrid() + " cells · particles x" + CinemaConfig.presetParticles());
        l.add("FPS " + minecraft.getFps());
        for (String s : l) { g.text(font, cut(s, panelW() - 8), x0 + 4, y, 0xFFE0E0E0, false); y += 12; }
    }
}
