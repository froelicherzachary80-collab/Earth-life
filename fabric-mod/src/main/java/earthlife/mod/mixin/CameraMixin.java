package earthlife.mod.mixin;

import earthlife.mod.client.cinema.FourD;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 4D / 4DX motion seats: the seat's pitch and vibration move the view direction, and its sideways roll tilts the view
 * (all zero unless you sit in a motion seat while a film plays).
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @ModifyVariable(method = "setRotation", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float earthlife$seatYaw(float yRot) { return yRot + FourD.yawOffset(); }

    @ModifyVariable(method = "setRotation", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private float earthlife$seatPitch(float xRot) { return xRot + FourD.pitchOffset(); }

    /** Roll: rotate the camera quaternion around its forward axis (optional - skipped if the method changes). */
    @Inject(method = "rotation", at = @At("RETURN"), cancellable = true, require = 0)
    private void earthlife$seatRoll(CallbackInfoReturnable<org.joml.Quaternionf> cir) {
        float roll = FourD.rollOffset();
        if (roll == 0 || cir.getReturnValue() == null) return;
        cir.setReturnValue(new org.joml.Quaternionf(cir.getReturnValue()).rotateZ((float) Math.toRadians(roll)));
    }
}
