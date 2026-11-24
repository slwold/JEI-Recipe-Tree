package com.slwold.jeirecipetree.mixin;

import com.slwold.jeirecipetree.client.SafeConfig;
import com.slwold.jeirecipetree.client.DirectoryIngredientElement;
import com.slwold.jeirecipetree.client.JeiReflectionCompat;
import mezz.jei.api.gui.inputs.RecipeSlotUnderMouse;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.gui.input.IClickableIngredientInternal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Optional;

@Pseudo
@Mixin(
    targets = {
        "mezz.jei.gui.recipes.RecipeSlotClickTargetFactory",
        "mezz.jei.common.input.RecipeSlotClickTargetFactory"
    },
    remap = false
)
public abstract class RecipeSlotClickTargetFactoryMixin {
    @Inject(
        method = {
            "create(Lmezz/jei/api/gui/inputs/RecipeSlotUnderMouse;Lmezz/jei/gui/input/IMouseOverable;)Ljava/util/Optional;",
            "create(Lmezz/jei/api/gui/inputs/RecipeSlotUnderMouse;Lmezz/jei/common/input/IMouseOverable;)Ljava/util/Optional;"
        },
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private void jert$directoryClick(
        RecipeSlotUnderMouse slotUnderMouse,
        @Coerce Object mouseOverable,
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
                JeiReflectionCompat.mouseOverPredicate(mouseOverable),
                false,
                true
            )));
        });
    }
}
