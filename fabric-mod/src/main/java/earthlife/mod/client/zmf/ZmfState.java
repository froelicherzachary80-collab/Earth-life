package earthlife.mod.client.zmf;

import earthlife.mod.zmf.ZmfPayloads;

import java.util.*;

/** What this client knows about nearby ZMF multiplexes: their programs (from the server) and the locator target. */
public final class ZmfState {
    private ZmfState() {}

    record Sched(ZmfPayloads.Schedule s, long offsetMs) {}
    static final Map<String, Sched> schedules = new HashMap<>();
    public static volatile ZmfPayloads.Nearest nearest;
    public static volatile long nearestAtMs;

    public static void receive(ZmfPayloads.Schedule s) { schedules.put(s.id(), new Sched(s, s.serverNowMs() - System.currentTimeMillis())); }

    public static ZmfPayloads.Schedule schedule(String mid) { Sched s = schedules.get(mid); return s == null ? null : s.s; }

    /** Server time now (the schedules' clock), estimated from the last schedule's timestamp. */
    public static long serverNow(String mid) { Sched s = schedules.get(mid); return System.currentTimeMillis() + (s == null ? 0 : s.offsetMs); }

    public static ZmfPayloads.RoomInfo room(String mid, String audKey) {
        var s = schedule(mid);
        if (s == null) return null;
        for (var r : s.rooms()) if (r.key().equals(audKey)) return r;
        return null;
    }

    public static ZmfPayloads.RoomInfo room(String audKey) {
        for (var s : schedules.values()) for (var r : s.s.rooms()) if (r.key().equals(audKey)) return r;
        return null;
    }

    public static Collection<ZmfPayloads.Schedule> all() { List<ZmfPayloads.Schedule> l = new ArrayList<>(); for (var s : schedules.values()) l.add(s.s); return l; }

    public static void reset() { schedules.clear(); nearest = null; }

    static String clock(long ms) {
        var t = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault());
        return String.format(Locale.ROOT, "%02d:%02d", t.getHour(), t.getMinute());
    }

    public static String countdown(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** One status line for a room ("Now playing · 34 min in", "Starts 19:40 (in 12:03)", "Coming Soon"). */
    public static String status(ZmfPayloads.RoomInfo r, long now) {
        return switch (r.status()) {
            case "playing" -> "Now playing · " + Math.max(0, (now - r.filmStartMs()) / 60000) + " min in";
            case "trailers" -> "Starts " + clock(r.filmStartMs()) + " · in " + countdown(r.filmStartMs() - now);
            case "next" -> r.nextStartMs() > 0 ? "Next " + clock(r.nextStartMs()) + " · in " + countdown(r.nextStartMs() - now) : "No more shows today";
            case "web" -> "Web cinema";
            default -> "Coming Soon";
        };
    }
}
