package com.slwold.jeirecipetree.client.storage;

import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class TomsStorageDriver implements StorageDriver {
    public static final TomsStorageDriver INSTANCE = new TomsStorageDriver();

    private static final String MENU_CLASS = "com.tom.storagemod.menu.StorageTerminalMenu";
    private static final String CRAFTING_MENU_CLASS = "com.tom.storagemod.menu.CraftingTerminalMenu";
    private static final String ITEM_STACK_CLASS = "com.tom.storagemod.inventory.StoredItemStack";
    private static final String SYNC_CLASS = "com.tom.storagemod.util.TerminalSyncManager";
    private static final String SLOT_ACTION_CLASS = SYNC_CLASS + "$SlotAction";

    private TomsStorageDriver() {
    }

    static boolean isTerminalMenu(Object menu) {
        return menu != null && classOrSuper(menu.getClass(), MENU_CLASS);
    }

    public static boolean isCraftingTerminalMenu(Object menu) {
        return menu != null && classOrSuper(menu.getClass(), CRAFTING_MENU_CLASS);
    }

    static boolean requestFill(Object menu, String recipeId) {
        if (!isCraftingTerminalMenu(menu) || recipeId == null || recipeId.isBlank()) {
            return false;
        }
        try {
            CompoundTag tag = new CompoundTag();
            tag.putString("fill", recipeId);
            Method send = menu.getClass().getMethod("sendMessage", CompoundTag.class);
            send.invoke(menu, tag);
            JERTLog.info("toms fill -> {} menu={}", recipeId, menu.getClass().getSimpleName());
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    public static Boolean takeResultOne(Object menu, int resultSlot, boolean send) {
        if (!send) {
            return Boolean.TRUE;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!(menu instanceof AbstractContainerMenu container)
            || minecraft.player == null || minecraft.gameMode == null
            || resultSlot < 0 || resultSlot >= container.slots.size()) {
            return false;
        }
        minecraft.gameMode.handleInventoryMouseClick(
            container.containerId, resultSlot, 0, ClickType.PICKUP, minecraft.player);
        JERTLog.debug("toms: pickup one result via PICKUP (no QUICK_MOVE refill loop)");
        return Boolean.TRUE;
    }

    public static boolean fillGridFromInventory(
        AbstractContainerMenu menu,
        CraftingRecipe recipe,
        Player player
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (recipe == null || player == null || minecraft.gameMode == null
            || menu == null || menu.slots.size() < 10) {
            return false;
        }
        if (!clearGrid(menu, minecraft)) {
            return false;
        }
        List<Ingredient> ingredients = recipe.getIngredients();
        int width = recipe instanceof ShapedRecipe shaped ? Math.max(1, shaped.getWidth()) : 0;
        for (int i = 0; i < ingredients.size() && i < 9; i++) {
            Ingredient ingredient = ingredients.get(i);
            if (ingredient == null || ingredient.isEmpty()) {
                continue;
            }
            int grid = width > 0 ? (i % width) + (i / width) * 3 : i;
            if (!fillSlot(menu, minecraft, player, ingredient, grid)) {
                return false;
            }
        }
        return true;
    }

    private static boolean clearGrid(AbstractContainerMenu menu, Minecraft minecraft) {
        try {
            menu.getClass().getMethod("clearCraftingContent").invoke(menu);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 0);
        return true;
    }

    private static boolean fillSlot(
        AbstractContainerMenu menu,
        Minecraft minecraft,
        Player player,
        Ingredient ingredient,
        int grid
    ) {
        ItemStack wanted = firstStack(ingredient);
        if (wanted.isEmpty()) {
            return false;
        }
        int source = findPlayerSlot(menu, player, wanted);
        if (source < 0) {
            return false;
        }
        minecraft.gameMode.handleInventoryMouseClick(
            menu.containerId, source, 0, ClickType.PICKUP, player);
        minecraft.gameMode.handleInventoryMouseClick(
            menu.containerId, 1 + grid, 1, ClickType.PICKUP, player);
        minecraft.gameMode.handleInventoryMouseClick(
            menu.containerId, source, 0, ClickType.PICKUP, player);
        ItemStack placed = menu.getSlot(1 + grid).getItem();
        return placed.getCount() >= 1 && ingredient.test(placed);
    }

    @Nullable
    private static ItemStack firstStack(Ingredient ingredient) {
        for (ItemStack stack : ingredient.getItems()) {
            if (!stack.isEmpty()) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static int findPlayerSlot(AbstractContainerMenu menu, Player player, ItemStack wanted) {
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            if (slot.container != player.getInventory() || !slot.hasItem()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (ItemStack.isSameItemSameComponents(stack, wanted) && slot.mayPickup(player)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public int priority() {
        return StorageDrivers.TOMS;
    }

    @Override
    public String id() {
        return "toms-storage";
    }

    @Override
    public boolean handles(Object menu) {
        return isTerminalMenu(menu);
    }

    @Override
    @Nullable
    public StorageNetworkIntegration.StorageSnapshot read(Object menu) {
        if (!isTerminalMenu(menu)) {
            return null;
        }
        try {
            Object list = readField(menu, "itemList");
            if (!(list instanceof Iterable<?> iterable)) {
                return null;
            }
            List<StorageNetworkIntegration.StoredStack> stacks = new ArrayList<>();
            for (Object entry : iterable) {
                if (entry == null) {
                    continue;
                }
                ItemStack stack = stackOf(invokeNoArg(entry, "getStack"));
                long amount = longOf(invokeNoArg(entry, "getQuantity"));
                if (stack.isEmpty() || amount <= 0L) {
                    continue;
                }
                stacks.add(new StorageNetworkIntegration.StoredStack(stack, amount));
            }
            return new StorageNetworkIntegration.StorageSnapshot(stacks, List.of(), System.nanoTime());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Override
    public boolean canWithdraw(Object menu) {
        if (!isTerminalMenu(menu)) {
            return false;
        }
        try {
            return readField(menu, "sync") != null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    @Override
    public boolean requestWithdraw(Object menu, ItemStack stack, int amount) {
        if (stack == null || stack.isEmpty() || amount <= 0) {
            return false;
        }
        try {
            Object sync = readField(menu, "sync");
            Object stored = findItem(menu, stack);
            if (sync == null || stored == null) {
                return false;
            }
            Object action = slotAction("PULL_OR_PUSH_STACK");
            Method send = sync.getClass().getMethod("sendInteract", Class.forName(ITEM_STACK_CLASS),
                Class.forName(SLOT_ACTION_CLASS), boolean.class);
            send.invoke(sync, stored, action, false);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    @Override
    public boolean depositCarried(Object menu) {
        if (!canWithdraw(menu)) {
            return false;
        }
        try {
            Object sync = readField(menu, "sync");
            Object action = slotAction("PULL_OR_PUSH_STACK");
            Method send = sync.getClass().getMethod("sendInteract", Class.forName(ITEM_STACK_CLASS),
                Class.forName(SLOT_ACTION_CLASS), boolean.class);
            send.invoke(sync, null, action, false);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    @Nullable
    private static Object findItem(Object menu, ItemStack wanted) {
        try {
            Object list = readField(menu, "itemList");
            if (!(list instanceof Iterable<?> iterable)) {
                return null;
            }
            for (Object entry : iterable) {
                ItemStack stack = stackOf(invokeNoArg(entry, "getStack"));
                if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, wanted)) {
                    return entry;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
        return null;
    }

    private static boolean classOrSuper(Class<?> type, String name) {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            if (name.equals(cursor.getName())) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static Object readField(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> cursor = target.getClass(); cursor != null; cursor = cursor.getSuperclass()) {
            try {
                Field field = cursor.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {

            }
        }
        throw new NoSuchFieldException(name);
    }

    @Nullable
    private static Object invokeNoArg(Object target, String name) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Object slotAction(String name) throws ReflectiveOperationException {
        return Class.forName(SLOT_ACTION_CLASS).getField(name).get(null);
    }

    private static ItemStack stackOf(Object value) {
        return value instanceof ItemStack stack ? stack : ItemStack.EMPTY;
    }

    private static long longOf(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
