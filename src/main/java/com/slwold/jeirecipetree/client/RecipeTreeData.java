package com.slwold.jeirecipetree.client;

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

public final class RecipeTreeData {
    public static final int MAX_DEPTH = 12;
    public static final int MAX_NODES = 384;

    private static String lastCompareSignature = "";

    private static final Set<String> UNAVAILABLE_LOGGED = new HashSet<>();

    private RecipeTreeData() {
    }

    static ItemStack copyStack(ItemStack stack) {
        ItemStack copy = FluidRecipeCompat.copyWithDisplay(stack);
        return copy == null ? ItemStack.EMPTY : copy;
    }

    public enum Progress {
        UNSTARTED,
        PARTIAL,
        COMPLETED
    }

    public record RecipeRef(
        IRecipeCategory<?> category,
        Object recipe,
        String key,
        String registryId
    ) {
    }

    public record RecipeInput(List<MaterialOption> options, List<Integer> slotIndexes, ItemRef tag) {
        public RecipeInput {
            options = options.stream()
                .filter(option -> option != null && option.isPresent())
                .toList();
            slotIndexes = List.copyOf(slotIndexes);
        }

        public RecipeInput(List<MaterialOption> options, List<Integer> slotIndexes) {
            this(options, slotIndexes, null);
        }

        public List<ItemStack> alternatives() {
            return options.stream()
                .map(MaterialOption::display)
                .filter(stack -> !stack.isEmpty())
                .map(RecipeTreeData::copyStack)
                .toList();
        }

        public MaterialOption firstOption() {
            return options.isEmpty() ? MaterialOption.EMPTY : options.getFirst();
        }

        public ItemStack first() {
            return firstOption().display();
        }
    }

    public record RecipeSnapshot(
        RecipeRef ref,
        List<RecipeInput> inputs,
        int inputSlotCount,
        List<ItemStack> outputs,
        List<List<ItemStack>> outputSlots
    ) {
        public List<ItemStack> outputsFor(String wanted) {
            return RecipeTreeOutputChoices.select(outputSlots, wanted, RecipeTreeData::ingredientKey);
        }

        public boolean canExpand() {
            return !inputs.isEmpty();
        }

        public long outputAmount(String ingredientKey) {

            return RecipeTreeOutputChoices.amount(
                outputSlots, ingredientKey, RecipeTreeData::ingredientKey, IngredientDisplay::amountOf
            );
        }

        public boolean produces(String ingredientKey) {
            return outputs.stream().anyMatch(output -> ingredientKey(output).equals(ingredientKey));
        }
    }

    public static final class Node {

        final MaterialOption material;

        final String ingredientKey;

        final ItemRef ingredientRef;
        private final RecipeSnapshot recipe;
        final List<ItemStack> alternatives;

        final List<MaterialOption> materialOptions;
        private final String choiceKey;
        private final String path;
        private final List<Integer> inputSlotIndexes;
        final boolean explicitChoice;

        final List<ItemRef> alternativeRefs;
        final String costSignature;
        final List<Node> children = new ArrayList<>();

        final long perCraft;
        private boolean expanded = true;
        private boolean cycle;
        long amount = 1;

        long wanted;
        long crafts;
        long remaining;
        Progress progress = Progress.UNSTARTED;
        private int x;
        private int y;

        private Node(MaterialOption material, RecipeSnapshot recipe) {
            this(material, recipe, List.of(material), "", "root", List.of(), false);
        }

        private Node(
            MaterialOption material,
            RecipeSnapshot recipe,
            List<MaterialOption> options,
            String choiceKey,
            String path,
            List<Integer> inputSlotIndexes,
            boolean explicitChoice
        ) {
            this.material = material == null ? MaterialOption.EMPTY : material;
            this.ingredientRef = this.material.ref();
            this.ingredientKey = ItemKeys.legacy(this.material.ref());
            this.recipe = recipe;
            this.materialOptions = List.copyOf(options);

            this.alternatives = options.stream()
                .map(MaterialOption::display)
                .filter(stack -> !stack.isEmpty())
                .map(RecipeTreeData::copyStack)
                .toList();
            this.choiceKey = choiceKey;
            this.path = path;
            this.inputSlotIndexes = List.copyOf(inputSlotIndexes);
            this.explicitChoice = explicitChoice;

            this.perCraft = Math.max(1L, this.material.amount());

            this.alternativeRefs = options.stream().map(MaterialOption::ref).toList();

            this.costSignature = ItemKeys.legacy(this.ingredientRef);
        }

        public ItemStack stack() {
            return material.display();
        }

        public MaterialOption material() {
            return material;
        }

        public String ingredientKey() {
            return ingredientKey;
        }

        public RecipeSnapshot recipe() {
            return recipe;
        }

        public List<ItemStack> alternatives() {
            return alternatives;
        }

        public boolean hasAlternatives() {
            return alternatives.size() > 1;
        }

        public boolean isOutputChoice() {
            return recipe != null && "root".equals(path) && hasAlternatives();
        }

        public boolean explicitChoice() {
            return explicitChoice;
        }

        public List<Integer> inputSlotIndexes() {
            return inputSlotIndexes;
        }

        public List<Node> children() {
            return children;
        }

        public boolean expanded() {
            return expanded;
        }

        public void setExpanded(boolean expanded) {
            this.expanded = expanded;
        }

        public boolean cycle() {
            return cycle;
        }

        public long amount() {
            return amount;
        }

        public long crafts() {
            return crafts;
        }

        public long remaining() {
            return remaining;
        }

        public Progress progress() {
            return progress;
        }

        int x() {
            return x;
        }

        int y() {
            return y;
        }

