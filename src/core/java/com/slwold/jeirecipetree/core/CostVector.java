package com.slwold.jeirecipetree.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CostVector {

    private final Map<ItemRef, Long> amounts = new LinkedHashMap<>();

    private CostVector() {
    }

    public static CostVector empty() {
        return new CostVector();
    }

    public static CostVector ofStock(Map<ItemRef, Long> stock) {
        CostVector vector = new CostVector();
        if (stock != null) {
            stock.forEach((ref, amount) -> {
                if (ref != null && amount != null && amount > 0) {
                    vector.add(ref, amount);
                }
            });
        }
        return vector;
    }

    public void require(ItemRef ref, long amount) {
        if (amount <= 0) {
            return;
        }
        add(ref, -amount);
    }

    public long amount(ItemRef ref) {
        return amounts.getOrDefault(ref, 0L);
    }

    public void add(ItemRef ref, long delta) {
        if (ref == null || delta == 0) {
            return;
        }
        set(ref, Amounts.safeAdd(amount(ref), delta));
    }

    boolean addTracked(ItemRef ref, long delta, Deque<ItemRef> pending) {
        if (ref == null || delta == 0) {
            return false;
        }
        long before = amount(ref);
        long after = Amounts.safeAdd(before, delta);
        set(ref, after);
        boolean newlyNegative = after < 0 && before >= 0;
        if (newlyNegative) {
            pending.add(ref);
        }
        return newlyNegative;
    }

    public List<ItemRef> deficits() {
        List<ItemRef> result = new ArrayList<>();
        amounts.forEach((ref, value) -> {
            if (value < 0) {
                result.add(ref);
            }
        });
        return result;
    }

    public Map<ItemRef, Long> view() {
        return Collections.unmodifiableMap(amounts);
    }

    private void set(ItemRef ref, long value) {
        if (value == 0) {
            amounts.remove(ref);
        } else {
            amounts.put(ref, value);
        }
    }
}
