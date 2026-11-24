package com.slwold.jeirecipetree.client;

import mezz.jei.gui.bookmarks.BookmarkList;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public final class JeiTreeIntegration {

    private JeiTreeIntegration() {
    }

    public static void bindBookmarks(BookmarkList bookmarkList) {
        RecipeTreeFavorites.bind(bookmarkList);
    }

    public static void refreshBeforeNotification() {
        RecipeTreeFavorites.refreshBeforeBookmarkListNotification();
    }

    public static final int CLASS_FINAL = RecipeTreeFavorites.CLASS_FINAL;

    public static final int CLASS_INTERMEDIATE = RecipeTreeFavorites.CLASS_INTERMEDIATE;

    public static final int CLASS_REQUIRED = RecipeTreeFavorites.CLASS_REQUIRED;

    public static boolean isActive() {
        return RecipeTreeFavorites.isActive();
    }

    public static boolean hasRows() {
        return !RecipeTreeFavorites.rows().isEmpty();
    }

    public static int rowCount() {
        return RecipeTreeFavorites.rows().size();
    }

    public static boolean isFinalProduct(ItemStack stack) {
        return RecipeTreeFavorites.isFinalProduct(stack);
    }

    public static boolean isIntermediate(ItemStack stack) {
        return RecipeTreeFavorites.isIntermediate(stack);
    }

    public static boolean isRequired(ItemStack stack) {
        return RecipeTreeFavorites.isRequired(stack);
    }

    public static int classify(ItemStack stack) {
        return RecipeTreeFavorites.classify(stack);
    }

    public static boolean isNetworkStorageSlot(Slot slot) {
        return RecipeTreeFavorites.isNetworkStorageSlot(slot);
    }

    public static void renderNetworkHighlights(GuiGraphics graphics, AbstractContainerScreen<?> screen) {
        RecipeTreeFavorites.renderVirtualNetworkHighlights(graphics, screen);
    }

    public static void renderBetterBeyondHighlights(GuiGraphics graphics, AbstractContainerScreen<?> screen) {
        RecipeTreeFavorites.renderBetterBeyondHighlights(graphics, screen);
    }

    public static void renderIntegratedHighlights(GuiGraphics graphics, AbstractContainerScreen<?> screen) {
        RecipeTreeFavorites.renderIntegratedTerminalHighlightsBeforeTooltip(graphics, screen);
    }

    public static void refreshThrottled() {
        RecipeTreeFavorites.refreshThrottled();
    }

    public static boolean hasTree() {
        return RecipeTreeSession.tree() != null;
    }

    public static void clearSession() {
        RecipeTreeSession.clear();
    }

    public static void openCurrentTree(Screen parent) {
        RecipeTreeScreen.openCurrent(parent);
    }
}
