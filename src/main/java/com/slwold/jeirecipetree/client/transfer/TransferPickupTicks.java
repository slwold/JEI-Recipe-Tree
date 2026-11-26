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

public final class TransferPickupTicks {
    private TransferPickupTicks() {
    }

    public static void tickVanillaPickup(AbstractContainerScreen<?> screen, Minecraft minecraft) {
        if (RecipeTreeTransfer.step().resultSlot < 0 || RecipeTreeTransfer.step().resultSlot >= screen.getMenu().slots.size()) {
            RecipeTreeTransfer.clearPending("pickup: result slot out of range");
            return;
        }
        if (RtsStorageDriver.isTerminalMenu(screen.getMenu())) {
            tickRtsPickup(screen, minecraft);
            return;
        }

        if (TomsStorageDriver.isCraftingTerminalMenu(screen.getMenu())) {
            tickTomsPickup(screen, minecraft);
            return;
        }
        Slot result = screen.getMenu().getSlot(RecipeTreeTransfer.step().resultSlot);
        if (!result.hasItem()) {

            RecipeTreeTransfer.queue().collectWholeLoadedBatch();
            RecipeTreeTransfer.step().destination = "inventory";
            RecipeTreeTransfer.completePendingCraft();
            return;
        }
        if (++RecipeTreeTransfer.step().pickupAttempts > 8) {

            RecipeTreeTransfer.clearPending("pickup: inventory full or result still owned");
            return;
        }
        minecraft.gameMode.handleInventoryMouseClick(
            RecipeTreeTransfer.step().menuId,
            RecipeTreeTransfer.step().resultSlot,
            0,
            ClickType.QUICK_MOVE,
            minecraft.player
        );
        RecipeTreeTransfer.step().waitFrames = 1;
    }

    public static void tickRtsPickup(AbstractContainerScreen<?> screen, Minecraft minecraft) {
        if (RecipeTreeTransfer.step().rtsPickupBefore < 0L) {

            JERTLog.debug("rts pickup: no inventory baseline, advancing without verification");
            RecipeTreeTransfer.queue().collectWholeLoadedBatch();
            RecipeTreeTransfer.step().destination = "inventory";
            RecipeTreeTransfer.completePendingCraft();
            return;
        }
        long now = RecipeTreeTransfer.playerInventoryAmount(screen.getMenu(), minecraft.player, RecipeTreeTransfer.step().rtsPickupKey);
        JERTLog.debug("toms pickup: before={} now={} gained={}",
            RecipeTreeTransfer.step().rtsPickupBefore, now, now - RecipeTreeTransfer.step().rtsPickupBefore);
        if (now > RecipeTreeTransfer.step().rtsPickupBefore) {
            RecipeTreeTransfer.queue().collectWholeLoadedBatch();
            RecipeTreeTransfer.step().destination = "inventory";
            RecipeTreeTransfer.completePendingCraft();
            return;
        }
        if (++RecipeTreeTransfer.step().emptyFrames > RecipeTreeTransfer.RTS_PICKUP_WAIT_FRAMES) {
            RecipeTreeTransfer.clearPending("rts pickup: inventory never grew");
        }
    }

    public static void tickTomsPickup(AbstractContainerScreen<?> screen, Minecraft minecraft) {
        if (RecipeTreeTransfer.step().rtsPickupBefore < 0L) {
            JERTLog.debug("toms pickup: no inventory baseline, advancing one batch");
            RecipeTreeTransfer.step().destination = "inventory";
            RecipeTreeTransfer.completePendingCraft();
            return;
        }
        long now = RecipeTreeTransfer.playerInventoryAmount(screen.getMenu(), minecraft.player, RecipeTreeTransfer.step().rtsPickupKey);
        if (now > RecipeTreeTransfer.step().rtsPickupBefore) {
            RecipeTreeTransfer.step().destination = "inventory";
            RecipeTreeTransfer.completePendingCraft();
            return;
        }
        if (++RecipeTreeTransfer.step().emptyFrames > RecipeTreeTransfer.RTS_PICKUP_WAIT_FRAMES) {
            RecipeTreeTransfer.clearPending("toms pickup: inventory never grew");
        }
    }

