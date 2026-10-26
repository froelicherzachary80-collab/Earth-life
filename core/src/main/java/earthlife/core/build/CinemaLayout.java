package earthlife.core.build;

import java.util.*;

/**
 * Generated movie theater inside a real cinema's building (location and footprint from OpenStreetMap; the inside is a
 * gameplay layout, never a claim about the real building's interior).
 *
 * Local frame (see {@link Blueprint}): u runs from the entrance facade (u = 0, facing the street) into the building,
 * v across it. Layout front to back:
 * <pre>
 *   u = 0            glass entrance, ticket kiosks
 *   lobby            concession counter with popcorn machines and soda fountains
 *   corridor         doors into the auditoriums
 *   auditoriums      side by side (a second row behind a back corridor in deep buildings):
 *                    screen on the corridor wall, stadium rows of recliners rising towards the back
 * </pre>
 * One auditorium (the largest) can be a 4D motion auditorium with motion seats.
 * Seat ids carry a facing suffix: "earthlife:recliner@u-" = the seat faces local -u (towards the screen).
 */
public final class CinemaLayout {
    public static final int HEIGHT = 11;              // auditorium ceiling (blocks above the floor)

    public record Auditorium(int index, String name, boolean motion, double screenU, double screenV, double screenY,
                             double screenW, double screenH, double[] door, List<int[]> seats) {}

    public record Layout(Blueprint bp, List<Auditorium> auditoriums, double[] kiosk, double[] concessions, double depth, double width) {}

    static final String WALL = "minecraft:gray_concrete", OUTER = "minecraft:light_gray_concrete", ACOUSTIC = "minecraft:gray_wool",
            CEIL = "minecraft:black_concrete", FLOOR = "minecraft:smooth_stone", CARPET = "minecraft:red_carpet", LIGHT = "minecraft:sea_lantern",
            LOBBY_FLOOR = "minecraft:polished_blackstone", AIR = "minecraft:air", GLASS = "minecraft:glass", COUNTER = "minecraft:smooth_quartz",
            SCREEN_WALL = "minecraft:black_concrete";

