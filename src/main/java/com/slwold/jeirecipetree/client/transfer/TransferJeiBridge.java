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

public final class TransferJeiBridge {
    private TransferJeiBridge() {
    }

    public static Boolean tryJeiTransfer(
        AbstractContainerMenu menu,
        IRecipeLayoutDrawable<?> layout,
        List<String> selectedInputs,
        int batches,
        Player player,
        boolean doTransfer,
        boolean recursiveCandidates,
        boolean maxTransfer
    ) {
        IRecipeTransferManager manager = Internal.getJeiRuntime().getRecipeTransferManager();
        Optional<IRecipeTransferHandler<AbstractContainerMenu, Object>> handler = (Optional) manager
            .getRecipeTransferHandler(menu, (IRecipeCategory) layout.getRecipeCategory());
        if (handler.isEmpty()) {
            return false;
        }

        IRecipeSlotsView slots = TransferFillPlan.adjustInputs(
            layout.getRecipeSlotsView(), selectedInputs, batches, recursiveCandidates, player
        );
        try {
            IRecipeTransferError error = handler.get().transferRecipe(
                menu,
                layout.getRecipe(),
                slots,
                player,
                maxTransfer,
                doTransfer
            );
            return error == null || error.getType().allowsTransfer;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static AbstractContainerScreen<?> containerScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        Screen current = minecraft.screen;
        if (current instanceof RecipeTreeScreen treeScreen) {
            current = treeScreen.parentScreen();
        }
        return findContainerScreen(current);
    }

    public static boolean exposeParentContainer() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof RecipeTreeScreen treeScreen) {
            if (findContainerScreen(treeScreen.parentScreen()) == null) {
                return false;
            }
            treeScreen.onClose();
            closeRecipeGuiLayers();
        }
        return containerScreen() != null;
    }

    public static void closeRecipeGuiLayers() {
        Minecraft minecraft = Minecraft.getInstance();
        for (int depth = 0; depth < 6; depth++) {
            Screen screen = minecraft.screen;
            if (screen == null
                || screen instanceof AbstractContainerScreen<?>
                || !screen.getClass().getName().contains("RecipesGui")) {
                return;
            }
            screen.onClose();
        }
    }

    public static AbstractContainerScreen<?> findContainerScreen(Screen start) {
        Screen current = start;
        for (int depth = 0; current != null && depth < 6; depth++) {
            if (current instanceof AbstractContainerScreen<?> screen) {
                return screen;
            }
            Screen parent = parentScreenOf(current);
            if (parent == current) {
                break;
            }
            current = parent;
        }
        return null;
    }

    public static Screen parentScreenOf(Screen screen) {
        if (screen instanceof RecipeTreeScreen treeScreen) {
            return treeScreen.parentScreen();
        }
        if (!screen.getClass().getName().contains("RecipesGui")) {
            return null;
        }
        try {
            Method method = screen.getClass().getMethod("getParentScreen");
            Object value = method.invoke(screen);
            if (value instanceof Optional<?> optional) {
                return optional.orElse(null) instanceof Screen parent ? parent : null;
            }
            if (value instanceof Screen parent) {
                return parent;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {

        }
        for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField("parentScreen");
                field.setAccessible(true);
                Object value = field.get(screen);
                return value instanceof Screen parent ? parent : null;
            } catch (NoSuchFieldException ignored) {

            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    public static int instantResultSlot(AbstractContainerMenu menu) {
        int networkResult = StorageNetworkIntegration.craftingResultSlot(menu);
        if (networkResult >= 0) {
            return networkResult;
        }
        if (menu instanceof CraftingMenu || menu instanceof InventoryMenu) {
            return 0;
        }
        if (menu instanceof StonecutterMenu) {
            return 1;
        }
        if (menu instanceof SmithingMenu || menu instanceof LoomMenu) {
            return 3;
        }
        if (menu instanceof CartographyTableMenu || menu instanceof GrindstoneMenu) {
            return 2;
        }
        return -1;
    }

    public static Boolean tryNetworkCraftingTransfer(
        AbstractContainerMenu menu,
        IRecipeLayoutDrawable<?> layout,
        List<String> selectedInputs,
        boolean doTransfer,
        boolean recursiveCandidates,
        String recipeId
    ) {
        if (TomsStorageDriver.isCraftingTerminalMenu(menu)) {

            JERTLog.debug("toms: skip server fill, use inventory click path");
            return null;
        }
        if (!(layout.getRecipe() instanceof CraftingRecipe)) {
            return null;
        }
        List<ItemStack> templates = ae2Templates(layout, selectedInputs, recursiveCandidates);
        if (templates == null) {
            return null;
        }
        return StorageNetworkIntegration.tryFillCraftingGrid(menu, templates, doTransfer, recipeId);
    }

    public static List<ItemStack> ae2Templates(
        IRecipeLayoutDrawable<?> layout,
        List<String> selectedInputs,
        boolean recursiveCandidates
    ) {
        List<IRecipeSlotView> inputs = layout.getRecipeSlotsView().getSlotViews().stream()
            .filter(slot -> slot.getRole() == RecipeIngredientRole.INPUT)
            .toList();
        if (inputs.size() != 9) {
            return null;
        }

        List<ItemStack> templates = new ArrayList<>(9);
        for (int index = 0; index < inputs.size(); index++) {
            IRecipeSlotView input = inputs.get(index);
            String selected = TransferFillPlan.preferredInputKey(
                input,
                index < selectedInputs.size() ? selectedInputs.get(index) : ""
            );
            ItemStack template = RecipeLookup.candidateStacks(input).stream()
                .filter(stack -> !stack.isEmpty())
                .filter(stack -> selected.isEmpty() || RecipeTreeData.ingredientKey(stack).equals(selected))
                .findFirst()
                .map(ItemStack::copy)
                .orElse(ItemStack.EMPTY);
            if (!template.isEmpty()) {
                template.setCount(1);
            }
            templates.add(template);
        }
        return templates;
    }

}
