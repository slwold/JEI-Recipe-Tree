package com.slwold.jeirecipetree.core;

import java.util.ArrayList;
import java.util.List;

public final class ItemRefTest {

    private ItemRefTest() {
    }

    public static void main(String[] args) {
        rejectsBlankKeys();
        equalityAndHashing();
        fluidDetection();
        tagDetection();
        placeholderIdentity();
        usableAsMapKey();
        System.out.println("ItemRef regression checks passed: blank guard, equality, fluid flag, tag flag, placeholder, map key.");
    }

    private static void rejectsBlankKeys() {
        expectThrows("null", () -> new ItemRef(null));
        expectThrows("empty", () -> new ItemRef(""));
        expectThrows("blank", () -> new ItemRef("   "));
    }

    private static void equalityAndHashing() {
        ItemRef a = ItemRef.of("minecraft:iron_ingot");
        ItemRef b = ItemRef.of("minecraft:iron_ingot");
        ItemRef c = ItemRef.of("minecraft:gold_ingot");

        check(a.equals(b), "same key must be equal");
        check(a.hashCode() == b.hashCode(), "same key must share hashCode");
        check(!a.equals(c), "different keys must differ");
        check(a.key().equals("minecraft:iron_ingot"), "key accessor must round-trip");
    }

    private static void fluidDetection() {
        check(ItemRef.of("fluid:minecraft:water").isFluid(), "fluid prefix must be detected");
        check(!ItemRef.of("minecraft:water_bucket").isFluid(), "item key must not be treated as fluid");
    }

    private static void tagDetection() {
        check(ItemRef.of("tag:minecraft:planks").isTag(), "tag prefix must be detected");
        check(!ItemRef.of("minecraft:oak_planks").isTag(), "item key must not be treated as tag");
        check(!ItemRef.of("tag:minecraft:planks").isFluid(), "tag must not be treated as fluid");
    }

    private static void placeholderIdentity() {
        ItemRef ref = ItemRef.placeholder("能耗");
        check(ref.isPlaceholder(), "placeholder prefix must be detected");
        check(ref.placeholderLabel().equals("能耗"), "label must round-trip, got " + ref.placeholderLabel());
        check(!ref.isFluid() && !ref.isTag(), "placeholder must not be fluid or tag");
        check(ItemRef.of("minecraft:paper").placeholderLabel().isEmpty(),
            "non-placeholder must have empty label");
        expectThrows("blank placeholder", () -> ItemRef.placeholder("   "));
    }

    private static void usableAsMapKey() {
        List<ItemRef> refs = new ArrayList<>(List.of(
            ItemRef.of("minecraft:oak_planks"),
            ItemRef.of("minecraft:iron_ingot"),
            ItemRef.of("minecraft:oak_planks")
        ));
        long distinct = refs.stream().distinct().count();
        check(distinct == 2, "distinct() must collapse duplicate keys, got " + distinct);
    }

    private static void expectThrows(String label, Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("expected IllegalArgumentException for " + label);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
