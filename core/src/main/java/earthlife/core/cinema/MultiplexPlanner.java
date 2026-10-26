package earthlife.core.cinema;

import earthlife.core.build.Blueprint;
import earthlife.core.cinema.FormatAllocator.Format;

import java.util.*;

/**
 * Builds the blueprint of a ZMF Cinema multiplex (12-32 auditoriums) for ordinary Minecraft worlds.
 *
 * Local frame (see {@link Blueprint}): u runs from the entrance facade (u = 0) into the building, v across it, y up from
 * the ground-floor slab (y = 0). Layout:
 * <pre>
 *   u &lt; 0          plaza with lamp posts
 *   u = 0          facade (style dependent: brick, glass front, concrete, futuristic, Hollywood, mall, urban)
 *   lobby          ticket kiosks by the doors, ticket counter, concession counter, candy displays, lounge seating,
 *                  restrooms, showtime board; two-floor buildings get a double-height atrium, a grand staircase and
 *                  an upper balcony
 *   hallway        a spine along +u on every floor; auditoriums on both sides, their screens on the hallway wall,
 *                  stadium rows rising away from it; doors beside the screens
 * </pre>
 * Everything random (style, room sizes, order, lobby shape, stair side, colours, seat styles, ceiling heights) comes from
 * the seed, so the same multiplex is rebuilt identically after a reload, and two multiplexes rarely look alike.
 *
 * Block ids are vanilla or "earthlife:" ids; a suffix "@u-", "@u+", "@v-", "@v+" gives a facing in the blueprint frame.
 */
public final class MultiplexPlanner {
    private MultiplexPlanner() {}

    public static final int FLOOR_HEIGHT = 15;           // slab-to-slab in two-floor buildings
    static final int HALL_HW = 2;                        // hallway half-width (interior is 2*HALL_HW+1 = 5 wide)

    public enum Style {
        BRICK("Brick", "minecraft:bricks", "minecraft:stone_bricks", "minecraft:glass", "minecraft:polished_andesite", "minecraft:ochre_froglight", "minecraft:red_terracotta"),
        GLASS_FRONT("Glass front", "minecraft:light_gray_concrete", "minecraft:smooth_stone", "minecraft:light_blue_stained_glass", "minecraft:polished_diorite", "minecraft:sea_lantern", "minecraft:white_concrete"),
        CONCRETE_MODERN("Modern concrete", "minecraft:gray_concrete", "minecraft:light_gray_concrete", "minecraft:gray_stained_glass", "minecraft:polished_deepslate", "minecraft:pearlescent_froglight", "minecraft:black_concrete"),
        FUTURISTIC("Futuristic glass", "minecraft:white_concrete", "minecraft:quartz_block", "minecraft:cyan_stained_glass", "minecraft:smooth_quartz", "minecraft:verdant_froglight", "minecraft:light_blue_concrete"),
        HOLLYWOOD("Vintage Hollywood", "minecraft:smooth_red_sandstone", "minecraft:gold_block", "minecraft:orange_stained_glass", "minecraft:red_concrete", "minecraft:glowstone", "minecraft:red_terracotta"),
        MALL("Shopping mall", "minecraft:smooth_sandstone", "minecraft:sandstone", "minecraft:glass", "minecraft:polished_granite", "minecraft:sea_lantern", "minecraft:terracotta"),
        URBAN("Urban multiplex", "minecraft:deepslate_bricks", "minecraft:polished_blackstone", "minecraft:black_stained_glass", "minecraft:polished_blackstone_bricks", "minecraft:shroomlight", "minecraft:gray_terracotta");
        public final String label, facade, trim, glass, lobbyFloor, light, accent;
        Style(String label, String facade, String trim, String glass, String lobbyFloor, String light, String accent) {
            this.label = label; this.facade = facade; this.trim = trim; this.glass = glass; this.lobbyFloor = lobbyFloor; this.light = light; this.accent = accent;
        }
    }

