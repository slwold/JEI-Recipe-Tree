package com.slwold.jeirecipetree.client;

import com.slwold.jeirecipetree.JERT;
import com.slwold.jeirecipetree.compat.sia.SiaMaterialBridge;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

public final class Diagnostics {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final List<String> METRICS = List.of(Perf.ANALYZE, Perf.RENDER, Perf.STORAGE, Perf.FETCH);

    private static final int UNKNOWN = -1;

    private Diagnostics() {
    }

    public static Path dump() {
        LocalDateTime now = LocalDateTime.now();
        StringBuilder report = new StringBuilder(2048);
        appendEnvironment(report, now);
        appendConfig(report);
        appendIntegration(report);
        appendJeiCapabilities(report);
        appendTreeSession(report);
        appendAnalysisTrace(report);
        appendPerformance(report);
        appendSupplySemantics(report);
        try {
            Path target = write(report.toString(), now);
            JERTLog.summary("diagnostics: {}", target);
            return target;
        } catch (IOException | RuntimeException error) {
            JERTLog.error("diagnostics: write failed ({})", error.toString());
            return null;
        }
    }

    private static void appendEnvironment(StringBuilder report, LocalDateTime now) {
        report.append("JERT diagnostics\n");
        report.append("generated: ").append(TIMESTAMP.format(now)).append('\n');
        report.append(JERT.startupSummary()).append('\n');
        entry(report, "screen", Diagnostics::describeScreen);
    }

    private static void appendConfig(StringBuilder report) {
        section(report, "config");
        entry(report, "hideRecipeBookmarkButton", () -> SafeConfig.hideRecipeBookmarkButton());
        entry(report, "recipeIngredientDirectoryEnabled", () -> SafeConfig.recipeIngredientDirectoryEnabled());
        entry(report, "recipeTreeEnabled", () -> SafeConfig.recipeTreeEnabled());
        entry(report, "disabledRecipeTypes", () -> SafeConfig.disabledRecipeTypes());
        entry(report, "automaticCraftingEnabled", () -> SafeConfig.automaticCraftingEnabled());
        entry(report, "developerMode", () -> SafeConfig.developerMode());
        entry(report, "sophisticatedRestockEnabled", () -> SafeConfig.sophisticatedRestockEnabled());

        entry(report, "hygiene", () -> JERT.configHygieneSummary());
    }

    private static void appendIntegration(StringBuilder report) {
        section(report, "integration");
        entry(report, "storageDrivers.active", StorageDriverRegistry::activeDrivers);
        entry(report, "storageDrivers.failed", StorageDriverRegistry::failedDrivers);
        entry(report, "storageDrivers.lastUsed", StorageDriverRegistry::lastUsedDriver);
        entry(report, "storageDrivers.currentMenu",
            () -> StorageDriverRegistry.driverFor(Ae2StorageIntegration.activeMenu()));
        entry(report, "craftQueue", RecipeTreeTransfer::queueStatus);
        entry(report, "transferPhase", RecipeTreeTransfer::phase);
        entry(report, "transferStep", RecipeTreeTransfer::stepStatus);
        entry(report, "sia.available", SiaMaterialBridge::available);
        entry(report, "fetch.available", MaterialFetch::isAvailable);
        entry(report, "fetch.pendingBatches", MaterialFetch::pendingBatches);
    }

    private static void appendJeiCapabilities(StringBuilder report) {
        section(report, "jei");
        entry(report, "capabilities", JeiCapabilities::summary);
        entry(report, "inputTargetPresent", () -> JeiCapabilities.present(INPUT_TARGET_CLASS));
        entry(report, "recipeTransferServicePresent", JeiCapabilities::recipeTransferServicePresent);
    }

    private static final String INPUT_TARGET_CLASS = "mezz.jei.common.input.IInputTarget";

    private static void appendTreeSession(StringBuilder report) {
        section(report, "tree");
        RecipeTreeData.Tree tree = RecipeTreeSession.craftingTree();
        if (tree == null) {
            report.append("  craftingTree=none\n");
            return;
        }
        entry(report, "craftingMode", tree::craftingMode);
        entry(report, "hasRoot", () -> tree.root() != null);
        entry(report, "costs", () -> count(tree, true));
        entry(report, "leftovers", () -> count(tree, false));
    }

