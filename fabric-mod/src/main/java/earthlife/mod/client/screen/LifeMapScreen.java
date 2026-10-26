package earthlife.mod.client.screen;

import earthlife.core.geo.GeoPoint;
import earthlife.core.transport.RoadGraph;
import earthlife.core.world.Features;
import earthlife.mod.client.ClientState;
import earthlife.mod.client.EarthLifeClient;
import earthlife.mod.client.map.ClientMapData;
import earthlife.mod.client.map.MapRaster;
import earthlife.mod.net.Payloads;
import earthlife.mod.npc.NpcEntity;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * LifeMap (M): a full-screen, pannable, zoomable map of the real place you're standing in, drawn from the same map data
 * the world was generated from. Shows you (arrow), your coordinates and latitude/longitude, towns, streets, buildings,
 * airports, landmarks, residents nearby, events, your waypoints/home/work, players who chose to share their location,
 * and a road route to any destination. Navigation never teleports you - follow the line.
 */
public final class LifeMapScreen extends Screen {
    static double centerX, centerZ;
    static int zoom = 2;
    static boolean follow = true, initialized;
    static final MapRaster.Layers layers = new MapRaster.Layers();
    static boolean showPois = true, showNpcs = true, showPlayers = true, showWaypoints = true, showEvents = true;

    private EditBox search;
    private List<Payloads.Poi> results = List.of();
    private double[] selected;            // clicked map point {x, z}
    private String selectedName;
    private boolean layersOpen;
    private String notice;
    private int toolbarH = 24, searchW = 120;

    public LifeMapScreen() { super(Component.literal("LifeMap")); }

    /** Opens the map with a route to (x, z). */
    public static LifeMapScreen withRoute(double x, double z, String label) {
        LifeMapScreen s = new LifeMapScreen();
        startRoute(x, z, label);
        centerX = x; centerZ = z; follow = false;
        return s;
    }

    @Override public boolean isPauseScreen() { return false; }

    static boolean configApplied;

