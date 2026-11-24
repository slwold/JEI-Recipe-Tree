package com.slwold.jeirecipetree.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class CraftStepOrderTest {

    public static void main(String[] args) {
        dependenciesComeFirst();
        sameRecipeMergesIntoOneStep();
        cycleIsFlaggedNotLost();
        System.out.println("CraftStepOrder checks passed: dependency-first order, same-recipe merge, cycle fallback");
    }

    private static void dependenciesComeFirst() {
        List<RecipeRecord> recipes = List.of(
            recipe("rainbow", slots(slot(8, "rainbow")),
                slots(slot(1, "f1"), slot(1, "f2"), slot(1, "f3"))),
            recipe("f1", slots(slot(1, "f1")), slots(slot(1, "base"))),
            recipe("f2", slots(slot(1, "f2")), slots(slot(1, "f1"))),
            recipe("f3", slots(slot(1, "f3")), slots(slot(1, "f2")))
        );
        CostVector vector = CostVector.empty();
        vector.require(ref("rainbow"), 1);
        CostCalculator.Result expanded = CostCalculator.expand(vector, source(recipes),
            CostCalculator.DEFAULT_MAX_STEPS);

        CraftStepOrder.Ordered ordered = CraftStepOrder.resolve(expanded.steps(), recipes);
        assertTrue(!ordered.cyclic(), "链式配方不应被判成环");
        assertEquals(4, ordered.steps().size(), "四条配方各展开一次");

        List<String> order = ordered.steps().stream().map(step -> step.target().key()).toList();
        assertTrue(order.indexOf("f1") < order.indexOf("f2"), "f1 必须先于 f2：" + order);
        assertTrue(order.indexOf("f2") < order.indexOf("f3"), "f2 必须先于 f3：" + order);
        assertTrue(order.indexOf("f3") < order.indexOf("rainbow"), "中间品必须先于成品：" + order);
    }

    private static void sameRecipeMergesIntoOneStep() {
        List<RecipeRecord> recipes = List.of(
            recipe("rainbow", slots(slot(1, "rainbow")),
                slots(slot(1, "part"), slot(1, "half"))),
            recipe("half", slots(slot(1, "half")), slots(slot(2, "part"))),
            recipe("part", slots(slot(1, "part")), slots(slot(1, "ore")))
        );
        CostVector vector = CostVector.empty();
        vector.require(ref("rainbow"), 1);
        CostCalculator.Result expanded = CostCalculator.expand(vector, source(recipes),
            CostCalculator.DEFAULT_MAX_STEPS);

        CraftStepOrder.Ordered ordered = CraftStepOrder.resolve(expanded.steps(), recipes);
        long partSteps = ordered.steps().stream()
            .filter(step -> step.target().equals(ref("part")))
            .count();
        assertEquals(1L, partSteps, "同一配方的二次展开必须合并成一步");
        long batches = ordered.steps().stream()
            .filter(step -> step.target().equals(ref("part")))
            .mapToLong(CostCalculator.Step::batches)
            .sum();
        assertEquals(3L, batches, "合并后批次应为 1 + 2");
        long needed = ordered.steps().stream()
            .filter(step -> step.target().equals(ref("part")))
            .mapToLong(CostCalculator.Step::needed)
            .sum();
        assertEquals(3L, needed, "合并后缺口也应相加（1 + 2），否则行上的需求量会显示成一次的量");
        assertTrue(indexOf(ordered.steps(), "part") < indexOf(ordered.steps(), "rainbow"),
            "合并后的 part 仍要排在 rainbow 之前");
    }

    private static void cycleIsFlaggedNotLost() {
        List<RecipeRecord> recipes = List.of(
            recipe("a", slots(slot(1, "a")), slots(slot(1, "b"))),
            recipe("b", slots(slot(1, "b")), slots(slot(1, "a")))
        );
        CostVector vector = CostVector.empty();
        vector.require(ref("a"), 1);
        CostCalculator.Result expanded = CostCalculator.expand(vector, source(recipes),
            CostCalculator.DEFAULT_MAX_STEPS);

        CraftStepOrder.Ordered ordered = CraftStepOrder.resolve(expanded.steps(), recipes);
        assertTrue(ordered.cyclic(), "互相依赖应被标成环");
        assertEquals(expanded.steps().size(), ordered.steps().size(), "成环也不能丢步骤");
    }

    private static int indexOf(List<CostCalculator.Step> steps, String key) {
        for (int index = 0; index < steps.size(); index++) {
            if (steps.get(index).target().equals(ref(key))) {
                return index;
            }
        }
        return -1;
    }

    private static ItemRef ref(String key) {
        return ItemRef.of(key);
    }

    private static RecipeRecord recipe(String id, List<RecipeRecord.Slot> outputs, List<RecipeRecord.Slot> inputs) {
        return new RecipeRecord(id, "", outputs, inputs, List.of());
    }

    private static List<RecipeRecord.Slot> slots(RecipeRecord.Slot... slots) {
        return List.of(slots);
    }

    private static RecipeRecord.Slot slot(long amount, String... keys) {
        List<ItemRef> alternatives = new ArrayList<>();
        Arrays.stream(keys).map(ItemRef::of).forEach(alternatives::add);
        return new RecipeRecord.Slot(alternatives, amount);
    }

    private static RecipeSource source(List<RecipeRecord> recipes) {
        return label -> {
            for (RecipeRecord record : recipes) {
                List<RecipeRecord.Slot> outputs = record.outputs();
                for (int index = 0; index < outputs.size(); index++) {
                    if (outputs.get(index).alternatives().contains(label)) {
                        return new RecipeSource.Match(record, index);
                    }
                }
            }
            return null;
        };
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + "：期望 " + expected + "，实际 " + actual);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
