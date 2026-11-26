package com.slwold.jeirecipetree.client.data;

import com.slwold.jeirecipetree.core.CostCalculator;
import com.slwold.jeirecipetree.core.CostVector;
import com.slwold.jeirecipetree.core.ItemRef;
import com.slwold.jeirecipetree.core.RecipeFingerprint;
import com.slwold.jeirecipetree.core.RecipeRecord;
import com.slwold.jeirecipetree.core.RecipeSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

public final class RecipeLibraryStoreTest {

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("jert-library-");
        roundTrip(directory.resolve("roundtrip.json"));
        unknownFieldsSurvive(directory.resolve("unknown.json"));
        badRecipeIsSkipped(directory.resolve("broken.json"));
        missingFileIsEmpty(directory.resolve("absent.json"));
        indexKeepsLibraryOrder();
        saveLeavesNoTempFile(directory.resolve("atomic.json"));
        contentIdIsStableAndContentSensitive();
        libraryChainExpandsToRawMaterials();
        sameTypeAlternativesAreIndexed();
        handWrittenKeysSurviveRoundTrip(directory.resolve("handwritten.json"));
        corruptFileBlocksOverwrite(directory.resolve("corrupt.json"));
        System.out.println("RecipeLibraryStore checks passed: round trip, unknown fields, bad data, "
            + "index order, atomic write, stable content id, chained expansion, same-type candidates, "
            + "hand-written chemical keys, corrupt-file protection");
    }

    private static void roundTrip(Path file) throws Exception {
        Files.writeString(file, """
            {
              "version": 1,
              "group": "default",
              "recipes": [
                {
                  "id": "funnel",
                  "outputs": [{"amount": 1, "alternatives": ["minecraft:hopper"]}],
                  "inputs": [
                    {"amount": 5, "alternatives": ["minecraft:iron_ingot"]},
                    {"amount": 1, "alternatives": ["minecraft:chest"]}
                  ]
                }
              ]
            }
            """, StandardCharsets.UTF_8);

        RecipeLibrary library = RecipeLibraryStore.load(file);
        assertEquals(1, library.size(), "应加载 1 条配方");
        assertEquals("default", library.group(), "group 应来自文件");

        RecipeRecord funnel = library.recipes().get(0);
        assertEquals("funnel", funnel.id(), "id");
        assertEquals(1L, funnel.outputs().get(0).amount(), "产出数量");
        assertEquals(2, funnel.inputs().size(), "输入槽数");
        assertEquals(5L, funnel.inputs().get(0).amount(), "铁锭数量");
        assertEquals(List.of(ItemRef.of("minecraft:chest")),
            funnel.inputs().get(1).alternatives(), "箱子候选");

        RecipeRecord chest = new RecipeRecord(
            "chest", "",
            List.of(new RecipeRecord.Slot(List.of(ItemRef.of("minecraft:chest")), 1)),
            List.of(new RecipeRecord.Slot(List.of(ItemRef.of("minecraft:oak_planks")), 8)),
            List.of()
        );
        RecipeLibrary grown = RecipeLibrary.of(
            library.group(), library.rawRoot(), List.of(funnel, chest), library.rawByIds());
        RecipeLibraryStore.save(file, grown);

        RecipeLibrary reloaded = RecipeLibraryStore.load(file);
        assertEquals(2, reloaded.size(), "写回后应能读回 2 条");
        assertEquals("chest", reloaded.recipes().get(1).id(), "顺序应保持");
    }

    private static void unknownFieldsSurvive(Path file) throws Exception {
        Files.writeString(file, """
            {
              "version": 1,
              "group": "note-group",
              "customTop": 42,
              "recipes": [
                {
                  "id": "smelt",
                  "note": "玩家备注",
                  "outputs": [{"amount": 1, "alternatives": ["minecraft:iron_ingot"]}],
                  "inputs": [{"amount": 1, "alternatives": ["minecraft:raw_iron"]}]
                }
              ]
            }
            """, StandardCharsets.UTF_8);

        RecipeLibrary loaded = RecipeLibraryStore.load(file);
        RecipeLibraryStore.save(file, loaded);
        String text = Files.readString(file, StandardCharsets.UTF_8);

        assertTrue(text.contains("customTop"), "顶层未知字段应保留：" + text);
        assertTrue(text.contains("玩家备注"), "配方级未知字段应保留：" + text);
        assertEquals(1, RecipeLibraryStore.load(file).size(), "写回后仍应可读");
    }

    private static void badRecipeIsSkipped(Path file) throws Exception {
        Files.writeString(file, """
            {
              "version": 1,
              "group": "broken",
              "recipes": [
                {
                  "id": "zero-amount",
                  "outputs": [{"amount": 0, "alternatives": ["minecraft:bad"]}],
                  "inputs": []
                },
                {
                  "id": "no-id-outputs",
                  "outputs": [],
                  "inputs": []
                },
                {
                  "id": "good",
                  "outputs": [{"amount": 1, "alternatives": ["minecraft:good"]}],
                  "inputs": [{"amount": 1, "alternatives": ["minecraft:raw"]}]
                }
              ]
            }
            """, StandardCharsets.UTF_8);

        RecipeLibrary library = RecipeLibraryStore.load(file);
        assertEquals(1, library.size(), "坏配方应被跳过，只留好的那条");
        assertEquals("good", library.recipes().get(0).id(), "保留的应是 good");
    }

    private static void missingFileIsEmpty(Path file) {
        RecipeLibrary library = RecipeLibraryStore.load(file);
        assertEquals(0, library.size(), "不存在应为空库");
        assertEquals("absent", library.group(), "group 应回落为文件名");
        assertTrue(library.match(ItemRef.of("minecraft:iron_ingot")) == null, "空库不应匹配任何材料");
    }

    private static void indexKeepsLibraryOrder() {
        RecipeRecord first = recipe("first", "minecraft:gear", 1);
        RecipeRecord second = recipe("second", "minecraft:gear", 1);
        RecipeLibrary library = RecipeLibrary.of("order", null, List.of(first, second), Map.of());

        RecipeSource.Match match = library.match(ItemRef.of("minecraft:gear"));
        assertTrue(match != null, "应命中");
        assertEquals("first", match.recipe().id(), "多配方时应取库内第一条");
        assertEquals(0, match.outputSlot(), "输出槽下标");

        RecipeRecord byproductFirst = new RecipeRecord(
            "slag", "",
            List.of(
                new RecipeRecord.Slot(List.of(ItemRef.of("minecraft:slag")), 1),
                new RecipeRecord.Slot(List.of(ItemRef.of("minecraft:iron_ingot")), 1)
            ),
            List.of(new RecipeRecord.Slot(List.of(ItemRef.of("minecraft:raw_iron")), 1)),
            List.of()
        );
        RecipeLibrary bySlag = RecipeLibrary.of("slag", null, List.of(byproductFirst), Map.of());
        RecipeSource.Match ingot = bySlag.match(ItemRef.of("minecraft:iron_ingot"));
        assertTrue(ingot != null, "副产物槽也应建索引");
        assertEquals(1, ingot.outputSlot(), "应指向第二个输出槽");
        assertTrue(bySlag.match(ItemRef.of("minecraft:nothing")) == null, "未知材料应为空");
    }

    private static void saveLeavesNoTempFile(Path file) throws Exception {
        RecipeLibrary library = RecipeLibrary.of(
            "atomic", null, List.of(recipe("only", "minecraft:stick", 4)), Map.of());
        RecipeLibraryStore.save(file, library);

        assertTrue(Files.isRegularFile(file), "目标文件应存在");
        try (Stream<Path> entries = Files.list(file.getParent())) {
            long temps = entries.filter(path -> path.getFileName().toString().endsWith(".tmp")).count();
            assertEquals(0L, temps, "不应残留 .tmp 文件");
        }
    }

    private static void contentIdIsStableAndContentSensitive() {
        List<RecipeRecord.Slot> outputs = List.of(
            new RecipeRecord.Slot(List.of(ItemRef.of("minecraft:iron_ingot")), 2));
        List<RecipeRecord.Slot> inputs = List.of(
            new RecipeRecord.Slot(List.of(ItemRef.of("minecraft:raw_iron"), ItemRef.of("minecraft:iron_ore")), 3),
            new RecipeRecord.Slot(List.of(ItemRef.of("minecraft:coal")), 1));

        String first = RecipeFingerprint.contentId("minecraft:smelting", outputs, inputs);
        String rebuilt = RecipeFingerprint.contentId("minecraft:smelting",
            List.of(new RecipeRecord.Slot(List.of(ItemRef.of("minecraft:iron_ingot")), 2)),
            List.of(
                new RecipeRecord.Slot(List.of(ItemRef.of("minecraft:raw_iron"), ItemRef.of("minecraft:iron_ore")), 3),
                new RecipeRecord.Slot(List.of(ItemRef.of("minecraft:coal")), 1)));
        assertEquals(first, rebuilt, "同一份内容两次构造应得到同一个 id");

        assertEquals("minecraft:smelting|content/fa0b3bd78af90d27", first, "内容指纹算法不得悄变");
        assertTrue(!first.contains("runtime/"), "库 id 不得再含 JVM 生命周期相关的 runtime/ 段");
        assertEquals(16, first.substring(first.lastIndexOf('/') + 1).length(), "指纹应为 16 位十六进制");

        assertTrue(!first.equals(RecipeFingerprint.contentId("minecraft:blasting", outputs, inputs)),
            "类别不同应是不同配方");
        assertTrue(!first.equals(RecipeFingerprint.contentId("minecraft:smelting",
                List.of(new RecipeRecord.Slot(List.of(ItemRef.of("minecraft:iron_ingot")), 3)), inputs)),
            "产出数量不同应是不同配方");
        assertTrue(!first.equals(RecipeFingerprint.contentId("minecraft:smelting", outputs,
                List.of(inputs.get(1), inputs.get(0)))),
            "输入槽顺序不同应是不同配方");
    }

    private static void sameTypeAlternativesAreIndexed() {
        RecipeRecord planks = new RecipeRecord("planks", "",
            List.of(new RecipeRecord.Slot(List.of(
                ItemRef.of("minecraft:oak_planks"),
                ItemRef.of("minecraft:spruce_planks"),
                ItemRef.of("minecraft:birch_planks")), 1)),
            List.of(slot("minecraft:oak_log", 1)), List.of());
        RecipeLibrary library = RecipeLibrary.of("tag", null, List.of(planks), Map.of());
        for (String key : List.of("minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks")) {
            RecipeSource.Match match = library.match(ItemRef.of(key));
            assertTrue(match != null, "同类候选 " + key + " 应命中同一条配方");
            assertEquals("planks", match.recipe().id(), "命中配方");
        }
    }

    private static void handWrittenKeysSurviveRoundTrip(Path file) throws Exception {
        String chemical = "mekanism:creative_chemical_tank:mekanism:carbon::";
        Files.writeString(file, """
            {
              "version": 1,
              "group": "default",
              "recipes": [
                {
                  "id": "player:chemical_coal",
                  "outputs": [{"amount": 1, "alternatives": ["%s"]}],
                  "inputs": [{"amount": 1, "alternatives": ["minecraft:coal"]}]
                }
              ]
            }
            """.formatted(chemical), StandardCharsets.UTF_8);
        RecipeLibrary library = RecipeLibraryStore.load(file);
        assertEquals(1, library.size(), "手写配方应被读取");
        RecipeSource.Match match = library.match(ItemRef.of(chemical));
        assertTrue(match != null, "特殊身份键应能命中");
        RecipeLibraryStore.save(file, library);
        RecipeLibrary reloaded = RecipeLibraryStore.load(file);
        assertTrue(reloaded.match(ItemRef.of(chemical)) != null, "写回后特殊键不得变形");
    }

    private static void corruptFileBlocksOverwrite(Path file) throws Exception {
        RecipeLibraryStore.save(file, RecipeLibrary.of("c", null, List.of(recipe("keep", "minecraft:apple", 1)), Map.of()));
        assertTrue(!RecipeLibraryStore.looksCorrupt(file), "好库不应被判为损坏");

        Files.writeString(file, "{ \"recipes\": [ { \"id\": \"broken\", ", StandardCharsets.UTF_8);
        assertTrue(RecipeLibraryStore.looksCorrupt(file), "语法错误必须被判为损坏");
        assertEquals(0, RecipeLibraryStore.load(file).size(), "损坏文件应读出空库而不是抛异常");

        RecipeLibraryStore.save(file, RecipeLibrary.of("c", null, List.of(recipe("rescue", "minecraft:stick", 1)), Map.of()));
        Path backup = file.resolveSibling(file.getFileName() + ".bak");
        assertTrue(Files.isRegularFile(backup), "覆盖前必须留 .bak 备份");
        assertTrue(RecipeLibraryStore.looksCorrupt(backup), "备份应是覆盖前的损坏现场");
    }

    private static void libraryChainExpandsToRawMaterials() {
        RecipeRecord a = new RecipeRecord("A", "",
            List.of(slot("A", 1)), List.of(slot("B", 2)), List.of());
        RecipeRecord b = new RecipeRecord("B", "",
            List.of(slot("B", 1)), List.of(slot("C", 3)), List.of());
        RecipeLibrary library = RecipeLibrary.of("chain", null, List.of(a, b), Map.of());

        CostVector vector = CostVector.empty();
        vector.require(ItemRef.of("A"), 1);
        CostCalculator.Result result = CostCalculator.expand(
            vector, library, CostCalculator.DEFAULT_MAX_STEPS);

        assertEquals(2, result.steps().size(), "库里两条配方都应被展开");
        assertEquals("A", result.steps().get(0).recipeId(), "先展开目标配方");
        assertEquals("B", result.steps().get(1).recipeId(), "中间物继续按库展开");
        assertEquals(-6L, result.net().get(ItemRef.of("C")), "缺口最终落在没有配方的原料上");
        assertTrue(!result.truncated(), "三级链不应触发步数上限");
    }

    private static RecipeRecord.Slot slot(String key, long amount) {
        return new RecipeRecord.Slot(List.of(ItemRef.of(key)), amount);
    }

    private static RecipeRecord recipe(String id, String output, long amount) {
        return new RecipeRecord(
            id, "",
            List.of(new RecipeRecord.Slot(List.of(ItemRef.of(output)), amount)),
            List.of(new RecipeRecord.Slot(List.of(ItemRef.of("minecraft:raw")), 1)),
            List.of()
        );
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + "：期望 " + expected + "，实际 " + actual);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
