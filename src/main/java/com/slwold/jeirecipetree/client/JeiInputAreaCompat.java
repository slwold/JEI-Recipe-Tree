package com.slwold.jeirecipetree.client;

import mezz.jei.common.util.ImmutableRect2i;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

public final class JeiInputAreaCompat {
    private static final ClassValue<Method> AREA_ACCESS = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> type) {
            try {
                Method method = type.getMethod("area");
                method.setAccessible(true);
                return method;
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Unsupported JEI input area: " + type.getName(), exception);
            }
        }
    };

    private JeiInputAreaCompat() {}

    public static Optional<?> selectTarget(List<?> areas, Optional<?> nativeTarget, double x, double y) {

        for (Object area : areas) {
            Object source = source(area);
            if (source instanceof JeiExtraInputArea extra && extra.jert$isModalInput()
                && extra.jert$containsExtraInput(x, y)) {
                return Optional.of(area);
            }
        }

        for (Object area : areas) {
            if (nativeTarget.isPresent() && nativeTarget.get() == area) return nativeTarget;
            Object source = source(area);
            if (source instanceof JeiExtraInputArea extra && extra.jert$containsExtraInput(x, y)) {
                return Optional.of(area);
            }
        }
        return nativeTarget;
    }

    private static Object source(Object area) {
        try {
            return AREA_ACCESS.get(area.getClass()).invoke(area);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to read JEI input area", exception);
        }
    }

    public static boolean insideBand(double x, double y, Object previous, Object next) {
        if (!(previous instanceof ImmutableRect2i left) || !(next instanceof ImmutableRect2i right)
            || left.isEmpty() || right.isEmpty()) return false;
        return x >= left.getX() - 2 && x < right.getX() + right.getWidth() + 2
            && y >= left.getY() - 2 && y < left.getY() + left.getHeight() + 2;
    }

    public static boolean scrollRecipeNavigation(Object gui, double x, double y, double dy) {
        if (dy == 0) return false;
        Object logic = JeiReflectionCompat.readField(gui, "logic");

        if (buttonBand(gui, x, y, "previousPage", "nextPage")) {
            JeiReflectionCompat.invokeNoArgsUnchecked(logic, dy < 0 ? "nextPage" : "previousPage");
            return true;
        }

        if (scrollRecipeTabs(JeiReflectionCompat.readField(gui, "recipeGuiTabs"), x, y, dy)) return true;
        if (buttonBand(gui, x, y, "previousRecipeCategory", "nextRecipeCategory")) {
            JeiReflectionCompat.invokeNoArgsUnchecked(
                logic, dy < 0 ? "nextRecipeCategory" : "previousRecipeCategory"
            );
            return true;
        }
        return false;
    }

    public static boolean scrollRecipeTabs(Object tabs, double x, double y, double dy) {
        if (tabs == null || dy == 0) return false;
        Object navigation = JeiReflectionCompat.readField(tabs, "pageNavigation");
        if (insideBand(x, y,
            JeiReflectionCompat.tryInvoke(navigation, "getBackButtonArea"),
            JeiReflectionCompat.tryInvoke(navigation, "getNextButtonArea"))) {
            JeiReflectionCompat.invokeNoArgsUnchecked(tabs, dy < 0 ? "nextPage" : "previousPage");
            return true;
        }
        Object area = JeiReflectionCompat.readField(tabs, "area");
        if (area instanceof ImmutableRect2i bounds && bounds.contains(x, y)) {
            Object logic = JeiReflectionCompat.readField(tabs, "recipeGuiLogic");
            JeiReflectionCompat.invokeNoArgsUnchecked(logic, dy < 0 ? "nextRecipeCategory" : "previousRecipeCategory");
            return true;
        }
        return false;
    }

    private static boolean buttonBand(Object gui, double x, double y, String previous, String next) {
        return insideBand(x, y,
            JeiReflectionCompat.tryInvoke(JeiReflectionCompat.readField(gui, previous), "getArea"),
            JeiReflectionCompat.tryInvoke(JeiReflectionCompat.readField(gui, next), "getArea"));
    }
}
