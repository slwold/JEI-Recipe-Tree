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

public final class TransferObservation {
    private TransferObservation() {
    }

    public static void dumpFilledSlots(AbstractContainerMenu menu, RecipeTreeData.CraftStep craft) {
        if (menu == null) {
            return;
        }
        StringBuilder filled = new StringBuilder();
        int nonPlayer = 0;
        for (Slot slot : menu.slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            nonPlayer++;
            if (!filled.isEmpty()) {
                filled.append(", ");
            }
            filled.append(slot.index).append(':')
                .append(stack.getHoverName().getString()).append('x').append(stack.getCount());
        }
        JERTLog.debug("transfer: probe timeout, menu={} target={} filled({})=[{}]",
            menu.getClass().getSimpleName(),
            craft == null ? "-" : craft.stack().getHoverName().getString(),
            nonPlayer,
            filled);
        dumpMissingIngredients(menu, craft);
    }

    public static void dumpMissingIngredients(AbstractContainerMenu menu, RecipeTreeData.CraftStep craft) {
        CraftingRecipe recipe = craft == null ? null : asCraftingRecipe(craft.recipe());
        if (recipe == null) {
            return;
        }
        List<Ingredient> needs = recipe.getIngredients();
        StringBuilder missing = new StringBuilder();
        for (int i = 0; i < needs.size() && i < 9; i++) {
            Ingredient need = needs.get(i);
            if (need == null || need.isEmpty()) {
                continue;
            }
            int slotIndex = 1 + i;
            ItemStack actual = slotIndex < menu.slots.size()
                ? menu.getSlot(slotIndex).getItem()
                : ItemStack.EMPTY;
            if (need.test(actual)) {
                continue;
            }
            if (!missing.isEmpty()) {
                missing.append(", ");
            }
            missing.append("slot").append(i + 1).append('=').append(firstOptionKey(need));
        }
        JERTLog.debug("transfer: recipe={} missing=[{}]", craft.recipe().registryId(), missing);
    }

    public static void logFillSnapshotOnce(AbstractContainerMenu menu) {
        if (RecipeTreeTransfer.step().fillLogged) {
            return;
        }
        boolean awaitingResult = RecipeTreeTransfer.step().ae2Carry || RecipeTreeTransfer.step().vanillaPickup
            || RecipeTreeTransfer.step().genericResultProbe || RecipeTreeTransfer.step().resultSlot >= 0;
        if (!awaitingResult) {
            return;
        }
        RecipeTreeTransfer.step().fillLogged = true;
        CraftQueue.Step<RecipeTreeData.CraftStep> craft = RecipeTreeTransfer.queue().current();
        String state = fillSummary(menu, craft == null ? null : craft.value());

        RecipeTreeTransfer.lastFillState = state;
        JERTLog.debug("transfer step fill  index={}/{} {} fillWaited={}fr",
            RecipeTreeTransfer.queue().index() + 1, RecipeTreeTransfer.queue().size(), state, RecipeTreeTransfer.step().fillWaitedFrames);
    }

    public static String recipeName(RecipeTreeData.RecipeRef ref) {
        if (ref == null) {
            return "-";
        }
        String id = ref.registryId();
        if (id != null && !id.isEmpty()) {
            return id;
        }
        String key = ref.key();
        return key == null || key.isEmpty() ? "-" : key;
    }

    public static String registryId(RecipeTreeData.RecipeRef ref) {
        if (ref == null) {
            return null;
        }
        Object raw = ref.recipe();
        if (raw instanceof RecipeHolder<?> holder) {
            String real = holder.id().toString();
            if (!real.equals(ref.registryId())) {
                com.slwold.jeirecipetree.client.JERTLog.debug(
                    "toms fill id: jei={} holder={}", ref.registryId(), real);
            }
            return real;
        }
        return ref.registryId();
    }

    public static CraftingRecipe asCraftingRecipe(RecipeTreeData.RecipeRef ref) {
        if (ref == null) {
            return null;
        }
        Object raw = ref.recipe();
        if (raw instanceof CraftingRecipe recipe) {
            return recipe;
        }
        if (raw instanceof RecipeHolder<?> holder && holder.value() instanceof CraftingRecipe recipe) {
            return recipe;
        }
        return null;
    }

    public static String fillSummary(AbstractContainerMenu menu, RecipeTreeData.CraftStep craft) {
        CraftingRecipe recipe = craft == null ? null : asCraftingRecipe(craft.recipe());
        if (menu == null || recipe == null) {
            return "grid=-";
        }
        List<Ingredient> needs = recipe.getIngredients();
        int total = 0;
        int matched = 0;
        StringBuilder missing = new StringBuilder();
        for (int i = 0; i < needs.size() && i < 9; i++) {
            Ingredient need = needs.get(i);
            if (need == null || need.isEmpty()) {
                continue;
            }
            total++;
            int slotIndex = 1 + i;
            ItemStack actual = slotIndex < menu.slots.size()
                ? menu.getSlot(slotIndex).getItem()
                : ItemStack.EMPTY;
            if (need.test(actual)) {
                matched++;
                continue;
            }
            if (!missing.isEmpty()) {
                missing.append(',');
            }
            missing.append("slot").append(i + 1).append('=').append(firstOptionKey(need));
        }
        return "grid=" + matched + "/" + total + " missing=[" + missing + "]";
    }

    public static String firstOptionKey(Ingredient ingredient) {
        ItemStack[] options = ingredient.getItems();
        if (options.length == 0) {
            return "?";
        }
        return net.minecraft.core.registries.BuiltInRegistries.ITEM
            .getKey(options[0].getItem()).toString();
    }

}
