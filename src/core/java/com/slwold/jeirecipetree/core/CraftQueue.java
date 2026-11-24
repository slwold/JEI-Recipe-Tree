package com.slwold.jeirecipetree.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

public final class CraftQueue<T> {

    public static final class Step<T> {
        private final T value;
        private long remaining;

        private Step(T value, long remaining) {
            this.value = value;
            this.remaining = remaining;
        }

        public T value() {
            return value;
        }

        public long remaining() {
            return remaining;
        }
    }

    private final List<Step<T>> steps = new ArrayList<>();
    private int index;
    private long chunk;
    private long loadedBatches;
    private int craftResultSlot = -1;

    private boolean active;

    private CraftQueue() {
    }

    public static <T> CraftQueue<T> create() {
        return new CraftQueue<>();
    }

    public static <T> CraftQueue<T> of(List<T> values, ToLongFunction<T> batches) {
        CraftQueue<T> queue = new CraftQueue<>();

        queue.active = true;
        if (values == null) {
            return queue;
        }
        for (T value : values) {
            long amount = batches.applyAsLong(value);
            if (amount > 0) {
                queue.steps.add(new Step<>(value, amount));
            }
        }
        return queue;
    }

    public boolean active() {
        return active;
    }

    public boolean hasCurrent() {
        return index < steps.size();
    }

    public Step<T> current() {
        return hasCurrent() ? steps.get(index) : null;
    }

    public int index() {
        return index;
    }

    public int size() {
        return steps.size();
    }

    public long chunk() {
        return chunk;
    }

    public long loadedBatches() {
        return loadedBatches;
    }

    public int craftResultSlot() {
        return craftResultSlot;
    }

    public void startStep(long transferredBatches, int resultSlot) {
        chunk = 1;
        loadedBatches = Math.max(0L, transferredBatches);
        craftResultSlot = resultSlot;
    }

    public void collectWholeLoadedBatch() {
        chunk = Math.max(1L, loadedBatches);
    }

    public boolean completeStep() {
        if (!hasCurrent()) {
            return false;
        }
        long completed = Math.max(1L, chunk);
        Step<T> step = steps.get(index);
        step.remaining = Math.max(0L, step.remaining - completed);
        loadedBatches = Math.max(0L, loadedBatches - completed);
        if (step.remaining <= 0) {
            index++;
        }
        boolean continueLoaded = loadedBatches > 0;
        chunk = continueLoaded ? 1 : 0;
        if (!continueLoaded) {
            craftResultSlot = -1;
        }
        return continueLoaded;
    }

    public void clear() {
        steps.clear();
        index = 0;
        chunk = 0;
        loadedBatches = 0;
        craftResultSlot = -1;
        active = false;
    }
}
