package earthlife.core.cinema;

import java.util.*;

/**
 * Decides which presentation formats every auditorium of a multiplex offers (ZMF Cinema).
 *
 * Formats are capabilities, and a room may combine two of them (3D + 4DX, IMAX + 3D, 4DX + IMAX, 3D + ScreenX), so the
 * number of capabilities can exceed the number of rooms. The allocator is constraint based: it picks how many rooms
 * combine two capabilities, then the per-format counts (each inside its allowed range) so that
 * {@code sum(counts) = rooms + combos}, then concrete combo pairs that every format can carry, and finally validates the
 * whole plan. Different seeds give different but always valid arrangements; nothing is chosen independently and then
 * hoped to add up.
 *
 * Every multiplex also gets four designated browser auditoriums (A 2D, B 3D, C 4DX, D IMAX) chosen among its own rooms -
 * they are part of the room count, never extra rooms.
 */
public final class FormatAllocator {
    private FormatAllocator() {}

    public enum Format {
        F2D("2D", 3, 5), F3D("3D", 3, 10), F4DX("4DX", 5, 10), IMAX("IMAX", 1, 6), SCREENX("ScreenX", 1, 1);
        public final String label; public final int min, max;
        Format(String label, int min, int max) { this.label = label; this.min = min; this.max = max; }
        public static Format parse(String s) {
            for (Format f : values()) if (f.label.equalsIgnoreCase(s) || f.name().equalsIgnoreCase(s)) return f;
            throw new IllegalArgumentException("unknown format " + s);
        }
    }

    /** The two-format combinations a room may offer. 2D rooms are always plain 2D. */
    public static final List<EnumSet<Format>> COMBOS = List.of(
            EnumSet.of(Format.F3D, Format.F4DX), EnumSet.of(Format.IMAX, Format.F3D),
            EnumSet.of(Format.F4DX, Format.IMAX), EnumSet.of(Format.F3D, Format.SCREENX));

    /** Browser room slots: A = 2D web cinema, B = 3D, C = 4DX, D = IMAX. */
    public static final char[] BROWSER_SLOTS = {'A', 'B', 'C', 'D'};
    static final Format[] BROWSER_FORMAT = {Format.F2D, Format.F3D, Format.F4DX, Format.IMAX};

    public static final int MIN_ROOMS = sumMin(), MAX_ROOMS = sumMax();

    static int sumMin() { int s = 0; for (Format f : Format.values()) s += f.min; return s - 1; }   // one combo can save a room
    static int sumMax() { int s = 0; for (Format f : Format.values()) s += f.max; return s; }

    /** One auditorium's formats and its browser slot ('-' when it is not a browser room). */
    public record Room(int index, EnumSet<Format> formats, char browser) {
        public boolean has(Format f) { return formats.contains(f); }
        public String label() {
            List<String> l = new ArrayList<>();
            // conventional order: IMAX / ScreenX first, then 3D, then 4DX ("IMAX 3D", "4DX 3D")
            for (Format f : new Format[]{Format.IMAX, Format.SCREENX, Format.F4DX, Format.F3D, Format.F2D}) if (formats.contains(f)) l.add(f.label);
            return String.join(" ", l);
        }
        public String code() {
            StringBuilder sb = new StringBuilder();
            for (Format f : formats) { if (!sb.isEmpty()) sb.append('+'); sb.append(f.label); }
            return sb.toString();
        }
    }

    public record Plan(int rooms, List<Room> list, Map<Format, Integer> counts, int combos) {
        public long count(Format f) { return list.stream().filter(r -> r.has(f)).count(); }
        public Optional<Room> browser(char slot) { return list.stream().filter(r -> r.browser == slot).findFirst(); }
    }

    public static boolean supports(int rooms) { return rooms >= MIN_ROOMS && rooms <= MAX_ROOMS; }

    /** A valid plan for this many rooms (12-32), different per seed. */
    public static Plan allocate(int rooms, long seed) {
        if (!supports(rooms)) throw new IllegalArgumentException("a multiplex has " + MIN_ROOMS + "-" + MAX_ROOMS + " rooms, not " + rooms);
        Random r = new Random(seed * 0x9E3779B97F4A7C15L + rooms);
        for (int attempt = 0; attempt < 400; attempt++) {
            Plan p = tryAllocate(rooms, r);
            if (p != null && validate(p).isEmpty()) return p;
        }
        throw new IllegalStateException("no valid format plan for " + rooms + " rooms");          // unreachable for 12-32
    }

