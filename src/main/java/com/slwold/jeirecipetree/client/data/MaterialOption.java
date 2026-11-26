package com.slwold.jeirecipetree.client.data;
import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;
import com.slwold.jeirecipetree.client.storage.*;
import com.slwold.jeirecipetree.client.transfer.*;
import com.slwold.jeirecipetree.client.ui.*;

import com.slwold.jeirecipetree.core.ItemRef;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.world.item.ItemStack;

public record MaterialOption(ItemRef ref, long amount, ItemStack display, ITypedIngredient<?> typed) {

    public MaterialOption {
        if (ref == null) {
            throw new IllegalArgumentException("MaterialOption requires an identity");
        }
        amount = Math.max(0L, amount);

        display = display == null ? ItemStack.EMPTY : FluidRecipeCompat.copyWithDisplay(display);
    }

    static final MaterialOption EMPTY =
        new MaterialOption(ItemRef.of("minecraft:air"), 0L, ItemStack.EMPTY, null);

    static MaterialOption of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return EMPTY;
        }
        return new MaterialOption(
            ItemKeys.of(stack),
            IngredientDisplay.amountOf(stack),
            stack,
            IngredientDisplay.sourceOf(stack).orElse(null)
        );
    }

    static MaterialOption of(ITypedIngredient<?> ingredient) {
        if (ingredient == null) {
            return EMPTY;
        }
        String key = IngredientDisplay.key(ingredient);
        if (key.isEmpty()) {
            return EMPTY;
        }
        return new MaterialOption(
            ItemRef.of(key),
            IngredientDisplay.amount(ingredient),
            IngredientDisplay.display(ingredient).orElse(ItemStack.EMPTY),
            ingredient
        );
    }

    boolean isPresent() {
        return amount > 0L || !display.isEmpty();
    }

    boolean measured() {
        return ref.isFluid() || ref.isChemical();
    }

    MaterialOption withAmount(long newAmount) {
        return new MaterialOption(ref, newAmount, display, typed);
    }

}
