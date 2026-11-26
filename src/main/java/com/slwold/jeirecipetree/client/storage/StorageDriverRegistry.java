package com.slwold.jeirecipetree.client.storage;

import com.slwold.jeirecipetree.client.*;
import com.slwold.jeirecipetree.client.jei.*;

import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class StorageDriverRegistry {

    public record Result(@Nullable StorageNetworkIntegration.StorageSnapshot snapshot, @Nullable String driverId) {
    }

    private static final CopyOnWriteArrayList<StorageDriver> DRIVERS = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<String> FAILED = new CopyOnWriteArrayList<>();

    private static volatile String lastUsedDriverId;

    private StorageDriverRegistry() {
    }

    public static void register(StorageDriver driver) {
        if (driver == null || DRIVERS.contains(driver)) {
            return;
        }
        DRIVERS.add(driver);

        DRIVERS.sort(Comparator.comparingInt(StorageDriver::priority));
    }

    public static boolean handles(Object menu) {
        for (StorageDriver driver : DRIVERS) {
            try {
                if (driver.handles(menu)) {
                    return true;
                }
            } catch (LinkageError | RuntimeException error) {
                disable(driver, error);
            }
        }
        return false;
    }

    @Nullable
    public static StorageNetworkIntegration.StorageSnapshot read(Object menu) {
        return readWithSource(menu).snapshot();
    }

    public static Result readWithSource(Object menu) {
        for (StorageDriver driver : new ArrayList<>(DRIVERS)) {
            if (!claims(driver, menu)) {
                continue;
            }
            try {
                StorageNetworkIntegration.StorageSnapshot snapshot = driver.read(menu);
                lastUsedDriverId = driver.id();
                return new Result(snapshot, driver.id());
            } catch (LinkageError | RuntimeException error) {
                disable(driver, error);

            }
        }
        lastUsedDriverId = null;
        return new Result(null, null);
    }

    private static boolean claims(StorageDriver driver, Object menu) {
        try {
            return driver.handles(menu);
        } catch (LinkageError | RuntimeException error) {
            disable(driver, error);
            return false;
        }
    }

    @Nullable
    private static StorageDriver claiming(Object menu) {
        for (StorageDriver driver : new ArrayList<>(DRIVERS)) {
            if (claims(driver, menu)) {
                return driver;
            }
        }
        return null;
    }

    public static boolean canWithdraw(Object menu) {
        StorageDriver driver = claiming(menu);
        if (driver == null) {
            return false;
        }
        try {
            return driver.canWithdraw(menu);
        } catch (LinkageError | RuntimeException error) {
            disable(driver, error);
            return false;
        }
    }

    public static boolean requestWithdraw(Object menu, ItemStack stack, int amount) {
        StorageDriver driver = claiming(menu);
        if (driver == null) {
            return false;
        }
        try {
            return driver.requestWithdraw(menu, stack, amount);
        } catch (LinkageError | RuntimeException error) {
            disable(driver, error);
            return false;
        }
    }

    public static boolean depositCarried(Object menu) {
        StorageDriver driver = claiming(menu);
        if (driver == null) {
            return false;
        }
        try {
            return driver.depositCarried(menu);
        } catch (LinkageError | RuntimeException error) {
            disable(driver, error);
            return false;
        }
    }

    private static void disable(StorageDriver driver, Throwable failure) {
        if (DRIVERS.remove(driver)) {
            FAILED.add(driver.id());

            try {
                JERTLog.error("storage driver " + driver.id() + " failed and has been disabled", failure);
            } catch (Throwable ignored) {

            }
        }
    }

    public static List<String> failedDrivers() {
        return List.copyOf(FAILED);
    }

    public static List<String> activeDrivers() {
        List<String> names = new ArrayList<>(DRIVERS.size());
        for (StorageDriver driver : DRIVERS) {
            names.add(driver.priority() + ":" + driver.id());
        }
        return List.copyOf(names);
    }

    public static String lastUsedDriver() {
        String value = lastUsedDriverId;
        return value == null ? "none" : value;
    }

    public static String driverFor(Object menu) {
        for (StorageDriver driver : DRIVERS) {
            try {
                if (driver.handles(menu)) {
                    return driver.id();
                }
            } catch (LinkageError | RuntimeException ignored) {

            }
        }
        return "none";
    }
}
