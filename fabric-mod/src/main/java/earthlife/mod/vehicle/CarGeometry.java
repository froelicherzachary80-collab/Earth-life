package earthlife.mod.vehicle;

import earthlife.core.transport.VehicleType;

/**
 * Body dimensions of the modelled cars, in model pixels (1/16 m), shared by the server (seats, doors, getting out)
 * and the client model. Model axes: +Z forward, +X to the car's left (driver's side in the US), +Y up, origin on
 * the ground under the centre of the car.
 *
 * The cabins are a little taller than a real sedan (1.6 m) because a Minecraft player is 1.8 m with a long torso:
 * sitting on a low cushion, the head just clears the roof and the eyes are at ~1.27 m.
 */
public record CarGeometry(float length, float width, float wheelR, float wheelW, float wheelZ, float bodyBottom, float beltY,
                          float hoodTopY, float windBaseZ, float windBaseY, float roofFrontZ, float roofY, float roofRearZ,
                          float rearBaseZ, float rearBaseY, float deckY, float bPillarZ, float hipY, float frontSeatZ,
                          float rearSeatZ, float seatX, boolean pickup) {

    public static final CarGeometry SEDAN = new CarGeometry(70, 29, 5.5f, 3.4f, 21.5f, 6, 13.5f,
            13.8f, 13, 14.5f, 4, 25.6f, -15, -22, 15, 15.2f, -4.5f, 5.6f, 0, -12, 6.7f, false);
    public static final CarGeometry PICKUP = new CarGeometry(86, 31, 6.5f, 4, 27, 8, 18.5f,
            18, 22, 19, 14, 31, -12, -12.5f, 19, 18.5f, 2, 12, 8, -5, 7.2f, true);

    public static CarGeometry of(VehicleType t) { return t == VehicleType.PICKUP ? PICKUP : SEDAN; }

    static final float PX = 1 / 16f;
    public static double bPillarM(VehicleType t) { return of(t).bPillarZ * PX; }
    public static double frontSeatM(VehicleType t) { return of(t).frontSeatZ * PX; }
    public static double rearSeatM(VehicleType t) { return of(t).rearSeatZ * PX; }
    public static double seatXM(VehicleType t) { return of(t).seatX * PX; }
    public static double hipM(VehicleType t) { return of(t).hipY * PX; }
    public static double wheelRadiusM(VehicleType t) { return clientModel(t) ? of(t).wheelR * PX : 0.38; }

    private static boolean clientModel(VehicleType t) { return VehicleEntity.clientModel(t); }
}
