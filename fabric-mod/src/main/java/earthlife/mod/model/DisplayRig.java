package earthlife.mod.model;

import com.mojang.math.Transformation;
import earthlife.core.model.Part;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;
import java.util.function.Predicate;

/**
 * Renders a core {@link Part} model (airliner, car, bus, train...) with vanilla block-display entities, so models
 * need no client-side mod code and look the same to every player. All displays sit at the vehicle's origin; each
 * one's transformation places, rotates and scales its block. Interpolation (2 ticks) smooths motion between updates.
 * Uses the access-widened Display setters (see earthlife.accesswidener). (26.3 access widener, official names).
 */
public final class DisplayRig {
    private final List<Part> parts;
    private final List<Display.BlockDisplay> displays = new ArrayList<>();
    private final Map<String, Float> tagScale = new HashMap<>();

    public DisplayRig(List<Part> parts) { this.parts = List.copyOf(parts); }

    /** Scale a tagged group (e.g. "gear" retracting, "door" opening = 0). */
    public void setTagScale(String tag, float s) { tagScale.put(tag, s); }

    public void pose(ServerLevel level, double x, double y, double z, float yawDeg, float pitchDeg) {
        if (displays.isEmpty()) spawn(level, x, y, z);
        // Minecraft yaw: 0 = facing south (+z). Model forward is +z, so the model rotates by -yaw about Y.
        Quaternionf rot = new Quaternionf().rotateY((float) Math.toRadians(-yawDeg)).rotateX((float) Math.toRadians(pitchDeg));
        for (int i = 0; i < displays.size(); i++) {
            Part p = parts.get(i);
            float s = tagScale.getOrDefault(p.tag(), 1f);
            Vector3f size = new Vector3f((float) p.sx(), (float) p.sy() * Math.max(0.001f, s), (float) p.sz());
            if (s <= 0.001f) size.set(0.001f);
            Vector3f centre = new Vector3f((float) p.x(), (float) (p.y() - p.sy() * (1 - s) / 2), (float) p.z()).rotate(rot);
            Vector3f corner = new Vector3f(-size.x / 2, -size.y / 2, -size.z / 2).rotate(rot);
            Display.BlockDisplay d = displays.get(i);
            d.setTransformation(new Transformation(centre.add(corner), rot, size, new Quaternionf()));
            d.setPos(x, y, z);
        }
    }

    private void spawn(ServerLevel level, double x, double y, double z) {
        for (Part p : parts) {
            Display.BlockDisplay d = net.minecraft.world.entity.EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.TRIGGERED);
            if (d == null) continue;
            d.setBlockState(state(p.block()));
            d.setPosRotInterpolationDuration(2);
            d.setTransformationInterpolationDuration(2);
            d.setPos(x, y, z);
            level.addFreshEntity(d);
            displays.add(d);
        }
    }

    static BlockState state(String id) {
        return earthlife.mod.McCompat.block(id);
    }

    public void remove() { displays.forEach(d -> d.discard()); displays.clear(); }

    /** Parts matching a filter, e.g. for collision or light toggles. */
    public List<Part> parts(Predicate<Part> f) { return parts.stream().filter(f).toList(); }
}
