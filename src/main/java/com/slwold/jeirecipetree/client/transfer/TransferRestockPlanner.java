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

public final class TransferRestockPlanner {
    private TransferRestockPlanner() {
    }

    public static List<StorageRestock.Need> needsForRestock(
        RecipeTreeData.CraftStep step,
        IRecipeLayoutDrawable<?> layout,
        int batches,
        AbstractContainerMenu menu,
        Player player,
        boolean doTransfer,
        boolean ae2Menu
    ) {
        if (ae2Menu) {
            return List.of();
        }

        if (RtsStorageDriver.isTerminalMenu(menu)) {
            return List.of();
        }

        if (StorageNetworkIntegration.isBeyondMenu(menu)) {
            return List.of();
        }

        if (doTransfer && RecipeTreeTransfer.step().restockDone) {

            return List.of();
        }
        return computeNeeds(layout, step.selectedInputs(), batches, menu, player);
    }

    public static boolean startRestock(
        RecipeTreeData.CraftStep craftStep,
        AbstractContainerMenu menu,
        int batches,
        boolean doTransfer,
        boolean recursiveCandidates,
        boolean maxTransfer,
        List<StorageRestock.Need> needs
    ) {

        if (!doTransfer || RecipeTreeTransfer.step().restockDone || needs.isEmpty()) {
            return false;
        }
        if (!StorageRestock.begin(menu, needs)) {
            return false;
        }
        RecipeTreeTransfer.step().restockDone = true;
        RecipeTreeTransfer.step().restockPending = true;
        RecipeTreeTransfer.step().restockTarget = craftStep;
        RecipeTreeTransfer.step().restockChunk = batches;
        RecipeTreeTransfer.step().restockRecursive = recursiveCandidates;
        RecipeTreeTransfer.step().restockMaxTransfer = maxTransfer;
        RecipeTreeTransfer.step().menuId = menu.containerId;
        RecipeTreeTransfer.step().waitFrames = 2;
        return true;
    }

    public static boolean canRestock(AbstractContainerMenu menu, List<StorageRestock.Need> needs) {
        if (StorageDriverRegistry.canWithdraw(menu)) {
            return coverable(menu, needs);
        }
        return SafeConfig.sophisticatedRestockEnabled() && SiaMaterialBridge.available();
    }

    public static List<StorageRestock.Need> computeNeeds(
        IRecipeLayoutDrawable<?> layout,
        List<String> selectedInputs,
        int batches,
        AbstractContainerMenu menu,
        Player player
    ) {
        if (layout == null || menu == null || player == null || batches <= 0) {
            return List.of();
        }
        Map<String, ItemStack> samples = new LinkedHashMap<>();
        Map<String, List<ItemStack>> groups = new LinkedHashMap<>();
        Map<String, Integer> wanted = new LinkedHashMap<>();
        int inputIndex = 0;
        for (IRecipeSlotView slot : layout.getRecipeSlotsView().getSlotViews()) {
            if (slot.getRole() != RecipeIngredientRole.INPUT) {
                continue;
            }
            String selected = TransferFillPlan.preferredInputKey(
                slot,
                inputIndex < selectedInputs.size() ? selectedInputs.get(inputIndex) : ""
            );
            inputIndex++;
            List<ItemStack> candidates = restockGroup(slot, selected);
            if (candidates.isEmpty()) {
                continue;
            }
            ItemStack picked = candidates.getFirst();
            long slotNeed = Math.min(
                (long) Math.max(1, picked.getCount()) * batches,
                picked.getMaxStackSize()
            );

            String signature = groupSignature(candidates);
            samples.putIfAbsent(signature, picked.copy());
            groups.putIfAbsent(signature, candidates);
            wanted.merge(signature, (int) Math.min(Integer.MAX_VALUE, slotNeed), Integer::sum);
        }
        if (wanted.isEmpty()) {
            return List.of();
        }
        return collectShortfalls(wanted, samples, groups, menu, player);
    }

    public static List<ItemStack> restockGroup(IRecipeSlotView slot, String selected) {
        Map<String, ItemStack> unique = new LinkedHashMap<>();
        for (ItemStack candidate : RecipeLookup.candidateStacks(slot)) {
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            unique.putIfAbsent(RecipeTreeData.ingredientKey(candidate), candidate.copy());
        }
        if (unique.isEmpty()) {
            return List.of();
        }
        List<ItemStack> ordered = new ArrayList<>(unique.size());
        ItemStack preferred = selected.isEmpty() ? null : unique.get(selected);
        if (preferred != null) {
            ordered.add(preferred);
        }
        for (ItemStack candidate : unique.values()) {
            if (ordered.size() >= RecipeTreeTransfer.MAX_RESTOCK_GROUP) {
                break;
            }
            if (candidate != preferred) {
                ordered.add(candidate);
            }
        }
        return List.copyOf(ordered);
    }