    /** Room colour themes (acoustic walls, carpet). */
    static final String[][] THEMES = {
            {"minecraft:gray_wool", "minecraft:red_carpet"}, {"minecraft:black_wool", "minecraft:purple_carpet"},
            {"minecraft:red_wool", "minecraft:gray_carpet"}, {"minecraft:blue_wool", "minecraft:light_blue_carpet"},
            {"minecraft:brown_wool", "minecraft:orange_carpet"}, {"minecraft:purple_wool", "minecraft:magenta_carpet"},
            {"minecraft:green_wool", "minecraft:lime_carpet"}, {"minecraft:cyan_wool", "minecraft:blue_carpet"}};

    /** One auditorium: local geometry plus everything the game needs to run it. */
    public record Room(int index, String name, FormatAllocator.Room formats, int floor, int side,
                       int u0, int u1, int v0, int v1, int floorY, int height,
                       double screenU, double screenV, double screenY, double screenW, double screenH, int normalV,
                       double[] door, double[] sign, List<int[]> seats, String seatBlock, String theme, boolean curved) {
        /** Interior box (u0+1..u1-1, floorY+1..floorY+height, v0+1..v1-1) for "is the player inside this room". */
        public int[] box() { return new int[]{u0 + 1, floorY + 1, v0 + 1, u1 - 1, floorY + height, v1 - 1}; }
    }

    public record Marker(String kind, double u, double y, double v, int normalU, int normalV, double w, double h) {}

    public record Plan(long seed, int roomCount, Style style, int floors, Blueprint bp, List<Room> rooms, List<Marker> markers,
                       FormatAllocator.Plan formats, int lengthU, int halfWidthV, int maxHeight, String name) {
        public Marker marker(String kind) { return markers.stream().filter(m -> m.kind.equals(kind)).findFirst().orElse(null); }
    }

    static final String[] NAME_A = {"Grand", "Starlight", "Regal", "Majestic", "Silver Screen", "Paramount", "Odeon", "Lumière", "Galaxy", "Empire",
            "Rialto", "Orion", "Aurora", "Metropolitan", "Pinnacle", "Nova", "Summit", "Harbor", "Crown", "Vista"};
    static final String[] NAME_B = {"Cinemas", "Multiplex", "Theatres", "Picture House", "Cineplex", "Movie Palace", "Screens", "Cinema Centre"};

    /** Rooms for a tier keyword or a number 12-32. */
    public static int roomsFor(String tier) {
        return switch (tier.toLowerCase(Locale.ROOT)) {
            case "small" -> 12; case "large" -> 20; case "mega", "huge" -> 32;
            default -> Integer.parseInt(tier);
        };
    }

    /** Random size for natural generation: mostly the three tiers, sometimes a size in between. */
    public static int randomRooms(Random r) {
        int x = r.nextInt(100);
        if (x < 40) return 12;
        if (x < 55) return 13 + r.nextInt(7);
        if (x < 80) return 20;
        if (x < 88) return 21 + r.nextInt(11);
        return 32;
    }

    public static Plan plan(int rooms, long seed) { return plan(rooms, seed, null); }

