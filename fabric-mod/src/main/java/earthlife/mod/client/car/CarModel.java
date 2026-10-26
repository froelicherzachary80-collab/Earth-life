package earthlife.mod.client.car;

import com.mojang.blaze3d.platform.NativeImage;
import earthlife.mod.EarthLifeMod;
import earthlife.mod.vehicle.CarGeometry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.*;

/**
 * An original, generic car model (no real make or brand), built from boxes in code: a sedan (car, taxi) and a crew-cab
 * pickup. Three layers share the same moving groups so they animate together:
 * <ul><li>opaque - body panels, bumpers, wheels, interior (seats, dashboard, steering wheel, centre console);</li>
 * <li>glass - windshield, rear and side windows (translucent);</li>
 * <li>lights - head/tail lights, drawn full-bright.</li></ul>
 * Moving groups: four doors hinged at their front edge, four wheels (spin; fronts also steer) and the steering wheel.
 * The texture is painted at runtime for each body colour, so any colour works without image files.
 */
public final class CarModel {
    public enum Mat { BODY, TRIM, UNDER, GLASS, TIRE, RIM, CHROME, HEADLIGHT, TAILLIGHT, INTERIOR, SEAT, DASH, PLATE, GRILLE, SIGN, SCREEN }

    public static final String[] DOORS = {"door_fl", "door_fr", "door_rl", "door_rr"};
    public static final String[] WHEELS = {"wheel_fl", "wheel_fr", "wheel_rl", "wheel_rr"};
    static final int TW = 512, TH = 512;

    public final CarGeometry g;
    public final boolean taxi;
    public final ModelPart opaque, glass, lights;
    /** GPS screen rectangle on the centre stack, in model pixels: x0, y0, x1, y1, z (screen faces the cabin, -Z). */
    public final float[] gpsScreen;
    private final List<Paint> paints;

    record Paint(int u, int v, float dx, float dy, float dz, Mat mat) {}

    /** Child boxes of the steering wheel (all in the opaque layer), turned about the column when steering. */
    public final List<String> steerParts;

    private CarModel(CarGeometry g, boolean taxi, ModelPart opaque, ModelPart glass, ModelPart lights, float[] gps, List<Paint> paints, List<String> steerParts) {
        this.g = g; this.taxi = taxi; this.opaque = opaque; this.glass = glass; this.lights = lights; this.gpsScreen = gps; this.paints = paints; this.steerParts = steerParts;
    }

    /** Poses every moving group of one layer: doors 0..1 open, wheel spin (radians), steering -1..1. */
    public void pose(ModelPart root, float[] doors, float spin, float steer) {
        for (int d = 0; d < 4; d++) {
            ModelPart p = root.getChild(DOORS[d]);
            p.resetPose();
            float t = doors[d];
            float eased = t * t * (3 - 2 * t);
            p.yRot = (d % 2 == 0 ? -1 : 1) * eased * 1.15f;          // left doors swing out to +X, right doors to -X
        }
        for (int w = 0; w < 4; w++) {
            ModelPart p = root.getChild(WHEELS[w]);
            p.resetPose();
            p.xRot = spin;
            if (w < 2) p.yRot = -steer * 0.5f;
        }
        if (root == opaque) {
            ModelPart s = root.getChild("steer");
            for (String n : steerParts) s.getChild(n).zRot = steer * 1.6f;
        }
    }

    // ------------------------------------------------------------------ building

    private static final class B {
        final MeshDefinition[] meshes = {new MeshDefinition(), new MeshDefinition(), new MeshDefinition()};   // opaque, glass, lights
        final Map<String, PartPose> poses = new LinkedHashMap<>();
        final List<Paint> paints = new ArrayList<>();
        int cu = 0, cv = 0, rowH = 0, n = 0;
        final List<String> steerParts = new ArrayList<>();

        B() { poses.put("static", PartPose.ZERO); }

        void group(String name, PartPose pose) { poses.put(name, pose); }

        PartDefinition part(int layer, String group) {
            PartDefinition root = meshes[layer].getRoot();
            for (var e : root.getChildren()) if (e.getKey().equals(group)) return e.getValue();
            return root.addOrReplaceChild(group, CubeListBuilder.create(), poses.get(group));
        }

        /** Adds a box from (x0,y0,z0) to (x1,y1,z1) in group coordinates. */
        void box(String group, Mat mat, float x0, float y0, float z0, float x1, float y1, float z1) {
            rot(group, mat, x0, y0, z0, x1, y1, z1, 0, 0, 0, 0, 0, 0);
        }

