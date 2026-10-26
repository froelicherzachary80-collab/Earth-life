package earthlife.mod.client.cinema;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import earthlife.mod.EarthLifeMod;
import earthlife.mod.cinema.CinemaScreenEntity;
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

/**
 * Draws a cinema screen: a black masking frame and the film picture (letterboxed to the film's shape), lit by itself.
 * With nothing playing it shows the auditorium name and how to start a film; while a film loads, its status.
 */
public final class CinemaScreenRenderer extends EntityRenderer<CinemaScreenEntity, CinemaScreenRenderer.State> {
    public static final class State extends EntityRenderState {
        float w, h, yaw, aspect;
        Identifier picture;
        String line1 = "", line2 = "";
    }

    static final Identifier FRAME = EarthLifeMod.id("textures/block/screen_frame.png");
    static final int FULL_BRIGHT = 0xF000F0;

    public CinemaScreenRenderer(EntityRendererProvider.Context ctx) { super(ctx); }

    @Override public State createRenderState() { return new State(); }

    @Override public boolean shouldRender(CinemaScreenEntity e, net.minecraft.client.renderer.culling.Frustum f, double cx, double cy, double cz, float partial) {
        return e.distanceToSqr(cx, cy, cz) < 96 * 96;
    }

    @Override public void extractRenderState(CinemaScreenEntity e, State s, float partial) {
        super.extractRenderState(e, s, partial);
        s.w = e.screenWidth(); s.h = e.screenHeight(); s.yaw = e.getYRot();
        VideoPlayer p = CinemaClient.player(e.key());
        if (p != null) p.updateTexture();                 // render thread: newest due frame -> texture
        s.picture = p == null ? null : p.texture();
        s.aspect = p == null ? 2.2f : p.aspect;
        var show = CinemaClient.show(e.key());
        if (s.picture == null) {
            if (show.isEmpty()) { s.line1 = e.label(); s.line2 = "Buy a ticket at the kiosk in the lobby to start a film"; }
            else {
                s.line1 = show.get().title() + ("2D".equals(show.get().format()) ? "" : " · 3D");
                s.line2 = show.get().paused() ? "Paused" : p == null ? "Come closer to watch" : p.status;
                if (!"2D".equals(show.get().format()) && !CinemaClient.wearingGlasses() && p != null && p.error == null) s.line2 += " · put on your 3D glasses";
            }
        }
    }

    @Override public void submit(State s, PoseStack ps, SubmitNodeCollector out, CameraRenderState camera) {
        ps.pushPose();
        ps.rotate(new Quaternionf().rotateY((float) Math.toRadians(-s.yaw)));
        float w = s.w, h = s.h;
        // masking frame (slightly behind the picture)
        out.submitCustomGeometry(ps, RenderTypes.entityCutout(FRAME), (pose, vc) -> quad(pose, vc, -w / 2 - 0.35f, -0.2f, w / 2 + 0.35f, h + 0.25f, -0.02f, 0xFF0A0A0A));
        if (s.picture != null) {
            // fit the film into the screen: scope films fill the width, narrower films are pillar-boxed
            float pw = w, ph = w / s.aspect;
            if (ph > h) { ph = h; pw = h * s.aspect; }
            float x0 = -pw / 2, y0 = (h - ph) / 2;
            Identifier pic = s.picture;
            final float fpw = pw, fph = ph;
            out.submitCustomGeometry(ps, RenderTypes.entityCutout(pic), (pose, vc) -> quad(pose, vc, x0, y0, x0 + fpw, y0 + fph, 0, 0xFFFFFFFF));
        } else {
            out.submitCustomGeometry(ps, RenderTypes.entityCutout(FRAME), (pose, vc) -> quad(pose, vc, -w / 2, 0, w / 2, h, 0, 0xFF1B1E24));
            text(ps, out, s.line1, h * 0.62f, Math.min(0.06f, w / 260f), 0xFFFFFFFF);
            text(ps, out, s.line2, h * 0.42f, Math.min(0.035f, w / 420f), 0xFFB0BEC5);
        }
        ps.popPose();
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
