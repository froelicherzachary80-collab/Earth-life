package earthlife.mod.zmf;

import earthlife.core.cinema.MovieScheduler;
import earthlife.core.util.Json;

import java.util.*;

/** One multiplex in a world: where it is, how it was planned, its state and its film program (persisted as JSON). */
public final class MultiplexRecord {
    public enum State { PLANNED, BUILDING, BUILT, REJECTED }

    public String id;                 // "m<cellX>_<cellZ>" for natural ones, "c<n>" for command-generated
    public int cellX, cellZ;
    public int x, y, z;               // facade middle (blueprint origin) and floor level
    public int heading;               // 0/90/180/270, the direction from the doors into the building
    public int rooms;
    public long seed;
    public String style = "";
    public String name = "";
    public boolean natural;
    public State state = State.PLANNED;
    public long builtAtMs;
    public String note = "";          // why a site was rejected, for diagnostics
    // program
    public long programDay = Long.MIN_VALUE;
    public long programSalt;          // changed by "reschedule" to shuffle the films (the layout seed stays)
    public List<MovieScheduler.Movie> program = new ArrayList<>();
    public final Map<Integer, MovieScheduler.Movie> manual = new HashMap<>();    // admin's choice per room (persists)
    public final Map<Integer, String> browserUrl = new HashMap<>();             // browser rooms currently showing the web
    public final Map<Integer, Long> browserSince = new HashMap<>();

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("cellX", (long) cellX); m.put("cellZ", (long) cellZ);
        m.put("x", (long) x); m.put("y", (long) y); m.put("z", (long) z); m.put("heading", (long) heading);
        m.put("rooms", (long) rooms); m.put("seed", String.valueOf(seed)); m.put("style", style); m.put("name", name);
        m.put("natural", natural); m.put("state", state.name()); m.put("builtAt", builtAtMs); m.put("note", note);
        m.put("programDay", programDay); m.put("programSalt", String.valueOf(programSalt));
        List<Object> pr = new ArrayList<>();
        for (var mv : program) pr.add(mv == null ? null : movie(mv));
        m.put("program", pr);
        Map<String, Object> man = new LinkedHashMap<>();
        manual.forEach((k, v) -> man.put(String.valueOf(k), movie(v)));
        m.put("manual", man);
        Map<String, Object> br = new LinkedHashMap<>();
        browserUrl.forEach((k, v) -> br.put(String.valueOf(k), Map.of("url", v, "since", browserSince.getOrDefault(k, 0L))));
        m.put("browser", br);
        return m;
    }

    public static MultiplexRecord fromJson(Map<String, Object> m) {
        MultiplexRecord r = new MultiplexRecord();
        r.id = Json.str(m, "id");
        r.cellX = (int) Json.num(m.get("cellX")); r.cellZ = (int) Json.num(m.get("cellZ"));
        r.x = (int) Json.num(m.get("x")); r.y = (int) Json.num(m.get("y")); r.z = (int) Json.num(m.get("z"));
        r.heading = (int) Json.num(m.get("heading")); r.rooms = (int) Json.num(m.get("rooms"));
        r.seed = Long.parseLong(Json.str(m, "seed"));
        r.style = Objects.requireNonNullElse(Json.str(m, "style"), ""); r.name = Objects.requireNonNullElse(Json.str(m, "name"), "");
        r.natural = Boolean.TRUE.equals(m.get("natural"));
        r.state = State.valueOf(Objects.requireNonNullElse(Json.str(m, "state"), "PLANNED"));
        r.builtAtMs = m.get("builtAt") == null ? 0 : (long) Json.num(m.get("builtAt"));
        r.note = Objects.requireNonNullElse(Json.str(m, "note"), "");
        r.programDay = m.get("programDay") == null ? Long.MIN_VALUE : (long) Json.num(m.get("programDay"));
        r.programSalt = m.get("programSalt") == null ? 0 : Long.parseLong(Json.str(m, "programSalt"));
        for (Object o : Json.arr(m.get("program"))) r.program.add(o == null ? null : movie(Json.obj(o)));
        if (m.get("manual") != null) Json.obj(m.get("manual")).forEach((k, v) -> r.manual.put(Integer.parseInt(k), movie(Json.obj(v))));
        if (m.get("browser") != null) Json.obj(m.get("browser")).forEach((k, v) -> {
            var b = Json.obj(v);
            r.browserUrl.put(Integer.parseInt(k), Json.str(b, "url"));
            r.browserSince.put(Integer.parseInt(k), b.get("since") == null ? 0L : (long) Json.num(b.get("since")));
        });
        return r;
    }

    public static Map<String, Object> movie(MovieScheduler.Movie m) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("source", m.source()); o.put("title", m.title()); o.put("year", (long) m.year()); o.put("genre", m.genre() == null ? "" : m.genre());
        o.put("runtimeMs", m.runtimeMs()); o.put("format3d", m.format3d() == null ? "2D" : m.format3d());
        return o;
    }

    public static MovieScheduler.Movie movie(Map<String, Object> o) {
        return new MovieScheduler.Movie(Json.str(o, "source"), Json.str(o, "title"), o.get("year") == null ? 0 : (int) Json.num(o.get("year")),
                Objects.requireNonNullElse(Json.str(o, "genre"), ""), o.get("runtimeMs") == null ? 0 : (long) Json.num(o.get("runtimeMs")),
                Objects.requireNonNullElse(Json.str(o, "format3d"), "2D"));
    }
}
