package com.slwold.jeirecipetree.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class SessionPersistence {

    public static final String FILE_NAME = "recipe_tree_session.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SessionPersistence() {
    }

    public static Path file(Path gameDirectory) {
        return gameDirectory.resolve("config").resolve("jeirecipetree").resolve(FILE_NAME);
    }

    public static void write(Path file, RecipeTreeData.Tree active) {
        if (active == null || !active.craftingMode() || active.root() == null
            || active.root().recipe() == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            JsonObject saved = new JsonObject();
            saved.addProperty("crafting", true);
            saved.addProperty("recipe", active.root().recipe().ref().key());
            saved.addProperty(
                "category",
                active.root().recipe().ref().category() == null
                    ? "" : active.root().recipe().ref().category().getRecipeType().getUid().toString()
            );
            saved.addProperty("registry", active.root().recipe().ref().registryId());
            saved.addProperty("output", active.root().ingredientKey());
            saved.addProperty("count", active.root().stack().getCount());
            saved.addProperty("batches", active.batches());
            String key = active.root().ingredientKey();
            if (!key.startsWith("fluid:")) {
                saved.addProperty("item",
                    BuiltInRegistries.ITEM.getKey(active.root().stack().getItem()).toString());
            }

            java.util.Map<String, String> resolutions = active.resolutionKeys();
            if (!resolutions.isEmpty()) {
                JsonObject bound = new JsonObject();
                resolutions.forEach(bound::addProperty);
                saved.add("resolutions", bound);
            }
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(saved, writer);
            }
        } catch (Exception ignored) {

        }
    }

    public static Optional<JsonObject> read(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return Optional.of(com.google.gson.JsonParser.parseReader(reader).getAsJsonObject());
        } catch (Exception ignored) {

            return Optional.empty();
        }
    }

    public static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (Exception ignored) {

        }
    }

    public static String string(JsonObject object, String key) {
        try {
            return object.has(key) ? object.get(key).getAsString() : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    public static int integer(JsonObject object, String key, int fallback) {
        try {
            return object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    public static long longValue(JsonObject object, String key, long fallback) {
        try {
            return object.has(key) ? object.get(key).getAsLong() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    public static boolean exists(Path file) {
        return Files.isRegularFile(file);
    }

    public static String itemId(RecipeTreeData.Tree active) {
        ItemStack stack = active.root().stack();
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
