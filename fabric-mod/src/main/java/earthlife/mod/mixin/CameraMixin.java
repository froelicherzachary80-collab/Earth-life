package earthlife.mod.mixin;

import earthlife.mod.client.cinema.FourD;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** 4D motion seats: adds the seat's sway/rumble to the view direction (zero unless you sit in a running 4D film). */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @ModifyVariable(method = "setRotation", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float earthlife$seatYaw(float yRot) { return yRot + FourD.yawOffset(); }

    @ModifyVariable(method = "setRotation", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private float earthlife$seatPitch(float xRot) { return xRot + FourD.pitchOffset(); }
}