    private static int count(RecipeTreeData.Tree tree, boolean costs) {
        try {
            RecipeTreeData.Analysis analysis = tree.analyze();
            if (analysis == null) {
                return UNKNOWN;
            }
            List<RecipeTreeData.Cost> rows = costs ? analysis.costs() : analysis.leftovers();
            return rows == null ? UNKNOWN : rows.size();
        } catch (RuntimeException error) {
            return UNKNOWN;
        }
    }

    private static void appendAnalysisTrace(StringBuilder report) {
        section(report, "analysis trace");
        report.append("  format=jert-analysis-v1\n");
        RecipeTreeData.Tree tree = RecipeTreeSession.craftingTree();
        if (tree == null) {
            report.append("  tree=none\n");
            return;
        }
        try {
            report.append("  craftingMode=").append(tree.craftingMode()).append('\n');
            report.append("  batches=").append(tree.batches()).append('\n');
            RecipeTreeData.Analysis analysis = tree.analyze();
            if (analysis != null) {
                appendCosts(report, "cost", analysis.costs());
                appendCosts(report, "leftover", analysis.leftovers());
            }
            appendSteps(report, tree.craftingSteps());
        } catch (RuntimeException | LinkageError error) {
            report.append("  incomplete=").append(error.getClass().getSimpleName()).append('\n');
        }
    }

    private static void appendCosts(StringBuilder report, String label, List<RecipeTreeData.Cost> rows) {
        List<String> lines = new java.util.ArrayList<>();
        if (rows != null) {
            for (RecipeTreeData.Cost cost : rows) {
                lines.add("  " + label + "|" + keyOf(cost.stack()) + "|"
                    + cost.required() + "|" + cost.supplied() + "\n");
            }
        }
        java.util.Collections.sort(lines);
        report.append("  ").append(label).append("s=").append(lines.size()).append('\n');
        for (String line : lines) {
            report.append(line);
        }
    }

    private static void appendSteps(StringBuilder report, List<RecipeTreeData.CraftStep> steps) {
        List<String> lines = new java.util.ArrayList<>();
        if (steps != null) {
            for (RecipeTreeData.CraftStep step : steps) {
                lines.add("  step|" + keyOf(step.stack()) + "|"
                    + step.batches() + "|" + step.remaining() + "|"
                    + step.total() + "|" + step.progress() + "\n");
            }
        }
        java.util.Collections.sort(lines);
        report.append("  steps=").append(lines.size()).append('\n');
        for (String line : lines) {
            report.append(line);
        }
    }

    private static String keyOf(net.minecraft.world.item.ItemStack stack) {
        return RecipeTreeData.ingredientKey(stack);
    }

    private static void appendPerformance(StringBuilder report) {
        section(report, "performance (last warn, ms ago)");
        long now = System.nanoTime();
        for (String metric : METRICS) {
            long lastWarn = Perf.lastWarnNanos(metric);
            String value = lastWarn == Perf.DISABLED
                ? "never"
                : String.format(Locale.ROOT, "%.0f", (now - lastWarn) / 1_000_000.0);
            report.append("  ").append(metric).append('=').append(value).append('\n');
        }
    }

    private static void appendSupplySemantics(StringBuilder report) {
        section(report, "supply semantics");
        report.append("  playerSlots=0..").append(SupplyLedger.PLAYER_SUPPLY_SLOTS - 1).append('\n');
        report.append("  containerExcludes=result slot, crafting grid, ae2 pattern slot\n");
        report.append("  craftingGridCounted=false\n");
        report.append("  equippedArmorOffhandCounted=false\n");
    }

    private static String describeScreen() {
        try {
            var screen = Minecraft.getInstance().screen;
            return screen == null ? "none" : screen.getClass().getName();
        } catch (RuntimeException error) {
            return "<unavailable>";
        }
    }

    private static void section(StringBuilder report, String title) {
        report.append('\n').append('[').append(title).append("]\n");
    }

    private static void entry(StringBuilder report, String key, Supplier<?> value) {
        String rendered;
        try {
            rendered = String.valueOf(value.get());
        } catch (RuntimeException error) {
            rendered = "<unavailable: " + error.getClass().getSimpleName() + ">";
        }
        report.append("  ").append(key).append('=').append(rendered).append('\n');
    }

    private static Path write(String content, LocalDateTime now) throws IOException {
        Path directory = FMLPaths.GAMEDIR.get().resolve("logs");
        Files.createDirectories(directory);
        Path target = directory.resolve("jert-diagnostics-" + FILE_STAMP.format(now) + ".txt");
        Files.writeString(target, content, StandardCharsets.UTF_8);
        return target;
    }
}