    /**
     * @param screens   number of screens from the map (0 = unknown, estimated from the floor area)
     * @param depth     building depth along u (metres), width across (v)
     * @param fourD     add a 4D motion auditorium
     */
    public static Layout build(int screens, double depth, double width, boolean fourD) {
        int D = Math.max(16, (int) Math.floor(depth) - 1), W = Math.max(12, (int) Math.floor(width) - 1);
        int hw = W / 2;
        Blueprint bp = new Blueprint();
        int lobby = D >= 40 ? 13 : D >= 28 ? 10 : 7;
        int corridor = D >= 28 ? 3 : 2;
        int audStart = lobby + corridor;                 // u of the first auditorium's screen wall
        int rowDepth = D - audStart;
        boolean twoRows = rowDepth >= 2 * 20 + 3 && W >= 30;
        int audDepth = twoRows ? (rowDepth - 3) / 2 : rowDepth;
        int perRow = Math.max(1, W / 13);
        int estimate = (int) Math.round(depth * width / 520.0);
        int want = screens > 0 ? screens : Math.max(1, Math.min(16, estimate));
        int rows = twoRows && want > perRow ? 2 : 1;
        int n = Math.max(1, Math.min(want, perRow * rows));

        // shell: outer walls, roof, floor
        bp.room(0, -hw, D, hw, 0, HEIGHT + 1, OUTER, FLOOR, "minecraft:gray_concrete");
        // lobby
        bp.box(1, 0, -hw + 1, lobby - 1, 0, hw - 1, LOBBY_FLOOR);
        for (int v = -1; v <= 1; v++) bp.box(1, 0, v, lobby - 1, 0, v, "minecraft:red_concrete");
        for (int v = -hw + 1; v <= hw - 1; v++) bp.set(0, 0, v, LOBBY_FLOOR);
        for (int y = 1; y <= 4; y++) for (int v = -hw + 2; v <= hw - 2; v++) bp.set(0, y, v, y <= 2 && Math.abs(v) <= 1 ? AIR : GLASS);   // glass facade, 3-wide door
        bp.box(1, HEIGHT + 1, -hw + 1, lobby - 1, HEIGHT + 1, hw - 1, "minecraft:white_concrete");
        for (int u = 3; u < lobby; u += 4) for (int v = -hw + 3; v <= hw - 3; v += 5) bp.set(u, HEIGHT + 1, v, LIGHT);
        bp.box(1, 1, -hw + 1, lobby - 1, HEIGHT, hw - 1, AIR);
        double[] kiosk = {2.5, 1, -Math.min(4, hw - 2) + 0.5};
        bp.set(2, 1, -Math.min(4, hw - 2), "earthlife:ticket_kiosk@u-");
        bp.set(2, 1, Math.min(4, hw - 2), "earthlife:ticket_kiosk@u-");
        // concession counter across the back of the lobby (gaps at both ends lead to the corridor)
        int cu = Math.max(3, lobby - 3), cw = Math.min(7, hw - 3);
        for (int v = -cw; v <= cw; v++) {
            bp.set(cu, 1, v, COUNTER);
            String machine = Math.floorMod(v, 4) == 0 ? "earthlife:popcorn_machine@u-" : Math.floorMod(v, 4) == 2 ? "earthlife:soda_fountain@u-" : null;
            if (machine != null && cu + 1 < lobby) bp.set(cu + 1, 1, v, machine);
        }
        double[] concessions = {cu - 1.0, 1, 0.5};
        // corridor wall between lobby and corridor (openings at the ends of the counter)
        for (int y = 1; y <= HEIGHT; y++) for (int v = -hw + 1; v <= hw - 1; v++) bp.set(lobby, y, v, Math.abs(v) > cw + 1 && y <= 3 ? AIR : WALL);
        bp.box(lobby + 1, 0, -hw + 1, audStart - 1, 0, hw - 1, FLOOR);
        bp.box(lobby + 1, 2, -hw + 1, audStart - 1, HEIGHT, hw - 1, AIR);
        bp.box(lobby + 1, 1, -hw + 1, audStart - 1, 1, hw - 1, "minecraft:gray_carpet");
        for (int v = -hw + 2; v <= hw - 2; v += 5) bp.set(lobby + 1, HEIGHT, v, LIGHT);

        List<Auditorium> auds = new ArrayList<>();
        int motionIdx = -1;
        int k = 0;
        for (int row = 0; row < rows && k < n; row++) {
            int a0 = row == 0 ? audStart : audStart + audDepth + 3;
            int a1 = row == 0 ? (rows == 2 ? audStart + audDepth : D) : D;
            int inRow = Math.min(perRow, n - k);
            if (row == 1) {
                // back corridor between the rows, reached by a hallway along the +v side
                bp.box(a0 - 3, 0, -hw + 1, a0 - 1, 0, hw - 1, FLOOR);
                bp.box(a0 - 3, 1, -hw + 1, a0 - 1, HEIGHT, hw - 1, AIR);
            }
            int span = (W - 1) / inRow;
            for (int i = 0; i < inRow; i++, k++) {
                int b0 = -hw + i * span, b1 = i == inRow - 1 ? hw : b0 + span;
                if (rows == 2 && i == inRow - 1) b1 = hw - 4;           // leave the hallway along the +v wall
                boolean motion = fourD && motionIdx < 0 && i == 0 && row == 0;
                if (motion) motionIdx = k;
                auds.add(auditorium(bp, k, motion, a0, a1, b0, b1));
            }
            if (rows == 2 && row == 0) {
                // hallway from the front corridor to the back corridor along the +v wall
                bp.box(audStart, 0, hw - 3, a1 + 3, 0, hw - 1, FLOOR);
                bp.box(audStart, 1, hw - 3, a1 + 3, 1, hw - 1, "minecraft:gray_carpet");
                bp.box(audStart, 2, hw - 3, a1 + 3, HEIGHT, hw - 1, AIR);
            }
        }
        return new Layout(bp, auds, kiosk, concessions, D, W);
    }