    public static Plan plan(int rooms, long seed, Style forced) {
        Random r = new Random(seed ^ 0x5DEECE66DL ^ ((long) rooms << 40));
        FormatAllocator.Plan fp = FormatAllocator.allocate(rooms, seed);
        Style style = forced != null ? forced : Style.values()[r.nextInt(Style.values().length)];
        int floors = rooms <= 16 ? 1 : 2;
        String name = NAME_A[r.nextInt(NAME_A.length)] + " " + NAME_B[r.nextInt(NAME_B.length)];
        Blueprint bp = new Blueprint();
        List<Marker> markers = new ArrayList<>();
        int FH = FLOOR_HEIGHT, hw = HALL_HW;

        // ---- room sizes: (u width, v length, interior height) per format
        record Size(int wd, int len, int h) {}
        List<FormatAllocator.Room> fr = new ArrayList<>(fp.list());
        Map<Integer, Size> size = new HashMap<>();
        boolean luxuryTier = rooms >= 20;
        for (var f : fr) {
            Size s;
            if (f.has(Format.IMAX)) s = new Size(21 + r.nextInt(5), 26 + r.nextInt(7), floors == 2 ? 16 + r.nextInt(2) : 13);
            else if (f.has(Format.SCREENX)) s = new Size(19 + r.nextInt(3), 22 + r.nextInt(5), floors == 2 ? 13 : 12);
            else s = new Size(13 + r.nextInt(5) + (luxuryTier ? 1 : 0), 18 + r.nextInt(6), 9 + r.nextInt(3));
            size.put(f.index(), s);
        }
        // ---- distribute rooms over floors and sides: big rooms upstairs (their ceilings need the height)
        List<List<FormatAllocator.Room>> perFloor = new ArrayList<>();
        for (int f = 0; f < floors; f++) perFloor.add(new ArrayList<>());
        List<FormatAllocator.Room> big = new ArrayList<>(), std = new ArrayList<>();
        for (var f : fr) (f.has(Format.IMAX) || f.has(Format.SCREENX) ? big : std).add(f);
        Collections.shuffle(std, r);
        if (floors == 1) { perFloor.get(0).addAll(std); perFloor.get(0).addAll(r.nextBoolean() ? 0 : perFloor.get(0).size(), big); }
        else {
            perFloor.get(1).addAll(big);
            // fill the top floor up to about half of all rooms, the rest downstairs
            int topTarget = rooms / 2;
            Iterator<FormatAllocator.Room> it = std.iterator();
            while (it.hasNext() && perFloor.get(1).size() < topTarget) { perFloor.get(1).add(it.next()); it.remove(); }
            perFloor.get(0).addAll(std);
            Collections.shuffle(perFloor.get(1), r);
        }

        // ---- lobby dimensions
        int lobbyDepth = floors == 2 ? (rooms >= 28 ? 30 : 26) : (rooms >= 15 ? 22 : 20);
        int lobbyH = floors == 2 ? 2 * FH - 1 : 12 + r.nextInt(3);
        // place rooms floor by floor: u cursor per side
        int hallStart = lobbyDepth + 1;               // first room wall u
        List<Room> out = new ArrayList<>();
        int[][] sideEnd = new int[floors][2];
        int[][] sideLen = new int[floors][2];         // max room length per side (v)
        for (int f = 0; f < floors; f++) {
            int[] cursor = {hallStart, hallStart};
            int fy = f * FH;
            int k = 0;
            for (var fmt : perFloor.get(f)) {
                Size s = size.get(fmt.index());
                int sideIdx = cursor[0] <= cursor[1] ? 0 : 1;       // keep both sides about equally long
                if (k < 2) sideIdx = k;                              // first rooms: one on each side
                int side = sideIdx == 0 ? 1 : -1;
                int u0 = cursor[sideIdx], u1 = u0 + s.wd + 1;
                int v0, v1;
                if (side > 0) { v0 = hw + 1; v1 = hw + 1 + s.len + 1; } else { v1 = -(hw + 1); v0 = v1 - s.len - 1; }
                cursor[sideIdx] = u1;
                sideLen[f][sideIdx] = Math.max(sideLen[f][sideIdx], s.len + 2);
                int h = f == floors - 1 ? s.h : Math.min(s.h, FH - 3);
                String[] theme = THEMES[r.nextInt(THEMES.length)];
                String seat = fmt.has(Format.F4DX) ? "earthlife:motion_seat" : fmt.has(Format.IMAX) || (luxuryTier && r.nextInt(3) == 0) ? "earthlife:luxury_recliner" : "earthlife:recliner";
                out.add(auditorium(bp, r, fmt, f, side, u0, u1, v0, v1, fy, h, theme, seat));
                k++;
            }
            sideEnd[f] = cursor.clone();
        }
        int hallEnd = 0;
        for (int[] e : sideEnd) hallEnd = Math.max(hallEnd, Math.max(e[0], e[1]));
        hallEnd += 1;

        // ---- hallways (after the rooms, so their doors and walls stay)
        for (int f = 0; f < floors; f++) {
            int fy = f * FH, hh = Math.min(6, FH - 4);
            for (int u = hallStart; u <= hallEnd; u++) for (int v = -hw - 1; v <= hw + 1; v++) {
                boolean wall = Math.abs(v) == hw + 1;
                if (wall && bp.get(u, fy + 2, v) != null) continue;                // a room wall already closes it
                bp.set(u, fy, v, "minecraft:polished_andesite");
                for (int y = fy + 1; y <= fy + hh; y++) bp.set(u, y, v, wall || u == hallEnd ? style.trim : "minecraft:air");
                bp.set(u, fy + hh + 1, v, "minecraft:smooth_stone");
            }
            for (int u = hallStart; u < hallEnd; u++) for (int v = -hw; v <= hw; v++) bp.set(u, fy + 1, v, Math.abs(v) <= 1 ? "minecraft:red_carpet" : "minecraft:gray_carpet");
            for (int u = hallStart + 2; u < hallEnd; u += 5) bp.set(u, fy + hh + 1, 0, style.light);
            // re-open the doors from the hallway into each room on this floor
            for (Room rm : out) if (rm.floor() == f) {
                int dv = rm.side() > 0 ? rm.v0() : rm.v1();
                int du = (int) Math.round(rm.door()[0]);
                bp.set(du, fy + 1, dv, "minecraft:air"); bp.set(du, fy + 2, dv, "minecraft:air");
                bp.set(du, fy + 1, dv + (rm.side() > 0 ? 1 : -1), rm.theme().split("\\|")[1]);
            }
            markers.add(new Marker("wayfinding:" + f, hallStart + 1.5, fy + 4.2, -hw - 0.47, 0, 1, 3.2, 1.2));
        }

        // ---- storage spaces under upper-floor rooms that stick out beyond the ground floor (no floating rooms)
        if (floors == 2) for (Room rm : out) if (rm.floor() == 1) {
            for (int u = rm.u0(); u <= rm.u1(); u++) for (int v = rm.v0(); v <= rm.v1(); v++) {
                boolean edge = u == rm.u0() || u == rm.u1() || v == rm.v0() || v == rm.v1();
                for (int y = 0; y < FH; y++) {
                    if (bp.get(u, y, v) != null) continue;
                    bp.set(u, y, v, y == 0 ? "minecraft:smooth_stone" : edge ? style.facade : "minecraft:air");
                }
            }
        }
        // the upper slab over the whole ground floor (between ceilings and the floor above)
        if (floors == 2) for (Room rm : out) if (rm.floor() == 0) {
            for (int u = rm.u0(); u <= rm.u1(); u++) for (int v = rm.v0(); v <= rm.v1(); v++)
                if (bp.get(u, FH, v) == null) bp.set(u, FH, v, style.facade);
        }

        // ---- lobby
        int halfW = hw + 1;
        halfW = Math.max(halfW, hw + Math.min(sideLen[0][0], sideLen[0][1]));
        halfW = Math.max(halfW, 15);
        lobby(bp, r, style, floors, lobbyDepth, halfW, lobbyH, markers, out.size(), rooms);
        // the hallway openings in the lobby's back wall (ground floor and, upstairs, from the balcony)
        for (int f = 0; f < floors; f++) {
            int fy = f * FH;
            for (int v = -hw; v <= hw; v++) for (int y = fy + 1; y <= fy + 4; y++) bp.set(lobbyDepth, y, v, "minecraft:air");
            for (int v = -hw; v <= hw; v++) bp.set(lobbyDepth, fy, v, "minecraft:polished_andesite");
        }

        // ---- facade sign
        markers.add(new Marker("facade", -0.53, lobbyH - 2.5, 0, -1, 0, Math.min(2 * halfW - 6, 24), 3.0));

        int[] b = bp.bounds();
        int maxH = 0;
        for (Room rm : out) maxH = Math.max(maxH, rm.floorY() + rm.height() + 1);
        maxH = Math.max(maxH, lobbyH + 1);
        out.sort(Comparator.comparingInt(Room::index));
        return new Plan(seed, rooms, style, floors, bp, List.copyOf(out), List.copyOf(markers), fp, b[2], Math.max(-b[1], b[3]), maxH, name);
    }

