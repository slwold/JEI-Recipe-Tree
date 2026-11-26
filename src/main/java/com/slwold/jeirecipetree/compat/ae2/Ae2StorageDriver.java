package com.slwold.jeirecipetree.compat.ae2;

import com.slwold.jeirecipetree.client.data.*;
import com.slwold.jeirecipetree.client.storage.Ae2StorageIntegration;
import com.slwold.jeirecipetree.client.storage.StorageDriver;
import com.slwold.jeirecipetree.client.storage.StorageDriverRegistry;
import com.slwold.jeirecipetree.client.storage.StorageNetworkIntegration;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.ILinkStatus;
import appeng.helpers.InventoryAction;
import appeng.menu.me.common.GridInventoryEntry;
import appeng.menu.me.common.IClientRepo;
import appeng.menu.me.common.MEStorageMenu;
import appeng.menu.slot.CraftingMatrixSlot;
import com.slwold.jeirecipetree.client.data.FluidRecipeCompat;
import com.slwold.jeirecipetree.client.JERTLog;
import com.slwold.jeirecipetree.client.data.RecipeTreeData;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class Ae2StorageDriver implements StorageDriver {

    public static final Ae2StorageDriver INSTANCE = new Ae2StorageDriver();

    private static final long REFRESH_INTERVAL_TICKS = 5L;

    private Object cachedMenu;
    private long nextRefreshTick;
    private long cachedVersion = Long.MIN_VALUE;
    private List<StorageNetworkIntegration.StoredStack> cachedStacks = List.of();
    private List<StorageNetworkIntegration.StoredFluid> cachedFluids = List.of();

    private String lastNote = "";

    private Ae2StorageDriver() {
    }

    public static void register() {
        StorageDriverRegistry.register(INSTANCE);
    }

    @Override
    public int priority() {
        return 50;
    }

    @Override
    public String id() {
        return "ae2-direct-api";
    }

    @Override
    public boolean handles(Object menu) {
        return menu instanceof MEStorageMenu;
    }

    @Override
    @Nullable
    public StorageNetworkIntegration.StorageSnapshot read(Object menu) {
        if (!(menu instanceof MEStorageMenu storageMenu)) {
            return null;
        }
        refresh(storageMenu);
        return new StorageNetworkIntegration.StorageSnapshot(
            cachedStacks, cachedFluids, cachedVersion);
    }

    @Override
    public boolean canWithdraw(Object menu) {
        return menu instanceof MEStorageMenu;
    }

    @Override
    public boolean requestWithdraw(Object menu, ItemStack stack, int amount) {
        if (!(menu instanceof MEStorageMenu storageMenu)
            || stack == null || stack.isEmpty() || amount <= 0) {
            return false;
        }
        ILinkStatus status = storageMenu.getLinkStatus();
        if (status != null && !status.connected()) {
            note("withdraw skipped: ME network not connected");
            return false;
        }
        AEItemKey wanted = AEItemKey.of(stack);
        IClientRepo repo = wanted == null ? null : storageMenu.getClientRepo();
        if (repo == null) {
            return false;
        }
        for (GridInventoryEntry entry : repo.getAllEntries()) {
            if (entry == null || entry.getStoredAmount() <= 0L) {
                continue;
            }
            AEKey what = entry.getWhat();
            if (what == null || !wanted.equals(what)) {
                continue;
            }

            storageMenu.handleInteraction(entry.getSerial(), InventoryAction.PICKUP_OR_SET_DOWN);
            note("withdraw request: " + stack.getHoverName().getString()
                + " (want " + amount + ", one stack max per request)");
            return true;
        }
        note("withdraw request: entry not found in client repo");
        return false;
    }

    @Override
    public boolean depositCarried(Object menu) {
        if (!(menu instanceof MEStorageMenu storageMenu) || storageMenu.getCarried().isEmpty()) {
            return false;
        }

        storageMenu.handleInteraction(-1L, InventoryAction.PICKUP_OR_SET_DOWN);
        return true;
    }

    private void refresh(MEStorageMenu menu) {
        Minecraft minecraft = Minecraft.getInstance();
        long gameTime = minecraft.level == null ? 0L : minecraft.level.getGameTime();
        if (cachedMenu == menu && gameTime < nextRefreshTick) {
            return;
        }
        cachedMenu = menu;
        nextRefreshTick = gameTime + REFRESH_INTERVAL_TICKS;

        ILinkStatus status = menu.getLinkStatus();
        if (status != null && !status.connected()) {
            note("ME network not connected; stock reported as 0");
            cachedStacks = List.of();
            cachedFluids = List.of();
            cachedVersion = Long.MIN_VALUE;
            return;
        }

        Map<String, ItemStack> stackByKey = new LinkedHashMap<>();
        Map<String, Long> itemAmounts = new LinkedHashMap<>();
        Map<String, Long> fluidAmounts = new LinkedHashMap<>();
        collectRepo(menu.getClientRepo(), stackByKey, itemAmounts, fluidAmounts);
        collectOpenSlots(menu, stackByKey, itemAmounts);

        cachedStacks = buildStacks(stackByKey, itemAmounts);
        cachedFluids = buildFluids(fluidAmounts);
        cachedVersion = fingerprint(menu, itemAmounts, fluidAmounts);
        if (!cachedStacks.isEmpty() || !cachedFluids.isEmpty()) {
            note("stock synced");
        }
    }

    private void collectRepo(
        IClientRepo repo,
        Map<String, ItemStack> stackByKey,
        Map<String, Long> itemAmounts,
        Map<String, Long> fluidAmounts
    ) {
        if (repo == null) {
            note("client repo not synchronized yet; only open-menu slots count");
            return;
        }
        for (GridInventoryEntry entry : repo.getAllEntries()) {
            AEKey key = entry.getWhat();
            long amount = entry.getStoredAmount();
            if (key == null || amount <= 0) {
                continue;
            }
            if (key instanceof AEItemKey itemKey) {
                addItem(itemKey.getReadOnlyStack(), amount, stackByKey, itemAmounts);
                continue;
            }
            Optional<FluidRecipeCompat.FluidInfo> info = FluidRecipeCompat.describeFluid(key);
            info.ifPresent(value -> fluidAmounts.merge(
                value.key(), Math.max(amount, value.amount()), Long::sum));
        }
    }

    private void collectOpenSlots(
        MEStorageMenu menu,
        Map<String, ItemStack> stackByKey,
        Map<String, Long> itemAmounts
    ) {
        int taken = 0;
        for (Slot slot : menu.slots) {
            if (!(slot instanceof CraftingMatrixSlot)) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            addItem(stack, stack.getCount(), stackByKey, itemAmounts);
            taken++;
        }
        if (taken > 0) {
            note("crafting grid merged: " + taken + " slot(s)");
        }
    }

    private static void addItem(
        ItemStack stack,
        long amount,
        Map<String, ItemStack> stackByKey,
        Map<String, Long> itemAmounts
    ) {
        if (stack == null || stack.isEmpty() || amount <= 0) {
            return;
        }
        String identity = RecipeTreeData.ingredientKey(stack);
        stackByKey.putIfAbsent(identity, stack);
        itemAmounts.merge(identity, amount, Long::sum);
    }

    private static List<StorageNetworkIntegration.StoredStack> buildStacks(
        Map<String, ItemStack> stackByKey,
        Map<String, Long> itemAmounts
    ) {
        List<StorageNetworkIntegration.StoredStack> stacks = new ArrayList<>(stackByKey.size());
        stackByKey.forEach((identity, stack) -> stacks.add(
            new StorageNetworkIntegration.StoredStack(stack, itemAmounts.getOrDefault(identity, 0L))));
        return List.copyOf(stacks);
    }

    private static List<StorageNetworkIntegration.StoredFluid> buildFluids(Map<String, Long> fluidAmounts) {
        List<StorageNetworkIntegration.StoredFluid> fluids = new ArrayList<>(fluidAmounts.size());
        fluidAmounts.forEach((key, amount) ->
            fluids.add(new StorageNetworkIntegration.StoredFluid(key, amount)));
        return List.copyOf(fluids);
    }

    private static long fingerprint(
        MEStorageMenu menu,
        Map<String, Long> items,
        Map<String, Long> fluids
    ) {
        long version = menu.containerId;
        for (Map.Entry<String, Long> entry : items.entrySet()) {
            version = 31L * version + entry.getKey().hashCode();
            version = 31L * version + Long.hashCode(entry.getValue());
        }
        for (Map.Entry<String, Long> entry : fluids.entrySet()) {
            version = 31L * version + entry.getKey().hashCode();
            version = 31L * version + Long.hashCode(entry.getValue());
        }
        return version;
    }

    private void note(String reason) {
        if (reason.equals(lastNote)) {
            return;
        }
        lastNote = reason;
        JERTLog.debug("ae2: {}", reason);
    }
}
