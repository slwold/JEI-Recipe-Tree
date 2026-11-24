package com.slwold.jeirecipetree.core;

public interface RecipeSource {

    Match match(ItemRef label);

    record Match(RecipeRecord recipe, int outputSlot) {

        public Match {
            if (recipe == null) {
                throw new IllegalArgumentException("match needs a recipe");
            }
            if (outputSlot < 0 || outputSlot >= recipe.outputs().size()) {
                throw new IllegalArgumentException("output slot out of range: " + outputSlot);
            }
        }
    }
}
