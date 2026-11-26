package com.slwold.jeirecipetree.client.data;
import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;
import com.slwold.jeirecipetree.client.storage.*;
import com.slwold.jeirecipetree.client.transfer.*;
import com.slwold.jeirecipetree.client.ui.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RecipeTreeDefaults {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, String> RESOLUTIONS = new HashMap<>();
    private static final Set<String> DISABLED_RECIPES = new HashSet<>();
    private static final Map<String, Status> STATUS_CACHE = new HashMap<>();
    private static boolean loaded;

    private RecipeTreeDefaults() {
    }

    public enum Status {
        EMPTY,
        PARTIAL,
        FULL
    }

    public static void reload() {
        STATUS_CACHE.clear();
        RESOLUTIONS.clear();
        DISABLED_RECIPES.clear();
        loadUserData();
        loaded = true;
    }

    public static RecipeTreeData.RecipeRef getPreferredRecipe(
        ItemStack output,
        List<RecipeTreeData.RecipeRef> candidates
    ) {
        ensureLoaded();
        String ingredientKey = RecipeTreeData.ingredientKey(output);
        String resolution = RESOLUTIONS.get(ingredientKey);
        if (resolution != null) {
            for (RecipeTreeData.RecipeRef candidate : candidates) {
                if (candidate.key().equals(resolution) && !DISABLED_RECIPES.contains(candidate.key())) {
                    return candidate;
                }
            }
        }

        return null;
    }

    public static Status getStatus(RecipeTreeData.RecipeSnapshot recipe) {
        if (recipe == null || recipe.outputs().isEmpty()) {
            return Status.EMPTY;
        }
        return getStatus(recipe.ref(), recipe.outputs());
    }

    public static Status getStatus(RecipeTreeData.RecipeRef ref, java.util.List<ItemStack> outputs) {
        if (ref == null || outputs == null || outputs.isEmpty()) {
            return Status.EMPTY;
        }
        ensureLoaded();
        return STATUS_CACHE.computeIfAbsent(ref.key(), ignored -> calculateStatus(ref, outputs));
    }

    private static Status calculateStatus(RecipeTreeData.RecipeSnapshot recipe) {
        return calculateStatus(recipe.ref(), recipe.outputs());
    }

    private static Status calculateStatus(RecipeTreeData.RecipeRef ref, java.util.List<ItemStack> outputs) {
        int matches = 0;
        Set<String> visited = new HashSet<>();
        int total = 0;
        for (ItemStack output : outputs) {
            String key = RecipeTreeData.ingredientKey(output);
            if (!visited.add(key)) {
                continue;
            }
            total++;
            RecipeTreeData.RecipeRef preferred = getPreferredRecipe(output, RecipeLookup.candidates(output));
            if (preferred != null && preferred.key().equals(ref.key())) {
                matches++;
            }
        }
        if (matches == 0) {
            return Status.EMPTY;
        }
        return matches >= total ? Status.FULL : Status.PARTIAL;
    }

    public static void toggle(RecipeTreeData.RecipeSnapshot recipe) {
        if (recipe == null) {
            return;
        }
        toggle(recipe.ref(), recipe.outputs());
    }

    public static void toggle(RecipeTreeData.RecipeRef ref, java.util.List<ItemStack> outputs) {
        if (ref == null || outputs == null || outputs.isEmpty()) {
            return;
        }
        Status status = getStatus(ref, outputs);
        String recipeKey = ref.key();
        if (status == Status.FULL) {
            for (ItemStack output : outputs) {
                RESOLUTIONS.remove(RecipeTreeData.ingredientKey(output), recipeKey);
            }
        } else {
            DISABLED_RECIPES.remove(recipeKey);
            for (ItemStack output : outputs) {
                RESOLUTIONS.put(RecipeTreeData.ingredientKey(output), recipeKey);
            }
        }
        STATUS_CACHE.clear();
        saveUserData();
        RecipeTreeSession.refreshDefaults();
    }

    public static Status cachedStatus(RecipeTreeData.RecipeRef ref) {
        return ref == null ? null : STATUS_CACHE.get(ref.key());
    }

    public static void warmup() {
        ensureLoaded();
    }

    private static void ensureLoaded() {
        if (!loaded) {
            reload();
        }
    }

    private static void loadUserData() {
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) {
                return;
            }
            JsonObject resolutions = root.getAsJsonObject("resolutions");
            if (resolutions != null) {
                for (Map.Entry<String, JsonElement> entry : resolutions.entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        RESOLUTIONS.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }
            JsonArray disabled = root.getAsJsonArray("disabled");
            if (disabled != null) {
                for (JsonElement element : disabled) {
                    if (element.isJsonPrimitive()) {
                        DISABLED_RECIPES.add(element.getAsString());
                    }
                }
            }
        } catch (Exception ignored) {

        }
    }

    private static void saveUserData() {
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            JsonObject resolutions = new JsonObject();
            RESOLUTIONS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> resolutions.addProperty(entry.getKey(), entry.getValue()));
            root.add("resolutions", resolutions);
            JsonArray disabled = new JsonArray();
            DISABLED_RECIPES.stream().sorted().forEach(disabled::add);
            root.add("disabled", disabled);
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (Exception ignored) {

        }
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath()
            .resolve("config")
            .resolve("jeirecipetree")
            .resolve("recipe_defaults.json");
    }

    public static boolean isExcludedFromAutomaticSelection(String recipeId) {
        if (recipeId == null) {
            return false;
        }
        String path = recipeId;
        int separator = path.indexOf(':');
        if (separator >= 0) {
            path = path.substring(separator + 1);
        }
        return path.endsWith("_ingot_from_nuggets") || path.endsWith("_ingot_from_nugget");
    }
}
