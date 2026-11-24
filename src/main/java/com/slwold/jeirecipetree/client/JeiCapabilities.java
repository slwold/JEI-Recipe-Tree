package com.slwold.jeirecipetree.client;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class JeiCapabilities {

    public enum InputLine {

        INTERACTION,

        LEGACY,

        UNKNOWN
    }

    private static final List<String> INPUT_TARGET = List.of("mezz.jei.common.input.IInputTarget");
    private static final List<String> TRANSFER_SERVICE = List.of("mezz.jei.common.transfer.RecipeTransferService");
    private static final List<String> USER_INPUT_HANDLER = List.of(
        "mezz.jei.common.input.IInputTarget",
        "mezz.jei.common.input.IUserInputHandler",
        "mezz.jei.gui.input.IUserInputHandler");
    private static final List<String> MOUSE_OVER = List.of(
        "mezz.jei.common.input.IMouseOverable",
        "mezz.jei.gui.input.IMouseOverable");
    private static final List<String> COMBINED_HANDLER = List.of(
        "mezz.jei.common.input.handlers.InputGroup",
        "mezz.jei.common.input.handlers.CombinedInputHandler",
        "mezz.jei.gui.input.handlers.CombinedInputHandler");
    private static final List<String> CLICKABLE_INGREDIENT = List.of("mezz.jei.gui.input.ClickableIngredientInternal");
    private static final List<String> CLIENT_CONFIGS_GETTER = List.of("getClientConfigs", "getJeiClientConfigs");
    private static final String INTERNAL = "mezz.jei.common.Internal";

    private static final ConcurrentMap<String, Optional<Class<?>>> TYPES = new ConcurrentHashMap<>();
    private static volatile String summary;

    private JeiCapabilities() {
    }

    public static Optional<Class<?>> find(String... candidates) {
        if (candidates == null || candidates.length == 0) {
            return Optional.empty();
        }
        for (String name : candidates) {
            Optional<Class<?>> cached = TYPES.get(name);
            if (cached != null) {
                if (cached.isPresent()) {
                    return cached;
                }
                continue;
            }
            Optional<Class<?>> resolved = load(name);
            TYPES.put(name, resolved);
            if (resolved.isPresent()) {
                return resolved;
            }
        }
        return Optional.empty();
    }

    public static Class<?> require(String... candidates) {
        return find(candidates).orElseThrow(() -> new IllegalStateException(
            "None of the supported JEI classes are available: " + String.join(", ", candidates)));
    }

    public static Optional<Class<?>> find(List<String> candidates) {
        return find(candidates.toArray(new String[0]));
    }

    public static Class<?> require(List<String> candidates) {
        return require(candidates.toArray(new String[0]));
    }

    public static boolean present(String className) {
        return find(className).isPresent();
    }

    private static Optional<Class<?>> load(String name) {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        Optional<Class<?>> fromContext = loadWith(name, contextLoader);
        if (fromContext.isPresent() || contextLoader == null) {
            return fromContext;
        }
        return loadWith(name, JeiCapabilities.class.getClassLoader());
    }

    private static Optional<Class<?>> loadWith(String name, ClassLoader loader) {
        if (loader == null) {
            return Optional.empty();
        }
        try {

            return Optional.of(Class.forName(name, false, loader));
        } catch (ClassNotFoundException | LinkageError ignored) {
            return Optional.empty();
        }
    }

    public static InputLine inputLine() {
        if (find(INPUT_TARGET).isPresent()) {
            return InputLine.INTERACTION;
        }
        if (find(USER_INPUT_HANDLER).isPresent()) {
            return InputLine.LEGACY;
        }
        return InputLine.UNKNOWN;
    }

    public static boolean recipeTransferServicePresent() {
        return find(TRANSFER_SERVICE).isPresent();
    }

    public static Class<?> userInputHandlerType() {
        return require(USER_INPUT_HANDLER);
    }

    public static Class<?> inputTargetType() {
        return require(INPUT_TARGET);
    }

    public static Class<?> mouseOverType() {
        return require(MOUSE_OVER);
    }

    public static Class<?> combinedHandlerType() {
        return require(COMBINED_HANDLER);
    }

    public static Class<?> clickableIngredientType() {
        return require(CLICKABLE_INGREDIENT);
    }

    public static Object clientConfigs() {
        Class<?> internal = require(INTERNAL);
        for (String getter : CLIENT_CONFIGS_GETTER) {
            try {
                Method method = internal.getMethod(getter);
                return method.invoke(null);
            } catch (NoSuchMethodException ignored) {

            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Unable to read JEI client configs", unwrap(error));
            } catch (LinkageError error) {
                throw new IllegalStateException("JEI client configs are not linkable", error);
            }
        }
        throw new IllegalStateException("No supported client config getter on " + internal.getName());
    }

    private static Throwable unwrap(ReflectiveOperationException error) {
        if (error instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            return invocation.getCause();
        }
        return error;
    }

    public static String summary() {
        String cached = summary;
        if (cached != null) {
            return cached;
        }
        String built = String.format(Locale.ROOT,
            "JERT JEI capabilities | input=%s | transferService=%s | userInput=%s | mouseOver=%s | configs=%s",
            inputLine(),
            recipeTransferServicePresent() ? "present" : "absent",
            simpleName(USER_INPUT_HANDLER),
            simpleName(MOUSE_OVER),
            clientConfigsGetter());
        summary = built;
        return built;
    }

    public static void logSummary() {
        try {
            JERTLog.summary(summary());
        } catch (RuntimeException | LinkageError error) {
            JERTLog.warn("JEI capability probe failed: {}", error.toString());
        }
    }

    private static String simpleName(List<String> candidates) {
        return find(candidates).map(Class::getSimpleName).orElse("none");
    }

    private static String clientConfigsGetter() {
        Optional<Class<?>> internal = find(INTERNAL);
        if (internal.isEmpty()) {
            return "none";
        }
        for (String getter : CLIENT_CONFIGS_GETTER) {
            try {
                internal.get().getMethod(getter);
                return getter;
            } catch (NoSuchMethodException ignored) {

            }
        }
        return "none";
    }
}