    /** One auditorium in [u0,u1] x [v0,v1] (walls on the boundary), floor block at fy. Screen on the hallway wall. */
    static Room auditorium(Blueprint bp, Random r, FormatAllocator.Room fmt, int floor, int side, int u0, int u1, int v0, int v1, int fy, int h,
                           String[] theme, String seatBlock) {
        String acoustic = theme[0], carpet = theme[1];
        int screenWallV = side > 0 ? v0 : v1, backV = side > 0 ? v1 : v0;
        int dir = side > 0 ? 1 : -1;                        // from the screen towards the back
        boolean imax = fmt.has(Format.IMAX), sx = fmt.has(Format.SCREENX), fx = fmt.has(Format.F4DX);
        // shell
        for (int u = u0; u <= u1; u++) for (int v = v0; v <= v1; v++) {
            bp.set(u, fy, v, "minecraft:smooth_stone");
            bp.set(u, fy + h + 1, v, "minecraft:black_concrete");
            boolean edgeU = u == u0 || u == u1, edgeV = v == v0 || v == v1;
            for (int y = fy + 1; y <= fy + h; y++) {
                String block;
                if (v == screenWallV) block = "minecraft:black_concrete";
                else if (edgeU || edgeV) block = sx && edgeU ? "minecraft:light_gray_concrete" : acoustic;     // ScreenX: side walls are projection surfaces
                else block = "minecraft:air";
                bp.set(u, y, v, block);
            }
        }
        // screen
        int inner = u1 - u0 - 1;
        double aspect = imax ? 1.9 : sx ? 2.0 : 2.39;
        double sw = imax ? inner - 1.0 : Math.max(6, inner - 3.0);
        double sh = Math.min(h - (imax ? 2.5 : 4.0), sw / aspect);
        if (imax) sw = Math.min(sw, sh * 2.2);
        double su = (u0 + u1) / 2.0, sv = screenWallV + dir * 0.53, sy = fy + (imax ? 1.6 : 2.6) + sh / 2;
        // stadium rows: first row 5-6 blocks from the screen (IMAX a bit more), then one row every 2 blocks, 1 block higher each
        List<int[]> seats = new ArrayList<>();
        int first = screenWallV + dir * (imax ? 7 : 6);
        int depth = Math.abs(backV - first) - 1;
        int rows = Math.max(1, Math.min(h - 4, depth / 2 + 1));
        String facing = side > 0 ? "@v-" : "@v+";
        for (int row = 0; row < rows; row++) {
            int vs = first + dir * 2 * row;
            for (int k = -1; k <= (row == rows - 1 ? 6 : 0); k++) {
                int v = vs + dir * k;
                if ((dir > 0 && v >= backV) || (dir < 0 && v <= backV)) break;
                for (int u = u0 + 1; u <= u1 - 1; u++) {
                    for (int y = fy + 1; y <= fy + row; y++) bp.set(u, y, v, "minecraft:smooth_stone");
                    boolean aisle = u <= u0 + 2 || u >= u1 - 2;
                    if (aisle && k == -1 && row > 0) bp.set(u, fy + row, v, "minecraft:sea_lantern");      // step lights
                    bp.set(u, fy + row + 1, v, carpet);
                }
            }
            for (int u = u0 + 3; u <= u1 - 3; u++) {
                if (fx && (u - u0) % 3 == 0) continue;                 // motion seats in pairs with space for the effect modules
                bp.set(u, fy + row + 1, vs, seatBlock + facing);
                seats.add(new int[]{u, fy + row + 1, vs});
            }
        }
        // front area carpet
        for (int v = screenWallV + dir; v != first - dir; v += dir) for (int u = u0 + 1; u <= u1 - 1; u++) bp.set(u, fy + 1, v, carpet);
        // dim ceiling lights
        for (int u = u0 + 3; u < u1; u += 5) for (int v = Math.min(v0, v1) + 4; v < Math.max(v0, v1); v += 6) bp.set(u, fy + h + 1, v, "minecraft:ochre_froglight");
        // equipment: projector window at the back, speakers, 4DX effect modules
        int pu = (u0 + u1) / 2;
        bp.set(pu, fy + h - 1, backV, "earthlife:projector" + (side > 0 ? "@v-" : "@v+"));
        if (imax || sx || r.nextBoolean()) for (int v = Math.min(v0, v1) + 3; v < Math.max(v0, v1) - 2; v += 4) {
            bp.set(u0, fy + Math.min(h - 1, 6), v, "earthlife:cinema_speaker@u+");
            bp.set(u1, fy + Math.min(h - 1, 6), v, "earthlife:cinema_speaker@u-");
        }
        if (imax) for (int u = u0 + 2; u < u1 - 1; u += 3) bp.set(u, fy + h, screenWallV + dir, "earthlife:cinema_speaker" + (side > 0 ? "@v+" : "@v-"));
        if (fx) for (int v = first; dir > 0 ? v < backV : v > backV; v += dir * 3) { bp.set(u0, fy + 3, v, "earthlife:effect_module@u+"); bp.set(u1, fy + 3, v, "earthlife:effect_module@u-"); }
        // door beside the screen (in the screen wall, at the u0 end) and a sign above it in the hallway
        int du = u0 + 1;
        double[] door = {du, fy + 1, screenWallV - dir};
        double[] sign = {du + 1.6, fy + 4.4, screenWallV - dir * 0.53};
        String fmtName = fmt.label();
        String name = (fmt.browser() != '-' ? "Web Cinema " + fmt.browser() : "Auditorium " + (fmt.index() + 1)) + " · " + fmtName;
        return new Room(fmt.index(), name, fmt, floor, side, u0, u1, v0, v1, fy, h, su, sv, sy, sw, sh, dir, door, sign, seats, seatBlock,
                acoustic + "|" + carpet, imax);
    }