        /** A box in its own frame (pivot px,py,pz with rotation rx,ry,rz radians) inside a group. */
        void rot(String group, Mat mat, float x0, float y0, float z0, float x1, float y1, float z1, float px, float py, float pz, float rx, float ry, float rz) {
            float dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0), dz = Math.abs(z1 - z0);
            int w = (int) Math.ceil(2 * (dx + dz)) + 1, h = (int) Math.ceil(dz + dy) + 1;
            if (cu + w > TW) { cu = 0; cv += rowH; rowH = 0; }
            int u = cu, v = cv;
            cu += w; rowH = Math.max(rowH, h);
            if (cv + h > TH) throw new IllegalStateException("car texture atlas full");
            paints.add(new Paint(u, v, dx, dy, dz, mat));
            int layer = mat == Mat.GLASS ? 1 : (mat == Mat.HEADLIGHT || mat == Mat.TAILLIGHT || mat == Mat.SIGN) ? 2 : 0;
            if (group.equals("steer")) steerParts.add("b" + n);
            part(layer, group).addOrReplaceChild("b" + (n++), CubeListBuilder.create().texOffs(u, v).addBox(Math.min(x0, x1), Math.min(y0, y1), Math.min(z0, z1), dx, dy, dz),
                    PartPose.offsetAndRotation(px, py, pz, rx, ry, rz));
        }

        /** Same box on both sides of the car (mirrored in X). */
        void both(String group, Mat mat, float x0, float y0, float z0, float x1, float y1, float z1) {
            box(group, mat, x0, y0, z0, x1, y1, z1);
            box(group, mat, -x1, y0, z0, -x0, y1, z1);
        }

