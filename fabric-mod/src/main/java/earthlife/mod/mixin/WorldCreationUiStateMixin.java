package earthlife.mod.mixin;

import earthlife.mod.client.screen.EarthSettingsScreen;
import net.minecraft.client.gui.screens.worldselection.PresetEditor;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives the "Earth - 1:1 Real World" world type a working Customize button. Vanilla's PresetEditor.EDITORS map is
 * immutable, so the editor lookup is extended here instead (only for the earthlife:earthlife preset).
 */
@Mixin(WorldCreationUiState.class)
public abstract class WorldCreationUiStateMixin {
    @Inject(method = "getPresetEditor", at = @At("HEAD"), cancellable = true)
    private void earthlife$earthEditor(CallbackInfoReturnable<PresetEditor> cir) {
        WorldCreationUiState self = (WorldCreationUiState) (Object) this;
        var entry = self.getWorldType();
        if (entry != null && entry.preset() != null && entry.preset().is(EarthSettingsScreen.EARTH_PRESET)) cir.setReturnValue(EarthSettingsScreen.EDITOR);
    }
}
