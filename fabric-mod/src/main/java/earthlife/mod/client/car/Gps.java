package earthlife.mod.client.car;

import com.mojang.blaze3d.platform.NativeImage;
import earthlife.core.transport.RoadGraph;
import earthlife.core.world.Features;
import earthlife.mod.EarthLifeMod;
import earthlife.mod.client.ClientState;
import earthlife.mod.client.map.ClientMapData;
import earthlife.mod.client.map.MapRaster;
import earthlife.mod.client.screen.LifeMapScreen;
import earthlife.mod.vehicle.VehicleEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

import java.util.List;
import java.util.Locale;

/**
 * In-car satellite navigation. While you sit in a car:
 * <ul><li>the dashboard screen ({@link CarRenderer}) shows a heading-up moving map with your route, the car arrow,
 * the next turn and your speed;</li>
 * <li>a HUD banner gives turn-by-turn directions ("Turn right onto Ridge Rd in 300 m"), the street you're on,
 * remaining distance and time, with a chime before each turn;</li>
 * <li>leaving the route for a few seconds recalculates it; reaching the destination ends navigation.</li></ul>
 * Routes come from the LifeMap [M] (double-click or "Route here"). Navigation never moves you - you drive.
 */
public final class Gps {
    static final int W = 192, H = 144;
    private static DynamicTexture texture;
    private static Identifier id;
    private static int offRouteTicks, tick;
    private static double lastAnnounce = -1;
    private static int lastAnnounceIdx = -1;
    /** Latest guidance, also shown by the HUD. */
    public static volatile Guidance guidance;

    public record Guidance(String arrow, String action, String onto, double toTurn, double remaining, double etaSec, String street, double speed) {}

    private Gps() {}

    public static Identifier textureId() { return id; }

    static VehicleEntity car() {
        var mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getVehicle() instanceof VehicleEntity v && VehicleEntity.clientModel(v.vehicleType()) ? v : null;
    }

    /** Called every client tick. */
    public static void tick(Minecraft mc) {
        VehicleEntity car = car();
        if (car == null) { guidance = null; offRouteTicks = 0; return; }
        tick++;
        double x = car.getX(), z = car.getZ();
        double speed = Math.abs(car.speed());
        guidance = guide(x, z, Math.toRadians(car.getYRot()), speed, mc);
        if (tick % 3 == 0) redraw(car, x, z, speed);
    }

    // ------------------------------------------------------------------ turn-by-turn

    static Guidance guide(double x, double z, double yaw, double speed, Minecraft mc) {
        ClientMapData d = ClientMapData.get();
        String street = d == null ? "" : streetAt(d, x, z, 25);
        List<Features.Pt> route = ClientState.route;
        double[] target = ClientState.routeTarget;
        if (route.size() < 2 || target == null) return new Guidance("", "", "", 0, 0, 0, street, speed);
        // where are we on the route?
        int seg = 0; double best = Double.MAX_VALUE, along = 0, alongBest = 0;
        for (int i = 1; i < route.size(); i++) {
            var a = route.get(i - 1); var b = route.get(i);
            double dd = Features.distToSegment(x, z, a, b);
            if (dd < best) { best = dd; seg = i; alongBest = along + proj(x, z, a, b); }
            along += Math.hypot(b.x() - a.x(), b.z() - a.z());
        }
        double total = along, remaining = Math.max(0, total - alongBest);
        if (Math.hypot(target[0] - x, target[1] - z) < 20 || remaining < 15) {
            mc.player.sendOverlayMessage(Component.literal("You have arrived: " + ClientState.routeLabel));
            mc.player.playSound(SoundEvents.NOTE_BLOCK_CHIME.value(), 1f, 1.2f);
            ClientState.route = List.of(); ClientState.routeTarget = null;
            return new Guidance("", "", "", 0, 0, 0, street, speed);
        }
        if (best > 35) {
            if (++offRouteTicks == 50) {
                mc.player.sendOverlayMessage(Component.literal("Recalculating route..."));
                LifeMapScreen.startRoute(target[0], target[1], ClientState.routeLabel);
            }
        } else offRouteTicks = 0;
        // next manoeuvre: first vertex ahead whose turn is over 30 degrees (bearings averaged over ~15 m)
        double dist = distToVertex(route, seg, x, z);
        String arrow = "↑", action = "Continue", onto = "";
        int turnIdx = -1;
        for (int i = seg; i < route.size() - 1; i++) {
            double in = bearing(route, i, -15), out = bearing(route, i, 15);
            double delta = norm(out - in);
            if (Math.abs(delta) >= 30) {
                turnIdx = i;
                String side = delta > 0 ? "right" : "left";
                if (Math.abs(delta) < 60) { action = "Slight " + side; arrow = delta > 0 ? "↗" : "↖"; }
                else if (Math.abs(delta) < 140) { action = "Turn " + side; arrow = delta > 0 ? "→" : "←"; }
                else { action = "Make a U-turn"; arrow = "↶"; }
                if (d != null) {
                    var p = route.get(i); var q = route.get(Math.min(route.size() - 1, i + 1));
                    double mx = p.x() + (q.x() - p.x()) * Math.min(1, 12 / Math.max(1, Math.hypot(q.x() - p.x(), q.z() - p.z())));
                    double mz = p.z() + (q.z() - p.z()) * Math.min(1, 12 / Math.max(1, Math.hypot(q.x() - p.x(), q.z() - p.z())));
                    onto = streetAt(d, mx, mz, 15);
                }
                break;
            }
            dist += Math.hypot(route.get(i + 1).x() - route.get(i).x(), route.get(i + 1).z() - route.get(i).z());
        }
        if (turnIdx < 0) { action = "Continue to " + ClientState.routeLabel; dist = remaining; }
        // chime before the turn: at ~400 m and again at ~80 m
        if (turnIdx >= 0) {
            boolean announce = (turnIdx != lastAnnounceIdx && dist < 400) || (turnIdx == lastAnnounceIdx && lastAnnounce > 100 && dist < 80);
            if (announce) {
                lastAnnounceIdx = turnIdx; lastAnnounce = dist;
                mc.player.playSound(SoundEvents.NOTE_BLOCK_CHIME.value(), 0.8f, dist < 100 ? 1.5f : 1.1f);
            }
        }
        double eta = remaining / Math.max(8, Math.max(speed, 11));
        return new Guidance(arrow, action, onto, dist, remaining, eta, street, speed);
    }

