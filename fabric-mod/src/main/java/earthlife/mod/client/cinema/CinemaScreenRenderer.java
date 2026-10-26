package earthlife.mod.client.cinema;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import earthlife.mod.EarthLifeMod;
import earthlife.mod.cinema.CinemaScreenEntity;
import earthlife.mod.client.zmf.ZmfState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws cinema screens and ZMF signage.
 * <ul>
 * <li>Screens: a black masking frame and the film picture, letterboxed or pillar-boxed to the film's own shape (nothing
 * is cropped), lit by itself. IMAX rooms get a curved screen; the ScreenX room also shows the picture's atmospheric
 * extension on both side walls; every playing screen throws a little of its light onto the floor and walls. With
 * nothing playing: the room's program, the pre-show countdown, or how to start a film.</li>
 * <li>Showtime boards, door signs, the facade marquee, wayfinding and restroom signs of ZMF multiplexes.</li>
 * </ul>
 */
public final class CinemaScreenRenderer extends EntityRenderer<CinemaScreenEntity, CinemaScreenRenderer.State> {
    public static final class State extends EntityRenderState {
        float w, h, yaw, aspect;
        Identifier picture, sideL, sideR;
        String line1 = "", line2 = "", kind = "screen";
        List<String> lines = new ArrayList<>();
        boolean curved, screenX;
        float side, depth, floor = -2, ceil = 8;
        int glow;
        float glowStrength;
        Identifier poster; float posterAspect;
    }

    static final Identifier FRAME = EarthLifeMod.id("textures/block/screen_frame.png");
    static final Identifier WHITE = EarthLifeMod.id("textures/misc/white.png");
    static final int FULL_BRIGHT = 0xF000F0;

    public CinemaScreenRenderer(EntityRendererProvider.Context ctx) { super(ctx); }

    @Override public State createRenderState() { return new State(); }

    @Override public boolean shouldRender(CinemaScreenEntity e, net.minecraft.client.renderer.culling.Frustum f, double cx, double cy, double cz, float partial) {
        return e.distanceToSqr(cx, cy, cz) < 96 * 96;
    }

    static float num(String s, float d) { try { return s.isEmpty() ? d : Float.parseFloat(s); } catch (NumberFormatException e) { return d; } }

    @Override public void extractRenderState(CinemaScreenEntity e, State s, float partial) {
        super.extractRenderState(e, s, partial);
        s.w = e.screenWidth(); s.h = e.screenHeight(); s.yaw = e.getYRot();
        String kind = e.dataValue("kind");
        s.kind = kind.isEmpty() ? "screen" : kind;
        s.lines = new ArrayList<>();
        s.picture = null; s.sideL = null; s.sideR = null; s.glowStrength = 0; s.poster = null;
        if (!s.kind.equals("screen")) { signage(e, s); return; }
        String fmt = e.dataValue("fmt");
        s.curved = "1".equals(e.dataValue("curved"));
        s.screenX = fmt.contains("ScreenX");
        s.side = num(e.dataValue("side"), s.w / 2 + 1); s.depth = num(e.dataValue("depth"), 12);
        s.floor = num(e.dataValue("floor"), -2); s.ceil = num(e.dataValue("ceil"), s.h + 2);
        var show = CinemaClient.show(e.key());
        if (show.isPresent() && show.get().source().startsWith("web:")) {
            s.picture = BrowserScreens.texture(e.key());
            s.aspect = 16f / 9f;
            if (s.picture == null) { s.line1 = show.get().title(); s.line2 = BrowserScreens.status(e.key()); }
            return;
        }
        VideoPlayer p = CinemaClient.player(e.key());
        if (p != null) p.updateTexture();                 // render thread: newest due frame -> texture
        s.picture = p == null ? null : p.texture();
        s.aspect = p == null ? 2.2f : p.aspect;
        if (p != null && s.picture != null) {
            s.glow = p.avgRgb;
            int r = s.glow >> 16 & 255, g = s.glow >> 8 & 255, b = s.glow & 255;
            s.glowStrength = (0.299f * r + 0.587f * g + 0.114f * b) / 255f;
            if (s.screenX) { s.sideL = p.sideTexture(true); s.sideR = p.sideTexture(false); }
        }
        if (s.picture == null) {
            var room = ZmfState.room(e.key());
            if (show.isEmpty()) {
                if (room != null) {
                    long now = ZmfState.serverNow(e.dataValue("mid"));
                    s.line1 = room.title() + (room.year() > 0 ? " (" + room.year() + ")" : "");
                    s.line2 = room.formats() + " · " + ZmfState.status(room, now);
                    var pe = earthlife.mod.client.zmf.Posters.get(room.source());
                    s.poster = earthlife.mod.client.zmf.Posters.id(pe); s.posterAspect = earthlife.mod.client.zmf.Posters.aspect(pe);
                } else { s.line1 = e.label(); s.line2 = e.dataValue("mid").isEmpty() ? "Buy a ticket at the kiosk in the lobby to start a film" : "Press B for showtimes"; }
            } else {
                boolean threeD = !"2D".equals(show.get().format());
                s.line1 = show.get().title() + (threeD ? " · 3D" : "");
                boolean zmf = !e.dataValue("box").isEmpty();
                s.line2 = show.get().paused() ? "Paused" : p == null ? (zmf ? "Step inside to watch" : "Come closer to watch") : p.status;
                if (threeD && !CinemaClient.wearingGlasses() && p != null && p.error == null) s.line2 += " · put on your 3D glasses";
            }
        }
    }

