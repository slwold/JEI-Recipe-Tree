package com.slwold.jeirecipetree.client.storage;

import com.slwold.jeirecipetree.client.data.*;
import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RtsStorageDriver implements StorageDriver {
    public static final RtsStorageDriver INSTANCE = new RtsStorageDriver();

    private static final String CONTROLLER_CLASS =
            "com.rtsbuilding.rtsbuilding.client.controller.ClientRtsController";
    private static final String MENU_CLASS =
            "com.rtsbuilding.rtsbuilding.server.menu.RtsCraftTerminalMenu";

    private static boolean readReported;

    private static int lastLoggedCount = -1;

    private RtsStorageDriver() {
    }

    public static boolean isTerminalMenu(Object menu) {
        if (menu == null) {
            return false;
        }
        return MENU_CLASS.equals(menu.getClass().getName());
    }

    @Override
    public int priority() {
        return 45;
    }

    @Override
    public String id() {
        return "rts-building-direct";
    }

    @Override
    public boolean handles(Object menu) {
        return isTerminalMenu(menu);
    }

    @Override
    @Nullable
    public StorageNetworkIntegration.StorageSnapshot read(Object menu) {
        Map<String, Long> totals = readTotals();
        if (totals == null) {
            return null;
        }
        List<StorageNetworkIntegration.StoredStack> stacks = new ArrayList<>();
        for (Map.Entry<String, Long> entry : totals.entrySet()) {
            long amount = entry.getValue() == null ? 0L : entry.getValue();
            if (amount <= 0L || entry.getKey() == null) {
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(entry.getKey());
            if (id == null) {
                continue;
            }
            ItemStack stack = BuiltInRegistries.ITEM.get(id).getDefaultInstance();
            if (stack.isEmpty()) {
                continue;
            }
            stacks.add(new StorageNetworkIntegration.StoredStack(stack, amount));
        }
        return new StorageNetworkIntegration.StorageSnapshot(stacks, List.of(), System.nanoTime());
    }

    @Nullable
    private static Object storageState() {
        try {
            Class<?> controller = Class.forName(CONTROLLER_CLASS);
            Object instance = controller.getMethod("get").invoke(null);
            if (instance == null) {
                return null;
            }
            Field field = controller.getDeclaredField("storageStateManager");
            field.setAccessible(true);
            return field.get(instance);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            return null;
        }
    }

    @Nullable
    private static Map<String, Long> readTotals() {
        Object state = storageState();
        if (state == null) {
            reportReadFailure("storageStateManager 取不到（ClientRtsController#get 或字段缺失）");
            return null;
        }
        Object raw = totalsOf(state);
        if (!(raw instanceof Map<?, ?> map)) {
            reportReadFailure("StorageStateManager 的库存表读不到（方法与字段都不匹配）");
            return null;
        }
        Map<String, Long> copy = new HashMap<>(map.size());
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key && entry.getValue() instanceof Long value) {
                copy.put(key, value);
            }
        }
        if (copy.size() != lastLoggedCount) {
            lastLoggedCount = copy.size();
            JERTLog.summary("rts storage: {} items via official StorageStateManager", copy.size());
        }
        return copy;
    }

    @Nullable
    private static Object totalsOf(Object state) {
        try {
            Method method = state.getClass().getDeclaredMethod("getInternalStorageTotalCounts");
            method.setAccessible(true);
            return method.invoke(state);
        } catch (ReflectiveOperationException | RuntimeException ignored) {

        }
        try {
            Field field = state.getClass().getDeclaredField("storageTotalCounts");
            field.setAccessible(true);
            return field.get(state);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static void reportReadFailure(String reason) {
        if (readReported) {
            return;
        }
        readReported = true;
        JERTLog.warn("rts storage driver disabled: {}", reason);
    }
}
