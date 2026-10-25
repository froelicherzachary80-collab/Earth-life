package earthlife.core.events;

import earthlife.core.util.Json;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static earthlife.core.events.LiveEvent.Activity.*;

/**
 * Parsers + request URLs for real event feeds. Fetching happens on the player's PC (EventEngine); these are pure
 * functions so they can be tested against recorded responses.
 *
 *  - MLB Stats API (statsapi.mlb.com, no key): MLB and Minor League schedules with venue coordinates and live
 *    linescores - e.g. Rochester Red Wings (Triple-A) home games at Innovative Field. Terms: personal, non-commercial use.
 *  - Ticketmaster Discovery API v2 (free key from developer.ticketmaster.com): concerts, sports, shows near a point.
 *  - iCalendar (.ics) URLs: any public calendar (a town's events, a venue's schedule).
 */
public final class Sources {
    private Sources() {}

    // ---------------- MLB Stats API ----------------
    public static String mlbScheduleUrl(int sportId, Integer teamId, LocalDate from, LocalDate to) {
        return "https://statsapi.mlb.com/api/v1/schedule?sportId=" + sportId + (teamId != null ? "&teamId=" + teamId : "")
                + "&startDate=" + from + "&endDate=" + to + "&hydrate=venue(location),linescore,team";
    }

    public static String mlbTeamsUrl(int sportId) { return "https://statsapi.mlb.com/api/v1/teams?sportId=" + sportId; }

    /** Finds a team id by (partial) name in a /teams response, e.g. "Rochester Red Wings". */
    public static Optional<Integer> mlbTeamId(String teamsJson, String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (Object o : Json.arr(Json.obj(Json.parse(teamsJson)).get("teams"))) {
            var t = Json.obj(o);
            String tn = String.valueOf(t.get("name")).toLowerCase(Locale.ROOT);
            if (tn.equals(n) || tn.contains(n)) return Optional.of(((Number) t.get("id")).intValue());
        }
        return Optional.empty();
    }

    public static List<LiveEvent> parseMlbSchedule(String json) {
        List<LiveEvent> out = new ArrayList<>();
        for (Object d : Json.arr(Json.obj(Json.parse(json)).get("dates"))) {
            for (Object g : Json.arr(Json.obj(d).get("games"))) {
                var game = Json.obj(g);
                var teams = Json.obj(game.get("teams"));
                var home = Json.obj(teams.get("home"));
                var away = Json.obj(teams.get("away"));
                String homeName = Json.str(Json.obj(home.get("team")), "name"), awayName = Json.str(Json.obj(away.get("team")), "name");
                Instant start = Instant.parse((String) game.get("gameDate"));
                var venue = Json.obj(game.get("venue"));
                double lat = Double.NaN, lon = Double.NaN;
                var loc = venue == null ? null : Json.obj(venue.get("location"));
                var coords = loc == null ? null : Json.obj(loc.get("defaultCoordinates"));
                if (coords != null) { lat = Json.num(coords.get("latitude")); lon = Json.num(coords.get("longitude")); }
                var status = Json.obj(game.get("status"));
                String state = status == null ? "Scheduled" : Json.str(status, "detailedState");
                LiveEvent.Score score = null;
                if (home.get("score") != null || away.get("score") != null) {
                    var ls = Json.obj(game.get("linescore"));
                    String detail = ls == null || ls.get("currentInning") == null || "Final".equals(state) ? state
                            : (ls.get("inningState") != null ? Json.str(ls, "inningState") : Json.str(ls, "inningHalf")) + " " + Json.str(ls, "currentInningOrdinal");
                    score = new LiveEvent.Score(homeName, awayName, home.get("score") == null ? 0 : ((Number) home.get("score")).intValue(),
                            away.get("score") == null ? 0 : ((Number) away.get("score")).intValue(), state, detail);
                }
                if ("Postponed".equals(state) || "Cancelled".equals(state)) continue;
                out.add(new LiveEvent("mlb:" + game.get("gamePk"), awayName + " at " + homeName, LiveEvent.Category.SPORTS,
                        start, start.plus(Duration.ofHours(3)), venue == null ? null : Json.str(venue, "name"), lat, lon,
                        "MLB Stats API", null, Set.of(CROWD, SCOREBOARD, ANNOUNCE), score));
            }
        }
        return out;
    }

    // ---------------- Ticketmaster Discovery v2 ----------------
    public static String ticketmasterUrl(String apiKey, double lat, double lon, int radiusMiles, Instant from, Instant to) {
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
        return "https://app.ticketmaster.com/discovery/v2/events.json?apikey=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
                + "&latlong=" + lat + "," + lon + "&radius=" + radiusMiles + "&unit=miles&size=200&sort=date,asc"
                + "&startDateTime=" + f.format(from) + "&endDateTime=" + f.format(to);
    }

