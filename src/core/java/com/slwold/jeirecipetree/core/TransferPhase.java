package com.slwold.jeirecipetree.core;

public enum TransferPhase {

    IDLE,

    AE2_CARRY,

    VANILLA_PICKUP,

    GENERIC_RESULT_PROBE,

    ADVANCE,

    RESULT_WAIT;

    public static TransferPhase of(
        boolean active,
        boolean ae2Carry,
        boolean vanillaPickup,
        boolean genericProbe,
        int resultSlot
    ) {
        if (!active) {
            return IDLE;
        }
        if (ae2Carry) {
            return AE2_CARRY;
        }
        if (vanillaPickup) {
            return VANILLA_PICKUP;
        }
        if (genericProbe) {
            return GENERIC_RESULT_PROBE;
        }
        if (resultSlot < 0) {
            return ADVANCE;
        }
        return RESULT_WAIT;
    }
}