    static Plan tryAllocate(int rooms, Random r) {
        Format[] fs = Format.values();
        int sumMin = 0, sumMax = 0;
        for (Format f : fs) { sumMin += f.min; sumMax += f.max; }
        int cMin = Math.max(0, sumMin - rooms);                 // rooms short of the minimum capabilities must combine
        int cMax = Math.min(sumMax - rooms, rooms - Format.F2D.min);
        if (cMin > cMax) return null;
        // usually few combined rooms; occasionally a couple more for variety
        int c = cMin + (cMax > cMin ? (r.nextInt(4) == 0 ? r.nextInt(Math.min(3, cMax - cMin) + 1) : (r.nextBoolean() ? 0 : Math.min(1, cMax - cMin))) : 0);
        int total = rooms + c;
        EnumMap<Format, Integer> k = new EnumMap<>(Format.class);
        for (Format f : fs) k.put(f, f.min);
        int left = total - sumMin;
        while (left > 0) {
            List<Format> open = new ArrayList<>();
            for (Format f : fs) if (k.get(f) < f.max) open.add(f);
            if (open.isEmpty()) return null;
            // weight 3D and 4DX higher: they are the formats with the most room to grow
            Format pick = open.get(r.nextInt(open.size()));
            if ((pick == Format.F2D || pick == Format.IMAX) && r.nextInt(3) == 0 && open.size() > 1) pick = open.get(r.nextInt(open.size()));
            k.put(pick, k.get(pick) + 1); left--;
        }
        // concrete combos: each uses one capability of two formats; 2D never combines
        EnumMap<Format, Integer> used = new EnumMap<>(Format.class);
        for (Format f : fs) used.put(f, 0);
        List<EnumSet<Format>> chosen = new ArrayList<>();
        for (int i = 0; i < c; i++) {
            List<EnumSet<Format>> ok = new ArrayList<>();
            for (EnumSet<Format> combo : COMBOS) {
                boolean fits = true;
                for (Format f : combo) if (used.get(f) + 1 > k.get(f)) fits = false;
                // keep at least one plain room of each format with min >= 3 so every format still has a "pure" room
                for (Format f : combo) if (f.min >= 3 && k.get(f) - used.get(f) - 1 < 1) fits = false;
                if (fits) { ok.add(combo); if (combo.equals(COMBOS.get(0))) ok.add(combo); }   // "4DX 3D" is the classic combined room

            }
            if (ok.isEmpty()) return null;
            EnumSet<Format> combo = ok.get(r.nextInt(ok.size()));
            chosen.add(combo);
            for (Format f : combo) used.put(f, used.get(f) + 1);
        }
        List<EnumSet<Format>> roomsList = new ArrayList<>(chosen);
        for (Format f : fs) for (int i = used.get(f); i < k.get(f); i++) roomsList.add(EnumSet.of(f));
        if (roomsList.size() != rooms) return null;
        Collections.shuffle(roomsList, r);
        // browser rooms: prefer a plain room of the slot's format
        char[] slot = new char[rooms];
        Arrays.fill(slot, '-');
        for (int s = 0; s < 4; s++) {
            Format want = BROWSER_FORMAT[s];
            int best = -1;
            for (int i = 0; i < rooms; i++) if (slot[i] == '-' && roomsList.get(i).contains(want) && !roomsList.get(i).contains(Format.SCREENX)) {
                if (best < 0 || roomsList.get(i).size() < roomsList.get(best).size()) best = i;
            }
            if (best < 0) return null;
            slot[best] = BROWSER_SLOTS[s];
        }
        List<Room> list = new ArrayList<>();
        for (int i = 0; i < rooms; i++) list.add(new Room(i, roomsList.get(i), slot[i]));
        return new Plan(rooms, List.copyOf(list), Collections.unmodifiableMap(k), c);
    }

    /** Every rule a plan must satisfy; empty = valid. */
    public static List<String> validate(Plan p) {
        List<String> err = new ArrayList<>();
        if (p.list().size() != p.rooms()) err.add("room count " + p.list().size() + " != " + p.rooms());
        Set<Integer> ids = new HashSet<>();
        for (Room r : p.list()) {
            if (!ids.add(r.index())) err.add("duplicate room " + r.index());
            if (r.formats().isEmpty() || r.formats().size() > 2) err.add("room " + r.index() + " has " + r.formats().size() + " formats");
            if (r.formats().size() == 2 && !COMBOS.contains(r.formats())) err.add("room " + r.index() + " combines " + r.code() + " (not an allowed combination)");
        }
        for (Format f : Format.values()) {
            long n = p.count(f);
            if (n < f.min || n > f.max) err.add(f.label + " rooms: " + n + " (allowed " + f.min + "-" + f.max + ")");
        }
        if (p.count(Format.SCREENX) != 1) err.add("exactly one ScreenX room required");
        for (int s = 0; s < 4; s++) {
            char c = BROWSER_SLOTS[s];
            var rooms = p.list().stream().filter(r -> r.browser() == c).toList();
            if (rooms.size() != 1) err.add("browser room " + c + ": " + rooms.size() + " rooms");
            else if (!rooms.get(0).has(BROWSER_FORMAT[s])) err.add("browser room " + c + " lacks " + BROWSER_FORMAT[s].label);
        }
        return err;
    }

    /** Short summary for logs and the admin panel: "12 rooms: 3x2D 4x3D 5x4DX 1xIMAX 1xScreenX (1 combined)". */
    public static String summary(Plan p) {
        StringBuilder sb = new StringBuilder(p.rooms() + " rooms:");
        for (Format f : Format.values()) sb.append(' ').append(p.count(f)).append('x').append(f.label);
        return sb.append(" (").append(p.combos()).append(" combined)").toString();
    }
}
