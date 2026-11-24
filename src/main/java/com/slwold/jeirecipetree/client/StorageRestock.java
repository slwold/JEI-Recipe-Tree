package com.slwold.jeirecipetree.client;

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

    record Need(ItemStack stack, String key, int amount, int target) {
        Need {
            stack = stack == null ? ItemStack.EMPTY : stack.copy();
            key = key == null ? "" : key;
            amount = Math.max(0, amount);
            target = Math.max(0, target);
        }
    }

    private enum Step {
        WAIT, CONTINUE, DONE, FAILED
    }

    private static final int WAIT_FRAMES = 40;

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
    private static boolean running;

    private static boolean viaSia;

    private static final Map<String, Integer> SIA_TARGETS = new LinkedHashMap<>();
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
        SIA_TARGETS.clear();
        for (Need need : usable) {
            ItemStack wanted = need.stack().copy();
            wanted.setCount(Math.max(1, need.target()));
            groups.add(List.of(wanted));
            SIA_TARGETS.put(need.key(), Math.max(1, need.target()));
        }
        SiaMaterialBridge.Result result = SiaMaterialBridge.restock(player, groups);
        if (result == SiaMaterialBridge.Result.UNAVAILABLE) {
            JERTLog.debug("restock: SIA rejected the request ({})", result);
            SIA_TARGETS.clear();
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
            return Boolean.TRUE;
        }
        if (step == Step.FAILED) {
            return Boolean.FALSE;
        }
        return null;
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
            return failStep("SIA restock timed out");
        }
        return Step.WAIT;
    }

    private static boolean satisfied(AbstractContainerMenu menu, Player player) {
        if (SIA_TARGETS.isEmpty()) {
            return true;
        }
        Map<String, Long> have = counts(menu, player);
        for (Map.Entry<String, Integer> entry : SIA_TARGETS.entrySet()) {
            if (have.getOrDefault(entry.getKey(), 0L) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static Step stepOnce(AbstractContainerMenu menu, Player player, Minecraft minecraft) {
        if (current == null) {
            Step picked = pickNext(menu, player, minecraft);
            if (picked != Step.CONTINUE) {
                return picked;
            }
        }
        ItemStack carried = menu.getCarried();
        return carried.isEmpty() ? request(menu) : place(menu, player, minecraft, carried);
    }

    private static Step pickNext(AbstractContainerMenu menu, Player player, Minecraft minecraft) {
        if (!menu.getCarried().isEmpty()) {
            depositOrStash(menu, player, minecraft);
            return failStep("cursor not empty between materials");
        }
        current = QUEUE.poll();
        if (current == null) {

            JERTLog.debug("restock done taken={} skipped={} items={}",
                takenTotal, skippedTotal, totalItems);
            reset();
            return Step.DONE;
        }
        remaining = current.amount();
        awaitingCursor = false;
        frames = 0;
        return Step.CONTINUE;
    }

    private static Step request(AbstractContainerMenu menu) {
        if (remaining <= 0) {
            current = null;
            return Step.CONTINUE;
        }
        if (awaitingCursor) {
            return ++frames > WAIT_FRAMES ? failStep("cursor never received " + current.key()) : Step.WAIT;
        }
        if (!StorageDriverRegistry.requestWithdraw(menu, current.stack(), remaining)) {
            return failStep("withdraw request rejected for " + current.key());
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
            return failStep("unexpected item on cursor: " + carried.getHoverName().getString());
        }
        int carriedCount = carried.getCount();
        int take = Math.min(carriedCount, remaining);
        boolean wholeStack = take >= carriedCount;
        int chunk = wholeStack ? take : Math.min(take, MAX_PLACE_CLICKS_PER_TICK);
        int slot = findDestination(menu, player, current.stack(), chunk);
        if (slot < 0) {
            depositOrStash(menu, player, minecraft);
            return failStep("no inventory space for " + current.key());
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

    private static void reset() {
        QUEUE.clear();
        SIA_TARGETS.clear();
        current = null;
        remaining = 0;
        awaitingCursor = false;
        frames = 0;
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
        }
        return builder.toString();
    }
}
