package earthlife.mod.client.cinema;

import earthlife.mod.client.EarthLifeClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Controls the web-cinema browser of the room you are in: the page itself (click, scroll, type), an address bar,
 * back / forward / reload / home, bookmarks, volume, cinema fullscreen, browser resolution, and - if you want everyone
 * in the room to see the page you are on - "Show to the room" (shares only the address). Esc returns to the game; the
 * page keeps playing on the cinema screen.
 */
public final class BrowserControlScreen extends Screen {
    static final String[][] BOOKMARKS = {{"YouTube", "https://www.youtube.com"}, {"Twitch", "https://www.twitch.tv"}, {"Google", "https://www.google.com"},
            {"Vimeo", "https://vimeo.com"}, {"Wikipedia", "https://www.wikipedia.org"}};
    EditBox address;
    int bx, by, bw, bh;                   // where the page is drawn
    String roomKey;

    public BrowserControlScreen() { super(Component.literal("Web cinema")); }

    @Override public boolean isPauseScreen() { return false; }

    BrowserScreens.Session ses() { return BrowserScreens.current(); }

    @Override protected void init() {
        var s = ses();
        roomKey = s == null ? null : s.key;
        int top = 4;
        int x = 4;
        addRenderableWidget(Button.builder(Component.literal("◀"), b -> BrowserScreens.back(ses())).bounds(x, top, 20, 20).build()); x += 22;
        addRenderableWidget(Button.builder(Component.literal("▶"), b -> BrowserScreens.forward(ses())).bounds(x, top, 20, 20).build()); x += 22;
        addRenderableWidget(Button.builder(Component.literal("⟳"), b -> BrowserScreens.reload(ses())).bounds(x, top, 20, 20).build()); x += 22;
        addRenderableWidget(Button.builder(Component.literal("⌂"), b -> go(CinemaConfig.browserHome)).bounds(x, top, 20, 20).build()); x += 24;
        int right = 4 + 64 + 2 + 20 + 2 + 20 + 2 + 50 + 2 + 92 + 2 + 92;
        address = new EditBox(font, x, top, Math.max(120, width - x - right - 8), 20, Component.literal("address"));
        address.setMaxLength(2000);
        address.setValue(s == null ? "" : BrowserScreens.url(s));
        addRenderableWidget(address);
        int rx = x + address.getWidth() + 4;
        addRenderableWidget(Button.builder(Component.literal("Go"), b -> go(address.getValue())).bounds(rx, top, 30, 20).build()); rx += 32;
        addRenderableWidget(Button.builder(Component.literal("Vol -"), b -> vol(-0.1)).bounds(rx, top, 32, 20).build()); rx += 34;
        addRenderableWidget(Button.builder(Component.literal("+"), b -> vol(0.1)).bounds(rx, top, 20, 20).build()); rx += 22;
        addRenderableWidget(Button.builder(Component.literal(CinemaConfig.browserHeight + "p"), b -> {
            int h = CinemaConfig.browserHeight >= 1080 ? 480 : CinemaConfig.browserHeight >= 720 ? 1080 : 720;
            BrowserScreens.resize(ses(), h); rebuildWidgets();
        }).bounds(rx, top, 50, 20).build()); rx += 52;
        addRenderableWidget(Button.builder(Component.literal("Fullscreen video"), b -> BrowserScreens.cinemaMode(ses())).bounds(rx, top, 92, 20).build()); rx += 94;
        addRenderableWidget(Button.builder(Component.literal("Show to the room"), b -> {
            var ss = ses(); if (ss != null && roomKey != null) EarthLifeClient.command("zmfcinema room " + roomKey + " browser " + BrowserScreens.url(ss));
        }).bounds(rx, top, 92, 20).build());
        // bookmarks + back to films
        int bxx = 4;
        for (String[] bm : BOOKMARKS) {
            addRenderableWidget(Button.builder(Component.literal(bm[0]), b -> go(bm[1])).bounds(bxx, top + 22, 62, 16).build());
            bxx += 64;
        }
        addRenderableWidget(Button.builder(Component.literal("Back to films (Jellyfin)"), b -> { if (roomKey != null) EarthLifeClient.command("zmfcinema room " + roomKey + " jellyfin"); onClose(); })
                .bounds(width - 154, top + 22, 150, 16).build());
        by = top + 42; bh = height - by - 4; bw = Math.min(width - 8, bh * 16 / 9); bh = bw * 9 / 16; bx = (width - bw) / 2;
        if (s != null) BrowserScreens.input(s, "focus", true);
    }

