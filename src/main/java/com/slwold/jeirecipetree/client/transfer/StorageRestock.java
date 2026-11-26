package com.slwold.jeirecipetree.client.transfer;

import com.slwold.jeirecipetree.client.data.*;
import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;
import com.slwold.jeirecipetree.client.storage.*;

import com.slwold.jeirecipetree.compat.sia.SiaMaterialBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class StorageRestock {

    record Need(ItemStack stack, String key, int amount, int target, List<ItemStack> candidates) {
        Need {
            stack = stack == null ? ItemStack.EMPTY : stack.copy();
            key = key == null ? "" : key;
            amount = Math.max(0, amount);
            target = Math.max(0, target);
            List<ItemStack> safe = new ArrayList<>();
            if (candidates != null) {
                for (ItemStack candidate : candidates) {
                    if (candidate == null || candidate.isEmpty()) {
                        continue;
                    }
                    safe.add(candidate.copy());
                }
            }
            if (safe.isEmpty() && !stack.isEmpty()) {
                safe.add(stack.copy());
            }
            candidates = List.copyOf(safe);
        }

        List<String> candidateKeys() {
            List<String> keys = new ArrayList<>(candidates.size());
            for (ItemStack candidate : candidates) {
                keys.add(RecipeTreeData.ingredientKey(candidate));
            }
            return keys;
        }
    }

    private enum Step {
        WAIT, CONTINUE, DONE, FAILED
    }

    private static final int WAIT_FRAMES = 60;

    private static final int MAX_ATTEMPTS = 2;

    private static final int SIA_WAIT_FRAMES = 30;

    private static final int MAX_PLACE_CLICKS_PER_TICK = 8;

    private static final int MAX_STEPS_PER_TICK = 8;

    private static final ArrayDeque<Need> QUEUE = new ArrayDeque<>();

    private static int totalItems;
    private static long takenTotal;
    private static int skippedTotal;

    private static Need current;
    private static int remaining;

    private static boolean awaitingCursor;
    private static int frames;

    private static int depositFrames;
    private static boolean running;

    private static boolean viaSia;

    private static final List<Need> SIA_NEEDS = new ArrayList<>();

    record Short(ItemStack stack, String key, int missing, int need, long inventory, long network) {
    }

    private static final List<Short> SHORTFALL = new ArrayList<>();

    private static final Map<String, Integer> ATTEMPTS = new LinkedHashMap<>();

    private static List<Short> lastShortfall = List.of();
    private static String lastFailure = "";

    private StorageRestock() {
    }

    static boolean running() {
        return running;
    }

    static String lastFailure() {
        return lastFailure;
    }

    static boolean begin(AbstractContainerMenu menu, List<Need> needs) {
        cancel();
        if (menu == null || needs == null || needs.isEmpty()) {
            return false;
        }
        List<Need> usable = new ArrayList<>(needs.size());
        for (Need need : needs) {
            if (need != null && need.amount() > 0 && !need.stack().isEmpty()) {
                usable.add(need);
            }
        }
        if (usable.isEmpty()) {
            return false;
        }
        if (StorageDriverRegistry.canWithdraw(menu)) {
            return beginCursor(menu, usable);
        }
        return beginSia(usable);
    }

    private static boolean beginCursor(AbstractContainerMenu menu, List<Need> usable) {
        if (!menu.getCarried().isEmpty()) {
            JERTLog.debug("restock: cursor is not empty, skipped");
            return false;
        }
        viaSia = false;
        totalItems = usable.size();
        takenTotal = 0L;
        skippedTotal = 0;
        QUEUE.addAll(usable);
        running = true;
        lastFailure = "";
        JERTLog.debug("restock: begin (terminal) with {} material(s): {}", usable.size(), summary(usable));
        return true;
    }

    private static boolean beginSia(List<Need> usable) {
        if (!SafeConfig.sophisticatedRestockEnabled() || !SiaMaterialBridge.available()) {
            JERTLog.debug("restock: no withdraw-capable driver and SIA unavailable");
            return false;
        }
        Player player = Minecraft.getInstance().player;
        List<List<ItemStack>> groups = new ArrayList<>(usable.size());
        SIA_NEEDS.clear();
        for (Need need : usable) {
            int target = Math.max(1, need.target());
            List<ItemStack> group = new ArrayList<>(need.candidates().size());
            for (ItemStack candidate : need.candidates()) {
                ItemStack wanted = candidate.copy();

                wanted.setCount(target);
                group.add(wanted);
            }
            groups.add(group);
            SIA_NEEDS.add(new Need(need.stack(), need.key(), need.amount(), target, group));
        }
        SiaMaterialBridge.Result result = SiaMaterialBridge.restock(player, groups);
        if (result == SiaMaterialBridge.Result.UNAVAILABLE) {
            JERTLog.debug("restock: SIA rejected the request ({})", result);
            SIA_NEEDS.clear();
            return false;
        }
        viaSia = true;
        running = true;
        totalItems = usable.size();
        takenTotal = 0L;
        skippedTotal = 0;
        frames = 0;
        lastFailure = "";
        JERTLog.debug("restock: begin (SIA, {}) with {} material(s): {}",
            result, usable.size(), summary(usable));
        return true;
    }

    static void cancel() {
        if (running) {
            JERTLog.debug("restock: cancelled ({})", describe());
        }
        reset();
    }

    static Boolean tick(AbstractContainerMenu menu, Minecraft minecraft) {
        if (!running) {
            return Boolean.TRUE;
        }
        Player player = minecraft == null ? null : minecraft.player;
        if (menu == null || player == null || minecraft.gameMode == null) {
            return fail(menu, "no container/player");
        }
        Step step = viaSia ? tickSia(menu, player) : runCursorSteps(menu, player, minecraft);
        if (step == Step.DONE) {

            return SHORTFALL.isEmpty();
        }
        if (step == Step.FAILED) {
            return Boolean.FALSE;
        }
        return null;
    }

    static List<Short> shortfall() {
        return lastShortfall;
    }

    private static Step runCursorSteps(AbstractContainerMenu menu, Player player, Minecraft minecraft) {
        for (int guard = 0; guard < MAX_STEPS_PER_TICK; guard++) {
            Step step = stepOnce(menu, player, minecraft);
            if (step != Step.CONTINUE) {
                return step;
            }
        }
        return Step.WAIT;
    }

    private static Step tickSia(AbstractContainerMenu menu, Player player) {
        if (satisfied(menu, player)) {
            JERTLog.debug("restock: SIA restock satisfied");
            reset();
            return Step.DONE;
        }
        if (++frames > SIA_WAIT_FRAMES) {

            recordSiaShortfall(menu, player);
            return failStep("SIA restock timed out");
        }
        return Step.WAIT;
    }

    private static void recordSiaShortfall(AbstractContainerMenu menu, Player player) {
        Map<String, Long> have = counts(menu, player);
        for (Need need : SIA_NEEDS) {
            long owned = groupOwned(need, have);
            long missing = need.target() - owned;
            if (missing <= 0) {
                continue;
            }
            SHORTFALL.add(new Short(
                need.stack(),
                need.key(),
                (int) Math.min(Integer.MAX_VALUE, missing),
                need.target(),
                owned,
                -1L
            ));
        }
    }

    private static boolean satisfied(AbstractContainerMenu menu, Player player) {
        if (SIA_NEEDS.isEmpty()) {
            return true;
        }
        Map<String, Long> have = counts(menu, player);
        for (Need need : SIA_NEEDS) {
            if (groupOwned(need, have) < need.target()) {
                return false;
            }
        }
        return true;
    }

    private static long groupOwned(Need need, Map<String, Long> have) {
        long total = 0L;
        for (ItemStack candidate : need.candidates()) {
            total += have.getOrDefault(RecipeTreeData.ingredientKey(candidate), 0L);
        }
        return total;
    }

    private static Step stepOnce(AbstractContainerMenu menu, Player player, Minecraft minecraft) {
        if (current == null) {
            Step picked = pickNext(menu, player, minecraft);
            if (picked != Step.CONTINUE) {
                return picked;
            }
        }
        ItemStack carried = menu.getCarried();
        return carried.isEmpty() ? request(menu, player) : place(menu, player, minecraft, carried);
    }

    private static Step pickNext(AbstractContainerMenu menu, Player player, Minecraft minecraft) {
        if (!menu.getCarried().isEmpty()) {

            if (depositFrames == 0) {
                if (depositOrStash(menu, player, minecraft)) {
                    return Step.CONTINUE;
                }
                depositFrames = 1;
                return Step.WAIT;
            }
            if (++depositFrames > WAIT_FRAMES) {
                return failStep("cursor_timeout between materials");
            }
            return Step.WAIT;
        }
        depositFrames = 0;
        current = QUEUE.poll();
        if (current == null) {

            lastShortfall = List.copyOf(SHORTFALL);
            JERTLog.debug("restock done taken={} skipped={} items={} short={}",
                takenTotal, skippedTotal, totalItems, SHORTFALL.size());
            reset();
            return Step.DONE;
        }
        remaining = current.amount();
        awaitingCursor = false;
        frames = 0;
        return Step.CONTINUE;
    }

    private static Step request(AbstractContainerMenu menu, Player player) {
        if (remaining <= 0) {
            current = null;
            return Step.CONTINUE;
        }
        if (awaitingCursor) {
            return ++frames > WAIT_FRAMES
                ? noteFailure(menu, player, "cursor_timeout")
                : Step.WAIT;
        }
        if (!StorageDriverRegistry.requestWithdraw(menu, current.stack(), remaining)) {
            return noteFailure(menu, player, "withdraw_rejected");
        }
        awaitingCursor = true;
        frames = 0;
        return Step.WAIT;
    }

    private static Step place(
        AbstractContainerMenu menu,
        Player player,
        Minecraft minecraft,
        ItemStack carried
    ) {
        awaitingCursor = false;
        frames = 0;
        if (!ItemStack.isSameItemSameComponents(carried, current.stack())) {
            depositOrStash(menu, player, minecraft);
            return noteFailure(menu, player, "unexpected_item");
        }
        int carriedCount = carried.getCount();
        int take = Math.min(carriedCount, remaining);
        boolean wholeStack = take >= carriedCount;
        int chunk = wholeStack ? take : Math.min(take, MAX_PLACE_CLICKS_PER_TICK);
        int slot = findDestination(menu, player, current.stack(), chunk);
        if (slot < 0) {
            depositOrStash(menu, player, minecraft);
            return noteFailure(menu, player, "no_room");
        }
        placeInto(minecraft, menu, slot, chunk, wholeStack);
        takenTotal += chunk;
        remaining -= chunk;
        if (remaining <= 0) {

            if (!menu.getCarried().isEmpty()) {
                depositOrStash(menu, player, minecraft);
            }
            current = null;
        }
        return Step.WAIT;
    }

    private static void placeInto(
        Minecraft minecraft,
        AbstractContainerMenu menu,
        int slot,
        int chunk,
        boolean wholeStack
    ) {
        if (wholeStack) {
            click(minecraft, menu, slot, 0);
            return;
        }

        for (int index = 0; index < chunk; index++) {
            click(minecraft, menu, slot, 1);
        }
    }

    private static void click(Minecraft minecraft, AbstractContainerMenu menu, int slot, int button) {
        minecraft.gameMode.handleInventoryMouseClick(
            menu.containerId,
            slot,
            button,
            ClickType.PICKUP,
            minecraft.player
        );
    }

    private static int findDestination(
        AbstractContainerMenu menu,
        Player player,
        ItemStack stack,
        int needed
    ) {
        if (needed <= 0) {
            return -1;
        }
        int empty = -1;
        for (Slot slot : menu.slots) {
            if (!isPlayerInventory(slot, player) || !slot.mayPlace(stack)) {
                continue;
            }
            ItemStack existing = slot.getItem();
            int limit = Math.min(slot.getMaxStackSize(), stack.getMaxStackSize());
            if (existing.isEmpty()) {
                if (empty < 0 && limit >= needed) {
                    empty = slot.index;
                }
                continue;
            }
            if (!ItemStack.isSameItemSameComponents(existing, stack)) {
                continue;
            }
            if (limit - existing.getCount() >= needed) {

                return slot.index;
            }
        }
        return empty;
    }

    static boolean depositOrStash(AbstractContainerMenu menu, Player player, Minecraft minecraft) {
        if (menu == null || menu.getCarried().isEmpty()) {
            return true;
        }
        if (StorageDriverRegistry.depositCarried(menu)) {
            return false;
        }
        JERTLog.debug("restock: network deposit unsupported, stashing cursor into inventory");
        return placeCarriedInInventory(menu, player, minecraft);
    }

    static boolean placeCarriedInInventory(
        AbstractContainerMenu menu,
        Player player,
        Minecraft minecraft
    ) {
        if (menu == null) {
            return false;
        }
        if (menu.getCarried().isEmpty()) {
            return true;
        }
        if (minecraft == null || minecraft.gameMode == null || player == null) {
            return false;
        }
        for (int guard = 0; guard < MAX_PLACE_CLICKS_PER_TICK && !menu.getCarried().isEmpty(); guard++) {
            int slot = findAnyDestination(menu, player, menu.getCarried());
            if (slot < 0) {
                break;
            }
            click(minecraft, menu, slot, 0);
        }
        return menu.getCarried().isEmpty();
    }

    private static int findAnyDestination(AbstractContainerMenu menu, Player player, ItemStack stack) {
        int empty = -1;
        for (Slot slot : menu.slots) {
            if (!isPlayerInventory(slot, player) || !slot.mayPlace(stack)) {
                continue;
            }
            ItemStack existing = slot.getItem();
            int limit = Math.min(slot.getMaxStackSize(), stack.getMaxStackSize());
            if (existing.isEmpty()) {
                if (empty < 0) {
                    empty = slot.index;
                }
                continue;
            }
            if (ItemStack.isSameItemSameComponents(existing, stack) && existing.getCount() < limit) {
                return slot.index;
            }
        }
        return empty;
    }

    static long roomFor(AbstractContainerMenu menu, Player player, ItemStack sample) {
        if (menu == null || player == null || sample == null || sample.isEmpty()) {
            return 0L;
        }
        long room = 0L;
        for (Slot slot : menu.slots) {
            if (!isPlayerInventory(slot, player) || !slot.mayPlace(sample)) {
                continue;
            }
            ItemStack existing = slot.getItem();
            int limit = Math.min(slot.getMaxStackSize(), sample.getMaxStackSize());
            if (existing.isEmpty()) {
                room += limit;
            } else if (ItemStack.isSameItemSameComponents(existing, sample)) {
                room += Math.max(0, limit - existing.getCount());
            }
        }
        return room;
    }

    static Map<String, Long> counts(AbstractContainerMenu menu, Player player) {
        Map<String, Long> amounts = new LinkedHashMap<>();
        for (Slot slot : menu.slots) {
            if (!isPlayerInventory(slot, player)) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            amounts.merge(RecipeTreeData.ingredientKey(stack), (long) stack.getCount(), Long::sum);
        }
        return amounts;
    }

    static boolean isPlayerInventory(Slot slot, Player player) {
        if (slot == null || slot.container == null) {
            return false;
        }
        if (slot.container == player.getInventory()) {
            return true;
        }
        return slot.container.getClass().getName().endsWith("PlayerInternalInventory");
    }

    private static Boolean fail(AbstractContainerMenu menu, String reason) {
        if (menu != null && !menu.getCarried().isEmpty()) {

            Minecraft minecraft = Minecraft.getInstance();
            depositOrStash(menu, minecraft == null ? null : minecraft.player, minecraft);
        }
        lastFailure = reason;
        skippedTotal++;
        JERTLog.debug("restock: failed ({}) | {}", reason, describe());
        reset();
        return Boolean.FALSE;
    }

    private static Step failStep(String reason) {
        fail(null, reason);
        return Step.FAILED;
    }

    private static Step noteFailure(AbstractContainerMenu menu, Player player, String reason) {
        Need failed = current;
        int missing = Math.max(0, remaining);
        current = null;
        remaining = 0;
        awaitingCursor = false;
        frames = 0;
        if (failed == null) {
            return Step.CONTINUE;
        }
        int attempts = ATTEMPTS.merge(failed.key(), 1, Integer::sum);
        if (attempts < MAX_ATTEMPTS) {
            JERTLog.debug("restock: retry {} after {} (attempt {}/{})",
                failed.key(), reason, attempts + 1, MAX_ATTEMPTS);
            QUEUE.addLast(failed);
            return Step.CONTINUE;
        }
        long inventory = counts(menu, player).getOrDefault(failed.key(), 0L);
        long network = networkAmount(menu, failed.key());
        SHORTFALL.add(new Short(
            failed.stack(),
            failed.key(),
            missing <= 0 ? failed.amount() : missing,
            failed.amount(),
            inventory,
            network
        ));
        skippedTotal++;
        JERTLog.warn("restock stop short: need={} have={} network={} item={} reason={}",
            failed.amount(), inventory, network, failed.key(), reason);
        return Step.CONTINUE;
    }

    private static long networkAmount(AbstractContainerMenu menu, String key) {
        try {
            StorageNetworkIntegration.StorageSnapshot snapshot = StorageDriverRegistry.read(menu);
            if (snapshot == null) {
                return -1L;
            }
            long total = 0L;
            for (StorageNetworkIntegration.StoredStack stored : snapshot.stacks()) {
                if (stored == null || stored.stack() == null || stored.stack().isEmpty()) {
                    continue;
                }
                if (key.equals(RecipeTreeData.ingredientKey(stored.stack()))) {
                    total += stored.amount();
                }
            }
            return total;
        } catch (LinkageError | RuntimeException ignored) {
            return -1L;
        }
    }

    private static void reset() {
        lastShortfall = List.copyOf(SHORTFALL);
        QUEUE.clear();
        SIA_NEEDS.clear();
        SHORTFALL.clear();
        ATTEMPTS.clear();
        current = null;
        remaining = 0;
        awaitingCursor = false;
        frames = 0;
        depositFrames = 0;
        viaSia = false;
        running = false;
    }

    static String describe() {
        return "running=" + running
            + " via=" + (viaSia ? "sia" : "cursor")
            + " current=" + (current == null ? "-" : current.key())
            + " remaining=" + remaining
            + " awaiting=" + awaitingCursor
            + " queue=" + QUEUE.size();
    }

    private static String summary(List<Need> needs) {
        StringBuilder builder = new StringBuilder();
        for (Need need : needs) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(need.key()).append(" x").append(need.amount());
            if (need.candidates().size() > 1) {
                builder.append(" (group=").append(need.candidates().size()).append(")");
            }
        }
        return builder.toString();
    }
}
