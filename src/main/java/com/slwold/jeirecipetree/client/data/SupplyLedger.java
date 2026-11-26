package com.slwold.jeirecipetree.client.data;
import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;
import com.slwold.jeirecipetree.client.storage.*;
import com.slwold.jeirecipetree.client.transfer.*;
import com.slwold.jeirecipetree.client.ui.*;

import com.slwold.jeirecipetree.client.storage.StorageNetworkIntegration;
import com.slwold.jeirecipetree.client.data.RecipeTreeData.MutableCost;
import com.slwold.jeirecipetree.core.Amounts;
import com.slwold.jeirecipetree.core.ItemRef;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SupplyLedger {

    public static final int PLAYER_SUPPLY_SLOTS = 36;

    private static final Set<String> WARNED_FAILURES = ConcurrentHashMap.newKeySet();

    private static final long FNV_OFFSET = 1469598103934665603L;

    private static Map<ItemRef, MutableCost> cachedInventory = Map.of();

    private static Map<ItemRef, MutableCost> cachedNetworkBuckets = Map.of();

    private static Map<ItemRef, MutableCost> cachedGridBuckets = Map.of();

    private static Map<String, Long> cachedPlanningAmounts = Map.of();
    private static Map<ItemRef, Long> cachedInventoryAmounts = Map.of();
    private static Map<ItemRef, Long> cachedPlayerAmounts = Map.of();
    private static Map<ItemRef, Long> cachedContainerAmounts = Map.of();

    private static Map<String, Long> legacyInventoryAmounts = Map.of();
    private static Map<String, Long> legacyPlayerAmounts = Map.of();
    private static Map<String, Long> legacyContainerAmounts = Map.of();

    private static boolean craftingModeEnabled;

    private static long revision;
    private static long cachedRevision = Long.MIN_VALUE;
    private static long scanCount;
    private static long lastScanNanos = Long.MIN_VALUE;

    private static final long MIN_SCAN_INTERVAL_NANOS = 250_000_000L;

    private static final long CONTENT_HASH_INTERVAL_NANOS = 200_000_000L;
    private static long lastContentHashNanos = Long.MIN_VALUE;
    private static long lastContentHash;

    private static boolean contentHashPrimed;

    private static Map<ItemRef, MutableCost> cachedPlayerBuckets = Map.of();

    private SupplyLedger() {
    }

    public static void setCraftingMode(boolean enabled) {
        if (craftingModeEnabled == enabled) {
            return;
        }
        craftingModeEnabled = enabled;
        revision++;
        clearCaches();
    }

    public static boolean craftingMode() {
        return craftingModeEnabled;
    }

    public static long scanCount() {
        return scanCount;
    }

    public static long revision() {
        return revision;
    }

    public static long playerContentHash() {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) {
            return 0L;
        }
        var inventory = player.getInventory();
        long hash = FNV_OFFSET;
        int slots = Math.min(PLAYER_SUPPLY_SLOTS, inventory.getContainerSize());
        for (int slot = 0; slot < slots; slot++) {
            hash = mixStack(hash, inventory.getItem(slot));
        }
        return hash;
    }

    public static long contentHash() {
        long hash = playerContentHash();

        hash = (hash ^ StorageNetworkIntegration.snapshotRevision()) * 1099511628211L;
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) {
            return hash;
        }
        var menu = player.containerMenu;
        var inventory = player.getInventory();
        if (menu == null) {
            return hash;
        }
        for (Slot slot : menu.slots) {

            try {
                if (slot == null) {
                    continue;
                }
                boolean craftingSlot = isCraftingGridSlot(slot) || slot.container instanceof CraftingContainer;
                if (!craftingSlot && (slot.container == inventory || !countsTowardContainerSupply(slot))) {
                    continue;
                }
                hash = mixStack(hash, slot.getItem());
            } catch (RuntimeException error) {
                warnOnce("contentHashSlot", "container slot hash failed, slot skipped: {}", error);
            }
        }
        return hash;
    }

    private static long mixStack(long hash, ItemStack stack) {
        hash = (hash ^ (stack == null ? 0 : stack.getItem().hashCode())) * 1099511628211L;
        hash = (hash ^ (stack == null ? 0 : stack.getCount())) * 1099511628211L;
        return (hash ^ (stack == null ? 0 : stack.getComponentsPatch().hashCode())) * 1099511628211L;
    }

    public static void invalidate() {
        revision++;
        clearCaches();
    }

    public enum SupplyView {
        PLAYER,
        PLAYER_AND_CONTAINER,
        ALL
    }

    private record Scan(
        Map<ItemRef, MutableCost> all,
        Map<ItemRef, MutableCost> playerBuckets,
        Map<ItemRef, Long> player,
        Map<ItemRef, Long> container,
        Map<ItemRef, MutableCost> network,
        Map<ItemRef, MutableCost> grid
    ) {
    }

    public static Map<String, Long> inventoryAmounts() {
        snapshot();
        return legacyInventoryAmounts;
    }

    public static Map<String, Long> playerAmounts() {
        snapshot();
        return legacyPlayerAmounts;
    }

    public static Map<String, Long> planningAmounts() {
        snapshot();
        return cachedPlanningAmounts;
    }

    public static Map<String, Long> amounts(SupplyView view) {
        if (view == null) {
            return Map.of();
        }
        snapshot();
        return switch (view) {
            case PLAYER -> legacyPlayerAmounts;
            case PLAYER_AND_CONTAINER -> mergeAmounts(legacyPlayerAmounts, legacyContainerAmounts);
            case ALL -> legacyInventoryAmounts;
        };
    }

    public static long inventoryAmount(ItemStack wanted) {
        if (wanted == null || wanted.isEmpty()) {
            return 0L;
        }
        return playerAmounts().getOrDefault(RecipeTreeData.ingredientKey(wanted), 0L);
    }

    public static long inventoryAmount(Collection<ItemStack> alternatives) {
        return inventoryAmount(alternatives, playerAmounts());
    }

    public static long inventoryAmount(Collection<ItemStack> alternatives, Map<String, Long> amounts) {
        if (alternatives == null || alternatives.isEmpty() || amounts == null || amounts.isEmpty()) {
            return 0L;
        }
        long total = 0L;
        Set<String> keys = new HashSet<>();
        for (ItemStack alternative : alternatives) {
            if (alternative != null && !alternative.isEmpty()) {
                keys.add(RecipeTreeData.ingredientKey(alternative));
            }
        }
        for (String key : keys) {
            total = Amounts.safeAdd(total, amounts.getOrDefault(key, 0L));
        }
        return total;
    }

    static Map<ItemRef, MutableCost> snapshot() {

        if (!craftingModeEnabled) {

            clearCaches();
            return Map.of();
        }

        detectContentChange();

        if (cachedRevision == revision) {
            return copyInventory(cachedInventory);
        }
        long now = System.nanoTime();
        if (now - lastScanNanos < MIN_SCAN_INTERVAL_NANOS) {
            return copyInventory(cachedInventory);
        }
        lastScanNanos = now;
        scanCount++;
        Scan scan = scan();
        cachedInventory = freezeInventory(scan.all());
        cachedPlayerBuckets = freezeInventory(scan.playerBuckets());
        cachedNetworkBuckets = freezeInventory(scan.network());
        cachedGridBuckets = freezeInventory(scan.grid());
        Map<ItemRef, Long> amounts = new LinkedHashMap<>();
        for (Map.Entry<ItemRef, MutableCost> entry : cachedInventory.entrySet()) {
            amounts.put(entry.getKey(), entry.getValue().amount);
        }
        cachedInventoryAmounts = Map.copyOf(amounts);
        cachedPlayerAmounts = Map.copyOf(scan.player());
        cachedContainerAmounts = Map.copyOf(scan.container());
        Map<ItemRef, Long> planning = new LinkedHashMap<>();
        copyInventory(cachedPlayerBuckets).forEach((ref, cost) -> planning.put(ref, cost.amount));
        cachedGridBuckets.forEach((ref, cost) ->
            planning.merge(ref, cost.amount, Amounts::safeAdd));
        cachedNetworkBuckets.forEach((ref, cost) ->
            planning.merge(ref, cost.amount, Amounts::safeAdd));
        cachedPlanningAmounts = legacyView(planning);
        legacyInventoryAmounts = legacyView(cachedInventoryAmounts);
        legacyPlayerAmounts = legacyView(cachedPlayerAmounts);
        legacyContainerAmounts = legacyView(cachedContainerAmounts);
        cachedRevision = revision;
        return copyInventory(cachedInventory);
    }

    static Map<ItemRef, MutableCost> planningSupply() {
        snapshot();
        Map<ItemRef, MutableCost> merged = copyInventory(cachedPlayerBuckets);
        mergeBuckets(merged, cachedGridBuckets);
        mergeBuckets(merged, cachedNetworkBuckets);
        return merged;
    }

    private static void mergeBuckets(Map<ItemRef, MutableCost> target, Map<ItemRef, MutableCost> extra) {
        if (extra.isEmpty()) {
            return;
        }
        extra.forEach((ref, cost) -> {
            MutableCost existing = target.get(ref);
            if (existing == null) {
                target.put(ref, new MutableCost(cost.stack, cost.alternatives, cost.amount));
            } else {
                existing.amount = Amounts.safeAdd(existing.amount, cost.amount);
            }
        });
    }

    private static void detectContentChange() {
        long now = System.nanoTime();
        if (contentHashPrimed && now - lastContentHashNanos < CONTENT_HASH_INTERVAL_NANOS) {
            return;
        }
        lastContentHashNanos = now;
        long hash = contentHash();
        if (contentHashPrimed && hash != lastContentHash) {
            revision++;
            JERTLog.debug("supply content changed, revision -> {}", revision);
        }
        lastContentHash = hash;
        contentHashPrimed = true;
    }

    public static void clearCaches() {
        cachedInventory = Map.of();
        cachedPlayerBuckets = Map.of();
        cachedNetworkBuckets = Map.of();
        cachedGridBuckets = Map.of();
        cachedPlanningAmounts = Map.of();
        cachedInventoryAmounts = Map.of();
        cachedPlayerAmounts = Map.of();
        cachedContainerAmounts = Map.of();
        legacyInventoryAmounts = Map.of();
        legacyPlayerAmounts = Map.of();
        legacyContainerAmounts = Map.of();
        cachedRevision = Long.MIN_VALUE;
        lastScanNanos = 0L;

        lastContentHashNanos = Long.MIN_VALUE;
        contentHashPrimed = false;
    }

    private static Scan scan() {
        Map<ItemRef, MutableCost> all = new LinkedHashMap<>();
        Map<ItemRef, MutableCost> playerBuckets = new LinkedHashMap<>();
        Map<ItemRef, Long> player = new LinkedHashMap<>();
        Map<ItemRef, Long> container = new LinkedHashMap<>();
        Map<ItemRef, MutableCost> network = new LinkedHashMap<>();
        Map<ItemRef, MutableCost> grid = new LinkedHashMap<>();
        var playerEntity = net.minecraft.client.Minecraft.getInstance().player;
        if (playerEntity == null) {
            return new Scan(all, playerBuckets, player, container, network, grid);
        }
        var inventory = playerEntity.getInventory();
        int playerSlots = Math.min(PLAYER_SUPPLY_SLOTS, inventory.getContainerSize());
        try {
            for (int slot = 0; slot < playerSlots; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                addSupply(all, stack, stack.getCount());
                addSupply(playerBuckets, stack, stack.getCount());
                addAmount(player, stack, stack.getCount());
            }
        } catch (RuntimeException error) {
            warnOnce("playerSupply", "player inventory scan failed, treated as empty: {}", error);
        }

        var menu = playerEntity.containerMenu;
        if (menu != null) {
            for (Slot slot : menu.slots) {

                try {
                    if (slot == null) {
                        continue;
                    }
                    if (isCraftingGridSlot(slot) || slot.container instanceof CraftingContainer) {

                        ItemStack inGrid = slot.getItem();
                        if (inGrid != null && !inGrid.isEmpty()) {
                            addSupply(all, inGrid, inGrid.getCount());
                            addSupply(grid, inGrid, inGrid.getCount());
                        }
                        continue;
                    }
                    if (slot.container == inventory || !countsTowardContainerSupply(slot)) {
                        continue;
                    }
                    ItemStack stack = slot.getItem();
                    if (stack == null || stack.isEmpty()) {
                        continue;
                    }
                    addSupply(all, stack, stack.getCount());
                    addAmount(container, stack, stack.getCount());
                } catch (RuntimeException error) {
                    warnOnce("containerSlot", "container slot scan failed, slot skipped: {}", error);
                }
            }
        }

        scanNetwork(network, all);
        reportOverlap(all, player, container, network);
        return new Scan(all, playerBuckets, player, container, network, grid);
    }

    private static void reportOverlap(
        Map<ItemRef, MutableCost> all,
        Map<ItemRef, Long> player,
        Map<ItemRef, Long> container,
        Map<ItemRef, MutableCost> network
    ) {
        if (!SafeConfig.developerMode()) {
            return;
        }
        int printed = 0;
        for (Map.Entry<ItemRef, MutableCost> entry : all.entrySet()) {
            ItemRef key = entry.getKey();
            long fromPlayer = player.getOrDefault(key, 0L);
            long fromContainer = container.getOrDefault(key, 0L);
            MutableCost net = network.get(key);
            long fromNetwork = net == null ? 0L : net.amount;
            int sources = (fromPlayer > 0 ? 1 : 0) + (fromContainer > 0 ? 1 : 0) + (fromNetwork > 0 ? 1 : 0);
            if (sources < 2) {
                continue;
            }
            JERTLog.debug("stock overlap: {} player={} container={} network={} total={}",
                key.key(), fromPlayer, fromContainer, fromNetwork, entry.getValue().amount);
            if (++printed >= 12) {
                return;
            }
        }
    }

    private static void scanNetwork(Map<ItemRef, MutableCost> network, Map<ItemRef, MutableCost> all) {
        try {
            StorageNetworkIntegration.StorageSnapshot snapshot = StorageNetworkIntegration.snapshot();
            for (StorageNetworkIntegration.StoredStack stored : snapshot.stacks()) {
                ItemStack stack = stored.stack();
                long amount = stored.amount();
                if (stack == null || stack.isEmpty() || amount <= 0) {
                    continue;
                }
                addSupply(all, stack, amount);
                addSupply(network, stack, amount);
            }
        } catch (RuntimeException error) {
            warnOnce("networkSupply", "network supply scan failed, treated as empty: {}", error);
        }
    }

    private static void warnOnce(String key, String message, Object detail) {
        if (!WARNED_FAILURES.add(key)) {
            return;
        }
        JERTLog.warn(message, String.valueOf(detail));
    }

    private static boolean countsTowardContainerSupply(Slot slot) {
        if (slot instanceof ResultSlot) {
            return false;
        }
        if (slot.container instanceof CraftingContainer) {
            return false;
        }

        if (isCraftingGridSlot(slot)) {
            return false;
        }

        if (isAe2WrappedPlayerInventory(slot)) {
            return false;
        }

        if (isVirtualPreviewSlot(slot)) {
            return false;
        }

        if (isBeyondNetworkSlot(slot)) {
            return false;
        }
        return !isAe2FunctionalSlot(slot);
    }

    private static boolean isBeyondNetworkSlot(Slot slot) {
        String name = slot.getClass().getName();
        return name.contains("beyonddimensions") && name.contains("TypedSlot");
    }

    private static boolean isAe2WrappedPlayerInventory(Slot slot) {
        Object container = slot.container;
        if (container == null) {
            return false;
        }
        for (Class<?> type = container.getClass(); type != null; type = type.getSuperclass()) {
            if ("appeng.util.inv.PlayerInternalInventory".equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isVirtualPreviewSlot(Slot slot) {
        for (Class<?> type = slot.getClass(); type != null; type = type.getSuperclass()) {
            if ("appeng.menu.slot.FakeSlot".equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCraftingGridSlot(Slot slot) {
        for (Class<?> type = slot.getClass(); type != null; type = type.getSuperclass()) {
            if ("appeng.menu.slot.CraftingMatrixSlot".equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAe2FunctionalSlot(Slot slot) {
        for (Class<?> type = slot.getClass(); type != null; type = type.getSuperclass()) {
            if ("appeng.menu.slot.RestrictedInputSlot".equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    private static void addAmount(Map<ItemRef, Long> map, ItemStack stack, long units) {
        if (stack == null || stack.isEmpty() || units <= 0) {
            return;
        }
        long amount = mapAmount(stack, units);
        if (amount > 0) {
            map.merge(ItemKeys.of(stack), amount, Amounts::safeAdd);
        }
        if (FluidRecipeCompat.fluidKey(stack).isEmpty()) {
            FluidRecipeCompat.fluidRepresentation(stack).ifPresent(fluid -> {
                long fluidAmount = mapAmount(fluid, units);
                if (fluidAmount > 0) {
                    map.merge(ItemKeys.of(fluid), fluidAmount, Amounts::safeAdd);
                }
            });
        }
    }

    static void add(Map<ItemRef, MutableCost> map, ItemStack stack, long amount) {
        if (stack == null || stack.isEmpty() || amount <= 0) {
            return;
        }
        ItemRef key = ItemKeys.of(stack);
        MutableCost cost = map.get(key);
        if (cost == null) {
            map.put(key, new MutableCost(stack, amount));
        } else {
            cost.amount = Amounts.safeAdd(cost.amount, amount);
        }
    }

    static void addSupply(Map<ItemRef, MutableCost> map, ItemStack stack, long units) {
        add(map, stack, mapAmount(stack, units));

        if (FluidRecipeCompat.fluidKey(stack).isEmpty()) {
            FluidRecipeCompat.fluidRepresentation(stack).ifPresent(fluid ->
                add(map, fluid, mapAmount(fluid, units))
            );
        }
    }

    static long mapAmount(ItemStack stack, long units) {
        if (stack == null || stack.isEmpty() || units <= 0) {
            return 0L;
        }
        return units;
    }

    private static Map<String, Long> legacyView(Map<ItemRef, Long> source) {
        if (source.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> legacy = new LinkedHashMap<>();
        source.forEach((ref, amount) -> legacy.put(ItemKeys.legacy(ref), amount));
        return Map.copyOf(legacy);
    }

    private static Map<ItemRef, MutableCost> freezeInventory(Map<ItemRef, MutableCost> source) {
        if (source.isEmpty()) {
            return Map.of();
        }
        Map<ItemRef, MutableCost> frozen = new LinkedHashMap<>();
        for (Map.Entry<ItemRef, MutableCost> entry : source.entrySet()) {
            MutableCost value = entry.getValue();
            frozen.put(entry.getKey(), new MutableCost(value.stack, value.alternatives, value.amount));
        }
        return Map.copyOf(frozen);
    }

    static Map<ItemRef, MutableCost> copyInventory(Map<ItemRef, MutableCost> source) {
        if (source.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<ItemRef, MutableCost> copy = new LinkedHashMap<>();
        for (Map.Entry<ItemRef, MutableCost> entry : source.entrySet()) {
            MutableCost value = entry.getValue();
            copy.put(entry.getKey(), new MutableCost(value.stack, value.alternatives, value.amount));
        }
        return copy;
    }

    private static <K> Map<K, Long> mergeAmounts(Map<K, Long> first, Map<K, Long> second) {
        if (second.isEmpty()) {
            return first;
        }
        Map<K, Long> merged = new LinkedHashMap<>(first);
        second.forEach((key, value) -> merged.merge(key, value, Amounts::safeAdd));
        return Map.copyOf(merged);
    }
}