        void setPosition(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    public record Cost(
        MaterialOption material,
        List<ItemStack> alternatives,
        long required,
        long supplied,
        long wanted
    ) {
        public Cost {
            material = material == null ? MaterialOption.EMPTY : material;
            alternatives = alternatives.stream().map(RecipeTreeData::copyStack).toList();
        }

        public Cost(ItemStack stack, List<ItemStack> alternatives, long required, long supplied, long wanted) {
            this(MaterialOption.of(stack), alternatives, required, supplied, wanted);
        }

        public ItemStack stack() {
            return material.display();
        }
    }

    public record Analysis(List<Cost> costs, List<Cost> leftovers, List<Cost> catalysts) {
    }

    private static List<Cost> catalystsOf(CostCalculator.Result result) {
        List<Cost> rows = new ArrayList<>();
        if (result == null) {
            return rows;
        }
        for (Map.Entry<ItemRef, Long> entry : result.catalysts().entrySet()) {

            boolean fluid = entry.getKey().isFluid();
            long raw = entry.getValue();
            int amount = fluid
                ? (int) Math.min(Integer.MAX_VALUE, Math.max(1L, raw))
                : 1;
            ItemStack stack = StoredRecipes.displayStack(entry.getKey(), amount);
            if (stack.isEmpty()) {
                continue;
            }

            long units = raw;
            rows.add(new Cost(copyStack(stack), List.of(copyStack(stack)), units, 0, units));
        }
        return rows;
    }

    public record CraftStep(
        RecipeRef recipe,
        ItemStack stack,
        List<ItemStack> alternatives,
        List<String> selectedInputs,
        long batches,
        long remaining,
        long total,
        Progress progress,
        List<String> nodePaths
    ) {
        public CraftStep {
            stack = copyStack(stack);
            alternatives = alternatives.stream().map(RecipeTreeData::copyStack).toList();
            selectedInputs = List.copyOf(selectedInputs);
            nodePaths = List.copyOf(nodePaths);
        }
    }

    public static final class Tree {
        private static final String ROOT_OUTPUT_CHOICE = "root-output";
        private final RecipeSnapshot rootRecipe;
        private ItemStack rootStack;
        private boolean rootOutputExplicit;
        private final Map<String, RecipeRef> resolutions = new HashMap<>();

        private final Set<String> suppressedResolutions = new HashSet<>();
        private final Map<String, String> inputSelections = new HashMap<>();

        private final Set<String> collapsedPaths = new HashSet<>();
        private Node root;
        private long batches = 1;
        private boolean craftingMode;
        private Analysis cachedAnalysis;
        private List<CraftStep> cachedCraftingSteps;
        private long cachedAnalysisSupplyRevision = Long.MIN_VALUE;
        private Object cachedAnalysisMenu;
        private boolean cachedAnalysisCraftingMode;
        private int analysisRevision;
        private int cachedAnalysisRevision = -1;
        private long cachedCraftingStepsSupplyRevision = Long.MIN_VALUE;
        private int cachedCraftingStepsRevision = -1;

        private Tree(RecipeSnapshot rootRecipe, ItemStack rootStack) {
            this.rootRecipe = rootRecipe;
            this.rootStack = copyStack(rootStack);
            rebuild();
        }

        public Node root() {
            return root;
        }

        public long batches() {
            return batches;
        }

        public void setBatches(long batches) {
            long clamped = Math.max(1, Math.min(1_000_000L, batches));
            if (this.batches != clamped) {
                this.batches = clamped;
                invalidateAnalysis();
            }
        }

        public boolean craftingMode() {
            return craftingMode;
        }

        public void setCraftingMode(boolean craftingMode) {
            if (this.craftingMode != craftingMode) {
                this.craftingMode = craftingMode;
                invalidateAnalysis();
            }
        }

        void bindResolution(String ingredientKey, RecipeRef recipe) {
            resolutions.put(ingredientKey, recipe);
            suppressedResolutions.remove(ingredientKey);
        }

        public void resolve(String ingredientKey, RecipeRef recipe) {
            bindResolution(ingredientKey, recipe);
            rebuild();
        }

        Map<String, String> resolutionKeys() {
            Map<String, String> keys = new LinkedHashMap<>();
            resolutions.forEach((key, ref) -> {
                if (ref != null) {
                    keys.put(key, ref.key());
                }
            });
            return keys;
        }

        void applyResolutions(Map<String, String> keys) {
            keys.forEach((key, id) -> resolutions.put(key,
                new RecipeRef(null, null, id, "")));
        }

        public void clearResolution(String ingredientKey) {

            resolutions.remove(ingredientKey);
            suppressedResolutions.add(ingredientKey);
            rebuild();
        }

        public void toggleExpanded(Node node) {
            if (node == null) {
                return;
            }
            boolean expand = !node.expanded();
            node.setExpanded(expand);
            if (expand) {
                collapsedPaths.remove(node.path);
            } else {
                collapsedPaths.add(node.path);
            }
        }

        public void selectInput(Node node, ItemStack selected) {
            if (node == null || node.choiceKey.isEmpty() || selected == null || selected.isEmpty()) {
                return;
            }
            String selectedKey = ingredientKey(selected);
            boolean valid = node.alternatives.stream()
                .anyMatch(stack -> ingredientKey(stack).equals(selectedKey));
            if (valid) {
                if (ROOT_OUTPUT_CHOICE.equals(node.choiceKey)) {
                    rootStack = copyStack(selected);
                    rootOutputExplicit = true;
                    rebuild();
                    return;
                }
                inputSelections.put(node.choiceKey, selectedKey);
                rebuild();
            }
        }

        public void clearInputSelection(Node node) {
            if (node != null && !node.choiceKey.isEmpty()) {
                if (ROOT_OUTPUT_CHOICE.equals(node.choiceKey)) {
                    rootStack = rootRecipe.outputs().isEmpty()
                        ? ItemStack.EMPTY
                        : copyStack(rootRecipe.outputs().getFirst());
                    rootOutputExplicit = false;
                    rebuild();
                    return;
                }
                inputSelections.remove(node.choiceKey);
                rebuild();
            }
        }

        public void rebuild() {
            invalidateAnalysis();
            BuildContext context = new BuildContext();
            root = new Node(
                MaterialOption.of(rootStack),
                rootRecipe,
                rootRecipe.outputs().stream().map(MaterialOption::of).toList(),
                ROOT_OUTPUT_CHOICE,
                "root",
                List.of(),
                rootOutputExplicit
            );
            context.add();
            expand(root, context, new HashSet<>(), 0, "root");
        }

        public long idealBatch() {
            return Math.max(1, idealBatch(root, 1, 1));
        }

        public Analysis analyze() {
            Object menu = Ae2StorageIntegration.activeMenu();

            long supplyRevision = SupplyLedger.revision();
            if (cachedAnalysis != null
                && cachedAnalysisRevision == analysisRevision
                && cachedAnalysisMenu == menu
                && cachedAnalysisSupplyRevision == supplyRevision
                && cachedAnalysisCraftingMode == craftingMode) {
                return cachedAnalysis;
            }

            long perfStart = Perf.startAlways();

            Map<String, MutableCost> totalCosts = new LinkedHashMap<>();
            Map<ItemRef, MutableCost> totalRemainders = new LinkedHashMap<>();

            Map<ItemRef, MutableCost> planAvailable = craftingMode
                ? SupplyLedger.planningSupply()
                : new LinkedHashMap<>();
            RecipePlanner.resetProgress(root);
            RecipePlanner.resetAmounts(root);
            long rootWanted = Amounts.safeMultiply(IngredientDisplay.amountOf(rootStack), batches);
            calculatePlan(root, rootWanted, rootWanted,
                totalCosts, totalRemainders, planAvailable);

            Map<String, MutableCost> missingCosts = new LinkedHashMap<>();
            if (craftingMode) {

                calculateProgress(root, Amounts.safeMultiply(IngredientDisplay.amountOf(rootStack), batches),
                    SupplyLedger.planningSupply(), missingCosts);
            }

            List<Cost> costs = new ArrayList<>();

            Map<String, Long> supplyAmountMap = craftingMode ? SupplyLedger.planningAmounts() : Map.of();

            CostCalculator.Result vectorResult =
                compareVectorPlan(totalCosts, Amounts.safeMultiply(IngredientDisplay.amountOf(rootStack), batches));
            Map<ItemRef, Long> vectorNet = vectorResult == null ? Map.of() : vectorResult.net();

            Set<ItemRef> produced = new HashSet<>();
            if (vectorResult != null) {
                for (CostCalculator.Step step : vectorResult.steps()) {
                    produced.add(step.target());
                }
            }
            java.util.Set<ItemRef> usedByVector = new java.util.HashSet<>();
            for (Map.Entry<String, MutableCost> entry : totalCosts.entrySet()) {
                MutableCost total = entry.getValue();
                ItemRef matched = null;
                long netAmount = 0L;
                for (ItemStack alternative : total.alternatives) {
                    ItemRef ref = ItemKeys.of(alternative);
                    Long value = vectorNet.get(ref);
                    if (value != null) {
                        matched = ref;
                        netAmount = value;
                        break;
                    }
                }
                long required;
                long wanted;
                if (matched != null) {
                    usedByVector.add(matched);
                    if (netAmount >= 0L && !produced.contains(matched)) {

                        continue;
                    }
                    required = Math.max(0L, -netAmount);

                    wanted = required;
                } else {

                    required = total.amount;
                    wanted = total.wanted;
                }

                long supplied = craftingMode ? SupplyLedger.inventoryAmount(total.alternatives, supplyAmountMap) : 0;
                costs.add(new Cost(copyStack(total.stack), total.alternatives,
                    required, supplied, wanted));
            }

            Map<String, Node> displayByKey = new HashMap<>();
            collectNodesByKey(root, displayByKey);
            int hiddenRows = 0;
            ItemRef hiddenWorst = null;
            long hiddenWorstAmount = 0L;
            for (Map.Entry<ItemRef, Long> entry : vectorNet.entrySet()) {
                if ((entry.getValue() >= 0L && !produced.contains(entry.getKey()))
                    || usedByVector.contains(entry.getKey())) {
                    continue;
                }
                long needed = Math.max(0L, -entry.getValue());
                ItemStack display = treeDisplay(displayByKey, entry.getKey(), Math.max(1L, needed))
                    .orElse(ItemStack.EMPTY);
                if (display.isEmpty()) {
                    display = IngredientDisplay.displayForKey(entry.getKey().key(), Math.max(1L, needed))
                        .orElse(ItemStack.EMPTY);
                }
                if (display.isEmpty()) {

                    hiddenRows++;
                    if (needed > hiddenWorstAmount) {
                        hiddenWorstAmount = needed;
                        hiddenWorst = entry.getKey();
                    }
                    continue;
                }
                costs.add(new Cost(display, List.of(display), needed, 0L, needed));
            }
            if (hiddenRows > 0) {

                JERTLog.debug("vector cost rows total={} shown={} hidden={} maxHiddenSlot={} maxHiddenCount={}",
                    costs.size() + hiddenRows, costs.size(), hiddenRows,
                    hiddenWorst == null ? "-" : hiddenWorst.key(), hiddenWorstAmount);
            }

            List<Cost> leftovers = totalRemainders.values().stream()
                .filter(cost -> cost.amount > 0)
                .map(cost -> new Cost(copyStack(cost.stack), cost.alternatives,
                    cost.amount, 0, cost.amount))
                .toList();

            cachedAnalysis = new Analysis(List.copyOf(costs), leftovers, catalystsOf(vectorResult));
            if (!cachedAnalysis.catalysts().isEmpty()) {
                JERTLog.debug("catalysts: {} kind(s) used by this tree", cachedAnalysis.catalysts().size());
            }
            cachedAnalysisRevision = analysisRevision;
            cachedAnalysisSupplyRevision = supplyRevision;
            cachedAnalysisCraftingMode = craftingMode;
            cachedAnalysisMenu = menu;
            cachedCraftingSteps = null;
            Perf.endAdaptive(Perf.ANALYZE, perfStart);
            return cachedAnalysis;
        }

        private Map<ItemRef, Long> stockAmounts() {
            Map<ItemRef, Long> amounts = new LinkedHashMap<>();
            if (!craftingMode) {
                return amounts;
            }
            SupplyLedger.planningSupply().forEach((ref, cost) -> amounts.put(ref, cost.amount));
            return amounts;
        }

        private CostCalculator.Result compareVectorPlan(Map<String, MutableCost> totalCosts, long desired) {
            List<RecipeRecord> records = RecipeArchive.records(this);
            if (records.isEmpty()) {
                JERTLog.debug("vector compare: skipped, current tree has no usable recipe snapshot");
                return null;
            }
            RecipeSource source = RecipeLibrary.of("tree", null, records, Map.of());
            CostVector vector = CostVector.ofStock(stockAmounts());

            vector.require(ItemKeys.of(rootStack), desired);
            CostCalculator.Result result = CostCalculator.expand(
                vector, source, CostCalculator.DEFAULT_MAX_STEPS);

            if (!SafeConfig.developerMode()) {
                return result;
            }

            Map<String, MutableCost> byAlternative = new HashMap<>();
            for (MutableCost cost : totalCosts.values()) {
                for (ItemStack alternative : cost.alternatives) {
                    byAlternative.putIfAbsent(ItemKeys.legacy(ItemKeys.of(alternative)), cost);
                }
            }

            int negatives = 0;
            int matched = 0;
            int amountDiff = 0;
            int newOnly = 0;
            for (Map.Entry<ItemRef, Long> entry : result.net().entrySet()) {
                if (entry.getValue() >= 0) {
                    continue;
                }
                negatives++;
                MutableCost cost = byAlternative.get(entry.getKey().key());
                if (cost == null) {
                    newOnly++;
                } else if (cost.amount != -entry.getValue()) {
                    amountDiff++;
                } else {
                    matched++;
                }
            }

            String signature = records.size() + "/" + negatives + "/" + matched + "/"
                + amountDiff + "/" + newOnly + "/" + result.steps().size() + "/" + result.truncated();
            if (!signature.equals(lastCompareSignature)) {
                lastCompareSignature = signature;
                JERTLog.debug(
                    "vector compare: {} recipes, {} negatives, {} matched, {} amount-diff, {} new-only, {} steps, truncated={}",
                    records.size(), negatives, matched, amountDiff, newOnly,
                    result.steps().size(), result.truncated());
            }
            return result;
        }

        public List<CraftStep> craftingSteps() {
            analyze();
            if (cachedCraftingSteps != null
                && cachedCraftingStepsRevision == cachedAnalysisRevision
                && cachedCraftingStepsSupplyRevision == cachedAnalysisSupplyRevision) {
                return cachedCraftingSteps;
            }

            List<CraftStep> kernel = kernelTreeSteps();
            if (!kernel.isEmpty()) {
                cachedCraftingSteps = kernel;
            } else {
                Map<String, MutableCraftStep> steps = new LinkedHashMap<>();
                collectCraftSteps(root, steps, false);
                cachedCraftingSteps = steps.values().stream()
                    .map(MutableCraftStep::freeze)
                    .toList();
            }
            cachedCraftingStepsRevision = cachedAnalysisRevision;
            cachedCraftingStepsSupplyRevision = cachedAnalysisSupplyRevision;
            return cachedCraftingSteps;
        }

        public Optional<CraftStep> directTransferStep(Node node) {
            if (node == null || node.recipe == null) {
                return Optional.empty();
            }
            analyze();

            return Optional.of(createCraftStep(node, 1));
        }

        public List<CraftStep> recursiveCraftingSteps(CraftStep target) {
            if (target == null) {
                return List.of();
            }
            analyze();

            List<CraftStep> kernel = kernelCraftingSteps(target);
            if (!kernel.isEmpty()) {
                invalidateAnalysis();
                return kernel;
            }
            Set<String> targets = Set.copyOf(target.nodePaths());

            for (String path : targets) {
                Node node = findNode(root, path);
                if (node != null) {
                    forceTargetProgress(node);
                }
            }
            List<CraftStep> steps = new ArrayList<>();
            collectTargetSubtrees(root, targets, false, steps);
            List<CraftStep> result = List.copyOf(steps);
            invalidateAnalysis();
            return result;
        }

        private List<CraftStep> kernelCraftingSteps(CraftStep target) {
            ItemRef root = ItemKeys.of(target.stack());
            Map<ItemRef, Long> stock = stockAmounts();
            for (ItemStack alternative : target.alternatives()) {
                stock.keySet().remove(ItemKeys.of(alternative));
            }
            return planSteps(root, Math.max(1L, target.remaining()), stock);
        }

        private List<CraftStep> kernelTreeSteps() {
            if (rootStack == null || rootStack.isEmpty()) {
                return List.of();
            }
            long desired = Amounts.safeMultiply(
                IngredientDisplay.amountOf(rootStack), Math.max(1L, batches));
            List<CraftStep> steps = planSteps(ItemKeys.of(rootStack), desired, stockAmounts());
            if (steps.isEmpty()) {
                return steps;
            }
            String rootKey = ingredientKey(rootStack);
            List<CraftStep> display = new ArrayList<>(steps.size());
            for (CraftStep step : steps) {
                if (ingredientKey(step.stack()).equals(rootKey)) {
                    display.add(step);
                }
            }
            for (CraftStep step : steps) {
                if (!ingredientKey(step.stack()).equals(rootKey)) {
                    display.add(step);
                }
            }
            return display;
        }

        private List<CraftStep> planSteps(ItemRef demand, long amount, Map<ItemRef, Long> stock) {
            List<RecipeRecord> records = RecipeArchive.records(this);
            if (records.isEmpty()) {
                JERTLog.debug("craft plan: no recipe records, using tree steps");
                return List.of();
            }
            CostVector vector = CostVector.ofStock(stock);
            vector.require(demand, Math.max(1L, amount));
            RecipeSource source = RecipeLibrary.of("tree", null, records, Map.of());
            CostCalculator.Result result = CostCalculator.expand(
                vector, source, CostCalculator.DEFAULT_MAX_STEPS);
            if (result.truncated()) {
                JERTLog.warn("craft plan: kernel hit the {}-step cap, plan is incomplete",
                    CostCalculator.DEFAULT_MAX_STEPS);
            }
            Map<String, RecipeRecord> byId = new HashMap<>();
            for (RecipeRecord record : records) {
                byId.putIfAbsent(record.id(), record);
            }

            Map<String, Node> byKey = new HashMap<>();
            collectNodesByKey(root, byKey);
            CraftStepOrder.Ordered ordered = CraftStepOrder.resolve(result.steps(), records);
            List<CraftStep> steps = new ArrayList<>();
            for (CostCalculator.Step step : ordered.steps()) {
                CraftStep mapped = toCraftStep(step, byId.get(step.recipeId()), byKey);
                if (mapped == null) {

                    return List.of();
                }
                steps.add(mapped);
            }
            JERTLog.debug("craft plan: kernel {} step(s), cyclic={}, demand={} x{}, truncated={}",
                steps.size(), ordered.cyclic(), demand.key(), amount, result.truncated());
            return steps;
        }

        private CraftStep toCraftStep(
            CostCalculator.Step step,
            RecipeRecord record,
            Map<String, Node> byKey
        ) {
            if (record == null) {
                unavailableStep(step.recipeId(), "no library record");
                return null;
            }
            RecipeRef live = liveRef(step.recipeId());
            if (live == null) {
                unavailableStep(step.recipeId(), "no live JEI recipe");
                return null;
            }
            RecipeRecord.Slot output = outputSlotOf(record, step.target());
            long needed = Math.max(1L, step.needed());
            Optional<ItemStack> display = treeDisplay(byKey, step.target(), needed);
            if (display.isEmpty()) {
                display = IngredientDisplay.displayForKey(step.target().key(), needed);
            }
            if (display.isEmpty()) {

                unavailableStep(step.recipeId(), "no display stack for " + step.target().key());
                return null;
            }
            List<ItemStack> alternatives = new ArrayList<>();
            for (ItemRef alternative : output.alternatives()) {
                IngredientDisplay.displayForKey(alternative.key(), needed)
                    .ifPresent(alternatives::add);
            }
            if (alternatives.isEmpty()) {
                alternatives.add(display.get());
            }

            return new CraftStep(
                live,
                display.get(),
                alternatives,
                java.util.Collections.nCopies(record.inputs().size(), ""),
                step.batches(),
                needed,
                needed,
                Progress.UNSTARTED,
                List.of()
            );
        }

        private static void collectNodesByKey(Node node, Map<String, Node> out) {
            if (node == null) {
                return;
            }
            String key = node.ingredientKey;
            if (key != null && !key.isBlank()) {
                out.putIfAbsent(key, node);
            }
            for (Node child : node.children) {
                collectNodesByKey(child, out);
            }
        }

        private static Optional<ItemStack> treeDisplay(
            Map<String, Node> byKey,
            ItemRef target,
            long amount
        ) {
            if (byKey == null || target == null) {
                return Optional.empty();
            }
            Node node = byKey.get(target.key());
            if (node == null) {
                return Optional.empty();
            }
            ItemStack stack = node.stack();
            if (stack.isEmpty()) {
                return Optional.empty();
            }
            ItemStack copy = stack.copy();
            copy.setCount(1);
            IngredientDisplay.mark(copy, target.key(), amount);
            return Optional.of(copy);
        }

        private RecipeRef liveRef(String id) {
            RecipeSnapshot snapshot = StoredRecipes.byId(id);
            if (snapshot != null && snapshot.ref().category() != null) {
                return snapshot.ref();
            }
            return StoredRecipeRefs.resolve(id).orElse(null);
        }

        private void unavailableStep(String recipeId, String why) {
            String line = recipeId + " → " + why;
            if (UNAVAILABLE_LOGGED.add(line)) {
                JERTLog.warn("craft plan: '{}' unavailable ({}), using tree steps", recipeId, why);
            }
        }

        private RecipeRecord.Slot outputSlotOf(RecipeRecord record, ItemRef target) {
            for (RecipeRecord.Slot slot : record.outputs()) {
                if (slot.alternatives().contains(target)) {
                    return slot;
                }
            }
            return record.outputs().getFirst();
        }

        private void invalidateAnalysis() {
            analysisRevision++;
            cachedAnalysis = null;
            cachedCraftingSteps = null;
            cachedAnalysisSupplyRevision = Long.MIN_VALUE;
            cachedCraftingStepsSupplyRevision = Long.MIN_VALUE;
            cachedAnalysisRevision = -1;
            cachedCraftingStepsRevision = -1;
        }

        private void forceTargetProgress(Node target) {
            if (target == null || target.amount <= 0) {
                return;
            }

            Map<ItemRef, MutableCost> available = SupplyLedger.planningSupply();

            if (target.explicitChoice) {
                available.remove(target.ingredientKey);
            } else {
                available.keySet().removeIf(key -> target.alternatives.stream()
                    .anyMatch(alternative -> ingredientKey(alternative).equals(key)));
            }
            RecipePlanner.resetProgress(target);
            calculateProgress(target, target.amount, available, new LinkedHashMap<>());
        }

        private Node findNode(Node node, String path) {
            if (node == null || path == null) {
                return null;
            }
            if (path.equals(node.path)) {
                return node;
            }
            for (Node child : node.children) {
                Node found = findNode(child, path);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }

        private void expand(Node node, BuildContext context, Set<String> activeRecipes, int depth, String path) {
            RecipeSnapshot snapshot = node.recipe();
            if (snapshot == null || !snapshot.canExpand() || depth >= MAX_DEPTH || context.count >= MAX_NODES) {
                return;
            }

            String recipeKey = snapshot.ref().key();
            if (!activeRecipes.add(recipeKey)) {
                node.cycle = true;
                node.expanded = false;
                return;
            }

            for (int inputIndex = 0; inputIndex < snapshot.inputs().size(); inputIndex++) {
                if (context.count >= MAX_NODES) {
                    break;
                }
                RecipeInput input = snapshot.inputs().get(inputIndex);
                if (input.alternatives().isEmpty()) {
                    continue;
                }
                String choiceKey = path + "/" + snapshot.ref().key() + "/" + inputIndex;
                SelectedInput selected = selectInput(input, choiceKey);
                MaterialOption selectedMaterial = selected.option();
                RecipeSnapshot childRecipe = preferredRecipe(selectedMaterial);

                if (childRecipe == null && !selected.explicit()) {
                    for (MaterialOption alternative : input.options()) {
                        if (alternative.ref().equals(selectedMaterial.ref())) {
                            continue;
                        }
                        RecipeSnapshot found = preferredRecipe(alternative);
                        if (found != null) {
                            childRecipe = found;
                            selectedMaterial = alternative;
                            break;
                        }
                    }
                }
                String childPath = choiceKey + "/" + selectedMaterial.ref().key();
                Node child = new Node(
                    selectedMaterial,
                    childRecipe,
                    input.options(),
                    choiceKey,
                    childPath,
                    input.slotIndexes(),
                    selected.explicit()
                );
                node.children.add(child);

                child.expanded = !collapsedPaths.contains(childPath);
                context.add();
                expand(child, context, activeRecipes, depth + 1, childPath);
            }
            activeRecipes.remove(recipeKey);
        }

        private SelectedInput selectInput(RecipeInput input, String choiceKey) {
            String selectedKey = inputSelections.get(choiceKey);
            if (selectedKey != null) {
                for (MaterialOption option : input.options()) {
                    if (option.ref().key().equals(selectedKey)) {
                        return new SelectedInput(option, true);
                    }
                }
                inputSelections.remove(choiceKey);
            }

            return RecipeTreeFavorites.bookmarkedCandidate(input.alternatives())
                .map(stack -> new SelectedInput(MaterialOption.of(stack), false))
                .orElseGet(() -> new SelectedInput(input.firstOption(), false));
        }

        private RecipeSnapshot preferredRecipe(MaterialOption material) {
            String key = material.ref().key();

            if (suppressedResolutions.contains(key)) {
                return null;
            }

            RecipeRef explicit = resolutions.get(key);
            if (explicit != null) {
                RecipeSnapshot chosen = RecipeLookup.snapshot(explicit);
                if (chosen == null) {

                    chosen = StoredRecipes.byId(explicit.key());
                }
                if (chosen != null && chosen.produces(key)) {
                    return chosen;
                }
            }

            return StoredRecipes.forTarget(material.ref());
        }

        private long idealBatch(Node node, long total, long amount) {
            if (node == null || node.recipe == null) {
                return total;
            }
            long divisor = node.recipe.outputAmount(node.ingredientKey);
            if (divisor > 0) {
                long multiple = divisor / Amounts.gcd(divisor, amount);
                total = Amounts.safeMultiply(total, multiple / Amounts.gcd(total, multiple));
            }
            for (Node child : node.children) {
                total = idealBatch(child, total, Amounts.safeMultiply(amount, child.perCraft));
            }
            return total;
        }

        private void calculatePlan(
            Node node,
            long wanted,
            long desired,
            Map<String, MutableCost> costs,
            Map<ItemRef, MutableCost> remainders,
            Map<ItemRef, MutableCost> available
        ) {

            node.wanted = wanted;
            node.amount = desired;
            long stillNeeded = RecipePlanner.consumeForNode(available, node, desired);
            long remaining = RecipePlanner.consumeForNode(remainders, node, stillNeeded);
            RecipeSnapshot recipe = node.recipe;
            if (recipe == null || node.children.isEmpty()) {

                RecipePlanner.addCost(costs, node, Math.max(0L, remaining), wanted);
                node.crafts = 0;
                return;
            }

            long outputAmount = recipe.outputAmount(node.ingredientKey);
            long crafts = remaining <= 0 ? 0 : Amounts.ceilDiv(remaining, outputAmount);

            long fullCrafts = wanted <= 0 ? 0 : Amounts.ceilDiv(wanted, outputAmount);
            node.crafts = crafts;
            for (Node child : node.children) {
                calculatePlan(child,
                    Amounts.safeMultiply(child.perCraft, fullCrafts),
                    Amounts.safeMultiply(child.perCraft, crafts),
                    costs, remainders, available);
            }
            if (crafts <= 0) {
                return;
            }

            long surplus = Amounts.safeMultiply(crafts, outputAmount) - remaining;
            SupplyLedger.add(remainders, node.stack(), surplus);
            for (ItemStack output : recipe.outputsFor(node.ingredientKey)) {
                if (!ingredientKey(output).equals(node.ingredientKey)) {

                    SupplyLedger.add(
                        remainders, output, Amounts.safeMultiply(IngredientDisplay.amountOf(output), crafts)
                    );
                }
            }
        }

        private void calculateProgress(
            Node node,
            long desired,
            Map<ItemRef, MutableCost> available,
            Map<String, MutableCost> missingCosts
        ) {
            long remaining = RecipePlanner.consumeForNode(available, node, desired);
            node.remaining = remaining;
            long supplied = desired - remaining;
            if (remaining <= 0) {
                RecipePlanner.complete(node);
                return;
            }
            node.progress = supplied > 0 ? Progress.PARTIAL : Progress.UNSTARTED;
            RecipeSnapshot recipe = node.recipe;
            if (recipe == null || node.children.isEmpty()) {
                RecipePlanner.addCost(missingCosts, node, remaining, remaining);
                return;
            }

            long crafts = Amounts.ceilDiv(remaining, recipe.outputAmount(node.ingredientKey));
            boolean childProgress = false;
            for (Node child : node.children) {
                calculateProgress(child, Amounts.safeMultiply(child.perCraft, crafts), available, missingCosts);
                childProgress |= child.progress != Progress.UNSTARTED;
            }
            if (node.progress == Progress.UNSTARTED && childProgress) {
                node.progress = Progress.PARTIAL;
            }
            for (ItemStack output : recipe.outputsFor(node.ingredientKey)) {
                long produced = Amounts.safeMultiply(IngredientDisplay.amountOf(output), crafts);
                SupplyLedger.addSupply(available, output, produced);
            }

            RecipePlanner.consume(available, node.ingredientRef, SupplyLedger.mapAmount(node.stack(), remaining));
        }

        private void collectCraftSteps(Node node, Map<String, MutableCraftStep> steps, boolean missingOnly) {

            if (node.recipe != null && node.wanted > 0 && (!missingOnly || node.remaining > 0)) {
                long craftBatches = node.remaining > 0
                    ? Amounts.ceilDiv(node.remaining, node.recipe.outputAmount(node.ingredientKey))
                    : node.crafts;
                addCraftStep(node, craftBatches, steps);
            }
            for (Node child : node.children) {
                collectCraftSteps(child, steps, missingOnly);
            }
        }

        private void collectTargetSubtrees(
            Node node,
            Set<String> targets,
            boolean forceFullTree,
            List<CraftStep> steps
        ) {
            if (targets.contains(node.path)) {
                collectSubtreePostOrder(node, forceFullTree, steps);
                return;
            }
            for (Node child : node.children) {
                collectTargetSubtrees(child, targets, forceFullTree, steps);
            }
        }

        private void collectSubtreePostOrder(
            Node node,
            boolean forceFullTree,
            List<CraftStep> steps
        ) {
            for (Node child : node.children) {
                if (forceFullTree || child.remaining > 0) {
                    collectSubtreePostOrder(child, forceFullTree, steps);
                }
            }
            if (node.recipe == null
                || node.remaining <= 0
                || (forceFullTree && node.crafts <= 0)) {
                return;
            }
            long craftBatches = forceFullTree
                ? node.crafts
                : Amounts.ceilDiv(node.remaining, node.recipe.outputAmount(node.ingredientKey));
            steps.add(createCraftStep(node, craftBatches));
        }

        private void addCraftStep(Node node, long craftBatches, Map<String, MutableCraftStep> steps) {
            CraftStep candidate = createCraftStep(node, craftBatches);
            String signature = candidate.recipe().key() + "|" + ingredientKey(candidate.stack()) + "|"
                + String.join(",", candidate.selectedInputs());
            MutableCraftStep step = steps.get(signature);
            if (step == null) {
                steps.put(signature, new MutableCraftStep(candidate));
            } else {
                step.mergeAlternatives(candidate.alternatives());
                step.batches = Amounts.safeAdd(step.batches, candidate.batches());
                step.remaining = Amounts.safeAdd(step.remaining, candidate.remaining());
                step.total = Amounts.safeAdd(step.total, candidate.total());
                step.nodePaths.addAll(candidate.nodePaths());
                if (step.progress != candidate.progress()) {
                    step.progress = Progress.PARTIAL;
                }
            }
        }

        private CraftStep createCraftStep(Node node, long craftBatches) {
            List<String> selectedInputs = new ArrayList<>(java.util.Collections.nCopies(
                node.recipe.inputSlotCount(),
                ""
            ));
            for (Node child : node.children) {
                if (!child.explicitChoice) {
                    continue;
                }
                for (int slotIndex : child.inputSlotIndexes) {
                    if (slotIndex >= 0 && slotIndex < selectedInputs.size()) {
                        selectedInputs.set(slotIndex, child.ingredientKey);
                    }
                }
            }
            return new CraftStep(
                node.recipe.ref(),
                node.stack(),
                node.explicitChoice ? List.of(node.stack()) : node.alternatives,
                selectedInputs,
                craftBatches,
                node.remaining,
                node.wanted,
                node.progress,
                List.of(node.path)
            );
        }
    }

    private static final class BuildContext {
        private int count;

        private void add() {
            count++;
        }
    }

    static final class MutableCost {
        final ItemStack stack;
        final List<ItemStack> alternatives;

        long amount;

        long wanted;

        MutableCost(ItemStack stack, long amount) {
            this(stack, List.of(stack), amount);
        }

        MutableCost(ItemStack stack, List<ItemStack> alternatives, long amount) {
            this.stack = copyStack(stack);
            this.stack.setCount(1);
            this.alternatives = alternatives.stream()
                .map(alternative -> {
                    ItemStack copy = copyStack(alternative);
                    copy.setCount(1);
                    return copy;
                })
                .toList();
            this.amount = amount;
            this.wanted = amount;
        }
    }

    private record SelectedInput(MaterialOption option, boolean explicit) {
    }

    private static final class MutableCraftStep {
        private final RecipeRef recipe;
        private final ItemStack stack;
        private final List<ItemStack> alternatives = new ArrayList<>();
        private final List<String> selectedInputs;
        private long batches;
        private long remaining;
        private long total;
        private Progress progress;
        private final List<String> nodePaths = new ArrayList<>();

        private MutableCraftStep(CraftStep source) {
            this.recipe = source.recipe();
            this.stack = copyStack(source.stack());
            mergeAlternatives(source.alternatives());
            this.selectedInputs = source.selectedInputs();
            this.batches = source.batches();
            this.remaining = source.remaining();
            this.total = source.total();
            this.progress = source.progress();
            this.nodePaths.addAll(source.nodePaths());
        }

        private CraftStep freeze() {
            return new CraftStep(recipe, stack, alternatives, selectedInputs, batches, remaining, total, progress, nodePaths);
        }

        private void mergeAlternatives(Collection<ItemStack> candidates) {
            Set<String> existing = alternatives.stream()
                .map(RecipeTreeData::ingredientKey)
                .collect(java.util.stream.Collectors.toSet());
            for (ItemStack candidate : candidates) {
                if (existing.add(ingredientKey(candidate))) {
                    alternatives.add(copyStack(candidate));
                }
            }
        }
    }

    public static Optional<Tree> build(IRecipeLayoutDrawable<?> rootLayout) {
        return build(rootLayout, null);
    }

    public static Optional<Tree> fromLibrary(RecipeRecord record) {
        RecipeSnapshot snapshot = StoredRecipes.snapshotOf(record);
        if (snapshot == null || snapshot.outputs().isEmpty()) {
            JERTLog.warn("recipe tree: library recipe {} has no usable output, cannot be a root",
                record == null ? "<null>" : record.id());
            return Optional.empty();
        }
        return Optional.of(new Tree(snapshot, snapshot.outputs().getFirst()));
    }

    public static Optional<Tree> build(IRecipeLayoutDrawable<?> rootLayout, ItemStack selectedOutput) {
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

    private static String ms(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1_000_000.0);
    }

    private static Optional<Tree> buildTree(IRecipeLayoutDrawable<?> rootLayout, ItemStack selectedOutput) {
        Optional<RecipeSnapshot> root = RecipeLookup.snapshot(rootLayout);
        if (root.isEmpty() || root.get().outputs.isEmpty()) {
            return Optional.empty();
        }
        RecipeSnapshot rootSnapshot = root.get();
        ItemStack output = copyStack(rootSnapshot.outputs.getFirst());
        if (selectedOutput != null && !selectedOutput.isEmpty()) {
            String selectedKey = ingredientKey(selectedOutput);
            output = rootSnapshot.outputs.stream()
                .filter(candidate -> ingredientKey(candidate).equals(selectedKey))
                .findFirst()
                .map(RecipeTreeData::copyStack)
                .orElse(output);
        }

        return Optional.of(new Tree(rootSnapshot, output));
    }

    static Optional<Tree> restore(
        RecipeRef rootRef,
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

    static Optional<Tree> restore(
        RecipeSnapshot rootSnapshot,
        String outputKey,
        int outputCount,
        long batches,
        boolean craftingMode
    ) {
        if (rootSnapshot == null || rootSnapshot.outputs().isEmpty()) {
            return Optional.empty();
        }
        ItemStack output = rootSnapshot.outputs().stream()
            .filter(candidate -> ingredientKey(candidate).equals(outputKey))
            .findFirst()
            .orElse(rootSnapshot.outputs().getFirst())
            .copy();

        if (outputCount > 0 && !IngredientDisplay.measured(output)) {
            output.setCount(Math.min(output.getMaxStackSize(), outputCount));
        }
        Tree restored = new Tree(rootSnapshot, output);
        restored.batches = Math.max(1, Math.min(1_000_000L, batches));
        restored.craftingMode = craftingMode;
        restored.invalidateAnalysis();
        return Optional.of(restored);
    }

    public static String ingredientKey(ItemStack stack) {
        return ItemKeys.legacy(ItemKeys.of(stack));
    }

    public static void clearCaches() {
        StoredRecipeRefs.clearCaches();
        RecipeLookup.clearCaches();
        FluidRecipeCompat.clear();
        SupplyLedger.clearCaches();
        ItemKeys.clear();
        SlotTags.clear();
        StoredRecipes.clearCaches();
    }
}
