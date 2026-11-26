package com.slwold.jeirecipetree.client.data;
import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;
import com.slwold.jeirecipetree.client.storage.*;
import com.slwold.jeirecipetree.client.transfer.*;
import com.slwold.jeirecipetree.client.ui.*;

import com.google.gson.JsonObject;
import com.slwold.jeirecipetree.core.ItemRef;
import com.slwold.jeirecipetree.core.RecipeRecord;
import com.slwold.jeirecipetree.core.RecipeSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RecipeLibrary implements RecipeSource {

    private final String group;
    private final JsonObject rawRoot;
    private final List<RecipeRecord> recipes;
    private final Map<String, JsonObject> rawById;
    private final Map<ItemRef, List<RecipeSource.Match>> byOutput;

    private RecipeLibrary(
        String group,
        JsonObject rawRoot,
        List<RecipeRecord> recipes,
        Map<String, JsonObject> rawById
    ) {
        this.group = group == null ? "" : group;
        this.rawRoot = rawRoot;
        this.recipes = List.copyOf(recipes);
        this.rawById = Collections.unmodifiableMap(new HashMap<>(rawById));
        this.byOutput = buildIndex(this.recipes);
    }

    public static RecipeLibrary of(
        String group,
        JsonObject rawRoot,
        List<RecipeRecord> recipes,
        Map<String, JsonObject> rawById
    ) {
        return new RecipeLibrary(group, rawRoot, recipes, rawById);
    }

    public static RecipeLibrary empty(String group) {
        return new RecipeLibrary(group, null, List.of(), Map.of());
    }

    private static Map<ItemRef, List<RecipeSource.Match>> buildIndex(List<RecipeRecord> recipes) {
        Map<ItemRef, List<RecipeSource.Match>> index = new HashMap<>();
        for (RecipeRecord recipe : recipes) {
            List<RecipeRecord.Slot> outputs = recipe.outputs();
            for (int slot = 0; slot < outputs.size(); slot++) {
                for (ItemRef alternative : outputs.get(slot).alternatives()) {
                    RecipeSource.Match match = new RecipeSource.Match(recipe, slot);
                    indexSlot(index, alternative, match);
                    if (alternative.isTag()) {
                        for (ItemRef member : SlotTags.members(alternative)) {
                            indexSlot(index, member, match);
                        }
                    }
                }
            }
        }
        return index;
    }

    private static void indexSlot(
        Map<ItemRef, List<RecipeSource.Match>> index,
        ItemRef ref,
        RecipeSource.Match match
    ) {
        index.computeIfAbsent(ref, ignored -> new ArrayList<>()).add(match);
    }

    @Override
    public RecipeSource.Match match(ItemRef label) {
        List<RecipeSource.Match> matches = byOutput.get(label);
        return matches == null || matches.isEmpty() ? null : matches.get(0);
    }

    public String group() {
        return group;
    }

    public List<RecipeRecord> recipes() {
        return recipes;
    }

    public JsonObject rawRoot() {
        return rawRoot;
    }

    public JsonObject rawById(String id) {
        return rawById.get(id);
    }

    public Map<String, JsonObject> rawByIds() {
        return rawById;
    }

    public int size() {
        return recipes.size();
    }
}
