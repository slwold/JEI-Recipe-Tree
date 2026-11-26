package com.slwold.jeirecipetree.client.ui;
import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;

import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.renderer.Rect2i;

import java.util.ArrayList;
import java.util.List;

public final class EditDragHandler implements IGhostIngredientHandler<RecipeEditScreen> {

    @Override
    public <I> List<Target<I>> getTargetsTyped(
        RecipeEditScreen screen,
        ITypedIngredient<I> ingredient,
        boolean doStart
    ) {

        if (doStart) {
            DragHighlight.begin();
        }
        List<Target<I>> targets = new ArrayList<>();
        for (int[] rect : RecipeEditScreen.slotRects()) {
            targets.add(new SlotTarget<>(rect));
        }
        return targets;
    }

    @Override
    public void onComplete() {
    }

    private record SlotTarget<I>(int[] rect) implements Target<I> {

        @Override
        public Rect2i getArea() {
            return new Rect2i(rect[2], rect[3], RecipeEditPanel.SLOT, RecipeEditPanel.SLOT);
        }

        @Override
        public void accept(I ingredient) {
            RecipeEditScreen.acceptDrop(rect[0], rect[1], ingredient);
        }
    }
}
