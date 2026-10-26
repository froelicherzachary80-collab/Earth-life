package earthlife.mod.client.car;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import earthlife.core.transport.VehicleType;
import earthlife.mod.vehicle.CarGeometry;
import earthlife.mod.vehicle.VehicleEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;

/**
 * Draws cars, taxis and pickups with {@link CarModel}; other vehicles (bus, train, ferry...) are block displays and
 * draw nothing here. When the local player sits in this car, the dashboard GPS screen shows the live {@link Gps} map.
 */
public final class CarRenderer extends EntityRenderer<VehicleEntity, CarRenderer.State> {
    public static final class State extends EntityRenderState {
        VehicleType type = VehicleType.CAR;
        String color = "white";
        float yaw, spin, steer;
        final float[] doors = new float[4];
        int flags;
        boolean night, localInside;
    }

    private static CarModel sedan, taxi, pickup;
    static final int FULL_BRIGHT = 0xF000F0;

    public CarRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 1.3f;
    }

    static CarModel model(VehicleType t) {
        if (t == VehicleType.PICKUP) return pickup != null ? pickup : (pickup = CarModel.build(CarGeometry.PICKUP, false));
        if (t == VehicleType.TAXI) return taxi != null ? taxi : (taxi = CarModel.build(CarGeometry.SEDAN, true));
        return sedan != null ? sedan : (sedan = CarModel.build(CarGeometry.SEDAN, false));
    }

    @Override public State createRenderState() { return new State(); }

    @Override public void extractRenderState(VehicleEntity e, State s, float partial) {
        super.extractRenderState(e, s, partial);
        s.type = e.vehicleType();
        s.color = e.color();
        s.yaw = Mth.rotLerp(partial, e.yRotO, e.getYRot());
        for (int i = 0; i < 4; i++) s.doors[i] = e.doorAnim(i, partial);
        s.spin = e.wheelSpin(partial);
        s.steer = e.steer();
        s.flags = e.flags();
        s.night = e.level().isDarkOutside();
        var me = Minecraft.getInstance().player;
        s.localInside = me != null && me.getVehicle() == e;
    }

    @Override public void submit(State s, PoseStack poseStack, SubmitNodeCollector out, CameraRenderState camera) {
        if (!VehicleEntity.clientModel(s.type)) return;
        CarModel m = model(s.type);
        var tex = m.texture(s.color);
        poseStack.pushPose();
        poseStack.mulPose(new Quaternionf().rotateY((float) Math.toRadians(-s.yaw)));
        final float[] doors = s.doors.clone();
        final float spin = s.spin, steer = s.steer;
        final int light = s.lightCoords;
        out.submitCustomGeometry(poseStack, RenderTypes.entityCutout(tex), (pose, vc) -> draw(m, m.opaque, pose, vc, doors, spin, steer, light, -1));
        boolean braking = (s.flags & VehicleEntity.BRAKE) != 0, reversing = (s.flags & VehicleEntity.REVERSE) != 0, engine = (s.flags & VehicleEntity.ENGINE) != 0;
        int lampLight = s.night || braking || reversing || (engine && s.night) ? FULL_BRIGHT : light;
        out.submitCustomGeometry(poseStack, RenderTypes.entityCutout(tex), (pose, vc) -> draw(m, m.lights, pose, vc, doors, spin, steer, lampLight, -1));
        out.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(tex), (pose, vc) -> draw(m, m.glass, pose, vc, doors, spin, steer, light, -1));
        if (s.localInside && Gps.textureId() != null) {
            float[] r = m.gpsScreen;
            out.submitCustomGeometry(poseStack, RenderTypes.entityCutout(Gps.textureId()), (pose, vc) -> quad(pose, vc, r));
        }
        poseStack.popPose();
        super.submit(s, poseStack, out, camera);
    }

    private static void draw(CarModel m, ModelPart root, PoseStack.Pose pose, VertexConsumer vc, float[] doors, float spin, float steer, int light, int color) {
        m.pose(root, doors, spin, steer);
        PoseStack ps = new PoseStack();
        ps.last().pose().set(pose.pose());
        ps.last().normal().set(pose.normal());
        ps.scale(1, 1, 1);
        root.render(ps, vc, light, OverlayTexture.NO_OVERLAY, color);
    }

    /** The GPS picture on the centre stack, facing the occupants (-Z), in model pixels. */
    private static void quad(PoseStack.Pose pose, VertexConsumer vc, float[] r) {
        float x0 = r[0] / 16, y0 = r[1] / 16, x1 = r[2] / 16, y1 = r[3] / 16, z = r[4] / 16;
        // seen from the cabin (looking +Z), the car's left (+X) is on the viewer's left
        vertex(pose, vc, x1, y0, z, 0, 1);
        vertex(pose, vc, x0, y0, z, 1, 1);
        vertex(pose, vc, x0, y1, z, 1, 0);
        vertex(pose, vc, x1, y1, z, 0, 0);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer vc, float x, float y, float z, float u, float v) {
        vc.addVertex(pose, x, y, z).setColor(0xFFFFFFFF).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULL_BRIGHT).setNormal(pose, 0, 0, -1);
    }
}
