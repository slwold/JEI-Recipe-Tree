package com.slwold.jeirecipetree.client;

import com.slwold.jeirecipetree.core.ItemRef;
import com.slwold.jeirecipetree.core.RecipeRecord;
import com.slwold.jeirecipetree.core.RecipeSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class StoredRecipes {

    private static RecipeLibrary library = RecipeLibrary.empty(RecipeArchive.GROUP);
    private static boolean loaded;
    private static final Map<String, RecipeTreeData.RecipeSnapshot> IMPORTED = new HashMap<>();

    private StoredRecipes() {
    }

    static void remember(String id, RecipeTreeData.RecipeSnapshot snapshot) {
        if (id != null && snapshot != null) {
            IMPORTED.put(id, snapshot);
        }
    }

    static void discardIfChanged(java.util.List<RecipeRecord> written) {
        for (RecipeRecord record : written) {
            RecipeTreeData.RecipeSnapshot snapshot = IMPORTED.get(record.id());
            if (snapshot == null) {
                continue;
            }
            if (!sameShape(snapshot, record)) {
                IMPORTED.remove(record.id());
                JERTLog.debug("recipe library: session snapshot for {} dropped (record was edited)",
                    record.id());
            }
        }
    }

    private static boolean sameShape(RecipeTreeData.RecipeSnapshot snapshot, RecipeRecord record) {
        return sameSlots(
            snapshot.inputs().stream().map(input -> input.alternatives()).toList(),
            record.inputs()
        ) && sameSlots(snapshot.outputSlots(), record.outputs());
    }

    private static boolean sameSlots(java.util.List<java.util.List<ItemStack>> stacks, java.util.List<RecipeRecord.Slot> slots) {
        if (stacks.size() != slots.size()) {
            return false;
        }
        for (int index = 0; index < slots.size(); index++) {
            java.util.List<ItemStack> alternatives = stacks.get(index);
            RecipeRecord.Slot slot = slots.get(index);
            if (alternatives.isEmpty()) {
                return false;
            }

            long actual = Math.max(1L, IngredientDisplay.amountOf(alternatives.get(0)));
            if (slot.amount() != actual) {
                return false;
            }
            if (!slot.primary().key().equals(ItemKeys.of(alternatives.get(0)).key())) {
                return false;
            }
        }
        return true;
    }

    static void reload() {
        Path directory = RecipeLibraryStore.directory(Minecraft.getInstance().gameDirectory.toPath());

        library = RecipeLibraryStore.loadDirectory(directory, RecipeArchive.GROUP);
        loaded = true;
        JERTLog.summary("recipe library: {} recipes available for tree expansion", library.size());
    }

    static void clearCaches() {
        IMPORTED.clear();
        library = RecipeLibrary.empty(RecipeArchive.GROUP);
        loaded = false;
    }

    private static RecipeLibrary library() {
        if (!loaded) {
            reload();
        }
        return library;
    }

    static RecipeTreeData.RecipeSnapshot byId(String id) {
        RecipeTreeData.RecipeSnapshot imported = IMPORTED.get(id);
        if (imported != null) {
            return imported;
        }
        for (RecipeTreeData.RecipeSnapshot snapshot : snapshots()) {
            if (snapshot.ref().key().equals(id)) {
                return snapshot;
            }
        }
        return null;
    }

    private static List<RecipeTreeData.RecipeSnapshot> snapshots() {
        List<RecipeTreeData.RecipeSnapshot> result = new ArrayList<>();
        for (RecipeRecord record : library().recipes()) {
            RecipeTreeData.RecipeSnapshot snapshot = fromRecord(record);
            if (snapshot != null) {
                result.add(snapshot);
            }
        }
        return result;
    }

    static RecipeTreeData.RecipeSnapshot forTarget(ItemRef target) {
        if (target == null) {
            return null;
        }
        RecipeSource.Match match = library().match(target);
        if (match == null) {
            return null;
        }
        RecipeRecord record = match.recipe();
        RecipeTreeData.RecipeSnapshot imported = IMPORTED.get(record.id());
        if (imported != null) {
            return imported;
        }
        return fromRecord(record);
    }

    static RecipeTreeData.RecipeSnapshot snapshotOf(RecipeRecord record) {
        return record == null ? null : fromRecord(record);
    }

    private static RecipeTreeData.RecipeSnapshot fromRecord(RecipeRecord record) {
        List<RecipeTreeData.RecipeInput> inputs = new ArrayList<>();
        for (int index = 0; index < record.inputs().size(); index++) {
            List<MaterialOption> options = materials(record.inputs().get(index));
            if (!options.isEmpty()) {
                inputs.add(new RecipeTreeData.RecipeInput(options, List.of(index)));
            }
        }
        List<List<ItemStack>> outputSlots = new ArrayList<>();
        List<ItemStack> outputs = new ArrayList<>();
        for (RecipeRecord.Slot slot : record.outputs()) {
            List<ItemStack> alternatives = stacks(slot);
            if (alternatives.isEmpty()) {
                continue;
            }
            outputSlots.add(alternatives);
            outputs.add(RecipeTreeData.copyStack(alternatives.getFirst()));
        }
        if (outputs.isEmpty()) {
            JERTLog.debug("recipe library: {} has no output slots, skipped", record.id());
            return null;
        }

        RecipeTreeData.RecipeRef ref = StoredRecipeRefs.resolve(record.id())
            .orElseGet(() -> new RecipeTreeData.RecipeRef(null, null, record.id(), ""));
        return new RecipeTreeData.RecipeSnapshot(
            ref,
            inputs,
            inputs.size(),
            outputs,
            outputSlots
        );
    }

    private static List<MaterialOption> materials(RecipeRecord.Slot slot) {
        long amount = Math.max(1L, slot.amount());
        int count = (int) Math.min(Integer.MAX_VALUE, amount);
        List<MaterialOption> result = new ArrayList<>();
        for (ItemRef ref : slot.alternatives()) {
            if (ref.isTag()) {
                continue;
            }
            result.add(new MaterialOption(ref, amount, stack(ref, count), null));
        }
        return result;
    }

    private static List<ItemStack> stacks(RecipeRecord.Slot slot) {
        int count = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, slot.amount()));
        List<ItemStack> result = new ArrayList<>();
        for (ItemRef ref : slot.alternatives()) {

            if (ref.isTag()) {
                continue;
            }
            ItemStack stack = stack(ref, count);
            if (!stack.isEmpty()) {
                result.add(stack);
            }
        }
        return result;
    }

    private static ItemStack placeholderStack(ItemRef ref) {
        ItemStack display = new ItemStack(Items.PAPER);
        display.set(
            DataComponents.CUSTOM_NAME,
            Component.literal(ref.placeholderLabel()).withStyle(ChatFormatting.LIGHT_PURPLE)
        );
        return ItemKeys.withIdentity(display, ref.key());
    }

    static List<RecipeRecord> all() {
        return library().recipes();
    }

    static ItemStack displayStack(ItemRef ref, int count) {
        return stack(ref, count);
    }

    static ItemStack previewOf(RecipeRecord record) {
        for (RecipeRecord.Slot slot : record.outputs()) {
            for (ItemRef ref : slot.alternatives()) {
                if (ref.isTag()) {
                    continue;
                }
                int amount = ref.isFluid()
                    ? (int) Math.min(Integer.MAX_VALUE, Math.max(1L, slot.amount()))
                    : (int) Math.max(1L, Math.min(99L, slot.amount()));
                ItemStack stack = stack(ref, amount);
                if (!stack.isEmpty()) {
                    return stack;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack stack(ItemRef ref, int count) {
        String key = ref.key();
        if (ref.isPlaceholder()) {
            return placeholderStack(ref);
        }
        if (ref.isFluid()) {
            ItemStack fluid = FluidRecipeCompat.representativeForKey(key, count).orElse(ItemStack.EMPTY);
            if (!fluid.isEmpty()) {

                fluid.setCount((int) Math.min(Integer.MAX_VALUE, FluidRecipeCompat.containerCount(count)));
            }
            return fluid;
        }
        if (ref.isChemical()) {

            return IngredientDisplay.displayForKey(key, count)
                .orElseGet(() -> ItemKeys.withIdentity(new ItemStack(Items.BARRIER), key));
        }
        ResourceLocation id = ResourceLocation.tryParse(key);
        if (id != null) {
            ItemStack item = BuiltInRegistries.ITEM.getOptional(id)
                .map(value -> new ItemStack(value, count))
                .orElse(ItemStack.EMPTY);
            if (!item.isEmpty()) {
                return item;
            }
        }

        ItemStack display = ItemStack.EMPTY;
        int separator = key.indexOf(':', key.indexOf(':') + 1);
        if (separator > 0) {
            ResourceLocation head = ResourceLocation.tryParse(key.substring(0, separator));
            if (head != null) {
                display = BuiltInRegistries.ITEM.getOptional(head)
                    .map(value -> new ItemStack(value, count))
                    .orElse(ItemStack.EMPTY);
            }
        }
        if (display.isEmpty()) {
            display = new ItemStack(Items.BARRIER, count);
            JERTLog.debug("recipe library: key '{}' has no renderable item, shown as placeholder", key);
        }
        return ItemKeys.withIdentity(display, key);
    }
}
