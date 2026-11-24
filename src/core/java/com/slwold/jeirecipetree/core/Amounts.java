package com.slwold.jeirecipetree.core;

public final class Amounts {

    private Amounts() {
    }

    public static long safeAdd(long first, long second) {
        if (second > 0 && first > Long.MAX_VALUE - second) {
            return Long.MAX_VALUE;
        }
        return first + second;
    }

    public static long safeMultiply(long first, long second) {
        if (first <= 0 || second <= 0) {
            return 0;
        }
        if (first > Long.MAX_VALUE / second) {
            return Long.MAX_VALUE;
        }
        return first * second;
    }

    public static long ceilDiv(long value, long divisor) {
        if (value <= 0) {
            return 0;
        }
        return 1 + (value - 1) / Math.max(1, divisor);
    }

    public static long gcd(long first, long second) {
        long a = Math.abs(first);
        long b = Math.abs(second);
        while (b != 0) {
            long next = a % b;
            a = b;
            b = next;
        }
        return Math.max(1, a);
    }
}
