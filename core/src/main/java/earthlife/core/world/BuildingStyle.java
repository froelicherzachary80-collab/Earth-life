package earthlife.core.world;

import earthlife.core.util.Json;

import java.util.*;

/**
 * Turns real map tags into a buildable look. When OpenStreetMap has the information (building:levels, height,
 * roof:shape, roof:height, roof:colour, building:colour, building:material) it is used as-is; when it doesn't, the
 * building type picks a style typical of upstate New York (gabled clapboard houses, red gambrel barns, flat-roofed
 * brick main streets, metal warehouses). Tag-derived values are exact; defaults are clearly just defaults.
 */
public final class BuildingStyle {
    public enum Wall { WHITE_SIDING, CREAM_SIDING, GREY_SIDING, BLUE_SIDING, YELLOW_SIDING, GREEN_SIDING, RED_BRICK, BROWN_BRICK,
        STONE, CONCRETE, GLASS, METAL, RED_BARN, WOOD, PLASTER }
    public enum Roof { ASPHALT_DARK, ASPHALT_GREY, ASPHALT_BROWN, METAL_GREEN, METAL_RED, METAL_GREY, SLATE, COPPER, TAR_FLAT, GLASS }
    public enum Shape { FLAT, GABLED, HIPPED, PYRAMIDAL, DOME, SKILLION, GAMBREL }

    /** Raw tag values carried from the map (null = not tagged). */
    public record Tags(Double heightM, Double roofHeightM, String roofShape, String roofColour, String colour, String material, String name, String use) {
        public static final Tags NONE = new Tags(null, null, null, null, null, null, null, null);

        public static Tags parse(Map<String, Object> m) {
            if (m == null) return NONE;
            return new Tags(num(m.get("height")), num(m.get("roofHeight")), Json.str(m, "roofShape"), Json.str(m, "roofColour"),
                    Json.str(m, "colour"), Json.str(m, "material"), Json.str(m, "name"), Json.str(m, "use"));
        }
        private static Double num(Object o) {
            if (o instanceof Number n) return n.doubleValue();
            if (o instanceof String s) { try { return Double.parseDouble(s.replaceAll("[^0-9.]", "")); } catch (NumberFormatException e) { return null; } }
            return null;
        }
        public boolean any() { return heightM != null || roofShape != null || roofColour != null || colour != null || material != null; }
    }

    /** Fully resolved style for one building. Heights are in blocks above the ground block. */
    public record Resolved(Wall wall, Roof roof, Shape shape, int eave, int roofHeight, boolean chimney, boolean fromTags) {}

    private BuildingStyle() {}

