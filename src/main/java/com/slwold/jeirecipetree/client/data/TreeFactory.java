package com.slwold.jeirecipetree.client.data;

import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;
import com.slwold.jeirecipetree.client.storage.*;
import com.slwold.jeirecipetree.client.transfer.*;
import com.slwold.jeirecipetree.client.ui.*;
import com.slwold.jeirecipetree.client.storage.Ae2StorageIntegration;
import com.slwold.jeirecipetree.core.Amounts;
import com.slwold.jeirecipetree.core.CostCalculator;
import com.slwold.jeirecipetree.core.CostVector;
import com.slwold.jeirecipetree.core.CraftStepOrder;
import com.slwold.jeirecipetree.core.ItemRef;
import com.slwold.jeirecipetree.core.RecipeRecord;
import com.slwold.jeirecipetree.core.RecipeSource;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class TreeFactory {
    private TreeFactory() {
    }

    public static Optional<RecipeTreeData.Tree> build(IRecipeLayoutDrawable<?> rootLayout) {
        return build(rootLayout, null);
    }

    public static Optional<RecipeTreeData.Tree> fromLibrary(RecipeRecord record) {
        RecipeTreeData.RecipeSnapshot snapshot = StoredRecipes.snapshotOf(record);
        if (snapshot == null || snapshot.outputs().isEmpty()) {
            JERTLog.warn("recipe tree: library recipe {} has no usable output, cannot be a root",
                record == null ? "<null>" : record.id());
            return Optional.empty();
        }
        return Optional.of(new RecipeTreeData.Tree(snapshot, snapshot.outputs().getFirst()));
    }

    public static Optional<RecipeTreeData.Tree> build(IRecipeLayoutDrawable<?> rootLayout, ItemStack selectedOutput) {
        long perfStart = Perf.startAlways();
        int layoutsBefore = RecipeLookup.layoutBuildCount();
        long layoutNanosBefore = RecipeLookup.layoutBuildNanoTime();
        int snapshotsBefore = RecipeLookup.snapshotBuildCount();
        long snapshotNanosBefore = RecipeLookup.snapshotBuildNanoTime();
        long keysBefore = ItemKeys.keyCallCount();
        int representativeBefore = RecipeLookup.representativeFallbackCount();
        int lookupsBefore = RecipeLookup.candidateLookupCount();
        try {
            return buildTree(rootLayout, selectedOutput);
        } finally {
            Perf.endAdaptive(Perf.BUILD, perfStart);
            int layouts = RecipeLookup.layoutBuildCount() - layoutsBefore;
            int snapshots = RecipeLookup.snapshotBuildCount() - snapshotsBefore;
            JERTLog.summary("tree build: {} layouts ({} ms), {} snapshots ({} ms), {} item keys, {} non-item inputs",
                layouts,
                ms(RecipeLookup.layoutBuildNanoTime() - layoutNanosBefore),
                snapshots,
                ms(RecipeLookup.snapshotBuildNanoTime() - snapshotNanosBefore),
                ItemKeys.keyCallCount() - keysBefore,
                RecipeLookup.representativeFallbackCount() - representativeBefore);
            JERTLog.summary("tree scan: {} total scans, {} candidate lookups this build",
                SupplyLedger.scanCount(), RecipeLookup.candidateLookupCount() - lookupsBefore);
        }
    }

    public static String ms(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1_000_000.0);
    }

    public static Optional<RecipeTreeData.Tree> buildTree(IRecipeLayoutDrawable<?> rootLayout, ItemStack selectedOutput) {
        Optional<RecipeTreeData.RecipeSnapshot> root = RecipeLookup.snapshot(rootLayout);
        if (root.isEmpty() || root.get().outputs().isEmpty()) {
            return Optional.empty();
        }
        RecipeTreeData.RecipeSnapshot rootSnapshot = root.get();
        ItemStack output = RecipeTreeData.copyStack(rootSnapshot.outputs().getFirst());
        if (selectedOutput != null && !selectedOutput.isEmpty()) {
            String selectedKey = RecipeTreeData.ingredientKey(selectedOutput);
            output = rootSnapshot.outputs().stream()
                .filter(candidate -> RecipeTreeData.ingredientKey(candidate).equals(selectedKey))
                .findFirst()
                .map(RecipeTreeData::copyStack)
                .orElse(output);
        }

        return Optional.of(new RecipeTreeData.Tree(rootSnapshot, output));
    }

    public static Optional<RecipeTreeData.Tree> restore(
        RecipeTreeData.RecipeRef rootRef,
        String outputKey,
        int outputCount,
        long batches,
        boolean craftingMode
    ) {
        if (rootRef == null) {
            return Optional.empty();
        }
        return restore(RecipeLookup.snapshot(rootRef), outputKey, outputCount, batches, craftingMode);
    }

    public static Optional<RecipeTreeData.Tree> restore(
        RecipeTreeData.RecipeSnapshot rootSnapshot,
        String outputKey,
        int outputCount,
        long batches,
        boolean craftingMode
    ) {
        if (rootSnapshot == null || rootSnapshot.outputs().isEmpty()) {
            return Optional.empty();
        }
        ItemStack output = rootSnapshot.outputs().stream()
            .filter(candidate -> RecipeTreeData.ingredientKey(candidate).equals(outputKey))
            .findFirst()
            .orElse(rootSnapshot.outputs().getFirst())
            .copy();

        if (outputCount > 0 && !IngredientDisplay.measured(output)) {
            output.setCount(Math.min(output.getMaxStackSize(), outputCount));
        }
        RecipeTreeData.Tree restored = new RecipeTreeData.Tree(rootSnapshot, output);
        restored.batches = Math.max(1, Math.min(1_000_000L, batches));
        restored.craftingMode = craftingMode;
        restored.invalidateAnalysis();
        return Optional.of(restored);
    }

}
