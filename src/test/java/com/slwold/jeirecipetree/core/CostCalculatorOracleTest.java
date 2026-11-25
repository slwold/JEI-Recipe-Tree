package com.slwold.jeirecipetree.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

public final class CostCalculatorOracleTest {

    public static void main(String[] args) {
        sameMaterialSharedAcrossBranches();
        remainderFeedsLaterBranch();
        byproductsAreReusable();
        tagKeyAndItemKeyStaySeparate();
        randomGraphsMatchRecursiveOracle();
        System.out.println("CostCalculator oracle checks passed: shared-material, remainder reuse, "
            + "byproduct reuse, tag/item key separation, random DAG parity (200 cases)");
    }

    private static void sameMaterialSharedAcrossBranches() {
        List<RecipeRecord> recipes = List.of(
            recipe("rainbow", slot(1, "rainbow"), List.of(slot(1, "half_a"), slot(1, "half_b"))),
            recipe("half_a", slot(1, "half_a"), List.of(slot(3, "ore"))),
            recipe("half_b", slot(1, "half_b"), List.of(slot(5, "ore")))
        );
        assertParity(recipes, Map.of(), Map.of("rainbow", 1L), "两处共用同一种原料");
    }

    private static void remainderFeedsLaterBranch() {
        List<RecipeRecord> recipes = List.of(
            recipe("frame", slot(1, "frame"), List.of(slot(1, "plate"), slot(1, "trim"))),
            recipe("plate", slot(4, "plate"), List.of(slot(1, "ingot"))),
            recipe("trim", slot(1, "trim"), List.of(slot(2, "plate")))
        );

        assertParity(recipes, Map.of(), Map.of("frame", 1L, "plate", 33L), "凑整余料被后续需求复用");
    }

    private static void byproductsAreReusable() {
        RecipeRecord dust = new RecipeRecord(
            "dust", "", List.of(slot(1, "dust"), slot(2, "grit")), List.of(slot(1, "ore")), List.of());
        List<RecipeRecord> recipes = List.of(
            recipe("block", slot(1, "block"), List.of(slot(1, "dust"), slot(1, "grit"))),
            dust
        );
        assertParity(recipes, Map.of(), Map.of("block", 2L), "副产物顶后续需求");
    }

    private static void tagKeyAndItemKeyStaySeparate() {
        List<RecipeRecord> recipes = List.of(
            recipe("frame", slot(1, "frame"), List.of(slot(1, "#planks"), slot(1, "stick"))),
            recipe("stick", slot(4, "stick"), List.of(slot(1, "#planks")))
        );
        CostVector vector = CostVector.ofStock(Map.of());
        vector.require(ItemRef.of("frame"), 1);
        CostCalculator.Result result = CostCalculator.expand(
            vector, source(recipes), CostCalculator.DEFAULT_MAX_STEPS);

        long planks = result.net().getOrDefault(ItemRef.of("#planks"), 0L);
        expect(planks, -2L, "tag 缺口应累计成 -2（框架 1 + 木棍 1）");
        long batches = result.steps().stream().mapToLong(CostCalculator.Step::batches).sum();
        expect(batches, 2L, "应展开 2 步（框架 + 木棍）");
    }

    private static void randomGraphsMatchRecursiveOracle() {
        Random random = new Random(20261124L);
        for (int round = 0; round < 200; round++) {
            int size = 2 + random.nextInt(6);
            List<RecipeRecord> recipes = new ArrayList<>();
            for (int index = 1; index < size; index++) {
                List<RecipeRecord.Slot> inputs = new ArrayList<>();
                int inputCount = 1 + random.nextInt(2);
                for (int slotIndex = 0; slotIndex < inputCount; slotIndex++) {
                    inputs.add(slot(1 + random.nextInt(4), "i" + random.nextInt(index)));
                }
                recipes.add(recipe("r" + index, slot(1 + random.nextInt(3), "i" + index), inputs));
            }
            Map<String, Long> stock = new LinkedHashMap<>();
            for (int index = 0; index < size; index++) {
                if (random.nextBoolean()) {
                    stock.put("i" + index, (long) random.nextInt(12));
                }
            }
            Map<String, Long> demands = new LinkedHashMap<>();
            demands.put("i" + (size - 1), 1L + random.nextInt(20));
            if (random.nextBoolean()) {
                demands.put("i" + random.nextInt(size), 1L + random.nextInt(6));
            }
            assertParity(recipes, stock, demands, "随机链第 " + round + " 组");
        }
    }

    private static void assertParity(
        List<RecipeRecord> recipes,
        Map<String, Long> stock,
        Map<String, Long> demands,
        String label
    ) {
        Oracle oracle = runOracle(recipes, stock, demands);

        CostVector vector = CostVector.ofStock(stockView(stock));
        demands.forEach((key, amount) -> vector.require(ItemRef.of(key), amount));
        CostCalculator.Result result = CostCalculator.expand(
            vector, source(recipes), CostCalculator.DEFAULT_MAX_STEPS);

        Map<ItemRef, Long> kernelCrafts = new LinkedHashMap<>();
        for (CostCalculator.Step step : result.steps()) {
            kernelCrafts.merge(step.target(), step.batches(), Long::sum);
        }
        assertSame(oracle.crafts(), kernelCrafts, label + "：每种物品的批次数");
        assertSame(oracle.balance(), result.net(), label + "：结存（库存+产出−消耗−需求）");
    }

