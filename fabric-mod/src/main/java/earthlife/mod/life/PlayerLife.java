package earthlife.mod.life;

import earthlife.core.util.Json;

import java.util.*;

/** Everything EarthLife remembers about one player in one world (saved as earthlife/players/<uuid>.json). */
public final class PlayerLife {
    public record Waypoint(String name, String kind, double x, double y, double z) {
        Map<String, Object> toJson() { return new LinkedHashMap<>(Map.of("name", name, "kind", kind, "x", x, "y", y, "z", z)); }
        static Waypoint of(Object o) {
            var m = Json.obj(o);
            return new Waypoint(Json.str(m, "name"), Objects.requireNonNullElse(Json.str(m, "kind"), "pin"), Json.num(m.get("x")), Json.num(m.getOrDefault("y", 64)), Json.num(m.get("z")));
        }
    }

    public final String uuid;
    public boolean arrived;              // got the safe spawn + scenario kit
    public boolean welcomed;             // closed the welcome overlay
    public String scenario = "NEW_LIFE";
    public final Set<String> objectivesDone = new LinkedHashSet<>();
    public final List<Waypoint> waypoints = new ArrayList<>();
    public Waypoint home, work;
    public boolean shareLocation;        // opt-in: show me on other players' LifeMaps (if the server allows it)
    public int candy;
    public final Set<String> treated = new HashSet<>();      // "<year>:<residentId>" houses that already gave candy
    public final Set<String> contests = new HashSet<>();     // "<year>:costume", "<year>:carving"
    public int version = 4;
    boolean dirty;

    public PlayerLife(String uuid) { this.uuid = uuid; }

    public void markDirty() { dirty = true; }

    public Optional<Waypoint> waypoint(String name) {
        if (home != null && home.name().equalsIgnoreCase(name)) return Optional.of(home);
        if (work != null && work.name().equalsIgnoreCase(name)) return Optional.of(work);
        return waypoints.stream().filter(w -> w.name().equalsIgnoreCase(name)).findFirst();
    }

    Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", version); m.put("uuid", uuid); m.put("arrived", arrived); m.put("welcomed", welcomed); m.put("scenario", scenario);
        m.put("objectivesDone", new ArrayList<>(objectivesDone));
        List<Object> w = new ArrayList<>(); for (Waypoint p : waypoints) w.add(p.toJson()); m.put("waypoints", w);
        if (home != null) m.put("home", home.toJson());
        if (work != null) m.put("work", work.toJson());
        m.put("shareLocation", shareLocation); m.put("candy", candy);
        m.put("treated", new ArrayList<>(treated)); m.put("contests", new ArrayList<>(contests));
        return m;
    }

    static PlayerLife fromJson(String uuid, Map<String, Object> m) {
        PlayerLife p = new PlayerLife(uuid);
        p.arrived = Boolean.TRUE.equals(m.get("arrived"));
        p.welcomed = Boolean.TRUE.equals(m.get("welcomed"));
        if (m.get("scenario") != null) p.scenario = m.get("scenario").toString();
        for (Object o : Json.arr(m.get("objectivesDone"))) p.objectivesDone.add(o.toString());
        for (Object o : Json.arr(m.get("waypoints"))) p.waypoints.add(Waypoint.of(o));
        if (m.get("home") != null) p.home = Waypoint.of(m.get("home"));
        if (m.get("work") != null) p.work = Waypoint.of(m.get("work"));
        p.shareLocation = Boolean.TRUE.equals(m.get("shareLocation"));
        if (m.get("candy") instanceof Number n) p.candy = n.intValue();
        for (Object o : Json.arr(m.get("treated"))) p.treated.add(o.toString());
        for (Object o : Json.arr(m.get("contests"))) p.contests.add(o.toString());
        return p;
    }

    /** For tests and migration checks. */
    public String toJsonString() { return Json.write(toJson()); }
    public static PlayerLife parse(String uuid, String json) { return fromJson(uuid, Json.obj(Json.parse(json))); }
}
