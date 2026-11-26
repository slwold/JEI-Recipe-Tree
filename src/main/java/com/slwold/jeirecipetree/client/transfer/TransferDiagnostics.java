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

public final class TransferDiagnostics {
    private TransferDiagnostics() {
    }

    public static final java.util.Map<String, String> REASON_CODES = java.util.Map.ofEntries(
        java.util.Map.entry("queue complete", "ok"),
        java.util.Map.entry("menu or session lost", "session_lost"),
        java.util.Map.entry("no container screen", "no_screen"),
        java.util.Map.entry("exposeParentContainer failed", "expose_failed"),
        java.util.Map.entry("no current craft step", "no_step"),
        java.util.Map.entry("queue exhausted during completion", "queue_exhausted"),
        java.util.Map.entry("transfer failed or nothing to transfer", "transfer_rejected"),
        java.util.Map.entry("transfer after restock failed", "transfer_rejected"),
        java.util.Map.entry("restock finished without a resume target", "restock_no_target"),
        java.util.Map.entry("restock short", "restock_short"),
        java.util.Map.entry("timed machine", "timed_machine"),
        java.util.Map.entry("result slot out of range", "slot_out_of_range"),
        java.util.Map.entry("pickup", "pickup_failed"),
        java.util.Map.entry("rts pickup", "pickup_failed"),
        java.util.Map.entry("result slot stayed empty", "result_empty"),
        java.util.Map.entry("generic result probe timed out", "probe_timeout"),
        java.util.Map.entry("AE2 carry timed out", "carry_timeout"),
        java.util.Map.entry("AE2 carry", "carry_failed"),
        java.util.Map.entry("AE2 network deposit failed to start", "deposit_failed"),
        java.util.Map.entry("unexpected craft output", "output_mismatch"));

    public static String reasonCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        String text = raw.trim();
        int colon = text.indexOf(':');
        String head = colon < 0 ? text : text.substring(0, colon);
        return REASON_CODES.getOrDefault(head, "other");
    }

    public static int doneSteps() {
        return Math.max(0, RecipeTreeTransfer.queue().index() - 1);
    }

    public static String safeDetail(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace('"', '\'').replace('\n', ' ');
    }

    public static String elapsedSeconds() {
        if (RecipeTreeTransfer.sessionStartNanos == 0L) {
            return "0.0";
        }
        long millis = Math.max(0L, (System.nanoTime() - RecipeTreeTransfer.sessionStartNanos) / 1_000_000L);
        return String.format(java.util.Locale.ROOT, "%.1f", millis / 1000.0);
    }

    public static String currentFillState() {
        return RecipeTreeTransfer.lastFillState.isEmpty() ? "grid=not_observed" : RecipeTreeTransfer.lastFillState;
    }

}
