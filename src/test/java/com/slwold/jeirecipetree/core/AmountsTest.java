package com.slwold.jeirecipetree.core;

public final class AmountsTest {

    private AmountsTest() {
    }

    public static void main(String[] args) {
        additionSaturates();
        multiplicationSaturates();
        ceilDivRoundsUp();
        gcdNeverReturnsZero();
        neverNegativeForPositiveInputs();
        System.out.println("Amounts regression checks passed: saturation, ceilDiv, gcd, sign invariant.");
    }

    private static void additionSaturates() {
        check(Amounts.safeAdd(2, 3) == 5, "plain addition");
        check(Amounts.safeAdd(0, 0) == 0, "zero addition");
        check(Amounts.safeAdd(Long.MAX_VALUE, 1) == Long.MAX_VALUE, "overflow must saturate, not wrap");
        check(Amounts.safeAdd(Long.MAX_VALUE - 1, 1) == Long.MAX_VALUE, "reaching the ceiling is fine");

        check(Amounts.safeAdd(5, -3) == 2, "negative addend is added as-is");
    }

    private static void multiplicationSaturates() {
        check(Amounts.safeMultiply(6, 7) == 42, "plain multiplication");
        check(Amounts.safeMultiply(0, 7) == 0, "zero factor yields zero");
        check(Amounts.safeMultiply(7, 0) == 0, "zero factor yields zero (commuted)");
        check(Amounts.safeMultiply(-1, 7) == 0, "negative factor yields zero");
        check(Amounts.safeMultiply(Long.MAX_VALUE, 2) == Long.MAX_VALUE, "overflow must saturate");
        check(Amounts.safeMultiply(Long.MAX_VALUE / 2, 3) == Long.MAX_VALUE, "near-ceiling saturates");
    }

    private static void ceilDivRoundsUp() {
        check(Amounts.ceilDiv(10, 3) == 4, "10 / 3 rounds up to 4");
        check(Amounts.ceilDiv(9, 3) == 3, "exact division stays");
        check(Amounts.ceilDiv(1, 64) == 1, "any positive remainder needs one more");
        check(Amounts.ceilDiv(0, 3) == 0, "zero yields zero");
        check(Amounts.ceilDiv(5, 0) == 5, "zero divisor is treated as one (defensive)");
    }

    private static void gcdNeverReturnsZero() {
        check(Amounts.gcd(12, 18) == 6, "gcd(12,18)");
        check(Amounts.gcd(17, 5) == 1, "coprime yields 1");
        check(Amounts.gcd(0, 0) == 1, "all-zero yields 1 so callers never divide by zero");
        check(Amounts.gcd(-12, 18) == 6, "signs are ignored");
    }

    private static void neverNegativeForPositiveInputs() {
        long[] samples = {1, 2, 64, 1_000, Long.MAX_VALUE / 2, Long.MAX_VALUE};
        for (long a : samples) {
            for (long b : samples) {
                check(Amounts.safeAdd(a, b) >= 0, "safeAdd stayed non-negative for " + a + "+" + b);
                check(Amounts.safeMultiply(a, b) >= 0, "safeMultiply stayed non-negative for " + a + "*" + b);
                check(Amounts.ceilDiv(a, b) >= 0, "ceilDiv stayed non-negative for " + a + "/" + b);
                check(Amounts.gcd(a, b) >= 1, "gcd stayed >= 1 for " + a + "," + b);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