    /** Boards, door signs, marquee, wayfinding and restroom signs: lines of text from the multiplex program. */
    void signage(CinemaScreenEntity e, State s) {
        String mid = e.dataValue("mid");
        var sched = ZmfState.schedule(mid);
        long now = ZmfState.serverNow(mid);
        switch (s.kind) {
            case "board" -> {
                s.lines.add("§6§lNOW SHOWING§r  ·  " + e.label());
                if (sched == null) s.lines.add("Loading the program...");
                else for (var r : sched.rooms()) s.lines.add(String.format("%2d  %-10s %s  ·  %s", r.number(), r.formats(), trim(r.title(), 26), ZmfState.status(r, now)));
            }
            case "sign" -> {
                var r = ZmfState.room(mid, e.dataValue("aud"));
                s.lines.add("§l" + e.dataValue("room") + "§r  " + (r == null ? "" : r.formats() + (r.browser().isEmpty() ? "" : " · Web " + r.browser())));
                if (r != null) { s.lines.add(trim(r.title(), 28)); s.lines.add(ZmfState.status(r, now)); }
            }
            case "facade" -> { s.lines.add("§l" + e.dataValue("text").toUpperCase(java.util.Locale.ROOT)); s.lines.add("ZMF CINEMA  ·  " + (sched == null ? "" : sched.roomCount() + " SCREENS  ·  ") + "IMAX · 4DX · 3D · ScreenX"); }
            case "wayfinding" -> s.lines.add("◀ " + e.dataValue("text") + " · Screens ▶");
            case "restroom" -> s.lines.add(e.dataValue("text"));
            default -> s.lines.add(e.label());
        }
    }

    static String trim(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "…"; }

