package com.slwold.jeirecipetree.client;

import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

public interface StorageDriver {

    default int priority() {
        return 100;
    }

    default String id() {
        return getClass().getSimpleName();
    }

    boolean handles(Object menu);

    @Nullable
    StorageNetworkIntegration.StorageSnapshot read(Object menu);

    default boolean canWithdraw(Object menu) {
        return false;
    }

    default boolean requestWithdraw(Object menu, ItemStack stack, int amount) {
        return false;
    }

    default boolean depositCarried(Object menu) {
        return false;
    }
}
