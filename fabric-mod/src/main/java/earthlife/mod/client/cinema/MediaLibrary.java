package earthlife.mod.client.cinema;

import earthlife.core.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * Where films come from - all on the player's own computer, nothing is hosted or redistributed by EarthLife:
 * <ul>
 * <li><b>Jellyfin</b>: your own media server(s). Sign in once (the password is only sent to your server, EarthLife keeps
 * the access token); libraries of several servers are combined into one list.</li>
 * <li><b>Internet Archive</b>: search the public-domain "Feature Films" collection (archive.org/details/feature_films).</li>
 * <li><b>Link</b>: a direct video URL you have the right to play.</li>
 * <li><b>File</b>: a video file on this computer.</li>
 * </ul>
 * Source strings shared through the server: "jellyfin:&lt;serverId&gt;:&lt;itemId&gt;", "ia:&lt;identifier&gt;", "url:&lt;link&gt;",
 * "file:&lt;path&gt;". A Jellyfin access token is never part of them.
 */
public final class MediaLibrary {
    private MediaLibrary() {}

    public record Film(String source, String title, String year, String detail, String format, long runtimeMs) {}

    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();

    static String auth(String token) {
        return "MediaBrowser Client=\"EarthLife\", Device=\"Minecraft\", DeviceId=\"" + CinemaConfig.deviceId + "\", Version=\"0.4.1\"" + (token == null ? "" : ", Token=\"" + token + "\"");
    }

