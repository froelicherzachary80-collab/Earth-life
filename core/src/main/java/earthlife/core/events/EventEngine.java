package earthlife.core.events;

import earthlife.core.build.Landmarks;
import earthlife.core.geo.GeoPoint;
import earthlife.core.util.Json;
import earthlife.core.world.AtomicFiles;
import earthlife.core.world.Towns;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/**
 * Collects real events, places them in the world, and answers "what's happening now / next".
 *
 *  - Sources refresh on their own interval (holidays: computed; MLB: 30 min, 1 min while a game is live;
 *    Ticketmaster/iCal: 6 h). Network calls go through an injectable fetcher so tests use recorded data.
 *  - Every successful fetch is cached to disk, so the schedule still works offline.
 *  - Placement: an event's venue name or coordinates are matched to a landmark (within 800 m, or by name);
 *    otherwise it is anchored to the nearest town. Events farther than the region radius are dropped.
 *  - Duplicates (same venue, start within 30 min, similar title) from different feeds are merged.
 */
public final class EventEngine {
    public record Config(boolean holidays, boolean mlb, List<String> mlbTeams, List<Integer> mlbSportIds,
                         String ticketmasterKey, int radiusMiles, List<String> icsFeeds, ZoneId zone) {
        public static Config defaults() {
            return new Config(true, true, List.of("Rochester Red Wings"), List.of(11), null, 50, List.of(), ZoneId.of("America/New_York"));
        }
    }

    /** Where an event happens in game: at a landmark, at a town centre, or region-wide. */
    public record Placement(LiveEvent event, String landmarkId, String town, double lat, double lon) {}

    public interface Fetcher { String get(String url) throws Exception; }

    private final Config cfg;
    private final GeoPoint regionCentre;
    private final double regionRadiusM;
    private final List<Landmarks.Landmark> landmarks;
    private final List<Towns.Town> towns;
    private final Path cacheFile;
    private final Fetcher fetcher;
    private final Map<String, List<LiveEvent>> bySource = new LinkedHashMap<>();
    private final Map<String, Instant> lastFetch = new HashMap<>();
    private final Map<String, Integer> teamIds = new HashMap<>();
    private final List<String> errors = new ArrayList<>();

    public EventEngine(Config cfg, GeoPoint regionCentre, double regionRadiusM, List<Landmarks.Landmark> landmarks, List<Towns.Town> towns,
                       Path cacheFile, Fetcher fetcher) {
        this.cfg = cfg; this.regionCentre = regionCentre; this.regionRadiusM = regionRadiusM; this.landmarks = landmarks; this.towns = towns;
        this.cacheFile = cacheFile; this.fetcher = fetcher;
        loadCache();
    }

    public synchronized List<String> errors() { return List.copyOf(errors); }

    /** Refresh whatever is due. Safe to call often (e.g. every 10 s from a worker thread). */
    public void refresh(Instant now) {
        if (cfg.holidays()) {
            LocalDate d = now.atZone(cfg.zone()).toLocalDate();
            List<LiveEvent> h = new ArrayList<>(Holidays.forYear(d.getYear() - 1, cfg.zone()));
            h.addAll(Holidays.forYear(d.getYear(), cfg.zone()));
            h.addAll(Holidays.forYear(d.getYear() + 1, cfg.zone()));
            put("holidays", h);
        }
        if (cfg.mlb()) {
            boolean live = all().stream().anyMatch(e -> e.source().equals("MLB Stats API") && e.activeAt(now));
            if (due("mlb", now, live ? Duration.ofMinutes(1) : Duration.ofMinutes(30))) fetch("mlb", now, () -> {
                List<LiveEvent> games = new ArrayList<>();
                LocalDate from = now.atZone(cfg.zone()).toLocalDate().minusDays(1);
                for (int sport : cfg.mlbSportIds()) for (String team : cfg.mlbTeams()) {
                    Integer id = teamIds.get(sport + ":" + team);
                    if (id == null) {
                        id = Sources.mlbTeamId(fetcher.get(Sources.mlbTeamsUrl(sport)), team).orElse(null);
                        if (id == null) { synchronized (this) { errors.add("MLB: no team named " + team); } continue; }
                        teamIds.put(sport + ":" + team, id);
                    }
                    games.addAll(Sources.parseMlbSchedule(fetcher.get(Sources.mlbScheduleUrl(sport, id, from, from.plusDays(45)))));
                }
                return games;
            });
        }
        if (cfg.ticketmasterKey() != null && !cfg.ticketmasterKey().isBlank() && due("ticketmaster", now, Duration.ofHours(6)))
            fetch("ticketmaster", now, () -> Sources.parseTicketmaster(fetcher.get(Sources.ticketmasterUrl(cfg.ticketmasterKey(),
                    regionCentre.lat(), regionCentre.lon(), cfg.radiusMiles(), now.minus(Duration.ofHours(6)), now.plus(Duration.ofDays(60))))));
        for (String url : cfg.icsFeeds()) {
            String key = "ics:" + url;
            if (due(key, now, Duration.ofHours(6))) fetch(key, now, () -> Sources.parseIcs(fetcher.get(url), shortName(url), cfg.zone()));
        }
    }

