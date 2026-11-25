package com.slwold.jeirecipetree.client;

import com.slwold.jeirecipetree.compat.sia.SiaMaterialBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class MaterialFetch {

    private static final int MAX_GROUPS_PER_PACKET = 64;

    private static final int MAX_TARGET_STACKS = 36;
    private static final long MIN_INTERVAL_NANOS = 500_000_000L;

    private static final ArrayDeque<List<List<ItemStack>>> PENDING = new ArrayDeque<>();
    private static long lastRequestNanos = Long.MIN_VALUE;

    public enum Outcome { SENT, NOTHING_TO_FETCH, DISABLED, UNAVAILABLE, NO_SESSION, BUSY, FAILED }

    private MaterialFetch() {
    }

    public static boolean isAvailable() {
        if (!SafeConfig.sophisticatedRestockEnabled()) {
            return false;
        }
        return SiaMaterialBridge.available() && RecipeTreeSession.craftingTree() != null;
    }

    public static Outcome request() {
        if (!SafeConfig.sophisticatedRestockEnabled()) {
            return Outcome.DISABLED;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return Outcome.NO_SESSION;
        }
        RecipeTreeData.Tree tree = RecipeTreeSession.craftingTree();
        if (tree == null || !tree.craftingMode() || tree.root() == null) {

            notifyPlayer(player, "message.jeirecipetree.fetch.no_session");
            return Outcome.NO_SESSION;
        }
        if (!SiaMaterialBridge.available()) {
            notifyPlayer(player, "message.jeirecipetree.fetch.unavailable");
            return Outcome.UNAVAILABLE;
        }
        long now = System.nanoTime();
        if (!PENDING.isEmpty()) {
            return Outcome.BUSY;
        }
        if (lastRequestNanos != Long.MIN_VALUE && now - lastRequestNanos >= 0L
            && now - lastRequestNanos < MIN_INTERVAL_NANOS) {
            return Outcome.BUSY;
        }
        lastRequestNanos = now;
        try {
            RecipeTreeData.Analysis analysis = tree.analyze();
            List<RecipeTreeData.Cost> costs = analysis == null ? List.of() : analysis.costs();
            List<List<ItemStack>> groups = buildGroups(costs, SupplyLedger.playerAmounts());
            if (groups.isEmpty()) {
                notifyPlayer(player, "message.jeirecipetree.fetch.nothing");
                JERTLog.debug("fetch: nothing to fetch ({} costs, all supplied in player inventory)",
                    costs.size());
                return Outcome.NOTHING_TO_FETCH;
            }
            PENDING.addAll(batches(groups));
            JERTLog.debug("fetch: {} groups in {} packet(s)", groups.size(), PENDING.size());
            tick();
            return Outcome.SENT;
        } catch (RuntimeException exception) {
            JERTLog.warn("fetch: analysis failed, nothing requested: {}", exception.toString());
            return Outcome.FAILED;
        }
    }

    public static void tick() {
        if (PENDING.isEmpty()) {
            return;
        }
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            PENDING.clear();
            return;
        }
        List<List<ItemStack>> batch = PENDING.pollFirst();
        SiaMaterialBridge.Result result = SiaMaterialBridge.restock(player, batch);
        if (result == SiaMaterialBridge.Result.UNAVAILABLE) {
            int dropped = PENDING.size() + 1;
            PENDING.clear();
            JERTLog.warn("fetch: SIA unavailable, dropped {} remaining batch(es)", dropped);
        }
    }

    public static int pendingBatches() {
        return PENDING.size();
    }

    static List<List<ItemStack>> buildGroups(List<RecipeTreeData.Cost> costs, Map<String, Long> playerAmounts) {
        List<List<ItemStack>> groups = new ArrayList<>();
        if (costs == null || costs.isEmpty()) {
            return groups;
        }
        for (RecipeTreeData.Cost cost : costs) {
            if (cost == null) {
                continue;
            }
            ItemStack primary = cost.stack();
            if (primary == null || primary.isEmpty()) {
                continue;
            }

            long missing = cost.required();
            if (missing <= 0) {
                continue;
            }
            List<ItemStack> alternatives = cost.alternatives() == null ? List.of() : cost.alternatives();
            long owned = SupplyLedger.inventoryAmount(alternatives, playerAmounts);
            long required = missing + owned;
            int maxStack = Math.max(1, primary.getMaxStackSize());
            long target = Math.min(required, (long) MAX_TARGET_STACKS * maxStack);
            int count = (int) Math.min(target, Integer.MAX_VALUE);
            List<ItemStack> group = new ArrayList<>(alternatives.size() + 1);
            group.add(primary.copyWithCount(count));
            for (ItemStack alternative : alternatives) {
                if (alternative == null || alternative.isEmpty()
                    || ItemStack.isSameItemSameComponents(alternative, primary)) {
                    continue;
                }
                group.add(alternative.copyWithCount(count));
            }
            groups.add(group);
        }
        return groups;
    }

    static List<List<List<ItemStack>>> batches(List<List<ItemStack>> groups) {
        List<List<List<ItemStack>>> batches = new ArrayList<>();
        if (groups == null || groups.isEmpty()) {
            return batches;
        }
        for (int start = 0; start < groups.size(); start += MAX_GROUPS_PER_PACKET) {
            int end = Math.min(start + MAX_GROUPS_PER_PACKET, groups.size());
            batches.add(List.copyOf(groups.subList(start, end)));
        }
        return batches;
    }

    private static void notifyPlayer(Player player, String translationKey) {
        try {
            player.displayClientMessage(Component.translatable(translationKey), true);
        } catch (RuntimeException ignored) {

        }
    }
}