    public static String groupSignature(List<ItemStack> candidates) {
        StringBuilder builder = new StringBuilder();
        for (ItemStack candidate : candidates) {
            if (!builder.isEmpty()) {
                builder.append(',');
            }
            builder.append(RecipeTreeData.ingredientKey(candidate));
        }
        return builder.toString();
    }

    public static List<StorageRestock.Need> collectShortfalls(
        Map<String, Integer> wanted,
        Map<String, ItemStack> samples,
        Map<String, List<ItemStack>> groups,
        AbstractContainerMenu menu,
        Player player
    ) {
        Map<String, Long> have = StorageRestock.counts(menu, player);
        List<StorageRestock.Need> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : wanted.entrySet()) {
            List<ItemStack> group = groups.get(entry.getKey());
            ItemStack sample = samples.get(entry.getKey());
            if (group == null || group.isEmpty() || sample == null || sample.isEmpty()) {
                continue;
            }
            long owned = 0L;
            for (ItemStack candidate : group) {
                owned += have.getOrDefault(RecipeTreeData.ingredientKey(candidate), 0L);
            }
            long missing = entry.getValue() - owned;
            if (missing <= 0) {
                continue;
            }
            sample = sample.copy();
            sample.setCount(1);
            long target = owned + missing;
            result.add(new StorageRestock.Need(
                sample,
                RecipeTreeData.ingredientKey(sample),
                (int) Math.min(Integer.MAX_VALUE, missing),
                (int) Math.min(Integer.MAX_VALUE, target),
                group
            ));
        }
        return result;
    }

    private record BatchDemand(ItemStack sample, long perBatch) {
    }

    public static Map<String, BatchDemand> perBatchDemand(
        IRecipeLayoutDrawable<?> layout,
        List<String> selectedInputs
    ) {
        Map<String, BatchDemand> result = new LinkedHashMap<>();
        if (layout == null) {
            return result;
        }
        int inputIndex = 0;
        for (IRecipeSlotView slot : layout.getRecipeSlotsView().getSlotViews()) {
            if (slot.getRole() != RecipeIngredientRole.INPUT) {
                continue;
            }
            String selected = TransferFillPlan.preferredInputKey(
                slot,
                inputIndex < selectedInputs.size() ? selectedInputs.get(inputIndex) : ""
            );
            inputIndex++;
            ItemStack picked = RecipeLookup.candidateStacks(slot).stream()
                .filter(stack -> !stack.isEmpty())
                .filter(stack -> selected.isEmpty()
                    || RecipeTreeData.ingredientKey(stack).equals(selected))
                .findFirst()
                .orElse(ItemStack.EMPTY);
            if (picked.isEmpty()) {
                continue;
            }
            String key = RecipeTreeData.ingredientKey(picked);
            long per = Math.max(1L, picked.getCount());
            BatchDemand current = result.get(key);
            result.put(key, current == null
                ? new BatchDemand(picked.copy(), per)
                : new BatchDemand(current.sample(), current.perBatch() + per));
        }
        return result;
    }

    public static int inventoryBoundChunk(
        RecipeTreeData.CraftStep step,
        int chunk,
        AbstractContainerMenu menu,
        Player player
    ) {
        if (step == null || chunk <= 1 || menu == null || player == null) {
            return chunk;
        }
        IRecipeLayoutDrawable<?> layout = RecipeLookup.createLayout(step.recipe()).orElse(null);
        Map<String, BatchDemand> demand = perBatchDemand(layout, step.selectedInputs());
        if (demand.isEmpty()) {
            return chunk;
        }
        Map<String, Long> have = StorageRestock.counts(menu, player);
        int bound = chunk;
        for (Map.Entry<String, BatchDemand> entry : demand.entrySet()) {
            BatchDemand item = entry.getValue();
            long capacity = have.getOrDefault(entry.getKey(), 0L)
                + StorageRestock.roomFor(menu, player, item.sample());
            long batches = capacity / Math.max(1L, item.perBatch());
            bound = (int) Math.min(bound, Math.max(1L, batches));
        }
        if (bound < chunk) {
            JERTLog.debug("restock: chunk {} -> {} (inventory room)", chunk, bound);
        }
        return Math.max(1, bound);
    }

    public static boolean coverable(AbstractContainerMenu menu, List<StorageRestock.Need> needs) {
        StorageNetworkIntegration.StorageSnapshot snapshot = StorageDriverRegistry.read(menu);
        if (snapshot == null) {
            return false;
        }
        Map<String, Long> stored = new HashMap<>();
        for (StorageNetworkIntegration.StoredStack stack : snapshot.stacks()) {
            if (stack == null || stack.stack().isEmpty()) {
                continue;
            }
            stored.merge(RecipeTreeData.ingredientKey(stack.stack()), stack.amount(), Long::sum);
        }
        for (StorageRestock.Need need : needs) {

            long storedGroup = 0L;
            for (ItemStack candidate : need.candidates()) {
                storedGroup += stored.getOrDefault(RecipeTreeData.ingredientKey(candidate), 0L);
            }
            if (storedGroup < need.amount()) {
                return false;
            }
        }
        return true;
    }

}
