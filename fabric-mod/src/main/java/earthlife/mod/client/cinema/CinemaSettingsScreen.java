package earthlife.mod.client.cinema;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Client cinema settings: FFmpeg, Jellyfin servers, picture quality, volume, 4D effect strength, TactSuit. */
public final class CinemaSettingsScreen extends Screen {
    private final Screen parent;
    private String message = "";

    public CinemaSettingsScreen(Screen parent) { super(Component.literal("Films & 4D")); this.parent = parent; }

    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        int x = width / 2 - 155, y = 40;
        String ff = Ffmpeg.path();
        addRenderableWidget(Button.builder(Component.literal(ff != null ? "FFmpeg: found - rescan" : Ffmpeg.downloadUrl() != null ? "Get FFmpeg (needed to play films)" : "FFmpeg not found - rescan"), b -> {
            if (Ffmpeg.path() != null || Ffmpeg.downloadUrl() == null) { Ffmpeg.rescan(); rebuildWidgets(); return; }
            message = "Downloading FFmpeg...";
            Ffmpeg.downloadAsync(s -> message = s).whenComplete((p, e) -> minecraft.execute(() -> { message = e != null ? "FFmpeg: " + MoviePickerScreen.rootMessage(e) : "FFmpeg ready."; rebuildWidgets(); }));
        }).bounds(x, y, 310, 20).build());
        y += 24;
        addRenderableWidget(Button.builder(Component.literal("Jellyfin servers: " + CinemaConfig.jellyfin.size() + " (sign in / manage)"), b -> {
            var s = new MoviePickerScreen("", false, "Jellyfin servers");
            minecraft.gui.setScreen(s);
        }).bounds(x, y, 310, 20).build());
        y += 24;
        addRenderableWidget(Button.builder(Component.literal("Picture: " + CinemaConfig.quality + "p"), b -> {
            int[] q = {360, 480, 720, 1080};
            int i = 0; while (i < q.length && q[i] != CinemaConfig.quality) i++;
            CinemaConfig.quality = q[(i + 1) % q.length]; CinemaConfig.save(); rebuildWidgets();
        }).bounds(x, y, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal(String.format(Locale.ROOT, "Film volume: %d%%", Math.round(CinemaConfig.volume * 100))), b -> {
            CinemaConfig.volume = CinemaConfig.volume >= 1 ? 0.25 : CinemaConfig.volume + 0.25; CinemaConfig.save(); rebuildWidgets();
        }).bounds(x + 160, y, 150, 20).build());
        y += 24;
        addRenderableWidget(Button.builder(Component.literal("4D effects: " + (CinemaConfig.effects <= 0 ? "OFF" : CinemaConfig.effects < 0.5 ? "gentle" : CinemaConfig.effects < 1 ? "medium" : "full")), b -> {
            CinemaConfig.effects = CinemaConfig.effects >= 1 ? 0 : CinemaConfig.effects + (CinemaConfig.effects < 0.3 ? 0.35 : 0.33); if (CinemaConfig.effects > 0.95) CinemaConfig.effects = 1;
            CinemaConfig.save(); rebuildWidgets();
        }).bounds(x, y, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("TactSuit (bHaptics): " + (CinemaConfig.haptics ? "ON" : "OFF")), b -> {
            CinemaConfig.haptics = !CinemaConfig.haptics; CinemaConfig.save(); if (!CinemaConfig.haptics) Haptics.close(); rebuildWidgets();
        }).bounds(x + 160, y, 150, 20).build());
        y += 24;
        addRenderableWidget(Button.builder(Component.literal("Test the TactSuit"), b -> {
            int[] f = new int[20]; java.util.Arrays.fill(f, 60);
            Haptics.frame(f, f, 300);
            message = Haptics.status;
        }).bounds(x, y, 310, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> minecraft.gui.setScreen(parent)).bounds(width / 2 - 50, height - 26, 100, 20).build());
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0xC0101418);
        g.centeredText(font, "§lFilms & 4D", width / 2, 12, 0xFFFFFFFF);
        g.centeredText(font, "Films stream and play on this computer. EarthLife hosts no films: use your Jellyfin, public-domain films, or your own links/files.", width / 2, 24, 0xFFB0BEC5);
        super.extractRenderState(g, mx, my, delta);
        g.centeredText(font, message.isEmpty() ? Ffmpeg.status + " · " + Haptics.status : message, width / 2, height - 40, 0xFFFFE082);
    }
}
