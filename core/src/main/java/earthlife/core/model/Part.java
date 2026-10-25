package earthlife.core.model;

/**
 * One box of a vehicle/aircraft model, in the model's local frame (x = right, y = up, z = forward, metres,
 * centre of the box). Rendered in Minecraft as a scaled block-display entity, so models need no client mod.
 * {@code tag} lets the game animate groups of parts (e.g. "gear", "door", "wheel").
 */
public record Part(double x, double y, double z, double sx, double sy, double sz, String block, String tag) {
    public Part(double x, double y, double z, double sx, double sy, double sz, String block) { this(x, y, z, sx, sy, sz, block, ""); }

    public double minX() { return x - sx / 2; } public double maxX() { return x + sx / 2; }
    public double minY() { return y - sy / 2; } public double maxY() { return y + sy / 2; }
    public double minZ() { return z - sz / 2; } public double maxZ() { return z + sz / 2; }

    public boolean contains(double px, double py, double pz) {
        return px >= minX() && px <= maxX() && py >= minY() && py <= maxY() && pz >= minZ() && pz <= maxZ();
    }
}
