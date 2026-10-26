package earthlife.mod.client.cinema;

import earthlife.mod.client.EarthLifeClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Pick the film for a free auditorium. Sources (all streamed/decoded on this computer):
 * Jellyfin (your servers, combined), Internet Archive (public-domain feature films), Link (a direct video URL),
 * File (a video file on this PC - drag it onto this window or type the path). Choose 2D or the 3D layout of a 3D file
 * (side-by-side / top-and-bottom); in 3D the screen shows red/cyan anaglyph while you wear the 3D glasses.
 */
public final class MoviePickerScreen extends Screen {
    static final String[] TABS = {"Jellyfin", "Internet Archive", "Link", "File"};
    static final String[] FORMATS = {"2D", "HSBS", "SBS", "HTAB", "TAB"};
    static final String[] FORMAT_NAMES = {"2D", "3D half side-by-side", "3D full side-by-side", "3D half top/bottom", "3D full top/bottom"};

    final String key, label;
    final boolean fourD;
    int tab = 0, page = 0, formatIdx = 0;
    String message = "";
    List<MediaLibrary.Film> results = List.of();
    CompletableFuture<?> pending;
    EditBox search, address, user, password, link, file;
    String lastQuery = null;

    public MoviePickerScreen(String key, boolean fourD, String label) {
        super(Component.literal("Choose a film"));
        this.key = key; this.fourD = fourD; this.label = label;
        tab = CinemaConfig.jellyfin.isEmpty() ? 1 : 0;
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override protected void init() {
        int w = Math.min(420, width - 20), x0 = (width - w) / 2, y = 28;
        int tw = w / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            final int t = i;
            addRenderableWidget(Button.builder(Component.literal((i == tab ? "§e" : "") + TABS[i]), b -> { tab = t; page = 0; results = List.of(); message = ""; lastQuery = null; rebuildWidgets(); })
                    .bounds(x0 + i * tw, y, tw - 2, 18).build());
        }
        y += 24;
        // FFmpeg status
        String ff = Ffmpeg.path();
        if (ff == null) {
            String url = Ffmpeg.downloadUrl();
            addRenderableWidget(Button.builder(Component.literal(url != null ? "Get FFmpeg (needed to play films)" : "Rescan for FFmpeg"), b -> {
                if (url == null) { Ffmpeg.rescan(); rebuildWidgets(); return; }
                message = "Downloading FFmpeg...";
                Ffmpeg.downloadAsync(s -> message = s).whenComplete((p, e) -> minecraft.execute(() -> { message = e != null ? "FFmpeg: " + rootMessage(e) : "FFmpeg ready."; rebuildWidgets(); }));
            }).bounds(x0, y, w, 18).build());
            y += 22;
        }
        switch (tab) {
            case 0 -> y = jellyfinTab(x0, y, w);
            case 1 -> y = searchTab(x0, y, w, "Search public-domain films (e.g. \"Nosferatu\", \"His Girl Friday\")");
            case 2 -> {
                link = box(x0, y, w, "https://... (a direct link to a video file you may play)"); y += 22;
                y = formatRow(x0, y, w);
                addRenderableWidget(Button.builder(Component.literal(startLabel()), b -> {
                    String u = link.getValue().trim();
                    if (!u.startsWith("http://") && !u.startsWith("https://")) { message = "Enter an http(s) link."; return; }
                    start("url:" + u, titleFromPath(u), FORMATS[formatIdx]);
                }).bounds(x0, y, w, 20).build());
                y += 24;
            }
            case 3 -> {
                file = box(x0, y, w, "Path of a video file on this computer (or drag the file onto this window)"); y += 22;
                y = formatRow(x0, y, w);
                addRenderableWidget(Button.builder(Component.literal(startLabel()), b -> {
                    Path p = Path.of(file.getValue().trim().replace("\"", ""));
                    if (!Files.isRegularFile(p)) { message = "File not found."; return; }
                    start("file:" + p.toAbsolutePath(), titleFromPath(p.getFileName().toString()), FORMATS[formatIdx]);
                }).bounds(x0, y, w, 20).build());
                y += 24;
                message = message.isEmpty() ? "Only you can see a film from your own file; others in the room see its title." : message;
            }
            default -> {}
        }
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).bounds(width / 2 - 40, height - 24, 80, 20).build());
    }

    String startLabel() {
        if (!earthlife.mod.client.ClientState.earth()) return "Start the film" + (fourD ? " (4DX)" : "") + (room3d ? " · 3D room" : "");
        int price = fourD ? 20 : FORMATS[formatIdx].equals("2D") ? 12 : 15;
        return "Start the film · ticket " + price + " LC" + (fourD ? " (4D motion)" : "");
    }

    /** ZMF 3D rooms: ordinary films are converted to 3D automatically. */
    public boolean room3d;

    int formatRow(int x0, int y, int w) {
        addRenderableWidget(Button.builder(Component.literal("Picture: " + FORMAT_NAMES[formatIdx]), b -> { formatIdx = (formatIdx + 1) % FORMATS.length; rebuildWidgets(); })
                .bounds(x0, y, w, 18).build());
        return y + 22;
    }

    EditBox box(int x, int y, int w, String hint) {
        EditBox e = new EditBox(font, x, y, w, 18, Component.literal(hint));
        e.setMaxLength(2048);
        e.setHint(Component.literal(hint));
        addRenderableWidget(e);
        return e;
    }

    boolean addMode;

    int jellyfinTab(int x0, int y, int w) {
        if (CinemaConfig.jellyfin.isEmpty() || addMode) {
            address = box(x0, y, w, "Jellyfin server address, e.g. http://192.168.1.20:8096"); y += 22;
            user = box(x0, y, w / 2 - 2, "User name");
            password = new PasswordBox(font, x0 + w / 2 + 2, y, w / 2 - 2, 18);
            addRenderableWidget(password);
            y += 22;
            addRenderableWidget(Button.builder(Component.literal("Sign in"), b -> {
                message = "Signing in...";
                String a = address.getValue(), u = user.getValue(), pw = ((PasswordBox) password).secret();
                pending = CompletableFuture.supplyAsync(() -> { try { return MediaLibrary.jellyfinLogin(a, u, pw); } catch (Exception e) { throw new java.util.concurrent.CompletionException(e); } })
                        .whenComplete((s, e) -> minecraft.execute(() -> {
                            message = e != null ? "Sign-in failed: " + rootMessage(e) : "Signed in to " + s.name() + ".";
                            if (e == null) addMode = false;
                            lastQuery = null; rebuildWidgets();
                        }));
            }).bounds(x0, y, w, 20).build());
            y += 24;
            if (!message.startsWith("Sign")) message = "Your password is only sent to your Jellyfin server; EarthLife keeps the access token on this PC.";
            return y;
        }
        StringBuilder names = new StringBuilder();
        for (var s : CinemaConfig.jellyfin) names.append(names.isEmpty() ? "" : ", ").append(s.name());
        if (message.isEmpty()) message = "Libraries: " + names;
        addRenderableWidget(Button.builder(Component.literal("Add another server"), b -> { addMode = true; message = ""; rebuildWidgets(); })
                .bounds(x0, y, w / 2 - 2, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Sign out of all"), b -> { CinemaConfig.jellyfin.clear(); CinemaConfig.save(); message = ""; rebuildWidgets(); })
                .bounds(x0 + w / 2 + 2, y, w / 2 - 2, 18).build());
        y += 22;
        return searchTab(x0, y, w, "Search your Jellyfin movies");
    }

    int searchTab(int x0, int y, int w, String hint) {
        search = box(x0, y, w - 64, hint);
        if (lastQuery != null) search.setValue(lastQuery);
        addRenderableWidget(Button.builder(Component.literal("Search"), b -> runSearch(search.getValue())).bounds(x0 + w - 60, y, 60, 18).build());
        y += 22;
        if (tab == 1) y = formatRow(x0, y, w);
        if (lastQuery == null) { lastQuery = ""; runSearch(""); }
        int per = Math.max(3, (height - y - 60) / 22);
        int from = page * per;
        for (int i = from; i < Math.min(results.size(), from + per); i++) {
            var f = results.get(i);
            String t = f.title() + (f.year().isEmpty() ? "" : " (" + f.year() + ")");
            String d = f.detail() + (f.runtimeMs() > 0 ? " · " + f.runtimeMs() / 60000 + " min" : "");
            addRenderableWidget(Button.builder(Component.literal(trim(t, 44) + " §7" + trim(d, 36)), b -> start(f.source(), f.title(), tab == 1 ? FORMATS[formatIdx] : f.format()))
                    .bounds(x0, y, w, 20).build());
            y += 22;
        }
        if (results.size() > per) {
            if (page > 0) addRenderableWidget(Button.builder(Component.literal("< Previous"), b -> { page--; rebuildWidgets(); }).bounds(x0, y, 90, 18).build());
            if (from + per < results.size()) addRenderableWidget(Button.builder(Component.literal("Next >"), b -> { page++; rebuildWidgets(); }).bounds(x0 + w - 90, y, 90, 18).build());
            y += 22;
        }
        return y;
    }

    void runSearch(String q) {
        lastQuery = q;
        message = "Searching...";
        final int t = tab;
        pending = CompletableFuture.supplyAsync(() -> {
            try { return t == 0 ? MediaLibrary.jellyfinMovies(q) : MediaLibrary.archiveSearch(q); }
            catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
        }).whenComplete((r, e) -> minecraft.execute(() -> {
            if (tab != t) return;
            if (e != null) { message = "Search failed: " + rootMessage(e); results = List.of(); }
            else { results = r; message = r.isEmpty() ? "Nothing found." : r.size() + " films" + (t == 1 ? " · public domain, from archive.org" : ""); page = 0; }
            rebuildWidgets();
        }));
    }

    void start(String source, String title, String format) {
        if (key.isEmpty()) { message = "To play this film, choose an auditorium at a cinema's ticket kiosk."; return; }
        if (room3d && "2D".equals(format)) format = "AUTO3D";
        String src = "\"" + source.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        EarthLifeClient.command("earthlife cinema start " + key + " " + format + " " + src + " " + title.replaceAll("[\\r\\n]", " "));
        onClose();
    }

    static String titleFromPath(String p) {
        String n = p.replaceAll("[?#].*$", "");
        n = n.substring(n.lastIndexOf('/') + 1);
        n = n.replaceAll("\\.[A-Za-z0-9]{2,4}$", "").replace('_', ' ').replace('.', ' ').trim();
        return n.isEmpty() ? "Film" : n;
    }

    static String trim(String s, int n) { return s == null ? "" : s.length() <= n ? s : s.substring(0, n - 1) + "…"; }

    static String rootMessage(Throwable e) {
        while (e.getCause() != null && (e instanceof java.util.concurrent.CompletionException || e.getMessage() == null)) e = e.getCause();
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    @Override public void onFilesDrop(List<Path> paths) {
        if (paths.isEmpty()) return;
        tab = 3; rebuildWidgets();
        if (file != null) file.setValue(paths.get(0).toAbsolutePath().toString());
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0xC0101418);
        g.centeredText(font, "§l" + label + (fourD ? " §b(4D motion)" : ""), width / 2, 8, 0xFFFFFFFF);
        g.centeredText(font, "Choose a film", width / 2, 18, 0xFFB0BEC5);
        super.extractRenderState(g, mx, my, delta);
        g.centeredText(font, trim(message.isEmpty() ? Ffmpeg.status : message, 90), width / 2, height - 38, 0xFFFFE082);
    }

    /** An edit box that shows dots instead of the password. */
    static final class PasswordBox extends EditBox {
        private final StringBuilder secret = new StringBuilder();
        PasswordBox(net.minecraft.client.gui.Font font, int x, int y, int w, int h) {
            super(font, x, y, w, h, Component.literal("Password"));
            setHint(Component.literal("Password"));
            setMaxLength(256);
            setResponder(v -> {
                // keep the typed characters, display bullets
                if (v.chars().allMatch(c -> c == '•')) { if (v.length() < secret.length()) secret.setLength(v.length()); return; }
                for (char c : v.toCharArray()) if (c != '•') secret.append(c);
                setValue("•".repeat(secret.length()));
            });
        }
        String secret() { return secret.toString(); }
    }
}
