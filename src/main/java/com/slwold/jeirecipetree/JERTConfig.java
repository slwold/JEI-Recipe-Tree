package com.slwold.jeirecipetree;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public final class JERTConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue HIDE_RECIPE_BOOKMARK_BUTTON =
        BUILDER
            .translation("jeirecipetree.configuration.hideRecipeBookmarkButton")
            .comment("隐藏 JEI 配方页原生的「加入书签」按钮（书签由配方树接管时使用）。",
                "Hide JEI's native add-to-bookmark button in recipe layouts.")
            .define("hideRecipeBookmarkButton", true);

    public static final ModConfigSpec.BooleanValue RECIPE_INGREDIENT_DIRECTORY_ENABLED =
        BUILDER
            .translation("jeirecipetree.configuration.recipeIngredientDirectoryEnabled")
            .comment("点击含多个候选产物的配方槽位时，打开材料目录以便挑选。",
                "Open the ingredient directory when clicking a recipe slot with multiple output items.")
            .define("recipeIngredientDirectoryEnabled", true);

    public static final ModConfigSpec.BooleanValue RECIPE_TREE_ENABLED =
        BUILDER
            .translation("jeirecipetree.configuration.recipeTreeEnabled")
            .comment("在 JEI 配方页与书签侧栏显示配方树入口。",
                "Add recipe-tree and crafting-assistant buttons to JEI recipe layouts.")
            .define("recipeTreeEnabled", true);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> DISABLED_RECIPE_TYPES =
        BUILDER
            .translation("jeirecipetree.configuration.disabledRecipeTypes")
            .comment("不参与配方树展开的配方类型 id，例如 [\"minecraft:blasting\"]。",
                "Recipe type ids excluded from the recipe tree, for example [\"minecraft:blasting\"].")
            .defineList("disabledRecipeTypes", List.of(), value -> value instanceof String);

    public static final ModConfigSpec.BooleanValue AUTOMATIC_CRAFTING_ENABLED =
        BUILDER
            .translation("jeirecipetree.configuration.automaticCraftingEnabled")
            .comment("允许在配方树书签上 Ctrl+左键，自动合成当前缺失的中间产物。",
                "Allow Ctrl-left-click in the recipe-tree bookmarks to automatically craft missing intermediate steps.")
            .define("automaticCraftingEnabled", true);

    public static final ModConfigSpec.BooleanValue PER_ITEM_CRAFTING =
        BUILDER
            .translation("jeirecipetree.configuration.perItemCrafting")
            .comment("自动合成多个成品时逐个完成：一次只做到 1 个成品（含其所需中间物），做完再开始下一个。适合配方里有工具等不可堆叠中间物的情况；仅作用于原版工作台与玩家 2×2 合成格，AE2 与 RTS 终端始终一次做完。",
                "Finish one crafted item at a time during automatic crafting: craft a single item (with its own intermediates) before starting the next. Useful when the recipe needs unstackable intermediates such as tools. Applies to the vanilla crafting table and the player 2x2 grid only; AE2 and RTS terminals always craft everything in one pass.")
            .define("perItemCrafting", true);

    public static final ModConfigSpec.BooleanValue HIDE_RAW_MATERIAL_ROWS =
        BUILDER
            .translation("jeirecipetree.configuration.hideRawMaterialRows")
            .comment("左侧 JEI 侧栏只显示最终产物与中间品，隐藏原材料树行。总耗材清单不受影响。",
                "Show only the final product and intermediates in the JEI sidebar; hide raw-material rows. The total-cost list is unaffected.")
            .define("hideRawMaterialRows", true);

    public static final ModConfigSpec.BooleanValue DEVELOPER_MODE =
        BUILDER
            .translation("jeirecipetree.configuration.developerMode")
            .comment("开发者模式：输出诊断日志（输入链路、面板区域、驱动状态）。",
                "Developer mode: verbose diagnostic logging.")
            .define("developerMode", false);

    public static final ModConfigSpec.BooleanValue SOPHISTICATED_RESTOCK_ENABLED =
        BUILDER
            .translation("jeirecipetree.configuration.sophisticatedRestockEnabled")
            .comment("显示配方书内的「取材料」按钮：通过 Sophisticated Item Actions 从附近容器补料。",
                "Show the fetch-materials button in the recipe book (requires Sophisticated Item Actions).")
            .define("sophisticatedRestockEnabled", true);

    public static final ModConfigSpec.EnumValue<TreeTheme> TREE_THEME =
        BUILDER
            .translation("jeirecipetree.configuration.treeTheme")
            .comment("配方树配色主题：paper（米白纸）/ classic（纸面蓝图）/ blueprint（IE 浅蓝蓝图）/ blueprint_dark（IE 深蓝蓝图）/ ae2（AE2 终端灰）/ dark（深色终端）。",
                "Recipe-tree colour theme: paper / classic / blueprint / blueprint_dark / ae2 / dark.")
            .defineEnum("treeTheme", TreeTheme.blueprint);

    public static final ModConfigSpec.EnumValue<TreeTextSize> TREE_TEXT_SIZE =
        BUILDER
            .translation("jeirecipetree.configuration.treeTextSize")
            .comment("配方树界面的文字大小：small（0.5 倍）/ normal（标准）/ large（2 倍）/ huge（3 倍）。只用整数倍，分数倍会把位图字体采样发虚。",
                "Recipe-tree text size: small (0.5x) / normal / large (2x) / huge (3x). Integer multiples only; fractional factors resample the bitmap font and look blurry.")
            .defineEnum("treeTextSize", TreeTextSize.normal);

    public static final ModConfigSpec.BooleanValue USE_GAME_FONT_FOR_NUMBERS =
        BUILDER
            .translation("jeirecipetree.configuration.useGameFontForNumbers")
            .comment("数量角标使用游戏当前字体（跟随字体资源包）；关闭时数字强制用原版位图字体。",
                "Use the current game font for quantity badges (follows font resource packs); when off, numbers always use the vanilla bitmap font.")
            .define("useGameFontForNumbers", false);

    public static boolean isRecipeTypeDisabled(ResourceLocation recipeType) {
        if (recipeType == null) {
            return false;
        }
        String id = recipeType.toString();
        for (String configured : DISABLED_RECIPE_TYPES.get()) {
            if (configured != null && configured.trim().equalsIgnoreCase(id)) {
                return true;
            }
        }
        return false;
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    public static final java.util.Set<String> KNOWN_KEYS = java.util.Set.of(
        "hideRecipeBookmarkButton",
        "recipeIngredientDirectoryEnabled",
        "recipeTreeEnabled",
        "disabledRecipeTypes",
        "automaticCraftingEnabled",
        "perItemCrafting",
        "hideRawMaterialRows",
        "developerMode",
        "sophisticatedRestockEnabled",
        "treeTheme",
        "treeTextSize",
        "useGameFontForNumbers"
    );

    public static final java.util.Map<String, String> DEFAULT_BLOCKS = java.util.Map.ofEntries(
        java.util.Map.entry("hideRecipeBookmarkButton",
            "#隐藏 JEI 配方页原生的「加入书签」按钮（书签由配方树接管时使用）。\n"
                + "#Hide JEI's native add-to-bookmark button in recipe layouts.\n"
                + "hideRecipeBookmarkButton = true"),
        java.util.Map.entry("recipeIngredientDirectoryEnabled",
            "#点击含多个候选产物的配方槽位时，打开材料目录以便挑选。\n"
                + "#Open the ingredient directory when clicking a recipe slot with multiple output items.\n"
                + "recipeIngredientDirectoryEnabled = true"),
        java.util.Map.entry("recipeTreeEnabled",
            "#在 JEI 配方页与书签侧栏显示配方树入口。\n"
                + "#Add recipe-tree and crafting-assistant buttons to JEI recipe layouts.\n"
                + "recipeTreeEnabled = true"),
        java.util.Map.entry("disabledRecipeTypes",
            "#不参与配方树展开的配方类型 id，例如 [\"minecraft:blasting\"]。\n"
                + "#Recipe type ids excluded from the recipe tree, for example [\"minecraft:blasting\"].\n"
                + "disabledRecipeTypes = []"),
        java.util.Map.entry("automaticCraftingEnabled",
            "#允许在配方树书签上 Ctrl+左键，自动合成当前缺失的中间产物。\n"
                + "#Allow Ctrl-left-click in the recipe-tree bookmarks to automatically craft missing intermediate steps.\n"
                + "automaticCraftingEnabled = true"),
        java.util.Map.entry("perItemCrafting",
            "#自动合成多个成品时逐个完成：一次只做到 1 个成品（含其所需中间物），做完再开始下一个。适合配方里有工具等不可堆叠中间物的情况；仅作用于原版工作台与玩家 2×2 合成格，AE2 与 RTS 终端始终一次做完。\n"
                + "#Finish one crafted item at a time during automatic crafting: craft a single item (with its own intermediates) before starting the next. Useful when the recipe needs unstackable intermediates such as tools. Applies to the vanilla crafting table and the player 2x2 grid only; AE2 and RTS terminals always craft everything in one pass.\n"
                + "perItemCrafting = true"),
        java.util.Map.entry("hideRawMaterialRows",
            "#左侧 JEI 侧栏只显示最终产物与中间品，隐藏原材料树行。总耗材清单不受影响。\n"
                + "#Show only the final product and intermediates in the JEI sidebar; hide raw-material rows. The total-cost list is unaffected.\n"
                + "hideRawMaterialRows = true"),
        java.util.Map.entry("developerMode",
            "#开发者模式：输出诊断日志（输入链路、面板区域、驱动状态）。\n"
                + "#Developer mode: verbose diagnostic logging.\n"
                + "developerMode = false"),
        java.util.Map.entry("sophisticatedRestockEnabled",
            "#显示配方书内的「取材料」按钮：通过 Sophisticated Item Actions 从附近容器补料。\n"
                + "#Show the fetch-materials button in the recipe book (requires Sophisticated Item Actions).\n"
                + "sophisticatedRestockEnabled = true"),
        java.util.Map.entry("treeTheme",
            "#配方树配色主题：paper（米白纸）/ classic（纸面蓝图）/ blueprint（IE 浅蓝蓝图）/ blueprint_dark（IE 深蓝蓝图）/ ae2（AE2 终端灰）/ dark（深色终端）。\n"
                + "#Recipe-tree colour theme: paper / classic / blueprint / blueprint_dark / ae2 / dark.\n"
                + "#Allowed Values: paper, classic, blueprint, blueprint_dark, ae2, dark\n"
                + "treeTheme = \"blueprint\""),
        java.util.Map.entry("treeTextSize",
            "#配方树界面的文字大小：small（0.5 倍）/ normal（标准）/ large（2 倍）/ huge（3 倍）。只用整数倍，分数倍会把位图字体采样发虚。\n"
                + "#Recipe-tree text size: small (0.5x) / normal / large (2x) / huge (3x). Integer multiples only; fractional factors resample the bitmap font and look blurry.\n"
                + "#Allowed Values: small, normal, large, huge\n"
                + "treeTextSize = \"normal\""),
        java.util.Map.entry("useGameFontForNumbers",
            "#数量角标使用游戏当前字体（跟随字体资源包）；关闭时数字强制用原版位图字体。\n"
                + "#Use the current game font for quantity badges (follows font resource packs); when off, numbers always use the vanilla bitmap font.\n"
                + "useGameFontForNumbers = false")
    );

    private JERTConfig() {
    }

    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, SPEC);
    }
}
