package com.slwold.jeirecipetree.client.storage;

import com.slwold.jeirecipetree.client.data.*;
import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

final public class StorageNetworkIntegration {
    private static final long CACHE_FALLBACK_NANOS = 250_000_000L;
    private static final String RS1_MENU = "com.refinedmods.refinedstorage.container.GridContainerMenu";
    private static final String RS2_MENU = "com.refinedmods.refinedstorage.common.grid.AbstractCraftingGridContainerMenu";
    private static final String RS1_SCREEN = "com.refinedmods.refinedstorage.screen.grid.GridScreen";

    private static final String BD_MENU = "com.wintercogs.beyonddimensions.common.menu.DimensionsNetMenu";
    private static final String BD_MENU_LEGACY = "com.wintercogs.beyonddimensions.common.menu.DimensionsCraftMenu";
    private static final String BBD_MENU_ACCESS =
        "net.xuwu.betterbeyonddimensions.common.NetworkStorageMenuAccess";
    private static final String BBD_NETWORK_SLOT =
        "net.xuwu.betterbeyonddimensions.common.NetworkStorageSlot";
    private static final String BBD_CLIENT_STORAGE_STATE =
        "net.xuwu.betterbeyonddimensions.client.ClientStorageState";
    private static final String BBD_RECIPE_FILL =
        "net.xuwu.betterbeyonddimensions.common.RecipeFill";
    private static final String BBD_NETWORK_HANDLER =
        "net.xuwu.betterbeyonddimensions.NetworkHandler";
    private static final String IT_MENU =
        "org.cyclops.integratedterminals.inventory.container.ContainerTerminalStorageBase";
    private static final String IT_SCREEN =
        "org.cyclops.integratedterminals.client.gui.container.ContainerScreenTerminalStorage";

    private static volatile Object cachedMenu;
    private static volatile long cachedGameTime = Long.MIN_VALUE;
    private static volatile long cachedAtNanos = Long.MIN_VALUE;
    private static volatile long cachedSnapshotRevision;
    private static volatile List<StoredStack> cachedStacks = List.of();
    private static volatile List<StoredFluid> cachedFluids = List.of();

    private StorageNetworkIntegration() {
    }

    static List<StoredStack> storedStacks() {
        if (RecipeTreeSession.tree() == null) {

            if (cachedMenu != null || !cachedStacks.isEmpty() || !cachedFluids.isEmpty()) {
                cachedSnapshotRevision++;
            }
            cachedMenu = null;
            cachedGameTime = Long.MIN_VALUE;
            cachedAtNanos = System.nanoTime();
            cachedStacks = List.of();
            cachedFluids = List.of();
            return List.of();
        }
        Object menu = Ae2StorageIntegration.activeMenu();
        long now = System.nanoTime();
        if (menu == null) {
            if (cachedMenu != null || !cachedStacks.isEmpty() || !cachedFluids.isEmpty()) {
                cachedSnapshotRevision++;
            }
            cachedMenu = null;
            cachedGameTime = Long.MIN_VALUE;
            cachedAtNanos = now;
            cachedStacks = List.of();
            cachedFluids = List.of();
            return List.of();
        }
        Minecraft minecraft = Minecraft.getInstance();
        long gameTime = minecraft.level == null ? -1L : minecraft.level.getGameTime();
        long cacheAge = now - cachedAtNanos;
        if (menu == cachedMenu
            && gameTime == cachedGameTime
            && cacheAge >= 0L
            && cacheAge < CACHE_FALLBACK_NANOS) {
            return cachedStacks;
        }

        StorageDriverRegistry.Result driven = StorageDriverRegistry.readWithSource(menu);
        StorageSnapshot drivenSnapshot = driven.snapshot();
        List<StoredStack> result = drivenSnapshot == null ? List.of() : drivenSnapshot.stacks();
        List<StoredFluid> fluidResult = drivenSnapshot == null ? List.of() : drivenSnapshot.fluids();
        fluidResult = mergeFluids(fluidResult);
        boolean snapshotChanged = menu != cachedMenu
            || !sameSnapshot(cachedStacks, result)
            || !sameFluidSnapshot(cachedFluids, fluidResult);
        cachedMenu = menu;
        cachedGameTime = gameTime;
        cachedAtNanos = now;
        cachedStacks = List.copyOf(result);
        cachedFluids = List.copyOf(fluidResult);
        if (snapshotChanged) {
            cachedSnapshotRevision++;
        }
        return cachedStacks;
    }

    public static long snapshotRevision() {
        storedStacks();
        return cachedSnapshotRevision;
    }

    static List<StoredFluid> storedFluids() {
        storedStacks();
        return cachedFluids;
    }

    public static StorageSnapshot snapshot() {
        List<StoredStack> stacks = storedStacks();
        return new StorageSnapshot(stacks, cachedFluids, cachedSnapshotRevision);
    }

    public record StorageSnapshot(List<StoredStack> stacks, List<StoredFluid> fluids, long revision) {
        public StorageSnapshot {
            stacks = List.copyOf(stacks == null ? List.of() : stacks);
            fluids = List.copyOf(fluids == null ? List.of() : fluids);
        }
    }

    private static boolean sameSnapshot(List<StoredStack> previous, List<StoredStack> next) {

        return stackAmounts(previous).equals(stackAmounts(next));
    }

    private static boolean sameFluidSnapshot(List<StoredFluid> previous, List<StoredFluid> next) {
        Map<String, Long> left = new java.util.HashMap<>();
        Map<String, Long> right = new java.util.HashMap<>();
        if (previous != null) {
            for (StoredFluid value : previous) {
                if (value != null && !value.key.isEmpty() && value.amount > 0) {
                    left.merge(value.key, value.amount, StorageNetworkIntegration::safeAdd);
                }
            }
        }
        if (next != null) {
            for (StoredFluid value : next) {
                if (value != null && !value.key.isEmpty() && value.amount > 0) {
                    right.merge(value.key, value.amount, StorageNetworkIntegration::safeAdd);
                }
            }
        }
        return left.equals(right);
    }

    private static Map<String, Long> stackAmounts(List<StoredStack> values) {
        Map<String, Long> amounts = new java.util.HashMap<>();
        if (values != null) {
            for (StoredStack value : values) {
                if (value != null && !value.key.isEmpty() && value.amount > 0) {
                    amounts.merge(value.key, value.amount, StorageNetworkIntegration::safeAdd);
                }
            }
        }
        return amounts;
    }

    static List<StoredFluid> mergeFluids(List<StoredFluid> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Map<String, Long> amounts = new java.util.LinkedHashMap<>();
        for (StoredFluid value : values) {
            if (value != null && !value.key.isEmpty() && value.amount > 0) {
                amounts.merge(value.key, value.amount, StorageNetworkIntegration::safeAdd);
            }
        }
        return amounts.entrySet().stream()
            .map(entry -> new StoredFluid(entry.getKey(), entry.getValue()))
            .toList();
    }

