package com.slwold.jeirecipetree.client;

import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IRecipesGui;
import mezz.jei.gui.overlay.elements.IngredientElement;
import mezz.jei.gui.util.FocusUtil;

import java.util.List;

@SuppressWarnings({"rawtypes", "unchecked"})
public final class DirectoryIngredientElement extends IngredientElement<Object> {
    private final List<ITypedIngredient<?>> ingredients;

    public DirectoryIngredientElement(ITypedIngredient<?> displayedIngredient, List<ITypedIngredient<?>> ingredients) {
        super((ITypedIngredient<Object>) (ITypedIngredient) displayedIngredient);
        this.ingredients = List.copyOf(ingredients);
    }

    @Override
    public void show(IRecipesGui recipesGui, FocusUtil focusUtil, List<RecipeIngredientRole> roles) {
        if (SafeConfig.recipeIngredientDirectoryEnabled()
            && roles.contains(RecipeIngredientRole.OUTPUT)) {
            DirectoryViewer.show(recipesGui, ingredients);
        } else {
            super.show(recipesGui, focusUtil, roles);
        }
    }
}
