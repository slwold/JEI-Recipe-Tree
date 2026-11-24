package com.slwold.jeirecipetree.client;

import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

final class StoredRecipeRefs {

    private static final Map<String, Map<String, RecipeTreeData.RecipeRef>> INDEX = new HashMap<>();

    private StoredRecipeRefs() {
    }

    static void clearCaches() {
        INDEX.clear();
    }

    static Optional<RecipeTreeData.RecipeRef> resolve(String id) {
        int split = id == null ? -1 : id.indexOf('|');
        if (split <= 0 || split == id.length() - 1) {
            return Optional.empty();
        }
        String typeUid = id.substring(0, split);
        String recipeId = id.substring(split + 1);
        if (recipeId.startsWith("runtime/")) {

            return Optional.empty();
        }
        return Optional.ofNullable(indexFor(typeUid).get(recipeId));
    }

    private static Map<String, RecipeTreeData.RecipeRef> indexFor(String typeUid) {
        Map<String, RecipeTreeData.RecipeRef> cached = INDEX.get(typeUid);
        if (cached != null) {
            return cached;
        }
        IJeiRuntime runtime = DirectoryRecipePlugin.getJeiRuntime();
        if (runtime == null) {

            return Map.of();
        }
        long start = System.nanoTime();
        Map<String, RecipeTreeData.RecipeRef> index = buildIndex(runtime.getRecipeManager(), typeUid);
        INDEX.put(typeUid, index);

        JERTLog.summary("recipe refs: indexed {} recipe(s) for {} in {} ms",
            index.size(), typeUid, String.format(java.util.Locale.ROOT, "%.1f", (System.nanoTime() - start) / 1_000_000.0));
        return index;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Map<String, RecipeTreeData.RecipeRef> buildIndex(IRecipeManager manager, String typeUid) {
        Map<String, RecipeTreeData.RecipeRef> index = new HashMap<>();
        ResourceLocation uid = ResourceLocation.tryParse(typeUid);
        if (uid == null) {
            return index;
        }
        Optional<RecipeType<?>> type = manager.getRecipeType(uid);
        if (type.isEmpty()) {
            return index;
        }

        List<IRecipeCategory<?>> categories = manager.createRecipeCategoryLookup()
            .limitTypes(List.<RecipeType<?>>of(type.get()))
            .get()
            .toList();
        if (categories.isEmpty()) {
            return index;
        }
        try (Stream<?> recipes = manager.createRecipeLookup((RecipeType) type.get()).get()) {
            recipes.forEach(recipe -> indexOne(index, categories, recipe));
        }
        return index;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void indexOne(
        Map<String, RecipeTreeData.RecipeRef> index,
        List<IRecipeCategory<?>> categories,
        Object recipe
    ) {
        for (IRecipeCategory<?> category : categories) {
            ResourceLocation name;
            try {
                name = ((IRecipeCategory) category).getRegistryName(recipe);
            } catch (RuntimeException | LinkageError ignored) {

                continue;
            }
            if (name != null) {
                index.putIfAbsent(name.toString(), RecipeLookup.ref(category, recipe));
                return;
            }
        }
    }
}