    @Override public void submit(State s, PoseStack ps, SubmitNodeCollector out, CameraRenderState camera) {
        ps.pushPose();
        ps.rotate(new Quaternionf().rotateY((float) Math.toRadians(-s.yaw)));
        float w = s.w, h = s.h;
        if (!s.kind.equals("screen")) { panel(s, ps, out); ps.popPose(); return; }
        // masking frame (slightly behind the picture)
        out.submitCustomGeometry(ps, RenderTypes.entityCutout(FRAME), (pose, vc) -> quad(pose, vc, -w / 2 - 0.35f, -0.2f, w / 2 + 0.35f, h + 0.25f, -0.02f, 0xFF0A0A0A));
        if (s.picture != null) {
            // fit the film into the screen without cropping: scope films fill the width, narrower films are pillar-boxed
            float pw = w, ph = w / s.aspect;
            if (ph > h) { ph = h; pw = h * s.aspect; }
            float x0 = -pw / 2, y0 = (h - ph) / 2;
            final float fpw = pw, fph = ph;
            Identifier pic = s.picture;
            if (s.curved) out.submitCustomGeometry(ps, RenderTypes.entityCutout(pic), (pose, vc) -> curved(pose, vc, x0, y0, fpw, fph, w * 1.25f));
            else out.submitCustomGeometry(ps, RenderTypes.entityCutout(pic), (pose, vc) -> quad(pose, vc, x0, y0, x0 + fpw, y0 + fph, 0, 0xFFFFFFFF));
            if (s.screenX && s.sideL != null && s.sideR != null) {
                float sd = s.side, dp = s.depth, f0 = s.floor + 0.3f, f1 = s.ceil - 0.3f;
                out.submitCustomGeometry(ps, RenderTypes.entityCutout(s.sideL), (pose, vc) -> {
                    v(pose, vc, -sd, f0, dp, 1, 1, 0xFFFFFFFF); v(pose, vc, -sd, f0, 0.2f, 0, 1, 0xFFFFFFFF); v(pose, vc, -sd, f1, 0.2f, 0, 0, 0xFFFFFFFF); v(pose, vc, -sd, f1, dp, 1, 0, 0xFFFFFFFF);
                });
                out.submitCustomGeometry(ps, RenderTypes.entityCutout(s.sideR), (pose, vc) -> {
                    v(pose, vc, sd, f0, 0.2f, 0, 1, 0xFFFFFFFF); v(pose, vc, sd, f0, dp, 1, 1, 0xFFFFFFFF); v(pose, vc, sd, f1, dp, 1, 0, 0xFFFFFFFF); v(pose, vc, sd, f1, 0.2f, 0, 0, 0xFFFFFFFF);
                });
            }
            if (s.glowStrength > 0.03f) spill(s, ps, out);
        } else {
            out.submitCustomGeometry(ps, RenderTypes.entityCutout(FRAME), (pose, vc) -> quad(pose, vc, -w / 2, 0, w / 2, h, 0, 0xFF1B1E24));
            if (s.poster != null) {
                float ph = h * 0.86f, pw = ph * s.posterAspect, px = -w / 2 + w * 0.05f, py = h * 0.07f;
                Identifier pt = s.poster;
                out.submitCustomGeometry(ps, RenderTypes.entityCutout(pt), (pose, vc) -> quad(pose, vc, px, py, px + pw, py + ph, 0.01f, 0xFFFFFFFF));
            }
            text(ps, out, s.line1, h * 0.62f, Math.min(0.06f, w / 260f), 0xFFFFFFFF);
            text(ps, out, s.line2, h * 0.42f, Math.min(0.035f, w / 420f), 0xFFB0BEC5);
        }
        ps.popPose();
    }

    /** The screen's light on the floor in front of it and on the side walls near it (soft, coloured by the picture). */
    void spill(State s, PoseStack ps, SubmitNodeCollector out) {
        int a = (int) (Math.min(1, s.glowStrength) * 70);
        int col = (a << 24) | (s.glow & 0xFFFFFF);
        float sd = Math.max(s.w / 2 + 0.5f, Math.min(s.side, s.w)), dp = Math.min(s.depth, 9), fy = s.floor + 0.04f;
        out.submitCustomGeometry(ps, RenderTypes.entityTranslucent(WHITE), (pose, vc) -> {
            // floor (both windings so it shows whichever way the pipeline culls)
            v(pose, vc, -sd, fy, 0.3f, 0, 0, col); v(pose, vc, -sd, fy, dp, 0, 1, col & 0x00FFFFFF); v(pose, vc, sd, fy, dp, 1, 1, col & 0x00FFFFFF); v(pose, vc, sd, fy, 0.3f, 1, 0, col);
            v(pose, vc, sd, fy, 0.3f, 1, 0, col); v(pose, vc, sd, fy, dp, 1, 1, col & 0x00FFFFFF); v(pose, vc, -sd, fy, dp, 0, 1, col & 0x00FFFFFF); v(pose, vc, -sd, fy, 0.3f, 0, 0, col);
        });
    }