    public static void tickAe2Carry(AbstractContainerScreen<?> screen, Minecraft minecraft) {
        AbstractContainerMenu menu = screen.getMenu();
        if (RecipeTreeTransfer.step().ae2NetworkDeposit) {
            tickAe2NetworkDeposit(menu, minecraft);
            return;
        }

        ItemStack carried = menu.getCarried();
        if (RecipeTreeTransfer.step().ae2PlacementInFlight) {
            if (carried.isEmpty()) {
                if (!RecipeTreeTransfer.step().ae2OutputKey.isEmpty()
                    && RecipeTreeTransfer.playerInventoryAmount(menu, minecraft.player, RecipeTreeTransfer.step().ae2OutputKey)
                        > RecipeTreeTransfer.step().ae2PlacementInventoryBefore) {
                    RecipeTreeTransfer.step().destination = "inventory";
                    RecipeTreeTransfer.completePendingCraft();
                    return;
                }
                if (++RecipeTreeTransfer.step().emptyFrames > RecipeTreeTransfer.AE2_WAIT_FRAMES) {
                    RecipeTreeTransfer.clearPending("AE2 carry timed out");
                } else {
                    RecipeTreeTransfer.step().waitFrames = 1;
                }
                return;
            }

            long inventoryAmount = RecipeTreeTransfer.step().ae2OutputKey.isEmpty()
                ? 0
                : RecipeTreeTransfer.playerInventoryAmount(menu, minecraft.player, RecipeTreeTransfer.step().ae2OutputKey);
            if (carried.getCount() < RecipeTreeTransfer.step().ae2PlacementCount
                || inventoryAmount > RecipeTreeTransfer.step().ae2PlacementInventoryBefore) {

                RecipeTreeTransfer.step().ae2PlacementInFlight = false;
                RecipeTreeTransfer.step().ae2PlacementSlot = -1;
                RecipeTreeTransfer.step().ae2PlacementCount = 0;
                RecipeTreeTransfer.step().emptyFrames = 0;
                RecipeTreeTransfer.step().waitFrames = 1;
                return;
            }
            if (++RecipeTreeTransfer.step().emptyFrames > RecipeTreeTransfer.AE2_WAIT_FRAMES) {
                RecipeTreeTransfer.step().ae2PlacementInFlight = false;
                if (startAe2NetworkDeposit(menu)) {
                    return;
                }
                RecipeTreeTransfer.clearPending("AE2 carry: no inventory destination and no network deposit");
            } else {
                RecipeTreeTransfer.step().waitFrames = 1;
            }
            return;
        }

        if (carried.isEmpty()) {

            if (!RecipeTreeTransfer.step().ae2OutputStack.isEmpty()
                && SupplyLedger.inventoryAmount(RecipeTreeTransfer.step().ae2OutputStack) > RecipeTreeTransfer.step().ae2InventoryBefore) {
                RecipeTreeTransfer.step().destination = "inventory";
                RecipeTreeTransfer.completePendingCraft();
                return;
            }
            if (++RecipeTreeTransfer.step().emptyFrames > RecipeTreeTransfer.AE2_WAIT_FRAMES) {
                RecipeTreeTransfer.clearPending("AE2 carry: cursor became empty without a pickup");
            }
            return;
        }

        if (RecipeTreeTransfer.step().ae2PlacementSlot < 0) {

            if (startAe2NetworkDeposit(menu)) {
                return;
            }
            int target = RecipeTreeTransfer.findInventoryDestination(menu, minecraft.player, carried);
            if (target < 0) {

                JERTLog.warn(
                    "transfer: output {} x{} fits neither the ME network nor the player inventory; "
                        + "it stays on the cursor until you free a slot",
                    carried.getHoverName().getString(),
                    carried.getCount());
                RecipeTreeTransfer.clearPending("AE2 carry: no network deposit and no inventory destination");
                return;
            }
            RecipeTreeTransfer.step().ae2PlacementSlot = target;
            RecipeTreeTransfer.step().ae2PlacementInFlight = true;
            RecipeTreeTransfer.step().ae2PlacementCount = carried.getCount();
            RecipeTreeTransfer.step().ae2PlacementInventoryBefore = RecipeTreeTransfer.step().ae2OutputKey.isEmpty()
                ? 0
                : RecipeTreeTransfer.playerInventoryAmount(menu, minecraft.player, RecipeTreeTransfer.step().ae2OutputKey);
            minecraft.gameMode.handleInventoryMouseClick(
                RecipeTreeTransfer.step().menuId,
                target,
                0,
                ClickType.PICKUP,
                minecraft.player
            );
            RecipeTreeTransfer.step().waitFrames = 1;
            return;
        }
    }