    private static Oracle runOracle(
        List<RecipeRecord> recipes,
        Map<String, Long> stock,
        Map<String, Long> demands
    ) {
        Map<ItemRef, Long> pool = new HashMap<>();
        stock.forEach((key, amount) -> pool.put(ItemRef.of(key), amount));
        Map<ItemRef, Long> produced = new LinkedHashMap<>();
        Map<ItemRef, Long> consumed = new LinkedHashMap<>();
        Map<ItemRef, Long> crafts = new LinkedHashMap<>();
        RecipeSource source = source(recipes);
        for (Map.Entry<String, Long> demand : demands.entrySet()) {
            descend(ItemRef.of(demand.getKey()), demand.getValue(), pool, produced, consumed, crafts,
                source, new ArrayDeque<>());
        }
        Map<ItemRef, Long> balance = new LinkedHashMap<>();
        stock.forEach((key, amount) -> balance.merge(ItemRef.of(key), amount, Long::sum));
        produced.forEach((ref, amount) -> balance.merge(ref, amount, Long::sum));
        consumed.forEach((ref, amount) -> balance.merge(ref, -amount, Long::sum));
        demands.forEach((key, amount) -> balance.merge(ItemRef.of(key), -amount, Long::sum));
        balance.values().removeIf(value -> value == 0L);
        return new Oracle(crafts, balance);
    }

    private static void descend(
        ItemRef label,
        long need,
        Map<ItemRef, Long> pool,
        Map<ItemRef, Long> produced,
        Map<ItemRef, Long> consumed,
        Map<ItemRef, Long> crafts,
        RecipeSource source,
        Deque<ItemRef> stack
    ) {
        if (need <= 0) {
            return;
        }
        long available = pool.getOrDefault(label, 0L);
        long used = Math.min(available, need);
        if (used > 0) {
            pool.put(label, available - used);
        }
        long remaining = need - used;
        if (remaining <= 0 || stack.contains(label)) {

            return;
        }
        RecipeSource.Match match = source.match(label);
        if (match == null) {
            return;
        }
        RecipeRecord recipe = match.recipe();
        long perBatch = recipe.outputs().get(match.outputSlot()).amount();
        long times = (remaining + perBatch - 1) / perBatch;
        crafts.merge(label, times, Long::sum);
        long made = times * perBatch;
        produced.merge(label, made, Long::sum);
        long surplus = made - remaining;
        if (surplus > 0) {
            pool.merge(label, surplus, Long::sum);
        }
        stack.addLast(label);
        for (RecipeRecord.Slot input : recipe.inputs()) {
            long amount = input.amount() * times;
            consumed.merge(input.accountingKey(), amount, Long::sum);
            descend(input.accountingKey(), amount, pool, produced, consumed, crafts, source, stack);
        }
        stack.removeLast();
        for (int index = 0; index < recipe.outputs().size(); index++) {
            if (index == match.outputSlot()) {
                continue;
            }
            RecipeRecord.Slot extra = recipe.outputs().get(index);
            long amount = extra.amount() * times;
            produced.merge(extra.accountingKey(), amount, Long::sum);
            pool.merge(extra.accountingKey(), amount, Long::sum);
        }
    }

    private record Oracle(Map<ItemRef, Long> crafts, Map<ItemRef, Long> balance) {
    }

    private static Map<ItemRef, Long> stockView(Map<String, Long> stock) {
        Map<ItemRef, Long> view = new LinkedHashMap<>();
        stock.forEach((key, amount) -> view.put(ItemRef.of(key), amount));
        return view;
    }

    private static void assertSame(Map<ItemRef, Long> expected, Map<ItemRef, Long> actual, String label) {
        Map<ItemRef, Long> cleaned = new LinkedHashMap<>(actual);
        cleaned.values().removeIf(value -> value == 0L);
        if (expected.equals(cleaned)) {
            return;
        }
        TreeSet<ItemRef> keys = new TreeSet<>(Comparator.comparing(ItemRef::key));
        keys.addAll(expected.keySet());
        keys.addAll(cleaned.keySet());
        List<String> rows = new ArrayList<>();
        for (ItemRef key : keys) {
            long left = expected.getOrDefault(key, 0L);
            long right = cleaned.getOrDefault(key, 0L);
            if (left != right) {
                rows.add(key.key() + " 对照 " + left + " / 内核 " + right);
            }
        }
        throw new AssertionError(label + " 不一致：" + String.join("；", rows));
    }

    private static void expect(long actual, long expected, String message) {
        if (actual != expected) {
            throw new AssertionError(message + "：期望 " + expected + "，实际 " + actual);
        }
    }

    private static RecipeRecord recipe(String id, RecipeRecord.Slot output, List<RecipeRecord.Slot> inputs) {
        return new RecipeRecord(id, "", List.of(output), inputs, List.of());
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
}
