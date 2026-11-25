package com.slwold.jeirecipetree.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CostCalculator {

    public static final int DEFAULT_MAX_STEPS = 1000;

    public record Step(ItemRef target, String recipeId, long batches, long needed) {
    }

    public record Result(List<Step> steps, Map<ItemRef, Long> net, Map<ItemRef, Long> catalysts, boolean truncated) {

        public Result {
            steps = List.copyOf(steps);
            catalysts = Map.copyOf(catalysts);
        }

        public List<ItemRef> rawMaterials() {
            List<ItemRef> result = new ArrayList<>();
            net.forEach((ref, amount) -> {
                if (amount < 0) {
                    result.add(ref);
                }
            });
            return result;
        }
    }

    private CostCalculator() {
    }

    public static Result expand(CostVector vector, RecipeSource source, int maxSteps) {
        int limit = maxSteps <= 0 ? DEFAULT_MAX_STEPS : maxSteps;
        List<Step> steps = new ArrayList<>();
        Map<ItemRef, ItemRef> parentOf = new HashMap<>();
        Map<ItemRef, Long> catalysts = new HashMap<>();
        Deque<ItemRef> pending = new ArrayDeque<>(vector.deficits());
        boolean truncated = false;

        while (!pending.isEmpty()) {
            if (steps.size() >= limit) {
                truncated = true;
                break;
            }
            ItemRef label = pending.poll();
            if (vector.amount(label) >= 0) {
                continue;
            }
            RecipeSource.Match match = source.match(label);
            if (match == null) {
                continue;
            }
            if (isOwnAncestor(parentOf, label)) {
                continue;
            }
            RecipeRecord recipe = match.recipe();
            RecipeRecord.Slot mainOutput = recipe.outputs().get(match.outputSlot());
            long needed = -vector.amount(label);
            long batches = Amounts.ceilDiv(needed, mainOutput.amount());
            if (batches <= 0) {
                continue;
            }

            vector.add(label, Amounts.safeMultiply(mainOutput.amount(), batches));
            for (int index = 0; index < recipe.outputs().size(); index++) {
                if (index == match.outputSlot()) {
                    continue;
                }
                RecipeRecord.Slot extra = recipe.outputs().get(index);
                vector.add(extra.accountingKey(), Amounts.safeMultiply(extra.amount(), batches));
            }
            for (RecipeRecord.Slot input : recipe.inputs()) {
                ItemRef ref = input.accountingKey();
                long delta = -Amounts.safeMultiply(input.amount(), batches);
                if (vector.addTracked(ref, delta, pending)) {
                    parentOf.putIfAbsent(ref, label);
                }
            }

            for (RecipeRecord.Slot catalyst : recipe.catalysts()) {
                catalysts.merge(catalyst.accountingKey(), catalyst.amount(), Math::max);
            }
            steps.add(new Step(label, recipe.id(), batches, needed));
        }
        return new Result(steps, Map.copyOf(vector.view()), catalysts, truncated);
    }

    private static boolean isOwnAncestor(Map<ItemRef, ItemRef> parentOf, ItemRef label) {
        ItemRef cursor = parentOf.get(label);
        int guard = 0;
        while (cursor != null && guard++ <= parentOf.size()) {
            if (cursor.equals(label)) {
                return true;
            }
            cursor = parentOf.get(cursor);
        }
        return false;
    }
}
