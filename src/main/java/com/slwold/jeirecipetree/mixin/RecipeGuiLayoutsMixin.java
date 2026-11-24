package com.slwold.jeirecipetree.mixin;

import com.slwold.jeirecipetree.client.SafeConfig;
import com.slwold.jeirecipetree.client.DirectoryIngredientElement;
import com.slwold.jeirecipetree.client.JeiReflectionCompat;
import mezz.jei.api.gui.inputs.RecipeSlotUnderMouse;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.gui.input.IClickableIngredientInternal;
import mezz.jei.gui.recipes.RecipeGuiLayouts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Optional;

@Mixin(value = RecipeGuiLayouts.class, remap = false)
public abstract class RecipeGuiLayoutsMixin {

    @Inject(
        method = "getClickedIngredient",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private static void jert$directoryClick(
        RecipeSlotUnderMouse slotUnderMouse,
        CallbackInfoReturnable<Optional<IClickableIngredientInternal<?>>> cir
    ) {
        if (!SafeConfig.recipeIngredientDirectoryEnabled()) {
            return;
        }
        List<ITypedIngredient<?>> ingredients = slotUnderMouse.slot().getAllIngredients().toList();
        if (ingredients.size() <= 1) {
            return;
        }

        slotUnderMouse.slot().getDisplayedIngredient().ifPresent(displayed -> {
            cir.setReturnValue(Optional.of(JeiReflectionCompat.clickableIngredient(
                new DirectoryIngredientElement(displayed, ingredients),
                slotUnderMouse::isMouseOver,
                false,
                true
            )));
        });
    }
}
