package com.slwold.jeirecipetree.client;

import mezz.jei.gui.bookmarks.IBookmark;
import net.minecraft.ChatFormatting;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class TreeRow implements TreeRowAccess {

    private final IBookmark bookmark;
    private final ITypedIngredient<?> typed;
    private final RecipeTreeData.CraftStep step;
    private final RecipeTreeData.Cost cost;
    private final boolean intermediate;

    public TreeRow(
        IBookmark bookmark,
        ITypedIngredient<?> typed,
        RecipeTreeData.CraftStep step,
        RecipeTreeData.Cost cost,
        boolean intermediate
    ) {
        this.bookmark = bookmark;
        this.typed = typed;
        this.step = step;
        this.cost = cost;
        this.intermediate = intermediate;
    }

    public IBookmark bookmark() {
        return bookmark;
    }

    public ITypedIngredient<?> typed() {
        return typed;
    }

    public RecipeTreeData.CraftStep step() {
        return step;
    }

    public RecipeTreeData.Cost cost() {
        return cost;
    }

    public boolean intermediate() {
        return intermediate;
    }

    @Override
    public ITypedIngredient<?> typedIngredient() {
        return typed;
    }

    public ItemStack sourceStack() {
        return step == null
            ? (cost == null ? ItemStack.EMPTY : cost.stack())
            : step.stack();
    }

    public long total() {
        return step == null ? cost.wanted() : step.total();
    }

    public long owned() {
        Map<String, Long> supply = SupplyLedger.planningAmounts();
        return step == null
            ? SupplyLedger.inventoryAmount(cost.alternatives(), supply)
            : SupplyLedger.inventoryAmount(step.alternatives(), supply);
    }

    public long remainingDisplayAmount() {
        long missing;
        if (step == null) {
            missing = cost.required();
        } else if (SupplyLedger.craftingMode()) {
            missing = step.remaining();
        } else {
            missing = total() - owned();
        }
        ItemStack source = sourceStack();
        if (IngredientDisplay.measured(source)) {
            return Math.max(0L, FluidRecipeCompat.amountForUnits(source, missing));
        }
        return Math.max(0L, missing);
    }

    public String quantityText(long units) {
        ItemStack source = sourceStack();
        if (IngredientDisplay.measured(source)) {
            return FluidRecipeCompat.formatAmount(FluidRecipeCompat.amountForUnits(source, units));
        }
        return amountText(units);
    }

    public String remainingText(long amount) {
        ItemStack source = sourceStack();
        return IngredientDisplay.measured(source)
            ? FluidRecipeCompat.formatAmount(amount)
            : quantityText(amount);
    }

    public List<ItemStack> tooltipAlternatives() {
        List<ItemStack> source = step == null
            ? (cost == null ? List.of() : cost.alternatives())
            : step.alternatives();
        return source.stream()
            .filter(stack -> stack != null && !stack.isEmpty())
            .map(stack -> {
                ItemStack copy = stack.copy();
                copy.setCount(1);
                return copy;
            })
            .toList();
    }

    @Override
    public List<Component> tooltipLines() {
        List<Component> lines = new ArrayList<>();
        ItemStack source = sourceStack();
        lines.add(source.isEmpty() ? Component.literal("?") : source.getHoverName());
        lines.add(Component.translatable("jeirecipetree.recipe_tree.favorite.remaining",
            remainingText(remainingDisplayAmount())).withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("jeirecipetree.recipe_tree.favorite.obtained",
            quantityText(owned())).withStyle(ChatFormatting.GRAY));
        if (step != null) {
            lines.add(Component.translatable("jeirecipetree.recipe_tree.favorite.lookup")
                .withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable(intermediate
                    ? "jeirecipetree.recipe_tree.favorite.click_intermediate"
                    : "jeirecipetree.recipe_tree.favorite.click")
                .withStyle(ChatFormatting.AQUA));
            if (SafeConfig.automaticCraftingEnabled()) {
                lines.add(Component.translatable("jeirecipetree.recipe_tree.favorite.control_click")
                    .withStyle(ChatFormatting.AQUA));
            }
        }
        return lines;
    }

    @Override
    public boolean canTransfer(boolean recursive) {
        return step != null && RecipeTreeTransfer.canTransfer(step, recursive);
    }

    @Override
    public boolean transfer(boolean recursive) {
        if (!canTransfer(recursive)) {
            return false;
        }
        RecipeTreeTransfer.transfer(step, recursive);
        return true;
    }

    private String amountText(long units) {
        ItemStack source = sourceStack();
        if (IngredientDisplay.measured(source)) {
            return FluidRecipeCompat.formatAmount(FluidRecipeCompat.amountForUnits(source, units));
        }
        return RecipeTreeFavorites.amount(units);
    }
}