    public static Resolved resolve(Features.Building b) {
        Tags t = b.tags() == null ? Tags.NONE : b.tags();
        String kind = b.kind() == null ? "yes" : b.kind();
        long h = (b.id().hashCode() * 0x9E3779B97F4A7C15L) >>> 1;

        Shape shape = t.roofShape() != null ? shapeOf(t.roofShape()) : switch (kind) {
            case "house", "detached", "residential", "semidetached_house", "farm", "bungalow", "cabin", "garage", "shed", "church", "chapel" -> Shape.GABLED;
            case "barn", "farm_auxiliary", "cowshed", "stable" -> Shape.GAMBREL;
            case "terraced", "apartments" -> (h % 3 == 0) ? Shape.HIPPED : Shape.FLAT;
            default -> Shape.FLAT;          // commercial, retail, industrial, school, office, warehouse, terminal...
        };

        Wall wall = t.colour() != null || t.material() != null ? wallFrom(t.material(), t.colour(), kind) : switch (kind) {
            case "house", "detached", "residential", "semidetached_house", "bungalow", "farm" ->
                    new Wall[]{Wall.WHITE_SIDING, Wall.WHITE_SIDING, Wall.CREAM_SIDING, Wall.GREY_SIDING, Wall.BLUE_SIDING, Wall.YELLOW_SIDING, Wall.GREEN_SIDING, Wall.RED_BRICK}[(int) (h % 8)];
            case "barn", "farm_auxiliary", "cowshed", "stable" -> h % 4 == 0 ? Wall.WOOD : Wall.RED_BARN;
            case "commercial", "retail", "school", "civic", "public", "townhall" -> h % 3 == 0 ? Wall.BROWN_BRICK : Wall.RED_BRICK;
            case "industrial", "warehouse", "hangar", "manufacture" -> Wall.METAL;
            case "office" -> h % 2 == 0 ? Wall.GLASS : Wall.CONCRETE;
            case "church", "chapel", "cathedral" -> h % 2 == 0 ? Wall.STONE : Wall.WHITE_SIDING;
            case "terminal" -> Wall.GLASS;
            case "garage", "shed" -> Wall.GREY_SIDING;
            default -> Wall.CONCRETE;
        };

        Roof roof = t.roofColour() != null ? roofFrom(t.roofColour()) : switch (shape) {
            case FLAT -> Roof.TAR_FLAT;
            case GAMBREL -> h % 2 == 0 ? Roof.METAL_GREY : Roof.METAL_RED;
            case DOME -> Roof.COPPER;
            default -> new Roof[]{Roof.ASPHALT_DARK, Roof.ASPHALT_GREY, Roof.ASPHALT_BROWN, Roof.ASPHALT_DARK, Roof.METAL_GREEN}[(int) (h % 5)];
        };
        if (wall == Wall.GLASS && shape == Shape.FLAT) roof = Roof.TAR_FLAT;

        int levels = Math.max(1, b.levels());
        int roofH;
        int eave;
        if (shape == Shape.FLAT) roofH = 1;
        else if (t.roofHeightM() != null) roofH = (int) Math.max(1, Math.round(t.roofHeightM()));
        else roofH = -1;   // computed from footprint width by the roof model
        if (t.heightM() != null) {
            int total = (int) Math.round(t.heightM());
            eave = Math.max(2, roofH > 0 ? total - roofH : total - 3);
        } else eave = Math.max(3, levels * 3);
        boolean chimney = (kind.equals("house") || kind.equals("detached") || kind.equals("farm")) && shape != Shape.FLAT && h % 3 != 0;
        return new Resolved(wall, roof, shape, eave, roofH, chimney, t.any());
    }

