package earthlife.core.transport;

import earthlife.core.model.Part;

import java.util.*;

/** Vehicle specs + blocky models. Units: m, m/s, m/s², degrees. */
public enum VehicleType {
    BICYCLE("Bicycle", 8, 1.5, 4, 1.1, 35, 1, 1.8, 0.6, 1.2, 40, "ground"),
    CAR("Car", 38, 3.2, 7, 2.7, 33, 4, 4.4, 1.9, 1.5, 250, "ground"),
    PICKUP("Pickup truck", 36, 2.8, 7, 3.4, 32, 3, 5.4, 2.0, 1.9, 320, "ground"),
    TAXI("Taxi", 36, 3.0, 7, 2.7, 33, 4, 4.5, 1.9, 1.5, 0, "ground"),
    BUS("Bus", 25, 1.2, 4.5, 6.0, 38, 30, 12, 2.6, 3.2, 0, "ground"),
    DELIVERY_VAN("Delivery van", 31, 2.0, 6, 3.5, 34, 2, 6.0, 2.1, 2.6, 0, "ground"),
    TRAIN("Commuter train", 44, 0.9, 1.2, 0, 0, 96, 25, 3.0, 4.0, 0, "rail"),
    FERRY("Ferry", 9, 0.25, 0.4, 0, 6, 80, 30, 9, 6, 0, "water"),
    BOAT("Motorboat", 15, 1.5, 2.0, 0, 20, 4, 6, 2.4, 1.6, 180, "water");

    public final String label, terrain;
    public final double maxSpeed, accel, brake, wheelbase, maxSteerDeg, length, width, height;
    public final int seats;
    public final long priceLC;   // 0 = can't be bought (public transport / company vehicle)

    VehicleType(String label, double vmax, double accel, double brake, double wheelbase, double steer, int seats,
                double length, double width, double height, long price, String terrain) {
        this.label = label; this.maxSpeed = vmax; this.accel = accel; this.brake = brake; this.wheelbase = wheelbase;
        this.maxSteerDeg = steer; this.seats = seats; this.length = length; this.width = width; this.height = height;
        this.priceLC = price; this.terrain = terrain;
    }

    /** Tightest turning radius (m) for steerable ground vehicles. */
    public double minTurnRadius() { return maxSteerDeg <= 0 ? Double.POSITIVE_INFINITY : wheelbase / Math.tan(Math.toRadians(maxSteerDeg)); }

    /** Seat positions in local coordinates (x right, z forward), front row first, driver = seat 0 (left). */
    public List<double[]> seatPositions() {
        List<double[]> out = new ArrayList<>();
        int perRow = this == BICYCLE ? 1 : this == BUS || this == TRAIN || this == FERRY ? 4 : 2;
        double rowPitch = this == BUS || this == TRAIN ? 0.9 : 1.0;
        int rows = (int) Math.ceil(seats / (double) perRow);
        double z0 = this == BUS ? length / 2 - 1.6 : length / 2 - (this == BICYCLE ? 1.0 : 1.9);
        for (int r = 0; r < rows; r++) for (int c = 0; c < perRow && out.size() < seats; c++) {
            double x = perRow == 1 ? 0 : perRow == 2 ? (c == 0 ? -0.45 : 0.45) : (c < 2 ? -width / 2 + 0.45 + c * 0.55 : width / 2 - 0.45 - (3 - c) * 0.55);
            out.add(new double[]{x, z0 - r * rowPitch});
        }
        return out;
    }