    static double proj(double x, double z, Features.Pt a, Features.Pt b) {
        double dx = b.x() - a.x(), dz = b.z() - a.z(), l2 = dx * dx + dz * dz;
        double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - a.x()) * dx + (z - a.z()) * dz) / l2));
        return t * Math.sqrt(l2);
    }

    static double distToVertex(List<Features.Pt> r, int seg, double x, double z) {
        var b = r.get(Math.min(seg, r.size() - 1));
        return Math.hypot(b.x() - x, b.z() - z);
    }

    /** Compass bearing (degrees, 0 = north) along the route from vertex i, looking `metres` ahead (or behind if negative). */
    static double bearing(List<Features.Pt> r, int i, double metres) {
        var p = r.get(i);
        double acc = 0; int j = i;
        int dir = metres > 0 ? 1 : -1;
        Features.Pt q = p;
        while (acc < Math.abs(metres)) {
            int k = j + dir;
            if (k < 0 || k >= r.size()) break;
            acc += Math.hypot(r.get(k).x() - r.get(j).x(), r.get(k).z() - r.get(j).z());
            j = k; q = r.get(j);
        }
        double dx = dir > 0 ? q.x() - p.x() : p.x() - q.x(), dz = dir > 0 ? q.z() - p.z() : p.z() - q.z();
        return Math.toDegrees(Math.atan2(dx, -dz));
    }

    static double norm(double a) { a = ((a + 180) % 360 + 360) % 360 - 180; return a; }

    /** Name of the nearest named road within r metres. */
    static String streetAt(ClientMapData d, double x, double z, double r) {
        String name = ""; double bd = r;
        for (Object o : d.query(x - r, z - r, x + r, z + r)) {
            if (!(o instanceof ClientMapData.NamedRoad nr) || nr.name() == null || nr.name().isBlank() || !nr.road().cls().drivable()) continue;
            var line = nr.road().line();
            for (int i = 1; i < line.size(); i++) {
                double dd = Features.distToSegment(x, z, line.get(i - 1), line.get(i));
                if (dd < bd) { bd = dd; name = nr.name(); }
            }
        }
        return name;
    }

    // ------------------------------------------------------------------ dashboard picture

    static void redraw(VehicleEntity car, double x, double z, double speed) {
        if (texture == null) {
            texture = new DynamicTexture(() -> "EarthLife GPS", W, H, false);
            id = EarthLifeMod.id("gps_screen");
            Minecraft.getInstance().getTextureManager().register(id, texture);
        }
        NativeImage img = texture.getPixels();
        if (img == null) return;
        ClientMapData d = ClientMapData.get();
        int zoom = speed > 16 ? 3 : 2;
        double bpp = MapRaster.BPP[zoom];
        double yaw = Math.toRadians(car.getYRot());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        int cx = W / 2, cy = (int) (H * 0.62);
        var layers = new MapRaster.Layers();
        int tileSpan = (int) (MapRaster.TILE * bpp);
        for (int py = 0; py < H; py++) for (int px = 0; px < W; px++) {
            double sx = (px - cx) * bpp, sy = (cy - py) * bpp;
            double wx = x + sx * rx + sy * fx, wz = z + sx * rz + sy * fz;
            int c = 0xFF20262C;
            if (d != null) {
                int tx = Math.floorDiv((int) Math.floor(wx), tileSpan), tz = Math.floorDiv((int) Math.floor(wz), tileSpan);
                int[] tile = MapRaster.pixels(d, zoom, tx, tz, layers);
                if (tile != null) {
                    int ix = (int) ((wx - tx * tileSpan) / bpp), iz = (int) ((wz - tz * tileSpan) / bpp);
                    c = tile[Math.min(MapRaster.TILE - 1, Math.max(0, iz)) * MapRaster.TILE + Math.min(MapRaster.TILE - 1, Math.max(0, ix))];
                    c = dim(c, 0.78);                                  // night-friendly screen
                }
            }
            img.setPixel(px, py, c);
        }
        // route
        List<Features.Pt> route = ClientState.route;
        for (int i = 1; i < route.size(); i++) {
            var a = route.get(i - 1); var b = route.get(i);
            double[] p = screen(a.x() - x, a.z() - z, rx, rz, fx, fz, bpp, cx, cy), q = screen(b.x() - x, b.z() - z, rx, rz, fx, fz, bpp, cx, cy);
            line(img, p[0], p[1], q[0], q[1], 0xFF2E8BFF, 2.4);
        }
        // car arrow
        for (int py = -9; py <= 7; py++) for (int px = -7; px <= 7; px++) {
            boolean inside = py <= 6 && Math.abs(px) <= (py + 9) * 0.45 && !(py > 2 && Math.abs(px) < (py - 2) * 1.2);
            boolean edge = py <= 7 && Math.abs(px) <= (py + 10) * 0.5 && !(py > 1 && Math.abs(px) < (py - 1) * 1.2);
            if (edge && !inside) set(img, cx + px, cy + py, 0xFFFFFFFF);
            if (inside) set(img, cx + px, cy + py, 0xFF1565C0);
        }
        // top bar: next turn + distance; bottom-left: speed
        Guidance gd = guidance;
        for (int py = 0; py < 22; py++) for (int px = 0; px < W; px++) img.setPixel(px, py, 0xF0142A44);
        for (int py = H - 16; py < H; py++) for (int px = 0; px < 64; px++) img.setPixel(px, py, 0xE0101418);
        if (gd != null && !gd.action().isEmpty()) {
            drawArrow(img, 13, 11, gd.arrow());
            Tiny.text(img, 30, 4, distance(gd.toTurn()), 0xFFFFFFFF, 2);
        } else Tiny.text(img, 8, 4, "GPS", 0xFF9FC6FF, 2);
        Tiny.text(img, 5, H - 13, Math.round(speed * 2.237) + " MPH", 0xFFFFFFFF, 2);
        if (gd != null && gd.remaining() > 0) Tiny.text(img, W - 6 - Tiny.width(distance(gd.remaining()), 1), H - 10, distance(gd.remaining()), 0xFFE0E0E0, 1);
        texture.upload();
    }

    static String distance(double m) {
        if (m >= 1609 * 0.25) return String.format(Locale.ROOT, "%.1f MI", m / 1609.34);
        return Math.round(m * 3.281 / 10) * 10 + " FT";
    }

    static double[] screen(double dx, double dz, double rx, double rz, double fx, double fz, double bpp, int cx, int cy) {
        double sx = dx * rx + dz * rz, sy = dx * fx + dz * fz;
        return new double[]{cx + sx / bpp, cy - sy / bpp};
    }

    static int dim(int c, double f) {
        int r = (int) (((c >> 16) & 255) * f), g = (int) (((c >> 8) & 255) * f), b = (int) ((c & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    static void set(NativeImage img, int x, int y, int c) { if (x >= 0 && y >= 0 && x < W && y < H) img.setPixel(x, y, c); }

    static void line(NativeImage img, double x0, double y0, double x1, double y1, int c, double w) {
        double len = Math.hypot(x1 - x0, y1 - y0);
        if (len > 4000) return;
        int n = (int) Math.ceil(len) + 1;
        for (int i = 0; i <= n; i++) {
            double t = i / (double) n, x = x0 + (x1 - x0) * t, y = y0 + (y1 - y0) * t;
            for (int oy = (int) -w; oy <= w; oy++) for (int ox = (int) -w; ox <= w; ox++)
                if (ox * ox + oy * oy <= w * w) set(img, (int) Math.round(x) + ox, (int) Math.round(y) + oy, c);
        }
    }

    static void drawArrow(NativeImage img, int cx, int cy, String arrow) {
        double ang = switch (arrow) { case "→" -> 90; case "←" -> -90; case "↗" -> 45; case "↖" -> -45; case "↶" -> 180; default -> 0; };
        double a = Math.toRadians(ang), s = Math.sin(a), c = Math.cos(a);
        for (int py = -9; py <= 9; py++) for (int px = -9; px <= 9; px++) {
            double ux = px * c + py * s, uy = -px * s + py * c;          // un-rotate into an "up" arrow
            boolean shaft = Math.abs(ux) <= 1.6 && uy >= -1 && uy <= 8;
            boolean head = uy >= -8 && uy < -1 && Math.abs(ux) <= (uy + 8) * 0.9;
            if (shaft || head) set(img, cx + px, cy + py, 0xFFFFFFFF);
        }
    }

    // ------------------------------------------------------------------ HUD banner

    public static void drawHud(GuiGraphicsExtractor g, Minecraft mc) {
        Guidance gd = guidance;
        if (gd == null || car() == null) return;
        int w = Math.min(320, mc.getWindow().getGuiScaledWidth() - 20), x0 = (mc.getWindow().getGuiScaledWidth() - w) / 2, y0 = 4;
        var font = mc.font;
        if (!gd.action().isEmpty()) {
            g.fill(x0, y0, x0 + w, y0 + 30, 0xD0143050);
            g.text(font, gd.arrow(), x0 + 8, y0 + 6, 0xFFFFFFFF, false);
            String l1 = gd.action() + (gd.onto().isEmpty() ? "" : " onto " + gd.onto());
            g.text(font, "§l" + distance(gd.toTurn()).toLowerCase(Locale.ROOT).replace("mi", "mi").replace("ft", "ft") + "§r  " + l1, x0 + 22, y0 + 6, 0xFFFFFFFF, false);
            String l2 = (gd.street().isEmpty() ? "" : gd.street() + " · ") + String.format(Locale.ROOT, "%d mph · %s to go · %d min", Math.round(gd.speed() * 2.237),
                    distance(gd.remaining()).toLowerCase(Locale.ROOT), Math.max(1, Math.round(gd.etaSec() / 60)));
            g.text(font, l2, x0 + 22, y0 + 18, 0xFFB8D4F0, false);
        } else {
            String l = (gd.street().isEmpty() ? "" : gd.street() + " · ") + Math.round(gd.speed() * 2.237) + " mph · set a destination on the map [M] for directions";
            int lw = Math.min(w, font.width(l) + 12);
            g.fill((mc.getWindow().getGuiScaledWidth() - lw) / 2, y0, (mc.getWindow().getGuiScaledWidth() + lw) / 2, y0 + 14, 0xB0101418);
            g.centeredText(font, l, mc.getWindow().getGuiScaledWidth() / 2, y0 + 3, 0xFFE0E0E0);
        }
    }

    /** A 3x5 pixel font for the dashboard screen (digits, capitals, a few symbols). */
    static final class Tiny {
        private static final String CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ .:-/";
        private static final String[] GLYPHS = {
                "111101101101111", "010110010010111", "111001111100111", "111001111001111", "101101111001001", "111100111001111", "111100111101111",
                "111001010010010", "111101111101111", "111101111001111", "010101111101101", "110101110101110", "111100100100111", "110101101101110",
                "111100110100111", "111100110100100", "111100101101111", "101101111101101", "111010010010111", "001001001101111", "101101110101101",
                "100100100100111", "101111111101101", "111101101101101", "111101101101111", "111101111100100", "111101101111001", "110101110101101",
                "111100111001111", "111010010010010", "101101101101111", "101101101101010", "101101111111101", "101101010101101", "101101111010010",
                "111001010100111", "000000000000000", "000000000000010", "000010000010000", "000000111000000", "001001010100100"};

        static int width(String s, int scale) { return s.length() * 4 * scale; }

        static void text(NativeImage img, int x, int y, String s, int color, int scale) {
            for (char ch : s.toUpperCase(Locale.ROOT).toCharArray()) {
                int k = CHARS.indexOf(ch);
                if (k >= 0) {
                    String g = GLYPHS[k];
                    for (int r = 0; r < 5; r++) for (int c = 0; c < 3; c++) if (g.charAt(r * 3 + c) == '1')
                        for (int a = 0; a < scale; a++) for (int b = 0; b < scale; b++) set(img, x + c * scale + a, y + r * scale + b, color);
                }
                x += 4 * scale;
            }
        }
    }
}
