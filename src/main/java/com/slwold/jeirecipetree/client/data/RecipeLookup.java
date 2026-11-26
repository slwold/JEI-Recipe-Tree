package com.slwold.jeirecipetree.client.data;
import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;
import com.slwold.jeirecipetree.client.storage.*;
import com.slwold.jeirecipetree.client.transfer.*;
import com.slwold.jeirecipetree.client.ui.*;

import com.slwold.jeirecipetree.client.jei.SequencedAssemblyCompat;
import com.slwold.jeirecipetree.JERTConfig;
import com.slwold.jeirecipetree.core.Amounts;
import com.slwold.jeirecipetree.core.ItemRef;
import com.slwold.jeirecipetree.client.data.RecipeTreeData.RecipeInput;
import com.slwold.jeirecipetree.client.data.RecipeTreeData.RecipeRef;
import com.slwold.jeirecipetree.client.data.RecipeTreeData.RecipeSnapshot;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class RecipeLookup {

    private static final Map<ItemRef, List<RecipeRef>> CANDIDATE_CACHE = new HashMap<>();
    private static final Map<ItemRef, List<RecipeSnapshot>> SNAPSHOT_CACHE = new HashMap<>();
    private static final Map<RecipeRef, RecipeSnapshot> RECIPE_SNAPSHOT_CACHE = new HashMap<>();
    private static final Map<RecipeRef, IRecipeLayoutDrawable<?>> LAYOUT_CACHE = new HashMap<>();

    private static int layoutBuilds;
    private static long layoutBuildNanos;

    private static final long SLOW_LAYOUT_NANOS = 20_000_000L;

    private static int snapshotBuilds;
    private static long snapshotBuildNanos;

    public static int snapshotBuildCount() {
        return snapshotBuilds;
    }

    public static long snapshotBuildNanoTime() {
        return snapshotBuildNanos;
    }

    public static int layoutBuildCount() {
        return layoutBuilds;
    }

    public static long layoutBuildNanoTime() {
        return layoutBuildNanos;
    }

    private static int candidateLookups;

    public static int candidateLookupCount() {
        return candidateLookups;
    }

    private RecipeLookup() {
    }

    static void clearCaches() {
        CANDIDATE_CACHE.clear();
        SNAPSHOT_CACHE.clear();
        RECIPE_SNAPSHOT_CACHE.clear();
        LAYOUT_CACHE.clear();
        IngredientDisplay.clear();
    }

    public static Optional<ITypedIngredient<?>> representativeSource(ItemStack stack) {
        return IngredientDisplay.sourceOf(stack);
    }

    public static boolean isButtonCandidate(IRecipeLayoutDrawable<?> layout) {
        if (layout == null || !isSupportedCategory(layout.getRecipeCategory())) {
            return false;
        }
        for (IRecipeSlotView slot : layout.getRecipeSlotsView().getSlotViews(RecipeIngredientRole.OUTPUT)) {
            if (!slot.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static boolean isSupportedCategory(IRecipeCategory<?> category) {
        if (category == null) {
            return false;
        }
        ResourceLocation recipeType = category.getRecipeType().getUid();
        return !recipeType.getPath().startsWith("tag_recipes/")
            && !isLootTableCategory(recipeType)
            && !JERTConfig.isRecipeTypeDisabled(recipeType);
    }

    private static boolean isLootTableCategory(ResourceLocation recipeType) {
        return recipeType.getPath().contains("loot");
    }

    public static Optional<ItemStack> firstOutput(IRecipeLayoutDrawable<?> layout) {
        if (layout == null || !isSupportedCategory(layout.getRecipeCategory())) {
            return Optional.empty();
        }
        return layout.getRecipeSlotsView().getSlotViews().stream()
            .filter(slot -> slot.getRole() == RecipeIngredientRole.OUTPUT)
            .flatMap(slot -> displayedStacks(slot, false).stream())
            .filter(stack -> !stack.isEmpty())
            .findFirst()
            .map(RecipeTreeData::copyStack);
    }

    public static List<ItemStack> outputStacks(IRecipeLayoutDrawable<?> layout) {
        if (layout == null || !isSupportedCategory(layout.getRecipeCategory())) {
            return List.of();
        }
        Map<String, ItemStack> outputs = new LinkedHashMap<>();
        for (IRecipeSlotView slot : layout.getRecipeSlotsView().getSlotViews(RecipeIngredientRole.OUTPUT)) {
            for (ItemStack stack : displayedStacks(slot, false)) {
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                outputs.putIfAbsent(RecipeTreeData.ingredientKey(stack), RecipeTreeData.copyStack(stack));
            }
        }
        return List.copyOf(outputs.values());
    }

    public static RecipeRef refOf(IRecipeLayoutDrawable<?> layout) {
        if (layout == null) {
            return null;
        }
        return ref(layout.getRecipeCategory(), layout.getRecipe());
    }

    public static Optional<RecipeSnapshot> snapshot(IRecipeLayoutDrawable<?> layout) {
        if (layout == null || !isSupportedCategory(layout.getRecipeCategory())) {
            return Optional.empty();
        }
        RecipeRef ref = ref(layout.getRecipeCategory(), layout.getRecipe());
        RecipeSnapshot cached = RECIPE_SNAPSHOT_CACHE.get(ref);
        if (cached != null) {
            return Optional.of(cached);
        }
        if (firstOutput(layout).isEmpty()) {
            return Optional.empty();
        }

        return createLayout(ref).map(canonical -> snapshot(ref, canonical));
    }

    public static RecipeSnapshot snapshot(RecipeRef ref) {
        if (ref == null) {
            return null;
        }
        RecipeSnapshot cached = RECIPE_SNAPSHOT_CACHE.get(ref);
        if (cached != null) {
            return cached;
        }
        return createLayout(ref)
            .map(layout -> snapshot(ref, layout))
            .orElse(null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Optional<IRecipeLayoutDrawable<?>> createLayout(RecipeRef requested) {
        IJeiRuntime runtime = DirectoryRecipePlugin.getJeiRuntime();
        RecipeRef ref = resolveLayoutRef(requested);
        if (ref == null) {
            return Optional.empty();
        }
        if (runtime == null || !isSupportedCategory(ref.category())) {
            return Optional.empty();
        }
        IRecipeLayoutDrawable<?> cached = LAYOUT_CACHE.get(ref);
        if (cached != null) {
            return Optional.of(cached);
        }
        IFocusGroup emptyFocus = runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup();
        long buildStart = System.nanoTime();
        Optional<IRecipeLayoutDrawable<Object>> layout = runtime.getRecipeManager().createRecipeLayoutDrawable(
            (IRecipeCategory) ref.category(),
            ref.recipe(),
            emptyFocus
        );
        long oneLayout = System.nanoTime() - buildStart;
        layoutBuildNanos += oneLayout;
        layoutBuilds++;

        if (oneLayout > SLOW_LAYOUT_NANOS) {
            JERTLog.warn("slow layout: {} took {} ms",
                ref.category().getRecipeType().getUid(),
                String.format(java.util.Locale.ROOT, "%.1f", oneLayout / 1_000_000.0));
        }
        layout.ifPresent(value -> LAYOUT_CACHE.put(ref, value));
        return (Optional) layout;
    }

    private static RecipeRef resolveLayoutRef(RecipeRef ref) {
        if (ref == null) {
            return null;
        }
        if (ref.category() != null) {
            return ref;
        }
        RecipeRef resolved = StoredRecipeRefs.resolve(ref.key()).orElse(null);
        if (resolved == null) {
            JERTLog.debug("recipe transfer: '{}' is a library recipe, no JEI layout to transfer", ref.key());
        }
        return resolved;
    }

    public static List<RecipeRef> candidates(ItemStack target) {
        IJeiRuntime runtime = DirectoryRecipePlugin.getJeiRuntime();
        if (runtime == null || target == null || target.isEmpty()) {
            return List.of();
        }

        ItemRef cacheKey = ItemKeys.of(target);
        List<RecipeRef> cached = CANDIDATE_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        IRecipeManager manager = runtime.getRecipeManager();
        try {

            IFocus<?> focus = FluidRecipeCompat.createOutputFocus(runtime, target)
                .<IFocus<?>>map(value -> value)
                .orElse(null);
            if (focus == null) {
                focus = IngredientDisplay.createOutputFocus(runtime, target).orElse(null);
            }
            if (focus == null) {
                focus = createOutputFocus(runtime, target).orElse(null);
            }
            if (focus == null) {

                CANDIDATE_CACHE.put(cacheKey, List.of());
                return List.of();
            }

            IFocus<?> resolvedFocus = focus;
            List<RecipeRef> result = new ArrayList<>();
            manager.createRecipeCategoryLookup()
                .limitFocus(List.of(resolvedFocus))
                .get()
                .filter(RecipeLookup::isSupportedCategory)
                .forEach(category -> addCandidates(manager, category, resolvedFocus, result));
            List<RecipeRef> immutable = List.copyOf(result);
            CANDIDATE_CACHE.put(cacheKey, immutable);
            return immutable;
        } finally {
            candidateLookups++;
        }
    }

    public static Optional<IFocus<ItemStack>> createOutputFocus(IJeiRuntime runtime, ItemStack target) {
        if (runtime == null || target == null || target.isEmpty()) {
            return Optional.empty();
        }
        ItemStack focusStack = target.copy();
        focusStack.setCount(1);
        try {
            return Optional.of(runtime.getJeiHelpers().getFocusFactory().createFocus(
                RecipeIngredientRole.OUTPUT,
                VanillaTypes.ITEM_STACK,
                focusStack
            ));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    public static List<RecipeSnapshot> candidateSnapshots(ItemStack target) {
        ItemRef ref = ItemKeys.of(target);
        String key = ItemKeys.legacy(ref);
        List<RecipeSnapshot> cached = SNAPSHOT_CACHE.get(ref);
        if (cached != null) {
            return cached;
        }
        List<RecipeSnapshot> snapshots = candidates(target).stream()
            .map(RecipeLookup::snapshot)
            .filter(snapshot -> snapshot != null && snapshot.produces(key))
            .toList();

        SNAPSHOT_CACHE.put(ref, snapshots);
        return snapshots;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void addCandidates(
        IRecipeManager manager,
        IRecipeCategory<?> category,
        IFocus<?> focus,
        List<RecipeRef> result
    ) {
        RecipeType type = category.getRecipeType();
        manager.createRecipeLookup(type)
            .limitFocus((Collection) List.of(focus))
            .get()
            .forEach(recipe -> result.add(ref(category, recipe)));
    }

    private static RecipeSnapshot snapshot(RecipeRef ref, IRecipeLayoutDrawable<?> layout) {
        RecipeSnapshot cached = RECIPE_SNAPSHOT_CACHE.get(ref);
        if (cached != null) {
            return cached;
        }
        long extractStart = System.nanoTime();
        try {
            return extractSnapshot(ref, layout);
        } finally {
            snapshotBuildNanos += System.nanoTime() - extractStart;
            snapshotBuilds++;
        }
    }

    private static RecipeSnapshot extractSnapshot(RecipeRef ref, IRecipeLayoutDrawable<?> layout) {
        Map<String, MutableRecipeInput> groupedInputs = new LinkedHashMap<>();
        Map<String, ItemStack> outputs = new LinkedHashMap<>();
        List<List<ItemStack>> outputSlots = new ArrayList<>();
        int inputSlotCount = 0;
        int droppedInputSlots = 0;
        for (IRecipeSlotView slot : layout.getRecipeSlotsView().getSlotViews()) {
            RecipeIngredientRole role = slot.getRole();

            if (role != RecipeIngredientRole.INPUT && role != RecipeIngredientRole.OUTPUT) {
                continue;
            }
            int inputSlotIndex = role == RecipeIngredientRole.INPUT ? inputSlotCount++ : -1;
            if (role == RecipeIngredientRole.INPUT) {

                List<MaterialOption> options = slotOptions(slot);
                if (options.isEmpty()) {

                    droppedInputSlots++;
                    continue;
                }
                addRecipeInput(groupedInputs, options, inputSlotIndex, SlotTags.tagOf(slot).orElse(null));
                continue;
            }
            List<ItemStack> alternatives = displayedStacks(slot, false);
            if (alternatives.isEmpty()) {
                continue;
            }

            outputSlots.add(List.copyOf(alternatives));
            for (ItemStack alternative : alternatives) {
                merge(outputs, alternative);
            }
        }
        IJeiRuntime runtime = DirectoryRecipePlugin.getJeiRuntime();
        if (runtime != null) {
            for (List<ITypedIngredient<?>> ingredients : SequencedAssemblyCompat.repeatedInputSlots(
                ref.recipe(), runtime.getIngredientManager()
            )) {
                List<MaterialOption> options = slotOptions(ingredients);

                if (options.isEmpty()) {
                    droppedInputSlots++;
                    continue;
                }
                addRecipeInput(groupedInputs, options, inputSlotCount++, null);
            }
        }
        List<RecipeInput> inputs = groupedInputs.values().stream()
            .map(MutableRecipeInput::freeze)
            .toList();
        if (droppedInputSlots > 0) {

            JERTLog.debug("recipe snapshot: {} input slot(s) had no resolvable ingredient, recipe={}",
                droppedInputSlots, ref.key());
        }
        RecipeSnapshot snapshot = new RecipeSnapshot(ref, inputs, inputSlotCount, List.copyOf(outputs.values()), List.copyOf(outputSlots));
        RECIPE_SNAPSHOT_CACHE.put(ref, snapshot);
        return snapshot;
    }

    private static void addRecipeInput(
        Map<String, MutableRecipeInput> groupedInputs,
        List<MaterialOption> options,
        int inputSlotIndex,
        ItemRef tag
    ) {
        if (options.isEmpty()) {
            return;
        }

        String signature = options.stream()
            .map(option -> option.ref().key() + "=" + option.amount())
            .sorted()
            .reduce((left, right) -> left + "\u001F" + right)
            .orElse("");
        MutableRecipeInput group = groupedInputs.get(signature);
        if (group == null) {
            groupedInputs.put(signature, new MutableRecipeInput(options, inputSlotIndex, tag));
        } else {
            group.merge(options, inputSlotIndex);
        }
    }

    private static List<MaterialOption> slotOptions(IRecipeSlotView slot) {
        return slotOptions(slot.getAllIngredientsList());
    }

    private static List<MaterialOption> slotOptions(List<? extends ITypedIngredient<?>> ingredients) {
        Map<String, MaterialOption> unique = new LinkedHashMap<>();
        for (ITypedIngredient<?> ingredient : ingredients) {
            if (ingredient == null) {
                continue;
            }
            MaterialOption option = MaterialOption.of(ingredient);
            if (option.isPresent()) {
                unique.putIfAbsent(option.ref().key(), option);
            }
        }
        return List.copyOf(unique.values());
    }

    private static List<ItemStack> displayedStacks(IRecipeSlotView slot, boolean input) {
        return displayedStacks(slot.getAllIngredientsList(), input);
    }

    private static List<ItemStack> displayedStacks(
        List<? extends ITypedIngredient<?>> ingredients,
        boolean input
    ) {
        Map<String, ItemStack> unique = new LinkedHashMap<>();
        for (ITypedIngredient<?> ingredient : ingredients) {
            if (ingredient == null) {
                continue;
            }

            Optional<ItemStack> item = ingredient.getItemStack().filter(stack -> !stack.isEmpty());
            item.map(ItemStack::copy)
                .ifPresent(stack -> unique.putIfAbsent(RecipeTreeData.ingredientKey(stack), stack));
            FluidRecipeCompat.representativeContainer(ingredient)
                .map(FluidRecipeCompat::copyWithDisplay)
                .ifPresent(stack -> unique.putIfAbsent(RecipeTreeData.ingredientKey(stack), stack));
            if (input) {
                FluidRecipeCompat.containerCandidate(ingredient)
                    .ifPresent(stack -> unique.putIfAbsent(RecipeTreeData.ingredientKey(stack), stack));
            }

            if (item.isEmpty() && FluidRecipeCompat.fluid(ingredient).isEmpty()) {
                IngredientDisplay.display(ingredient)
                    .ifPresent(stack -> unique.putIfAbsent(RecipeTreeData.ingredientKey(stack), stack));
            }
        }
        if (input && unique.isEmpty()) {
            var player = net.minecraft.client.Minecraft.getInstance().player;
            if (player != null) {
                for (ITypedIngredient<?> ingredient : ingredients) {
                    if (ingredient == null) {
                        continue;
                    }
                    for (ItemStack stack : FluidRecipeCompat.matchingContainers(player, ingredient)) {
                        ItemStack displayed = FluidRecipeCompat.copyWithDisplay(stack);
                        unique.putIfAbsent(RecipeTreeData.ingredientKey(displayed), displayed);
                    }
                }
            }
        }
        return List.copyOf(unique.values());
    }

    static int representativeFallbackCount() {
        return IngredientDisplay.nonItemDisplayCount();
    }

    public static List<ItemStack> candidateStacks(IRecipeSlotView slot) {
        return slot == null ? List.of() : displayedStacks(slot, true);
    }

    private static final class MutableRecipeInput {
        private final Map<String, MaterialOption> options = new LinkedHashMap<>();
        private final List<Integer> slotIndexes = new ArrayList<>();
        private final ItemRef tag;

        private MutableRecipeInput(List<MaterialOption> options, int slotIndex, ItemRef tag) {
            this.tag = tag;
            merge(options, slotIndex);
        }

        private void merge(List<MaterialOption> additions, int slotIndex) {
            slotIndexes.add(slotIndex);
            for (MaterialOption addition : additions) {
                MaterialOption current = options.get(addition.ref().key());
                if (current == null) {
                    options.put(addition.ref().key(), addition);
                } else {
                    options.put(addition.ref().key(),
                        current.withAmount(Amounts.safeAdd(current.amount(), addition.amount())));
                }
            }
        }

        private RecipeInput freeze() {
            return new RecipeInput(List.copyOf(options.values()), slotIndexes, tag);
        }
    }

    private static void merge(Map<String, ItemStack> destination, ItemStack stack) {
        String key = RecipeTreeData.ingredientKey(stack);
        ItemStack existing = destination.get(key);
        if (existing == null) {
            destination.put(key, RecipeTreeData.copyStack(stack));
        } else {
            existing.setCount(existing.getCount() + stack.getCount());
        }
    }

    static RecipeRef ref(IRecipeCategory<?> category, Object recipe) {
        @SuppressWarnings({"rawtypes", "unchecked"})
        ResourceLocation id = ((IRecipeCategory) category).getRegistryName(recipe);
        String registryId = id == null ? "" : id.toString();
        String identity = registryId.isEmpty() ? "runtime/" + System.identityHashCode(recipe) : registryId;
        String key = category.getRecipeType().getUid() + "|" + identity;
        return new RecipeRef(category, recipe, key, registryId);
    }
}
