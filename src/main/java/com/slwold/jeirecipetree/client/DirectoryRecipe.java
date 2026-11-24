package com.slwold.jeirecipetree.client;

import mezz.jei.api.ingredients.ITypedIngredient;

import java.util.List;

public record DirectoryRecipe(List<ITypedIngredient<?>> ingredients) {
    public DirectoryRecipe {
        ingredients = List.copyOf(ingredients);
    }
}
