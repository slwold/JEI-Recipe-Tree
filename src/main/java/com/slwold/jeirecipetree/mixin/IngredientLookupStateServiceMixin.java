package com.slwold.jeirecipetree.mixin;

import com.slwold.jeirecipetree.client.jei.RecipeBookmarkNavigationContext;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.common.transfer.RecipeTransferService;
import mezz.jei.gui.recipes.lookups.ILookupState;
import mezz.jei.gui.recipes.lookups.IngredientLookupState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(value = IngredientLookupState.class, remap = false)
public abstract class IngredientLookupStateServiceMixin {
    @Inject(method = "create", at = @At("RETURN"), remap = false, require = 0)
    private static void jert$bookmarkCategoryFirst(
        IRecipeManager recipeManager,
        IFocusGroup focusGroup,
        java.util.List<IRecipeCategory<?>> recipeCategories,
        RecipeTransferService recipeTransferService,
        CallbackInfoReturnable<ILookupState> cir
    ) {
        Optional<IRecipeCategory<?>> category = RecipeBookmarkNavigationContext.category();
        if (category.isPresent()) {
            cir.getReturnValue().moveToRecipeCategory(category.get());
        }
    }
}
