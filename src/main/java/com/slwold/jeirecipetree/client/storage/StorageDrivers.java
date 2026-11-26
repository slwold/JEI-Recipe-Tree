package com.slwold.jeirecipetree.client.storage;

import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class StorageDrivers {

    public static final int REFINED = 10;
    public static final int BEYOND = 20;
    public static final int BETTER_BEYOND = 30;
    public static final int INTEGRATED = 40;
    public static final int TOMS = 60;
    public static final int FALLBACK = 1000;

    private StorageDrivers() {
    }

    public static void registerStandard() {
        StorageDriverRegistry.register(new RefinedStorageDriver());
        StorageDriverRegistry.register(new BeyondDimensionsDriver());
        StorageDriverRegistry.register(new BetterBeyondDriver());
        StorageDriverRegistry.register(new IntegratedTerminalDriver());
    }

    public static void registerFallback() {
        StorageDriverRegistry.register(new LegacyReflectionDriver());
    }

    private static StorageNetworkIntegration.StorageSnapshot snapshot(
        List<StorageNetworkIntegration.StoredStack> stacks,
        List<StorageNetworkIntegration.StoredFluid> fluids
    ) {
        return new StorageNetworkIntegration.StorageSnapshot(stacks, fluids, System.nanoTime());
    }

    private static final class RefinedStorageDriver implements StorageDriver {
        @Override public int priority() { return REFINED; }
        @Override public String id() { return "refined-storage"; }

        @Override
        public boolean handles(Object menu) {
            return StorageNetworkIntegration.isRefinedStorageMenu(menu);
        }

        @Override
        @Nullable
        public StorageNetworkIntegration.StorageSnapshot read(Object menu) {
            StorageNetworkIntegration.RefinedSnapshot value =
                StorageNetworkIntegration.refinedStorageSnapshot(menu);
            return value == null ? null : snapshot(value.stacks(), value.fluids());
        }
    }

    private static final class BeyondDimensionsDriver implements StorageDriver {
        @Override public int priority() { return BEYOND; }
        @Override public String id() { return "beyond-dimensions"; }

        @Override
        public boolean handles(Object menu) {
            return StorageNetworkIntegration.isBeyondMenu(menu);
        }

        @Override
        @Nullable
        public StorageNetworkIntegration.StorageSnapshot read(Object menu) {
            StorageNetworkIntegration.BeyondSnapshot value =
                StorageNetworkIntegration.beyondSnapshot(menu);
            return value == null ? null : snapshot(value.stacks(), value.fluids());
        }
    }

    private static final class BetterBeyondDriver implements StorageDriver {
        @Override public int priority() { return BETTER_BEYOND; }
        @Override public String id() { return "better-beyond"; }

        @Override
        public boolean handles(Object menu) {
            return StorageNetworkIntegration.isBetterBeyondMenu(menu);
        }

        @Override
        @Nullable
        public StorageNetworkIntegration.StorageSnapshot read(Object menu) {
            StorageNetworkIntegration.BetterBeyondSnapshot value =
                StorageNetworkIntegration.betterBeyondSnapshot(menu);
            return value == null ? null : snapshot(value.stacks(), value.fluids());
        }
    }

    private static final class IntegratedTerminalDriver implements StorageDriver {
        @Override public int priority() { return INTEGRATED; }
        @Override public String id() { return "integrated-terminal"; }

        @Override
        public boolean handles(Object menu) {
            return StorageNetworkIntegration.isIntegratedTerminalMenu(menu);
        }

        @Override
        @Nullable
        public StorageNetworkIntegration.StorageSnapshot read(Object menu) {
            return snapshot(
                StorageNetworkIntegration.integratedTerminalStacks(menu),
                StorageNetworkIntegration.integratedTerminalFluids(menu));
        }
    }

    private static final class LegacyReflectionDriver implements StorageDriver {
        @Override public int priority() { return FALLBACK; }
        @Override public String id() { return "legacy-reflection-fallback"; }

        @Override
        public boolean handles(Object menu) {
            return true;
        }

        @Override
        @Nullable
        public StorageNetworkIntegration.StorageSnapshot read(Object menu) {
            boolean refined = StorageNetworkIntegration.isRefinedStorageMenu(menu);
            boolean beyond = StorageNetworkIntegration.isBeyondMenu(menu);
            boolean betterBeyond = StorageNetworkIntegration.isBetterBeyondMenu(menu);
            boolean integrated = StorageNetworkIntegration.isIntegratedTerminalMenu(menu);
            boolean ae2Crafting = !refined && !beyond && !betterBeyond && !integrated
                && Ae2StorageIntegration.isCraftingMenu(menu);

            List<StorageNetworkIntegration.StoredStack> ae2Stacks = new ArrayList<>();
            List<StorageNetworkIntegration.StoredFluid> ae2Fluids = new ArrayList<>();
            if (!refined && !beyond && !betterBeyond && !integrated) {
                for (Ae2StorageIntegration.StoredStack stored : Ae2StorageIntegration.storedStacks()) {
                    ae2Stacks.add(new StorageNetworkIntegration.StoredStack(stored.stack(), stored.amount()));
                }
                for (StorageNetworkIntegration.StoredFluid stored : Ae2StorageIntegration.storedFluids()) {
                    ae2Fluids.add(new StorageNetworkIntegration.StoredFluid(stored.key(), stored.amount()));
                }
            }
            if (!ae2Stacks.isEmpty() || !ae2Fluids.isEmpty() || ae2Crafting) {
                return snapshot(ae2Stacks, ae2Fluids);
            }
            StorageNetworkIntegration.RefinedSnapshot refinedValue =
                StorageNetworkIntegration.refinedStorageSnapshot(menu);
            if (!refinedValue.stacks().isEmpty() || !refinedValue.fluids().isEmpty() || refined) {
                return snapshot(refinedValue.stacks(), refinedValue.fluids());
            }
            StorageNetworkIntegration.BeyondSnapshot beyondValue =
                StorageNetworkIntegration.beyondSnapshot(menu);
            if (!beyondValue.stacks().isEmpty() || !beyondValue.fluids().isEmpty() || beyond) {
                return snapshot(beyondValue.stacks(), beyondValue.fluids());
            }
            StorageNetworkIntegration.BetterBeyondSnapshot betterBeyondValue =
                StorageNetworkIntegration.betterBeyondSnapshot(menu);
            if (!betterBeyondValue.stacks().isEmpty() || !betterBeyondValue.fluids().isEmpty()
                || betterBeyond) {
                return snapshot(betterBeyondValue.stacks(), betterBeyondValue.fluids());
            }
            return snapshot(
                StorageNetworkIntegration.integratedTerminalStacks(menu),
                StorageNetworkIntegration.integratedTerminalFluids(menu));
        }
    }
}