    public static void tickAe2NetworkDeposit(AbstractContainerMenu menu, Minecraft minecraft) {
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) {
            RecipeTreeTransfer.step().destination = "network";
            RecipeTreeTransfer.completePendingCraft();
            return;
        }
        if (RecipeTreeTransfer.step().ae2NetworkRequest) {
            if (carried.getCount() < RecipeTreeTransfer.step().ae2NetworkCount) {

                RecipeTreeTransfer.step().ae2NetworkRequest = false;
                RecipeTreeTransfer.step().ae2NetworkCount = carried.getCount();
                RecipeTreeTransfer.step().emptyFrames = 0;
                RecipeTreeTransfer.step().waitFrames = 1;
                return;
            }
            if (++RecipeTreeTransfer.step().emptyFrames > RecipeTreeTransfer.AE2_DEPOSIT_WAIT_FRAMES) {

                JERTLog.debug("transfer: AE2 network deposit timed out, falling back to inventory | {}",
                    RecipeTreeTransfer.queueStatus());
                RecipeTreeTransfer.step().ae2NetworkDeposit = false;
                RecipeTreeTransfer.step().ae2NetworkRequest = false;
                RecipeTreeTransfer.step().ae2NetworkSkip = true;
                RecipeTreeTransfer.step().ae2PlacementSlot = -1;
                RecipeTreeTransfer.step().emptyFrames = 0;
                RecipeTreeTransfer.step().waitFrames = 1;
            } else {
                RecipeTreeTransfer.step().waitFrames = 1;
            }
            return;
        }
        if (!startAe2NetworkDeposit(menu)) {

            if (StorageRestock.placeCarriedInInventory(menu, minecraft.player, minecraft)) {
                JERTLog.debug("transfer: network deposit unavailable, output moved to inventory | {}",
                    RecipeTreeTransfer.queueStatus());
                RecipeTreeTransfer.step().destination = "inventory";
                RecipeTreeTransfer.completePendingCraft();
                return;
            }
            JERTLog.warn(
                "transfer: network deposit failed and no inventory slot is free; "
                    + "the output stays on the cursor");
            RecipeTreeTransfer.clearPending("AE2 network deposit failed to start");
        }
    }

    public static boolean startAe2NetworkDeposit(AbstractContainerMenu menu) {
        if (RecipeTreeTransfer.step().ae2NetworkSkip) {

            return false;
        }
        Boolean deposited = StorageNetworkIntegration.putCarriedItemIntoNetwork(menu, false);
        if (!Boolean.TRUE.equals(deposited)) {
            return false;
        }
        RecipeTreeTransfer.step().ae2NetworkDeposit = true;
        RecipeTreeTransfer.step().ae2NetworkRequest = true;
        RecipeTreeTransfer.step().ae2NetworkCount = Math.max(1, menu.getCarried().getCount());
        RecipeTreeTransfer.step().emptyFrames = 0;
        RecipeTreeTransfer.step().waitFrames = 1;
        return true;
    }

    public static void clearCursorBeforeTransfer(AbstractContainerMenu menu) {
        if (menu == null || menu.getCarried().isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ItemStack stuck = menu.getCarried().copy();
        if (StorageRestock.placeCarriedInInventory(menu, minecraft.player, minecraft)) {
            JERTLog.debug("transfer: cleared {} x{} from cursor before transfer",
                stuck.getHoverName().getString(), stuck.getCount());
            return;
        }
        JERTLog.warn("transfer: cursor still holds {} x{} (inventory full?), transfer may be rejected",
            stuck.getHoverName().getString(), stuck.getCount());
    }

}