    @Override protected void init() {
        if (!configApplied) { layers.satellite = earthlife.mod.client.ClientConfig.satelliteMap; configApplied = true; }
        var p = minecraft.player;
        if (p != null && (follow || !initialized)) { centerX = p.getX(); centerZ = p.getZ(); initialized = true; }
        int bx = width - 6;
        bx = tool(bx, 20, "×", b -> onClose());
        bx = tool(bx, 20, "−", b -> zoomBy(1, width / 2.0, height / 2.0));
        bx = tool(bx, 20, "+", b -> zoomBy(-1, width / 2.0, height / 2.0));
        bx = tool(bx, 54, follow ? "● Follow" : "◎ Me", b -> { follow = true; rebuildWidgets(); });
        bx = tool(bx, 52, "Layers", b -> { layersOpen = !layersOpen; rebuildWidgets(); });
        bx = tool(bx, 66, "Waypoints", b -> minecraft.gui.setScreen(new EarthMenuScreen("life")));
        if (!ClientState.route.isEmpty()) bx = tool(bx, 70, "Clear route", b -> { ClientState.route = List.of(); ClientState.routeTarget = null; rebuildWidgets(); });
        searchW = Math.max(60, Math.min(200, bx - 10));
        search = new EditBox(font, 6, 3, searchW, 18, Component.literal("Search"));
        search.setHint(Component.literal("Search towns, streets, places…"));
        search.setResponder(this::runSearch);
        addRenderableWidget(search);


        int py = toolbarH + 4;
        if (layersOpen) {
            py = toggle(py, "Terrain", layers.terrain, v -> layers.terrain = v);
            py = toggle(py, "Water", layers.water, v -> layers.water = v);
            py = toggle(py, "Land cover", layers.landcover, v -> layers.landcover = v);
            py = toggle(py, "Buildings", layers.buildings, v -> layers.buildings = v);
            py = toggle(py, "Roads", layers.roads, v -> layers.roads = v);
            py = toggle(py, "Railways", layers.railways, v -> layers.railways = v);
            py = toggle(py, "Runways & taxiways", layers.aeroways, v -> layers.aeroways = v);
            py = toggle(py, "Satellite-style colours", layers.satellite, v -> layers.satellite = v);
            py = toggle(py, "Places", showPois, v -> showPois = v);
            py = toggle(py, "Residents", showNpcs, v -> showNpcs = v);
            py = toggle(py, "Events", showEvents, v -> showEvents = v);
            py = toggle(py, "Waypoints", showWaypoints, v -> showWaypoints = v);
            py = toggle(py, "Shared players", showPlayers, v -> showPlayers = v);
            var life = ClientState.life;
            boolean sharing = life != null && life.sharingLocation();
            addRenderableWidget(Button.builder(Component.literal(sharing ? "Sharing my location: ON" : "Sharing my location: OFF"),
                    b -> { EarthLifeClient.command("earthlife map share " + (sharing ? "off" : "on")); }).bounds(width - 156, py, 150, 18).build());
            py += 22;
        }
        int ry = toolbarH + 2;
        for (int i = 0; i < Math.min(8, results.size()); i++) {
            Payloads.Poi r = results.get(i);
            String kindLabel = r.kind().equals("far") ? "outside this world's data" : r.kind().replace("town:", "").replace("place:", "");
            addRenderableWidget(Button.builder(Component.literal(trim(r.name(), 30) + " · " + kindLabel), b -> {
                results = List.of(); search.setValue("");
                if (r.kind().equals("far")) {
                    notice = r.name() + String.format(" is %.0f km away, outside this world's map region: no roads or buildings are generated there yet.",
                            Math.hypot(r.x() - minecraft.player.getX(), r.z() - minecraft.player.getZ()) / 1000);
                } else { centerX = r.x(); centerZ = r.z(); follow = false; selected = new double[]{r.x(), r.z()}; selectedName = r.name(); notice = null; }
                rebuildWidgets();
            }).bounds(6, ry, Math.max(searchW, 160), 16).build());
            ry += 17;
        }
        if (selected != null) {
            int sx = 6, sy = height - 46;
            addRenderableWidget(Button.builder(Component.literal("Route here"), b -> { startRoute(selected[0], selected[1], selectedName == null ? "map point" : selectedName); rebuildWidgets(); })
                    .bounds(sx, sy, 80, 18).build());
            addRenderableWidget(Button.builder(Component.literal("Add waypoint"), b -> {
                String name = selectedName != null ? selectedName.replaceAll("[^A-Za-z0-9_-]", "_") : "Pin" + (1 + (ClientState.life == null ? 0 : ClientState.life.waypoints().size()));
                EarthLifeClient.command(String.format(Locale.ROOT, "earthlife waypoint set %s %.1f %.1f", trim(name, 24), selected[0], selected[1]));
            }).bounds(sx + 84, sy, 84, 18).build());
            addRenderableWidget(Button.builder(Component.literal("Set as home"), b -> EarthLifeClient.command(String.format(Locale.ROOT, "earthlife home setat %.1f %.1f", selected[0], selected[1])))
                    .bounds(sx + 172, sy, 80, 18).build());
            addRenderableWidget(Button.builder(Component.literal("×"), b -> { selected = null; selectedName = null; rebuildWidgets(); }).bounds(sx + 256, sy, 18, 18).build());
        }
        EarthLifeClient.command("earthlife ui opened map");
        EarthLifeClient.command("earthlife ui events");
    }

    private int tool(int right, int w, String label, Button.OnPress press) {
        addRenderableWidget(Button.builder(Component.literal(label), press).bounds(right - w, 3, w, 18).build());
        return right - w - 3;
    }

    private int toggle(int y, String name, boolean on, java.util.function.Consumer<Boolean> set) {
        addRenderableWidget(Button.builder(Component.literal((on ? "☑ " : "☐ ") + name), b -> { set.accept(!on); rebuildWidgets(); })
                .bounds(width - 156, y, 150, 16).build());
        return y + 17;
    }

    static String trim(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "…"; }