    /** IMAX: the picture on a slightly curved screen (its edges come towards the audience). */
    static void curved(PoseStack.Pose pose, VertexConsumer vc, float x0, float y0, float pw, float ph, float radius) {
        int seg = 16;
        for (int i = 0; i < seg; i++) {
            float a = x0 + pw * i / seg, b = x0 + pw * (i + 1) / seg;
            float za = radius - (float) Math.sqrt(Math.max(0, radius * radius - a * a)), zb = radius - (float) Math.sqrt(Math.max(0, radius * radius - b * b));
            float ua = (float) i / seg, ub = (float) (i + 1) / seg;
            v(pose, vc, a, y0, za, ua, 1, 0xFFFFFFFF); v(pose, vc, b, y0, zb, ub, 1, 0xFFFFFFFF); v(pose, vc, b, y0 + ph, zb, ub, 0, 0xFFFFFFFF); v(pose, vc, a, y0 + ph, za, ua, 0, 0xFFFFFFFF);
        }
    }

    void panel(State s, PoseStack ps, SubmitNodeCollector out) {
        float w = s.w, h = s.h;
        int bg = switch (s.kind) { case "facade" -> 0xFF101010; case "restroom" -> 0xFF1E3A5F; default -> 0xFF14171C; };
        out.submitCustomGeometry(ps, RenderTypes.entityCutout(FRAME), (pose, vc) -> quad(pose, vc, -w / 2, 0, w / 2, h, 0, bg));
        int n = Math.max(1, s.lines.size());
        float lineH = h / (n + 0.6f);
        Font font = Minecraft.getInstance().font;
        for (int i = 0; i < s.lines.size(); i++) {
            String line = s.lines.get(i);
            float scale = Math.min(lineH * 0.7f / 9f, (w * 0.94f) / Math.max(20, font.width(line)));
            if (i == 0 && !s.kind.equals("board")) scale = Math.min(scale * 1.25f, (w * 0.94f) / Math.max(20, font.width(line)));
            int color = i == 0 ? 0xFFFFD54F : 0xFFE0E0E0;
            text(ps, out, line, h - lineH * (i + 0.85f), scale, color);
        }
    }

    private void text(PoseStack ps, SubmitNodeCollector out, String s, float y, float scale, int color) {
        if (s == null || s.isEmpty()) return;
        Font font = Minecraft.getInstance().font;
        var seq = Component.literal(s).getVisualOrderText();
        ps.pushPose();
        ps.translate(0, y, 0.02f);
        ps.scale(scale, -scale, scale);              // like sign text: readable from the seats (+Z)
        out.submitText(ps, -font.width(seq) / 2f, 0, seq, false, Font.DisplayMode.POLYGON_OFFSET, FULL_BRIGHT, color, 0, 0);
        ps.popPose();
    }

    /** A picture rectangle in the screen plane (z), facing +Z (the seats). u runs left to right as seen from the seats. */
    static void quad(PoseStack.Pose pose, VertexConsumer vc, float x0, float y0, float x1, float y1, float z, int color) {
        v(pose, vc, x0, y0, z, 0, 1, color);
        v(pose, vc, x1, y0, z, 1, 1, color);
        v(pose, vc, x1, y1, z, 1, 0, color);
        v(pose, vc, x0, y1, z, 0, 0, color);
    }

    private static void v(PoseStack.Pose pose, VertexConsumer vc, float x, float y, float z, float u, float vv, int color) {
        vc.addVertex(pose, x, y, z).setColor(color).setUv(u, vv).setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULL_BRIGHT).setNormal(pose, 0, 0, 1);
    }
}
