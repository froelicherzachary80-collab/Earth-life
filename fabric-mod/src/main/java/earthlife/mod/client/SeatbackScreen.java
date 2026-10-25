package earthlife.mod.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Seatback in-flight entertainment: moving map plus flight data. Everything shown comes from the latest
 * server telemetry packet (sent twice a second from the live FlightSim) - the aircraft symbol moves
 * because the aircraft moved, not because an animation is playing.
 */
public final class SeatbackScreen extends Screen {
    public SeatbackScreen() { super(Component.literal("Seatback")); }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        var t = EarthLifeClient.telemetry;
        int w = Math.min(width - 20, 420), h = Math.min(height - 20, 260), x0 = (width - w) / 2, y0 = (height - h) / 2;
        g.fill(x0 - 4, y0 - 4, x0 + w + 4, y0 + h + 4, 0xFF111111);       // screen bezel
        g.fill(x0, y0, x0 + w, y0 + h, 0xFF0B2A4A);                       // "ocean" map background
        if (t == null || System.currentTimeMillis() - EarthLifeClient.telemetryAtMs > 5000) {
            g.drawCenteredString(font, "No flight data — board an aircraft to use the seatback screen.", width / 2, height / 2, 0xFFFFFFFF);
            return;
        }
        int mapW = w * 2 / 3, mapH = h - 16;
        // Fit the route's bounding box into the map area (equirectangular, longitude scaled by cos(lat)).
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (int i = 0; i < t.routeLat().length; i++) {
            minLat = Math.min(minLat, t.routeLat()[i]); maxLat = Math.max(maxLat, t.routeLat()[i]);
            minLon = Math.min(minLon, t.routeLon()[i]); maxLon = Math.max(maxLon, t.routeLon()[i]);
        }
        double k = Math.cos(Math.toRadians((minLat + maxLat) / 2));
        double spanX = Math.max(1e-4, (maxLon - minLon) * k), spanY = Math.max(1e-4, maxLat - minLat);
        double scale = Math.min((mapW - 30) / spanX, (mapH - 30) / spanY);
        double cx = (minLon + maxLon) / 2, cy = (minLat + maxLat) / 2;
        int ox = x0 + 8 + mapW / 2, oy = y0 + 8 + mapH / 2;
        java.util.function.BiFunction<Double, Double, int[]> px = (lat, lon) ->
                new int[]{ox + (int) ((lon - cx) * k * scale), oy - (int) ((lat - cy) * scale)};

        g.fill(x0 + 8, y0 + 8, x0 + 8 + mapW, y0 + 8 + mapH, 0xFF2E5E2E);  // land tint
        int[] prev = null;
        for (int i = 0; i < t.routeLat().length; i++) {
            int[] p = px.apply(t.routeLat()[i], t.routeLon()[i]);
            if (prev != null) line(g, prev[0], prev[1], p[0], p[1], 0xFFFFFFFF);
            prev = p;
        }
        int[] a = px.apply(t.routeLat()[0], t.routeLon()[0]);
        int[] b = px.apply(t.routeLat()[t.routeLat().length - 1], t.routeLon()[t.routeLon().length - 1]);
        g.fill(a[0] - 3, a[1] - 3, a[0] + 3, a[1] + 3, 0xFF4FC3F7); g.drawString(font, t.from(), a[0] + 5, a[1] - 4, 0xFFFFFFFF);
        g.fill(b[0] - 3, b[1] - 3, b[0] + 3, b[1] + 3, 0xFFFF7043); g.drawString(font, t.to(), b[0] + 5, b[1] - 4, 0xFFFFFFFF);
        int[] me = px.apply(t.lat(), t.lon());
        double hr = Math.toRadians(t.heading());
        line(g, me[0], me[1], me[0] + (int) (Math.sin(hr) * 9), me[1] - (int) (Math.cos(hr) * 9), 0xFFFFEB3B);
        g.fill(me[0] - 3, me[1] - 3, me[0] + 3, me[1] + 3, 0xFFFFEB3B);

        int tx = x0 + mapW + 18, ty = y0 + 12;
        long eta = Math.round(t.etaSeconds());
        String[] rows = {
                t.flight() + "  " + t.from() + " → " + t.to(),
                "Phase: " + t.phase().replace('_', ' '),
                String.format("Altitude: %,d ft", Math.round(t.altitudeM() * 3.28084)),
                String.format("Ground speed: %d kt", Math.round(t.groundSpeed() * 1.94384)),
                String.format("Heading: %03d°", Math.round(t.heading()) % 360),
                String.format("Remaining: %.1f km", t.remainingM() / 1000),
                String.format("Arrival in: %d:%02d", eta / 60, eta % 60),
                String.format("Position: %.4f, %.4f", t.lat(), t.lon())};
        for (String r : rows) { g.drawString(font, r, tx, ty, 0xFFE0F7FA); ty += 14; }
    }

    private static void line(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        int n = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        for (int i = 0; i <= n; i++) {
            int x = x0 + (n == 0 ? 0 : (x1 - x0) * i / n), y = y0 + (n == 0 ? 0 : (y1 - y0) * i / n);
            g.fill(x, y, x + 1, y + 1, color);
        }
    }
}
