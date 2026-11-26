package com.slwold.jeirecipetree.mixin;

import com.slwold.jeirecipetree.client.jei.JeiInputAreaCompat;
import mezz.jei.gui.recipes.RecipesGui;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "mezz.jei.gui.recipes.RecipesGui$RecipeCategoryScrollInputHandler", remap = false)
public abstract class JeiRecipeNavigationMixin {
    @Shadow @Final private RecipesGui recipesGui;

    @Inject(method = "scroll", at = @At("HEAD"), cancellable = true, remap = false)
    private void jert$navigation(double x, double y, double dx, double dy, CallbackInfoReturnable<Boolean> cir) {
        if (recipesGui.isOpen() && JeiInputAreaCompat.scrollRecipeNavigation(recipesGui, x, y, dy)) {
            cir.setReturnValue(true);
        }
    }
}
