package com.slwold.jeirecipetree.client;

import com.slwold.jeirecipetree.JERTConfig;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SafeConfig {

    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private SafeConfig() {
    }

    public static boolean getBoolean(net.neoforged.neoforge.common.ModConfigSpec.BooleanValue value,
        boolean fallback) {
        try {
            return value.get();
        } catch (Throwable error) {
            warnOnce(error);
            return fallback;
        }
    }

    public static List<String> getStringList(
        net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<List<? extends String>> value,
        List<String> fallback) {
        try {
            List<? extends String> raw = value.get();
            return raw == null ? fallback : List.copyOf(raw);
        } catch (Throwable error) {
            warnOnce(error);
            return fallback;
        }
    }

    public static boolean hideRecipeBookmarkButton() {
        return getBoolean(JERTConfig.HIDE_RECIPE_BOOKMARK_BUTTON, true);
    }

    public static boolean recipeIngredientDirectoryEnabled() {
        return getBoolean(JERTConfig.RECIPE_INGREDIENT_DIRECTORY_ENABLED, true);
    }

    public static boolean recipeTreeEnabled() {
        return getBoolean(JERTConfig.RECIPE_TREE_ENABLED, true);
    }

    public static boolean automaticCraftingEnabled() {
        return getBoolean(JERTConfig.AUTOMATIC_CRAFTING_ENABLED, true);
    }

    public static boolean developerMode() {
        return getBoolean(JERTConfig.DEVELOPER_MODE, false);
    }

    public static boolean sophisticatedRestockEnabled() {
        return getBoolean(JERTConfig.SOPHISTICATED_RESTOCK_ENABLED, true);
    }

    public static List<String> disabledRecipeTypes() {
        return getStringList(JERTConfig.DISABLED_RECIPE_TYPES, List.of());
    }

    public static com.slwold.jeirecipetree.TreeTheme treeTheme() {
        try {
            com.slwold.jeirecipetree.TreeTheme value = JERTConfig.TREE_THEME.get();
            return value == null ? com.slwold.jeirecipetree.TreeTheme.blueprint : value;
        } catch (Throwable error) {
            warnOnce(error);
            return com.slwold.jeirecipetree.TreeTheme.blueprint;
        }
    }

    public static com.slwold.jeirecipetree.TreeTextSize treeTextSize() {
        try {
            com.slwold.jeirecipetree.TreeTextSize value = JERTConfig.TREE_TEXT_SIZE.get();
            return value == null ? com.slwold.jeirecipetree.TreeTextSize.normal : value;
        } catch (Throwable error) {
            warnOnce(error);
            return com.slwold.jeirecipetree.TreeTextSize.normal;
        }
    }

    public static boolean useGameFontForNumbers() {
        try {
            return JERTConfig.USE_GAME_FONT_FOR_NUMBERS.get();
        } catch (Throwable error) {
            warnOnce(error);
            return false;
        }
    }

    private static void warnOnce(Throwable error) {
        if (WARNED.compareAndSet(false, true)) {
            JERTLog.warn("config read failed (using defaults): {} | {}",
                error.getClass().getSimpleName(), error.getMessage());
        }
    }
}
