package earthlife.mod.client.cinema;

import earthlife.mod.EarthLifeMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Finds FFmpeg (the open-source media decoder EarthLife uses to play films): the path in config/earthlife-cinema.json,
 * then "ffmpeg" on the system PATH, then a copy EarthLife downloaded into .minecraft/earthlife/ffmpeg/ when you agreed
 * to it (Windows: BtbN's LGPL build from GitHub; macOS: evermeet.cx's static build; on Linux install it with your
 * package manager, e.g. "sudo apt install ffmpeg"). FFmpeg is not part of Minecraft or EarthLife.
 */
public final class Ffmpeg {
    private Ffmpeg() {}

    private static volatile String found;
    private static volatile boolean searched;
    public static volatile String status = "";
    public static volatile CompletableFuture<String> download;

    static boolean windows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }
    static boolean mac() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac"); }

    static Path home() { return FabricLoader.getInstance().getGameDir().resolve("earthlife").resolve("ffmpeg"); }
    static Path bundled() { return home().resolve(windows() ? "ffmpeg.exe" : "ffmpeg"); }

    /** Path or command of a working ffmpeg, or null. */
    public static synchronized String path() {
        if (found != null) return found;
        if (searched && download == null) return null;
        searched = true;
        List<String> cands = new ArrayList<>();
        if (!CinemaConfig.ffmpegPath.isBlank()) cands.add(CinemaConfig.ffmpegPath);
        cands.add(bundled().toString());
        cands.add(windows() ? "ffmpeg.exe" : "ffmpeg");
        if (mac()) { cands.add("/opt/homebrew/bin/ffmpeg"); cands.add("/usr/local/bin/ffmpeg"); }
        for (String c : cands) if (works(c)) { found = c; status = "FFmpeg: " + c; return c; }
        status = "FFmpeg not found";
        return null;
    }

    public static void rescan() { found = null; searched = false; }

    static boolean works(String cmd) {
        try {
            if (cmd.contains(File.separator) && !Files.isRegularFile(Path.of(cmd))) return false;
            Process p = new ProcessBuilder(cmd, "-hide_banner", "-version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    /** Where EarthLife would download FFmpeg from on this OS, or null (Linux: use the package manager). */
    public static String downloadUrl() {
        if (!CinemaConfig.ffmpegDownloadUrl.isBlank()) return CinemaConfig.ffmpegDownloadUrl;
        String arch = System.getProperty("os.arch", "");
        if (windows() && (arch.contains("64"))) return "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-lgpl.zip";
        if (mac()) return "https://evermeet.cx/ffmpeg/getrelease/zip";
        return null;
    }

    /** Downloads and unpacks FFmpeg (only when the player clicks "Download FFmpeg"). */
    public static synchronized CompletableFuture<String> downloadAsync(Consumer<String> progress) {
        if (download != null && !download.isDone()) return download;
        String url = downloadUrl();
        if (url == null) return CompletableFuture.failedFuture(new IOException("Install FFmpeg with your package manager (e.g. sudo apt install ffmpeg), then press Rescan."));
        download = CompletableFuture.supplyAsync(() -> {
            try {
                Files.createDirectories(home());
                HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(20)).build();
                HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "EarthLife/0.5.0 (Minecraft mod)").GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode() + " from " + url);
                long total = r.headers().firstValueAsLong("content-length").orElse(-1);
                Path zip = home().resolve("download.zip");
                try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(zip)) {
                    byte[] buf = new byte[1 << 16]; long n = 0; int k; long last = 0;
                    while ((k = in.read(buf)) > 0) {
                        out.write(buf, 0, k); n += k;
                        if (n - last > (1 << 20)) { last = n; progress.accept(total > 0 ? String.format("Downloading FFmpeg %d%%", n * 100 / total) : String.format("Downloading FFmpeg %.0f MB", n / 1e6)); }
                    }
                }
                progress.accept("Unpacking FFmpeg...");
                String exe = windows() ? "ffmpeg.exe" : "ffmpeg";
                boolean ok = false;
                try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip))) {
                    ZipEntry e;
                    while ((e = z.getNextEntry()) != null) {
                        String name = e.getName().replace('\\', '/');
                        if (!e.isDirectory() && (name.equals(exe) || name.endsWith("/bin/" + exe) || name.endsWith("/" + exe))) {
                            Files.copy(z, bundled(), StandardCopyOption.REPLACE_EXISTING);
                            ok = true;
                            break;
                        }
                    }
                }
                Files.deleteIfExists(zip);
                if (!ok) throw new IOException("ffmpeg executable not found in the download");
                bundled().toFile().setExecutable(true);
                rescan();
                String p = path();
                if (p == null) throw new IOException("downloaded FFmpeg does not run on this computer");
                progress.accept("FFmpeg ready");
                return p;
            } catch (Exception e) {
                EarthLifeMod.LOG.warn("FFmpeg download failed", e);
                throw new java.util.concurrent.CompletionException(e);
            }
        });
        return download;
    }
}