    public List<Part> model(String color) {
        List<Part> p = new ArrayList<>();
        double L = length, W = width, H = height;
        String glass = "minecraft:light_blue_stained_glass", tyre = "minecraft:black_concrete", body = "minecraft:" + color + "_concrete";
        switch (this) {
            case BICYCLE -> {
                p.add(new Part(0, 0.35, 0.65, 0.08, 0.7, 0.08, tyre, "wheel"));
                p.add(new Part(0, 0.35, -0.65, 0.08, 0.7, 0.08, tyre, "wheel"));
                p.add(new Part(0, 0.6, 0, 0.06, 0.06, 1.2, body));
                p.add(new Part(0, 0.85, -0.2, 0.25, 0.06, 0.3, "minecraft:black_wool", "seat"));
                p.add(new Part(0, 1.0, 0.55, 0.55, 0.05, 0.05, "minecraft:iron_block"));
            }
            case TRAIN -> {
                p.add(new Part(0, 0.6, 0, W, 0.4, L, "minecraft:gray_concrete"));
                p.add(new Part(0, 0.8 + (H - 0.8) / 2, 0, W, H - 0.8, L, body));
                p.add(new Part(-W / 2 - 0.01, 2.2, 0, 0.02, 0.8, L - 2, glass, "window"));
                p.add(new Part(W / 2 + 0.01, 2.2, 0, 0.02, 0.8, L - 2, glass, "window"));
                p.add(new Part(0, 2.3, L / 2 + 0.01, W - 0.6, 0.9, 0.02, glass, "window"));
                for (double z : new double[]{-L / 2 + 3, L / 2 - 3}) p.add(new Part(0, 0.3, z, W - 0.4, 0.6, 2.4, tyre, "bogie"));
            }
            case FERRY, BOAT -> {
                p.add(new Part(0, 0.5, 0, W, 1.0, L, this == FERRY ? "minecraft:white_concrete" : body, "hull"));
                p.add(new Part(0, 0.5, L / 2 + 0.6, W * 0.6, 0.9, 1.2, this == FERRY ? "minecraft:white_concrete" : body, "bow"));
                p.add(new Part(0, 1.05, 0, W - 0.2, 0.1, L - 0.2, "minecraft:spruce_planks", "deck"));
                if (this == FERRY) {
                    p.add(new Part(0, 2.4, -2, W - 2, 2.6, L * 0.5, "minecraft:white_concrete", "cabin"));
                    p.add(new Part(0, 2.6, -2, W - 1.98, 0.8, L * 0.5 - 0.4, glass, "window"));
                    p.add(new Part(0, 4.5, -4, 1.2, 1.6, 1.2, "minecraft:blue_concrete", "funnel"));
                } else p.add(new Part(0, 1.6, 0.8, W - 0.3, 0.8, 0.1, glass, "window"));
            }
            default -> {
                double chassis = 0.45;
                p.add(new Part(0, chassis + 0.35, 0, W, 0.7, L, body, "body"));
                boolean box = this == BUS || this == DELIVERY_VAN;
                double cabinLen = box ? L - 0.4 : this == PICKUP ? L * 0.38 : L * 0.55;
                double cabinZ = box ? 0 : this == PICKUP ? L * 0.08 : -L * 0.05;
                double cabinH = H - chassis - 0.7;
                p.add(new Part(-W / 2 + 0.05, chassis + 0.7 + cabinH / 2, cabinZ, 0.1, cabinH, cabinLen, glass, "window"));
                p.add(new Part(W / 2 - 0.05, chassis + 0.7 + cabinH / 2, cabinZ, 0.1, cabinH, cabinLen, glass, "window"));
                p.add(new Part(0, chassis + 0.7 + cabinH / 2, cabinZ + cabinLen / 2 - 0.05, W - 0.2, cabinH, 0.1, glass, "windshield"));
                p.add(new Part(0, H - 0.05, cabinZ, W, 0.1, cabinLen, body, "roof"));
                if (this == TAXI) p.add(new Part(0, H + 0.12, cabinZ, 0.6, 0.2, 0.3, "minecraft:yellow_concrete", "sign"));
                if (box) for (int i = 0; i < 4; i++) p.add(new Part(-W / 2 + 0.05, chassis + 0.7 + cabinH * 0.6, cabinZ - cabinLen / 2 + 1 + i * (cabinLen - 2) / 3, 0.12, cabinH * 0.2, 0.15, body, "pillar"));
                for (int sx = -1; sx <= 1; sx += 2) for (double z : new double[]{L / 2 - 0.9, -L / 2 + 0.9}) {
                    p.add(new Part(sx * (W / 2 - 0.15), 0.38, z, 0.3, 0.76, 0.76, tyre, "wheel"));
                }
                p.add(new Part(-W / 2 + 0.35, chassis + 0.45, L / 2 + 0.01, 0.35, 0.18, 0.02, "minecraft:sea_lantern", "headlight"));
                p.add(new Part(W / 2 - 0.35, chassis + 0.45, L / 2 + 0.01, 0.35, 0.18, 0.02, "minecraft:sea_lantern", "headlight"));
                p.add(new Part(-W / 2 + 0.3, chassis + 0.45, -L / 2 - 0.01, 0.3, 0.15, 0.02, "minecraft:redstone_block", "taillight"));
                p.add(new Part(W / 2 - 0.3, chassis + 0.45, -L / 2 - 0.01, 0.3, 0.15, 0.02, "minecraft:redstone_block", "taillight"));
            }
        }
        for (double[] s : seatPositions()) if (this != BICYCLE)
            p.add(new Part(s[0], (this == TRAIN ? 0.8 : this == FERRY || this == BOAT ? 1.1 : 0.45) + 0.25, s[1], 0.5, 0.5, 0.5, "minecraft:black_wool", "seat"));
        return p;
    }
}
