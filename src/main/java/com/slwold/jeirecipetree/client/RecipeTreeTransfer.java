package com.slwold.jeirecipetree.client;

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
    private static final int AE2_WAIT_FRAMES = 600;

    private static final int AE2_DEPOSIT_WAIT_FRAMES = 40;

    private static final int GENERIC_RESULT_WAIT_FRAMES = 40;

    private static final int RTS_PICKUP_WAIT_FRAMES = 20;

    private static CraftQueue<RecipeTreeData.CraftStep> queue = CraftQueue.create();
    private static final TransferStepState step = new TransferStepState();

    private static long sessionStartNanos;

    private static String lastFillState = "";

    private RecipeTreeTransfer() {
    }

    public static void cancel() {
        clearPending();
    }

    static String queueStatus() {
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
        int chunk = transferChunk(first, first.batches(), recursive);
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
            if (!exposeParentContainer()) {
                return false;
            }
            int chunk = transferChunk(step, step == null ? 0 : step.batches(), false);
            AbstractContainerScreen<?> parent = containerScreen();
            AbstractContainerMenu parentMenu = parent == null ? null : parent.getMenu();
            chunk = inventoryBoundChunk(
                step,
                chunk,
                parentMenu,
                Minecraft.getInstance().player
            );
            clearCursorBeforeTransfer(parentMenu);
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
        queue = CraftQueue.of(plan, RecipeTreeData.CraftStep::batches);
        sessionStartNanos = System.nanoTime();
        lastFillState = "";
        JERTLog.info("transfer start origin=tree_row target={} want={} steps={} maxTransfer={}",
            RecipeTreeData.ingredientKey(step.stack()),
            step.total(),
            queue.size(),
            maxTransfer);
        if (!exposeParentContainer()) {
            clearPending("exposeParentContainer failed");
            return false;
        }
        return startNextCraft();
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

        logFillSnapshotOnce(screen.getMenu());

        if (step.ae2Carry) {
            tickAe2Carry(screen, minecraft);
            return;
        }

        if (step.vanillaPickup) {
            tickVanillaPickup(screen, minecraft);
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
                dumpFilledSlots(screen.getMenu(), craft.value());
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

        if (RtsStorageDriver.isTerminalMenu(screen.getMenu())) {
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
        if (!Boolean.TRUE.equals(done)) {
            JERTLog.debug("transfer: restock failed, falling back to plain transfer | {}",
                StorageRestock.lastFailure());
        }
        resumeAfterRestock();
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

    private static void tickVanillaPickup(AbstractContainerScreen<?> screen, Minecraft minecraft) {
        if (step.resultSlot < 0 || step.resultSlot >= screen.getMenu().slots.size()) {
            clearPending("pickup: result slot out of range");
            return;
        }
        if (RtsStorageDriver.isTerminalMenu(screen.getMenu())) {
            tickRtsPickup(screen, minecraft);
            return;
        }
        Slot result = screen.getMenu().getSlot(step.resultSlot);
        if (!result.hasItem()) {

            queue.collectWholeLoadedBatch();
            step.destination = "inventory";
            completePendingCraft();
            return;
        }
        if (++step.pickupAttempts > 8) {

            clearPending("pickup: inventory full or result still owned");
            return;
        }
        minecraft.gameMode.handleInventoryMouseClick(
            step.menuId,
            step.resultSlot,
            0,
            ClickType.QUICK_MOVE,
            minecraft.player
        );
        step.waitFrames = 1;
    }

    private static void tickRtsPickup(AbstractContainerScreen<?> screen, Minecraft minecraft) {
        if (step.rtsPickupBefore < 0L) {

            JERTLog.debug("rts pickup: no inventory baseline, advancing without verification");
            queue.collectWholeLoadedBatch();
            step.destination = "inventory";
            completePendingCraft();
            return;
        }
        long now = playerInventoryAmount(screen.getMenu(), minecraft.player, step.rtsPickupKey);
        if (now > step.rtsPickupBefore) {
            queue.collectWholeLoadedBatch();
            step.destination = "inventory";
            completePendingCraft();
            return;
        }
        if (++step.emptyFrames > RTS_PICKUP_WAIT_FRAMES) {
            clearPending("rts pickup: inventory never grew");
        }
    }

    private static void tickAe2Carry(AbstractContainerScreen<?> screen, Minecraft minecraft) {
        AbstractContainerMenu menu = screen.getMenu();
        if (step.ae2NetworkDeposit) {
            tickAe2NetworkDeposit(menu, minecraft);
            return;
        }

        ItemStack carried = menu.getCarried();
        if (step.ae2PlacementInFlight) {
            if (carried.isEmpty()) {
                if (!step.ae2OutputKey.isEmpty()
                    && playerInventoryAmount(menu, minecraft.player, step.ae2OutputKey)
                        > step.ae2PlacementInventoryBefore) {
                    step.destination = "inventory";
                    completePendingCraft();
                    return;
                }
                if (++step.emptyFrames > AE2_WAIT_FRAMES) {
                    clearPending("AE2 carry timed out");
                } else {
                    step.waitFrames = 1;
                }
                return;
            }

            long inventoryAmount = step.ae2OutputKey.isEmpty()
                ? 0
                : playerInventoryAmount(menu, minecraft.player, step.ae2OutputKey);
            if (carried.getCount() < step.ae2PlacementCount
                || inventoryAmount > step.ae2PlacementInventoryBefore) {

                step.ae2PlacementInFlight = false;
                step.ae2PlacementSlot = -1;
                step.ae2PlacementCount = 0;
                step.emptyFrames = 0;
                step.waitFrames = 1;
                return;
            }
            if (++step.emptyFrames > AE2_WAIT_FRAMES) {
                step.ae2PlacementInFlight = false;
                if (startAe2NetworkDeposit(menu)) {
                    return;
                }
                clearPending("AE2 carry: no inventory destination and no network deposit");
            } else {
                step.waitFrames = 1;
            }
            return;
        }

        if (carried.isEmpty()) {

            if (!step.ae2OutputStack.isEmpty()
                && SupplyLedger.inventoryAmount(step.ae2OutputStack) > step.ae2InventoryBefore) {
                step.destination = "inventory";
                completePendingCraft();
                return;
            }
            if (++step.emptyFrames > AE2_WAIT_FRAMES) {
                clearPending("AE2 carry: cursor became empty without a pickup");
            }
            return;
        }

        if (step.ae2PlacementSlot < 0) {

            if (startAe2NetworkDeposit(menu)) {
                return;
            }
            int target = findInventoryDestination(menu, minecraft.player, carried);
            if (target < 0) {

                JERTLog.warn(
                    "transfer: output {} x{} fits neither the ME network nor the player inventory; "
                        + "it stays on the cursor until you free a slot",
                    carried.getHoverName().getString(),
                    carried.getCount());
                clearPending("AE2 carry: no network deposit and no inventory destination");
                return;
            }
            step.ae2PlacementSlot = target;
            step.ae2PlacementInFlight = true;
            step.ae2PlacementCount = carried.getCount();
            step.ae2PlacementInventoryBefore = step.ae2OutputKey.isEmpty()
                ? 0
                : playerInventoryAmount(menu, minecraft.player, step.ae2OutputKey);
            minecraft.gameMode.handleInventoryMouseClick(
                step.menuId,
                target,
                0,
                ClickType.PICKUP,
                minecraft.player
            );
            step.waitFrames = 1;
            return;
        }
    }

    private static void tickAe2NetworkDeposit(AbstractContainerMenu menu, Minecraft minecraft) {
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) {
            step.destination = "network";
            completePendingCraft();
            return;
        }
        if (step.ae2NetworkRequest) {
            if (carried.getCount() < step.ae2NetworkCount) {

                step.ae2NetworkRequest = false;
                step.ae2NetworkCount = carried.getCount();
                step.emptyFrames = 0;
                step.waitFrames = 1;
                return;
            }
            if (++step.emptyFrames > AE2_DEPOSIT_WAIT_FRAMES) {

                JERTLog.debug("transfer: AE2 network deposit timed out, falling back to inventory | {}",
                    queueStatus());
                step.ae2NetworkDeposit = false;
                step.ae2NetworkRequest = false;
                step.ae2NetworkSkip = true;
                step.ae2PlacementSlot = -1;
                step.emptyFrames = 0;
                step.waitFrames = 1;
            } else {
                step.waitFrames = 1;
            }
            return;
        }
        if (!startAe2NetworkDeposit(menu)) {

            if (StorageRestock.placeCarriedInInventory(menu, minecraft.player, minecraft)) {
                JERTLog.debug("transfer: network deposit unavailable, output moved to inventory | {}",
                    queueStatus());
                step.destination = "inventory";
                completePendingCraft();
                return;
            }
            JERTLog.warn(
                "transfer: network deposit failed and no inventory slot is free; "
                    + "the output stays on the cursor");
            clearPending("AE2 network deposit failed to start");
        }
    }

    private static boolean startAe2NetworkDeposit(AbstractContainerMenu menu) {
        if (step.ae2NetworkSkip) {

            return false;
        }
        Boolean deposited = StorageNetworkIntegration.putCarriedItemIntoNetwork(menu, false);
        if (!Boolean.TRUE.equals(deposited)) {
            return false;
        }
        step.ae2NetworkDeposit = true;
        step.ae2NetworkRequest = true;
        step.ae2NetworkCount = Math.max(1, menu.getCarried().getCount());
        step.emptyFrames = 0;
        step.waitFrames = 1;
        return true;
    }

    private static void clearCursorBeforeTransfer(AbstractContainerMenu menu) {
        if (menu == null || menu.getCarried().isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ItemStack stuck = menu.getCarried().copy();
        if (StorageRestock.placeCarriedInInventory(menu, minecraft.player, minecraft)) {
            JERTLog.debug("transfer: cleared {} x{} from cursor before transfer",
                stuck.getHoverName().getString(), stuck.getCount());
            return;
        }
        JERTLog.warn("transfer: cursor still holds {} x{} (inventory full?), transfer may be rejected",
            stuck.getHoverName().getString(), stuck.getCount());
    }

    private static void dumpFilledSlots(AbstractContainerMenu menu, RecipeTreeData.CraftStep craft) {
        if (menu == null) {
            return;
        }
        StringBuilder filled = new StringBuilder();
        int nonPlayer = 0;
        for (Slot slot : menu.slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            nonPlayer++;
            if (!filled.isEmpty()) {
                filled.append(", ");
            }
            filled.append(slot.index).append(':')
                .append(stack.getHoverName().getString()).append('x').append(stack.getCount());
        }
        JERTLog.debug("transfer: probe timeout, menu={} target={} filled({})=[{}]",
            menu.getClass().getSimpleName(),
            craft == null ? "-" : craft.stack().getHoverName().getString(),
            nonPlayer,
            filled);
        dumpMissingIngredients(menu, craft);
    }

    private static void dumpMissingIngredients(AbstractContainerMenu menu, RecipeTreeData.CraftStep craft) {
        CraftingRecipe recipe = craft == null ? null : asCraftingRecipe(craft.recipe());
        if (recipe == null) {
            return;
        }
        List<Ingredient> needs = recipe.getIngredients();
        StringBuilder missing = new StringBuilder();
        for (int i = 0; i < needs.size() && i < 9; i++) {
            Ingredient need = needs.get(i);
            if (need == null || need.isEmpty()) {
                continue;
            }
            int slotIndex = 1 + i;
            ItemStack actual = slotIndex < menu.slots.size()
                ? menu.getSlot(slotIndex).getItem()
                : ItemStack.EMPTY;
            if (need.test(actual)) {
                continue;
            }
            if (!missing.isEmpty()) {
                missing.append(", ");
            }
            missing.append("slot").append(i + 1).append('=').append(firstOptionKey(need));
        }
        JERTLog.debug("transfer: recipe={} missing=[{}]", craft.recipe().registryId(), missing);
    }

    private static void logFillSnapshotOnce(AbstractContainerMenu menu) {
        if (step.fillLogged) {
            return;
        }
        boolean awaitingResult = step.ae2Carry || step.vanillaPickup
            || step.genericResultProbe || step.resultSlot >= 0;
        if (!awaitingResult) {
            return;
        }
        step.fillLogged = true;
        CraftQueue.Step<RecipeTreeData.CraftStep> craft = queue.current();
        String state = fillSummary(menu, craft == null ? null : craft.value());

        lastFillState = state;
        JERTLog.debug("transfer step fill  index={}/{} {} fillWaited={}fr",
            queue.index() + 1, queue.size(), state, step.fillWaitedFrames);
    }

    private static String recipeName(RecipeTreeData.RecipeRef ref) {
        if (ref == null) {
            return "-";
        }
        String id = ref.registryId();
        if (id != null && !id.isEmpty()) {
            return id;
        }
        String key = ref.key();
        return key == null || key.isEmpty() ? "-" : key;
    }

    private static CraftingRecipe asCraftingRecipe(RecipeTreeData.RecipeRef ref) {
        if (ref == null) {
            return null;
        }
        Object raw = ref.recipe();
        if (raw instanceof CraftingRecipe recipe) {
            return recipe;
        }
        if (raw instanceof RecipeHolder<?> holder && holder.value() instanceof CraftingRecipe recipe) {
            return recipe;
        }
        return null;
    }

    private static String fillSummary(AbstractContainerMenu menu, RecipeTreeData.CraftStep craft) {
        CraftingRecipe recipe = craft == null ? null : asCraftingRecipe(craft.recipe());
        if (menu == null || recipe == null) {
            return "grid=-";
        }
        List<Ingredient> needs = recipe.getIngredients();
        int total = 0;
        int matched = 0;
        StringBuilder missing = new StringBuilder();
        for (int i = 0; i < needs.size() && i < 9; i++) {
            Ingredient need = needs.get(i);
            if (need == null || need.isEmpty()) {
                continue;
            }
            total++;
            int slotIndex = 1 + i;
            ItemStack actual = slotIndex < menu.slots.size()
                ? menu.getSlot(slotIndex).getItem()
                : ItemStack.EMPTY;
            if (need.test(actual)) {
                matched++;
                continue;
            }
            if (!missing.isEmpty()) {
                missing.append(',');
            }
            missing.append("slot").append(i + 1).append('=').append(firstOptionKey(need));
        }
        return "grid=" + matched + "/" + total + " missing=[" + missing + "]";
    }

    private static String firstOptionKey(Ingredient ingredient) {
        ItemStack[] options = ingredient.getItems();
        if (options.length == 0) {
            return "?";
        }
        return net.minecraft.core.registries.BuiltInRegistries.ITEM
            .getKey(options[0].getItem()).toString();
    }

    private static void completePendingCraft() {
        if (!queue.hasCurrent()) {
            clearPending("queue exhausted during completion");
            return;
        }

        int waitedFrames = step.stepFrames;
        String destination = step.destination;
        boolean continueLoaded = queue.completeStep();
        resetStepState();
        JERTLog.debug("transfer step done  index={}/{} waited={}fr destination={} chunkLoaded={}",
            queue.index(), queue.size(), waitedFrames,
            destination.isEmpty() ? "-" : destination, continueLoaded);

        step.resultSlot = continueLoaded ? queue.craftResultSlot() : -1;
        step.genericResultProbe = continueLoaded && step.resultSlot < 0;
    }

    private static int findInventoryDestination(
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

    private static long playerInventoryAmount(
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
            clearPending("queue complete");
            RecipeTreeFavorites.refreshNow();
            return true;
        }

        AbstractContainerScreen<?> screen = containerScreen();
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
        int resultSlot = instantResultSlot(menu);
        boolean genericResult = resultSlot < 0
            && hasGenericResultSlot(menu, minecraft.player);
        int chunk;
        if (resultSlot >= 0 || genericResult) {
            int requested = transferChunk(craft.value(), craft.remaining(), true);
            int inputCapacity = maxBatchesForInstantInputs(
                menu, craft.value(), craft.value().selectedInputs(), true
            );
            chunk = Math.min(requested, inputCapacity);
        } else {
            chunk = transferChunk(craft.value(), craft.remaining(), true);
        }

        chunk = inventoryBoundChunk(craft.value(), chunk, menu, minecraft.player);
        clearCursorBeforeTransfer(menu);
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
            recipeName(craft.value().recipe()),
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

    private static int transferChunk(RecipeTreeData.CraftStep step) {
        return transferChunk(step, step == null ? 0 : step.batches(), false);
    }

    private static Optional<ItemStack> selectedInput(
        IRecipeSlotView slot,
        String selectedKey,
        boolean recursiveCandidates
    ) {
        String preferred = preferredInputKey(slot, selectedKey);
        return slot.getItemStacks()
            .filter(stack -> !stack.isEmpty())
            .filter(stack -> preferred.isEmpty()
                || RecipeTreeData.ingredientKey(stack).equals(preferred))
            .findFirst()
            .map(ItemStack::copy);
    }

    private static int transferChunk(
        RecipeTreeData.CraftStep step,
        long requested,
        boolean recursiveCandidates
    ) {
        if (step == null || requested <= 0) {
            return 0;
        }
        IRecipeLayoutDrawable<?> layout = RecipeLookup.createLayout(step.recipe()).orElse(null);
        if (layout == null) {
            return 0;
        }
        int capacity = maxBatchesPerTransfer(
            layout.getRecipeSlotsView(),
            step.selectedInputs(),
            recursiveCandidates
        );
        return (int) Math.max(1, Math.min(Math.min(Integer.MAX_VALUE, requested), capacity));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int maxBatchesForInstantInputs(
        AbstractContainerMenu menu,
        RecipeTreeData.CraftStep step,
        List<String> selectedInputs,
        boolean recursiveCandidates
    ) {
        if (menu == null || step == null || Ae2StorageIntegration.isCraftingMenu(menu)) {
            return 1;
        }
        IRecipeLayoutDrawable<?> layout = RecipeLookup.createLayout(step.recipe()).orElse(null);
        if (layout == null) {
            return 1;
        }

        List<IRecipeSlotView> inputs = layout.getRecipeSlotsView().getSlotViews().stream()
            .filter(slot -> slot.getRole() == RecipeIngredientRole.INPUT)
            .toList();
        if (inputs.isEmpty()) {
            return 1;
        }

        List<Slot> recipeSlots = null;
        try {
            IRecipeTransferManager manager = Internal.getJeiRuntime().getRecipeTransferManager();
            Optional<IRecipeTransferHandler<AbstractContainerMenu, Object>> handler = (Optional) manager
                .getRecipeTransferHandler(menu, (IRecipeCategory) layout.getRecipeCategory());
            if (handler.isPresent()) {
                recipeSlots = findRecipeSlots(handler.get(), menu, layout.getRecipe());
            }
        } catch (RuntimeException ignored) {

        }

        if (recipeSlots != null && inputs.size() <= recipeSlots.size()) {
            int result = Integer.MAX_VALUE;
            boolean foundItemInput = false;
            for (int index = 0; index < inputs.size(); index++) {
                if (inputs.get(index).isEmpty()) {

                    continue;
                }
                Optional<ItemStack> input = selectedInput(
                    inputs.get(index),
                    index < selectedInputs.size() ? selectedInputs.get(index) : "",
                    recursiveCandidates
                );
                if (input.isEmpty()) {

                    return 1;
                }
                foundItemInput = true;
                ItemStack stack = input.get();
                int perBatch = Math.max(1, stack.getCount());
                int slotCapacity = recipeSlots.get(index).getMaxStackSize(stack);
                result = Math.min(result, Math.max(1, slotCapacity / perBatch));
            }
            return foundItemInput ? Math.max(1, result) : 1;
        }

        if (menu instanceof CraftingMenu || menu instanceof InventoryMenu) {
            return Integer.MAX_VALUE;
        }

        int result = Integer.MAX_VALUE;
        boolean foundItemInput = false;
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return 1;
        }
        for (int inputIndex = 0; inputIndex < inputs.size(); inputIndex++) {
            IRecipeSlotView inputView = inputs.get(inputIndex);
            if (inputView.isEmpty()) {
                continue;
            }
            Optional<ItemStack> input = selectedInput(
                inputView,
                inputIndex < selectedInputs.size() ? selectedInputs.get(inputIndex) : "",
                recursiveCandidates
            );
            if (input.isEmpty()) {
                return 1;
            }
            foundItemInput = true;
            ItemStack stack = input.get();
            int perBatch = Math.max(1, stack.getCount());
            boolean foundSlot = false;
            for (Slot slot : menu.slots) {
                if (slot.container == player.getInventory() || !slot.mayPlace(stack)) {
                    continue;
                }
                foundSlot = true;
                int slotCapacity = slot.getMaxStackSize(stack);
                result = Math.min(result, Math.max(1, slotCapacity / perBatch));
            }
            if (!foundSlot) {
                return 1;
            }
        }
        return foundItemInput ? Math.max(1, result) : 1;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<Slot> findRecipeSlots(
        IRecipeTransferHandler handler,
        AbstractContainerMenu menu,
        Object recipe
    ) {
        for (Class<?> type = handler.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!IRecipeTransferInfo.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.trySetAccessible();
                    IRecipeTransferInfo info = (IRecipeTransferInfo) field.get(handler);
                    return List.copyOf(info.getRecipeSlots(menu, recipe));
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    return null;
                }
            }
        }
        return null;
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
        AbstractContainerScreen<?> screen = containerScreen();
        if (screen == null) {
            return false;
        }
        IRecipeLayoutDrawable<?> layout = RecipeLookup.createLayout(step.recipe()).orElse(null);
        if (layout == null) {
            return false;
        }

        AbstractContainerMenu menu = screen.getMenu();

        boolean ae2Menu = Ae2StorageIntegration.isCraftingMenu(menu);

        List<StorageRestock.Need> needs = needsForRestock(
            step, layout, batches, menu, player, doTransfer, ae2Menu
        );
        if (startRestock(step, menu, batches, doTransfer, recursiveCandidates, maxTransfer, needs)) {
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
        if (!ae2Menu || maxTransfer) {
            Boolean generic = tryJeiTransfer(
                menu, layout, step.selectedInputs(), batches, player,
                doTransfer, recursiveCandidates, maxTransfer
            );
            if (Boolean.TRUE.equals(generic)) {
                return true;
            }
        }

        Boolean networkTransfer = tryNetworkCraftingTransfer(
            menu, layout, step.selectedInputs(), doTransfer, recursiveCandidates
        );
        if (networkTransfer != null) {
            return networkTransfer;
        }
        boolean transferred = ae2Menu
            && Boolean.TRUE.equals(tryJeiTransfer(
                menu, layout, step.selectedInputs(), batches, player,
                doTransfer, recursiveCandidates, maxTransfer
            ));
        if (transferred) {
            return true;
        }

        if (!doTransfer && !needs.isEmpty()) {
            return canRestock(menu, needs);
        }
        return false;
    }

    private static List<StorageRestock.Need> needsForRestock(
        RecipeTreeData.CraftStep step,
        IRecipeLayoutDrawable<?> layout,
        int batches,
        AbstractContainerMenu menu,
        Player player,
        boolean doTransfer,
        boolean ae2Menu
    ) {
        if (ae2Menu) {
            return List.of();
        }

        if (RtsStorageDriver.isTerminalMenu(menu)) {
            return List.of();
        }
        if (doTransfer && RecipeTreeTransfer.step.restockDone) {

            return List.of();
        }
        return computeNeeds(layout, step.selectedInputs(), batches, menu, player);
    }

    private static boolean startRestock(
        RecipeTreeData.CraftStep craftStep,
        AbstractContainerMenu menu,
        int batches,
        boolean doTransfer,
        boolean recursiveCandidates,
        boolean maxTransfer,
        List<StorageRestock.Need> needs
    ) {

        if (!doTransfer || step.restockDone || needs.isEmpty()) {
            return false;
        }
        if (!StorageRestock.begin(menu, needs)) {
            return false;
        }
        step.restockDone = true;
        step.restockPending = true;
        step.restockTarget = craftStep;
        step.restockChunk = batches;
        step.restockRecursive = recursiveCandidates;
        step.restockMaxTransfer = maxTransfer;
        step.menuId = menu.containerId;
        step.waitFrames = 2;
        return true;
    }

    private static boolean canRestock(AbstractContainerMenu menu, List<StorageRestock.Need> needs) {
        if (StorageDriverRegistry.canWithdraw(menu)) {
            return coverable(menu, needs);
        }
        return SafeConfig.sophisticatedRestockEnabled() && SiaMaterialBridge.available();
    }

    private static List<StorageRestock.Need> computeNeeds(
        IRecipeLayoutDrawable<?> layout,
        List<String> selectedInputs,
        int batches,
        AbstractContainerMenu menu,
        Player player
    ) {
        if (layout == null || menu == null || player == null || batches <= 0) {
            return List.of();
        }
        Map<String, ItemStack> samples = new LinkedHashMap<>();
        Map<String, Integer> wanted = new LinkedHashMap<>();
        int inputIndex = 0;
        for (IRecipeSlotView slot : layout.getRecipeSlotsView().getSlotViews()) {
            if (slot.getRole() != RecipeIngredientRole.INPUT) {
                continue;
            }
            String selected = preferredInputKey(
                slot,
                inputIndex < selectedInputs.size() ? selectedInputs.get(inputIndex) : ""
            );
            inputIndex++;
            ItemStack picked = RecipeLookup.candidateStacks(slot).stream()
                .filter(stack -> !stack.isEmpty())
                .filter(stack -> selected.isEmpty()
                    || RecipeTreeData.ingredientKey(stack).equals(selected))
                .findFirst()
                .orElse(ItemStack.EMPTY);
            if (picked.isEmpty()) {
                continue;
            }
            long slotNeed = Math.min(
                (long) Math.max(1, picked.getCount()) * batches,
                picked.getMaxStackSize()
            );
            String key = RecipeTreeData.ingredientKey(picked);
            samples.putIfAbsent(key, picked.copy());
            wanted.merge(key, (int) Math.min(Integer.MAX_VALUE, slotNeed), Integer::sum);
        }
        if (wanted.isEmpty()) {
            return List.of();
        }

        Map<String, Long> have = StorageRestock.counts(menu, player);
        List<StorageRestock.Need> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : wanted.entrySet()) {
            long missing = entry.getValue() - have.getOrDefault(entry.getKey(), 0L);
            if (missing <= 0) {
                continue;
            }
            ItemStack sample = samples.get(entry.getKey());
            if (sample == null || sample.isEmpty()) {
                continue;
            }
            sample.setCount(1);
            long target = have.getOrDefault(entry.getKey(), 0L) + missing;
            result.add(new StorageRestock.Need(
                sample,
                entry.getKey(),
                (int) Math.min(Integer.MAX_VALUE, missing),
                (int) Math.min(Integer.MAX_VALUE, target)
            ));
        }
        return result;
    }

    private record BatchDemand(ItemStack sample, long perBatch) {
    }

    private static Map<String, BatchDemand> perBatchDemand(
        IRecipeLayoutDrawable<?> layout,
        List<String> selectedInputs
    ) {
        Map<String, BatchDemand> result = new LinkedHashMap<>();
        if (layout == null) {
            return result;
        }
        int inputIndex = 0;
        for (IRecipeSlotView slot : layout.getRecipeSlotsView().getSlotViews()) {
            if (slot.getRole() != RecipeIngredientRole.INPUT) {
                continue;
            }
            String selected = preferredInputKey(
                slot,
                inputIndex < selectedInputs.size() ? selectedInputs.get(inputIndex) : ""
            );
            inputIndex++;
            ItemStack picked = RecipeLookup.candidateStacks(slot).stream()
                .filter(stack -> !stack.isEmpty())
                .filter(stack -> selected.isEmpty()
                    || RecipeTreeData.ingredientKey(stack).equals(selected))
                .findFirst()
                .orElse(ItemStack.EMPTY);
            if (picked.isEmpty()) {
                continue;
            }
            String key = RecipeTreeData.ingredientKey(picked);
            long per = Math.max(1L, picked.getCount());
            BatchDemand current = result.get(key);
            result.put(key, current == null
                ? new BatchDemand(picked.copy(), per)
                : new BatchDemand(current.sample(), current.perBatch() + per));
        }
        return result;
    }

    private static int inventoryBoundChunk(
        RecipeTreeData.CraftStep step,
        int chunk,
        AbstractContainerMenu menu,
        Player player
    ) {
        if (step == null || chunk <= 1 || menu == null || player == null) {
            return chunk;
        }
        IRecipeLayoutDrawable<?> layout = RecipeLookup.createLayout(step.recipe()).orElse(null);
        Map<String, BatchDemand> demand = perBatchDemand(layout, step.selectedInputs());
        if (demand.isEmpty()) {
            return chunk;
        }
        Map<String, Long> have = StorageRestock.counts(menu, player);
        int bound = chunk;
        for (Map.Entry<String, BatchDemand> entry : demand.entrySet()) {
            BatchDemand item = entry.getValue();
            long capacity = have.getOrDefault(entry.getKey(), 0L)
                + StorageRestock.roomFor(menu, player, item.sample());
            long batches = capacity / Math.max(1L, item.perBatch());
            bound = (int) Math.min(bound, Math.max(1L, batches));
        }
        if (bound < chunk) {
            JERTLog.debug("restock: chunk {} -> {} (inventory room)", chunk, bound);
        }
        return Math.max(1, bound);
    }

    private static boolean coverable(AbstractContainerMenu menu, List<StorageRestock.Need> needs) {
        StorageNetworkIntegration.StorageSnapshot snapshot = StorageDriverRegistry.read(menu);
        if (snapshot == null) {
            return false;
        }
        Map<String, Long> stored = new HashMap<>();
        for (StorageNetworkIntegration.StoredStack stack : snapshot.stacks()) {
            if (stack == null || stack.stack().isEmpty()) {
                continue;
            }
            stored.merge(RecipeTreeData.ingredientKey(stack.stack()), stack.amount(), Long::sum);
        }
        for (StorageRestock.Need need : needs) {
            if (stored.getOrDefault(need.key(), 0L) < need.amount()) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Boolean tryJeiTransfer(
        AbstractContainerMenu menu,
        IRecipeLayoutDrawable<?> layout,
        List<String> selectedInputs,
        int batches,
        Player player,
        boolean doTransfer,
        boolean recursiveCandidates,
        boolean maxTransfer
    ) {
        IRecipeTransferManager manager = Internal.getJeiRuntime().getRecipeTransferManager();
        Optional<IRecipeTransferHandler<AbstractContainerMenu, Object>> handler = (Optional) manager
            .getRecipeTransferHandler(menu, (IRecipeCategory) layout.getRecipeCategory());
        if (handler.isEmpty()) {
            return false;
        }

        IRecipeSlotsView slots = adjustInputs(
            layout.getRecipeSlotsView(), selectedInputs, batches, recursiveCandidates, player
        );
        try {
            IRecipeTransferError error = handler.get().transferRecipe(
                menu,
                layout.getRecipe(),
                slots,
                player,
                maxTransfer,
                doTransfer
            );
            return error == null || error.getType().allowsTransfer;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static AbstractContainerScreen<?> containerScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        Screen current = minecraft.screen;
        if (current instanceof RecipeTreeScreen treeScreen) {
            current = treeScreen.parentScreen();
        }
        return findContainerScreen(current);
    }

    private static boolean exposeParentContainer() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof RecipeTreeScreen treeScreen) {
            if (findContainerScreen(treeScreen.parentScreen()) == null) {
                return false;
            }
            treeScreen.onClose();
            closeRecipeGuiLayers();
        }
        return containerScreen() != null;
    }

    private static void closeRecipeGuiLayers() {
        Minecraft minecraft = Minecraft.getInstance();
        for (int depth = 0; depth < 6; depth++) {
            Screen screen = minecraft.screen;
            if (screen == null
                || screen instanceof AbstractContainerScreen<?>
                || !screen.getClass().getName().contains("RecipesGui")) {
                return;
            }
            screen.onClose();
        }
    }

    private static AbstractContainerScreen<?> findContainerScreen(Screen start) {
        Screen current = start;
        for (int depth = 0; current != null && depth < 6; depth++) {
            if (current instanceof AbstractContainerScreen<?> screen) {
                return screen;
            }
            Screen parent = parentScreenOf(current);
            if (parent == current) {
                break;
            }
            current = parent;
        }
        return null;
    }

    private static Screen parentScreenOf(Screen screen) {
        if (screen instanceof RecipeTreeScreen treeScreen) {
            return treeScreen.parentScreen();
        }
        if (!screen.getClass().getName().contains("RecipesGui")) {
            return null;
        }
        try {
            Method method = screen.getClass().getMethod("getParentScreen");
            Object value = method.invoke(screen);
            if (value instanceof Optional<?> optional) {
                return optional.orElse(null) instanceof Screen parent ? parent : null;
            }
            if (value instanceof Screen parent) {
                return parent;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {

        }
        for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField("parentScreen");
                field.setAccessible(true);
                Object value = field.get(screen);
                return value instanceof Screen parent ? parent : null;
            } catch (NoSuchFieldException ignored) {

            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static IRecipeSlotsView adjustInputs(
        IRecipeSlotsView original,
        List<String> selectedInputs,
        int batches,
        boolean recursiveCandidates,
        Player player
    ) {
        List<IRecipeSlotView> adjusted = new ArrayList<>();
        int inputIndex = 0;
        for (IRecipeSlotView slot : original.getSlotViews()) {
            if (slot.getRole() == RecipeIngredientRole.INPUT) {
                String selected = preferredInputKey(
                    slot,
                    inputIndex < selectedInputs.size() ? selectedInputs.get(inputIndex) : ""
                );
                adjusted.add(new AdjustedSlot(slot, selected, batches, player));
                inputIndex++;
            } else {
                adjusted.add(slot);
            }
        }
        List<IRecipeSlotView> immutable = List.copyOf(adjusted);
        return () -> immutable;
    }

    private static int maxBatchesPerTransfer(
        IRecipeSlotsView slots,
        List<String> selectedInputs,
        boolean recursiveCandidates
    ) {
        int result = Integer.MAX_VALUE;
        int inputIndex = 0;
        boolean foundItemInput = false;
        for (IRecipeSlotView slot : slots.getSlotViews()) {
            if (slot.getRole() != RecipeIngredientRole.INPUT) {
                continue;
            }
            String selected = preferredInputKey(
                slot,
                inputIndex < selectedInputs.size() ? selectedInputs.get(inputIndex) : ""
            );
            inputIndex++;
            int slotCapacity = RecipeLookup.candidateStacks(slot).stream()
                .filter(stack -> selected.isEmpty() || RecipeTreeData.ingredientKey(stack).equals(selected))
                .mapToInt(stack -> Math.max(1, stack.getMaxStackSize() / Math.max(1, stack.getCount())))
                .max()
                .orElse(Integer.MAX_VALUE);
            if (slotCapacity != Integer.MAX_VALUE) {
                foundItemInput = true;
                result = Math.min(result, slotCapacity);
            }
        }
        return foundItemInput ? Math.max(1, result) : 1;
    }

    private static String preferredInputKey(IRecipeSlotView slot, String selectedKey) {
        if (!selectedKey.isEmpty()) {
            return selectedKey;
        }

        return RecipeTreeFavorites.bookmarkedCandidate(RecipeLookup.candidateStacks(slot))
            .map(RecipeTreeData::ingredientKey)
            .orElse("");
    }

    private static int instantResultSlot(AbstractContainerMenu menu) {
        int networkResult = StorageNetworkIntegration.craftingResultSlot(menu);
        if (networkResult >= 0) {
            return networkResult;
        }
        if (menu instanceof CraftingMenu || menu instanceof InventoryMenu) {
            return 0;
        }
        if (menu instanceof StonecutterMenu) {
            return 1;
        }
        if (menu instanceof SmithingMenu || menu instanceof LoomMenu) {
            return 3;
        }
        if (menu instanceof CartographyTableMenu || menu instanceof GrindstoneMenu) {
            return 2;
        }
        return -1;
    }

    private static Boolean tryNetworkCraftingTransfer(
        AbstractContainerMenu menu,
        IRecipeLayoutDrawable<?> layout,
        List<String> selectedInputs,
        boolean doTransfer,
        boolean recursiveCandidates
    ) {
        if (!(layout.getRecipe() instanceof CraftingRecipe)) {
            return null;
        }
        List<ItemStack> templates = ae2Templates(layout, selectedInputs, recursiveCandidates);
        if (templates == null) {
            return null;
        }
        return StorageNetworkIntegration.tryFillCraftingGrid(menu, templates, doTransfer);
    }

    private static List<ItemStack> ae2Templates(
        IRecipeLayoutDrawable<?> layout,
        List<String> selectedInputs,
        boolean recursiveCandidates
    ) {
        List<IRecipeSlotView> inputs = layout.getRecipeSlotsView().getSlotViews().stream()
            .filter(slot -> slot.getRole() == RecipeIngredientRole.INPUT)
            .toList();
        if (inputs.size() != 9) {
            return null;
        }

        List<ItemStack> templates = new ArrayList<>(9);
        for (int index = 0; index < inputs.size(); index++) {
            IRecipeSlotView input = inputs.get(index);
            String selected = preferredInputKey(
                input,
                index < selectedInputs.size() ? selectedInputs.get(index) : ""
            );
            ItemStack template = RecipeLookup.candidateStacks(input).stream()
                .filter(stack -> !stack.isEmpty())
                .filter(stack -> selected.isEmpty() || RecipeTreeData.ingredientKey(stack).equals(selected))
                .findFirst()
                .map(ItemStack::copy)
                .orElse(ItemStack.EMPTY);
            if (!template.isEmpty()) {
                template.setCount(1);
            }
            templates.add(template);
        }
        return templates;
    }

    private static final java.util.Map<String, String> REASON_CODES = java.util.Map.ofEntries(
        java.util.Map.entry("queue complete", "ok"),
        java.util.Map.entry("menu or session lost", "session_lost"),
        java.util.Map.entry("no container screen", "no_screen"),
        java.util.Map.entry("exposeParentContainer failed", "expose_failed"),
        java.util.Map.entry("no current craft step", "no_step"),
        java.util.Map.entry("queue exhausted during completion", "queue_exhausted"),
        java.util.Map.entry("transfer failed or nothing to transfer", "transfer_rejected"),
        java.util.Map.entry("transfer after restock failed", "transfer_rejected"),
        java.util.Map.entry("restock finished without a resume target", "restock_no_target"),
        java.util.Map.entry("timed machine", "timed_machine"),
        java.util.Map.entry("result slot out of range", "slot_out_of_range"),
        java.util.Map.entry("pickup", "pickup_failed"),
        java.util.Map.entry("rts pickup", "pickup_failed"),
        java.util.Map.entry("result slot stayed empty", "result_empty"),
        java.util.Map.entry("generic result probe timed out", "probe_timeout"),
        java.util.Map.entry("AE2 carry timed out", "carry_timeout"),
        java.util.Map.entry("AE2 carry", "carry_failed"),
        java.util.Map.entry("AE2 network deposit failed to start", "deposit_failed"),
        java.util.Map.entry("unexpected craft output", "output_mismatch"));

    private static String reasonCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        String text = raw.trim();
        int colon = text.indexOf(':');
        String head = colon < 0 ? text : text.substring(0, colon);
        return REASON_CODES.getOrDefault(head, "other");
    }

    private static String phaseCode() {
        return phase().name().toLowerCase(java.util.Locale.ROOT);
    }

    private static void clearPending(String reason) {
        String code = reasonCode(reason);
        if ("ok".equals(code)) {
            JERTLog.info("transfer end completed={} total={} failed=0 result=ok took={}s",
                queue.index(), queue.size(), elapsedSeconds());
        } else if ("session_lost".equals(code)) {

            JERTLog.info("transfer end completed={} total={} stopped_at={} reason=session_lost"
                    + " result=aborted took={}s",
                doneSteps(), queue.size(), queue.index(), elapsedSeconds());
        } else {
            JERTLog.error(
                "transfer step fail index={}/{} phase={} reason={} waited={}fr {} detail=\"{}\"",
                queue.index(), queue.size(), phaseCode(), code, step.emptyFrames,
                currentFillState(), safeDetail(reason));
            JERTLog.error("transfer end completed={} total={} failed=1 stopped_at={} result=partial"
                    + " reason={} took={}s",
                doneSteps(), queue.size(), queue.index(), code, elapsedSeconds());
        }
        clearPending();
    }

    private static int doneSteps() {
        return Math.max(0, queue.index() - 1);
    }

    private static String safeDetail(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace('"', '\'').replace('\n', ' ');
    }

    private static String elapsedSeconds() {
        if (sessionStartNanos == 0L) {
            return "0.0";
        }
        long millis = Math.max(0L, (System.nanoTime() - sessionStartNanos) / 1_000_000L);
        return String.format(java.util.Locale.ROOT, "%.1f", millis / 1000.0);
    }

    private static String currentFillState() {
        return lastFillState.isEmpty() ? "grid=not_observed" : lastFillState;
    }

    static TransferPhase phase() {
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

    private static void resetStepState() {
        step.resetStep();
    }

    static String stepStatus() {
        return step.describe() + " menuId=" + step.menuId + " wait=" + step.waitFrames;
    }

    private static final class AdjustedSlot implements IRecipeSlotView {
        private final IRecipeSlotView delegate;
        private final List<ITypedIngredient<?>> ingredients;

        private AdjustedSlot(IRecipeSlotView delegate, String selectedKey, int batches, Player player) {
            this.delegate = delegate;
            IJeiRuntime runtime = DirectoryRecipePlugin.getJeiRuntime();
            List<ITypedIngredient<?>> adjusted = new ArrayList<>();
            if (runtime != null) {
                for (ITypedIngredient<?> ingredient : delegate.getAllIngredientsList()) {
                    if (ingredient == null) {
                        continue;
                    }
                    Optional<ItemStack> itemStack = ingredient.getItemStack();
                    if (itemStack.isEmpty()) {

                        Optional<net.neoforged.neoforge.fluids.FluidStack> fluid =
                            FluidRecipeCompat.fluid(ingredient);
                        if (fluid.isPresent()) {
                            String fluidKey = FluidRecipeCompat.fluidKey(fluid.get());
                            if (selectedKey.isEmpty() || selectedKey.equals(fluidKey)) {
                                FluidRecipeCompat.scaled(ingredient, batches, runtime.getIngredientManager())
                                    .ifPresent(adjusted::add);
                            }

                            for (ItemStack container : FluidRecipeCompat.matchingContainers(player, fluid.get(), batches)) {
                                if (selectedKey.isEmpty()
                                    || RecipeTreeData.ingredientKey(container).equals(selectedKey)) {
                                    runtime.getIngredientManager()
                                        .createTypedIngredient(VanillaTypes.ITEM_STACK, container)
                                        .ifPresent(adjusted::add);
                                }
                            }
                        } else {
                            adjusted.add(ingredient);
                        }
                        continue;
                    }
                    ItemStack stack = itemStack.get();
                    if (!selectedKey.isEmpty() && !RecipeTreeData.ingredientKey(stack).equals(selectedKey)) {
                        continue;
                    }
                    ItemStack scaled = stack.copy();
                    long count = (long) Math.max(1, stack.getCount()) * batches;
                    if (count > stack.getMaxStackSize()) {
                        continue;
                    }
                    scaled.setCount((int) count);
                    runtime.getIngredientManager()
                        .createTypedIngredient(VanillaTypes.ITEM_STACK, scaled)
                        .ifPresent(adjusted::add);
                }
            }
            this.ingredients = adjusted.isEmpty()
                ? delegate.getAllIngredients().toList()
                : List.copyOf(adjusted);
        }

        @Override
        public Stream<ITypedIngredient<?>> getAllIngredients() {
            return ingredients.stream();
        }

        @Override
        public List<ITypedIngredient<?>> getAllIngredientsList() {
            return ingredients;
        }

        @Override
        public Optional<ITypedIngredient<?>> getDisplayedIngredient() {
            return ingredients.stream().findFirst();
        }

        public Stream<ITypedIngredient<?>> getDisplayedIngredients() {
            return ingredients.stream();
        }

        public Optional<TagKey<?>> getTagKey() {
            return Optional.empty();
        }

        @Override
        public RecipeIngredientRole getRole() {
            return delegate.getRole();
        }

        @Override
        public void drawHighlight(GuiGraphics guiGraphics, int color) {
            delegate.drawHighlight(guiGraphics, color);
        }

        @Override
        public Optional<String> getSlotName() {
            return delegate.getSlotName();
        }
    }
}
