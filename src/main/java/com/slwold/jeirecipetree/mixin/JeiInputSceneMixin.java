package com.slwold.jeirecipetree.mixin;

import com.slwold.jeirecipetree.client.jei.JeiInputAreaCompat;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;
import java.util.Optional;

@Pseudo
@Mixin(targets = "mezz.jei.gui.input.GuiInputScene", remap = false)
public abstract class JeiInputSceneMixin {
    @Shadow @Final private List<?> inputAreas;

    @Inject(
        method = "findTarget(DD)Ljava/util/Optional;",
        at = @At("RETURN"),
        cancellable = true,
        remap = false
    )
    private void jert$extraAreas(double x, double y, CallbackInfoReturnable<Optional<?>> cir) {
        cir.setReturnValue(JeiInputAreaCompat.selectTarget(inputAreas, cir.getReturnValue(), x, y));
    }
}
