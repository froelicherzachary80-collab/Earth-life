package earthlife.mod.client.zmf;

import earthlife.mod.client.cinema.CinemaConfig;
import earthlife.mod.client.cinema.MediaLibrary;
import earthlife.mod.zmf.ZmfPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Shares the titles of your own film library (signed-in Jellyfin servers and configured folders) with the world you
 * play in, so its multiplexes can put them on their screens. Sent: title, year, genre, length, 3D layout and a
 * reference ("jellyfin:&lt;server&gt;:&lt;item&gt;" or "file:&lt;path&gt;") - never files, passwords or access tokens.
 * Runs automatically when you join a world (if sharing is on), and on demand.
 */
public final class LibraryShare {
    private LibraryShare() {}

    public static volatile String status = "Library not shared yet";
    static volatile CompletableFuture<?> running;
    public static volatile int lastCount;

    public static void onJoin() {
        if (CinemaConfig.shareLibrary && (!CinemaConfig.jellyfin.isEmpty() || !CinemaConfig.libraryFolders.isEmpty())) shareNow(s -> {});
    }

    public static synchronized void shareNow(Consumer<String> done) {
        if (running != null && !running.isDone()) { done.accept("Already reading your library..."); return; }
        status = "Reading your library...";
        running = CompletableFuture.supplyAsync(() -> {
            List<MediaLibrary.CatalogFilm> all = new ArrayList<>();
            String err = "";
            try { all.addAll(MediaLibrary.jellyfinCatalog()); } catch (Exception e) { err = " (Jellyfin: " + e.getMessage() + ")"; }
            all.addAll(MediaLibrary.folderCatalog());
            final String error = err;
            Minecraft.getInstance().execute(() -> {
                if (!ClientPlayNetworking.canSend(ZmfPayloads.Catalog.TYPE)) { status = "This server doesn't run ZMF Cinema"; done.accept(status); return; }
                List<ZmfPayloads.CatalogEntry> l = new ArrayList<>();
                for (var f : all) l.add(new ZmfPayloads.CatalogEntry(f.source(), cut(f.title(), 200), f.year(), cut(f.genre(), 40), f.runtimeMs(), f.format3d()));
                int n = l.size();
                // serverbound packets are limited to 32 KiB: send chunks of about 20 KB
                List<List<ZmfPayloads.CatalogEntry>> chunks = new ArrayList<>();
                List<ZmfPayloads.CatalogEntry> cur = new ArrayList<>(); int bytes = 0;
                for (var e : l) {
                    int sz = 24 + 3 * (e.source().length() + e.title().length() + e.genre().length() + e.format3d().length());
                    if (sz > 30_000) continue;                                     // absurdly long path: skip
                    if (bytes + sz > 20_000 && !cur.isEmpty()) { chunks.add(cur); cur = new ArrayList<>(); bytes = 0; }
                    cur.add(e); bytes += sz;
                }
                chunks.add(cur);
                for (int i = 0; i < chunks.size(); i++) ClientPlayNetworking.send(new ZmfPayloads.Catalog(i == 0, i == chunks.size() - 1, chunks.get(i)));
                lastCount = n;
                long recent = all.stream().filter(f -> f.year() >= 2000).count();
                status = "Shared " + n + " titles (" + recent + " from 2000 on)" + error;
                done.accept(status);
            });
            return null;
        });
    }

    static String cut(String s, int n) { return s == null ? "" : s.length() <= n ? s : s.substring(0, n); }
}
