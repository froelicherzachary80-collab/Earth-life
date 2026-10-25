package earthlife.launcher;

import earthlife.core.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** HTTP helpers on java.net.http (no dependencies). */
public final class Http {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
    public static final String UA = "EarthLifeLauncher/0.1";

    public static final class HttpError extends IOException {
        public final int status; public final String body;
        HttpError(int status, String url, String body) { super("HTTP " + status + " from " + url + ": " + truncate(body)); this.status = status; this.body = body; }
    }

    static String truncate(String s) { return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s; }

    public static Map<String, Object> getJson(String url, String bearer) throws IOException, InterruptedException {
        var b = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", UA).header("Accept", "application/json").GET();
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return Json.obj(Json.parse(send(b.build(), url)));
    }

    public static String getText(String url) throws IOException, InterruptedException {
        if (url.startsWith("file:")) return Files.readString(Path.of(URI.create(url)));
        return send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", UA).GET().build(), url);
    }

    public static Map<String, Object> postJson(String url, Object body, String bearer) throws IOException, InterruptedException {
        var b = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", UA)
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return Json.obj(Json.parse(send(b.build(), url)));
    }

    public static Map<String, Object> postForm(String url, Map<String, String> form) throws IOException, InterruptedException {
        StringBuilder sb = new StringBuilder();
        for (var e : form.entrySet()) {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        var req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", UA)
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(sb.toString())).build();
        return Json.obj(Json.parse(send(req, url)));
    }

    private static String send(HttpRequest req, String url) throws IOException, InterruptedException {
        HttpResponse<String> r = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new HttpError(r.statusCode(), url, r.body());
        return r.body();
    }

    /** Downloads to {@code dest} unless it already exists with the expected SHA-1. Verifies the hash after download. */
    public static boolean download(String url, Path dest, String sha1, long size) throws IOException, InterruptedException {
        if (Files.exists(dest) && (sha1 == null || sha1.equalsIgnoreCase(sha1(dest)))) return false;
        Files.createDirectories(dest.toAbsolutePath().getParent());
        Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
        if (url.startsWith("file:")) Files.copy(Path.of(URI.create(url)), tmp, StandardCopyOption.REPLACE_EXISTING);
        else {
            var req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", UA).GET().build();
            HttpResponse<Path> r = CLIENT.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
            if (r.statusCode() / 100 != 2) { Files.deleteIfExists(tmp); throw new HttpError(r.statusCode(), url, null); }
        }
        if (size > 0 && Files.size(tmp) != size) { Files.deleteIfExists(tmp); throw new IOException("Size mismatch for " + url); }
        if (sha1 != null && !sha1.equalsIgnoreCase(sha1(tmp))) { Files.deleteIfExists(tmp); throw new IOException("SHA-1 mismatch for " + url); }
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    public static String sha1(Path p) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            try (var in = Files.newInputStream(p)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
