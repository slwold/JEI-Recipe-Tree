package com.slwold.jeirecipetree.client.ui;
import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;

import com.slwold.jeirecipetree.core.ItemRef;
import net.minecraft.network.chat.Component;
import com.slwold.jeirecipetree.core.RecipeRecord;

import java.util.ArrayList;
import java.util.List;

public final class RecipeDraft {

    public enum Section {
        OUTPUT, CATALYST, INPUT
    }

    public static final long MAX_AMOUNT = 999_999L;

    public static final int MAX_SLOTS = 16;

    private final String id;
    private final String group;
    private final List<RecipeRecord.Slot> outputs;
    private final List<RecipeRecord.Slot> inputs;
    private final List<RecipeRecord.Slot> catalysts;

    public RecipeDraft(RecipeRecord record) {
        this.id = record.id();
        this.group = record.group();
        this.outputs = new ArrayList<>(record.outputs());
        this.inputs = new ArrayList<>(record.inputs());
        this.catalysts = new ArrayList<>(record.catalysts());
    }

    public List<RecipeRecord.Slot> slots(Section section) {
        return switch (section) {
            case OUTPUT -> outputs;
            case CATALYST -> catalysts;
            case INPUT -> inputs;
        };
    }

    public void adjust(Section section, int index, long delta) {
        List<RecipeRecord.Slot> slots = slots(section);
        if (index < 0 || index >= slots.size()) {
            return;
        }
        RecipeRecord.Slot slot = slots.get(index);
        long amount = Math.max(1L, Math.min(MAX_AMOUNT, slot.amount() + delta));
        if (amount != slot.amount()) {
            slots.set(index, new RecipeRecord.Slot(slot.alternatives(), amount));
        }
    }

    public void setAmount(Section section, int index, long amount) {
        List<RecipeRecord.Slot> slots = slots(section);
        if (index < 0 || index >= slots.size()) {
            return;
        }
        RecipeRecord.Slot slot = slots.get(index);
        long clamped = Math.max(1L, Math.min(MAX_AMOUNT, amount));
        if (clamped != slot.amount()) {
            slots.set(index, new RecipeRecord.Slot(slot.alternatives(), clamped));
        }
    }

    public boolean remove(Section section, int index) {
        List<RecipeRecord.Slot> slots = slots(section);
        if (index < 0 || index >= slots.size()) {
            return false;
        }
        if (section == Section.OUTPUT && slots.size() <= 1) {
            return false;
        }
        slots.remove(index);
        return true;
    }

    public RecipeRecord toRecord() {
        return new RecipeRecord(id, group, outputs, inputs, catalysts);
    }

    public String id() {
        return id;
    }

    public String group() {
        return group;
    }

    public RecipeRecord toRecord(String targetGroup) {
        return new RecipeRecord(id, targetGroup == null || targetGroup.isBlank() ? group : targetGroup,
            outputs, inputs, catalysts);
    }

    public void clear() {
        outputs.clear();
        inputs.clear();
        catalysts.clear();
        addSlot(Section.OUTPUT, ItemRef.placeholder(Component.translatable("gui.jeirecipetree.edit.newOutput").getString()));
    }

    public void replaceAlternative(Section section, int index, ItemRef ref) {
        List<RecipeRecord.Slot> slots = slots(section);
        if (ref == null || index < 0 || index >= slots.size()) {
            return;
        }
        RecipeRecord.Slot slot = slots.get(index);
        long amount = slot.amount();

        if (slot.primary().isFluid() != ref.isFluid()) {
            amount = ref.isFluid() ? 1000L : 1L;
        }
        slots.set(index, new RecipeRecord.Slot(List.of(ref), amount));
    }

    public void replaceSlot(Section section, int index, RecipeRecord.Slot slot) {
        List<RecipeRecord.Slot> slots = slots(section);
        if (slot == null || index < 0 || index >= slots.size()) {
            return;
        }
        slots.set(index, slot);
    }

    public boolean addSlot(Section section, RecipeRecord.Slot slot) {
        List<RecipeRecord.Slot> slots = slots(section);
        if (slot == null || slots.size() >= MAX_SLOTS) {
            return false;
        }
        long clamped = Math.max(1L, Math.min(MAX_AMOUNT, slot.amount()));
        slots.add(new RecipeRecord.Slot(slot.alternatives(), clamped));
        return true;
    }

    public RecipeRecord.Slot takeSlot(Section section, int index) {
        List<RecipeRecord.Slot> slots = slots(section);
        if (index < 0 || index >= slots.size()) {
            return null;
        }
        if (section == Section.OUTPUT && slots.size() <= 1) {
            return null;
        }
        return slots.remove(index);
    }

    public boolean addSlot(Section section, ItemRef ref) {
        return addSlot(section, ref, 1L);
    }

    public boolean addSlot(Section section, ItemRef ref, long amount) {
        List<RecipeRecord.Slot> slots = slots(section);
        if (ref == null || slots.size() >= MAX_SLOTS) {
            return false;
        }
        long clamped = Math.max(1L, Math.min(MAX_AMOUNT, amount));
        slots.add(new RecipeRecord.Slot(List.of(ref), clamped));
        return true;
    }

}
