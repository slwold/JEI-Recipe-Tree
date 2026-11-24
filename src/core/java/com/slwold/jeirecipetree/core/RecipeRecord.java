package com.slwold.jeirecipetree.core;

import java.util.List;

public record RecipeRecord(
    String id,
    String group,
    List<Slot> outputs,
    List<Slot> inputs,
    List<Slot> catalysts
) {

    public RecipeRecord {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("recipe id must not be blank");
        }
        group = group == null ? "" : group;
        outputs = copySlots(outputs, "outputs");
        inputs = copySlots(inputs, "inputs");
        catalysts = copySlots(catalysts, "catalysts");
        if (outputs.isEmpty()) {
            throw new IllegalArgumentException("recipe " + id + " has no output slot");
        }
    }

    private static List<Slot> copySlots(List<Slot> slots, String name) {
        if (slots == null) {
            return List.of();
        }
        for (Slot slot : slots) {
            if (slot == null) {
                throw new IllegalArgumentException("recipe " + name + " contains a null slot");
            }
        }
        return List.copyOf(slots);
    }

    public record Slot(List<ItemRef> alternatives, long amount) {

        public Slot {
            if (alternatives == null || alternatives.isEmpty()) {
                throw new IllegalArgumentException("slot needs at least one alternative");
            }
            for (ItemRef alternative : alternatives) {
                if (alternative == null) {
                    throw new IllegalArgumentException("slot contains a null alternative");
                }
            }
            alternatives = List.copyOf(alternatives);
            if (amount <= 0) {
                throw new IllegalArgumentException("slot amount must be positive, got " + amount);
            }
        }

        public ItemRef primary() {
            return alternatives.get(0);
        }
    }
}
