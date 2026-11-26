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
import com.google.gson.JsonParser;
import com.slwold.jeirecipetree.core.ItemRef;
import com.slwold.jeirecipetree.core.RecipeRecord;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RecipeLibraryStore {

    public static final int FORMAT_VERSION = 1;

    public static final String DIRECTORY = "recipes";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private RecipeLibraryStore() {
    }

    public static Path directory(Path gameDirectory) {
        return gameDirectory.resolve("config").resolve("jeirecipetree").resolve(DIRECTORY);
    }

    public static RecipeLibrary loadDirectory(Path directory, String primaryGroup) {
        List<RecipeLibrary> libraries = new ArrayList<>();
        if (Files.isDirectory(directory)) {
            try (var stream = Files.list(directory)) {
                stream.filter(path -> Files.isRegularFile(path))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(path -> libraries.add(load(path)));
            } catch (Exception error) {
                JERTLog.warn("recipe library directory unreadable ({}): {}", directory, error.toString());
            }
        }
        if (libraries.isEmpty()) {
            return RecipeLibrary.empty(primaryGroup);
        }
        List<RecipeRecord> recipes = new ArrayList<>();
        Map<String, JsonObject> rawById = new HashMap<>();
        JsonObject primaryRoot = null;
        for (RecipeLibrary library : libraries) {
            recipes.addAll(library.recipes());
            rawById.putAll(library.rawByIds());
            if (primaryRoot == null || primaryGroup.equals(library.group())) {
                primaryRoot = library.rawRoot();
            }
        }
        return RecipeLibrary.of(primaryGroup, primaryRoot, recipes, rawById);
    }

    public static RecipeLibrary load(Path file) {
        String fallbackGroup = groupOf(file);
        if (!Files.isRegularFile(file)) {
            return RecipeLibrary.empty(fallbackGroup);
        }
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception error) {
            JERTLog.warn("recipe library unreadable ({}): {}", file.getFileName(), error.toString());
            return RecipeLibrary.empty(fallbackGroup);
        }

        String group = SessionPersistence.string(root, "group");
        if (group.isBlank()) {
            group = fallbackGroup;
        }
        List<RecipeRecord> recipes = new ArrayList<>();
        Map<String, JsonObject> rawById = new HashMap<>();
        for (JsonElement element : arrayOf(root, "recipes")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            try {
                RecipeRecord record = parseRecipe(object);

                if (record.group().isBlank()) {
                    record = new RecipeRecord(record.id(), group, record.outputs(), record.inputs(), record.catalysts());
                }
                recipes.add(record);
                rawById.put(record.id(), object);
            } catch (RuntimeException error) {
                JERTLog.warn("recipe skipped in {}: {}", file.getFileName(), error.getMessage());
            }
        }
        if (!recipes.isEmpty()) {
            JERTLog.summary("recipe library {}: {} recipes", file.getFileName(), recipes.size());
        }
        return RecipeLibrary.of(group, root, recipes, rawById);
    }

    public static boolean looksCorrupt(Path file) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonParser.parseReader(reader).getAsJsonObject();
            return false;
        } catch (Exception error) {
            return true;
        }
    }

    public static void save(Path file, RecipeLibrary library) {
        try {
            Files.createDirectories(file.getParent());

            Path backup = file.resolveSibling(file.getFileName() + ".bak");
            if (Files.isRegularFile(file)) {
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            JsonObject root = library.rawRoot() == null
                ? new JsonObject()
                : library.rawRoot().deepCopy();
            root.addProperty("version", FORMAT_VERSION);
            root.addProperty("group", library.group());

            JsonArray array = new JsonArray();
            for (RecipeRecord recipe : library.recipes()) {
                JsonObject source = library.rawById(recipe.id());
                JsonObject object = source == null ? new JsonObject() : source.deepCopy();
                object.addProperty("id", recipe.id());
                if (!recipe.group().isBlank()) {
                    object.addProperty("group", recipe.group());
                }
                object.add("outputs", slotsToJson(recipe.outputs()));
                object.add("inputs", slotsToJson(recipe.inputs()));
                object.add("catalysts", slotsToJson(recipe.catalysts()));
                array.add(object);
            }
            root.add("recipes", array);

            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception error) {
            JERTLog.warn("recipe library write failed ({}): {}", file.getFileName(), error.toString());
        }
    }

    private static RecipeRecord parseRecipe(JsonObject object) {
        String id = SessionPersistence.string(object, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("missing id");
        }
        return new RecipeRecord(
            id,
            SessionPersistence.string(object, "group"),
            parseSlots(object, "outputs"),
            parseSlots(object, "inputs"),
            parseSlots(object, "catalysts")
        );
    }

    private static List<RecipeRecord.Slot> parseSlots(JsonObject object, String key) {
        List<RecipeRecord.Slot> slots = new ArrayList<>();
        for (JsonElement element : arrayOf(object, key)) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException(key + " contains a non-object slot");
            }
            JsonObject slot = element.getAsJsonObject();
            long amount = SessionPersistence.longValue(slot, "amount", 0);
            List<ItemRef> alternatives = new ArrayList<>();
            for (JsonElement alternative : arrayOf(slot, "alternatives")) {
                if (alternative.isJsonPrimitive()) {

                    alternatives.add(ItemRef.of(IngredientDisplay.canonicalKey(alternative.getAsString())));
                }
            }
            slots.add(new RecipeRecord.Slot(alternatives, amount));
        }
        return slots;
    }

    private static JsonArray slotsToJson(List<RecipeRecord.Slot> slots) {
        JsonArray array = new JsonArray();
        for (RecipeRecord.Slot slot : slots) {
            JsonObject object = new JsonObject();
            object.addProperty("amount", slot.amount());
            JsonArray alternatives = new JsonArray();
            for (ItemRef reference : slot.alternatives()) {
                alternatives.add(reference.key());
            }
            object.add("alternatives", alternatives);
            array.add(object);
        }
        return array;
    }

    private static JsonArray arrayOf(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonArray()) {
            return new JsonArray();
        }
        return object.getAsJsonArray(key);
    }

    private static String groupOf(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        return name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
    }
}