    static String norm(String url) {
        url = url.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://" + url;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    static Map<String, Object> getJson(String url, String authHeader) throws IOException, InterruptedException {
        var b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("Accept", "application/json").header("User-Agent", "EarthLife/0.4.1 (Minecraft mod)");
        if (authHeader != null) b.header("Authorization", authHeader);
        var r = HTTP.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 401) throw new IOException("not signed in (HTTP 401) - sign in to this Jellyfin server again");
        if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode() + " from " + URI.create(url).getHost());
        return Json.obj(Json.parse(r.body()));
    }

    // ------------------------------------------------------------------ Jellyfin

    /** Signs in to a Jellyfin server and remembers it (address + access token) in config/earthlife-cinema.json. */
    public static CinemaConfig.JellyfinServer jellyfinLogin(String address, String user, String password) throws Exception {
        String url = norm(address);
        String body = Json.write(Map.of("Username", user, "Pw", password));
        var r = HTTP.send(HttpRequest.newBuilder(URI.create(url + "/Users/AuthenticateByName")).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").header("Authorization", auth(null)).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 401) throw new IOException("wrong user name or password");
        if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode() + " - is " + url + " a Jellyfin server?");
        var m = Json.obj(Json.parse(r.body()));
        String token = Json.str(m, "AccessToken"), serverId = Json.str(m, "ServerId");
        String userId = Json.str(Json.obj(m.get("User")), "Id");
        String name = url;
        try { name = Objects.requireNonNullElse(Json.str(getJson(url + "/System/Info/Public", null), "ServerName"), url); } catch (Exception ignored) {}
        var s = new CinemaConfig.JellyfinServer(name, url, serverId, userId, token);
        synchronized (CinemaConfig.class) {
            CinemaConfig.jellyfin.removeIf(j -> j.serverId() != null && j.serverId().equals(serverId));
            CinemaConfig.jellyfin.add(s);
            CinemaConfig.save();
        }
        return s;
    }

    static String format3d(String jf) {
        if (jf == null) return "2D";
        return switch (jf) {
            case "HalfSideBySide" -> "HSBS"; case "FullSideBySide" -> "SBS";
            case "HalfTopAndBottom" -> "HTAB"; case "FullTopAndBottom" -> "TAB";
            default -> "2D";
        };
    }

    /** Movies from every signed-in Jellyfin server, combined (same title and year = one entry, first server wins). */
    public static List<Film> jellyfinMovies(String search) throws Exception {
        List<Film> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> errors = new ArrayList<>();
        for (var s : List.copyOf(CinemaConfig.jellyfin)) {
            try {
                String q = s.url() + "/Users/" + s.userId() + "/Items?IncludeItemTypes=Movie&Recursive=true&SortBy=SortName&Limit=300"
                        + "&Fields=Video3DFormat,ProductionYear,RunTimeTicks" + (search == null || search.isBlank() ? "" : "&SearchTerm=" + URLEncoder.encode(search, StandardCharsets.UTF_8));
                var m = getJson(q, auth(s.token()));
                for (Object o : Json.arr(m.get("Items"))) {
                    var it = Json.obj(o);
                    String title = Json.str(it, "Name"), year = it.get("ProductionYear") == null ? "" : String.valueOf((long) Json.num(it.get("ProductionYear")));
                    if (!seen.add((title + "|" + year).toLowerCase(Locale.ROOT))) continue;
                    long ticks = it.get("RunTimeTicks") == null ? 0 : (long) Json.num(it.get("RunTimeTicks"));
                    String f3 = format3d(Json.str(it, "Video3DFormat"));
                    out.add(new Film("jellyfin:" + s.serverId() + ":" + Json.str(it, "Id"), title, year, s.name() + (f3.equals("2D") ? "" : " · 3D " + f3), f3, ticks / 10_000));
                }
            } catch (Exception e) { errors.add(s.name() + ": " + e.getMessage()); }
        }
        if (out.isEmpty() && !errors.isEmpty()) throw new IOException(String.join("; ", errors));
        return out;
    }

    static Optional<CinemaConfig.JellyfinServer> server(String serverId) {
        return CinemaConfig.jellyfin.stream().filter(j -> serverId.equals(j.serverId())).findFirst();
    }

    // ------------------------------------------------------------------ Internet Archive (public-domain feature films)

    public static List<Film> archiveSearch(String search) throws Exception {
        String q = "collection:(feature_films) AND mediatype:(movies)" + (search == null || search.isBlank() ? "" : " AND title:(" + search.replaceAll("[()\\\\:\"]", " ") + ")");
        String url = "https://archive.org/advancedsearch.php?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8)
                + "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=year&fl%5B%5D=runtime&sort%5B%5D=downloads+desc&rows=40&page=1&output=json";
        var m = getJson(url, null);
        List<Film> out = new ArrayList<>();
        for (Object o : Json.arr(Json.obj(m.get("response")).get("docs"))) {
            var d = Json.obj(o);
            String year = d.get("year") == null ? "" : String.valueOf(d.get("year")).replaceAll("\\.0$", "");
            out.add(new Film("ia:" + Json.str(d, "identifier"), Json.str(d, "title"), year, "Internet Archive · public domain" + (d.get("runtime") == null ? "" : " · " + d.get("runtime")), "2D", 0));
        }
        return out;
    }

    /** Best playable file of an Internet Archive item. */
    static String archiveFile(String identifier) throws Exception {
        var m = getJson("https://archive.org/metadata/" + URLEncoder.encode(identifier, StandardCharsets.UTF_8), null);
        String best = null; int bestRank = 99;
        List<String> pref = List.of("h.264", "h.264 ia", "mpeg4", "512kb mpeg4", "matroska", "ogg video", "cinepack");
        for (Object o : Json.arr(m.get("files"))) {
            var f = Json.obj(o);
            String fmt = String.valueOf(f.get("format")).toLowerCase(Locale.ROOT), name = Json.str(f, "name");
            int r = pref.indexOf(fmt);
            if (r < 0 && name != null && (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm"))) r = 20;
            if (r >= 0 && r < bestRank) { bestRank = r; best = name; }
        }
        if (best == null) throw new IOException("no playable video file in archive.org item " + identifier);
        return "https://archive.org/download/" + identifier + "/" + URLEncoder.encode(best, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ------------------------------------------------------------------ resolving a shared source on this computer

    public record Input(String url, List<String> ffmpegArgs) {}

    /** What this computer feeds FFmpeg for a source, or an exception explaining why it can't play here. */
    public static Input resolve(String source, String owner) throws Exception {
        if (source.startsWith("jellyfin:")) {
            String[] a = source.split(":", 3);
            var s = server(a[1]).orElseThrow(() -> new IOException("this film is from " + owner + "'s Jellyfin library - sign in to that server in the cinema menu to watch"));
            return new Input(s.url() + "/Videos/" + a[2] + "/stream?static=true&api_key=" + s.token(), List.of());
        }
        if (source.startsWith("ia:")) return new Input(archiveFile(source.substring(3)), List.of());
        if (source.startsWith("url:")) {
            String u = source.substring(4);
            if (!u.startsWith("http://") && !u.startsWith("https://")) throw new IOException("only http(s) links can be played");
            return new Input(u, List.of());
        }
        if (source.startsWith("file:")) {
            Path p = Path.of(source.substring(5));
            if (!Files.isRegularFile(p)) throw new IOException("this film is a file on " + owner + "'s computer");
            return new Input(p.toString(), List.of());
        }
        throw new IOException("unknown film source");
    }
}