    void go(String url) {
        url = url.trim();
        if (url.isEmpty()) return;
        if (!url.contains("://")) url = url.contains(".") && !url.contains(" ") ? "https://" + url : "https://www.google.com/search?q=" + java.net.URLEncoder.encode(url, java.nio.charset.StandardCharsets.UTF_8);
        BrowserScreens.navigate(ses(), url);
        address.setValue(url);
    }

    void vol(double d) {
        var s = ses(); if (s == null) return;
        s.volume = Math.max(0, Math.min(1, s.volume + d));
        BrowserScreens.setVolume(s, s.volume);
    }

    boolean onPage(double mx, double my) { return mx >= bx && mx < bx + bw && my >= by && my < by + bh && !address.isFocused(); }
    int px(double mx) { var s = ses(); return s == null ? 0 : (int) ((mx - bx) * s.w / bw); }
    int py(double my) { var s = ses(); return s == null ? 0 : (int) ((my - by) * s.h / bh); }

    @Override public boolean mouseClicked(MouseButtonEvent e, boolean doubled) {
        if (onPage(e.x(), e.y())) { setFocused(null); address.setFocused(false); BrowserScreens.input(ses(), "click", new MouseButtonEvent(px(e.x()), py(e.y()), e.buttonInfo()), doubled); return true; }
        return super.mouseClicked(e, doubled);
    }

    @Override public boolean mouseReleased(MouseButtonEvent e) {
        if (onPage(e.x(), e.y())) BrowserScreens.input(ses(), "release", new MouseButtonEvent(px(e.x()), py(e.y()), e.buttonInfo()));
        return super.mouseReleased(e);
    }

    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (onPage(mx, my)) { BrowserScreens.input(ses(), "scroll", px(mx), py(my), sy); return true; }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override public void mouseMoved(double mx, double my) {
        if (onPage(mx, my)) BrowserScreens.input(ses(), "move", px(mx), py(my));
        super.mouseMoved(mx, my);
    }

    @Override public boolean keyPressed(KeyEvent e) {
        if (address.isFocused()) {
            if (e.key() == 257 || e.key() == 335) { go(address.getValue()); address.setFocused(false); return true; }      // Enter
            return super.keyPressed(e);
        }
        if (e.key() == 256) { onClose(); return true; }                                                               // Esc
        BrowserScreens.input(ses(), "key", e);
        return true;
    }

    @Override public boolean keyReleased(KeyEvent e) {
        if (!address.isFocused()) BrowserScreens.input(ses(), "keyup", e);
        return super.keyReleased(e);
    }

    @Override public boolean charTyped(CharacterEvent e) {
        if (address.isFocused()) return super.charTyped(e);
        BrowserScreens.input(ses(), "char", e);
        return true;
    }

    @Override public void removed() { var s = ses(); if (s != null) BrowserScreens.input(s, "focus", false); }

    @Override public void tick() {
        if (ses() == null) { onClose(); return; }
        if (!address.isFocused()) { String u = BrowserScreens.url(ses()); if (!u.equals(address.getValue())) address.setValue(u); }
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float d) {
        g.fill(0, 0, width, height, 0xE0080A0C);
        var s = ses();
        net.minecraft.resources.Identifier tex = roomKey == null ? null : BrowserScreens.texture(roomKey);
        if (tex != null) g.blit(tex, bx, by, bx + bw, by + bh, 0f, 1f, 0f, 1f);
        else g.centeredText(font, s == null ? "Walk into a web cinema (rooms A-D) first." : BrowserScreens.status(roomKey), width / 2, height / 2, 0xFFB0BEC5);
        super.extractRenderState(g, mx, my, d);
        if (s != null) g.text(font, String.format(Locale.ROOT, "vol %d%%", Math.round(s.volume * 100)), 4, height - 12, 0x80FFFFFF, false);
    }
}