    /** One auditorium in [a0,a1] x [b0,b1] (walls on the boundary). Screen on the a0 wall; rows rise towards a1. */
    static Auditorium auditorium(Blueprint bp, int index, boolean motion, int a0, int a1, int b0, int b1) {
        // walls
        for (int y = 1; y <= HEIGHT; y++) {
            for (int v = b0; v <= b1; v++) { bp.set(a0, y, v, SCREEN_WALL); bp.set(a1, y, v, ACOUSTIC); }
            for (int u = a0; u <= a1; u++) { bp.set(u, y, b0, ACOUSTIC); bp.set(u, y, b1, ACOUSTIC); }
        }
        bp.box(a0 + 1, HEIGHT + 1, b0 + 1, a1 - 1, HEIGHT + 1, b1 - 1, CEIL);
        bp.box(a0 + 1, 1, b0 + 1, a1 - 1, HEIGHT, b1 - 1, AIR);
        bp.box(a0 + 1, 0, b0 + 1, a1 - 1, 0, b1 - 1, FLOOR);
        // door from the corridor beside the screen, at the low (front) end
        bp.set(a0, 1, b0 + 1, AIR); bp.set(a0, 2, b0 + 1, AIR);
        double[] door = {a0 - 1, 1, b0 + 1};
        // screen: the width of the room minus the aisles, cinema-scope 2.2:1 or as tall as fits
        double inner = b1 - b0 - 1;
        double sw = Math.max(5, inner - 3);
        double sh = Math.min(HEIGHT - 4.0, sw / 2.2);
        double sv = (b0 + b1) / 2.0, sy = 3.0 + sh / 2;              // relative to the floor block (y = 0)
        // stadium rows: each row is 2 deep (legroom + seat) and 1 higher than the one in front
        List<int[]> seats = new ArrayList<>();
        int first = a0 + 6;
        int rows = Math.max(1, Math.min(HEIGHT - 4, (a1 - 1 - first) / 2 + 1));
        String seat = motion ? "earthlife:motion_seat@u-" : "earthlife:recliner@u-";
        for (int r = 0; r < rows; r++) {
            int us = first + 2 * r;                     // seat u
            for (int u = us - 1; u <= Math.min(a1 - 1, us + (r == rows - 1 ? 8 : 0)); u++)
                for (int v = b0 + 1; v <= b1 - 1; v++) {
                    for (int y = 1; y <= r; y++) bp.set(u, y, v, FLOOR);
                    bp.set(u, r + 1, v, CARPET);
                }
            for (int v = b0 + 3; v <= b1 - 3; v++) {
                if (motion && (v - b0) % 3 == 0) continue;          // motion seats in pairs with room for effects
                bp.set(us, r + 1, v, seat);
                seats.add(new int[]{us, r + 1, v});
            }
        }
        // ceiling lights and wall sconces
        for (int u = a0 + 4; u < a1; u += 6) for (int v = b0 + 3; v < b1; v += 6) bp.set(u, HEIGHT + 1, v, LIGHT);
        // projection window in the back wall
        bp.set(a1, HEIGHT - 2, (b0 + b1) / 2, GLASS);
        bp.box(a0 + 1, 1, b0 + 1, first - 2, 1, b1 - 1, CARPET);     // front of the room, below the screen
        // blocks are centred on integer local coordinates: the wall face is half a block in front of a0
        return new Auditorium(index, (motion ? "4D " : "") + "Auditorium " + (index + 1), motion, a0 + 0.53, sv, sy, sw, sh, door, seats);
    }
}
