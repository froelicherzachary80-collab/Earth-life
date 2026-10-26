package earthlife.mod.client.zmf;

import earthlife.mod.client.cinema.CinemaConfig;
import earthlife.mod.client.cinema.MediaLibrary;
import earthlife.mod.client.cinema.MoviePickerScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Jellyfin servers: add (sign in), test the connection, choose libraries, sign out; streaming and language options. */
public final class JellyfinScreen extends Screen {
    final Screen parent;
    String message = "";
    String libsFor;                                  // serverId whose libraries are shown
    List<MediaLibrary.Library> libs = List.of();
    EditBox audio, subs;

    public JellyfinScreen(Screen parent) { super(Component.literal("Jellyfin servers")); this.parent = parent; }

    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        int w = Math.min(460, width - 20), x0 = (width - w) / 2, y = 30;
        addRenderableWidget(Button.builder(Component.literal("Add a server (sign in)"), b -> {
            var p = new MoviePickerScreen("", false, "Jellyfin servers");
            minecraft.gui.setScreen(p);
        }).bounds(x0, y, w, 20).build());
        y += 26;
        for (var s : List.copyOf(CinemaConfig.jellyfin)) {
            int bw = 70;
            addRenderableWidget(Button.builder(Component.literal("Test"), b -> async(() -> MediaLibrary.jellyfinTest(s), r -> message = "✔ " + r)).bounds(x0 + w - 3 * (bw + 2), y, bw, 18).build());
            addRenderableWidget(Button.builder(Component.literal("Libraries"), b -> async(() -> MediaLibrary.jellyfinLibraries(s), r -> { libsFor = s.serverId(); libs = r; rebuildWidgets(); }))
                    .bounds(x0 + w - 2 * (bw + 2), y, bw, 18).build());
            addRenderableWidget(Button.builder(Component.literal("Sign out"), b -> { MediaLibrary.jellyfinLogout(s); message = "Signed out of " + s.name(); rebuildWidgets(); })
                    .bounds(x0 + w - (bw + 2), y, bw, 18).build());
            y += 22;
            if (s.serverId().equals(libsFor)) {
                List<String> sel = CinemaConfig.jellyfinLibraries.computeIfAbsent(s.serverId(), k -> new ArrayList<>());
                for (var l : libs) {
                    boolean on = sel.isEmpty() || sel.contains(l.id());
                    addRenderableWidget(Button.builder(Component.literal((on ? "☑ " : "☐ ") + l.name() + (l.type().isEmpty() ? "" : " (" + l.type() + ")")), b -> {
                        if (sel.isEmpty()) for (var x : libs) sel.add(x.id());
                        if (sel.contains(l.id())) sel.remove(l.id()); else sel.add(l.id());
                        if (sel.size() == libs.size()) sel.clear();         // all = no filter
                        CinemaConfig.save(); rebuildWidgets();
                    }).bounds(x0 + 16, y, w - 16, 16).build());
                    y += 18;
                }
            }
        }
        y += 8;
        addRenderableWidget(Button.builder(Component.literal("Streaming: " + (CinemaConfig.jellyfinTranscode ? "server transcoding (H.264, up to " + CinemaConfig.jellyfinMaxHeight + "p)" : "direct play (original file)")), b -> {
            CinemaConfig.jellyfinTranscode = !CinemaConfig.jellyfinTranscode; CinemaConfig.save(); rebuildWidgets();
        }).bounds(x0, y, w - 84, 20).build());
        addRenderableWidget(Button.builder(Component.literal(CinemaConfig.jellyfinMaxHeight + "p"), b -> {
            int[] q = {720, 1080, 1440, 2160}; int i = 0; while (i < q.length && q[i] != CinemaConfig.jellyfinMaxHeight) i++;
            CinemaConfig.jellyfinMaxHeight = q[(i + 1) % q.length]; CinemaConfig.save(); rebuildWidgets();
        }).bounds(x0 + w - 80, y, 80, 20).build());
        y += 24;
        audio = new EditBox(font, x0, y, w / 2 - 2, 20, Component.literal("audio"));
        audio.setHint(Component.literal("Audio language (eng, deu, fra...)")); audio.setValue(CinemaConfig.audioLang);
        audio.setResponder(v -> { CinemaConfig.audioLang = v.trim(); CinemaConfig.save(); });
        subs = new EditBox(font, x0 + w / 2 + 2, y, w / 2 - 2, 20, Component.literal("subs"));
        subs.setHint(Component.literal("Subtitles language (empty = off)")); subs.setValue(CinemaConfig.subtitleLang);
        subs.setResponder(v -> { CinemaConfig.subtitleLang = v.trim(); CinemaConfig.save(); });
        addRenderableWidget(audio); addRenderableWidget(subs);
        y += 24;
        addRenderableWidget(Button.builder(Component.literal("Report watch progress to Jellyfin: " + (CinemaConfig.reportProgress ? "ON" : "OFF")), b -> {
            CinemaConfig.reportProgress = !CinemaConfig.reportProgress; CinemaConfig.save(); rebuildWidgets();
        }).bounds(x0, y, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> minecraft.gui.setScreen(parent)).bounds(width / 2 - 50, height - 26, 100, 20).build());
    }

    interface Job<T> { T run() throws Exception; }

    <T> void async(Job<T> job, java.util.function.Consumer<T> ok) {
        message = "Working...";
        CompletableFuture.supplyAsync(() -> { try { return job.run(); } catch (Exception e) { throw new java.util.concurrent.CompletionException(e); } })
                .whenComplete((r, e) -> minecraft.execute(() -> { if (e != null) message = "✖ " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()); else ok.accept(r); }));
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float d) {
        int w = Math.min(460, width - 20), x0 = (width - w) / 2;
        g.fill(x0 - 6, 4, x0 + w + 6, height - 30, 0xD0101418);
        g.centeredText(font, "§lJellyfin servers", width / 2, 12, 0xFFFFFFFF);
        super.extractRenderState(g, mx, my, d);
        int y = 56;
        for (var s : CinemaConfig.jellyfin) {
            g.text(font, s.name() + " §7" + s.url(), x0, y + 5, 0xFFFFFFFF, false);
            y += 22;
            if (s.serverId().equals(libsFor)) y += 18 * libs.size();
        }
        if (CinemaConfig.jellyfin.isEmpty()) g.text(font, "No servers yet. Your password is only sent to your own server; the access token stays on this PC.", x0, y, 0xFF9E9E9E, false);
        g.textWithWordWrap(font, Component.literal("Direct play needs FFmpeg to decode the original file (4K works if your PC can decode it). Transcoding makes the server "
                + "convert it (needs server CPU/GPU) - required for burned-in subtitles. Films stream from your servers to this PC only; other players need their "
                + "own access to the same server."), x0, height - 66, w, 0xFF9E9E9E);
        if (!message.isEmpty()) g.centeredText(font, message, width / 2, height - 40, 0xFFFFE082);
    }
}