    static void lobby(Blueprint bp, Random r, Style s, int floors, int D, int hwL, int H, List<Marker> markers, int roomsBuilt, int rooms) {
        int FH = FLOOR_HEIGHT;
        // Zones (u = depth from the doors): 1-4 entrance + kiosks; 5-8 ticket counter (ticket side); middle: lounge;
        // back: concession counter across the lobby; restrooms in the back corner of the ticket side; stairs along the
        // other side wall in two-floor buildings.
        for (int u = 0; u <= D; u++) for (int v = -hwL; v <= hwL; v++) {
            bp.set(u, 0, v, s.lobbyFloor);
            bp.set(u, H + 1, v, s.trim);
            boolean edge = u == 0 || u == D || Math.abs(v) == hwL;
            for (int y = 1; y <= H; y++) bp.set(u, y, v, edge ? s.facade : "minecraft:air");
        }
        for (int u = 1; u < D; u++) for (int v = -1; v <= 1; v++) bp.set(u, 0, v, s.accent);           // runner to the hallway
        // facade: glass with a 5-wide entrance and a marquee band of lights
        boolean glassy = s == Style.GLASS_FRONT || s == Style.FUTURISTIC || s == Style.MALL;
        for (int v = -hwL + 1; v < hwL; v++) for (int y = 1; y <= Math.min(H, glassy ? H - 1 : 6); y++) {
            boolean door = Math.abs(v) <= 2 && y <= 3;
            boolean pillar = !glassy && Math.floorMod(v, 6) == 0;
            bp.set(0, y, v, door ? "minecraft:air" : pillar ? s.trim : s.glass);
        }
        int marquee = Math.min(H - 1, glassy ? H - 1 : 8);
        for (int v = -hwL + 1; v < hwL; v++) bp.set(0, marquee, v, Math.floorMod(v, 2) == 0 ? s.light : s.trim);
        if (s == Style.HOLLYWOOD || s == Style.URBAN) {          // canopy over the doors
            for (int u = -4; u <= -1; u++) for (int v = -5; v <= 5; v++) bp.set(u, 5, v, Math.abs(v) == 5 || u == -4 ? s.light : s.accent);
        }
        if (s == Style.FUTURISTIC) for (int y = 1; y <= H; y++) { bp.set(-1, y, -hwL, s.light); bp.set(-1, y, hwL, s.light); }   // light fins
        // plaza with lamp posts
        for (int u = -8; u <= -1; u++) for (int v = -hwL; v <= hwL; v++) if (bp.get(u, 0, v) == null) bp.set(u, 0, v, "minecraft:smooth_stone");
        for (int v = -hwL + 2; v <= hwL - 2; v += 8) { bp.set(-6, 1, v, "minecraft:polished_blackstone_wall"); bp.set(-6, 2, v, "minecraft:polished_blackstone_wall"); bp.set(-6, 3, v, s.light); }
        // ceiling lights
        for (int u = 3; u < D; u += 5) for (int v = -hwL + 3; v < hwL - 1; v += 6) bp.set(u, H + 1, v, s.light);
        int ts = r.nextBoolean() ? 1 : -1, ss = -ts;              // ticket side, stair side
        // self-service ticket kiosks by the doors, facing the entrance
        int kv = Math.min(hwL - 3, 7);
        for (int v : new int[]{-kv, -kv + 2, kv - 2, kv}) bp.set(3, 1, v, "earthlife:ticket_kiosk@u-");
        markers.add(new Marker("kiosk", 2.5, 1, kv - 1, -1, 0, 0, 0));
        // staffed ticket counter along the ticket-side wall
        int tv = ts * (hwL - 3);
        for (int u = 5; u <= 8; u++) {
            bp.set(u, 1, tv, "minecraft:smooth_quartz");
            if (u % 2 == 1) bp.set(u, 2, tv, "earthlife:ticket_kiosk" + (ts > 0 ? "@v-" : "@v+"));
        }
        // restrooms: two rooms in the back corner of the ticket side
        int ru1 = D - 1, ru0 = ru1 - 11;
        for (int i = 0; i < 2; i++) {
            int u0 = ru0 + 6 * i, u1 = u0 + 5;
            int vin = ts * (hwL - 5), vout = ts * hwL;
            restroom(bp, u0, u1, Math.min(vin, vout), Math.max(vin, vout), ts, vin);
            markers.add(new Marker("restroom:" + (i == 0 ? "Men" : "Women"), (u0 + u1) / 2.0, 3.4, vin - ts * 0.47, 0, -ts, 2.4, 0.8));
        }
        // concession counter across the back of the lobby, path to the hallway left free
        int cu = D - (floors == 2 ? 6 : 4);
        int cw = Math.min(hwL - 7, 9);
        for (int v = -cw; v <= cw; v++) {
            if (Math.abs(v) <= 2) continue;
            bp.set(cu, 1, v, "minecraft:smooth_quartz");
            String m = Math.floorMod(v, 3) == 0 ? "earthlife:popcorn_machine@u-" : Math.floorMod(v, 3) == 1 ? "earthlife:soda_fountain@u-" : "earthlife:candy_display@u-";
            bp.set(cu + 1, 1, v, m);
        }
        markers.add(new Marker("concessions", cu - 1.5, 1, 3.5, -1, 0, 0, 0));
        // snack shelves along the stair-side wall (in front of the stairs in two-floor buildings)
        int shelfEnd = floors == 2 ? D - 5 - FH : cu - 2;
        for (int u = 5; u <= shelfEnd; u += 2) bp.set(u, 1, ss * (hwL - 1), "earthlife:snack_shelf" + (ss > 0 ? "@v-" : "@v+"));
        // lounge seating in the middle of the lobby
        int lu = Math.max(9, cu - 5);
        for (int v : new int[]{-6, -5, 5, 6}) { bp.set(lu, 1, v, "earthlife:lounge_sofa@u-"); bp.set(lu - 3, 1, v, "earthlife:lounge_sofa@u+"); }
        // showtime board on the back wall, above the hallway entrance (above the balcony in two-floor lobbies)
        markers.add(new Marker("board", D - 0.53, floors == 2 ? FH + 7.5 : Math.min(H - 2.5, 8.5), 0, -1, 0, Math.min(14, 2 * hwL - 8), 4.0));
        // grand staircase to the upper balcony in two-floor buildings
        if (floors == 2) {
            int balcony0 = D - 4, us = balcony0 - FH;
            int sv0 = ss > 0 ? hwL - 4 : -hwL + 1;
            for (int i = 0; i < FH; i++) {
                for (int v = sv0; v < sv0 + 3; v++) {
                    for (int y = 1; y <= i; y++) bp.set(us + i, y, v, s.trim);
                    bp.set(us + i, i + 1, v, "minecraft:polished_andesite_stairs@u+");
                }
                bp.set(us + i, i + 2, ss > 0 ? sv0 - 1 : sv0 + 3, s.glass);                      // railing on the open side
            }
            for (int u = balcony0; u < D; u++) for (int v = -hwL + 1; v < hwL; v++) {
                bp.set(u, FH, v, s.lobbyFloor);
                for (int y = FH + 1; y <= FH + 4; y++) if (bp.get(u, y, v) == null) bp.set(u, y, v, "minecraft:air");
                if (u == balcony0 && (v < sv0 || v > sv0 + 2)) bp.set(u, FH + 1, v, s.glass);        // balcony railing
            }
            if (rooms >= 28) for (int v = -7; v <= 7; v++) if (Math.abs(v) > 2 && v % 3 == 0) bp.set(D - 2, FH + 1, v, v % 2 == 0 ? "earthlife:popcorn_machine@u-" : "earthlife:soda_fountain@u-");
            markers.add(new Marker("stairs", us + 0.5, 1, sv0 + 1.5, 1, 0, 0, 0));
        }
    }

    static void restroom(Blueprint bp, int u0, int u1, int v0, int v1, int side, int doorV) {
        for (int u = u0; u <= u1; u++) for (int v = v0; v <= v1; v++) {
            boolean edge = u == u0 || u == u1 || v == v0 || v == v1;
            bp.set(u, 0, v, "minecraft:white_concrete");
            for (int y = 1; y <= 4; y++) bp.set(u, y, v, edge ? "minecraft:quartz_bricks" : "minecraft:air");
            bp.set(u, 5, v, "minecraft:smooth_quartz");
        }
        // doorway facing the lobby, a sink (cauldron), a stall wall and a light
        int mu = (u0 + u1) / 2;
        bp.set(mu, 1, doorV, "minecraft:air"); bp.set(mu, 2, doorV, "minecraft:air");
        int back = doorV + side * 3;
        bp.set(u0 + 1, 1, back, "minecraft:cauldron");
        bp.set(u1 - 1, 1, back, "minecraft:white_concrete"); bp.set(u1 - 1, 2, back, "minecraft:white_concrete");
        bp.set(mu, 4, (v0 + v1) / 2, "minecraft:sea_lantern");
    }
}
