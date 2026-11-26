package com.slwold.jeirecipetree.client.jei;

import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.storage.*;

import mezz.jei.api.ingredients.ITypedIngredient;

import java.util.List;

public record DirectoryRecipe(List<ITypedIngredient<?>> ingredients) {
    public DirectoryRecipe {
        ingredients = List.copyOf(ingredients);
    }
}
