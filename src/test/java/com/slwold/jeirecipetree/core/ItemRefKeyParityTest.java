package com.slwold.jeirecipetree.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class ItemRefKeyParityTest {

    private static final List<String> KEYS = List.of(
        "minecraft:iron_ingot",
        "minecraft:oak_planks",
        "minecraft:air",
        "fluid:minecraft:water",
        "fluid:mekanism:hydrogen",
        "jei:item|minecraft:stick|nbt={Damage:2}",
        "runtime/1234567",
        "create:andesite_alloy",
        "minecraft:white_shulker_box\u001Fcolor=white",
        "\u673A\u68B0\u52A8\u529B:\u9EC4\u94DC",
        "a|b|c",
        "  spaced:key  "
    );

    private ItemRefKeyParityTest() {
    }

    public static void main(String[] args) {
        roundTripIsVerbatim();
        distinctKeysStayDistinct();
        groupingTotalsMatch();
        shortfallVectorsMatch();
        fluidClassificationMatches();
        System.out.println("ItemRef key parity checks passed: verbatim round-trip, distinctness, "
            + "grouping, shortfall, fluid classification.");
    }

    private static void roundTripIsVerbatim() {
        for (String legacy : KEYS) {
            ItemRef ref = ItemRef.of(legacy);
            check(legacy.equals(ref.key()), "key must round-trip verbatim: " + legacy);
        }
    }

    private static void distinctKeysStayDistinct() {
        for (int i = 0; i < KEYS.size(); i++) {
            for (int j = i + 1; j < KEYS.size(); j++) {
                check(!ItemRef.of(KEYS.get(i)).equals(ItemRef.of(KEYS.get(j))),
                    "different legacy keys must stay distinct: " + KEYS.get(i) + " / " + KEYS.get(j));
            }
        }
    }

    private static void groupingTotalsMatch() {
        List<Amount> events = events();
        Map<String, Long> byString = accumulate(events, false);
        Map<ItemRef, Long> byRef = accumulate(events, true);
        check(byString.size() == distinctLegacyKeys(events), "legacy grouping key count");
        check(byRef.size() == byString.size(), "ItemRef grouping must produce the same number of groups");
        for (Map.Entry<String, Long> entry : byString.entrySet()) {
            Long refValue = byRef.get(ItemRef.of(entry.getKey()));
            check(refValue != null && refValue.equals(entry.getValue()),
                "group total mismatch for " + entry.getKey());
        }
    }

    private static void shortfallVectorsMatch() {
        List<Amount> events = events();
        Map<String, Long> requiredByString = accumulate(events, false);
        Map<ItemRef, Long> requiredByRef = accumulate(events, true);
        Map<String, Long> suppliedByString = new LinkedHashMap<>();
        Map<ItemRef, Long> suppliedByRef = new LinkedHashMap<>();
        for (String key : KEYS) {
            long supplied = 3L;
            suppliedByString.put(key, supplied);
            suppliedByRef.put(ItemRef.of(key), supplied);
        }
        for (Map.Entry<String, Long> entry : requiredByString.entrySet()) {
            String key = entry.getKey();
            long legacyShortfall = Math.max(0L, entry.getValue() - suppliedByString.getOrDefault(key, 0L));
            long refShortfall = Math.max(0L,
                requiredByRef.getOrDefault(ItemRef.of(key), 0L)
                    - suppliedByRef.getOrDefault(ItemRef.of(key), 0L));
            check(legacyShortfall == refShortfall, "shortfall mismatch for " + key);
        }
    }

    private static void fluidClassificationMatches() {
        for (String key : KEYS) {
            boolean legacy = key.startsWith("fluid:");
            check(ItemRef.of(key).isFluid() == legacy, "fluid classification mismatch for " + key);
        }
    }

    private static List<Amount> events() {
        List<Amount> result = new ArrayList<>();
        for (String key : KEYS) {
            result.add(new Amount(key, 2L));
            result.add(new Amount(key, 5L));
        }
        result.add(new Amount(KEYS.get(0), 7L));
        result.add(new Amount(KEYS.get(3), Long.MAX_VALUE));
        result.add(new Amount(KEYS.get(3), 1L));
        return result;
    }

    private static long distinctLegacyKeys(List<Amount> events) {
        return events.stream().map(Amount::key).distinct().count();
    }

    private static <K> Map<K, Long> accumulate(List<Amount> events, boolean useRef) {
        Map<K, Long> totals = new TreeMap<>((left, right) -> String.valueOf(left).compareTo(String.valueOf(right)));
        for (Amount event : events) {
            @SuppressWarnings("unchecked")
            K key = (K) (useRef ? ItemRef.of(event.key()) : event.key());
            totals.merge(key, event.amount(), Amounts::safeAdd);
        }
        return totals;
    }

    private record Amount(String key, long amount) {
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
