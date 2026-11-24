package com.slwold.jeirecipetree.core;

public final class TransferPhaseTest {

    private TransferPhaseTest() {
    }

    public static void main(String[] args) {
        int checked = 0;
        for (int mask = 0; mask < 32; mask++) {
            boolean active = (mask & 1) != 0;
            boolean ae2Carry = (mask & 2) != 0;
            boolean vanillaPickup = (mask & 4) != 0;
            boolean genericProbe = (mask & 8) != 0;
            int resultSlot = (mask & 16) != 0 ? 7 : -1;

            TransferPhase expected = oracle(active, ae2Carry, vanillaPickup, genericProbe, resultSlot);
            TransferPhase actual = TransferPhase.of(active, ae2Carry, vanillaPickup, genericProbe, resultSlot);
            check(expected == actual,
                "phase mismatch for mask " + mask + ": " + expected + " vs " + actual);
            checked++;
        }
        check(TransferPhase.of(false, true, true, true, 7) == TransferPhase.IDLE,
            "no active session must always win over all sub-state flags");
        check(TransferPhase.of(true, true, true, true, 7) == TransferPhase.AE2_CARRY,
            "AE2 carry must take priority over vanilla pickup");
        check(TransferPhase.of(true, false, true, true, 7) == TransferPhase.VANILLA_PICKUP,
            "vanilla pickup must take priority over the generic probe");
        System.out.println("TransferPhase checks passed: " + checked
            + " combinations matched the tick() dispatch oracle; priority order verified.");
    }

    private static TransferPhase oracle(
        boolean active,
        boolean ae2Carry,
        boolean vanillaPickup,
        boolean genericProbe,
        int resultSlot
    ) {
        if (!active) {
            return TransferPhase.IDLE;
        }
        if (ae2Carry) {
            return TransferPhase.AE2_CARRY;
        }
        if (vanillaPickup) {
            return TransferPhase.VANILLA_PICKUP;
        }
        if (genericProbe) {
            return TransferPhase.GENERIC_RESULT_PROBE;
        }
        if (resultSlot < 0) {
            return TransferPhase.ADVANCE;
        }
        return TransferPhase.RESULT_WAIT;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
