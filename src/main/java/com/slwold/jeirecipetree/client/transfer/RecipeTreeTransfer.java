package com.slwold.jeirecipetree.client.transfer;

import com.slwold.jeirecipetree.client.data.*;
import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;
import com.slwold.jeirecipetree.client.storage.*;

import com.slwold.jeirecipetree.core.CraftQueue;
import com.slwold.jeirecipetree.core.TransferPhase;
import com.slwold.jeirecipetree.compat.sia.SiaMaterialBridge;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferInfo;
import mezz.jei.api.recipe.transfer.IRecipeTransferManager;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.common.Internal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.tags.TagKey;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class RecipeTreeTransfer {

    static final int MAX_RESTOCK_GROUP = 16;
    static final int AE2_WAIT_FRAMES = 600;

    static final int AE2_DEPOSIT_WAIT_FRAMES = 40;

    private static final int GENERIC_RESULT_WAIT_FRAMES = 40;

    static final int RTS_PICKUP_WAIT_FRAMES = 20;

    private static CraftQueue<RecipeTreeData.CraftStep> queue = CraftQueue.create();
    private static final TransferStepState step = new TransferStepState();

    private static int perItemTotal;
    private static int perItemDone;

    private static List<RecipeTreeData.CraftStep> perItemPlan = List.of();

    private static final int MAX_PER_ITEM_OUTPUTS = 4096;

    static long sessionStartNanos;

    static String lastFillState = "";

    private RecipeTreeTransfer() {
    }

    static TransferStepState step() {
        return step;
    }

    static CraftQueue<RecipeTreeData.CraftStep> queue() {
        return queue;
    }

    public static void cancel() {
        clearPerItem();
        clearPending();
    }

    public static String queueStatus() {
        return String.format(java.util.Locale.ROOT,
            "active=%s index=%d/%d remaining=%s loaded=%d chunk=%d resultSlot=%d",
            queue.active(), queue.index(), queue.size(),
            queue.hasCurrent() ? queue.current().remaining() : "-",
            queue.loadedBatches(), queue.chunk(), queue.craftResultSlot());
    }

    public static boolean canTransfer(RecipeTreeData.CraftStep step, boolean recursive) {
        RecipeTreeData.CraftStep first = firstStep(step, recursive);
        if (first == null) {
            JERTLog.debug("canTransfer -> no craft step (row is not a craftable node)");
            return false;
        }
        int chunk = TransferFillPlan.transferChunk(first, first.batches(), recursive);
        if (chunk <= 0) {
            JERTLog.debug("canTransfer -> nothing to transfer (chunk=0, batches={})", first.batches());
            return false;
        }
        boolean ok = runTransfer(first, chunk, false, recursive, false);
        if (!ok) {
            JERTLog.debug("canTransfer -> dry run failed (chunk={})", chunk);
        }
        return ok;
    }

    public static boolean transfer(RecipeTreeData.CraftStep step, boolean recursive) {
        return transfer(step, recursive, false);
    }

    public static boolean transfer(
        RecipeTreeData.CraftStep step,
        boolean recursive,
        boolean maxTransfer
    ) {
        clearPending();
        if (!recursive) {
            if (!TransferJeiBridge.exposeParentContainer()) {
                return false;
            }
            int chunk = TransferFillPlan.transferChunk(step, step == null ? 0 : step.batches(), false);
            AbstractContainerScreen<?> parent = TransferJeiBridge.containerScreen();
            AbstractContainerMenu parentMenu = parent == null ? null : parent.getMenu();
            chunk = TransferRestockPlanner.inventoryBoundChunk(
                step,
                chunk,
                parentMenu,
                Minecraft.getInstance().player
            );
            TransferPickupTicks.clearCursorBeforeTransfer(parentMenu);
            return chunk > 0 && runTransfer(step, chunk, true, false, maxTransfer);
        }

        RecipeTreeData.Tree tree = RecipeTreeSession.craftingTree();
        if (tree == null) {
            return false;
        }
        List<RecipeTreeData.CraftStep> plan = tree.recursiveCraftingSteps(step);
        if (plan.isEmpty()) {
            return false;
        }
        if (!TransferJeiBridge.exposeParentContainer()) {
            clearPending("exposeParentContainer failed");
            return false;
        }

        if (step.batches() > 1
            && SafeConfig.perItemCraftingEnabled()
            && perItemCrafting(TransferJeiBridge.containerScreen().getMenu())) {
            return startPerItem(tree, step);
        }
        queue = CraftQueue.of(plan, RecipeTreeData.CraftStep::batches);
        sessionStartNanos = System.nanoTime();
        lastFillState = "";
        JERTLog.info("transfer start origin=tree_row target={} want={} steps={} maxTransfer={}",
            RecipeTreeData.ingredientKey(step.stack()),
            step.total(),
            queue.size(),
            maxTransfer);
        return startNextCraft();
    }

    private static boolean perItemCrafting(AbstractContainerMenu menu) {
        if (menu == null) {
            return false;
        }
        if (Ae2StorageIntegration.isCraftingMenu(menu)) {
            return false;
        }
        if (RtsStorageDriver.isTerminalMenu(menu)) {
            return false;
        }
        return menu instanceof CraftingMenu || menu instanceof InventoryMenu;
    }

    private static boolean startPerItem(RecipeTreeData.Tree tree, RecipeTreeData.CraftStep root) {
        int total = (int) Math.min(root.batches(), MAX_PER_ITEM_OUTPUTS);
        if (total <= 1) {
            return false;
        }
        List<RecipeTreeData.CraftStep> firstPlan = tree.recursiveCraftingSteps(withSingleBatch(root));
        if (firstPlan.isEmpty()) {
            return stopPerItem("per-item plan empty");
        }
        perItemTotal = total;
        perItemDone = 0;
        perItemPlan = List.copyOf(firstPlan);
        JERTLog.info("transfer start origin=tree_row target={} want={} items={} per_item=true",
            RecipeTreeData.ingredientKey(root.stack()), root.total(), total);
        return startNextItem();
    }

    private static boolean startNextItem() {
        if (perItemPlan.isEmpty()) {
            return stopPerItem("per-item plan empty");
        }
        queue = CraftQueue.of(perItemPlan, RecipeTreeData.CraftStep::batches);
        sessionStartNanos = System.nanoTime();
        lastFillState = "";
        notifyPerItemProgress();
        return startNextCraft();
    }

    private static boolean continuePerItem() {
        if (perItemTotal <= 0) {
            return false;
        }
        perItemDone++;
        if (perItemDone < perItemTotal) {
            return startNextItem();
        }
        JERTLog.info("transfer items done {}/{}", perItemDone, perItemTotal);
        clearPerItem();
        return false;
    }

    private static void stopPerItemNow() {
        if (perItemTotal > 0) {
            JERTLog.info("transfer items stopped at {}/{}", perItemDone + 1, perItemTotal);
        }
        clearPerItem();
    }

    private static boolean stopPerItem(String reason) {
        JERTLog.warn("transfer: per-item crafting stopped ({})", reason);
        stopPerItemNow();
        clearPending(reason);
        return false;
    }

    private static void clearPerItem() {
        perItemTotal = 0;
        perItemDone = 0;
        perItemPlan = List.of();
    }

    private static RecipeTreeData.CraftStep withSingleBatch(RecipeTreeData.CraftStep root) {
        return new RecipeTreeData.CraftStep(
            root.recipe(),
            root.stack(),
            root.alternatives(),
            root.selectedInputs(),
            1L,
            1L,
            1L,
            root.progress(),
            root.nodePaths()
        );
    }

    private static void notifyPerItemProgress() {
        if (perItemTotal <= 1) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        try {
            minecraft.player.displayClientMessage(Component.translatable(
                "message.jeirecipetree.perItem.progress", perItemDone + 1, perItemTotal), true);
        } catch (RuntimeException ignored) {

        }
    }

    public static void tick(Screen currentScreen) {

        if (!queue.active() && !step.restockPending) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!(currentScreen instanceof AbstractContainerScreen<?> screen)
            || minecraft.player == null
            || minecraft.gameMode == null
            || screen.getMenu().containerId != step.menuId) {
            clearPending("menu or session lost");
            return;
        }
        if (step.waitFrames-- > 0) {
            return;
        }
        step.stepFrames++;
        if (!step.fillLogged) {
            step.fillWaitedFrames++;
        }

        if (step.restockPending) {
            tickRestock(screen, minecraft);
            return;
        }

        TransferObservation.logFillSnapshotOnce(screen.getMenu());

        if (step.ae2Carry) {
            TransferPickupTicks.tickAe2Carry(screen, minecraft);
            return;
        }

        if (step.vanillaPickup) {
            TransferPickupTicks.tickVanillaPickup(screen, minecraft);
            return;
        }

        if (step.genericResultProbe) {
            CraftQueue.Step<RecipeTreeData.CraftStep> craft = queue.current();
            if (craft == null) {
                clearPending("no current craft step (generic probe)");
                return;
            }
            int genericResult = findGenericResultSlot(
                screen.getMenu(), craft.value(), minecraft.player
            );
            if (genericResult >= 0) {
                step.resultSlot = genericResult;
                step.genericResultProbe = false;
                step.emptyFrames = 0;
            } else if (++step.emptyFrames > GENERIC_RESULT_WAIT_FRAMES) {
                TransferObservation.dumpFilledSlots(screen.getMenu(), craft.value());
                clearPending("generic result probe timed out");
                return;
            } else {
                return;
            }
        }

        if (step.resultSlot < 0) {
            startNextCraft();
            return;
        }
        if (step.resultSlot >= screen.getMenu().slots.size()) {
            clearPending("result slot out of range");
            return;
        }

        Slot result = screen.getMenu().getSlot(step.resultSlot);
        if (!result.hasItem()) {
            if (++step.emptyFrames > AE2_WAIT_FRAMES) {
                clearPending("result slot stayed empty");
            }
            return;
        }

        CraftQueue.Step<RecipeTreeData.CraftStep> craft = queue.current();
        if (craft == null) {
            clearPending("no current craft step");
            return;
        }
        ItemStack outputBefore = result.getItem().copy();
        if (!isExpectedCraftOutput(craft.value(), outputBefore)) {

            if (++step.emptyFrames > AE2_WAIT_FRAMES) {
                clearPending("unexpected craft output");
            }
            return;
        }

        step.emptyFrames = 0;
        long ownedBefore = SupplyLedger.inventoryAmount(outputBefore);
        Boolean ae2Pickup = StorageNetworkIntegration.takeCraftingResult(
            screen.getMenu(),
            step.resultSlot,
            true
        );
        if (Boolean.TRUE.equals(ae2Pickup)) {

            step.ae2Carry = true;
            step.ae2PlacementSlot = -1;
            step.ae2OutputKey = RecipeTreeData.ingredientKey(outputBefore);
            step.ae2OutputStack = outputBefore.copy();
            step.ae2OutputStack.setCount(1);

            step.ae2InventoryBefore = ownedBefore;
            step.resultSlot = -1;
            step.emptyFrames = 0;
            step.waitFrames = 2;
            return;
        }

        if (RtsStorageDriver.isTerminalMenu(screen.getMenu())
            || TomsStorageDriver.isCraftingTerminalMenu(screen.getMenu())) {
            step.rtsPickupKey = RecipeTreeData.ingredientKey(outputBefore);
            step.rtsPickupBefore = playerInventoryAmount(
                screen.getMenu(), minecraft.player, step.rtsPickupKey
            );
        }
        minecraft.gameMode.handleInventoryMouseClick(
            step.menuId,
            step.resultSlot,
            0,
            ClickType.QUICK_MOVE,
            minecraft.player
        );
        step.vanillaPickup = true;
        step.pickupAttempts = 1;
        step.waitFrames = 1;
    }

    private static void tickRestock(AbstractContainerScreen<?> screen, Minecraft minecraft) {
        Boolean done = StorageRestock.tick(screen.getMenu(), minecraft);
        if (done == null) {
            step.waitFrames = 1;
            return;
        }
        step.restockPending = false;
        if (Boolean.TRUE.equals(done)) {
            resumeAfterRestock();
            return;
        }

        JERTLog.warn("transfer: restock incomplete, stopping before transfer | short={} reason={}",
            StorageRestock.shortfall().size(), StorageRestock.lastFailure());
        notifyRestockShort(minecraft);
        clearPending("restock short");
    }

    private static void notifyRestockShort(Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        List<StorageRestock.Short> shortfall = StorageRestock.shortfall();
        Component message;
        if (shortfall.isEmpty()) {
            message = Component.translatable("message.jeirecipetree.restock.failed",
                StorageRestock.lastFailure());
        } else {
            message = shortfallMessage(shortfall);
        }
        try {
            minecraft.player.displayClientMessage(message, true);
        } catch (RuntimeException ignored) {

        }
    }

    private static Component shortfallMessage(List<StorageRestock.Short> shortfall) {
        int shown = Math.min(2, shortfall.size());
        MutableComponent items = Component.empty();
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                items.append(Component.translatable("message.jeirecipetree.restock.sep"));
            }
            items.append(shortfallDetail(shortfall.get(i)));
        }
        return shortfall.size() > shown
            ? Component.translatable(
                "message.jeirecipetree.restock.short.more", items, shortfall.size())
            : Component.translatable("message.jeirecipetree.restock.short", items);
    }

    private static Component shortfallDetail(StorageRestock.Short entry) {
        Component name = entry.stack() == null || entry.stack().isEmpty()
            ? Component.literal(entry.key())
            : entry.stack().getHoverName();

        return entry.network() < 0
            ? Component.translatable(
                "message.jeirecipetree.restock.detail.nonet",
                name, entry.missing(), entry.need(), entry.inventory())
            : Component.translatable(
                "message.jeirecipetree.restock.detail",
                name, entry.missing(), entry.need(), entry.network(), entry.inventory());
    }

    private static void resumeAfterRestock() {
        step.waitFrames = 3;
        step.emptyFrames = 0;
        if (queue.active() && queue.hasCurrent()) {

            startNextCraft();
            return;
        }
        RecipeTreeData.CraftStep target = step.restockTarget;
        int chunk = step.restockChunk;
        boolean recursive = step.restockRecursive;
        boolean maxTransfer = step.restockMaxTransfer;
        step.restockTarget = null;
        if (target == null) {
            clearPending("restock finished without a resume target");
            return;
        }
        if (!runTransfer(target, chunk, true, recursive, maxTransfer)) {
            clearPending("transfer after restock failed");
        }
    }

    public static void completePendingCraft() {
        if (!queue.hasCurrent()) {
            clearPending("queue exhausted during completion");
            return;
        }

        int waitedFrames = step.stepFrames;
        String destination = step.destination;
        boolean continueLoaded = queue.completeStep();
        JERTLog.debug("transfer step advance: continueLoaded={} loaded={} chunk={}",
            continueLoaded, queue.loadedBatches(), queue.chunk());
        resetStepState();
        JERTLog.debug("transfer step done  index={}/{} waited={}fr destination={} chunkLoaded={}",
            queue.index(), queue.size(), waitedFrames,
            destination.isEmpty() ? "-" : destination, continueLoaded);

        step.resultSlot = continueLoaded ? queue.craftResultSlot() : -1;
        step.genericResultProbe = continueLoaded && step.resultSlot < 0;
    }

    public static int findInventoryDestination(
        AbstractContainerMenu menu,
        Player player,
        ItemStack stack
    ) {
        if (player == null || stack.isEmpty()) {
            return -1;
        }
        int empty = -1;
        int partial = -1;
        for (Slot slot : menu.slots) {
            if (!StorageRestock.isPlayerInventory(slot, player) || !slot.mayPlace(stack)) {
                continue;
            }
            ItemStack existing = slot.getItem();
            if (!existing.isEmpty()
                && RecipeTreeData.ingredientKey(existing).equals(RecipeTreeData.ingredientKey(stack))
                && existing.getCount() < Math.min(slot.getMaxStackSize(), stack.getMaxStackSize())) {
                int capacity = Math.min(slot.getMaxStackSize(), stack.getMaxStackSize()) - existing.getCount();
                if (capacity >= stack.getCount()) {
                    return slot.index;
                }
                if (partial < 0) {
                    partial = slot.index;
                }
            }
            if (empty < 0 && existing.isEmpty()) {
                empty = slot.index;
            }
        }
        return empty >= 0 ? empty : partial;
    }

    public static long playerInventoryAmount(
        AbstractContainerMenu menu,
        Player player,
        String key
    ) {
        if (menu == null || player == null || key == null || key.isEmpty()) {
            return 0;
        }
        long amount = 0;
        for (Slot slot : menu.slots) {
            if (StorageRestock.isPlayerInventory(slot, player) && !slot.getItem().isEmpty()
                && key.equals(RecipeTreeData.ingredientKey(slot.getItem()))) {
                amount += slot.getItem().getCount();
            }
        }
        return amount;
    }

    private static RecipeTreeData.CraftStep firstStep(RecipeTreeData.CraftStep step, boolean recursive) {
        if (step == null) {
            return null;
        }
        if (!recursive) {
            return step;
        }
        RecipeTreeData.Tree tree = RecipeTreeSession.craftingTree();
        if (tree == null) {
            return null;
        }
        return tree.recursiveCraftingSteps(step).stream().findFirst().orElse(null);
    }

    private static boolean isExpectedCraftOutput(RecipeTreeData.CraftStep step, ItemStack actual) {
        if (step == null || actual == null || actual.isEmpty()) {
            return false;
        }
        String actualKey = RecipeTreeData.ingredientKey(actual);
        if (actualKey.equals(RecipeTreeData.ingredientKey(step.stack()))) {
            return true;
        }

        RecipeTreeData.RecipeSnapshot snapshot = RecipeLookup.snapshot(step.recipe());
        return snapshot != null && snapshot.outputs().stream()
            .anyMatch(output -> actualKey.equals(RecipeTreeData.ingredientKey(output)));
    }

    private static boolean startNextCraft() {
        if (!queue.hasCurrent()) {

            if (continuePerItem()) {
                return true;
            }
            clearPending("queue complete");
            RecipeTreeFavorites.refreshNow();
            return true;
        }

        AbstractContainerScreen<?> screen = TransferJeiBridge.containerScreen();
        if (screen == null) {
            clearPending("no container screen");
            return false;
        }
        CraftQueue.Step<RecipeTreeData.CraftStep> craft = queue.current();
        if (craft == null) {
            clearPending("no current craft step");
            return false;
        }
        AbstractContainerMenu menu = screen.getMenu();
        Minecraft minecraft = Minecraft.getInstance();
        int resultSlot = TransferJeiBridge.instantResultSlot(menu);
        boolean genericResult = resultSlot < 0
            && hasGenericResultSlot(menu, minecraft.player);
        int chunk;
        if (resultSlot >= 0 || genericResult) {
            int requested = TransferFillPlan.transferChunk(craft.value(), craft.remaining(), true);
            int inputCapacity = TransferFillPlan.maxBatchesForInstantInputs(
                menu, craft.value(), craft.value().selectedInputs(), true
            );
            chunk = Math.min(requested, inputCapacity);
        } else {
            chunk = TransferFillPlan.transferChunk(craft.value(), craft.remaining(), true);
        }

        chunk = TransferRestockPlanner.inventoryBoundChunk(craft.value(), chunk, menu, minecraft.player);
        TransferPickupTicks.clearCursorBeforeTransfer(menu);
        if (chunk <= 0 || !runTransfer(craft.value(), chunk, true, true, false)) {
            clearPending("transfer failed or nothing to transfer");
            return false;
        }
        if (step.restockPending) {

            return true;
        }

        if (resultSlot < 0 && !genericResult) {

            clearPending("timed machine: inputs only");
            return true;
        }
        step.menuId = menu.containerId;
        step.resultSlot = resultSlot;
        step.genericResultProbe = genericResult;
        queue.startStep(chunk, resultSlot);
        JERTLog.debug("transfer fill done: chunk={} loaded={} remaining={} resultSlot={}",
            chunk, queue.loadedBatches(), craft.remaining(), resultSlot);
        step.ae2Carry = false;
        step.ae2NetworkDeposit = false;
        step.ae2NetworkRequest = false;
        step.ae2NetworkCount = 0;
        step.ae2PlacementSlot = -1;
        step.ae2PlacementInFlight = false;
        step.ae2PlacementCount = 0;
        step.ae2PlacementInventoryBefore = 0;
        step.waitFrames = 3;
        step.emptyFrames = 0;
        step.fillLogged = false;
        lastFillState = "";
        JERTLog.debug(
            "transfer step begin index={}/{} recipe={} batches={} menu={}",
            queue.index() + 1,
            queue.size(),
            TransferObservation.recipeName(craft.value().recipe()),
            craft.value().batches(),
            menu.getClass().getSimpleName());
        return true;
    }

    private static int findGenericResultSlot(
        AbstractContainerMenu menu,
        RecipeTreeData.CraftStep step,
        Player player
    ) {
        if (menu == null || step == null || player == null) {
            return -1;
        }
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory()
                || slot.mayPlace(ItemStack.EMPTY)
                || !slot.hasItem()
                || !isExpectedCraftOutput(step, slot.getItem())
                || !slot.mayPickup(player)) {
                continue;
            }
            return slot.index;
        }
        return -1;
    }

    private static boolean hasGenericResultSlot(
        AbstractContainerMenu menu,
        Player player
    ) {
        if (menu == null || player == null) {
            return false;
        }
        return menu.slots.stream()
            .anyMatch(slot -> slot.container != player.getInventory()
                && !slot.mayPlace(ItemStack.EMPTY));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean runTransfer(
        RecipeTreeData.CraftStep step,
        int batches,
        boolean doTransfer,
        boolean recursiveCandidates,
        boolean maxTransfer
    ) {
        if (step == null || batches <= 0) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null) {
            return false;
        }
        AbstractContainerScreen<?> screen = TransferJeiBridge.containerScreen();
        if (screen == null) {
            return false;
        }
        IRecipeLayoutDrawable<?> layout = RecipeLookup.createLayout(step.recipe()).orElse(null);
        if (layout == null) {
            return false;
        }

        AbstractContainerMenu menu = screen.getMenu();

        boolean ae2Menu = Ae2StorageIntegration.isCraftingMenu(menu);

        List<StorageRestock.Need> needs = TransferRestockPlanner.needsForRestock(
            step, layout, batches, menu, player, doTransfer, ae2Menu
        );
        if (TransferRestockPlanner.startRestock(step, menu, batches, doTransfer, recursiveCandidates, maxTransfer, needs)) {
            return true;
        }

        return finishRun(
            step, layout, menu, batches, player, doTransfer, recursiveCandidates, maxTransfer, ae2Menu, needs
        );
    }

    private static boolean finishRun(
        RecipeTreeData.CraftStep step,
        IRecipeLayoutDrawable<?> layout,
        AbstractContainerMenu menu,
        int batches,
        Player player,
        boolean doTransfer,
        boolean recursiveCandidates,
        boolean maxTransfer,
        boolean ae2Menu,
        List<StorageRestock.Need> needs
    ) {
        boolean tomsMenu = TomsStorageDriver.isCraftingTerminalMenu(menu);

        boolean beyondMenu = StorageNetworkIntegration.isBeyondMenu(menu);
        boolean networkMenu = ae2Menu || tomsMenu || beyondMenu;
        if (!networkMenu || maxTransfer) {
            Boolean generic = TransferJeiBridge.tryJeiTransfer(
                menu, layout, step.selectedInputs(), batches, player,
                doTransfer, recursiveCandidates, maxTransfer
            );
            if (Boolean.TRUE.equals(generic)) {
                return true;
            }
        }

        Boolean networkTransfer = TransferJeiBridge.tryNetworkCraftingTransfer(
            menu, layout, step.selectedInputs(), doTransfer, recursiveCandidates,
            TransferObservation.registryId(step.recipe())
        );
        if (Boolean.TRUE.equals(networkTransfer)) {
            return true;
        }

        if (doTransfer && tomsMenu
            && TomsStorageDriver.fillGridFromInventory(
                menu, TransferObservation.asCraftingRecipe(step.recipe()), player)) {
            return true;
        }
        if (networkTransfer != null) {
            return false;
        }
        boolean transferred = networkMenu
            && Boolean.TRUE.equals(TransferJeiBridge.tryJeiTransfer(
                menu, layout, step.selectedInputs(), batches, player,
                doTransfer, recursiveCandidates, maxTransfer
            ));
        if (transferred) {
            return true;
        }

        if (!doTransfer && !needs.isEmpty()) {
            return TransferRestockPlanner.canRestock(menu, needs);
        }
        return false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})

    static void clearPending(String reason) {

        stopPerItemNow();
        String code = TransferDiagnostics.reasonCode(reason);
        if ("ok".equals(code)) {
            JERTLog.info("transfer end completed={} total={} failed=0 result=ok took={}s",
                queue.index(), queue.size(), TransferDiagnostics.elapsedSeconds());
        } else if ("session_lost".equals(code)) {

            JERTLog.info("transfer end completed={} total={} stopped_at={} reason=session_lost"
                    + " result=aborted took={}s",
                TransferDiagnostics.doneSteps(), queue.size(), queue.index(), TransferDiagnostics.elapsedSeconds());
        } else {
            JERTLog.error(
                "transfer step fail index={}/{} phase={} reason={} waited={}fr {} detail=\"{}\"",
                queue.index(), queue.size(), phaseCode(), code, step.emptyFrames,
                TransferDiagnostics.currentFillState(), TransferDiagnostics.safeDetail(reason));
            JERTLog.error("transfer end completed={} total={} failed=1 stopped_at={} result=partial"
                    + " reason={} took={}s",
                TransferDiagnostics.doneSteps(), queue.size(), queue.index(), code, TransferDiagnostics.elapsedSeconds());
        }
        clearPending();
    }

    private static void resetStepState() {
        step.resetStep();
    }

    private static String phaseCode() {
        return phase().name().toLowerCase(java.util.Locale.ROOT);
    }

    public static TransferPhase phase() {
        return TransferPhase.of(queue.active(), step.ae2Carry, step.vanillaPickup,
            step.genericResultProbe, step.resultSlot);
    }

    private static void clearPending() {
        queue.clear();
        StorageRestock.cancel();
        step.resetAll();

        sessionStartNanos = 0L;
        lastFillState = "";
    }

    public static String stepStatus() {
        return step.describe() + " menuId=" + step.menuId + " wait=" + step.waitFrames;
    }

}
