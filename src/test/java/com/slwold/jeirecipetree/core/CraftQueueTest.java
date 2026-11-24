package com.slwold.jeirecipetree.core;

import java.util.ArrayList;
import java.util.List;

public final class CraftQueueTest {

    private CraftQueueTest() {
    }

    public static void main(String[] args) {
        buildAndActivation();
        parityWithOldAlgorithm();
        wholeLoadedBatchCollection();
        emptyAndClearedQueues();
        System.out.println("CraftQueue checks passed: activation, old-algorithm parity, "
            + "whole-batch collection, empty/cleared queues.");
    }

    private static void buildAndActivation() {
        CraftQueue<String> queue = CraftQueue.of(List.of("a", "skip", "b", "c"),
            value -> switch (value) {
                case "a" -> 2L;
                case "skip" -> 0L;
                case "b" -> 3L;
                default -> 1L;
            });
        check(queue.active(), "a freshly built queue must be active");
        check(queue.size() == 3, "zero-batch steps must be filtered, got " + queue.size());
        check(queue.hasCurrent() && queue.current().value().equals("a"), "first step must be 'a'");
        check(queue.current().remaining() == 2, "first step remaining");
        check(CraftQueue.<String>create().active() == false, "an empty queue must not be active");
    }

    private static void parityWithOldAlgorithm() {
        List<Long> plan = List.of(2L, 3L, 1L);
        OldState old = new OldState(plan);
        CraftQueue<String> queue = CraftQueue.of(
            List.of("step0", "step1", "step2"), value -> plan.get(Integer.parseInt(value.substring(4))));

        long[] transferred = {3, 1, 2, 4, 1, 1};
        int[] resultSlots = {7, -1, 9, 11, 13, -1};
        boolean[] wholeBatch = {false, false, true, false, false, true};

        for (int round = 0; round < transferred.length; round++) {
            if (!queue.hasCurrent()) {
                check(old.index >= old.remaining.size(), "oracle must also be exhausted");
                break;
            }
            old.start(transferred[round], resultSlots[round]);
            queue.startStep(transferred[round], resultSlots[round]);
            assertSame(old, queue, "after startStep round " + round);

            if (wholeBatch[round]) {
                old.collectWholeLoadedBatch();
                queue.collectWholeLoadedBatch();
                assertSame(old, queue, "after collectWholeLoadedBatch round " + round);
            }
            boolean oldContinue = old.complete();
            boolean newContinue = queue.completeStep();
            check(oldContinue == newContinue, "continueLoaded mismatch at round " + round);
            assertSame(old, queue, "after completeStep round " + round);
        }
        check(!queue.hasCurrent(), "all steps must be consumed by the sequence");
        check(queue.active(), "index at the end must NOT deactivate the session (tick still needs a final pass)");
    }

    private static void wholeLoadedBatchCollection() {
        CraftQueue<String> queue = CraftQueue.of(List.of("x"), value -> 4L);
        queue.startStep(4, 3);
        queue.collectWholeLoadedBatch();
        check(queue.chunk() == 4, "chunk must be raised to the whole loaded batch");
        check(!queue.completeStep(), "one collection must consume all loaded batches");
        check(queue.current() == null, "the step must be finished after 4 of 4 batches");
        check(queue.craftResultSlot() == -1, "result slot must be cleared when loaded batches run out");

        CraftQueue<String> partial = CraftQueue.of(List.of("y"), value -> 5L);
        partial.startStep(4, 3);
        partial.collectWholeLoadedBatch();
        partial.completeStep();
        check(partial.hasCurrent() && partial.current().remaining() == 1,
            "collecting 4 of 5 batches must leave 1 remaining");
    }

    private static void emptyAndClearedQueues() {
        CraftQueue<String> empty = CraftQueue.of(null, value -> 1L);
        check(empty.active(), "of(null) must still be active (transfer() relies on a final startNextCraft pass)");
        check(!empty.hasCurrent() && empty.current() == null, "current() must be null-safe, not throw");

        CraftQueue<String> queue = CraftQueue.of(List.of("a"), value -> 1L);
        queue.startStep(1, 4);
        queue.clear();
        check(!queue.active(), "clear() must deactivate");
        check(queue.size() == 0 && queue.index() == 0, "clear() must reset steps and index");
        check(queue.chunk() == 0 && queue.loadedBatches() == 0 && queue.craftResultSlot() == -1,
            "clear() must reset all counters");
    }

    private static void assertSame(OldState old, CraftQueue<String> queue, String label) {
        check(old.index == queue.index(), label + ": index " + old.index + " vs " + queue.index());
        check(old.chunk == queue.chunk(), label + ": chunk " + old.chunk + " vs " + queue.chunk());
        check(old.loaded == queue.loadedBatches(), label + ": loaded " + old.loaded + " vs " + queue.loadedBatches());
        check(old.resultSlot == queue.craftResultSlot(),
            label + ": resultSlot " + old.resultSlot + " vs " + queue.craftResultSlot());
        for (int i = 0; i < old.remaining.size(); i++) {
            long expected = old.remaining.get(i);
            if (i < queue.size()) {

                if (i == queue.index()) {
                    check(expected == queue.current().remaining(), label + ": remaining[" + i + "]");
                }
            }
        }
    }

    private static final class OldState {
        private final List<Long> remaining = new ArrayList<>();
        private int index;
        private long chunk;
        private long loaded;
        private int resultSlot = -1;

        private OldState(List<Long> plan) {
            remaining.addAll(plan);
        }

        private void start(long transferredBatches, int slot) {
            chunk = 1;
            loaded = Math.max(0, transferredBatches);
            resultSlot = slot;
        }

        private void collectWholeLoadedBatch() {
            chunk = Math.max(1, loaded);
        }

        private boolean complete() {
            long completedBatches = Math.max(1, chunk);
            remaining.set(index, Math.max(0, remaining.get(index) - completedBatches));
            loaded = Math.max(0, loaded - completedBatches);
            if (remaining.get(index) <= 0) {
                index++;
            }
            boolean continueLoaded = loaded > 0;
            chunk = continueLoaded ? 1 : 0;
            if (!continueLoaded) {
                resultSlot = -1;
            }
            return continueLoaded;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
