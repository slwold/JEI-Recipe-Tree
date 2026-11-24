package com.slwold.jeirecipetree.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class StaleConfigKeysTest {

    private static final Set<String> KNOWN = Set.of(
        "hideRecipeBookmarkButton",
        "recipeIngredientDirectoryEnabled",
        "recipeTreeEnabled",
        "disabledRecipeTypes",
        "automaticCraftingEnabled",
        "developerMode",
        "sophisticatedRestockEnabled"
    );

    private static final String LEGACY = """
        #隐藏 JEI 配方页原生的「加入书签」按钮（书签由配方树接管时使用）。
        #Hide JEI's native add-to-bookmark button in recipe layouts.
        hideRecipeBookmarkButton = true
        #点击含多个候选产物的配方槽位时，打开材料目录以便挑选。
        #Open the ingredient directory when clicking a recipe slot with multiple output items.
        recipeIngredientDirectoryEnabled = true
        #在 JEI 配方页与书签侧栏显示配方树入口。
        #Add recipe-tree and crafting-assistant buttons to JEI recipe layouts.
        recipeTreeEnabled = true
        #不参与配方树展开的配方类型 id，例如 ["minecraft:blasting"]。
        #Recipe type ids excluded from the recipe tree, for example ["minecraft:blasting"].
        disabledRecipeTypes = []
        #允许在配方树书签上 Ctrl+左键，自动合成当前缺失的中间产物。
        #Allow Ctrl-left-click in the recipe-tree bookmarks to automatically craft missing intermediate steps.
        automaticCraftingEnabled = true
        #开发者模式：输出诊断日志（输入链路、面板区域、驱动状态）。
        #Developer mode: verbose diagnostic logging.
        developerMode = true
        #用配方树材料填充 Minecraft 原生配方书的内容区。
        #Fill the vanilla recipe book's content area with recipe-tree materials.
        replaceVanillaRecipeBook = false
        """;

    private StaleConfigKeysTest() {
    }

    public static void main(String[] args) {
        removesLegacyKey();
        keepsEveryKnownKeyByteForByte();
        dropsTheCommentThatDescribesTheRemovedKey();
        leavesCleanFileUntouched();
        fillAddsMissingKeys();
        fillWritesComments();
        fillKeepsTopLevelKeysAboveTables();
        fillIsIdempotent();
        refusesToTouchNestedOrQuotedKeys();
        emptyAndNullInputsAreSafe();
        reblockRefreshesStaleCommentsButKeepsValues();
        reblockIsIdempotent();
        reblockLeavesTablesAlone();
        System.out.println("Stale config key checks passed");
    }

    private static void removesLegacyKey() {
        StaleConfigKeys.Result result = StaleConfigKeys.clean(LEGACY, KNOWN);
        check(result.changed(), "the legacy file must be repaired");
        check(result.removed().equals(List.of("replaceVanillaRecipeBook")),
            "exactly one stale key expected, got " + result.removed());
        check(!result.text().contains("replaceVanillaRecipeBook"),
            "the stale key must be gone from the output");
    }

    private static void keepsEveryKnownKeyByteForByte() {
        StaleConfigKeys.Result result = StaleConfigKeys.clean(LEGACY, KNOWN);
        String out = result.text();
        check(out.contains("hideRecipeBookmarkButton = true"), "hideRecipeBookmarkButton changed");
        check(out.contains("recipeIngredientDirectoryEnabled = true"),
            "recipeIngredientDirectoryEnabled changed");
        check(out.contains("recipeTreeEnabled = true"), "recipeTreeEnabled changed");
        check(out.contains("disabledRecipeTypes = []"), "disabledRecipeTypes changed");
        check(out.contains("automaticCraftingEnabled = true"), "automaticCraftingEnabled changed");
        check(out.contains("developerMode = true"), "developerMode changed");
    }

    private static void dropsTheCommentThatDescribesTheRemovedKey() {
        StaleConfigKeys.Result result = StaleConfigKeys.clean(LEGACY, KNOWN);
        check(!result.text().contains("Fill the vanilla recipe book"),
            "the comment describing the removed key must go too");
        check(result.text().contains("Developer mode: verbose diagnostic logging."),
            "unrelated comments must survive");
    }

    private static void leavesCleanFileUntouched() {
        String clean = """
            hideRecipeBookmarkButton = true
            recipeIngredientDirectoryEnabled = true
            recipeTreeEnabled = true
            disabledRecipeTypes = []
            automaticCraftingEnabled = true
            developerMode = false
            sophisticatedRestockEnabled = true
            """;
        StaleConfigKeys.Result result = StaleConfigKeys.clean(clean, KNOWN);
        check(!result.changed(), "a clean file must not be reported as changed");
        check(result.text().equals(clean), "a clean file must stay byte-identical");
    }

    private static void fillAddsMissingKeys() {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("sophisticatedRestockEnabled", "sophisticatedRestockEnabled = true");
        StaleConfigKeys.FillResult result = StaleConfigKeys.fill(LEGACY, defaults);
        check(result.changed(), "the missing key must be added");
        check(result.added().equals(List.of("sophisticatedRestockEnabled")),
            "exactly one key expected, got " + result.added());
        check(result.text().contains("sophisticatedRestockEnabled = true"),
            "the added key must carry its default value");
    }

    private static void fillWritesComments() {
        String text = "developerMode = false\n";
        String block = "#说明。\n#English.\nrecipeTreeEnabled = true";
        var result = StaleConfigKeys.fill(text, java.util.Map.of("recipeTreeEnabled", block));
        check(result.changed(), "the missing key must be added");
        check(result.text().contains("#说明。"), "the comment must be written with the key");
        check(result.text().contains("#English."), "both comment lines must be written");
        check(result.text().contains("recipeTreeEnabled = true"), "the key line must follow its comment");
        int commentAt = result.text().indexOf("#说明。");
        int keyAt = result.text().indexOf("recipeTreeEnabled = true");
        check(commentAt < keyAt, "the comment must precede the key, not follow it");
    }

    private static void fillKeepsTopLevelKeysAboveTables() {
        String withTable = """
            developerMode = false
            [advanced]
            someKey = 1
            """;
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("sophisticatedRestockEnabled", "sophisticatedRestockEnabled = true");
        StaleConfigKeys.FillResult result = StaleConfigKeys.fill(withTable, defaults);
        int addedAt = result.text().indexOf("sophisticatedRestockEnabled");
        int tableAt = result.text().indexOf("[advanced]");
        check(addedAt >= 0 && tableAt >= 0, "both the added key and the table must survive");
        check(addedAt < tableAt, "the added top-level key must come before the table header");
    }

    private static void fillIsIdempotent() {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("sophisticatedRestockEnabled", "sophisticatedRestockEnabled = true");
        StaleConfigKeys.FillResult first = StaleConfigKeys.fill(LEGACY, defaults);
        StaleConfigKeys.FillResult second = StaleConfigKeys.fill(first.text(), defaults);
        check(!second.changed(), "filling must be idempotent");
        check(second.text().equals(first.text()), "the second pass must not alter the text");
    }

    private static void refusesToTouchNestedOrQuotedKeys() {
        String tricky = """
            developerMode = false
            [section]
            nested = 1
              indented = 2
            "quoted.key" = 3
            """;
        StaleConfigKeys.Result result = StaleConfigKeys.clean(tricky, KNOWN);
        check(!result.changed(), "nothing here may be treated as a stale top-level key");
        check(result.text().equals(tricky), "the tricky file must stay byte-identical");
    }

    private static void emptyAndNullInputsAreSafe() {
        check(StaleConfigKeys.clean(null, KNOWN).text().isEmpty(), "null input must be safe");
        check(StaleConfigKeys.clean("", KNOWN).text().isEmpty(), "empty input must be safe");
        check(!StaleConfigKeys.clean("developerMode = false", Set.of()).changed(),
            "an empty known-key set must not delete everything");
        check(StaleConfigKeys.fill(null, Map.of()).text().isEmpty(), "null fill input must be safe");
        check(StaleConfigKeys.reblock(null, Map.of()).text().isEmpty(), "null reblock input must be safe");
    }

    private static void reblockRefreshesStaleCommentsButKeepsValues() {
        String file = """
            #配方树配色主题：旧说明
            treeTheme = "dark"
            """;
        Map<String, String> blocks = new LinkedHashMap<>();
        blocks.put("treeTheme",
            "#配方树配色主题：新说明\n"
                + "#Allowed Values: paper, classic, blueprint, blueprint_dark, ae2, dark\n"
                + "treeTheme = \"blueprint\"");
        StaleConfigKeys.SyncResult result = StaleConfigKeys.reblock(file, blocks);
        check(result.changed(), "a stale comment block must be reported as changed");
        check(result.reblocked().equals(List.of("treeTheme")),
            "exactly one key expected, got " + result.reblocked());
        check(result.text().contains("treeTheme = \"dark\""),
            "the player's value must survive the comment rewrite: " + result.text());
        check(result.text().contains("#Allowed Values: paper"),
            "the current comment block must be written");
        check(!result.text().contains("旧说明"), "the stale comment must be gone");
    }

    private static void reblockIsIdempotent() {
        Map<String, String> blocks = new LinkedHashMap<>();
        blocks.put("developerMode", "#开发者模式\n#Allowed Values: a, b\ndeveloperMode = false");
        String once = StaleConfigKeys.reblock("developerMode = true", blocks).text();
        StaleConfigKeys.SyncResult twice = StaleConfigKeys.reblock(once, blocks);
        check(!twice.changed(), "a synced file must not be reported as changed");
        check(twice.text().equals(once), "a synced file must stay byte-identical");
        check(once.contains("developerMode = true"), "the value must be untouched");
    }

    private static void reblockLeavesTablesAlone() {
        String file = """
            [section]
            developerMode = false
            """;
        Map<String, String> blocks = new LinkedHashMap<>();
        blocks.put("developerMode", "#开发者模式\ndeveloperMode = false");
        StaleConfigKeys.SyncResult result = StaleConfigKeys.reblock(file, blocks);
        check(!result.changed(), "table-scoped keys must not be re-commented");
        check(result.text().equals(file), "the file must stay byte-identical");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
