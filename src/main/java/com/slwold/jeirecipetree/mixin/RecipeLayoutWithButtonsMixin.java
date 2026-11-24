package com.slwold.jeirecipetree.mixin;

import com.slwold.jeirecipetree.client.SafeConfig;
import mezz.jei.gui.recipes.RecipeLayoutWithButtons;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = RecipeLayoutWithButtons.class, remap = false)
public abstract class RecipeLayoutWithButtonsMixin {

    @Shadow @Final private List<?> buttons;

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void jert$removeNativeRecipeBookmarkButton(CallbackInfo ci) {
        if (!SafeConfig.hideRecipeBookmarkButton()) {
            return;
        }

        if (buttons.size() > 1) {
            try {
                buttons.remove(1);
            } catch (UnsupportedOperationException ignored) {

            }
        }
    }
}
