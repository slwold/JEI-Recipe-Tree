package com.slwold.jeirecipetree.client;

import com.slwold.jeirecipetree.core.ItemRef;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SlotTags {

    public static final String PREFIX = "tag:";

    private static final Map<ItemRef, List<ItemRef>> MEMBERS = new HashMap<>();

    private SlotTags() {
    }

    public static Optional<ItemRef> tagOf(IRecipeSlotView slot) {
        if (slot == null) {
            return Optional.empty();
        }
        return slot.getTagKey().map(SlotTags::refOf);
    }

    private static ItemRef refOf(TagKey<?> key) {
        return ItemRef.of(PREFIX + key.location());
    }

    public static List<ItemRef> members(ItemRef tag) {
        if (tag == null || !tag.isTag()) {
            return List.of();
        }
        return MEMBERS.computeIfAbsent(tag, SlotTags::expand);
    }

    public static List<ItemRef> siblingsOf(ItemRef ref) {
        if (ref == null || ref.isTag()) {
            return List.of();
        }
        ResourceLocation id = ResourceLocation.tryParse(ref.key());
        if (id == null) {
            return List.of();
        }
        Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null) {
            return List.of();
        }
        TagKey<Item> best = null;
        int bestSize = Integer.MAX_VALUE;
        for (TagKey<Item> tag : BuiltInRegistries.ITEM.getTagNames().toList()) {
            Optional<HolderSet.Named<Item>> holders = BuiltInRegistries.ITEM.getTag(tag);
            if (holders.isEmpty()) {
                continue;
            }
            int size = holders.get().size();
            if (size < 2 || size > 64 || size >= bestSize) {
                continue;
            }
            boolean contains = holders.get().stream().anyMatch(h -> h.value() == item);
            if (contains) {
                best = tag;
                bestSize = size;
            }
        }
        if (best == null) {
            return List.of();
        }
        return expand(ItemRef.of(PREFIX + best.location()));
    }

    private static List<ItemRef> expand(ItemRef tag) {
        ResourceLocation id = ResourceLocation.tryParse(tag.key().substring(PREFIX.length()));
        if (id == null) {
            JERTLog.debug("slot tag: '{}' is not a valid resource location", tag.key());
            return List.of();
        }
        Optional<? extends Iterable<Holder<Item>>> holders =
            BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, id));
        if (holders.isEmpty()) {
            JERTLog.debug("slot tag: '{}' has no item member in this pack", id);
            return List.of();
        }
        List<ItemRef> result = new ArrayList<>();
        for (Holder<Item> holder : holders.get()) {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(holder.value());
            if (itemId != null) {
                result.add(ItemRef.of(itemId.toString()));
            }
        }
        return List.copyOf(result);
    }

    static void clear() {
        MEMBERS.clear();
    }
}
