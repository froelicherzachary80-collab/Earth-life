package earthlife.mod.mixin.mcef;

import earthlife.mod.client.cinema.BrowserScreens;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.Rectangle;
import java.nio.ByteBuffer;

/**
 * MCEF Modern paints each browser frame (BGRA) in onPaint. ZMF Cinema copies the frames of its own web-cinema browsers
 * (throttled) so the page can be shown on the in-world screen and fed to the 3D conversion and 4DX analysis. Only
 * applied when MCEF Modern is installed (see {@link McefPlugin}); the browser type is coerced so EarthLife doesn't need
 * MCEF or JCEF to compile.
 */
@Pseudo
@Mixin(targets = "net.dimaskama.mcef.impl.MCEFBrowserImpl", remap = false)
public abstract class McefPaintMixin {
    @Inject(method = "onPaint", at = @At("HEAD"), remap = false, require = 0)
    private void earthlife$paint(@Coerce Object browser, boolean popup, Rectangle[] dirty, ByteBuffer buffer, int width, int height, CallbackInfo ci) {
        if (!popup) BrowserScreens.onPaint(this, buffer, width, height);
    }
}
