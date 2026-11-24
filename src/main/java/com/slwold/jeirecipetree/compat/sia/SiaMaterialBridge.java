package com.slwold.jeirecipetree.compat.sia;

import com.slwold.jeirecipetree.client.JERTLog;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

public final class SiaMaterialBridge {

    private static final String MOD_ID = "sophisticateditemactions";
    private static final String CORE_MOD_ID = "sophisticatedcore";
    private static final String HANDLER_CLASS =
        "net.p3pp3rf1y.sophisticateditemactions.common.ItemTransferHandler";
    private static final String RESTOCK_METHOD = "restockRecipeItems";

    private static final int MAX_TRANSIENT_FAILURES = 3;

    private static Method restockRecipeItems;
    private static boolean resolved;
    private static boolean failed;
    private static boolean warnedMissing;
    private static int transientFailures;

    public enum Result {

        SENT,

        UNAVAILABLE,

        TRANSIENT_FAILURE
    }

    private SiaMaterialBridge() {
    }

    public static boolean available() {
        try {
            return ModList.get().isLoaded(MOD_ID) && ModList.get().isLoaded(CORE_MOD_ID);
        } catch (RuntimeException | LinkageError ignored) {

            return false;
        }
    }

    public static boolean resolved() {
        return resolved;
    }

    public static Result restock(Player player, List<List<ItemStack>> groups) {
        if (player == null || groups == null || groups.isEmpty() || failed || !available()) {
            return Result.UNAVAILABLE;
        }
        Method method = method();
        if (method == null) {
            return Result.UNAVAILABLE;
        }
        try {
            method.invoke(null, player, groups);
            transientFailures = 0;
            return Result.SENT;
        } catch (InvocationTargetException exception) {

            return transientFailure(exception.getCause());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            return transientFailure(exception);
        }
    }

    private static Result transientFailure(Throwable cause) {
        transientFailures++;
        String detail = cause == null ? "unknown" : cause.toString();
        if (transientFailures >= MAX_TRANSIENT_FAILURES) {
            failed = true;
            JERTLog.warn("SIA restockRecipeItems failed {} times in a row, disabling bridge for this session: {}",
                MAX_TRANSIENT_FAILURES, detail);
        } else {
            JERTLog.warn("SIA restockRecipeItems failed (attempt {} of {}): {}",
                transientFailures, MAX_TRANSIENT_FAILURES, detail);
        }
        return Result.TRANSIENT_FAILURE;
    }

    private static Method method() {
        if (resolved) {
            return restockRecipeItems;
        }
        if (failed) {
            return null;
        }
        resolved = true;
        try {
            Class<?> handler = Class.forName(HANDLER_CLASS);
            restockRecipeItems = handler.getDeclaredMethod(RESTOCK_METHOD, Player.class, List.class);
            JERTLog.debug("SIA bridge: resolved {}", restockRecipeItems);
        } catch (ClassNotFoundException | NoSuchMethodException | RuntimeException | LinkageError exception) {

            failed = true;
            warn("SIA bridge unavailable (class or method not found); material fetching disabled", exception);
            return null;
        }
        return restockRecipeItems;
    }

    private static void warn(String message, Throwable cause) {
        failed = true;
        if (warnedMissing) {
            return;
        }
        warnedMissing = true;
        if (cause == null) {
            JERTLog.warn(message);
        } else {
            JERTLog.warn("{}: {}", message, cause.toString());
        }
    }
}
