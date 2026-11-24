package com.slwold.jeirecipetree.client;

import java.util.Optional;

public final class JeiCapabilitiesTest {

    private JeiCapabilitiesTest() {
    }

    public static void main(String[] args) {
        resolvesKnownTypes();
        negativeResultsAreCachedNotThrown();
        missingTypesFailWithCandidateNames();
        inputLineMatchesBaseline();
        summaryIsStableAndComplete();
        System.out.println("JEI capability checks passed: type resolution, negative caching, "
            + "failure message, input line, cached summary.");
    }

    private static void resolvesKnownTypes() {
        Class<?> handler = JeiCapabilities.userInputHandlerType();
        check(handler != null, "user input handler type must resolve");
        check(handler.getName().endsWith("IUserInputHandler"),
            "unexpected user input handler: " + handler.getName());
        check(JeiCapabilities.mouseOverType().getName().endsWith("IMouseOverable"),
            "unexpected mouse-over type: " + JeiCapabilities.mouseOverType().getName());
        check(JeiCapabilities.present("mezz.jei.common.Internal"),
            "JEI Internal must be visible: client configs are read through it");
    }

    private static void negativeResultsAreCachedNotThrown() {
        Optional<Class<?>> first = JeiCapabilities.find("jert.probe.DoesNotExist");
        Optional<Class<?>> second = JeiCapabilities.find("jert.probe.DoesNotExist");
        check(first.isEmpty() && second.isEmpty(), "missing type must resolve to empty, not throw");
        check(JeiCapabilities.find("jert.probe.DoesNotExist").isEmpty(), "negative cache must stay empty");
    }

    private static void missingTypesFailWithCandidateNames() {
        try {
            JeiCapabilities.require("jert.probe.Alpha", "jert.probe.Beta");
        } catch (IllegalStateException expected) {
            String message = String.valueOf(expected.getMessage());
            check(message.contains("jert.probe.Alpha") && message.contains("jert.probe.Beta"),
                "failure message must list every candidate, got: " + message);
            return;
        }
        throw new AssertionError("require() must throw when no candidate exists");
    }

    private static void inputLineMatchesBaseline() {
        check(!JeiCapabilities.present("mezz.jei.common.input.IInputTarget"),
            "baseline JEI must not expose the interaction input line");
        check(JeiCapabilities.inputLine() == JeiCapabilities.InputLine.LEGACY,
            "baseline JEI must be detected as LEGACY, got " + JeiCapabilities.inputLine());
        check(JeiCapabilities.recipeTransferServicePresent(),
            "baseline JEI ships RecipeTransferService; if this changed, re-check the port plan");
    }

    private static void summaryIsStableAndComplete() {
        String first = JeiCapabilities.summary();
        String second = JeiCapabilities.summary();
        check(first.equals(second), "summary must be stable");
        check(first == second, "summary must be cached, not rebuilt per call");
        check(first.contains("input=LEGACY"), "summary must report the input line: " + first);
        check(first.contains("transferService=present"), "summary must report RecipeTransferService: " + first);
        check(!first.contains("configs=none"), "summary must resolve a client config getter: " + first);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
