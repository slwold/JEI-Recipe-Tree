package com.slwold.jeirecipetree.client;

import com.slwold.jeirecipetree.core.ItemRef;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class ItemKeys {

    public static final String EMPTY_KEY = "minecraft:air";

    private static long keyCalls;

    static long keyCallCount() {
        return keyCalls;
    }

    private ItemKeys() {
    }

    private static final Map<net.minecraft.world.item.Item, String> PLAIN_KEYS = new ConcurrentHashMap<>();

    private static final String IDENTITY_TAG = "jert_identity";

    public static ItemRef of(ItemStack stack) {
        String identity = storedIdentity(stack);
        return ItemRef.of(identity != null ? identity : legacyKey(stack));
    }

    public static ItemStack withIdentity(ItemStack stack, String key) {
        if (stack == null || stack.isEmpty() || key == null || key.isBlank()) {
            return stack;
        }
        CompoundTag tag = new CompoundTag();
        tag.putString(IDENTITY_TAG, key);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    private static String storedIdentity(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        String key = data.copyTag().getString(IDENTITY_TAG);
        return key.isEmpty() ? null : key;
    }

    public static String legacy(ItemRef ref) {
        return ref == null ? EMPTY_KEY : ref.key();
    }

    private static String legacyKey(ItemStack stack) {
        keyCalls++;
        if (stack == null || stack.isEmpty()) {
            return EMPTY_KEY;
        }
        Optional<String> fluidKey = FluidRecipeCompat.fluidKey(stack);
        if (fluidKey.isPresent()) {
            return fluidKey.get();
        }

        if (stack.getComponentsPatch().isEmpty()) {
            String cached = PLAIN_KEYS.get(stack.getItem());
            if (cached != null) {
                return cached;
            }
            String computed = uniqueId(stack);
            PLAIN_KEYS.put(stack.getItem(), computed);
            return computed;
        }
        return uniqueId(stack);
    }

    private static String uniqueId(ItemStack stack) {
        IJeiRuntime runtime = DirectoryRecipePlugin.getJeiRuntime();
        if (runtime != null) {
            try {
                return runtime.getIngredientManager()
                    .getIngredientHelper(VanillaTypes.ITEM_STACK)
                    .getUniqueId(stack, UidContext.Ingredient);
            } catch (RuntimeException ignored) {

            }
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    static void clear() {
        PLAIN_KEYS.clear();
    }
}
