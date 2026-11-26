package com.slwold.jeirecipetree.client.transfer;

import com.slwold.jeirecipetree.client.data.*;
import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;
import com.slwold.jeirecipetree.client.storage.*;
import com.slwold.jeirecipetree.core.CraftQueue;
import com.slwold.jeirecipetree.core.TransferPhase;
import com.slwold.jeirecipetree.compat.sia.SiaMaterialBridge;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferInfo;
import mezz.jei.api.recipe.transfer.IRecipeTransferManager;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.common.Internal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.tags.TagKey;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class TransferFillPlan {
    private TransferFillPlan() {
    }

    public static int transferChunk(RecipeTreeData.CraftStep step) {
        return transferChunk(step, step == null ? 0 : step.batches(), false);
    }

    public static Optional<ItemStack> selectedInput(
        IRecipeSlotView slot,
        String selectedKey,
        boolean recursiveCandidates
    ) {
        String preferred = preferredInputKey(slot, selectedKey);
        return slot.getItemStacks()
            .filter(stack -> !stack.isEmpty())
            .filter(stack -> preferred.isEmpty()
                || RecipeTreeData.ingredientKey(stack).equals(preferred))
            .findFirst()
            .map(ItemStack::copy);
    }

    public static int transferChunk(
        RecipeTreeData.CraftStep step,
        long requested,
        boolean recursiveCandidates
    ) {
        if (step == null || requested <= 0) {
            return 0;
        }
        IRecipeLayoutDrawable<?> layout = RecipeLookup.createLayout(step.recipe()).orElse(null);
        if (layout == null) {
            return 0;
        }
        int capacity = maxBatchesPerTransfer(
            layout.getRecipeSlotsView(),
            step.selectedInputs(),
            recursiveCandidates
        );
        return (int) Math.max(1, Math.min(Math.min(Integer.MAX_VALUE, requested), capacity));
    }

    public static int maxBatchesForInstantInputs(
        AbstractContainerMenu menu,
        RecipeTreeData.CraftStep step,
        List<String> selectedInputs,
        boolean recursiveCandidates
    ) {
        if (menu == null || step == null || Ae2StorageIntegration.isCraftingMenu(menu)
            || TomsStorageDriver.isCraftingTerminalMenu(menu)) {

            return 1;
        }
        IRecipeLayoutDrawable<?> layout = RecipeLookup.createLayout(step.recipe()).orElse(null);
        if (layout == null) {
            return 1;
        }

        List<IRecipeSlotView> inputs = layout.getRecipeSlotsView().getSlotViews().stream()
            .filter(slot -> slot.getRole() == RecipeIngredientRole.INPUT)
            .toList();
        if (inputs.isEmpty()) {
            return 1;
        }

        List<Slot> recipeSlots = null;
        try {
            IRecipeTransferManager manager = Internal.getJeiRuntime().getRecipeTransferManager();
            Optional<IRecipeTransferHandler<AbstractContainerMenu, Object>> handler = (Optional) manager
                .getRecipeTransferHandler(menu, (IRecipeCategory) layout.getRecipeCategory());
            if (handler.isPresent()) {
                recipeSlots = findRecipeSlots(handler.get(), menu, layout.getRecipe());
            }
        } catch (RuntimeException ignored) {

        }

        if (recipeSlots != null && inputs.size() <= recipeSlots.size()) {
            int result = Integer.MAX_VALUE;
            boolean foundItemInput = false;
            for (int index = 0; index < inputs.size(); index++) {
                if (inputs.get(index).isEmpty()) {

                    continue;
                }
                Optional<ItemStack> input = selectedInput(
                    inputs.get(index),
                    index < selectedInputs.size() ? selectedInputs.get(index) : "",
                    recursiveCandidates
                );
                if (input.isEmpty()) {

                    return 1;
                }
                foundItemInput = true;
                ItemStack stack = input.get();
                int perBatch = Math.max(1, stack.getCount());
                int slotCapacity = recipeSlots.get(index).getMaxStackSize(stack);
                result = Math.min(result, Math.max(1, slotCapacity / perBatch));
            }
            return foundItemInput ? Math.max(1, result) : 1;
        }

        if (menu instanceof CraftingMenu || menu instanceof InventoryMenu) {
            return Integer.MAX_VALUE;
        }

        int result = Integer.MAX_VALUE;
        boolean foundItemInput = false;
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return 1;
        }
        for (int inputIndex = 0; inputIndex < inputs.size(); inputIndex++) {
            IRecipeSlotView inputView = inputs.get(inputIndex);
            if (inputView.isEmpty()) {
                continue;
            }
            Optional<ItemStack> input = selectedInput(
                inputView,
                inputIndex < selectedInputs.size() ? selectedInputs.get(inputIndex) : "",
                recursiveCandidates
            );
            if (input.isEmpty()) {
                return 1;
            }
            foundItemInput = true;
            ItemStack stack = input.get();
            int perBatch = Math.max(1, stack.getCount());
            boolean foundSlot = false;
            for (Slot slot : menu.slots) {
                if (slot.container == player.getInventory() || !slot.mayPlace(stack)) {
                    continue;
                }
                foundSlot = true;
                int slotCapacity = slot.getMaxStackSize(stack);
                result = Math.min(result, Math.max(1, slotCapacity / perBatch));
            }
            if (!foundSlot) {
                return 1;
            }
        }
        return foundItemInput ? Math.max(1, result) : 1;
    }

    public static List<Slot> findRecipeSlots(
        IRecipeTransferHandler handler,
        AbstractContainerMenu menu,
        Object recipe
    ) {
        for (Class<?> type = handler.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!IRecipeTransferInfo.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.trySetAccessible();
                    IRecipeTransferInfo info = (IRecipeTransferInfo) field.get(handler);
                    return List.copyOf(info.getRecipeSlots(menu, recipe));
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    public static IRecipeSlotsView adjustInputs(
        IRecipeSlotsView original,
        List<String> selectedInputs,
        int batches,
        boolean recursiveCandidates,
        Player player
    ) {
        List<IRecipeSlotView> adjusted = new ArrayList<>();
        int inputIndex = 0;
        for (IRecipeSlotView slot : original.getSlotViews()) {
            if (slot.getRole() == RecipeIngredientRole.INPUT) {
                String selected = preferredInputKey(
                    slot,
                    inputIndex < selectedInputs.size() ? selectedInputs.get(inputIndex) : ""
                );
                adjusted.add(new AdjustedSlot(slot, selected, batches, player));
                inputIndex++;
            } else {
                adjusted.add(slot);
            }
        }
        List<IRecipeSlotView> immutable = List.copyOf(adjusted);
        return () -> immutable;
    }

    public static int maxBatchesPerTransfer(
        IRecipeSlotsView slots,
        List<String> selectedInputs,
        boolean recursiveCandidates
    ) {
        int result = Integer.MAX_VALUE;
        int inputIndex = 0;
        boolean foundItemInput = false;
        for (IRecipeSlotView slot : slots.getSlotViews()) {
            if (slot.getRole() != RecipeIngredientRole.INPUT) {
                continue;
            }
            String selected = preferredInputKey(
                slot,
                inputIndex < selectedInputs.size() ? selectedInputs.get(inputIndex) : ""
            );
            inputIndex++;
            int slotCapacity = RecipeLookup.candidateStacks(slot).stream()
                .filter(stack -> selected.isEmpty() || RecipeTreeData.ingredientKey(stack).equals(selected))
                .mapToInt(stack -> Math.max(1, stack.getMaxStackSize() / Math.max(1, stack.getCount())))
                .max()
                .orElse(Integer.MAX_VALUE);
            if (slotCapacity != Integer.MAX_VALUE) {
                foundItemInput = true;
                result = Math.min(result, slotCapacity);
            }
        }
        return foundItemInput ? Math.max(1, result) : 1;
    }

    public static String preferredInputKey(IRecipeSlotView slot, String selectedKey) {
        if (!selectedKey.isEmpty()) {
            return selectedKey;
        }

        return RecipeTreeFavorites.bookmarkedCandidate(RecipeLookup.candidateStacks(slot))
            .map(RecipeTreeData::ingredientKey)
            .orElse("");
    }

    private static final class AdjustedSlot implements IRecipeSlotView {
        private final IRecipeSlotView delegate;
        private final List<ITypedIngredient<?>> ingredients;

        private AdjustedSlot(IRecipeSlotView delegate, String selectedKey, int batches, Player player) {
            this.delegate = delegate;
            IJeiRuntime runtime = DirectoryRecipePlugin.getJeiRuntime();
            List<ITypedIngredient<?>> adjusted = new ArrayList<>();
            if (runtime != null) {
                for (ITypedIngredient<?> ingredient : delegate.getAllIngredientsList()) {
                    if (ingredient == null) {
                        continue;
                    }
                    Optional<ItemStack> itemStack = ingredient.getItemStack();
                    if (itemStack.isEmpty()) {

                        Optional<net.neoforged.neoforge.fluids.FluidStack> fluid =
                            FluidRecipeCompat.fluid(ingredient);
                        if (fluid.isPresent()) {
                            String fluidKey = FluidRecipeCompat.fluidKey(fluid.get());
                            if (selectedKey.isEmpty() || selectedKey.equals(fluidKey)) {
                                FluidRecipeCompat.scaled(ingredient, batches, runtime.getIngredientManager())
                                    .ifPresent(adjusted::add);
                            }

                            for (ItemStack container : FluidRecipeCompat.matchingContainers(player, fluid.get(), batches)) {
                                if (selectedKey.isEmpty()
                                    || RecipeTreeData.ingredientKey(container).equals(selectedKey)) {
                                    runtime.getIngredientManager()
                                        .createTypedIngredient(VanillaTypes.ITEM_STACK, container)
                                        .ifPresent(adjusted::add);
                                }
                            }
                        } else {
                            adjusted.add(ingredient);
                        }
                        continue;
                    }
                    ItemStack stack = itemStack.get();
                    if (!selectedKey.isEmpty() && !RecipeTreeData.ingredientKey(stack).equals(selectedKey)) {
                        continue;
                    }
                    ItemStack scaled = stack.copy();
                    long count = (long) Math.max(1, stack.getCount()) * batches;
                    if (count > stack.getMaxStackSize()) {
                        continue;
                    }
                    scaled.setCount((int) count);
                    runtime.getIngredientManager()
                        .createTypedIngredient(VanillaTypes.ITEM_STACK, scaled)
                        .ifPresent(adjusted::add);
                }
            }
            this.ingredients = adjusted.isEmpty()
                ? delegate.getAllIngredients().toList()
                : List.copyOf(adjusted);
        }

        @Override
        public Stream<ITypedIngredient<?>> getAllIngredients() {
            return ingredients.stream();
        }

        @Override
        public List<ITypedIngredient<?>> getAllIngredientsList() {
            return ingredients;
        }

        @Override
        public Optional<ITypedIngredient<?>> getDisplayedIngredient() {
            return ingredients.stream().findFirst();
        }

        public Stream<ITypedIngredient<?>> getDisplayedIngredients() {
            return ingredients.stream();
        }

        public Optional<TagKey<?>> getTagKey() {
            return Optional.empty();
        }

        @Override
        public RecipeIngredientRole getRole() {
            return delegate.getRole();
        }

        @Override
        public void drawHighlight(GuiGraphics guiGraphics, int color) {
            delegate.drawHighlight(guiGraphics, color);
        }

        @Override
        public Optional<String> getSlotName() {
            return delegate.getSlotName();
        }
    }

}