    private List<Payloads.Poi> extraPois() {
        List<Payloads.Poi> l = new ArrayList<>();
        if (ClientState.world != null) l.addAll(ClientState.world.pois());
        if (ClientState.life != null) l.addAll(ClientState.life.waypoints());
        return l;
    }

    private void runSearch(String q) {
        ClientMapData d = ClientMapData.get();
        if (d == null) {
            String s = q.toLowerCase(Locale.ROOT);
            results = s.length() < 2 ? List.of() : extraPois().stream().filter(p -> p.name().toLowerCase(Locale.ROOT).contains(s)).limit(8).toList();
        } else results = d.search(q, extraPois());
        String keep = search.getValue();
        rebuildWidgets();
        search.setValue(keep);
        setFocused(search);
        search.setFocused(true);
    }

    public static void startRoute(double tx, double tz, String label) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return;
        double fx = mc.player.getX(), fz = mc.player.getZ();
        ClientState.routeTarget = new double[]{tx, tz};
        ClientState.routeLabel = label;
        ClientState.route = List.of(new Features.Pt(fx, fz), new Features.Pt(tx, tz));   // straight line until the road route is ready
        ClientMapData d = ClientMapData.get();
        if (d == null || d.roads.isEmpty()) return;
        CompletableFuture.supplyAsync(() -> {
            RoadGraph g = d.graph();
            List<Features.Pt> path = new ArrayList<>(g.route(fx, fz, tx, tz));
            if (path.isEmpty()) return List.<Features.Pt>of(new Features.Pt(fx, fz), new Features.Pt(tx, tz));
            path.add(0, new Features.Pt(fx, fz));
            path.add(new Features.Pt(tx, tz));
            return path;
        }).thenAccept(p -> mc.execute(() -> { if (ClientState.routeTarget != null && ClientState.routeTarget[0] == tx && ClientState.routeTarget[1] == tz) ClientState.route = p; }));
    }

    double bpp() { return MapRaster.BPP[zoom]; }
    double sx(double wx) { return (wx - centerX) / bpp() + width / 2.0; }
    double sy(double wz) { return (wz - centerZ) / bpp() + height / 2.0; }
    double wx(double sx) { return centerX + (sx - width / 2.0) * bpp(); }
    double wz(double sy) { return centerZ + (sy - height / 2.0) * bpp(); }

    void zoomBy(int dz, double mx, double my) {
        int nz = Math.max(0, Math.min(MapRaster.BPP.length - 1, zoom + dz));
        if (nz == zoom) return;
        double ax = wx(mx), az = wz(my);
        zoom = nz;
        if (!follow) { centerX = ax - (mx - width / 2.0) * bpp(); centerZ = az - (my - height / 2.0) * bpp(); }
    }

    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        zoomBy(sy > 0 ? -1 : 1, mx, my);
        return true;
    }

    @Override public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (super.mouseDragged(e, dx, dy)) return true;
        if (e.y() < toolbarH) return false;
        follow = false;
        centerX -= dx * bpp(); centerZ -= dy * bpp();
        return true;
    }

    @Override public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        if (super.mouseClicked(e, dbl)) return true;
        if (e.y() < toolbarH) return false;
        double x = wx(e.x()), z = wz(e.y());
        if (e.button() == 2) { follow = true; rebuildWidgets(); return true; }                 // middle click: centre on me
        if (e.button() == 1) {                                                                // right click: drop a waypoint
            String name = "Pin" + (1 + (ClientState.life == null ? 0 : ClientState.life.waypoints().size()));
            EarthLifeClient.command(String.format(Locale.ROOT, "earthlife waypoint set %s %.1f %.1f", name, x, z));
            selected = new double[]{x, z}; selectedName = name; notice = "Waypoint " + name + " added.";
            rebuildWidgets();
            return true;
        }
        selected = new double[]{x, z};
        selectedName = nearestLabel(x, z, 12 * bpp());
        if (dbl) { startRoute(x, z, selectedName == null ? "map point" : selectedName); }
        rebuildWidgets();
        return true;
    }

    String nearestLabel(double x, double z, double r) {
        String best = null; double bd = r;
        for (Payloads.Poi p : extraPois()) { double d = Math.hypot(p.x() - x, p.z() - z); if (d < bd) { bd = d; best = p.name(); } }
        return best;
    }

    @Override public boolean keyPressed(KeyEvent e) {
        if (!search.isFocused() && EarthLifeClient.MAP != null && EarthLifeClient.MAP.matches(e)) { onClose(); return true; }
        return super.keyPressed(e);
    }

    @Override public void tick() {
        var p = minecraft.player;
        if (follow && p != null) { centerX = p.getX(); centerZ = p.getZ(); }
    }

    @Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float d) {
        g.fill(0, 0, width, height, 0xFF1B2A38);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        var p = minecraft.player;
        if (follow && p != null) { centerX = p.getX(); centerZ = p.getZ(); }
        ClientMapData data = ClientMapData.get();
        if (data != null) drawTiles(g, data);
        else g.centeredText(font, ClientState.earth() ? (ClientMapData.loading() ? "Loading map data…" : "Map data unavailable") : "The LifeMap works in Earth worlds.", width / 2, height / 2, 0xFFFFFFFF);
        if (data != null) drawOverlays(g, data);
        // toolbar + info panel
        g.fill(0, 0, width, toolbarH, 0xC0101820);
        super.extractRenderState(g, mx, my, delta);
        drawInfo(g, data, mx, my);
    }

    private void drawTiles(GuiGraphicsExtractor g, ClientMapData data) {
        double tileWorld = MapRaster.TILE * bpp();
        int tx0 = (int) Math.floor(wx(0) / tileWorld), tx1 = (int) Math.floor(wx(width) / tileWorld);
        int tz0 = (int) Math.floor(wz(0) / tileWorld), tz1 = (int) Math.floor(wz(height) / tileWorld);
        int[] budget = {3};
        for (int tz = tz0; tz <= tz1; tz++) for (int tx = tx0; tx <= tx1; tx++) {
            int sx = (int) Math.floor(sx(tx * tileWorld)), sy = (int) Math.floor(sy(tz * tileWorld));
            Identifier tex = MapRaster.tile(data, zoom, tx, tz, layers, budget);
            if (tex != null) g.blit(RenderPipelines.GUI_TEXTURED, tex, sx, sy, 0f, 0f, MapRaster.TILE, MapRaster.TILE, MapRaster.TILE, MapRaster.TILE);
            else g.fill(sx, sy, sx + MapRaster.TILE, sy + MapRaster.TILE, 0xFF2A3A48);
        }
    }

    private void dot(GuiGraphicsExtractor g, double wx, double wz, int r, int color) {
        int x = (int) sx(wx), y = (int) sy(wz);
        if (x < -10 || y < -10 || x > width + 10 || y > height + 10) return;
        g.fill(x - r - 1, y - r - 1, x + r + 1, y + r + 1, 0xFF000000);
        g.fill(x - r, y - r, x + r, y + r, color);
    }

    private void label(GuiGraphicsExtractor g, double wx, double wz, String text, int color) {
        int x = (int) sx(wx), y = (int) sy(wz);
        if (x < -100 || y < -10 || x > width + 100 || y > height + 10) return;
        int w = font.width(text);
        g.fill(x - w / 2 - 2, y - 13, x + w / 2 + 2, y - 3, 0x90000000);
        g.text(font, text, x - w / 2, y - 12, color, false);
    }

    private void polyline(GuiGraphicsExtractor g, List<Features.Pt> pts, int color, int thick) {
        for (int i = 1; i < pts.size(); i++) {
            double ax = sx(pts.get(i - 1).x()), ay = sy(pts.get(i - 1).z()), bx = sx(pts.get(i).x()), by = sy(pts.get(i).z());
            if (Math.max(ax, bx) < 0 || Math.min(ax, bx) > width || Math.max(ay, by) < 0 || Math.min(ay, by) > height) continue;
            int n = (int) Math.min(2000, Math.max(1, Math.hypot(bx - ax, by - ay) / 2));
            for (int s = 0; s <= n; s++) {
                int x = (int) (ax + (bx - ax) * s / n), y = (int) (ay + (by - ay) * s / n);
                g.fill(x - thick, y - thick, x + thick, y + thick, color);
            }
        }
    }

    private void drawOverlays(GuiGraphicsExtractor g, ClientMapData data) {
        if (!ClientState.route.isEmpty()) polyline(g, ClientState.route, 0xFF1E88E5, 2);
        if (showPois && zoom >= 3 && zoom <= 8) for (Payloads.Poi pl : data.places) label(g, pl.x(), pl.z(), pl.name(), 0xFFE0E0E0);
        if (showPois && ClientState.world != null) for (Payloads.Poi poi : ClientState.world.pois()) {
            boolean town = poi.kind().startsWith("town");
            if (town && poi.kind().endsWith("hamlet") && zoom > 4) continue;
            int c = town ? 0xFFFFFFFF : poi.kind().equals("airport") ? 0xFF90CAF9 : 0xFFFFCC80;
            if (!town) dot(g, poi.x(), poi.z(), 3, c);
            label(g, poi.x(), poi.z(), (poi.kind().equals("airport") ? "✈ " : poi.kind().equals("landmark") ? "★ " : "") + poi.name(), c);
        }
        if (showEvents) {
            var ev = ClientState.lists.get("events");
            if (ev != null) for (Payloads.Row r : ev.rows()) {
                if (!r.command().startsWith("earthlife map route geo ")) continue;
                String[] a = r.command().substring("earthlife map route geo ".length()).split(" ");
                try {
                    var b = data.projection.toBlock(Double.parseDouble(a[0]), Double.parseDouble(a[1]));
                    dot(g, b.x(), b.z(), 3, 0xFFE040FB);
                    label(g, b.x(), b.z(), "♪ " + trim(r.title(), 28), 0xFFF3C5FF);
                } catch (Exception ignored) {}
            }
        }
        var life = ClientState.life;
        if (showWaypoints && life != null) for (Payloads.Poi w : life.waypoints()) {
            int c = w.kind().equals("home") ? 0xFF66BB6A : w.kind().equals("work") ? 0xFFFFA726 : 0xFFEF5350;
            dot(g, w.x(), w.z(), 3, c);
            label(g, w.x(), w.z(), (w.kind().equals("home") ? "⌂ " : w.kind().equals("work") ? "⚒ " : "⚑ ") + w.name(), c);
        }
        var p = minecraft.player;
        if (showNpcs && p != null && minecraft.level != null && zoom <= 4) {
            for (NpcEntity n : minecraft.level.getEntitiesOfClass(NpcEntity.class, new AABB(p.getX() - 160, p.getY() - 64, p.getZ() - 160, p.getX() + 160, p.getY() + 64, p.getZ() + 160))) {
                dot(g, n.getX(), n.getZ(), 2, 0xFF4DD0E1);
                if (zoom <= 2 && n.getCustomName() != null) label(g, n.getX(), n.getZ(), n.getCustomName().getString(), 0xFFB2EBF2);
            }
        }
        if (showPlayers) for (Payloads.Poi o : ClientState.sharedPlayers) {
            if (p != null && o.name().equals(p.getName().getString())) continue;
            dot(g, o.x(), o.z(), 3, 0xFFFFEB3B);
            label(g, o.x(), o.z(), o.name(), 0xFFFFF59D);
        }
        if (selected != null) { dot(g, selected[0], selected[1], 2, 0xFFFFFFFF); if (selectedName != null) label(g, selected[0], selected[1], selectedName, 0xFFFFFFFF); }
        if (p != null) arrow(g, sx(p.getX()), sy(p.getZ()), p.getYRot());
    }

    /** Player arrow pointing where you face (Minecraft yaw 0 = south). */
    private void arrow(GuiGraphicsExtractor g, double x, double y, float yaw) {
        g.pose().pushMatrix();
        g.pose().translate((float) x, (float) y);
        g.pose().rotate((float) Math.toRadians(yaw + 180));
        for (int row = 0; row < 9; row++) {
            int half = row / 2;
            g.fill(-half - 1, -6 + row, half + 2, -5 + row, 0xFF000000);
        }
        for (int row = 1; row < 8; row++) {
            int half = (row - 1) / 2;
            g.fill(-half, -6 + row, half + 1, -5 + row, row < 6 ? 0xFFFF3D00 : 0xFFFFFFFF);
        }
        g.pose().popMatrix();
    }

    private void drawInfo(GuiGraphicsExtractor g, ClientMapData data, int mx, int my) {
        var p = minecraft.player;
        List<String> lines = new ArrayList<>();
        if (p != null && earthlife.mod.client.ClientConfig.showCoordinates) {
            lines.add(String.format("XYZ %.0f / %.0f / %.0f", p.getX(), p.getY(), p.getZ()));
            if (data != null) {
                GeoPoint gp = data.projection.fromBlock(p.getX(), p.getZ());
                lines.add(String.format("%.6f, %.6f", gp.lat(), gp.lon()));
                lines.add(String.format("Elevation %.0f m", data.projection.yToElevation(p.getY())));
            }
        }
        var life = ClientState.life;
        if (life != null) lines.add(0, life.place());
        if (data != null && p != null) {
            GeoPoint gp = data.projection.fromBlock(p.getX(), p.getZ());
            String county = county(gp.lat(), gp.lon());
            var w = ClientState.world;
            lines.add(1, (county.isEmpty() ? "" : county + ", ") + (w != null ? w.startState() + ", " + w.startCountry() : ""));
        }
        lines.add("Zoom: " + MapRaster.ZOOM_NAMES[zoom] + (notice != null ? "" : ""));
        if (!ClientState.route.isEmpty() && p != null) {
            double len = RoadGraph.length(ClientState.route);
            lines.add(String.format("Route to %s: %.1f km · walk %d min · drive %d min", trim(ClientState.routeLabel, 24), len / 1000, Math.round(len / 1.4 / 60), Math.round(len / 13.4 / 60)));
        }
        if (data != null && my > toolbarH) {
            GeoPoint c = data.projection.fromBlock(wx(mx), wz(my));
            lines.add(String.format("Cursor %.0f, %.0f · %.5f, %.5f", wx(mx), wz(my), c.lat(), c.lon()));
        }
        int y = height - 14 - lines.size() * 10 - (selected != null ? 24 : 0);
        int w = 0;
        for (String l : lines) w = Math.max(w, font.width(l));
        g.fill(width - w - 12, y - 3, width - 2, y + lines.size() * 10, 0xA0000000);
        for (String l : lines) { g.text(font, l, width - w - 7, y, 0xFFFFFFFF, false); y += 10; }
        // scale bar
        double metres = 100 * bpp();
        double nice = Math.pow(10, Math.floor(Math.log10(metres)));
        if (metres / nice >= 5) nice *= 5; else if (metres / nice >= 2) nice *= 2;
        int barPx = (int) (nice / bpp());
        int bx = 8, by = height - 12;
        g.fill(bx, by, bx + barPx, by + 2, 0xFFFFFFFF);
        g.text(font, nice >= 1000 ? String.format("%.0f km", nice / 1000) : String.format("%.0f m", nice), bx + barPx + 4, by - 4, 0xFFFFFFFF, true);
        if (notice != null) {
            int nw = font.width(notice);
            g.fill(width / 2 - nw / 2 - 4, toolbarH + 4, width / 2 + nw / 2 + 4, toolbarH + 16, 0xC0000000);
            g.centeredText(font, notice, width / 2, toolbarH + 6, 0xFFFFE082);
        }
        String attrib = "© OpenStreetMap contributors · terrain: USGS/NOAA via AWS Terrain Tiles · wheel zoom · drag pan · click select · right-click waypoint · middle-click centre · double-click route";
        g.text(font, attrib, bx + barPx + 50, by - 4, 0xFFBDBDBD, true);
    }

    /** County for the Western NY region (approximate borders, from latitude/longitude). */
    static String county(double lat, double lon) { return earthlife.core.geo.Counties.of(lat, lon); }

    @Override public void onClose() { super.onClose(); }
}
