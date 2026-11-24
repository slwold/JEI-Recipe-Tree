package com.slwold.jeirecipetree.client;

import java.util.List;

public final class StorageDriverRegistryTest {

    private StorageDriverRegistryTest() {
    }

    public static void main(String[] args) {
        Object menu = new Object();

        StorageDriverRegistry.register(new FakeDriver("fallback", 1000, true, false));
        StorageDriverRegistry.register(new FakeDriver("late-registered", 10, false, false));
        StorageDriverRegistry.register(new FakeDriver("first", 5, true, false));
        List<String> order = StorageDriverRegistry.activeDrivers();
        check(order.get(0).equals("5:first"), "lowest priority must come first, got " + order);
        check(order.get(order.size() - 1).equals("1000:fallback"),
            "fallback must stay last, got " + order);

        var hit = StorageDriverRegistry.readWithSource(menu);
        check(hit.snapshot() != null, "a claiming driver must produce a snapshot");
        check("first".equals(hit.driverId()), "wrong driver claimed: " + hit.driverId());
        check("first".equals(StorageDriverRegistry.lastUsedDriver()), "lastUsed must be recorded");
        check("first".equals(StorageDriverRegistry.driverFor(menu)), "driverFor must agree with read");

        StorageDriverRegistry.register(new FakeDriver("broken", 1, true, true));
        var recovered = StorageDriverRegistry.readWithSource(menu);
        check(recovered.snapshot() != null, "a single broken driver must not kill the whole registry");
        check("first".equals(recovered.driverId()),
            "next healthy driver must take over, got " + recovered.driverId());
        check(StorageDriverRegistry.failedDrivers().contains("broken"), "broken driver must be recorded as failed");
        check(StorageDriverRegistry.activeDrivers().stream().noneMatch(name -> name.endsWith(":broken")),
            "broken driver must be removed from the active list");

        Object unknownMenu = "unknown";
        var fallback = new FakeDriver("always", 2000, true, false);
        StorageDriverRegistry.register(fallback);
        check(StorageDriverRegistry.handles(unknownMenu), "fallback must keep handles() true");

        System.out.println("Storage driver registry checks passed: priority order, claiming, "
            + "failure isolation, fallback availability.");
    }

    private static final class FakeDriver implements StorageDriver {
        private final String id;
        private final int priority;
        private final boolean handles;
        private final boolean fails;

        private FakeDriver(String id, int priority, boolean handles, boolean fails) {
            this.id = id;
            this.priority = priority;
            this.handles = handles;
            this.fails = fails;
        }

        @Override public int priority() { return priority; }
        @Override public String id() { return id; }
        @Override public boolean handles(Object menu) { return handles; }

        @Override
        public StorageNetworkIntegration.StorageSnapshot read(Object menu) {
            if (fails) {
                throw new IllegalStateException("simulated driver failure: " + id);
            }
            return new StorageNetworkIntegration.StorageSnapshot(List.of(), List.of(), 0L);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
