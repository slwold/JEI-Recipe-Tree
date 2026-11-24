package com.slwold.jeirecipetree.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CraftStepOrder {

    public record Ordered(List<CostCalculator.Step> steps, boolean cyclic) {

        public Ordered {
            steps = List.copyOf(steps);
        }
    }

    private CraftStepOrder() {
    }

    public static Ordered resolve(List<CostCalculator.Step> steps, List<RecipeRecord> recipes) {
        if (steps == null || steps.isEmpty()) {
            return new Ordered(List.of(), false);
        }
        Map<String, RecipeRecord> byId = new HashMap<>();
        for (RecipeRecord recipe : recipes == null ? List.<RecipeRecord>of() : recipes) {
            byId.putIfAbsent(recipe.id(), recipe);
        }
        List<CostCalculator.Step> merged = merge(steps);
        List<List<Integer>> edges = dependencyEdges(merged, byId);
        return kahn(merged, edges);
    }

    private static List<CostCalculator.Step> merge(List<CostCalculator.Step> steps) {
        Map<String, Integer> seen = new LinkedHashMap<>();
        List<CostCalculator.Step> merged = new ArrayList<>();
        for (CostCalculator.Step step : steps) {
            String key = step.target().key() + "\u0000" + step.recipeId();
            Integer existing = seen.get(key);
            if (existing == null) {
                seen.put(key, merged.size());
                merged.add(step);
                continue;
            }
            CostCalculator.Step before = merged.get(existing);
            merged.set(existing, new CostCalculator.Step(
                before.target(),
                before.recipeId(),
                Amounts.safeAdd(before.batches(), step.batches()),
                Amounts.safeAdd(before.needed(), step.needed())
            ));
        }
        return merged;
    }

    private static Map<ItemRef, Integer> producers(
        List<CostCalculator.Step> steps,
        Map<String, RecipeRecord> byId
    ) {
        Map<ItemRef, Integer> producers = new HashMap<>();
        for (int index = 0; index < steps.size(); index++) {
            RecipeRecord recipe = byId.get(steps.get(index).recipeId());
            if (recipe == null) {
                continue;
            }
            for (RecipeRecord.Slot slot : recipe.outputs()) {
                for (ItemRef alternative : slot.alternatives()) {
                    producers.putIfAbsent(alternative, index);
                }
            }
        }
        return producers;
    }

    private static List<List<Integer>> dependencyEdges(
        List<CostCalculator.Step> steps,
        Map<String, RecipeRecord> byId
    ) {
        List<List<Integer>> edges = new ArrayList<>(steps.size());
        for (int index = 0; index < steps.size(); index++) {
            edges.add(new ArrayList<>());
        }
        Map<ItemRef, Integer> producers = producers(steps, byId);
        for (int consumer = 0; consumer < steps.size(); consumer++) {
            RecipeRecord recipe = byId.get(steps.get(consumer).recipeId());
            if (recipe == null) {
                continue;
            }
            for (RecipeRecord.Slot input : recipe.inputs()) {
                for (ItemRef alternative : input.alternatives()) {
                    Integer producer = producers.get(alternative);
                    if (producer != null && producer != consumer) {
                        edges.get(producer).add(consumer);
                    }
                }
            }
        }
        return edges;
    }

    private static Ordered kahn(List<CostCalculator.Step> steps, List<List<Integer>> edges) {
        int[] indegree = new int[steps.size()];
        for (List<Integer> consumers : edges) {
            for (int consumer : consumers) {
                indegree[consumer]++;
            }
        }
        Deque<Integer> ready = new ArrayDeque<>();
        for (int index = 0; index < indegree.length; index++) {
            if (indegree[index] == 0) {
                ready.add(index);
            }
        }
        List<CostCalculator.Step> ordered = new ArrayList<>(steps.size());
        boolean[] emitted = new boolean[steps.size()];
        while (!ready.isEmpty()) {
            int index = ready.poll();
            emitted[index] = true;
            ordered.add(steps.get(index));
            for (int consumer : edges.get(index)) {
                if (--indegree[consumer] == 0) {
                    ready.add(consumer);
                }
            }
        }
        boolean cyclic = ordered.size() != steps.size();
        if (cyclic) {

            for (int index = 0; index < steps.size(); index++) {
                if (!emitted[index]) {
                    ordered.add(steps.get(index));
                }
            }
        }
        return new Ordered(ordered, cyclic);
    }
}
