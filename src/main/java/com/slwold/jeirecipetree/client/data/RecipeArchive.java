package com.slwold.jeirecipetree.client.data;
import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;
import com.slwold.jeirecipetree.client.storage.*;
import com.slwold.jeirecipetree.client.transfer.*;
import com.slwold.jeirecipetree.client.ui.*;

import com.slwold.jeirecipetree.core.ItemRef;
import com.slwold.jeirecipetree.core.RecipeFingerprint;
import com.slwold.jeirecipetree.core.RecipeRecord;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.gui.recipes.IRecipeLayoutWithButtons;
import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class RecipeArchive {

    public static final String GROUP = "default";

    private RecipeArchive() {
    }

    public static int exportCurrentTree() {
        RecipeTreeData.Tree tree = RecipeTreeSession.tree();
        if (tree == null || tree.root() == null) {
            JERTLog.warn("recipe archive: no active tree, nothing to export");
            return 0;
        }
        Map<String, RecipeRecord> collected = new LinkedHashMap<>();
        collect(tree.root(), collected);
        if (collected.isEmpty()) {
            JERTLog.warn("recipe archive: current tree has no expandable recipe");
            return 0;
        }
        return saveRecords(new ArrayList<>(collected.values()));
    }

    public static int importFromJeiScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof RecipesGui gui)) {
            JERTLog.warn("recipe import: no JEI recipe screen open, ignored");
            return 0;
        }
        double scale = mc.getWindow().getGuiScale();
        double mouseX = mc.mouseHandler.xpos() / scale;
        double mouseY = mc.mouseHandler.ypos() / scale;
        Optional<IRecipeLayoutWithButtons<?>> underMouse = gui.getRecipeLayoutUnderMouse(mouseX, mouseY);
        if (underMouse.isEmpty()) {
            JERTLog.warn("recipe import: mouse is not over a recipe layout");
            return 0;
        }
        return importLayout(underMouse.get().getRecipeLayout());
    }

    public static int importLayout(IRecipeLayoutDrawable<?> layout) {
        if (layout == null || !RecipeLookup.isSupportedCategory(layout.getRecipeCategory())) {
            JERTLog.warn("recipe import: unsupported recipe category, ignored");
            return 0;
        }
        RecipeRecord record = RecipeLookup.snapshot(layout)
            .map(RecipeArchive::toRecord)
            .orElse(null);
        if (record == null) {
            JERTLog.warn("recipe import: recipe has no usable output slot");
            return 0;
        }
        List<RecipeRecord.Slot> catalysts = catalystsOf(layout);
        if (!catalysts.isEmpty()) {
            record = new RecipeRecord(record.id(), record.group(), record.outputs(), record.inputs(), catalysts);
        }
        JERTLog.debug("recipe import: {} from JEI recipe page", record.id());
        return saveRecords(List.of(record));
    }

    public static RecipeRecord draftOf(RecipeTreeData.RecipeSnapshot snapshot) {
        if (snapshot == null) {
            JERTLog.warn("recipe import: recipe has no snapshot, ignored");
            return null;
        }
        RecipeRecord record = toRecord(snapshot);
        if (record == null) {
            JERTLog.warn("recipe import: recipe has no usable output slot");
            return null;
        }

        JERTLog.debug("recipe draft {}: in={} out={}", record.id(),
            record.inputs().stream()
                .map(slot -> slot.alternatives().getFirst().key() + " x" + slot.amount()).toList(),
            record.outputs().stream()
                .map(slot -> slot.alternatives().getFirst().key() + " x" + slot.amount()).toList());
        return record;
    }

    public static RecipeRecord draftOf(RecipeTreeData.RecipeSnapshot snapshot, IRecipeLayoutDrawable<?> layout) {
        RecipeRecord base = draftOf(snapshot);
        if (base == null) {
            return null;
        }
        List<RecipeRecord.Slot> catalysts = catalystsOf(layout);
        if (catalysts.isEmpty()) {
            return base;
        }
        return new RecipeRecord(base.id(), base.group(), base.outputs(), base.inputs(), catalysts);
    }

    private static List<RecipeRecord.Slot> catalystsOf(IRecipeLayoutDrawable<?> layout) {
        if (layout == null) {
            return List.of();
        }
        List<RecipeRecord.Slot> slots = new ArrayList<>();
        for (IRecipeSlotView view : layout.getRecipeSlotsView().getSlotViews(RecipeIngredientRole.CATALYST)) {
            List<ItemStack> alternatives = RecipeLookup.candidateStacks(view);
            if (!alternatives.isEmpty()) {
                slots.add(toSlot(alternatives));
            }
        }
        return slots;
    }

    public static RecipeRecord newManualRecipe(String productLabel, String group) {
        String label = productLabel == null ? "" : productLabel.trim();
        if (label.isEmpty()) {
            return null;
        }
        String base = "manual:" + label;
        String id = base;
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (RecipeRecord existing : StoredRecipes.all()) {
            taken.add(existing.id());
        }
        for (int suffix = 2; taken.contains(id); suffix++) {
            id = base + "#" + suffix;
        }
        return new RecipeRecord(
            id,
            group == null ? "" : group,
            List.of(new RecipeRecord.Slot(List.of(ItemRef.placeholder(label)), 1L)),
            List.of(),
            List.of()
        );
    }

    public static int saveRecord(RecipeRecord record) {
        if (record == null) {
            return 0;
        }
        JERTLog.debug("recipe import: {} confirmed in edit page", record.id());
        return saveRecords(List.of(record));
    }

    public static int saveAll(List<RecipeRecord> recipes) {
        Path file = RecipeLibraryStore.directory(Minecraft.getInstance().gameDirectory.toPath())
            .resolve(GROUP + ".json");
        if (RecipeLibraryStore.looksCorrupt(file)) {
            JERTLog.warn("recipe library: {} is not valid JSON, refusing to overwrite; fix or delete the file first",
                file.getFileName());
            return RecipeLibraryStore.load(file).size();
        }

        List<RecipeRecord> primary = recipes.stream()
            .filter(recipe -> recipe.group().isBlank() || GROUP.equals(recipe.group()))
            .toList();
        int foreign = recipes.size() - primary.size();
        if (foreign > 0) {
            JERTLog.warn("recipe library: {} recipe(s) live in other group files, left untouched", foreign);
        }
        RecipeLibrary existing = RecipeLibraryStore.load(file);
        RecipeLibrary library = RecipeLibrary.of(
            GROUP,
            existing.rawRoot(),
            primary,
            existing.rawByIds()
        );
        RecipeLibraryStore.save(file, library);
        StoredRecipes.reload();
        JERTLog.summary("recipe library: {} recipes saved from manager -> {}", primary.size(), file);
        return primary.size();
    }

    public static Path exportAll() {
        Path directory = RecipeLibraryStore.directory(Minecraft.getInstance().gameDirectory.toPath());
        Path file = directory.resolve("export").resolve("groups.json");
        List<RecipeRecord> recipes = StoredRecipes.all();
        if (recipes.isEmpty()) {
            JERTLog.warn("recipe export: library is empty, nothing to export");
            return null;
        }
        try {
            java.nio.file.Files.createDirectories(file.getParent());
        } catch (Exception error) {
            JERTLog.warn("recipe export: cannot create {}: {}", file.getParent(), error.toString());
            return null;
        }
        RecipeLibrary library = RecipeLibrary.of(GROUP, null, recipes, Map.of());
        RecipeLibraryStore.save(file, library);
        JERTLog.summary("recipe export: {} recipes -> {}", recipes.size(), file);
        return file;
    }

    private static int saveRecords(List<RecipeRecord> collected) {
        Map<String, List<RecipeRecord>> byGroup = new LinkedHashMap<>();
        for (RecipeRecord record : collected) {
            String group = record.group().isBlank() ? GROUP : record.group();
            byGroup.computeIfAbsent(group, key -> new ArrayList<>()).add(record);
        }
        int written = 0;
        for (Map.Entry<String, List<RecipeRecord>> entry : byGroup.entrySet()) {
            written += saveGroup(entry.getKey(), entry.getValue());
        }
        return written;
    }

    private static int saveGroup(String group, List<RecipeRecord> collected) {
        Path file = RecipeLibraryStore.directory(Minecraft.getInstance().gameDirectory.toPath())
            .resolve(group + ".json");

        if (RecipeLibraryStore.looksCorrupt(file)) {
            JERTLog.warn("recipe import: {} is not valid JSON, refusing to overwrite; fix or delete the file first",
                file.getFileName());
            return 0;
        }
        RecipeLibrary existing = RecipeLibraryStore.load(file);
        Map<String, RecipeRecord> merged = new LinkedHashMap<>();
        existing.recipes().forEach(record -> merged.put(record.id(), record));
        collected.forEach(record -> merged.put(record.id(), record));

        RecipeLibrary library = RecipeLibrary.of(
            group,
            existing.rawRoot(),
            new ArrayList<>(merged.values()),
            existing.rawByIds()
        );
        RecipeLibraryStore.save(file, library);

        StoredRecipes.discardIfChanged(collected);

        StoredRecipes.reload();
        JERTLog.summary("recipe archive: {} collected, {} total in {} -> {}",
            collected.size(), merged.size(), group, file);
        return collected.size();
    }

    public static List<RecipeRecord> records(RecipeTreeData.Tree tree) {
        if (tree == null || tree.root() == null) {
            return List.of();
        }
        Map<String, RecipeRecord> collected = new LinkedHashMap<>();
        collect(tree.root(), collected);

        return List.copyOf(collected.values());
    }

    private static void collect(RecipeTreeData.Node node, Map<String, RecipeRecord> out) {
        if (node == null) {
            return;
        }
        RecipeTreeData.RecipeSnapshot snapshot = node.recipe();
        if (snapshot != null) {
            RecipeRecord record = toRecord(snapshot);
            if (record != null) {
                out.put(record.id(), record);
            }
        }
        for (RecipeTreeData.Node child : node.children()) {
            collect(child, out);
        }
    }

    private static RecipeRecord toRecord(RecipeTreeData.RecipeSnapshot snapshot) {
        List<RecipeRecord.Slot> outputs = new ArrayList<>();
        for (List<ItemStack> slot : snapshot.outputSlots()) {
            if (slot == null || slot.isEmpty()) {
                continue;
            }
            outputs.add(toSlot(slot));
        }
        if (outputs.isEmpty()) {
            return null;
        }
        List<RecipeRecord.Slot> inputs = new ArrayList<>();
        for (RecipeTreeData.RecipeInput input : snapshot.inputs()) {
            List<MaterialOption> options = input.options();
            if (options.isEmpty()) {
                continue;
            }
            List<ItemRef> refs = new ArrayList<>();

            if (input.tag() != null) {
                refs.add(input.tag());
            }

            for (MaterialOption option : options) {
                if (!refs.contains(option.ref())) {
                    refs.add(option.ref());
                }
            }
            inputs.add(new RecipeRecord.Slot(refs, Math.max(1L, options.getFirst().amount())));
        }
        RecipeTreeData.RecipeRef ref = snapshot.ref();
        String registryId = ref.registryId();
        String id;
        if (ref.category() == null) {

            id = ref.key();
        } else if (registryId == null || registryId.isEmpty()) {

            id = RecipeFingerprint.contentId(
                ref.category().getRecipeType().getUid().toString(), outputs, inputs);
            JERTLog.debug("recipe archive: no registry id, using content id {}", id);
        } else {
            id = ref.key();
        }
        StoredRecipes.remember(id, snapshot);
        return new RecipeRecord(
            id,
            GROUP,
            outputs,
            inputs,
            List.of()
        );
    }

    private static RecipeRecord.Slot toSlot(List<ItemStack> alternatives) {
        List<ItemRef> refs = alternatives.stream().map(ItemKeys::of).toList();
        return new RecipeRecord.Slot(refs, slotAmount(alternatives));
    }

    private static long slotAmount(List<ItemStack> alternatives) {
        return Math.max(1L, IngredientDisplay.amountOf(alternatives.get(0)));
    }
}
