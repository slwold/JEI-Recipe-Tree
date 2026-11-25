package com.slwold.jeirecipetree.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CostCalculatorTest {

    public static void main(String[] args) {
        handCalculationMatches();
        stockOffsetsRequirement();
        orderDoesNotMatter();
        sameMaterialExpandsOnce();
        remainderAndRedeploy();
        cycleTerminates();
        stepLimitTruncates();
        byproductsCount();
        catalystsAreIgnored();
        catalystsMergeByMax();
        tagFirstInputExpandsConcreteIntermediate();
        System.out.println("CostCalculator checks passed: hand-calc, stock offset, order independence, "
            + "aggregate batching, remainder/redeploy, cycle, truncation, byproducts, catalysts, catalyst max-merge, "
            + "tag-first accounting");
    }

    private static void handCalculationMatches() {
        CostVector vector = CostVector.ofStock(Map.of(ref("iron_ingot"), 12L));
        vector.require(ref("funnel"), 2);

        CostCalculator.Result result = CostCalculator.expand(vector, source(
            recipe("funnel", slots(slot(1, "funnel")), slots(slot(5, "iron_ingot"), slot(1, "chest"))),
            recipe("chest", slots(slot(1, "chest")), slots(slot(8, "planks")))
        ), CostCalculator.DEFAULT_MAX_STEPS);

        assertAmount(result.net(), "planks", -16, "木板应缺 16（2 箱 × 8）");
        assertAmount(result.net(), "iron_ingot", 2, "铁锭 12 - 10 = 剩 2");
        assertAmount(result.net(), "funnel", 0, "目标自身应被产出抵消");
        assertAmount(result.net(), "chest", 0, "箱子应被展开抵消");
        assertEquals(List.of(ref("planks")), result.rawMaterials(), "唯一原料应是木板");
        assertEquals(2, result.steps().size(), "应展开漏斗与箱子各一次");
        assertTrue(!result.truncated(), "不应触发截断");
    }

    private static void stockOffsetsRequirement() {
        CostVector vector = CostVector.ofStock(Map.of(ref("iron_ingot"), 12L));
        vector.require(ref("iron_ingot"), 20);
        assertEquals(-8L, vector.amount(ref("iron_ingot")), "12 - 20 = -8");
    }

    private static void orderDoesNotMatter() {
        Map<ItemRef, Long> forward = runBatchedOrder(false);
        Map<ItemRef, Long> reverse = runBatchedOrder(true);
        assertEquals(forward.keySet(), reverse.keySet(), "键集合应一致");
        forward.forEach((key, value) ->
            assertEquals(value, reverse.get(key), "键 " + key.key() + " 的净额应一致"));
    }

    private static Map<ItemRef, Long> runBatchedOrder(boolean reversed) {
        CostVector vector = CostVector.empty();
        if (reversed) {
            vector.require(ref("gear"), 2);
            vector.require(ref("casing"), 3);
        } else {
            vector.require(ref("casing"), 3);
            vector.require(ref("gear"), 2);
        }
        CostCalculator.Result result = CostCalculator.expand(vector, source(
            recipe("gear", slots(slot(1, "gear")), slots(slot(3, "iron_ingot"))),
            recipe("casing", slots(slot(1, "casing")), slots(slot(2, "iron_ingot")))
        ), CostCalculator.DEFAULT_MAX_STEPS);
        Map<ItemRef, Long> copy = new LinkedHashMap<>();
        result.net().forEach((key, value) -> copy.put(key, value));
        return copy;
    }

    private static void sameMaterialExpandsOnce() {
        CostVector vector = CostVector.empty();
        vector.require(ref("a"), 1);
        vector.require(ref("c"), 1);

        CostCalculator.Result result = CostCalculator.expand(vector, source(
            recipe("a", slots(slot(1, "a")), slots(slot(5, "b"))),
            recipe("c", slots(slot(1, "c")), slots(slot(3, "b"))),
            recipe("b", slots(slot(4, "b")), slots(slot(1, "ore")))
        ), CostCalculator.DEFAULT_MAX_STEPS);

        long bSteps = result.steps().stream().filter(step -> step.target().equals(ref("b"))).count();
        assertEquals(1L, bSteps, "同一材料应只展开一次");
        CostCalculator.Step bStep = result.steps().stream()
            .filter(step -> step.target().equals(ref("b")))
            .findFirst()
            .orElseThrow();
        assertEquals(2L, bStep.batches(), "8 个 B 按每批 4 个应做 2 批");
        assertAmount(result.net(), "b", 0, "B 应正好被 2 批补齐");
        assertAmount(result.net(), "ore", -2, "2 批各需 1 矿");
    }

    private static void remainderAndRedeploy() {
        CostVector first = CostVector.empty();
        first.require(ref("x"), 1);
        CostCalculator.Result redeployed = CostCalculator.expand(first, source(
            recipe("x", slots(slot(1, "x")), slots(slot(1, "b"), slot(1, "d"))),
            recipe("d", slots(slot(1, "d")), slots(slot(1, "b"))),
            recipe("b", slots(slot(1, "b")), slots(slot(1, "ore")))
        ), CostCalculator.DEFAULT_MAX_STEPS);
        assertEquals(2L, countSteps(redeployed, "b"), "B 应展开两次（第二次由 D 追加需求触发）");
        assertAmount(redeployed.net(), "ore", -2, "两次展开各消耗 1 矿");

        CostVector second = CostVector.empty();
        second.require(ref("x"), 1);
        CostCalculator.Result reused = CostCalculator.expand(second, source(
            recipe("x", slots(slot(1, "x")), slots(slot(1, "b"), slot(1, "d"))),
            recipe("d", slots(slot(1, "d")), slots(slot(3, "b"))),
            recipe("b", slots(slot(4, "b")), slots(slot(1, "ore")))
        ), CostCalculator.DEFAULT_MAX_STEPS);
        assertEquals(1L, countSteps(reused, "b"), "余料足够时 B 不应二次展开");
        assertAmount(reused.net(), "ore", -1, "只需 1 批");
        assertTrue(!reused.net().containsKey(ref("b")), "B 归零后应从向量中移除");
    }

    private static void cycleTerminates() {
        CostVector vector = CostVector.empty();
        vector.require(ref("a"), 1);

        CostCalculator.Result result = CostCalculator.expand(vector, source(
            recipe("a", slots(slot(1, "a")), slots(slot(1, "b"))),
            recipe("b", slots(slot(1, "b")), slots(slot(1, "a")))
        ), CostCalculator.DEFAULT_MAX_STEPS);

        assertEquals(2, result.steps().size(), "环上各展开一次后应停止");
        assertAmount(result.net(), "a", -1, "环上材料应留在缺口里");
        assertEquals(List.of(ref("a")), result.rawMaterials(), "A 应被视为原料");
        assertTrue(!result.truncated(), "成环不应靠上限截断");
    }

    private static void stepLimitTruncates() {
        CostVector vector = CostVector.empty();
        vector.require(ref("a"), 1);

        CostCalculator.Result result = CostCalculator.expand(vector, source(
            recipe("a", slots(slot(1, "a")), slots(slot(1, "b"))),
            recipe("b", slots(slot(1, "b")), slots(slot(1, "c")))
        ), 1);

        assertEquals(1, result.steps().size(), "上限 1 步");
        assertTrue(result.truncated(), "应标记截断");
        assertAmount(result.net(), "b", -1, "B 的缺口尚未展开");
    }

    private static void byproductsCount() {
        CostVector vector = CostVector.empty();
        vector.require(ref("ingot"), 1);

        CostCalculator.Result result = CostCalculator.expand(vector, source(
            recipe("smelt", slots(slot(1, "ingot"), slot(1, "slag")), slots(slot(1, "ore")))
        ), CostCalculator.DEFAULT_MAX_STEPS);

        assertAmount(result.net(), "slag", 1, "副产物应记入正项");
        assertAmount(result.net(), "ore", -1, "输入应记入负项");
    }

    private static void catalystsAreIgnored() {
        RecipeRecord withCatalyst = new RecipeRecord(
            "smelt", "",
            slots(slot(1, "ingot")),
            slots(slot(1, "ore")),
            slots(slot(1, "hammer"))
        );
        CostVector vector = CostVector.empty();
        vector.require(ref("ingot"), 1);

        CostCalculator.Result result = CostCalculator.expand(vector, source(withCatalyst),
            CostCalculator.DEFAULT_MAX_STEPS);

        assertAmount(result.net(), "hammer", 0, "催化剂不应被消耗");
        assertAmount(result.net(), "ore", -1, "输入照常记负");
        assertAmount(result.catalysts(), "hammer", 1, "催化剂要进清单：这条链需要一把锤子");
    }

    private static void catalystsMergeByMax() {
        RecipeRecord first = new RecipeRecord(
            "a", "",
            slots(slot(1, "part_a")),
            slots(slot(1, "ore_a")),
            slots(slot(1, "anvil"))
        );
        RecipeRecord second = new RecipeRecord(
            "b", "",
            slots(slot(1, "part_b")),
            slots(slot(1, "ore_b")),
            slots(slot(2, "anvil"))
        );
        CostVector vector = CostVector.empty();
        vector.require(ref("part_a"), 1);
        vector.require(ref("part_b"), 1);

        CostCalculator.Result result = CostCalculator.expand(vector, source(first, second),
            CostCalculator.DEFAULT_MAX_STEPS);

        assertAmount(result.catalysts(), "anvil", 2, "同一催化剂跨步取最大值，不累加");
    }

    private static void tagFirstInputExpandsConcreteIntermediate() {
        CostVector vector = CostVector.empty();
        vector.require(ref("netherite_furnace"), 1);

        CostCalculator.Result result = CostCalculator.expand(vector, source(
            recipe("netherite_furnace", slots(slot(1, "netherite_furnace")),
                slots(slot(1, "tag:c:furnaces/obsidian", "obsidian_furnace"))),
            recipe("obsidian_furnace", slots(slot(1, "obsidian_furnace")), slots(slot(4, "obsidian")))
        ), CostCalculator.DEFAULT_MAX_STEPS);

        assertEquals(2, result.steps().size(), "tag 首候选的中间品也必须展开");
        assertTrue(countSteps(result, "obsidian_furnace") == 1, "中间品步骤应存在");
        assertAmount(result.net(), "obsidian_furnace", 0, "中间品应被展开抵消");
        assertAmount(result.net(), "obsidian", -4, "原料缺口应落到具体物品键");
        assertAmount(result.net(), "tag:c:furnaces/obsidian", 0, "不应再按 tag 键记账");
    }

    private static ItemRef ref(String key) {
        return ItemRef.of(key);
    }

    private static long countSteps(CostCalculator.Result result, String key) {
        return result.steps().stream().filter(step -> step.target().equals(ref(key))).count();
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

    private static RecipeSource source(RecipeRecord... recipes) {
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

    private static void assertAmount(Map<ItemRef, Long> net, String key, long expected, String message) {
        long actual = net.getOrDefault(ref(key), 0L);
        if (actual != expected) {
            throw new AssertionError(message + "：期望 " + expected + "，实际 " + actual);
        }
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
