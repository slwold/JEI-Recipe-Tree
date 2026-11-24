package com.slwold.jeirecipetree.client;

import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.network.chat.Component;

import java.util.List;

public interface TreeRowAccess {

    ITypedIngredient<?> typedIngredient();

    List<Component> tooltipLines();

    boolean canTransfer(boolean recursive);

    boolean transfer(boolean recursive);
}