    private static long safeAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, left + right);
    }

    public static Boolean tryFillCraftingGrid(
        AbstractContainerMenu menu,
        List<ItemStack> templates,
        boolean send,
        String recipeId
    ) {
        if (TomsStorageDriver.isCraftingTerminalMenu(menu)) {
            return send ? TomsStorageDriver.requestFill(menu, recipeId) : Boolean.TRUE;
        }
        Boolean ae2 = Ae2StorageIntegration.tryFillCraftingGrid(menu, templates, send);
        if (ae2 != null) {
            return ae2;
        }
        if (isRs1Menu(menu)) {
            return sendRs1Recipe(menu, templates, send);
        }
        if (isRs2Menu(menu)) {
            return sendRs2Recipe(menu, templates, send);
        }
        if (isBeyondMenu(menu)) {
            return sendBeyondRecipe(menu, templates, send);
        }
        if (isBetterBeyondMenu(menu)) {
            return sendBetterBeyondRecipe(menu, templates, send);
        }
        return null;
    }

    static Boolean craftingGridMatches(Object menu, List<ItemStack> templates) {
        Boolean ae2 = Ae2StorageIntegration.craftingGridMatches(menu, templates);
        if (ae2 != null) {
            return ae2;
        }
        try {
            List<ItemStack> actual = new ArrayList<>(9);
            if (isRs1Menu(menu)) {
                Object grid = invokeNoArg(menu, "getGrid");
                Object matrix = invokeNoArg(grid, "getCraftingMatrix");
                readContainer(matrix, actual, 9);
            } else if (isRs2Menu(menu)) {
                Object slots = invokeNoArg(menu, "getCraftingMatrixSlots");
                if (!(slots instanceof List<?> list) || list.size() < 9) {
                    return false;
                }
                for (int i = 0; i < 9; i++) {
                    Object slot = list.get(i);
                    actual.add(stackOf(invokeNoArg(slot, "getItem")));
                }
            } else if (isBeyondMenu(menu)) {
                int start = intValue(readField(menu, "craftSlotStartIndex"));
                int end = intValue(readField(menu, "craftSlotEndIndex"));
                if (start < 0 || end - start < 9) {
                    return false;
                }
                for (int i = 0; i < 9; i++) {
                    actual.add(stackOf(invokeNoArg(invoke(menu, "getSlot", int.class, start + i), "getItem")));
                }
            } else if (isBetterBeyondMenu(menu)) {
                List<Integer> slots = betterBeyondCraftingSlotIds(menu);
                if (slots.size() < 9) {
                    return false;
                }
                for (int index = 0; index < 9; index++) {
                    actual.add(stackOf(menu instanceof AbstractContainerMenu container
                        ? container.getSlot(slots.get(index)).getItem() : null));
                }
            } else if (isIntegratedTerminalMenu(menu)) {
                Object commonTab = integratedSelectedCommonTab(menu);
                Object matrix = invokeNoArg(commonTab, "getInventoryCrafting");
                readContainer(matrix, actual, 9);
            } else {
                return null;
            }
            if (actual.size() < templates.size()) {
                return false;
            }
            for (int i = 0; i < templates.size(); i++) {
                ItemStack expected = templates.get(i);
                ItemStack present = actual.get(i);
                if (expected == null || expected.isEmpty()) {
                    if (!present.isEmpty()) {
                        return false;
                    }
                } else if (present.isEmpty()
                    || !RecipeTreeData.ingredientKey(expected).equals(RecipeTreeData.ingredientKey(present))) {
                    return false;
                }
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    public static int craftingResultSlot(AbstractContainerMenu menu) {
        int ae2 = Ae2StorageIntegration.craftingResultSlot(menu);
        if (ae2 >= 0 || menu == null) {
            return ae2;
        }

        if (menu instanceof RecipeBookMenu<?, ?> book && book.getSize() > 0) {
            int index = book.getResultSlotIndex();
            if (index >= 0 && index < menu.slots.size()) {
                return index;
            }
        }
        for (int i = 0; i < menu.slots.size(); i++) {
            String name = menu.getSlot(i).getClass().getName();
            if (name.endsWith("ResultCraftingGridSlot")
                || name.endsWith("CraftingGridResultSlot")
                || name.endsWith("AutoRefillResultSlot")) {
                return i;
            }
        }
        if (isIntegratedTerminalMenu(menu)) {
            try {
                Object commonTab = integratedSelectedCommonTab(menu);
                Object result = invokeNoArg(commonTab, "getSlotCrafting");
                for (int i = 0; i < menu.slots.size(); i++) {
                    if (menu.getSlot(i) == result) {
                        return i;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {

            }
        }
        return -1;
    }

    public static Boolean takeCraftingResult(Object menu, int resultSlot, boolean send) {
        Boolean ae2 = Ae2StorageIntegration.takeCraftingResult(menu, resultSlot, send);
        if (ae2 != null) {
            return ae2;
        }
        if (TomsStorageDriver.isCraftingTerminalMenu(menu)) {
            return TomsStorageDriver.takeResultOne(menu, resultSlot, send);
        }
        if (!isRefinedStorageMenu(menu) && !isBeyondMenu(menu) && !isIntegratedTerminalMenu(menu)) {
            return null;
        }
        if (!send) {
            return true;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!(menu instanceof AbstractContainerMenu container)
            || minecraft.player == null
            || minecraft.gameMode == null
            || resultSlot < 0
            || resultSlot >= container.slots.size()) {
            return false;
        }
        minecraft.gameMode.handleInventoryMouseClick(
            container.containerId,
            resultSlot,
            0,
            ClickType.PICKUP,
            minecraft.player
        );
        return true;
    }

    public static Boolean putCarriedItemIntoNetwork(Object menu, boolean single) {
        Boolean ae2 = Ae2StorageIntegration.putCarriedItemIntoNetwork(menu, single);
        if (ae2 != null) {
            return ae2;
        }
        try {
            if (isRs1Menu(menu)) {
                Class<?> messageType = Class.forName(
                    "com.refinedmods.refinedstorage.network.grid.GridItemInsertHeldMessage"
                );
                Object message = constructor(messageType, boolean.class).newInstance(single);
                Class<?> rsType = Class.forName("com.refinedmods.refinedstorage.RS");
                Object handler = readStaticField(rsType, "NETWORK_HANDLER");
                Method sender = findCompatibleMethod(handler == null ? null : handler.getClass(), "sendToServer", messageType);
                if (handler != null && sender != null) {
                    sender.invoke(handler, message);
                    return true;
                }
                return false;
            }
            if (isRs2Menu(menu)) {
                Class<?> modeType = Class.forName(
                    "com.refinedmods.refinedstorage.api.network.node.grid.GridInsertMode"
                );
                @SuppressWarnings({"rawtypes", "unchecked"})
                Object mode = Enum.valueOf(
                    (Class<? extends Enum>) modeType.asSubclass(Enum.class),
                    single ? "SINGLE_RESOURCE" : "ENTIRE_RESOURCE"
                );
                Class<?> packets = Class.forName(
                    "com.refinedmods.refinedstorage.common.support.packet.c2s.C2SPackets"
                );
                Method sender = findCompatibleMethod(packets, "sendGridInsert", modeType, boolean.class);
                if (sender != null) {
                    sender.invoke(null, mode, true);
                    return true;
                }
                return false;
            }
            if (isBeyondMenu(menu)) {
                return sendBeyondCursorInsert(menu);
            }
            if (isIntegratedTerminalMenu(menu)) {
                return sendIntegratedCursorInsert(menu);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {

        }
        return null;
    }

    private static Boolean sendBeyondCursorInsert(Object menu) {
        try {
            Slot target = null;
            for (Slot slot : menu instanceof AbstractContainerMenu container ? container.slots : List.<Slot>of()) {
                String name = slot.getClass().getName();
                if (name.contains("beyonddimensions") && name.contains("StackTypedSlot")) {
                    target = slot;
                    break;
                }
            }
            if (target == null) {
                return false;
            }
            Object clickItem = invokeNoArg(target, "getVanillaActualStack");
            Class<?> packetType = Class.forName(
                "com.wintercogs.beyonddimensions.network.packet.c2s.CallSeverClickPacket"
            );
            Constructor<?> packetConstructor = constructor(
                packetType,
                int.class,
                clickItem == null ? Object.class : clickItem.getClass(),
                int.class,
                boolean.class
            );
            if (packetConstructor == null) {
                return false;
            }
            int index = intValue(readField(target, "index"));
            if (index < 0) {
                index = intValue(invokeNoArg(target, "getContainerSlot"));
            }
            Object packet = packetConstructor.newInstance(index, clickItem, 0, false);
            for (String distributorName : List.of(
                "net.neoforged.neoforge.network.PacketDistributor",
                "net.minecraftforge.network.PacketDistributor"
            )) {
                try {
                    Class<?> distributor = Class.forName(distributorName);
                    Method sender = findCompatibleMethod(distributor, "sendToServer", packetType);
                    if (sender != null) {
                        sender.invoke(null, packet);
                        return true;
                    }
                } catch (ClassNotFoundException ignored) {

                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {

        }
        return false;
    }

    private static Boolean sendIntegratedCursorInsert(Object menu) {
        try {
            Object tab = integratedItemClientTab(menu);
            if (tab == null || !(menu instanceof AbstractContainerMenu container)) {
                return false;
            }
            int channel = intValue(invokeNoArg(menu, "getSelectedChannel"));
            Method click = findCompatibleMethod(
                tab.getClass(),
                "handleClick",
                AbstractContainerMenu.class,
                int.class,
                int.class,
                int.class,
                boolean.class,
                boolean.class,
                int.class,
                boolean.class
            );
            if (click == null) {
                return false;
            }
            Object handled = click.invoke(tab, container, channel, 0, 0, false, true, -1, false);
            return Boolean.TRUE.equals(handled);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    static boolean isCraftingMenu(Object menu) {
        return Ae2StorageIntegration.isCraftingMenu(menu)
            || isRs1Menu(menu)
            || isRs2Menu(menu)
            || isBeyondMenu(menu)
            || isIntegratedTerminalMenu(menu);
    }

    static RefinedSnapshot refinedStorageSnapshot(Object menu) {
        try {
            if (isRs1Menu(menu)) {
                AbstractContainerScreen<?> screen = activeContainerScreen();
                if (screen != null && classOrSuper(screen.getClass(), RS1_SCREEN)) {
                    Object view = invokeNoArg(screen, "getView");
                    Object entries = invokeNoArg(view, "getAllStacks");
                    if (entries instanceof Iterable<?> iterable) {
                        List<StoredStack> stacks = new ArrayList<>();
                        List<StoredFluid> fluids = new ArrayList<>();
                        for (Object entry : iterable) {
                            ItemStack stack = rs1EntryStack(entry);
                            long amount = numberValue(invokeNoArg(entry, "getQuantity"));
                            if (!stack.isEmpty() && amount > 0) {
                                stacks.add(new StoredStack(stack, amount));
                            } else {
                                StoredFluid fluid = fluidEntry(entry, "getQuantity", null);
                                if (fluid != null) {
                                    fluids.add(fluid);
                                }
                            }
                        }
                        return new RefinedSnapshot(List.copyOf(stacks), List.copyOf(fluids));
                    }
                }

                Object grid = invokeNoArg(menu, "getGrid");
                List<StoredStack> stacks = new ArrayList<>();
                Object cache = invokeNoArg(grid, "getStorageCache");
                Object list = invokeNoArg(cache, "getList");
                Object entries = invokeNoArg(list, "getStacks");
                if (entries instanceof Iterable<?> iterable) {
                    for (Object entry : iterable) {
                        ItemStack stack = stackOf(entry);
                        if (stack.isEmpty()) {
                            stack = stackOf(invokeNoArg(entry, "getStack"));
                        }
                        if (!stack.isEmpty() && stack.getCount() > 0) {
                            stacks.add(new StoredStack(stack, stack.getCount()));
                        }
                    }
                }
                List<StoredFluid> fluids = new ArrayList<>();
                for (String cacheName : List.of("getFluidStorageCache", "getFluidCache")) {
                    Object fluidCache = invokeNoArg(grid, cacheName);
                    Object fluidList = invokeNoArg(fluidCache, "getList");
                    Object fluidEntries = invokeNoArg(fluidList, "getStacks");
                    fluids.addAll(collectFluidEntries(fluidEntries, null));
                    if (!fluids.isEmpty()) {
                        break;
                    }
                }
                return new RefinedSnapshot(List.copyOf(stacks), List.copyOf(fluids));
            }
            if (isRs2Menu(menu)) {
                Object repository = invokeNoArg(menu, "getRepository");
                Object entries = invokeNoArg(repository, "getViewList");
                if (entries instanceof Iterable<?> iterable) {
                    List<StoredStack> stacks = new ArrayList<>();
                    List<StoredFluid> fluids = new ArrayList<>();
                    for (Object entry : iterable) {
                        String name = entry == null ? "" : entry.getClass().getName();
                        if (name.endsWith("ItemGridResource")) {
                            ItemStack stack = stackOf(invokeNoArg(entry, "getItemStack"));
                            long amount = numberValue(invoke(entry, "getAmount", repository.getClass(), repository));
                            if (amount <= 0) {
                                amount = stack.getCount();
                            }
                            if (!stack.isEmpty() && amount > 0) {
                                stack.setCount(1);
                                stacks.add(new StoredStack(stack, amount));
                            }
                        } else {
                            StoredFluid fluid = fluidEntry(entry, null, repository);
                            if (fluid != null) {
                                fluids.add(fluid);
                            }
                        }
                    }
                    return new RefinedSnapshot(List.copyOf(stacks), List.copyOf(fluids));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {

        }
        return RefinedSnapshot.EMPTY;
    }

    record RefinedSnapshot(List<StoredStack> stacks, List<StoredFluid> fluids) {
        private static final RefinedSnapshot EMPTY = new RefinedSnapshot(List.of(), List.of());
    }

    static List<StoredStack> integratedTerminalStacks(Object menu) {
        if (!isIntegratedTerminalMenu(menu)) {
            return List.of();
        }
        try {
            Object tab = integratedItemClientTab(menu);
            if (tab == null) {
                return List.of();
            }
            int channel = intValue(invokeNoArg(menu, "getSelectedChannel"));
            Object entries = invoke(tab, "createUnfilteredIngredientsView", int.class, channel);
            if (!(entries instanceof Iterable<?> iterable)) {
                return List.of();
            }
            List<StoredStack> result = new ArrayList<>();
            for (Object entry : iterable) {

                if (invokeNoArg(entry, "getCraftingOption") != null) {
                    continue;
                }
                ItemStack stack = stackOf(invokeNoArg(entry, "getInstance"));
                if (!stack.isEmpty() && stack.getCount() > 0) {
                    result.add(new StoredStack(stack, stack.getCount()));
                }
            }
            return result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return List.of();
        }
    }

    static List<StoredFluid> integratedTerminalFluids(Object menu) {
        if (!isIntegratedTerminalMenu(menu)) {
            return List.of();
        }
        try {
            Object tab = integratedFluidClientTab(menu);
            if (tab == null) {
                return List.of();
            }
            int channel = intValue(invokeNoArg(menu, "getSelectedChannel"));
            Object entries = invoke(tab, "createUnfilteredIngredientsView", int.class, channel);
            return collectFluidEntries(entries, null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return List.of();
        }
    }

    static BeyondSnapshot beyondSnapshot(Object menu) {
        if (!isBeyondMenu(menu)) {
            return BeyondSnapshot.EMPTY;
        }

        long now = System.nanoTime();
        if (menu == beyondCachedMenu
            && now - beyondCachedAtNanos < CACHE_FALLBACK_NANOS
            && beyondCached != null) {
            return beyondCached;
        }
        try {
            Object storage = readField(menu, "storage");
            Object entries = invokeNoArg(storage, "getStorage");
            if (!(entries instanceof Iterable<?> iterable)) {
                return BeyondSnapshot.EMPTY;
            }
            List<StoredStack> stacks = new ArrayList<>();
            List<StoredFluid> fluids = new ArrayList<>();
            for (Object entry : iterable) {
                addBeyondEntry(entry, stacks, fluids);
            }
            BeyondSnapshot snapshot = new BeyondSnapshot(List.copyOf(stacks), List.copyOf(fluids));
            beyondCachedMenu = menu;
            beyondCachedAtNanos = now;
            beyondCached = snapshot;
            JERTLog.debug("beyond: snapshot stacks={} fluids={}", stacks.size(), fluids.size());
            return snapshot;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            JERTLog.debug("beyond: snapshot unavailable ({})", exception.getClass().getSimpleName());
            return BeyondSnapshot.EMPTY;
        }
    }

    private static volatile Object beyondCachedMenu;
    private static volatile long beyondCachedAtNanos = Long.MIN_VALUE;
    private static volatile BeyondSnapshot beyondCached;

    private static void addBeyondEntry(
        Object entry, List<StoredStack> stacks, List<StoredFluid> fluids
    ) throws ReflectiveOperationException {
        if (entry == null) {
            return;
        }
        Method craftingOption = findNoArgMethod(entry.getClass(), "getCraftingOption");
        if (craftingOption != null && invokeNoArg(entry, "getCraftingOption") != null) {
            return;
        }
        long amount = numberValue(invokeNoArg(entry, "amount"));
        if (amount <= 0) {
            return;
        }
        Object key = invokeNoArg(entry, "key");
        ItemStack stack = storageItemStack(key);
        if (!stack.isEmpty()) {
            stack.setCount(1);
            stacks.add(new StoredStack(stack, amount));
            return;
        }
        var info = FluidRecipeCompat.describeFluid(key == null ? entry : key);
        if (info.isEmpty() && key != null) {
            Method toStack = findNoArgMethod(key.getClass(), "toStack");
            info = FluidRecipeCompat.describeFluid(toStack == null ? null : invokeNoArg(key, "toStack"));
        }
        if (info.isPresent() && info.get().amount() > 0) {
            fluids.add(new StoredFluid(info.get().key(), Math.max(amount, info.get().amount())));
        }
    }

    record BeyondSnapshot(List<StoredStack> stacks, List<StoredFluid> fluids) {
        private static final BeyondSnapshot EMPTY = new BeyondSnapshot(List.of(), List.of());
    }

    static BetterBeyondSnapshot betterBeyondSnapshot(Object menu) {
        if (!isBetterBeyondMenu(menu)) {
            return BetterBeyondSnapshot.EMPTY;
        }
        try {
            Class<?> stateType = Class.forName(BBD_CLIENT_STORAGE_STATE);

            if (!Boolean.TRUE.equals(invokeStaticNoArg(stateType, "available"))) {
                return BetterBeyondSnapshot.EMPTY;
            }
            Object entries = invokeStaticNoArg(stateType, "entries");
            if (!(entries instanceof Iterable<?> iterable)) {
                return BetterBeyondSnapshot.EMPTY;
            }

            List<StoredStack> stacks = new ArrayList<>();
            for (Object entry : iterable) {
                if (entry == null) {
                    continue;
                }
                ItemStack stack = stackOf(invokeNoArg(entry, "stack"));
                long amount = numberValue(invokeNoArg(entry, "amount"));
                if (stack.isEmpty() || amount <= 0L) {
                    continue;
                }
                stack.setCount(1);
                stacks.add(new StoredStack(stack, amount));
            }
            return new BetterBeyondSnapshot(List.copyOf(stacks), List.of());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return BetterBeyondSnapshot.EMPTY;
        }
    }

    record BetterBeyondSnapshot(List<StoredStack> stacks, List<StoredFluid> fluids) {
        private static final BetterBeyondSnapshot EMPTY = new BetterBeyondSnapshot(List.of(), List.of());
    }

    private static ItemStack storageItemStack(Object value) throws ReflectiveOperationException {
        if (value instanceof ItemStack stack && !stack.isEmpty()) {
            return stack.copy();
        }
        ItemStack stack = stackOf(invokeNoArg(value, "getReadOnlyStack"));
        if (stack.isEmpty()) {
            stack = stackOf(invokeNoArg(value, "toStack"));
        }
        return stack;
    }

    private static List<StoredFluid> collectFluidEntries(Object entries, String amountMethod)
        throws ReflectiveOperationException {
        return collectFluidEntries(entries, amountMethod, null);
    }

    private static List<StoredFluid> collectFluidEntries(
        Object entries,
        String amountMethod,
        Object amountContext
    ) throws ReflectiveOperationException {
        if (!(entries instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<StoredFluid> result = new ArrayList<>();
        for (Object entry : iterable) {
            StoredFluid fluid = fluidEntry(entry, amountMethod, amountContext);
            if (fluid != null) {
                result.add(fluid);
            }
        }
        return result;
    }

    private static StoredFluid fluidEntry(Object entry, String amountMethod, Object amountContext)
        throws ReflectiveOperationException {
        if (entry == null || invokeNoArg(entry, "getCraftingOption") != null) {
            return null;
        }
        Object value = entry;
        Object key = invokeNoArg(entry, "key");
        if (key != null) {
            value = key;
        }

        if (!storageItemStack(value).isEmpty()) {
            return null;
        }
        var info = FluidRecipeCompat.describeFluid(value);
        if (info.isEmpty()) {
            Object instance = invokeNoArg(entry, "getInstance");
            info = FluidRecipeCompat.describeFluid(instance);
        }
        if (info.isEmpty()) {
            return null;
        }
        long amount = amountMethod == null ? 0L : numberValue(invokeNoArg(entry, amountMethod));
        if (amount <= 0) {
            for (String method : List.of("getQuantity", "getTotalQuantity", "getAmount", "amount", "getCount")) {
                amount = numberValue(invokeNoArg(entry, method));
                if (amount > 0) {
                    break;
                }
            }
        }
        if (amount <= 0 && amountContext != null) {
            for (String method : List.of("getAmount", "getQuantity")) {
                amount = numberValue(invoke(entry, method, amountContext.getClass(), amountContext));
                if (amount > 0) {
                    break;
                }
            }
        }
        if (info.get().amount() > 0) {
            amount = Math.max(amount, info.get().amount());
        }
        return amount > 0 ? new StoredFluid(info.get().key(), amount) : null;
    }

    private static Boolean sendRs1Recipe(Object menu, List<ItemStack> templates, boolean send) {
        if (templates.size() != 9) {
            return false;
        }
        if (!send) {
            return true;
        }
        try {
            Class<?> messageType = Class.forName(
                "com.refinedmods.refinedstorage.network.grid.GridTransferMessage"
            );
            List<List<ItemStack>> inputs = new ArrayList<>(9);
            for (int i = 0; i < 9; i++) {
                ItemStack stack = templates.get(i);
                inputs.add(stack == null || stack.isEmpty() ? List.of() : List.of(stack.copy()));
            }
            Object message;
            Constructor<?> listConstructor = findConstructor(messageType, List.class);
            if (listConstructor != null) {
                message = listConstructor.newInstance(inputs);
            } else {
                ItemStack[][] recipe = new ItemStack[9][];
                for (int i = 0; i < inputs.size(); i++) {
                    recipe[i] = inputs.get(i).toArray(ItemStack[]::new);
                }
                Constructor<?> arrayConstructor = findConstructor(messageType, ItemStack[][].class);
                if (arrayConstructor == null) {
                    return false;
                }
                message = arrayConstructor.newInstance((Object) recipe);
            }
            Class<?> rsType = Class.forName("com.refinedmods.refinedstorage.RS");
            Object handler = readStaticField(rsType, "NETWORK_HANDLER");
            Method sender = findCompatibleMethod(handler.getClass(), "sendToServer", messageType);
            if (sender == null) {
                return false;
            }
            sender.invoke(handler, message);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static Boolean sendRs2Recipe(Object menu, List<ItemStack> templates, boolean send) {
        if (templates.size() != 9) {
            return false;
        }
        if (!send) {
            return true;
        }
        try {
            Class<?> itemResourceType = Class.forName(
                "com.refinedmods.refinedstorage.common.support.resource.ItemResource"
            );
            Method ofStack = findCompatibleMethod(itemResourceType, "ofItemStack", ItemStack.class);
            if (ofStack == null) {
                return false;
            }
            List<List<Object>> recipe = new ArrayList<>(9);
            for (ItemStack template : templates) {
                if (template == null || template.isEmpty()) {
                    recipe.add(List.of());
                } else {
                    recipe.add(List.of(ofStack.invoke(null, template.copy())));
                }
            }
            Class<?> packets = Class.forName(
                "com.refinedmods.refinedstorage.common.support.packet.c2s.C2SPackets"
            );
            Method sender = findCompatibleMethod(packets, "sendCraftingGridRecipeTransfer", List.class);
            if (sender == null) {
                return false;
            }
            sender.invoke(null, recipe);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static Boolean sendBeyondRecipeModern(Object menu, List<ItemStack> templates) {
        try {
            Class<?> keyType = Class.forName("com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey");
            Constructor<?> keyConstructor = constructor(keyType, ItemStack.class);
            Object empty = keyType.getField("EMPTY").get(null);
            Class<?> workbenchType = Class.forName("net.minecraft.resources.ResourceLocation");
            Method fromPath = workbenchType.getMethod("fromNamespaceAndPath", String.class, String.class);
            Object craftingWorkbench = fromPath.invoke(null, "beyonddimensions", "crafting");

            List<Object> keys = new ArrayList<>(9);
            List<Long> amounts = new ArrayList<>(9);
            for (ItemStack template : templates) {
                if (template == null || template.isEmpty()) {
                    keys.add(empty);
                    amounts.add(0L);
                } else {
                    keys.add(keyConstructor.newInstance(template.copy()));
                    amounts.add(1L);
                }
            }

            Object commands = invokeNoArg(menu, "commands");
            Method recipe = findCompatibleMethod(
                commands.getClass(), "recipe",
                workbenchType, List.class, List.class, boolean.class, workbenchType
            );
            if (recipe == null) {
                return null;
            }
            recipe.setAccessible(true);
            Object queued = recipe.invoke(commands, craftingWorkbench, keys, amounts, false, null);
            JERTLog.debug("beyond: modern fillRecipe queued={} via commands.recipe", queued);
            return Boolean.TRUE.equals(queued);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static Boolean sendBeyondRecipe(Object menu, List<ItemStack> templates, boolean send) {
        if (templates.size() != 9) {
            return false;
        }
        if (!send) {
            return true;
        }

        if (classOrSuper(menu.getClass(), BD_MENU)) {
            Boolean modern = sendBeyondRecipeModern(menu, templates);
            if (modern != null) {
                return modern;
            }
        }
        try {
            Class<?> keyType = Class.forName(
                "com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey"
            );
            Field emptyField = findField(keyType, "EMPTY");
            Object empty = emptyField == null ? null : emptyField.get(null);
            Constructor<?> keyConstructor = constructor(keyType, ItemStack.class);
            List<Object> keys = new ArrayList<>(9);
            List<Long> amounts = new ArrayList<>(9);
            for (ItemStack template : templates) {
                if (template == null || template.isEmpty()) {
                    keys.add(empty);
                    amounts.add(0L);
                } else {
                    keys.add(keyConstructor.newInstance(template.copy()));
                    amounts.add(1L);
                }
            }
            Class<?> packetType = Class.forName(
                "com.wintercogs.beyonddimensions.network.packet.c2s.RecipeFillC2SPacket"
            );
            Object packet = constructor(packetType, List.class, List.class).newInstance(keys, amounts);
            for (String distributorName : List.of(
                "net.neoforged.neoforge.network.PacketDistributor",
                "net.minecraftforge.network.PacketDistributor"
            )) {
                try {
                    Class<?> distributor = Class.forName(distributorName);
                    Method sendToServer = findCompatibleMethod(distributor, "sendToServer", packetType);
                    if (sendToServer != null) {
                        sendToServer.invoke(null, packet);
                        return true;
                    }
                } catch (ClassNotFoundException ignored) {

                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {

        }
        return false;
    }

    private static Boolean sendBetterBeyondRecipe(Object menu, List<ItemStack> templates, boolean send) {
        if (templates.size() != 9) {
            return null;
        }
        List<Integer> slotIds = betterBeyondCraftingSlotIds(menu);
        if (slotIds.size() < 9) {
            return null;
        }
        try {
            Class<?> fillType = Class.forName(BBD_RECIPE_FILL);
            Constructor<?> fillConstructor = constructor(fillType, int.class, ItemStack.class, int.class);
            Class<?> handlerType = Class.forName(BBD_NETWORK_HANDLER);
            Method fillRecipe = findCompatibleMethod(handlerType, "fillRecipe", List.class);
            boolean extendedFill = false;
            if (fillRecipe == null) {

                fillRecipe = findCompatibleMethod(
                    handlerType, "fillRecipe", List.class, boolean.class, boolean.class
                );
                extendedFill = fillRecipe != null;
            }
            if (fillRecipe == null) {
                return false;
            }
            if (send) {
                List<Object> fills = new ArrayList<>(9);
                for (int index = 0; index < 9; index++) {
                    ItemStack stack = templates.get(index) == null
                        ? ItemStack.EMPTY : templates.get(index).copy();
                    int amount = stack.isEmpty() ? 0 : Math.max(1, stack.getCount());
                    if (amount > 0) {
                        stack.setCount(1);
                    }
                    fills.add(fillConstructor.newInstance(slotIds.get(index), stack, amount));
                }
                if (extendedFill) {
                    fillRecipe.invoke(null, fills, false, false);
                } else {
                    fillRecipe.invoke(null, fills);
                }
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static List<Integer> betterBeyondCraftingSlotIds(Object menu) {
        if (!(menu instanceof AbstractContainerMenu container) || !isBetterBeyondMenu(menu)) {
            return List.of();
        }
        List<Integer> result = new ArrayList<>(9);
        for (int index = 0; index < container.slots.size(); index++) {
            Slot slot = container.getSlot(index);
            if (slot.container != null
                && slot.container.getClass().getName().endsWith("CraftingContainer")) {
                result.add(index);
            }
        }
        return result.size() < 9 ? List.of() : List.copyOf(result.subList(0, 9));
    }

    static boolean isRefinedStorageMenu(Object menu) {
        return isRs1Menu(menu) || isRs2Menu(menu);
    }

    private static boolean isRs1Menu(Object menu) {
        return menu != null && classOrSuper(menu.getClass(), RS1_MENU);
    }

    private static boolean isRs2Menu(Object menu) {
        return menu != null && classOrSuper(menu.getClass(), RS2_MENU);
    }

    public static boolean isBeyondMenu(Object menu) {
        return menu != null
            && (classOrSuper(menu.getClass(), BD_MENU) || classOrSuper(menu.getClass(), BD_MENU_LEGACY));
    }

    static boolean isBetterBeyondMenu(Object menu) {
        if (!(menu instanceof AbstractContainerMenu container)) {
            return false;
        }
        if (classOrInterface(container.getClass(), BBD_MENU_ACCESS)) {
            return true;
        }
        for (Slot slot : container.slots) {
            if (slot != null && BBD_NETWORK_SLOT.equals(slot.getClass().getName())) {
                return true;
            }
        }
        return false;
    }

    static boolean isIntegratedTerminalMenu(Object menu) {
        return menu != null && classOrSuper(menu.getClass(), IT_MENU);
    }

    public static void renderVirtualStorageHighlights(
        GuiGraphics graphics,
        AbstractContainerScreen<?> screen
    ) {
        if (graphics == null || screen == null || !RecipeTreeFavorites.isActive()) {
            return;
        }

        RecipeTreeFavorites.refreshThrottled();
        try {
            if (classOrSuper(screen.getClass(), RS1_SCREEN)) {
                renderRs1Highlights(graphics, screen);
            }
            Object menu = screen.getMenu();
            if (isRs2Menu(menu) || isRs2Screen(screen)) {
                renderRs2Highlights(graphics, screen);
            }
            if (Ae2StorageIntegration.isCraftingMenu(menu)
                || classOrSuper(screen.getClass(), "appeng.client.gui.me.common.MEStorageScreen")) {
                renderAe2Highlights(graphics, screen);
            }

        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {

        }
    }

    public static void renderIntegratedTerminalHighlightsBeforeTooltip(
        GuiGraphics graphics,
        AbstractContainerScreen<?> screen
    ) {
        if (graphics == null || screen == null || !RecipeTreeFavorites.isActive()) {
            return;
        }
        RecipeTreeFavorites.refreshThrottled();
        try {
            if (classOrSuper(screen.getClass(), IT_SCREEN)) {
                renderIntegratedHighlights(graphics, screen);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {

        }
    }

    private static void renderRs1Highlights(GuiGraphics graphics, AbstractContainerScreen<?> screen)
        throws ReflectiveOperationException {
        Object view = invokeNoArg(screen, "getView");
        Object entries = invokeNoArg(view, "getStacks");
        if (!(entries instanceof List<?> list)) {
            return;
        }
        int rowOffset = Math.max(0, intValue(invokeNoArg(screen, "getCurrentOffset")));
        int rows = Math.max(0, intValue(invokeNoArg(screen, "getVisibleRows")));
        int start = rowOffset * 9;
        for (int local = 0; local < rows * 9 && start + local < list.size(); local++) {
            ItemStack stack = rs1EntryStack(list.get(start + local));
            int x = screen.getGuiLeft() + 8 + local % 9 * 18;
            int y = screen.getGuiTop() + 19 + local / 9 * 18;
            drawHighlight(graphics, x, y, stack);
            if (stack.isEmpty()) {
                FluidRecipeCompat.describeFluid(list.get(start + local))
                    .ifPresent(info -> drawHighlightKey(graphics, x, y, info.key()));
            }
        }
    }

    private static void renderRs2Highlights(GuiGraphics graphics, AbstractContainerScreen<?> screen)
        throws ReflectiveOperationException {
        Object repository = invokeNoArg(screen.getMenu(), "getRepository");
        Object entries = invokeNoArg(repository, "getViewList");
        if (!(entries instanceof List<?> list)) {
            return;
        }
        int pinRows = Math.max(0, intValue(readField(screen, "pinRows")));
        int visibleRows = intValue(invokeNoArg(screen, "getVisibleRows"));
        if (visibleRows <= 0) {
            visibleRows = intValue(readField(screen, "visibleRows"));
        }
        int scrollOffset = Math.max(0, intValue(invokeNoArg(screen, "getScrollbarOffset")));
        int rowOffset = scrollOffset / 18;
        int partialPixelOffset = scrollOffset % 18;
        int resourceRows = Math.max(0, visibleRows - pinRows);
        int maxCells = resourceRows * 9;
        int first = rowOffset * 9;
        int x0 = screen.getGuiLeft() + 8;
        int y0 = screen.getGuiTop() + 20 + pinRows * 18 - partialPixelOffset;
        for (int local = 0; local < maxCells && first + local < list.size(); local++) {
            int row = local / 9;
            int column = local % 9;
            int x = x0 + column * 18;
            int y = y0 + row * 18;
            Object entry = list.get(first + local);
            ItemStack stack = stackOf(invokeNoArg(entry, "getItemStack"));
            drawHighlight(graphics, x, y, stack);
            if (stack.isEmpty()) {
                FluidRecipeCompat.describeFluid(entry)
                    .ifPresent(info -> drawHighlightKey(graphics, x, y, info.key()));
            }
        }
    }

    private static void renderAe2Highlights(GuiGraphics graphics, AbstractContainerScreen<?> screen)
        throws ReflectiveOperationException {
        for (Slot slot : screen.getMenu().slots) {
            if (!slot.getClass().getName().endsWith("RepoSlot")) {
                continue;
            }
            Object entry = invokeNoArg(slot, "getEntry");
            Object key = invokeNoArg(entry, "getWhat");
            FluidRecipeCompat.describeFluid(key)
                .ifPresent(info -> drawHighlightKey(
                    graphics,
                    screen.getGuiLeft() + slot.x,
                    screen.getGuiTop() + slot.y,
                    info.key()
                ));
        }
    }

    public static void renderBetterBeyondHighlights(
        GuiGraphics graphics,
        AbstractContainerScreen<?> screen
    ) {
        if (graphics == null || screen == null || !RecipeTreeFavorites.isActive()
            || !isBetterBeyondMenu(screen.getMenu())
            || !isBetterBeyondSidebarEnabled(screen)) {
            return;
        }
        try {
            for (Slot slot : screen.getMenu().slots) {
                if (slot == null
                    || !BBD_NETWORK_SLOT.equals(slot.getClass().getName())
                    || !slot.hasItem()) {
                    continue;
                }
                drawHighlight(
                    graphics,
                    screen.getGuiLeft() + slot.x,
                    screen.getGuiTop() + slot.y,
                    slot.getItem()
                );
            }
        } catch (RuntimeException | LinkageError ignored) {

        }
    }

    private static void renderIntegratedHighlights(GuiGraphics graphics, AbstractContainerScreen<?> screen)
        throws ReflectiveOperationException {
        Object menu = screen.getMenu();
        Object tab = integratedItemClientTab(menu);
        if (tab == null) {
            tab = integratedFluidClientTab(menu);
        }
        if (tab == null) {
            return;
        }
        int channel = intValue(invokeNoArg(menu, "getSelectedChannel"));
        int firstRow = Math.max(0, intValue(invokeNoArg(screen, "getSelectedFirstRow")));
        int rowLength = Math.max(1, intValue(invokeNoArg(screen, "getSlotRowLength")));
        int visibleRows = Math.max(0, intValue(invokeNoArg(screen, "getSlotVisibleRows")));
        int start = firstRow * rowLength;
        Object visible = invokeThreeInts(tab, "getSlots", channel, start, visibleRows * rowLength);
        if (!(visible instanceof List<?> list)) {
            return;
        }
        for (int local = 0; local < list.size(); local++) {
            Object instance = invokeNoArg(list.get(local), "getInstance");
            ItemStack stack = stackOf(instance);
            Object value = invoke(screen, "getStorageSlotRect", int.class, start + local);

            if (value instanceof Rect2i rect) {

                drawHighlight(
                    graphics,
                    rect.getX() - screen.getGuiLeft() - 1,
                    rect.getY() - screen.getGuiTop() - 1,
                    stack,
                    RenderLayers.OVERLAY_BADGE
                );
                if (stack.isEmpty()) {
                    FluidRecipeCompat.describeFluid(instance)
                        .ifPresent(info -> drawHighlightKey(
                            graphics,
                            rect.getX() - screen.getGuiLeft() - 1,
                            rect.getY() - screen.getGuiTop() - 1,
                            info.key(),
                            RenderLayers.OVERLAY_BADGE
                        ));
                }
            }
        }
    }

    private static AbstractContainerScreen<?> activeContainerScreen() {
        Object current = Minecraft.getInstance().screen;
        for (int depth = 0; depth < 4 && current instanceof RecipeTreeScreen tree; depth++) {
            current = tree.parentScreen();
        }
        return current instanceof AbstractContainerScreen<?> screen ? screen : null;
    }

    private static boolean isBetterBeyondSidebarEnabled(AbstractContainerScreen<?> screen) {
        if (screen == null) {
            return false;
        }
        try {
            Object value = invokeNoArg(screen, "bbd$isSidebarEnabled");
            return !(value instanceof Boolean) || (Boolean) value;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return true;
        }
    }

    private static void drawHighlight(GuiGraphics graphics, int x, int y, ItemStack stack) {
        drawHighlight(graphics, x, y, stack, RenderLayers.AMOUNT_BADGE);
    }

    private static void drawHighlight(GuiGraphics graphics, int x, int y, ItemStack stack, int z) {
        if (stack != null && !stack.isEmpty()) {
            String key = RecipeTreeData.ingredientKey(stack);
            boolean direct = RecipeTreeFavorites.isFinalProductKey(key)
                || RecipeTreeFavorites.isIntermediateKey(key)
                || RecipeTreeFavorites.isRequiredKey(key);
            if (direct) {
                drawHighlightKey(graphics, x, y, key, z);
            } else {
                FluidRecipeCompat.displayFluidKey(stack)
                    .ifPresent(fluidKey -> drawHighlightKey(graphics, x, y, fluidKey, z));
            }
        }
    }

    private static void drawHighlightKey(GuiGraphics graphics, int x, int y, String key) {
        drawHighlightKey(graphics, x, y, key, RenderLayers.AMOUNT_BADGE);
    }

    private static void drawHighlightKey(GuiGraphics graphics, int x, int y, String key, int z) {
        boolean finalProduct = RecipeTreeFavorites.isFinalProductKey(key);
        boolean intermediate = RecipeTreeFavorites.isIntermediateKey(key);
        boolean required = RecipeTreeFavorites.isRequiredKey(key);
        if (!finalProduct && !intermediate && !required) {
            return;
        }
        int fill = finalProduct ? 0x4433CC66 : (intermediate ? 0x44FF2222 : 0x3300BBFF);
        int border = finalProduct ? 0xDD66FF88 : (intermediate ? 0xDDFF5555 : 0xCC55DDFF);
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, z);
        graphics.fill(x, y, x + 16, y + 16, fill);
        graphics.fill(x, y, x + 16, y + 1, border);
        graphics.fill(x, y + 15, x + 16, y + 16, border);
        graphics.fill(x, y, x + 1, y + 16, border);
        graphics.fill(x + 15, y, x + 16, y + 16, border);
        graphics.pose().popPose();
    }

    private static ItemStack rs1EntryStack(Object entry) throws ReflectiveOperationException {
        ItemStack stack = stackOf(entry);
        if (stack.isEmpty()) {
            stack = stackOf(invokeNoArg(entry, "getIngredient"));
        }
        if (stack.isEmpty()) {
            stack = stackOf(invokeNoArg(entry, "getStack"));
        }
        return stack;
    }

    private static Object integratedSelectedCommonTab(Object menu) throws ReflectiveOperationException {
        Object selected = invokeNoArg(menu, "getSelectedTab");
        return selected instanceof String id
            ? invoke(menu, "getTabCommon", String.class, id)
            : null;
    }

    private static Object integratedItemClientTab(Object menu) throws ReflectiveOperationException {
        if (!isIntegratedTerminalMenu(menu)) {
            return null;
        }
        Object selected = invokeNoArg(menu, "getSelectedTab");
        if (selected instanceof String id) {
            Object tab = invoke(menu, "getTabClient", String.class, id);
            if (isIntegratedItemTab(tab)) {
                return tab;
            }
        }
        Object tabs = invokeNoArg(menu, "getTabsClient");
        if (tabs instanceof Map<?, ?> map) {
            for (Object tab : map.values()) {
                if (isIntegratedItemTab(tab)) {
                    return tab;
                }
            }
        }
        return null;
    }

    private static Object integratedFluidClientTab(Object menu) throws ReflectiveOperationException {
        if (!isIntegratedTerminalMenu(menu)) {
            return null;
        }
        Object selected = invokeNoArg(menu, "getSelectedTab");
        if (selected instanceof String id) {
            Object tab = invoke(menu, "getTabClient", String.class, id);
            if (isIntegratedFluidTab(tab)) {
                return tab;
            }
        }
        Object tabs = invokeNoArg(menu, "getTabsClient");
        if (tabs instanceof Map<?, ?> map) {
            for (Object tab : map.values()) {
                if (isIntegratedFluidTab(tab)) {
                    return tab;
                }
            }
        }
        return null;
    }

    private static boolean isIntegratedItemTab(Object tab) {
        if (tab == null) {
            return false;
        }
        String name = tab.getClass().getName();
        if (name.contains("TerminalStorageTabIngredientComponentItemStack")) {
            return true;
        }
        try {
            Object component = invokeNoArg(tab, "getIngredientComponent");
            if (component == null) {
                return false;
            }
            String componentName = String.valueOf(invokeNoArg(component, "getName"));
            return !componentName.toLowerCase(java.util.Locale.ROOT).contains("fluid");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static boolean isIntegratedFluidTab(Object tab) {
        if (tab == null) {
            return false;
        }
        String name = tab.getClass().getName();
        if (name.contains("FluidStack")) {
            return true;
        }
        try {
            Object component = invokeNoArg(tab, "getIngredientComponent");
            if (component == null) {
                return false;
            }
            String componentName = String.valueOf(invokeNoArg(component, "getName"));
            return componentName.toLowerCase(java.util.Locale.ROOT).contains("fluid");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static boolean classOrSuper(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (name.equals(current.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean classOrInterface(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (name.equals(current.getName())) {
                return true;
            }
            for (Class<?> interfaceType : current.getInterfaces()) {
                if (classOrInterface(interfaceType, name)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean matchesHighlightKey(ItemStack stack, Set<String> keys) {
        String key = RecipeTreeData.ingredientKey(stack);
        return keys.contains(key)
            || FluidRecipeCompat.displayFluidKey(stack).map(keys::contains).orElse(false);
    }

    @SuppressWarnings("unchecked")
    private static boolean isRs2Screen(AbstractContainerScreen<?> screen) {
        if (screen == null) {
            return false;
        }
        String name = screen.getClass().getName();
        return name.contains("refinedstorage") && name.contains("GridScreen");
    }

    private static void readContainer(Object container, List<ItemStack> target, int size) throws ReflectiveOperationException {
        int actualSize = intValue(invokeNoArg(container, "getContainerSize"));
        if (actualSize <= 0) {
            actualSize = intValue(invokeNoArg(container, "size"));
        }
        for (int i = 0; i < Math.min(size, actualSize); i++) {
            target.add(stackOf(invoke(container, "getItem", int.class, i)));
        }
    }

    private static ItemStack stackOf(Object value) {
        return value instanceof ItemStack stack ? stack.copy() : ItemStack.EMPTY;
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : -1;
    }

    private static long numberValue(Object value) {
        return value instanceof Number number ? Math.max(0L, number.longValue()) : 0L;
    }

    private static Object readField(Object target, String name) throws ReflectiveOperationException {
        Field field = findField(target == null ? null : target.getClass(), name);
        return field == null ? null : field.get(target);
    }

    private static Object readStaticField(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = findField(type, name);
        return field == null ? null : field.get(null);
    }

    private static Object invokeNoArg(Object target, String name) throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        Method method = findNoArgMethod(target.getClass(), name);
        return method == null ? null : method.invoke(target);
    }

    private static Object invokeStaticNoArg(Class<?> type, String name) throws ReflectiveOperationException {
        Method method = findNoArgMethod(type, name);
        return method == null ? null : method.invoke(null);
    }

    private static Object invoke(Object target, String name, Class<?> parameterType, Object argument)
        throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        Method method = findCompatibleMethod(target.getClass(), name, parameterType);
        return method == null ? null : method.invoke(target, argument);
    }

    private static Constructor<?> constructor(Class<?> type, Class<?>... parameterTypes)
        throws NoSuchMethodException {
        Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
        try {
            constructor.trySetAccessible();
        } catch (RuntimeException ignored) {

        }
        return constructor;
    }

    private static Constructor<?> findConstructor(Class<?> type, Class<?>... parameterTypes) {
        try {
            return constructor(type, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static Object invokeThreeInts(Object target, String name, int first, int second, int third)
        throws ReflectiveOperationException {
        if (target == null) {
            return null;
        }
        Method method = findCompatibleMethod(target.getClass(), name, int.class, int.class, int.class);
        return method == null ? null : method.invoke(target, first, second, third);
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                try {
                    field.trySetAccessible();
                } catch (RuntimeException ignored) {

                }
                return field;
            } catch (NoSuchFieldException ignored) {

            }
        }
        return null;
    }

    private static Method findNoArgMethod(Class<?> type, String name) {
        return findCompatibleMethod(type, name);
    }

    private static Method findCompatibleMethod(Class<?> type, String name, Class<?>... arguments) {
        if (type == null) {
            return null;
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!method.getName().equals(name) || parameters.length != arguments.length) {
                    continue;
                }
                boolean compatible = true;
                for (int i = 0; i < parameters.length; i++) {
                    if (arguments[i] != null && !parameters[i].isAssignableFrom(arguments[i])) {
                        compatible = false;
                        break;
                    }
                }
                if (compatible) {
                    try {
                        method.trySetAccessible();
                    } catch (RuntimeException ignored) {

                    }
                    return method;
                }
            }
        }
        for (Method method : type.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!method.getName().equals(name) || parameters.length != arguments.length) {
                continue;
            }
            boolean compatible = true;
            for (int i = 0; i < parameters.length; i++) {
                if (arguments[i] != null && !parameters[i].isAssignableFrom(arguments[i])) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) {
                return method;
            }
        }
        return null;
    }

    public static final class StoredStack {
        private final ItemStack stack;
        private final String key;
        private final long amount;

        public StoredStack(ItemStack stack, long amount) {
            this.stack = stack.copy();
            this.stack.setCount(1);
            this.key = RecipeTreeData.ingredientKey(this.stack);
            this.amount = amount;
        }

        public ItemStack stack() {
            return stack;
        }

        public long amount() {
            return amount;
        }
    }

    public static final class StoredFluid {
        private final String key;
        private final long amount;

        public StoredFluid(String key, long amount) {
            this.key = key == null ? "" : key;
            this.amount = Math.max(0L, amount);
        }

        String key() {
            return key;
        }

        long amount() {
            return amount;
        }
    }
}
