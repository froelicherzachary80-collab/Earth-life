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
        return "MediaBrowser Client=\"EarthLife\", Device=\"Minecraft\", DeviceId=\"" + CinemaConfig.deviceId + "\", Version=\"0.5.0\"" + (token == null ? "" : ", Token=\"" + token + "\"");
    }

    static String norm(String url) {
        url = url.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://" + url;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    static Map<String, Object> getJson(String url, String authHeader) throws IOException, InterruptedException {
        var b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("Accept", "application/json").header("User-Agent", "EarthLife/0.5.0 (Minecraft mod)");
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

    /** Signs out of one server (ends the session on the server too) and forgets it. */
    public static void jellyfinLogout(CinemaConfig.JellyfinServer s) {
        try {
            HTTP.send(HttpRequest.newBuilder(URI.create(s.url() + "/Sessions/Logout")).timeout(Duration.ofSeconds(10)).header("Authorization", auth(s.token()))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {}
        synchronized (CinemaConfig.class) { CinemaConfig.jellyfin.removeIf(j -> Objects.equals(j.serverId(), s.serverId())); CinemaConfig.jellyfinLibraries.remove(s.serverId()); CinemaConfig.save(); }
    }

    /** Connection test: server name, version and whether the token still works. */
    public static String jellyfinTest(CinemaConfig.JellyfinServer s) throws Exception {
        long t0 = System.currentTimeMillis();
        var info = getJson(s.url() + "/System/Info", auth(s.token()));
        return Json.str(info, "ServerName") + " · Jellyfin " + Json.str(info, "Version") + " · " + (System.currentTimeMillis() - t0) + " ms";
    }

    public record Library(String id, String name, String type) {}

    /** The user's libraries on a server (movies, shows, ...). */
    public static List<Library> jellyfinLibraries(CinemaConfig.JellyfinServer s) throws Exception {
        var m = getJson(s.url() + "/Users/" + s.userId() + "/Views", auth(s.token()));
        List<Library> out = new ArrayList<>();
        for (Object o : Json.arr(m.get("Items"))) { var it = Json.obj(o); out.add(new Library(Json.str(it, "Id"), Json.str(it, "Name"), Objects.requireNonNullElse(Json.str(it, "CollectionType"), ""))); }
        return out;
    }

    /** A playable title for the multiplex schedules. */
    public record CatalogFilm(String source, String title, int year, String genre, long runtimeMs, String format3d) {}

    /** Every movie of the selected libraries of every signed-in server (paged, up to 5,000 per server). */
    public static List<CatalogFilm> jellyfinCatalog() throws Exception {
        List<CatalogFilm> out = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (var s : List.copyOf(CinemaConfig.jellyfin)) {
            List<String> libs = CinemaConfig.jellyfinLibraries.getOrDefault(s.serverId(), List.of());
            for (String parent : libs.isEmpty() ? Collections.<String>singletonList(null) : libs) {
                try {
                    for (int start = 0; start < 5000; start += 500) {
                        String q = s.url() + "/Users/" + s.userId() + "/Items?IncludeItemTypes=Movie&Recursive=true&SortBy=SortName&StartIndex=" + start + "&Limit=500"
                                + "&Fields=Video3DFormat,ProductionYear,RunTimeTicks,Genres" + (parent == null ? "" : "&ParentId=" + parent);
                        var m = getJson(q, auth(s.token()));
                        var items = Json.arr(m.get("Items"));
                        for (Object o : items) {
                            var it = Json.obj(o);
                            int year = it.get("ProductionYear") == null ? 0 : (int) Json.num(it.get("ProductionYear"));
                            long ticks = it.get("RunTimeTicks") == null ? 0 : (long) Json.num(it.get("RunTimeTicks"));
                            var genres = Json.arr(it.get("Genres"));
                            out.add(new CatalogFilm("jellyfin:" + s.serverId() + ":" + Json.str(it, "Id"), Json.str(it, "Name"), year,
                                    genres.isEmpty() ? "" : String.valueOf(genres.get(0)), ticks / 10_000, format3d(Json.str(it, "Video3DFormat"))));
                        }
                        if (items.size() < 500) break;
                    }
                } catch (Exception e) { errors.add(s.name() + ": " + e.getMessage()); }
            }
        }
        if (out.isEmpty() && !errors.isEmpty()) throw new IOException(String.join("; ", errors));
        return out;
    }

    static final java.util.regex.Pattern YEAR = java.util.regex.Pattern.compile("[\\(\\[ ._-]((?:19|20)\\d{2})[\\)\\] ._-]");

    /** Video files in the configured library folders (title and year from the file name, e.g. "Some Film (2012).mkv"). */
    public static List<CatalogFilm> folderCatalog() {
        List<CatalogFilm> out = new ArrayList<>();
        for (String f : List.copyOf(CinemaConfig.libraryFolders)) {
            try (var walk = Files.walk(Path.of(f), 3)) {
                walk.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.(mp4|mkv|webm|mov|avi|m4v)$")).limit(5000).forEach(p -> {
                    String name = p.getFileName().toString();
                    var m = YEAR.matcher(" " + name.replaceAll("\\.[A-Za-z0-9]{2,4}$", " ") + " ");
                    int year = m.find() ? Integer.parseInt(m.group(1)) : 0;
                    String title = name.replaceAll("\\.[A-Za-z0-9]{2,4}$", "").replaceAll("[\\(\\[]?(19|20)\\d{2}[\\)\\]]?.*$", "").replace('_', ' ').replace('.', ' ').trim();
                    String f3 = name.matches("(?i).*\\b(h?sbs)\\b.*") ? (name.toLowerCase(Locale.ROOT).contains("hsbs") ? "HSBS" : "SBS")
                            : name.matches("(?i).*\\b(h?(tab|ou))\\b.*") ? "HTAB" : "2D";
                    out.add(new CatalogFilm("file:" + p.toAbsolutePath(), title.isEmpty() ? name : title, year, "", 0, f3));
                });
            } catch (Exception ignored) {}
        }
        return out;
    }

    /**
     * A Jellyfin film as FFmpeg input. Direct play streams the original file (static=true); with transcoding on, or when
     * subtitles are wanted (the server burns them in), the server converts it to H.264/AAC at the size limit. The audio
     * track follows your preferred language.
     */
    static Input jellyfinStream(CinemaConfig.JellyfinServer s, String item) throws Exception {
        int audioRel = 0, audioAbs = -1, subAbs = -1;
        try {
            var info = getJson(s.url() + "/Items/" + item + "/PlaybackInfo?UserId=" + s.userId(), auth(s.token()));
            var sources = Json.arr(info.get("MediaSources"));
            if (!sources.isEmpty()) {
                int rel = 0;
                for (Object o : Json.arr(Json.obj(sources.get(0)).get("MediaStreams"))) {
                    var st = Json.obj(o);
                    String type = Json.str(st, "Type"), lang = Objects.requireNonNullElse(Json.str(st, "Language"), "");
                    int idx = st.get("Index") == null ? -1 : (int) Json.num(st.get("Index"));
                    if ("Audio".equals(type)) {
                        if (!CinemaConfig.audioLang.isBlank() && lang.equalsIgnoreCase(CinemaConfig.audioLang) && audioAbs < 0) { audioRel = rel; audioAbs = idx; }
                        rel++;
                    } else if ("Subtitle".equals(type) && !CinemaConfig.subtitleLang.isBlank() && lang.equalsIgnoreCase(CinemaConfig.subtitleLang) && subAbs < 0) subAbs = idx;
                }
            }
        } catch (Exception e) { /* older servers / no permission: play the default streams */ }
        String base = s.url() + "/Videos/" + item + "/stream";
        if (!CinemaConfig.jellyfinTranscode && subAbs < 0)
            return new Input(base + "?static=true&api_key=" + s.token(), List.of(), "0:a:" + audioRel, s.serverId(), item);
        int maxH = Math.max(360, Math.min(2160, CinemaConfig.jellyfinMaxHeight));
        String q = "?static=false&container=mkv&videoCodec=h264&audioCodec=aac&audioChannels=2&maxHeight=" + maxH
                + "&videoBitRate=" + (maxH >= 2160 ? 40_000_000 : maxH >= 1080 ? 12_000_000 : 5_000_000) + "&audioBitRate=192000"
                + (audioAbs >= 0 ? "&audioStreamIndex=" + audioAbs : "") + (subAbs >= 0 ? "&subtitleStreamIndex=" + subAbs + "&subtitleMethod=Encode" : "")
                + "&deviceId=" + URLEncoder.encode(CinemaConfig.deviceId, StandardCharsets.UTF_8) + "&api_key=" + s.token();
        return new Input(base + q, List.of(), "0:a:0", s.serverId(), item);
    }

    /** Watch progress for Jellyfin ("Playing", "Progress", "Stopped"); never blocks the caller. */
    public static void jellyfinProgress(Input in, String what, long positionMs, boolean paused) {
        if (!CinemaConfig.reportProgress || in == null || in.jellyfinServer() == null) return;
        var s = server(in.jellyfinServer()).orElse(null);
        if (s == null) return;
        String path = switch (what) { case "start" -> "/Sessions/Playing"; case "stop" -> "/Sessions/Playing/Stopped"; default -> "/Sessions/Playing/Progress"; };
        String body = Json.write(Map.of("ItemId", in.jellyfinItem(), "PositionTicks", positionMs * 10_000, "IsPaused", paused,
                "PlayMethod", in.url().contains("static=true") ? "DirectStream" : "Transcode", "CanSeek", true));
        HTTP.sendAsync(HttpRequest.newBuilder(URI.create(s.url() + path)).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .header("Authorization", auth(s.token())).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding());
    }

    /** Poster of a Jellyfin film as PNG bytes (null if not available). */
    public static byte[] poster(String source) {
        try {
            if (source == null || !source.startsWith("jellyfin:")) return null;
            String[] a = source.split(":", 3);
            var s = server(a[1]).orElse(null);
            if (s == null) return null;
            var r = HTTP.send(HttpRequest.newBuilder(URI.create(s.url() + "/Items/" + a[2] + "/Images/Primary?maxHeight=360&format=Png&quality=90"))
                    .timeout(Duration.ofSeconds(20)).header("Authorization", auth(s.token())).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            return r.statusCode() / 100 == 2 ? r.body() : null;
        } catch (Exception e) { return null; }
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

    /** What FFmpeg opens: url, input options, which audio stream to play ("0:a:0"), and the Jellyfin item for progress reports. */
    public record Input(String url, List<String> ffmpegArgs, String audioMap, String jellyfinServer, String jellyfinItem) {
        public Input(String url, List<String> ffmpegArgs) { this(url, ffmpegArgs, "0:a:0", null, null); }
    }

    /** What this computer feeds FFmpeg for a source, or an exception explaining why it can't play here. */
    public static Input resolve(String source, String owner) throws Exception {
        if (source.startsWith("jellyfin:")) {
            String[] a = source.split(":", 3);
            var s = server(a[1]).orElseThrow(() -> new IOException("this film is from " + owner + "'s Jellyfin library - sign in to that server in the cinema menu to watch"));
            return jellyfinStream(s, a[2]);
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