    interface Job { List<LiveEvent> run() throws Exception; }

    private void fetch(String key, Instant now, Job job) {
        try {
            List<LiveEvent> got = job.run();
            put(key, got);
            synchronized (this) { lastFetch.put(key, now); errors.removeIf(e -> e.startsWith(key + ":")); }
            saveCache();
        } catch (Exception e) {
            synchronized (this) { lastFetch.put(key, now.minus(Duration.ofMinutes(25))); errors.add(key + ": " + e.getMessage()); }
        }
    }

    private synchronized boolean due(String key, Instant now, Duration every) {
        Instant last = lastFetch.get(key);
        return last == null || !now.isBefore(last.plus(every));
    }

    private synchronized void put(String key, List<LiveEvent> evs) { bySource.put(key, List.copyOf(evs)); }

    private static String shortName(String url) {
        String s = url.replaceAll("^https?://", "");
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    public synchronized List<LiveEvent> all() {
        List<LiveEvent> out = new ArrayList<>();
        bySource.values().forEach(out::addAll);
        return out;
    }

    /** Resolves where the event happens in the world, or empty if it's outside the region. */
    public Optional<Placement> place(LiveEvent e) {
        if (e.regionWide()) return Optional.of(new Placement(e, null, null, Double.NaN, Double.NaN));
        Optional<Landmarks.Landmark> byName = Landmarks.find(landmarks, e.venue());
        if (byName.isPresent() && (Double.isNaN(e.lat()) || byName.get().pos().distanceTo(new GeoPoint(e.lat(), e.lon())) < 5_000))
            return Optional.of(new Placement(e, byName.get().id(), null, byName.get().pos().lat(), byName.get().pos().lon()));
        if (Double.isNaN(e.lat())) return Optional.empty();
        GeoPoint p = new GeoPoint(e.lat(), e.lon());
        if (p.distanceTo(regionCentre) > regionRadiusM) return Optional.empty();
        for (Landmarks.Landmark l : landmarks) if (l.pos().distanceTo(p) < 800) return Optional.of(new Placement(e, l.id(), null, l.pos().lat(), l.pos().lon()));
        String town = towns.isEmpty() ? null : Towns.nearest(towns, p).name();
        return Optional.of(new Placement(e, null, town, e.lat(), e.lon()));
    }

    /** Events in the region overlapping [from, to), de-duplicated, sorted by start. */
    public List<Placement> between(Instant from, Instant to) {
        List<Placement> out = new ArrayList<>();
        for (LiveEvent e : all()) {
            if (!e.end().isAfter(from) || !e.start().isBefore(to)) continue;
            place(e).ifPresent(out::add);
        }
        out.sort(Comparator.comparing(p -> p.event().start()));
        List<Placement> dedup = new ArrayList<>();
        outer:
        for (Placement p : out) {
            for (Placement q : dedup) {
                if (Objects.equals(p.landmarkId(), q.landmarkId()) && p.landmarkId() != null
                        && Duration.between(q.event().start(), p.event().start()).abs().toMinutes() <= 30
                        && similar(p.event().title(), q.event().title())) continue outer;
            }
            dedup.add(p);
        }
        return dedup;
    }

    public List<Placement> activeAt(Instant t) { return between(t, t.plusMillis(1)); }
    public List<Placement> upcoming(Instant t, Duration horizon) { return between(t, t.plus(horizon)).stream().filter(p -> p.event().start().isAfter(t)).toList(); }

    static boolean similar(String a, String b) {
        Set<String> wa = new HashSet<>(Arrays.asList(a.toLowerCase(Locale.ROOT).split("\\W+"))), wb = new HashSet<>(Arrays.asList(b.toLowerCase(Locale.ROOT).split("\\W+")));
        wa.removeIf(w -> w.length() < 4); wb.removeIf(w -> w.length() < 4);
        if (wa.isEmpty() || wb.isEmpty()) return false;
        Set<String> inter = new HashSet<>(wa); inter.retainAll(wb);
        return inter.size() * 2 >= Math.min(wa.size(), wb.size());
    }

    // ---- offline cache ----
    private void saveCache() {
        if (cacheFile == null) return;
        Map<String, Object> root = new LinkedHashMap<>();
        synchronized (this) {
            for (var e : bySource.entrySet()) {
                if (e.getKey().equals("holidays")) continue;
                List<Object> list = new ArrayList<>();
                for (LiveEvent ev : e.getValue()) list.add(toJson(ev));
                root.put(e.getKey(), list);
            }
        }
        try { AtomicFiles.write(cacheFile, Json.write(root).getBytes(StandardCharsets.UTF_8)); } catch (IOException ignored) {}
    }

    private void loadCache() {
        if (cacheFile == null || !Files.exists(cacheFile)) return;
        try {
            var root = Json.obj(Json.parse(Files.readString(cacheFile)));
            for (var e : root.entrySet()) {
                List<LiveEvent> evs = new ArrayList<>();
                for (Object o : Json.arr(e.getValue())) evs.add(fromJson(Json.obj(o)));
                bySource.put(e.getKey(), evs);
            }
        } catch (Exception ignored) { /* corrupt cache: refetch */ }
    }

    static Map<String, Object> toJson(LiveEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id()); m.put("title", e.title()); m.put("category", e.category().name());
        m.put("start", e.start().toString()); m.put("end", e.end().toString()); m.put("venue", e.venue());
        m.put("lat", Double.isNaN(e.lat()) ? null : e.lat()); m.put("lon", Double.isNaN(e.lon()) ? null : e.lon());
        m.put("source", e.source()); m.put("url", e.url());
        m.put("activities", e.activities().stream().map(Enum::name).sorted().toList());
        if (e.score() != null) {
            Map<String, Object> sc = new LinkedHashMap<>();
            sc.put("home", e.score().home()); sc.put("away", e.score().away()); sc.put("homeRuns", e.score().homeRuns());
            sc.put("awayRuns", e.score().awayRuns()); sc.put("status", e.score().status()); sc.put("detail", e.score().detail());
            m.put("score", sc);
        }
        return m;
    }

    static LiveEvent fromJson(Map<String, Object> m) {
        Function<String, Double> num = k -> m.get(k) == null ? Double.NaN : ((Number) m.get(k)).doubleValue();
        Set<LiveEvent.Activity> acts = EnumSet.noneOf(LiveEvent.Activity.class);
        for (Object a : Json.arr(m.get("activities"))) acts.add(LiveEvent.Activity.valueOf((String) a));
        var s = Json.obj(m.get("score"));
        LiveEvent.Score score = s == null ? null : new LiveEvent.Score((String) s.get("home"), (String) s.get("away"),
                ((Number) s.get("homeRuns")).intValue(), ((Number) s.get("awayRuns")).intValue(), (String) s.get("status"), (String) s.get("detail"));
        return new LiveEvent((String) m.get("id"), (String) m.get("title"), LiveEvent.Category.valueOf((String) m.get("category")),
                Instant.parse((String) m.get("start")), Instant.parse((String) m.get("end")), (String) m.get("venue"), num.apply("lat"), num.apply("lon"),
                (String) m.get("source"), (String) m.get("url"), acts, score);
    }
}
