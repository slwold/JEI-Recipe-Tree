package com.slwold.jeirecipetree.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Perf {

    public static final String ANALYZE = "analyze";
    public static final String RENDER = "render";
    public static final String STORAGE = "storage";
    public static final String FETCH = "fetch";

    public static final String REFRESH = "sidebar-refresh";

    public static final String RESTORE = "session-restore";

    public static final String BUILD = "tree-build";

    public static final long WARN_8MS = 8_000_000L;

    private static final long WARN_INTERVAL_NANOS = 10_000_000_000L;

    private static final Map<String, Long> lastWarnNanos = new ConcurrentHashMap<>();

    private Perf() {
    }

    public static boolean enabled() {
        try {
            return SafeConfig.developerMode();
        } catch (RuntimeException error) {
            return false;
        }
    }

    public static long start() {
        return enabled() ? System.nanoTime() : DISABLED;
    }

    public static void end(String metric, long startNanos, long warnNanos) {
        if (startNanos == DISABLED) {
            return;
        }
        long elapsed = System.nanoTime() - startNanos;
        if (warnNanos <= 0 || elapsed < warnNanos) {
            return;
        }
        long now = System.nanoTime();
        Long previous = lastWarnNanos.get(metric);
        if (previous != null && now - previous >= 0L && now - previous < WARN_INTERVAL_NANOS) {
            return;
        }
        lastWarnNanos.put(metric, now);
        JERTLog.warn("perf: {} took {} ms (threshold {} ms)", metric,
            formatMillis(elapsed), formatMillis(warnNanos));
    }

    public static long lastWarnNanos(String metric) {
        return lastWarnNanos.getOrDefault(metric, Long.MIN_VALUE);
    }

    public static final long DISABLED = Long.MIN_VALUE;

    public static final long SLOW_100MS = 100_000_000L;

    public static long startAlways() {
        return System.nanoTime();
    }

    public static void endAdaptive(String metric, long startNanos) {
        endSlow(metric, startNanos, enabled() ? WARN_8MS : SLOW_100MS);
    }

    public static void endSlow(String metric, long startNanos, long warnNanos) {
        if (startNanos == DISABLED) {
            return;
        }
        long elapsed = System.nanoTime() - startNanos;
        if (warnNanos <= 0 || elapsed < warnNanos) {
            return;
        }
        long now = System.nanoTime();
        Long previous = lastWarnNanos.get(metric);
        if (previous != null && now - previous >= 0L && now - previous < WARN_INTERVAL_NANOS) {
            return;
        }
        lastWarnNanos.put(metric, now);
        JERTLog.warn("perf: {} took {} ms (slow path, threshold {} ms)", metric,
            formatMillis(elapsed), formatMillis(warnNanos));
    }

    private static String formatMillis(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1_000_000.0);
    }
}