    public static List<LiveEvent> parseTicketmaster(String json) {
        List<LiveEvent> out = new ArrayList<>();
        var emb = Json.obj(Json.obj(Json.parse(json)).get("_embedded"));
        if (emb == null) return out;
        for (Object o : Json.arr(emb.get("events"))) {
            var e = Json.obj(o);
            var dates = Json.obj(e.get("dates"));
            var start = Json.obj(dates.get("start"));
            String dt = Json.str(start, "dateTime");
            if (dt == null) continue;                                    // date-only / TBA events are skipped
            var st = Json.obj(dates.get("status"));
            if (st != null && ("cancelled".equals(st.get("code")) || "postponed".equals(st.get("code")))) continue;
            Instant s = Instant.parse(dt);
            String segment = "";
            for (Object c : Json.arr(e.get("classifications"))) { var seg = Json.obj(Json.obj(c).get("segment")); if (seg != null) { segment = Json.str(seg, "name"); break; } }
            LiveEvent.Category cat = switch (segment) { case "Music" -> LiveEvent.Category.CONCERT; case "Sports" -> LiveEvent.Category.SPORTS; default -> LiveEvent.Category.OTHER; };
            String venue = null; double lat = Double.NaN, lon = Double.NaN;
            var ve = Json.obj(e.get("_embedded"));
            if (ve != null && !Json.arr(ve.get("venues")).isEmpty()) {
                var v = Json.obj(Json.arr(ve.get("venues")).get(0));
                venue = Json.str(v, "name");
                var loc = Json.obj(v.get("location"));
                if (loc != null) { lat = Double.parseDouble(String.valueOf(loc.get("latitude"))); lon = Double.parseDouble(String.valueOf(loc.get("longitude"))); }
            }
            Set<LiveEvent.Activity> acts = cat == LiveEvent.Category.CONCERT ? Set.of(CROWD, STAGE_MUSIC, ANNOUNCE)
                    : cat == LiveEvent.Category.SPORTS ? Set.of(CROWD, SCOREBOARD, ANNOUNCE) : Set.of(CROWD, ANNOUNCE);
            Duration len = cat == LiveEvent.Category.CONCERT ? Duration.ofHours(3) : Duration.ofMinutes(150);
            out.add(new LiveEvent("tm:" + e.get("id"), Json.str(e, "name"), cat, s, s.plus(len), venue, lat, lon, "Ticketmaster", Json.str(e, "url"), acts, null));
        }
        return out;
    }

    // ---------------- iCalendar ----------------
    public static List<LiveEvent> parseIcs(String text, String feedName, ZoneId defaultZone) {
        List<LiveEvent> out = new ArrayList<>();
        // RFC 5545 line unfolding: CRLF followed by space/tab continues the previous line
        String unfolded = text.replace("\r\n", "\n").replaceAll("\n[ \t]", "");
        Map<String, String> cur = null;
        Map<String, String> params = null;
        for (String line : unfolded.split("\n")) {
            if (line.equals("BEGIN:VEVENT")) { cur = new HashMap<>(); params = new HashMap<>(); continue; }
            if (line.equals("END:VEVENT") && cur != null) {
                try {
                    Instant s = icsTime(cur.get("DTSTART"), params.get("DTSTART"), defaultZone);
                    Instant e = cur.containsKey("DTEND") ? icsTime(cur.get("DTEND"), params.get("DTEND"), defaultZone) : s.plus(Duration.ofHours(2));
                    double lat = Double.NaN, lon = Double.NaN;
                    if (cur.containsKey("GEO")) { String[] g = cur.get("GEO").split("[;,]"); lat = Double.parseDouble(g[0]); lon = Double.parseDouble(g[1]); }
                    String title = unescape(cur.getOrDefault("SUMMARY", "Event"));
                    String uid = cur.getOrDefault("UID", title + s);
                    String lower = title.toLowerCase(Locale.ROOT);
                    LiveEvent.Category cat = lower.contains("concert") || lower.contains("music") || lower.contains("band") ? LiveEvent.Category.CONCERT
                            : lower.contains(" vs") || lower.contains(" at ") && lower.contains("game") ? LiveEvent.Category.SPORTS : LiveEvent.Category.COMMUNITY;
                    Set<LiveEvent.Activity> acts = new HashSet<>(Set.of(CROWD, ANNOUNCE));
                    if (lower.contains("firework")) acts.add(FIREWORKS);
                    if (cat == LiveEvent.Category.CONCERT) acts.add(STAGE_MUSIC);
                    out.add(new LiveEvent("ics:" + feedName + ":" + uid, title, cat, s, e, unescape(cur.get("LOCATION")), lat, lon, feedName,
                            cur.get("URL"), Set.copyOf(acts), null));
                } catch (RuntimeException ex) { /* skip malformed event */ }
                cur = null;
                continue;
            }
            if (cur == null) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon), value = line.substring(colon + 1);
            String key = name.contains(";") ? name.substring(0, name.indexOf(';')) : name;
            cur.put(key, value);
            if (name.contains(";")) params.put(key, name.substring(name.indexOf(';') + 1));
        }
        return out;
    }

    static Instant icsTime(String v, String params, ZoneId def) {
        if (v == null) throw new IllegalArgumentException("no time");
        if (v.length() == 8) return LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(def).toInstant();   // all-day
        boolean utc = v.endsWith("Z");
        LocalDateTime t = LocalDateTime.parse(v.replace("Z", ""), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));
        if (utc) return t.toInstant(ZoneOffset.UTC);
        ZoneId z = def;
        if (params != null && params.contains("TZID=")) {
            String id = params.substring(params.indexOf("TZID=") + 5).split(";")[0].replace("\"", "");
            try { z = ZoneId.of(id); } catch (DateTimeException ignored) {}
        }
        return t.atZone(z).toInstant();
    }

    static String unescape(String s) { return s == null ? null : s.replace("\\,", ",").replace("\\;", ";").replace("\\n", " ").replace("\\\\", "\\"); }
}
