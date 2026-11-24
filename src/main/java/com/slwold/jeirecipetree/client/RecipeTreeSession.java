package com.slwold.jeirecipetree.client;

import com.google.gson.JsonObject;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class RecipeTreeSession {
    private static RecipeTreeData.Tree tree;

    private static RecipeTreeData.Tree craftingTree;

    private static boolean transientView;
    private static String pendingIngredient;
    private static String pendingRecipeKey;
    private static Screen resolutionParent;

    private static boolean runtimeReloadPending;
    private static Object runtimeReloadLevel;
    private static boolean persistenceChecked;
    private static boolean restoringPersistence;

    private static final long PERSISTENCE_RETRY_NANOS = 500_000_000L;
    private static long lastPersistenceAttemptNanos = Long.MIN_VALUE;

    private RecipeTreeSession() {
    }

    public static RecipeTreeData.Tree tree() {
        ensureRestored();
        return tree;
    }

    public static RecipeTreeData.Tree craftingTree() {
        ensureRestored();
        if (craftingTree != null) {
            return craftingTree;
        }
        return tree != null && tree.craftingMode() ? tree : null;
    }

    public static boolean setGoal(IRecipeLayoutDrawable<?> layout) {
        return replaceGoal(RecipeTreeData.build(layout).orElse(null));
    }

    public static boolean setGoalFromRecord(com.slwold.jeirecipetree.core.RecipeRecord record) {
        return replaceGoal(RecipeTreeData.fromLibrary(record).orElse(null));
    }

    private static boolean replaceGoal(RecipeTreeData.Tree replacement) {
        if (replacement == null) {

            return false;
        }

        if (!transientView && tree != null && tree.craftingMode()) {
            craftingTree = tree;
        }
        if (craftingTree == null) {
            RecipeTreeTransfer.cancel();
        }
        tree = replacement;
        transientView = craftingTree != null;
        pendingIngredient = null;
        pendingRecipeKey = null;
        resolutionParent = null;
        RecipeTreeFavorites.refreshNow();
        persistIfCrafting();
        return tree != null;
    }

    public static void clear() {
        RecipeTreeTransfer.cancel();
        tree = null;
        craftingTree = null;
        transientView = false;
        runtimeReloadPending = false;
        runtimeReloadLevel = null;
        pendingIngredient = null;
        pendingRecipeKey = null;
        resolutionParent = null;
        deletePersistentState();
        persistenceChecked = true;
        lastPersistenceAttemptNanos = Long.MIN_VALUE;
        SupplyLedger.setCraftingMode(false);
        RecipeTreeFavorites.refreshNow();
    }

    public static void suspendForRuntimeReload() {

        persistIfCrafting();
        runtimeReloadPending = true;
        runtimeReloadLevel = Minecraft.getInstance().level;
        RecipeTreeTransfer.cancel();
        cancelResolution();
        if (runtimeReloadLevel == null) {
            discardRuntimeStatePreservingPersistence();
        }
    }

    public static void resumeAfterRuntimeReload() {
        if (!runtimeReloadPending) {
            return;
        }
        runtimeReloadPending = false;
        Object currentLevel = Minecraft.getInstance().level;
        boolean sameWorld = runtimeReloadLevel != null && runtimeReloadLevel == currentLevel;
        boolean hadWorld = runtimeReloadLevel != null;
        runtimeReloadLevel = null;
        if (!hadWorld || !sameWorld) {

            discardRuntimeStatePreservingPersistence();
            RecipeTreeFavorites.refreshNow();
            return;
        }
        try {
            if (tree != null) {
                tree.rebuild();
            }
            if (craftingTree != null && craftingTree != tree) {
                craftingTree.rebuild();
            }
        } catch (RuntimeException ignored) {

        }
        RecipeTreeFavorites.refreshNow();
        persistIfCrafting();
    }

    public static void beginResolution(RecipeTreeData.Node node, Screen parent) {
        pendingIngredient = node == null ? null : node.ingredientKey();
        pendingRecipeKey = node == null || node.recipe() == null ? null : node.recipe().ref().key();
        resolutionParent = pendingIngredient == null ? null : parent;
    }

    public static void cancelResolution() {
        pendingIngredient = null;
        pendingRecipeKey = null;
        resolutionParent = null;
    }

    public static boolean isResolving() {
        return pendingIngredient != null && tree != null;
    }

    public static boolean canResolve(IRecipeLayoutDrawable<?> layout) {
        if (!isResolving() || pendingIngredient == null) {
            return false;
        }

        return RecipeLookup.outputStacks(layout).stream()
            .map(RecipeTreeData::ingredientKey)
            .anyMatch(pendingIngredient::equals);
    }

    public static boolean isCurrentResolution(IRecipeLayoutDrawable<?> layout) {
        if (!canResolve(layout) || pendingRecipeKey == null) {
            return false;
        }
        RecipeTreeData.RecipeRef ref = RecipeLookup.refOf(layout);
        return ref != null && ref.key().equals(pendingRecipeKey);
    }

    public static boolean resolve(IRecipeLayoutDrawable<?> layout, String recordId) {
        if (!canResolve(layout) || tree == null || pendingIngredient == null) {
            return false;
        }

        RecipeTreeData.RecipeRef ref = recordId == null || recordId.isEmpty()
            ? RecipeLookup.refOf(layout)
            : new RecipeTreeData.RecipeRef(null, null, recordId, "");
        if (ref != null) {
            tree.bindResolution(pendingIngredient, ref);
        }
        tree.rebuild();
        pendingIngredient = null;
        pendingRecipeKey = null;
        RecipeTreeFavorites.refreshNow();
        persistIfCrafting();
        return true;
    }

    public static boolean selectInput(RecipeTreeData.Node node, ItemStack stack) {
        if (tree == null || node == null || stack == null || stack.isEmpty()) {
            return false;
        }
        String selectedKey = RecipeTreeData.ingredientKey(stack);
        boolean valid = node.alternatives().stream()
            .anyMatch(alternative -> RecipeTreeData.ingredientKey(alternative).equals(selectedKey));
        if (!valid) {
            return false;
        }
        tree.selectInput(node, stack);
        RecipeTreeFavorites.refreshNow();
        persistIfCrafting();
        return true;
    }

    public static Screen takeResolutionParent() {
        Screen parent = resolutionParent;
        resolutionParent = null;
        return parent;
    }

    public static void clearResolution(RecipeTreeData.Node node) {
        if (tree != null && node != null) {
            tree.clearResolution(node.ingredientKey());
            RecipeTreeFavorites.refreshNow();
            persistIfCrafting();
        }
    }

    public static void clearInputSelection(RecipeTreeData.Node node) {
        if (tree != null && node != null) {
            tree.clearInputSelection(node);
            RecipeTreeFavorites.refreshNow();
            persistIfCrafting();
        }
    }

    public static void setCraftingMode(boolean enabled) {
        if (tree == null) {
            return;
        }
        if (enabled) {
            if (transientView || (craftingTree != null && craftingTree != tree)) {
                RecipeTreeTransfer.cancel();
            }
            craftingTree = tree;
            transientView = false;
            tree.setCraftingMode(true);
            persistIfCrafting();
        } else {
            tree.setCraftingMode(false);
            if (craftingTree == tree) {
                craftingTree = null;
            }
            transientView = false;
            deletePersistentState();
            persistenceChecked = true;
        }

        SupplyLedger.setCraftingMode(tree != null && tree.craftingMode());
        RecipeTreeFavorites.refreshNow();
    }

    public static void restoreCraftingTree() {
        if (transientView && craftingTree != null) {
            tree = craftingTree;
            craftingTree = null;
        }
        transientView = false;
        SupplyLedger.setCraftingMode(tree != null && tree.craftingMode());
        RecipeTreeFavorites.refreshNow();
    }

    public static boolean autoResolve(RecipeTreeData.Node node) {
        if (tree == null || node == null) {
            return false;
        }
        List<RecipeTreeData.RecipeSnapshot> candidates = RecipeLookup.candidateSnapshots(node.stack()).stream()
            .filter(candidate -> !RecipeTreeDefaults.isExcludedFromAutomaticSelection(
                candidate.ref().registryId()
            ))
            .toList();
        if (candidates.isEmpty()) {
            return false;
        }

        tree.resolve(node.ingredientKey(), candidates.getFirst().ref());
        RecipeTreeFavorites.refreshNow();
        persistIfCrafting();
        return true;
    }

    public static void refreshDefaults() {
        if (tree != null) {
            tree.rebuild();
        }
        if (craftingTree != null && craftingTree != tree) {
            craftingTree.rebuild();
        }
        if (tree != null || craftingTree != null) {
            RecipeTreeFavorites.refreshNow();
            persistIfCrafting();
        }
    }

    static void savePersistentState() {
        persistIfCrafting();
    }

    private static void ensureRestored() {
        long now = System.nanoTime();
        if (persistenceChecked || restoringPersistence || tree != null
            || Minecraft.getInstance().level == null
            || DirectoryRecipePlugin.getJeiRuntime() == null
            || (lastPersistenceAttemptNanos != Long.MIN_VALUE
                && now - lastPersistenceAttemptNanos >= 0L
                && now - lastPersistenceAttemptNanos < PERSISTENCE_RETRY_NANOS)) {
            return;
        }
        lastPersistenceAttemptNanos = now;
        restoringPersistence = true;

        long slowStart = Perf.startAlways();
        int layoutsBefore = RecipeLookup.layoutBuildCount();
        long layoutNanosBefore = RecipeLookup.layoutBuildNanoTime();
        int snapshotsBefore = RecipeLookup.snapshotBuildCount();
        long snapshotNanosBefore = RecipeLookup.snapshotBuildNanoTime();
        long keyCallsBefore = ItemKeys.keyCallCount();
        int lookupsBefore = RecipeLookup.candidateLookupCount();
        try {
            Path file = persistentFile();
            if (!Files.isRegularFile(file)) {
                persistenceChecked = true;
                return;
            }
            JsonObject saved;
            try {
                saved = SessionPersistence.read(file).orElse(null);
            } catch (Exception ignored) {

                persistenceChecked = true;
                return;
            }
            if (saved == null || !saved.has("crafting") || !saved.get("crafting").getAsBoolean()) {
                persistenceChecked = true;
                return;
            }
            String recipeKey = string(saved, "recipe");
            String outputKey = string(saved, "output");
            String itemId = string(saved, "item");
            String categoryKey = string(saved, "category");
            String registryId = string(saved, "registry");
            if (recipeKey.isEmpty() || outputKey.isEmpty()) {
                persistenceChecked = true;
                return;
            }
            ItemStack lookup = lookupStack(outputKey, itemId);
            if (lookup.isEmpty()) {

                return;
            }
            int outputCount = Math.max(1, integer(saved, "count", 1));
            long batches = Math.max(1L, longValue(saved, "batches", 1L));

            RecipeTreeData.RecipeSnapshot libraryRoot = StoredRecipes.byId(recipeKey);
            Optional<RecipeTreeData.Tree> restored = libraryRoot == null
                ? Optional.empty()
                : RecipeTreeData.restore(libraryRoot, outputKey, outputCount, batches, true);
            if (libraryRoot == null) {
                List<RecipeTreeData.RecipeRef> candidates = RecipeLookup.candidates(lookup);
                if (candidates.isEmpty()) {

                    RecipeTreeData.clearCaches();
                    return;
                }
                RecipeTreeData.RecipeRef ref = candidates.stream()
                    .filter(candidate -> matchesPersistedRecipe(
                        candidate, recipeKey, categoryKey, registryId
                    ))
                    .findFirst()
                    .orElse(null);
                if (ref == null) {

                    persistenceChecked = true;
                    return;
                }
                restored = RecipeTreeData.restore(ref, outputKey, outputCount, batches, true);
            }
            if (restored.isEmpty()) {

                RecipeTreeData.clearCaches();
                return;
            }
            tree = restored.get();
            craftingTree = tree;
            transientView = false;
            persistenceChecked = true;
            applyPersistedResolutions(saved);

            SupplyLedger.setCraftingMode(true);
            RecipeTreeFavorites.refreshNow();
        } catch (Exception ignored) {

        } finally {
            restoringPersistence = false;
            Perf.endAdaptive(Perf.RESTORE, slowStart);

            int layouts = RecipeLookup.layoutBuildCount() - layoutsBefore;
            if (layouts > 0) {
                long layoutNanos = RecipeLookup.layoutBuildNanoTime() - layoutNanosBefore;
                JERTLog.summary("restore layouts: {} built in {} ms (avg {} ms)",
                    layouts,
                    String.format(java.util.Locale.ROOT, "%.1f", layoutNanos / 1_000_000.0),
                    String.format(java.util.Locale.ROOT, "%.2f", layoutNanos / 1_000_000.0 / layouts));
            }

            int snapshots = RecipeLookup.snapshotBuildCount() - snapshotsBefore;
            long snapshotNanos = RecipeLookup.snapshotBuildNanoTime() - snapshotNanosBefore;
            long keyCalls = ItemKeys.keyCallCount() - keyCallsBefore;
            if (snapshots > 0) {
                JERTLog.summary("restore snapshots: {} extracted in {} ms (avg {} ms), {} item keys",
                    snapshots,
                    String.format(java.util.Locale.ROOT, "%.1f", snapshotNanos / 1_000_000.0),
                    String.format(java.util.Locale.ROOT, "%.2f", snapshotNanos / 1_000_000.0 / snapshots),
                    keyCalls);
            }

            JERTLog.summary("restore scans: {} total, {} candidate lookups",
                SupplyLedger.scanCount(), RecipeLookup.candidateLookupCount() - lookupsBefore);
        }
    }

    private static void applyPersistedResolutions(JsonObject saved) {
        if (tree == null || !saved.has("resolutions")) {
            return;
        }
        try {
            JsonObject bound = saved.getAsJsonObject("resolutions");
            if (bound == null || bound.isEmpty()) {
                return;
            }
            java.util.Map<String, String> keys = new java.util.LinkedHashMap<>();
            bound.entrySet().forEach(entry -> keys.put(entry.getKey(), entry.getValue().getAsString()));
            tree.applyResolutions(keys);
            tree.rebuild();
        } catch (RuntimeException ignored) {

        }
    }

    private static boolean matchesPersistedRecipe(
        RecipeTreeData.RecipeRef candidate,
        String recipeKey,
        String categoryKey,
        String registryId
    ) {
        if (recipeKey.equals(candidate.key())) {
            return true;
        }
        if (registryId.isEmpty() || !registryId.equals(candidate.registryId())) {
            return false;
        }
        return categoryKey.isEmpty()
            || categoryKey.equals(candidate.category().getRecipeType().getUid().toString());
    }

    private static ItemStack lookupStack(String outputKey, String itemId) {
        if (outputKey.startsWith("fluid:")) {
            return FluidRecipeCompat.representativeForKey(outputKey, 1000).orElse(ItemStack.EMPTY);
        }
        ResourceLocation id = ResourceLocation.tryParse(itemId.isEmpty() ? outputKey : itemId);
        if (id == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(id));
        return stack.isEmpty() ? ItemStack.EMPTY : stack;
    }

    private static void persistIfCrafting() {
        RecipeTreeData.Tree active = craftingTree;
        if (active == null && tree != null && tree.craftingMode()) {
            active = tree;
        }
        if (active == null || !active.craftingMode() || active.root() == null || active.root().recipe() == null) {
            return;
        }
        SessionPersistence.write(persistentFile(), active);
        persistenceChecked = true;
    }

    private static void deletePersistentState() {
        SessionPersistence.delete(persistentFile());
    }

    private static void discardRuntimeStatePreservingPersistence() {
        tree = null;
        craftingTree = null;
        transientView = false;
        pendingIngredient = null;
        pendingRecipeKey = null;
        resolutionParent = null;

        persistenceChecked = false;
        lastPersistenceAttemptNanos = Long.MIN_VALUE;
    }

    private static Path persistentFile() {
        return SessionPersistence.file(Minecraft.getInstance().gameDirectory.toPath());
    }

    private static String string(JsonObject object, String key) {
        return SessionPersistence.string(object, key);
    }

    private static int integer(JsonObject object, String key, int fallback) {
        return SessionPersistence.integer(object, key, fallback);
    }

    private static long longValue(JsonObject object, String key, long fallback) {
        return SessionPersistence.longValue(object, key, fallback);
    }

}