        ModelPart bake(int layer) {
            // every layer gets every group (empty if unused) so the renderer can pose them uniformly
            for (String gname : poses.keySet()) part(layer, gname);
            return LayerDefinition.create(meshes[layer], TW, TH).bakeRoot();
        }
    }

    public static CarModel build(CarGeometry g, boolean taxi) {
        B b = new B();
        float L = g.length() / 2, W = g.width() / 2, R = g.wheelR();
        // ---- moving groups (poses = hinge / axle positions) ----
        b.group("door_fl", PartPose.offset(W, 0, g.windBaseZ()));
        b.group("door_fr", PartPose.offset(-W, 0, g.windBaseZ()));
        b.group("door_rl", PartPose.offset(W, 0, g.bPillarZ() - 0.7f));
        b.group("door_rr", PartPose.offset(-W, 0, g.bPillarZ() - 0.7f));
        float wx = W - g.wheelW() / 2 - 0.3f;
        b.group("wheel_fl", PartPose.offset(wx, R, g.wheelZ()));
        b.group("wheel_fr", PartPose.offset(-wx, R, g.wheelZ()));
        b.group("wheel_rl", PartPose.offset(wx, R, -g.wheelZ()));
        b.group("wheel_rr", PartPose.offset(-wx, R, -g.wheelZ()));
        float sx = g.seatX();
        float dashZ = g.windBaseZ() - 4;
        b.group("steer", PartPose.offsetAndRotation(sx, g.windBaseY() + 1.6f, dashZ - 2.2f, -1.05f, 0, 0));

        // ---- body ---- (the sides between the front fender and the rear quarter are the doors themselves)
        float bb = g.bodyBottom(), belt = g.beltY();
        float rearDoorHinge = g.bPillarZ() - 0.7f, doorRearEnd = g.pickup() ? g.rearBaseZ() : g.rearBaseZ() + 3.5f;
        b.box("static", Mat.UNDER, -W + 1.5f, R - 2.5f, -L + 2, W - 1.5f, bb, L - 2);
        b.box("static", Mat.BODY, -W, bb, g.windBaseZ(), W, belt, L - 1.5f);                                     // front: fenders + engine bay
        b.box("static", Mat.BODY, -W, bb, -L + 1.5f, W, g.pickup() ? belt - 4 : belt, doorRearEnd);              // rear quarters + trunk / bed base
        b.both("static", Mat.BODY, W - 1.2f, R - 1.5f, doorRearEnd, W, bb, g.windBaseZ());                       // sills under the doors
        b.box("static", Mat.TRIM, -W + 0.5f, R - 0.5f, L - 1.5f, W - 0.5f, bb + 3, L + 0.6f);                      // front bumper
        b.box("static", Mat.TRIM, -W + 0.5f, R - 0.5f, -L - 0.6f, W - 0.5f, bb + 3, -L + 1.5f);                    // rear bumper
        b.box("static", Mat.BODY, -W + 0.5f, belt, g.windBaseZ(), W - 0.5f, g.hoodTopY() + 0.4f, L - 2);          // hood
        b.box("static", Mat.GRILLE, -W + 7, bb + 2.5f, L - 1.6f, W - 7, belt - 1, L - 1.2f);
        b.box("static", Mat.PLATE, -3.5f, bb + 0.3f, L + 0.6f, 3.5f, bb + 2.5f, L + 0.9f);
        b.box("static", Mat.PLATE, -3.5f, bb + 3.5f, -L - 0.9f, 3.5f, bb + 5.7f, -L - 0.6f);
        b.both("static", Mat.HEADLIGHT, W - 6, belt - 3.2f, L - 1.6f, W - 1, belt - 1.2f, L - 1.2f);
        b.both("static", Mat.TAILLIGHT, W - 5, belt - 3.5f, -L + 1.2f, W - 0.8f, belt - 1, -L + 1.6f);
        b.both("static", Mat.CHROME, W - 7, belt - 3.6f, L - 1.65f, W - 6, belt - 0.8f, L - 1.15f);
        // mirrors
        b.both("static", Mat.BODY, W, belt + 1, g.windBaseZ() - 2.5f, W + 2.2f, belt + 2.8f, g.windBaseZ() - 1);
        b.both("static", Mat.CHROME, W + 2.2f, belt + 1.1f, g.windBaseZ() - 2.4f, W + 2.4f, belt + 2.7f, g.windBaseZ() - 1.1f);
        if (g.pickup()) {
            // cab back wall and the open bed
            b.box("static", Mat.BODY, -W + 0.5f, belt, g.rearBaseZ() - 0.8f, W - 0.5f, g.roofY(), g.rearBaseZ());
            b.box("static", Mat.TRIM, -W + 1, belt - 4, -L + 1.5f, W - 1, belt - 3, g.rearBaseZ() - 0.8f);          // bed floor
            b.both("static", Mat.BODY, W - 1, belt - 3, -L + 1.5f, W, belt + 1.5f, g.rearBaseZ() - 0.8f);          // bed sides
            b.box("static", Mat.BODY, -W + 1, belt - 3, -L + 1.5f, W - 1, belt + 1.5f, -L + 2.5f);                 // tailgate
            b.both("static", Mat.TRIM, W - 1.2f, belt + 1.5f, -L + 1.5f, W + 0.1f, belt + 1.9f, g.rearBaseZ() - 0.8f);
        } else {
            b.box("static", Mat.BODY, -W + 0.5f, belt, -L + 2, W - 0.5f, g.deckY(), g.rearBaseZ());               // trunk lid
        }
        // roof, pillars, windshield, rear window
        float roofW = W - 1.5f;
        b.box("static", Mat.BODY, -roofW, g.roofY() - 1.2f, g.roofRearZ(), roofW, g.roofY(), g.roofFrontZ());
        b.both("static", Mat.BODY, roofW - 0.5f, g.roofY() - 1.8f, g.roofRearZ(), roofW + 0.6f, g.roofY() - 0.6f, g.roofFrontZ());
        float wsLen = (float) Math.hypot(g.roofY() - g.windBaseY(), g.windBaseZ() - g.roofFrontZ());
        float wsAng = (float) -Math.atan2(g.windBaseZ() - g.roofFrontZ(), g.roofY() - g.windBaseY());
        b.rot("static", Mat.GLASS, -roofW + 0.4f, 0, -0.2f, roofW - 0.4f, wsLen, 0.2f, 0, g.windBaseY(), g.windBaseZ(), wsAng, 0, 0);
        for (int s = -1; s <= 1; s += 2)
            b.rot("static", Mat.BODY, s * roofW - 0.6f, 0, -0.6f, s * roofW + 0.6f, wsLen, 0.6f, 0, g.windBaseY(), g.windBaseZ(), wsAng, 0, 0);
        float rwLen = (float) Math.hypot(g.roofY() - g.rearBaseY(), g.roofRearZ() - g.rearBaseZ());
        float rwAng = (float) Math.atan2(g.roofRearZ() - g.rearBaseZ(), g.roofY() - g.rearBaseY());
        b.rot("static", Mat.GLASS, -roofW + 0.6f, 0, -0.2f, roofW - 0.6f, rwLen, 0.2f, 0, g.rearBaseY(), g.rearBaseZ(), rwAng, 0, 0);
        for (int s = -1; s <= 1; s += 2)
            b.rot("static", Mat.BODY, s * roofW - 1.2f, 0, -0.8f, s * roofW + 0.6f, rwLen, 0.8f, 0, g.rearBaseY(), g.rearBaseZ(), rwAng, 0, 0);
        b.both("static", Mat.BODY, W - 1.6f, belt, g.bPillarZ() - 0.7f, W - 0.4f, g.roofY() - 1.2f, g.bPillarZ() + 0.7f);      // B pillars
        // quarter windows behind the rear doors (sedan): fixed glass between the rear door and the C pillar
        if (!g.pickup()) b.both("static", Mat.GLASS, W - 1.1f, belt, g.rearBaseZ() + 1.2f, W - 0.7f, g.rearBaseY() + 3, doorRearEnd);
        if (taxi) {
            b.box("static", Mat.SIGN, -4, g.roofY(), -3, 4, g.roofY() + 3, 1);
            b.box("static", Mat.TRIM, -4.3f, g.roofY(), -3.3f, 4.3f, g.roofY() + 0.4f, 1.3f);
        }

        // ---- interior ----
        float floor = Math.max(bb - 2, R - 2);
        b.box("static", Mat.INTERIOR, -W + 1.2f, floor, g.rearBaseZ() + 0.5f, W - 1.2f, floor + 0.6f, g.windBaseZ());
        b.box("static", Mat.DASH, -W + 1.2f, belt - 1, dashZ, W - 1.2f, g.windBaseY() + 1, g.windBaseZ());
        b.box("static", Mat.DASH, -W + 1.2f, g.windBaseY(), dashZ + 1, W - 1.2f, g.windBaseY() + 1.2f, g.windBaseZ());
        b.box("static", Mat.DASH, -2.6f, floor + 0.6f, g.frontSeatZ() - 3, 2.6f, g.hipY() + 2.2f, dashZ);        // centre console
        float scrY0 = g.windBaseY() - 1.6f, scrY1 = g.windBaseY() + 2.6f, scrZ = dashZ - 0.6f;
        b.box("static", Mat.DASH, -3.6f, scrY0 - 0.6f, scrZ, 3.6f, scrY1 + 0.6f, dashZ + 0.2f);                  // GPS head unit
        b.box("static", Mat.DASH, sx - 3.5f, g.windBaseY() - 0.5f, dashZ - 0.4f, sx + 3.5f, g.windBaseY() + 2.2f, dashZ);   // instrument cluster hood
        float[] gps = {-3.0f, scrY0, 3.0f, scrY1, scrZ - 0.02f};
        // steering column + wheel (group "steer" is tilted towards the driver)
        b.box("static", Mat.DASH, sx - 0.6f, g.windBaseY() - 1.2f, dashZ - 2.4f, sx + 0.6f, g.windBaseY(), dashZ);
        b.box("steer", Mat.DASH, -3.6f, 2.8f, -0.5f, 3.6f, 3.6f, 0.5f);
        b.box("steer", Mat.DASH, -3.6f, -3.6f, -0.5f, 3.6f, -2.8f, 0.5f);
        b.box("steer", Mat.DASH, -3.6f, -2.8f, -0.5f, -2.8f, 2.8f, 0.5f);
        b.box("steer", Mat.DASH, 2.8f, -2.8f, -0.5f, 3.6f, 2.8f, 0.5f);
        b.box("steer", Mat.TRIM, -2.8f, -0.4f, -0.3f, 2.8f, 0.4f, 0.3f);
        b.box("steer", Mat.CHROME, -1, -1, -0.6f, 1, 1, 0.4f);
        // seats: cushion + backrest + headrest, front pair and rear bench
        float hip = g.hipY();
        for (int s = -1; s <= 1; s += 2) {
            float cx = s * sx, fz = g.frontSeatZ();
            b.box("static", Mat.SEAT, cx - 3.6f, floor + 0.6f, fz - 3, cx + 3.6f, hip - 0.2f, fz + 5.5f);
            b.rot("static", Mat.SEAT, cx - 3.6f, 0, -1.6f, cx + 3.6f, 11, 0, 0, hip - 0.4f, fz - 2.4f, -0.22f, 0, 0);
            b.rot("static", Mat.SEAT, cx - 2, 11.5f, -1.4f, cx + 2, 14.5f, 0, 0, hip - 0.4f, fz - 2.4f, -0.22f, 0, 0);
        }
        float rz = g.rearSeatZ();
        b.box("static", Mat.SEAT, -W + 1.5f, floor + 0.6f, rz - 3, W - 1.5f, hip - 0.2f, rz + 5);
        b.rot("static", Mat.SEAT, -W + 1.5f, 0, -1.6f, W - 1.5f, g.pickup() ? 11 : 10, 0, 0, hip - 0.4f, rz - 2.4f, g.pickup() ? -0.12f : -0.3f, 0, 0);

        // ---- doors: outer panel, inner trim, handle, window glass; hinged at their front edge ----
        float frontLen = g.windBaseZ() - g.bPillarZ() - 0.7f, rearLen = rearDoorHinge - doorRearEnd;
        for (int d = 0; d < 4; d++) {
            String gname = DOORS[d];
            boolean left = d % 2 == 0;
            float len = d < 2 ? frontLen : rearLen;
            float x0 = left ? -1.2f : 0, x1 = left ? 0 : 1.2f;
            b.box(gname, Mat.BODY, x0, bb, -len, x1, belt, 0);
            b.box(gname, Mat.INTERIOR, left ? -1.6f : 1.2f, bb + 0.5f, -len + 0.5f, left ? -1.2f : 1.6f, belt - 0.3f, -0.5f);
            b.box(gname, Mat.CHROME, left ? 0 : -0.35f, belt - 2.2f, -len + 1.5f, left ? 0.35f : 0, belt - 1.6f, -len + 3.5f);
            // window: full height where the roof is above it, half height under the sloping A/C pillars
            float top = g.roofY() - 2.2f, gx0 = left ? -0.9f : 0.5f, gx1 = left ? -0.5f : 0.9f;
            float fullFrom = d < 2 ? -len + 0.6f : Math.max(-len + 0.6f, g.roofRearZ() - rearDoorHinge);
            float fullTo = d < 2 ? Math.min(-1, g.roofFrontZ() - g.windBaseZ()) : -0.6f;
            b.box(gname, Mat.GLASS, gx0, belt, fullFrom, gx1, top, fullTo);
            b.box(gname, Mat.BODY, x0, top, fullFrom - 0.6f, x1, top + 0.8f, fullTo + 0.6f);
            float half = belt + (top - belt) * 0.45f;
            if (d < 2 && fullTo < -1.2f) b.box(gname, Mat.GLASS, gx0, belt, fullTo, gx1, half, -1);
            if (d >= 2 && fullFrom > -len + 1.2f) b.box(gname, Mat.GLASS, gx0, belt, -len + 0.6f, gx1, half, fullFrom);
        }

        // ---- wheels: tyre, rim with spokes (painted), hub cap ----
        for (int w = 0; w < 4; w++) {
            boolean left = w % 2 == 0;
            float hw = g.wheelW() / 2;
            b.box(WHEELS[w], Mat.TIRE, -hw, -R, -R, hw, R, R);
            b.box(WHEELS[w], Mat.RIM, left ? hw : -hw - 0.3f, -R + 1.6f, -R + 1.6f, left ? hw + 0.3f : -hw, R - 1.6f, R - 1.6f);
            b.box(WHEELS[w], Mat.CHROME, left ? hw + 0.3f : -hw - 0.6f, -0.8f, -0.8f, left ? hw + 0.6f : -hw - 0.3f, 0.8f, 0.8f);
        }
        return new CarModel(g, taxi, b.bake(0), b.bake(1), b.bake(2), gps, b.paints, List.copyOf(b.steerParts));
    }

    // ------------------------------------------------------------------ texture

    private static final Map<String, Identifier> TEXTURES = new HashMap<>();

    public Identifier texture(String color) {
        String key = (g.pickup() ? "pickup_" : "sedan_") + (taxi ? "taxi_" : "") + color;
        return TEXTURES.computeIfAbsent(key, k -> {
            NativeImage img = new NativeImage(TW, TH, true);
            int body = bodyColor(taxi ? "yellow" : color);
            for (Paint p : paints) paint(img, p, body);
            Identifier id = EarthLifeMod.id("car/" + k.replaceAll("[^a-z0-9_]", "_"));
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "EarthLife car " + k, img));
            return id;
        });
    }

    public static int bodyColor(String c) {
        return switch (c == null ? "" : c) {
            case "red" -> 0xFFA8141A; case "blue" -> 0xFF1D4A94; case "white" -> 0xFFEEEFF0; case "black" -> 0xFF16181B;
            case "gray", "grey" -> 0xFF5F6469; case "light_gray", "silver" -> 0xFFB4BAC0; case "green" -> 0xFF2B6438;
            case "yellow" -> 0xFFF0BE1A; case "orange" -> 0xFFD8661C; case "light_blue" -> 0xFF5E9BD0; case "brown" -> 0xFF5C3B22;
            case "purple" -> 0xFF54307A; case "cyan" -> 0xFF168A94; case "lime" -> 0xFF6CB02A; case "pink" -> 0xFFD8749A; case "magenta" -> 0xFF9E2E8E;
            default -> 0xFFB4BAC0;
        };
    }

    static int shade(int argb, double f) {
        int a = argb >>> 24, r = (argb >> 16) & 255, gg = (argb >> 8) & 255, b = argb & 255;
        r = (int) Math.max(0, Math.min(255, r * f)); gg = (int) Math.max(0, Math.min(255, gg * f)); b = (int) Math.max(0, Math.min(255, b * f));
        return (a << 24) | (r << 16) | (gg << 8) | b;
    }

    private static void paint(NativeImage img, Paint p, int body) {
        int dx = (int) Math.ceil(p.dx()), dy = (int) Math.ceil(p.dy()), dz = (int) Math.ceil(p.dz());
        int w = 2 * (dx + dz) + 1, h = dz + dy + 1;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int px = p.u() + x, py = p.v() + y;
            if (px >= TW || py >= TH) continue;
            boolean side = y >= dz;                                  // side faces row (below the top/bottom faces)
            double t = side && dy > 0 ? (y - dz) / (double) Math.max(1, dy) : 0;   // 0 top .. 1 bottom of a side face
            int c = switch (p.mat()) {
                case BODY -> shade(body, side ? 1.08 - 0.22 * t : 1.12);                       // lighter top, darker sills
                case TRIM -> 0xFF2A2C2F;
                case UNDER -> 0xFF161616;
                case GLASS -> 0x7A3E5C70;
                case TIRE -> ((x + y) % 3 == 0) ? 0xFF222224 : 0xFF19191A;
                case RIM -> rim(x, y, dx, dz, p);
                case CHROME -> shade(0xFFD6DADE, 1.05 - 0.15 * t);
                case HEADLIGHT -> 0xFFFFF4D2;
                case TAILLIGHT -> 0xFFD4141C;
                case INTERIOR -> 0xFF3B3631;
                case SEAT -> ((x / 2 + y / 2) % 2 == 0) ? 0xFF2C2C30 : 0xFF333338;
                case DASH -> 0xFF1D1E21;
                case PLATE -> (side && y - dz == 0) || (side && y - dz == dy - 1) ? 0xFF1B3A7A : 0xFFF0F0F0;
                case GRILLE -> (y % 2 == 0) ? 0xFF0E0E0E : 0xFF4A4D50;
                case SIGN -> 0xFFFFE15A;
                case SCREEN -> 0xFF0A0A0A;
            };
            img.setPixel(px, py, c);
        }
    }

    /** Silver rim with five dark spokes gaps on the two side faces (so a spinning wheel is visible). */
    private static int rim(int x, int y, int dx, int dz, Paint p) {
        int dy = (int) Math.ceil(p.dy());
        if (y < dz) return 0xFFB0B4B8;
        int fy = y - dz;
        int fx;
        if (x < dz) fx = x; else if (x >= dz + dx && x < 2 * dz + dx) fx = x - dz - dx; else return 0xFFB0B4B8;
        double cx = (dz - 1) / 2.0, cy = (dy - 1) / 2.0;
        double a = Math.atan2(fy - cy, fx - cx), r = Math.hypot(fy - cy, fx - cx);
        if (r > Math.min(cx, cy) + 0.3) return 0xFF8C9094;
        double sector = ((a + Math.PI) / (2 * Math.PI) * 5) % 1.0;
        return r > 1.2 && sector > 0.55 ? 0xFF2E3134 : 0xFFC4C8CC;
    }
}