    static Shape shapeOf(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "gabled", "saltbox", "half-hipped", "side_hipped", "crosspitched" -> Shape.GABLED;
            case "hipped", "mansard" -> Shape.HIPPED;
            case "pyramidal", "spire", "cone" -> Shape.PYRAMIDAL;
            case "dome", "onion", "round" -> Shape.DOME;
            case "skillion", "lean_to" -> Shape.SKILLION;
            case "gambrel" -> Shape.GAMBREL;
            default -> Shape.FLAT;
        };
    }

    static Wall wallFrom(String material, String colour, String kind) {
        if (material != null) {
            switch (material.toLowerCase(Locale.ROOT)) {
                case "brick": return colour != null && rgb(colour) != null && luminance(rgb(colour)) < 0.3 ? Wall.BROWN_BRICK : Wall.RED_BRICK;
                case "stone", "sandstone", "limestone", "granite": return Wall.STONE;
                case "concrete", "cement_block": return Wall.CONCRETE;
                case "glass": return Wall.GLASS;
                case "metal", "steel", "metal_plates": return Wall.METAL;
                case "wood", "timber_framing", "log": return colour == null ? Wall.WOOD : nearestWall(colour);
                case "plaster", "stucco": return Wall.PLASTER;
                case "vinyl", "plastic": return colour == null ? Wall.WHITE_SIDING : nearestWall(colour);
                default: break;
            }
        }
        return colour == null ? Wall.CONCRETE : nearestWall(colour);
    }

    static final Map<Wall, int[]> WALL_RGB = Map.ofEntries(
            Map.entry(Wall.WHITE_SIDING, new int[]{235, 235, 230}), Map.entry(Wall.CREAM_SIDING, new int[]{232, 220, 185}),
            Map.entry(Wall.GREY_SIDING, new int[]{150, 155, 160}), Map.entry(Wall.BLUE_SIDING, new int[]{110, 140, 175}),
            Map.entry(Wall.YELLOW_SIDING, new int[]{230, 200, 110}), Map.entry(Wall.GREEN_SIDING, new int[]{120, 150, 110}),
            Map.entry(Wall.RED_BRICK, new int[]{165, 70, 55}), Map.entry(Wall.BROWN_BRICK, new int[]{110, 70, 50}),
            Map.entry(Wall.STONE, new int[]{130, 130, 125}), Map.entry(Wall.CONCRETE, new int[]{175, 175, 170}),
            Map.entry(Wall.RED_BARN, new int[]{150, 40, 35}), Map.entry(Wall.WOOD, new int[]{140, 100, 65}),
            Map.entry(Wall.PLASTER, new int[]{225, 215, 200}), Map.entry(Wall.METAL, new int[]{160, 165, 170}));

    static final Map<Roof, int[]> ROOF_RGB = Map.of(
            Roof.ASPHALT_DARK, new int[]{50, 50, 55}, Roof.ASPHALT_GREY, new int[]{110, 110, 110}, Roof.ASPHALT_BROWN, new int[]{100, 70, 50},
            Roof.METAL_GREEN, new int[]{60, 110, 70}, Roof.METAL_RED, new int[]{150, 45, 40}, Roof.METAL_GREY, new int[]{150, 155, 160},
            Roof.SLATE, new int[]{70, 75, 85}, Roof.COPPER, new int[]{90, 160, 130}, Roof.TAR_FLAT, new int[]{80, 80, 80});

    static Wall nearestWall(String colour) {
        int[] c = rgb(colour);
        if (c == null) return Wall.CONCRETE;
        return WALL_RGB.entrySet().stream().min(Comparator.comparingDouble(e -> dist(e.getValue(), c))).get().getKey();
    }

    static Roof roofFrom(String colour) {
        int[] c = rgb(colour);
        if (c == null) return Roof.ASPHALT_DARK;
        return ROOF_RGB.entrySet().stream().min(Comparator.comparingDouble(e -> dist(e.getValue(), c))).get().getKey();
    }

    static final Map<String, int[]> NAMED = Map.ofEntries(
            Map.entry("white", new int[]{240, 240, 240}), Map.entry("black", new int[]{20, 20, 20}), Map.entry("grey", new int[]{128, 128, 128}),
            Map.entry("gray", new int[]{128, 128, 128}), Map.entry("red", new int[]{170, 40, 35}), Map.entry("darkred", new int[]{120, 30, 30}),
            Map.entry("maroon", new int[]{120, 30, 30}), Map.entry("brown", new int[]{110, 70, 45}), Map.entry("beige", new int[]{225, 210, 175}),
            Map.entry("cream", new int[]{240, 225, 190}), Map.entry("yellow", new int[]{230, 200, 90}), Map.entry("green", new int[]{70, 130, 70}),
            Map.entry("darkgreen", new int[]{40, 90, 50}), Map.entry("blue", new int[]{70, 110, 170}), Map.entry("lightblue", new int[]{150, 190, 220}),
            Map.entry("silver", new int[]{190, 190, 195}), Map.entry("tan", new int[]{200, 175, 135}), Map.entry("orange", new int[]{220, 130, 60}));

    /** Parses "#rrggbb", "#rgb" or a common colour name. */
    public static int[] rgb(String s) {
        if (s == null) return null;
        String v = s.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "");
        if (v.matches("#?[0-9a-f]{6}")) { v = v.replace("#", ""); return new int[]{Integer.parseInt(v.substring(0, 2), 16), Integer.parseInt(v.substring(2, 4), 16), Integer.parseInt(v.substring(4, 6), 16)}; }
        if (v.matches("#[0-9a-f]{3}")) return new int[]{Integer.parseInt(v.substring(1, 2), 16) * 17, Integer.parseInt(v.substring(2, 3), 16) * 17, Integer.parseInt(v.substring(3, 4), 16) * 17};
        return NAMED.get(v);
    }

    static double luminance(int[] c) { return (0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]) / 255.0; }
    static double dist(int[] a, int[] b) { double r = a[0] - b[0], g = a[1] - b[1], bb = a[2] - b[2]; return r * r * 0.3 + g * g * 0.59 + bb * bb * 0.11; }

    // ---------------------------------------------------------------------------------------------------
    /** Oriented bounding box of a footprint: centre, unit long axis (ux,uz) and half-lengths along/across it. */
    public record Obb(double cx, double cz, double ux, double uz, double halfU, double halfV) {
        public double[] local(double x, double z) {
            double dx = x - cx, dz = z - cz;
            return new double[]{dx * ux + dz * uz, -dx * uz + dz * ux};
        }
    }

    /** Minimum-area rectangle over the footprint's edge directions (exact for convex outlines, good for others). */
    public static Obb obb(List<Features.Pt> ring) {
        Obb best = null;
        double bestArea = Double.MAX_VALUE;
        for (int i = 0; i < ring.size(); i++) {
            Features.Pt a = ring.get(i), b = ring.get((i + 1) % ring.size());
            double len = Math.hypot(b.x() - a.x(), b.z() - a.z());
            if (len < 1e-6) continue;
            double ux = (b.x() - a.x()) / len, uz = (b.z() - a.z()) / len;
            double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE, minV = Double.MAX_VALUE, maxV = -Double.MAX_VALUE;
            for (Features.Pt p : ring) {
                double u = p.x() * ux + p.z() * uz, v = -p.x() * uz + p.z() * ux;
                minU = Math.min(minU, u); maxU = Math.max(maxU, u); minV = Math.min(minV, v); maxV = Math.max(maxV, v);
            }
            double area = (maxU - minU) * (maxV - minV);
            if (area < bestArea) {
                bestArea = area;
                double cu = (minU + maxU) / 2, cv = (minV + maxV) / 2;
                double cx = cu * ux - cv * uz, cz = cu * uz + cv * ux;
                double hu = (maxU - minU) / 2, hv = (maxV - minV) / 2;
                best = hu >= hv ? new Obb(cx, cz, ux, uz, hu, hv) : new Obb(cx, cz, -uz, ux, hv, hu);   // u = long axis
            }
        }
        return best;
    }

    /** Default pitched-roof height (blocks) for a footprint: ~35° pitch over half the short side, max 9. */
    public static int defaultRoofHeight(Shape s, Obb o) {
        return switch (s) {
            case FLAT -> 1;
            case DOME -> (int) Math.max(2, Math.round(Math.min(o.halfU(), o.halfV())));
            case SKILLION -> (int) Math.max(1, Math.min(5, Math.round(o.halfV() * 0.5)));
            default -> (int) Math.max(1, Math.min(9, Math.round(o.halfV() * 0.75)));
        };
    }

    /** Roof surface height (0..roofH) above the eave at a point. */
    public static double roofAt(Shape s, Obb o, int roofH, double x, double z) {
        double[] l = o.local(x, z);
        double fu = Math.min(1, Math.abs(l[0]) / Math.max(0.5, o.halfU())), fv = Math.min(1, Math.abs(l[1]) / Math.max(0.5, o.halfV()));
        double hv = o.halfV();
        return switch (s) {
            case FLAT -> 1;
            case GABLED -> roofH * (1 - fv);
            case HIPPED -> roofH * Math.max(0, Math.min(1 - fv, (o.halfU() - Math.abs(l[0])) / Math.max(0.5, hv)));
            case PYRAMIDAL -> roofH * Math.max(0, Math.min(1 - fu, 1 - fv));
            case DOME -> { double r = Math.hypot(l[0], l[1]) / Math.max(0.5, Math.min(o.halfU(), o.halfV())); yield r >= 1 ? 0 : roofH * Math.sqrt(1 - r * r); }
            case SKILLION -> roofH * (l[1] + hv) / Math.max(1, 2 * hv);
            case GAMBREL -> fv < 0.5 ? roofH * (1 - fv * 0.6) : roofH * 0.7 * (1 - fv) / 0.5 + 0.0;   // steep lower, shallow upper
        };
    }
}
